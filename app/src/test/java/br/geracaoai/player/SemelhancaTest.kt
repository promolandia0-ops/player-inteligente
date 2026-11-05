package br.geracaoai.player

import org.junit.Assert.*
import org.junit.Test

class SemelhancaTest {
    @Test fun packUnpack() {
        val v = FloatArray(Semelhanca.DIM) { it * 0.01f }
        val back = Semelhanca.unpack(Semelhanca.pack(v))!!
        assertEquals(v.size, back.size)
        for (i in v.indices) assertEquals(v[i], back[i], 1e-5f)
    }

    @Test fun distanciaMenorParaVizinhos() {
        fun emb(vararg xs: Float): FloatArray {
            val it = FloatArray(Semelhanca.DIM) { i -> if (i < xs.size) xs[i] else 0f }
            var s = 0f; for (x in it) s += x * x
            val n = kotlin.math.sqrt(s).coerceAtLeast(1e-6f)
            for (i in it.indices) it[i] /= n
            return it
        }
        val a = emb(1f, 0f); val b = emb(0.95f, 0.05f); val c = emb(0f, 1f)
        assertTrue(Semelhanca.distancia(a, b) < Semelhanca.distancia(a, c))
        assertTrue(Semelhanca.distancia(a, b) < Semelhanca.distancia(b, c))
    }

    @Test fun dimensaoFixa() { assertEquals(96, Semelhanca.DIM) }
}
