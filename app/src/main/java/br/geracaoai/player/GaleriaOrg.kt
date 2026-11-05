package br.geracaoai.player

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri

enum class ClassifNudez { NUDE, LIMPO, PENDENTE }

data class ResultadoOrg(val uri: String, val classif: ClassifNudez, val cats: Set<Categoria>, val mtime: Long)

/** DB local de álbuns virtuais — não move/copia arquivos. */
class GaleriaDb(ctx: Context) : SQLiteOpenHelper(ctx, "galeria_org.db", null, 1) {
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
    }
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

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
            ResultadoOrg(
                c.getString(0),
                runCatching { ClassifNudez.valueOf(c.getString(1)) }.getOrDefault(ClassifNudez.PENDENTE),
                c.getString(2).split(',').filter { it.isNotBlank() }.mapNotNull { runCatching { Categoria.valueOf(it) }.getOrNull() }.toSet(),
                c.getLong(3),
            )
        }

    fun todos(): Map<String, ResultadoOrg> {
        val m = LinkedHashMap<String, ResultadoOrg>()
        readableDatabase.rawQuery("SELECT uri,classif,cats,mtime FROM org", null).use { c ->
            while (c.moveToNext()) {
                val r = ResultadoOrg(
                    c.getString(0),
                    runCatching { ClassifNudez.valueOf(c.getString(1)) }.getOrDefault(ClassifNudez.PENDENTE),
                    c.getString(2).split(',').filter { it.isNotBlank() }.mapNotNull { runCatching { Categoria.valueOf(it) }.getOrNull() }.toSet(),
                    c.getLong(3),
                )
                m[r.uri] = r
            }
        }
        return m
    }

    fun limpar() { writableDatabase.delete("org", null, null) }
}
