package br.geracaoai.player

/**
 * Lógica pura das pastas Por tamanho (Peituda/…): só mulheres.
 * Índices NudeNet 320n (YOLOv8, 18 classes).
 * Pares (Peituda/Peitinho etc.) são mutuamente exclusivos via split por distribuição.
 */
object TamanhoLogica {
    const val LIMIAR = 0.55f
    const val FACE_FEMALE = 1
    const val BUTTOCKS_EXPOSED = 2
    const val FEMALE_BREAST_EXPOSED = 3
    const val FEMALE_GENITALIA_EXPOSED = 4
    const val MALE_BREAST_EXPOSED = 5
    const val FACE_MALE = 12
    const val MALE_GENITALIA_EXPOSED = 14
    // COVERED — nunca entram nas pastas de tamanho (biquíni/roupa).
    const val FEMALE_GENITALIA_COVERED = 0
    const val FEMALE_BREAST_COVERED = 16
    const val BUTTOCKS_COVERED = 17

    enum class Alvo(val id: String, val rotulo: String, val asc: Boolean) {
        PEITUDA("__peituda", "Peituda", false),
        PEITINHO("__peitinho", "Peitinho", true),
        RABUDA("__rabuda", "Rabuda", false),
        RABINHO("__rabinho", "Rabinho", true),
        BUCETUDA("__bucetuda", "Bucetuda", false),
        BUCETINHA("__bucetinha", "Bucetinha", true);

        companion object {
            fun deId(id: String): Alvo? = entries.firstOrNull { it.id == id }
        }
    }

    data class Evidencia(
        val faceFemale: Float = 0f,
        val faceMale: Float = 0f,
        val maleGen: Float = 0f,
        val maleBreast: Float = 0f,
        val femaleBreast: Float = 0f,
        val femaleGen: Float = 0f,
        val buttocks: Float = 0f,
        /** Métrica bruta normalizada (área caixa / escala do corpo); >0 útil; -1 rejeitado. */
        val areaPeito: Float = 0f,
        val areaBunda: Float = 0f,
        val areaBuceta: Float = 0f,
        val faceArea: Float = 0f,
        val breastCovered: Float = 0f,
        val buttocksCovered: Float = 0f,
        val genCovered: Float = 0f,
        val hitsPeito: Int = 0,
        val hitsBunda: Int = 0,
        val hitsBuceta: Int = 0,
    )

    /** Homem dominante: genitália/peito masculino ou FACE_MALE acima das evidências femininas. */
    fun masculinoDominante(e: Evidencia, limiar: Float = LIMIAR): Boolean {
        if (e.maleGen >= limiar) return true
        if (e.maleBreast >= limiar && e.femaleBreast < limiar && e.femaleGen < limiar) return true
        val fem = maxOf(e.faceFemale, e.femaleBreast, e.femaleGen)
        if (e.faceMale >= limiar && e.faceMale > e.faceFemale && fem < limiar) return true
        return false
    }

    fun evidenciaFeminina(e: Evidencia, limiar: Float = LIMIAR): Boolean {
        return e.faceFemale >= limiar || e.femaleBreast >= limiar || e.femaleGen >= limiar
    }

    /** Só *_EXPOSED conta; COVERED alto sem EXPOSED → rejeita (biquíni). */
    fun soCoberto(alvo: Alvo, e: Evidencia, limiar: Float = LIMIAR): Boolean {
        return when (alvo) {
            Alvo.PEITUDA, Alvo.PEITINHO ->
                e.breastCovered >= limiar && e.femaleBreast < limiar
            Alvo.RABUDA, Alvo.RABINHO ->
                e.buttocksCovered >= limiar && e.buttocks < limiar
            Alvo.BUCETUDA, Alvo.BUCETINHA ->
                e.genCovered >= limiar && e.femaleGen < limiar
        }
    }

    /**
     * Aceita o vídeo para o alvo (só mulheres, *_EXPOSED ≥ limiar, ≥2 frames).
     * Peito/buceta: exige a classe feminina correspondente.
     * Bunda: BUTTOCKS_EXPOSED + evidência feminina e sem macho dominante.
     */
    fun aceita(alvo: Alvo, e: Evidencia, limiar: Float = LIMIAR, minHits: Int = 2): Boolean {
        if (masculinoDominante(e, limiar)) return false
        if (soCoberto(alvo, e, limiar)) return false
        return when (alvo) {
            Alvo.PEITUDA, Alvo.PEITINHO ->
                e.femaleBreast >= limiar && e.areaPeito > 0f && e.hitsPeito >= minHits
            Alvo.BUCETUDA, Alvo.BUCETINHA ->
                e.femaleGen >= limiar && e.areaBuceta > 0f && e.hitsBuceta >= minHits
            Alvo.RABUDA, Alvo.RABINHO ->
                e.buttocks >= limiar && e.areaBunda > 0f && e.hitsBunda >= minHits && evidenciaFeminina(e, limiar)
        }
    }

