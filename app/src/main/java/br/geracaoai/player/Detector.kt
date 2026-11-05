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
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** NudeNet 320n (YOLOv8n, 18 classes) — UMA OrtSession reutilizada; tensors fechados em use{}. */
class Detector(ctx: Context, usarAcelerador0: Boolean) : AutoCloseable {
    companion object {
        /** NNAPI derrubou o processo antes (crash nativo) → desliga sozinho. */
        fun nnapiBloqueado(ctx: Context) = File(ctx.filesDir, "nnapi_off").exists()
        fun liberarNnapi(ctx: Context) { File(ctx.filesDir, "nnapi_off").delete(); File(ctx.filesDir, "nnapi_guard").delete() }
    }
    private val guard = File(ctx.filesDir, "nnapi_guard")
    private val off = File(ctx.filesDir, "nnapi_off")
    private var guardAtivo = false
    private val env = OrtEnvironment.getEnvironment()
    private val sessao: OrtSession
    private val opcoes: OrtSession.SessionOptions
    var backend = "CPU"; private set
    private val S = 320
    private val buf: FloatBuffer = ByteBuffer.allocateDirect(4 * 3 * S * S).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val px = IntArray(S * S)
    private val tela = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        if (guard.exists()) { runCatching { off.writeText("crash nativo com NNAPI"); guard.delete() } }
        val usarAcelerador = usarAcelerador0 && !off.exists()
        val modelo = ctx.assets.open("nudenet_320n.onnx").use { it.readBytes() }
        fun opts(nnapi: Boolean) = OrtSession.SessionOptions().apply {
            setSymbolicDimensionValue("batch", 1)
            setSymbolicDimensionValue("height", S.toLong())
            setSymbolicDimensionValue("width", S.toLong())
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(2) // menos pressão de memória na galeria
            if (nnapi) addNnapi() else runCatching { addXnnpack(mapOf("intra_op_num_threads" to "2")) }
        }
        var s: OrtSession? = null
        var o: OrtSession.SessionOptions? = null
        if (usarAcelerador) {
            o = opts(true)
            runCatching { guard.writeText("1") }; guardAtivo = true
            s = runCatching { env.createSession(modelo, o).also { backend = "NNAPI" } }
                .onFailure { Log.w("Detector", "NNAPI indisponível", it); runCatching { o?.close() }; o = null }
                .getOrNull()
        }
        if (s == null) {
            o = opts(false)
            s = runCatching { env.createSession(modelo, o).also { backend = "CPU (XNNPACK)" } }
                .onFailure { runCatching { o?.close() }; o = null }
                .getOrNull()
        }
        if (s == null) {
            o = OrtSession.SessionOptions()
            s = env.createSession(modelo, o).also { backend = "CPU" }
        }
        if (backend != "NNAPI" && guardAtivo) { guard.delete(); guardAtivo = false }
        sessao = s!!
        opcoes = o!!
    }

    fun pontuar(frame: Bitmap): Map<Categoria, Float> {
        preparar(frame)
        OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, S.toLong(), S.toLong())).use { t ->
            sessao.run(mapOf(sessao.inputNames.first() to t)).use { r ->
                val out = (r[0] as OnnxTensor)
                val a = out.info.shape[2].toInt()
                val fb = out.floatBuffer
                okRodou()
                return Categoria.entries.associateWith { cat ->
                    val base = (4 + cat.classIndex) * a; var m = 0f
                    for (i in 0 until a) { val v = fb.get(base + i); if (v > m) m = v }
                    m
                }
            }
        }
    }

    fun pontuarExposto(frame: Bitmap, limiar: Float = OrganizarWorker.LIMIAR): Pair<Set<Categoria>, Boolean> {
        preparar(frame)
        OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, S.toLong(), S.toLong())).use { tensor ->
            sessao.run(mapOf(sessao.inputNames.first() to tensor)).use { r ->
                val out = (r[0] as OnnxTensor)
                val a = out.info.shape[2].toInt()
                val fb = out.floatBuffer
                fun maxClasse(idx: Int): Float {
                    val base = (4 + idx) * a; var m = 0f
                    for (i in 0 until a) { val v = fb.get(base + i); if (v > m) m = v }
                    return m
                }
                okRodou()
                val ui = Categoria.entries.associateWith { maxClasse(it.classIndex) }
                val cats = ui.filter { it.value >= limiar }.keys
                val putaria = OrganizarWorker.CLASSES_EXPOSTAS.any { maxClasse(it) >= limiar }
                return cats to putaria
            }
        }
    }

    /** Primeira inferência NNAPI ok → remove a sentinela. */
    private fun okRodou() { if (guardAtivo) { guard.delete(); guardAtivo = false } }

    private fun preparar(frame: Bitmap) {
        val c = Canvas(tela); c.drawColor(Color.BLACK)
        val esc = S.toFloat() / maxOf(frame.width, frame.height)
        val w = (frame.width * esc).toInt().coerceAtLeast(1)
        val h = (frame.height * esc).toInt().coerceAtLeast(1)
        c.drawBitmap(frame, android.graphics.Rect(0, 0, frame.width, frame.height), android.graphics.Rect(0, 0, w, h), paint)
        tela.getPixels(px, 0, S, 0, 0, S, S)
        buf.rewind()
        val n = S * S
        for (i in 0 until n) {
            val p = px[i]
            buf.put(i, ((p shr 16) and 0xFF) / 255f)
            buf.put(n + i, ((p shr 8) and 0xFF) / 255f)
            buf.put(2 * n + i, (p and 0xFF) / 255f)
        }
        buf.rewind()
    }

    override fun close() {
        runCatching { sessao.close() }
        runCatching { opcoes.close() }
        okRodou()
        if (!tela.isRecycled) tela.recycle()
    }
}
