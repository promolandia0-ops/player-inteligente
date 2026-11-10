package br.geracaoai.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Pessoa pronta para a UI: só vídeos que ainda existem na galeria. */
class PessoaUi(val id: Long, val nome: String, val videos: List<Video>, val miniatura: File?) {
    val rotulo: String get() = nome.ifBlank { "Pessoa $id" }
    fun comoPasta() = Pasta("__pessoa_$id", rotulo, videos)
}

object PessoasCarga {
    /** Fora da thread principal. Esconde quem tem < [Agrupamento.MIN_VIDEOS] vídeos existentes. */
    fun carregar(ctx: android.content.Context, videos: List<Video>): List<PessoaUi> {
        val db = PessoasDb(ctx)
        try {
            val porChave = videos.associateBy { VideosMedia.chaveEstavel(it.uri.toString()) }
            return db.listar(1).mapNotNull { p ->
                val vs = db.videosDe(p.id).mapNotNull { porChave[it.chave] }.distinctBy { it.uri.toString() }
                if (vs.size < Agrupamento.MIN_VIDEOS) null else PessoaUi(p.id, p.nome, vs, p.miniatura)
            }.sortedByDescending { it.videos.size }
        } finally { runCatching { db.close() } }
    }
}

private object CacheRostos {
    private val m = object : LinkedHashMap<String, Bitmap>(64, 0.75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Bitmap>) = size > 80 }
    @Synchronized fun obter(k: String): Bitmap? = m[k]
    @Synchronized fun guardar(k: String, b: Bitmap) { m[k] = b }
}

@Composable
private fun RostoRedondo(f: File?, modifier: Modifier) {
    val bmp by produceState<Bitmap?>(f?.let { CacheRostos.obter(it.path) }, f) {
        if (f == null) return@produceState
        CacheRostos.obter(f.path)?.let { value = it; return@produceState }
        value = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull() }?.also { CacheRostos.guardar(f.path, it) }
    }
    Box(modifier.clip(CircleShape).background(Color(0xFF1A1A1A)), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Default.Face, null, tint = Color(0x66FFFFFF), modifier = Modifier.size(36.dp))
    }
}

