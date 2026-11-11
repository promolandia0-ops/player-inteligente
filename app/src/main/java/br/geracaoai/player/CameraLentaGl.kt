package br.geracaoai.player

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.TextureView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Câmera lenta suave (v1.3.23) — interpolação de quadros em tempo real na GPU (OpenGL ES 3.0), entre o decodificador e a tela.
 *
 *  ExoPlayer → Surface(SurfaceTexture OES) → [este motor] → janela EGL da MESMA TextureView (o zoom/pan continua sendo a
 *  transformação da view; getBitmap do zoom automático continua funcionando).
 *
 * Por par de quadros (A→B), uma vez: pirâmide de luma (4 níveis) → fluxo óptico por casamento de blocos (SAD 5×5, grosso→fino,
 * sub-pixel) nos DOIS sentidos → mediana 3×3. A cada vsync (Choreographer): `interp.frag` gera o quadro em t∈[0,1] com
 * warp por ponto fixo, confiança por consistência frente-trás (oclusão), mistura só quando A e B concordam e REPETE o quadro
 * (nunca mistura cega) quando o fluxo não é confiável; corte de cena/flash/movimento rápido demais → repete o quadro inteiro.
 * Custo: 1 quadro de atraso (≈ 33 ms de mídia), imperceptível; o áudio fica em sincronia de mídia.
 *
 * v1.3.24 — menos "pipocos": o fluxo ganha mediana + LIMITE de módulo, confiança por vetor (frente-trás + vizinhança + custo), máscara
 * espalhada (feather) e estável no tempo (mistura com o par anterior), mistura contínua em vez de "vence o maior" e plano B global
 * (crossfade/repetição) quando blocos ruins demais. Também hospeda o "Melhorar imagem" (nitidez adaptativa + desbloqueio leve +
 * saturação/contraste, 1 passada final na GPU) — funciona com ou sem interpolação (a ≥ 1× o motor só repassa o quadro, sem fluxo).
 *
 * Qualquer falha (EGL/GLSL/extensão/desempenho) chama [aoFalhar] e o chamador volta ao caminho normal. A melhoria que passa do
 * orçamento de tempo (ou falha ao compilar) se desliga SOZINHA ([aoMelhoriaFalhar]) e o vídeo continua.
 */
