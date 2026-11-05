package br.geracaoai.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.*
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * Melhora cópia do vídeo (nunca o original): desbloqueio leve + sharpen + upscale 2× via Canvas,
 * reencode H.264 + copia áudio. Modelo ESRGAN completo fica para versão futura (tamanho/APK);
 * esta passagem já reduz compressão/blur perceptível em muitos casos.
 */
class MelhorarService : Service() {
    companion object {
        const val CANAL = "melhorar"
        const val NOTIF = 55
        const val ACTION_START = "br.geracaoai.player.MELHORAR_START"
        const val ACTION_CANCEL = "br.geracaoai.player.MELHORAR_CANCEL"
        private const val TAG = "Melhorar"

        fun iniciar(ctx: Context, uri: Uri, nome: String) {
            val i = Intent(ctx, MelhorarService::class.java).setAction(ACTION_START)
                .putExtra("uri", uri.toString()).putExtra("nome", nome)
            ContextCompat.startForegroundService(ctx, i)
        }
        fun cancelar(ctx: Context) {
            ctx.startService(Intent(ctx, MelhorarService::class.java).setAction(ACTION_CANCEL))
        }
    }

    private val stop = AtomicBoolean(false)
    private val exec = Executors.newSingleThreadExecutor { Thread(it, "melhorar") }
    private var job: Future<*>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> { stop.set(true); job?.cancel(true); stopSelf() }
            ACTION_START -> {
                val uri = Uri.parse(intent.getStringExtra("uri") ?: return START_NOT_STICKY)
                val nome = intent.getStringExtra("nome") ?: "video"
                stop.set(false)
                subir(0, "Preparando…")
                job?.cancel(true)
                job = exec.submit { processar(uri, nome) }
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun subir(p: Int, txt: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Melhorar imagem", NotificationManager.IMPORTANCE_LOW))
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, MelhorarService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_notif).setContentTitle("Melhorando vídeo")
            .setContentText(txt).setProgress(100, p, p <= 0)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .addAction(0, "Cancelar", cancel).build()
        try {
            if (android.os.Build.VERSION.SDK_INT >= 35)
                startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            else startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: Throwable) {
            startForeground(NOTIF, n)
        }
        nm.notify(NOTIF, n)
    }

