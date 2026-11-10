package br.geracaoai.player

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * "Pessoas" (v1.3.20) — módulo ISOLADO de rostos, 100% offline. Não usa o Detector/NudeNet.
 * Modelos (assets): YuNet (detector de rosto + 5 pontos, MIT, 0,2 MB) e SFace int8 (embedding 128-d, Apache-2.0, 9,9 MB),
 * ambos do OpenCV Zoo. Este arquivo é Kotlin puro + ONNX Runtime (roda na JVM nos testes).
 */
object RostoMat {
    const val ENTRADA = 640
    const val ALINHADO = 112
    /** Pontos de referência do SFace (olho dir., olho esq., nariz, canto dir./esq. da boca) em 112×112. */
    private val MODELO = floatArrayOf(38.2946f, 51.6963f, 73.5318f, 51.5014f, 56.0252f, 71.7366f, 41.5493f, 92.3655f, 70.7299f, 92.2041f)

    class Rosto(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val score: Float, val kps: FloatArray) {
        val largura: Float get() = x1 - x0
    }

    /** Quadro ARGB → tensor BGR 0–255 640×640 (canto sup. esq., resto preto). Devolve (dados, escala). */
    fun letterbox(pix: IntArray, w: Int, h: Int): Pair<FloatArray, Float> {
        val s = ENTRADA.toFloat() / max(w, h)
        val nw = Math.round(w * s).coerceIn(1, ENTRADA); val nh = Math.round(h * s).coerceIn(1, ENTRADA)
        val plano = ENTRADA * ENTRADA
        val out = FloatArray(3 * plano)
        for (y in 0 until nh) {
            val sy = ((y + 0.5f) / s - 0.5f).coerceIn(0f, h - 1f); val y0 = sy.toInt(); val y1 = min(y0 + 1, h - 1); val fy = sy - y0
            for (x in 0 until nw) {
                val sx = ((x + 0.5f) / s - 0.5f).coerceIn(0f, w - 1f); val x0 = sx.toInt(); val x1 = min(x0 + 1, w - 1); val fx = sx - x0
                val p00 = pix[y0 * w + x0]; val p01 = pix[y0 * w + x1]; val p10 = pix[y1 * w + x0]; val p11 = pix[y1 * w + x1]
                fun canal(sh: Int): Float {
                    val a = (p00 shr sh and 255) * (1 - fx) + (p01 shr sh and 255) * fx
                    val b = (p10 shr sh and 255) * (1 - fx) + (p11 shr sh and 255) * fx
                    return a * (1 - fy) + b * fy
                }
                val i = y * ENTRADA + x
                out[i] = canal(0); out[plano + i] = canal(8); out[2 * plano + i] = canal(16)   // B, G, R
            }
        }
        return out to s
    }

    /** Decodifica as 12 saídas do YuNet (cls/obj/bbox/kps × strides 8/16/32) → rostos em coords do quadro original. */
    fun decodificar(saida: (String) -> FloatArray, escala: Float, limiar: Float = 0.6f): List<Rosto> {
        val achados = ArrayList<Rosto>()
        for (st in intArrayOf(8, 16, 32)) {
            val cols = ENTRADA / st
            val cls = saida("cls_$st"); val obj = saida("obj_$st"); val bb = saida("bbox_$st"); val kp = saida("kps_$st")
            for (i in cls.indices) {
                val sc = sqrt(cls[i].coerceIn(0f, 1f) * obj[i].coerceIn(0f, 1f))
                if (sc < limiar) continue
                val r = i / cols; val c = i % cols
                val cx = (c + bb[i * 4]) * st; val cy = (r + bb[i * 4 + 1]) * st
                val w = exp(bb[i * 4 + 2]) * st; val h = exp(bb[i * 4 + 3]) * st
                val k = FloatArray(10) { n -> ((if (n % 2 == 0) kp[i * 10 + n] + c else kp[i * 10 + n] + r) * st) / escala }
                achados += Rosto((cx - w / 2) / escala, (cy - h / 2) / escala, (cx + w / 2) / escala, (cy + h / 2) / escala, sc, k)
            }
        }
        return nms(achados)
    }

