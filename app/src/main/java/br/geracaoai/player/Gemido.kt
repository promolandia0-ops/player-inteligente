package br.geracaoai.player

import android.content.Context
import android.net.Uri
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * Detector de gemido/respiração pesada via YAMNet (AudioSet) + heurística de energia.
 * AudioSet NÃO tem "gemido sexual" explícito — usamos classes próximas (Wail/moan, Groan,
 * Sigh, Gasp, Breathing, Pant, Whimper, Sob) e exigimos energia/variação. Expectativa:
 * muitos falsos positivos em música/drama e falsos negativos em gemidos baixos.
 */
object Gemido {
    private const val TAG = "Gemido"
    private const val SR = 16000
    private const val WIN = 15600 // 0.975s @ 16k — entrada YAMNet
    // Índices AudioSet / YAMNet (yamnet_class_map.csv)
    private val CLASSES = intArrayOf(
        23,  // Baby cry, infant cry (às vezes)
        24,  // Whimper
        25,  // Wail, moan
        26,  // Sigh
        27,  // Singing (ruído — usamos baixo peso)
        30,  // Groan
        31,  // Grunt
        32,  // Yawn
        37,  // Gasp
        38,  // Pant
        39,  // Snore (evitar)
        42,  // Breathing
        0,   // Speech — penaliza
    )
    private val PESO = floatArrayOf(0.3f, 0.8f, 1.2f, 0.7f, -0.5f, 1.0f, 0.5f, 0.2f, 0.9f, 1.1f, -0.8f, 0.6f, -1.0f)

    fun analisar(ctx: Context, uri: Uri, durMs: Long, passoMs: Long = 1000L): FloatArray {
        val n = ((durMs / passoMs).toInt() + 1).coerceAtLeast(1)
        val out = FloatArray(n)
        val interp = try { abrir(ctx) } catch (e: Throwable) {
            Log.w(TAG, "YAMNet indisponível", e); return out
        }
        try {
            val pcm = decodificarPcm16k(ctx, uri) ?: return out
            val scores = Array(1) { FloatArray(521) }
            val input = Array(1) { FloatArray(WIN) }
            for (i in 0 until n) {
                val t0 = ((i * passoMs) * SR / 1000L).toInt()
                if (t0 >= pcm.size) break
                var energy = 0.0
                for (k in 0 until WIN) {
                    val s = if (t0 + k < pcm.size) pcm[t0 + k] else 0f
                    input[0][k] = s
                    energy += s * s
                }
                energy = sqrt(energy / WIN)
                interp.run(input, scores)
                var gem = 0f
                for (j in CLASSES.indices) {
                    val idx = CLASSES[j]
                    if (idx in scores[0].indices) gem += PESO[j] * scores[0][idx]
                }
                // energia: gemidos costumam ter RMS moderado-alto
                val eBoost = when {
                    energy < 0.01 -> 0.3f
                    energy < 0.04 -> 0.7f
                    energy < 0.2 -> 1.2f
                    else -> 0.8f // gritaria/música alta
                }
                out[i] = (gem * eBoost).coerceIn(0f, 1f)
            }
        } finally {
            runCatching { interp.close() }
        }
        return out
    }

    private fun abrir(ctx: Context): Interpreter {
        val fd = ctx.assets.openFd("audio/yamnet.tflite")
        FileInputStream(fd.fileDescriptor).channel.use { ch ->
            val map = ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            return Interpreter(map, Interpreter.Options().apply { setNumThreads(2) })
        }
    }

    /** PCM float mono -1..1 @ 16 kHz — delega ao decoder robusto. */
    fun decodificarPcm16k(ctx: Context, uri: Uri, maxSec: Int = 60 * 90): FloatArray? =
        AudioPcm.decodificarPcm16k(ctx, uri, maxSec)

}
