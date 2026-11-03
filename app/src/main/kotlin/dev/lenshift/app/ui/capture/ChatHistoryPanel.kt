package dev.lenshift.app.ui.capture

import android.graphics.Bitmap
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lenshift.app.conversation.ChatHistoryFormat
import dev.lenshift.app.conversation.ChatThread
import dev.lenshift.app.ui.theme.AssistantCaptureColors

/**
 * Saved chats, in a panel that slides in from the left over the overlay, the way AI Mode
 * shows its history: a New chat button on top, then chats grouped by date. Each chat can
 * be deleted from its menu; everything can be cleared at the bottom.
 */
@Composable
fun ChatHistoryPanel(
    visible: Boolean,
    enabled: Boolean,
    threads: List<ChatThread>?,
    loadImage: suspend (String) -> Bitmap?,
    onOpen: (ChatThread) -> Unit,
    onDelete: (ChatThread) -> Unit,
    onClearAll: () -> Unit,
    onNewChat: () -> Unit,
    onClose: () -> Unit
) {
    AnimatedVisibility(visible, enter = fadeIn(tween(180)), exit = fadeOut(tween(160))) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose
                )
        )
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = minOf(420.dp, maxWidth * 0.86f)
        AnimatedVisibility(
            visible,
            enter = slideInHorizontally(tween(260)) { -it },
            exit = slideOutHorizontally(tween(200)) { -it }
        ) {
            Column(
                modifier = Modifier
                    .width(width)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp))
                    .background(AssistantCaptureColors.Sheet)
                    // Swallow taps so they do not reach the scrim behind the panel.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "History",
                        fontSize = 22.sp,
                        color = AssistantCaptureColors.Text,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onClose) {
                        Icon(Icons.Outlined.Close, "Close history", tint = AssistantCaptureColors.Text)
                    }
                }

                Row(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(CircleShape)
                        .background(AssistantCaptureColors.Field)
                        .clickable(role = Role.Button, onClick = onNewChat)
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.AddComment, null, tint = AssistantCaptureColors.Text, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("New chat", fontSize = 16.sp, color = AssistantCaptureColors.Text)
                }

                if (!enabled) {
                    Text(
                        "Chat history is off. Turn on \"Save chat history\" in Lenshift settings to keep new conversations.",
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = AssistantCaptureColors.Muted,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                    )
                }

                when {
                    threads == null -> Spacer(Modifier.weight(1f))
                    threads.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp)) {
                        Text("No chats yet", fontSize = 15.sp, color = AssistantCaptureColors.Muted)
                    }
                    else -> {
                        val groups = remember(threads) { ChatHistoryFormat.group(threads, System.currentTimeMillis()) }
                        LazyColumn(Modifier.weight(1f)) {
                            groups.forEach { (label, items) ->
                                item(key = "h-$label") {
                                    Text(
                                        label,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = AssistantCaptureColors.Muted,
                                        modifier = Modifier.padding(start = 24.dp, top = 18.dp, bottom = 6.dp)
                                    )
                                }
                                items.forEach { thread ->
                                    item(key = thread.id) {
                                        HistoryRow(thread, loadImage, onOpen = { onOpen(thread) }, onDelete = { onDelete(thread) })
                                    }
                                }
                            }
                        }
                        var confirmClear by remember { mutableStateOf(false) }
                        Text(
                            if (confirmClear) "Tap again to delete all chats" else "Clear all",
                            fontSize = 15.sp,
                            color = if (confirmClear) AssistantCaptureColors.Error else AssistantCaptureColors.Muted,
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .clip(CircleShape)
                                .clickable(role = Role.Button) {
                                    if (confirmClear) {
                                        confirmClear = false
                                        onClearAll()
                                    } else confirmClear = true
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    thread: ChatThread,
    loadImage: suspend (String) -> Bitmap?,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val image by produceState<Bitmap?>(null, thread.id) {
        if (thread.hasImage) value = loadImage(thread.id)
    }
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(AssistantCaptureColors.Field),
            contentAlignment = Alignment.Center
        ) {
            val bitmap = image
            if (bitmap != null) {
                Image(
                    bitmap = remember(bitmap) { bitmap.asImageBitmap() },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.AutoMirrored.Outlined.Chat, null, tint = AssistantCaptureColors.Muted, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                thread.title,
                fontSize = 16.sp,
                color = AssistantCaptureColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val turns = thread.turns.size
            Text(
                DateUtils.getRelativeTimeSpanString(thread.updatedAt).toString() +
                    "  ·  " + (if (turns == 1) "1 reply" else "$turns replies"),
                fontSize = 13.sp,
                color = AssistantCaptureColors.Muted,
                maxLines = 1
            )
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Outlined.MoreVert, "Chat options", tint = AssistantCaptureColors.Muted)
            }
            DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = AssistantCaptureColors.Field) {
                DropdownMenuItem(
                    text = { Text("Delete", color = AssistantCaptureColors.Text) },
                    leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = AssistantCaptureColors.Text) },
                    onClick = {
                        menu = false
                        onDelete()
                    }
                )
            }
        }
    }
}
