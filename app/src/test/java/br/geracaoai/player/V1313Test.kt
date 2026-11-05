package br.geracaoai.player

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V1313Test {
    private fun emb(vararg xs: Float) = FloatArray(Semelhanca.DIM) { i -> if (i < xs.size) xs[i] else 0f }
    private fun vid(id: Int, data: Long) =
        Video(Uri.parse("content://media/external_primary/video/media/$id"), "v$id", 1000, 1, dataMod = data)

    @Test fun semelhancaUsaChaveEstavel() {
        // MediaStore lista external_primary; DB guarda external (chave estável).
        val a = vid(1, 3); val b = vid(2, 2); val c = vid(3, 1)
        val m = mapOf(
            "content://media/external/video/media/1" to emb(1f, 0f),
            "content://media/external/video/media/2" to emb(0f, 1f),
            "content://media/external/video/media/3" to emb(0.98f, 0.02f),
        )
        val out = Semelhanca.ordenarPorSemelhanca(listOf(a, b, c), m)
        // 1 e 3 são parecidos → adjacentes (por data seria 1,2,3).
        val i1 = out.indexOf(a); val i3 = out.indexOf(c)
        assertEquals(1, kotlin.math.abs(i1 - i3))
    }

    @Test fun mesmoConteudoComparaVetores() {
        val a = mapOf("x" to floatArrayOf(1f, 2f))
        assertTrue(Semelhanca.mesmoConteudo(a, mapOf("x" to floatArrayOf(1f, 2f))))
        assertFalse(Semelhanca.mesmoConteudo(a, mapOf("x" to floatArrayOf(1f, 3f))))
        assertFalse(Semelhanca.mesmoConteudo(a, mapOf("y" to floatArrayOf(1f, 2f))))
    }

    @Test fun limparCacheNaoContaDuasVezesNemApagaAnalises() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.cacheDir.deleteRecursively(); ctx.cacheDir.mkdirs()
        File(ctx.cacheDir, "image_manager_disk_cache").apply { mkdirs() }.resolve("a").writeBytes(ByteArray(1000))
        val an = File(ctx.filesDir, "analises").apply { mkdirs() }.resolve("k.json").apply { writeText("{}") }
        assertEquals(1000L, CacheLimpeza.tamanhoCache(ctx))
        assertEquals(1000L, CacheLimpeza.limpar(ctx))
        assertTrue(an.exists())
        assertEquals(0L, CacheLimpeza.tamanhoCache(ctx))
    }
}
