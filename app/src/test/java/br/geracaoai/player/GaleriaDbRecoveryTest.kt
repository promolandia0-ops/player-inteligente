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
@Config(sdk = [34], manifest = Config.NONE)
class GaleriaDbRecoveryTest {
    private lateinit var ctx: Context
    private lateinit var db: GaleriaDb

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.deleteDatabase("galeria_org.db")
        db = GaleriaDb(ctx)
    }

    @Test fun linhaAntigaComCategoriaGemidoAindaLe() {
        db.salvar("content://media/external/video/media/55", ClassifNudez.NUDE, setOf(Categoria.PEITO), 1L)
        db.writableDatabase.execSQL("UPDATE org SET cats='PEITO,GEMIDO' WHERE uri='content://media/external/video/media/55'")
        val r = db.obter("content://media/external/video/media/55")!!
        assertEquals(setOf(Categoria.PEITO), r.cats)
        assertEquals(ClassifNudez.NUDE, r.classif)
    }

    @Test fun normalizarUrisPreservaClassif() {
        db.salvar("content://media/external_primary/video/media/1", ClassifNudez.NUDE, setOf(Categoria.PEITO), 10L)
        db.salvar("content://media/external_primary/video/media/2", ClassifNudez.LIMPO, emptySet(), 11L)
        val n = db.normalizarUris()
        assertEquals(2, n)
        val mapa = db.todosPorChaveEstavel()
        assertEquals(ClassifNudez.NUDE, mapa["content://media/external/video/media/1"]?.classif)
        assertEquals(ClassifNudez.LIMPO, mapa["content://media/external/video/media/2"]?.classif)
        // chave velha sumiu
        assertNull(db.obter("content://media/external_primary/video/media/1"))
    }

    @Test fun limparNudeNaoEChamadoNaRecuperacao() {
        db.salvar("content://media/external/video/media/1", ClassifNudez.NUDE, setOf(Categoria.BUNDA), 1L)
        db.salvar("content://media/external/video/media/2", ClassifNudez.LIMPO, emptySet(), 1L)
        // Simula só a recuperação (sem limparNude)
        val ren = db.normalizarUris()
        assertEquals(0, ren)
        assertEquals(1, db.contagens()[ClassifNudez.NUDE])
        assertEquals(1, db.contagens()[ClassifNudez.LIMPO])
    }

    @Test fun aposLimparNudeLimpoSobreviveECasa() {
        db.salvar("content://media/external_primary/video/media/7", ClassifNudez.NUDE, setOf(Categoria.PEITO), 1L)
        db.salvar("content://media/external_primary/video/media/8", ClassifNudez.LIMPO, emptySet(), 1L, scanV = ClassifUnica.VERSAO_ESTRITA, frames = 30)   // varredura rigorosa (v1.3.23)
        db.limparNude() // bug v1.3.0
        assertEquals(0, db.contagens()[ClassifNudez.NUDE] ?: 0)
        assertEquals(1, db.contagens()[ClassifNudez.LIMPO] ?: 0)
        db.normalizarUris()
        val feitos = OrgRecuperacao.mapaFeitos(db)
        assertTrue(OrgRecuperacao.estaFeito(feitos, "content://media/external/video/media/8"))
        assertFalse(OrgRecuperacao.estaFeito(feitos, "content://media/external/video/media/7"))
    }

    @Test fun mtimeDiferenteAindaContaFeito() {
        db.salvar("content://media/external/video/media/3", ClassifNudez.LIMPO, emptySet(), 100L, scanV = ClassifUnica.VERSAO_ESTRITA, frames = 30)
        val feitos = OrgRecuperacao.mapaFeitos(db)
        assertTrue(OrgRecuperacao.estaFeito(feitos, "content://media/external/video/media/3"))
    }

    @Test fun upgradeSchemaNaoApaga() {
        db.salvar("content://media/external/video/media/9", ClassifNudez.NUDE, setOf(Categoria.BUCETA), 5L, 0.2f, 0.1f, 0.3f)
        db.close()
        val db2 = GaleriaDb(ctx)
        val r = db2.obter("content://media/external/video/media/9")
        assertNotNull(r)
        assertEquals(ClassifNudez.NUDE, r!!.classif)
        assertEquals(0.2f, r.areaPeito, 0.001f)
    }
}
