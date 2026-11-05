package br.geracaoai.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.*
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

/**
 * Análise em 2 passagens, a partir da posição de reprodução:
 *  1) grosso (~1 quadro / 2 s, keyframe) — marcas aparecem na hora
 *  2) refino ±2 s em torno dos hits, no fps das preferências
 * Decode e inferência em pipeline (produtor/consumidor). MediaCodec quando possível;
 * fallback MediaMetadataRetriever com OPTION_CLOSEST_SYNC / CLOSEST.
 */
class AnaliseWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object {
        const val NOME = "analise"
        const val CANAL = "analise"
        const val GROSSO_MS = 2000L
        const val LIMIAR_GROSSO = 0.28f
        private const val TAG = "Analise"

        fun iniciar(ctx: Context, uri: Uri, startMs: Long = 0L) {
            // Prioridade: pede ao :scan para ceder (não é pausa do usuário).
            runCatching { OrganizarScanService.yieldToAnalise(ctx) }
            val req = OneTimeWorkRequestBuilder<AnaliseWorker>()
                .setInputData(workDataOf("uri" to uri.toString(), "startMs" to startMs))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NOME, ExistingWorkPolicy.REPLACE, req)
        }
        fun cancelar(ctx: Context) = WorkManager.getInstance(ctx).cancelUniqueWork(NOME)
    }

    private fun notif(p: Int, texto: String) = NotificationCompat.Builder(applicationContext, CANAL)
        .setSmallIcon(R.drawable.ic_notif).setContentTitle("Buscando cenas…").setContentText(texto)
        .setProgress(100, p, false).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
        .addAction(0, "Cancelar", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()

    private fun fg(p: Int, t: String) = ForegroundInfo(42, notif(p, t), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    override suspend fun getForegroundInfo() = fg(0, "Preparando")

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val ctx = applicationContext
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Análise de vídeo", NotificationManager.IMPORTANCE_LOW))
        runCatching { setForeground(fg(0, "Preparando")) }
        val uri = Uri.parse(inputData.getString("uri") ?: return@withContext Result.failure())
        val startMs = inputData.getLong("startMs", 0L).coerceAtLeast(0L)
        val prefs = Prefs(ctx)
        val finoMs = 1000L / prefs.fps.coerceIn(1, 4)
        val chave = Cache.chave(ctx, uri)
        val t0 = System.currentTimeMillis()

        try {
            val dur = duracao(ctx, uri) ?: return@withContext Result.failure()
            val mapa = LinkedHashMap<Long, FloatArray>() // scores por Categoria.ordinal
            fun slot(t: Long) = mapa.getOrPut(t) { FloatArray(Categoria.entries.size) }

            fun persistir(parcial: Boolean) {
                val tempos = mapa.keys.sorted().toLongArray()
                val sc = Categoria.entries.associateWith { c ->
                    FloatArray(tempos.size) { i -> mapa[tempos[i]]?.get(c.ordinal) ?: 0f }
                }
                Cache.salvar(ctx, chave, Analise(dur, tempos, sc, parcial), finoMs)
            }

            suspend fun progresso(fase: String, feitos: Int, total: Int, backend: String) {
                val pf = feitos.toFloat() / max(1, total)
                val p = (pf * 100).toInt().coerceIn(0, 99)
                val el = (System.currentTimeMillis() - t0) / 1000
                val rest = if (feitos > 0) el * (total - feitos) / feitos else 0
                val txt = "$fase · $p% · $backend · ~${rest}s"
                setProgress(workDataOf("p" to pf.coerceIn(0f, 1f), "uri" to uri.toString(), "txt" to txt))
                nm.notify(42, notif(p, txt))
                persistir(true)
            }

            Detector(ctx, prefs.acelerador).use { det ->
                val grossos = temposAPartirDe(dur, GROSSO_MS, startMs)
                val totalEst = grossos.size + grossos.size // refino estimado ≈ grosso
                val feitos = AtomicInteger(0)

                // Passagem 1 — grosso
                pipeline(ctx, uri, grossos, keyframe = true, det) { t, sc ->
                    if (isStopped) return@pipeline false
                    val s = slot(t)
                    for (i in sc.indices) s[i] = max(s[i], sc[i])
                    val n = feitos.incrementAndGet()
                    if (n % 2 == 0 || n == grossos.size) progresso("Rápida", n, totalEst, det.backend)
                    !isStopped
                }
                if (isStopped) { persistir(true); return@withContext Result.failure() }

                // Candidatos + janelas de refino
                val hits = grossos.filter { t -> slot(t).any { it >= LIMIAR_GROSSO } }
                val refinados = LinkedHashSet<Long>()
                for (h in hits) {
                    var t = max(0L, h - GROSSO_MS)
                    val fim = min(dur, h + GROSSO_MS)
                    while (t <= fim) {
                        if (t !in mapa) refinados.add(t)
                        t += finoMs
                    }
                }
                val listaRefino = refinados.sortedBy { ordemAPartirDe(it, startMs, dur) }
                val total2 = feitos.get() + listaRefino.size

                pipeline(ctx, uri, listaRefino, keyframe = false, det) { t, sc ->
                    if (isStopped) return@pipeline false
                    val s = slot(t)
                    for (i in sc.indices) s[i] = max(s[i], sc[i])
                    val n = feitos.incrementAndGet()
                    if (n % 3 == 0 || n >= total2) progresso("Refino", n, max(total2, n), det.backend)
                    !isStopped
                }
            }

            persistir(false)
            val el = (System.currentTimeMillis() - t0) / 1000f
            Log.i(TAG, "ok %.1fs frames=${mapa.size} dur=${dur}ms start=$startMs".format(el))
            setProgress(workDataOf("p" to 1f, "uri" to uri.toString(), "txt" to "Pronto · %.1fs".format(el)))
            retomarGaleriaSePreciso(ctx)
            Result.success(workDataOf("uri" to uri.toString(), "secs" to el))
        } catch (e: Exception) {
            Log.e(TAG, "falhou", e)
            retomarGaleriaSePreciso(ctx)
            Result.failure(workDataOf("erro" to (e.message ?: e.toString())))
        }
    }

    private fun retomarGaleriaSePreciso(ctx: Context) {
        runCatching {
            val op = OrgPrefs(ctx)
            if (op.ativo && !op.pausado) {
                OrganizarScanService.start(ctx, op.bucketId)
                OrganizarWorker.reagendar(ctx)
            }
        }
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
        val ch = Channel<Pair<Long, Bitmap>>(2)
        val prod = CoroutineScope(Dispatchers.IO).async {
            try {
                if (!decodificarMediaCodec(ctx, uri, tempos, keyframe) { t, bmp ->
                        runBlocking { ch.send(t to bmp) }; !isStopped
                    }) {
                    // fallback MMR
                    val mmr = MediaMetadataRetriever()
                    try {
                        mmr.setDataSource(ctx, uri)
                        val opt = if (keyframe) MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                        else MediaMetadataRetriever.OPTION_CLOSEST
                        for (t in tempos) {
                            if (isStopped) break
                            val bmp = mmr.getScaledFrameAtTime(t * 1000, opt, 320, 320) ?: continue
                            ch.send(t to bmp)
                        }
                    } finally { runCatching { mmr.release() } }
                }
            } finally { ch.close() }
        }
        for ((t, bmp) in ch) {
            try {
                val mapa = det.pontuar(bmp)
                val arr = FloatArray(Categoria.entries.size) { i -> mapa[Categoria.entries[i]] ?: 0f }
                if (!onScore(t, arr)) break
            } finally { bmp.recycle() }
        }
        prod.await()
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
        } catch (e: Exception) {
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
    } catch (e: Exception) {
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
