package br.geracaoai.player

import br.geracaoai.player.TamanhoLogica.CaixaParte
import br.geracaoai.player.TamanhoLogica.RectN
import br.geracaoai.player.TamanhoLogica.ZoomAlvo

/**
 * Decide o que o Zoom automático faz a cada ciclo (~400 ms), de forma ESTÁVEL:
 *  - só considera o melhor aglomerado FEMININO (uma pessoa) — nada de união com outra pessoa/fundo;
 *  - trava na mulher e segura o último enquadramento por [maxFalhas] ciclos sem detecção (≈2 s);
 *  - suaviza a caixa (EMA) e usa histerese: liga o zoom com ela ocupando < 55% do viewport e só desliga > 75%;
 *  - nenhuma troca por um único quadro: ligar/desligar exige [confirmar] ciclos seguidos.
 * Modos tight (Buceta/Bunda/Peito+rosto): enquadram a parte; sem ela, seguram ~2 s e voltam ao corpo inteiro.
 * Puro Kotlin (sem Android) → testável na JVM.
 */
class ZoomControle(
    private val maxFalhas: Int = 5,
    private val ema: Float = 0.6f,
    private val confirmar: Int = 2,
    private val segurarParte: Int = 5,
) {
    enum class Estado { PROCURANDO, SEGUINDO, OCUPA_QUADRO }
    /** [alvo] null = manter o enquadramento atual. */
    class Saida(val alvo: ZoomAlvo?, val estado: Estado)

    companion object {
        const val ENTRAR = 0.55f
        const val SAIR = 0.75f
        const val MAX_ZOOM_CORPO = 2.5f
        const val MAX_ZOOM_PARTE = 4f
        private val UM = ZoomAlvo(1f, 0f, 0f)
    }

    private var travado = false
    private var falhas = 0
    private var caixa: RectN? = null
    private var caixaParte: RectN? = null
    private var falhasParte = 0
    private var zoomLigado = false
    private var votos = 0

    val temTrava: Boolean get() = travado
    val zoomEstaLigado: Boolean get() = zoomLigado

    fun reset() { travado = false; falhas = 0; caixa = null; caixaParte = null; falhasParte = 0; zoomLigado = false; votos = 0 }

    private fun suavizar(ant: RectN?, novo: RectN): RectN {
        if (ant == null) return novo
        // Ela "pulou" de lugar (andou muito / outra pessoa): vai direto, sem arrastar o passado.
        if (kotlin.math.abs(ant.cx - novo.cx) > 0.2f || kotlin.math.abs(ant.cy - novo.cy) > 0.2f) return novo
        fun m(a: Float, b: Float) = a + (b - a) * ema
        return RectN(m(ant.x0, novo.x0), m(ant.y0, novo.y0), m(ant.x1, novo.x1), m(ant.y1, novo.y1))
    }

    fun passo(partes: List<CaixaParte>, modo: ModoZoom, vw: Float, vh: Float, cw: Float, ch: Float): Saida {
        if (modo == ModoZoom.DESLIGADO) { reset(); return Saida(null, Estado.PROCURANDO) }
        val cl = TamanhoLogica.aglomeradoFeminino(partes).takeIf { TamanhoLogica.valido(it) }
        if (cl == null) {
            falhas++
            if (!travado) return Saida(null, Estado.PROCURANDO)
            if (falhas < maxFalhas) return Saida(null, Estado.SEGUINDO)   // segura o último enquadramento
            reset()
            return Saida(UM, Estado.PROCURANDO)
        }
        falhas = 0; travado = true
        val corpo = TamanhoLogica.caixaCorpoPadded(cl) ?: return Saida(null, Estado.SEGUINDO)
        caixa = suavizar(caixa, corpo)

        if (modo != ModoZoom.CORPO) {
            val parte = TamanhoLogica.caixaAlvoModo(modo, cl)
            if (parte != null) {
                falhasParte = 0
                val cp = suavizar(caixaParte, parte); caixaParte = cp
                val a = TamanhoLogica.alvoParaCaixa(cp, vw, vh, cw, ch, MAX_ZOOM_PARTE)
                return Saida(if (a.zoom < 1.1f) UM else a, Estado.SEGUINDO)
            }
            falhasParte++
            if (caixaParte != null && falhasParte < segurarParte) return Saida(null, Estado.SEGUINDO)  // segura ~2 s
            caixaParte = null   // sumiu de vez: volta ao corpo inteiro
        }
        return corpoInteiro(caixa!!, vw, vh, cw, ch)
    }

    private fun corpoInteiro(box: RectN, vw: Float, vh: Float, cw: Float, ch: Float): Saida {
        val occ = TamanhoLogica.ocupacao(box, vw, vh, cw, ch)
        val quer = if (zoomLigado) occ <= SAIR else occ < ENTRAR
        if (quer == zoomLigado) votos = 0 else if (++votos >= confirmar) { zoomLigado = quer; votos = 0 }
        if (zoomLigado) {
            val a = TamanhoLogica.alvoParaCaixa(box, vw, vh, cw, ch, MAX_ZOOM_CORPO)
            return Saida(a, Estado.SEGUINDO)
        }
        if (votos > 0) return Saida(null, Estado.SEGUINDO)          // confirmando (não troca em 1 quadro)
        return if (occ >= SAIR) Saida(UM, Estado.OCUPA_QUADRO) else Saida(UM, Estado.SEGUINDO)
    }
}
