package br.geracaoai.player

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

object Cache {
    fun chave(ctx: Context, uri: Uri): String {
        var size = -1L; var mtime = -1L
        runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
                    (c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED).takeIf { it >= 0 }
                        ?: c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED).takeIf { it >= 0 })
                        ?.let { mtime = c.getLong(it) }
                }
            }
        }
        val s = "$uri|$size|$mtime"
        return MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun arq(ctx: Context, chave: String) = File(File(ctx.filesDir, "analises").apply { mkdirs() }, "$chave.json")

    fun salvar(ctx: Context, chave: String, a: Analise, intervaloMs: Long) {
        val o = JSONObject().put("v", 1).put("duracao", a.duracaoMs).put("intervalo", intervaloMs).put("parcial", a.parcial)
            .put("t", JSONArray().apply { a.tempos.forEach { put(it) } })
        Categoria.entries.forEach { c -> o.put(c.name, JSONArray().apply { a.scores[c]!!.forEach { put((it * 1000).toInt()) } }) }
        val tmp = File(arq(ctx, chave).path + ".tmp"); tmp.writeText(o.toString()); tmp.renameTo(arq(ctx, chave))
    }

    fun carregar(ctx: Context, chave: String): Pair<Analise, Long>? = runCatching {
        val f = arq(ctx, chave); if (!f.exists()) return null
        val o = JSONObject(f.readText())
        val t = o.getJSONArray("t"); val tempos = LongArray(t.length()) { t.getLong(it) }
        val sc = Categoria.entries.associateWith { c -> val a = o.getJSONArray(c.name); FloatArray(a.length()) { a.getInt(it) / 1000f } }
        Analise(o.getLong("duracao"), tempos, sc, o.optBoolean("parcial", false)) to o.getLong("intervalo")
    }.getOrNull()

    fun limpar(ctx: Context) { File(ctx.filesDir, "analises").deleteRecursively() }
}

class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    var limiar: Float get() = p.getFloat("limiar", 0.45f); set(v) = p.edit().putFloat("limiar", v).apply()
    var offsetS: Int get() = p.getInt("offset", 3); set(v) = p.edit().putInt("offset", v).apply()
    var fps: Int get() = p.getInt("fps", 2); set(v) = p.edit().putInt("fps", v).apply()
    var acelerador: Boolean get() = p.getBoolean("nnapi", true); set(v) = p.edit().putBoolean("nnapi", v).apply()
    var cats: Set<Categoria>
        get() = p.getStringSet("cats", Categoria.entries.map { it.name }.toSet())!!.mapNotNull { runCatching { Categoria.valueOf(it) }.getOrNull() }.toSet()
        set(v) = p.edit().putStringSet("cats", v.map { it.name }.toSet()).apply()
}
