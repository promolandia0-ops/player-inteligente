package br.geracaoai.player

/**
 * "Pessoas": agrupamento por rosto (puro, testável). Conservador: prefere SEPARAR a juntar errado.
 * Limiares calibrados no LFW (150 pessoas, SFace int8): impostores chegam a ~0,56 em fotos soltas, mas com a média de
 * 2–3 quadros do mesmo vídeo ("trilha") e centroide da pessoa, 0,55 deu 0 atribuições erradas (0,45 deu ~9 em 300).
 */
object Agrupamento {
    /** Trilha → pessoa: similaridade (cosseno) com o centroide da pessoa. */
    const val LIMIAR_ATRIBUIR = 0.55f
    /** Dentro do MESMO vídeo, rostos com esta similaridade ou mais são a mesma pessoa. */
    const val LIMIAR_TRILHA = 0.55f
    /** Trilha de 1 só quadro só vale se o detector tinha certeza. */
    const val SCORE_TRILHA_UNICA = 0.90f
    /** Pessoa só aparece na galeria com este nº de vídeos. */
    const val MIN_VIDEOS = 2

    class Obs(val emb: FloatArray, val score: Float, val nitidez: Float, val ref: Int)
    class Trilha(val emb: FloatArray, val obs: List<Obs>) {
        /** Melhor observação (score × nitidez) — vira a miniatura. */
        val melhor: Obs get() = obs.maxByOrNull { it.score * (1f + it.nitidez / 200f) }!!
        val qualidade: Float get() = obs.sumOf { it.score.toDouble() }.toFloat() / obs.size * kotlin.math.min(obs.size, 4)
    }

    /** Ordem em que os 8 quadros do vídeo são lidos: espalhada (passo 3 mod 8), p/ parar cedo sem pegar só o começo. */
    val ORDEM_QUADROS = intArrayOf(2, 5, 0, 3, 6, 1, 4, 7)
    /** Sem NENHUM rosto bom nestes quadros espalhados, o vídeo provavelmente não tem rosto: para. */
    const val QUADROS_SEM_ROSTO = 5
    /** Rostos bons suficientes (em quadros diferentes) p/ fechar a trilha. */
    const val ROSTOS_SUFICIENTES = 3

    /**
     * Leitura adaptativa de um vídeo: para quando há ≥ [ROSTOS_SUFICIENTES] observações e TODA pessoa vista tem ≥ 2
     * (uma pessoa só vista 1× continua sendo procurada nos quadros restantes), ou quando [lidos] quadros não achou rosto algum.
     */
    fun podeParar(obs: List<Obs>, lidos: Int): Boolean {
        if (obs.isEmpty()) return lidos >= QUADROS_SEM_ROSTO
        if (obs.size < ROSTOS_SUFICIENTES) return false
        return agrupar(obs).all { it.obs.size >= 2 }
    }

    /** Junta as observações de UM vídeo em trilhas (gulosa, pelo centroide da trilha) e descarta as fracas. */
    fun tracar(obs: List<Obs>): List<Trilha> = agrupar(obs).filter { it.obs.size >= 2 || it.obs[0].score >= SCORE_TRILHA_UNICA }

    /** Agrupamento sem filtro de qualidade (usado p/ decidir quando parar). */
    fun agrupar(obs: List<Obs>): List<Trilha> {
        val grupos = ArrayList<MutableList<Obs>>(); val somas = ArrayList<FloatArray>()
        for (o in obs.sortedByDescending { it.score }) {
            var melhor = -1; var bs = LIMIAR_TRILHA
            for (i in grupos.indices) { val s = RostoMat.cos(RostoMat.normalizar(somas[i]), o.emb); if (s >= bs) { bs = s; melhor = i } }
            if (melhor < 0) { grupos += mutableListOf(o); somas += o.emb.copyOf() }
            else { grupos[melhor] += o; val sm = somas[melhor]; for (k in sm.indices) sm[k] += o.emb[k] }
        }
        return grupos.indices.map { Trilha(RostoMat.normalizar(somas[it]), grupos[it]) }
    }

    /** Pessoa existente mais parecida (≥ [LIMIAR_ATRIBUIR]) ou null → cria pessoa nova. [somas] = soma dos embeddings por pessoa. */
    fun melhorPessoa(somas: Map<Long, FloatArray>, emb: FloatArray): Long? {
        var id: Long? = null; var bs = LIMIAR_ATRIBUIR
        for ((p, s) in somas) { val c = RostoMat.cos(RostoMat.normalizar(s), emb); if (c >= bs) { bs = c; id = p } }
        return id
    }
}
