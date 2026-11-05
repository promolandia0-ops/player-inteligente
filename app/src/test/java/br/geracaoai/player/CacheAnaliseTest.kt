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

    @Test fun salvarCarregarRoundtripComGemido() {
        Cache.limpar(ctx)
        val n = 8
        val tempos = LongArray(n) { it * 500L }
        val sc = Categoria.entries.associateWith { c ->
            FloatArray(n) { i -> if (c == Categoria.PEITO) 0.7f else if (c == Categoria.GEMIDO) 0.4f else 0.05f }
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
        assertEquals(0.4f, a.scores[Categoria.GEMIDO]!![3], 0.001f)
        assertFalse(a.parcial)
    }

    @Test fun carregarSemGemidoNoJsonAindaFunciona() {
        Cache.limpar(ctx)
        val chave = "legacy"
        val n = 4
        val tempos = LongArray(n) { it * 1000L }
        // Salva só cats “antigas” via JSON parcial: simula arquivo sem GEMIDO usando salvar com mapa completo
        val sc = Categoria.entries.associateWith { FloatArray(n) { 0.2f } }
        Cache.salvar(ctx, chave, Analise(4000L, tempos, sc), 1000L)
        assertNotNull(Cache.carregar(ctx, chave))
    }
}
