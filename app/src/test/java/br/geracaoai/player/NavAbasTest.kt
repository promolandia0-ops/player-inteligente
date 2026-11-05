package br.geracaoai.player

import org.junit.Assert.*
import org.junit.Test

class NavAbasTest {
    private val albuns = AbaGaleria.ALBUNS.name
    private val videos = AbaGaleria.VIDEOS.name
    private val intel = AbaGaleria.INTELIGENTE.name
    private val ajustes = AbaGaleria.AJUSTES.name

    @Test fun trocaGuardaAnterior() {
        val s = NavAbas.aoTrocar(albuns, videos, albuns)
        assertEquals(videos, s.aba)
        assertEquals(albuns, s.anterior)
    }

    @Test fun trocaMesmaAbaNaoMexa() {
        val s = NavAbas.aoTrocar(videos, videos, albuns)
        assertEquals(videos, s.aba)
        assertEquals(albuns, s.anterior)
    }

    @Test fun backDeUmaAbaVoltaHome() {
        val s = NavAbas.aoVoltar(videos, albuns)
        assertEquals(albuns, s.aba)
        assertEquals(albuns, s.anterior)
    }

    @Test fun backDuasVezesNaoTrava() {
        // Álbuns → Vídeos → Inteligente; Back → Vídeos; Back → Álbuns (não trava em Vídeos)
        var aba = albuns
        var ant = albuns
        fun ir(nova: String) {
            val s = NavAbas.aoTrocar(aba, nova, ant)
            aba = s.aba; ant = s.anterior
        }
        fun back() {
            val s = NavAbas.aoVoltar(aba, ant)
            aba = s.aba; ant = s.anterior
        }
        ir(videos)
        ir(intel)
        assertEquals(intel, aba)
        assertEquals(videos, ant)
        back()
        assertEquals(videos, aba)
        assertEquals(albuns, ant) // próximo back cai no home
        back()
        assertEquals(albuns, aba)
        // Back no home é no-op
        back()
        assertEquals(albuns, aba)
    }

    @Test fun backComAnteriorStaleVaiHome() {
        // Bug v1.3.9: após um back, anterior == atual e o 2º back travava.
        val s = NavAbas.aoVoltar(videos, videos)
        assertEquals(albuns, s.aba)
        assertEquals(albuns, s.anterior)
    }

    @Test fun backDeAjustesParaAnterior() {
        val depoisTroca = NavAbas.aoTrocar(videos, ajustes, albuns)
        assertEquals(ajustes, depoisTroca.aba)
        assertEquals(videos, depoisTroca.anterior)
        val back = NavAbas.aoVoltar(depoisTroca.aba, depoisTroca.anterior)
        assertEquals(videos, back.aba)
        assertEquals(albuns, back.anterior)
    }

    @Test fun pastaIdsTamanhoRemovidos() {
        for (id in listOf("__peituda", "__peitinho", "__rabuda", "__rabinho", "__bucetuda", "__bucetinha")) {
            assertTrue(id, NavAbas.pastaIdRemovido(id))
        }
        assertFalse(NavAbas.pastaIdRemovido("__putaria"))
        assertFalse(NavAbas.pastaIdRemovido("Camera"))
    }
}
