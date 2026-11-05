package br.geracaoai.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.tanh

/** Matemática do ganho. nivel: 0..2 (0–100% = volume do sistema; 100–200% = reforço real). */
object Ganho {
    const val MAX_FATOR = 3.0f // 200% => 3x amplitude (~+9,5 dB)
    fun fator(nivel: Float): Float = if (nivel <= 1f) 1f else 1f + (nivel.coerceAtMost(2f) - 1f) * (MAX_FATOR - 1f)
    fun db(nivel: Float): Float = (20 * log10(fator(nivel).toDouble())).toFloat()
    /** Fração do volume do sistema (0..1) para um nível. */
    fun sistema(nivel: Float): Float = nivel.coerceIn(0f, 1f)
    /** Limitador suave: linear até o joelho, depois satura com tanh, nunca passa de 1. */
    const val JOELHO = 0.8f
    fun limitar(x: Float): Float { val a = abs(x); if (a <= JOELHO) return x
        val r = 1f - JOELHO; val y = JOELHO + r * tanh(((a - JOELHO) / r).toDouble()).toFloat(); return if (x < 0) -y else y }
    fun amostra(s: Short, fator: Float): Short = if (fator == 1f) s else (limitar(s / 32768f * fator) * 32767f).toInt().toShort()
}

/** AudioProcessor do Media3 que aplica o ganho com limitador em PCM 16 bits. */
class ReforcoAudio : BaseAudioProcessor() {
    @Volatile var fator = 1f
    override fun onConfigure(f: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (f.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(f)
        return f
    }
    override fun queueInput(input: ByteBuffer) {
        val n = input.remaining(); if (n == 0) return
        val out = replaceOutputBuffer(n)
        val g = fator
        input.order(ByteOrder.LITTLE_ENDIAN)
        while (input.remaining() >= 2) out.putShort(Ganho.amostra(input.short, g))
        out.flip()
    }
}
