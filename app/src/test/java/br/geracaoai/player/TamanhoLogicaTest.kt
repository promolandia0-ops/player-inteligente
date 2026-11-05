package br.geracaoai.player

import org.junit.Assert.*
import org.junit.Test

class TamanhoLogicaTest {
    private fun femPeito(area: Float = 0.2f, hits: Int = 2) = TamanhoLogica.Evidencia(
        faceFemale = 0.6f, femaleBreast = 0.7f, areaPeito = area, buttocks = 0.1f, hitsPeito = hits,
    )
    private fun macho() = TamanhoLogica.Evidencia(
        faceMale = 0.8f, maleGen = 0.7f, maleBreast = 0.5f, areaPeito = 0.3f, buttocks = 0.4f, areaBunda = 0.4f, hitsPeito = 2,
    )
    private fun bundaFem(hits: Int = 2) = TamanhoLogica.Evidencia(
        faceFemale = 0.6f, buttocks = 0.7f, areaBunda = 0.25f, femaleBreast = 0.2f, hitsBunda = hits,
    )
    private fun bundaSo() = TamanhoLogica.Evidencia(
        buttocks = 0.8f, areaBunda = 0.3f, hitsBunda = 2,
    )

    @Test fun peitudaAceitaSoMulher() {
        assertTrue(TamanhoLogica.aceita(TamanhoLogica.Alvo.PEITUDA, femPeito()))
        assertFalse(TamanhoLogica.aceita(TamanhoLogica.Alvo.PEITUDA, macho()))
    }

    @Test fun peitudaExigeDoisFrames() {
        assertFalse(TamanhoLogica.aceita(TamanhoLogica.Alvo.PEITUDA, femPeito(hits = 1)))
        assertTrue(TamanhoLogica.aceita(TamanhoLogica.Alvo.PEITUDA, femPeito(hits = 2)))
    }

    @Test fun bucetudaExigeGenitaliaFemininaExposta() {
        val ok = TamanhoLogica.Evidencia(faceFemale = 0.5f, femaleGen = 0.7f, areaBuceta = 0.15f, hitsBuceta = 2)
        val sem = TamanhoLogica.Evidencia(faceFemale = 0.5f, areaBuceta = 0.15f, hitsBuceta = 2)
        assertTrue(TamanhoLogica.aceita(TamanhoLogica.Alvo.BUCETUDA, ok))
        assertFalse(TamanhoLogica.aceita(TamanhoLogica.Alvo.BUCETUDA, sem))
        assertFalse(TamanhoLogica.aceita(TamanhoLogica.Alvo.BUCETUDA, macho()))
    }

    @Test fun coveredOnlyRejeitadoBiquini() {
        val bikiniBunda = TamanhoLogica.Evidencia(
            faceFemale = 0.7f, buttocksCovered = 0.8f, buttocks = 0.2f, areaBunda = 0.1f, hitsBunda = 3,
        )
        val bikiniBuceta = TamanhoLogica.Evidencia(
            faceFemale = 0.7f, genCovered = 0.9f, femaleGen = 0.1f, areaBuceta = 0.05f, hitsBuceta = 3,
        )
        val bikiniPeito = TamanhoLogica.Evidencia(
            faceFemale = 0.7f, breastCovered = 0.85f, femaleBreast = 0.2f, areaPeito = 0.1f, hitsPeito = 3,
        )
        assertTrue(TamanhoLogica.soCoberto(TamanhoLogica.Alvo.RABINHO, bikiniBunda))
        assertFalse(TamanhoLogica.aceita(TamanhoLogica.Alvo.RABINHO, bikiniBunda))
        assertFalse(TamanhoLogica.aceita(TamanhoLogica.Alvo.BUCETINHA, bikiniBuceta))
        assertFalse(TamanhoLogica.aceita(TamanhoLogica.Alvo.PEITINHO, bikiniPeito))
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
        assertEquals(0.9f, c.faceFemale, 0f)
        assertEquals(2, c.hitsPeito)
    }

    @Test fun alvoIdsBatemComTiles() {
        val ids = listOf("__peituda", "__peitinho", "__rabuda", "__rabinho", "__bucetuda", "__bucetinha")
        assertEquals(ids, TamanhoLogica.Alvo.entries.map { it.id })
    }

    @Test fun partirPorTamanhoParesDisjuntos() {
        val scored = (1..20).map { "v$it" to it.toFloat() }
        val (grande, pequeno) = TamanhoLogica.partirPorTamanho(scored)
        assertTrue(grande.isNotEmpty())
        assertTrue(pequeno.isNotEmpty())
        val g = grande.map { it.first }.toSet()
        val p = pequeno.map { it.first }.toSet()
        assertTrue("overlap=${g.intersect(p)}", g.intersect(p).isEmpty())
        assertEquals(g.size + p.size, (g + p).size)
        // grande tem métricas maiores
        assertTrue(grande.minOf { it.second } >= pequeno.maxOf { it.second })
        // ~40% cada
        assertEquals(8, grande.size)
        assertEquals(8, pequeno.size)
    }

    @Test fun partirPorTamanhoNaoDuplicaMesmoConjunto() {
        val scored = listOf("a" to 0.9f, "b" to 0.8f, "c" to 0.7f, "d" to 0.2f, "e" to 0.1f, "f" to 0.05f,
            "g" to 0.4f, "h" to 0.35f, "i" to 0.3f, "j" to 0.25f)
        val (g1, p1) = TamanhoLogica.partirPorTamanho(scored)
        assertNotEquals(g1.map { it.first }.toSet(), p1.map { it.first }.toSet())
        assertTrue(g1.map { it.first }.toSet().intersect(p1.map { it.first }.toSet()).isEmpty())
    }

    @Test fun metricaRelativaUsaRosto() {
        assertEquals(2f, TamanhoLogica.metricaRelativa(0.1f, 0.05f), 0.001f)
        assertEquals(0.1f, TamanhoLogica.metricaRelativa(0.1f, 0f), 0.001f)
        assertEquals(0f, TamanhoLogica.metricaRelativa(0f, 0.05f), 0f)
    }

    @Test fun medianaPositiva() {
        assertEquals(0.2f, TamanhoLogica.medianaPositiva(listOf(0.1f, 0.2f, 0.9f)), 0.001f)
        assertEquals(0f, TamanhoLogica.medianaPositiva(emptyList()), 0f)
        assertEquals(0.15f, TamanhoLogica.medianaPositiva(listOf(0.1f, 0.2f)), 0.001f)
    }
}
