package br.geracaoai.player

import android.graphics.Bitmap

/**
 * Acha (e acompanha) a mulher no quadro do vídeo para o Zoom automático.
 *
 * Por que existe: o NudeNet 320n só enxerga a mulher quando ela ocupa ~55–70% da altura do quadro — e nesse
 * caso o enquadramento já é ≥ 60% (sem zoom). Ou seja, com uma única passada no quadro inteiro o zoom NUNCA
 * disparava. Aqui o detector roda em recortes QUADRADOS (usam o canvas 320×320 inteiro e ampliam quem está longe):
 *  - acompanhando: 1 recorte em volta da última posição (rápido → segue o movimento);
 *  - procurando: quadro inteiro, depois quadrados grandes, depois quadrados menores (em lotes, p/ não pesar).
 *
 * [detectar] recebe o bitmap (inteiro ou recorte) e devolve caixas no canvas do detector.
 */
class ZoomRastreador(
    private val limiar: Float = LIMIAR,
    private val detectar: (Bitmap, Float) -> List<TamanhoLogica.CaixaParte>,
) {
    companion object {
        /** Mais baixo que o da análise: aqui basta achar rosto/2 partes; falso positivo só gera zoom leve. */
        const val LIMIAR = 0.35f
        private const val LOTE = 4
    }

    enum class Modo { SEGUINDO, QUADRO, QUADRADOS_GRANDES, QUADRADOS_PEQUENOS, NADA }
    class Resultado(val partes: List<TamanhoLogica.CaixaParte>, val modo: Modo, val inferencias: Int)

    private var ultimaCaixa: TamanhoLogica.RectN? = null
    private var proxPequeno = 0

    fun esquecer() { ultimaCaixa = null; proxPequeno = 0 }

    private fun regiao(frame: Bitmap, rg: TamanhoLogica.RectN): Pair<List<TamanhoLogica.CaixaParte>, Int> {
        val inteiro = rg.x0 <= 0f && rg.y0 <= 0f && rg.x1 >= 1f && rg.y1 >= 1f
        if (inteiro) return TamanhoLogica.paraQuadro(detectar(frame, limiar), frame.width, frame.height) to 1
        val x = (rg.x0 * frame.width).toInt().coerceIn(0, frame.width - 2)
        val y = (rg.y0 * frame.height).toInt().coerceIn(0, frame.height - 2)
        val w = ((rg.x1 - rg.x0) * frame.width).toInt().coerceIn(2, frame.width - x)
        val h = ((rg.y1 - rg.y0) * frame.height).toInt().coerceIn(2, frame.height - y)
        val rec = Bitmap.createBitmap(frame, x, y, w, h)
        try {
            val real = TamanhoLogica.RectN(x.toFloat() / frame.width, y.toFloat() / frame.height, (x + w).toFloat() / frame.width, (y + h).toFloat() / frame.height)
            return TamanhoLogica.paraQuadro(detectar(rec, limiar), w, h, real) to 1
        } finally { if (rec !== frame && !rec.isRecycled) rec.recycle() }
    }

    /** Roda cada região e devolve a melhor detecção VÁLIDA (soma dos scores) — não mistura recortes diferentes. */
    private fun varrer(frame: Bitmap, regs: List<TamanhoLogica.RectN>): Pair<List<TamanhoLogica.CaixaParte>, Int> {
        var melhor: List<TamanhoLogica.CaixaParte> = emptyList(); var bs = 0f; var n = 0
        for (rg in regs) {
            val (p, k) = regiao(frame, rg); n += k
            if (!TamanhoLogica.valido(p)) continue
            val s = p.sumOf { it.caixa.sc.toDouble() }.toFloat()
            if (s > bs) { bs = s; melhor = p }
        }
        return melhor to n
    }

    /** Um ciclo de amostragem. [frame] = quadro inteiro do vídeo (não recicla). */
    fun passo(frame: Bitmap): Resultado {
        var inf = 0
        ultimaCaixa?.let { cx ->
            val (p, k) = regiao(frame, TamanhoLogica.recorteQuadradoEm(cx, frame.width, frame.height))
            inf += k
            if (TamanhoLogica.valido(p)) { ultimaCaixa = TamanhoLogica.caixaCorpoPadded(p); return Resultado(p, Modo.SEGUINDO, inf) }
            ultimaCaixa = null   // perdeu: procura de novo já neste ciclo
        }
        val (q, k1) = regiao(frame, TamanhoLogica.RectN(0f, 0f, 1f, 1f)); inf += k1
        if (TamanhoLogica.valido(q)) { ultimaCaixa = TamanhoLogica.caixaCorpoPadded(q); return Resultado(q, Modo.QUADRO, inf) }
        val (g, k2) = varrer(frame, TamanhoLogica.quadrados(frame.width, frame.height, 1f)); inf += k2
        if (TamanhoLogica.valido(g)) { ultimaCaixa = TamanhoLogica.caixaCorpoPadded(g); return Resultado(g, Modo.QUADRADOS_GRANDES, inf) }
        val pequenos = TamanhoLogica.quadrados(frame.width, frame.height, 0.5f)
        if (pequenos.isEmpty()) return Resultado(emptyList(), Modo.NADA, inf)
        val lote = List(minOf(LOTE, pequenos.size)) { pequenos[(proxPequeno + it) % pequenos.size] }
        proxPequeno = (proxPequeno + lote.size) % pequenos.size
        val (s, k3) = varrer(frame, lote); inf += k3
        if (TamanhoLogica.valido(s)) { ultimaCaixa = TamanhoLogica.caixaCorpoPadded(s); return Resultado(s, Modo.QUADRADOS_PEQUENOS, inf) }
        return Resultado(emptyList(), Modo.NADA, inf)
    }
}
