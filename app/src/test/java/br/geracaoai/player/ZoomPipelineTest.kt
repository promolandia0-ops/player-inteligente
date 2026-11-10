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

    // ── Retrato 9:16 (celular em pé, câmera parada, mulher pequena no meio-esquerda) ──

    private fun comRuido(src: Bitmap, rnd: java.util.Random): Bitmap {
        val b = src.copy(Bitmap.Config.ARGB_8888, true)
        val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        for (i in px.indices) {
            val c = px[i]; val d = (rnd.nextGaussian() * 3.0).toInt()
            fun k(v: Int) = (v + d).coerceIn(0, 255)
            px[i] = Color.rgb(k(Color.red(c)), k(Color.green(c)), k(Color.blue(c)))
        }
        b.setPixels(px, 0, b.width, 0, 0, b.width, b.height); return b
    }

    private fun retrato30Quadros(nome: String, modo: ModoZoom = ModoZoom.CORPO): String {
        // Vídeo 720×1280 em Ajustar num celular em pé 1080×2340 → view 1080×1920.
        val vwR = 1080f; val vhR = 1920f; val cwR = 1080f; val chR = 2340f
        val base = frame(nome); val rnd = java.util.Random(42)
        val log = StringBuilder()
        comDetector { r ->
            val c = ZoomControle(); var aplicado: TamanhoLogica.ZoomAlvo? = null
            var primeiroLock = -1; var primeiroZoom = -1; val zooms = ArrayList<Float>(); var ocupa = 0; var ligadoDepois = true
            var ultimaPartes: List<TamanhoLogica.CaixaParte> = emptyList()
            for (i in 0 until 30) {
                val res = r.passo(comRuido(base, rnd))
                if (res.partes.isNotEmpty()) { if (primeiroLock < 0) primeiroLock = i + 1; ultimaPartes = res.partes }
                val s = c.passo(res.partes, modo, vwR, vhR, cwR, chR)
                if (s.estado == ZoomControle.Estado.OCUPA_QUADRO) ocupa++
                s.alvo?.let { a -> zooms += a.zoom; if (a.zoom > 1.05f && primeiroZoom < 0) primeiroZoom = i + 1; if (TamanhoLogica.deveAtualizarAlvo(aplicado, a)) aplicado = a }
                if (primeiroZoom in 1..i && !c.zoomEstaLigado) ligadoDepois = false
                log.append("  ciclo ${i + 1}: modo=${res.modo} inf=${res.inferencias} partes=${res.partes.joinToString { "${it.classe}:${"%.2f".format(it.caixa.sc)}" }} estado=${s.estado} zoom=${s.alvo?.zoom}\n")
            }
            val a = aplicado
            val box = TamanhoLogica.caixaCorpoPadded(ultimaPartes)
            log.append("RESUMO $nome lock=$primeiroLock zoom1=$primeiroZoom zooms=${zooms.minOrNull()}..${zooms.maxOrNull()} ocupa=$ocupa aplicado=$a box=$box ligadoSempre=$ligadoDepois\n")
            assertTrue("$nome: lock em 1–3 ciclos (foi $primeiroLock)\n$log", primeiroLock in 1..3)
            assertTrue("$nome: zoom ligado em até 4 ciclos (foi $primeiroZoom)\n$log", primeiroZoom in 1..4)
            assertEquals("$nome: nunca 'ocupa o quadro'", 0, ocupa)
            assertTrue("$nome: zoom nunca desliga\n$log", ligadoDepois)
            assertTrue("$nome: zoom>1,8 (min=${zooms.minOrNull()})\n$log", zooms.minOrNull()!! > 1.8f)
            assertTrue("$nome: zoom constante ${zooms.minOrNull()}..${zooms.maxOrNull()}", zooms.maxOrNull()!! / zooms.minOrNull()!! < 1.12f)
            for (nx in floatArrayOf(box!!.x0, box.x1)) for (ny in floatArrayOf(box.y0, box.y1)) {
                val (sx, sy) = TamanhoLogica.projetar(nx, ny, vwR, vhR, cwR, chR, a!!.zoom, a.panX, a.panY)
                assertTrue("$nome: x=$sx fora", sx in -2f..(cwR + 2f)); assertTrue("$nome: y=$sy fora", sy in -2f..(chR + 2f))
            }
        }
        return log.toString()
    }

    @Test fun retrato9x16MulherA15PorCentoDaAlturaTrava() { println(retrato30Quadros("sala_192.jpg")) }
    @Test fun retrato9x16MulherA20PorCentoDaAlturaTrava() { println(retrato30Quadros("sala_256.jpg")) }
    @Test fun retrato9x16MulherA25PorCentoDaAlturaTrava() { println(retrato30Quadros("sala_320.jpg")) }
    @Test fun retrato9x16MulherPequenaForaDoCentroTambemTravaEm3Ciclos() {
        println(retrato30Quadros("sala_192_baixo.jpg")); println(retrato30Quadros("sala_192_alto.jpg"))
    }
}
