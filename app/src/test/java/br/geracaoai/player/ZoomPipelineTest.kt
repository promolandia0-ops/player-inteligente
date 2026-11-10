package br.geracaoai.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pipeline REAL do Zoom automático na JVM: quadro (bitmap) → ZoomRastreador → Detector REAL (NudeNet via ONNX
 * Runtime desktop) → remapeamento de coordenadas → enquadramento → projeção na tela.
 * (A única parte fora daqui é o TextureView.getBitmap do aparelho.)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ZoomPipelineTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun frame(n: String): Bitmap =
        javaClass.getResourceAsStream("/zoom/$n")!!.use { BitmapFactory.decodeStream(it) }

    // Vídeo 16:9 (1280×720) no container de um celular em pé (Ajustar): view 1080×608 centrada em 1080×2340.
    private val vw = 1080f; private val vh = 608f; private val cw = 1080f; private val ch = 2340f

    private fun alvoDe(partes: List<TamanhoLogica.CaixaParte>) =
        TamanhoLogica.enquadrarCorpoInteiro(partes, vw, vh, contW = cw, contH = ch)

    private fun corpoInteiroVisivel(partes: List<TamanhoLogica.CaixaParte>, a: TamanhoLogica.ZoomAlvo) {
        val box = TamanhoLogica.caixaCorpoPadded(partes)!!
        for (nx in floatArrayOf(box.x0, box.x1)) for (ny in floatArrayOf(box.y0, box.y1)) {
            val (sx, sy) = TamanhoLogica.projetar(nx, ny, vw, vh, cw, ch, a.zoom, a.panX, a.panY)
            assertTrue("x=$sx fora [0,$cw]", sx in -2f..(cw + 2f)); assertTrue("y=$sy fora [0,$ch]", sy in -2f..(ch + 2f))
        }
    }

    private inline fun <T> comDetector(bloco: (ZoomRastreador) -> T): T =
        Detector(ctx, false).use { d -> bloco(ZoomRastreador { b, l -> d.caixasCorpo(b, l) }) }

    @Test fun mulherDistanteEhAchadaEEnquadradaInteira() {
        comDetector { r ->
            for (n in listOf("walk_2.jpg", "walk_20.jpg", "walk_45.jpg", "dist_200.jpg", "dist_150.jpg")) {
                r.esquecer()
                var res = r.passo(frame(n)); var ciclos = 1
                while (res.partes.isEmpty() && ciclos < 8) { res = r.passo(frame(n)); ciclos++ }
                assertTrue("$n: não achou a mulher em $ciclos ciclos", res.partes.isNotEmpty())
                assertTrue("$n: resultado precisa ser válido", TamanhoLogica.valido(res.partes))
                val a = alvoDe(res.partes)
                assertNotNull("$n: sem alvo", a)
                assertTrue("$n: zoom=${a!!.zoom} deveria ampliar", a.zoom > 1.5f)
                assertTrue(a.zoom <= 2.5f + 1e-3f)
                corpoInteiroVisivel(res.partes, a)
            }
        }
    }

    @Test fun seguraOMovimentoParaOLado() {
        comDetector { r ->
            fun alvoEm(n: String): TamanhoLogica.ZoomAlvo {
                r.esquecer(); var res = r.passo(frame(n)); var c = 1
                while (res.partes.isEmpty() && c < 8) { res = r.passo(frame(n)); c++ }
                return alvoDe(res.partes)!!
            }
            val esq = alvoEm("walk_45.jpg")   // ela no lado esquerdo do quadro
            val meio = alvoEm("walk_2.jpg")
            val dir = alvoEm("walk_20.jpg")   // lado direito
            // O pan acompanha: esquerda → pan positivo (traz a esquerda para o centro); direita → negativo.
            assertTrue("esq=${esq.panX} meio=${meio.panX} dir=${dir.panX}", esq.panX > meio.panX && meio.panX > dir.panX)
            assertTrue(esq.panX > 50f && dir.panX < -50f)
        }
    }

    @Test fun depoisDeAcharSegueComUmaInferenciaEReachaCasoMude() {
        comDetector { r ->
            var res = r.passo(frame("walk_20.jpg")); var c = 1
            while (res.partes.isEmpty() && c < 8) { res = r.passo(frame("walk_20.jpg")); c++ }
            assertTrue(res.partes.isNotEmpty())
            // Mesma posição: agora acompanha com 1 única inferência (rápido).
            val seg = r.passo(frame("walk_20.jpg"))
            assertEquals(ZoomRastreador.Modo.SEGUINDO, seg.modo)
            assertEquals(1, seg.inferencias)
            // Ela "pula" para o outro lado: perde o recorte e reacha NO MESMO ciclo (sem esperar vários).
            val pulo = r.passo(frame("walk_45.jpg"))
            assertTrue("reachar no mesmo ciclo", pulo.partes.isNotEmpty())
            assertNotEquals(ZoomRastreador.Modo.SEGUINDO, pulo.modo)
            // E volta a seguir.
            assertEquals(ZoomRastreador.Modo.SEGUINDO, r.passo(frame("walk_45.jpg")).modo)
        }
    }

    @Test fun elaJaOcupaOQuadroNaoAmplia() {
        comDetector { r ->
            val res = r.passo(frame("perto_700.jpg"))
            assertTrue(res.partes.isNotEmpty())
            val a = alvoDe(res.partes)!!
            assertEquals(1f, a.zoom, 1e-3f)
        }
    }

    @Test fun quadroSemNinguemNaoDaZoom() {
        comDetector { r ->
            val vazio = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.rgb(111, 143, 106)) }
            repeat(6) { assertTrue(r.passo(vazio).partes.isEmpty()) }
        }
    }

    @Test fun tamanhoMinimoDetectavelDocumentado() {
        // Limite conhecido do NudeNet 320n: ~14% da altura do quadro (longe_100) ainda não é detectada.
        comDetector { r ->
            repeat(8) { assertTrue(r.passo(frame("longe_100.jpg")).partes.isEmpty()) }
        }
    }
}
