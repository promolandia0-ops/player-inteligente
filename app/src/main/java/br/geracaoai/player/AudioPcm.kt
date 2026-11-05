package br.geracaoai.player

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Extrai PCM float mono @ 16 kHz de um vídeo/áudio via MediaExtractor + MediaCodec.
 * Corrige falhas comuns que faziam Legenda/Gemido reportar "Sem áudio" em vídeos com som:
 * - KEY_SAMPLE_RATE/CHANNEL_COUNT só no output format
 * - PCM_FLOAT vs PCM_16BIT
 * - drain após EOS (não abortar no 1º TRY_AGAIN_LATER)
 * - faixas audio/… além de aac (mp4a-latm, opus, vorbis…)
 */
object AudioPcm {
    private const val TAG = "AudioPcm"
    const val SR = 16_000

    /**
     * @return PCM mono -1..1 @ 16 kHz, ou null se não houver faixa de áudio / falha dura.
     * Array vazio != null (faixa existe mas silenciosa).
     */
    fun decodificarPcm16k(ctx: Context, uri: Uri, maxSec: Int = 60 * 90): FloatArray? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            ex.setDataSource(ctx, uri, null)
            val track = (0 until ex.trackCount).firstOrNull { i ->
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: return@firstOrNull false
                mime.startsWith("audio/")
            }
            if (track == null) {
                Log.w(TAG, "sem faixa de áudio uri=$uri tracks=${ex.trackCount}")
                return null
            }
            ex.selectTrack(track)
            var format = ex.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            Log.i(TAG, "track=$track mime=$mime fmt=$format")

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var srcSr = format.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, 0)
            var ch = format.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, 1).coerceAtLeast(1)
            var pcmEncoding = format.getIntegerOr(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)

            val out = ArrayList<Float>((SR * 30).coerceAtMost(SR * maxSec))
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var drainIdle = 0
            val maxSamples = SR * maxSec
            val maxLoops = 2_000_000
            var loops = 0

            while (out.size < maxSamples && loops++ < maxLoops) {
                if (!inDone) {
                    val ix = codec.dequeueInputBuffer(10_000)
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

                when (val ox = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (inDone) {
                            drainIdle++
                            if (drainIdle > 8) break
                        }
                    }
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        srcSr = of.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, srcSr).let { if (it > 0) it else srcSr }
                        ch = of.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, ch).coerceAtLeast(1)
                        pcmEncoding = of.getIntegerOr(MediaFormat.KEY_PCM_ENCODING, pcmEncoding)
                        Log.i(TAG, "outFormat sr=$srcSr ch=$ch enc=$pcmEncoding")
                        drainIdle = 0
                    }
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> { /* legado */ }
                    else -> if (ox >= 0) {
                        drainIdle = 0
                        val buf = codec.getOutputBuffer(ox)
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            appendPcm(buf, ch, pcmEncoding, out, maxSamples)
                        }
                        codec.releaseOutputBuffer(ox, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }

            if (srcSr <= 0) srcSr = SR
            val raw = out.toFloatArray()
            Log.i(TAG, "decoded samples=${raw.size} srcSr=$srcSr")
            if (raw.isEmpty()) {
                // Faixa existia mas decoder não produziu — trata como falha (Legenda mostra erro claro)
                return null
            }
            if (srcSr == SR) raw else resample(raw, srcSr, SR)
        } catch (e: Throwable) {
            Log.w(TAG, "pcm uri=$uri", e)
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { ex.release() }
        }
    }

    fun appendPcm(
        buf: ByteBuffer,
        ch: Int,
        encoding: Int,
        out: ArrayList<Float>,
        maxSamples: Int,
    ) {
        buf.order(ByteOrder.nativeOrder())
        val channels = ch.coerceAtLeast(1)
        when (encoding) {
            android.media.AudioFormat.ENCODING_PCM_FLOAT -> {
                val floats = buf.remaining() / 4
                var i = 0
                while (i + channels - 1 < floats && out.size < maxSamples) {
                    var acc = 0f
                    for (c in 0 until channels) {
                        acc += buf.float
                        i++
                    }
                    out.add((acc / channels).coerceIn(-1f, 1f))
                }
            }
            else -> {
                // PCM_16BIT (e fallback)
                val shorts = buf.remaining() / 2
                var i = 0
                while (i + channels - 1 < shorts && out.size < maxSamples) {
                    var acc = 0
                    for (c in 0 until channels) {
                        acc += buf.short.toInt()
                        i++
                    }
                    out.add((acc / channels) / 32768f)
                }
            }
        }
    }

    fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || x.isEmpty() || from <= 0 || to <= 0) return x
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

    /** RMS — útil p/ testes / telemetria (não bloqueia legenda se baixo). */
    fun rms(x: FloatArray): Float {
        if (x.isEmpty()) return 0f
        var s = 0.0
        for (v in x) s += v * v
        return kotlin.math.sqrt(s / x.size).toFloat()
    }

    private fun MediaFormat.getIntegerOr(key: String, default: Int): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(default) else default
}
