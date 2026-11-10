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
        Categoria.entries.forEach { c ->
            val arr = a.scores[c] ?: FloatArray(a.tempos.size)
            o.put(c.name, JSONArray().apply { arr.forEach { put((it * 1000).toInt()) } })
        }
        val tmp = File(arq(ctx, chave).path + ".tmp"); tmp.writeText(o.toString()); tmp.renameTo(arq(ctx, chave))
    }

    fun carregar(ctx: Context, chave: String): Pair<Analise, Long>? = runCatching {
        val f = arq(ctx, chave); if (!f.exists()) return null
        val o = JSONObject(f.readText())
        val t = o.getJSONArray("t"); val tempos = LongArray(t.length()) { t.getLong(it) }
        val sc = Categoria.entries.associateWith { c ->
            if (!o.has(c.name)) FloatArray(0)
            else { val a = o.getJSONArray(c.name); FloatArray(a.length()) { a.getInt(it) / 1000f } }
        }.filterValues { it.isNotEmpty() }.let { m ->
            // chave JSON é "t" (não "tempos") — bug v1.3.0 fazia carregar() sempre falhar → Analisar preso/sem resultado
            val n = tempos.size
            val base = Categoria.entries.associateWith { c -> m[c]?.let { if (it.size == n) it else FloatArray(n) } ?: FloatArray(n) }
            // "Bunda aberta" não é gravada pelo detector: deriva de buceta + cu salvos (vale também p/ análises antigas).
            base + (Categoria.BUNDA_ABERTA to Derivadas.bundaAberta(tempos, base[Categoria.BUCETA]!!, base[Categoria.ANUS]!!, base[Categoria.BUNDA]!!))
        }
        Analise(o.getLong("duracao"), tempos, sc, o.optBoolean("parcial", false)) to o.getLong("intervalo")
    }.getOrNull()

    fun limpar(ctx: Context) { File(ctx.filesDir, "analises").deleteRecursively() }
}

class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    var limiar: Float get() = p.getFloat("limiar", 0.45f); set(v) = p.edit().putFloat("limiar", v).apply()
    var offsetS: Int get() = p.getInt("offset", 3); set(v) = p.edit().putInt("offset", v).apply()
    var fps: Int get() = p.getInt("fps", 2); set(v) = p.edit().putInt("fps", v).apply()
    var acelerador: Boolean get() = p.getBoolean("nnapi2", false); set(v) = p.edit().putBoolean("nnapi2", v).apply()
    /** Continua o áudio/vídeo com a tela desligada (wake lock local). */
    var telaDesligada: Boolean get() = p.getBoolean("tela_off", false); set(v) = p.edit().putBoolean("tela_off", v).apply()
    /** Zoom automático no player (off por padrão). */
    var zoomAuto: Boolean get() = p.getBoolean("zoom_auto", false); set(v) = p.edit().putBoolean("zoom_auto", v).apply()
    /** Modo do Zoom automático (índice de [ModoZoom]). Migra o antigo liga/desliga: ligado → Corpo inteiro. */
    var zoomModo: Int
        get() = if (p.contains("zoom_modo")) p.getInt("zoom_modo", 0) else if (p.getBoolean("zoom_auto", false)) ModoZoom.CORPO.ordinal else 0
        set(v) = p.edit().putInt("zoom_modo", v).putBoolean("zoom_auto", v != 0).apply()
    /** Câmera lenta suave (interpolação de quadros na GPU) abaixo de 1×. Ligada por padrão; cai sozinha p/ o normal se a GPU não der conta. */
    var lentaSuave: Boolean get() = p.getBoolean("lenta_suave", true); set(v) = p.edit().putBoolean("lenta_suave", v).apply()
    var cats: Set<Categoria>
        get() = p.getStringSet("cats", Categoria.entries.map { it.name }.toSet())!!.mapNotNull { runCatching { Categoria.valueOf(it) }.getOrNull() }.toSet()
        set(v) = p.edit().putStringSet("cats", v.map { it.name }.toSet()).apply()
}