    fun nms(l: List<Rosto>, iouMax: Float = 0.3f): List<Rosto> {
        val keep = ArrayList<Rosto>()
        for (d in l.sortedByDescending { it.score }) if (keep.all { iou(it, d) <= iouMax }) keep += d
        return keep
    }

    private fun iou(a: Rosto, b: Rosto): Float {
        val i = max(0f, min(a.x1, b.x1) - max(a.x0, b.x0)) * max(0f, min(a.y1, b.y1) - max(a.y0, b.y0))
        val u = (a.x1 - a.x0) * (a.y1 - a.y0) + (b.x1 - b.x0) * (b.y1 - b.y0) - i
        return if (u > 0f) i / u else 0f
    }

    /** Rotação do olhar (graus) e "yaw" = desvio do nariz em relação ao meio dos olhos / distância entre olhos (0 = de frente). */
    fun rollYaw(k: FloatArray): Pair<Float, Float> {
        val dx = k[2] - k[0]; val dy = k[3] - k[1]
        val roll = Math.abs(Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble()))).toFloat()
        val olhos = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-3f)
        val yaw = Math.abs(k[4] - (k[0] + k[2]) / 2f) / olhos
        return roll to yaw
    }

    /** Recorte alinhado 112×112 (RGB 0–255, CHW) por similaridade (4 graus de liberdade) dos 5 pontos ao modelo. */
    fun alinhar(pix: IntArray, w: Int, h: Int, k: FloatArray): FloatArray {
        val msx = (k[0] + k[2] + k[4] + k[6] + k[8]) / 5f; val msy = (k[1] + k[3] + k[5] + k[7] + k[9]) / 5f
        val mdx = (MODELO[0] + MODELO[2] + MODELO[4] + MODELO[6] + MODELO[8]) / 5f; val mdy = (MODELO[1] + MODELO[3] + MODELO[5] + MODELO[7] + MODELO[9]) / 5f
        var num1 = 0f; var num2 = 0f; var den = 1e-6f
        for (i in 0 until 5) {
            val sx = k[2 * i] - msx; val sy = k[2 * i + 1] - msy; val dx = MODELO[2 * i] - mdx; val dy = MODELO[2 * i + 1] - mdy
            num1 += sx * dx + sy * dy; num2 += sx * dy - sy * dx; den += sx * sx + sy * sy
        }
        val a = num1 / den; val b = num2 / den                       // dst = [[a,-b],[b,a]]·src + t
        val tx = mdx - (a * msx - b * msy); val ty = mdy - (b * msx + a * msy)
        val m2 = (a * a + b * b).coerceAtLeast(1e-9f)
        val plano = ALINHADO * ALINHADO
        val out = FloatArray(3 * plano)
        for (y in 0 until ALINHADO) for (x in 0 until ALINHADO) {
            val ux = x + 0.5f - tx; val uy = y + 0.5f - ty
            val sx = (a * ux + b * uy) / m2 - 0.5f; val sy = (-b * ux + a * uy) / m2 - 0.5f
            val i = y * ALINHADO + x
            if (sx < -1f || sy < -1f || sx > w || sy > h) continue    // fora do quadro = preto
            val x0 = Math.floor(sx.toDouble()).toInt(); val y0 = Math.floor(sy.toDouble()).toInt(); val fx = sx - x0; val fy = sy - y0
            fun px(xx: Int, yy: Int) = if (xx < 0 || yy < 0 || xx >= w || yy >= h) 0 else pix[yy * w + xx]
            val p00 = px(x0, y0); val p01 = px(x0 + 1, y0); val p10 = px(x0, y0 + 1); val p11 = px(x0 + 1, y0 + 1)
            fun canal(sh: Int): Float {
                val u = (p00 shr sh and 255) * (1 - fx) + (p01 shr sh and 255) * fx
                val v = (p10 shr sh and 255) * (1 - fx) + (p11 shr sh and 255) * fx
                return u * (1 - fy) + v * fy
            }
            out[i] = canal(16); out[plano + i] = canal(8); out[2 * plano + i] = canal(0)   // R, G, B
        }
        return out
    }

    /** Nitidez = variância do laplaciano da luminância do recorte alinhado (baixa = borrado). */
    fun nitidez(chw: FloatArray): Float {
        val n = ALINHADO; val plano = n * n
        val g = FloatArray(plano) { 0.299f * chw[it] + 0.587f * chw[plano + it] + 0.114f * chw[2 * plano + it] }
        var s = 0.0; var s2 = 0.0; var c = 0
        for (y in 1 until n - 1) for (x in 1 until n - 1) {
            val l = g[y * n + x] * 4 - g[(y - 1) * n + x] - g[(y + 1) * n + x] - g[y * n + x - 1] - g[y * n + x + 1]
            s += l; s2 += l * l; c++
        }
        val m = s / c
        return (s2 / c - m * m).toFloat()
    }

    fun normalizar(v: FloatArray): FloatArray {
        var n = 0.0; for (x in v) n += x * x
        val d = sqrt(n).toFloat().coerceAtLeast(1e-9f)
        return FloatArray(v.size) { v[it] / d }
    }

    fun cos(a: FloatArray, b: FloatArray): Float { var s = 0f; for (i in a.indices) s += a[i] * b[i]; return s }
}

