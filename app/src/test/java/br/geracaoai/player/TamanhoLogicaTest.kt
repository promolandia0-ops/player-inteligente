package br.geracaoai.player

import org.junit.Assert.*
import org.junit.Test

class TamanhoLogicaTest {
    private fun femPeito(area: Float = 0.2f, hits: Int = 3) = TamanhoLogica.Evidencia(
        faceFemale = 0.6f, femaleBreast = 0.7f, areaPeito = area, buttocks = 0.1f, hitsPeito = hits,
    )
    private fun macho() = TamanhoLogica.Evidencia(
        faceMale = 0.8f, maleGen = 0.7f, maleBreast = 0.5f, areaPeito = 0.3f, buttocks = 0.4f, areaBunda = 0.4f, hitsPeito = 3,
    )
    private fun bundaFem(hits: Int = 3) = TamanhoLogica.Evidencia(
        faceFemale = 0.6f, buttocks = 0.7f, areaBunda = 0.25f, femaleBreast = 0.2f, hitsBunda = hits,
    )
    private fun bundaSo() = TamanhoLogica.Evidencia(
        buttocks = 0.8f, areaBunda = 0.3f, hitsBunda = 3,
    )
    private fun cx(sc: Float, cx: Float, cy: Float, w: Float, h: Float) =
        TamanhoLogica.Caixa(sc, cx, cy, w, h)

    @Test fun peitudaAceitaSoMulher() {
        assertTrue(TamanhoLogica.aceita(TamanhoLogica.Alvo.PEITUDA, femPeito()))
        assertFalse(TamanhoLogica.aceita(TamanhoLogica.Alvo.PEITUDA, macho()))
    }

    @Test fun peitudaExigeTresFrames() {
        assertFalse(TamanhoLogica.aceita(TamanhoLogica.Alvo.PEITUDA, femPeito(hits = 2)))
        assertTrue(TamanhoLogica.aceita(TamanhoLogica.Alvo.PEITUDA, femPeito(hits = 3)))
    }

    @Test fun bucetudaExigeGenitaliaFemininaExposta() {
        val ok = TamanhoLogica.Evidencia(faceFemale = 0.5f, femaleGen = 0.7f, areaBuceta = 0.15f, hitsBuceta = 3)
        val sem = TamanhoLogica.Evidencia(faceFemale = 0.5f, areaBuceta = 0.15f, hitsBuceta = 3)
        assertTrue(TamanhoLogica.aceita(TamanhoLogica.Alvo.BUCETUDA, ok))
        assertFalse(TamanhoLogica.aceita(TamanhoLogica.Alvo.BUCETUDA, sem))
        assertFalse(TamanhoLogica.aceita(TamanhoLogica.Alvo.BUCETUDA, macho()))
    }

    @Test fun coveredOnlyRejeitadoBiquini() {
        val bikiniBunda = TamanhoLogica.Evidencia(
            faceFemale = 0.7f, buttocksCovered = 0.8f, buttocks = 0.2f, areaBunda = 0.1f, hitsBunda = 3,
        )
        assertTrue(TamanhoLogica.soCoberto(TamanhoLogica.Alvo.RABINHO, bikiniBunda))
        assertFalse(TamanhoLogica.aceita(TamanhoLogica.Alvo.RABINHO, bikiniBunda))
    }

    @Test fun rabudaExigeEvidenciaFeminina() {
        assertTrue(TamanhoLogica.aceita(TamanhoLogica.Alvo.RABUDA, bundaFem()))
        assertFalse(TamanhoLogica.aceita(TamanhoLogica.Alvo.RABUDA, bundaSo()))
        assertFalse(TamanhoLogica.aceita(TamanhoLogica.Alvo.RABUDA, macho()))
    }

    @Test fun masculinoDominanteDetecta() {
        assertTrue(TamanhoLogica.masculinoDominante(macho()))
        assertFalse(TamanhoLogica.masculinoDominante(femPeito()))
    }

    @Test fun areaPendenteSoZero() {
        assertTrue(TamanhoLogica.areaPendente(0f))
        assertFalse(TamanhoLogica.areaPendente(-1f))
        assertFalse(TamanhoLogica.areaPendente(0.2f))
    }

    @Test fun acumularPegaMaximosESomaHits() {
        val a = TamanhoLogica.Evidencia(femaleBreast = 0.2f, areaPeito = 0.1f, hitsPeito = 1)
        val b = TamanhoLogica.Evidencia(femaleBreast = 0.8f, areaPeito = 0.05f, faceFemale = 0.9f, hitsPeito = 1)
        val c = TamanhoLogica.acumular(a, b)
        assertEquals(0.8f, c.femaleBreast, 0f)
        assertEquals(0.1f, c.areaPeito, 0f)
        assertEquals(2, c.hitsPeito)
    }

    @Test fun alvoIdsBatemComTiles() {
        val ids = listOf("__peituda", "__peitinho", "__rabuda", "__rabinho", "__bucetuda", "__bucetinha")
        assertEquals(ids, TamanhoLogica.Alvo.entries.map { it.id })
    }

