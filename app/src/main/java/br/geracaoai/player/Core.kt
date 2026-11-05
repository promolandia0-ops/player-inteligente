package br.geracaoai.player

/** Categorias exibidas na UI. classIndex = índice da classe no NudeNet 320n (saída 4+idx). */
enum class Categoria(val rotulo: String, val classIndex: Int, val cor: Long) {
    PEITO("Peito", 3, 0xFFFF5C8A),      // FEMALE_BREAST_EXPOSED
    BUNDA("Bunda", 2, 0xFFFFB300),      // BUTTOCKS_EXPOSED
    BUCETA("Buceta", 4, 0xFFB388FF);    // FEMALE_GENITALIA_EXPOSED
}

data class Segmento(val categoria: Categoria, val inicioMs: Long, val fimMs: Long, val pico: Float)

/** Resultado bruto da análise: pontuação por frame e por categoria. */
class Analise(val duracaoMs: Long, val tempos: LongArray, val scores: Map<Categoria, FloatArray>, val parcial: Boolean = false)

object Segmentador {
    /**
     * Junta frames marcados (score >= limiar) em segmentos. Frames marcados separados por
     * até [maxGapMs] viram um único segmento. Fim = último frame marcado + intervalo de amostragem.
     */
    fun mesclar(
        tempos: LongArray, scores: FloatArray, categoria: Categoria, limiar: Float,
        intervaloMs: Long, maxGapMs: Long = 2000, minDurMs: Long = 0, duracaoMs: Long = Long.MAX_VALUE
    ): List<Segmento> {
        val out = ArrayList<Segmento>()
        var ini = -1L; var ult = -1L; var pico = 0f
        fun fecha() {
            if (ini >= 0) {
                val fim = minOf(ult + intervaloMs, duracaoMs)
                if (fim - ini >= minDurMs) out.add(Segmento(categoria, ini, fim, pico))
            }
            ini = -1; ult = -1; pico = 0f
        }
        val idx = tempos.indices.sortedBy { tempos[it] }
        for (i in idx) {
            if (scores[i] < limiar) continue
            val t = tempos[i]
            if (ini >= 0 && t - ult > maxGapMs) fecha()
            if (ini < 0) ini = t
            ult = t; pico = maxOf(pico, scores[i])
        }
        fecha()
        return out
    }

    fun segmentos(a: Analise, cats: Set<Categoria>, limiar: Float, intervaloMs: Long): List<Segmento> =
        cats.flatMap { c -> a.scores[c]?.let { mesclar(a.tempos, it, c, limiar, intervaloMs, duracaoMs = a.duracaoMs) } ?: emptyList() }
            .sortedBy { it.inicioMs }

    fun alvoDoSalto(s: Segmento, offsetMs: Long) = maxOf(0L, s.inicioMs - offsetMs)

    fun proxima(segs: List<Segmento>, posMs: Long, offsetMs: Long): Segmento? =
        segs.firstOrNull { alvoDoSalto(it, offsetMs) > posMs + 500 }

    fun anterior(segs: List<Segmento>, posMs: Long, offsetMs: Long): Segmento? =
        segs.lastOrNull { alvoDoSalto(it, offsetMs) < posMs - 1500 }
}
