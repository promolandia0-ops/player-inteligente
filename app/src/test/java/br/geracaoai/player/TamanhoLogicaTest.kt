package br.geracaoai.player

import org.junit.Assert.*
import org.junit.Test

class TamanhoLogicaTest {
    private fun p(classe: Int, cx: Float, cy: Float, w: Float, h: Float, sc: Float = 0.9f) =
        TamanhoLogica.CaixaParte(classe, TamanhoLogica.Caixa(sc, cx, cy, w, h))

    @Test fun labelsFemininasSemMacho() {
        assertTrue(TamanhoLogica.FACE_MALE !in TamanhoLogica.CLASSES_FEMININAS.toList())
        assertTrue(TamanhoLogica.MALE_GENITALIA_EXPOSED !in TamanhoLogica.CLASSES_FEMININAS.toList())
        assertTrue(TamanhoLogica.FACE_FEMALE in TamanhoLogica.CLASSES_FEMININAS.toList())
        assertTrue(TamanhoLogica.BUTTOCKS_EXPOSED in TamanhoLogica.CLASSES_FEMININAS.toList())
    }

    @Test fun exigeDuasPartesDistintas() {
        val soPe = listOf(p(TamanhoLogica.FEET_EXPOSED, 0.5f, 0.85f, 0.1f, 0.08f))
        assertNull(TamanhoLogica.enquadrarCorpoInteiro(soPe, 1080f, 1920f))
        val facePe = listOf(
            p(TamanhoLogica.FACE_FEMALE, 0.5f, 0.2f, 0.12f, 0.14f),
            p(TamanhoLogica.FEET_EXPOSED, 0.5f, 0.9f, 0.1f, 0.08f),
        )
        assertNotNull(TamanhoLogica.enquadrarCorpoInteiro(facePe, 1080f, 1920f))
    }

    @Test fun boxSempreDentroDaViewport() {
        val partes = listOf(
            p(TamanhoLogica.FACE_FEMALE, 0.55f, 0.18f, 0.1f, 0.12f),
            p(TamanhoLogica.FEMALE_BREAST_EXPOSED, 0.5f, 0.38f, 0.22f, 0.14f),
            p(TamanhoLogica.BELLY_EXPOSED, 0.5f, 0.52f, 0.18f, 0.12f),
            p(TamanhoLogica.FEMALE_GENITALIA_EXPOSED, 0.5f, 0.68f, 0.08f, 0.06f),
            p(TamanhoLogica.FEET_EXPOSED, 0.48f, 0.92f, 0.12f, 0.07f),
        )
        val vw = 1080f; val vh = 1920f
        val alvo = TamanhoLogica.enquadrarCorpoInteiro(partes, vw, vh)!!
        val box = TamanhoLogica.caixaCorpoPadded(partes)!!
        // Todos os cantos do box padded dentro da tela após transform.
        for (nx in floatArrayOf(box.x0, box.x1)) {
            for (ny in floatArrayOf(box.y0, box.y1)) {
                val sx = (nx - 0.5f) * vw * alvo.zoom + alvo.panX + vw / 2f
                val sy = (ny - 0.5f) * vh * alvo.zoom + alvo.panY + vh / 2f
                assertTrue("sx=$sx", sx in -2f..(vw + 2f))
                assertTrue("sy=$sy", sy in -2f..(vh + 2f))
            }
        }
        assertTrue(alvo.zoom in 1f..2.5f)
    }

    @Test fun sujeitoGrandeVoltaPara1x() {
        val partes = listOf(
            p(TamanhoLogica.FACE_FEMALE, 0.5f, 0.15f, 0.3f, 0.25f),
            p(TamanhoLogica.FEET_EXPOSED, 0.5f, 0.9f, 0.35f, 0.2f),
            p(TamanhoLogica.BELLY_EXPOSED, 0.5f, 0.5f, 0.5f, 0.4f),
        )
        val a = TamanhoLogica.enquadrarCorpoInteiro(partes, 1080f, 1920f)!!
        assertEquals(1f, a.zoom, 0.01f)
        assertEquals(0f, a.panX, 0.5f)
        assertEquals(0f, a.panY, 0.5f)
    }

