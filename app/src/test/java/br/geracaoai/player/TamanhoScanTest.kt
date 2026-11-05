package br.geracaoai.player

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TamanhoScanTest {
    private lateinit var ctx: Context

    @Before fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        (TamanhoLogica.Alvo.entries.map { it.id } + listOf(TamanhoScanService.PROG_REORG)).forEach {
            TamanhoProgresso.clear(ctx, it)
        }
        GaleriaDb(ctx).limpar()
        ctx.getSharedPreferences("tamanho_metric", Context.MODE_PRIVATE)
            .edit().putInt("v", TamanhoLogica.METRIC_VERSAO).commit()
    }

    @Test fun semPutariaMostraMensagemENaoSobeService() {
        TamanhoScanService.iniciar(ctx, "__peituda")
        val snap = TamanhoProgresso.get(ctx, "__peituda")
        assertNotNull(snap)
        assertEquals(1f, snap!!.frac, 0f)
        assertTrue(snap.txt.contains("Putaria") || snap.txt.contains("Organizar"))
        val started = Shadows.shadowOf(ctx as Application).nextStartedService
        assertTrue(started == null || started.action != TamanhoScanService.ACTION_START)
    }

    @Test fun comPutariaPendenteEnfileiraServiceEProgressoInicial() {
        GaleriaDb(ctx).salvar(
            "content://media/external/video/media/42",
            ClassifNudez.NUDE, setOf(Categoria.PEITO), 1L,
            areaPeito = 0f, areaBunda = 0f, areaBuceta = 0f,
        )
        TamanhoScanService.iniciar(ctx, "__peituda")
        val snap = TamanhoProgresso.get(ctx, "__peituda")
        assertNotNull(snap)
        assertTrue(snap!!.frac in 0.01f..0.5f)
        assertNotNull(TamanhoProgresso.get(ctx, "__peitinho"))
        val started = Shadows.shadowOf(ctx as Application).nextStartedService
        assertNotNull(started)
        assertEquals(TamanhoScanService.ACTION_START, started!!.action)
    }

    @Test fun candidatosContaPendentes() {
        val nude = listOf(
            ResultadoOrg("a", ClassifNudez.NUDE, emptySet(), 1L, 0f, 0f, 0f),
            ResultadoOrg("b", ClassifNudez.NUDE, emptySet(), 1L, 0.2f, 0f, 0f),
            ResultadoOrg("c", ClassifNudez.NUDE, emptySet(), 1L, -1f, 0f, 0f),
        )
        val (total, pend) = TamanhoProgresso.candidatos(nude, TamanhoLogica.Alvo.PEITUDA)
        assertEquals(3, total)
        assertEquals(1, pend)
    }

    @Test fun candidatosTodosContaQualquerPendente() {
        val nude = listOf(
            ResultadoOrg("a", ClassifNudez.NUDE, emptySet(), 1L, 0.2f, 0f, 0f),
            ResultadoOrg("b", ClassifNudez.NUDE, emptySet(), 1L, 0.1f, 0.1f, 0.1f),
            ResultadoOrg("c", ClassifNudez.NUDE, emptySet(), 1L, 0f, 0f, 0f),
        )
        val (total, pend) = TamanhoProgresso.candidatos(nude, null)
        assertEquals(3, total)
        assertEquals(2, pend)
    }

    @Test fun reorganizarSemPendentesSoFeedback() {
        GaleriaDb(ctx).salvar(
            "content://media/external/video/media/7",
            ClassifNudez.NUDE, setOf(Categoria.PEITO, Categoria.BUNDA, Categoria.BUCETA), 1L,
            areaPeito = 0.3f, areaBunda = 0.2f, areaBuceta = 0.1f,
        )
        TamanhoScanService.reorganizar(ctx)
        val snap = TamanhoProgresso.get(ctx, TamanhoScanService.PROG_REORG)
        assertNotNull(snap)
        assertEquals(1f, snap!!.frac, 0f)
        val started = Shadows.shadowOf(ctx as Application).nextStartedService
        assertTrue(started == null || started.action != TamanhoScanService.ACTION_START)
    }

    @Test fun reorganizarComPendentesEnfileira() {
        GaleriaDb(ctx).salvar(
            "content://media/external/video/media/9",
            ClassifNudez.NUDE, setOf(Categoria.PEITO), 1L,
            areaPeito = 0f, areaBunda = 0f, areaBuceta = 0f,
        )
        TamanhoScanService.reorganizar(ctx)
        val snap = TamanhoProgresso.get(ctx, TamanhoScanService.PROG_REORG)
        assertNotNull(snap)
        assertTrue(snap!!.frac in 0.01f..0.5f)
        val started = Shadows.shadowOf(ctx as Application).nextStartedService
        assertNotNull(started)
        assertEquals(TamanhoScanService.ACTION_START, started!!.action)
    }

    @Test fun reorganizarParLongPress() {
        GaleriaDb(ctx).salvar(
            "content://media/external/video/media/11",
            ClassifNudez.NUDE, setOf(Categoria.PEITO), 1L,
            areaPeito = 0f, areaBunda = 0.1f, areaBuceta = 0.1f,
        )
        TamanhoScanService.reorganizar(ctx, "__peitinho")
        assertNotNull(TamanhoProgresso.get(ctx, "__peituda"))
        val started = Shadows.shadowOf(ctx as Application).nextStartedService
        assertNotNull(started)
        assertEquals("__peitinho", started!!.getStringExtra("alvo"))
    }

    @Test fun idsDoParDisjuntos() {
        assertEquals(listOf("__peituda", "__peitinho"), TamanhoProgresso.idsDoPar("__peituda"))
        assertEquals(listOf("__rabuda", "__rabinho"), TamanhoProgresso.idsDoPar("__rabinho"))
    }

    @Test fun garantirMetricVZeraAreasAntigas() {
        GaleriaDb(ctx).salvar(
            "content://media/external/video/media/99",
            ClassifNudez.NUDE, setOf(Categoria.PEITO), 1L,
            areaPeito = 0.5f, areaBunda = 0.4f, areaBuceta = 0.3f,
        )
        ctx.getSharedPreferences("tamanho_metric", Context.MODE_PRIVATE).edit().putInt("v", 1).commit()
        assertTrue(TamanhoScanService.garantirMetricV(ctx))
        val r = GaleriaDb(ctx).obter("content://media/external/video/media/99")
        assertNotNull(r)
        assertEquals(0f, r!!.areaPeito, 0f)
        assertEquals(0f, r.areaBunda, 0f)
        assertEquals(0f, r.areaBuceta, 0f)
        ctx.getSharedPreferences("tamanho_metric", Context.MODE_PRIVATE)
            .edit().putInt("v", TamanhoLogica.METRIC_VERSAO).commit()
        assertFalse(TamanhoScanService.garantirMetricV(ctx))
    }

    @Test fun reorganizarComMetricBumpEnfileiraMesmoComAreas() {
        GaleriaDb(ctx).salvar(
            "content://media/external/video/media/77",
            ClassifNudez.NUDE, setOf(Categoria.PEITO, Categoria.BUNDA, Categoria.BUCETA), 1L,
            areaPeito = 0.3f, areaBunda = 0.2f, areaBuceta = 0.1f,
        )
        ctx.getSharedPreferences("tamanho_metric", Context.MODE_PRIVATE).edit().putInt("v", 1).commit()
        val shadow = Shadows.shadowOf(ctx as Application)
        while (shadow.nextStartedService != null) { /* drain */ }
        TamanhoScanService.reorganizar(ctx)
        val started = shadow.nextStartedService
        assertNotNull(started)
        assertEquals(TamanhoScanService.ACTION_START, started!!.action)
    }
}
