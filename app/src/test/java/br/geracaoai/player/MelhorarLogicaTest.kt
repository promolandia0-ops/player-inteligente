package br.geracaoai.player

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class MelhorarLogicaTest {
    @Test fun dimensoesParesELimitadas() {
        val (w, h) = MelhorarLogica.dimensoesSaida(1280, 720)
        assertEquals(0, w % 2); assertEquals(0, h % 2)
        assertTrue(w >= 1280); assertTrue(h >= 720)
        assertTrue(maxOf(w, h) <= 1920 * 2) // cap via esc
        val (w2, h2) = MelhorarLogica.dimensoesSaida(3840, 2160)
        assertTrue(maxOf(w2, h2) <= 1920)
    }

    @Test fun fpsCaiEmVideosLongos() {
        assertEquals(20, MelhorarLogica.fpsPara(30_000))
        assertEquals(15, MelhorarLogica.fpsPara(120_000))
        assertEquals(12, MelhorarLogica.fpsPara(600_000))
    }

    @Test fun progressoNuncaZeroNoPrimeiroFrame() {
        assertTrue(MelhorarLogica.progressoFrac(0, 100) >= 0.01f)
        assertEquals(0.99f, MelhorarLogica.progressoFrac(99, 100), 0.001f)
    }

    @Test fun ajustarTamanhoExato() {
        val src = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888)
        src.eraseColor(Color.RED)
        val out = MelhorarLogica.ajustarTamanho(src, 128, 64)
        assertEquals(128, out.width)
        assertEquals(64, out.height)
    }

    @Test fun sharpenAlteraPixels() {
        val src = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        // gradiente
        for (y in 0 until 32) for (x in 0 until 32) {
            val v = (x * 8).coerceIn(0, 255)
            src.setPixel(x, y, Color.rgb(v, v, v))
        }
        val out = MelhorarLogica.sharpen(src)
        var dif = 0
        for (y in 2 until 30) for (x in 2 until 30) {
            if (src.getPixel(x, y) != out.getPixel(x, y)) dif++
        }
        assertTrue("sharpen deve alterar o miolo", dif > 10)
    }

    @Test fun nv12TamanhoCorreto() {
        val src = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        src.eraseColor(Color.GREEN)
        val yuv = MelhorarLogica.argbToNv12(src)
        assertEquals(64 * 48 * 3 / 2, yuv.size)
    }
}
