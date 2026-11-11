package br.geracaoai.player

import br.geracaoai.player.CameraLentaLogica.Guarda
import br.geracaoai.player.CameraLentaLogica.Ritmo
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CameraLentaTest {
    private val ms = 1_000_000L

    @Test fun soAtivaAbaixoDeUmXELigada() {
        assertTrue(CameraLentaLogica.ativa(true, 0.25f)); assertTrue(CameraLentaLogica.ativa(true, 0.5f)); assertTrue(CameraLentaLogica.ativa(true, 0.75f))
        assertFalse("1× = caminho normal intocado", CameraLentaLogica.ativa(true, 1f))
        assertFalse(CameraLentaLogica.ativa(true, 1.25f)); assertFalse(CameraLentaLogica.ativa(true, 2f))
        assertFalse("desligada", CameraLentaLogica.ativa(false, 0.25f)); assertFalse(CameraLentaLogica.ativa(true, 0f))
    }

    @Test fun audioSilenciosoAbaixoDeUmQuartoDeX() {
        assertEquals(0f, CameraLentaLogica.volume(0.1f, true), 0f); assertEquals(0f, CameraLentaLogica.volume(0.125f, false), 0f)
        assertEquals(1f, CameraLentaLogica.volume(0.25f, true), 0f)     // ≥0,25×: ExoPlayer (Sonic) corrige o tom
        assertEquals(1f, CameraLentaLogica.volume(0.5f, true), 0f); assertEquals(1f, CameraLentaLogica.volume(0.75f, true), 0f)
        for (v in listOf(1f, 2f, 3f, 6f)) assertEquals(1f, CameraLentaLogica.volume(v, false), 0f)
    }

    @Test fun velocidadesVao0_1AteSeisECicla() {
        val l = CameraLentaLogica.VELOCIDADES
        assertEquals(listOf(0.1f, 0.125f, 0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f, 5f, 6f), l)
        assertEquals(l.sorted(), l); assertEquals(6f, l.max(), 0f); assertEquals(0.1f, l.min(), 0f)
        assertEquals(0.125f, CameraLentaLogica.proximaVelocidade(0.1f), 0f); assertEquals(6f, CameraLentaLogica.proximaVelocidade(5f), 0f)
        assertEquals("cicla", 0.1f, CameraLentaLogica.proximaVelocidade(6f), 0f)
        assertEquals("valor fora da lista → segue a partir do mais próximo", 1.25f, CameraLentaLogica.proximaVelocidade(1.02f), 0f)
        assertEquals("0.125x", CameraLentaLogica.rotulo(0.125f)); assertEquals("1x", CameraLentaLogica.rotulo(1f)); assertEquals("1.5x", CameraLentaLogica.rotulo(1.5f))
        assertEquals("0.1x", CameraLentaLogica.rotulo(0.1f)); assertEquals("6x", CameraLentaLogica.rotulo(6f))
        // interpolação só abaixo de 1×, em qualquer velocidade da lista
        for (v in l) assertEquals("v=$v", v < 1f, CameraLentaLogica.ativa(true, v))
    }

    @Test fun motorLigaComInterpolacaoOuMelhoria() {
        assertTrue(CameraLentaLogica.motorAtivo(true, false, 0.5f)); assertFalse(CameraLentaLogica.motorAtivo(true, false, 1f))
        assertTrue("melhoria sozinha a 1×", CameraLentaLogica.motorAtivo(true, true, 1f)); assertTrue(CameraLentaLogica.motorAtivo(false, true, 6f))
        assertFalse(CameraLentaLogica.motorAtivo(false, false, 0.25f))
    }

    @Test fun planoBGlobalSuavizadoNoTempoECrossfadeSoSeQuaseParado() {
        val g = CameraLentaLogica.Global()
        g.atualizar(0.1f, 0.1f); assertEquals(0f, g.peso, 0f)                  // poucos blocos ruins: interpola normal
        g.atualizar(0.9f, 0.01f); assertEquals("1 par ruim só leva meio caminho (sem salto)", 0.5f, g.peso, 0.01f); assertTrue(g.crossfade)
        g.atualizar(0.9f, 0.1f); assertEquals(0.75f, g.peso, 0.01f); assertFalse("A e B diferentes → repetição", g.crossfade)
        repeat(6) { g.atualizar(0.1f, 0.1f) }; assertTrue(g.peso < 0.02f)
        g.atualizar(0.42f, 0.01f); assertTrue("meio termo: parcial", g.peso in 0.1f..0.5f)
        g.zerar(); assertEquals(0f, g.peso, 0f)
    }

    @Test fun estatistica2DecodificaFracaoRuimEDiferenca() {
        val b = ByteArray(144 * 4); for (i in 0 until 144) { b[4 * i] = 64; b[4 * i + 1] = 128.toByte() }
        val (r, m) = CameraLentaLogica.decodificarEstat2(b)
        assertEquals(64 / 255f, r, 0.002f); assertEquals(128 / 255f / 8f, m, 0.001f)
        assertEquals(0f to 0f, CameraLentaLogica.decodificarEstat2(ByteArray(0)))
        assertEquals(0.09f * 384, CameraLentaLogica.vetorMax(384), 1e-3f)
    }

    @Test fun guardaDaMelhoriaDesligaPorTempoOuPorQuadrosPerdidos() {
        val g = CameraLentaLogica.GuardaMelhoria(); val bom = 2 * ms; val ruim = 12 * ms
        repeat(4) { assertFalse(g.amostra(ruim)) }; assertFalse("um bom no meio zera", g.amostra(bom))
        repeat(4) { assertFalse(g.amostra(ruim)) }; assertTrue("5 ruins seguidos → desliga", g.amostra(ruim))
        val h = CameraLentaLogica.GuardaMelhoria(); val vs = 16_666_667L
        repeat(60) { assertFalse("60 Hz estável", h.intervalo(vs)) }
        var desligou = false
        for (i in 1..40) if (h.intervalo(if (i % 3 == 0) 3 * vs else vs)) desligou = true
        assertTrue("1 em 3 vsyncs perdido → desliga", desligou)
        val k = CameraLentaLogica.GuardaMelhoria(); repeat(100) { k.intervalo(vs) }
        repeat(60) { assertFalse("pausa/seek longa não conta", k.intervalo(2_000 * ms)) }
    }

    private fun enc(v: Float): Pair<Int, Int> { val s = (v * 4f).coerceIn(0f, 0.9999f) * 255f; val hi = Math.floor(s.toDouble()).toInt(); return hi to Math.round((s - hi) * 255f) }
    private fun estat(custo: Float, contraste: Float, n: Int = 144): ByteArray {
        val b = ByteArray(n * 4); val (ch, cl) = enc(custo); val (kh, kl) = enc(contraste)
        for (i in 0 until n) { b[4 * i] = ch.toByte(); b[4 * i + 1] = cl.toByte(); b[4 * i + 2] = kh.toByte(); b[4 * i + 3] = kl.toByte() }
        return b
    }

    @Test fun estatisticaDecodificaEDetectaCorteComOsNumerosMedidosNoHarness() {
        // valores medidos nos testes de GPU (Mesa): pan 0,193 · pan+objeto 0,048 · movimento rápido demais 0,95 · corte 1,27 · ruído σ=.06 0,635
        val casos = listOf(Triple(0.0043f, 0.0222f, false), Triple(0.0011f, 0.0222f, false), Triple(0.0202f, 0.0318f, false),
            Triple(0.0211f, 0.0222f, true), Triple(0.0283f, 0.0222f, true), Triple(0.1205f, 0.0223f, true))
        for ((c, k, corte) in casos) {
            val (dc, dk) = CameraLentaLogica.decodificarEstat(estat(c, k))
            assertEquals(c, dc, 0.0002f); assertEquals(k, dk, 0.0002f)
            assertEquals("custo=$c contraste=$k", corte, CameraLentaLogica.ehCorte(dc, dk))
        }
        // quadro liso/preto: razão instável → NÃO é corte (interpolar não causa dano)
        assertFalse(CameraLentaLogica.ehCorte(0.002f, 0.001f))
        assertEquals(0f to 0f, CameraLentaLogica.decodificarEstat(ByteArray(0)))
    }

    @Test fun ritmoUsaOPtsDivididoPelaVelocidadeEOTempoNormalizadoVaiDe0a1() {
        val r = Ritmo(); var t = 0L
        assertTrue("1º quadro é descontinuidade (mostra direto)", r.novoQuadro(t, 0L, 0.25f))
        for (k in 1..10) {
            t += 133 * ms + 333_333L                                  // 30 fps a 0,25× = 133,3 ms
            assertFalse(r.novoQuadro(t, k * 33_333L, 0.25f))
        }
        assertEquals(133_333_000.0, r.periodoNs.toDouble(), 2.0 * ms)
        assertEquals(0f, r.t(r.chegadaNs), 0f)
        assertEquals(0.5f, r.t(r.chegadaNs + r.periodoNs / 2), 0.01f)
        assertEquals(1f, r.t(r.chegadaNs + 3 * r.periodoNs), 0f)            // trava em 1 (pausado: segura o quadro)
        assertEquals(0f, r.t(r.chegadaNs - 5 * ms), 0f)
    }

    @Test fun jitterDeChegadaNaoMexeNoPeriodoQuandoHaPts() {
        val r = Ritmo(); var t = 0L; val rnd = java.util.Random(1)
        r.novoQuadro(t, 0L, 0.5f)
        for (k in 1..60) { t += 66_666_000L + (rnd.nextInt(17) - 8) * ms; r.novoQuadro(t, k * 33_333L, 0.5f) }
        assertEquals(66_666_000.0, r.periodoNs.toDouble(), 1.5 * ms)
    }

    @Test fun seekEPausaLongaViramDescontinuidade() {
        val r = Ritmo(); var t = 0L
        r.novoQuadro(t, 0L, 0.5f)
        for (k in 1..5) { t += 66 * ms; r.novoQuadro(t, k * 33_333L, 0.5f) }
        assertTrue("seek p/ trás (pts menor)", r.novoQuadro(t + 70 * ms, 10_000L, 0.5f))
        t += 70 * ms
        assertTrue("seek p/ frente (pts muito maior)", r.novoQuadro(t + 70 * ms, 90_000_000L, 0.5f)); t += 70 * ms
        r.novoQuadro(t + 66 * ms, 90_033_333L, 0.5f); t += 66 * ms
        assertTrue("pausa de 3 s sem pts", r.novoQuadro(t + 3_000 * ms, null, 0.5f))
    }

    @Test fun semPtsUsaMediaMovelDoIntervaloDeChegada() {
        val r = Ritmo(); var t = 0L
        r.novoQuadro(t, null, 0.25f)
        for (k in 1..40) { t += 120 * ms; assertFalse(r.novoQuadro(t, null, 0.25f)) }
        assertEquals(120 * ms.toDouble(), r.periodoNs.toDouble(), 3 * ms.toDouble())
    }

    @Test fun mudarAVelocidadeAdaptaOPeriodoNoQuadroSeguinte() {
        val r = Ritmo(); var t = 0L
        r.novoQuadro(t, 0L, 0.25f); t += 133 * ms; r.novoQuadro(t, 33_333L, 0.25f)
        t += 66 * ms; r.novoQuadro(t, 66_666L, 0.5f)
        assertEquals(66_666_000.0, r.periodoNs.toDouble(), 1.5 * ms)
    }

    @Test fun guardaReduzUmaVezDepoisDesligaEBomResetaContagem() {
        val g = Guarda(); val p = 133 * ms
        repeat(3) { assertEquals(Guarda.Acao.OK, g.registrar(100 * ms, p)) }
        assertEquals("um bom no meio zera a contagem", Guarda.Acao.OK, g.registrar(10 * ms, p))
        repeat(3) { assertEquals(Guarda.Acao.OK, g.registrar(100 * ms, p)) }
        assertEquals(Guarda.Acao.REDUZIR, g.registrar(100 * ms, p)); assertEquals(1, g.nivel)
        repeat(3) { assertEquals(Guarda.Acao.OK, g.registrar(100 * ms, p)) }
        assertEquals(Guarda.Acao.DESLIGAR, g.registrar(100 * ms, p))
        assertTrue(CameraLentaLogica.larguraFluxo(1) < CameraLentaLogica.larguraFluxo(0))
    }

    @Test fun tamanhoDoQuadroLimitaLadoMaiorEFicaPar() {
        assertEquals(1280 to 720, CameraLentaLogica.tamanhoQuadro(1280, 720))
        assertEquals(1920 to 1080, CameraLentaLogica.tamanhoQuadro(1920, 1080))
        assertEquals(1920 to 1080, CameraLentaLogica.tamanhoQuadro(3840, 2160))
        assertEquals(1080 to 1920, CameraLentaLogica.tamanhoQuadro(2160, 3840))
        val (w, h) = CameraLentaLogica.tamanhoQuadro(1081, 721); assertTrue(w % 2 == 0 && h % 2 == 0)
    }

    // ---- contrato Kotlin ↔ shaders (os shaders em si foram compilados como GLSL ES 3.00 no Mesa e validados numericamente) ----
    private fun asset(n: String) = File("src/main/assets/camera_lenta/$n").readText()

    @Test fun shadersExistemSemVersaoPropriaEOMotorUsaSoUniformsQueExistem() {
        val nomes = listOf("quad.vert", "luma.frag", "down.frag", "flow.frag", "smooth.frag", "interp.frag", "stat.frag", "conf.frag", "temporal.frag", "stat2.frag", "enhance.frag")
        for (n in nomes) { val s = asset(n); assertTrue(n, s.length > 50); assertFalse("$n não pode ter #version (o motor injeta)", s.contains("#version")) }
        val kt = File("src/main/java/br/geracaoai/player/CameraLentaGl.kt").readText()
        val arq = mapOf("oes" to null, "luma" to "luma.frag", "down" to "down.frag", "smooth" to "smooth.frag", "interp" to "interp.frag",
            "stat" to "stat.frag", "conf" to "conf.frag", "temporal" to "temporal.frag", "stat2" to "stat2.frag", "enhance" to "enhance.frag", "flow1" to "flow.frag", "flow2" to "flow.frag", "flow3" to "flow.frag")
        Regex("""u\(\s*"([a-z0-9]+)"\s*,\s*"(\w+)"\s*\)""").findAll(kt).forEach { m ->
            val (prog, uni) = m.destructured
            val a = arq[prog] ?: return@forEach
            assertTrue("$prog/$uni existe em $a", Regex("""uniform\s+\w+\s+$uni\s*;""").containsMatchIn(asset(a)))
        }
        Regex("""u\(p,\s*"(\w+)"\)""").findAll(kt).forEach { m ->     // programas de fluxo (nome dinâmico)
            assertTrue("flow/${m.groupValues[1]}", Regex("""uniform\s+\w+\s+${m.groupValues[1]}\s*;""").containsMatchIn(asset("flow.frag")))
        }
    }

    @Test fun apkNaoGanhaModeloNemBibliotecaNativa() {
        val total = File("src/main/assets/camera_lenta").listFiles()!!.sumOf { it.length() }
        assertTrue("shaders ocupam só $total bytes (< 20 KB)", total < 20_000)
    }
}
