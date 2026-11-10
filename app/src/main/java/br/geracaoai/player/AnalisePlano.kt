package br.geracaoai.player

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri

/**
 * Plano de amostragem da "Analisar vídeo" (v1.3.17) — só decide QUAIS quadros ler; o Detector não muda.
 *
 * Antes: 1 quadro a cada 0,5 s (fps=2) com OPTION_CLOSEST (decodifica do keyframe anterior até o quadro exato) →
 * ~0,8 s/quadro → 1 h de vídeo ≈ 100 min. Agora:
 *  1) passada GROSSA a cada 2 s (1 s se Precisão ≥ 3), cada pedido "encaixado" no keyframe mais próximo
 *     (OPTION_CLOSEST_SYNC = decodifica 1 só quadro, sem rebobinar a partir do keyframe anterior) com o tempo REAL
 *     do keyframe gravado; onde não há keyframe perto, cai no OPTION_CLOSEST antigo (exato);
 *  2) passada de REFINO: só entre amostras vizinhas em que alguma tem pontuação perto do limiar, preenche com o
 *     passo fino (1 s; ou o da Precisão ≥ 3) → Melhores momentos e saltos continuam precisos onde há cena.
 *  Quadros quase idênticos ao anterior reaproveitam a pontuação (sem inferência).
 */
object AnalisePlano {
    class Pedido(val tMs: Long, val exato: Boolean)

    fun passoGrosso(fps: Int) = if (fps >= 3) 1000L else 2000L
    fun passoFino(fps: Int) = if (fps >= 3) 1000L / fps.coerceIn(1, 4) else 1000L

    /** Alvos da passada grossa a partir de [startMs] até o fim e depois [0, startMs) (como antes). */
    fun alvosGrossos(startMs: Long, durMs: Long, passo: Long): List<Long> {
        val out = ArrayList<Long>()
        var t = (startMs / passo) * passo
        while (t <= durMs) { out += t.coerceIn(0L, durMs); t += passo }
        t = 0L
        while (t < startMs) { if (t !in out) out += t; t += passo }
        if (out.isEmpty()) out += 0L
        return out
    }

    /** Mais próximo de [t] em [v] ordenado, ou -1 se vazio. */
    private fun proximo(v: LongArray, t: Long): Long {
        if (v.isEmpty()) return -1L
        var lo = 0; var hi = v.size - 1
        while (lo < hi) { val m = (lo + hi) / 2; if (v[m] < t) lo = m + 1 else hi = m }
        val a = v[lo]; val b = if (lo > 0) v[lo - 1] else a
        return if (kotlin.math.abs(a - t) <= kotlin.math.abs(b - t)) a else b
    }

    /**
     * Converte alvos em pedidos: encaixa no keyframe se ele estiver a ≤ [tolMs]; senão pede o quadro exato.
     * [syncs] null = não deu para ler os keyframes → tudo exato (comportamento antigo, mas já com passo maior).
     * Remove repetidos e o que já foi lido ([feitos]).
     */
    fun resolver(alvos: List<Long>, syncs: LongArray?, tolMs: Long, feitos: Set<Long> = emptySet()): List<Pedido> {
        val vistos = HashSet<Long>(feitos); val out = ArrayList<Pedido>()
        for (t in alvos) {
            val s = if (syncs != null) proximo(syncs, t) else -1L
            val p = if (s >= 0 && kotlin.math.abs(s - t) <= tolMs) Pedido(s, false) else Pedido(t, true)
            if (vistos.add(p.tMs)) out += p
        }
        return out
    }

    /**
     * Alvos de refino: entre duas amostras vizinhas (ordenadas por tempo, distância ≤ 2,5× o passo grosso) em que
     * alguma tem score ≥ [limiarAcerto] em qualquer categoria, preenche com o passo fino.
     */
    fun alvosRefino(tempos: LongArray, maxScore: FloatArray, limiarAcerto: Float, passoGrosso: Long, passoFino: Long, durMs: Long): List<Long> {
        val ord = tempos.indices.sortedBy { tempos[it] }
        val out = ArrayList<Long>()
        for (k in 0 until ord.size - 1) {
            val a = ord[k]; val b = ord[k + 1]
            val gap = tempos[b] - tempos[a]
            if (gap <= passoFino * 3 / 2 || gap > passoGrosso * 5 / 2) continue
            if (maxScore[a] < limiarAcerto && maxScore[b] < limiarAcerto) continue
            var t = tempos[a] + passoFino
            while (t < tempos[b] - passoFino / 2 && t <= durMs) { out += t; t += passoFino }
        }
        return out
    }

    /** Assinatura 16×16 (luma) do quadro p/ detectar quadros repetidos. */
    fun assinatura(b: Bitmap): IntArray {
        val w = b.width; val h = b.height
        return IntArray(256) { k ->
            val c = b.getPixel(((k % 16) * 2 + 1) * w / 32, ((k / 16) * 2 + 1) * h / 32)
            ((c shr 16 and 255) * 299 + (c shr 8 and 255) * 587 + (c and 255) * 114) / 1000
        }
    }

    /** Diferença média por amostra < [tol] níveis de 0–255 → mesmo quadro (reaproveita a pontuação). */
    fun quaseIgual(a: IntArray?, b: IntArray, tol: Float = 1.0f): Boolean {
        if (a == null || a.size != b.size) return false
        var s = 0L
        for (i in a.indices) s += kotlin.math.abs(a[i] - b[i])
        return s.toFloat() / a.size < tol
    }

    /** Tempos (ms) dos keyframes do vídeo via MediaExtractor (salta de keyframe em keyframe). null se falhar/demorar. */
    fun lerKeyframes(ctx: Context, uri: Uri, orcamentoMs: Long = 8000L): LongArray? = runCatching {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
            val tr = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: return@runCatching null
            ex.selectTrack(tr)
            val fim = System.currentTimeMillis() + orcamentoMs
            val out = ArrayList<Long>()
            ex.seekTo(0L, MediaExtractor.SEEK_TO_NEXT_SYNC)
            var ult = Long.MIN_VALUE
            while (true) {
                val t = ex.sampleTime
                if (t < 0 || t <= ult) break
                out += t / 1000L; ult = t
                if (System.currentTimeMillis() > fim) return@runCatching null
                ex.seekTo(t + 1L, MediaExtractor.SEEK_TO_NEXT_SYNC)
            }
            if (out.isEmpty()) null else out.toLongArray()
        } finally { runCatching { ex.release() } }
    }.getOrNull()
}
