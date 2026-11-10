package br.geracaoai.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CacheAnaliseTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun salvarCarregarRoundtrip() {
        Cache.limpar(ctx)
        val n = 8
        val tempos = LongArray(n) { it * 500L }
        val sc = Categoria.entries.associateWith { c ->
            FloatArray(n) { i -> if (c == Categoria.PEITO) 0.7f else if (c == Categoria.BUNDA) 0.4f else 0.05f }
        }
        val chave = "teste-chave-analise"
        Cache.salvar(ctx, chave, Analise(4000L, tempos, sc), 500L)
        val loaded = Cache.carregar(ctx, chave)
        assertNotNull("carregar() não pode retornar null (bug v1.3.0 chave tempos)", loaded)
        val (a, iv) = loaded!!
        assertEquals(500L, iv)
        assertEquals(4000L, a.duracaoMs)
        assertEquals(n, a.tempos.size)
        assertEquals(0.7f, a.scores[Categoria.PEITO]!![0], 0.001f)
        assertEquals(0.4f, a.scores[Categoria.BUNDA]!![3], 0.001f)
        assertFalse(a.parcial)
    }

    @Test fun carregarSemGemidoNoJsonAindaFunciona() {
        Cache.limpar(ctx)
        val n = 4
        val sc = Categoria.entries.associateWith { FloatArray(n) { 0.2f } }
        Cache.salvar(ctx, "legacy", Analise(4000L, LongArray(n) { it * 1000L }, sc), 1000L)
        assertNotNull(Cache.carregar(ctx, "legacy"))
    }

    /** Análises salvas por versões antigas têm a chave "GEMIDO" no JSON: deve continuar legível e ignorada. */
    @Test fun cacheAntigoComChaveGemidoContinuaLegivel() {
        Cache.limpar(ctx)
        val dir = java.io.File(ctx.filesDir, "analises").apply { mkdirs() }
        java.io.File(dir, "velho.json").writeText(
            """{"v":1,"duracao":4000,"intervalo":1000,"parcial":false,"t":[0,1000,2000,3000],
               "PEITO":[900,100,100,800],"BUNDA":[0,0,0,0],"BUCETA":[0,0,0,0],"ANUS":[0,0,0,0],
               "GEMIDO":[500,500,500,500]}""")
        val (a, iv) = Cache.carregar(ctx, "velho")!!
        assertEquals(1000L, iv)
        assertEquals(4, a.tempos.size)
        assertEquals(0.9f, a.scores[Categoria.PEITO]!![0], 0.001f)
        assertEquals(Categoria.entries.toSet(), a.scores.keys)
    }

    /** Prefs/DB antigos podem ter "GEMIDO" salvo: não pode quebrar nem aparecer como aba. */
    @Test fun prefsEDbAntigosComGemidoNaoQuebram() {
        val p = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        p.edit().putStringSet("cats", setOf("GEMIDO")).commit()
        assertTrue(Prefs(ctx).cats.isEmpty())
        p.edit().putStringSet("cats", setOf("GEMIDO", "BUNDA")).commit()
        assertEquals(setOf(Categoria.BUNDA), Prefs(ctx).cats)
        p.edit().clear().commit()
    }
}
