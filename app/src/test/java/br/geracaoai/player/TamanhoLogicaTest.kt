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
}
