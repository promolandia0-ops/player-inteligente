package br.geracaoai.player

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Cores {
    val Fundo = Color(0xFF07060B)
    val Painel = Color(0xFF110F18)
    val Magenta = Color(0xFFFF2BD6)
    val Violeta = Color(0xFF7B2FFF)
    val Texto = Color(0xFFF3EEFF)
    val Suave = Color(0xFF9A93AD)
    val Gradiente = Brush.linearGradient(listOf(Magenta, Violeta))
    val GradienteH = Brush.horizontalGradient(listOf(Magenta, Violeta))
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun sora(w: Int) = Font(R.font.sora, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val Sora = FontFamily(sora(300), sora(400), sora(500), sora(600), sora(700), sora(800))

private fun ts(sz: Int, w: FontWeight, ls: Double = 0.0) = TextStyle(fontFamily = Sora, fontSize = sz.sp, fontWeight = w, letterSpacing = ls.sp)
val Tipos = Typography(
    displaySmall = ts(32, FontWeight.ExtraBold, -0.8), headlineMedium = ts(26, FontWeight.Bold, -0.5),
    titleLarge = ts(20, FontWeight.Bold, -0.3), titleMedium = ts(16, FontWeight.SemiBold), titleSmall = ts(14, FontWeight.SemiBold),
    bodyLarge = ts(15, FontWeight.Normal), bodyMedium = ts(14, FontWeight.Normal), bodySmall = ts(12, FontWeight.Normal),
    labelLarge = ts(14, FontWeight.SemiBold, 0.2), labelMedium = ts(12, FontWeight.Medium, 0.3), labelSmall = ts(11, FontWeight.Medium, 0.4))

@Composable
fun TemaPlayer(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(primary = Cores.Magenta, secondary = Cores.Violeta, background = Cores.Fundo, surface = Cores.Painel,
        onBackground = Cores.Texto, onSurface = Cores.Texto, surfaceVariant = Color(0xFF1A1724), onSurfaceVariant = Cores.Suave,
        surfaceContainer = Cores.Painel, surfaceContainerHigh = Color(0xFF1A1724), outline = Color(0x33FFFFFF)),
    typography = Tipos, content = content)

/** Painel "vidro": translúcido, com borda de brilho. */
fun Modifier.vidro(shape: Shape = RoundedCornerShape(22.dp), alpha: Float = 0.55f) = this
    .clip(shape)
    .background(Brush.verticalGradient(listOf(Color(0xFF1C1828).copy(alpha = alpha), Color(0xFF0B0A10).copy(alpha = alpha + 0.15f))))
    .border(1.dp, Brush.verticalGradient(listOf(Color(0x40FFFFFF), Color(0x0DFFFFFF))), shape)

/** Escala com mola ao pressionar + háptico. */
@Composable
fun Modifier.toque(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.92f else 1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium), label = "s")
    val hap = LocalHapticFeedback.current
    return this.scale(s).clickable(src, null, enabled) { hap.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() }
}

/** Como [toque], com toque longo (ex.: menu de favoritar nos tiles). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun Modifier.toqueLongo(onLongClick: () -> Unit, onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.92f else 1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium), label = "sl")
    val hap = LocalHapticFeedback.current
    return this.scale(s).combinedClickable(
        interactionSource = src, indication = null,
        onLongClick = { hap.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() },
        onClick = { hap.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() },
    )
}

@Composable
fun BotaoGradiente(texto: String, modifier: Modifier = Modifier, enabled: Boolean = true, icone: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(modifier.toque(enabled, onClick).clip(RoundedCornerShape(50))
        .background(if (enabled) Cores.GradienteH else Brush.horizontalGradient(listOf(Color(0xFF2A2633), Color(0xFF2A2633))))
        .padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        icone?.invoke(); if (icone != null) Spacer(Modifier.width(8.dp))
        Text(texto, style = MaterialTheme.typography.labelLarge, color = if (enabled) Color.White else Cores.Suave)
    }
}

@Composable
fun BotaoVidro(texto: String, modifier: Modifier = Modifier, enabled: Boolean = true, icone: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(modifier.toque(enabled, onClick).vidro(RoundedCornerShape(50)).padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        icone?.invoke(); if (icone != null) Spacer(Modifier.width(6.dp))
        Text(texto, style = MaterialTheme.typography.labelLarge, color = if (enabled) Cores.Texto else Cores.Suave)
    }
}

@Composable
fun ChipCategoria(c: Categoria, sel: Boolean, onClick: () -> Unit) {
    val s by animateFloatAsState(if (sel) 1.06f else 1f, spring(dampingRatio = 0.4f, stiffness = 500f), label = "c")
    val cor = Color(c.cor)
    Row(Modifier.scale(s).toque(onClick = onClick).clip(RoundedCornerShape(50))
        .then(if (sel) Modifier.background(Brush.horizontalGradient(listOf(cor.copy(alpha = 0.95f), cor.copy(alpha = 0.55f))))
              else Modifier.vidro(RoundedCornerShape(50)))
        .padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(if (sel) Color.White else cor))
        Spacer(Modifier.width(8.dp))
        Text(c.rotulo, style = MaterialTheme.typography.labelLarge, color = if (sel) Color(0xFF0B0A10) else Cores.Texto)
    }
}

@Composable
fun AnelProgresso(p: Float, tamanho: Dp = 64.dp) {
    val anim by animateFloatAsState(p, spring(stiffness = Spring.StiffnessLow), label = "p")
    val rot by rememberInfiniteTransition(label = "r").animateFloat(0f, 360f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "r")
    Box(Modifier.size(tamanho), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val st = androidx.compose.ui.graphics.drawscope.Stroke(width = 6.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawArc(Color(0x22FFFFFF), 0f, 360f, false, style = st)
            drawArc(Brush.sweepGradient(listOf(Cores.Violeta, Cores.Magenta, Cores.Violeta)), rot - 90f, maxOf(6f, 360f * anim), false, style = st)
        }
        Text("${(anim * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = Cores.Texto)
    }
}
