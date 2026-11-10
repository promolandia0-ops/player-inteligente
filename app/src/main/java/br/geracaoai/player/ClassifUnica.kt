package br.geracaoai.player

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * v1.3.23 — FONTE ÚNICA da classificação de nudez da galeria (Putaria / Sem nudez / Ainda não verificado).
 *
 * Bug anterior: "Sem nudez" era o balde de TUDO que a varredura rápida (8 quadros, limiar 0,55, 2 confirmações) não
 * confirmou — vídeo com cena curta, ânus fraco (0,2–0,4) ou só detectado na análise completa caía lá. E a "Análise de
 * vídeo" (cenas com categorias) e a varredura da galeria eram duas verdades separadas.
 *
 * Agora, para cada vídeo, UM veredito (exclusivo):
 *  - PUTARIA: classificada NUDE; OU qualquer categoria registrada; OU a análise salva tem qualquer quadro ≥ limiar BAIXO
 *    (por classe: ânus × [Limiares.FATOR_ANUS]);
 *  - SEM NUDEZ: só se foi varrida de forma RIGOROSA (versão [VERSAO_ESTRITA], quadros espalhados pelo vídeo todo,
 *    limiar baixo) ou tem análise completa sem nenhum acerto — e nenhuma categoria;
 *  - AINDA NÃO VERIFICADO: o resto (sem varredura, varredura antiga/fraca, erro).
 */
object ClassifUnica {
    /** Limiar BASE baixo (≤ o padrão de 0,45 do usuário); o ânus usa 0,6× → 0,24. */
    const val LIMIAR_BAIXO = 0.40f
    /** Varreduras gravadas com esta versão (ou maior) são rigorosas. Linhas antigas têm 0. */
    const val VERSAO_ESTRITA = 2
    /** Para a varredura depois de tantos quadros com acerto (cats já completas o bastante p/ os chips). */
    const val QUADROS_PARA_PARAR = 3

    /** Quadros por vídeo: 1 a cada ~12 s, entre 10 e 40, espalhados do começo ao fim. */
    fun framesAlvo(durMs: Long): Int = ((durMs + 11_999L) / 12_000L).toInt().coerceIn(10, 40)
    /** Quadros mínimos lidos com sucesso p/ a varredura valer como "suficiente". */
    fun framesMinimos(durMs: Long): Int = maxOf(8, (framesAlvo(durMs) * 4 + 4) / 5)
    /** Instante (ms) do quadro [i] de [n]: ponto médio de cada fatia (nunca capa/créditos). */
    fun tempoDoQuadro(durMs: Long, i: Int, n: Int): Long = durMs * (2L * i + 1) / (2L * n)

    /** Categorias com acerto no quadro, com limiar por classe. Só as 4 classes detectadas (nunca a derivada). */
    fun acertos(scores: Map<Categoria, Float>): Set<Categoria> =
        scores.filter { (c, v) -> c.classIndex >= 0 && v >= Limiares.de(c, LIMIAR_BAIXO) }.keys

    fun suficiente(r: ResultadoOrg, durMs: Long): Boolean =
        r.classif == ClassifNudez.LIMPO && r.scanV >= VERSAO_ESTRITA && r.frames >= framesMinimos(durMs)

    /** Resumo da análise salva do vídeo. [completo]: terminou (não parcial) e leu quadros suficientes. */
    class ResumoAnalise(val cats: Set<Categoria>, val completo: Boolean)

    fun resumir(a: Analise): ResumoAnalise {
        val cats = LinkedHashSet<Categoria>()
        for (c in Categoria.entries) {
            if (c.classIndex < 0) continue
            val s = a.scores[c] ?: continue
            val lim = Limiares.de(c, LIMIAR_BAIXO)
            if (s.any { it >= lim }) cats += c
        }
        return ResumoAnalise(cats, !a.parcial && a.tempos.size >= framesMinimos(a.duracaoMs))
    }

    enum class Balde { PUTARIA, SEM_NUDEZ, NAO_VERIFICADO }

    /**
     * Veredito ÚNICO e exclusivo de um vídeo. [r] = linha do DB (ou null), [durMs] = duração, [an] = resumo da análise salva.
     * Devolve a linha "vista" (a que a UI usa): NUDE com categorias, LIMPO (só se estrito) ou PENDENTE.
     */
    fun vista(uri: String, r: ResultadoOrg?, durMs: Long, an: ResumoAnalise?): ResultadoOrg {
        val base = r ?: ResultadoOrg(uri, ClassifNudez.PENDENTE, emptySet(), 0L)
        val cats = base.cats + (an?.cats ?: emptySet())
        return when {
            cats.isNotEmpty() || base.classif == ClassifNudez.NUDE ->
                base.copy(classif = ClassifNudez.NUDE, cats = cats)
            base.classif == ClassifNudez.LIMPO && (suficiente(base, durMs) || an?.completo == true) ->
                base.copy(classif = ClassifNudez.LIMPO, cats = emptySet())
            else -> base.copy(classif = ClassifNudez.PENDENTE, cats = emptySet())
        }
    }

