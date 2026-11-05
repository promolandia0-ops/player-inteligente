package br.geracaoai.player

import org.junit.Assert.*
import org.junit.Test

class VideosMediaTest {
    @Test fun chaveEstavelUnificaPrimary() {
        val a = "content://media/external/video/media/42"
        val b = "content://media/external_primary/video/media/42"
        assertEquals(VideosMedia.chaveEstavel(a), VideosMedia.chaveEstavel(b))
        assertEquals(a, VideosMedia.chaveEstavel(b))
    }

    @Test fun chaveEstavelNaoMexeSd() {
        val sd = "content://media/0000-0000/video/media/7"
        assertEquals(sd, VideosMedia.chaveEstavel(sd))
    }
}

class OrdenacaoTest {
    @Test fun labelsPresentes() {
        assertTrue(Ordenacao.entries.any { it.rotulo == "Mais longos" })
        assertTrue(Ordenacao.entries.any { it.rotulo == "Semelhança" })
        assertTrue(Ordenacao.entries.any { it.rotulo == "Maior resolução" })
    }
}


