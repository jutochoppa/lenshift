package dev.lenshift.app.ui.visual

import android.graphics.Bitmap
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lenshift.app.ui.theme.AssistantCaptureColors
import dev.lenshift.app.visual.ImageBlocks

/** What an answer's ```image blocks show, looked up by their order in the answer. */
class AnswerVisuals(
    val image: (index: Int) -> ImageCardState?,
    val onPreview: (Bitmap) -> Unit,
    val onRetry: (index: Int) -> Unit,
    /** False when no image model is chosen, so blocks say why nothing was made. */
    val imagesEnabled: Boolean
)

/** One generated image as the answer shows it. */
sealed interface ImageCardState {
    data object Pending : ImageCardState
    data class Ready(val bitmap: Bitmap, val model: String) : ImageCardState
    data class Failed(val message: String) : ImageCardState
}

/**
 * A ```image block: the picture the image model made for it, a placeholder while it is
 * being made, the reason it failed with a retry, or, without an image model, the idea
 * itself and where to turn image generation on.
 */
@Composable
fun GeneratedImageBlock(source: String, complete: Boolean, index: Int, visuals: AnswerVisuals?) {
    val request = remember(source, complete) { ImageBlocks.parse(source, index, complete) }
    val ratio = remember(request.aspect) { ratio(request.aspect) }
    val state = visuals?.image?.invoke(index)
    DisableSelection {
        Column(Modifier.fillMaxWidth().widthIn(max = 560.dp)) {
            when {
                !complete -> Waiting("Writing the image prompt…", ratio)
                state is ImageCardState.Ready -> {
                    val bitmap = remember(state.bitmap) { state.bitmap.asImageBitmap() }
                    Image(
                        bitmap,
                        contentDescription = request.prompt,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(state.bitmap.width.toFloat() / state.bitmap.height.coerceAtLeast(1))
                            .heightIn(max = 520.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .clickable(role = Role.Image, onClickLabel = "View image") { visuals.onPreview(state.bitmap) }
                    )
                    Caption(request.prompt, state.model)
                }
                state is ImageCardState.Pending -> {
                    Waiting("Creating image…", ratio)
                    Caption(request.prompt, null)
                }
                state is ImageCardState.Failed -> Notice(
                    "Couldn't create the image: ${state.message}",
                    request.prompt,
                    action = "Retry" to { visuals.onRetry(index) }
                )
                else -> Notice(
                    "Image idea",
                    request.prompt,
                    hint = if (visuals?.imagesEnabled == false || visuals == null) {
                        "Choose an image model in Lenshift settings to create images in answers."
                    } else null
                )
            }
        }
    }
}

private fun ratio(aspect: String?): Float {
    val parts = aspect?.split(':')?.mapNotNull { it.trim().toFloatOrNull() } ?: return 1f
    return if (parts.size == 2 && parts[0] > 0 && parts[1] > 0) parts[0] / parts[1] else 1f
}

@Composable
private fun Waiting(text: String, ratio: Float) {
    val pulse by rememberInfiniteTransition(label = "image").animateFloat(
        initialValue = 0.45f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "imagePulse"
    )
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(ratio.coerceIn(0.5f, 2.4f))
            .heightIn(max = 360.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            Modifier
                .matchParentSize()
                .alpha(pulse)
                .background(AssistantCaptureColors.SkeletonHighlight.copy(alpha = 0.35f))
        )
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = AssistantCaptureColors.Accent)
            Spacer(Modifier.width(10.dp))
            Text(text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Caption(prompt: String, model: String?) {
    Row(Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
        Icon(
            Icons.Outlined.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp).size(14.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            listOfNotNull(prompt, model?.let { "made with $it" }).joinToString("  ·  "),
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun Notice(title: String, prompt: String, hint: String? = null, action: Pair<String, () -> Unit>? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = if (action == null) 12.dp else 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, fontSize = 14.sp, color = if (action != null) AssistantCaptureColors.Error else MaterialTheme.colorScheme.onSurface)
        }
        Text(prompt, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        hint?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        action?.let { (label, onClick) ->
            TextButton(onClick = onClick) { Text(label) }
        }
    }
}
