package br.geracaoai.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.app.Application
import org.robolectric.Shadows

/**
 * Toque em Analisar (Por tamanho): deve gravar progresso imediatamente e,
 * se houver Putaria, disparar o foreground service.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TamanhoScanTest {
    private lateinit var ctx: Context

    @Before fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        TamanhoLogica.Alvo.entries.forEach { TamanhoProgresso.clear(ctx, it.id) }
        GaleriaDb(ctx).limpar()
    }

    @Test fun semPutariaMostraMensagemENaoSobeService() {
        TamanhoScanService.iniciar(ctx, "__peituda")
        val snap = TamanhoProgresso.get(ctx, "__peituda")
        assertNotNull(snap)
        assertEquals(1f, snap!!.frac, 0f)
        assertTrue(snap.txt.contains("Putaria") || snap.txt.contains("Organizar"))
        val started = Shadows.shadowOf(ctx as Application).nextStartedService
        // Pode ser null (retornou cedo) — não deve enfileirar FGS de tamanho
        assertTrue(started == null || started.action != TamanhoScanService.ACTION_START)
    }

    @Test fun comPutariaPendenteEnfileiraServiceEProgressoInicial() {
        val db = GaleriaDb(ctx)
        db.salvar(
            "content://media/external/video/media/42",
            ClassifNudez.NUDE, setOf(Categoria.PEITO), 1L,
            areaPeito = 0f, areaBunda = 0f, areaBuceta = 0f,
        )
        TamanhoScanService.iniciar(ctx, "__peituda")
        val snap = TamanhoProgresso.get(ctx, "__peituda")
        assertNotNull(snap)
        assertTrue("frac inicial >0", snap!!.frac in 0.01f..0.5f)
        assertTrue(snap.txt.contains("Analisando"))
        val started = Shadows.shadowOf(ctx as Application).nextStartedService
        assertNotNull("deve startForegroundService", started)
        assertEquals(TamanhoScanService.ACTION_START, started!!.action)
        assertEquals("__peituda", started.getStringExtra("alvo"))
    }

    @Test fun candidatosContaPendentes() {
        val nude = listOf(
            ResultadoOrg("a", ClassifNudez.NUDE, emptySet(), 1L, 0f, 0f, 0f),
            ResultadoOrg("b", ClassifNudez.NUDE, emptySet(), 1L, 0.2f, 0f, 0f),
            ResultadoOrg("c", ClassifNudez.NUDE, emptySet(), 1L, -1f, 0f, 0f),
        )
        val (total, pend) = TamanhoProgresso.candidatos(nude, TamanhoLogica.Alvo.PEITUDA)
        assertEquals(3, total)
        assertEquals(1, pend) // só "a" com peito==0
    }
}
