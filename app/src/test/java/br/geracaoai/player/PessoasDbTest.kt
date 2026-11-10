package br.geracaoai.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PessoasDbTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: PessoasDb
    @Before fun abre() { db = PessoasDb(ctx); db.limparTudo() }
    @After fun fecha() { db.close() }

    private fun trilha(v: Float) = Agrupamento.Trilha(FloatArray(128) { if (it == 0) v else 0.1f }, listOf(Agrupamento.Obs(FloatArray(128), 0.95f, 100f, 0), Agrupamento.Obs(FloatArray(128), 0.9f, 90f, 1)))

    @Test fun salvaListaRetomaERespeitaMinimoDeVideos() {
        val p1 = db.novaPessoa(); val p2 = db.novaPessoa()
        db.salvarVideo("a", "content://v/a", 1, listOf(Triple(trilha(1f), p1, null)))
        db.salvarVideo("b", "content://v/b", 1, listOf(Triple(trilha(1f), p1, null), Triple(trilha(2f), p2, null)))
        db.salvarVideo("c", "content://v/c", 0, emptyList())          // vídeo sem rosto também fica marcado como feito
        assertEquals(setOf("a", "b", "c"), db.videosFeitos())         // retomável: o job só lê o que não está aqui
        val l = db.listar()
        assertEquals(listOf(p1), l.map { it.id })                     // p2 só tem 1 vídeo: escondida
        assertEquals(2, l.single().videos)
        assertEquals(setOf("a", "b"), db.videosDe(p1).map { it.chave }.toSet())
        assertEquals(2, db.somasPorPessoa().size)
    }

    @Test fun renomearEJuntarMantemNomesETrilhas() {
        val a = db.novaPessoa(); val b = db.novaPessoa()
        db.salvarVideo("a", "u/a", 1, listOf(Triple(trilha(1f), a, null)))
        db.salvarVideo("b", "u/b", 1, listOf(Triple(trilha(1f), b, null)))
        db.renomear(b, "  Maria ")
        db.juntar(b, a)                                               // destino sem nome herda o da origem
        val l = db.listar()
        assertEquals(1, l.size); assertEquals(a, l.single().id); assertEquals("Maria", l.single().nome); assertEquals(2, l.single().videos)
        assertEquals(setOf(a), db.somasPorPessoa().keys)
        db.juntar(a, a)                                               // juntar consigo mesma não faz nada
        assertEquals(1, db.listar().size)
    }

    @Test fun limparTudoZera() {
        val a = db.novaPessoa(); db.salvarVideo("a", "u/a", 1, listOf(Triple(trilha(1f), a, null)))
        db.limparTudo()
        assertTrue(db.videosFeitos().isEmpty()); assertTrue(db.listar(1).isEmpty())
    }
}
