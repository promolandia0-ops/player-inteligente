package br.geracaoai.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BundaAbertaTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun t(vararg s: Int) = LongArray(s.size) { s[it] * 500L }

    @Test fun mesmoQuadroPegaOMenorDosDois() {
        val r = Derivadas.bundaAberta(t(0, 1, 2), floatArrayOf(0.9f, 0f, 0f), floatArrayOf(0.6f, 0f, 0f))
        assertEquals(0.6f, r[0], 1e-4f); assertEquals(0f, r[1], 1e-4f)
    }

    @Test fun quadrosDiferentesDentroDaJanelaContamEForaNao() {
        // buceta no quadro 0 (t=0), cu no quadro 2 (t=1000 ms ≤ 1500): os DOIS quadros pontuam
        val a = Derivadas.bundaAberta(t(0, 1, 2, 3), floatArrayOf(0.9f, 0f, 0f, 0f), floatArrayOf(0f, 0f, 0.7f, 0f))
        assertEquals(0.7f, a[0], 1e-4f); assertEquals(0.7f, a[2], 1e-4f)
        assertEquals("quadro sem buceta nem cu não recebe: cena não se alarga", 0f, a[1], 1e-4f)
        // cu a 2,5 s da buceta: fora da janela
        val b = Derivadas.bundaAberta(t(0, 5), floatArrayOf(0.9f, 0f), floatArrayOf(0f, 0.7f))
        assertEquals(0f, b[0], 1e-4f); assertEquals(0f, b[1], 1e-4f)
    }

    @Test fun soUmaParteNuncaPontua() {
        val r = Derivadas.bundaAberta(t(0, 1, 2), floatArrayOf(0.9f, 0.8f, 0.9f), FloatArray(3))
        assertTrue(r.all { it == 0f })
        assertTrue(Derivadas.bundaAberta(t(0, 1), FloatArray(2), floatArrayOf(0.9f, 0.9f)).all { it == 0f })
        assertEquals(0, Derivadas.bundaAberta(LongArray(0), FloatArray(0), FloatArray(0)).size)
    }

    @Test fun temposForaDeOrdemFuncionam() {
        // análise começa no meio do vídeo e depois volta ao 0 → tempos [3000, 3500, 0, 500]
        val tempos = longArrayOf(3000, 3500, 0, 500)
        val r = Derivadas.bundaAberta(tempos, floatArrayOf(0.9f, 0f, 0f, 0f), floatArrayOf(0f, 0.8f, 0f, 0f))
        assertEquals(0.8f, r[0], 1e-4f); assertEquals(0.8f, r[1], 1e-4f); assertEquals(0f, r[2], 1e-4f); assertEquals(0f, r[3], 1e-4f)
    }

    @Test fun carregarDeriveDaAnaliseSalvaGeraSegmentoEContagemComoAsOutrasCategorias() {
        Cache.limpar(ctx)
        val n = 10; val tempos = LongArray(n) { it * 500L }
        val sc = Categoria.entries.associateWith { c ->
            FloatArray(n) { i -> when (c) { Categoria.BUCETA -> if (i in 3..5) 0.8f else 0f; Categoria.ANUS -> if (i in 4..6) 0.7f else 0f; else -> 0f } }
        }
        Cache.salvar(ctx, "k-bunda-aberta", Analise(5000L, tempos, sc), 500L)
        val (a, iv) = Cache.carregar(ctx, "k-bunda-aberta")!!
        val d = a.scores[Categoria.BUNDA_ABERTA]!!
        assertEquals(n, d.size)
        assertTrue("quadros 3..6 têm as duas partes (na janela)", (3..6).all { d[it] >= 0.7f })
        assertEquals(0f, d[0], 1e-4f); assertEquals(0f, d[9], 1e-4f)
        val segs = Segmentador.segmentos(a, Categoria.entries.toSet(), 0.45f, iv).filter { it.categoria == Categoria.BUNDA_ABERTA }
        assertEquals(1, segs.size)
        assertTrue(segs[0].inicioMs <= 1500 && segs[0].fimMs >= 3000)
        // as categorias de verdade seguem intactas
        assertEquals(0.8f, a.scores[Categoria.BUCETA]!![4], 1e-4f)
    }

    @Test fun analiseAntigaSemAChaveContinuaAbrindoEDerivaNaCarga() {
        Cache.limpar(ctx)
        // arquivo no formato antigo (sem BUNDA_ABERTA)
        val dir = java.io.File(ctx.filesDir, "analises").apply { mkdirs() }
        java.io.File(dir, "antiga.json").writeText("""{"v":1,"duracao":2000,"intervalo":500,"parcial":false,"t":[0,500,1000,1500],
            "PEITO":[0,0,0,0],"BUNDA":[0,0,0,0],"BUCETA":[900,0,0,0],"ANUS":[0,800,0,0]}""")
        val (a, _) = Cache.carregar(ctx, "antiga")!!
        assertEquals(0.8f, a.scores[Categoria.BUNDA_ABERTA]!![0], 1e-3f)
        assertEquals(4, a.scores.size - 0 - (Categoria.entries.size - 4))
    }

    @Test fun categoriaDerivadaNaoEPontuadaPeloDetectorNemEntraNosChipsDaGaleria() {
        assertEquals(-1, Categoria.BUNDA_ABERTA.classIndex)
        assertEquals("Bunda aberta", Categoria.BUNDA_ABERTA.rotulo)
        assertEquals(listOf("Peito", "Bunda", "Buceta", "Cuzinho"), Categoria.entries.filter { it.classIndex >= 0 }.map { it.rotulo })
    }
}