    @Test fun partirPorTamanho30Disjuntos() {
        val scored = (1..20).map { "v$it" to it.toFloat() }
        val (grande, pequeno) = TamanhoLogica.partirPorTamanho(scored)
        assertEquals(6, grande.size) // 30% de 20
        assertEquals(6, pequeno.size)
        assertTrue(grande.map { it.first }.toSet().intersect(pequeno.map { it.first }.toSet()).isEmpty())
        assertTrue(grande.minOf { it.second } >= pequeno.maxOf { it.second })
    }

    @Test fun metricaPeitoSemReferenciaEIncerta() {
        val peitos = listOf(cx(0.8f, 0.4f, 0.4f, 0.2f, 0.15f))
        assertEquals(0f, TamanhoLogica.metricaPeito(peitos, null, null, null, null), 0f)
    }

    @Test fun metricaPeitoDoisBoxesUsaDistancia() {
        // Dois peitos próximos com caixas largas → score alto
        val grandes = listOf(
            cx(0.9f, 0.35f, 0.4f, 0.18f, 0.14f),
            cx(0.88f, 0.55f, 0.4f, 0.18f, 0.14f),
        )
        // Mesma distância, caixas pequenas → score menor
        val pequenos = listOf(
            cx(0.9f, 0.35f, 0.4f, 0.06f, 0.05f),
            cx(0.88f, 0.55f, 0.4f, 0.06f, 0.05f),
        )
        val face = cx(0.8f, 0.45f, 0.2f, 0.12f, 0.14f)
        val g = TamanhoLogica.metricaPeito(grandes, face)
        val p = TamanhoLogica.metricaPeito(pequenos, face)
        assertTrue("g=$g p=$p", g > p)
        assertTrue(g > 0f && p > 0f)
    }

    @Test fun metricaPeitoCloseUpNaoInflaSemEscala() {
        // Close-up: peito ocupa quase o frame, mas COM face também grande → razão estável
        val peitoClose = listOf(cx(0.9f, 0.5f, 0.55f, 0.55f, 0.4f))
        val faceClose = cx(0.85f, 0.5f, 0.2f, 0.35f, 0.4f)
        val peitoLonge = listOf(cx(0.9f, 0.5f, 0.55f, 0.08f, 0.06f))
        val faceLonge = cx(0.85f, 0.5f, 0.25f, 0.05f, 0.06f)
        val a = TamanhoLogica.metricaPeito(peitoClose, faceClose)
        val b = TamanhoLogica.metricaPeito(peitoLonge, faceLonge)
        // Razões área peito/face devem ser da mesma ordem (não 10× por causa do zoom)
        val razao = maxOf(a, b) / minOf(a, b).coerceAtLeast(1e-3f)
        assertTrue("close=$a longe=$b razao=$razao", razao < 4f)
    }

    @Test fun metricaBundaSemReferenciaZero() {
        val b = cx(0.9f, 0.5f, 0.6f, 0.3f, 0.25f)
        assertEquals(0f, TamanhoLogica.metricaBunda(b, null, null, null, null), 0f)
    }

    @Test fun metricaBundaMaiorQueMenor() {
        val face = cx(0.8f, 0.5f, 0.2f, 0.1f, 0.12f)
        val grande = cx(0.9f, 0.5f, 0.65f, 0.28f, 0.22f)
        val pequena = cx(0.9f, 0.5f, 0.65f, 0.08f, 0.07f)
        assertTrue(TamanhoLogica.metricaBunda(grande, face) > TamanhoLogica.metricaBunda(pequena, face))
    }

    @Test fun metricaBucetaUsaFace() {
        val face = cx(0.8f, 0.5f, 0.2f, 0.1f, 0.12f)
        val g = cx(0.9f, 0.5f, 0.7f, 0.08f, 0.06f)
        assertTrue(TamanhoLogica.metricaBuceta(g, face) > 0f)
        assertEquals(0f, TamanhoLogica.metricaBuceta(g, null), 0f)
    }

    @Test fun metricaConsistenteExigeMinEDescartaOutlier() {
        assertEquals(0f, TamanhoLogica.metricaConsistente(listOf(1f, 1.1f), 3), 0f)
        val v = listOf(1f, 1.05f, 0.95f, 1.1f, 10f) // 10 = outlier
        val m = TamanhoLogica.metricaConsistente(v, 3)
        assertTrue(m in 0.9f..1.2f)
    }

    @Test fun metricaRelativaSemFaceAgoraIncerta() {
        assertEquals(0f, TamanhoLogica.metricaRelativa(0.1f, 0f), 0f)
        assertEquals(2f, TamanhoLogica.metricaRelativa(0.1f, 0.05f), 0.001f)
    }

    @Test fun escalaCorpoPriorizaFace() {
        val face = cx(0.9f, 0.5f, 0.2f, 0.1f, 0.12f)
        val belly = cx(0.8f, 0.5f, 0.5f, 0.3f, 0.2f)
        assertEquals(face.diag, TamanhoLogica.escalaCorpo(face, belly, null, null), 0.001f)
        assertTrue(TamanhoLogica.escalaCorpo(null, belly, null, null) > 0f)
        assertEquals(0f, TamanhoLogica.escalaCorpo(null, null, null, null), 0f)
    }

    @Test fun metricVersaoConstante() {
        assertEquals(2, TamanhoLogica.METRIC_VERSAO)
    }
}
