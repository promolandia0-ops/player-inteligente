package br.geracaoai.player

import org.junit.Assert.*
import org.junit.Test

class TamanhoLogicaTest {
    private fun femPeito(area: Float = 0.2f, hits: Int = 1) = TamanhoLogica.Evidencia(
        faceFemale = 0.6f, femaleBreast = 0.7f, areaPeito = area, buttocks = 0.1f, hitsPeito = hits,
    )
    private fun macho() = TamanhoLogica.Evidencia(
        faceMale = 0.8f, maleGen = 0.7f, maleBreast = 0.5f, areaPeito = 0.3f, buttocks = 0.4f, areaBunda = 0.4f, hitsPeito = 1,
    )
    private fun bundaFem(hits: Int = 1) = TamanhoLogica.Evidencia(
        faceFemale = 0.6f, buttocks = 0.7f, areaBunda = 0.25f, femaleBreast = 0.2f, hitsBunda = hits,
    )
    private fun cx(sc: Float, cx: Float, cy: Float, w: Float, h: Float) =
        TamanhoLogica.Caixa(sc, cx, cy, w, h)

    @Test fun peitudaAceitaSoMulher() {
        assertTrue(TamanhoLogica.aceita(TamanhoLogica.Alvo.PEITUDA, femPeito()))
        assertFalse(TamanhoLogica.aceita(TamanhoLogica.Alvo.PEITUDA, macho()))
    }

    @Test fun peitudaAceitaUmFrame() {
        assertTrue(TamanhoLogica.aceita(TamanhoLogica.Alvo.PEITUDA, femPeito(hits = 1)))
    }

    @Test fun coveredOnlyRejeitadoBiquini() {
        val bikiniBunda = TamanhoLogica.Evidencia(
            faceFemale = 0.7f, buttocksCovered = 0.8f, buttocks = 0.2f, areaBunda = 0.1f, hitsBunda = 2,
        )
        assertFalse(TamanhoLogica.aceita(TamanhoLogica.Alvo.RABINHO, bikiniBunda))
    }

    @Test fun rabudaExigeEvidenciaFeminina() {
        assertTrue(TamanhoLogica.aceita(TamanhoLogica.Alvo.RABUDA, bundaFem()))
        assertFalse(TamanhoLogica.aceita(TamanhoLogica.Alvo.RABUDA, TamanhoLogica.Evidencia(buttocks = 0.8f, areaBunda = 0.3f, hitsBunda = 1)))
    }

    @Test fun metricaPeitoSemRefUsaPessoaOuLargura() {
        val peitos = listOf(cx(0.8f, 0.4f, 0.4f, 0.2f, 0.15f))
        // Sem ref e sem pessoa extra → fallback largura > 0
        assertTrue(TamanhoLogica.metricaPeito(peitos, null) > 0f)
        val pessoa = listOf(
            peitos[0],
            cx(0.7f, 0.45f, 0.55f, 0.25f, 0.35f), // torso
        )
        assertTrue(TamanhoLogica.metricaPeito(peitos, null, pessoa = pessoa) > 0f)
    }

    @Test fun metricaPeitoComFaceMaiorQuePequeno() {
        val face = cx(0.8f, 0.45f, 0.2f, 0.12f, 0.14f)
        val g = TamanhoLogica.metricaPeito(
            listOf(cx(0.9f, 0.35f, 0.4f, 0.18f, 0.14f), cx(0.88f, 0.55f, 0.4f, 0.18f, 0.14f)), face,
        )
        val p = TamanhoLogica.metricaPeito(
            listOf(cx(0.9f, 0.35f, 0.4f, 0.06f, 0.05f), cx(0.88f, 0.55f, 0.4f, 0.06f, 0.05f)), face,
        )
        assertTrue(g > p)
    }

