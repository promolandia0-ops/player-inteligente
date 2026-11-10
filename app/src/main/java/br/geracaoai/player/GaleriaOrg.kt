package br.geracaoai.player

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

enum class ClassifNudez { NUDE, LIMPO, PENDENTE, ERRO }

data class ResultadoOrg(
    val uri: String, val classif: ClassifNudez, val cats: Set<Categoria>, val mtime: Long,
    val areaPeito: Float = 0f, val areaBunda: Float = 0f, val areaBuceta: Float = 0f,
)

/** DB local de álbuns virtuais — não move/copia arquivos. Persistência por vídeo. */
class GaleriaDb(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "galeria_org.db", null, 4) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS org(
              uri TEXT PRIMARY KEY NOT NULL,
              classif TEXT NOT NULL,
              cats TEXT NOT NULL DEFAULT '',
              mtime INTEGER NOT NULL DEFAULT 0,
              scanned_at INTEGER NOT NULL DEFAULT 0,
              emb BLOB,
              area_peito REAL NOT NULL DEFAULT 0,
              area_bunda REAL NOT NULL DEFAULT 0,
              area_buceta REAL NOT NULL DEFAULT 0
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
        if (o < 3) runCatching { db.execSQL("ALTER TABLE org ADD COLUMN emb BLOB") }
        if (o < 4) {
            runCatching { db.execSQL("ALTER TABLE org ADD COLUMN area_peito REAL NOT NULL DEFAULT 0") }
            runCatching { db.execSQL("ALTER TABLE org ADD COLUMN area_bunda REAL NOT NULL DEFAULT 0") }
            runCatching { db.execSQL("ALTER TABLE org ADD COLUMN area_buceta REAL NOT NULL DEFAULT 0") }
        }
    }

    fun salvar(
        uri: String, classif: ClassifNudez, cats: Set<Categoria>, mtime: Long,
        areaPeito: Float = 0f, areaBunda: Float = 0f, areaBuceta: Float = 0f,
    ) {
        writableDatabase.insertWithOnConflict("org", null, ContentValues().apply {
            put("uri", uri); put("classif", classif.name)
            put("cats", cats.joinToString(",") { it.name })
            put("mtime", mtime); put("scanned_at", System.currentTimeMillis())
            put("area_peito", areaPeito); put("area_bunda", areaBunda); put("area_buceta", areaBuceta)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun obter(uri: String): ResultadoOrg? =
        readableDatabase.rawQuery(
            "SELECT uri,classif,cats,mtime,area_peito,area_bunda,area_buceta FROM org WHERE uri=?", arrayOf(uri),
        ).use { c ->
            if (!c.moveToFirst()) return null
            ler(c)
        }

    fun todos(): Map<String, ResultadoOrg> {
        val m = LinkedHashMap<String, ResultadoOrg>()
        readableDatabase.rawQuery(
            "SELECT uri,classif,cats,mtime,area_peito,area_bunda,area_buceta FROM org", null,
        ).use { c ->
            while (c.moveToNext()) { val r = ler(c); m[r.uri] = r }
        }
        return m
    }

    /** Índice por chave estável (external vs external_primary). */
    fun todosPorChaveEstavel(): Map<String, ResultadoOrg> {
        val m = LinkedHashMap<String, ResultadoOrg>()
        for ((u, r) in todos()) {
            val k = VideosMedia.chaveEstavel(u)
            // Prefere NUDE/LIMPO se houver colisão
            val ant = m[k]
            if (ant == null || ant.classif == ClassifNudez.ERRO || ant.classif == ClassifNudez.PENDENTE) m[k] = r
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

    /**
     * Reescreve URIs do DB para chave canônica (`external_primary` → `external`).
     * Não apaga classificações. Retorna quantas linhas mudaram de chave.
     */
    fun normalizarUris(): Int {
        data class Row(
            val old: String, val neu: String, val classif: String, val cats: String, val mtime: Long,
            val ap: Float, val ab: Float, val abu: Float, val emb: ByteArray?, val scanned: Long,
        )
        val db = writableDatabase
        val rows = ArrayList<Row>()
        db.rawQuery(
            "SELECT uri,classif,cats,mtime,area_peito,area_bunda,area_buceta,emb,scanned_at FROM org", null,
        ).use { c ->
            val iEmb = c.getColumnIndex("emb")
            val iSc = c.getColumnIndex("scanned_at")
            while (c.moveToNext()) {
                val u = c.getString(0)
                val k = VideosMedia.chaveEstavel(u)
                if (k == u) continue
                rows += Row(
                    u, k, c.getString(1), c.getString(2), c.getLong(3),
                    c.getFloat(4), c.getFloat(5), c.getFloat(6),
                    if (iEmb >= 0 && !c.isNull(iEmb)) c.getBlob(iEmb) else null,
                    if (iSc >= 0 && !c.isNull(iSc)) c.getLong(iSc) else 0L,
                )
            }
        }
        if (rows.isEmpty()) return 0
        var n = 0
        db.beginTransaction()
        try {
            for (r in rows) {
                val destClass = db.rawQuery("SELECT classif FROM org WHERE uri=?", arrayOf(r.neu)).use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
                val cv = ContentValues().apply {
                    put("uri", r.neu); put("classif", r.classif); put("cats", r.cats); put("mtime", r.mtime)
                    put("area_peito", r.ap); put("area_bunda", r.ab); put("area_buceta", r.abu)
                    put("scanned_at", r.scanned)
                    if (r.emb != null) put("emb", r.emb)
                }
                when {
                    destClass == null -> {
                        // delete+insert (UPDATE de PK é frágil)
                        db.delete("org", "uri=?", arrayOf(r.old))
                        db.insertWithOnConflict("org", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                        n++
                    }
                    else -> {
                        val velhoMelhor = r.classif == "NUDE" ||
                            (r.classif == "LIMPO" && destClass != "NUDE" && destClass != "LIMPO")
                        if (velhoMelhor) {
                            db.update("org", ContentValues().apply {
                                put("classif", r.classif); put("cats", r.cats); put("mtime", r.mtime)
                                put("area_peito", r.ap); put("area_bunda", r.ab); put("area_buceta", r.abu)
                                put("scanned_at", r.scanned)
                                if (r.emb != null) put("emb", r.emb)
                            }, "uri=?", arrayOf(r.neu))
                        }
                        db.delete("org", "uri=?", arrayOf(r.old))
                        n++
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return n
    }

    /** Assinatura barata (sem materializar linhas) p/ a UI saber se algo mudou desde a última leitura. */
    fun assinatura(): String =
        readableDatabase.rawQuery(
            "SELECT COUNT(*), COALESCE(SUM(scanned_at),0), COALESCE(SUM(emb IS NOT NULL),0), COALESCE(SUM(LENGTH(cats)),0), COALESCE(SUM(mtime),0) FROM org", null,
        ).use { c -> if (c.moveToFirst()) (0 until 5).joinToString("|") { c.getLong(it).toString() } else "" }

    fun limpar() { writableDatabase.delete("org", null, null) }
    /** Força reclassificar só os marcados como Putaria (regras mais rígidas na v1.1.2). */
    fun limparNude() { writableDatabase.delete("org", "classif=?", arrayOf("NUDE")) }
    /** Zera métricas de tamanho (força re-medida na nova versão). */
    fun zerarAreas() {
        writableDatabase.execSQL(
            "UPDATE org SET area_peito=0, area_bunda=0, area_buceta=0 WHERE classif=?",
            arrayOf("NUDE"),
        )
    }

    fun salvarAreas(uri: String, areaPeito: Float, areaBunda: Float, areaBuceta: Float) {
        writableDatabase.update(
            "org",
            ContentValues().apply {
                put("area_peito", areaPeito); put("area_bunda", areaBunda); put("area_buceta", areaBuceta)
            },
            "uri=?", arrayOf(uri),
        )
    }

    fun salvarEmb(uri: String, emb: FloatArray) {
        writableDatabase.update("org", ContentValues().apply { put("emb", Semelhanca.pack(emb)) }, "uri=?", arrayOf(uri))
    }

    fun embeddings(): Map<String, FloatArray> {
        val m = HashMap<String, FloatArray>()
        readableDatabase.rawQuery("SELECT uri,emb FROM org WHERE emb IS NOT NULL", null).use { c ->
            while (c.moveToNext()) {
                val v = Semelhanca.unpack(c.getBlob(1)) ?: continue
                m[c.getString(0)] = v
            }
        }
        return m
    }

    fun temEmb(uri: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM org WHERE uri=? AND emb IS NOT NULL", arrayOf(uri)).use { it.moveToFirst() }

    private fun ler(c: android.database.Cursor) = ResultadoOrg(
        c.getString(0),
        runCatching { ClassifNudez.valueOf(c.getString(1)) }.getOrDefault(ClassifNudez.PENDENTE),
        c.getString(2).split(',').filter { it.isNotBlank() }
            .mapNotNull { runCatching { Categoria.valueOf(it) }.getOrNull() }.toSet(),
        c.getLong(3),
        if (c.columnCount > 4) c.getFloat(4) else 0f,
        if (c.columnCount > 5) c.getFloat(5) else 0f,
        if (c.columnCount > 6) c.getFloat(6) else 0f,
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
            // Não mexe em ativo/pausado — quem controla o ciclo de vida é o Service/Worker.
        }
    }
}
