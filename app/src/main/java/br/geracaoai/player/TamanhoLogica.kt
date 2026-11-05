package br.geracaoai.player

/**
 * Lógica pura das pastas Por tamanho (Peituda/…): só mulheres.
 * Índices NudeNet 320n (YOLOv8, 18 classes) — ordem oficial notAI-tech:
 * 0 FEMALE_GENITALIA_COVERED, 1 FACE_FEMALE, 2 BUTTOCKS_EXPOSED,
 * 3 FEMALE_BREAST_EXPOSED, 4 FEMALE_GENITALIA_EXPOSED, 5 MALE_BREAST_EXPOSED,
 * 6 ANUS_EXPOSED, 7 FEET_EXPOSED, 8 BELLY_COVERED, 9 FEET_COVERED,
 * 10 ARMPITS_COVERED, 11 ARMPITS_EXPOSED, 12 FACE_MALE, 13 BELLY_EXPOSED,
 * 14 MALE_GENITALIA_EXPOSED, 15 ANUS_COVERED, 16 FEMALE_BREAST_COVERED,
 * 17 BUTTOCKS_COVERED.
 *
 * Métrica v3: referência corporal quando houver; senão união das caixas da pessoa;
 * último recurso = largura da parte. Agrega com ≥1–2 frames. Split 35%/35%.
 */
object TamanhoLogica {
    const val METRIC_VERSAO = 3
    const val LIMIAR = 0.45f // detecção de tamanho (mais permissivo que org 0.55)
    const val FACE_FEMALE = 1
    const val BUTTOCKS_EXPOSED = 2
    const val FEMALE_BREAST_EXPOSED = 3
    const val FEMALE_GENITALIA_EXPOSED = 4
    const val MALE_BREAST_EXPOSED = 5
    const val ANUS_EXPOSED = 6
    const val FEET_EXPOSED = 7
    const val BELLY_COVERED = 8
    const val FEET_COVERED = 9
    const val ARMPITS_COVERED = 10
    const val ARMPITS_EXPOSED = 11
    const val FACE_MALE = 12
    const val BELLY_EXPOSED = 13
    const val MALE_GENITALIA_EXPOSED = 14
    const val ANUS_COVERED = 15
    const val FEMALE_GENITALIA_COVERED = 0
    const val FEMALE_BREAST_COVERED = 16
    const val BUTTOCKS_COVERED = 17

    /** Classes usadas só para estimar a extensão da pessoa no frame. */
    val CLASSES_CORPO = intArrayOf(
        FACE_FEMALE, FACE_MALE, BELLY_EXPOSED, BELLY_COVERED,
        ARMPITS_EXPOSED, ARMPITS_COVERED, FEET_EXPOSED, FEET_COVERED,
        FEMALE_BREAST_EXPOSED, FEMALE_BREAST_COVERED,
        BUTTOCKS_EXPOSED, BUTTOCKS_COVERED,
        FEMALE_GENITALIA_EXPOSED, MALE_BREAST_EXPOSED, MALE_GENITALIA_EXPOSED, ANUS_EXPOSED,
    )

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

    data class Caixa(val sc: Float, val cx: Float, val cy: Float, val w: Float, val h: Float) {
        val area: Float get() = (w * h).coerceIn(0f, 1f)
        val diag: Float get() = kotlin.math.sqrt((w * w + h * h).toDouble()).toFloat()
        val aspecto: Float get() = if (h > 1e-6f) w / h else 0f
        val x0: Float get() = cx - w / 2f
        val x1: Float get() = cx + w / 2f
        val y0: Float get() = cy - h / 2f
        val y1: Float get() = cy + h / 2f
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

