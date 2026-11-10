package br.geracaoai.player

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import br.geracaoai.player.ClassifUnica.Balde

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ClassifUnicaTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val DUR = 10 * 60_000L
    private fun vid(id: Int, prim: Boolean = false) =
        Video(Uri.parse("content://media/${if (prim) "external_primary" else "external"}/video/media/$id"), "v$id", DUR, 1L)
    private fun linha(id: Int, c: ClassifNudez, cats: Set<Categoria> = emptySet(), sv: Int = 0, fr: Int = 0) =
        ResultadoOrg("content://media/external/video/media/$id", c, cats, 1L, scanV = sv, frames = fr)
    private fun analise(n: Int = 120, parcial: Boolean = false, put: (Categoria, Int) -> Float = { _, _ -> 0f }): Analise {
        val t = LongArray(n) { it * 5000L }
        return Analise(DUR, t, Categoria.entries.associateWith { c -> FloatArray(n) { put(c, it) } }, parcial)
    }

    @Test fun semNudezEstritoExigeVarreduraRigorosaEQuadrosSuficientes() {
        val min = ClassifUnica.framesMinimos(DUR)
        assertTrue(min >= 8)
        // linha antiga (scan_v=0) LIMPO: NÃO é Sem nudez
        assertEquals(Balde.NAO_VERIFICADO, ClassifUnica.balde(ClassifUnica.vista("u", linha(1, ClassifNudez.LIMPO), DUR, null)))
        // rigorosa mas leu poucos quadros: NÃO
        assertEquals(Balde.NAO_VERIFICADO, ClassifUnica.balde(ClassifUnica.vista("u", linha(1, ClassifNudez.LIMPO, sv = 2, fr = min - 1), DUR, null)))
        // rigorosa e suficiente: Sem nudez
        assertEquals(Balde.SEM_NUDEZ, ClassifUnica.balde(ClassifUnica.vista("u", linha(1, ClassifNudez.LIMPO, sv = 2, fr = min), DUR, null)))
        // sem linha / pendente / erro: nunca Sem nudez
        assertEquals(Balde.NAO_VERIFICADO, ClassifUnica.balde(ClassifUnica.vista("u", null, DUR, null)))
        assertEquals(Balde.NAO_VERIFICADO, ClassifUnica.balde(ClassifUnica.vista("u", linha(1, ClassifNudez.ERRO), DUR, null)))
        assertEquals(Balde.NAO_VERIFICADO, ClassifUnica.balde(ClassifUnica.vista("u", linha(1, ClassifNudez.PENDENTE), DUR, null)))
    }

    @Test fun quadrosEspalhadosCobremOVideoTodo() {
        val n = ClassifUnica.framesAlvo(2 * 3600_000L)
        assertEquals(40, n)
        val ts = (0 until n).map { ClassifUnica.tempoDoQuadro(2 * 3600_000L, it, n) }
        assertTrue(ts.first() < 2 * 3600_000L / n && ts.last() > 2 * 3600_000L * (n - 1) / n)   // 1ª e última fatias
        assertEquals(ts, ts.sorted())
        assertEquals(10, ClassifUnica.framesAlvo(30_000L))
    }

    @Test fun qualquerCategoriaFazPutariaNuncaSemNudez_mesmoComLinhaLimpoRigorosa() {
        // linha LIMPO rigorosa MAS com categoria registrada → Putaria
        val v = ClassifUnica.vista("u", linha(1, ClassifNudez.LIMPO, setOf(Categoria.PEITO), sv = 2, fr = 30), DUR, null)
        assertEquals(Balde.PUTARIA, ClassifUnica.balde(v)); assertEquals(ClassifNudez.NUDE, v.classif)
        // para toda classificação possível × toda categoria: com categoria nunca cai em Sem nudez
        for (c in ClassifNudez.entries) for (cat in Categoria.entries.filter { it.classIndex >= 0 }) for (sv in 0..3) {
            val r = ClassifUnica.vista("u", linha(1, c, setOf(cat), sv = sv, fr = 99), DUR, null)
            assertEquals("$c/$cat/$sv", Balde.PUTARIA, ClassifUnica.balde(r))
        }
    }

    @Test fun analiseSalvaComAcertoMandaParaPutariaMesmoSeAVarreduraDisseLimpo() {
        val an = ClassifUnica.resumir(analise { c, i -> if (c == Categoria.BUCETA && i == 7) 0.42f else 0f })
        assertEquals(setOf(Categoria.BUCETA), an.cats)
        val v = ClassifUnica.vista("u", linha(1, ClassifNudez.LIMPO, sv = 2, fr = 30), DUR, an)
        assertEquals(Balde.PUTARIA, ClassifUnica.balde(v)); assertTrue(Categoria.BUCETA in v.cats)
    }

    @Test fun anusFracoUsaLimiarPorClasse() {
        // 0,30 de ânus (< 0,40 base, ≥ 0,24 efetivo) é acerto; 0,30 de peito não é
        assertEquals(setOf(Categoria.ANUS), ClassifUnica.acertos(mapOf(Categoria.ANUS to 0.30f, Categoria.PEITO to 0.30f)))
        assertTrue(ClassifUnica.acertos(mapOf(Categoria.ANUS to 0.20f)).isEmpty())
        val an = ClassifUnica.resumir(analise { c, i -> if (c == Categoria.ANUS && i == 3) 0.30f else 0f })
        assertEquals(setOf(Categoria.ANUS), an.cats)
        // a categoria derivada Bunda aberta nunca entra sozinha
        assertTrue(ClassifUnica.acertos(mapOf(Categoria.BUNDA_ABERTA to 0.9f)).isEmpty())
    }

    @Test fun analiseCompletaSemAcertoProvaSemNudezMasParcialNao() {
        val limpa = ClassifUnica.resumir(analise())
        assertTrue(limpa.cats.isEmpty() && limpa.completo)
        assertEquals(Balde.SEM_NUDEZ, ClassifUnica.balde(ClassifUnica.vista("u", linha(1, ClassifNudez.LIMPO), DUR, limpa)))
        val parcial = ClassifUnica.resumir(analise(parcial = true))
        assertEquals(Balde.NAO_VERIFICADO, ClassifUnica.balde(ClassifUnica.vista("u", linha(1, ClassifNudez.LIMPO), DUR, parcial)))
        val curta = ClassifUnica.resumir(analise(n = 3))
        assertFalse(curta.completo)
    }

    @Test fun particaoEExclusivaECadaVideoApareceEmExatamenteUmaPasta() {
        val vs = (1..60).map { vid(it) } + vid(5, prim = true) + vid(6, prim = true)     // 5 e 6 repetidos (external_primary)
        val mapa = HashMap<String, ResultadoOrg>()
        for (i in 1..60) mapa["content://media/external/video/media/$i"] = when (i % 6) {
            0 -> linha(i, ClassifNudez.NUDE, setOf(Categoria.BUCETA))
            1 -> linha(i, ClassifNudez.LIMPO, sv = 2, fr = 40)
            2 -> linha(i, ClassifNudez.LIMPO)                         // antigo/fraco
            3 -> linha(i, ClassifNudez.LIMPO, setOf(Categoria.BUNDA))  // inconsistente: LIMPO com categoria
            4 -> linha(i, ClassifNudez.ERRO)
            else -> linha(i, ClassifNudez.PENDENTE)
        }
        val vista = ClassifUnica.vistaDe(vs, mapa, emptyMap())
        val part = ClassifUnica.particionar(vs, vista)
        val todos = part.values.flatten().map { VideosMedia.chaveEstavel(it.uri.toString()) }
        assertEquals("cada vídeo uma vez só", todos.size, todos.toSet().size)
        assertEquals(60, todos.size)
        val sem = part.getValue(Balde.SEM_NUDEZ).map { VideosMedia.chaveEstavel(it.uri.toString()) }.toSet()
        val put = part.getValue(Balde.PUTARIA).map { VideosMedia.chaveEstavel(it.uri.toString()) }.toSet()
        assertTrue(sem.intersect(put).isEmpty())
        // todo vídeo com categoria no DB está em Putaria, nenhum em Sem nudez
        for ((k, r) in mapa) if (r.cats.isNotEmpty()) { assertTrue(k in put); assertFalse(k in sem) }
        // Sem nudez: só os rigorosos (i % 6 == 1)
        assertEquals((1..60).filter { it % 6 == 1 }.map { "content://media/external/video/media/$it" }.toSet(), sem)
        assertEquals(10, sem.size)
    }

    @Test fun colisaoDeLinhasDuplicadasNudeVence() {
        ctx.deleteDatabase("galeria_org.db")
        val db = GaleriaDb(ctx)
        db.salvar("content://media/external_primary/video/media/9", ClassifNudez.LIMPO, emptySet(), 1L)
        db.salvar("content://media/external/video/media/9", ClassifNudez.NUDE, setOf(Categoria.PEITO), 1L)
        val m = db.todosPorChaveEstavel()
        assertEquals(ClassifNudez.NUDE, m["content://media/external/video/media/9"]!!.classif)
    }

    @Test fun dbGuardaVersaoDaVarreduraEMigracaoReconheceLimposFracos() {
        ctx.deleteDatabase("galeria_org.db")
        val db = GaleriaDb(ctx)
        db.salvar("content://media/external/video/media/1", ClassifNudez.LIMPO, emptySet(), 1L)          // linha "antiga": scan_v=0
        db.gravarVarredura("content://media/external/video/media/2", ClassifNudez.LIMPO, emptySet(), 1L, ClassifUnica.VERSAO_ESTRITA, 25)
        assertEquals(1, db.contagemLimpoFraco(ClassifUnica.VERSAO_ESTRITA))
        val r2 = db.obter("content://media/external/video/media/2")!!
        assertEquals(ClassifUnica.VERSAO_ESTRITA, r2.scanV); assertEquals(25, r2.frames)
        // linha antiga não conta como feita (é re-varrida); a rigorosa sim
        val feitos = OrgRecuperacao.mapaFeitos(db)
        assertNull(feitos["content://media/external/video/media/1"]); assertNotNull(feitos["content://media/external/video/media/2"])
        // gravarVarredura preserva o embedding
        db.salvarEmb("content://media/external/video/media/2", FloatArray(64) { it * 0.01f })
        db.gravarVarredura("content://media/external/video/media/2", ClassifNudez.NUDE, setOf(Categoria.ANUS), 2L, 2, 12)
        assertTrue(db.temEmb("content://media/external/video/media/2"))
        // promover NUDE (análise com acerto) mantém emb e escreve as categorias
        db.promoverNude("content://media/external/video/media/1", setOf(Categoria.BUCETA))
        assertEquals(setOf(Categoria.BUCETA), db.obter("content://media/external/video/media/1")!!.cats)
        assertEquals(0, db.contagemLimpoFraco(ClassifUnica.VERSAO_ESTRITA))
    }

    @Test fun migracaoLigaARevarreduraUmaVezESoSeHouverLimpoFraco() {
        ctx.deleteDatabase("galeria_org.db")
        ctx.getSharedPreferences("org_versao", Context.MODE_PRIVATE).edit().clear().commit()
        // sem linhas fracas: só marca como feita, não liga varredura
        ClassifMigracao.rodarSePreciso(ctx)
        assertEquals(ClassifUnica.VERSAO_ESTRITA, ctx.getSharedPreferences("org_versao", Context.MODE_PRIVATE).getInt("estrito_v", 0))
        assertFalse(OrgPrefs(ctx).ativo)
    }
}
