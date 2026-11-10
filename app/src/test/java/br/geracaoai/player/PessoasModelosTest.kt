package br.geracaoai.player

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Pipeline REAL (YuNet + SFace int8 via ONNX Runtime desktop) em rostos públicos do LFW (só rostos SFW, nada de vídeo do usuário).
 * Pula sozinho se as imagens de teste não estiverem na máquina ([PASTA]).
 */
class PessoasModelosTest {
    companion object { const val PASTA = "/workspace/facemodels/ppm" }

    private fun motor() = RostoMotor(File("src/main/assets/face_det_yunet.onnx").readBytes(), File("src/main/assets/face_rec_sface_int8.onnx").readBytes())
    /** PPM (P6) gerado de fotos públicas do LFW (JPEG não decodifica na JVM sem Android). */
    private fun pixels(f: File): Triple<IntArray, Int, Int> {
        val b = f.readBytes(); var pos = 0; val tok = ArrayList<String>()
        while (tok.size < 4) { while (b[pos].toInt().toChar().isWhitespace()) pos++; val ini = pos; while (!b[pos].toInt().toChar().isWhitespace()) pos++; tok += String(b, ini, pos - ini) }
        pos++; val w = tok[1].toInt(); val h = tok[2].toInt()
        return Triple(IntArray(w * h) { i -> (0xFF shl 24) or ((b[pos + 3 * i].toInt() and 255) shl 16) or ((b[pos + 3 * i + 1].toInt() and 255) shl 8) or (b[pos + 3 * i + 2].toInt() and 255) }, w, h)
    }

    @Test fun assetsDosModelosExistemETemTamanhoRazoavel() {
        val d = File("src/main/assets/face_det_yunet.onnx"); val r = File("src/main/assets/face_rec_sface_int8.onnx")
        assertTrue(d.length() in 100_000..2_000_000); assertTrue(r.length() in 5_000_000..15_000_000)
    }

    @Test fun lfwMesmaPessoaAgrupaEPessoasDiferentesNaoSeJuntam() {
        val dir = File(PASTA); assumeTrue(dir.isDirectory)
        val arquivos = dir.listFiles { f -> f.name.endsWith(".ppm") }!!.sortedBy { it.name }
        assumeTrue(arquivos.size >= 100)
        val nome = { f: File -> f.name.substringBeforeLast('_') }
        val somas = HashMap<Long, FloatArray>(); val dono = HashMap<Long, MutableList<String>>(); var prox = 1L
        var semRosto = 0; var usados = 0
        motor().use { m ->
            // "vídeo" = 3 fotos seguidas da mesma pessoa (como 3 quadros do mesmo vídeo)
            for ((_, fotos) in arquivos.groupBy(nome)) for (grupo in fotos.chunked(3)) {
                val obs = ArrayList<Agrupamento.Obs>()
                for (f in grupo) { val (p, w, h) = pixels(f); val r = m.analisar(p, w, h); if (r.isEmpty()) semRosto++ else { val x = r.maxByOrNull { it.rosto.largura }!!; obs += Agrupamento.Obs(x.emb, x.rosto.score, x.nitidez, obs.size) } }
                for (t in Agrupamento.tracar(obs)) {
                    val alvo = Agrupamento.melhorPessoa(somas, t.emb) ?: prox++.also { somas[it] = FloatArray(128) }
                    val s = somas[alvo]!!; for (k in 0 until 128) s[k] += t.emb[k]
                    dono.getOrPut(alvo) { ArrayList() } += nome(grupo[0]); usados++
                }
            }
        }
        val pessoas = arquivos.map(nome).toSet().size
        val errados = dono.values.sumOf { l -> l.size - l.groupBy { it }.maxOf { it.value.size } }
        println("LFW: $pessoas pessoas reais, ${dono.size} grupos, $usados trilhas, $errados na pessoa errada, $semRosto fotos sem rosto aceito")
        assertEquals("nenhuma trilha agrupada com a pessoa errada", 0, errados)
        assertTrue("grupos ${dono.size} p/ $pessoas pessoas", dono.size in pessoas..(pessoas * 2))
    }
}
