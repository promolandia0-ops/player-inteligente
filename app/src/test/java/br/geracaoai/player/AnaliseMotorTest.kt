package br.geracaoai.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Fluxo per-vídeo (v1.1.3): motor in-process com detector fake → progresso >0 → Cache completo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AnaliseMotorTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun progressoSobeECompletaComFakeDetector() {
        Cache.limpar(ctx)
        val n = 20
        val tempos = LongArray(n) { it * 500L }
        val progressos = mutableListOf<Float>()
        val sc = AnaliseMotor.processar(
            n = n,
            pontuar = { i ->
                Categoria.entries.associateWith { c ->
                    when (c) {
                        Categoria.PEITO -> if (i in 5..8) 0.8f else 0.1f
                        Categoria.GEMIDO -> 0f
                        else -> 0.05f
                    }
                }
            },
            onProgresso = { progressos += it },
        )
        assertTrue("deve reportar progresso", progressos.isNotEmpty())
        assertTrue("progresso deve passar de 0", progressos.any { it > 0f })
        assertEquals(1f, progressos.last(), 0.001f)
        assertTrue(progressos.zipWithNext().all { (a, b) -> b >= a })

        val chave = "fake-video"
        Cache.salvar(ctx, chave, Analise(tempos.last(), tempos, sc), 500L)
        AnaliseProgresso.set(ctx, "content://fake/1", 1f)

        val loaded = Cache.carregar(ctx, chave)
        assertNotNull(loaded)
        val segs = Segmentador.segmentos(loaded!!.first, setOf(Categoria.PEITO), 0.45f, 500L)
        assertTrue("deve achar cenas de peito", segs.isNotEmpty())
        assertEquals(1f, AnaliseProgresso.get(ctx, "content://fake/1")!!, 0.001f)
    }

    @Test fun paradoCedoNaoChegaA100() {
        val progressos = mutableListOf<Float>()
        var calls = 0
        AnaliseMotor.processar(
            n = 40,
            pontuar = { Categoria.entries.associateWith { 0.1f } },
            onProgresso = { progressos += it },
            parado = { calls++; calls > 8 },
        )
        assertTrue(progressos.isNotEmpty())
        assertTrue(progressos.last() < 1f)
    }
}
