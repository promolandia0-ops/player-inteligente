package br.geracaoai.player

import android.content.Context
import android.util.Log

/**
 * Recuperação pós-upgrade (v1.3.0 apagava NUDE via limparNude; chaves URI podiam divergir).
 *
 * - Normaliza URIs do DB para chave estável.
 * - Nunca apaga classificações.
 * - Conta o que ainda dá para casar com a galeria atual.
 *
 * Honestidade: linhas NUDE já deletadas pela v1.3.0 NÃO voltam.
 * LIMPO/ERRO e qualquer NUDE que ainda esteja no DB são reassociados pela chave estável.
 */
object OrgRecuperacao {
    private const val TAG = "OrgRecuperacao"
    private const val SP = "org_versao"
    private const val FLAG = "recup_v"

    data class Relatorio(
        val urisRenomeadas: Int,
        val nudeNoDb: Int,
        val limpoNoDb: Int,
        val erroNoDb: Int,
        val casadosComGaleria: Int,
        val totalVideos: Int,
        /** true se o bug da v1.3.0 provavelmente já rodou (classif_v>=2) e Putaria sumiu. */
        val putariaProvavelmenteApagada: Boolean,
    )

    fun precisaRodar(ctx: Context): Boolean {
        val sp = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
        return sp.getInt(FLAG, 0) < 1
    }

    fun rodarSePreciso(ctx: Context, videos: List<Video>? = null): Relatorio {
        val db = GaleriaDb(ctx)
        val ren = runCatching { db.normalizarUris() }.getOrDefault(0)
        val cont = db.contagens()
        val nude = cont[ClassifNudez.NUDE] ?: 0
        val limpo = cont[ClassifNudez.LIMPO] ?: 0
        val erro = cont[ClassifNudez.ERRO] ?: 0
        val mapa = db.todosPorChaveEstavel()
        val lista = videos ?: runCatching { VideosMedia.listar(ctx) }.getOrDefault(emptyList())
        val casados = lista.count { v ->
            val r = mapa[VideosMedia.chaveEstavel(v.uri.toString())]
            r != null && (r.classif == ClassifNudez.NUDE || r.classif == ClassifNudez.LIMPO || r.classif == ClassifNudez.ERRO)
        }
        val sp = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
        val classifV = sp.getInt("classif_v", 1)
        // v1.3.0 setou classif_v=2 após limparNude. Se Putaria=0 e há muitos limpos/pendentes, sinaliza.
        val putariaApagada = classifV >= 2 && nude == 0 && (limpo + erro + lista.size) > 0
        sp.edit().putInt(FLAG, 1).putInt("classif_v", maxOf(classifV, 4)).apply()
        val rel = Relatorio(ren, nude, limpo, erro, casados, lista.size, putariaApagada)
        Log.i(TAG, "recup=$rel")
        return rel
    }

    /** Já classificado? Só pela chave — ignora mtime (mtime diferente não zera progresso). */
    fun estaFeito(feitosPorChave: Map<String, ClassifNudez>, uri: String): Boolean {
        val c = feitosPorChave[VideosMedia.chaveEstavel(uri)] ?: return false
        return c == ClassifNudez.NUDE || c == ClassifNudez.LIMPO || c == ClassifNudez.ERRO
    }

    fun mapaFeitos(db: GaleriaDb): MutableMap<String, ClassifNudez> {
        val out = HashMap<String, ClassifNudez>()
        for ((u, r) in db.todos()) {
            if (r.classif == ClassifNudez.NUDE || r.classif == ClassifNudez.LIMPO || r.classif == ClassifNudez.ERRO) {
                val k = VideosMedia.chaveEstavel(u)
                val ant = out[k]
                if (ant == null || ant == ClassifNudez.ERRO || (ant == ClassifNudez.LIMPO && r.classif == ClassifNudez.NUDE)) {
                    out[k] = r.classif
                }
            }
        }
        return out
    }
}
