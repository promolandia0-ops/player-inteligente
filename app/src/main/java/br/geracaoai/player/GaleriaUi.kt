package br.geracaoai.player

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AbaGaleria(val rotulo: String, val icone: ImageVector) {
    VIDEOS("Vídeos", Icons.Default.PlayCircle),
    ALBUNS("Álbuns", Icons.Default.PhotoAlbum),
    INTELIGENTE("Inteligente", Icons.Default.AutoAwesome),
    AJUSTES("Ajustes", Icons.Default.Tune),
}

/** Pilha de 1 nível entre abas: Back volta à anterior; o próximo Back cai em Álbuns. */
object NavAbas {
    val HOME = AbaGaleria.ALBUNS.name
    data class Snap(val aba: String, val anterior: String)

    fun aoTrocar(atual: String, nova: String, anterior: String): Snap {
        if (nova == atual) return Snap(atual, anterior)
        return Snap(nova, atual)
    }

    fun aoVoltar(atual: String, anterior: String): Snap {
        if (atual == HOME) return Snap(HOME, HOME)
        val dest = if (anterior.isBlank() || anterior == atual || !ehAba(anterior)) HOME else anterior
        return Snap(dest, HOME)
    }

    fun ehAba(nome: String): Boolean = runCatching { AbaGaleria.valueOf(nome) }.isSuccess

