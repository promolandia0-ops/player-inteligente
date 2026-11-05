package br.geracaoai.player

import org.junit.Assert.*
import org.junit.Test

class FmtTamTest {
    @Test fun bytesEKb() {
        assertEquals("500 B", fmtTam(500))
        assertEquals("999 B", fmtTam(999))
        assertEquals("1 KB", fmtTam(1024))
        assertEquals("512 KB", fmtTam(512 * 1024L))
        assertEquals("1023 KB", fmtTam(1023 * 1024L))
    }
    @Test fun mbEGb() {
        assertEquals("1.5 MB", fmtTam((1.5 * 1048576).toLong()))
        assertEquals("12 MB", fmtTam(12L * 1048576))
        assertEquals("1.00 GB", fmtTam(1073741824L))
    }
    @Test fun naoMostraZeroMbParaArquivosPequenos() {
        assertFalse(fmtTam(400_000).contains("0 MB"))
        assertTrue(fmtTam(400_000).endsWith("KB"))
    }
}

class PastasOrdemTest {
    @Test fun cameraPrimeiro() {
        assertTrue(ePastaCamera("Camera"))
        assertTrue(ePastaCamera("Câmera"))
        assertTrue(ePastaCamera("DCIM"))
        assertFalse(ePastaCamera("WhatsApp Video"))
        val nomes = ordenarNomesPastas(
            listOf(
                "WhatsApp Video" to 100L,
                "Camera" to 10L,
                "Download" to 50L,
                "Telegram" to 80L,
            ),
        )
        assertEquals(listOf("Camera", "WhatsApp Video", "Telegram", "Download"), nomes)
    }
    @Test fun cameraComAcentoAntesDeRecente() {
        assertEquals(
            listOf("Câmera", "Download"),
            ordenarNomesPastas(listOf("Câmera" to 1L, "Download" to 99L)),
        )
    }
}
