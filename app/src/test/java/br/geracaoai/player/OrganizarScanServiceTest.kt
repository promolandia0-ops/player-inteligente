package br.geracaoai.player

import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class OrganizarScanServiceTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun pausarSemVarreduraEncerraServico() {
        val ctl = Robolectric.buildService(OrganizarScanService::class.java).create()
        val svc = ctl.get()
        val i = Intent(ctx, OrganizarScanService::class.java).setAction(OrganizarScanService.ACTION_PAUSE)
        svc.onStartCommand(i, 0, 1)
        assertTrue("serviço ficava preso em foreground com 'Preparando'", shadowOf(svc).isStoppedBySelf)
        assertTrue(OrgPrefs(ctx).pausado)
        ctl.destroy()
    }

    @Test fun posicaoVersaoMudaAoFecharPlayer() {
        val antes = PosicaoPrefs.versao.intValue
        PosicaoPrefs.marcarAlterado()
        assertEquals(antes + 1, PosicaoPrefs.versao.intValue)
    }
}
