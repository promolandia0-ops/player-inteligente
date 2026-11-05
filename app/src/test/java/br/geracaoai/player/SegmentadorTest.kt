package br.geracaoai.player

import org.junit.Assert.*
import org.junit.Test

class SegmentadorTest {
    private val iv = 500L
    private fun t(n: Int) = LongArray(n) { it * iv }

    @Test fun vazioQuandoNadaPassaDoLimiar() {
        assertTrue(Segmentador.mesclar(t(10), FloatArray(10) { 0.1f }, Categoria.PEITO, 0.5f, iv).isEmpty())
    }
    @Test fun umSegmentoContinuo() {
        val s = FloatArray(10); for (i in 2..5) s[i] = 0.8f
        val r = Segmentador.mesclar(t(10), s, Categoria.BUNDA, 0.5f, iv)
        assertEquals(1, r.size); assertEquals(1000L, r[0].inicioMs); assertEquals(3000L, r[0].fimMs); assertEquals(0.8f, r[0].pico, 1e-6f)
    }
    @Test fun lacunaPequenaEMesclada() {
        val s = FloatArray(20); s[2] = 0.9f; s[5] = 0.9f // gap 1.5 s <= 2 s
        assertEquals(1, Segmentador.mesclar(t(20), s, Categoria.PEITO, 0.5f, iv).size)
    }
    @Test fun lacunaGrandeSepara() {
        val s = FloatArray(30); s[2] = 0.9f; s[12] = 0.7f // gap 5 s
        val r = Segmentador.mesclar(t(30), s, Categoria.BUCETA, 0.5f, iv)
        assertEquals(2, r.size); assertEquals(6000L, r[1].inicioMs)
    }
    @Test fun limiarAjustavel() {
        val s = FloatArray(10); s[3] = 0.4f
        assertEquals(0, Segmentador.mesclar(t(10), s, Categoria.PEITO, 0.5f, iv).size)
        assertEquals(1, Segmentador.mesclar(t(10), s, Categoria.PEITO, 0.3f, iv).size)
    }
    @Test fun fimLimitadoADuracao() {
        val s = FloatArray(4) { 0.9f }
        assertEquals(1700L, Segmentador.mesclar(t(4), s, Categoria.PEITO, 0.5f, iv, duracaoMs = 1700).single().fimMs)
    }
    @Test fun segmentosPorCategoriaOrdenados() {
        val a = Analise(10_000, t(20), mapOf(
            Categoria.PEITO to FloatArray(20).also { it[10] = 0.9f },
            Categoria.BUNDA to FloatArray(20).also { it[2] = 0.9f },
            Categoria.BUCETA to FloatArray(20).also { it[15] = 0.9f }))
        val r = Segmentador.segmentos(a, setOf(Categoria.PEITO, Categoria.BUNDA), 0.5f, iv)
        assertEquals(listOf(Categoria.BUNDA, Categoria.PEITO), r.map { it.categoria })
    }
    @Test fun saltoTresSegundosAntesComClamp() {
        assertEquals(7000L, Segmentador.alvoDoSalto(Segmento(Categoria.PEITO, 10_000, 12_000, 1f), 3000))
        assertEquals(0L, Segmentador.alvoDoSalto(Segmento(Categoria.PEITO, 1_000, 2_000, 1f), 3000))
    }
    @Test fun proximaEAnterior() {
        val segs = listOf(Segmento(Categoria.PEITO, 10_000, 12_000, 1f), Segmento(Categoria.BUNDA, 30_000, 31_000, 1f))
        assertEquals(10_000L, Segmentador.proxima(segs, 0, 3000)!!.inicioMs)
        assertEquals(30_000L, Segmentador.proxima(segs, 7_000, 3000)!!.inicioMs) // já pulou p/ 7 s
        assertNull(Segmentador.proxima(segs, 27_000, 3000))
        assertEquals(10_000L, Segmentador.anterior(segs, 27_000, 3000)!!.inicioMs)
        assertNull(Segmentador.anterior(segs, 7_000, 3000))
    }
}

class GanhoTest {
    @Test fun ate100NaoAmplifica() { assertEquals(1f, Ganho.fator(0.5f), 0f); assertEquals(1f, Ganho.fator(1f), 0f) }
    @Test fun reforcoReal() {
        assertEquals(2f, Ganho.fator(1.5f), 1e-6f); assertEquals(3f, Ganho.fator(2f), 1e-6f); assertEquals(3f, Ganho.fator(5f), 1e-6f)
        assertEquals(9.54f, Ganho.db(2f), 0.01f); assertEquals(6.02f, Ganho.db(1.5f), 0.01f)
    }
    @Test fun limitadorSuave() {
        assertEquals(0.5f, Ganho.limitar(0.5f), 0f)
        assertTrue(Ganho.limitar(3f) <= 1f && Ganho.limitar(3f) > 0.99f); assertTrue(Ganho.limitar(1.2f) > Ganho.limitar(1.0f)) // monotônico
        assertEquals(-Ganho.limitar(2f), Ganho.limitar(-2f), 0f)
    }
    @Test fun amostraPcmSemEstouro() {
        assertEquals(20000.toShort(), Ganho.amostra(20000, 1f))
        val s = Ganho.amostra(8000, 2f); assertEquals(16000.0, s.toDouble(), 2.0) // abaixo do joelho: ganho exato
        assertTrue(Ganho.amostra(30000, 3f) in 26000..32767); assertTrue(Ganho.amostra((-30000).toShort(), 3f) in -32767..-26000)
    }
    @Test fun sistemaContinuo() { assertEquals(0.6f, Ganho.sistema(0.6f), 0f); assertEquals(1f, Ganho.sistema(1.7f), 0f) }
}
