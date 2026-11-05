package br.geracaoai.player

/** Categorias exibidas na UI. classIndex = índice da classe no NudeNet 320n (saída 4+idx). */
enum class Categoria(val rotulo: String, val classIndex: Int, val cor: Long) {
    PEITO("Peito", 3, 0xFFFF5C8A),      // FEMALE_BREAST_EXPOSED
    BUNDA("Bunda", 2, 0xFFFFB300),      // BUTTOCKS_EXPOSED
    BUCETA("Buceta", 4, 0xFFB388FF),    // FEMALE_GENITALIA_EXPOSED
    ANUS("Cuzinho", 6, 0xFF00E5A8);     // ANUS_EXPOSED
}

data class Segmento(val categoria: Categoria, val inicioMs: Long, val fimMs: Long, val pico: Float)

/** Resultado bruto da análise: pontuação por frame e por categoria. */
class Analise(val duracaoMs: Long, val tempos: LongArray, val scores: Map<Categoria, FloatArray>, val parcial: Boolean = false)

object Segmentador {
    const val MIN_DUR_MS = 1500L

    /**
     * Junta frames marcados (score >= limiar) em segmentos. Frames marcados separados por
     * até [maxGapMs] viram um único segmento. Fim = último frame marcado + intervalo.
     * Descarta/expande cenas menores que [minDurMs] e funde sobreposições da mesma categoria.
     */
    fun mesclar(
        tempos: LongArray, scores: FloatArray, categoria: Categoria, limiar: Float,
        intervaloMs: Long, maxGapMs: Long = 2000, minDurMs: Long = MIN_DUR_MS, duracaoMs: Long = Long.MAX_VALUE
    ): List<Segmento> {
        val brutos = ArrayList<Segmento>()
        var ini = -1L; var ult = -1L; var pico = 0f
        fun fecha() {
            if (ini >= 0) {
                var fim = minOf(ult + maxOf(intervaloMs, 500L), duracaoMs)
                // garante duração mínima expandindo o fim (sem ultrapassar o vídeo)
                if (fim - ini < minDurMs) fim = minOf(ini + minDurMs, duracaoMs)
                if (fim > ini) brutos.add(Segmento(categoria, ini, fim, pico))
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
        return fundirSobrepostos(brutos)
    }

    /** Funde segmentos da mesma categoria que se tocam/sobreõem. */
    fun fundirSobrepostos(segs: List<Segmento>, folgaMs: Long = 400L): List<Segmento> {
        if (segs.isEmpty()) return segs
        val ord = segs.sortedBy { it.inicioMs }
        val out = ArrayList<Segmento>()
        var cur = ord.first()
        for (i in 1 until ord.size) {
            val s = ord[i]
            if (s.inicioMs <= cur.fimMs + folgaMs) {
                cur = Segmento(cur.categoria, cur.inicioMs, maxOf(cur.fimMs, s.fimMs), maxOf(cur.pico, s.pico))
            } else {
                out.add(cur); cur = s
            }
        }
        out.add(cur)
        return out
    }

    fun segmentos(a: Analise, cats: Set<Categoria>, limiar: Float, intervaloMs: Long): List<Segmento> =
        cats.flatMap { c ->
            a.scores[c]?.let { mesclar(a.tempos, it, c, limiar, intervaloMs, duracaoMs = a.duracaoMs) } ?: emptyList()
        }.sortedBy { it.inicioMs }

    fun alvoDoSalto(s: Segmento, offsetMs: Long) = maxOf(0L, s.inicioMs - offsetMs)

    fun proxima(segs: List<Segmento>, posMs: Long, offsetMs: Long): Segmento? =
        segs.firstOrNull { alvoDoSalto(it, offsetMs) > posMs + 500 }

    fun anterior(segs: List<Segmento>, posMs: Long, offsetMs: Long): Segmento? =
        segs.lastOrNull { alvoDoSalto(it, offsetMs) < posMs - 1500 }
}

/**
 * Exclusão entre galeria (:scan) e player/análise por vídeo (:analise). Arquivos com batida
 * (timestamp) funcionam entre processos e expiram sozinhos se um processo morrer.
 */
object Exclusao {
    private const val VALIDADE_MS = 12_000L
    private fun f(ctx: android.content.Context, n: String) = java.io.File(ctx.filesDir, n)
    private fun bater(ctx: android.content.Context, n: String) = runCatching { f(ctx, n).writeText(System.currentTimeMillis().toString()) }
    private fun vivo(ctx: android.content.Context, n: String) = runCatching {
        System.currentTimeMillis() - f(ctx, n).readText().trim().toLong() < VALIDADE_MS }.getOrDefault(false)

    fun playerAberto(ctx: android.content.Context) = bater(ctx, "lock_player")
    fun playerFechado(ctx: android.content.Context) = runCatching { f(ctx, "lock_player").delete() }
    fun playerEstaAberto(ctx: android.content.Context) = vivo(ctx, "lock_player")

    fun scanBatida(ctx: android.content.Context) = bater(ctx, "lock_scan")
    fun scanFim(ctx: android.content.Context) = runCatching { f(ctx, "lock_scan").delete() }
    fun scanRodando(ctx: android.content.Context) = vivo(ctx, "lock_scan")

    /** Espera a galeria liberar decoder/modelo (no máx. [maxMs]). */
    fun esperarScanParar(ctx: android.content.Context, maxMs: Long = 20_000L): Boolean {
        val fim = System.currentTimeMillis() + maxMs
        while (scanRodando(ctx) && System.currentTimeMillis() < fim) Thread.sleep(250)
        return !scanRodando(ctx)
    }

    /** Retoma a galeria se o usuário não pausou e nada mais está usando o modelo. */
    fun retomarGaleriaSePreciso(ctx: android.content.Context) = runCatching {
        if (playerEstaAberto(ctx) || AnaliseService.rodando(ctx)) return@runCatching
        val op = OrgPrefs(ctx)
        if (op.ativo && !op.pausado) { OrganizarScanService.start(ctx, op.bucketId); OrganizarWorker.reagendar(ctx) }
    }
}
