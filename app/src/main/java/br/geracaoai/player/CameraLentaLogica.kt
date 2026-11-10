package br.geracaoai.player

/**
 * Lógica PURA (sem Android) da "Câmera lenta suave" (v1.3.23): quando ligar, ritmo de apresentação, detecção de corte de cena,
 * guarda de desempenho e política de áudio. O motor de GPU fica em [CameraLentaGl].
 */
object CameraLentaLogica {
    /** Só interpola em velocidade < 1×; a 1× ou mais o caminho é o normal (ExoPlayer → TextureView), intocado. */
    fun ativa(ligado: Boolean, velocidade: Float): Boolean = ligado && velocidade > 0f && velocidade < 0.999f

    /** Áudio: ≥ 0,5× o ExoPlayer já corrige o tom (Sonic); abaixo disso fica picotado/grave → silêncio limpo (só com o recurso ativo). */
    fun volume(velocidade: Float, ativa: Boolean): Float = if (ativa && velocidade < 0.5f) 0f else 1f

    /** Razão custo-do-casamento / contraste acima da qual o fluxo não é confiável (corte de cena, flash, movimento rápido demais). */
    const val LIMIAR_RAZAO = 0.8f
    /** Quadro quase sem textura (preto/liso): a razão é instável e interpolar não causa dano → não conta como corte. */
    const val CONTRASTE_MIN = 0.004f

    fun ehCorte(custo: Float, contraste: Float): Boolean = contraste >= CONTRASTE_MIN && custo / contraste > LIMIAR_RAZAO

    /**
     * Decodifica o buffer RGBA8 16×9 do `stat.frag` (R,G = custo×4 em 16 bits; B,A = contraste×4) → (custo médio, contraste médio).
     */
    fun decodificarEstat(b: ByteArray): Pair<Float, Float> {
        val n = b.size / 4
        if (n == 0) return 0f to 0f
        var c = 0.0; var k = 0.0
        for (i in 0 until n) {
            c += (b[4 * i].toInt() and 255) + (b[4 * i + 1].toInt() and 255) / 255.0
            k += (b[4 * i + 2].toInt() and 255) + (b[4 * i + 3].toInt() and 255) / 255.0
        }
        return (c / n / 255.0 / 4.0).toFloat() to (k / n / 255.0 / 4.0).toFloat()
    }

    /** Larguras da pirâmide de fluxo por nível de desempenho (0 = melhor). */
    fun larguraFluxo(nivel: Int): Int = if (nivel <= 0) 384 else 256

    /** Tamanho dos quadros guardados na GPU: lado maior ≤ 1920 (evita memória/banda demais em 4K). */
    fun tamanhoQuadro(w: Int, h: Int, maxLado: Int = 1920): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return 2 to 2
        val m = maxOf(w, h)
        if (m <= maxLado) return (w and 1.inv()).coerceAtLeast(2) to (h and 1.inv()).coerceAtLeast(2)
        val e = maxLado.toFloat() / m
        return ((w * e).toInt() and 1.inv()).coerceAtLeast(2) to ((h * e).toInt() and 1.inv()).coerceAtLeast(2)
    }

    /**
     * Ritmo de apresentação: cada quadro de origem chega em [chegadaNs]; entre dois quadros o tempo normalizado
     * t = (agora − chegada) / período vai de 0 a 1 (mostra A→B interpolado). O período vem dos PTS do vídeo ÷ velocidade
     * (exato, também para fps variável) ou, sem PTS, da média móvel do intervalo de chegada.
     */
    class Ritmo {
        var periodoNs: Long = 33_333_333L; private set
        var chegadaNs: Long = 0L; private set
        private var ptsAntUs: Long = Long.MIN_VALUE
        private var primeiro = true

        /** Registra um quadro novo. Devolve true se for DESCONTINUIDADE (1º quadro, seek, pausa longa): mostrar direto, sem interpolar. */
        fun novoQuadro(agoraNs: Long, ptsUs: Long?, velocidade: Float): Boolean {
            val v = velocidade.coerceIn(0.05f, 1f)
            val gap = agoraNs - chegadaNs
            val limite = maxOf(4 * periodoNs, 700_000_000L)
            var desc = false
            if (primeiro) { desc = true; periodoNs = (1e9 / 30.0 / v).toLong() }
            else if (ptsUs != null && ptsAntUs != Long.MIN_VALUE) {
                val d = (ptsUs - ptsAntUs) * 1000L
                if (d <= 0L) desc = true
                else {
                    val nom = (d / v).toLong()
                    if (nom > limite) desc = true else if (nom in 4_000_000L..1_500_000_000L) periodoNs = nom
                }
            } else {
                if (gap > limite) desc = true
                else if (gap in 4_000_000L..1_500_000_000L) periodoNs = (0.65 * periodoNs + 0.35 * gap).toLong()
            }
            primeiro = false; chegadaNs = agoraNs; ptsAntUs = ptsUs ?: Long.MIN_VALUE
            return desc
        }

        fun t(agoraNs: Long): Float = ((agoraNs - chegadaNs).toDouble() / periodoNs).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Guarda de desempenho: compara o tempo de cálculo do fluxo de cada par com o período. Ruim [RUINS_SEGUIDOS] vezes seguidas:
     * 1ª vez reduz a resolução do fluxo (nível 1); depois de novo ruim → desliga (volta ao reprodutor normal, sem travar).
     */
    class Guarda {
        enum class Acao { OK, REDUZIR, DESLIGAR }
        var nivel = 0; private set
        private var ruins = 0
        fun registrar(computeNs: Long, periodoNs: Long): Acao {
            if (computeNs > LIMITE_FRACAO * periodoNs) ruins++ else ruins = 0
            if (ruins < RUINS_SEGUIDOS) return Acao.OK
            ruins = 0
            return if (nivel == 0) { nivel = 1; Acao.REDUZIR } else Acao.DESLIGAR
        }
        companion object { const val LIMITE_FRACAO = 0.6; const val RUINS_SEGUIDOS = 4 }
    }
}
