package br.geracaoai.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.*
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

/** Estado da análise por vídeo, trocado entre processos via arquivo (UI ↔ :analise). */
data class AnaliseStatus(val uri: String, val estado: String, val p: Float, val txt: String, val erro: String?, val hb: Long) {
    val rodando get() = estado == RODANDO
    companion object {
        const val RODANDO = "rodando"; const val OK = "ok"; const val ERRO = "erro"; const val CANCELADO = "cancelado"
        private fun arq(ctx: Context) = File(ctx.filesDir, "analise_status.json")
        fun gravar(ctx: Context, s: AnaliseStatus) = runCatching {
            val f = arq(ctx); val tmp = File(f.path + ".tmp")
            tmp.writeText(JSONObject().put("uri", s.uri).put("estado", s.estado).put("p", s.p.toDouble())
                .put("txt", s.txt).put("erro", s.erro ?: "").put("hb", s.hb).toString())
            tmp.renameTo(f)
        }
        fun ler(ctx: Context): AnaliseStatus? = runCatching {
            val j = JSONObject(arq(ctx).readText())
            AnaliseStatus(j.getString("uri"), j.getString("estado"), j.optDouble("p", 0.0).toFloat(), j.optString("txt"),
                j.optString("erro").ifBlank { null }, j.optLong("hb"))
        }.getOrNull()
    }
}

/**
 * Análise em 2 passagens, a partir da posição de reprodução:
 *  1) grosso (~1 quadro / 2 s, keyframe) — marcas aparecem na hora
 *  2) refino ±2 s em torno dos hits, no fps das preferências
 * Roda num Service em processo próprio `:analise`: crash nativo (NNAPI/codec) ou OOM aqui
 * NÃO fecha o player. Uma análise por vez, uma única OrtSession, quadros de 320 px.
 */
class AnaliseService : Service() {
    companion object {
        const val CANAL = "analise"
        const val GROSSO_MS = 2000L
        const val LIMIAR_GROSSO = 0.28f
        const val ACTION_START = "br.geracaoai.player.ANALISE_START"
        const val ACTION_CANCEL = "br.geracaoai.player.ANALISE_CANCEL"
        private const val TAG = "Analise"
        private const val NOTIF = 42
        /** Sem batida há mais que isso com estado "rodando" = processo morreu. */
        const val HB_MORTO_MS = 25_000L

        fun iniciar(ctx: Context, uri: Uri, startMs: Long = 0L) {
            val p = Prefs(ctx)
            AnaliseStatus.gravar(ctx, AnaliseStatus(uri.toString(), AnaliseStatus.RODANDO, 0f, "Preparando", null, System.currentTimeMillis()))
            // Nunca roda junto com a galeria: pede ao :scan para ceder.
            runCatching { OrganizarScanService.yieldToAnalise(ctx) }
            val i = Intent(ctx, AnaliseService::class.java).setAction(ACTION_START)
                .putExtra("uri", uri.toString()).putExtra("startMs", startMs)
                .putExtra("fps", p.fps).putExtra("nnapi", p.acelerador)
            ContextCompat.startForegroundService(ctx, i)
        }
        fun cancelar(ctx: Context) {
            runCatching { ctx.startService(Intent(ctx, AnaliseService::class.java).setAction(ACTION_CANCEL)) }
            AnaliseStatus.ler(ctx)?.let { if (it.rodando) AnaliseStatus.gravar(ctx, it.copy(estado = AnaliseStatus.CANCELADO, hb = System.currentTimeMillis())) }
        }
        fun rodando(ctx: Context): Boolean = AnaliseStatus.ler(ctx)?.let { it.rodando && System.currentTimeMillis() - it.hb < HB_MORTO_MS } ?: false
    }

