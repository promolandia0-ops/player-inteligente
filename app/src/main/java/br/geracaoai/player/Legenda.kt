package br.geracaoai.player

import android.content.Context
import android.net.Uri
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class Cue(val t0: Long, val t1: Long, val en: String, val pt: String)

/**
 * Legendas EN→PT offline: Whisper tiny.en (sherpa-onnx) + tradução lexical leve.
 * Qualidade ASR: boa em inglês claro; fraca em sotaque/ruído.
 * Qualidade PT: aproximada (dicionário + ordem), não é NMT completo — modelos Opus-MT/CTranslate2
 * não entraram no APK por falta de runtime Android estável neste release.
 */
object LegendaProgresso {
    private fun p(ctx: Context) = ctx.getSharedPreferences("legenda_prog", Context.MODE_PRIVATE)
    fun set(ctx: Context, uri: String, frac: Float) {
        p(ctx).edit().putFloat("p:$uri", frac).putLong("t:$uri", System.currentTimeMillis()).apply()
    }
    fun clear(ctx: Context, uri: String) {
        p(ctx).edit().remove("p:$uri").remove("t:$uri").apply()
    }
    fun get(ctx: Context, uri: String): Float? {
        val sp = p(ctx)
        if (!sp.contains("p:$uri")) return null
        if (System.currentTimeMillis() - sp.getLong("t:$uri", 0) > 6 * 3600_000L) return null
        return sp.getFloat("p:$uri", 0f)
    }
}

