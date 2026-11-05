package br.geracaoai.player

import android.content.Context

enum class Ordenacao(val rotulo: String) {
    MAIS_NOVOS("Mais novos"),
    MAIS_ANTIGOS("Mais antigos"),
    MAIS_LONGOS("Mais longos"),
    MAIS_CURTOS("Mais curtos"),
    MAIS_PESADOS("Mais pesados"),
    MAIS_LEVES("Mais leves"),
    MAIOR_RES("Maior resolução"),
    MENOR_RES("Menor resolução"),
    NOME("Nome A-Z"),
    SEMELHANCA("Semelhança"),
}

object OrdemPrefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("ordem_pasta", Context.MODE_PRIVATE)
    fun get(ctx: Context, pastaId: String, padrao: Ordenacao = Ordenacao.MAIS_NOVOS): Ordenacao {
        val s = p(ctx).getString("o:$pastaId", null) ?: return padrao
        return runCatching { Ordenacao.valueOf(s) }.getOrDefault(padrao)
    }
    fun set(ctx: Context, pastaId: String, o: Ordenacao) {
        p(ctx).edit().putString("o:$pastaId", o.name).apply()
    }
}

object AnaliseProgresso {
    private fun p(ctx: Context) = ctx.getSharedPreferences("analise_prog", Context.MODE_PRIVATE)
    fun set(ctx: Context, uri: String, frac: Float) {
        p(ctx).edit().putFloat("p:$uri", frac).putLong("t:$uri", System.currentTimeMillis()).apply()
    }
    fun clear(ctx: Context, uri: String) {
        p(ctx).edit().remove("p:$uri").remove("t:$uri").apply()
    }
    fun get(ctx: Context, uri: String): Float? {
        val sp = p(ctx)
        if (!sp.contains("p:$uri")) return null
        val t = sp.getLong("t:$uri", 0L)
        if (System.currentTimeMillis() - t > 6 * 3600_000L) return null
        return sp.getFloat("p:$uri", 0f)
    }
    fun todosAtivos(ctx: Context): Map<String, Float> {
        val sp = p(ctx)
        val out = HashMap<String, Float>()
        for (k in sp.all.keys) {
            if (!k.startsWith("p:")) continue
            val uri = k.removePrefix("p:")
            get(ctx, uri)?.let { out[uri] = it }
        }
        return out
    }
}


object TamanhoProgresso {
    private fun p(ctx: Context) = ctx.getSharedPreferences("tamanho_prog", Context.MODE_PRIVATE)
    fun set(ctx: Context, alvoId: String, frac: Float) {
        p(ctx).edit().putFloat("p:$alvoId", frac).putLong("t:$alvoId", System.currentTimeMillis()).apply()
    }
    fun clear(ctx: Context, alvoId: String) {
        p(ctx).edit().remove("p:$alvoId").remove("t:$alvoId").apply()
    }
    fun get(ctx: Context, alvoId: String): Float? {
        val sp = p(ctx)
        if (!sp.contains("p:$alvoId")) return null
        val t = sp.getLong("t:$alvoId", 0L)
        if (System.currentTimeMillis() - t > 6 * 3600_000L) return null
        return sp.getFloat("p:$alvoId", 0f)
    }
    fun todosAtivos(ctx: Context): Map<String, Float> {
        val sp = p(ctx)
        val out = HashMap<String, Float>()
        for (k in sp.all.keys) {
            if (!k.startsWith("p:")) continue
            val id = k.removePrefix("p:")
            get(ctx, id)?.let { out[id] = it }
        }
        return out
    }
}
