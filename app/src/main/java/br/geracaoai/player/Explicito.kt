package br.geracaoai.player

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Ordenação "Mais explícito" (v1.3.23): pontuação por vídeo a partir da análise JÁ salva (sem rodar modelo).
 *
 * Por quadro usa a mesma pontuação dos Melhores momentos (buceta/ânus 3,0 · peito/bunda 1,5, bônus 1,25 se 2+ juntas), com o
 * ânus na escala do limiar por classe. Cada quadro vale o tempo até o próximo (máx. [PASSO_MAX_MS]):
 *   densidade = Σ(pontuação × tempo) / duração            (fração explícita, comparável entre vídeos curtos e longos)
 *   score     = densidade × (1 + 0,25 · ln(1 + segundosExplícitos / 10))   (bônus pelo tempo explícito total)
 * Vídeo sem análise não tem pontuação (vai ao fim, na ordem atual).
 */
object Explicito {
    const val PASSO_MAX_MS = 4_000L
    private const val VERSAO = 1

    fun pontuar(tempos: LongArray, scores: Map<Categoria, FloatArray>, duracaoMs: Long, limiar: Float = 0.45f): Float {
        val n = tempos.size
        if (n == 0) return 0f
        val ord = (0 until n).sortedBy { tempos[it] }
        val dur = if (duracaoMs > 0) duracaoMs else tempos[ord.last()]
        if (dur <= 0L) return 0f
        var soma = 0.0; var segExpl = 0.0
        val m = HashMap<Categoria, Float>(4)
        for (k in 0 until n) {
            val i = ord[k]
            for (c in MelhoresMomentos.PESOS.keys) {
                val v = scores[c]?.getOrNull(i) ?: 0f
                m[c] = if (c == Categoria.ANUS) Limiares.normalizarAnus(v) else v
            }
            val q = MelhoresMomentos.pontuarQuadro(m, limiar)
            if (q <= 0f) continue
            val prox = if (k + 1 < n) tempos[ord[k + 1]] else minOf(dur, tempos[i] + PASSO_MAX_MS)
            val w = (prox - tempos[i]).coerceIn(500L, PASSO_MAX_MS) / 1000.0
            soma += q * w; segExpl += w
        }
        if (soma <= 0.0) return 0f
        val dens = soma / (dur / 1000.0)
        return (dens * (1.0 + 0.25 * kotlin.math.ln(1.0 + segExpl / 10.0))).toFloat()
    }

    fun pontuar(a: Analise, limiar: Float = 0.45f) = pontuar(a.tempos, a.scores, a.duracaoMs, limiar)

    /**
     * Ordena: analisados por score (maior primeiro; empate → mais novo); sem análise no FIM, na ordem atual (mais novos).
     * [pontos] = chave estável → score (ausente = sem análise).
     */
    fun ordenar(videos: List<Video>, pontos: Map<String, Float>): List<Video> {
        fun p(v: Video) = pontos[VideosMedia.chaveEstavel(v.uri.toString())]
        val (com, sem) = videos.partition { p(it) != null }
        return com.sortedWith(compareByDescending<Video> { p(it)!! }.thenByDescending { it.dataMod }) + sem.sortedByDescending { it.dataMod }
    }

    // ---- cache: memória + arquivo (filesDir/explicito.json), invalidado pelo mtime do arquivo da análise ----
    private class Ent(val mtime: Long, val score: Float)
    private val mem = ConcurrentHashMap<String, Ent>()
    @Volatile private var carregado = false
    private val chaves = ConcurrentHashMap<String, String>()

    private fun arqCache(ctx: Context) = File(ctx.filesDir, "explicito.json")

    private fun carregarArquivo(ctx: Context) {
        if (carregado) return
        synchronized(this) {
            if (carregado) return
            runCatching {
                val f = arqCache(ctx)
                if (f.exists()) {
                    val o = JSONObject(f.readText())
                    if (o.optInt("v") == VERSAO) {
                        val d = o.getJSONObject("d")
                        for (k in d.keys()) { val e = d.getJSONArray(k); mem[k] = Ent(e.getLong(0), e.getDouble(1).toFloat()) }
                    }
                }
            }
            carregado = true
        }
    }

    /** Chamar FORA da thread principal. Só vídeos com análise salva entram no mapa (chave estável do vídeo → score). */
    fun carregar(ctx: Context, videos: List<Video>, limiar: Float = 0.45f): Map<String, Float> {
        val dir = File(ctx.filesDir, "analises")
        if (dir.list().isNullOrEmpty()) return emptyMap()
        carregarArquivo(ctx)
        val out = HashMap<String, Float>(); var sujo = false
        for (v in videos) {
            val u = v.uri.toString()
            val ch = chaves.getOrPut(u) { runCatching { Cache.chave(ctx, v.uri) }.getOrDefault("") }
            if (ch.isEmpty()) continue
            val mt = File(dir, "$ch.json").lastModified(); if (mt == 0L) continue
            val e = mem[ch]?.takeIf { it.mtime == mt } ?: run {
                val a = Cache.carregar(ctx, ch)?.first ?: return@run null
                sujo = true; Ent(mt, pontuar(a, limiar)).also { mem[ch] = it }
            } ?: continue
            out[VideosMedia.chaveEstavel(u)] = e.score
        }
        if (sujo) runCatching {
            val d = JSONObject(); for ((k, e) in mem) d.put(k, org.json.JSONArray().put(e.mtime).put(e.score.toDouble()))
            val tmp = File(ctx.filesDir, "explicito.json.tmp"); tmp.writeText(JSONObject().put("v", VERSAO).put("d", d).toString())
            tmp.renameTo(arqCache(ctx))
        }
        return out
    }
}
