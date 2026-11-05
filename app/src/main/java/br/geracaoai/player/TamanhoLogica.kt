package br.geracaoai.player

/**
 * Framing do Zoom automático (corpo feminino inteiro).
 * Índices NudeNet 320n oficiais. Sem lógica de pastas Por tamanho (removida na v1.3.9).
 */
object TamanhoLogica {
    const val LIMIAR = 0.45f
    const val FACE_FEMALE = 1
    const val BUTTOCKS_EXPOSED = 2
    const val FEMALE_BREAST_EXPOSED = 3
    const val FEMALE_GENITALIA_EXPOSED = 4
    const val FEET_EXPOSED = 7
    const val BELLY_COVERED = 8
    const val FEET_COVERED = 9
    const val ARMPITS_COVERED = 10
    const val ARMPITS_EXPOSED = 11
    const val BELLY_EXPOSED = 13
    const val FEMALE_BREAST_COVERED = 16
    const val BUTTOCKS_COVERED = 17
    const val FACE_MALE = 12
    const val MALE_BREAST_EXPOSED = 5
    const val MALE_GENITALIA_EXPOSED = 14

    /** Só partes femininas / corpo (nunca masculino). */
    val CLASSES_FEMININAS = intArrayOf(
        FACE_FEMALE,
        FEMALE_BREAST_EXPOSED, FEMALE_BREAST_COVERED,
        BELLY_EXPOSED, BELLY_COVERED,
        BUTTOCKS_EXPOSED, BUTTOCKS_COVERED,
        FEMALE_GENITALIA_EXPOSED,
        FEET_EXPOSED, FEET_COVERED,
        ARMPITS_EXPOSED, ARMPITS_COVERED,
    )

    data class Caixa(val sc: Float, val cx: Float, val cy: Float, val w: Float, val h: Float) {
        val x0: Float get() = cx - w / 2f
        val x1: Float get() = cx + w / 2f
        val y0: Float get() = cy - h / 2f
        val y1: Float get() = cy + h / 2f
    }

    data class CaixaParte(val classe: Int, val caixa: Caixa)

    data class ZoomAlvo(val zoom: Float, val panX: Float, val panY: Float)

    data class RectN(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
        val w: Float get() = (x1 - x0).coerceAtLeast(0.01f)
        val h: Float get() = (y1 - y0).coerceAtLeast(0.01f)
        val cx: Float get() = (x0 + x1) / 2f
        val cy: Float get() = (y0 + y1) / 2f
    }

    fun classesDistintas(partes: List<CaixaParte>): Int =
        partes.map { it.classe }.toSet().size

    fun uniaoPartes(partes: List<CaixaParte>): RectN? {
        if (partes.isEmpty()) return null
        return RectN(
            partes.minOf { it.caixa.x0 },
            partes.minOf { it.caixa.y0 },
            partes.maxOf { it.caixa.x1 },
            partes.maxOf { it.caixa.y1 },
        )
    }

    /** União temporal: junta várias detecções. */
    fun uniaoTemporal(historico: List<List<CaixaParte>>): List<CaixaParte> =
        historico.flatten()

    /**
     * Expande a união com padding generoso e estima cabeça→pés se faltar face/pés.
     */
    fun caixaCorpoPadded(
        partes: List<CaixaParte>,
        padding: Float = 0.25f,
    ): RectN? {
        val base = uniaoPartes(partes) ?: return null
        var x0 = base.x0; var y0 = base.y0; var x1 = base.x1; val y1b = base.y1
        var y1 = y1b
        val face = partes.filter { it.classe == FACE_FEMALE }.maxByOrNull { it.caixa.sc }?.caixa
        val feet = partes.filter { it.classe == FEET_EXPOSED || it.classe == FEET_COVERED }
            .maxByOrNull { it.caixa.sc }?.caixa
        // Estima extensão vertical cabeça↔pés.
        if (face != null && feet == null) {
            val down = face.h * 3.2f
            y1 = maxOf(y1, face.y1 + down)
        }
        if (feet != null && face == null) {
            val up = feet.h * 3.5f
            y0 = minOf(y0, feet.y0 - up)
        }
        if (face != null && feet != null) {
            y0 = minOf(y0, face.y0)
            y1 = maxOf(y1, feet.y1)
        }
        val bw = (x1 - x0).coerceAtLeast(0.05f)
        val bh = (y1 - y0).coerceAtLeast(0.05f)
        x0 -= bw * padding; x1 += bw * padding
        y0 -= bh * padding; y1 += bh * padding
        return RectN(
            x0.coerceIn(0f, 0.98f),
            y0.coerceIn(0f, 0.98f),
            x1.coerceIn(0.02f, 1f),
            y1.coerceIn(0.02f, 1f),
        )
    }