    @Test fun uniaoTemporalAcumula() {
        val a = listOf(p(TamanhoLogica.FACE_FEMALE, 0.5f, 0.2f, 0.1f, 0.1f))
        val b = listOf(p(TamanhoLogica.BUTTOCKS_EXPOSED, 0.5f, 0.7f, 0.2f, 0.15f))
        val u = TamanhoLogica.uniaoTemporal(listOf(a, b))
        assertEquals(2, TamanhoLogica.classesDistintas(u))
    }

    @Test fun histereseIgnoraMudancaPequena() {
        val a = TamanhoLogica.ZoomAlvo(1.5f, 10f, 10f)
        val quase = TamanhoLogica.ZoomAlvo(1.55f, 12f, 11f)
        assertFalse(TamanhoLogica.deveAtualizarAlvo(a, quase, limiarZoom = 0.15f))
        val longe = TamanhoLogica.ZoomAlvo(2.0f, 10f, 10f)
        assertTrue(TamanhoLogica.deveAtualizarAlvo(a, longe, limiarZoom = 0.15f))
    }

    @Test fun fitNuncaExcedeMaxZoom() {
        val partes = listOf(
            p(TamanhoLogica.FACE_FEMALE, 0.5f, 0.45f, 0.04f, 0.05f),
            p(TamanhoLogica.FEMALE_BREAST_EXPOSED, 0.5f, 0.52f, 0.06f, 0.04f),
        )
        val a = TamanhoLogica.enquadrarCorpoInteiro(partes, 1080f, 1920f)!!
        assertTrue(a.zoom <= 2.5f + 1e-3f)
    }

    @Test fun letterboxNaoCortaCabecaNemPes() {
        // Vídeo paisagem em tela retrato (modo Ajustar): view 1080x608 dentro de container 1080x2400.
        val vw = 1080f; val vh = 608f; val cw = 1080f; val ch = 2400f
        val partes = listOf(
            p(TamanhoLogica.FACE_FEMALE, 0.3f, 0.2f, 0.04f, 0.06f),
            p(TamanhoLogica.FEMALE_BREAST_EXPOSED, 0.3f, 0.3f, 0.06f, 0.05f),
            p(TamanhoLogica.FEET_EXPOSED, 0.3f, 0.5f, 0.05f, 0.04f),
        )
        val alvo = TamanhoLogica.enquadrarCorpoInteiro(partes, vw, vh, contW = cw, contH = ch)!!
        assertTrue(alvo.zoom > 1.2f)
        val box = TamanhoLogica.caixaCorpoPadded(partes)!!
        for (nx in floatArrayOf(box.x0, box.x1)) {
            for (ny in floatArrayOf(box.y0, box.y1)) {
                val sx = (nx - 0.5f) * vw * alvo.zoom + alvo.panX + cw / 2f
                val sy = (ny - 0.5f) * vh * alvo.zoom + alvo.panY + ch / 2f
                assertTrue("sx=$sx", sx in -2f..(cw + 2f))
                assertTrue("sy=$sy", sy in -2f..(ch + 2f))
            }
        }
        // Vídeo ampliado continua menor que o container na vertical → sem pan vertical.
        assertEquals(0f, alvo.panY, 0.5f)
    }

    // ---------- Zoom automático: coordenadas, mulher distante, transform na tela ----------

    @Test fun paraQuadroDesfazOLetterboxDoDetector() {
        // Quadro 16:9 (800×450) encaixado em 320×320 pelo maior lado: altura útil = 180 px.
        val bw = 800; val bh = 450
        // Caixa real no quadro (normalizada): centro (0.3, 0.6), 0.2 × 0.4.
        val det = p(TamanhoLogica.FACE_FEMALE, 0.3f * 1f, 0.6f * (180f / 320f), 0.2f, 0.4f * (180f / 320f))
        val r = TamanhoLogica.paraQuadro(listOf(det), bw, bh).single().caixa
        assertEquals(0.3f, r.cx, 1e-4f); assertEquals(0.6f, r.cy, 1e-4f)
        assertEquals(0.2f, r.w, 1e-4f); assertEquals(0.4f, r.h, 1e-4f)
    }

