package br.geracaoai.player

import org.junit.Assert.*
import org.junit.Test

class BarraRolagemTest {
    @Test fun soApareceEmListasGrandes() {
        assertFalse(BarraRolagemLogica.deveExistir(20, 16))
        assertFalse(BarraRolagemLogica.deveExistir(0, 0))
        assertTrue(BarraRolagemLogica.deveExistir(500, 16))
    }

    @Test fun progressoVaiDe0a1() {
        assertEquals(0f, BarraRolagemLogica.progresso(0, 0f, 500, 20), 1e-4f)
        assertEquals(1f, BarraRolagemLogica.progresso(480, 0f, 500, 20), 1e-4f)
        assertEquals(0.5f, BarraRolagemLogica.progresso(240, 0f, 500, 20), 1e-3f)
        assertEquals(1f, BarraRolagemLogica.progresso(499, 0.9f, 500, 20), 1e-4f)   // nunca passa de 1
    }

    @Test fun arrastoMapeiaParaIndicesMonotonicosEFimAlcancaOUltimo() {
        var ant = -1
        for (k in 0..100) {
            val i = BarraRolagemLogica.indiceAlvo(k / 100f, 1000, 20)
            assertTrue("monotônico", i >= ant); assertTrue(i in 0..999); ant = i
        }
        assertEquals(0, BarraRolagemLogica.indiceAlvo(0f, 1000, 20))
        assertEquals(999, BarraRolagemLogica.indiceAlvo(1f, 1000, 20))
        assertEquals(0, BarraRolagemLogica.indiceAlvo(0.5f, 0, 0))
        // ida e volta: arrastar até a fração do progresso atual cai no mesmo item
        val f = BarraRolagemLogica.progresso(300, 0f, 1000, 20)
        assertEquals(300, BarraRolagemLogica.indiceAlvo(f, 1000, 20))
    }
}
