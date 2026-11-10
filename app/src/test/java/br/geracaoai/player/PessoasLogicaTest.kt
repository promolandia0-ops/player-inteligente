package br.geracaoai.player

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class PessoasLogicaTest {
    private val dim = 128
    private fun unit(r: Random) = RostoMat.normalizar(FloatArray(dim) { (r.nextDouble() * 2 - 1).toFloat() + (r.nextDouble() * 2 - 1).toFloat() })
    /** Observação ruidosa de uma identidade; sigma ≈ 0,065 → cosseno entre 2 obs. da MESMA pessoa ≈ 0,65 (como o SFace no LFW). */
    private fun obsDe(id: FloatArray, r: Random, sigma: Float = 0.065f) = RostoMat.normalizar(FloatArray(dim) { id[it] + sigma * r.nextGaussian() })
    private fun Random.nextGaussian(): Float { val u = nextDouble().coerceAtLeast(1e-9); val v = nextDouble(); return (Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v)).toFloat() }
    private fun o(e: FloatArray, ref: Int = 0, score: Float = 0.95f) = Agrupamento.Obs(e, score, 100f, ref)

    @Test fun mesmaPessoaNoMesmoVideoViraUmaTrilhaEOutraPessoaViraOutra() {
        val r = Random(1); val a = unit(r); val b = unit(r)
        val t = Agrupamento.tracar(listOf(o(obsDe(a, r, 0.04f), 0), o(obsDe(a, r, 0.04f), 1), o(obsDe(a, r, 0.04f), 2), o(obsDe(b, r, 0.04f), 3), o(obsDe(b, r, 0.04f), 4)))
        assertEquals(2, t.size)
        assertEquals(setOf(3, 2), t.map { it.obs.size }.toSet())
    }

    @Test fun trilhaDeUmQuadroSoVaiSeOScoreForAlto() {
        val r = Random(2); val a = unit(r)
        assertEquals(1, Agrupamento.tracar(listOf(o(obsDe(a, r), score = 0.95f))).size)
        assertEquals(0, Agrupamento.tracar(listOf(o(obsDe(a, r), score = 0.82f))).size)
    }

    @Test fun cemVideosDeVintePessoasAgrupamPuroSemJuntarErrado() {
        val r = Random(7); val ids = List(20) { unit(r) }
        val somas = HashMap<Long, FloatArray>(); val dono = HashMap<Long, MutableList<Int>>(); var prox = 1L
        val videos = (0 until 100).map { it to (it * 7 % 20) }.shuffled(r)
        for ((_, p) in videos) {
            for (trilha in Agrupamento.tracar(List(3) { o(obsDe(ids[p], r), it) })) {
                val alvo = Agrupamento.melhorPessoa(somas, trilha.emb) ?: prox++.also { somas[it] = FloatArray(dim) }
                val s = somas[alvo]!!; for (k in 0 until dim) s[k] += trilha.emb[k]
                dono.getOrPut(alvo) { ArrayList() } += p
            }
        }
        val errados = dono.values.sumOf { l -> l.size - l.groupBy { it }.maxOf { it.value.size } }
        assertEquals("nenhuma trilha na pessoa errada", 0, errados)
        assertTrue("sem fragmentar demais: ${dono.size} grupos p/ 20 pessoas", dono.size <= 30)
    }

    @Test fun rostoDeAlguemMuitoDiferenteNuncaEntraEmPessoaExistenteESeparaEmVezDeJuntar() {
        val r = Random(3); val a = unit(r); val outro = unit(r)
        val somas = mutableMapOf(1L to obsDe(a, r))
        assertNull(Agrupamento.melhorPessoa(somas, obsDe(outro, r)))
        assertEquals(1L, Agrupamento.melhorPessoa(somas, obsDe(a, r)))
        // zona cinzenta (similaridade ~0,5, abaixo do limiar 0,55): cria pessoa nova
        val cinza = RostoMat.normalizar(FloatArray(dim) { a[it] * 0.5f + outro[it] * 0.866f })
        assertTrue(RostoMat.cos(somas[1]!!.let { RostoMat.normalizar(it) }, cinza) < Agrupamento.LIMIAR_ATRIBUIR)
        assertNull(Agrupamento.melhorPessoa(somas, cinza))
    }

    @Test fun deteccaoYunetDecodificaCaixaEPontos() {
        // 1 acerto no stride 8, célula (r=10,c=20): centro (20+0)*8=160, (10+0)*8=80; w=h=exp(ln 6)*8=48
        fun saida(n: String): FloatArray {
            val st = n.substringAfterLast('_').toInt(); val cels = (640 / st) * (640 / st)
            return when (n.substringBefore('_')) {
                "cls", "obj" -> FloatArray(cels).also { if (st == 8) it[10 * 80 + 20] = 0.95f }
                "bbox" -> FloatArray(cels * 4).also { if (st == 8) { val i = (10 * 80 + 20) * 4; it[i + 2] = Math.log(6.0).toFloat(); it[i + 3] = Math.log(6.0).toFloat() } }
                else -> FloatArray(cels * 10)
            }
        }
        val r = RostoMat.decodificar(::saida, escala = 1f).single()
        assertEquals(136f, r.x0, 0.01f); assertEquals(184f, r.x1, 0.01f); assertEquals(56f, r.y0, 0.01f); assertEquals(0.95f, r.score, 0.001f)
        val r2 = RostoMat.decodificar(::saida, escala = 0.5f).single()                // escala do letterbox: coords voltam ao quadro original
        assertEquals(272f, r2.x0, 0.01f)
    }

    @Test fun rollEYawDistinguemRostoDeFrenteDePerfil() {
        val frente = floatArrayOf(40f, 50f, 70f, 50f, 55f, 65f, 43f, 80f, 67f, 80f)
        val perfil = floatArrayOf(40f, 50f, 70f, 50f, 72f, 65f, 43f, 80f, 67f, 80f)
        assertTrue(RostoMat.rollYaw(frente).second < RostoMotor.YAW_MAX)
        assertTrue(RostoMat.rollYaw(perfil).second > RostoMotor.YAW_MAX)
        assertTrue(RostoMat.rollYaw(floatArrayOf(40f, 50f, 70f, 80f, 55f, 65f, 43f, 80f, 67f, 80f)).first > RostoMotor.ROLL_MAX)
    }

    @Test fun leituraAdaptivaParaCedoComRostoEContinuaQuandoFaltaProvaOuAchaPouco() {
        val r = Random(11); val a = unit(r); val b = unit(r)
        val o = { id: FloatArray, k: Int -> o(obsDe(id, r, 0.04f), k) }
        assertFalse("sem rosto nos 4 primeiros quadros: ainda procura", Agrupamento.podeParar(emptyList(), 4))
        assertTrue("sem rosto em 5 quadros espalhados: desiste (vídeo sem rosto)", Agrupamento.podeParar(emptyList(), Agrupamento.QUADROS_SEM_ROSTO))
        assertFalse(Agrupamento.podeParar(listOf(o(a, 0), o(a, 1)), 2))                     // só 2 rostos
        assertTrue("3 rostos da mesma pessoa: para", Agrupamento.podeParar(listOf(o(a, 0), o(a, 1), o(a, 2)), 3))
        assertFalse("outra pessoa vista só 1×: continua procurando", Agrupamento.podeParar(listOf(o(a, 0), o(a, 1), o(a, 2), o(b, 3)), 4))
        assertTrue("…até ela também ter 2", Agrupamento.podeParar(listOf(o(a, 0), o(a, 1), o(a, 2), o(b, 3), o(b, 4)), 5))
    }

    @Test fun simulacaoDeVideosGastaMuitoMenosQuadrosQueOsOitoFixos() {
        val r = Random(5); val ids = List(10) { unit(r) }
        var quadros = 0; var videos = 0; var semRosto = 0; var pessoasAchadas = 0; var esperadas = 0
        for (v in 0 until 100) {
            val tem = v % 5 != 0          // 20% dos vídeos sem rosto
            val p = ids[v % 10]; val obs = ArrayList<Agrupamento.Obs>(); var lidos = 0
            for (q in Agrupamento.ORDEM_QUADROS) {
                lidos++
                if (tem && r.nextFloat() < 0.7f) obs += o(obsDe(p, r, 0.04f), obs.size)    // rosto bom em 70% dos quadros
                if (Agrupamento.podeParar(obs, lidos)) break
            }
            quadros += lidos; videos++
            if (!tem) semRosto++ else { esperadas++; if (Agrupamento.tracar(obs).isNotEmpty()) pessoasAchadas++ }
        }
        println("leitura adaptativa: ${"%.1f".format(quadros.toFloat() / videos)} quadros/vídeo (antes 8); pessoas achadas $pessoasAchadas de $esperadas")
        assertTrue("média ${quadros.toFloat() / videos} quadros/vídeo", quadros.toFloat() / videos <= 4.6f)
        assertTrue("achou $pessoasAchadas/$esperadas", pessoasAchadas >= esperadas * 0.97)
    }
}
