package br.geracaoai.player

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Vistos recentemente" (v1.3.18): últimos vídeos abertos no player, mais recente primeiro, sem repetição,
 * no máximo [MAX]. Persistido em SharedPreferences ("historico"). Leitura/escrita são baratas, mas chame fora da UI.
 */
object HistoricoVistos {
    const val MAX = 20
    private const val PREFS = "historico"
    private const val CHAVE = "vistos"

    class Entrada(val uri: String, val nome: String, val duracao: Long, val quando: Long)

    @Synchronized
    fun registrar(ctx: Context, uri: Uri, nome: String, duracao: Long, agora: Long = System.currentTimeMillis()) {
        val u = uri.toString()
        val lista = ler(ctx).toMutableList()
        val antiga = lista.firstOrNull { it.uri == u }
        lista.removeAll { it.uri == u }
        // Mantém nome/duração conhecidos se a nova abertura não os trouxe (ex.: aberto por intent).
        lista.add(0, Entrada(u, nome.ifBlank { antiga?.nome ?: "Vídeo" }, if (duracao > 0) duracao else antiga?.duracao ?: 0L, agora))
        gravar(ctx, lista.take(MAX))
    }

    @Synchronized
    fun listar(ctx: Context): List<Entrada> = ler(ctx)

    @Synchronized
    fun limpar(ctx: Context) { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(CHAVE).apply() }

    private fun ler(ctx: Context): List<Entrada> = runCatching {
        val a = JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CHAVE, "[]"))
        (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i); val u = o.optString("u")
            if (u.isBlank()) null else Entrada(u, o.optString("n", "Vídeo"), o.optLong("d", 0L), o.optLong("t", 0L))
        }.sortedByDescending { it.quando }
    }.getOrDefault(emptyList())

    private fun gravar(ctx: Context, l: List<Entrada>) {
        val a = JSONArray(); l.forEach { a.put(JSONObject().put("u", it.uri).put("n", it.nome).put("d", it.duracao).put("t", it.quando)) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(CHAVE, a.toString()).apply()
    }

    /**
     * Vídeos do histórico que ainda existem (mais recente primeiro). Usa o da galeria ([videos]) quando está lá
     * (traz duração/tamanho); senão confere se o arquivo abre. Chamar fora da thread principal.
     */
    fun resolver(ctx: Context, videos: List<Video>?, existe: (Uri) -> Boolean = { arquivoExiste(ctx, it) }): List<Video> {
        val porUri = videos?.associateBy { it.uri.toString() } ?: emptyMap()
        return listar(ctx).mapNotNull { e ->
            porUri[e.uri] ?: Uri.parse(e.uri).takeIf(existe)?.let { Video(it, e.nome, e.duracao, 0L) }
        }
    }

    fun arquivoExiste(ctx: Context, uri: Uri): Boolean = runCatching {
        ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)
}
