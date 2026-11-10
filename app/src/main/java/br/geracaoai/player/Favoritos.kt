package br.geracaoai.player

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Favoritos (v1.3.23) — armazenamento PRÓPRIO (SharedPreferences "favoritos"), independente de histórico/DB/análise.
 * Chave = chave estável do vídeo ([VideosMedia.chaveEstavel]); cada favorito guarda quando foi favoritado.
 *
 * Camadas:
 *  - [Loja]: puro e testável (ler/gravar/alternar/listar), sem Compose. Chame fora da UI.
 *  - [estado]: mapa observável (chave → quando) que a UI lê (estrela no tile/player) — carregado fora da UI, atualizado na hora ao
 *    alternar; a gravação em disco roda numa thread própria (nunca na principal).
 */
object Favoritos {
    private const val PREFS = "favoritos"
    private const val CHAVE = "lista"
    const val PASTA_ID = "__favoritos"

    object Loja {
        @Synchronized fun ler(ctx: Context): Map<String, Long> = runCatching {
            val a = JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CHAVE, "[]"))
            val m = LinkedHashMap<String, Long>()
            for (i in 0 until a.length()) { val o = a.getJSONObject(i); val k = o.optString("k"); if (k.isNotBlank()) m[k] = o.optLong("t", 0L) }
            m
        }.getOrDefault(emptyMap())

        @Synchronized fun gravar(ctx: Context, m: Map<String, Long>) {
            val a = JSONArray(); m.forEach { (k, t) -> a.put(JSONObject().put("k", k).put("t", t)) }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(CHAVE, a.toString()).apply()
        }

        /** Alterna e devolve o novo estado (true = agora é favorito). */
        @Synchronized fun alternar(ctx: Context, uri: Uri, agora: Long = System.currentTimeMillis()): Boolean {
            val k = VideosMedia.chaveEstavel(uri.toString())
            val m = LinkedHashMap(ler(ctx))
            val fav = if (m.containsKey(k)) { m.remove(k); false } else { m[k] = agora; true }
            gravar(ctx, m); return fav
        }

        /** Chaves mais recentemente favoritadas primeiro. */
        fun chavesRecentes(m: Map<String, Long>): List<String> = m.entries.sortedByDescending { it.value }.map { it.key }

        fun remover(ctx: Context, chaves: Collection<String>) {
            if (chaves.isEmpty()) return
            gravar(ctx, ler(ctx).filterKeys { it !in chaves })
        }
    }

    /** Favoritos observáveis pela UI (chave estável → quando). Vazio até [carregar] rodar. */
    var estado by mutableStateOf<Map<String, Long>>(emptyMap())
        private set

    private val gravador = Executors.newSingleThreadExecutor { r -> Thread(r, "favoritos").also { it.priority = Thread.MIN_PRIORITY } }

    fun ehFavorito(uri: Uri): Boolean = estado.containsKey(VideosMedia.chaveEstavel(uri.toString()))

    /** Lê do disco (FORA da thread principal) e publica no estado. */
    fun carregar(ctx: Context) { estado = Loja.ler(ctx) }

    /** Alterna na hora (estado observável) e grava em segundo plano. Seguro de chamar da UI. */
    fun alternar(ctx: Context, uri: Uri): Boolean {
        val app = ctx.applicationContext
        val k = VideosMedia.chaveEstavel(uri.toString())
        val m = LinkedHashMap(estado)
        val fav = if (m.containsKey(k)) { m.remove(k); false } else { m[k] = System.currentTimeMillis(); true }
        estado = m
        runCatching { gravador.execute { runCatching { Loja.gravar(app, m) } } }
        return fav
    }

    /**
     * Vídeos favoritos que ainda existem, mais recente primeiro. Usa o da galeria ([videos]) quando está lá; senão confere
     * se o arquivo abre ([existe]). Quem não existe mais é ignorado (e removido do armazenamento). Fora da thread principal.
     */
    fun resolver(ctx: Context, videos: List<Video>?, m: Map<String, Long> = estado, existe: (Uri) -> Boolean = { HistoricoVistos.arquivoExiste(ctx, it) }): List<Video> {
        val porChave = videos?.associateBy { VideosMedia.chaveEstavel(it.uri.toString()) } ?: emptyMap()
        val mortos = ArrayList<String>()
        val out = Loja.chavesRecentes(m).mapNotNull { k ->
            porChave[k] ?: Uri.parse(k).takeIf(existe)?.let { Video(it, it.lastPathSegment ?: "Vídeo", 0L, 0L) } ?: run { mortos += k; null }
        }
        // Só poda quando a lista da galeria é confiável (carregada); evita apagar favoritos por falta de permissão momentânea.
        if (videos != null && videos.isNotEmpty() && mortos.isNotEmpty()) runCatching { Loja.remover(ctx, mortos); estado = estado.filterKeys { it !in mortos } }
        return out
    }
}