    fun aceita(alvo: Alvo, e: Evidencia, limiar: Float = LIMIAR, minHits: Int = 1): Boolean {
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

    fun escalaCorpo(face: Caixa?, belly: Caixa?, armpit: Caixa?, feet: Caixa?): Float {
        face?.takeIf { it.sc > 0f && it.diag > 0.01f }?.let { return it.diag.coerceAtLeast(0.02f) }
        belly?.takeIf { it.sc > 0f && it.w > 0.02f }?.let { return it.w.coerceAtLeast(0.02f) }
        armpit?.takeIf { it.sc > 0f && it.w > 0.015f }?.let { return (it.w * 2f).coerceAtLeast(0.02f) }
        feet?.takeIf { it.sc > 0f && it.w > 0.015f }?.let { return (it.w * 1.5f).coerceAtLeast(0.02f) }
        return 0f
    }

    /** Lado característico da pessoa = união de caixas detectadas (não o frame inteiro). */
    fun escalaPessoa(partes: List<Caixa>): Float {
        val ok = partes.filter { it.sc > 0f && it.w > 0.008f && it.h > 0.008f }
        if (ok.isEmpty()) return 0f
        if (ok.size == 1) return ok[0].diag.coerceAtLeast(0.02f)
        val minX = ok.minOf { it.x0 }; val maxX = ok.maxOf { it.x1 }
        val minY = ok.minOf { it.y0 }; val maxY = ok.maxOf { it.y1 }
        val bw = (maxX - minX).coerceAtLeast(0.02f)
        val bh = (maxY - minY).coerceAtLeast(0.02f)
        return maxOf(bw, bh)
    }

    /**
     * Cadeia: ref corporal → extensão da pessoa → largura da parte.
     * [pesoRef]/1, pesoPessoa]<1, [pesoAbs]<1 para refletir confiança.
     */
    fun escalaEfetiva(
        face: Caixa?, belly: Caixa?, armpit: Caixa?, feet: Caixa?,
        pessoa: List<Caixa>,
    ): Pair<Float, Float> {
        val r = escalaCorpo(face, belly, armpit, feet)
        if (r > 0f) return r to 1f
        val p = escalaPessoa(pessoa)
        if (p > 0f) return p to 0.85f
        return 0f to 0f
    }

    fun metricaPeito(
        peitos: List<Caixa>,
        face: Caixa?,
        belly: Caixa? = null,
        armpit: Caixa? = null,
        feet: Caixa? = null,
        pessoa: List<Caixa> = emptyList(),
    ): Float {
        val partes = peitos.filter { it.sc > 0f && it.w > 0.01f && it.h > 0.01f }
            .sortedByDescending { it.sc }.take(2)
        if (partes.isEmpty()) return 0f
        val (esc, peso) = escalaEfetiva(face, belly, armpit, feet, pessoa.ifEmpty { partes })
        val base = if (partes.size >= 2) {
            val a = partes[0]; val b = partes[1]
            val dist = kotlin.math.hypot((a.cx - b.cx).toDouble(), (a.cy - b.cy).toDouble()).toFloat()
                .coerceAtLeast(0.01f)
            ((a.w + b.w) / 2f / dist).coerceIn(0f, 8f)
        } else {
            val p = partes[0]
            when {
                esc > 0f && face != null && face.area > 0.0005f && peso >= 0.99f ->
                    (p.area / face.area).coerceIn(0f, 30f)
                esc > 0f -> (p.w / esc).coerceIn(0f, 8f)
                else -> (p.w * 3f).coerceIn(0f, 3f) // último recurso
            }
        }
        val asp = partes.map { it.aspecto }.average().toFloat()
        val forma = (1f + 0.2f * ((asp.coerceIn(0.4f, 2.5f) - 0.7f).coerceAtLeast(0f))).coerceIn(0.85f, 1.35f)
        val conf = if (esc > 0f) peso else 0.6f
        return (base * forma * conf).coerceIn(0f, 40f)
    }

    fun metricaBunda(
        bunda: Caixa?,
        face: Caixa?,
        belly: Caixa? = null,
        armpit: Caixa? = null,
        feet: Caixa? = null,
        pessoa: List<Caixa> = emptyList(),
    ): Float {
        val b = bunda?.takeIf { it.sc > 0f && it.w > 0.012f && it.h > 0.012f } ?: return 0f
        val (esc, peso) = escalaEfetiva(face, belly, armpit, feet, pessoa.ifEmpty { listOf(b) })
        val tamanho = if (esc > 0f) (b.w / esc).coerceIn(0f, 10f) else (b.w * 3f).coerceIn(0f, 3f)
        val alt = if (esc > 0f) (b.h / esc).coerceIn(0f, 6f) else (b.h * 3f).coerceIn(0f, 3f)
        val round = (1f - kotlin.math.abs(b.aspecto - 1.15f).coerceIn(0f, 1f) * 0.3f).coerceIn(0.7f, 1.2f)
        val conf = if (esc > 0f) peso else 0.6f
        return ((0.65f * tamanho + 0.35f * alt) * round * conf).coerceIn(0f, 20f)
    }

    fun metricaBuceta(
        buceta: Caixa?,
        face: Caixa?,
        belly: Caixa? = null,
        armpit: Caixa? = null,
        feet: Caixa? = null,
        pessoa: List<Caixa> = emptyList(),
    ): Float {
        val g = buceta?.takeIf { it.sc > 0f && it.area > 0.00015f } ?: return 0f
        val (esc, peso) = escalaEfetiva(face, belly, armpit, feet, pessoa.ifEmpty { listOf(g) })
        val base = when {
            face != null && face.area > 0.0005f && peso >= 0.99f ->
                (g.area / face.area).coerceIn(0f, 20f)
            esc > 0f -> (g.area / (esc * esc)).coerceIn(0f, 20f)
            else -> (g.area * 20f).coerceIn(0f, 4f)
        }
        val conf = if (esc > 0f) peso else 0.6f
        return (base * conf).coerceIn(0f, 20f)
    }

    fun metricaRelativa(boxArea: Float, faceArea: Float): Float {
        if (boxArea <= 0f) return 0f
        return if (faceArea > 0.0005f) (boxArea / faceArea).coerceIn(0f, 50f) else boxArea
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
     * Agrega frames: minN=1 aceita um hit; com ≥3 descarta outliers extremos.
     * Confiança implícita: mais samples → mediana mais estável.
     */
    fun metricaConsistente(valores: List<Float>, minN: Int = 1): Float {
        val v = valores.filter { it > 0f }.sorted()
        if (v.size < minN) return 0f
        if (v.size == 1) return v[0]
        if (v.size == 2) return (v[0] + v[1]) / 2f
        val med = medianaPositiva(v)
        val ok = v.filter { it in med * 0.35f..med * 3f }
        return medianaPositiva(if (ok.size >= 2) ok else v)
    }

    /** Split 35%/35% mutuamente exclusivo; meio excluído. */
    fun <T> partirPorTamanho(
        scored: List<Pair<T, Float>>,
        topoFrac: Float = 0.35f,
        fundoFrac: Float = 0.35f,
    ): Pair<List<Pair<T, Float>>, List<Pair<T, Float>>> {
        val ok = scored.filter { it.second > 0f }.sortedByDescending { it.second }
        if (ok.isEmpty()) return emptyList<Pair<T, Float>>() to emptyList()
        if (ok.size == 1) return listOf(ok[0]) to emptyList()
        if (ok.size < 4) {
            val mid = (ok.size + 1) / 2
            return ok.take(mid) to ok.drop(mid)
        }
        val nTopo = (ok.size * topoFrac).toInt().coerceAtLeast(1)
        val nFundo = (ok.size * fundoFrac).toInt().coerceAtLeast(1)
        // Garante que cada lado tenha pelo menos ~25% quando N≥8
        val nTopo2 = if (ok.size >= 8) maxOf(nTopo, (ok.size * 0.25f).toInt()) else nTopo
        val nFundo2 = if (ok.size >= 8) maxOf(nFundo, (ok.size * 0.25f).toInt()) else nFundo
        val grande = ok.take(nTopo2)
        val gIds = grande.map { it.first }.toSet()
        val peq = ok.takeLast(nFundo2).filter { it.first !in gIds }
        return grande to peq
    }

    /**
     * Enquadra união das caixas (0..1) no view: zoom 1..3 só se sujeito < [minSubjectFrac].
     * Retorna null se não houver caixas ou sujeito já grande (não mexe no zoom atual).
     */
    fun zoomPanParaSujeito(
        caixas: List<Caixa>,
        viewW: Float,
        viewH: Float,
        minSubjectFrac: Float = 0.40f,
        targetFrac: Float = 0.62f,
        maxZoom: Float = 3f,
    ): Pair<Float, FloatArray>? { // zoom to floatArrayOf(panX, panY) — Pair com Offset precisa compose
        if (caixas.isEmpty() || viewW <= 1f || viewH <= 1f) return null
        val x0 = caixas.minOf { it.x0 }; val x1 = caixas.maxOf { it.x1 }
        val y0 = caixas.minOf { it.y0 }; val y1 = caixas.maxOf { it.y1 }
        val pw = (x1 - x0).coerceAtLeast(0.02f)
        val ph = (y1 - y0).coerceAtLeast(0.02f)
        // Expande ~para cabeça→pés.
        val ex = pw * 0.12f; val ey = ph * 0.20f
        val u0 = (x0 - ex).coerceAtLeast(0f); val u1 = (x1 + ex).coerceAtMost(1f)
        val v0 = (y0 - ey).coerceAtLeast(0f); val v1 = (y1 + ey).coerceAtMost(1f)
        val uw = (u1 - u0).coerceAtLeast(0.05f)
        val uh = (v1 - v0).coerceAtLeast(0.05f)
        val subjectFrac = maxOf(uw, uh)
        if (subjectFrac >= minSubjectFrac) return null
        val z = (targetFrac / subjectFrac).coerceIn(1f, maxZoom)
        val cx = (u0 + u1) / 2f; val cy = (v0 + v1) / 2f
        val panX = (0.5f - cx) * viewW * z
        val panY = (0.5f - cy) * viewH * z
        val mx = (z - 1f) * viewW / 2f; val my = (z - 1f) * viewH / 2f
        return z to floatArrayOf(panX.coerceIn(-mx, mx), panY.coerceIn(-my, my))
    }

    fun areaPendente(area: Float) = area == 0f
}

