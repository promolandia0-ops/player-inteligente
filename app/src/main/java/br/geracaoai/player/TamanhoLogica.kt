package br.geracaoai.player

/**
 * Lógica pura das pastas Por tamanho (Peituda/…): só mulheres.
 * Índices NudeNet 320n (YOLOv8, 18 classes).
 *
 * Métrica v2: normaliza a caixa da parte por uma escala de corpo no MESMO frame
 * (rosto / barriga / axilas / distância entre peitos). Sem referência → incerto (0).
 * Pares mutuamente exclusivos via split topo/fundo ~30%.
 */
object TamanhoLogica {
    const val METRIC_VERSAO = 2
    const val LIMIAR = 0.55f
    const val FACE_FEMALE = 1
    const val BUTTOCKS_EXPOSED = 2
    const val FEMALE_BREAST_EXPOSED = 3
    const val FEMALE_GENITALIA_EXPOSED = 4
    const val MALE_BREAST_EXPOSED = 5
    const val ANUS_EXPOSED = 6
    const val FEET_EXPOSED = 7
    const val ARMPITS_EXPOSED = 11
    const val FACE_MALE = 12
    const val BELLY_EXPOSED = 13
    const val MALE_GENITALIA_EXPOSED = 14
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

    /** Caixa YOLO normalizada (cx,cy,w,h em 0..1 do input 320). */
    data class Caixa(val sc: Float, val cx: Float, val cy: Float, val w: Float, val h: Float) {
        val area: Float get() = (w * h).coerceIn(0f, 1f)
        val diag: Float get() = kotlin.math.sqrt((w * w + h * h).toDouble()).toFloat()
        /** Proporção largura/altura (protrusão / formato). */
        val aspecto: Float get() = if (h > 1e-6f) w / h else 0f
    }

