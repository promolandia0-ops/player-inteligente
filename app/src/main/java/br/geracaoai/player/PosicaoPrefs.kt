package br.geracaoai.player

import android.content.Context

/** Continuar de onde parou — chave estável do vídeo. */
object PosicaoPrefs {
    private const val MIN_MS = 5_000L
    private const val FIM_MS = 5_000L

    /** Muda quando o player fecha → miniaturas recalculam a barrinha de "continuar". */
    val versao = androidx.compose.runtime.mutableIntStateOf(0)
    fun marcarAlterado() { versao.intValue++ }

    private fun p(ctx: Context) = ctx.getSharedPreferences("posicao_video", Context.MODE_PRIVATE)

    fun salvar(ctx: Context, chave: String, posMs: Long, durMs: Long) {
        if (chave.isBlank()) return
        if (durMs > 0 && posMs >= durMs - FIM_MS) {
            p(ctx).edit().remove("p:$chave").apply()
            return
        }
        if (posMs < MIN_MS) {
            p(ctx).edit().remove("p:$chave").apply()
            return
        }
        p(ctx).edit().putLong("p:$chave", posMs).apply()
    }

    fun obter(ctx: Context, chave: String, durMs: Long = 0L): Long? {
        if (!p(ctx).contains("p:$chave")) return null
        val pos = p(ctx).getLong("p:$chave", 0L)
        if (pos < MIN_MS) return null
        if (durMs > 0 && pos >= durMs - FIM_MS) return null
        return pos
    }

    fun limpar(ctx: Context, chave: String) {
        p(ctx).edit().remove("p:$chave").apply()
    }

    /** Fração 0..1 para barra no thumbnail; null se não houver posição útil. */
    fun fracao(ctx: Context, chave: String, durMs: Long): Float? {
        if (durMs <= 0L) return null
        val pos = obter(ctx, chave, durMs) ?: return null
        return (pos.toFloat() / durMs).coerceIn(0f, 1f)
    }

    fun deveRetomar(posMs: Long, durMs: Long): Boolean {
        if (posMs < MIN_MS) return false
        if (durMs > 0 && posMs >= durMs - FIM_MS) return false
        return true
    }
}
