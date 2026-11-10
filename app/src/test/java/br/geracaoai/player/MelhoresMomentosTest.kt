package br.geracaoai.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class MelhoresMomentosTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    /** 1 quadro/s; [marcas] = tempo(s) → (categoria → score). */
    private fun analise(durS: Int, marcas: Map<Int, Map<Categoria, Float>>, parcial: Boolean = false, comeca: Int = 0): Analise {
        val ordem = (comeca..durS).toList() + (0 until comeca).toList()   // como o worker: da posição atual, depois o início
        val tempos = LongArray(ordem.size) { ordem[it] * 1000L }
        val sc = Categoria.entries.associateWith { c -> FloatArray(ordem.size) { i -> marcas[ordem[i]]?.get(c) ?: 0f } }
        return Analise(durS * 1000L, tempos, sc, parcial)
    }
    private fun bloco(de: Int, ate: Int, c: Categoria, v: Float = 0.9f) = (de..ate).associateWith { mapOf(c to v) }

    @Test fun pesosBucetaEAnusMaioresQuePeitoEBunda() {
        val l = 0.45f
        val buceta = MelhoresMomentos.pontuarQuadro(mapOf(Categoria.BUCETA to 0.8f), l)
        val anus = MelhoresMomentos.pontuarQuadro(mapOf(Categoria.ANUS to 0.8f), l)
        val peito = MelhoresMomentos.pontuarQuadro(mapOf(Categoria.PEITO to 0.8f), l)
        val bunda = MelhoresMomentos.pontuarQuadro(mapOf(Categoria.BUNDA to 0.8f), l)
        assertTrue(buceta > peito && buceta > bunda)
        assertTrue(anus > peito && anus > bunda)
        assertEquals(buceta, anus, 1e-6f)
    }

    @Test fun abaixoDoLimiarNaoConta_eCoocorrenciaDaBonus() {
        assertEquals(0f, MelhoresMomentos.pontuarQuadro(mapOf(Categoria.BUCETA to 0.3f), 0.45f), 0f)
        val so = MelhoresMomentos.pontuarQuadro(mapOf(Categoria.PEITO to 0.8f), 0.45f)
        val junto = MelhoresMomentos.pontuarQuadro(mapOf(Categoria.PEITO to 0.8f, Categoria.BUCETA to 0.8f), 0.45f)
        assertTrue(junto > so + MelhoresMomentos.PESOS[Categoria.BUCETA]!! * 0.8f)
    }

    @Test fun semMarcacoesNaoRetornaNada() {
        assertTrue(MelhoresMomentos.calcular(analise(300, emptyMap()), 0.45f).isEmpty())
        assertTrue(MelhoresMomentos.calcular(Analise(0, LongArray(0), emptyMap()), 0.45f).isEmpty())
    }

    @Test fun escolheJanelaMaisPesadaEOrdenaPorTempo() {
        val m = bloco(100, 110, Categoria.PEITO, 0.9f) + bloco(200, 210, Categoria.BUCETA, 0.9f)
        val r = MelhoresMomentos.calcular(analise(300, m), 0.45f)
        assertEquals(2, r.size)
        assertEquals(listOf(Categoria.PEITO, Categoria.BUCETA), r.map { it.categoria })
        assertTrue(r[0].inicioMs < r[1].inicioMs)
        assertTrue(r[1].pontuacao > r[0].pontuacao)
    }

    @Test fun janelasNaoSeSobrepoemEFicamEntre3e5() {
        val m = HashMap<Int, Map<Categoria, Float>>()
        for (k in 0 until 8) m += bloco(30 + k * 60, 40 + k * 60, Categoria.BUNDA, 0.8f)
        val r = MelhoresMomentos.calcular(analise(600, m), 0.45f)
        assertTrue("n=${r.size}", r.size in 3..5)
        for ((a, b) in r.zipWithNext()) assertTrue(b.inicioMs >= a.fimMs)
        assertEquals(r.sortedBy { it.inicioMs }, r)
    }

    @Test fun trechoUnicoGrandeNaoGeraVariasJanelasColadas() {
        val r = MelhoresMomentos.calcular(analise(300, bloco(100, 130, Categoria.BUCETA)), 0.45f)
        for ((a, b) in r.zipWithNext()) assertTrue(b.inicioMs >= a.fimMs)
        assertTrue(r.isNotEmpty())
    }

    @Test fun janelaEntre8e15s() {
        assertEquals(8_000L, MelhoresMomentos.tamanhoJanela(30_000))
        assertEquals(15_000L, MelhoresMomentos.tamanhoJanela(3_600_000))
        assertEquals(10_000L, MelhoresMomentos.tamanhoJanela(120_000))
    }

    @Test fun analiseQueComecaNoMeioDoVideoFunciona() {
        val r = MelhoresMomentos.calcular(analise(300, bloco(20, 30, Categoria.ANUS), comeca = 150), 0.45f)
        assertEquals(1, r.size)
        assertTrue(r[0].inicioMs in 19_000L..31_000L)
        assertEquals(Categoria.ANUS, r[0].categoria)
    }

    @Test fun seekNuncaNegativoENuncaPassaDoVideo() {
        val a = analise(60, bloco(0, 5, Categoria.BUCETA) + bloco(55, 60, Categoria.PEITO))
        val r = MelhoresMomentos.calcular(a, 0.45f)
        assertTrue(r.isNotEmpty())
        for (m in r) { assertTrue(m.seekMs >= 0); assertTrue(m.seekMs <= 60_000L); assertTrue(m.fimMs <= 60_000L) }
    }

    @Test fun formatarTempo() {
        assertEquals("0:05", MelhoresMomentos.formatar(5_000))
        assertEquals("1:05", MelhoresMomentos.formatar(65_000))
        assertEquals("1:01:01", MelhoresMomentos.formatar(3_661_000))
    }

    @Test fun cacheEmArquivoDevolveOMesmoResultadoSemRecalcular() {
        MelhoresMomentos.limparMemoria()
        val a = analise(300, bloco(100, 110, Categoria.PEITO) + bloco(200, 210, Categoria.BUCETA))
        val r1 = MelhoresMomentos.obter(ctx, "k1", a, 0.45f)
        MelhoresMomentos.limparMemoria()            // força a leitura do arquivo
        val vazia = Analise(a.duracaoMs, a.tempos, a.scores.mapValues { FloatArray(it.value.size) }) // mesma assinatura, scores zerados
        val r2 = MelhoresMomentos.obter(ctx, "k1", vazia, 0.45f)
        assertEquals(r1, r2)
        assertTrue(r1.isNotEmpty())
        // análise nova (mais quadros) invalida o cache
        MelhoresMomentos.limparMemoria()
        val maior = analise(400, bloco(100, 110, Categoria.PEITO))
        assertNotEquals(r1, MelhoresMomentos.obter(ctx, "k1", maior, 0.45f))
    }

    @Test fun naoAfetaCategoriasForaDosPesos() {
        // Todas as categorias existentes têm peso definido (nada fica de fora por engano).
        assertEquals(Categoria.entries.filter { it.classIndex >= 0 }.toSet(), MelhoresMomentos.PESOS.keys)   // derivada (Bunda aberta) fica de fora dos pesos de propósito
    }
}
