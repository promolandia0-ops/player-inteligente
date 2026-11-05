package br.geracaoai.player

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** NudeNet 320n (YOLOv8n, 18 classes) rodando 100% no aparelho via ONNX Runtime Mobile. */
class Detector(ctx: Context, usarAcelerador: Boolean) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val sessao: OrtSession
    var backend = "CPU"; private set
    private val S = 320
    private val buf: FloatBuffer = ByteBuffer.allocateDirect(4 * 3 * S * S).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val px = IntArray(S * S)
    private val tela = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        val modelo = ctx.assets.open("nudenet_320n.onnx").use { it.readBytes() }
        fun opts(nnapi: Boolean) = OrtSession.SessionOptions().apply {
            setSymbolicDimensionValue("batch", 1)
            setSymbolicDimensionValue("height", S.toLong())
            setSymbolicDimensionValue("width", S.toLong())
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(4)
            if (nnapi) addNnapi() else runCatching { addXnnpack(mapOf("intra_op_num_threads" to "4")) }
        }
        var s: OrtSession? = null
        if (usarAcelerador) s = runCatching { env.createSession(modelo, opts(true)).also { backend = "NNAPI" } }
            .onFailure { Log.w("Detector", "NNAPI indisponível", it) }.getOrNull()
        if (s == null) s = runCatching { env.createSession(modelo, opts(false)).also { backend = "CPU (XNNPACK)" } }.getOrNull()
        if (s == null) s = env.createSession(modelo, OrtSession.SessionOptions()).also { backend = "CPU" }
        sessao = s!!
    }

    /** Retorna, para cada categoria, a maior confiança entre todas as caixas do frame. */
    fun pontuar(frame: Bitmap): Map<Categoria, Float> {
        // letterbox: encaixa no canto superior esquerdo, completa com preto (igual ao NudeNet)
        val c = Canvas(tela); c.drawColor(Color.BLACK)
        val esc = S.toFloat() / maxOf(frame.width, frame.height)
        val w = (frame.width * esc).toInt().coerceAtLeast(1); val h = (frame.height * esc).toInt().coerceAtLeast(1)
        c.drawBitmap(frame, android.graphics.Rect(0, 0, frame.width, frame.height), android.graphics.Rect(0, 0, w, h), paint)
        tela.getPixels(px, 0, S, 0, 0, S, S)
        buf.rewind()
        val n = S * S
        for (i in 0 until n) { val p = px[i]; buf.put(i, ((p shr 16) and 0xFF) / 255f); buf.put(n + i, ((p shr 8) and 0xFF) / 255f); buf.put(2 * n + i, (p and 0xFF) / 255f) }
        buf.rewind()
        OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, S.toLong(), S.toLong())).use { t ->
            sessao.run(mapOf(sessao.inputNames.first() to t)).use { r ->
                val out = (r[0] as OnnxTensor)
                val shape = out.info.shape // [1, 22, A]
                val a = shape[2].toInt()
                val fb = out.floatBuffer
                return Categoria.entries.associateWith { cat ->
                    val base = (4 + cat.classIndex) * a; var m = 0f
                    for (i in 0 until a) { val v = fb.get(base + i); if (v > m) m = v }
                    m
                }
            }
        }
    }

    override fun close() { sessao.close() }
}
