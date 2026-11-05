package br.geracaoai.player

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Size
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.ArrayDeque
import kotlin.math.abs

data class Video(
    val uri: Uri,
    val nome: String,
    val duracao: Long,
    val tamanho: Long,
    val bucketId: String = "",
    val bucketNome: String = "",
    val dataMod: Long = 0L,
    val largura: Int = 0,
    val altura: Int = 0,
) {
    val pixels: Long get() = largura.toLong() * altura.toLong()
}
data class Pasta(
    val id: String,
    val nome: String,
    val videos: List<Video>,
) {
    val capa: Video get() = videos.first()
    val contagem: Int get() = videos.size
    val recente: Long get() = videos.maxOfOrNull { it.dataMod } ?: 0L
}
/** Tupla leve para snapshot da varredura (evita recriar muitos remembers). */
internal data class Quint<A,B,C,D,E>(val a: A, val b: B, val c: C, val d: D, val e: E)

fun fmtTam(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1048576L -> "%.0f KB".format(bytes / 1024.0)
    bytes < 1073741824L -> {
        val mb = bytes / 1048576.0
        if (mb < 10) "%.1f MB".format(mb) else "%.0f MB".format(mb)
    }
    else -> "%.2f GB".format(bytes / 1073741824.0)
}

fun ePastaCamera(nome: String): Boolean {
    val s = nome.lowercase()
    return s == "camera" || s == "câmera" || s == "camera roll" || s.contains("dcim")
}

fun pastasDe(videos: List<Video>): List<Pasta> {
    val grupos = videos.groupBy { it.bucketId.ifBlank { it.bucketNome.ifBlank { "_outro" } } }
        .map { (id, vs) ->
            val nome = vs.firstOrNull()?.bucketNome?.takeIf { it.isNotBlank() } ?: "Outros"
            Pasta(id, nome, vs.sortedByDescending { it.dataMod })
        }
    return grupos.sortedWith(
        compareByDescending<Pasta> { ePastaCamera(it.nome) }
            .thenByDescending { it.recente }
            .thenBy { it.nome.lowercase() }
    )
}

/** Ordem esperada dos nomes de pasta (Câmera primeiro, depois mais recente). */
fun ordenarNomesPastas(itens: List<Pair<String, Long>>): List<String> =
    itens.sortedWith(
        compareByDescending<Pair<String, Long>> { ePastaCamera(it.first) }
            .thenByDescending { it.second }
            .thenBy { it.first.lowercase() },
    ).map { it.first }
data class Nav(val video: Video? = null, val ajustes: Boolean = false)

fun fmt(ms: Long): String { val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val ss = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss) }

enum class Modo(val rotulo: String) { AJUSTAR("Ajustar"), PREENCHER("Preencher"), ESTICAR("Esticar"), ORIGINAL("100%"), AUTO("Auto") }

/** Tamanho (px) do vídeo dentro do container para cada modo. */
fun tamanhoVideo(modo: Modo, cw: Float, ch: Float, vw: Float, vh: Float): Pair<Float, Float> {
    if (vw <= 0 || vh <= 0) return cw to ch
    val fit = minOf(cw / vw, ch / vh); val fill = maxOf(cw / vw, ch / vh)
    return when (modo) {
        Modo.AJUSTAR -> vw * fit to vh * fit
        Modo.PREENCHER -> vw * fill to vh * fill
        Modo.ESTICAR -> cw to ch
        Modo.ORIGINAL -> vw to vh
        Modo.AUTO -> { val rv = vw / vh; val rc = cw / ch
            // mesma orientação e proporção próxima (até 25% de corte) => preencher; senão ajustar
            if ((rv >= 1) == (rc >= 1) && maxOf(rv, rc) / minOf(rv, rc) <= 1.34f) vw * fill to vh * fill else vw * fit to vh * fit }
    }
}

class MainActivity : ComponentActivity() {
    private val nav = mutableStateOf(Nav())

    @OptIn(ExperimentalSharedTransitionApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        intent?.data?.let { nav.value = Nav(videoDeUri(it)) }
        setContent {
            TemaPlayer {
                Box(Modifier.fillMaxSize().background(Cores.Fundo)) {
                    SharedTransitionLayout {
                        val playerAberto = nav.value.video != null
                        val ajustesAberto = nav.value.ajustes
                        // Biblioteca permanece composta sob o player (scroll/aba/pasta intactos).
                        when {
                            ajustesAberto -> {
                                BackHandler { nav.value = Nav() }
                                Ajustes { nav.value = Nav() }
                            }
                            else -> {
                                Box(Modifier.fillMaxSize()) {
                                    // visible=true sempre: não dispose ao abrir vídeo
                                    androidx.compose.animation.AnimatedVisibility(
                                        visible = true,
                                        enter = EnterTransition.None,
                                        exit = ExitTransition.None,
                                    ) {
                                        Biblioteca(
                                            this@SharedTransitionLayout, this,
                                            onAbrir = { nav.value = Nav(it) },
                                            onAjustes = { },
                                            videoDeUri = ::videoDeUri,
                                            ativa = !playerAberto,
                                        )
                                    }
                                    if (playerAberto) {
                                        val v = nav.value.video!!
                                        fun fecharPlayer() {
                                            sairTelaCheia()
                                            nav.value = Nav()
                                        }
                                        BackHandler { fecharPlayer() }
                                        key(v.uri.toString()) {
                                            Box(Modifier.fillMaxSize().background(Color.Black)) {
                                                PlayerTela(v, this@MainActivity, this@SharedTransitionLayout, null) {
                                                    fecharPlayer()
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); intent.data?.let { nav.value = Nav(videoDeUri(it)) } }

    fun videoDeUri(u: Uri): Video {
        var nome = u.lastPathSegment ?: "Vídeo"; var tam = 0L
        runCatching { contentResolver.query(u, null, null, null, null)?.use { c -> if (c.moveToFirst()) {
            c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { nome = c.getString(it) ?: nome }
            c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { tam = c.getLong(it) } } } }
        return Video(u, nome, 0, tam)
    }

    fun telaCheia(on: Boolean, paisagem: Boolean = true) {
        val ctl = WindowInsetsControllerCompat(window, window.decorView)
        if (on) { requestedOrientation = if (paisagem) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            ctl.hide(WindowInsetsCompat.Type.systemBars()); ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
        else sairTelaCheia()
    }
    fun sairTelaCheia() { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        window.attributes = window.attributes.apply { screenBrightness = -1f } }
    fun brilho(v: Float) { window.attributes = window.attributes.apply { screenBrightness = v.coerceIn(0.01f, 1f) } }
}

@Composable
fun Thumb(uri: Uri, modifier: Modifier, tempoMs: Long? = null, w: Int = 480, h: Int = 270) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, uri, tempoMs) {
        value = withContext(Dispatchers.IO) { runCatching {
            if (tempoMs == null) ctx.contentResolver.loadThumbnail(uri, Size(w, h), null)
            else MediaMetadataRetriever().run { try { setDataSource(ctx, uri); getScaledFrameAtTime(tempoMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h) } finally { release() } }
        }.getOrNull() }
    }
    Box(modifier.background(Color(0xFF15121D)), contentAlignment = Alignment.Center) {
        Crossfade(bmp, animationSpec = tween(250), label = "t") { b ->
            if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF1A1424), Color(0xFF0E0B14)))))
        }
    }
}

@Composable
fun Ajustes(voltar: () -> Unit) {
    val ctx = LocalContext.current; val p = remember { Prefs(ctx) }
    var limiar by remember { mutableFloatStateOf(p.limiar) }
    var offset by remember { mutableIntStateOf(p.offsetS) }
    var fps by remember { mutableIntStateOf(p.fps) }
    var nn by remember { mutableStateOf(p.acelerador) }
    val sc = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Cores.Magenta, inactiveTrackColor = Color(0x33FFFFFF), activeTickColor = Color.White, inactiveTickColor = Color(0x55FFFFFF))
    @Composable fun Cartao(conteudo: @Composable ColumnScope.() -> Unit) = Column(Modifier.fillMaxWidth().vidro().padding(18.dp), content = conteudo)
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).toque(onClick = voltar).vidro(RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", tint = Cores.Texto) }
            Spacer(Modifier.width(14.dp)); Text("Ajustes", style = MaterialTheme.typography.headlineMedium)
        }
        Cartao { Text("Sensibilidade", style = MaterialTheme.typography.titleMedium)
            Text("Limiar de confiança: ${"%.0f".format(limiar * 100)}%. Menor encontra mais cenas (e mais falsos positivos). Muda na hora, sem reanalisar.", style = MaterialTheme.typography.bodySmall, color = Cores.Suave)
            Slider(limiar, { limiar = it; p.limiar = it }, valueRange = 0.15f..0.9f, colors = sc) }
        Cartao { Text("Pular antes da cena: $offset s", style = MaterialTheme.typography.titleMedium)
            Text("Ao tocar numa cena ou em Próxima/Anterior, o vídeo começa esse tempo antes da parte aparecer.", style = MaterialTheme.typography.bodySmall, color = Cores.Suave)
            Slider(offset.toFloat(), { offset = it.toInt(); p.offsetS = offset }, valueRange = 0f..10f, steps = 9, colors = sc) }
        Cartao { Text("Precisão: $fps quadro(s)/s", style = MaterialTheme.typography.titleMedium)
            Text("Mais quadros = início da cena mais exato, análise mais lenta. Vale para novas buscas.", style = MaterialTheme.typography.bodySmall, color = Cores.Suave)
            Slider(fps.toFloat(), { fps = it.toInt(); p.fps = fps }, valueRange = 1f..4f, steps = 2, colors = sc) }
        Cartao { Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Acelerador (NNAPI / NPU)", style = MaterialTheme.typography.titleMedium)
                Text("Se não for suportado, usa a CPU automaticamente.", style = MaterialTheme.typography.bodySmall, color = Cores.Suave) }
            Switch(nn, { nn = it; p.acelerador = it; if (it) Detector.liberarNnapi(ctx) }, colors = SwitchDefaults.colors(checkedTrackColor = Cores.Magenta, checkedThumbColor = Color.White)) } }
        var log by remember { mutableStateOf<String?>(null) }
        BotaoVidro("Ver último erro", icone = { Icon(Icons.Default.BugReport, null, tint = Cores.Texto) }) { log = App.ultimoErro(ctx) }
        log?.let { txt ->
            AlertDialog(onDismissRequest = { log = null },
                title = { Text("Último erro") },
                text = { Text(txt, style = MaterialTheme.typography.bodySmall, modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
                confirmButton = { TextButton({
                    val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("erro", txt))
                    android.widget.Toast.makeText(ctx, "Copiado", android.widget.Toast.LENGTH_SHORT).show() }) { Text("Copiar") } },
                dismissButton = { TextButton({
                    runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, txt), "Enviar erro").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Text("Compartilhar") } })
        }
        BotaoVidro("Reanalisar galeria", icone = { Icon(Icons.Default.Refresh, null, tint = Cores.Texto) }) {
            runCatching { GaleriaDb(ctx).limpar(); OrganizarWorker.iniciar(ctx) }
            android.widget.Toast.makeText(ctx, "Reclassificando a galeria do zero…", android.widget.Toast.LENGTH_SHORT).show() }
        var cacheInfo by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            cacheInfo = withContext(Dispatchers.IO) { "Cache ~${fmtTam(CacheLimpeza.tamanhoCache(ctx))}" }
        }
        cacheInfo?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Cores.Suave) }
        BotaoVidro("Limpar cache", icone = { Icon(Icons.Default.CleaningServices, null, tint = Cores.Texto) }) {
            val freed = CacheLimpeza.limpar(ctx)
            cacheInfo = "Cache ~${fmtTam(CacheLimpeza.tamanhoCache(ctx))}"
            android.widget.Toast.makeText(ctx, "Liberou ${fmtTam(freed)} (análises e vídeos intactos)", android.widget.Toast.LENGTH_LONG).show()
        }
        BotaoVidro("Apagar análises salvas", icone = { Icon(Icons.Default.DeleteSweep, null, tint = Cores.Texto) }) {
            Cache.limpar(ctx); android.widget.Toast.makeText(ctx, "Análises apagadas", android.widget.Toast.LENGTH_SHORT).show() }
        val totalVids = remember { mutableIntStateOf(-1) }
        LaunchedEffect(Unit) {
            totalVids.intValue = withContext(Dispatchers.IO) { VideosMedia.total(ctx) }
        }
        Text(
            "MediaStore · ${if (totalVids.intValue < 0) "…" else totalVids.intValue} vídeos" +
                (if (VideosMedia.temAcessoParcial(ctx)) " · acesso parcial" else "") +
                " · ${VideosMedia.colecoes(ctx).size} volume(s)",
            style = MaterialTheme.typography.labelMedium, color = Cores.Suave,
        )
        Text("100% offline: o app não tem permissão de Internet.\nModelo: NudeNet 320n (detector por parte do corpo) via ONNX Runtime Mobile.\nSó lista vídeos (fotos do Samsung não entram na contagem).",
            style = MaterialTheme.typography.bodySmall, color = Cores.Suave)
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PlayerTela(video: Video, act: MainActivity, sts: SharedTransitionScope, avs: AnimatedVisibilityScope?, voltar: () -> Unit) {
    val ctx = LocalContext.current
    val hap = LocalHapticFeedback.current
    val prefs = remember { Prefs(ctx) }
    val reforco = remember { ReforcoAudio() }
    val player = remember {
        val rf = object : DefaultRenderersFactory(ctx) {
            override fun buildAudioSink(c: Context, floatOut: Boolean, audioTrackPlayback: Boolean): AudioSink =
                DefaultAudioSink.Builder(c).setAudioProcessors(arrayOf(reforco)).build()
        }.setEnableDecoderFallback(true).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        ExoPlayer.Builder(ctx, rf).setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).build()
    }
    val am = remember { ctx.getSystemService(AudioManager::class.java) }
    val maxVol = remember { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var nivelVol by remember { mutableFloatStateOf(am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol) }
    var brilho by remember { mutableFloatStateOf(act.window.attributes.screenBrightness.takeIf { it >= 0 } ?: 0.5f) }
    var hud by remember { mutableStateOf<String?>(null) }
    var menuPlayer by remember { mutableStateOf(false) }
    var telaOff by remember { mutableStateOf(prefs.telaDesligada) }
    var zoomAuto by remember { mutableStateOf(prefs.zoomAuto) }
    var zoomManual by remember(video.uri) { mutableStateOf(false) }
    var textureView by remember { mutableStateOf<TextureView?>(null) }
    var viewCw by remember { mutableFloatStateOf(0f) }
    var viewCh by remember { mutableFloatStateOf(0f) }
    val mediaUri = video.uri

    val chave = remember(video.uri) { Cache.chave(ctx, video.uri) }
    var analise by remember(video.uri) { mutableStateOf(Cache.carregar(ctx, chave)) }
    var aba by remember { mutableStateOf(prefs.cats.firstOrNull() ?: Categoria.PEITO) }
    val offsetMs = prefs.offsetS * 1000L
    val segsTodas = remember(analise, prefs.limiar) {
        analise?.let { (a, iv) -> Segmentador.segmentos(a, Categoria.entries.toSet(), prefs.limiar, iv) } ?: emptyList()
    }
    val segs = remember(segsTodas, aba) { segsTodas.filter { it.categoria == aba } }
    LaunchedEffect(aba) { prefs.cats = setOf(aba) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var tocando by remember { mutableStateOf(false) }
    // Mantém tela ligada ao tocar; opção "tela desligada" usa wake lock local p/ continuar o áudio.
    DisposableEffect(tocando, telaOff) {
        val w = act.window
        if (tocando && !telaOff) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        player.setWakeMode(if (telaOff) C.WAKE_MODE_LOCAL else C.WAKE_MODE_NONE)
        onDispose {
            w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            runCatching { player.setWakeMode(C.WAKE_MODE_NONE) }
        }
    }
    var velocidade by remember { mutableFloatStateOf(1f) }
    var controles by remember { mutableStateOf(true) }
    var vsize by remember { mutableStateOf(VideoSize.UNKNOWN) }
    var modo by remember(video.uri) { mutableStateOf(Modo.AJUSTAR) }
    var zoom by remember(video.uri) { mutableFloatStateOf(1f) }
    var pan by remember(video.uri) { mutableStateOf(Offset.Zero) }
    val zoomAnim by animateFloatAsState(zoom, spring(stiffness = 280f, dampingRatio = 0.86f), label = "z")
    val panAnim by animateOffsetAsState(pan, spring(stiffness = 280f, dampingRatio = 0.86f), label = "pn")

    // Galeria nunca compete com o player: cede ao abrir, retoma ao sair.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { Exclusao.playerAberto(ctx); runCatching { if (OrgPrefs(ctx).ativo) OrganizarScanService.yieldToAnalise(ctx) } }
        while (true) { delay(4000); withContext(Dispatchers.IO) { Exclusao.playerAberto(ctx) } }
    }
    DisposableEffect(Unit) { onDispose { Exclusao.playerFechado(ctx); Exclusao.retomarGaleriaSePreciso(ctx) } }
    val chavePos = remember(video.uri) { VideosMedia.chaveEstavel(video.uri.toString()) }
    DisposableEffect(video.uri) {
        // Sempre reentra imersivo a cada abertura (evita status bar por cima nas 2ª+ aberturas)
        act.telaCheia(true, paisagem = false)
        player.setMediaItem(MediaItem.fromUri(mediaUri))
        player.prepare()
        val salvo = PosicaoPrefs.obter(ctx, chavePos, video.duracao)
        if (salvo != null && PosicaoPrefs.deveRetomar(salvo, video.duracao)) {
            player.seekTo(salvo)
        }
        player.play()
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(p: Boolean) {
                tocando = p
                if (!p) {
                    val d = player.duration.coerceAtLeast(0L)
                    PosicaoPrefs.salvar(ctx, chavePos, player.currentPosition, if (d > 0) d else video.duracao)
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) PosicaoPrefs.limpar(ctx, chavePos)
            }
            override fun onVideoSizeChanged(s: VideoSize) { vsize = s }
        }
        player.addListener(l)
        onDispose {
            val d = player.duration.coerceAtLeast(0L)
            PosicaoPrefs.salvar(ctx, chavePos, player.currentPosition, if (d > 0) d else video.duracao)
            player.removeListener(l)
            runCatching { player.stop() }
            runCatching { player.clearMediaItems() }
            runCatching { player.release() }
            act.sairTelaCheia()
        }
    }
    // Persiste a cada poucos segundos enquanto toca / em background compose.
    LaunchedEffect(video.uri) {
        while (true) {
            delay(3000)
            if (player.currentPosition > 0) {
                val d = player.duration.coerceAtLeast(0L)
                PosicaoPrefs.salvar(ctx, chavePos, player.currentPosition, if (d > 0) d else video.duracao)
            }
        }
    }
    LaunchedEffect(zoomAuto, zoomManual, video.uri) {
        if (!zoomAuto || zoomManual) return@LaunchedEffect
        var det: Detector? = null
        val historico = ArrayDeque<List<TamanhoLogica.CaixaParte>>(4)
        var ultimo: TamanhoLogica.ZoomAlvo? = null
        var semCorpo = 0
        try {
            while (true) {
                delay(700)
                if (!zoomAuto || zoomManual) break
                if (AnaliseWorker.rodando(ctx)) continue
                val tv = textureView ?: continue
                if (!tv.isAvailable) continue
                val vw = viewCw; val vh = viewCh
                if (vw < 8f || vh < 8f) continue
                val bmp = runCatching { tv.getBitmap(320, 320) }.getOrNull() ?: continue
                val partes = withContext(Dispatchers.Default) {
                    try {
                        if (det == null) det = Detector(ctx, false)
                        det!!.caixasCorpo(bmp, TamanhoLogica.LIMIAR)
                    } catch (_: Throwable) {
                        runCatching { det?.close() }; det = null
                        emptyList()
                    } finally {
                        if (!bmp.isRecycled) bmp.recycle()
                    }
                }
                if (partes.isNotEmpty()) {
                    historico.addLast(partes)
                    while (historico.size > 3) historico.removeFirst()
                    semCorpo = 0
                } else {
                    semCorpo++
                    if (semCorpo >= 4) {
                        // Nada por ~3s → volta suavemente a 1x
                        if (zoom > 1.05f) { zoom = 1f; pan = Offset.Zero; ultimo = TamanhoLogica.ZoomAlvo(1f, 0f, 0f) }
                        historico.clear()
                    }
                    continue
                }
                val uniao = TamanhoLogica.uniaoTemporal(historico.toList())
                if (TamanhoLogica.classesDistintas(uniao) < 2) continue
                val alvo = TamanhoLogica.enquadrarCorpoInteiro(uniao, vw, vh) ?: continue
                if (!TamanhoLogica.deveAtualizarAlvo(ultimo, alvo)) continue
                ultimo = alvo
                zoom = alvo.zoom
                pan = Offset(alvo.panX, alvo.panY)
            }
        } finally {
            runCatching { det?.close() }
        }
    }
    // Reaplica imersivo se o sistema devolver as barras
    LaunchedEffect(video.uri) {
        while (true) {
            delay(1500)
            act.telaCheia(true, paisagem = false)
        }
    }
    LaunchedEffect(Unit) { while (true) { pos = player.currentPosition; dur = player.duration.coerceAtLeast(0); delay(200) } }
    LaunchedEffect(controles, tocando) { if (controles && tocando) { delay(3500); controles = false } }
    LaunchedEffect(hud) { if (hud != null) { delay(900); hud = null } }
    fun aplicaVolume(n: Float) {
        nivelVol = n.coerceIn(0f, 2f)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, (Ganho.sistema(nivelVol) * maxVol).toInt(), 0)
        reforco.fator = Ganho.fator(nivelVol)
        hud = "Volume ${(nivelVol * 100).toInt()}%" + if (nivelVol > 1f) "  (+%.1f dB)".format(Ganho.db(nivelVol)) else ""
    }

    val infos by WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(AnaliseWorker.NOME).collectAsState(initial = emptyList())
    val info = infos.firstOrNull()
    val rodandoWm = info?.state == WorkInfo.State.RUNNING || info?.state == WorkInfo.State.ENQUEUED
    val uriInfo = info?.progress?.getString("uri") ?: info?.outputData?.getString("uri")
    val uriStr = video.uri.toString()
    val desteVideo = uriInfo == uriStr || (rodandoWm && uriInfo == null)
    // Fallback: AnaliseProgresso (prefs) — cobre gap antes do 1º setProgress do WorkManager.
    var progPrefs by remember(video.uri) { mutableFloatStateOf(AnaliseProgresso.get(ctx, uriStr) ?: 0f) }
    LaunchedEffect(video.uri, rodandoWm, info?.state) {
        while (true) {
            progPrefs = AnaliseProgresso.get(ctx, uriStr) ?: 0f
            val st = info?.state; if (!rodandoWm && (st == null || st.isFinished)) break
            delay(400)
        }
    }
    val rodando = (rodandoWm && desteVideo) || (progPrefs in 0.001f..0.999f)
    val progWm = info?.progress?.getFloat("p", 0f) ?: 0f
    val prog = maxOf(progWm, if (desteVideo || progPrefs > 0f) progPrefs else 0f)
    LaunchedEffect((prog * 20).toInt(), info?.state, rodando) {
        if ((desteVideo || rodando) && (rodando || info?.state == WorkInfo.State.SUCCEEDED || info?.state == WorkInfo.State.CANCELLED || info?.state == WorkInfo.State.FAILED || progPrefs >= 1f)) {
            val novo = withContext(Dispatchers.IO) { runCatching { Cache.carregar(ctx, chave) }.getOrNull() }
            if (novo != null) analise = novo
            if (info?.state == WorkInfo.State.SUCCEEDED || progPrefs >= 1f) {
                hap.performHapticFeedback(HapticFeedbackType.LongPress)
                if (progPrefs >= 1f) AnaliseProgresso.clear(ctx, uriStr)
            }
            if (info?.state == WorkInfo.State.FAILED) {
                val erro = info.outputData.getString("erro") ?: "erro"
                android.widget.Toast.makeText(ctx, "Não deu para analisar: $erro. Veja Ajustes › Ver último erro.", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        AnaliseWorker.iniciar(ctx, video.uri, player.currentPosition.coerceAtLeast(0L))
    }
    fun buscar() {
        hap.performHapticFeedback(HapticFeedbackType.LongPress)
        val start = player.currentPosition.coerceAtLeast(0L)
        try {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
            else AnaliseWorker.iniciar(ctx, video.uri, start)
        } catch (e: Throwable) {
            App.gravarErro(ctx, "iniciar análise", e)
            android.widget.Toast.makeText(ctx, "Não consegui iniciar: ${e.message ?: e.javaClass.simpleName}", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    fun pular(s: Segmento) { hap.performHapticFeedback(HapticFeedbackType.TextHandleMove); player.seekTo(Segmentador.alvoDoSalto(s, offsetMs)); player.play() }
    fun proxima() { Segmentador.proxima(segs, player.currentPosition, offsetMs)?.let { pular(it) } ?: run { hud = "Não há próxima cena" } }
    fun anterior() { Segmentador.anterior(segs, player.currentPosition, offsetMs)?.let { pular(it) } ?: run { hud = "Não há cena anterior" } }
    fun resetZoom() { zoom = 1f; pan = Offset.Zero }

    BackHandler { voltar() }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).clipToBounds()) {
        val d = LocalDensity.current
        val cw = with(d) { maxWidth.toPx() }; val ch = with(d) { maxHeight.toPx() }
        SideEffect { viewCw = cw; viewCh = ch }
        val vw = vsize.width * (if (vsize.pixelWidthHeightRatio > 0) vsize.pixelWidthHeightRatio else 1f); val vh = vsize.height.toFloat()
        val (w, h) = tamanhoVideo(modo, cw, ch, vw, vh)
        val wDp by animateDpAsState(with(d) { w.toDp() }, spring(stiffness = 500f), label = "w")
        val hDp by animateDpAsState(with(d) { h.toDp() }, spring(stiffness = 500f), label = "h")
        val videoMod = Modifier.align(Alignment.Center).requiredSize(wDp, hDp)
            .graphicsLayer { scaleX = zoomAnim; scaleY = zoomAnim; translationX = panAnim.x; translationY = panAnim.y }
        val videoMod2 = if (avs != null) {
            with(sts) { videoMod.sharedBounds(rememberSharedContentState("v-${video.uri}"), avs, resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds) }
        } else videoMod
        AndroidView({ TextureView(it).also { tv -> textureView = tv; player.setVideoTextureView(tv) } }, videoMod2)
        Box(Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { controles = !controles },
                    onDoubleTap = { o -> hap.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        if (o.x < size.width / 2) { player.seekBack(); hud = "−10 s" } else { player.seekForward(); hud = "+10 s" } },
                    onLongPress = { if (zoom != 1f) { resetZoom(); hud = "Zoom 100%" } })
            }
            .pointerInput(cw, ch) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var modoGesto = 0
                    val esquerda = down.position.x < size.width / 2
                    var acumulado = Offset.Zero
                    do {
                        val ev = awaitPointerEvent()
                        val pressionados = ev.changes.count { it.pressed }
                        if (pressionados >= 2 || modoGesto == 1) {
                            if (modoGesto != 1 && pressionados >= 2) modoGesto = 1
                            if (modoGesto == 1) {
                                val zf = ev.calculateZoom(); val pf = ev.calculatePan()
                                val nz = (zoom * zf).coerceIn(1f, 5f)
                                val mx = (nz - 1f) * cw / 2; val my = (nz - 1f) * ch / 2
                                zoom = nz; pan = Offset((pan.x + pf.x).coerceIn(-mx, mx), (pan.y + pf.y).coerceIn(-my, my))
                                if (zoomAuto) zoomManual = true
                                hud = "Zoom ${(nz * 100).toInt()}%"
                                ev.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } else if (pressionados == 1) {
                            val c = ev.changes.first(); val dlt = c.positionChange(); acumulado += dlt
                            if (modoGesto == 0 && acumulado.getDistance() > viewConfiguration.touchSlop) {
                                modoGesto = if (zoom > 1f) 1 else if (abs(acumulado.y) > abs(acumulado.x)) 2 else 3
                            }
                            if (modoGesto == 1) {
                                val mx = (zoom - 1f) * cw / 2; val my = (zoom - 1f) * ch / 2
                                pan = Offset((pan.x + dlt.x).coerceIn(-mx, mx), (pan.y + dlt.y).coerceIn(-my, my)); c.consume()
                            }
                            if (modoGesto == 2) {
                                val f = -dlt.y / (size.height * 0.8f)
                                if (esquerda) { brilho = (brilho + f).coerceIn(0.01f, 1f); act.brilho(brilho); hud = "Brilho ${(brilho * 100).toInt()}%" }
                                else aplicaVolume(nivelVol + f * 2f)
                                c.consume()
                            }
                        }
                    } while (ev.changes.any { it.pressed })
                }
            })

        AnimatedVisibility(hud != null, Modifier.align(Alignment.Center), enter = fadeIn() + scaleIn(spring(0.5f, 600f), 0.8f), exit = fadeOut()) {
            Text(hud ?: "", Modifier.vidro(RoundedCornerShape(16.dp), 0.6f).padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall, color = Color.White)
        }
        // —— TOPO: voltar + pills + números + ícone buscar ——
        AnimatedVisibility(controles, Modifier.align(Alignment.TopCenter).statusBarsPadding(),
            enter = fadeIn() + slideInVertically { -it / 2 }, exit = fadeOut() + slideOutVertically { -it / 2 }) {
            val cenaAtual = segs.indexOfFirst { pos in Segmentador.alvoDoSalto(it, offsetMs)..it.fimMs }.takeIf { it >= 0 }
            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).toque(onClick = voltar).vidro(RoundedCornerShape(12.dp), 0.5f), contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Categoria.entries.forEach { c ->
                            val n = segsTodas.count { it.categoria == c }
                            val sel = c == aba
                            Row(Modifier.toque { aba = c }.clip(RoundedCornerShape(50))
                                .then(if (sel) Modifier.background(Brush.horizontalGradient(listOf(Color(c.cor), Color(c.cor).copy(alpha = 0.65f))))
                                      else Modifier.background(Color(0x66000000)).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(50)))
                                .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(c.rotulo, style = MaterialTheme.typography.labelSmall, color = if (sel) Color(0xFF0B0A10) else Color.White, maxLines = 1)
                                if (n > 0) {
                                    Spacer(Modifier.width(5.dp))
                                    Box(Modifier.size(16.dp).clip(RoundedCornerShape(8.dp))
                                        .background(if (sel) Color(0x33000000) else Cores.Magenta), contentAlignment = Alignment.Center) {
                                        Text("$n", style = MaterialTheme.typography.labelSmall, color = if (sel) Color(0xFF0B0A10) else Color.White)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    Box {
                        Box(Modifier.size(36.dp).toque { menuPlayer = true }.vidro(RoundedCornerShape(12.dp), 0.5f), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.MoreVert, "Menu", tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                        DropdownMenu(menuPlayer, { menuPlayer = false }) {
                            DropdownMenuItem(
                                text = { Text(if (telaOff) "Tela desligada: ligado" else "Tocar com tela desligada") },
                                onClick = {
                                    telaOff = !telaOff
                                    prefs.telaDesligada = telaOff
                                    hud = if (telaOff) "Tela desligada: áudio continua" else "Tela permanece ligada"
                                    menuPlayer = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(if (zoomAuto) "Zoom automático: ligado" else "Zoom automático") },
                                onClick = {
                                    zoomAuto = !zoomAuto
                                    prefs.zoomAuto = zoomAuto
                                    zoomManual = false
                                    if (!zoomAuto) resetZoom()
                                    hud = if (zoomAuto) "Zoom automático ligado" else "Zoom automático desligado"
                                    menuPlayer = false
                                },
                            )
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    // Botão Analisar: pill grande se ainda não há análise; % + parar se rodando; ícone se já analisou.
                    val temAnalise = analise != null && !(analise!!.first.parcial && segsTodas.isEmpty())
                    val semResultados = segsTodas.isEmpty()
                    when {
                        rodando && desteVideo -> {
                            Row(Modifier.toque { AnaliseWorker.cancelar(ctx) }
                                .clip(RoundedCornerShape(50)).background(Cores.GradienteH)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("${(prog * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = Color.White)
                                Spacer(Modifier.width(6.dp))
                                Icon(Icons.Default.Close, "Parar", tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                        }
                        !temAnalise || semResultados -> {
                            Row(Modifier.toque { buscar() }
                                .clip(RoundedCornerShape(50)).background(Cores.GradienteH)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Analisar vídeo", style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1)
                            }
                        }
                        else -> {
                            Box(Modifier.size(36.dp).toque { buscar() }
                                .clip(RoundedCornerShape(12.dp)).background(Cores.GradienteH), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.AutoAwesome, "Analisar de novo", tint = Color.White, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
                if (rodando && desteVideo) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { prog.coerceIn(0f, 1f) }, Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                        color = Cores.Magenta, trackColor = Color(0x33FFFFFF))
                }
                if (!rodando && segsTodas.isEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("Toque em Analisar vídeo para encontrar cenas", style = MaterialTheme.typography.labelSmall, color = Color(0xCCFFFFFF))
                }
                AnimatedContent(aba to segs.size, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "nums") { (_, _) ->
                    if (segs.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            segs.forEachIndexed { i, s ->
                                val on = cenaAtual == i
                                Box(Modifier.size(28.dp).toque { pular(s) }.clip(RoundedCornerShape(50))
                                    .then(if (on) Modifier.background(Cores.GradienteH) else Modifier.background(Color(0x66000000)).border(1.dp, Color(0x44FFFFFF), RoundedCornerShape(50))),
                                    contentAlignment = Alignment.Center) {
                                    Text("${i + 1}", style = MaterialTheme.typography.labelSmall, color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }

        // —— BASE: seek + controles ——
        AnimatedVisibility(controles, Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
            enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                BarraBusca(pos, dur, segs) { player.seekTo(it); pos = it }
                Row(Modifier.fillMaxWidth().vidro(RoundedCornerShape(22.dp), 0.45f).padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    @Composable fun B(ic: androidx.compose.ui.graphics.vector.ImageVector, d: String, en: Boolean = true, f: () -> Unit) =
                        Box(Modifier.size(40.dp).toque(en, f), contentAlignment = Alignment.Center) { Icon(ic, d, tint = if (en) Color.White else Color(0x55FFFFFF), modifier = Modifier.size(22.dp)) }
                    B(Icons.Default.SkipPrevious, "Anterior", segs.isNotEmpty()) { anterior() }
                    B(Icons.Default.Replay10, "−10s") { player.seekBack() }
                    Box(Modifier.size(46.dp).toque { if (player.isPlaying) player.pause() else player.play() }.clip(RoundedCornerShape(50)).background(Cores.Gradiente), contentAlignment = Alignment.Center) {
                        Crossfade(tocando, label = "pp") { Icon(if (it) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White) }
                    }
                    B(Icons.Default.Forward10, "+10s") { player.seekForward() }
                    B(Icons.Default.SkipNext, "Próxima", segs.isNotEmpty()) { proxima() }
                    Text("${fmt(pos)} / ${fmt(dur)}", Modifier.weight(1f).padding(start = 4.dp), color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    Text("${velocidade}x", Modifier.toque {
                        val l = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f); velocidade = l[(l.indexOf(velocidade) + 1) % l.size]; player.setPlaybackSpeed(velocidade)
                    }.padding(6.dp), color = Color.White, style = MaterialTheme.typography.labelMedium)
                    Text(modo.rotulo, Modifier.toque { modo = Modo.entries[(modo.ordinal + 1) % Modo.entries.size]; hud = modo.rotulo }.padding(6.dp),
                        color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
fun BarraBusca(pos: Long, dur: Long, segs: List<Segmento>, onSeek: (Long) -> Unit) {
    var arrastando by remember { mutableStateOf<Float?>(null) }
    val frac = arrastando ?: if (dur > 0) pos.toFloat() / dur else 0f
    val alfas = remember { mutableStateMapOf<String, Animatable<Float, AnimationVector1D>>() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(segs) { segs.forEach { s -> val k = "${s.categoria}-${s.inicioMs}"
        if (k !in alfas) { val a = Animatable(0f); alfas[k] = a; scope.launch { a.animateTo(1f, tween(600)) } } } }
    Box(Modifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().height(5.dp).padding(horizontal = 10.dp)) {
            drawRoundRect(Color(0x33FFFFFF), cornerRadius = CornerRadius(3.dp.toPx()))
            drawRoundRect(Cores.GradienteH, size = GSize(size.width * frac.coerceIn(0f, 1f), size.height), cornerRadius = CornerRadius(3.dp.toPx()), alpha = 0.55f)
            if (dur > 0) segs.forEach { s ->
                val a = alfas["${s.categoria}-${s.inicioMs}"]?.value ?: 0f
                val x0 = size.width * s.inicioMs / dur; val x1 = maxOf(x0 + 3.dp.toPx(), size.width * s.fimMs / dur)
                drawRoundRect(Color(s.categoria.cor), Offset(x0, -2.dp.toPx() * a), GSize(x1 - x0, size.height + 4.dp.toPx() * a), CornerRadius(2.dp.toPx()), alpha = a)
            }
        }
        Slider(frac, { arrastando = it }, onValueChangeFinished = { arrastando?.let { onSeek((it * dur).toLong()) }; arrastando = null },
            colors = SliderDefaults.colors(activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent, thumbColor = Color.White))
    }
}
