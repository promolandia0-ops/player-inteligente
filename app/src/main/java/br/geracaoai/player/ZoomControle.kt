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
    private val peitoMem = PeitoRostoMemoria(segurarParte)

    val temTrava: Boolean get() = travado
    val zoomEstaLigado: Boolean get() = zoomLigado

    fun reset() { travado = false; falhas = 0; caixa = null; caixaParte = null; falhasParte = 0; zoomLigado = false; votos = 0; peitoMem.limpar() }

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
            val parte = if (modo == ModoZoom.PEITO_ROSTO) peitoMem.passo(cl) else TamanhoLogica.caixaAlvoModo(modo, cl)
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

/**
 * Memória do modo "Peito + rosto": o alvo é SEMPRE a união dos dois peitos + rosto da mesma mulher.
 * A detecção por ciclo é irregular (às vezes só o peito esquerdo, só o direito, só o rosto) — por isso cada parte
 * (peito esq., peito dir., rosto) guarda a última caixa por [validade] ciclos; o que faltou no ciclo vem da memória.
 * Se só um peito é conhecido, o outro é inferido (espelhado no eixo do rosto, ou alargando a caixa); se não há
 * rosto, estende para cima. Nunca devolve só uma parte. Puro Kotlin.
 */
internal class PeitoRostoMemoria(private val validade: Int = 5, private val margem: Float = 0.22f) {
    private class Slot(var caixa: RectN, var idade: Int = 0)
    private var esq: Slot? = null
    private var dir: Slot? = null
    private var rosto: Slot? = null

    fun limpar() { esq = null; dir = null; rosto = null }

    private fun r(c: TamanhoLogica.Caixa) = RectN(c.x0, c.y0, c.x1, c.y1)

    /** [cl] = aglomerado feminino do ciclo. Devolve a caixa-alvo (com margem) ou null se não há peito conhecido. */
    fun passo(cl: List<CaixaParte>): RectN? {
        val peitos = cl.filter { it.classe == TamanhoLogica.FEMALE_BREAST_EXPOSED || it.classe == TamanhoLogica.FEMALE_BREAST_COVERED }
            .sortedByDescending { it.caixa.sc }.take(2).sortedBy { it.caixa.cx }
        val face = cl.filter { it.classe == TamanhoLogica.FACE_FEMALE }.maxByOrNull { it.caixa.sc }
        // Todas as partes envelhecem; as vistas agora rejuvenescem.
        for (s in listOf(esq, dir, rosto)) s?.let { it.idade++ }
        // Ela andou muito (união atual longe da memória): esquece o passado.
        val atuais = TamanhoLogica.uniaoPartes(peitos + listOfNotNull(face))
        val mem = uniaoMem()
        if (atuais != null && mem != null) {
            val f = 0.5f * maxOf(mem.w, mem.h) + 0.05f
            if (atuais.x1 < mem.x0 - f || atuais.x0 > mem.x1 + f || atuais.y1 < mem.y0 - f || atuais.y0 > mem.y1 + f) limpar()
        }
        face?.let { rosto = Slot(r(it.caixa)) }
        val fx = rosto?.caixa?.cx
        when (peitos.size) {
            2 -> { esq = Slot(r(peitos[0].caixa)); dir = Slot(r(peitos[1].caixa)) }
            1 -> {
                val b = r(peitos[0].caixa)
                val e = esq; val d = dir
                val perto = { o: RectN -> kotlin.math.abs(b.cx - o.cx) <= 0.7f * maxOf(b.w, o.w) }
                when {
                    e != null && d != null -> if (kotlin.math.abs(b.cx - e.caixa.cx) <= kotlin.math.abs(b.cx - d.caixa.cx)) esq = Slot(b) else dir = Slot(b)
                    e != null -> when {
                        perto(e.caixa) -> esq = Slot(b)
                        b.cx > e.caixa.cx -> dir = Slot(b)
                        else -> { dir = e; esq = Slot(b) }       // o conhecido era o direito
                    }
                    d != null -> when {
                        perto(d.caixa) -> dir = Slot(b)
                        b.cx < d.caixa.cx -> esq = Slot(b)
                        else -> { esq = d; dir = Slot(b) }
                    }
                    fx != null && b.cx >= fx -> dir = Slot(b)
                    else -> esq = Slot(b)
                }
            }
        }
        if (esq?.let { it.idade >= validade } == true) esq = null
        if (dir?.let { it.idade >= validade } == true) dir = null
        if (rosto?.let { it.idade >= validade } == true) rosto = null
        val e = esq?.caixa; val d = dir?.caixa
        if (e == null && d == null) return null
        var u: RectN = if (e != null && d != null) uniao(e, d) else {
            val u1 = (e ?: d)!!
            val espelho = fx?.let { c ->
                val cx2 = 2f * c - u1.cx
                val dist = kotlin.math.abs(cx2 - u1.cx)
                if (dist in (0.5f * u1.w)..(3f * u1.w)) RectN(cx2 - u1.w / 2f, u1.y0, cx2 + u1.w / 2f, u1.y1) else null
            } ?: RectN(u1.x0 - u1.w, u1.y0, u1.x1 + u1.w, u1.y1)   // sem eixo: alarga p/ os dois lados
            uniao(u1, espelho)
        }
        val f = rosto?.caixa
        u = if (f != null) uniao(u, f) else RectN(u.x0, u.y0 - 0.6f * u.w, u.x1, u.y1)   // sem rosto: estende p/ cima
        val m = maxOf(u.w, u.h) * margem
        return RectN((u.x0 - m).coerceIn(0f, 0.98f), (u.y0 - m).coerceIn(0f, 0.98f), (u.x1 + m).coerceIn(0.02f, 1f), (u.y1 + m).coerceIn(0.02f, 1f))
    }

    private fun uniao(a: RectN, b: RectN) = RectN(minOf(a.x0, b.x0), minOf(a.y0, b.y0), maxOf(a.x1, b.x1), maxOf(a.y1, b.y1))
    private fun uniaoMem(): RectN? = listOfNotNull(esq?.caixa, dir?.caixa, rosto?.caixa).reduceOrNull { a, b -> uniao(a, b) }
}