    @Volatile private var isStopped = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notif(p: Int, texto: String): android.app.Notification {
        val cancel = PendingIntent.getService(this, 1, Intent(this, AnaliseService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_notif).setContentTitle("Buscando cenas…").setContentText(texto)
            .setProgress(100, p, false).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .addAction(0, "Cancelar", cancel).build()
    }

    private fun subirForeground() {
        try {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CANAL, "Análise de vídeo", NotificationManager.IMPORTANCE_LOW))
            val n = notif(0, "Preparando")
            val tipo = if (android.os.Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            try { startForeground(NOTIF, n, tipo) }
            catch (e: Throwable) {
                Log.w(TAG, "fgs tipo $tipo falhou", e)
                startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            }
        } catch (e: Throwable) { Log.e(TAG, "startForeground falhou (seguindo sem FGS)", e) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        subirForeground()
        when (intent?.action) {
            ACTION_CANCEL -> {
                isStopped = true; job?.cancel()
                AnaliseStatus.ler(this)?.let { if (it.rodando) AnaliseStatus.gravar(this, it.copy(estado = AnaliseStatus.CANCELADO)) }
                if (job == null || job?.isActive != true) stopSelf()
            }
            ACTION_START -> {
                val uri = intent.getStringExtra("uri") ?: run { stopSelf(); return START_NOT_STICKY }
                val startMs = intent.getLongExtra("startMs", 0L)
                val fps = intent.getIntExtra("fps", 2)
                val nnapi = intent.getBooleanExtra("nnapi", false)
                val anterior = job
                isStopped = true
                job = scope.launch {
                    anterior?.cancelAndJoin()
                    isStopped = false
                    rodar(Uri.parse(uri), startMs, fps, nnapi)
                    if (job === coroutineContext[Job]) stopSelf()
                }
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { isStopped = true; scope.cancel(); super.onDestroy() }

    private suspend fun rodar(uri: Uri, startMs0: Long, fps: Int, nnapi: Boolean) {
        val ctx = applicationContext
        val nm = getSystemService(NotificationManager::class.java)
        val startMs = startMs0.coerceAtLeast(0L)
        val finoMs = 1000L / fps.coerceIn(1, 4)
        val t0 = System.currentTimeMillis()
        fun status(estado: String, p: Float, txt: String, erro: String? = null) =
            AnaliseStatus.gravar(ctx, AnaliseStatus(uri.toString(), estado, p, txt, erro, System.currentTimeMillis()))
        Log.i(TAG, "início pid=${Process.myPid()} uri=$uri nnapi=$nnapi")
        status(AnaliseStatus.RODANDO, 0f, "Preparando")
        // Batida: mesmo com quadro lento a UI sabe que o processo está vivo.
        val hb = scope.launch { while (isActive) { delay(5000); AnaliseStatus.ler(ctx)?.let { if (it.rodando && it.uri == uri.toString()) AnaliseStatus.gravar(ctx, it.copy(hb = System.currentTimeMillis())) } } }
        try {
            // Nunca dois modelos/decoders ao mesmo tempo: espera o :scan soltar tudo.
            runCatching { OrganizarScanService.yieldToAnalise(ctx) }
            if (!withContext(Dispatchers.IO) { Exclusao.esperarScanParar(ctx) }) Log.w(TAG, "scan não liberou a tempo; seguindo")
            if (isStopped) return
            val chave = Cache.chave(ctx, uri)
            val dur = duracao(ctx, uri) ?: throw IllegalStateException("Não consegui ler a duração do vídeo")
            val mapa = LinkedHashMap<Long, FloatArray>()
            fun slot(t: Long) = mapa.getOrPut(t) { FloatArray(Categoria.entries.size) }
            fun persistir(parcial: Boolean) {
                val tempos = mapa.keys.sorted().toLongArray()
                val sc = Categoria.entries.associateWith { c -> FloatArray(tempos.size) { i -> mapa[tempos[i]]?.get(c.ordinal) ?: 0f } }
                Cache.salvar(ctx, chave, Analise(dur, tempos, sc, parcial), finoMs)
            }
            fun progresso(fase: String, feitos: Int, total: Int, backend: String) {
                val pf = (feitos.toFloat() / max(1, total)).coerceIn(0f, 0.99f)
                val el = (System.currentTimeMillis() - t0) / 1000
                val rest = if (feitos > 0) el * (total - feitos) / feitos else 0
                val txt = "$fase · ${(pf * 100).toInt()}% · $backend · ~${rest}s"
                status(AnaliseStatus.RODANDO, pf, txt)
                runCatching { nm.notify(NOTIF, notif((pf * 100).toInt(), txt)) }
                persistir(true)
            }

            Detector(ctx, nnapi).use { det ->
                val grossos = temposAPartirDe(dur, GROSSO_MS, startMs)
                val totalEst = grossos.size * 2
                val feitos = AtomicInteger(0)
                pipeline(ctx, uri, grossos, keyframe = true, det) { t, sc ->
                    val s = slot(t); for (i in sc.indices) s[i] = max(s[i], sc[i])
                    val n = feitos.incrementAndGet()
                    if (n % 2 == 0 || n == grossos.size) progresso("Rápida", n, totalEst, det.backend)
                    !isStopped
                }
                if (isStopped) { persistir(true); return }
                val hits = grossos.filter { t -> slot(t).any { it >= LIMIAR_GROSSO } }
                val refinados = LinkedHashSet<Long>()
                for (h in hits) {
                    var t = max(0L, h - GROSSO_MS); val fim = min(dur, h + GROSSO_MS)
                    while (t <= fim) { if (t !in mapa) refinados.add(t); t += finoMs }
                }
                val listaRefino = refinados.sortedBy { ordemAPartirDe(it, startMs, dur) }
                val total2 = feitos.get() + listaRefino.size
                pipeline(ctx, uri, listaRefino, keyframe = false, det) { t, sc ->
                    val s = slot(t); for (i in sc.indices) s[i] = max(s[i], sc[i])
                    val n = feitos.incrementAndGet()
                    if (n % 3 == 0 || n >= total2) progresso("Refino", n, max(total2, n), det.backend)
                    !isStopped
                }
                if (isStopped) { persistir(true); return }
            }
            persistir(false)
            val el = (System.currentTimeMillis() - t0) / 1000f
            Log.i(TAG, "ok %.1fs frames=${mapa.size}".format(el))
            status(AnaliseStatus.OK, 1f, "Pronto · %.1fs".format(el))
        } catch (e: CancellationException) {
            status(AnaliseStatus.CANCELADO, 0f, "Cancelado")
        } catch (e: Throwable) {
            Log.e(TAG, "falhou", e)
            App.gravarErro(ctx, "analise", e)
            status(AnaliseStatus.ERRO, 0f, "Falhou", e.message ?: e.javaClass.simpleName)
        } finally {
            hb.cancel()
            // :analise termina limpo; libera memória nativa do ORT.
            if (job == null || job?.isCancelled == false) scope.launch { delay(500); if (job?.isActive != true) { stopSelf() } }
            runCatching { nm.cancel(NOTIF) }
            retomarGaleriaSePreciso(ctx)
        }
    }

    private fun retomarGaleriaSePreciso(ctx: Context) {
        // Player aberto → a galeria volta só quando o usuário sair do player.
        if (!Exclusao.playerEstaAberto(ctx)) Exclusao.retomarGaleriaSePreciso(ctx)
    }

    /** Produtor decodifica; consumidor pontua. Capacidade 2 = pipeline curto. */
    private suspend fun pipeline(
        ctx: Context,
        uri: Uri,
        tempos: List<Long>,
        keyframe: Boolean,
        det: Detector,
        onScore: suspend (Long, FloatArray) -> Boolean,
    ) {
        if (tempos.isEmpty()) return
        // Estritamente sequencial: decodifica 1 quadro (≤320 px) → pontua → recicla → próximo.
        var continuar = true
        suspend fun um(t: Long, bmp: Bitmap): Boolean {
            try {
                val mapa = det.pontuar(bmp)
                val arr = FloatArray(Categoria.entries.size) { i -> mapa[Categoria.entries[i]] ?: 0f }
                continuar = onScore(t, arr) && !isStopped
            } finally { if (!bmp.isRecycled) bmp.recycle() }
            return continuar
        }
        val feitosMc = HashSet<Long>()
        val okMc = withContext(Dispatchers.IO) {
            decodificarMediaCodec(ctx, uri, tempos, keyframe) { t, bmp ->
                feitosMc.add(t); runBlocking { um(t, bmp) }
            }
        }
        if (!continuar || isStopped) return
        val faltam = if (okMc) tempos.filter { it !in feitosMc && !keyframe } else tempos
        if (okMc && keyframe) return
        if (faltam.isEmpty()) return
        withContext(Dispatchers.IO) {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(ctx, uri)
                val opt = if (keyframe) MediaMetadataRetriever.OPTION_CLOSEST_SYNC else MediaMetadataRetriever.OPTION_CLOSEST
                for (t in faltam) {
                    if (isStopped || !continuar) break
                    val bmp = try { mmr.getScaledFrameAtTime(t * 1000, opt, 320, 320) } catch (e: Throwable) { Log.w(TAG, "frame $t", e); null } ?: continue
                    um(t, bmp)
                }
            } finally { runCatching { mmr.release() } }
        }
    }

    /**
     * MediaCodec + ImageReader: seek por tempo e captura 1 frame.
     * Retorna false se o device/caminho falhar (caller usa MMR).
     */
    private fun decodificarMediaCodec(
        ctx: Context,
        uri: Uri,
        tempos: List<Long>,
        keyframe: Boolean,
        onFrame: (Long, Bitmap) -> Boolean,
    ): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(ctx, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return false
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return false
            val srcW = format.getInteger(MediaFormat.KEY_WIDTH)
            val srcH = format.getInteger(MediaFormat.KEY_HEIGHT)
            val esc = 320f / max(srcW, srcH)
            val outW = max(16, (srcW * esc).toInt() and 0x7FFFFFFE)
            val outH = max(16, (srcH * esc).toInt() and 0x7FFFFFFE)

            val reader = ImageReader.newInstance(outW, outH, ImageFormat.YUV_420_888, 2)
            val thread = HandlerThread("mc-dec").also { it.start() }
            val handler = Handler(thread.looper)
            val fila = java.util.concurrent.ArrayBlockingQueue<Image>(2)
            reader.setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                if (!fila.offer(img)) img.close()
            }, handler)

            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, reader.surface, null, 0)
                codec.start()
            } catch (e: Exception) {
                Log.w(TAG, "codec configure fail", e)
                runCatching { codec.release() }; reader.close(); thread.quitSafely()
                return false
            }