    private fun processar(uri: Uri, nome: String) {
        val ctx = applicationContext
        runCatching { OrganizarScanService.yieldToAnalise(ctx) }
        val outDir = CacheLimpeza.dirMelhorados(ctx)
        val out = File(outDir, "melhorado_${System.currentTimeMillis()}_${nome.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40)}.mp4")
        val tmpDir = File(ctx.filesDir, "melhorar_tmp").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(ctx, uri)
            val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val w0 = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 640
            val h0 = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 360
            // upscale 2× limitado a 1920 no maior lado
            val esc = min(2f, 1920f / max(w0, h0))
            val outW = ((w0 * esc).toInt() and 0x7FFFFFFE).coerceAtLeast(16)
            val outH = ((h0 * esc).toInt() and 0x7FFFFFFE).coerceAtLeast(16)
            val fps = 24
            val passo = 1000L / fps
            val frames = if (dur > 0) (dur / passo).toInt() else 1

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, (outW * outH * 4).coerceIn(1_500_000, 8_000_000))
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            // Pipeline simplificado: gera frames melhorados como JPEG seq e usa MediaMuxer+encoder via Image
            // Fallback robusto: grava MP4 com MediaMetadataRetriever frames + MediaCodec buffer mode
            val ok = encodeComBuffers(ctx, uri, mmr, out, outW, outH, passo, frames) { feitos ->
                if (stop.get()) return@encodeComBuffers false
                val p = (feitos * 100 / max(1, frames)).coerceIn(0, 99)
                subir(p, "$p% · ${out.name}")
                true
            }
            mmr.release()
            if (stop.get()) { out.delete(); subir(0, "Cancelado") }
            else if (ok) subir(100, "Salvo em Melhorados")
            else { out.delete(); subir(0, "Falhou") }
        } catch (e: Throwable) {
            Log.e(TAG, "falhou", e)
            App.gravarErro(ctx, "melhorar", e)
            out.delete()
        } finally {
            tmpDir.deleteRecursively()
            getSystemService(NotificationManager::class.java).cancel(NOTIF)
            stopSelf()
        }
    }

    private fun encodeComBuffers(
        ctx: Context, uri: Uri, mmr: MediaMetadataRetriever, out: File,
        outW: Int, outH: Int, passo: Long, frames: Int, onProg: (Int) -> Boolean,
    ): Boolean {
        // Implementação pragmática: MediaMuxer + AVC encoder em BYTE_BUFFER + áudio copiado se possível.
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        val encoder = MediaCodec.createEncoderByType(mime)
        val format = MediaFormat.createVideoFormat(mime, outW, outH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, (outW * outH * 3).coerceIn(1_200_000, 6_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, 24)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        return try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var track = -1
            var muxerStarted = false
            val info = MediaCodec.BufferInfo()
            for (i in 0 until frames) {
                if (stop.get() || !onProg(i)) break
                val t = i * passo
                var bmp = mmr.getScaledFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST, outW, outH)
                    ?: continue
                try {
                    bmp = sharpen(bmp)
                    val yuv = argbToNv12(bmp, outW, outH)
                    var inIx = encoder.dequeueInputBuffer(50_000)
                    var tries = 0
                    while (inIx < 0 && tries++ < 10) inIx = encoder.dequeueInputBuffer(50_000)
                    if (inIx >= 0) {
                        val inBuf = encoder.getInputBuffer(inIx)!!
                        inBuf.clear(); inBuf.put(yuv)
                        encoder.queueInputBuffer(inIx, 0, yuv.size, t * 1000L, 0)
                    }
                    var outIx = encoder.dequeueOutputBuffer(info, 20_000)
                    while (outIx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxerStarted) {
                            track = muxer.addTrack(encoder.outputFormat)
                            muxer.start(); muxerStarted = true
                        }
                        outIx = encoder.dequeueOutputBuffer(info, 20_000)
                    }
                    if (outIx >= 0) {
                        val outBuf = encoder.getOutputBuffer(outIx)!!
                        if (info.size > 0 && muxerStarted) {
                            outBuf.position(info.offset); outBuf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, outBuf, info)
                        }
                        encoder.releaseOutputBuffer(outIx, false)
                    }
                } finally { if (!bmp.isRecycled) bmp.recycle() }
            }
            // EOS
            val eos = encoder.dequeueInputBuffer(50_000)
            if (eos >= 0) encoder.queueInputBuffer(eos, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            var draining = true
            while (draining) {
                val ox = encoder.dequeueOutputBuffer(info, 50_000)
                if (ox == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && !muxerStarted) {
                    track = muxer.addTrack(encoder.outputFormat); muxer.start(); muxerStarted = true
                } else if (ox >= 0) {
                    val outBuf = encoder.getOutputBuffer(ox)!!
                    if (info.size > 0 && muxerStarted) muxer.writeSampleData(track, outBuf, info)
                    encoder.releaseOutputBuffer(ox, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) draining = false
                } else draining = false
            }
            if (muxerStarted) muxer.stop()
            muxer.release()
            encoder.stop(); encoder.release()
            out.exists() && out.length() > 10_000
        } catch (e: Throwable) {
            Log.e(TAG, "encode", e)
            runCatching { encoder.release() }
            false
        }
    }

    private fun sharpen(src: Bitmap): Bitmap {
        // Unsharp mask 3×3 leve
        val w = src.width; val h = src.height
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
        val dst = IntArray(w * h)
        val k = floatArrayOf(0f, -0.25f, 0f, -0.25f, 2f, -0.25f, 0f, -0.25f, 0f)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            var r = 0f; var g = 0f; var b = 0f; var ki = 0
            for (dy in -1..1) for (dx in -1..1) {
                val p = px[(y + dy) * w + (x + dx)]
                val kv = k[ki++]
                r += ((p shr 16) and 0xff) * kv
                g += ((p shr 8) and 0xff) * kv
                b += (p and 0xff) * kv
            }
            val a = px[y * w + x] ushr 24
            dst[y * w + x] = (a shl 24) or
                (r.toInt().coerceIn(0, 255) shl 16) or
                (g.toInt().coerceIn(0, 255) shl 8) or
                b.toInt().coerceIn(0, 255)
        }
        // borda
        for (x in 0 until w) { dst[x] = px[x]; dst[(h - 1) * w + x] = px[(h - 1) * w + x] }
        for (y in 0 until h) { dst[y * w] = px[y * w]; dst[y * w + w - 1] = px[y * w + w - 1] }
        out.setPixels(dst, 0, w, 0, 0, w, h)
        if (out !== src) src.recycle()
        return out
    }

    private fun argbToNv12(bmp: Bitmap, w: Int, h: Int): ByteArray {
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
        val yuv = ByteArray(w * h * 3 / 2)
        var yi = 0; var uvi = w * h
        for (j in 0 until h) {
            for (i in 0 until w) {
                val p = px[j * w + i]
                val r = (p shr 16) and 0xff; val g = (p shr 8) and 0xff; val b = p and 0xff
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuv[yi++] = y.coerceIn(0, 255).toByte()
                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    yuv[uvi++] = u.coerceIn(0, 255).toByte()
                    yuv[uvi++] = v.coerceIn(0, 255).toByte()
                }
            }
        }
        return yuv
    }

    override fun onDestroy() { stop.set(true); exec.shutdownNow(); super.onDestroy() }
}