    /** Ids antigos de Por tamanho (v1.3.8) — ignorar no restore. */
    fun pastaIdRemovido(id: String): Boolean =
        id.startsWith("__peit") || id.startsWith("__rab") || id.startsWith("__bucet")
}

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class)
@Composable
fun Biblioteca(
    sts: SharedTransitionScope,
    avs: AnimatedVisibilityScope,
    onAbrir: (Video) -> Unit,
    onAjustes: () -> Unit, // reservado (ajustes agora é aba local)
    videoDeUri: (Uri) -> Video,
    ativa: Boolean = true,
) {
    val ctx = LocalContext.current
    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
    var temPerm by remember { mutableStateOf(VideosMedia.temAlgumAcesso(ctx)) }
    var acessoParcial by remember { mutableStateOf(VideosMedia.temAcessoParcial(ctx)) }
    val pedir = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        temPerm = VideosMedia.temAlgumAcesso(ctx)
        acessoParcial = VideosMedia.temAcessoParcial(ctx)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            onAbrir(videoDeUri(u))
        }
    }

    var videos by remember { mutableStateOf<List<Video>?>(null) }
    var orgMap by remember { mutableStateOf<Map<String, ResultadoOrg>>(emptyMap()) }
    var embMap by remember { mutableStateOf<Map<String, FloatArray>>(emptyMap()) }
    var analiseProg by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    val db = remember { GaleriaDb(ctx) }
    var orgTick by remember { mutableIntStateOf(0) }

    var aba by rememberSaveable { mutableStateOf(AbaGaleria.ALBUNS.name) }
    var abaAnterior by rememberSaveable { mutableStateOf(AbaGaleria.ALBUNS.name) }
    val abaAtual = runCatching { AbaGaleria.valueOf(aba) }.getOrDefault(AbaGaleria.ALBUNS)
    var pastaId by rememberSaveable { mutableStateOf<String?>(null) }
    var busca by rememberSaveable { mutableStateOf("") }
    var ordem by rememberSaveable { mutableStateOf(OrdemPrefs.get(ctx, "__videos", Ordenacao.MAIS_NOVOS).name) }
    var menuOrdem by remember { mutableStateOf(false) }
    var colsVideos by rememberSaveable { mutableIntStateOf(4) }
    var colsAlbuns by rememberSaveable { mutableIntStateOf(3) }

    // Scroll de cada aba / pasta — sobrevive a player e troca de pasta.
    val scrollVideos = rememberLazyGridState()
    val scrollAlbuns = rememberLazyGridState()
    val scrollIntel = rememberLazyGridState()
    val scrollPasta = rememberLazyGridState()
    val escopo = rememberCoroutineScope()
    var pastaScrollIx by rememberSaveable { mutableIntStateOf(0) }
    var pastaScrollOff by rememberSaveable { mutableIntStateOf(0) }
    var videosScrollIx by rememberSaveable { mutableIntStateOf(0) }
    var videosScrollOff by rememberSaveable { mutableIntStateOf(0) }
    var albunsScrollIx by rememberSaveable { mutableIntStateOf(0) }
    var albunsScrollOff by rememberSaveable { mutableIntStateOf(0) }
    var intelScrollIx by rememberSaveable { mutableIntStateOf(0) }
    var intelScrollOff by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        if (videosScrollIx > 0 || videosScrollOff > 0) scrollVideos.scrollToItem(videosScrollIx, videosScrollOff)
        if (albunsScrollIx > 0 || albunsScrollOff > 0) scrollAlbuns.scrollToItem(albunsScrollIx, albunsScrollOff)
        if (intelScrollIx > 0 || intelScrollOff > 0) scrollIntel.scrollToItem(intelScrollIx, intelScrollOff)
        if (pastaScrollIx > 0 || pastaScrollOff > 0) scrollPasta.scrollToItem(pastaScrollIx, pastaScrollOff)
    }
    fun guardarScrolls() {
        videosScrollIx = scrollVideos.firstVisibleItemIndex; videosScrollOff = scrollVideos.firstVisibleItemScrollOffset
        albunsScrollIx = scrollAlbuns.firstVisibleItemIndex; albunsScrollOff = scrollAlbuns.firstVisibleItemScrollOffset
        intelScrollIx = scrollIntel.firstVisibleItemIndex; intelScrollOff = scrollIntel.firstVisibleItemScrollOffset
        pastaScrollIx = scrollPasta.firstVisibleItemIndex; pastaScrollOff = scrollPasta.firstVisibleItemScrollOffset
    }
    DisposableEffect(Unit) { onDispose { guardarScrolls() } }
    LaunchedEffect(scrollVideos.isScrollInProgress, scrollAlbuns.isScrollInProgress, scrollIntel.isScrollInProgress, scrollPasta.isScrollInProgress) {
        if (!scrollVideos.isScrollInProgress && !scrollAlbuns.isScrollInProgress && !scrollIntel.isScrollInProgress && !scrollPasta.isScrollInProgress) guardarScrolls()
    }

    var recupMsg by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(temPerm, orgTick) {
        if (!temPerm) { pedir.launch(perm); return@LaunchedEffect }
        val packed = withContext(Dispatchers.IO) {
            val lista = VideosMedia.listar(ctx)
            val r = OrgRecuperacao.rodarSePreciso(ctx, lista)
            lista to (db.todosPorChaveEstavel() to (db.embeddings() to r))
        }
        val vids = packed.first
        val mapa = packed.second.first
        val emb = packed.second.second.first
        val rel = packed.second.second.second
        videos = vids; orgMap = mapa; embMap = emb
        analiseProg = AnaliseProgresso.todosAtivos(ctx)
        acessoParcial = VideosMedia.temAcessoParcial(ctx)
        if (rel.casadosComGaleria > 0 || rel.urisRenomeadas > 0 || rel.putariaProvavelmenteApagada) {
            recupMsg = buildString {
                append("Recuperados ${rel.casadosComGaleria} de ${rel.totalVideos}")
                if (rel.limpoNoDb > 0) append(" · Sem nudez ${rel.limpoNoDb}")
                if (rel.nudeNoDb > 0) append(" · Putaria ${rel.nudeNoDb}")
                if (rel.putariaProvavelmenteApagada && rel.nudeNoDb == 0)
                    append(" · Putaria da v1.3.0 não pôde ser restaurada (bug apagou essas linhas); reorganize só o que faltar")
            }
        }
    }

    val orgInfos by WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(OrganizarWorker.NOME).collectAsState(initial = emptyList())
    val orgInfo = orgInfos.firstOrNull()
    val orgPrefsTick = remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(800); orgPrefsTick.intValue++; analiseProg = AnaliseProgresso.todosAtivos(ctx) } }
    val orgSnap = remember(orgPrefsTick.intValue, orgInfo?.state, orgInfo?.progress) {
        val op = OrgPrefs(ctx)
        val feitos = orgInfo?.progress?.getInt("feitos", op.feitos) ?: op.feitos
        val total = (orgInfo?.progress?.getInt("total", op.total) ?: op.total).coerceAtLeast(1)
        val txt = orgInfo?.progress?.getString("txt") ?: op.txt
        val frac = orgInfo?.progress?.getFloat("p", if (total > 0) feitos.toFloat() / total else 0f)
            ?: (if (total > 0) feitos.toFloat() / total else 0f)
        val pausado = op.pausado && op.ativo
        val rodando = op.ativo && !op.pausado
        Quint(rodando, pausado, frac, txt.ifBlank { "$feitos de $total" }, feitos to total)
    }
    val orgRodando = orgSnap.a && !orgSnap.b
    val orgPausado = orgSnap.b
    val orgProg = orgSnap.c
    val orgTxt = orgSnap.d
    LaunchedEffect(orgPrefsTick.intValue, orgInfo?.state) {
        val (mapa, emb) = withContext(Dispatchers.IO) { db.todosPorChaveEstavel() to db.embeddings() }
        orgMap = mapa; embMap = emb
    }

    val pastas = remember(videos) { videos?.let { pastasDe(it) } ?: emptyList() }
    val albumsVirtuais = remember(videos, orgMap) {
        val vs = videos ?: return@remember emptyList()
        if (orgMap.isEmpty()) return@remember emptyList()
        fun res(v: Video) = orgMap[VideosMedia.chaveEstavel(v.uri.toString())] ?: orgMap[v.uri.toString()]
        val nude = vs.filter { res(it)?.classif == ClassifNudez.NUDE }
        val limpo = vs.filter { res(it)?.classif == ClassifNudez.LIMPO }
        val pend = vs.filter {
            val c = res(it)?.classif
            c == null || c == ClassifNudez.PENDENTE
        }
        buildList {
            if (nude.isNotEmpty()) add(Pasta("__putaria", "Putaria", nude))
            if (limpo.isNotEmpty()) add(Pasta("__sem", "Sem nudez", limpo))
            if (pend.isNotEmpty()) add(Pasta("__pend", "Não analisados", pend))
        }
    }
    val pastaSel = remember(pastaId, pastas, albumsVirtuais) {
        pastaId?.let { id ->
            if (NavAbas.pastaIdRemovido(id)) null
            else pastas.find { it.id == id } ?: albumsVirtuais.find { it.id == id }
        }
    }
    val ordemAtual = runCatching { Ordenacao.valueOf(ordem) }.getOrDefault(Ordenacao.MAIS_NOVOS)

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { OrganizarWorker.iniciar(ctx) }
    fun organizar() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        else OrganizarWorker.iniciar(ctx)
    }

    BackHandler(enabled = ativa && pastaSel != null) {
        guardarScrolls(); pastaId = null; busca = ""
    }
    // Dentro de uma aba (sem pasta): volta à aba anterior, não sai do app.
    BackHandler(enabled = ativa && pastaSel == null && abaAtual != AbaGaleria.ALBUNS) {
        guardarScrolls()
        val s = NavAbas.aoVoltar(aba, abaAnterior)
        aba = s.aba
        abaAnterior = s.anterior
        // Volta pra Vídeos: restaura a ordem escolhida lá (não herda a ordem da última pasta).
        if (s.aba == AbaGaleria.VIDEOS.name) ordem = OrdemPrefs.get(ctx, "__videos", Ordenacao.MAIS_NOVOS).name
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            pastaSel != null -> PastaVideosTela(
                pasta = pastaSel!!, sts = sts, avs = avs, busca = busca, onBusca = { busca = it },
                ordem = ordemAtual, onOrdem = { o -> ordem = o.name; pastaId?.let { OrdemPrefs.set(ctx, it, o) } }, menuOrdem = menuOrdem, onMenuOrdem = { menuOrdem = it },
                onVoltar = { guardarScrolls(); pastaId = null; busca = "" },
                onAbrir = { guardarScrolls(); onAbrir(it) },
                orgMap = orgMap, embMap = embMap, analiseProg = analiseProg, filtroCats = pastaSel!!.id.startsWith("__"),
                cols = colsVideos, onCols = { colsVideos = it.coerceIn(3, 5) },
                gridState = scrollPasta,
            )
            abaAtual == AbaGaleria.VIDEOS -> AbaVideos(
                videos = videos, temPerm = temPerm, acessoParcial = acessoParcial,
                onPermitir = { pedir.launch(perm) },
                onAbrir = { guardarScrolls(); onAbrir(it) },
                sts = sts, avs = avs, orgMap = orgMap, embMap = embMap,
                analiseProg = analiseProg,
                ordem = ordemAtual,
                onOrdem = { o -> ordem = o.name; OrdemPrefs.set(ctx, "__videos", o) },
                menuOrdem = menuOrdem, onMenuOrdem = { menuOrdem = it },
                cols = colsVideos, onCols = { colsVideos = it.coerceIn(3, 5) },
                gridState = scrollVideos, onAbrirArquivo = { picker.launch(arrayOf("video/*")) },
            )
            abaAtual == AbaGaleria.ALBUNS -> AbaAlbuns(
                pastas = pastas, videos = videos, temPerm = temPerm, acessoParcial = acessoParcial,
                onPermitir = { pedir.launch(perm) },
                onPasta = { p ->
                    guardarScrolls(); pastaId = p.id; busca = ""
                    ordem = OrdemPrefs.get(ctx, p.id, Ordenacao.MAIS_NOVOS).name
                    pastaScrollIx = 0; pastaScrollOff = 0
                    // Grid da pasta é compartilhado: pasta nova abre no topo (não na rolagem da anterior).
                    escopo.launch { runCatching { scrollPasta.scrollToItem(0) } }
                },
                cols = colsAlbuns, onCols = { colsAlbuns = it.coerceIn(2, 4) },
                gridState = scrollAlbuns, onAbrirArquivo = { picker.launch(arrayOf("video/*")) },
            )
            abaAtual == AbaGaleria.INTELIGENTE -> AbaInteligente(
                smart = albumsVirtuais, videos = videos, temPerm = temPerm, acessoParcial = acessoParcial,
                orgRodando = orgRodando || orgPausado, orgPausado = orgPausado,
                orgProg = orgProg, orgTxt = orgTxt, recupMsg = recupMsg,
                onPermitir = { pedir.launch(perm) },
                onPasta = { p ->
                    guardarScrolls(); pastaId = p.id; busca = ""
                    val padrao = if (p.id == "__putaria") Ordenacao.SEMELHANCA else Ordenacao.MAIS_NOVOS
                    ordem = OrdemPrefs.get(ctx, p.id, padrao).name
                    pastaScrollIx = 0; pastaScrollOff = 0
                    // Grid da pasta é compartilhado: pasta nova abre no topo (não na rolagem da anterior).
                    escopo.launch { runCatching { scrollPasta.scrollToItem(0) } }
                },
                onOrganizar = { organizar() },
                onPausarOrg = { OrganizarWorker.pausar(ctx) },
                onContinuarOrg = { OrganizarWorker.continuar(ctx) },
                onCancelarOrg = { OrganizarWorker.cancelar(ctx) },
                onReanalisar = {
                    runCatching { GaleriaDb(ctx).limpar(); OrganizarWorker.iniciar(ctx) }
                    orgTick++
                },
                gridState = scrollIntel,
            )
            else -> {
                // Ajustes embutido na aba (mantém bottom bar e posição das outras abas).
                Box(Modifier.fillMaxSize().padding(bottom = 88.dp)) { Ajustes(voltar = { aba = AbaGaleria.ALBUNS.name }) }
            }
        }

        if (pastaSel == null && ativa) {
            BarraInferiorSamsung(
                aba = abaAtual,
                onAba = { nova ->
                    guardarScrolls()
                    val s = NavAbas.aoTrocar(aba, nova.name, abaAnterior)
                    abaAnterior = s.anterior
                    aba = s.aba
                    if (nova == AbaGaleria.VIDEOS) {
                        ordem = OrdemPrefs.get(ctx, "__videos", Ordenacao.MAIS_NOVOS).name
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun BarraInferiorSamsung(aba: AbaGaleria, onAba: (AbaGaleria) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().height(64.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(Color(0xE6121212))
                .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(32.dp))
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            AbaGaleria.entries.forEach { t ->
                val sel = t == aba
                Column(
                    Modifier.weight(1f).fillMaxHeight().toque { onAba(t) }
                        .then(if (sel) Modifier.clip(RoundedCornerShape(28.dp)).background(Color(0x33FFFFFF)) else Modifier)
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(t.icone, t.rotulo, tint = if (sel) Color.White else Color(0x99FFFFFF), modifier = Modifier.size(22.dp))
                    Spacer(Modifier.height(2.dp))
                    Text(t.rotulo, fontSize = 10.sp, color = if (sel) Color.White else Color(0x99FFFFFF), maxLines = 1)
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun AbaVideos(
    videos: List<Video>?, temPerm: Boolean, acessoParcial: Boolean, onPermitir: () -> Unit, onAbrir: (Video) -> Unit,
    sts: SharedTransitionScope, avs: AnimatedVisibilityScope, orgMap: Map<String, ResultadoOrg>,
    embMap: Map<String, FloatArray>,
    analiseProg: Map<String, Float>,
    ordem: Ordenacao, onOrdem: (Ordenacao) -> Unit, menuOrdem: Boolean, onMenuOrdem: (Boolean) -> Unit,
    cols: Int, onCols: (Int) -> Unit, gridState: LazyGridState, onAbrirArquivo: () -> Unit,
) {
    val brutos = videos ?: emptyList()
    val lista = remember(brutos, ordem, embMap) {
        when (ordem) {
            Ordenacao.MAIS_NOVOS -> brutos.sortedByDescending { it.dataMod }
            Ordenacao.MAIS_ANTIGOS -> brutos.sortedBy { it.dataMod }
            Ordenacao.MAIS_LONGOS -> brutos.sortedByDescending { it.duracao }
            Ordenacao.MAIS_CURTOS -> brutos.sortedBy { it.duracao }
            Ordenacao.MAIS_PESADOS -> brutos.sortedByDescending { it.tamanho }
            Ordenacao.MAIS_LEVES -> brutos.sortedBy { it.tamanho }
            Ordenacao.MAIOR_RES -> brutos.sortedByDescending { it.pixels }
            Ordenacao.MENOR_RES -> brutos.sortedBy { it.pixels }
            Ordenacao.NOME -> brutos.sortedBy { it.nome.lowercase() }
            Ordenacao.SEMELHANCA -> Semelhanca.ordenarPorSemelhanca(brutos, embMap)
        }
    }
    Column(Modifier.fillMaxSize().pointerInput(cols) {
        detectTransformGestures { _, _, zoom, _ ->
            if (zoom > 1.08f) onCols(cols - 1) else if (zoom < 0.92f) onCols(cols + 1)
        }
    }) {
        Row(
            Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Vídeos", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), color = Color.White, modifier = Modifier.weight(1f))
            Box {
                IconButton(onClick = { onMenuOrdem(true) }) { Icon(Icons.AutoMirrored.Filled.Sort, "Ordenar", tint = Color.White) }
                DropdownMenu(menuOrdem, { onMenuOrdem(false) }) {
                    Ordenacao.entries.forEach { o ->
                        DropdownMenuItem(
                            text = { Text(o.rotulo) },
                            onClick = { onOrdem(o); onMenuOrdem(false) },
                            trailingIcon = { if (o == ordem) Icon(Icons.Default.Check, null) },
                        )
                    }
                }
            }
            IconButton(onClick = onAbrirArquivo) { Icon(Icons.Default.FolderOpen, "Abrir arquivo", tint = Color.White) }
        }
        Text(
            "Ordenar: ${ordem.rotulo}",
            Modifier.padding(horizontal = 16.dp, vertical = 2.dp).toque { onMenuOrdem(true) }
                .clip(RoundedCornerShape(50)).background(Color(0x22FFFFFF)).padding(horizontal = 10.dp, vertical = 4.dp),
            color = Color(0xCCFFFFFF), style = MaterialTheme.typography.labelSmall,
        )
        if (!temPerm) {
            BotaoGradiente("Permitir acesso aos vídeos", Modifier.padding(16.dp), onClick = onPermitir)
            return
        }
        BannerAcessoParcial(acessoParcial, onPermitir)
        LazyVerticalGrid(
            columns = GridCells.Fixed(cols), state = gridState,
            contentPadding = PaddingValues(start = 2.dp, end = 2.dp, bottom = 100.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(lista.size, key = { lista[it].uri.toString() }) { i ->
                TileVideoDenso(lista[i], sts, avs, (orgMap[VideosMedia.chaveEstavel(lista[i].uri.toString())] ?: orgMap[lista[i].uri.toString()])?.classif == ClassifNudez.NUDE, analiseProg[lista[i].uri.toString()]) { onAbrir(lista[i]) }
            }
        }
    }
}

@Composable
private fun AbaAlbuns(
    pastas: List<Pasta>, videos: List<Video>?, temPerm: Boolean, acessoParcial: Boolean, onPermitir: () -> Unit,
    onPasta: (Pasta) -> Unit, cols: Int, onCols: (Int) -> Unit, gridState: LazyGridState, onAbrirArquivo: () -> Unit,
) {
    Column(Modifier.fillMaxSize().pointerInput(cols) {
        detectTransformGestures { _, _, zoom, _ ->
            if (zoom > 1.08f) onCols(cols - 1) else if (zoom < 0.92f) onCols(cols + 1)
        }
    }) {
        Row(
            Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Álbuns", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), color = Color.White)
                Text(
                    when {
                        !temPerm -> "Sem acesso à galeria"
                        videos == null -> "Carregando…"
                        else -> "${pastas.size} álbuns · ${videos.size} vídeos (só vídeo; fotos não entram)"
                    },
                    style = MaterialTheme.typography.labelMedium, color = Color(0x99FFFFFF),
                )
            }
            IconButton(onClick = onAbrirArquivo) { Icon(Icons.Default.FolderOpen, "Abrir arquivo", tint = Color.White) }
        }
        if (!temPerm) {
            BotaoGradiente("Permitir acesso aos vídeos", Modifier.padding(16.dp), onClick = onPermitir)
            return
        }
        BannerAcessoParcial(acessoParcial, onPermitir)
        LazyVerticalGrid(
            columns = GridCells.Fixed(cols), state = gridState,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 100.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(pastas.size, key = { pastas[it].id }) { i ->
                CartaAlbumSamsung(pastas[i]) { onPasta(pastas[i]) }
            }
        }
    }
}

@Composable
private fun AbaInteligente(
    smart: List<Pasta>, videos: List<Video>?, temPerm: Boolean, acessoParcial: Boolean,
    orgRodando: Boolean, orgPausado: Boolean, orgProg: Float, orgTxt: String?, recupMsg: String?,
    onPermitir: () -> Unit, onPasta: (Pasta) -> Unit,
    onOrganizar: () -> Unit, onPausarOrg: () -> Unit, onContinuarOrg: () -> Unit, onCancelarOrg: () -> Unit,
    onReanalisar: () -> Unit, gridState: LazyGridState,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2), state = gridState,
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 100.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(2) }) {
            Column(Modifier.statusBarsPadding().padding(top = 14.dp, bottom = 6.dp)) {
                Text("Inteligente", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), color = Color.White)
                Text("Álbuns por nudez · 100% offline", style = MaterialTheme.typography.labelMedium, color = Color(0x99FFFFFF))
                if (!recupMsg.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(recupMsg, style = MaterialTheme.typography.labelSmall, color = Color(0xFFFFB74D))
                }
                Spacer(Modifier.height(12.dp))
                if (!temPerm) BotaoGradiente("Permitir acesso aos vídeos", onClick = onPermitir)
                else {
                BannerAcessoParcial(acessoParcial, onPermitir)
                if (orgRodando || orgPausado) {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFF1A1A1A)).padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(if (orgPausado) "Organização pausada" else "Organizando…", style = MaterialTheme.typography.titleSmall, color = Color.White)
                                Text(orgTxt ?: "Varredura", style = MaterialTheme.typography.labelSmall, color = Color(0x99FFFFFF))
                            }
                            if (orgPausado) BotaoVidro("Continuar") { onContinuarOrg() }
                            else BotaoVidro("Pausar") { onPausarOrg() }
                            Spacer(Modifier.width(8.dp))
                            BotaoVidro("Parar") { onCancelarOrg() }
                        }
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { orgProg.coerceIn(0f, 1f) },
                            Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                            color = Cores.Magenta, trackColor = Color(0x22FFFFFF),
                        )
                    }
                } else {
                    BotaoGradiente("Organizar galeria", Modifier.fillMaxWidth(),
                        icone = { Icon(Icons.Default.AutoAwesome, null, tint = Color.White) }, onClick = onOrganizar)
                    Spacer(Modifier.height(8.dp))
                    BotaoVidro("Reanalisar galeria", Modifier.fillMaxWidth(),
                        icone = { Icon(Icons.Default.Refresh, null, tint = Cores.Texto) }, onClick = onReanalisar)
                    Text(
                        "Separa Putaria e Sem nudez. Não move arquivos.",
                        style = MaterialTheme.typography.labelSmall, color = Color(0x99FFFFFF),
                        modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                    )
                }
                }
                if (smart.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("Álbuns", style = MaterialTheme.typography.titleSmall, color = Color(0x99FFFFFF))
                } else if (temPerm && videos != null) {
                    Spacer(Modifier.height(10.dp))
                    Text("Toque em Organizar para criar Putaria / Sem nudez.", style = MaterialTheme.typography.bodySmall, color = Color(0x99FFFFFF))
                }
            }
        }
        items(smart.size, key = { smart[it].id }) { i ->
            CartaAlbumSamsung(smart[i], destaque = smart[i].id == "__putaria") { onPasta(smart[i]) }
        }
    }
}


