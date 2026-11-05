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

/**
 * Toque em Analisar / Reorganizar (Por tamanho).
 */
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
        // par também recebe progresso
        assertNotNull(TamanhoProgresso.get(ctx, "__peitinho"))
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

    @Test fun candidatosTodosContaQualquerPendente() {
        val nude = listOf(
            ResultadoOrg("a", ClassifNudez.NUDE, emptySet(), 1L, 0.2f, 0f, 0f), // falta bunda/buceta
            ResultadoOrg("b", ClassifNudez.NUDE, emptySet(), 1L, 0.1f, 0.1f, 0.1f),
            ResultadoOrg("c", ClassifNudez.NUDE, emptySet(), 1L, 0f, 0f, 0f),
        )
        val (total, pend) = TamanhoProgresso.candidatos(nude, null)
        assertEquals(3, total)
        assertEquals(2, pend) // a e c
    }

    @Test fun reorganizarSemPendentesSoFeedback() {
        val db = GaleriaDb(ctx)
        db.salvar(
            "content://media/external/video/media/7",
            ClassifNudez.NUDE, setOf(Categoria.PEITO, Categoria.BUNDA, Categoria.BUCETA), 1L,
            areaPeito = 0.3f, areaBunda = 0.2f, areaBuceta = 0.1f,
        )
        TamanhoScanService.reorganizar(ctx)
        val snap = TamanhoProgresso.get(ctx, TamanhoScanService.PROG_REORG)
        assertNotNull(snap)
        assertEquals(1f, snap!!.frac, 0f)
        assertTrue(snap.txt.contains("atualiz") || snap.txt.contains("Pastas"))
        val started = Shadows.shadowOf(ctx as Application).nextStartedService
        assertTrue(started == null || started.action != TamanhoScanService.ACTION_START)
    }

    @Test fun reorganizarComPendentesEnfileira() {
        val db = GaleriaDb(ctx)
        db.salvar(
            "content://media/external/video/media/9",
            ClassifNudez.NUDE, setOf(Categoria.PEITO), 1L,
            areaPeito = 0f, areaBunda = 0f, areaBuceta = 0f,
        )
        TamanhoScanService.reorganizar(ctx)
        val snap = TamanhoProgresso.get(ctx, TamanhoScanService.PROG_REORG)
        assertNotNull(snap)
        assertTrue(snap!!.frac in 0.01f..0.5f)
        assertTrue(snap.txt.contains("Reorganizando") || snap.txt.contains("Analisando"))
        val started = Shadows.shadowOf(ctx as Application).nextStartedService
        assertNotNull(started)
        assertEquals(TamanhoScanService.ACTION_START, started!!.action)
        assertEquals("", started.getStringExtra("alvo") ?: "")
    }

    @Test fun reorganizarParLongPress() {
        val db = GaleriaDb(ctx)
        db.salvar(
            "content://media/external/video/media/11",
            ClassifNudez.NUDE, setOf(Categoria.PEITO), 1L,
            areaPeito = 0f, areaBunda = 0.1f, areaBuceta = 0.1f,
        )
        TamanhoScanService.reorganizar(ctx, "__peitinho")
        assertNotNull(TamanhoProgresso.get(ctx, "__peituda"))
        assertNotNull(TamanhoProgresso.get(ctx, "__peitinho"))
        val started = Shadows.shadowOf(ctx as Application).nextStartedService
        assertNotNull(started)
        assertEquals("__peitinho", started!!.getStringExtra("alvo"))
    }

    @Test fun idsDoParDisjuntos() {
        assertEquals(listOf("__peituda", "__peitinho"), TamanhoProgresso.idsDoPar("__peituda"))
        assertEquals(listOf("__rabuda", "__rabinho"), TamanhoProgresso.idsDoPar("__rabinho"))
    }
}