            val info = MediaCodec.BufferInfo()
            var okFrames = 0
            for (alvo in tempos) {
                if (isStopped) break
                val mode = if (keyframe) MediaExtractor.SEEK_TO_PREVIOUS_SYNC else MediaExtractor.SEEK_TO_CLOSEST_SYNC
                extractor.seekTo(alvo * 1000L, mode)
                codec.flush()
                while (true) { fila.poll()?.close() ?: break }

                var got = false
                var tries = 0
                var inputDone = false
                while (!got && tries++ < 45 && !isStopped) {
                    if (!inputDone) {
                        val inIx = codec.dequeueInputBuffer(3_000)
                        if (inIx >= 0) {
                            val buf = codec.getInputBuffer(inIx)!!
                            val n = extractor.readSampleData(buf, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(inIx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIx, 0, n, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIx = codec.dequeueOutputBuffer(info, 3_000)
                    if (outIx >= 0) {
                        val pts = info.presentationTimeUs / 1000L
                        val render = keyframe || pts + 120 >= alvo ||
                            (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        codec.releaseOutputBuffer(outIx, render)
                        if (render) {
                            val img = fila.poll(180, java.util.concurrent.TimeUnit.MILLISECONDS)
                            if (img != null) {
                                try {
                                    val bmp = yuvParaBitmap(img) ?: continue
                                    okFrames++
                                    if (!onFrame(alvo, bmp)) { got = true; break }
                                    got = true
                                } finally { img.close() }
                            } else if (keyframe) got = true
                        }
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                }
            }
            runCatching { codec.stop() }; runCatching { codec.release() }
            reader.close(); thread.quitSafely()
            while (true) { fila.poll()?.close() ?: break }
            okFrames > 0
        } catch (e: Throwable) {
            Log.w(TAG, "MediaCodec path fail, using MMR", e)
            false
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun yuvParaBitmap(image: Image): Bitmap? = try {
        val width = image.width; val height = image.height
        val yPlane = image.planes[0]; val uPlane = image.planes[1]; val vPlane = image.planes[2]
        val ySize = width * height
        val nv21 = ByteArray(ySize + width * height / 2)
        // Y
        val yBuf = yPlane.buffer; val yRow = yPlane.rowStride
        var pos = 0
        if (yRow == width) {
            yBuf.get(nv21, 0, ySize); pos = ySize
        } else {
            for (row in 0 until height) {
                yBuf.position(row * yRow)
                yBuf.get(nv21, pos, width); pos += width
            }
        }
        // UV → NV21 (VUVU…)
        val vBuf = vPlane.buffer; val uBuf = uPlane.buffer
        val vRow = vPlane.rowStride; val uRow = uPlane.rowStride
        val vPix = vPlane.pixelStride; val uPix = uPlane.pixelStride
        pos = ySize
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                nv21[pos++] = vBuf.get(row * vRow + col * vPix)
                nv21[pos++] = uBuf.get(row * uRow + col * uPix)
            }
        }
        val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val baos = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, width, height), 70, baos)
        val bytes = baos.toByteArray()
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (e: Throwable) {
        Log.w(TAG, "yuv", e); null
    }

    private fun duracao(ctx: Context, uri: Uri): Long? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(ctx, uri)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally { runCatching { mmr.release() } }
    }

    private fun ordemAPartirDe(t: Long, start: Long, dur: Long): Long {
        val d = max(1L, dur)
        return ((t - start) % d + d) % d
    }

    private fun temposAPartirDe(dur: Long, passo: Long, startMs: Long): List<Long> {
        if (dur <= 0L || passo <= 0L) return emptyList()
        val base = (startMs / passo) * passo
        val out = ArrayList<Long>()
        var t = base
        val guard = (dur / passo).toInt() + 3
        repeat(guard) {
            val x = if (t > dur) t - ((dur / passo) * passo + passo) else t
            val v = x.coerceIn(0L, dur)
            if (v !in out) out.add(v)
            t += passo
            if (t > dur + passo && out.size >= (dur / passo).toInt()) return@repeat
        }
        if (0L !in out) out.add(0L)
        return out.sortedBy { ordemAPartirDe(it, startMs, dur) }
    }
}
