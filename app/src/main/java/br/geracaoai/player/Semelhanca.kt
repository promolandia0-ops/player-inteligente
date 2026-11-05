package br.geracaoai.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import kotlin.math.sqrt

/**
 * Embedding leve 100% offline (sem modelo extra): histograma HSV de pele + mosaico 8×8.
 * Ordenação gulosa por vizinho mais próximo — similar ficam juntos, sem títulos.
 */
object Semelhanca {
    private const val TAG = "Semelhanca"
    const val DIM = 24 /* H */ + 8 /* S */ + 64 /* mosaic */ // = 96
    private const val FRAME_MAX = 160

    fun extrairDeBitmap(bmp: Bitmap): FloatArray {
        val hHist = FloatArray(24)
        val sHist = FloatArray(8)
        val mosaic = FloatArray(64)
        val w = bmp.width; val h = bmp.height
        if (w <= 0 || h <= 0) return FloatArray(DIM)
        var pele = 0
        val step = maxOf(1, minOf(w, h) / 80)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val p = bmp.getPixel(x, y)
                val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                val hsv = FloatArray(3)
                Color.RGBToHSV(r, g, b, hsv)
                // pele aproximada (offline, sem rede)
                val peleOk = hsv[0] in 0f..50f && hsv[1] in 0.15f..0.75f && hsv[2] in 0.25f..0.95f &&
                    r > 60 && r > g && r > b
                if (peleOk) {
                    hHist[(hsv[0] / 60f * 24f).toInt().coerceIn(0, 23)]++
                    sHist[(hsv[1] * 8f).toInt().coerceIn(0, 7)]++
                    pele++
                }
                x += step
            }
            y += step
        }
        // mosaico luminância 8×8 (forma/corpo grosseira)
        for (my in 0 until 8) for (mx in 0 until 8) {
            val x0 = mx * w / 8; val x1 = (mx + 1) * w / 8
            val y0 = my * h / 8; val y1 = (my + 1) * h / 8
            var sum = 0L; var n = 0
            var yy = y0
            while (yy < y1) {
                var xx = x0
                while (xx < x1) {
                    val p = bmp.getPixel(xx, yy)
                    sum += (Color.red(p) + Color.green(p) + Color.blue(p)) / 3
                    n++; xx += step
                }
                yy += step
            }
            mosaic[my * 8 + mx] = if (n > 0) sum.toFloat() / (n * 255f) else 0f
        }
        val out = FloatArray(DIM)
        val ph = if (pele > 0) pele.toFloat() else 1f
        for (i in 0 until 24) out[i] = hHist[i] / ph
        for (i in 0 until 8) out[24 + i] = sHist[i] / ph
        for (i in 0 until 64) out[32 + i] = mosaic[i]
        normalizar(out)
        return out
    }

    /** Média de 3 keyframes; null se não der. */
    fun extrairDeVideo(ctx: Context, uri: Uri): FloatArray? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(ctx, uri)
            val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (dur <= 0L) return null
            val tempos = longArrayOf(dur / 5, dur / 2, (dur * 4) / 5)
            val acc = FloatArray(DIM)
            var n = 0
            for (t in tempos) {
                val bmp = mmr.getScaledFrameAtTime(
                    t * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, FRAME_MAX, FRAME_MAX,
                ) ?: continue
                try {
                    val v = extrairDeBitmap(bmp)
                    for (i in v.indices) acc[i] += v[i]
                    n++
                } finally { if (!bmp.isRecycled) bmp.recycle() }
            }
            if (n == 0) null else {
                for (i in acc.indices) acc[i] /= n
                normalizar(acc); acc
            }
        } catch (e: Throwable) {
            Log.w(TAG, "emb $uri", e); null
        } finally { runCatching { mmr.release() } }
    }

    fun distancia(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        var s = 0f
        for (i in 0 until n) { val d = a[i] - b[i]; s += d * d }
        return s
    }

    /**
     * Ordena para similar ficarem adjacentes (caminho guloso a partir do mais “central”).
     * Estável se embeddings faltarem: quem não tem emb cai no fim por data.
     */
    fun ordenarPorSemelhanca(videos: List<Video>, emb: Map<String, FloatArray>): List<Video> {
        if (videos.size <= 1) return videos
        // DB guarda o embedding pela chave estável (…/external/…), mas o MediaStore lista
        // …/external_primary/… no Android 10+ — sem isso a ordem caía sempre em "por data".
        fun embDe(v: Video): FloatArray? {
            val u = v.uri.toString()
            return emb[VideosMedia.chaveEstavel(u)] ?: emb[u]
        }
        val com = videos.mapNotNull { v -> embDe(v)?.let { v to it } }
        val sem = videos.filter { embDe(it) == null }.sortedByDescending { it.dataMod }
        if (com.isEmpty()) return videos.sortedByDescending { it.dataMod }
        // começa pelo de menor distância média (centro aproximado)
        val resto = com.toMutableList()
        var cur = resto.minBy { (_, e) ->
            resto.sumOf { (_, o) -> distancia(e, o).toDouble() } / resto.size
        }
        resto.remove(cur)
        val out = ArrayList<Video>(videos.size)
        out.add(cur.first)
        while (resto.isNotEmpty()) {
            val ref = cur.second
            val ix = resto.indices.minBy { distancia(ref, resto[it].second) }
            cur = resto.removeAt(ix)
            out.add(cur.first)
        }
        out.addAll(sem)
        return out
    }

    /** Mesmo conteúdo (chaves + vetores)? Evita reordenar O(n²) a cada refresh da UI. */
    fun mesmoConteudo(a: Map<String, FloatArray>, b: Map<String, FloatArray>): Boolean {
        if (a === b) return true
        if (a.size != b.size) return false
        for ((k, v) in a) { val o = b[k] ?: return false; if (!v.contentEquals(o)) return false }
        return true
    }

    private fun normalizar(v: FloatArray) {
        var s = 0f; for (x in v) s += x * x
        val n = sqrt(s)
        if (n > 1e-6f) for (i in v.indices) v[i] /= n
    }

    fun pack(v: FloatArray): ByteArray {
        val b = ByteArray(v.size * 4)
        for (i in v.indices) {
            val bits = java.lang.Float.floatToIntBits(v[i])
            val o = i * 4
            b[o] = (bits ushr 24).toByte(); b[o + 1] = (bits ushr 16).toByte()
            b[o + 2] = (bits ushr 8).toByte(); b[o + 3] = bits.toByte()
        }
        return b
    }

    fun unpack(b: ByteArray?): FloatArray? {
        if (b == null || b.size < 4 || b.size % 4 != 0) return null
        val v = FloatArray(b.size / 4)
        for (i in v.indices) {
            val o = i * 4
            val bits = ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or
                ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)
            v[i] = java.lang.Float.intBitsToFloat(bits)
        }
        return v
    }
}
