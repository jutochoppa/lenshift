package dev.lenshift.app.ui.visual

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.lenshift.app.ui.markdown.MathRenderer
import dev.lenshift.app.ui.theme.AssistantCaptureColors
import dev.lenshift.app.visual.Analysis
import dev.lenshift.app.visual.Camera3
import dev.lenshift.app.visual.Dash
import dev.lenshift.app.visual.Graph2D
import dev.lenshift.app.visual.LegendEntry
import dev.lenshift.app.visual.LegendKind
import dev.lenshift.app.visual.MathText
import dev.lenshift.app.visual.Param
import dev.lenshift.app.visual.Scene3D
import dev.lenshift.app.visual.View2
import dev.lenshift.app.visual.Visual
import dev.lenshift.app.visual.VisualSpec
import kotlin.math.roundToInt

/**
 * A ```graph or ```graph3d block of an answer: a placeholder while it streams, then the
 * drawn visual, or what went wrong with the spec beside its source.
 */
@Composable
fun VisualBlock(source: String, threeD: Boolean, complete: Boolean) {
    DisableSelection {
        if (!complete) {
            VisualPlaceholder(if (threeD) "Building 3D model…" else "Drawing graph…")
            return@DisableSelection
        }
        val parsed = remember(source, threeD) { runCatching { VisualSpec.parse(source, threeD) } }
        parsed.fold(
            onSuccess = { VisualCard(it) },
            onFailure = { VisualError(it.message ?: "The spec could not be read.", source) }
        )
    }
}

/** Everything the user changed on one visual: sliders, view, dragged points, camera. */
@Stable
class VisualState(private val visual: Visual) {
    val sliders = mutableStateListOf<Double>().apply { addAll(visual.params.map { it.value }) }
    var playing by mutableStateOf<String?>(null)
    var view by mutableStateOf<View2?>(null)
    val drags = mutableStateMapOf<String, DoubleArray>()
    var camera by mutableStateOf(Camera3())
    var interacting by mutableStateOf(false)
    var legend by mutableStateOf<List<LegendEntry>>(emptyList())
    var runtimeNotes by mutableStateOf<List<String>>(emptyList())

    /** Back to the starting view, points and camera (double tap). */
    fun resetView() {
        view = null
        drags.clear()
        camera = Camera3()
    }

    /** Back to how the answer drew it, sliders included (the reset button). */
    fun resetAll() {
        resetView()
        playing = null
        sliders.clear()
        sliders.addAll(visual.params.map { it.value })
    }
}

@Composable
private fun VisualCard(visual: Visual) {
    val state = remember(visual) { VisualState(visual) }
    var full by remember { mutableStateOf(false) }
    Animate(visual, state)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 12.dp)
    ) {
        Header(visual.title, onReset = state::resetAll, onFullScreen = { full = true })
        VisualCanvas(
            visual, state,
            Modifier
                .padding(end = 8.dp)
                .fillMaxWidth()
                .height(if (visual is Scene3D) 320.dp else 280.dp)
        )
        Details(visual, state, Modifier.padding(end = 8.dp))
    }
    if (full) FullScreen(visual, state, onClose = { full = false })
}

@Composable
private fun VisualCanvas(visual: Visual, state: VisualState, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(CANVAS)) {
        when (visual) {
            is Graph2D -> Graph2DCanvas(visual, state, Modifier.fillMaxSize())
            is Scene3D -> Graph3DCanvas(visual, state, Modifier.fillMaxSize())
        }
    }
}

private val CANVAS = Color(0x33000000)