    @Test fun paraQuadroComRegiaoVoltaParaOQuadroInteiro() {
        // Recorte = metade direita/inferior (0.4..1.0); bitmap do recorte 320×320.
        val reg = TamanhoLogica.RectN(0.4f, 0.4f, 1f, 1f)
        val det = p(TamanhoLogica.FACE_FEMALE, 0.5f, 0.5f, 0.1f, 0.2f)
        val c = TamanhoLogica.paraQuadro(listOf(det), 320, 320, reg).single().caixa
        assertEquals(0.4f + 0.5f * 0.6f, c.cx, 1e-4f); assertEquals(0.4f + 0.5f * 0.6f, c.cy, 1e-4f)
        assertEquals(0.06f, c.w, 1e-4f); assertEquals(0.12f, c.h, 1e-4f)
    }

    @Test fun ladrilhosCobremOQuadroTodo() {
        val l = TamanhoLogica.ladrilhos()
        assertEquals(4, l.size)
        assertEquals(0f, l.minOf { it.x0 }, 0f); assertEquals(1f, l.maxOf { it.x1 }, 0f)
        assertEquals(0f, l.minOf { it.y0 }, 0f); assertEquals(1f, l.maxOf { it.y1 }, 0f)
        // Qualquer ponto do quadro cai em ao menos um ladrilho.
        for (x in 0..20) for (y in 0..20) {
            val nx = x / 20f; val ny = y / 20f
            assertTrue(l.any { nx in it.x0..it.x1 && ny in it.y0..it.y1 })
        }
    }

    private fun cantosDentro(partes: List<TamanhoLogica.CaixaParte>, vw: Float, vh: Float, cw: Float, ch: Float, a: TamanhoLogica.ZoomAlvo) {
        val box = TamanhoLogica.caixaCorpoPadded(partes)!!
        for (nx in floatArrayOf(box.x0, box.x1)) for (ny in floatArrayOf(box.y0, box.y1)) {
            val (sx, sy) = TamanhoLogica.projetar(nx, ny, vw, vh, cw, ch, a.zoom, a.panX, a.panY)
            assertTrue("sx=$sx", sx in -2f..(cw + 2f)); assertTrue("sy=$sy", sy in -2f..(ch + 2f))
        }
    }

    @Test fun mulherDistanteSoRostoZoomaECabeCorpoInteiro() {
        // Só o rosto detectado, pequeno (mulher longe), no meio do quadro.
        val rosto = p(TamanhoLogica.FACE_FEMALE, 0.5f, 0.35f, 0.03f, 0.04f)
        val vw = 1080f; val vh = 608f; val cw = 1080f; val ch = 2200f   // vídeo 16:9 em tela vertical (Ajustar)
        val a = TamanhoLogica.enquadrarCorpoInteiro(listOf(rosto), vw, vh, contW = cw, contH = ch)
        assertNotNull("rosto sozinho (mulher longe) tem que enquadrar", a)
        assertTrue("deve ampliar: ${a!!.zoom}", a.zoom > 1.5f)
        cantosDentro(listOf(rosto), vw, vh, cw, ch, a)
        // A estimativa cobre cabeça→pés: o box vai bem abaixo do rosto.
        val box = TamanhoLogica.caixaCorpoPadded(listOf(rosto))!!
        assertTrue(box.y1 > 0.35f + 0.04f * 7f)
        assertTrue(box.y0 <= 0.33f)
    }

    @Test fun soPesNuncaDispara_eSoCabecaMasEstimaCorpo() {
        assertNull(TamanhoLogica.enquadrarCorpoInteiro(listOf(p(TamanhoLogica.FEET_EXPOSED, 0.5f, 0.8f, 0.05f, 0.04f)), 1080f, 1920f))
        assertNull(TamanhoLogica.enquadrarCorpoInteiro(listOf(p(TamanhoLogica.BELLY_EXPOSED, 0.5f, 0.5f, 0.05f, 0.04f)), 1080f, 1920f))
    }