/** Cartão de entrada no topo de Álbuns. */
@Composable
fun CartaPessoasEntrada(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().toque(onClick = onClick).clip(RoundedCornerShape(18.dp)).background(Color(0xFF1A1A1A)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Cores.GradienteH), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Face, null, tint = Color.White)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Pessoas", color = Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            Text("Agrupa os vídeos pelo rosto · offline", color = Color(0x99FFFFFF), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PessoasTela(videos: List<Video>, onVoltar: () -> Unit, onAbrir: (Pasta) -> Unit) {
    val ctx = LocalContext.current
    val prog = remember { PessoasProg(ctx) }
    var tick by remember { mutableIntStateOf(0) }
    var ativo by remember { mutableStateOf(prog.ativo) }
    var feitos by remember { mutableIntStateOf(prog.feitos) }
    var total by remember { mutableIntStateOf(prog.total) }
    var txt by remember { mutableStateOf(prog.txt) }
    // Lê o progresso a cada 1 s (SharedPreferences, barato); a lista só recarrega quando algo mudou.
    LaunchedEffect(Unit) {
        while (true) {
            if (ativo != prog.ativo) { ativo = prog.ativo; tick++ }
            if (feitos != prog.feitos) { feitos = prog.feitos; if (feitos % 4 == 0) tick++ }
            total = prog.total; txt = prog.txt
            delay(1000)
        }
    }
    val pessoas by produceState<List<PessoaUi>?>(null, videos, tick) {
        value = withContext(Dispatchers.IO) { runCatching { PessoasCarga.carregar(ctx, videos) }.getOrDefault(emptyList()) }
    }
    var opcoes by remember { mutableStateOf<PessoaUi?>(null) }
    var juntando by remember { mutableStateOf<PessoaUi?>(null) }
    var confirmarLimpar by remember { mutableStateOf(false) }
    val escopo = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.statusBarsPadding().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onVoltar) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", tint = Color.White) }
            Text("Pessoas", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = Color.White)
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0xFF1A1A1A)).padding(14.dp)) {
            if (ativo) {
                Text(txt.ifBlank { "Analisando rostos…" }, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { if (total > 0) (feitos.toFloat() / total).coerceIn(0f, 1f) else 0f },
                    Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)), color = Cores.Magenta, trackColor = Color(0x22FFFFFF))
                Spacer(Modifier.height(10.dp))
                BotaoVidro("Parar", Modifier.fillMaxWidth()) { PessoasWorker.cancelar(ctx); ativo = false }
            } else {
                Text(
                    if (feitos > 0 && feitos >= total && total > 0) "Todos os vídeos já foram analisados. Toque para ver só os novos."
                    else if (feitos > 0) "$feitos de $total vídeos analisados. Pode continuar de onde parou."
                    else "Procura rostos nos vídeos e junta os da mesma pessoa. Roda em segundo plano e dá pra parar a qualquer hora.",
                    color = Color(0xCCFFFFFF), style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
                BotaoGradiente(if (feitos > 0) "Buscar pessoas nos vídeos novos" else "Buscar pessoas", Modifier.fillMaxWidth()) { PessoasWorker.iniciar(ctx); ativo = true }
                if (feitos > 0) { Spacer(Modifier.height(8.dp)); BotaoVidro("Apagar e refazer", Modifier.fillMaxWidth()) { confirmarLimpar = true } }
            }
        }
        Text("Toque e segure numa pessoa para renomear ou juntar.", Modifier.padding(horizontal = 18.dp, vertical = 8.dp), color = Color(0x80FFFFFF), style = MaterialTheme.typography.labelSmall)
        val lista = pessoas
        when {
            lista == null -> Text("Carregando…", Modifier.padding(16.dp), color = Color(0x99FFFFFF))
            lista.isEmpty() -> Text(if (ativo) "Procurando… as pessoas aparecem aqui conforme forem achadas (a partir de 2 vídeos)." else "Nenhuma pessoa ainda.", Modifier.padding(16.dp), color = Color(0x99FFFFFF))
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize(),
            ) {
                items(lista.size, key = { lista[it].id }) { i ->
                    val p = lista[i]
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).combinedClickable(onClick = { onAbrir(p.comoPasta()) }, onLongClick = { opcoes = p }).padding(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        RostoRedondo(p.miniatura, Modifier.fillMaxWidth(0.9f).aspectRatio(1f))
                        Spacer(Modifier.height(6.dp))
                        Text(p.rotulo, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        Text("${p.videos.size} vídeos", color = Color(0x99FFFFFF), fontSize = 11.sp)
                    }
                }
            }
        }
    }

    opcoes?.let { p ->
        var nome by remember(p.id) { mutableStateOf(p.nome) }
        AlertDialog(
            onDismissRequest = { opcoes = null },
            title = { Text(p.rotulo) },
            text = {
                Column {
                    OutlinedTextField(nome, { nome = it }, label = { Text("Nome") }, singleLine = true)
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = { juntando = p; opcoes = null }) { Text("Juntar com outra pessoa…") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = p.id; val n = nome
                    opcoes = null
                    escopo.launch(Dispatchers.IO) { PessoasDb(ctx).usar { it.renomear(id, n) }; tick++ }
                }) { Text("Salvar") }
            },
            dismissButton = { TextButton(onClick = { opcoes = null }) { Text("Cancelar") } },
        )
    }
    juntando?.let { origem ->
        AlertDialog(
            onDismissRequest = { juntando = null },
            title = { Text("Juntar \"${origem.rotulo}\" com…") },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items((pessoas ?: emptyList()).filter { it.id != origem.id }, key = { it.id }) { d ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).combinedClickable(onClick = {
                                juntando = null
                                escopo.launch(Dispatchers.IO) { PessoasDb(ctx).usar { it.juntar(origem.id, d.id) }; tick++ }
                            }).padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RostoRedondo(d.miniatura, Modifier.size(44.dp)); Spacer(Modifier.width(12.dp))
                            Column { Text(d.rotulo); Text("${d.videos.size} vídeos", fontSize = 12.sp, color = Color(0x99FFFFFF)) }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { juntando = null }) { Text("Cancelar") } },
        )
    }
    if (confirmarLimpar) {
        AlertDialog(
            onDismissRequest = { confirmarLimpar = false },
            title = { Text("Apagar e refazer?") },
            text = { Text("Apaga as pessoas, nomes e junções feitas e analisa tudo de novo. Os vídeos não são mexidos.") },
            confirmButton = { TextButton(onClick = {
                confirmarLimpar = false
                escopo.launch(Dispatchers.IO) { PessoasDb(ctx).usar { it.limparTudo() }; prog.feitos = 0; prog.total = 0; feitos = 0; total = 0; tick++ }
            }) { Text("Apagar") } },
            dismissButton = { TextButton(onClick = { confirmarLimpar = false }) { Text("Cancelar") } },
        )
    }
}

private fun <T> PessoasDb.usar(b: (PessoasDb) -> T): T = try { b(this) } finally { runCatching { close() } }