    fun balde(v: ResultadoOrg): Balde = when (v.classif) {
        ClassifNudez.NUDE -> Balde.PUTARIA
        ClassifNudez.LIMPO -> if (v.cats.isEmpty()) Balde.SEM_NUDEZ else Balde.PUTARIA
        else -> Balde.NAO_VERIFICADO
    }

    /**
     * Partição EXCLUSIVA dos vídeos nos 3 baldes (cada vídeo em exatamente um; vídeos repetidos pela mesma chave estável
     * — ex.: external × external_primary — contam uma vez). [vista] vem de [vistaDe]/[carregarVista].
     */
    fun particionar(videos: List<Video>, vista: Map<String, ResultadoOrg>): Map<Balde, List<Video>> {
        val out = Balde.entries.associateWith { ArrayList<Video>() }
        val vistos = HashSet<String>()
        for (v in videos) {
            val k = VideosMedia.chaveEstavel(v.uri.toString())
            if (!vistos.add(k)) continue
            val r = vista[k] ?: vista[v.uri.toString()] ?: vista(k, null, v.duracao, null)
            out.getValue(balde(r)) += v
        }
        return out
    }

    /** Aplica [vista] a todos os vídeos (mapa por chave estável). Um vídeo sem linha também vira PENDENTE. */
    fun vistaDe(videos: List<Video>, mapa: Map<String, ResultadoOrg>, resumos: Map<String, ResumoAnalise>): Map<String, ResultadoOrg> {
        if (mapa.isEmpty() && resumos.isEmpty()) return emptyMap()   // nada varrido/analisado ainda: sem álbuns virtuais (como antes)
        val out = LinkedHashMap<String, ResultadoOrg>(mapa.size + 16)
        for ((k, r) in mapa) out[k] = vista(r.uri, r, 0L, resumos[k])
        for (v in videos) {
            val k = VideosMedia.chaveEstavel(v.uri.toString())
            out[k] = vista(k, mapa[k] ?: mapa[v.uri.toString()], v.duracao, resumos[k])
        }
        return out
    }

    // ---- análise salva → resumo (cache em memória por arquivo+mtime; I/O fora da UI) ----
    private val chaves = ConcurrentHashMap<String, String>()
    private class Ent(val mtime: Long, val resumo: ResumoAnalise?)
    private val resumos = ConcurrentHashMap<String, Ent>()

    /** Chamar fora da thread principal. Só lê análises que existem. Mapa: chave estável do vídeo → resumo. */
    fun resumosDasAnalises(ctx: Context, videos: List<Video>): Map<String, ResumoAnalise> {
        val dir = File(ctx.filesDir, "analises")
        if (dir.list().isNullOrEmpty()) return emptyMap()
        val out = HashMap<String, ResumoAnalise>()
        for (v in videos) {
            val u = v.uri.toString()
            val ch = chaves.getOrPut(u) { runCatching { Cache.chave(ctx, v.uri) }.getOrDefault("") }
            if (ch.isEmpty()) continue
            val f = File(dir, "$ch.json"); val mt = f.lastModified()
            if (mt == 0L) continue
            val e = resumos[ch]?.takeIf { it.mtime == mt }
                ?: Ent(mt, Cache.carregar(ctx, ch)?.first?.let { resumir(it) }).also { resumos[ch] = it }
            e.resumo?.let { out[VideosMedia.chaveEstavel(u)] = it }
        }
        return out
    }

    /**
     * Carrega a classificação JÁ reconciliada p/ a UI (IO). Promove no DB quem tem acerto na análise (NUDE + categorias)
     * p/ a varredura também enxergar a mesma verdade.
     */
    fun carregarVista(ctx: Context, db: GaleriaDb, videos: List<Video>?): Map<String, ResultadoOrg> {
        val bruto = db.todosPorChaveEstavel()
        if (videos == null) return bruto.mapValues { (_, r) -> vista(r.uri, r, 0L, null) }
        val an = resumosDasAnalises(ctx, videos)
        val v = vistaDe(videos, bruto, an)
        for ((k, r) in v) {
            val antes = bruto[k]
            if (r.classif == ClassifNudez.NUDE && (antes == null || antes.classif != ClassifNudez.NUDE || antes.cats != r.cats))
                runCatching { db.promoverNude(k, r.cats) }
        }
        return v
    }
}

/** Migração: na 1ª vez após o upgrade, liga a re-varredura rigorosa em segundo plano p/ quem estava "Sem nudez" sem prova. */
object ClassifMigracao {
    private const val SP = "org_versao"
    private const val FLAG = "estrito_v"

    fun rodarSePreciso(ctx: Context) {
        val sp = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
        if (sp.getInt(FLAG, 0) >= ClassifUnica.VERSAO_ESTRITA) return
        val db = GaleriaDb(ctx)
        try {
            val fracos = db.contagemLimpoFraco(ClassifUnica.VERSAO_ESTRITA)
            sp.edit().putInt(FLAG, ClassifUnica.VERSAO_ESTRITA).apply()
            if (fracos > 0 && !OrgPrefs(ctx).ativo) OrganizarWorker.iniciar(ctx, null)
        } finally { runCatching { db.close() } }
    }
}
