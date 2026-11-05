package br.geracaoai.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PosicaoPrefsTest {
    private lateinit var ctx: Context

    @Before fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("posicao_video", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun ignoraMenosDe5s() {
        PosicaoPrefs.salvar(ctx, "v1", 3_000L, 60_000L)
        assertNull(PosicaoPrefs.obter(ctx, "v1", 60_000L))
    }

    @Test fun salvaERetoma() {
        PosicaoPrefs.salvar(ctx, "v1", 12_000L, 60_000L)
        assertEquals(12_000L, PosicaoPrefs.obter(ctx, "v1", 60_000L))
        assertTrue(PosicaoPrefs.deveRetomar(12_000L, 60_000L))
    }

    @Test fun limpaPertoDoFim() {
        PosicaoPrefs.salvar(ctx, "v1", 57_000L, 60_000L)
        assertNull(PosicaoPrefs.obter(ctx, "v1", 60_000L))
    }

    @Test fun limparManual() {
        PosicaoPrefs.salvar(ctx, "v1", 20_000L, 60_000L)
        PosicaoPrefs.limpar(ctx, "v1")
        assertNull(PosicaoPrefs.obter(ctx, "v1", 60_000L))
    }

    @Test fun fracaoThumbnail() {
        PosicaoPrefs.salvar(ctx, "v1", 30_000L, 60_000L)
        assertEquals(0.5f, PosicaoPrefs.fracao(ctx, "v1", 60_000L)!!, 0.01f)
    }
}
