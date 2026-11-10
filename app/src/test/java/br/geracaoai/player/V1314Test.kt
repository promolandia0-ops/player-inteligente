package br.geracaoai.player

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class V1314Test {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun assinaturaDoDbSoMudaQuandoHaMudanca() {
        ctx.deleteDatabase("galeria_org.db")
        val db = GaleriaDb(ctx)
        val vazio = db.assinatura()
        assertEquals("vazio estável", vazio, db.assinatura())
        db.salvar("content://media/external/video/media/1", ClassifNudez.NUDE, setOf(Categoria.PEITO), 10L)
        val a1 = db.assinatura()
        assertNotEquals(vazio, a1)
        assertEquals("sem mudança → mesma assinatura (UI pula a releitura)", a1, db.assinatura())
        db.salvarEmb("content://media/external/video/media/1", FloatArray(64) { it * 0.01f })
        val a2 = db.assinatura()
        assertNotEquals(a1, a2)
        db.salvar("content://media/external/video/media/2", ClassifNudez.LIMPO, emptySet(), 11L)
        assertNotEquals(a2, db.assinatura())
        db.limpar()
        assertEquals(vazio, db.assinatura())
    }

    /** Android 12+/14/15 e Samsung podem negar startForegroundService: nunca pode derrubar o chamador. */
    @Test fun iniciarServicoNegadoNaoLancaExcecao() {
        val negando = object : ContextWrapper(ctx) {
            override fun startForegroundService(service: Intent?) = throw IllegalStateException("ForegroundServiceStartNotAllowedException (simulado)")
            override fun startService(service: Intent?) = throw IllegalStateException("BackgroundServiceStartNotAllowed (simulado)")
        }
        assertFalse(OrganizarScanService.iniciarSeguro(negando, Intent(negando, OrganizarScanService::class.java)))
        OrganizarScanService.start(negando, "123")
        OrganizarScanService.pause(negando)
        OrganizarScanService.stop(negando)
    }

    @Test fun iniciarServicoPermitidoRetornaTrue() {
        assertTrue(OrganizarScanService.iniciarSeguro(ctx, Intent(ctx, OrganizarScanService::class.java)))
    }

    @Test fun thumbCacheGuardaEDevolveSemRecarregar() {
        ThumbCache.limpar()
        val u = Uri.parse("content://media/external/video/media/77")
        val k = ThumbCache.chave(u, null, 240, 240)
        assertNull(ThumbCache.obter(k))
        val b = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        ThumbCache.guardar(k, b)
        assertSame(b, ThumbCache.obter(k))
        assertNotEquals(k, ThumbCache.chave(u, null, 360, 360))
        assertNotEquals(k, ThumbCache.chave(u, 5_000L, 240, 240))
        // bitmap reciclado nunca é devolvido (evita "Canvas: trying to use a recycled bitmap")
        b.recycle()
        assertNull(ThumbCache.obter(k))
        assertTrue(ThumbCache.tamanhoMax() in (4 shl 20)..(48 shl 20))
    }

    @Test fun retomarGaleriaForaDaThreadPrincipalNaoQuebra() {
        // Mesmo caminho do onDispose do player: não pode lançar mesmo sem nada ativo.
        val t = Thread { Exclusao.playerFechado(ctx); Exclusao.retomarGaleriaSePreciso(ctx) }
        t.start(); t.join(10_000)
        assertFalse(t.isAlive)
    }
}