    fun areaDoAlvo(alvo: Alvo, e: Evidencia): Float = when (alvo) {
        Alvo.PEITUDA, Alvo.PEITINHO -> e.areaPeito
        Alvo.RABUDA, Alvo.RABINHO -> e.areaBunda
        Alvo.BUCETUDA, Alvo.BUCETINHA -> e.areaBuceta
    }

    /** Área da caixa relativa à escala do corpo (rosto); senão fração do frame. */
    fun metricaRelativa(boxArea: Float, faceArea: Float): Float {
        if (boxArea <= 0f) return 0f
        return if (faceArea > 0.0005f) (boxArea / faceArea).coerceIn(0f, 50f) else boxArea
    }

    /** Junta evidências de vários frames (máx scores; soma hits; áreas via listas externas → mediana). */
    fun acumular(a: Evidencia, b: Evidencia) = Evidencia(
        faceFemale = maxOf(a.faceFemale, b.faceFemale),
        faceMale = maxOf(a.faceMale, b.faceMale),
        maleGen = maxOf(a.maleGen, b.maleGen),
        maleBreast = maxOf(a.maleBreast, b.maleBreast),
        femaleBreast = maxOf(a.femaleBreast, b.femaleBreast),
        femaleGen = maxOf(a.femaleGen, b.femaleGen),
        buttocks = maxOf(a.buttocks, b.buttocks),
        areaPeito = maxOf(a.areaPeito, b.areaPeito),
        areaBunda = maxOf(a.areaBunda, b.areaBunda),
        areaBuceta = maxOf(a.areaBuceta, b.areaBuceta),
        faceArea = maxOf(a.faceArea, b.faceArea),
        breastCovered = maxOf(a.breastCovered, b.breastCovered),
        buttocksCovered = maxOf(a.buttocksCovered, b.buttocksCovered),
        genCovered = maxOf(a.genCovered, b.genCovered),
        hitsPeito = a.hitsPeito + b.hitsPeito,
        hitsBunda = a.hitsBunda + b.hitsBunda,
        hitsBuceta = a.hitsBuceta + b.hitsBuceta,
    )

    /** Mediana de valores > 0; 0 se vazio. */
    fun medianaPositiva(xs: List<Float>): Float {
        val v = xs.filter { it > 0f }.sorted()
        if (v.isEmpty()) return 0f
        val m = v.size / 2
        return if (v.size % 2 == 1) v[m] else (v[m - 1] + v[m]) / 2f
    }

    /**
     * Split mutuamente exclusivo: topo ~40% = grande, fundo ~40% = pequeno, meio excluído.
     * Com N<5: mediana (acima = grande, abaixo/igual = pequeno) — ainda disjuntos.
     */
    fun <T> partirPorTamanho(
        scored: List<Pair<T, Float>>,
        topoFrac: Float = 0.40f,
        fundoFrac: Float = 0.40f,
    ): Pair<List<Pair<T, Float>>, List<Pair<T, Float>>> {
        val ok = scored.filter { it.second > 0f }.sortedByDescending { it.second }
        if (ok.isEmpty()) return emptyList<Pair<T, Float>>() to emptyList()
        if (ok.size == 1) return listOf(ok[0]) to emptyList()
        if (ok.size < 5) {
            val med = medianaPositiva(ok.map { it.second })
            val grande = ok.filter { it.second > med }
            val pequeno = ok.filter { it.second <= med }
            // garante disjuntos (já são); se todos iguais, só grande fica com a metade superior por índice
            if (grande.isEmpty() || pequeno.isEmpty()) {
                val mid = ok.size / 2
                return ok.take(mid.coerceAtLeast(1)) to ok.drop(mid.coerceAtLeast(1))
            }
            return grande to pequeno
        }
        val nTopo = (ok.size * topoFrac).toInt().coerceAtLeast(1)
        val nFundo = (ok.size * fundoFrac).toInt().coerceAtLeast(1)
        val grande = ok.take(nTopo)
        val pequeno = ok.takeLast(nFundo)
        // segurança: sem overlap
        val gIds = grande.map { it.first }.toSet()
        val peq = pequeno.filter { it.first !in gIds }
        return grande to peq
    }

    /** -1 = medido e rejeitado/sem match; 0 = ainda não medido; >0 = área útil. */
    fun areaPendente(area: Float) = area == 0f
}
