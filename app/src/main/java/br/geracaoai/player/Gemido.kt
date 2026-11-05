package br.geracaoai.player

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Detector de gemido/respiração pesada via YAMNet (AudioSet) + heurística de energia.
 * AudioSet NÃO tem "gemido sexual" explícito — usamos classes próximas (Wail/moan, Groan,
 * Sigh, Gasp, Breathing, Pant, Whimper, Sob) e exigimos energia/variação. Expectativa:
 * muitos falsos positivos em música/drama e falsos negativos em gemidos baixos.
 */
object Gemido {
    private const val TAG = "Gemido"
    private const val SR = 16000
    private const val WIN = 15600 // 0.975s @ 16k — entrada YAMNet
    // Índices AudioSet / YAMNet (yamnet_class_map.csv)
    private val CLASSES = intArrayOf(
        23,  // Baby cry, infant cry (às vezes)
        24,  // Whimper
        25,  // Wail, moan
        26,  // Sigh
        27,  // Singing (ruído — usamos baixo peso)
        30,  // Groan
        31,  // Grunt
        32,  // Yawn
        37,  // Gasp
        38,  // Pant
        39,  // Snore (evitar)
        42,  // Breathing
        0,   // Speech — penaliza
    )
    private val PESO = floatArrayOf(0.3f, 0.8f, 1.2f, 0.7f, -0.5f, 1.0f, 0.5f, 0.2f, 0.9f, 1.1f, -0.8f, 0.6f, -1.0f)

    fun analisar(ctx: Context, uri: Uri, durMs: Long, passoMs: Long = 1000L): FloatArray {
        val n = ((durMs / passoMs).toInt() + 1).coerceAtLeast(1)
        val out = FloatArray(n)
        val interp = try { abrir(ctx) } catch (e: Throwable) {
            Log.w(TAG, "YAMNet indisponível", e); return out
        }
        try {
            val pcm = decodificarPcm16k(ctx, uri) ?: return out
            val scores = Array(1) { FloatArray(521) }
            val input = Array(1) { FloatArray(WIN) }
            for (i in 0 until n) {
                val t0 = ((i * passoMs) * SR / 1000L).toInt()
                if (t0 >= pcm.size) break
                var energy = 0.0
                for (k in 0 until WIN) {
                    val s = if (t0 + k < pcm.size) pcm[t0 + k] else 0f
                    input[0][k] = s
                    energy += s * s
                }
                energy = sqrt(energy / WIN)
                interp.run(input, scores)
                var gem = 0f
                for (j in CLASSES.indices) {
                    val idx = CLASSES[j]
                    if (idx in scores[0].indices) gem += PESO[j] * scores[0][idx]
                }
                // energia: gemidos costumam ter RMS moderado-alto
                val eBoost = when {
                    energy < 0.01 -> 0.3f
                    energy < 0.04 -> 0.7f
                    energy < 0.2 -> 1.2f
                    else -> 0.8f // gritaria/música alta
                }
                out[i] = (gem * eBoost).coerceIn(0f, 1f)
            }
        } finally {
            runCatching { interp.close() }
        }
        return out
    }

    private fun abrir(ctx: Context): Interpreter {
        val fd = ctx.assets.openFd("audio/yamnet.tflite")
        FileInputStream(fd.fileDescriptor).channel.use { ch ->
            val map = ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            return Interpreter(map, Interpreter.Options().apply { setNumThreads(2) })
        }
    }

    /** PCM float mono -1..1 @ 16 kHz (pode ser longo — caller em background). */
    fun decodificarPcm16k(ctx: Context, uri: Uri, maxSec: Int = 60 * 90): FloatArray? {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(ctx, uri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val srcSr = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val ch = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            val out = ArrayList<Float>(SR * 60)
            val info = MediaCodec.BufferInfo()
            var inDone = false
            val maxSamples = SR * maxSec
            while (out.size < maxSamples) {
                if (!inDone) {
                    val ix = codec.dequeueInputBuffer(5_000)
                    if (ix >= 0) {
                        val buf = codec.getInputBuffer(ix)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(ix, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            codec.queueInputBuffer(ix, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val ox = codec.dequeueOutputBuffer(info, 5_000)
                if (ox >= 0) {
                    val buf = codec.getOutputBuffer(ox)!!
                    if (info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        // PCM 16-bit
                        val shorts = info.size / 2
                        var i = 0
                        while (i + ch - 1 < shorts && out.size < maxSamples) {
                            var acc = 0
                            for (c in 0 until ch) {
                                acc += buf.short.toInt()
                                i++
                            }
                            val sample = (acc / ch) / 32768f
                            // resample linear simples para 16k
                            // acumula em taxa src; reamostra depois em bloco — aqui: decima/expande
                            out.add(sample)
                        }
                    }
                    codec.releaseOutputBuffer(ox, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else if (ox == MediaCodec.INFO_TRY_AGAIN_LATER && inDone) break
            }
            codec.stop(); codec.release()
            val raw = out.toFloatArray()
            if (srcSr == SR) raw else resample(raw, srcSr, SR)
        } catch (e: Throwable) {
            Log.w(TAG, "pcm", e); null
        } finally {
            runCatching { ex.release() }
        }
    }

    private fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || x.isEmpty()) return x
        val n = (x.size.toLong() * to / from).toInt().coerceAtLeast(1)
        val y = FloatArray(n)
        for (i in 0 until n) {
            val src = i.toDouble() * from / to
            val i0 = src.toInt().coerceIn(0, x.lastIndex)
            val i1 = (i0 + 1).coerceAtMost(x.lastIndex)
            val t = (src - i0).toFloat()
            y[i] = x[i0] * (1 - t) + x[i1] * t
        }
        return y
    }
}
