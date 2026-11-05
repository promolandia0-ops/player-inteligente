package br.geracaoai.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.max
import kotlin.math.min

/** Funções puras da melhoria de imagem (testáveis sem MediaCodec). */
object MelhorarLogica {
    fun dimensoesSaida(w0: Int, h0: Int, maxLado: Int = 1920): Pair<Int, Int> {
        val esc = min(2f, maxLado.toFloat() / max(w0, h0).coerceAtLeast(1))
        val outW = ((w0 * esc).toInt() and 0x7FFFFFFE).coerceAtLeast(16)
        val outH = ((h0 * esc).toInt() and 0x7FFFFFFE).coerceAtLeast(16)
        return outW to outH
    }

    fun fpsPara(durMs: Long): Int = when {
        durMs <= 60_000L -> 20
        durMs <= 180_000L -> 15
        else -> 12
    }

    /** Garante bitmap exatamente outW×outH ARGB_8888. */
    fun ajustarTamanho(src: Bitmap, outW: Int, outH: Int): Bitmap {
        if (src.width == outW && src.height == outH && src.config == Bitmap.Config.ARGB_8888) {
            return if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true)
        }
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        c.drawBitmap(src, null, android.graphics.Rect(0, 0, outW, outH), p)
        return out
    }

    /** Unsharp 3×3 mais forte (visível) sem estourar. */
    fun sharpen(src: Bitmap): Bitmap {
        val w = src.width; val h = src.height
        val out = src.copy(Bitmap.Config.ARGB_8888, true) ?: return src
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
        val dst = IntArray(w * h)
        // centro 2.6, vizinhos -0.4 → sharpen perceptível
        val k = floatArrayOf(0f, -0.4f, 0f, -0.4f, 2.6f, -0.4f, 0f, -0.4f, 0f)
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
        for (x in 0 until w) { dst[x] = px[x]; dst[(h - 1) * w + x] = px[(h - 1) * w + x] }
        for (y in 0 until h) { dst[y * w] = px[y * w]; dst[y * w + w - 1] = px[y * w + w - 1] }
        out.setPixels(dst, 0, w, 0, 0, w, h)
        return out
    }

    /** NV12 (YUV420 semi-planar) — layout esperado por COLOR_FormatYUV420SemiPlanar. */
    fun argbToNv12(bmp: Bitmap): ByteArray {
        val w = bmp.width; val h = bmp.height
        require(w % 2 == 0 && h % 2 == 0) { "NV12 exige dimensões pares" }
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
        val yuv = ByteArray(w * h * 3 / 2)
        var yi = 0
        val uvStart = w * h
        for (j in 0 until h) {
            for (i in 0 until w) {
                val p = px[j * w + i]
                val r = (p shr 16) and 0xff; val g = (p shr 8) and 0xff; val b = p and 0xff
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuv[yi++] = y.coerceIn(0, 255).toByte()
                if (j % 2 == 0 && i % 2 == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    val uvi = uvStart + (j / 2) * w + i
                    yuv[uvi] = u.coerceIn(0, 255).toByte()
                    yuv[uvi + 1] = v.coerceIn(0, 255).toByte()
                }
            }
        }
        return yuv
    }

    fun progressoFrac(feitos: Int, total: Int): Float =
        if (total <= 0) 0f else ((feitos + 1).toFloat() / total).coerceIn(0.01f, 0.99f)
}
