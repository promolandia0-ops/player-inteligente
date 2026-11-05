package br.geracaoai.player

import org.junit.Assert.*
import org.junit.Test

/**
 * Lógica de retomada/progresso da varredura (espelha OrgRecuperacao + scan).
 * v1.3.2: match só por chave estável — mtime NÃO zera progresso.
 */
class OrganizarLogicTest {

    data class FakeVideo(val uri: String, val mtime: Long)
    data class FakeResult(val classif: ClassifNudez, val mtime: Long)

    private fun chave(u: String) = VideosMedia.chaveEstavel(u)

    private fun mapaFeitos(db: Map<String, FakeResult>): Map<String, ClassifNudez> {
        val out = HashMap<String, ClassifNudez>()
        for ((u, r) in db) {
            if (r.classif == ClassifNudez.NUDE || r.classif == ClassifNudez.LIMPO || r.classif == ClassifNudez.ERRO) {
                out[chave(u)] = r.classif
            }
        }
        return out
    }

    private fun estaFeito(feitos: Map<String, ClassifNudez>, uri: String): Boolean {
        val c = feitos[chave(uri)] ?: return false
        return c == ClassifNudez.NUDE || c == ClassifNudez.LIMPO || c == ClassifNudez.ERRO
    }

    private fun pendentes(videos: List<FakeVideo>, db: Map<String, FakeResult>): List<FakeVideo> {
        val feitos = mapaFeitos(db)
        return videos.filter { !estaFeito(feitos, it.uri) }
    }

    private fun feitosCount(videos: List<FakeVideo>, db: Map<String, FakeResult>): Int {
        val feitos = mapaFeitos(db)
        return videos.count { estaFeito(feitos, it.uri) }
    }

    @Test fun retomaPulaJaAnalisados() {
        val videos = (1..5000).map { FakeVideo("content://media/external/video/media/$it", 100L + it) }
        val db = videos.take(150).associate { it.uri to FakeResult(ClassifNudez.LIMPO, it.mtime) }
        assertEquals(4850, pendentes(videos, db).size)
        assertEquals(150, feitosCount(videos, db))
        assertEquals(0.03f, feitosCount(videos, db).toFloat() / videos.size, 0.0001f)
    }

    @Test fun mtimeMudouNAOReanalisaMais() {
        // v1.3.2: mtime diferente não manda pra pendente (evita Retomando 0)
        val v = FakeVideo("content://media/external/video/media/1", 200L)
        val db = mapOf(v.uri to FakeResult(ClassifNudez.NUDE, 100L))
        assertEquals(0, pendentes(listOf(v), db).size)
        assertEquals(1, feitosCount(listOf(v), db))
    }

    @Test fun externalPrimaryCasaComExternal() {
        val video = FakeVideo("content://media/external/video/media/42", 1L)
        val db = mapOf(
            "content://media/external_primary/video/media/42" to FakeResult(ClassifNudez.LIMPO, 99L),
        )
        assertEquals(1, feitosCount(listOf(video), db))
        assertEquals(0, pendentes(listOf(video), db).size)
    }

    @Test fun aposLimparNudeSoLimpoConta() {
        // Simula bug v1.3.0: NUDE apagados, LIMPO ficam
        val videos = (1..100).map { FakeVideo("content://media/external/video/media/$it", 1L) }
        val db = videos.filter { it.uri.substringAfterLast('/').toInt() % 2 == 0 }
            .associate { it.uri to FakeResult(ClassifNudez.LIMPO, 1L) }
        assertEquals(50, feitosCount(videos, db))
        assertEquals(50, pendentes(videos, db).size)
        // NÃO fica 0 de 100 se ainda há LIMPO
        assertTrue(feitosCount(videos, db) > 0)
    }

    @Test fun pendenteNaoContaComoFeito() {
        val v = FakeVideo("u1", 1L)
        val db = mapOf("u1" to FakeResult(ClassifNudez.PENDENTE, 1L))
        assertEquals(0, feitosCount(listOf(v), db))
        assertEquals(1, pendentes(listOf(v), db).size)
    }

    @Test fun erroContaComoFeitoParaNaoTravar() {
        val v = FakeVideo("u1", 1L)
        val db = mapOf("u1" to FakeResult(ClassifNudez.ERRO, 1L))
        assertEquals(1, feitosCount(listOf(v), db))
        assertEquals(0, pendentes(listOf(v), db).size)
    }

    @Test fun framesKeyframeDistribuidos() {
        val dur = 60_000L
        val frames = 6
        val tempos = (0 until frames).map { i -> if (frames == 1) 0L else (dur * i) / (frames - 1) }
        assertEquals(listOf(0L, 12000L, 24000L, 36000L, 48000L, 60000L), tempos)
    }

    @Test fun stressSimula5000SequencialComOomSkip() {
        val total = 5000
        val db = LinkedHashMap<String, FakeResult>()
        var feitos = 0
        for (i in 1..total) {
            val uri = "content://media/external/video/media/$i"
            val classif = when {
                i % 97 == 0 -> ClassifNudez.ERRO
                i % 11 == 0 -> ClassifNudez.NUDE
                else -> ClassifNudez.LIMPO
            }
            db[uri] = FakeResult(classif, i.toLong())
            feitos++
            assertEquals(feitos, db.size)
        }
        val videos = (1..total).map { FakeVideo("content://media/external/video/media/$it", it.toLong()) }
        assertEquals(total, feitosCount(videos, db))
        assertEquals(0, pendentes(videos, db).size)
    }

    @Test fun classesExpostasSaoAsCorretas() {
        assertArrayEquals(intArrayOf(2, 3, 4, 6, 14), OrganizarWorker.CLASSES_EXPOSTAS)
        assertEquals(8, OrganizarWorker.FRAMES)
        assertTrue(OrganizarWorker.LIMIAR >= 0.5f)
        assertEquals(2, OrganizarWorker.CONFIRMACOES)
    }

    @Test fun retomandoZeroSoSeDbVazio() {
        val videos = (1..1314).map { FakeVideo("content://media/external/video/media/$it", 1L) }
        assertEquals(0, feitosCount(videos, emptyMap()))
        // Com 800 LIMPO restantes após wipe NUDE → NÃO é 0
        val db = videos.take(800).associate { it.uri to FakeResult(ClassifNudez.LIMPO, 1L) }
        assertEquals(800, feitosCount(videos, db))
        assertNotEquals(0, feitosCount(videos, db))
    }
}
