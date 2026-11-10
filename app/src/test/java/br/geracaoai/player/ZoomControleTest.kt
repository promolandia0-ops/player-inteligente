package br.geracaoai.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import br.geracaoai.player.TamanhoLogica.Caixa
import br.geracaoai.player.TamanhoLogica.CaixaParte
import br.geracaoai.player.TamanhoLogica.ZoomAlvo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/** Lógica pura do Zoom automático (controle, aglomerado feminino, modos). Coordenadas normalizadas do vídeo. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ZoomControleTest {
    private fun p(c: Int, cx: Float, cy: Float, w: Float, h: Float, sc: Float = 0.7f) = CaixaParte(c, Caixa(sc, cx, cy, w, h))

    // Vídeo retrato 9:16 (720×1280) em Ajustar num celular em pé: view 1080×1920 no container 1080×2340.
    private val vw = 1080f; private val vh = 1920f; private val cw = 1080f; private val ch = 2340f

    /** Mulher em pé com [altura] (fração da altura do vídeo), centro em ([cx],[cy]): rosto, peito, bunda coberta e pés. */
    private fun mulher(cx: Float, cy: Float, altura: Float, j: () -> Float = { 0f }): List<CaixaParte> {
        val topo = cy - altura / 2f; val fh = altura / 10f
        return listOf(
            p(TamanhoLogica.FACE_FEMALE, cx + j(), topo + fh * 0.6f + j(), fh * 0.8f, fh),
            p(TamanhoLogica.BUTTOCKS_COVERED, cx + j(), topo + altura * 0.52f + j(), altura * 0.16f, altura * 0.1f),
            p(TamanhoLogica.FEET_COVERED, cx + j(), topo + altura * 0.97f + j(), altura * 0.12f, altura * 0.05f),
        )
    }

    private fun dentro(box: TamanhoLogica.RectN, a: ZoomAlvo, vw: Float, vh: Float, cw: Float, ch: Float) {
        for (nx in floatArrayOf(box.x0, box.x1)) for (ny in floatArrayOf(box.y0, box.y1)) {
            val (sx, sy) = TamanhoLogica.projetar(nx, ny, vw, vh, cw, ch, a.zoom, a.panX, a.panY)
            assertTrue("x=$sx fora de [0,$cw]", sx in -2f..(cw + 2f)); assertTrue("y=$sy fora de [0,$ch]", sy in -2f..(ch + 2f))
        }
    }

    // ───────── Corpo inteiro: estabilidade ─────────

    @Test fun mulherPequenaEmRetratoTravaDaZoomEFicaConstante() {
        val c = ZoomControle(); val rnd = Random(7)
        var aplicado: ZoomAlvo? = null; var atualizacoes = 0
        val zooms = ArrayList<Float>()
        for (i in 0 until 30) {
            val partes = mulher(0.30f, 0.55f, 0.15f) { (rnd.nextFloat() - 0.5f) * 0.004f }
            val s = c.passo(partes, ModoZoom.CORPO, vw, vh, cw, ch)
            assertNotEquals("ciclo $i: nunca 'ela já ocupa o quadro'", ZoomControle.Estado.OCUPA_QUADRO, s.estado)
            s.alvo?.let { a ->
                if (TamanhoLogica.deveAtualizarAlvo(aplicado, a)) { aplicado = a; atualizacoes++ }
                zooms += a.zoom
            }
            if (i >= 2) assertTrue("ciclo $i: zoom precisa estar ligado", c.zoomEstaLigado)
        }
        assertTrue("zoom>1,8 (min=${zooms.minOrNull()})", zooms.minOrNull()!! > 1.8f)
        assertTrue("zoom constante: ${zooms.minOrNull()}..${zooms.maxOrNull()}", zooms.maxOrNull()!! / zooms.minOrNull()!! < 1.1f)
        assertTrue("poucas atualizações visíveis: $atualizacoes", atualizacoes <= 3)
        val box = TamanhoLogica.caixaCorpoPadded(mulher(0.30f, 0.55f, 0.15f))!!
        dentro(box, aplicado!!, vw, vh, cw, ch)
    }

    @Test fun naoTrocaEmUmUnicoQuadroNemAoPerderDetecoes() {
        val c = ZoomControle()
        repeat(4) { c.passo(mulher(0.3f, 0.55f, 0.15f), ModoZoom.CORPO, vw, vh, cw, ch) }
        assertTrue(c.zoomEstaLigado)
        // um quadro "grande" isolado não desliga o zoom
        c.passo(mulher(0.5f, 0.5f, 0.9f), ModoZoom.CORPO, vw, vh, cw, ch)
        assertTrue(c.zoomEstaLigado)
        repeat(3) { c.passo(mulher(0.3f, 0.55f, 0.15f), ModoZoom.CORPO, vw, vh, cw, ch) }
        assertTrue(c.zoomEstaLigado)
        // 4 ciclos sem detecção: segura o enquadramento (alvo null = não muda); no 5º solta e volta a 1x
        repeat(4) { val s = c.passo(emptyList(), ModoZoom.CORPO, vw, vh, cw, ch); assertNull(s.alvo); assertEquals(ZoomControle.Estado.SEGUINDO, s.estado) }
        val s5 = c.passo(emptyList(), ModoZoom.CORPO, vw, vh, cw, ch)
        assertEquals(1f, s5.alvo!!.zoom, 1e-4f); assertEquals(ZoomControle.Estado.PROCURANDO, s5.estado)
        assertFalse(c.temTrava)
    }

    @Test fun histereseEntra55SaiDe75() {
        // vw=vh=cw=ch → ocupação = maior lado da caixa padded (≈1,5× a altura do corpo)
        fun rodar(c: ZoomControle, alt: Float, n: Int) { repeat(n) { c.passo(mulher(0.5f, 0.5f, alt), ModoZoom.CORPO, 1000f, 1000f, 1000f, 1000f) } }
        // ocupação ~0,65 (entre 55% e 75%): sem zoom ligado, não liga
        val a = ZoomControle(); rodar(a, 0.43f, 6); assertFalse(a.zoomEstaLigado)
        // pequena (~0,2): liga; sobe p/ ~0,70: continua ligado (histerese)
        val b = ZoomControle(); rodar(b, 0.13f, 4); assertTrue(b.zoomEstaLigado)
        rodar(b, 0.46f, 6); assertTrue("0,70 ainda dentro da histerese", b.zoomEstaLigado)
        // passa de 75%: desliga, mas só depois de ciclos seguidos (nunca em 1 quadro)
        b.passo(mulher(0.5f, 0.5f, 0.6f), ModoZoom.CORPO, 1000f, 1000f, 1000f, 1000f); assertTrue(b.zoomEstaLigado)
        rodar(b, 0.6f, 2); assertFalse(b.zoomEstaLigado)
    }

    @Test fun ocupacaoEhRelativaAoViewportNaoAoQuadroDoVideo() {
        // vídeo 16:9 num celular em pé: view 1080×608 em container 1080×2340. Mulher ocupando 90% da ALTURA do vídeo
        // é só ~23% da altura da tela → NÃO é "ocupa o quadro" e o zoom liga.
        val c = ZoomControle()
        var estado = ZoomControle.Estado.PROCURANDO
        repeat(4) { estado = c.passo(mulher(0.5f, 0.5f, 0.9f), ModoZoom.CORPO, 1080f, 608f, 1080f, 2340f).estado }
        assertNotEquals(ZoomControle.Estado.OCUPA_QUADRO, estado)
        assertTrue(c.zoomEstaLigado)
        // já grande no viewport (retrato cheio): ocupa o quadro
        val d = ZoomControle(); var e2 = ZoomControle.Estado.PROCURANDO
        repeat(4) { e2 = d.passo(mulher(0.5f, 0.5f, 0.95f), ModoZoom.CORPO, vw, vh, cw, ch).estado }
        assertEquals(ZoomControle.Estado.OCUPA_QUADRO, e2)
    }

    // ───────── Só a mulher ─────────

    @Test fun doisVultosSeparadosSoUmComRotulosFemininosZoomCentraNela() {
        for (ladoMulher in listOf(0.25f, 0.75f)) {
            val outro = 1f - ladoMulher
            val ela = listOf(
                p(TamanhoLogica.FACE_FEMALE, ladoMulher, 0.40f, 0.03f, 0.035f),
                p(TamanhoLogica.FEMALE_BREAST_EXPOSED, ladoMulher, 0.46f, 0.03f, 0.02f),
                p(TamanhoLogica.FEMALE_GENITALIA_EXPOSED, ladoMulher, 0.55f, 0.02f, 0.015f),
            )
            // o outro vulto só tem classes neutras (barriga, pés, axila) — como um homem
            val ele = listOf(
                p(TamanhoLogica.BELLY_EXPOSED, outro, 0.50f, 0.07f, 0.05f, 0.9f),
                p(TamanhoLogica.FEET_EXPOSED, outro, 0.66f, 0.06f, 0.025f, 0.9f),
                p(TamanhoLogica.ARMPITS_EXPOSED, outro, 0.44f, 0.03f, 0.02f, 0.9f),
            )
            val cl = TamanhoLogica.aglomeradoFeminino(ela + ele)
            assertEquals(ela.map { it.classe }.toSet(), cl.map { it.classe }.toSet())
            assertTrue(cl.all { kotlin.math.abs(it.caixa.cx - ladoMulher) < 0.01f })
            val c = ZoomControle(); var alvo: ZoomAlvo? = null
            repeat(5) { c.passo(ela + ele, ModoZoom.CORPO, vw, vh, cw, ch).alvo?.let { alvo = it } }
            val a = alvo!!; assertTrue(a.zoom > 1.5f)
            val box = TamanhoLogica.caixaCorpoPadded(cl)!!
            val (sx, _) = TamanhoLogica.projetar(box.cx, box.cy, vw, vh, cw, ch, a.zoom, a.panX, a.panY)
            assertEquals("centrado nela (lado=$ladoMulher)", cw / 2f, sx, cw * 0.08f)
            dentro(box, a, vw, vh, cw, ch)
        }
    }

    @Test fun outroVultoSemRotuloFemininoNuncaTravaSozinho() {
        val ele = listOf(p(TamanhoLogica.BELLY_EXPOSED, 0.5f, 0.5f, 0.07f, 0.05f, 0.9f), p(TamanhoLogica.FEET_EXPOSED, 0.5f, 0.66f, 0.06f, 0.025f, 0.9f))
        val c = ZoomControle()
        repeat(6) { val s = c.passo(ele, ModoZoom.CORPO, vw, vh, cw, ch); assertNull(s.alvo); assertEquals(ZoomControle.Estado.PROCURANDO, s.estado) }
        assertFalse(c.temTrava)
    }

    @Test fun partesSoltasNaoInflamACaixaDela() {
        // falso positivo (pés) longe da mulher não entra no aglomerado nem estica a caixa
        val ela = mulher(0.3f, 0.5f, 0.15f)
        val ruido = p(TamanhoLogica.FEET_EXPOSED, 0.9f, 0.95f, 0.05f, 0.02f, 0.6f)
        val cl = TamanhoLogica.aglomeradoFeminino(ela + ruido)
        assertFalse(cl.any { it.classe == TamanhoLogica.FEET_EXPOSED && it.caixa.cx > 0.8f })
        val box = TamanhoLogica.caixaCorpoPadded(cl)!!
        assertTrue("caixa=${box}", box.w < 0.4f && box.h < 0.4f)
    }

    @Test fun ancoraSozinhaEstimaCorpoComMargem() {
        for ((cls, w, h) in listOf(Triple(TamanhoLogica.FEMALE_BREAST_EXPOSED, 0.025f, 0.015f),
            Triple(TamanhoLogica.BUTTOCKS_EXPOSED, 0.04f, 0.02f), Triple(TamanhoLogica.FEMALE_GENITALIA_EXPOSED, 0.02f, 0.015f))) {
            val parte = p(cls, 0.3f, 0.5f, w, h)
            val box = TamanhoLogica.caixaCorpoPadded(listOf(parte))!!
            assertTrue("cls=$cls caixa=$box deveria ser bem maior que a parte", box.h > h * 6f && box.w > w * 4f)
            assertTrue(box.x0 < 0.3f && box.x1 > 0.3f && box.y0 < 0.5f && box.y1 > 0.5f)
        }
    }

    // ───────── Modos ─────────

    private fun rodar(c: ZoomControle, modo: ModoZoom, partes: List<CaixaParte>, n: Int = 6): ZoomAlvo? {
        var a: ZoomAlvo? = null
        repeat(n) { c.passo(partes, modo, vw, vh, cw, ch).alvo?.let { a = it } }
        return a
    }

    private fun centradoEDentro(box: TamanhoLogica.RectN, a: ZoomAlvo) {
        dentro(box, a, vw, vh, cw, ch)
        val (sx, sy) = TamanhoLogica.projetar(box.cx, box.cy, vw, vh, cw, ch, a.zoom, a.panX, a.panY)
        assertEquals(cw / 2f, sx, cw * 0.06f); assertEquals(ch / 2f, sy, ch * 0.06f)
    }

    @Test fun modoBucetaEnquadraSoAGenitaliaComMargemPequena() {
        val gen = p(TamanhoLogica.FEMALE_GENITALIA_EXPOSED, 0.5f, 0.55f, 0.07f, 0.04f)
        val partes = mulher(0.5f, 0.5f, 0.35f) + gen
        val a = rodar(ZoomControle(), ModoZoom.BUCETA, partes)!!
        assertTrue("zoom=${a.zoom}", a.zoom >= 3.5f && a.zoom <= 4f + 1e-3f)
        val alvo = TamanhoLogica.caixaAlvoModo(ModoZoom.BUCETA, partes)!!
        assertTrue("margem pequena: ${alvo.w}", alvo.w < gen.caixa.w * 2f)
        assertTrue(alvo.x0 < gen.caixa.x0 && alvo.x1 > gen.caixa.x1)
        centradoEDentro(alvo, a)
    }

    @Test fun modoBucetaSemDeteccaoSegura2sEVoltaAoCorpoInteiro() {
        val c = ZoomControle()
        val gen = p(TamanhoLogica.FEMALE_GENITALIA_EXPOSED, 0.5f, 0.55f, 0.07f, 0.04f)
        val corpo = mulher(0.5f, 0.5f, 0.3f)
        val tight = rodar(c, ModoZoom.BUCETA, corpo + gen)!!
        // sem a genitália: segura o último enquadramento por 4 ciclos (~1,6 s) sem emitir alvo
        repeat(4) { assertNull("segurando", c.passo(corpo, ModoZoom.BUCETA, vw, vh, cw, ch).alvo) }
        // depois relaxa para o corpo inteiro dela (zoom menor que o tight, corpo todo visível)
        var corpoAlvo: ZoomAlvo? = null
        repeat(4) { c.passo(corpo, ModoZoom.BUCETA, vw, vh, cw, ch).alvo?.let { corpoAlvo = it } }
        val a = corpoAlvo!!
        assertTrue("corpo ${a.zoom} < tight ${tight.zoom}", a.zoom < tight.zoom && a.zoom > 1f)
        dentro(TamanhoLogica.caixaCorpoPadded(corpo)!!, a, vw, vh, cw, ch)
    }

    @Test fun modoBundaEnquadraBundaEAnus() {
        val bunda = p(TamanhoLogica.BUTTOCKS_EXPOSED, 0.4f, 0.55f, 0.08f, 0.05f)
        val anus = p(TamanhoLogica.ANUS_EXPOSED, 0.4f, 0.56f, 0.015f, 0.01f, 0.5f)
        val partes = mulher(0.4f, 0.5f, 0.35f) + bunda + anus
        val a = rodar(ZoomControle(), ModoZoom.BUNDA, partes)!!
        assertTrue("zoom=${a.zoom}", a.zoom >= 3f)
        val alvo = TamanhoLogica.caixaAlvoModo(ModoZoom.BUNDA, partes)!!
        assertTrue(alvo.x0 < bunda.caixa.x0 && alvo.x1 > bunda.caixa.x1 && alvo.y0 < bunda.caixa.y0 && alvo.y1 > bunda.caixa.y1)
        assertTrue("só bunda/ânus, sem o corpo todo: h=${alvo.h}", alvo.h < 0.12f)
        centradoEDentro(alvo, a)
        // sem bunda exposta no modo Bunda: nada tight
        assertNull(TamanhoLogica.caixaAlvoModo(ModoZoom.BUNDA, mulher(0.4f, 0.5f, 0.35f)))
    }

    @Test fun modoPeitoRostoEnquadraUniaoPeitoERostoOuSoOPeito() {
        val rosto = p(TamanhoLogica.FACE_FEMALE, 0.5f, 0.42f, 0.05f, 0.06f)
        val peito = p(TamanhoLogica.FEMALE_BREAST_EXPOSED, 0.5f, 0.50f, 0.09f, 0.05f)
        val comRosto = listOf(rosto, peito, p(TamanhoLogica.FEET_COVERED, 0.5f, 0.85f, 0.06f, 0.03f))
        val alvo = TamanhoLogica.caixaAlvoModo(ModoZoom.PEITO_ROSTO, comRosto)!!
        assertTrue(alvo.y0 < rosto.caixa.y0 && alvo.y1 > peito.caixa.y1 && alvo.x0 < peito.caixa.x0 && alvo.x1 > peito.caixa.x1)
        val a = rodar(ZoomControle(), ModoZoom.PEITO_ROSTO, comRosto)!!
        assertTrue("zoom=${a.zoom}", a.zoom > 2.5f)
        centradoEDentro(alvo, a)
        // sem rosto: só os peitos
        val soPeito = TamanhoLogica.caixaAlvoModo(ModoZoom.PEITO_ROSTO, listOf(peito, comRosto.last()))!!
        assertTrue(soPeito.y0 > rosto.caixa.cy && soPeito.h < alvo.h)
        assertEquals(peito.caixa.cx, soPeito.cx, 0.01f)
        // rosto sem peito exposto: sem alvo tight (cai no corpo inteiro)
        assertNull(TamanhoLogica.caixaAlvoModo(ModoZoom.PEITO_ROSTO, listOf(rosto)))
    }

    @Test fun modosTightUsamAMesmaFiltragemSoMulher() {
        // peito (dela, à esquerda) + genitália "de outro vulto" sem contexto feminino perto: fica só o aglomerado dela
        val ela = listOf(p(TamanhoLogica.FACE_FEMALE, 0.2f, 0.40f, 0.04f, 0.045f), p(TamanhoLogica.FEMALE_BREAST_EXPOSED, 0.2f, 0.47f, 0.05f, 0.03f))
        val c = ZoomControle(); var a: ZoomAlvo? = null
        repeat(5) { c.passo(ela + p(TamanhoLogica.BELLY_EXPOSED, 0.85f, 0.5f, 0.08f, 0.05f, 0.9f), ModoZoom.PEITO_ROSTO, vw, vh, cw, ch).alvo?.let { a = it } }
        val box = TamanhoLogica.caixaAlvoModo(ModoZoom.PEITO_ROSTO, ela)!!
        val (sx, _) = TamanhoLogica.projetar(box.cx, box.cy, vw, vh, cw, ch, a!!.zoom, a!!.panX, a!!.panY)
        assertEquals(cw / 2f, sx, cw * 0.06f)
    }

    // ───────── Persistência do modo ─────────

    @Test fun modoPersisteEMigraDoLigaDesliga() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().clear().apply()
        assertEquals(ModoZoom.DESLIGADO, ModoZoom.de(Prefs(ctx).zoomModo))            // padrão: desligado
        ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().putBoolean("zoom_auto", true).apply()
        assertEquals(ModoZoom.CORPO, ModoZoom.de(Prefs(ctx).zoomModo))                // v1.3.15 ligado → Corpo inteiro
        for (m in ModoZoom.entries) { Prefs(ctx).zoomModo = m.ordinal; assertEquals(m, ModoZoom.de(Prefs(ctx).zoomModo)) }
        assertEquals(listOf("Desligado", "Corpo inteiro", "Buceta", "Bunda", "Peito + rosto"), ModoZoom.entries.map { it.rotulo })
        assertEquals(ModoZoom.DESLIGADO, ModoZoom.de(99))
    }
}
