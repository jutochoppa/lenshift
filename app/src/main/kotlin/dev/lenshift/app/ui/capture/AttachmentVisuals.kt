package dev.lenshift.app.ui.capture

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.PathMeasure
import android.graphics.SweepGradient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.lenshift.app.ui.theme.AssistantCaptureColors

/**
 * Once, when it first appears: a bright line of colour traces around the content's
 * rounded border, then fades, so a newly added photo or screenshot is noticed.
 */
@Composable
fun Modifier.traceGlowOnAppear(cornerRadius: Dp): Modifier {
    val trace = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        trace.animateTo(1f, tween(TRACE_MS, easing = FastOutSlowInEasing))
        fade.animateTo(0f, tween(FADE_MS, easing = LinearEasing))
    }
    val colours = remember { AssistantCaptureColors.Glow.map { it.toArgb() }.toIntArray() }
    return this.drawWithContent {
        drawContent()
        val alpha = fade.value
        if (alpha <= 0.01f) return@drawWithContent
        val r = cornerRadius.toPx()
        val inset = 1.5.dp.toPx()
        val path = android.graphics.Path().apply {
            addRoundRect(inset, inset, size.width - inset, size.height - inset, r, r, android.graphics.Path.Direction.CW)
        }
        val measure = PathMeasure(path, true)
        val length = measure.length
        val segment = android.graphics.Path()
        measure.getSegment(0f, length * trace.value, segment, true)
        val shader = SweepGradient(size.width / 2f, size.height / 2f, colours, null)
        drawIntoCanvas { canvas ->
            val glow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 7.dp.toPx()
                strokeCap = android.graphics.Paint.Cap.ROUND
                this.shader = shader
                this.alpha = (alpha * 200).toInt()
                maskFilter = BlurMaskFilter(6.dp.toPx(), BlurMaskFilter.Blur.NORMAL)
            }
            val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 2.5.dp.toPx()
                strokeCap = android.graphics.Paint.Cap.ROUND
                this.shader = shader
                this.alpha = (alpha * 255).toInt()
            }
            canvas.nativeCanvas.drawPath(segment, glow)
            canvas.nativeCanvas.drawPath(segment, line)
        }
    }
}

/**
 * Full-screen view of an image in the chat. Pinch to zoom, drag to pan, double-tap to
 * zoom in or reset. The X or the back button closes it.
 */
@Composable
fun ImagePreview(image: Bitmap, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var scale by remember(image) { mutableFloatStateOf(1f) }
    var offset by remember(image) { mutableStateOf(Offset.Zero) }
    val bitmap = remember(image) { image.asImageBitmap() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.94f))
            // Swallow touches so nothing underneath reacts.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = "Image preview",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .pointerInput(image) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        offset = if (scale == 1f) Offset.Zero else offset + pan
                    }
                }
                .pointerInput(image) {
                    detectTapGestures(onDoubleTap = {
                        if (scale > 1.01f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                    })
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(16.dp)
                .size(48.dp)
                .clip(CircleShape)
                .background(AssistantCaptureColors.Toolbar)
                .clickable(role = Role.Button, onClick = onClose)
                .semantics { contentDescription = "Close preview" },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.Close, contentDescription = null, tint = Color.White)
        }
    }
}

private const val TRACE_MS = 900
private const val FADE_MS = 700
