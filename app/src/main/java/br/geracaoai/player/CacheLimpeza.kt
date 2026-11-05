package br.geracaoai.player

import android.content.Context
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
        // restos de features removidas (v1.3.5+)
        t += bytes(File(ctx.filesDir, "legendas"))
        t += bytes(File(ctx.filesDir, "asr_whisper"))
        t += bytes(File(ctx.filesDir, "melhorar_tmp"))
        // (image_manager_disk_cache fica dentro de cacheDir — já contado acima)
        return t
    }

    /** Apaga só cache/temp do app — NUNCA vídeos do usuário nem DB de análise. */
    fun limpar(ctx: Context): Long {
        var freed = 0L
        fun del(f: File) {
            if (!f.exists()) return
            freed += bytes(f)
            f.deleteRecursively()
        }
        del(ctx.cacheDir)
        del(File(ctx.filesDir, "legendas"))
        del(File(ctx.filesDir, "asr_whisper"))
        del(File(ctx.filesDir, "melhorar_tmp"))
        // NÃO apaga: analises/, galeria_org.db, org_scan_state
        ctx.cacheDir.mkdirs()
        return freed
    }
}