    data class Evidencia(
        val faceFemale: Float = 0f,
        val faceMale: Float = 0f,
        val maleGen: Float = 0f,
        val maleBreast: Float = 0f,
        val femaleBreast: Float = 0f,
        val femaleGen: Float = 0f,
        val buttocks: Float = 0f,
        /** Métrica v2 (escala de corpo); >0 útil; -1 rejeitado; 0 pendente/incerto. */
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

    fun aceita(alvo: Alvo, e: Evidencia, limiar: Float = LIMIAR, minHits: Int = 3): Boolean {
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

    /**
     * Escala de corpo no frame: prioriza face, depois barriga, axila, pés.
     * Retorna “lado” característico (≈ largura do referente); 0 = sem referência.
     */
    fun escalaCorpo(face: Caixa?, belly: Caixa?, armpit: Caixa?, feet: Caixa?): Float {
        face?.takeIf { it.sc > 0f && it.diag > 0.01f }?.let { return it.diag.coerceAtLeast(0.02f) }
        belly?.takeIf { it.sc > 0f && it.w > 0.02f }?.let { return it.w.coerceAtLeast(0.02f) }
        armpit?.takeIf { it.sc > 0f && it.w > 0.015f }?.let { return (it.w * 2f).coerceAtLeast(0.02f) }
        feet?.takeIf { it.sc > 0f && it.w > 0.015f }?.let { return (it.w * 1.5f).coerceAtLeast(0.02f) }
        return 0f
    }

    /**
     * Peito: se 2 caixas → largura média / distância entre centros (robusto à distância da câmera);
     * senão área(peito)/área(rosto) ou largura/escala. Sem referência → 0.
     * Aspecto (w/h) reforça protrusão (caixas mais “cheias” / arredondadas).
     */
    fun metricaPeito(
        peitos: List<Caixa>,
        face: Caixa?,
        belly: Caixa? = null,
        armpit: Caixa? = null,
        feet: Caixa? = null,
    ): Float {
        val partes = peitos.filter { it.sc > 0f && it.w > 0.01f && it.h > 0.01f }
            .sortedByDescending { it.sc }
            .take(2)
        if (partes.isEmpty()) return 0f
        val esc = escalaCorpo(face, belly, armpit, feet)
        if (esc <= 0f) return 0f // incerto sem referência corporal

        val base = if (partes.size >= 2) {
            val a = partes[0]; val b = partes[1]
            val dist = kotlin.math.hypot((a.cx - b.cx).toDouble(), (a.cy - b.cy).toDouble()).toFloat()
                .coerceAtLeast(0.01f)
            val mediaW = (a.w + b.w) / 2f
            // Peitos grandes → caixas largas em relação ao vão entre elas.
            (mediaW / dist).coerceIn(0f, 8f)
        } else {
            val p = partes[0]
            if (face != null && face.area > 0.0005f) {
                (p.area / face.area).coerceIn(0f, 30f)
            } else {
                (p.w / esc).coerceIn(0f, 8f)
            }
        }
        val asp = partes.map { it.aspecto }.average().toFloat()
        // Peito projetado tende a w/h um pouco > 1; reforço suave (não domina).
        val forma = (1f + 0.25f * ((asp.coerceIn(0.4f, 2.5f) - 0.7f).coerceAtLeast(0f))).coerceIn(0.85f, 1.4f)
        return (base * forma).coerceIn(0f, 40f)
    }

    /**
     * Bunda: largura relativa à escala do corpo + formato (caixa larga/arredondada).
     * Sem referência → 0.
     */
    fun metricaBunda(
        bunda: Caixa?,
        face: Caixa?,
        belly: Caixa? = null,
        armpit: Caixa? = null,
        feet: Caixa? = null,
    ): Float {
        val b = bunda?.takeIf { it.sc > 0f && it.w > 0.015f && it.h > 0.015f } ?: return 0f
        val esc = escalaCorpo(face, belly, armpit, feet)
        if (esc <= 0f) return 0f
        val tamanho = (b.w / esc).coerceIn(0f, 10f)
        // Formato: próximo de 1.0–1.6 (cheia); muito alta/estreita = perfil fraco.
        val round = (1f - kotlin.math.abs(b.aspecto - 1.15f).coerceIn(0f, 1f) * 0.35f).coerceIn(0.65f, 1.2f)
        // Altura relativa à escala (protrusão / volume vertical da caixa).
        val alt = (b.h / esc).coerceIn(0f, 6f)
        return (0.65f * tamanho + 0.35f * alt) * round
    }

    /** Buceta: área / escala² (ou / face.area); sem ref → 0. */
    fun metricaBuceta(
        buceta: Caixa?,
        face: Caixa?,
        belly: Caixa? = null,
        armpit: Caixa? = null,
        feet: Caixa? = null,
    ): Float {
        val g = buceta?.takeIf { it.sc > 0f && it.area > 0.0002f } ?: return 0f
        val esc = escalaCorpo(face, belly, armpit, feet)
        if (esc <= 0f) return 0f
        return if (face != null && face.area > 0.0005f) {
            (g.area / face.area).coerceIn(0f, 20f)
        } else {
            (g.area / (esc * esc)).coerceIn(0f, 20f)
        }
    }

    /** @deprecated use metricaPeito/Bunda/Buceta; mantido p/ testes legados. */
    fun metricaRelativa(boxArea: Float, faceArea: Float): Float {
        if (boxArea <= 0f) return 0f
        return if (faceArea > 0.0005f) (boxArea / faceArea).coerceIn(0f, 50f) else 0f // sem face = incerto
    }

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

    fun medianaPositiva(xs: List<Float>): Float {
        val v = xs.filter { it > 0f }.sorted()
        if (v.isEmpty()) return 0f
        val m = v.size / 2
        return if (v.size % 2 == 1) v[m] else (v[m - 1] + v[m]) / 2f
    }

    /**
     * Mediana estável: exige ≥[minN] amostras; descarta outliers longe da mediana.
     * Sem consistência → 0 (não entra em nenhum lado do par).
     */
    fun metricaConsistente(valores: List<Float>, minN: Int = 3): Float {
        val v = valores.filter { it > 0f }.sorted()
        if (v.size < minN) return 0f
        val med = medianaPositiva(v)
        if (med <= 0f) return 0f
        val ok = v.filter { it in med * 0.4f..med * 2.5f }
        if (ok.size < minN) return 0f
        return medianaPositiva(ok)
    }

    /**
     * Split mutuamente exclusivo: topo ~30% = grande, fundo ~30% = pequeno, meio excluído.
     */
    fun <T> partirPorTamanho(
        scored: List<Pair<T, Float>>,
        topoFrac: Float = 0.30f,
        fundoFrac: Float = 0.30f,
    ): Pair<List<Pair<T, Float>>, List<Pair<T, Float>>> {
        val ok = scored.filter { it.second > 0f }.sortedByDescending { it.second }
        if (ok.isEmpty()) return emptyList<Pair<T, Float>>() to emptyList()
        if (ok.size == 1) return listOf(ok[0]) to emptyList()
        if (ok.size < 5) {
            val med = medianaPositiva(ok.map { it.second })
            val grande = ok.filter { it.second > med }
            val pequeno = ok.filter { it.second <= med }
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
        val gIds = grande.map { it.first }.toSet()
        val peq = pequeno.filter { it.first !in gIds }
        return grande to peq
    }

    fun areaPendente(area: Float) = area == 0f
}
