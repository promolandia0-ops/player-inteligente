package br.geracaoai.player

import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ExplicitoTest {
    private fun sc(n: Int, f: (Categoria, Int) -> Float) = Categoria.entries.associateWith { c -> FloatArray(n) { f(c, it) } }
    private fun t(n: Int, passo: Long = 2000L) = LongArray(n) { it * passo }
    private fun v(id: Int, mod: Long = id.toLong()) = Video(Uri.parse("content://media/external/video/media/$id"), "v$id", 60_000L, 1L, dataMod = mod)

    @Test fun maisGenitaliaQuePeitoEMaisTempoExplicitoPontuaMais() {
        val n = 100; val dur = 200_000L
        val peito = Explicito.pontuar(t(n), sc(n) { c, i -> if (c == Categoria.PEITO && i < 20) 0.8f else 0f }, dur)
        val bucet = Explicito.pontuar(t(n), sc(n) { c, i -> if (c == Categoria.BUCETA && i < 20) 0.8f else 0f }, dur)
        val bucetMais = Explicito.pontuar(t(n), sc(n) { c, i -> if (c == Categoria.BUCETA && i < 60) 0.8f else 0f }, dur)
        assertTrue("genitália (3,0) > peito (1,5): $bucet vs $peito", bucet > peito * 1.9f)
        assertTrue("mais tempo explícito > menos", bucetMais > bucet * 2.5f)
        assertEquals(0f, Explicito.pontuar(t(n), sc(n) { _, _ -> 0f }, dur), 0f)
        assertEquals(0f, Explicito.pontuar(LongArray(0), emptyMap(), 1000L), 0f)
    }

    @Test fun normalizaPelaDuracaoVideoCurtoDensoGanhaDoLongoDiluido() {
        val curto = Explicito.pontuar(t(10), sc(10) { c, _ -> if (c == Categoria.BUCETA) 0.8f else 0f }, 20_000L)   // 20 s todo explícito
        val longo = Explicito.pontuar(t(1800), sc(1800) { c, i -> if (c == Categoria.BUCETA && i < 10) 0.8f else 0f }, 3_600_000L)   // 20 s em 1 h
        assertTrue("$curto vs $longo", curto > longo * 10)
    }

    @Test fun anusFracoContaNaEscalaDoLimiarPorClasse() {
        val fraco = Explicito.pontuar(t(20), sc(20) { c, _ -> if (c == Categoria.ANUS) 0.30f else 0f }, 40_000L)   // 0,30 ≥ 0,27
        assertTrue(fraco > 0f)
        val abaixo = Explicito.pontuar(t(20), sc(20) { c, _ -> if (c == Categoria.ANUS) 0.20f else 0f }, 40_000L)
        assertEquals(0f, abaixo, 0f)
        val peito30 = Explicito.pontuar(t(20), sc(20) { c, _ -> if (c == Categoria.PEITO) 0.30f else 0f }, 40_000L)
        assertEquals("peito a 0,30 não passa o limiar de 0,45", 0f, peito30, 0f)
    }

    @Test fun temposForaDeOrdemDaMesmaNota() {
        val n = 50
        val a = Explicito.pontuar(t(n), sc(n) { c, i -> if (c == Categoria.BUNDA && i in 10..30) 0.7f else 0f }, 100_000L)
        val idx = (0 until n).toList().shuffled(java.util.Random(3))
        val tt = t(n); val s = sc(n) { c, i -> if (c == Categoria.BUNDA && i in 10..30) 0.7f else 0f }
        val b = Explicito.pontuar(LongArray(n) { tt[idx[it]] }, s.mapValues { (_, arr) -> FloatArray(n) { arr[idx[it]] } }, 100_000L)
        assertEquals(a, b, 1e-4f)
    }

    @Test fun ordenarAnalisadosPorScoreESemAnaliseNoFimNaOrdemAtual() {
        val vs = listOf(v(1, 10), v(2, 50), v(3, 30), v(4, 40), v(5, 20))
        val pontos = mapOf("content://media/external/video/media/1" to 0.5f, "content://media/external/video/media/3" to 2.0f,
            "content://media/external/video/media/5" to 0f)
        val r = Explicito.ordenar(vs, pontos).map { it.nome }
        assertEquals(listOf("v3", "v1", "v5", "v2", "v4"), r)    // analisados por score (v5 = analisado sem acerto), depois v2(50) v4(40) por mais novos
        // chave estável: external_primary casa com external
        val p = Explicito.ordenar(listOf(Video(Uri.parse("content://media/external_primary/video/media/9"), "x", 1, 1), v(8)),
            mapOf("content://media/external/video/media/9" to 1f))
        assertEquals("x", p.first().nome)
    }
}
