package br.geracaoai.player

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Armazenamento PRÓPRIO do módulo Pessoas (pessoas.db + filesDir/pessoas (miniaturas jpg)). Não toca o banco da galeria. */
class PessoasDb(private val ctx: Context) : SQLiteOpenHelper(ctx, "pessoas.db", null, 1) {
    class Pessoa(val id: Long, val nome: String, val videos: Int, val miniatura: File?)
    class VideoRef(val chave: String, val uri: String)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE videos(chave TEXT PRIMARY KEY, uri TEXT, ts INTEGER, n INTEGER)")
        db.execSQL("CREATE TABLE pessoas(id INTEGER PRIMARY KEY AUTOINCREMENT, nome TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE TABLE trilhas(id INTEGER PRIMARY KEY AUTOINCREMENT, video TEXT, emb BLOB, n INTEGER, q REAL, pessoa INTEGER, thumb TEXT)")
        db.execSQL("CREATE INDEX trilhas_pessoa ON trilhas(pessoa)")
        db.execSQL("CREATE INDEX trilhas_video ON trilhas(video)")
    }
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

    val pasta: File get() = File(ctx.filesDir, "pessoas").apply { mkdirs() }

    private fun blob(v: FloatArray): ByteArray { val b = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN); v.forEach { b.putFloat(it) }; return b.array() }
    private fun deBlob(b: ByteArray): FloatArray { val f = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer(); return FloatArray(f.remaining()).also { f.get(it) } }

    fun videosFeitos(): Set<String> = readableDatabase.rawQuery("SELECT chave FROM videos", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
    fun totalFeitos(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM videos", null).use { it.moveToFirst(); it.getInt(0) }

    /** Soma dos embeddings por pessoa (base dos centroides). */
    fun somasPorPessoa(): MutableMap<Long, FloatArray> {
        val m = HashMap<Long, FloatArray>()
        readableDatabase.rawQuery("SELECT pessoa, emb FROM trilhas", null).use { c ->
            while (c.moveToNext()) {
                val e = deBlob(c.getBlob(1)); val s = m.getOrPut(c.getLong(0)) { FloatArray(e.size) }
                for (i in e.indices) s[i] += e[i]
            }
        }
        return m
    }

    fun novaPessoa(): Long = writableDatabase.insert("pessoas", null, ContentValues().apply { put("nome", "") })

    /** Grava o vídeo e suas trilhas (cada uma com a pessoa já decidida) numa transação — retomável: ou grava tudo ou nada. */
    fun salvarVideo(chave: String, uri: String, n: Int, trilhas: List<Triple<Agrupamento.Trilha, Long, String?>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((t, pessoa, thumb) in trilhas) db.insert("trilhas", null, ContentValues().apply {
                put("video", chave); put("emb", blob(t.emb)); put("n", t.obs.size); put("q", t.qualidade.toDouble()); put("pessoa", pessoa); put("thumb", thumb)
            })
            db.insertWithOnConflict("videos", null, ContentValues().apply { put("chave", chave); put("uri", uri); put("ts", System.currentTimeMillis()); put("n", n) }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Pessoas com pelo menos [minVideos] vídeos, mais vídeos primeiro. */
    fun listar(minVideos: Int = Agrupamento.MIN_VIDEOS): List<Pessoa> {
        val out = ArrayList<Pessoa>()
        readableDatabase.rawQuery(
            "SELECT p.id, p.nome, COUNT(DISTINCT t.video) AS nv, " +
                "(SELECT thumb FROM trilhas WHERE pessoa = p.id AND thumb IS NOT NULL ORDER BY q DESC LIMIT 1) " +
                "FROM pessoas p JOIN trilhas t ON t.pessoa = p.id GROUP BY p.id HAVING nv >= ${minVideos.coerceAtLeast(0)} ORDER BY nv DESC, p.id", null,
        ).use { c -> while (c.moveToNext()) out += Pessoa(c.getLong(0), c.getString(1) ?: "", c.getInt(2), c.getString(3)?.let { File(pasta, it) }?.takeIf { it.exists() }) }
        return out
    }

    fun videosDe(pessoa: Long): List<VideoRef> {
        val out = ArrayList<VideoRef>()
        readableDatabase.rawQuery("SELECT DISTINCT v.chave, v.uri FROM trilhas t JOIN videos v ON v.chave = t.video WHERE t.pessoa = ?", arrayOf(pessoa.toString()))
            .use { c -> while (c.moveToNext()) out += VideoRef(c.getString(0), c.getString(1)) }
        return out
    }

    fun renomear(id: Long, nome: String) { writableDatabase.update("pessoas", ContentValues().apply { put("nome", nome.trim()) }, "id=?", arrayOf(id.toString())) }

    /** Junta [origem] em [destino] (as trilhas passam a ser do destino). Mantém o nome do destino, ou o da origem se faltar. */
    fun juntar(origem: Long, destino: Long) {
        if (origem == destino) return
        val db = writableDatabase; db.beginTransaction()
        try {
            val nomeO = db.rawQuery("SELECT nome FROM pessoas WHERE id=?", arrayOf(origem.toString())).use { if (it.moveToFirst()) it.getString(0) ?: "" else "" }
            val nomeD = db.rawQuery("SELECT nome FROM pessoas WHERE id=?", arrayOf(destino.toString())).use { if (it.moveToFirst()) it.getString(0) ?: "" else "" }
            db.execSQL("UPDATE trilhas SET pessoa=? WHERE pessoa=?", arrayOf(destino, origem))
            db.delete("pessoas", "id=?", arrayOf(origem.toString()))
            if (nomeD.isBlank() && nomeO.isNotBlank()) renomear(destino, nomeO)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Apaga tudo (re-escanear do zero). */
    fun limparTudo() {
        val db = writableDatabase; db.beginTransaction()
        try { db.delete("trilhas", null, null); db.delete("pessoas", null, null); db.delete("videos", null, null); db.setTransactionSuccessful() } finally { db.endTransaction() }
        pasta.listFiles()?.forEach { it.delete() }
    }
}