    @Test fun metricaBundaFallbackSemFace() {
        val b = cx(0.9f, 0.5f, 0.6f, 0.25f, 0.2f)
        assertTrue(TamanhoLogica.metricaBunda(b, null) > 0f)
    }

    @Test fun metricaConsistenteAceitaUm() {
        assertEquals(1.2f, TamanhoLogica.metricaConsistente(listOf(1.2f), 1), 0.001f)
        assertEquals(0f, TamanhoLogica.metricaConsistente(emptyList(), 1), 0f)
    }

    @Test fun partir35GaranteFatia() {
        val scored = (1..40).map { "v$it" to it.toFloat() }
        val (grande, pequeno) = TamanhoLogica.partirPorTamanho(scored)
        assertTrue(grande.size >= (40 * 0.25).toInt())
        assertTrue(pequeno.size >= (40 * 0.25).toInt())
        assertTrue(grande.map { it.first }.toSet().intersect(pequeno.map { it.first }.toSet()).isEmpty())
    }

    @Test fun split50VideosCadaTilePeloMenos25pct() {
        // 50 vídeos com métrica peito crescente; 35% → ≥25% por lado (com piso).
        val scored = (1..50).map { "p$it" to it.toFloat() }
        val (g, p) = TamanhoLogica.partirPorTamanho(scored)
        val min = (50 * 0.25).toInt()
        assertTrue("grande=${g.size}", g.size >= min)
        assertTrue("pequeno=${p.size}", p.size >= min)
        // Mesma lógica para bunda/buceta com outra distribuição
        val scored2 = (1..50).map { "b$it" to (it * it).toFloat() }
        val (g2, p2) = TamanhoLogica.partirPorTamanho(scored2)
        assertTrue(g2.size >= min && p2.size >= min)
    }

    @Test fun escalaPessoaUniao() {
        val a = cx(0.9f, 0.3f, 0.3f, 0.1f, 0.1f)
        val b = cx(0.8f, 0.7f, 0.7f, 0.1f, 0.1f)
        val esc = TamanhoLogica.escalaPessoa(listOf(a, b))
        assertTrue(esc > 0.3f) // união cobre ~0.5
    }

    @Test fun labelsNudeNetOrdemOficial() {
        assertEquals(1, TamanhoLogica.FACE_FEMALE)
        assertEquals(2, TamanhoLogica.BUTTOCKS_EXPOSED)
        assertEquals(3, TamanhoLogica.FEMALE_BREAST_EXPOSED)
        assertEquals(4, TamanhoLogica.FEMALE_GENITALIA_EXPOSED)
        assertEquals(7, TamanhoLogica.FEET_EXPOSED)
        assertEquals(11, TamanhoLogica.ARMPITS_EXPOSED)
        assertEquals(13, TamanhoLogica.BELLY_EXPOSED)
        assertEquals(3, TamanhoLogica.METRIC_VERSAO)
    }

    @Test fun alvoIdsBatemComTiles() {
        val ids = listOf("__peituda", "__peitinho", "__rabuda", "__rabinho", "__bucetuda", "__bucetinha")
        assertEquals(ids, TamanhoLogica.Alvo.entries.map { it.id })
    }


    @Test fun zoomPanSoQuandoSujeitoPequeno() {
        val pequeno = listOf(TamanhoLogica.Caixa(0.9f, 0.5f, 0.5f, 0.15f, 0.2f))
        val r = TamanhoLogica.zoomPanParaSujeito(pequeno, 1080f, 1920f)
        assertNotNull(r)
        assertTrue(r!!.first in 1f..3f)
        val grande = listOf(TamanhoLogica.Caixa(0.9f, 0.5f, 0.5f, 0.55f, 0.7f))
        assertNull(TamanhoLogica.zoomPanParaSujeito(grande, 1080f, 1920f))
    }

    @Test fun zoomPanVazioNull() {
        assertNull(TamanhoLogica.zoomPanParaSujeito(emptyList(), 100f, 100f))
    }
}
