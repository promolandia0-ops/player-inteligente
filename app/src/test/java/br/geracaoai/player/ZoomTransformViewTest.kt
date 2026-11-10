package br.geracaoai.player

import android.content.Context
import android.view.TextureView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifica que a escala/translação calculadas (as mesmas aplicadas via graphicsLayer no player)
 * realmente movem os pontos na tela: aplica scaleX/scaleY/translation num TextureView real
 * (pivô no centro, como o graphicsLayer) e compara com TamanhoLogica.projetar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ZoomTransformViewTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun transformDaViewMudaDeFatoEBateComProjetar() {
        val vw = 1080; val vh = 608; val cw = 1080f; val ch = 2200f
        val tv = TextureView(ctx)
        tv.layout(0, ((ch - vh) / 2).toInt(), vw, ((ch - vh) / 2).toInt() + vh)  // view centrada no container
        val partes = listOf(
            TamanhoLogica.CaixaParte(TamanhoLogica.FACE_FEMALE, TamanhoLogica.Caixa(0.9f, 0.45f, 0.30f, 0.04f, 0.05f)),
            TamanhoLogica.CaixaParte(TamanhoLogica.FEET_EXPOSED, TamanhoLogica.Caixa(0.9f, 0.45f, 0.62f, 0.05f, 0.04f)),
        )
        val a = TamanhoLogica.enquadrarCorpoInteiro(partes, vw.toFloat(), vh.toFloat(), contW = cw, contH = ch)!!
        assertTrue("tem que ampliar de verdade", a.zoom > 1.2f)

        // Antes: identidade.
        assertEquals(1f, tv.scaleX, 0f); assertEquals(0f, tv.translationX, 0f)
        // Aplica exatamente o que o player passa ao graphicsLayer.
        tv.pivotX = vw / 2f; tv.pivotY = vh / 2f
        tv.scaleX = a.zoom; tv.scaleY = a.zoom; tv.translationX = a.panX; tv.translationY = a.panY
        assertEquals(a.zoom, tv.scaleX, 1e-6f)
        assertEquals(a.panX, tv.translationX, 1e-6f)
        assertEquals(a.panY, tv.translationY, 1e-6f)

        // Matriz equivalente (pivô no centro + translação) — mapeia o ponto como a plataforma faria.
        val m = android.graphics.Matrix()
        m.postScale(tv.scaleX, tv.scaleY, tv.pivotX, tv.pivotY)
        m.postTranslate(tv.translationX, tv.translationY)
        val box = TamanhoLogica.caixaCorpoPadded(partes)!!
        for (nx in floatArrayOf(box.x0, box.x1)) for (ny in floatArrayOf(box.y0, box.y1)) {
            val pt = floatArrayOf(nx * vw, ny * vh)
            m.mapPoints(pt)
            val sx = pt[0] + tv.left; val sy = pt[1] + tv.top      // para coordenadas do container
            val (ex, ey) = TamanhoLogica.projetar(nx, ny, vw.toFloat(), vh.toFloat(), cw, ch, a.zoom, a.panX, a.panY)
            assertEquals(ex, sx, 0.5f); assertEquals(ey, sy, 0.5f)
            // e o corpo inteiro (box com margem) fica visível no container
            assertTrue(sx in -1f..(cw + 1f)); assertTrue(sy in -1f..(ch + 1f))
        }
    }
}