    /**
     * Zoom FIT: o retângulo padded inteiro cabe na viewport (nunca corta).
     * Só amplia se a caixa padded ocupar < [minBoxFrac] do maior lado.
     * Clamp 1x–[maxZoom].
     *
     * Mapeamento (graphicsLayer scale no centro + translation):
     *   screenX = (nx - 0.5) * viewW * z + panX + viewW/2
     */
    fun enquadrarCorpoInteiro(
        partes: List<CaixaParte>,
        viewW: Float,
        viewH: Float,
        padding: Float = 0.25f,
        maxZoom: Float = 2.5f,
        minBoxFrac: Float = 0.60f,
        minPartesDistintas: Int = 2,
    ): ZoomAlvo? {
        if (viewW < 8f || viewH < 8f) return null
        if (classesDistintas(partes) < minPartesDistintas) return null
        val box = caixaCorpoPadded(partes, padding) ?: return null
        val frac = maxOf(box.w, box.h)
        // Já ocupa boa parte do quadro → não amplia (volta a 1x).
        if (frac >= minBoxFrac) return ZoomAlvo(1f, 0f, 0f)
        // Fit: região visível = 1/z do conteúdo; precisa box.w <= 1/z e box.h <= 1/z.
        val zFit = minOf(1f / box.w, 1f / box.h)
        val z = zFit.coerceIn(1f, maxZoom)
        // Com z limitado por maxZoom, se ainda não cabe, não amplie além do fit seguro.
        val zSafe = minOf(z, 1f / box.w, 1f / box.h).coerceAtLeast(1f).coerceAtMost(maxZoom)
        var panX = (0.5f - box.cx) * viewW * zSafe
        var panY = (0.5f - box.cy) * viewH * zSafe
        val mx = (zSafe - 1f) * viewW / 2f
        val my = (zSafe - 1f) * viewH / 2f
        panX = panX.coerceIn(-mx, mx)
        panY = panY.coerceIn(-my, my)
        // Ajuste fino: se algum canto sair, reduz zoom (nunca cortar).
        return garantirDentro(box, viewW, viewH, zSafe, panX, panY, maxZoom)
    }

    /** Garante que os 4 cantos do box fiquem dentro de [0,viewW]×[0,viewH]. */
    fun garantirDentro(
        box: RectN,
        viewW: Float,
        viewH: Float,
        zoom: Float,
        panX: Float,
        panY: Float,
        maxZoom: Float,
    ): ZoomAlvo {
        var z = zoom.coerceIn(1f, maxZoom)
        var px = panX
        var py = panY
        fun cornersOk(zz: Float, ppx: Float, ppy: Float): Boolean {
            for (nx in floatArrayOf(box.x0, box.x1)) {
                for (ny in floatArrayOf(box.y0, box.y1)) {
                    val sx = (nx - 0.5f) * viewW * zz + ppx + viewW / 2f
                    val sy = (ny - 0.5f) * viewH * zz + ppy + viewH / 2f
                    if (sx < -1f || sx > viewW + 1f || sy < -1f || sy > viewH + 1f) return false
                }
            }
            return true
        }
        // Recentrar
        px = (0.5f - box.cx) * viewW * z
        py = (0.5f - box.cy) * viewH * z
        val mx = (z - 1f) * viewW / 2f
        val my = (z - 1f) * viewH / 2f
        px = px.coerceIn(-mx, mx)
        py = py.coerceIn(-my, my)
        var guard = 0
        while (!cornersOk(z, px, py) && z > 1.01f && guard++ < 24) {
            z = (z * 0.92f).coerceAtLeast(1f)
            px = (0.5f - box.cx) * viewW * z
            py = (0.5f - box.cy) * viewH * z
            val mx2 = (z - 1f) * viewW / 2f
            val my2 = (z - 1f) * viewH / 2f
            px = px.coerceIn(-mx2, mx2)
            py = py.coerceIn(-my2, my2)
        }
        if (!cornersOk(z, px, py)) return ZoomAlvo(1f, 0f, 0f)
        return ZoomAlvo(z, px, py)
    }

    /** Histerese: só atualiza se zoom mudar >15% ou pan mudar bastante. */
    fun deveAtualizarAlvo(atual: ZoomAlvo?, novo: ZoomAlvo, limiarZoom: Float = 0.15f): Boolean {
        if (atual == null) return true
        val dz = kotlin.math.abs(novo.zoom - atual.zoom) / atual.zoom.coerceAtLeast(1f)
        if (dz >= limiarZoom) return true
        val dp = kotlin.math.hypot(
            (novo.panX - atual.panX).toDouble(),
            (novo.panY - atual.panY).toDouble(),
        )
        // ~8% da diagonal típica — caller passa view; aqui usa limiar absoluto frouxo via zoom
        return dp > 40.0 * novo.zoom
    }

    // Compat: API antiga usada pelo player até migrar — delega.
    fun zoomPanParaSujeito(
        caixas: List<Caixa>,
        viewW: Float,
        viewH: Float,
        minSubjectFrac: Float = 0.60f,
        targetFrac: Float = 0.62f,
        maxZoom: Float = 2.5f,
    ): Pair<Float, FloatArray>? {
        // Sem classes: trata cada caixa como parte distinta sintética.
        val partes = caixas.mapIndexed { i, c -> CaixaParte(1000 + i, c) }
        if (partes.size < 2) return null
        val a = enquadrarCorpoInteiro(partes, viewW, viewH, minBoxFrac = minSubjectFrac, maxZoom = maxZoom)
            ?: return null
        return a.zoom to floatArrayOf(a.panX, a.panY)
    }
}
