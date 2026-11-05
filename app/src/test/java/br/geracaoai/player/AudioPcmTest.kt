package br.geracaoai.player

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioPcmTest {
    @Test fun resample48000para16000() {
        val src = FloatArray(4800) { i -> kotlin.math.sin(i * 0.01f) }
        val y = AudioPcm.resample(src, 48_000, 16_000)
        assertEquals(1600, y.size)
        assertTrue(AudioPcm.rms(y) > 0.01f)
    }

    @Test fun resampleIdentidade() {
        val x = floatArrayOf(0f, 0.5f, -0.5f, 1f)
        assertArrayEquals(x, AudioPcm.resample(x, 16_000, 16_000), 0f)
    }

    @Test fun appendPcm16StereoMono() {
        val buf = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder())
        // 2 frames stereo: (1000,2000), (3000,4000)
        buf.putShort(1000); buf.putShort(2000)
        buf.putShort(3000); buf.putShort(4000)
        buf.flip()
        val out = ArrayList<Float>()
        AudioPcm.appendPcm(buf, ch = 2, encoding = android.media.AudioFormat.ENCODING_PCM_16BIT, out, 100)
        assertEquals(2, out.size)
        assertEquals(((1000 + 2000) / 2) / 32768f, out[0], 1e-5f)
        assertEquals(((3000 + 4000) / 2) / 32768f, out[1], 1e-5f)
    }

    @Test fun appendPcmFloat() {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())
        buf.putFloat(0.5f); buf.putFloat(-0.25f); buf.flip()
        val out = ArrayList<Float>()
        AudioPcm.appendPcm(buf, ch = 1, encoding = android.media.AudioFormat.ENCODING_PCM_FLOAT, out, 100)
        assertEquals(2, out.size)
        assertEquals(0.5f, out[0], 0f)
        assertEquals(-0.25f, out[1], 0f)
    }

    @Test fun rmsSilencio() {
        assertEquals(0f, AudioPcm.rms(FloatArray(100)), 0f)
        assertTrue(AudioPcm.rms(FloatArray(100) { 0.1f }) > 0.09f)
    }

    @Test fun tradutorLexicoBasico() {
        val pt = TradutorLexico.traduzir("I love you")
        assertTrue(pt.lowercase().contains("eu"))
        assertTrue(pt.lowercase().contains("amor") || pt.lowercase().contains("love"))
        assertTrue(pt.lowercase().contains("você") || pt.lowercase().contains("you"))
    }
}
