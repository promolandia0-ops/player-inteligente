package br.geracaoai.player

import org.junit.Assert.*
import org.junit.Test

class AnalisePlanoTest {
    @Test fun passosPorPrecisao() {
        assertEquals(2000L, AnalisePlano.passoGrosso(1)); assertEquals(2000L, AnalisePlano.passoGrosso(2))
        assertEquals(1000L, AnalisePlano.passoGrosso(3)); assertEquals(1000L, AnalisePlano.passoFino(2)); assertEquals(250L, AnalisePlano.passoFino(4))
    }

    @Test fun umaHoraTemPoucosQuadrosGrossosEComecaOndeOUsuarioEsta() {
        val dur = 3_600_000L
        val a = AnalisePlano.alvosGrossos(600_000L, dur, 2000L)
        assertEquals(600_000L, a.first())
        assertEquals(1801, a.size)                         // vs 7201 quadros a 0,5 s antes
        assertEquals(setOf<Long>(), a.groupBy { it }.filter { it.value.size > 1 }.keys)
        assertTrue(a.contains(0L) && a.contains(598_000L) && a.contains(dur))
    }

    @Test fun resolverEncaixaNoKeyframeComTempoRealERepeticoesSomem() {
        val syncs = longArrayOf(0, 1000, 2000, 3000, 10_000)
        val r = AnalisePlano.resolver(listOf(0L, 1900L, 2000L, 6000L), syncs, 1500L)
        assertEquals(listOf(0L, 2000L, 6000L), r.map { it.tMs })     // 1900 e 2000 → mesmo keyframe 2000
        assertFalse(r[0].exato); assertFalse(r[1].exato)
        assertTrue("6000 está a 3 s do keyframe mais perto: pede exato", r[2].exato)
        // sem keyframes: tudo exato (modo antigo)
        assertTrue(AnalisePlano.resolver(listOf(500L, 2500L), null, 1500L).all { it.exato })
        // já feitos não voltam
        assertTrue(AnalisePlano.resolver(listOf(2000L), syncs, 1500L, setOf(2000L)).isEmpty())
    }

    @Test fun refinoSoPreenchePertoDeAcertos() {
        val t = longArrayOf(0, 2000, 4000, 6000, 8000)
        val mx = floatArrayOf(0f, 0f, 0.5f, 0f, 0f)
        val r = AnalisePlano.alvosRefino(t, mx, 0.27f, 2000L, 1000L, 100_000L)
        assertEquals(listOf(3000L, 5000L), r)               // só os dois vãos ao lado do acerto
        assertTrue(AnalisePlano.alvosRefino(t, FloatArray(5), 0.27f, 2000L, 1000L, 100_000L).isEmpty())
        // fora de ordem (começou no meio): continua achando vizinhos
        val t2 = longArrayOf(4000, 6000, 0, 2000)
        assertEquals(listOf(1000L, 3000L), AnalisePlano.alvosRefino(t2, floatArrayOf(0f, 0f, 0f, 0.5f), 0.27f, 2000L, 1000L, 100_000L).sorted())
    }

    @Test fun quadrosQuaseIdenticosReaproveitamPontuacao() {
        val a = IntArray(256) { it }
        assertTrue(AnalisePlano.quaseIgual(a, a.copyOf()))
        assertTrue(AnalisePlano.quaseIgual(a, IntArray(256) { a[it] + (if (it % 8 == 0) 1 else 0) }))
        assertFalse(AnalisePlano.quaseIgual(a, IntArray(256) { a[it] + 10 }))
        assertFalse(AnalisePlano.quaseIgual(null, a))
    }
}
