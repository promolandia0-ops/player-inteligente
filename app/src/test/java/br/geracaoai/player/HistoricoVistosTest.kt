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
@Config(sdk = [34])
class HistoricoVistosTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun u(i: Int) = Uri.parse("content://media/external/video/media/$i")

    @Before fun limpa() { HistoricoVistos.limpar(ctx) }

    @Test fun maisRecentePrimeiroSemRepeticaoEPersiste() {
        HistoricoVistos.registrar(ctx, u(1), "a.mp4", 1000, agora = 100)
        HistoricoVistos.registrar(ctx, u(2), "b.mp4", 2000, agora = 200)
        HistoricoVistos.registrar(ctx, u(1), "a.mp4", 1000, agora = 300)   // reabre: sobe pro topo, sem duplicar
        assertEquals(listOf(u(1).toString(), u(2).toString()), HistoricoVistos.listar(ctx).map { it.uri })
        assertEquals(300L, HistoricoVistos.listar(ctx).first().quando)
    }

    @Test fun guardaSoOsUltimos20() {
        for (i in 1..30) HistoricoVistos.registrar(ctx, u(i), "v$i", 0, agora = i.toLong())
        val l = HistoricoVistos.listar(ctx)
        assertEquals(HistoricoVistos.MAX, l.size)
        assertEquals(u(30).toString(), l.first().uri); assertEquals(u(11).toString(), l.last().uri)
    }

    @Test fun reaberturaSemDuracaoMantemADuracaoConhecida() {
        HistoricoVistos.registrar(ctx, u(1), "a.mp4", 5000, agora = 1)
        HistoricoVistos.registrar(ctx, u(1), "", 0, agora = 2)
        val e = HistoricoVistos.listar(ctx).single()
        assertEquals(5000L, e.duracao); assertEquals("a.mp4", e.nome)
    }

    @Test fun resolverPulaArquivosQueNaoExistemMaisEUsaDadosDaGaleria() {
        HistoricoVistos.registrar(ctx, u(1), "a", 0, agora = 1)
        HistoricoVistos.registrar(ctx, u(2), "b", 0, agora = 2)   // apagado
        HistoricoVistos.registrar(ctx, u(3), "c", 0, agora = 3)   // fora da galeria, mas existe
        val daGaleria = Video(u(1), "a.mp4", 9000, 1234)
        val r = HistoricoVistos.resolver(ctx, listOf(daGaleria)) { it == u(3) }
        assertEquals(listOf(u(3), u(1)), r.map { it.uri })
        assertEquals(9000L, r.last().duracao)
    }

    @Test fun vazioEDadosCorrompidosNaoQuebram() {
        assertTrue(HistoricoVistos.listar(ctx).isEmpty())
        ctx.getSharedPreferences("historico", Context.MODE_PRIVATE).edit().putString("vistos", "{lixo").apply()
        assertTrue(HistoricoVistos.listar(ctx).isEmpty())
        HistoricoVistos.registrar(ctx, u(1), "a", 0)           // volta a funcionar
        assertEquals(1, HistoricoVistos.listar(ctx).size)
    }
}