object LegendaEngine {
    private const val TAG = "Legenda"
    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "legenda").also { it.priority = Thread.MIN_PRIORITY + 1 }
    }
    @Volatile private var job: Future<*>? = null
    private val stop = AtomicBoolean(false)
    private val seekMs = AtomicLong(0)
    @Volatile private var ligadoUri: String? = null

    fun cacheFile(ctx: Context, uri: Uri) =
        File(File(ctx.filesDir, "legendas").apply { mkdirs() }, Cache.chave(ctx, uri) + ".json")

    fun carregar(ctx: Context, uri: Uri): List<Cue> = runCatching {
        val j = JSONObject(cacheFile(ctx, uri).readText())
        val a = j.getJSONArray("cues")
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Cue(o.getLong("t0"), o.getLong("t1"), o.getString("en"), o.getString("pt"))
        }
    }.getOrDefault(emptyList())

    fun salvar(ctx: Context, uri: Uri, cues: List<Cue>) = runCatching {
        val a = JSONArray()
        cues.forEach { c -> a.put(JSONObject().put("t0", c.t0).put("t1", c.t1).put("en", c.en).put("pt", c.pt)) }
        cacheFile(ctx, uri).writeText(JSONObject().put("cues", a).toString())
    }

    fun preferencia(ctx: Context, uri: Uri, on: Boolean) {
        ctx.getSharedPreferences("legenda", Context.MODE_PRIVATE).edit()
            .putBoolean(Cache.chave(ctx, uri), on).apply()
    }
    fun preferencia(ctx: Context, uri: Uri) =
        ctx.getSharedPreferences("legenda", Context.MODE_PRIVATE).getBoolean(Cache.chave(ctx, uri), false)

    fun seek(posMs: Long) { seekMs.set(posMs) }

    fun parar() {
        stop.set(true)
        job?.cancel(true)
        job = null
        ligadoUri = null
    }

    fun iniciar(
        ctx: Context, uri: Uri, startMs: Long,
        onCue: (Cue) -> Unit, onStatus: (String) -> Unit,
    ) {
        parar()
        stop.set(false)
        seekMs.set(startMs)
        ligadoUri = uri.toString()
        preferencia(ctx, uri, true)
        val cached = carregar(ctx, uri)
        if (cached.isNotEmpty()) {
            cached.forEach(onCue)
            LegendaProgresso.set(ctx, uri.toString(), 1f)
            onStatus("Pronto")
            return
        }
        job = exec.submit {
            try {
                onStatus("Traduzindo…")
                LegendaProgresso.set(ctx, uri.toString(), 0.02f)
                val assetDir = copiarModelos(ctx)
                val recognizer = criarRecognizer(assetDir)
                val pcm = AudioPcm.decodificarPcm16k(ctx, uri)
                if (pcm == null || pcm.isEmpty()) {
                    onStatus("Não li o áudio deste vídeo")
                    LegendaProgresso.clear(ctx, uri.toString())
                    return@submit
                }
                if (AudioPcm.rms(pcm) < 1e-5f) {
                    onStatus("Áudio silencioso")
                    LegendaProgresso.clear(ctx, uri.toString())
                    return@submit
                }
                val cues = ArrayList<Cue>()
                val chunk = 16_000 * 15 // 15s
                var pos = ((seekMs.get() / 1000.0) * 16_000).toInt().coerceIn(0, pcm.size)
                val total = (pcm.size - pos).coerceAtLeast(1)
                val startPos = pos
                while (!stop.get() && pos < pcm.size) {
                    val seekAt = ((seekMs.get() / 1000.0) * 16_000).toInt()
                    if (kotlin.math.abs(seekAt - pos) > 16_000 * 3) pos = seekAt.coerceIn(0, pcm.size)
                    val end = (pos + chunk).coerceAtMost(pcm.size)
                    val slice = pcm.copyOfRange(pos, end)
                    val t0 = pos * 1000L / 16_000
                    val t1 = end * 1000L / 16_000
                    val stream = recognizer.createStream()
                    stream.acceptWaveform(slice, 16000)
                    recognizer.decode(stream)
                    val en = recognizer.getResult(stream).text.trim()
                    stream.release()
                    if (en.isNotBlank()) {
                        val pt = TradutorLexico.traduzir(en)
                        val cue = Cue(t0, t1, en, pt)
                        cues.add(cue)
                        onCue(cue)
                    }
                    pos = end
                    val frac = ((pos - startPos).toFloat() / total).coerceIn(0f, 0.99f)
                    LegendaProgresso.set(ctx, uri.toString(), frac)
                    onStatus("Traduzindo… ${(frac * 100).toInt()}%")
                }
                if (cues.isNotEmpty()) salvar(ctx, uri, cues)
                if (!stop.get()) LegendaProgresso.set(ctx, uri.toString(), 1f)
                else LegendaProgresso.clear(ctx, uri.toString())
                onStatus(if (stop.get()) "Parado" else "Pronto")
                recognizer.release()
            } catch (e: Throwable) {
                Log.e(TAG, "falhou", e)
                LegendaProgresso.clear(ctx, uri.toString())
                onStatus("Erro: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun copiarModelos(ctx: Context): File {
        val dir = File(ctx.filesDir, "asr_whisper")
        if (!File(dir, "tiny.en-encoder.int8.onnx").exists()) {
            dir.mkdirs()
            listOf("tiny.en-encoder.int8.onnx", "tiny.en-decoder.int8.onnx", "tiny.en-tokens.txt").forEach { n ->
                ctx.assets.open("asr/$n").use { inp -> File(dir, n).outputStream().use { inp.copyTo(it) } }
            }
        }
        return dir
    }

    private fun criarRecognizer(dir: File): OfflineRecognizer {
        val whisper = OfflineWhisperModelConfig(
            encoder = File(dir, "tiny.en-encoder.int8.onnx").absolutePath,
            decoder = File(dir, "tiny.en-decoder.int8.onnx").absolutePath,
            language = "en",
            task = "transcribe",
        )
        val model = OfflineModelConfig(
            whisper = whisper,
            tokens = File(dir, "tiny.en-tokens.txt").absolutePath,
            numThreads = 2,
            provider = "cpu",
            modelType = "whisper",
        )
        val cfg = OfflineRecognizerConfig(modelConfig = model, decodingMethod = "greedy_search")
        // assetManager null = carrega de caminhos absolutos
        return OfflineRecognizer(assetManager = null, config = cfg)
    }
}

/** Tradução lexical EN→PT (offline, leve). Qualidade inferior a Opus-MT. */
object TradutorLexico {
    private val mapa = mapOf(
        "i" to "eu", "you" to "você", "the" to "o", "a" to "um", "to" to "para", "and" to "e",
        "is" to "é", "are" to "são", "was" to "era", "my" to "meu", "your" to "seu", "me" to "me",
        "we" to "nós", "they" to "eles", "he" to "ele", "she" to "ela", "it" to "isso",
        "this" to "isso", "that" to "aquilo", "with" to "com", "for" to "para", "on" to "em",
        "in" to "em", "of" to "de", "not" to "não", "no" to "não", "yes" to "sim", "oh" to "ah",
        "please" to "por favor", "love" to "amor", "baby" to "bebê", "come" to "vem", "go" to "vai",
        "want" to "quero", "need" to "preciso", "fuck" to "porra", "shit" to "merda", "yes" to "sim",
        "good" to "bom", "bad" to "ruim", "more" to "mais", "now" to "agora", "here" to "aqui",
        "there" to "lá", "what" to "o que", "why" to "por que", "how" to "como", "when" to "quando",
        "who" to "quem", "don't" to "não", "can't" to "não posso", "won't" to "não vou",
        "I'm" to "eu estou", "you're" to "você está", "it's" to "é", "that's" to "isso é",
        "let's" to "vamos", "gonna" to "vou", "wanna" to "quero", "gotta" to "tenho que",
    )
    fun traduzir(en: String): String {
        val words = en.split(Regex("\\s+"))
        return words.joinToString(" ") { w ->
            val key = w.lowercase().trim(',', '.', '!', '?', '"', '\'', ';', ':')
            val punct = w.filter { it in ",.!?\"';:" }
            val t = mapa[key] ?: w
            if (w.firstOrNull()?.isUpperCase() == true && t.isNotEmpty()) t.replaceFirstChar { it.uppercase() } + punct
            else t + punct
        }
    }
}
