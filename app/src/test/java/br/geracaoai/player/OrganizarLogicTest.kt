package br.geracaoai.player

import org.junit.Assert.*
import org.junit.Test

/**
 * Simula a lógica de retomada/progresso da varredura sem Android Runtime.
 * Espelha o filtro de "já analisados" e o cálculo "x de total".
 */
class OrganizarLogicTest {

    data class FakeVideo(val uri: String, val mtime: Long)
    data class FakeResult(val classif: ClassifNudez, val mtime: Long)

    private fun pendentes(videos: List<FakeVideo>, db: Map<String, FakeResult>): List<FakeVideo> =
        videos.filter { v ->
            val ant = db[v.uri]
            ant == null || ant.mtime != v.mtime || ant.classif == ClassifNudez.PENDENTE
        }

    private fun feitosCount(videos: List<FakeVideo>, db: Map<String, FakeResult>): Int =
        videos.count { v ->
            val ant = db[v.uri]
            ant != null && ant.mtime == v.mtime &&
                (ant.classif == ClassifNudez.NUDE || ant.classif == ClassifNudez.LIMPO || ant.classif == ClassifNudez.ERRO)
        }

    @Test fun retomaPulaJaAnalisados() {
        val videos = (1..5000).map { FakeVideo("u$it", 100L + it) }
        val db = videos.take(150).associate { it.uri to FakeResult(ClassifNudez.LIMPO, it.mtime) }
        val pend = pendentes(videos, db)
        assertEquals(4850, pend.size)
        assertEquals(150, feitosCount(videos, db))
        // Progresso deve ser 150/5000 = 3%, NÃO 0/4850
        val frac = feitosCount(videos, db).toFloat() / videos.size
        assertEquals(0.03f, frac, 0.0001f)
    }

    @Test fun mtimeMudouReanalisa() {
        val v = FakeVideo("u1", 200L)
        val db = mapOf("u1" to FakeResult(ClassifNudez.NUDE, 100L))
        assertEquals(1, pendentes(listOf(v), db).size)
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
        // 6 frames em vídeo de 60s → tempos 0,12,24,36,48,60 (s) em ms
        val dur = 60_000L
        val frames = 6
        val tempos = (0 until frames).map { i -> if (frames == 1) 0L else (dur * i) / (frames - 1) }
        assertEquals(listOf(0L, 12000L, 24000L, 36000L, 48000L, 60000L), tempos)
    }

    @Test fun stressSimula5000SequencialComOomSkip() {
        // Simula processar 5000 itens, alguns "OOM"/timeout → ERRO, progresso monotônico
        val total = 5000
        val db = LinkedHashMap<String, FakeResult>()
        var feitos = 0
        for (i in 1..total) {
            val uri = "u$i"
            val classif = when {
                i % 97 == 0 -> ClassifNudez.ERRO      // timeout/OOM simulado
                i % 11 == 0 -> ClassifNudez.NUDE
                else -> ClassifNudez.LIMPO
            }
            db[uri] = FakeResult(classif, i.toLong())
            feitos++
            val frac = feitos.toFloat() / total
            assertTrue(frac in 0f..1f)
            // Nunca "reinicia" o contador
            assertEquals(feitos, db.size)
        }
        assertEquals(total, feitos)
        assertEquals(total / 97, db.values.count { it.classif == ClassifNudez.ERRO })
        // Retomada a partir de 3% não zera
        val videos = (1..total).map { FakeVideo("u$it", it.toLong()) }
        val ja = feitosCount(videos, db)
        assertEquals(total, ja)
        assertEquals(0, pendentes(videos, db).size)
    }

    @Test fun classesExpostasSaoAsCorretas() {
        assertArrayEquals(intArrayOf(2, 3, 4, 6, 14), OrganizarWorker.CLASSES_EXPOSTAS)
        assertEquals(6, OrganizarWorker.FRAMES)
    }
}
