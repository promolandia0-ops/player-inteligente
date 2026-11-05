package br.geracaoai.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Melhora cópia do vídeo (nunca o original): sharpen + upscale até 2×, reencode H.264.
 * Saída em getExternalFilesDir/Movies/Melhorados (sem permissão WRITE pública).
 */
object MelhorarProgresso {
    private fun p(ctx: Context) = ctx.getSharedPreferences("melhorar_prog", Context.MODE_PRIVATE)
    fun set(ctx: Context, uriOrig: String, frac: Float, outUri: String? = null) {
        p(ctx).edit().putFloat("p:$uriOrig", frac).putString("o:$uriOrig", outUri ?: "")
            .putLong("t:$uriOrig", System.currentTimeMillis()).apply()
    }
    fun clear(ctx: Context, uriOrig: String) {
        p(ctx).edit().remove("p:$uriOrig").remove("o:$uriOrig").remove("t:$uriOrig").apply()
    }
    fun get(ctx: Context, uriOrig: String): Pair<Float, String?>? {
        val sp = p(ctx)
        if (!sp.contains("p:$uriOrig")) return null
        if (System.currentTimeMillis() - sp.getLong("t:$uriOrig", 0) > 6 * 3600_000L) return null
        val out = sp.getString("o:$uriOrig", "")?.ifBlank { null }
        return sp.getFloat("p:$uriOrig", 0f) to out
    }
}