/** Um rosto bom (de frente, nítido, grande o bastante) já com embedding normalizado. */
class RostoDetectado(val rosto: RostoMat.Rosto, val emb: FloatArray, val nitidez: Float, val roll: Float, val yaw: Float)

/** Detector + embedding. UMA instância por job; [close] libera as sessões. */
class RostoMotor(detBytes: ByteArray, recBytes: ByteArray) : AutoCloseable {
    companion object {
        const val SCORE_MIN = 0.8f
        const val LARGURA_MIN = 56f
        const val ROLL_MAX = 25f
        const val YAW_MAX = 0.35f
        const val NITIDEZ_MIN = 30f
    }
    private val env = OrtEnvironment.getEnvironment()
    private val optDet = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
    private val optRec = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
    private val det = env.createSession(detBytes, optDet)
    private val rec = env.createSession(recBytes, optRec)

    /** Todos os rostos com score ≥ 0,6 (sem embedding) — útil p/ diagnóstico. */
    fun detectar(pix: IntArray, w: Int, h: Int): List<RostoMat.Rosto> {
        val (dados, escala) = RostoMat.letterbox(pix, w, h)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(dados), longArrayOf(1, 3, RostoMat.ENTRADA.toLong(), RostoMat.ENTRADA.toLong())).use { t ->
            det.run(mapOf(det.inputNames.first() to t)).use { r ->
                val cache = HashMap<String, FloatArray>()
                fun saida(n: String): FloatArray = cache.getOrPut(n) {
                    val fb = (r.get(n).get() as OnnxTensor).floatBuffer; FloatArray(fb.remaining()).also { fb.get(it) }
                }
                return RostoMat.decodificar(::saida, escala)
            }
        }
    }

    fun embedding(chw: FloatArray): FloatArray {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), longArrayOf(1, 3, RostoMat.ALINHADO.toLong(), RostoMat.ALINHADO.toLong())).use { t ->
            rec.run(mapOf(rec.inputNames.first() to t)).use { r ->
                val fb = (r[0] as OnnxTensor).floatBuffer; val v = FloatArray(fb.remaining()); fb.get(v)
                return RostoMat.normalizar(v)
            }
        }
    }

    /** Rostos de boa qualidade do quadro (frontais, nítidos, ≥ [LARGURA_MIN] px) com embedding. */
    fun analisar(pix: IntArray, w: Int, h: Int): List<RostoDetectado> {
        val out = ArrayList<RostoDetectado>()
        for (f in detectar(pix, w, h)) {
            if (f.score < SCORE_MIN || f.largura < LARGURA_MIN) continue
            val (roll, yaw) = RostoMat.rollYaw(f.kps)
            if (roll > ROLL_MAX || yaw > YAW_MAX) continue
            val chw = RostoMat.alinhar(pix, w, h, f.kps)
            val nit = RostoMat.nitidez(chw)
            if (nit < NITIDEZ_MIN) continue
            out += RostoDetectado(f, embedding(chw), nit, roll, yaw)
        }
        return out
    }

    override fun close() { runCatching { det.close() }; runCatching { rec.close() }; runCatching { optDet.close() }; runCatching { optRec.close() } }
}
