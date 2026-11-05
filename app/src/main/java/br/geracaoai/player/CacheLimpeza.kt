package br.geracaoai.player

import android.content.Context
import android.os.Environment
import java.io.File

object CacheLimpeza {
    fun bytes(f: File): Long = when {
        !f.exists() -> 0L
        f.isFile -> f.length()
        else -> f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun tamanhoCache(ctx: Context): Long {
        var t = 0L
        t += bytes(ctx.cacheDir)
        t += bytes(File(ctx.filesDir, "legendas"))
        t += bytes(File(ctx.filesDir, "asr_whisper"))
        t += bytes(File(ctx.filesDir, "melhorar_tmp"))
        // thumbnails do sistema do app
        t += bytes(File(ctx.cacheDir, "image_manager_disk_cache"))
        return t
    }

    fun tamanhoMelhorados(ctx: Context): Long = bytes(dirMelhorados(ctx))

    fun dirMelhorados(ctx: Context): File {
        // App-specific: não precisa WRITE_EXTERNAL_STORAGE (falhava em Android 10+ → Melhorar 0%).
        val base = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(ctx.filesDir, "movies")
        return File(base, "Melhorados").apply { mkdirs() }
    }

    fun listarMelhorados(ctx: Context): List<File> =
        dirMelhorados(ctx).listFiles()?.filter { it.isFile && it.extension.lowercase() in listOf("mp4", "mkv", "webm") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    /** Apaga só cache/temp do app — NUNCA vídeos do usuário, DB de análise, nem Melhorados. */
    fun limpar(ctx: Context): Long {
        var freed = 0L
        fun del(f: File) {
            if (!f.exists()) return
            freed += bytes(f)
            f.deleteRecursively()
        }
        del(ctx.cacheDir)
        del(File(ctx.filesDir, "legendas"))
        // asr_whisper modelos: regeneráveis dos assets — pode apagar para liberar
        del(File(ctx.filesDir, "asr_whisper"))
        del(File(ctx.filesDir, "melhorar_tmp"))
        // NÃO apaga: analises/, galeria_org.db, org_scan_state, Melhorados
        ctx.cacheDir.mkdirs()
        return freed
    }
}