@Composable
private fun BannerAcessoParcial(mostrar: Boolean, onPermitir: () -> Unit) {
    if (!mostrar) return
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp)).background(Color(0x33FF5C8A)).padding(12.dp),
    ) {
        Text("Acesso parcial à galeria", color = Color.White, style = MaterialTheme.typography.titleSmall)
        Text(
            "O Android só liberou alguns arquivos. Toque para permitir todos os vídeos — assim as pastas ficam com a contagem certa.",
            color = Color(0xCCFFFFFF), style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.height(8.dp))
        BotaoGradiente("Permitir todos os vídeos", Modifier.fillMaxWidth(), onClick = onPermitir)
    }
}

@Composable
private fun CartaAlbumSamsung(p: Pasta, destaque: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().aspectRatio(1f).toque(onClick = onClick)
            .clip(RoundedCornerShape(18.dp))
            .then(if (destaque) Modifier.border(1.5.dp, Cores.GradienteH, RoundedCornerShape(18.dp)) else Modifier),
    ) {
        if (p.videos.isNotEmpty()) Thumb(p.capa.uri, Modifier.fillMaxSize(), w = 360, h = 360)
        else Box(Modifier.fillMaxSize().background(Color(0xFF1A1A1A)))
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xCC000000)),
            ),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
            Text(
                p.nome, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(if (p.contagem == 1) "1 vídeo" else "${p.contagem} vídeos", color = Color(0xCCFFFFFF), fontSize = 12.sp)
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun PastaVideosTela(
    pasta: Pasta, sts: SharedTransitionScope, avs: AnimatedVisibilityScope,
    busca: String, onBusca: (String) -> Unit, ordem: Ordenacao, onOrdem: (Ordenacao) -> Unit,
    menuOrdem: Boolean, onMenuOrdem: (Boolean) -> Unit, onVoltar: () -> Unit, onAbrir: (Video) -> Unit,
    orgMap: Map<String, ResultadoOrg>, embMap: Map<String, FloatArray>, analiseProg: Map<String, Float>, filtroCats: Boolean,
    cols: Int, onCols: (Int) -> Unit, gridState: LazyGridState,
) {
    var catFiltro by remember { mutableStateOf<Categoria?>(null) }
    val filtrados = remember(pasta, busca, ordem, catFiltro, orgMap, embMap) {
        var base = pasta.videos.filter { busca.isBlank() || it.nome.contains(busca, true) }
        if (catFiltro != null) base = base.filter { (orgMap[VideosMedia.chaveEstavel(it.uri.toString())] ?: orgMap[it.uri.toString()])?.cats?.contains(catFiltro) == true }
        when (ordem) {
            Ordenacao.MAIS_NOVOS -> base.sortedByDescending { it.dataMod }
            Ordenacao.MAIS_ANTIGOS -> base.sortedBy { it.dataMod }
            Ordenacao.MAIS_LONGOS -> base.sortedByDescending { it.duracao }
            Ordenacao.MAIS_CURTOS -> base.sortedBy { it.duracao }
            Ordenacao.MAIS_PESADOS -> base.sortedByDescending { it.tamanho }
            Ordenacao.MAIS_LEVES -> base.sortedBy { it.tamanho }
            Ordenacao.MAIOR_RES -> base.sortedByDescending { it.pixels }
            Ordenacao.MENOR_RES -> base.sortedBy { it.pixels }
            Ordenacao.NOME -> base.sortedBy { it.nome.lowercase() }
            Ordenacao.SEMELHANCA -> Semelhanca.ordenarPorSemelhanca(base, embMap)
        }
    }
    Column(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(cols) {
            detectTransformGestures { _, _, zoom, _ ->
                if (zoom > 1.08f) onCols(cols - 1) else if (zoom < 0.92f) onCols(cols + 1)
            }
        },
    ) {
        Row(
            Modifier.statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onVoltar) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(pasta.nome, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (filtrados.size == 1) "1 vídeo" else "${filtrados.size} vídeos", style = MaterialTheme.typography.labelSmall, color = Color(0x99FFFFFF))
            }
            Box {
                IconButton(onClick = { onMenuOrdem(true) }) { Icon(Icons.AutoMirrored.Filled.Sort, "Ordenar", tint = Color.White) }
                DropdownMenu(menuOrdem, { onMenuOrdem(false) }) {
                    Ordenacao.entries.forEach { o ->
                        DropdownMenuItem(
                            text = { Text(o.rotulo) },
                            onClick = { onOrdem(o); onMenuOrdem(false) },
                            trailingIcon = { if (o == ordem) Icon(Icons.Default.Check, null) },
                        )
                    }
                }
            }
        }
        // chip com ordem atual
        Text(
            "Ordenar: ${ordem.rotulo}",
            Modifier.padding(horizontal = 16.dp, vertical = 2.dp).toque { onMenuOrdem(true) }
                .clip(RoundedCornerShape(50)).background(Color(0x22FFFFFF)).padding(horizontal = 10.dp, vertical = 4.dp),
            color = Color(0xCCFFFFFF), style = MaterialTheme.typography.labelSmall,
        )
        OutlinedTextField(
            value = busca, onValueChange = onBusca, singleLine = true,
            placeholder = { Text("Buscar", color = Color(0x66FFFFFF)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0x33FFFFFF), unfocusedBorderColor = Color(0x22FFFFFF),
                cursorColor = Cores.Magenta,
            ),
            shape = RoundedCornerShape(14.dp),
        )
        if (filtroCats) {
            LazyRow(
                Modifier.padding(top = 8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    val todos = catFiltro == null
                    Row(
                        Modifier.toque { catFiltro = null }.clip(RoundedCornerShape(50))
                            .then(if (todos) Modifier.background(Cores.GradienteH) else Modifier.background(Color(0x22FFFFFF)))
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    ) { Text("Todas", style = MaterialTheme.typography.labelMedium, color = Color.White) }
                }
                items(Categoria.entries.size) { i ->
                    val c = Categoria.entries[i]
                    ChipCategoria(c, c == catFiltro) { catFiltro = if (catFiltro == c) null else c }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(cols), state = gridState,
            contentPadding = PaddingValues(start = 2.dp, end = 2.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(filtrados.size, key = { filtrados[it].uri.toString() }) { i ->
                val v = filtrados[i]
                TileVideoDenso(v, sts, avs, (orgMap[VideosMedia.chaveEstavel(v.uri.toString())] ?: orgMap[v.uri.toString()])?.classif == ClassifNudez.NUDE, analiseProg[v.uri.toString()]) { onAbrir(v) }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun TileVideoDenso(
    v: Video, sts: SharedTransitionScope, avs: AnimatedVisibilityScope, nude: Boolean, prog: Float? = null, onClick: () -> Unit,
) {
    val ctxTile = LocalContext.current
    val fracPos = remember(v.uri, v.duracao) {
        PosicaoPrefs.fracao(ctxTile, VideosMedia.chaveEstavel(v.uri.toString()), v.duracao)
    }
    with(sts) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).toque(onClick = onClick)
                .sharedBounds(rememberSharedContentState("v-${v.uri}"), avs, resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds)
                .clip(RoundedCornerShape(4.dp)),
        ) {
            Thumb(v.uri, Modifier.fillMaxSize(), w = 240, h = 240)
            if (fracPos != null && fracPos > 0.02f && fracPos < 0.98f) {
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp)
                        .background(Color(0x55000000)),
                ) {
                    Box(
                        Modifier.fillMaxHeight().fillMaxWidth(fracPos)
                            .background(Cores.Magenta.copy(alpha = 0.85f)),
                    )
                }
            }
            Text(
                fmt(v.duracao),
                Modifier.align(Alignment.BottomEnd).padding(4.dp)
                    .background(Color(0x99000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                color = Color.White, fontSize = 10.sp,
            )
            if (nude) {
                Box(
                    Modifier.align(Alignment.TopStart).padding(5.dp).size(7.dp)
                        .clip(CircleShape).background(Cores.Magenta),
                )
            }
            if (prog != null) {
                Box(
                    Modifier.align(Alignment.Center).clip(RoundedCornerShape(8.dp))
                        .background(Color(0xCC000000)).padding(horizontal = 6.dp, vertical = 3.dp),
                ) {
                    Text(if (prog >= 0.999f) "✓" else "${(prog * 100).toInt()}%", color = Color.White, fontSize = 10.sp)
                }
            }
        }
    }
}
