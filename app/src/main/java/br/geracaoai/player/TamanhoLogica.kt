package br.geracaoai.player

/**
 * Lógica pura das pastas Por tamanho (Peituda/…): só mulheres.
 * Índices NudeNet 320n (YOLOv8, 18 classes).
 */
object TamanhoLogica {
    const val FACE_FEMALE = 1
    const val BUTTOCKS_EXPOSED = 2
    const val FEMALE_BREAST_EXPOSED = 3
    const val FEMALE_GENITALIA_EXPOSED = 4
    const val MALE_BREAST_EXPOSED = 5
    const val FACE_MALE = 12
    const val MALE_GENITALIA_EXPOSED = 14

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
        val areaPeito: Float = 0f,
        val areaBunda: Float = 0f,
        val areaBuceta: Float = 0f,
    )

    /** Homem dominante: genitália/peito masculino ou FACE_MALE acima das evidências femininas. */
    fun masculinoDominante(e: Evidencia, limiar: Float = 0.35f): Boolean {
        if (e.maleGen >= limiar) return true
        if (e.maleBreast >= limiar && e.femaleBreast < limiar && e.femaleGen < limiar) return true
        val fem = maxOf(e.faceFemale, e.femaleBreast, e.femaleGen)
        if (e.faceMale >= limiar && e.faceMale > e.faceFemale && fem < limiar) return true
        return false
    }

    fun evidenciaFeminina(e: Evidencia, limiar: Float = 0.35f): Boolean {
        return e.faceFemale >= limiar || e.femaleBreast >= limiar || e.femaleGen >= limiar
    }

    /**
     * Aceita o vídeo para o alvo (só mulheres).
     * Peito/buceta: exige a classe feminina correspondente.
     * Bunda: BUTTOCKS_EXPOSED + evidência feminina e sem macho dominante.
     */
    fun aceita(alvo: Alvo, e: Evidencia, limiar: Float = 0.35f): Boolean {
        if (masculinoDominante(e, limiar)) return false
        return when (alvo) {
            Alvo.PEITUDA, Alvo.PEITINHO ->
                e.femaleBreast >= limiar && e.areaPeito > 0f
            Alvo.BUCETUDA, Alvo.BUCETINHA ->
                e.femaleGen >= limiar && e.areaBuceta > 0f
            Alvo.RABUDA, Alvo.RABINHO ->
                e.buttocks >= limiar && e.areaBunda > 0f && evidenciaFeminina(e, limiar)
        }
    }

    fun areaDoAlvo(alvo: Alvo, e: Evidencia): Float = when (alvo) {
        Alvo.PEITUDA, Alvo.PEITINHO -> e.areaPeito
        Alvo.RABUDA, Alvo.RABINHO -> e.areaBunda
        Alvo.BUCETUDA, Alvo.BUCETINHA -> e.areaBuceta
    }

    /** Junta evidências de vários frames (máximos). */
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
    )

    /** -1 = medido e rejeitado/sem match; 0 = ainda não medido; >0 = área útil. */
    fun areaPendente(area: Float) = area == 0f
}