class CameraLentaGl(
    private val ctx: Context,
    private val tv: TextureView,
    private val player: ExoPlayer,
    larguraVideo: Int, alturaVideo: Int,
    private val velocidade: () -> Float,
    private val lentaLigada: () -> Boolean,
    private val melhorar: () -> Boolean,
    private val aoMelhoriaFalhar: (String) -> Unit,
    private val aoFalhar: (String) -> Unit,
) {
    companion object {
        private const val TAG = "CameraLenta"
        private const val EGL_OPENGL_ES3_BIT = 0x40
        private const val NIVEIS = 4
        private val RAIO = intArrayOf(1, 2, 2, 3)
        private const val HEADER = "#version 300 es\nprecision highp float;\nprecision highp int;\nprecision highp sampler2D;\n"
        private const val COPIA_OES = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
precision highp int;
uniform samplerExternalOES uOes;
uniform mat4 uTransform;
in vec2 vUv;
layout(location = 0) out vec4 oColor;
void main() { oColor = vec4(texture(uOes, (uTransform * vec4(vUv, 0.0, 1.0)).xy).rgb, 1.0); }
"""
    }

    private val tamanho = CameraLentaLogica.tamanhoQuadro(larguraVideo, alturaVideo)
    private val fw = tamanho.first; private val fh = tamanho.second
    private val th = HandlerThread("camera-lenta", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val h = Handler(th.looper)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var parado = false
    @Volatile private var falhou = false

    // ---- EGL ----
    private var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglCtx: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglCfg: EGLConfig? = null
    private var pbuf: EGLSurface = EGL14.EGL_NO_SURFACE
    private var janela: EGLSurface = EGL14.EGL_NO_SURFACE

    // ---- GL ----
    private var oesTex = 0
    private lateinit var st: SurfaceTexture
    private lateinit var entrada: Surface
    private val progs = HashMap<String, Int>()
    private val uni = HashMap<String, Int>()
    private var fbo = 0
    private val quadros = IntArray(2)                          // RGBA8 cheio
    private val luma = Array(2) { IntArray(NIVEIS) }           // R8, por quadro
    private val fluxoAB = IntArray(NIVEIS); private val fluxoBA = IntArray(NIVEIS)   // RGBA16F por nível
    private var fabS = 0; private var fbaS = 0                 // mediana + limite, nível 0
    private var fabC = 0; private var fbaC = 0                 // + confiança (conf.frag)
    private val fabT = IntArray(2); private val fbaT = IntArray(2)   // final: confiança espalhada + estável no tempo (ping-pong)
    private var idxT = 0; private var histT = false
    private var compo = 0                                      // quadro interpolado (RGBA8, tamanho cheio) quando há melhoria
    private var texStat = 0
    private var tamanhos = Array(NIVEIS) { 0 to 0 }
    private var nivelDesempenho = 0
    private val xform = FloatArray(16)

    // ---- estado de apresentação ----
    private val ritmo = CameraLentaLogica.Ritmo()
    private val global = CameraLentaLogica.Global()
    private val guardaMel = CameraLentaLogica.GuardaMelhoria()
    @Volatile private var melhoriaOk = true; @Volatile private var melhoriaOff = false
    private var lumaValida = false; private var contDesenho = 0; private var ultimoDesenhoNs = 0L; private var continuoAnterior = false
    private val guarda = CameraLentaLogica.Guarda()
    private var cur = 0; private var vistos = 0
    private var temPar = false           // há (prev, cur) com fluxo calculado/pendente
    private var corte = false
    private var statPendente = false; private var statEmitidoNs = 0L
    private var sujo = true
    private var rodandoCoreografo = false
    private val filaPts = ConcurrentLinkedQueue<LongArray>()   // [releaseNs, ptsUs]
    private val statBuf = ByteBuffer.allocateDirect(16 * 18 * 4)

    private val metaListener = VideoFrameMetadataListener { pts, releaseNs, _, _ ->
        filaPts.add(longArrayOf(releaseNs, pts)); while (filaPts.size > 24) filaPts.poll()
    }
    private val frameCb = Choreographer.FrameCallback { t -> rodandoCoreografo = false; desenhar(t) }

    /** Main thread. Prepara o GL na thread própria; quando pronto, troca a saída do ExoPlayer para o motor. */
    fun iniciar() {
        h.post {
            try { initGl(); if (!melhoriaOk) desligarMelhoria("shader não compilou"); main.post { if (!parado) trocarSaida() } }
            catch (t: Throwable) { falhar("GL: ${t.javaClass.simpleName}: ${t.message}", t) }
        }
    }

    private fun trocarSaida() {
        player.setVideoFrameMetadataListener(metaListener)
        player.setVideoSurface(entrada)
        // Pausado: força 1 quadro na nova saída. Plus: se nenhum quadro vier, cria a janela depois de um tempo (o ExoPlayer já soltou a view).
        if (!player.isPlaying) player.seekTo(player.currentPosition)
        // O ExoPlayer solta a TextureView de forma assíncrona: espera um pouco e tenta conectar a janela EGL (com novas tentativas).
        h.postDelayed({ criarJanela(1) }, 350)
    }

    /**
     * Main thread. Desfaz tudo e devolve a TextureView ao ExoPlayer (caminho normal). Ordem importa: 1) solta a janela EGL da
     * view (GL thread, síncrono); 2) ExoPlayer volta a ligar na view; 3) só depois (com folga) libera SurfaceTexture/contexto.
     */
    fun parar() {
        if (parado) return
        parado = true
        val l = CountDownLatch(1)
        h.removeCallbacksAndMessages(null)
        h.post {
            try {
                runCatching { Choreographer.getInstance().removeFrameCallback(frameCb) }
                runCatching { EGL14.eglMakeCurrent(dpy, pbuf, pbuf, eglCtx) }
                if (janela != EGL14.EGL_NO_SURFACE) runCatching { EGL14.eglDestroySurface(dpy, janela) }
                janela = EGL14.EGL_NO_SURFACE
            } finally { l.countDown() }
        }
        runCatching { l.await(1200, TimeUnit.MILLISECONDS) }
        runCatching { player.clearVideoFrameMetadataListener(metaListener) }
        runCatching {
            if (tv.isAvailable) player.setVideoTextureView(tv)
            if (!player.isPlaying) player.seekTo(player.currentPosition)
        }
        h.postDelayed({ try { liberarGl() } finally { th.quitSafely(); runCatching { entrada.release() } } }, 600)
    }

    private fun falhar(msg: String, t: Throwable? = null) {
        if (falhou) return
        falhou = true
        Log.w(TAG, msg, t)
        main.post { if (!parado) aoFalhar(msg) }
    }

    // =============================================================== GL

    private fun initGl() {
        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2); check(EGL14.eglInitialize(dpy, v, 0, v, 1)) { "eglInitialize" }
        val cfgs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
        val attr = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
        check(EGL14.eglChooseConfig(dpy, attr, 0, cfgs, 0, 1, n, 0) && n[0] > 0) { "eglChooseConfig" }
        eglCfg = cfgs[0]
        eglCtx = EGL14.eglCreateContext(dpy, eglCfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        check(eglCtx != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
        pbuf = EGL14.eglCreatePbufferSurface(dpy, eglCfg, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(dpy, pbuf, pbuf, eglCtx)) { "eglMakeCurrent(pbuffer)" }

        val ext = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
        check(ext.contains("GL_EXT_color_buffer_float") || ext.contains("GL_EXT_color_buffer_half_float")) { "sem render em float16" }
        check(ext.contains("OES_EGL_image_external_essl3")) { "sem OES_EGL_image_external_essl3" }

        montarProgramas()
        val t = IntArray(1); GLES30.glGenTextures(1, t, 0); oesTex = t[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        st = SurfaceTexture(oesTex).also { it.setDefaultBufferSize(fw, fh) }
        entrada = Surface(st)
        st.setOnFrameAvailableListener({ if (!parado) novoQuadro() }, h)

        val f = IntArray(1); GLES30.glGenFramebuffers(1, f, 0); fbo = f[0]
        alocarTexturas()
        GLES30.glDisable(GLES30.GL_BLEND); GLES30.glDisable(GLES30.GL_DEPTH_TEST)
    }

    private fun montarProgramas() {
        fun ler(n: String) = ctx.assets.open("camera_lenta/$n").bufferedReader().use { it.readText() }
        val vs = HEADER + ler("quad.vert")
        fun prog(nome: String, fs: String) { progs[nome] = linkar(vs, fs) }
        prog("oes", COPIA_OES)   // #extension precisa vir antes de qualquer outro token: shader completo próprio
        prog("luma", HEADER + ler("luma.frag")); prog("down", HEADER + ler("down.frag"))
        prog("smooth", HEADER + ler("smooth.frag")); prog("interp", HEADER + ler("interp.frag")); prog("stat", HEADER + ler("stat.frag"))
        prog("conf", HEADER + ler("conf.frag")); prog("temporal", HEADER + ler("temporal.frag")); prog("stat2", HEADER + ler("stat2.frag"))
        // A melhoria é opcional: se o shader dela não compilar, só ela fica de fora (o resto do motor segue).
        try { prog("enhance", HEADER + ler("enhance.frag")) } catch (t: Throwable) { melhoriaOk = false; Log.w(TAG, "enhance: ${t.message}") }
        val fl = ler("flow.frag")
        for (r in 1..3) prog("flow$r", HEADER + "#define RADIUS $r\n" + fl)
    }

    private fun compilar(tipo: Int, src: String): Int {
        val s = GLES30.glCreateShader(tipo); GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
        val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "shader: " + GLES30.glGetShaderInfoLog(s).take(300) }
        return s
    }
    private fun linkar(vs: String, fs: String): Int {
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, compilar(GLES30.GL_VERTEX_SHADER, vs)); GLES30.glAttachShader(p, compilar(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "link: " + GLES30.glGetProgramInfoLog(p).take(300) }
        return p
    }
    private fun u(p: String, n: String): Int = uni.getOrPut("$p/$n") { GLES30.glGetUniformLocation(progs.getValue(p), n) }

    private fun novaTex(w: Int, hh: Int, interno: Int, formato: Int, tipo: Int, filtro: Int = GLES30.GL_LINEAR): Int {
        val t = IntArray(1); GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, interno, w, hh, 0, formato, tipo, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filtro); GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filtro)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE); GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return t[0]
    }
    private fun apagar(v: IntArray) { if (v.any { it != 0 }) GLES30.glDeleteTextures(v.size, v, 0) }

    /** (Re)cria as texturas que dependem do nível de desempenho (pirâmide/fluxo) e as de tamanho cheio (uma vez). */
    private fun alocarTexturas() {
        if (quadros[0] == 0) {
            for (i in 0..1) quadros[i] = novaTex(fw, fh, GLES30.GL_RGBA8, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE)
            texStat = novaTex(16, 18, GLES30.GL_RGBA8, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, GLES30.GL_NEAREST)
            compo = novaTex(fw, fh, GLES30.GL_RGBA8, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE)
        }
        for (i in 0..1) { apagar(luma[i]); }
        apagar(fluxoAB); apagar(fluxoBA); apagar(intArrayOf(fabS, fbaS, fabC, fbaC)); apagar(fabT); apagar(fbaT)
        val w0 = CameraLentaLogica.larguraFluxo(nivelDesempenho); val h0 = (w0.toLong() * fh / fw).toInt().coerceAtLeast(8)
        tamanhos = Array(NIVEIS) { maxOf(w0 shr it, 8) to maxOf(h0 shr it, 8) }
        for (i in 0..1) for (l in 0 until NIVEIS) luma[i][l] = novaTex(tamanhos[l].first, tamanhos[l].second, GLES30.GL_R8, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE)
        for (l in 0 until NIVEIS) {
            fluxoAB[l] = novaTex(tamanhos[l].first, tamanhos[l].second, GLES30.GL_RGBA16F, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT)
            fluxoBA[l] = novaTex(tamanhos[l].first, tamanhos[l].second, GLES30.GL_RGBA16F, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT)
        }
        fabS = novaTex(tamanhos[0].first, tamanhos[0].second, GLES30.GL_RGBA16F, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT)
        fbaS = novaTex(tamanhos[0].first, tamanhos[0].second, GLES30.GL_RGBA16F, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT)
        fun f16() = novaTex(tamanhos[0].first, tamanhos[0].second, GLES30.GL_RGBA16F, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT)
        fabC = f16(); fbaC = f16(); for (i in 0..1) { fabT[i] = f16(); fbaT[i] = f16() }
        temPar = false; statPendente = false; histT = false; lumaValida = false; global.zerar()
    }

    private fun alvo(tex: Int, w: Int, hh: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0)
        GLES30.glViewport(0, 0, w, hh)
    }
    private fun ligar(unidade: Int, tex: Int) { GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unidade); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex) }
    private fun desenharTriangulo() = GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)

    // =============================================================== quadros

    private fun novoQuadro() {
        if (falhou) { runCatching { st.updateTexImage() }; return }
        try {
            st.updateTexImage(); st.getTransformMatrix(xform)
            val ts = st.timestamp
            val agora = System.nanoTime()
            val pts = acharPts(ts)
            val dest = 1 - cur
            // OES → quadro RGBA8 (aplica a matriz de transformação do SurfaceTexture: rotação/corte do decodificador)
            alvo(quadros[dest], fw, fh)
            GLES30.glUseProgram(progs.getValue("oes"))
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
            GLES30.glUniform1i(u("oes", "uOes"), 0); GLES30.glUniformMatrix4fv(u("oes", "uTransform"), 1, false, xform, 0)
            desenharTriangulo()
            val interpolar = lentaLigada() && velocidade() < 0.999f
            if (interpolar) piramide(dest)
            val desc = ritmo.novoQuadro(agora, pts, velocidade()) || !interpolar || !lumaValida
            lumaValida = interpolar
            val anterior = cur; cur = dest; vistos++
            if (desc || vistos < 2) { temPar = false; corte = false; statPendente = false; histT = false; global.zerar() }
            else {
                fluxo(anterior, cur)
                temPar = true; corte = false; statPendente = true; statEmitidoNs = agora
            }
            sujo = true
            agendar()
        } catch (t: Throwable) { falhar("quadro: ${t.javaClass.simpleName}: ${t.message}", t) }
    }

    /** Casa o timestamp do SurfaceTexture com o PTS informado pelo ExoPlayer (release time). */
    private fun acharPts(tsNs: Long): Long? {
        var achado: Long? = null
        while (true) {
            val e = filaPts.peek() ?: break
            if (e[0] < tsNs - 8_000_000L) { filaPts.poll(); continue }     // quadro descartado/antigo
            if (kotlin.math.abs(e[0] - tsNs) <= 8_000_000L) { achado = e[1]; filaPts.poll() }
            break
        }
        return achado
    }

    private fun piramide(idx: Int) {
        // nível 0: média de N×N amostras do quadro cheio
        val (w0, h0) = tamanhos[0]
        val taps = ((fw.toFloat() / w0) + 0.5f).toInt().coerceIn(1, 4)
        alvo(luma[idx][0], w0, h0)
        GLES30.glUseProgram(progs.getValue("luma")); ligar(0, quadros[idx])
        GLES30.glUniform1i(u("luma", "uSrc"), 0); GLES30.glUniform1i(u("luma", "uTaps"), taps); GLES30.glUniform2f(u("luma", "uDst"), w0.toFloat(), h0.toFloat())
        desenharTriangulo()
        GLES30.glUseProgram(progs.getValue("down"))
        for (l in 1 until NIVEIS) {
            alvo(luma[idx][l], tamanhos[l].first, tamanhos[l].second); ligar(0, luma[idx][l - 1])
            GLES30.glUniform1i(u("down", "uSrc"), 0); desenharTriangulo()
        }
    }

    private fun fluxo(a: Int, b: Int) {
        for (dir in 0..1) {
            val de = if (dir == 0) a else b; val para = if (dir == 0) b else a
            val saida = if (dir == 0) fluxoAB else fluxoBA
            for (l in NIVEIS - 1 downTo 0) {
                val p = "flow${RAIO[l]}"
                alvo(saida[l], tamanhos[l].first, tamanhos[l].second)
                GLES30.glUseProgram(progs.getValue(p))
                ligar(0, luma[de][l]); ligar(1, luma[para][l]); ligar(2, if (l == NIVEIS - 1) luma[de][l] else saida[l + 1])
                GLES30.glUniform1i(u(p, "uA"), 0); GLES30.glUniform1i(u(p, "uB"), 1); GLES30.glUniform1i(u(p, "uPrior"), 2)
                GLES30.glUniform1f(u(p, "uPriorOn"), if (l == NIVEIS - 1) 0f else 1f); GLES30.glUniform1f(u(p, "uLambda"), 0.004f)
                desenharTriangulo()
            }
            val s = if (dir == 0) fabS else fbaS
            alvo(s, tamanhos[0].first, tamanhos[0].second)
            GLES30.glUseProgram(progs.getValue("smooth")); ligar(0, saida[0]); GLES30.glUniform1i(u("smooth", "uFlow"), 0)
            GLES30.glUniform1f(u("smooth", "uMax"), CameraLentaLogica.vetorMax(tamanhos[0].first)); desenharTriangulo()
        }
        // confiança por vetor (frente-trás + vizinhança + custo), nos dois sentidos
        val (w0, h0) = tamanhos[0]
        for (dir in 0..1) {
            alvo(if (dir == 0) fabC else fbaC, w0, h0); GLES30.glUseProgram(progs.getValue("conf"))
            ligar(0, if (dir == 0) fabS else fbaS); ligar(1, if (dir == 0) fbaS else fabS)
            GLES30.glUniform1i(u("conf", "uF"), 0); GLES30.glUniform1i(u("conf", "uG"), 1); desenharTriangulo()
        }
        // espalha (feather) a confiança e estabiliza no tempo (mistura com o par anterior)
        val novo = 1 - idxT
        for (dir in 0..1) {
            val dst = if (dir == 0) fabT[novo] else fbaT[novo]; val prv = if (dir == 0) fabT[idxT] else fbaT[idxT]
            alvo(dst, w0, h0); GLES30.glUseProgram(progs.getValue("temporal"))
            ligar(0, if (dir == 0) fabC else fbaC); ligar(1, prv)
            GLES30.glUniform1i(u("temporal", "uCur"), 0); GLES30.glUniform1i(u("temporal", "uPrev"), 1)
            GLES30.glUniform1f(u("temporal", "uPrevOn"), if (histT) 1f else 0f); desenharTriangulo()
        }
        idxT = novo; histT = true
        // estatística: (1) corte de cena (custo/contraste, 16×9) e (2) confiança global (blocos ruins, 16×9) numa só textura 16×18
        alvo(texStat, 16, 9)
        GLES30.glUseProgram(progs.getValue("stat")); ligar(0, fabS); ligar(1, luma[a][0])
        GLES30.glUniform1i(u("stat", "uFlow"), 0); GLES30.glUniform1i(u("stat", "uLuma"), 1); desenharTriangulo()
        GLES30.glViewport(0, 9, 16, 9)
        GLES30.glUseProgram(progs.getValue("stat2")); ligar(0, fabT[idxT]); ligar(1, luma[a][0]); ligar(2, luma[b][0])
        GLES30.glUniform1i(u("stat2", "uFlow"), 0); GLES30.glUniform1i(u("stat2", "uLumaA"), 1); GLES30.glUniform1i(u("stat2", "uLumaB"), 2); desenharTriangulo()
        GLES30.glFlush()
    }

    /** Lê a estatística do par atual (sincroniza com a GPU; chamado 1 vsync depois de emitir) e decide corte + desempenho. */
    private fun resolverEstat() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texStat, 0)
        statBuf.clear()
        GLES30.glReadPixels(0, 0, 16, 18, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, statBuf)
        val computeNs = System.nanoTime() - statEmitidoNs
        val b = ByteArray(16 * 18 * 4); statBuf.rewind(); statBuf.get(b)
        val (custo, contraste) = CameraLentaLogica.decodificarEstat(b.copyOfRange(0, 16 * 9 * 4))
        corte = CameraLentaLogica.ehCorte(custo, contraste)
        val (ruim, dif) = CameraLentaLogica.decodificarEstat2(b.copyOfRange(16 * 9 * 4, 16 * 18 * 4))
        global.atualizar(ruim, dif)
        if (corte) { histT = false; global.zerar() }
        statPendente = false
        when (guarda.registrar(computeNs, ritmo.periodoNs)) {
            CameraLentaLogica.Guarda.Acao.REDUZIR -> { nivelDesempenho = guarda.nivel; alocarTexturas(); vistos = 0 }
            CameraLentaLogica.Guarda.Acao.DESLIGAR -> falhar("GPU lenta demais p/ interpolar")
            else -> {}
        }
    }

    // =============================================================== janela / desenho

    private fun criarJanela(tentativa: Int = 1) {
        if (parado || falhou || janela != EGL14.EGL_NO_SURFACE) return
        try {
            val s = tv.surfaceTexture ?: throw IllegalStateException("TextureView sem superfície")
            janela = EGL14.eglCreateWindowSurface(dpy, eglCfg, s, intArrayOf(EGL14.EGL_NONE), 0)
            if (janela == EGL14.EGL_NO_SURFACE) {
                val err = EGL14.eglGetError()
                if (tentativa < 6) { h.postDelayed({ criarJanela(tentativa + 1) }, 250); return }
                throw IllegalStateException("eglCreateWindowSurface 0x${Integer.toHexString(err)}")
            }
            check(EGL14.eglMakeCurrent(dpy, janela, janela, eglCtx)) { "eglMakeCurrent(janela)" }
            EGL14.eglSwapInterval(dpy, 1)
            sujo = true; agendar()
        } catch (t: Throwable) { falhar("janela: ${t.javaClass.simpleName}: ${t.message}", t) }
    }

    private fun agendar() {
        if (rodandoCoreografo || janela == EGL14.EGL_NO_SURFACE || parado) return
        rodandoCoreografo = true
        Choreographer.getInstance().postFrameCallback(frameCb)
    }

    private fun desenhar(frameNs: Long) {
        if (parado || falhou || janela == EGL14.EGL_NO_SURFACE || vistos == 0) return
        try {
            val mel = melhorar() && melhoriaOk && !melhoriaOff
            if (mel) {
                // quadros perdidos em sequência contínua (o desenho anterior já pedia o próximo vsync)
                if (continuoAnterior && ultimoDesenhoNs != 0L && guardaMel.intervalo(frameNs - ultimoDesenhoNs)) { desligarMelhoria("quadros perdidos"); return desenhar(frameNs) }
            } else guardaMel.zerar()
            ultimoDesenhoNs = frameNs
            if (statPendente && temPar) resolverEstat()
            if (falhou) return
            val t = if (temPar) ritmo.t(frameNs) else 1f
            val modo = when {
                !temPar -> 2                                  // sem par (1º quadro/seek/≥ 1×): mostra o quadro atual direto
                statPendente || corte -> if (t >= 0.999f) 2 else 1   // sem fluxo ainda / corte: repete A, depois B
                else -> 0
            }
            val w = IntArray(1); val hh = IntArray(1)
            EGL14.eglQuerySurface(dpy, janela, EGL14.EGL_WIDTH, w, 0); EGL14.eglQuerySurface(dpy, janela, EGL14.EGL_HEIGHT, hh, 0)
            val prev = 1 - cur
            val passaInterp = modo != 2 || !mel          // com melhoria e sem interpolar: a melhoria lê o quadro direto (0 passadas extras)
            var fonte = quadros[cur]
            if (passaInterp) {
                if (mel) alvo(compo, fw, fh) else { GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0); GLES30.glViewport(0, 0, maxOf(w[0], 1), maxOf(hh[0], 1)) }
                GLES30.glUseProgram(progs.getValue("interp"))
                ligar(0, if (temPar) quadros[prev] else quadros[cur]); ligar(1, quadros[cur]); ligar(2, fabT[idxT]); ligar(3, fbaT[idxT])
                GLES30.glUniform1i(u("interp", "uA"), 0); GLES30.glUniform1i(u("interp", "uB"), 1)
                GLES30.glUniform1i(u("interp", "uFab"), 2); GLES30.glUniform1i(u("interp", "uFba"), 3)
                GLES30.glUniform1f(u("interp", "uT"), t); GLES30.glUniform1i(u("interp", "uModo"), modo)
                GLES30.glUniform1f(u("interp", "uFb"), global.peso); GLES30.glUniform1f(u("interp", "uFbCross"), if (global.crossfade) 1f else 0f)
                desenharTriangulo()
                fonte = compo
            }
            if (mel) {
                // amostra o tempo de GPU da passada de melhoria em 1 de cada 8 desenhos (glFinish só aí)
                val medir = (contDesenho++ and 7) == 0
                if (medir) GLES30.glFinish()
                val t0 = System.nanoTime()
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0); GLES30.glViewport(0, 0, maxOf(w[0], 1), maxOf(hh[0], 1))
                GLES30.glUseProgram(progs.getValue("enhance")); ligar(0, fonte)
                GLES30.glUniform1i(u("enhance", "uSrc"), 0); GLES30.glUniform1f(u("enhance", "uForca"), 0.6f)
                desenharTriangulo()
                if (medir) { GLES30.glFinish(); if (guardaMel.amostra(System.nanoTime() - t0)) { desligarMelhoria("lenta demais"); return desenhar(frameNs) } }
            }
            EGL14.eglSwapBuffers(dpy, janela)
            sujo = false
            continuoAnterior = t < 1f || statPendente
            if (continuoAnterior) agendar()
        } catch (e: Throwable) { falhar("desenho: ${e.javaClass.simpleName}: ${e.message}", e) }
    }

    /** Desliga só a melhoria (o vídeo e a interpolação seguem) e avisa a interface (toast). */
    private fun desligarMelhoria(motivo: String) {
        if (melhoriaOff) return
        melhoriaOff = true
        Log.w(TAG, "melhoria desligada: $motivo")
        main.post { if (!parado) aoMelhoriaFalhar(motivo) }
    }

    private fun liberarGl() {
        runCatching { Choreographer.getInstance().removeFrameCallback(frameCb) }
        runCatching { st.setOnFrameAvailableListener(null) }
        runCatching { EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }
        if (janela != EGL14.EGL_NO_SURFACE) runCatching { EGL14.eglDestroySurface(dpy, janela) }
        janela = EGL14.EGL_NO_SURFACE
        if (pbuf != EGL14.EGL_NO_SURFACE) runCatching { EGL14.eglDestroySurface(dpy, pbuf) }
        if (eglCtx != EGL14.EGL_NO_CONTEXT) runCatching { EGL14.eglDestroyContext(dpy, eglCtx) }
        runCatching { st.release() }
        if (dpy != EGL14.EGL_NO_DISPLAY) runCatching { EGL14.eglTerminate(dpy) }
    }
}
