package br.geracaoai.player

import org.junit.Assert.*
import org.junit.Test

class TamanhoLogicaTest {
    private fun femPeito(area: Float = 0.2f) = TamanhoLogica.Evidencia(
        faceFemale = 0.6f, femaleBreast = 0.7f, areaPeito = area, buttocks = 0.1f,
    )
    private fun macho() = TamanhoLogica.Evidencia(
        faceMale = 0.8f, maleGen = 0.7f, maleBreast = 0.5f, areaPeito = 0.3f, buttocks = 0.4f, areaBunda = 0.4f,
    )
    private fun bundaFem() = TamanhoLogica.Evidencia(
        faceFemale = 0.5f, buttocks = 0.7f, areaBunda = 0.25f, femaleBreast = 0.2f,
    )
    private fun bundaSo() = TamanhoLogica.Evidencia(
        buttocks = 0.8f, areaBunda = 0.3f, // sem evidência feminina
    )

    @Test fun peitudaAceitaSoMulher() {
        assertTrue(TamanhoLogica.aceita(TamanhoLogica.Alvo.PEITUDA, femPeito()))
        assertFalse(TamanhoLogica.aceita(TamanhoLogica.Alvo.PEITUDA, macho()))
    }

    @Test fun bucetudaExigeGenitaliaFeminina() {
        val ok = TamanhoLogica.Evidencia(faceFemale = 0.5f, femaleGen = 0.7f, areaBuceta = 0.15f)
        val sem = TamanhoLogica.Evidencia(faceFemale = 0.5f, areaBuceta = 0.15f)
        assertTrue(TamanhoLogica.aceita(TamanhoLogica.Alvo.BUCETUDA, ok))
        assertFalse(TamanhoLogica.aceita(TamanhoLogica.Alvo.BUCETUDA, sem))
        assertFalse(TamanhoLogica.aceita(TamanhoLogica.Alvo.BUCETUDA, macho()))
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

    @Test fun acumularPegaMaximos() {
        val a = TamanhoLogica.Evidencia(femaleBreast = 0.2f, areaPeito = 0.1f)
        val b = TamanhoLogica.Evidencia(femaleBreast = 0.8f, areaPeito = 0.05f, faceFemale = 0.9f)
        val c = TamanhoLogica.acumular(a, b)
        assertEquals(0.8f, c.femaleBreast, 0f)
        assertEquals(0.1f, c.areaPeito, 0f)
        assertEquals(0.9f, c.faceFemale, 0f)
    }

    @Test fun alvoIdsBatemComTiles() {
        val ids = listOf("__peituda", "__peitinho", "__rabuda", "__rabinho", "__bucetuda", "__bucetinha")
        assertEquals(ids, TamanhoLogica.Alvo.entries.map { it.id })
    }
}
