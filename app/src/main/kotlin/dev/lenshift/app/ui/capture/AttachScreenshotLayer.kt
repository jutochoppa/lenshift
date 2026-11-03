package dev.lenshift.app.ui.capture

import androidx.compose.material.icons.outlined.Screenshot
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lenshift.app.selection.ContentTrim
import dev.lenshift.app.selection.IntRect
import dev.lenshift.app.selection.PointF2
import dev.lenshift.app.selection.SelectionGeometry
import dev.lenshift.app.selection.SelectionMode
import dev.lenshift.app.ui.theme.AssistantCaptureColors

/**
 * A fresh screenshot taken to add to the current chat. The user circles, taps, or drags
 * over part of it (or takes the whole screen); the cropped region becomes an attachment.
 * Shown over the chat, which is left exactly as it was.
 */
@Composable
fun AttachScreenshotLayer(
    screenshot: Bitmap,
    onAdd: (Bitmap) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    /** Offered when [screenshot] is the screen Lenshift opened on: go and capture another. */
    onOtherScreen: (() -> Unit)? = null
) {
    var stroke by remember { mutableStateOf<List<PointF2>>(emptyList()) }
    var boxDraft by remember { mutableStateOf<Rect?>(null) }

    BackHandler(onBack = onCancel)

    /**
     * Crops [rect]; with [trim], plain background margins left by a loose circle or a tap
     * are removed first (see [ContentTrim]), keeping a small border.
     */
    fun crop(rect: IntRect, trim: Boolean = true) {
        var safe = SelectionGeometry.clamp(rect, screenshot.width, screenshot.height)
        if (safe.isEmpty) return
        if (trim) {
            val pixels = IntArray(safe.width * safe.height)
            val trimmed = runCatching {
                screenshot.getPixels(pixels, 0, safe.width, safe.left, safe.top, safe.width, safe.height)
                val margin = (minOf(screenshot.width, screenshot.height) * TRIM_MARGIN_FRACTION).toInt().coerceAtLeast(6)
                ContentTrim.contentBounds(pixels, safe.width, safe.height, margin)
            }.getOrNull()
            if (trimmed != null) {
                safe = IntRect(
                    safe.left + trimmed.left,
                    safe.top + trimmed.top,
                    safe.left + trimmed.right,
                    safe.top + trimmed.bottom
                )
            }
        }
        runCatching { Bitmap.createBitmap(screenshot, safe.left, safe.top, safe.width, safe.height) }
            .getOrNull()?.let(onAdd)
    }

    fun lens(point: PointF2) = crop(SelectionGeometry.tapBounds(point.x, point.y, screenshot.width, screenshot.height))

    val gestures = remember(screenshot) {
        object : CanvasGestures {
            override fun onStrokeUpdate(points: List<PointF2>) {
                stroke = points
            }

            override fun onStrokeEnd(points: List<PointF2>) {
                stroke = emptyList()
                if (points.size >= 2) crop(SelectionGeometry.lassoBounds(points, screenshot.width, screenshot.height))
            }

            override fun onBoxUpdate(rect: Rect?) {
                boxDraft = rect
            }

            override fun onBoxEnd(rect: Rect?) {
                boxDraft = null
            }

            override fun onTap(point: PointF2) = lens(point)
            override fun onLongPress(point: PointF2) = lens(point)
            override fun onLongPressDrag(point: PointF2) = Unit
            override fun onLongPressEnd() = Unit
            override fun onResize(rect: IntRect) = Unit
            override fun onResizeEnd(rect: IntRect) = Unit
            override fun onHandleDrag(isStart: Boolean, point: PointF2) = Unit
            override fun onHandleEnd() = Unit
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(AssistantCaptureColors.Backdrop)
    ) {
        ScreenshotCanvas(
            bitmap = screenshot,
            mode = SelectionMode.LASSO,
            stroke = stroke,
            boxDraft = boxDraft,
            selection = null,
            textHighlights = emptyList(),
            textHandles = null,
            cornerRadius = 0.dp,
            glowPhase = { 0f },
            gestures = gestures,
            modifier = Modifier.fillMaxSize()
        )

        Box(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 18.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Dark pills keep the controls readable over any screenshot.
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(AssistantCaptureColors.Toolbar)
                    .clickable(role = Role.Button, onClick = onCancel)
                    .semantics { contentDescription = "Cancel adding a screenshot" },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.Close, contentDescription = null, tint = Color.White)
            }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) {
                Text(
                    "Circle or tap what to add to your chat",
                    color = Color.White,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(AssistantCaptureColors.Toolbar)
                        .padding(horizontal = 18.dp, vertical = 12.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            onOtherScreen?.let { other ->
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(AssistantCaptureColors.Toolbar)
                        .clickable(role = Role.Button, onClick = other)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Screenshot, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Another screen", color = Color.White, fontSize = 15.sp)
                }
                Spacer(Modifier.width(8.dp))
            }
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(AssistantCaptureColors.Toolbar)
                    .clickable(role = Role.Button) { crop(IntRect(0, 0, screenshot.width, screenshot.height), trim = false) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.CropFree, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Whole screen", color = Color.White, fontSize = 15.sp)
            }
        }
    }
}

/** Border kept around trimmed content, as a fraction of the screen's short side. */
private const val TRIM_MARGIN_FRACTION = 0.008f
