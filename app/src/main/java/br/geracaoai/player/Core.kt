package br.geracaoai.player

/** Categorias exibidas na UI. classIndex = índice da classe no NudeNet 320n (saída 4+idx). */
enum class Categoria(val rotulo: String, val classIndex: Int, val cor: Long) {
    PEITO("Peito", 3, 0xFFFF5C8A),      // FEMALE_BREAST_EXPOSED
    BUNDA("Bunda", 2, 0xFFFFB300),      // BUTTOCKS_EXPOSED
    BUCETA("Buceta", 4, 0xFFB388FF),    // FEMALE_GENITALIA_EXPOSED
    ANUS("Cuzinho", 6, 0xFF00E5A8),     // ANUS_EXPOSED
    /** Derivada (v1.3.20): buceta E cu expostos juntos no mesmo quadro/janela curta. classIndex -1 = o Detector nunca a pontua; calculada ao carregar a análise. */
    BUNDA_ABERTA("Bunda aberta", -1, 0xFFFF6E40);
}

/**
 * Limiar POR CLASSE na camada de categorias (o Detector não muda). O NudeNet 320n é fraco em ânus exposto: os scores brutos
 * costumam ficar em 0,2–0,4, abaixo do limiar único de 0,45 — a categoria quase nunca aparecia. O ânus passa a valer a
 * partir de [FATOR_ANUS] × o limiar do usuário (0,45 → 0,27); as demais continuam com o limiar inteiro.
 */
object Limiares {
    const val FATOR_ANUS = 0.6f
    fun fator(c: Categoria): Float = if (c == Categoria.ANUS) FATOR_ANUS else 1f
    /** Limiar efetivo da categoria (nunca abaixo de ~0,18 nem acima do limiar base). */
    fun de(c: Categoria, base: Float): Float { val f = fator(c); return if (f >= 1f) base else maxOf(base * f, minOf(base, 0.18f)) }
    /** Score do ânus na escala do limiar base (≥ base ⇔ bruto ≥ limiar efetivo). */
    fun normalizarAnus(v: Float): Float = minOf(1f, v / FATOR_ANUS)
}

/** Categorias derivadas dos scores já salvos (sem mudar o Detector nem o formato da análise). */
object Derivadas {
    /** Janela (ms) em que buceta e cu (ou bunda) contam como "juntos". */
    const val JANELA_MS = 2500L
    /** Caminho "por trás" (buceta + bunda exposta, sem cu detectado) vale um pouco menos que o com cu. */
    const val PESO_BUNDA = 0.85f

    /**
     * Pontuação de "Bunda aberta" por quadro (escala do limiar base):
     *  - com cu: min(buceta, cu normalizado) — buceta e cu no mesmo quadro ou a ≤ [janelaMs];
     *  - por trás: [PESO_BUNDA] × min(buceta, bunda exposta) na mesma janela (o cu quase nunca é detectado de fato).
     * Só quadros que têm buceta, cu ou bunda recebem valor — a cena não se alarga além dos quadros detectados.
     * [tempos] pode estar fora de ordem.
     */
    fun bundaAberta(tempos: LongArray, buceta: FloatArray, anus: FloatArray, bunda: FloatArray? = null, janelaMs: Long = JANELA_MS): FloatArray {
        val n = tempos.size
        val out = FloatArray(n)
        if (n == 0 || buceta.size != n || anus.size != n || (bunda != null && bunda.size != n)) return out
        val an = FloatArray(n) { Limiares.normalizarAnus(anus[it]) }
        val ord = (0 until n).sortedBy { tempos[it] }
        for (k in 0 until n) {
            val i = ord[k]
            var maxB = buceta[i]; var maxA = an[i]; var maxU = bunda?.get(i) ?: 0f
            fun olha(j: Int) { maxB = maxOf(maxB, buceta[j]); maxA = maxOf(maxA, an[j]); if (bunda != null) maxU = maxOf(maxU, bunda[j]) }
            var j = k - 1
            while (j >= 0 && tempos[i] - tempos[ord[j]] <= janelaMs) { olha(ord[j]); j-- }
            j = k + 1
            while (j < n && tempos[ord[j]] - tempos[i] <= janelaMs) { olha(ord[j]); j++ }
            var v = maxOf(minOf(buceta[i], maxA), minOf(an[i], maxB))
            if (bunda != null) v = maxOf(v, PESO_BUNDA * maxOf(minOf(buceta[i], maxU), minOf(bunda[i], maxB)))
            out[i] = v
        }
        return out
    }
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
            a.scores[c]?.let { mesclar(a.tempos, it, c, Limiares.de(c, limiar), intervaloMs, duracaoMs = a.duracaoMs) } ?: emptyList()
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

    /** Espera a galeria liberar (default 3 s — nunca trava a análise). */
    fun esperarScanParar(ctx: android.content.Context, maxMs: Long = 3_000L): Boolean {
        val fim = System.currentTimeMillis() + maxMs
        while (scanRodando(ctx) && System.currentTimeMillis() < fim) Thread.sleep(200)
        return !scanRodando(ctx)
    }

    fun retomarGaleriaSePreciso(ctx: android.content.Context) = runCatching {
        if (playerEstaAberto(ctx) || AnaliseWorker.rodando(ctx)) return@runCatching
        val op = OrgPrefs(ctx)
        if (op.ativo && !op.pausado) { OrganizarScanService.start(ctx, op.bucketId); OrganizarWorker.reagendar(ctx) }
    }
}