@Composable
private fun Header(title: String?, onReset: () -> Unit, onFullScreen: (() -> Unit)?, onClose: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            title?.let { MathText.plain(it) }.orEmpty(),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp)
        )
        IconButton(onClick = onReset) {
            Icon(Icons.Outlined.Refresh, contentDescription = "Reset", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        onFullScreen?.let {
            IconButton(onClick = it) {
                Icon(Icons.Outlined.OpenInFull, contentDescription = "Full screen", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        onClose?.let {
            IconButton(onClick = it) {
                Icon(Icons.Outlined.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** Legend, sliders and notes under the canvas. */
@Composable
private fun Details(visual: Visual, state: VisualState, modifier: Modifier = Modifier) {
    Column(modifier) {
        Legend(state.legend)
        visual.params.forEachIndexed { i, p -> ParamSlider(p, i, state) }
        val notes = (visual.notes + state.runtimeNotes).distinct()
        if (notes.isNotEmpty()) {
            Text(
                "Not drawn: " + notes.take(3).joinToString("; ") + if (notes.size > 3) " (+${notes.size - 3} more)" else "",
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(entries: List<LegendEntry>) {
    if (entries.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 10.dp)
    ) {
        entries.forEach { entry ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Swatch(entry)
                Spacer(Modifier.width(6.dp))
                LabelText(entry.label, Color(entry.color))
            }
        }
    }
}

@Composable
private fun Swatch(entry: LegendEntry) {
    val color = Color(entry.color)
    androidx.compose.foundation.Canvas(Modifier.size(width = 18.dp, height = 12.dp)) {
        when (entry.kind) {
            LegendKind.LINE -> drawLine(
                color,
                androidx.compose.ui.geometry.Offset(0f, size.height / 2),
                androidx.compose.ui.geometry.Offset(size.width, size.height / 2),
                2.5.dp.toPx(),
                pathEffect = when (entry.dash) {
                    Dash.SOLID -> null
                    Dash.DASHED -> PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx()))
                    Dash.DOTTED -> PathEffect.dashPathEffect(floatArrayOf(1.5.dp.toPx(), 3.dp.toPx()))
                }
            )
            LegendKind.AREA -> {
                drawRect(color.copy(alpha = 0.45f))
                drawRect(color, style = Stroke(1.dp.toPx()))
            }
            LegendKind.DOT -> drawCircle(color, 4.dp.toPx())
        }
    }
}

/** A label: typeset when it is one LaTeX formula, otherwise plain text with symbols. */
@Composable
private fun LabelText(label: String, color: Color) {
    if (MathText.isFormula(label)) {
        val context = LocalContext.current
        val density = LocalDensity.current
        val sizePx = with(density) { 14.sp.toPx() }
        val tex = label.trim().removePrefix("$").removeSuffix("$")
        val rendered = remember(tex, color) { MathRenderer.render(context, tex, sizePx, color.toArgb(), false, null) }
        if (rendered != null) {
            Image(
                rendered.image,
                contentDescription = tex,
                modifier = with(density) { Modifier.size(rendered.widthPx.toDp(), rendered.heightPx.toDp()) }
            )
            return
        }
    }
    Text(MathText.plain(label), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun ParamSlider(p: Param, index: Int, state: VisualState) {
    val value = state.sliders.getOrElse(index) { p.value }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Text(
            "${p.name} = ${Analysis.num(value)}",
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.widthIn(min = 92.dp)
        )
        val steps = p.step?.let { ((p.max - p.min) / it).roundToInt() - 1 }?.takeIf { it in 1..200 } ?: 0
        Slider(
            value = value.toFloat(),
            onValueChange = { v ->
                state.playing = null
                state.sliders[index] = p.step?.let { s -> p.min + ((v - p.min) / s).roundToInt() * s } ?: v.toDouble()
            },
            valueRange = p.min.toFloat()..p.max.toFloat(),
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = AssistantCaptureColors.Accent,
                activeTrackColor = AssistantCaptureColors.Accent,
                inactiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            ),
            modifier = Modifier.weight(1f)
        )
        if (p.animate) {
            val playing = state.playing == p.name
            IconButton(onClick = { state.playing = if (playing) null else p.name }) {
                Icon(
                    if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = if (playing) "Pause ${p.name}" else "Play ${p.name}",
                    tint = AssistantCaptureColors.Accent
                )
            }
        }
    }
}

/** Sweeps the playing slider back and forth, end to end in four seconds. */
@Composable
private fun Animate(visual: Visual, state: VisualState) {
    LaunchedEffect(state.playing) {
        val name = state.playing ?: return@LaunchedEffect
        val index = visual.params.indexOfFirst { it.name == name }
        if (index < 0) return@LaunchedEffect
        val p = visual.params[index]
        var direction = 1.0
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = (now - last) / 1e9
            last = now
            var v = state.sliders[index] + direction * (p.max - p.min) * dt / 4.0
            if (v >= p.max) {
                v = p.max
                direction = -1.0
            } else if (v <= p.min) {
                v = p.min
                direction = 1.0
            }
            state.sliders[index] = v
        }
    }
}

@Composable
private fun FullScreen(visual: Visual, state: VisualState, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        MaterialTheme(
            colorScheme = MaterialTheme.colorScheme.copy(
                surfaceVariant = AssistantCaptureColors.Field,
                onSurface = AssistantCaptureColors.Text,
                onSurfaceVariant = AssistantCaptureColors.Muted
            )
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(AssistantCaptureColors.Sheet)
                    .systemBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Header(visual.title, onReset = state::resetAll, onFullScreen = null, onClose = onClose)
                VisualCanvas(visual, state, Modifier.fillMaxWidth().weight(1f))
                Details(visual, state)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun VisualPlaceholder(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = AssistantCaptureColors.Accent)
        Spacer(Modifier.width(12.dp))
        Text(text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun VisualError(message: String, source: String) {
    var showSource by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)
    ) {
        Text("Couldn't draw this: $message", fontSize = 14.sp, color = AssistantCaptureColors.Error)
        TextButton(onClick = { showSource = !showSource }) {
            Text(if (showSource) "Hide the spec" else "Show the spec", fontSize = 13.sp)
        }
        if (showSource) {
            Text(
                source,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(end = 8.dp, bottom = 8.dp)
            )
        }
    }
}
