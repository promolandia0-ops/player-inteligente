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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlin.math.abs

data class Video(val uri: Uri, val nome: String, val duracao: Long, val tamanho: Long)
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
                        AnimatedContent(nav.value, transitionSpec = {
                            (fadeIn(tween(320)) + scaleIn(spring(0.8f, 300f), 0.96f)) togetherWith fadeOut(tween(220))
                        }, label = "nav", contentKey = { if (it.ajustes) "a" else it.video?.uri?.toString() ?: "b" }) { n ->
                            when {
                                n.ajustes -> { BackHandler { nav.value = Nav() }; Ajustes { nav.value = Nav() } }
                                n.video != null -> { BackHandler { nav.value = Nav(); sairTelaCheia() }
                                    PlayerTela(n.video, this@MainActivity, this@SharedTransitionLayout, this@AnimatedContent) { nav.value = Nav(); sairTelaCheia() } }
                                else -> Biblioteca(this@SharedTransitionLayout, this@AnimatedContent, onAbrir = { nav.value = Nav(it) },
                                    onAjustes = { nav.value = Nav(ajustes = true) }, videoDeUri = ::videoDeUri)
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

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Biblioteca(sts: SharedTransitionScope, avs: AnimatedVisibilityScope, onAbrir: (Video) -> Unit, onAjustes: () -> Unit, videoDeUri: (Uri) -> Video) {
    val ctx = LocalContext.current
    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
    var temPerm by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED) }
    val pedir = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { temPerm = it }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) { runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }; onAbrir(videoDeUri(u)) } }
    var videos by remember { mutableStateOf<List<Video>?>(null) }
    var busca by remember { mutableStateOf("") }
    LaunchedEffect(temPerm) {
        if (!temPerm) { pedir.launch(perm); return@LaunchedEffect }
        videos = withContext(Dispatchers.IO) {
            val l = ArrayList<Video>()
            ctx.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE),
                null, null, "${MediaStore.Video.Media.DATE_MODIFIED} DESC")?.use { c ->
                while (c.moveToNext()) l.add(Video(ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0)),
                    c.getString(1) ?: "?", c.getLong(2), c.getLong(3)))
            }
            l
        }
    }
    val lista = videos?.filter { busca.isBlank() || it.nome.contains(busca, true) } ?: emptyList()
    Box(Modifier.fillMaxSize()) {
        // brilho de fundo
        Box(Modifier.size(420.dp).offset((-120).dp, (-160).dp).background(Brush.radialGradient(listOf(Cores.Violeta.copy(alpha = 0.35f), Color.Transparent))))
        Box(Modifier.size(360.dp).align(Alignment.TopEnd).offset(120.dp, (-60).dp).background(Brush.radialGradient(listOf(Cores.Magenta.copy(alpha = 0.22f), Color.Transparent))))
        LazyVerticalStaggeredGrid(StaggeredGridCells.Fixed(2), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 0.dp, bottom = 120.dp),
            verticalItemSpacing = 12.dp, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            item(span = StaggeredGridItemSpan.FullLine) {
                Column(Modifier.statusBarsPadding().padding(top = 18.dp, bottom = 6.dp, start = 4.dp, end = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Player", style = MaterialTheme.typography.displaySmall, color = Cores.Texto)
                            Text("Inteligente", style = MaterialTheme.typography.displaySmall.merge(TextStyle(brush = Cores.GradienteH)))
                        }
                        Box(Modifier.size(48.dp).toque(onClick = onAjustes).vidro(RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Tune, "Ajustes", tint = Cores.Texto) }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth().vidro(RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Search, null, tint = Cores.Suave)
                        TextField(busca, { busca = it }, Modifier.weight(1f), placeholder = { Text("Pesquisar vídeos", color = Cores.Suave) }, singleLine = true,
                            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = Cores.Magenta))
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(when { !temPerm -> "Sem acesso à galeria"; videos == null -> "Carregando…"; else -> "${lista.size} vídeos" },
                        style = MaterialTheme.typography.labelMedium, color = Cores.Suave, modifier = Modifier.padding(start = 4.dp))
                    if (!temPerm) { Spacer(Modifier.height(12.dp)); BotaoGradiente("Permitir acesso aos vídeos") { pedir.launch(perm) } }
                }
            }
            items(lista.size, key = { lista[it].uri.toString() }) { i ->
                val v = lista[i]
                val alt = if (i % 3 == 0) 0.72f else if (i % 3 == 1) 1.25f else 0.95f
                with(sts) {
                    Box(Modifier.fillMaxWidth().aspectRatio(alt).toque { onAbrir(v) }
                        .sharedBounds(rememberSharedContentState("v-${v.uri}"), avs, resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds)
                        .clip(RoundedCornerShape(24.dp))) {
                        Thumb(v.uri, Modifier.fillMaxSize())
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xE6000000))))
                        Text(fmt(v.duracao), Modifier.align(Alignment.TopEnd).padding(10.dp).vidro(RoundedCornerShape(10.dp), 0.45f).padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall, color = Color.White)
                        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                            Text(v.nome, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("%.0f MB".format(v.tamanho / 1048576.0), style = MaterialTheme.typography.labelSmall, color = Color(0xB3FFFFFF))
                        }
                    }
                }
            }
        }
        BotaoGradiente("Abrir arquivo", Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 20.dp),
            icone = { Icon(Icons.Default.FolderOpen, null, tint = Color.White) }) { picker.launch(arrayOf("video/*")) }
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
            Switch(nn, { nn = it; p.acelerador = it }, colors = SwitchDefaults.colors(checkedTrackColor = Cores.Magenta, checkedThumbColor = Color.White)) } }
        BotaoVidro("Apagar análises salvas", icone = { Icon(Icons.Default.DeleteSweep, null, tint = Cores.Texto) }) {
            Cache.limpar(ctx); android.widget.Toast.makeText(ctx, "Análises apagadas", android.widget.Toast.LENGTH_SHORT).show() }
        Text("100% offline: o app não tem permissão de Internet.\nModelo: NudeNet 320n (detector por parte do corpo) via ONNX Runtime Mobile.",
            style = MaterialTheme.typography.bodySmall, color = Cores.Suave)
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PlayerTela(video: Video, act: MainActivity, sts: SharedTransitionScope, avs: AnimatedVisibilityScope, voltar: () -> Unit) {
    val ctx = LocalContext.current
    val hap = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
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

    val chave = remember(video.uri) { Cache.chave(ctx, video.uri) }
    var analise by remember(video.uri) { mutableStateOf(Cache.carregar(ctx, chave)) }
    var cats by remember { mutableStateOf(prefs.cats) }
    val offsetMs = prefs.offsetS * 1000L
    val segs = remember(analise, cats) { analise?.let { (a, iv) -> Segmentador.segmentos(a, cats, prefs.limiar, iv) } ?: emptyList() }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var tocando by remember { mutableStateOf(false) }
    var velocidade by remember { mutableFloatStateOf(1f) }
    var cheia by remember { mutableStateOf(false) }
    var controles by remember { mutableStateOf(true) }
    var vsize by remember { mutableStateOf(VideoSize.UNKNOWN) }
    var modo by remember(video.uri) { mutableStateOf(Modo.AUTO) }
    var zoom by remember(video.uri) { mutableFloatStateOf(1f) }
    var pan by remember(video.uri) { mutableStateOf(Offset.Zero) }
    val zoomAnim by animateFloatAsState(zoom, spring(stiffness = 900f), label = "z")
    val panAnim by animateOffsetAsState(pan, spring(stiffness = 900f), label = "pn")

    DisposableEffect(video.uri) {
        player.setMediaItem(MediaItem.fromUri(video.uri)); player.prepare(); player.play()
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(p: Boolean) { tocando = p }
            override fun onVideoSizeChanged(s: VideoSize) { vsize = s }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
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
    val rodando = info?.state == WorkInfo.State.RUNNING || info?.state == WorkInfo.State.ENQUEUED
    val uriInfo = info?.progress?.getString("uri") ?: info?.outputData?.getString("uri")
    val desteVideo = uriInfo == video.uri.toString() || (rodando && uriInfo == null)
    val prog = info?.progress?.getFloat("p", 0f) ?: 0f
    // recarrega resultados parciais enquanto analisa => marcas aparecem ao vivo
    LaunchedEffect((prog * 20).toInt(), info?.state) {
        if (desteVideo && (rodando || info?.state == WorkInfo.State.SUCCEEDED || info?.state == WorkInfo.State.CANCELLED)) {
            val novo = withContext(Dispatchers.IO) { Cache.carregar(ctx, chave) }
            if (novo != null) analise = novo
            if (info?.state == WorkInfo.State.SUCCEEDED) hap.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { AnaliseWorker.iniciar(ctx, video.uri) }
    fun buscar() { hap.performHapticFeedback(HapticFeedbackType.LongPress)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) else AnaliseWorker.iniciar(ctx, video.uri) }
    fun pular(s: Segmento) { hap.performHapticFeedback(HapticFeedbackType.TextHandleMove); player.seekTo(Segmentador.alvoDoSalto(s, offsetMs)); player.play() }
    fun proxima() { Segmentador.proxima(segs, player.currentPosition, offsetMs)?.let { pular(it) } ?: run { hud = "Não há próxima cena" } }
    fun anterior() { Segmentador.anterior(segs, player.currentPosition, offsetMs)?.let { pular(it) } ?: run { hud = "Não há cena anterior" } }
    fun resetZoom() { zoom = 1f; pan = Offset.Zero }

    val areaVideo = @Composable { mod: Modifier ->
        BoxWithConstraints(mod.background(Color.Black).clipToBounds()) {
            val d = LocalDensity.current
            val cw = with(d) { maxWidth.toPx() }; val ch = with(d) { maxHeight.toPx() }
            val vw = vsize.width * (if (vsize.pixelWidthHeightRatio > 0) vsize.pixelWidthHeightRatio else 1f); val vh = vsize.height.toFloat()
            val (w, h) = tamanhoVideo(modo, cw, ch, vw, vh)
            val wDp by animateDpAsState(with(d) { w.toDp() }, spring(stiffness = 500f), label = "w")
            val hDp by animateDpAsState(with(d) { h.toDp() }, spring(stiffness = 500f), label = "h")
            AndroidView({ TextureView(it).also { tv -> player.setVideoTextureView(tv) } },
                Modifier.align(Alignment.Center).requiredSize(wDp, hDp).graphicsLayer {
                    scaleX = zoomAnim; scaleY = zoomAnim; translationX = panAnim.x; translationY = panAnim.y })
            // gestos: pinça/arrasto p/ zoom e pan; arrasto vertical = brilho (esq.) / volume (dir.)
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
                        var modoGesto = 0 // 0 indefinido, 1 transformar, 2 vertical
                        val esquerda = down.position.x < size.width / 2
                        var acumulado = Offset.Zero
                        do {
                            val ev = awaitPointerEvent()
                            val pressionados = ev.changes.count { it.pressed }
                            if (pressionados >= 2 || (modoGesto == 1)) {
                                if (modoGesto != 1 && pressionados >= 2) modoGesto = 1
                                if (modoGesto == 1) {
                                    val zf = ev.calculateZoom(); val pf = ev.calculatePan()
                                    val nz = (zoom * zf).coerceIn(1f, 5f)
                                    val mx = (nz - 1f) * cw / 2; val my = (nz - 1f) * ch / 2
                                    zoom = nz; pan = Offset((pan.x + pf.x).coerceIn(-mx, mx), (pan.y + pf.y).coerceIn(-my, my))
                                    hud = "Zoom ${(nz * 100).toInt()}%"
                                    ev.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            } else if (pressionados == 1) {
                                val c = ev.changes.first(); val dlt = c.positionChange(); acumulado += dlt
                                if (modoGesto == 0 && acumulado.getDistance() > viewConfiguration.touchSlop) {
                                    modoGesto = if (zoom > 1f) 1 else if (abs(acumulado.y) > abs(acumulado.x)) 2 else 3
                                }
                                if (modoGesto == 1) { val mx = (zoom - 1f) * cw / 2; val my = (zoom - 1f) * ch / 2
                                    pan = Offset((pan.x + dlt.x).coerceIn(-mx, mx), (pan.y + dlt.y).coerceIn(-my, my)); c.consume() }
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
            AnimatedVisibility(zoom > 1.01f, Modifier.align(Alignment.TopEnd).padding(12.dp), enter = fadeIn() + slideInHorizontally { it }, exit = fadeOut()) {
                Row(Modifier.toque { resetZoom() }.vidro(RoundedCornerShape(50), 0.6f).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ZoomOutMap, null, tint = Color.White, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                    Text("${(zoomAnim * 100).toInt()}% · Redefinir", style = MaterialTheme.typography.labelMedium, color = Color.White)
                }
            }
            AnimatedVisibility(controles, Modifier.align(Alignment.BottomCenter), enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
                Column(Modifier.fillMaxWidth().padding(10.dp).vidro(RoundedCornerShape(24.dp), 0.5f).padding(horizontal = 8.dp, vertical = 4.dp)) {
                    BarraBusca(pos, dur, segs) { player.seekTo(it); pos = it }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        @Composable fun B(ic: androidx.compose.ui.graphics.vector.ImageVector, d: String, en: Boolean = true, f: () -> Unit) =
                            Box(Modifier.size(40.dp).toque(en, f), contentAlignment = Alignment.Center) { Icon(ic, d, tint = if (en) Color.White else Color(0x55FFFFFF)) }
                        B(Icons.Default.SkipPrevious, "Cena anterior", segs.isNotEmpty()) { anterior() }
                        B(Icons.Default.Replay10, "Voltar 10s") { player.seekBack() }
                        Box(Modifier.size(46.dp).toque { if (player.isPlaying) player.pause() else player.play() }.clip(RoundedCornerShape(50)).background(Cores.Gradiente), contentAlignment = Alignment.Center) {
                            Crossfade(tocando, label = "pp") { Icon(if (it) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pausa", tint = Color.White) } }
                        B(Icons.Default.Forward10, "Avançar 10s") { player.seekForward() }
                        B(Icons.Default.SkipNext, "Próxima cena", segs.isNotEmpty()) { proxima() }
                        Text("${fmt(pos)} / ${fmt(dur)}", Modifier.weight(1f).padding(start = 4.dp), color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        Text("${velocidade}x", Modifier.toque { val l = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f); velocidade = l[(l.indexOf(velocidade) + 1) % l.size]; player.setPlaybackSpeed(velocidade) }.padding(6.dp),
                            color = Color.White, style = MaterialTheme.typography.labelMedium)
                        Text(modo.rotulo, Modifier.toque { modo = Modo.entries[(modo.ordinal + 1) % Modo.entries.size]; hud = modo.rotulo }.padding(6.dp),
                            color = Color.White, style = MaterialTheme.typography.labelMedium)
                        B(if (cheia) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, "Tela cheia") {
                            cheia = !cheia; act.telaCheia(cheia, paisagem = vsize.width >= vsize.height) }
                    }
                }
            }
        }
    }

    if (cheia) { BackHandler { cheia = false; act.telaCheia(false) }; areaVideo(Modifier.fillMaxSize()); return }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).toque(onClick = voltar).vidro(RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", tint = Cores.Texto) }
            Spacer(Modifier.width(12.dp))
            Text(video.nome, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        with(sts) {
            areaVideo(Modifier.padding(horizontal = 10.dp).fillMaxWidth().aspectRatio(16f / 9f)
                .sharedBounds(rememberSharedContentState("v-${video.uri}"), avs, resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds)
                .clip(RoundedCornerShape(22.dp)))
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text("O que buscar", style = MaterialTheme.typography.labelMedium, color = Cores.Suave)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Categoria.entries.forEach { c -> ChipCategoria(c, c in cats) { cats = if (c in cats) cats - c else cats + c; prefs.cats = cats } }
            }
            Spacer(Modifier.height(14.dp))
            AnimatedContent(rodando && desteVideo, transitionSpec = { (fadeIn() + scaleIn(spring(0.6f, 400f), 0.9f)) togetherWith fadeOut() }, label = "b") { anal ->
                if (anal) Row(Modifier.fillMaxWidth().vidro().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    AnelProgresso(prog, 58.dp); Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Buscando cenas…", style = MaterialTheme.typography.titleSmall)
                        Text(info?.progress?.getString("txt") ?: "Preparando", style = MaterialTheme.typography.labelSmall, color = Cores.Suave)
                        Text("${segs.size} cena(s) até agora", style = MaterialTheme.typography.labelSmall, color = Cores.Magenta)
                    }
                    BotaoVidro("Cancelar") { AnaliseWorker.cancelar(ctx) }
                } else Row(verticalAlignment = Alignment.CenterVertically) {
                    BotaoGradiente(if (analise == null) "Buscar" else "Buscar de novo", enabled = cats.isNotEmpty() && !rodando,
                        icone = { Icon(Icons.Default.AutoAwesome, null, tint = Color.White) }) { buscar() }
                    Spacer(Modifier.width(14.dp))
                    Text(when { rodando -> "Outra análise em andamento"; analise == null -> "Nada analisado ainda"
                        analise!!.first.parcial -> "${segs.size} cena(s) · análise parcial"; else -> "${segs.size} cena(s) encontradas" },
                        style = MaterialTheme.typography.bodySmall, color = Cores.Suave)
                }
            }
            if (info?.state == WorkInfo.State.FAILED && desteVideo) info.outputData.getString("erro")?.let { Text("Erro: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        AnimatedVisibility(segs.isNotEmpty()) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BotaoVidro("Cena anterior", Modifier.weight(1f), icone = { Icon(Icons.Default.SkipPrevious, null, tint = Cores.Texto) }) { anterior() }
                BotaoGradiente("Próxima cena", Modifier.weight(1f), icone = { Icon(Icons.Default.SkipNext, null, tint = Color.White) }) { proxima() }
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 14.dp, 16.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(segs, key = { "${it.categoria}-${it.inicioMs}" }) { s ->
                val atual = pos in Segmentador.alvoDoSalto(s, offsetMs)..s.fimMs
                Row(Modifier.animateItem().fillMaxWidth().toque { pular(s) }.vidro(RoundedCornerShape(20.dp), if (atual) 0.9f else 0.5f)
                    .then(if (atual) Modifier.border(1.5.dp, Cores.GradienteH, RoundedCornerShape(20.dp)) else Modifier).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Thumb(video.uri, Modifier.size(120.dp, 68.dp).clip(RoundedCornerShape(14.dp)), s.inicioMs + 500, 320, 180)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(9.dp).clip(RoundedCornerShape(5.dp)).background(Color(s.categoria.cor))); Spacer(Modifier.width(7.dp))
                            Text(s.categoria.rotulo, style = MaterialTheme.typography.titleSmall) }
                        Text("${fmt(s.inicioMs)} – ${fmt(s.fimMs)}", style = MaterialTheme.typography.bodySmall, color = Cores.Texto)
                        Text("confiança ${"%.0f".format(s.pico * 100)}%", style = MaterialTheme.typography.labelSmall, color = Cores.Suave)
                    }
                    Icon(Icons.Default.PlayCircle, null, tint = Cores.Magenta, modifier = Modifier.size(30.dp))
                }
            }
        }
    }
}

@Composable
fun BarraBusca(pos: Long, dur: Long, segs: List<Segmento>, onSeek: (Long) -> Unit) {
    var arrastando by remember { mutableStateOf<Float?>(null) }
    val frac = arrastando ?: if (dur > 0) pos.toFloat() / dur else 0f
    // marcas aparecem com fade quando novas cenas são encontradas
    val alfas = remember { mutableStateMapOf<String, Animatable<Float, AnimationVector1D>>() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(segs) { segs.forEach { s -> val k = "${s.categoria}-${s.inicioMs}"
        if (k !in alfas) { val a = Animatable(0f); alfas[k] = a; scope.launch { a.animateTo(1f, tween(600)) } } } }
    Box(Modifier.fillMaxWidth().height(34.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().height(6.dp).padding(horizontal = 10.dp)) {
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
