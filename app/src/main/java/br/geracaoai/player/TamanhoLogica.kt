package br.geracaoai.player

/**
 * Framing do Zoom automático (corpo feminino inteiro).
 * Índices NudeNet 320n oficiais. Sem lógica de pastas Por tamanho (removida na v1.3.9).
 */
object TamanhoLogica {
    const val LIMIAR = 0.45f
    const val FACE_FEMALE = 1
    const val FEMALE_GENITALIA_COVERED = 0
    const val BUTTOCKS_EXPOSED = 2
    const val FEMALE_BREAST_EXPOSED = 3
    const val FEMALE_GENITALIA_EXPOSED = 4
    const val ANUS_EXPOSED = 6
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
        FEMALE_GENITALIA_EXPOSED, FEMALE_GENITALIA_COVERED, ANUS_EXPOSED,
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
        // Cabeça ≈ 1/7,5 do corpo; a caixa do rosto ≈ 0,75 da cabeça → corpo ≈ 8–10× a altura do rosto.
        if (face != null && feet == null) {
            y1 = maxOf(y1, face.y0 + face.h * 8f)
        }
        if (face != null) {
            // Largura mínima de ombros/braços ao redor do rosto (mulher pequena/distante: só o rosto foi detectado).
            x0 = minOf(x0, face.cx - face.w * 1.6f)
            x1 = maxOf(x1, face.cx + face.w * 1.6f)
        }
        if (feet != null && face == null) {
            y0 = minOf(y0, feet.y0 - feet.h * 10f)
        }
        if (face != null && feet != null) {
            y0 = minOf(y0, face.y0)
            y1 = maxOf(y1, feet.y1)
        }
        if (face == null && feet == null) {
            // Só peito/bunda/genitália (mulher distante, sem rosto): estima o corpo todo a partir da parte.
            for (a in partes) if (a.classe == FEMALE_BREAST_EXPOSED || a.classe == BUTTOCKS_EXPOSED ||
                a.classe == FEMALE_GENITALIA_EXPOSED || a.classe == ANUS_EXPOSED) {
                val e = estimativaCorpo(a)
                x0 = minOf(x0, e.x0); y0 = minOf(y0, e.y0); x1 = maxOf(x1, e.x1); y1 = maxOf(y1, e.y1)
            }
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
     * Zoom FIT: o retângulo padded inteiro cabe na tela (nunca corta).
     * Só amplia se a caixa padded ocupar < [minBoxFrac] do maior lado.
     * Clamp 1x–[maxZoom].
     *
     * [viewW]/[viewH] = tamanho da view do vídeo (onde as coords normalizadas vivem);
     * [contW]/[contH] = área visível (container). No modo Ajustar o vídeo é menor que o
     * container (tarja preta) — usar o container no lugar da view errava o pan vertical
     * e cortava cabeça/pés.
     *
     * Mapeamento (graphicsLayer scale no centro da view + translation; view centrada no container):
     *   screenX = (nx - 0.5) * viewW * z + panX + contW/2
     */
    fun enquadrarCorpoInteiro(
        partes: List<CaixaParte>,
        viewW: Float,
        viewH: Float,
        padding: Float = 0.25f,
        maxZoom: Float = 2.5f,
        minBoxFrac: Float = 0.60f,
        minPartesDistintas: Int = 2,
        contW: Float = viewW,
        contH: Float = viewH,
    ): ZoomAlvo? {
        if (viewW < 8f || viewH < 8f || contW < 8f || contH < 8f) return null
        // Mulher pequena/distante: às vezes só o rosto aparece → aceita, estimando o corpo todo a partir dele.
        val soRostoOk = partes.any { it.classe == FACE_FEMALE }
        if (classesDistintas(partes) < minPartesDistintas && !soRostoOk) return null
        val box = caixaCorpoPadded(partes, padding) ?: return null
        val frac = maxOf(box.w, box.h)
        // Já ocupa boa parte do quadro → não amplia (volta a 1x).
        if (frac >= minBoxFrac) return ZoomAlvo(1f, 0f, 0f)
        // Fit: o box ampliado precisa caber no container.
        val zFit = minOf(contW / (box.w * viewW), contH / (box.h * viewH))
        val z = zFit.coerceIn(1f, maxZoom)
        // Ajuste fino: se algum canto sair, reduz zoom (nunca cortar).
        return garantirDentro(box, viewW, viewH, z, 0f, 0f, maxZoom, contW, contH)
    }

    /** Pan que centraliza o box, limitado para não mostrar além da borda do vídeo. */
    private fun panCentrado(c: Float, view: Float, cont: Float, z: Float): Float {
        val m = ((view * z - cont) / 2f).coerceAtLeast(0f)
        return ((0.5f - c) * view * z).coerceIn(-m, m)
    }

    /** Garante que os 4 cantos do box fiquem dentro do container [contW]×[contH]. */
    fun garantirDentro(
        box: RectN,
        viewW: Float,
        viewH: Float,
        zoom: Float,
        panX: Float,
        panY: Float,
        maxZoom: Float,
        contW: Float = viewW,
        contH: Float = viewH,
    ): ZoomAlvo {
        var z = zoom.coerceIn(1f, maxZoom)
        fun cornersOk(zz: Float, ppx: Float, ppy: Float): Boolean {
            for (nx in floatArrayOf(box.x0, box.x1)) {
                for (ny in floatArrayOf(box.y0, box.y1)) {
                    val sx = (nx - 0.5f) * viewW * zz + ppx + contW / 2f
                    val sy = (ny - 0.5f) * viewH * zz + ppy + contH / 2f
                    if (sx < -1f || sx > contW + 1f || sy < -1f || sy > contH + 1f) return false
                }
            }
            return true
        }
        // Recentrar
        var px = panCentrado(box.cx, viewW, contW, z)
        var py = panCentrado(box.cy, viewH, contH, z)
        var guard = 0
        while (!cornersOk(z, px, py) && z > 1.01f && guard++ < 24) {
            z = (z * 0.92f).coerceAtLeast(1f)
            px = panCentrado(box.cx, viewW, contW, z)
            py = panCentrado(box.cy, viewH, contH, z)
        }
        if (!cornersOk(z, px, py)) return ZoomAlvo(1f, 0f, 0f)
        return ZoomAlvo(z, px, py)
    }

    /**
     * Converte caixas do detector (normalizadas no canvas S×S onde o bitmap de [bmpW]×[bmpH] foi encaixado
     * com o maior lado = S, canto superior esquerdo) para coordenadas normalizadas do quadro/região.
     * [regiao] = região do quadro (normalizada) de onde o bitmap saiu; quadro todo = (0,0,1,1).
     */
    fun paraQuadro(partes: List<CaixaParte>, bmpW: Int, bmpH: Int, regiao: RectN = RectN(0f, 0f, 1f, 1f), s: Float = 320f): List<CaixaParte> {
        if (bmpW <= 0 || bmpH <= 0) return emptyList()
        val esc = s / maxOf(bmpW, bmpH)
        val fx = s / (bmpW * esc); val fy = s / (bmpH * esc)
        return partes.map { p ->
            val c = p.caixa
            val cx = regiao.x0 + (c.cx * fx) * regiao.w
            val cy = regiao.y0 + (c.cy * fy) * regiao.h
            val w = c.w * fx * regiao.w
            val h = c.h * fy * regiao.h
            CaixaParte(p.classe, Caixa(c.sc, cx, cy, w, h))
        }
    }

    /** Âncoras: partes femininas inequívocas (rosto feminino, peito/bunda/genitália/ânus expostos). */
    val ANCORAS = intArrayOf(FACE_FEMALE, FEMALE_BREAST_EXPOSED, BUTTOCKS_EXPOSED, FEMALE_GENITALIA_EXPOSED, ANUS_EXPOSED)
    /** Score mínimo para uma âncora SOZINHA bastar (piso do detector no zoom já é ~0,25). */
    const val ANCORA_MIN = 0.30f

    fun ehAncora(classe: Int) = classe in ANCORAS

    /** Classes específicas do corpo FEMININO (barriga/pés/axilas não distinguem sexo e não bastam sozinhas). */
    val ESPECIFICAS = intArrayOf(
        FACE_FEMALE, FEMALE_GENITALIA_COVERED, FEMALE_GENITALIA_EXPOSED, FEMALE_BREAST_EXPOSED, FEMALE_BREAST_COVERED,
        BUTTOCKS_EXPOSED, BUTTOCKS_COVERED, ANUS_EXPOSED,
    )

    /**
     * Resultado utilizável: uma âncora feminina forte sozinha (o corpo é estimado dela), ou 2+ partes distintas
     * com pelo menos UMA específica feminina — barriga+pés (neutros) podem ser de um homem e não travam o zoom.
     */
    fun valido(partes: List<CaixaParte>): Boolean =
        partes.isNotEmpty() && (
            partes.any { ehAncora(it.classe) && it.caixa.sc >= ANCORA_MIN } ||
                (classesDistintas(partes) >= 2 && partes.any { it.classe in ESPECIFICAS })
            )

    /** Caixa do corpo inteiro estimada (generosa) a partir de UMA parte; usada p/ agrupar e p/ estimar o corpo. */
    fun estimativaCorpo(p: CaixaParte): RectN {
        val c = p.caixa
        val s = maxOf(c.w, c.h)
        fun ancora(hb: Float, acima: Float, abaixo: Float, larg: Float) =
            RectN(c.cx - larg * hb, c.cy - acima * hb, c.cx + larg * hb, c.cy + abaixo * hb)
        return when (p.classe) {
            FACE_FEMALE -> RectN(c.cx - 3.2f * c.w, c.y0 - 0.8f * c.h, c.cx + 3.2f * c.w, c.y0 + 10.5f * c.h)
            FEMALE_BREAST_EXPOSED, FEMALE_BREAST_COVERED -> ancora(12f * s, 0.30f, 0.74f, 0.32f)
            BUTTOCKS_EXPOSED, BUTTOCKS_COVERED -> ancora(7f * s, 0.50f, 0.52f, 0.32f)
            FEMALE_GENITALIA_EXPOSED, FEMALE_GENITALIA_COVERED -> ancora(9f * s, 0.53f, 0.50f, 0.32f)
            ANUS_EXPOSED -> ancora(14f * s, 0.50f, 0.52f, 0.30f)
            FEET_EXPOSED, FEET_COVERED -> ancora(7.5f * s, 1.05f, 0.10f, 0.40f)
            else -> ancora(8f * s, 0.50f, 0.60f, 0.40f)
        }
    }

    private fun dentro(r: RectN, x: Float, y: Float) = x in r.x0..r.x1 && y in r.y0..r.y1

    /**
     * Entre as partes detectadas (de uma região) fica só o MELHOR aglomerado feminino: uma pessoa. Cada parte é
     * "semente"; o aglomerado são as partes cujo centro cai na caixa de corpo estimada da semente. Vence quem
     * tem mais âncoras e maior soma de scores — partes soltas (outra pessoa, fundo, falso positivo) são descartadas
     * e não inflam a caixa. Detecções masculinas nunca chegam aqui: o Detector só devolve classes femininas.
     */
    fun aglomeradoFeminino(partes: List<CaixaParte>): List<CaixaParte> {
        if (partes.size < 2) return partes
        var melhor: List<CaixaParte> = emptyList(); var melhorScore = -1f
        for (semente in partes) {
            val est = estimativaCorpo(semente)
            val cl = partes.filter { it === semente || dentro(est, it.caixa.cx, it.caixa.cy) }
            if (!valido(cl)) continue
            val score = cl.sumOf { (it.caixa.sc + if (ehAncora(it.classe)) 0.5f else 0f).toDouble() }.toFloat()
            if (score > melhorScore) { melhorScore = score; melhor = cl }
        }
        return melhor
    }

    /** Fração do viewport (container) que a caixa ocupa a 1x — o MAIOR dos dois eixos. */
    fun ocupacao(box: RectN, viewW: Float, viewH: Float, contW: Float, contH: Float): Float =
        maxOf(box.w * viewW / contW, box.h * viewH / contH)

    /** Caixa-alvo dos modos "tight" (com margem pequena); null se a parte do modo não está no aglomerado. */
    fun caixaAlvoModo(modo: ModoZoom, partes: List<CaixaParte>, margem: Float = 0.30f): RectN? {
        val alvo = when (modo) {
            ModoZoom.BUCETA -> partes.filter { it.classe == FEMALE_GENITALIA_EXPOSED }
            ModoZoom.BUNDA -> partes.filter { it.classe == BUTTOCKS_EXPOSED || it.classe == ANUS_EXPOSED }
            ModoZoom.PEITO_ROSTO -> {
                val peitos = partes.filter { it.classe == FEMALE_BREAST_EXPOSED }
                if (peitos.isEmpty()) emptyList() else peitos + partes.filter { it.classe == FACE_FEMALE }
            }
            else -> emptyList()
        }
        val u = uniaoPartes(alvo) ?: return null
        val m = maxOf(u.w, u.h) * margem
        return RectN((u.x0 - m).coerceIn(0f, 0.98f), (u.y0 - m).coerceIn(0f, 0.98f), (u.x1 + m).coerceIn(0.02f, 1f), (u.y1 + m).coerceIn(0.02f, 1f))
    }

    /** Zoom FIT para uma caixa qualquer: cabe inteira no container, 1x–[maxZoom]. */
    fun alvoParaCaixa(box: RectN, viewW: Float, viewH: Float, contW: Float, contH: Float, maxZoom: Float): ZoomAlvo {
        val zFit = minOf(contW / (box.w * viewW), contH / (box.h * viewH))
        return garantirDentro(box, viewW, viewH, zFit.coerceIn(1f, maxZoom), 0f, 0f, maxZoom, contW, contH)
    }

    /**
     * Quadrados (em coordenadas normalizadas do quadro) de lado [ladoFrac]×lado menor do quadro, varrendo o
     * quadro todo com sobreposição. Quadrados usam o canvas 320×320 do detector por inteiro (um vídeo 16:9
     * inteiro só usava 320×180) e ampliam a mulher distante.
     */
    fun quadrados(frameW: Int, frameH: Int, ladoFrac: Float, passoFrac: Float = 0.75f): List<RectN> {
        if (frameW < 2 || frameH < 2) return emptyList()
        val curto = minOf(frameW, frameH).toFloat()
        val lado = (curto * ladoFrac).coerceIn(2f, curto)
        fun posicoes(total: Int): List<Float> {
            if (lado >= total - 1f) return listOf(0f)
            val n = kotlin.math.ceil(((total - lado) / (lado * passoFrac)).toDouble()).toInt() + 1
            return List(n) { i -> (total - lado) * i / (n - 1) }
        }
        val out = ArrayList<RectN>()
        for (y in posicoes(frameH)) for (x in posicoes(frameW)) {
            out += RectN(x / frameW, y / frameH, (x + lado) / frameW, (y + lado) / frameH)
        }
        return out
    }

    /**
     * Recorte quadrado em volta de [box] (acompanhar a mulher já achada com 1 só inferência):
     * lado = [folga]× o maior lado do box, entre [minFrac] e 1× o lado menor do quadro, sempre dentro do quadro.
     */
    fun recorteQuadradoEm(box: RectN, frameW: Int, frameH: Int, folga: Float = 1.35f, minFrac: Float = 0.30f): RectN {
        val curto = minOf(frameW, frameH).toFloat()
        val lado = (maxOf(box.w * frameW, box.h * frameH) * folga).coerceIn(curto * minFrac, curto)
        val x0 = (box.cx * frameW - lado / 2f).coerceIn(0f, frameW - lado)
        val y0 = (box.cy * frameH - lado / 2f).coerceIn(0f, frameH - lado)
        return RectN(x0 / frameW, y0 / frameH, (x0 + lado) / frameW, (y0 + lado) / frameH)
    }

    /** Regiões 2×2 sobrepostas (cada uma com [frac] do quadro) p/ achar mulher pequena/distante. */
    fun ladrilhos(frac: Float = 0.6f): List<RectN> {
        val o = 1f - frac
        return listOf(RectN(0f, 0f, frac, frac), RectN(o, 0f, 1f, frac), RectN(0f, o, frac, 1f), RectN(o, o, 1f, 1f))
    }

    /**
     * Onde um ponto normalizado (nx,ny) do vídeo aparece no container após o graphicsLayer
     * (escala [zoom] no centro da view + translação [panX],[panY]; view centrada no container).
     */
    fun projetar(nx: Float, ny: Float, viewW: Float, viewH: Float, contW: Float, contH: Float, zoom: Float, panX: Float, panY: Float): Pair<Float, Float> =
        ((nx - 0.5f) * viewW * zoom + panX + contW / 2f) to ((ny - 0.5f) * viewH * zoom + panY + contH / 2f)

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
