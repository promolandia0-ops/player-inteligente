package br.geracaoai.player

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri

enum class ClassifNudez { NUDE, LIMPO, PENDENTE, ERRO }

data class ResultadoOrg(val uri: String, val classif: ClassifNudez, val cats: Set<Categoria>, val mtime: Long)

/** DB local de álbuns virtuais — não move/copia arquivos. Persistência por vídeo. */
class GaleriaDb(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "galeria_org.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS org(
              uri TEXT PRIMARY KEY NOT NULL,
              classif TEXT NOT NULL,
              cats TEXT NOT NULL DEFAULT '',
              mtime INTEGER NOT NULL DEFAULT 0,
              scanned_at INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_org_classif ON org(classif)")
    }
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
    }
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        if (o < 2) db.execSQL("CREATE INDEX IF NOT EXISTS idx_org_classif ON org(classif)")
    }

    fun salvar(uri: String, classif: ClassifNudez, cats: Set<Categoria>, mtime: Long) {
        writableDatabase.insertWithOnConflict("org", null, ContentValues().apply {
            put("uri", uri); put("classif", classif.name)
            put("cats", cats.joinToString(",") { it.name })
            put("mtime", mtime); put("scanned_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun obter(uri: String): ResultadoOrg? =
        readableDatabase.rawQuery("SELECT uri,classif,cats,mtime FROM org WHERE uri=?", arrayOf(uri)).use { c ->
            if (!c.moveToFirst()) return null
            ler(c)
        }

    fun todos(): Map<String, ResultadoOrg> {
        val m = LinkedHashMap<String, ResultadoOrg>()
        readableDatabase.rawQuery("SELECT uri,classif,cats,mtime FROM org", null).use { c ->
            while (c.moveToNext()) { val r = ler(c); m[r.uri] = r }
        }
        return m
    }

    /** Contagens leves — evita materializar milhares de ResultadoOrg na UI. */
    fun contagens(): Map<ClassifNudez, Int> {
        val out = mutableMapOf<ClassifNudez, Int>()
        readableDatabase.rawQuery("SELECT classif, COUNT(*) FROM org GROUP BY classif", null).use { c ->
            while (c.moveToNext()) {
                val k = runCatching { ClassifNudez.valueOf(c.getString(0)) }.getOrNull() ?: continue
                out[k] = c.getInt(1)
            }
        }
        return out
    }

    fun contagemFeitos(): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM org WHERE classif IN ('NUDE','LIMPO','ERRO')", null
        ).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    fun urisFeitos(): Set<String> {
        val s = HashSet<String>()
        readableDatabase.rawQuery(
            "SELECT uri,classif,mtime FROM org WHERE classif IN ('NUDE','LIMPO','ERRO')", null
        ).use { c ->
            while (c.moveToNext()) s.add(c.getString(0))
        }
        return s
    }

    /** Capa (uri) + contagem para um classif, sem carregar a lista inteira. */
    fun amostra(classif: ClassifNudez, limite: Int = 1): List<String> {
        val out = ArrayList<String>(limite)
        readableDatabase.rawQuery(
            "SELECT uri FROM org WHERE classif=? ORDER BY scanned_at DESC LIMIT ?",
            arrayOf(classif.name, limite.toString()),
        ).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
        return out
    }

    fun limpar() { writableDatabase.delete("org", null, null) }

    private fun ler(c: android.database.Cursor) = ResultadoOrg(
        c.getString(0),
        runCatching { ClassifNudez.valueOf(c.getString(1)) }.getOrDefault(ClassifNudez.PENDENTE),
        c.getString(2).split(',').filter { it.isNotBlank() }
            .mapNotNull { runCatching { Categoria.valueOf(it) }.getOrNull() }.toSet(),
        c.getLong(3),
    )
}

/**
 * Estado da varredura em arquivo (visível entre processo UI e `:scan`).
 * Formato: key=value por linha. Escrita atômica via tmp+rename.
 */
class OrgPrefs(ctx: Context) {
    private val dir = ctx.applicationContext.filesDir
    private val file = java.io.File(dir, "org_scan_state.properties")

    @Synchronized private fun load(): java.util.Properties {
        val p = java.util.Properties()
        runCatching { if (file.exists()) file.inputStream().use { p.load(it) } }
        return p
    }
    @Synchronized private fun save(mutate: java.util.Properties.() -> Unit) {
        val p = load(); p.mutate()
        val tmp = java.io.File(dir, "org_scan_state.properties.tmp")
        tmp.outputStream().use { p.store(it, "org-scan") }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
    private fun get(k: String, d: String = "") = load().getProperty(k, d) ?: d

    var pausado: Boolean
        get() = get("pausado") == "1"
        set(v) { save { setProperty("pausado", if (v) "1" else "0") } }
    var ativo: Boolean
        get() = get("ativo") == "1"
        set(v) { save { setProperty("ativo", if (v) "1" else "0") } }
    var bucketId: String?
        get() = get("bucketId").takeIf { it.isNotBlank() }
        set(v) { save { setProperty("bucketId", v ?: "") } }
    var feitos: Int
        get() = get("feitos", "0").toIntOrNull() ?: 0
        set(v) { save { setProperty("feitos", v.toString()) } }
    var total: Int
        get() = get("total", "0").toIntOrNull() ?: 0
        set(v) { save { setProperty("total", v.toString()) } }
    var txt: String
        get() = get("txt")
        set(v) { save { setProperty("txt", v) } }

    /** Atualiza feitos/total/txt numa única escrita (menos corrida entre processos). */
    fun progresso(feitos: Int, total: Int, txt: String) {
        save {
            setProperty("feitos", feitos.toString())
            setProperty("total", total.toString())
            setProperty("txt", txt)
            setProperty("ativo", "1")
        }
    }
}
