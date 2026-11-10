package br.geracaoai.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** Matemática da barra de rolagem rápida (pura → testável). Índices são de itens da grade. */
object BarraRolagemLogica {
    /** Só aparece se a lista for bem maior que a tela (≥ 2 telas). */
    fun deveExistir(total: Int, visiveis: Int) = visiveis > 0 && total > visiveis * 2

    /** 0..1: posição atual na lista a partir do 1º item visível (+ fração da rolagem dentro dele). */
    fun progresso(primeiro: Int, fracItem: Float, total: Int, visiveis: Int): Float {
        val max = (total - visiveis).coerceAtLeast(1)
        return ((primeiro + fracItem.coerceIn(0f, 1f)) / max).coerceIn(0f, 1f)
    }

    /** Índice a rolar para a fração [f] (0..1); 1f leva ao fim da lista. */
    fun indiceAlvo(f: Float, total: Int, visiveis: Int): Int {
        if (total <= 0) return 0
        val max = (total - visiveis).coerceAtLeast(0)
        return if (f >= 0.999f) total - 1 else (f.coerceIn(0f, 1f) * max).toInt().coerceIn(0, total - 1)
    }
}

/**
 * Grade com barra de rolagem rápida arrastável na borda direita: aparece ao rolar, some ~1,5 s depois de parar,
 * alça alta (área de toque 48 dp) na cor de destaque. Durante o arraste só faz scrollToItem (sem trabalho pesado).
 */
@Composable
fun GradeRapida(
    columns: GridCells,
    state: LazyGridState,
    contentPadding: PaddingValues,
    horizontalArrangement: Arrangement.Horizontal,
    verticalArrangement: Arrangement.Vertical,
    modifier: Modifier = Modifier,
    content: LazyGridScope.() -> Unit,
) {
    Box(modifier) {
        LazyVerticalGrid(
            columns = columns, state = state, contentPadding = contentPadding,
            horizontalArrangement = horizontalArrangement, verticalArrangement = verticalArrangement,
            modifier = Modifier.fillMaxSize(), content = content,
        )
        AlcaRolagem(state, contentPadding.calculateBottomPadding(), Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun AlcaRolagem(state: LazyGridState, fundo: Dp, modifier: Modifier) {
    val densidade = androidx.compose.ui.platform.LocalDensity.current
    var arrastando by remember { mutableStateOf(false) }
    var visivel by remember { mutableStateOf(false) }
    var alturaPx by remember { mutableIntStateOf(0) }
    var fracArraste by remember { mutableFloatStateOf(0f) }
    val alvo = remember { mutableIntStateOf(-1) }

    val existe by remember(state) {
        derivedStateOf {
            val i = state.layoutInfo
            BarraRolagemLogica.deveExistir(i.totalItemsCount, i.visibleItemsInfo.size)
        }
    }
    val fracLista by remember(state) {
        derivedStateOf {
            val i = state.layoutInfo
            val h = i.visibleItemsInfo.firstOrNull()?.size?.height ?: 0
            val f = if (h > 0) state.firstVisibleItemScrollOffset.toFloat() / h else 0f
            BarraRolagemLogica.progresso(state.firstVisibleItemIndex, f, i.totalItemsCount, i.visibleItemsInfo.size)
        }
    }
    val rolando = state.isScrollInProgress

    // Aparece ao rolar/arrastar; some 1,5 s depois de parar.
    LaunchedEffect(rolando, arrastando) {
        if (rolando || arrastando) visivel = true else { delay(1500); visivel = false }
    }
    // Rola para o alvo do arraste (só o último pedido vale).
    LaunchedEffect(state) {
        snapshotFlow { alvo.intValue }.collectLatest { if (it >= 0) runCatching { state.scrollToItem(it) } }
    }

    val alpha by animateFloatAsState(if (existe && visivel) 1f else 0f, tween(180), label = "alca")
    if (alpha <= 0.01f || !existe) return

    val alcaAltura = 64.dp
    val alcaPx = with(densidade) { alcaAltura.toPx() }
    val faixaPx = (alturaPx - alcaPx).coerceAtLeast(1f)
    val f = if (arrastando) fracArraste else fracLista
    Box(modifier.fillMaxHeight().padding(bottom = fundo).width(48.dp).onSizeChanged { alturaPx = it.height }.alpha(alpha)) {
        Box(
            Modifier.align(Alignment.TopEnd).offset { IntOffset(0, (f * faixaPx).toInt()) }
                .size(width = 48.dp, height = alcaAltura)
                .pointerInput(state) {
                    detectVerticalDragGestures(
                        onDragStart = { arrastando = true; fracArraste = fracLista },
                        onDragEnd = { arrastando = false },
                        onDragCancel = { arrastando = false },
                    ) { change, dy ->
                        change.consume()
                        fracArraste = (fracArraste + dy / faixaPx).coerceIn(0f, 1f)
                        val i = state.layoutInfo
                        alvo.intValue = BarraRolagemLogica.indiceAlvo(fracArraste, i.totalItemsCount, i.visibleItemsInfo.size)
                    }
                },
        ) {
            Box(
                Modifier.align(Alignment.CenterEnd).padding(end = 3.dp)
                    .size(width = if (arrastando) 12.dp else 8.dp, height = if (arrastando) alcaAltura else 48.dp)
                    .background(Cores.Magenta.copy(alpha = 0.92f), RoundedCornerShape(50)),
            )
        }
    }
}
