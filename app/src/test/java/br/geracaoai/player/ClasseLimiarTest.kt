package br.geracaoai.player

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/** v1.3.22: limiar por classe (ânus fraco no NudeNet), refino por classe e Bunda aberta mais tolerante. Dados 100% sintéticos. */
class ClasseLimiarTest {
    private val iv = 1000L
    private fun t(n: Int, passo: Long = 2000L) = LongArray(n) { it * passo }

    /** Cena de ânus de 8 quadros (16 s) com scores brutos fracos 0,18–0,42, no meio de 60 quadros de fundo (0–0,12). */
    private fun cenaFraca(seed: Int): Pair<LongArray, FloatArray> {
        val r = Random(seed); val n = 60
        val s = FloatArray(n) { r.nextFloat() * 0.12f }
        for (i in 25 until 33) s[i] = 0.18f + r.nextFloat() * 0.24f
        return t(n) to s
    }

    @Test fun limiarDoAnusEMenorEAsOutrasCategoriasNaoMudam() {
        assertEquals(0.27f, Limiares.de(Categoria.ANUS, 0.45f), 1e-4f)
        for (c in listOf(Categoria.PEITO, Categoria.BUNDA, Categoria.BUCETA, Categoria.BUNDA_ABERTA)) assertEquals(0.45f, Limiares.de(c, 0.45f), 1e-6f)
        assertTrue("slider no mínimo não derruba o ânus a ~0", Limiares.de(Categoria.ANUS, 0.15f) >= 0.15f - 1e-6f)
        assertTrue(Limiares.de(Categoria.ANUS, 0.9f) <= 0.9f)
    }

    @Test fun anusFracoQueAntesNuncaAparecia_agoraViraCena_semInventarCenaNoFundo() {
        var antes = 0; var depois = 0; var fundoSo = 0
        for (seed in 1..40) {
            val (tempos, s) = cenaFraca(seed)
            val zeros = FloatArray(s.size)
            val sc = mapOf(Categoria.ANUS to s, Categoria.PEITO to zeros, Categoria.BUNDA to zeros, Categoria.BUCETA to zeros, Categoria.BUNDA_ABERTA to zeros)
            val a = Analise(120_000, tempos, sc)
            // lógica antiga: limiar único
            if (Segmentador.mesclar(tempos, s, Categoria.ANUS, 0.45f, iv, duracaoMs = a.duracaoMs).isNotEmpty()) antes++
            val seg = Segmentador.segmentos(a, setOf(Categoria.ANUS), 0.45f, iv)
            if (seg.any { it.inicioMs in 48_000L..66_000L }) depois++
            if (seg.any { it.fimMs < 40_000L || it.inicioMs > 80_000L }) fundoSo++
            // fundo sozinho (sem cena) não gera nada
            val fundo = FloatArray(s.size) { s[it].coerceAtMost(0.12f) }
            assertTrue(Segmentador.segmentos(Analise(120_000, tempos, sc + (Categoria.ANUS to fundo)), setOf(Categoria.ANUS), 0.45f, iv).isEmpty())
        }
        println("ânus fraco (40 sequências sintéticas): achava $antes → agora $depois; cenas falsas no fundo: $fundoSo")
        assertTrue("antes quase nunca achava: $antes", antes <= 4)
        assertTrue("agora acha quase sempre: $depois", depois >= 38)
        assertEquals(0, fundoSo)
    }

    @Test fun refinoUsaAEscalaDoAnusEAlcancaSinaisFracos() {
        val tempos = t(5)
        val anus = floatArrayOf(0f, 0f, 0.19f, 0f, 0f)           // sinal fraco: 0,19 < 0,27 (antigo gatilho do refino)
        val antigo = AnalisePlano.alvosRefino(tempos, anus, 0.45f * 0.6f, 2000L, 1000L, 100_000L)
        assertTrue("antes: sem refino", antigo.isEmpty())
        val mx = FloatArray(5) { anus[it] / Limiares.fator(Categoria.ANUS) }   // como o Analisador faz
        val novo = AnalisePlano.alvosRefino(tempos, mx, 0.45f * 0.6f, 2000L, 1000L, 100_000L)
        assertEquals(listOf(3000L, 5000L), novo)
        // as outras categorias seguem na escala original (fator 1)
        assertEquals(1f, Limiares.fator(Categoria.PEITO), 0f)
    }

    @Test fun bundaAbertaAchaBucetaComAnusFracoNaJanelaDe25s() {
        // buceta forte (0,6) e ânus fraco (0,3: cru abaixo de 0,45) a 2 s de distância
        val tempos = t(6)
        val buceta = floatArrayOf(0f, 0f, 0.6f, 0f, 0f, 0f); val anus = floatArrayOf(0f, 0f, 0f, 0.3f, 0f, 0f)
        val d = Derivadas.bundaAberta(tempos, buceta, anus)
        assertTrue("passa o limiar 0,45: ${d[2]}/${d[3]}", d[2] >= 0.45f && d[3] >= 0.45f)
        assertEquals(0f, d[0], 0f); assertEquals(0f, d[5], 0f)
    }

    @Test fun bundaAbertaPorTras_bucetaMaisBundaExpostaSemAnusDetectado() {
        val tempos = t(5)
        val buceta = floatArrayOf(0f, 0.6f, 0f, 0f, 0f); val bunda = floatArrayOf(0f, 0f, 0.7f, 0f, 0f); val anus = FloatArray(5)
        assertTrue("sem a bunda não pontua", Derivadas.bundaAberta(tempos, buceta, anus).all { it == 0f })
        val d = Derivadas.bundaAberta(tempos, buceta, anus, bunda)
        assertTrue(d[1] >= 0.45f && d[2] >= 0.45f)
        assertEquals("peso menor que o caminho com cu", Derivadas.PESO_BUNDA * 0.6f, d[1], 1e-4f)
        // bunda fraca + buceta fraca juntas NÃO bastam (evita marcar toda cena por trás)
        val fraco = Derivadas.bundaAberta(tempos, floatArrayOf(0f, 0.4f, 0f, 0f, 0f), anus, floatArrayOf(0f, 0f, 0.4f, 0f, 0f))
        assertTrue(fraco.all { it < 0.45f })
    }

    @Test fun categoriasQueNaoSaoAnusContinuamComLimiarUnico() {
        val tempos = t(10)
        val s = FloatArray(10) { if (it in 4..6) 0.3f else 0f }
        for (c in listOf(Categoria.PEITO, Categoria.BUNDA, Categoria.BUCETA)) {
            val a = Analise(20_000, tempos, mapOf(c to s))
            assertTrue("$c a 0,30 < 0,45 segue sem cena", Segmentador.segmentos(a, setOf(c), 0.45f, iv).isEmpty())
        }
    }
}