class MelhorarService : Service() {
    companion object {
        const val CANAL = "melhorar"
        const val NOTIF = 55
        const val ACTION_START = "br.geracaoai.player.MELHORAR_START"
        const val ACTION_CANCEL = "br.geracaoai.player.MELHORAR_CANCEL"
        private const val TAG = "Melhorar"

        fun iniciar(ctx: Context, uri: Uri, nome: String) {
            MelhorarProgresso.set(ctx, uri.toString(), 0.02f)
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
            ACTION_CANCEL -> {
                stop.set(true); job?.cancel(true)
                stopSelf()
            }
            ACTION_START -> {
                val uri = Uri.parse(intent.getStringExtra("uri") ?: return START_NOT_STICKY)
                val nome = intent.getStringExtra("nome") ?: "video"
                stop.set(false)
                subir(2, "Preparando…")
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
            .setContentText(txt).setProgress(100, p.coerceIn(0, 100), false)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .addAction(0, "Cancelar", cancel).build()
        try {
            if (android.os.Build.VERSION.SDK_INT >= 35)
                startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            else startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (_: Throwable) {
            runCatching { startForeground(NOTIF, n) }
        }
        nm.notify(NOTIF, n)
    }

    private fun processar(uri: Uri, nome: String) {
        val ctx = applicationContext
        runCatching { OrganizarScanService.yieldToAnalise(ctx) }
        runCatching { TamanhoScanService.parar(ctx) }
        val outDir = CacheLimpeza.dirMelhorados(ctx)
        if (!outDir.exists() && !outDir.mkdirs()) {
            Log.e(TAG, "mkdir falhou $outDir")
            MelhorarProgresso.clear(ctx, uri.toString())
            App.gravarErro(ctx, "melhorar", IllegalStateException("Não criei pasta $outDir"))
            stopSelf(); return
        }
        val safe = nome.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40).ifBlank { "video" }
        val out = File(outDir, "melhorado_${System.currentTimeMillis()}_$safe.mp4")
        var mmr: MediaMetadataRetriever? = null
        try {
            MelhorarProgresso.set(ctx, uri.toString(), 0.03f)
            subir(3, "Lendo vídeo…")
            mmr = MediaMetadataRetriever()
            mmr.setDataSource(ctx, uri)
            val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val w0 = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 640
            val h0 = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 360
            val (outW, outH) = MelhorarLogica.dimensoesSaida(w0, h0)
            val fps = MelhorarLogica.fpsPara(dur)
            val passo = 1000L / fps
            val frames = if (dur > 0) ((dur / passo).toInt()).coerceAtLeast(1) else 1
            Log.i(TAG, "encode ${w0}x${h0}→${outW}x${outH} frames=$frames fps=$fps out=$out")

            val ok = encode(ctx, mmr, out, outW, outH, passo, frames, uri.toString())
            if (stop.get()) {
                out.delete()
                MelhorarProgresso.clear(ctx, uri.toString())
                subir(0, "Cancelado")
            } else if (ok && out.exists() && out.length() > 5_000) {
                val outUri = Uri.fromFile(out).toString()
                MelhorarProgresso.set(ctx, uri.toString(), 1f, outUri)
                runCatching {
                    android.media.MediaScannerConnection.scanFile(
                        ctx, arrayOf(out.absolutePath), arrayOf("video/mp4"), null,
                    )
                }
                subir(100, "Pronto")
                Log.i(TAG, "ok bytes=${out.length()}")
            } else {
                out.delete()
                MelhorarProgresso.clear(ctx, uri.toString())
                subir(0, "Falhou")
                App.gravarErro(ctx, "melhorar", IllegalStateException("encode retornou false ou arquivo vazio"))
            }
        } catch (e: Throwable) {
            Log.e(TAG, "falhou", e)
            App.gravarErro(ctx, "melhorar", e)
            out.delete()
            MelhorarProgresso.clear(ctx, uri.toString())
        } finally {
            runCatching { mmr?.release() }
            getSystemService(NotificationManager::class.java).cancel(NOTIF)
            stopSelf()
        }
    }

    private fun encode(
        ctx: Context, mmr: MediaMetadataRetriever, out: File,
        outW: Int, outH: Int, passo: Long, frames: Int, uriKey: String,
    ): Boolean {
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        val encoder = MediaCodec.createEncoderByType(mime)
        val format = MediaFormat.createVideoFormat(mime, outW, outH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
            setInteger(MediaFormat.KEY_BIT_RATE, (outW * outH * 4).coerceIn(1_500_000, 8_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, (1000L / passo).toInt().coerceIn(10, 30))
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        var muxer: MediaMuxer? = null
        return try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var track = -1
            var muxerStarted = false
            val info = MediaCodec.BufferInfo()
            var enviados = 0

            fun drain(aguardar: Long = 20_000L) {
                while (true) {
                    when (val ox = encoder.dequeueOutputBuffer(info, aguardar)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> return
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) {
                                track = muxer!!.addTrack(encoder.outputFormat)
                                muxer!!.start()
                                muxerStarted = true
                            }
                        }
                        MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {}
                        else -> if (ox >= 0) {
                            val outBuf = encoder.getOutputBuffer(ox)
                            if (outBuf != null && info.size > 0 && muxerStarted &&
                                info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                            ) {
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                muxer!!.writeSampleData(track, outBuf, info)
                            }
                            encoder.releaseOutputBuffer(ox, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        } else return
                    }
                }
            }

            for (i in 0 until frames) {
                if (stop.get()) break
                val frac = MelhorarLogica.progressoFrac(i, frames)
                MelhorarProgresso.set(ctx, uriKey, frac)
                subir((frac * 100).toInt(), "${(frac * 100).toInt()}%")

                val tUs = i * passo * 1000L
                var bmp: Bitmap? = null
                try {
                    bmp = mmr.getScaledFrameAtTime(
                        tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, outW, outH,
                    ) ?: mmr.getScaledFrameAtTime(
                        tUs, MediaMetadataRetriever.OPTION_CLOSEST, outW, outH,
                    )
                    if (bmp == null) continue
                    var frame = MelhorarLogica.ajustarTamanho(bmp, outW, outH)
                    if (frame !== bmp) { bmp.recycle(); bmp = frame }
                    frame = MelhorarLogica.sharpen(frame)
                    if (frame !== bmp) { bmp.recycle(); bmp = frame }
                    val yuv = MelhorarLogica.argbToNv12(bmp!!)

                    var inIx = encoder.dequeueInputBuffer(100_000)
                    var tries = 0
                    while (inIx < 0 && tries++ < 20 && !stop.get()) {
                        drain(5_000)
                        inIx = encoder.dequeueInputBuffer(100_000)
                    }
                    if (inIx >= 0) {
                        val inBuf = encoder.getInputBuffer(inIx)!!
                        inBuf.clear()
                        if (inBuf.remaining() < yuv.size) {
                            Log.w(TAG, "input buffer pequeno ${inBuf.remaining()} < ${yuv.size}")
                        } else {
                            inBuf.put(yuv)
                            encoder.queueInputBuffer(inIx, 0, yuv.size, tUs, 0)
                            enviados++
                        }
                    }
                    drain(10_000)
                } finally {
                    bmp?.let { if (!it.isRecycled) it.recycle() }
                }
            }

            // EOS
            var eosIx = encoder.dequeueInputBuffer(100_000)
            var t = 0
            while (eosIx < 0 && t++ < 20) {
                drain(20_000); eosIx = encoder.dequeueInputBuffer(100_000)
            }
            if (eosIx >= 0) {
                encoder.queueInputBuffer(eosIx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            var guard = 0
            while (guard++ < 200) {
                val ox = encoder.dequeueOutputBuffer(info, 50_000)
                when {
                    ox == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                    ox == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            track = muxer!!.addTrack(encoder.outputFormat)
                            muxer!!.start(); muxerStarted = true
                        }
                    }
                    ox >= 0 -> {
                        val outBuf = encoder.getOutputBuffer(ox)
                        if (outBuf != null && info.size > 0 && muxerStarted &&
                            info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        ) {
                            outBuf.position(info.offset); outBuf.limit(info.offset + info.size)
                            muxer!!.writeSampleData(track, outBuf, info)
                        }
                        encoder.releaseOutputBuffer(ox, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                    else -> break
                }
            }
            if (muxerStarted) runCatching { muxer!!.stop() }
            runCatching { muxer!!.release() }
            runCatching { encoder.stop() }; runCatching { encoder.release() }
            Log.i(TAG, "enviados=$enviados muxerStarted=$muxerStarted size=${out.length()}")
            muxerStarted && enviados > 0 && out.exists() && out.length() > 5_000
        } catch (e: Throwable) {
            Log.e(TAG, "encode", e)
            runCatching { muxer?.release() }
            runCatching { encoder.release() }
            false
        }
    }

    override fun onDestroy() { stop.set(true); exec.shutdownNow(); super.onDestroy() }
}
