package br.geracaoai.player

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class FavoritosTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun u(id: Int, prim: Boolean = false) = Uri.parse("content://media/${if (prim) "external_primary" else "external"}/video/media/$id")
    private fun v(id: Int) = Video(u(id), "v$id", 1000L, 1L)
    @Before fun limpa() { ctx.getSharedPreferences("favoritos", Context.MODE_PRIVATE).edit().clear().commit(); Favoritos.carregar(ctx) }

    @Test fun alternarFavoritaDesfavoritaEPersiste() {
        assertTrue(Favoritos.Loja.alternar(ctx, u(1), 100L))
        assertTrue(Favoritos.Loja.alternar(ctx, u(2), 200L))
        assertEquals(setOf("content://media/external/video/media/1", "content://media/external/video/media/2"), Favoritos.Loja.ler(ctx).keys)
        assertFalse("segunda vez desfavorita", Favoritos.Loja.alternar(ctx, u(1), 300L))
        assertEquals(setOf("content://media/external/video/media/2"), Favoritos.Loja.ler(ctx).keys)
    }

    @Test fun chaveEstavelUnificaVolumes() {
        assertTrue(Favoritos.Loja.alternar(ctx, u(5, prim = true), 1L))
        assertFalse("o mesmo vídeo por external desfavorita", Favoritos.Loja.alternar(ctx, u(5), 2L))
        assertTrue(Favoritos.Loja.ler(ctx).isEmpty())
    }

    @Test fun ordemMaisRecenteFavoritadoPrimeiro() {
        Favoritos.Loja.alternar(ctx, u(1), 100L); Favoritos.Loja.alternar(ctx, u(2), 300L); Favoritos.Loja.alternar(ctx, u(3), 200L)
        val r = Favoritos.resolver(ctx, listOf(v(1), v(2), v(3)), Favoritos.Loja.ler(ctx)).map { it.nome }
        assertEquals(listOf("v2", "v3", "v1"), r)
    }

    @Test fun arquivosApagadosSaoIgnoradosEPodados() {
        Favoritos.Loja.alternar(ctx, u(1), 100L); Favoritos.Loja.alternar(ctx, u(2), 200L); Favoritos.Loja.alternar(ctx, u(3), 300L)
        val m = Favoritos.Loja.ler(ctx)
        // galeria só tem o 1 e o 3; o 2 foi apagado (e a checagem de existência também falha)
        val r = Favoritos.resolver(ctx, listOf(v(1), v(3)), m, existe = { false })
        assertEquals(listOf("v3", "v1"), r.map { it.nome })
        assertEquals("apagado sai do armazenamento", setOf("content://media/external/video/media/1", "content://media/external/video/media/3"), Favoritos.Loja.ler(ctx).keys)
        // vídeo fora da galeria (aberto por arquivo) mas que ainda abre: mantém
        val fora = Favoritos.resolver(ctx, listOf(v(1), v(3)), mapOf("content://media/external/video/media/77" to 5L), existe = { true })
        assertEquals(1, fora.size)
    }

    @Test fun listaDaGaleriaVaziaNaoPodaNada() {
        Favoritos.Loja.alternar(ctx, u(1), 100L)
        Favoritos.resolver(ctx, emptyList(), Favoritos.Loja.ler(ctx), existe = { false })
        assertEquals(1, Favoritos.Loja.ler(ctx).size)     // sem permissão/lista vazia: não apaga favoritos
    }

    @Test fun estadoObservavelAtualizaNaHoraEGravaEmSegundoPlano() {
        assertFalse(Favoritos.ehFavorito(u(9)))
        assertTrue(Favoritos.alternar(ctx, u(9)))
        assertTrue("estado imediato (estrela acende sem esperar disco)", Favoritos.ehFavorito(u(9)))
        assertTrue(Favoritos.ehFavorito(u(9, prim = true)))
        for (i in 0 until 50) { if (Favoritos.Loja.ler(ctx).isNotEmpty()) break; Thread.sleep(20) }
        assertEquals(1, Favoritos.Loja.ler(ctx).size)
        assertFalse(Favoritos.alternar(ctx, u(9)))
        assertFalse(Favoritos.ehFavorito(u(9)))
    }

    @Test fun armazenamentoCorrompidoNaoQuebra() {
        ctx.getSharedPreferences("favoritos", Context.MODE_PRIVATE).edit().putString("lista", "{{lixo").commit()
        assertTrue(Favoritos.Loja.ler(ctx).isEmpty())
        assertTrue(Favoritos.Loja.alternar(ctx, u(1), 1L))
    }
}
