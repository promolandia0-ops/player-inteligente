package br.geracaoai.player

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** NudeNet 320n (YOLOv8n, 18 classes) — UMA OrtSession reutilizada; tensors fechados em use{}. */
class Detector(ctx: Context, usarAcelerador0: Boolean) : AutoCloseable {
    companion object {
        const val ASSET = "nudenet_320n.onnx"
        private const val TAG = "Detector"

        /** NNAPI derrubou o processo antes → fica desligado até o usuário religar o acelerador. */
        fun nnapiBloqueado(ctx: Context) = File(ctx.filesDir, "nnapi_off").exists()
        fun liberarNnapi(ctx: Context) {
            File(ctx.filesDir, "nnapi_off").delete()
            File(ctx.filesDir, "nnapi_guard").delete()
        }

        /** Carrega o modelo do asset (sem compressão) ou da cópia em filesDir. Erro claro se faltar. */
        fun carregarModelo(ctx: Context): ByteArray {
            // Preferência: cópia já extraída (evita reabrir o APK a cada processo).
            val copia = File(ctx.filesDir, ASSET)
            if (copia.exists() && copia.length() > 1_000_000L) {
                return copia.readBytes()
            }
            val bytes = try {
                ctx.assets.open(ASSET).use { it.readBytes() }
            } catch (e: Throwable) {
                throw IllegalStateException(
                    "Modelo $ASSET não encontrado no app (assets). Reinstale o APK. Detalhe: ${e.message}", e
                )
            }
            if (bytes.size < 1_000_000) {
                throw IllegalStateException(
                    "Modelo $ASSET parece corrompido (${bytes.size} bytes). Reinstale o APK."
                )
            }
            runCatching {
                val tmp = File(ctx.filesDir, "$ASSET.tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(copia)) { copia.writeBytes(bytes); tmp.delete() }
            }
            Log.i(TAG, "modelo ok ${bytes.size} bytes (asset→filesDir)")
            return bytes
        }
    }

    private val env: OrtEnvironment = run {
        // Garante JNI Microsoft ORT (não o .so do sherpa) antes do clinit.
        runCatching { System.loadLibrary("onnxruntime4j_jni") }
            .onFailure { Log.e(TAG, "load onnxruntime4j_jni", it) }
        try {
            OrtEnvironment.getEnvironment()
        } catch (e: Throwable) {
            throw IllegalStateException(
                "ONNX Runtime não carregou (${e.javaClass.simpleName}: ${e.message}). Reinstale o APK.", e,
            )
        }
    }
    private val sessao: OrtSession
    private val opcoes: OrtSession.SessionOptions
    var backend = "CPU"; private set
    private val S = 320
    private val buf: FloatBuffer = ByteBuffer.allocateDirect(4 * 3 * S * S).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val px = IntArray(S * S)
    private val tela = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val guard = File(ctx.filesDir, "nnapi_guard")
    private val off = File(ctx.filesDir, "nnapi_off")
    private var guardAtivo = false

    init {
        if (guard.exists()) {
            runCatching { off.writeText("crash nativo com NNAPI"); guard.delete() }
        }
        val usarAcelerador = usarAcelerador0 && !off.exists()
        val modelo = carregarModelo(ctx)
        fun opts(nnapi: Boolean) = OrtSession.SessionOptions().apply {
            setSymbolicDimensionValue("batch", 1)
            setSymbolicDimensionValue("height", S.toLong())
            setSymbolicDimensionValue("width", S.toLong())
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(2)
            if (nnapi) addNnapi() else runCatching { addXnnpack(mapOf("intra_op_num_threads" to "2")) }
        }
        var s: OrtSession? = null
        var o: OrtSession.SessionOptions? = null
        if (usarAcelerador) {
            o = opts(true)
            runCatching { guard.writeText("1") }; guardAtivo = true
            s = runCatching { env.createSession(modelo, o).also { backend = "NNAPI" } }
                .onFailure { Log.w(TAG, "NNAPI indisponível", it); runCatching { o?.close() }; o = null }
                .getOrNull()
        }
        if (s == null) {
            o = opts(false)
            s = runCatching { env.createSession(modelo, o).also { backend = "CPU (XNNPACK)" } }
                .onFailure { runCatching { o?.close() }; o = null }
                .getOrNull()
        }
        if (s == null) {
            o = OrtSession.SessionOptions()
            s = try {
                env.createSession(modelo, o).also { backend = "CPU" }
            } catch (e: Throwable) {
                runCatching { o.close() }
                throw IllegalStateException("Falha ao abrir o modelo ONNX: ${e.message}", e)
            }
        }
        if (backend != "NNAPI" && guardAtivo) { guard.delete(); guardAtivo = false }
        sessao = s
        opcoes = o!!
        Log.i(TAG, "sessão pronta backend=$backend")
    }

    fun pontuar(frame: Bitmap): Map<Categoria, Float> {
        preparar(frame)
        OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, S.toLong(), S.toLong())).use { t ->
            sessao.run(mapOf(sessao.inputNames.first() to t)).use { r ->
                val out = (r[0] as OnnxTensor)
                val a = out.info.shape[2].toInt()
                val fb = out.floatBuffer
                okRodou()
                return Categoria.entries.associateWith { cat ->
                    if (cat.classIndex < 0) 0f else {
                        val base = (4 + cat.classIndex) * a; var m = 0f
                        for (i in 0 until a) { val v = fb.get(base + i); if (v > m) m = v }
                        m
                    }
                }
            }
        }
    }

    /**
     * @return cats acima do limiar, se há nudez exposta, e área máx. da caixa (fração do frame 320²)
     * por categoria UI (0 se não detectou).
     */
    fun pontuarExposto(frame: Bitmap, limiar: Float = OrganizarWorker.LIMIAR): Triple<Set<Categoria>, Boolean, Map<Categoria, Float>> {
        preparar(frame)
        OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, S.toLong(), S.toLong())).use { tensor ->
            sessao.run(mapOf(sessao.inputNames.first() to tensor)).use { r ->
                val out = (r[0] as OnnxTensor)
                val a = out.info.shape[2].toInt()
                val fb = out.floatBuffer
                // YOLOv8: linhas 0..3 = cx,cy,w,h (norm. ~0..S); 4+ = classes
                fun maxClasseEArea(idx: Int): Pair<Float, Float> {
                    val base = (4 + idx) * a
                    var m = 0f; var area = 0f
                    for (i in 0 until a) {
                        val v = fb.get(base + i)
                        if (v >= m) {
                            m = v
                            val w = fb.get(2 * a + i).coerceAtLeast(0f)
                            val h = fb.get(3 * a + i).coerceAtLeast(0f)
                            area = ((w * h) / (S * S).toFloat()).coerceIn(0f, 1f)
                        }
                    }
                    return m to area
                }
                okRodou()
                val areas = HashMap<Categoria, Float>()
                val cats = linkedSetOf<Categoria>()
                for (cat in Categoria.entries) {
                    if (cat.classIndex < 0) continue
                    val (sc, ar) = maxClasseEArea(cat.classIndex)
                    if (sc >= limiar) { cats.add(cat); areas[cat] = ar }
                }
                var putaria = false
                for (idx in OrganizarWorker.CLASSES_EXPOSTAS) {
                    if (maxClasseEArea(idx).first >= limiar) { putaria = true; break }
                }
                return Triple(cats, putaria, areas)
            }
        }
    }


    /**
     * Detalhe p/ pastas Por tamanho (métrica v2). NÃO usado na análise por vídeo.
     * Extrai caixas YOLO e normaliza por escala corporal no mesmo frame.
     */
    fun pontuarDetalhe(frame: Bitmap, limiar: Float = OrganizarWorker.LIMIAR): TamanhoLogica.Evidencia {
        preparar(frame)
        OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, S.toLong(), S.toLong())).use { tensor ->
            sessao.run(mapOf(sessao.inputNames.first() to tensor)).use { r ->
                val out = (r[0] as OnnxTensor)
                val a = out.info.shape[2].toInt()
                val fb = out.floatBuffer
                val s = S.toFloat()
                fun caixas(idx: Int, lim: Float, maxN: Int = 3): List<TamanhoLogica.Caixa> {
                    val base = (4 + idx) * a
                    val tmp = ArrayList<TamanhoLogica.Caixa>(8)
                    for (i in 0 until a) {
                        val v = fb.get(base + i)
                        if (v < lim) continue
                        val cx = fb.get(0 * a + i) / s
                        val cy = fb.get(1 * a + i) / s
                        val w = (fb.get(2 * a + i) / s).coerceAtLeast(0f)
                        val h = (fb.get(3 * a + i) / s).coerceAtLeast(0f)
                        if (w < 0.005f || h < 0.005f) continue
                        tmp.add(TamanhoLogica.Caixa(v, cx, cy, w.coerceAtMost(1.5f), h.coerceAtMost(1.5f)))
                    }
                    return tmp.sortedByDescending { it.sc }.take(maxN)
                }
                fun top(idx: Int, lim: Float = limiar): TamanhoLogica.Caixa? = caixas(idx, lim, 1).firstOrNull()
                fun maxSc(idx: Int): Float {
                    val base = (4 + idx) * a
                    var m = 0f
                    for (i in 0 until a) m = maxOf(m, fb.get(base + i))
                    return m
                }
                okRodou()
                val peitos = caixas(TamanhoLogica.FEMALE_BREAST_EXPOSED, limiar, 2)
                val bunda = top(TamanhoLogica.BUTTOCKS_EXPOSED)
                val buceta = top(TamanhoLogica.FEMALE_GENITALIA_EXPOSED)
                val face = top(TamanhoLogica.FACE_FEMALE, limiar * 0.85f) // face um pouco mais permissiva p/ escala
                val belly = top(TamanhoLogica.BELLY_EXPOSED, limiar * 0.85f)
                val armpit = top(TamanhoLogica.ARMPITS_EXPOSED, limiar * 0.85f)
                val feet = top(TamanhoLogica.FEET_EXPOSED, limiar * 0.85f)
                val fbSc = peitos.maxOfOrNull { it.sc } ?: 0f
                val buSc = bunda?.sc ?: 0f
                val fgSc = buceta?.sc ?: 0f
                val ffSc = face?.sc ?: maxSc(TamanhoLogica.FACE_FEMALE)
                val ap = TamanhoLogica.metricaPeito(peitos, face, belly, armpit, feet)
                val ab = TamanhoLogica.metricaBunda(bunda, face, belly, armpit, feet)
                val abu = TamanhoLogica.metricaBuceta(buceta, face, belly, armpit, feet)
                return TamanhoLogica.Evidencia(
                    faceFemale = ffSc,
                    faceMale = maxSc(TamanhoLogica.FACE_MALE),
                    maleGen = maxSc(TamanhoLogica.MALE_GENITALIA_EXPOSED),
                    maleBreast = maxSc(TamanhoLogica.MALE_BREAST_EXPOSED),
                    femaleBreast = fbSc,
                    femaleGen = fgSc,
                    buttocks = buSc,
                    areaPeito = ap,
                    areaBunda = ab,
                    areaBuceta = abu,
                    faceArea = face?.area ?: 0f,
                    breastCovered = maxSc(TamanhoLogica.FEMALE_BREAST_COVERED),
                    buttocksCovered = maxSc(TamanhoLogica.BUTTOCKS_COVERED),
                    genCovered = maxSc(TamanhoLogica.FEMALE_GENITALIA_COVERED),
                    hitsPeito = if (ap > 0f) 1 else 0,
                    hitsBunda = if (ab > 0f) 1 else 0,
                    hitsBuceta = if (abu > 0f) 1 else 0,
                )
            }
        }
    }

    private fun okRodou() { if (guardAtivo) { guard.delete(); guardAtivo = false } }

    private fun preparar(frame: Bitmap) {
        val c = Canvas(tela); c.drawColor(Color.BLACK)
        val esc = S.toFloat() / maxOf(frame.width, frame.height)
        val w = (frame.width * esc).toInt().coerceAtLeast(1)
        val h = (frame.height * esc).toInt().coerceAtLeast(1)
        c.drawBitmap(frame, android.graphics.Rect(0, 0, frame.width, frame.height), android.graphics.Rect(0, 0, w, h), paint)
        tela.getPixels(px, 0, S, 0, 0, S, S)
        buf.rewind()
        val n = S * S
        for (i in 0 until n) {
            val p = px[i]
            buf.put(i, ((p shr 16) and 0xFF) / 255f)
            buf.put(n + i, ((p shr 8) and 0xFF) / 255f)
            buf.put(2 * n + i, (p and 0xFF) / 255f)
        }
        buf.rewind()
    }

    override fun close() {
        runCatching { sessao.close() }
        runCatching { opcoes.close() }
        okRodou()
        if (!tela.isRecycled) tela.recycle()
    }
}
