package br.geracaoai.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Um "melhor momento": janela [inicioMs, fimMs) com pontuação; [seekMs] é onde o player pula. */
data class Momento(val inicioMs: Long, val fimMs: Long, val seekMs: Long, val pontuacao: Float, val categoria: Categoria)

/**
 * Melhores momentos a partir das marcações JÁ salvas da análise (scores por categoria por quadro).
 * Não roda modelo nenhum. Só categorias femininas/nudez do NudeNet (a análise nunca grava masculino).
 *
 * Pesos: BUCETA e ANUS (mais), depois PEITO e BUNDA. Quadros acima do limiar contam com o próprio score.
 * Obs.: a análise por vídeo grava só 4 categorias — FACE_FEMALE não é gravada, então o bônus "rosto junto"
 * não pode ser calculado sem mexer no motor de análise. Em vez disso, quadros com 2+ categorias ao mesmo
 * tempo (ex.: peito + buceta) recebem um bônus de co-ocorrência.
 */
object MelhoresMomentos {
    const val MAX = 5
    const val MIN_JANELA_MS = 8_000L
    const val MAX_JANELA_MS = 15_000L
    const val BONUS_COOCORRENCIA = 1.25f
    private const val VERSAO = 1

    val PESOS: Map<Categoria, Float> = mapOf(
        Categoria.BUCETA to 3.0f, Categoria.ANUS to 3.0f,
        Categoria.PEITO to 1.5f, Categoria.BUNDA to 1.5f,
    )

    fun tamanhoJanela(duracaoMs: Long): Long = (duracaoMs / 12L).coerceIn(MIN_JANELA_MS, MAX_JANELA_MS)

    /** Pontuação de um quadro. */
    fun pontuarQuadro(scores: Map<Categoria, Float>, limiar: Float): Float {
        var s = 0f; var n = 0
        for ((c, w) in PESOS) {
            val v = scores[c] ?: 0f
            if (v >= limiar) { s += w * v; n++ }
        }
        return if (n >= 2) s * BONUS_COOCORRENCIA else s
    }

    fun calcular(a: Analise, limiar: Float, max: Int = MAX): List<Momento> {
        val n = a.tempos.size
        if (n == 0 || max <= 0) return emptyList()
        // A análise começa na posição do usuário e depois volta ao 0: ordena por tempo.
        val ord = (0 until n).sortedBy { a.tempos[it] }
        val t = LongArray(n) { a.tempos[ord[it]] }
        val q = FloatArray(n); val cat = Array(n) { Categoria.PEITO }
        for (k in 0 until n) {
            val i = ord[k]
            val m = HashMap<Categoria, Float>(4)
            var melhor = 0f
            for ((c, w) in PESOS) {
                val v = a.scores[c]?.getOrNull(i) ?: 0f
                m[c] = v
                if (v >= limiar && w * v > melhor) { melhor = w * v; cat[k] = c }
            }
            q[k] = pontuarQuadro(m, limiar)
        }
        if (q.all { it <= 0f }) return emptyList()
        val dur = if (a.duracaoMs > 0) a.duracaoMs else t.last()
        val jan = tamanhoJanela(dur)
        // Janela deslizante (dois ponteiros) começando em cada quadro.
        class Cand(val ini: Int, val score: Float)
        val cands = ArrayList<Cand>(n)
        var fim = 0; var soma = 0f
        for (ini in 0 until n) {
            if (fim < ini) { fim = ini; soma = 0f }
            while (fim < n && t[fim] - t[ini] < jan) { soma += q[fim]; fim++ }
            cands.add(Cand(ini, soma))
            soma -= q[ini]
        }
        // Início = 1º quadro marcado da janela (evita abrir em cena vazia); fim = início + janela.
        val minSep = maxOf(jan, dur / (max * 2L))
        val escolhidos = ArrayList<Momento>()
        for (c in cands.sortedByDescending { it.score }) {
            if (escolhidos.size >= max) break
            if (c.score <= 0f) break
            val primeiro = (c.ini until n).firstOrNull { q[it] > 0f && t[it] - t[c.ini] < jan } ?: continue
            val ini = t[primeiro]
            if (escolhidos.any { kotlin.math.abs(it.inicioMs - ini) < minSep }) continue
            // pico de categoria na janela
            var best = primeiro
            for (k in primeiro until n) { if (t[k] - ini >= jan) break; if (q[k] > q[best]) best = k }
            escolhidos.add(Momento(ini, minOf(ini + jan, maxOf(dur, ini)), (ini - 1_000L).coerceAtLeast(0L), c.score, cat[best]))
        }
        // Mantém só janelas relevantes (>= 25% da melhor) — mas sempre ao menos 3 se existirem.
        val topo = escolhidos.maxOfOrNull { it.pontuacao } ?: return emptyList()
        val rel = escolhidos.filter { it.pontuacao >= topo * 0.25f }
        val finais = if (rel.size >= 3) rel else escolhidos.sortedByDescending { it.pontuacao }.take(maxOf(3, rel.size))
        return finais.sortedBy { it.inicioMs }
    }

    fun formatar(ms: Long): String {
        val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val ss = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
    }

    // ---- cache (memória + arquivo em cacheDir/momentos) ----
    private val mem = object : LinkedHashMap<String, List<Momento>>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, List<Momento>>?) = size > 16
    }

    private fun assinatura(chave: String, a: Analise, limiar: Float) =
        "$chave|v$VERSAO|n${a.tempos.size}|p${a.parcial}|l${(limiar * 1000).toInt()}"

    private fun arq(ctx: Context, chave: String) =
        File(File(ctx.cacheDir, "momentos").apply { mkdirs() }, "$chave.json")

    /** Chamar fora da thread principal. */
    fun obter(ctx: Context, chave: String, a: Analise, limiar: Float): List<Momento> {
        val sig = assinatura(chave, a, limiar)
        synchronized(mem) { mem[sig]?.let { return it } }
        val lido = lerArquivo(ctx, chave, sig)
        val r = lido ?: calcular(a, limiar).also { runCatching { gravarArquivo(ctx, chave, sig, it) } }
        synchronized(mem) { mem[sig] = r }
        return r
    }

    private fun gravarArquivo(ctx: Context, chave: String, sig: String, l: List<Momento>) {
        val o = JSONObject().put("sig", sig).put("m", JSONArray().apply {
            l.forEach { put(JSONArray().put(it.inicioMs).put(it.fimMs).put(it.seekMs).put(it.pontuacao.toDouble()).put(it.categoria.name)) }
        })
        val f = arq(ctx, chave); val tmp = File(f.path + ".tmp")
        tmp.writeText(o.toString()); if (!tmp.renameTo(f)) { f.writeText(o.toString()); tmp.delete() }
    }

    private fun lerArquivo(ctx: Context, chave: String, sig: String): List<Momento>? = runCatching {
        val f = arq(ctx, chave); if (!f.exists()) return null
        val o = JSONObject(f.readText()); if (o.getString("sig") != sig) return null
        val m = o.getJSONArray("m")
        List(m.length()) { i ->
            val r = m.getJSONArray(i)
            Momento(r.getLong(0), r.getLong(1), r.getLong(2), r.getDouble(3).toFloat(), Categoria.valueOf(r.getString(4)))
        }
    }.getOrNull()

    fun limparMemoria() = synchronized(mem) { mem.clear() }
}