    @Test fun naoAmpliaQuandoElaJaPreencheOQuadro() {
        val grande = listOf(
            p(TamanhoLogica.FACE_FEMALE, 0.5f, 0.2f, 0.3f, 0.2f),
            p(TamanhoLogica.FEMALE_BREAST_EXPOSED, 0.5f, 0.5f, 0.6f, 0.2f),
        )
        val a = TamanhoLogica.enquadrarCorpoInteiro(grande, 1080f, 1920f)!!
        assertEquals(1f, a.zoom, 1e-3f)
    }

    @Test fun anusEBucetaCobertaContamComoPartesFemininas() {
        assertTrue(TamanhoLogica.ANUS_EXPOSED in TamanhoLogica.CLASSES_FEMININAS.toList())
        assertTrue(TamanhoLogica.FEMALE_GENITALIA_COVERED in TamanhoLogica.CLASSES_FEMININAS.toList())
        assertTrue(TamanhoLogica.FACE_MALE !in TamanhoLogica.CLASSES_FEMININAS.toList())
        assertTrue(TamanhoLogica.MALE_BREAST_EXPOSED !in TamanhoLogica.CLASSES_FEMININAS.toList())
    }

    @Test fun propriedadeCaixaPaddedSempreDentroDoContainer() {
        val rnd = java.util.Random(42)
        val classes = intArrayOf(1, 2, 3, 4, 6, 7, 8, 11, 13, 16)
        var ampliou = 0
        repeat(400) {
            val n = 1 + rnd.nextInt(5)
            val partes = List(n) {
                val w = 0.015f + rnd.nextFloat() * 0.06f; val h = 0.015f + rnd.nextFloat() * 0.06f
                p(classes[rnd.nextInt(classes.size)], 0.3f + rnd.nextFloat() * 0.4f, 0.25f + rnd.nextFloat() * 0.3f, w, h)
            }
            val vw = 300f + rnd.nextInt(1200); val vh = 300f + rnd.nextInt(1600)
            val cw = vw + rnd.nextInt(200); val ch = vh + rnd.nextInt(1200)   // Ajustar: view ≤ container
            val a = TamanhoLogica.enquadrarCorpoInteiro(partes, vw, vh, contW = cw, contH = ch) ?: return@repeat
            assertTrue(a.zoom in 1f..2.5f + 1e-3f)
            if (a.zoom > 1.01f) { ampliou++; cantosDentro(partes, vw, vh, cw, ch, a) }
        }
        assertTrue("o gerador deveria produzir zooms", ampliou > 20)
    }

    @Test fun seguirMovimentoComHistereseSemTremer() {
        // Mulher andando devagar: alvos sucessivos quase iguais não reatualizam; salto grande reatualiza.
        var alvo: TamanhoLogica.ZoomAlvo? = null
        var updates = 0
        for (k in 0 until 10) {
            val cx = 0.5f + k * 0.002f
            val partes = listOf(p(TamanhoLogica.FACE_FEMALE, cx, 0.3f, 0.04f, 0.05f), p(TamanhoLogica.FEET_EXPOSED, cx, 0.8f, 0.05f, 0.04f))
            val n = TamanhoLogica.enquadrarCorpoInteiro(partes, 1080f, 1920f)!!
            if (TamanhoLogica.deveAtualizarAlvo(alvo, n)) { alvo = n; updates++ }
        }
        assertTrue("updates=$updates", updates <= 2)
        val longe = TamanhoLogica.enquadrarCorpoInteiro(
            listOf(p(TamanhoLogica.FACE_FEMALE, 0.5f, 0.45f, 0.02f, 0.03f), p(TamanhoLogica.FEET_EXPOSED, 0.5f, 0.6f, 0.03f, 0.02f)), 1080f, 1920f)!!
        assertTrue(TamanhoLogica.deveAtualizarAlvo(alvo, longe))
    }
}
