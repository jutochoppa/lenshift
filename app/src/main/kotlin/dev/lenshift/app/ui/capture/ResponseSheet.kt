package dev.lenshift.app.ui.capture

import dev.lenshift.app.conversation.ImageKey
import dev.lenshift.app.conversation.TurnImage
import dev.lenshift.app.ui.visual.AnswerVisuals
import dev.lenshift.app.ui.visual.ImageCardState
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.key
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import dev.lenshift.app.conversation.ConversationPhase
import dev.lenshift.app.conversation.ConversationState
import dev.lenshift.app.data.settings.QuickAction
import dev.lenshift.app.ui.markdown.MarkdownText
import dev.lenshift.app.ui.theme.AssistantCaptureColors

/** One saved model of one provider, as offered in the chat's model picker. */
data class ModelChoice(val profileId: String, val providerName: String, val model: String) {
    /** The part after any vendor prefix ("openai/gpt-4o" shows as "gpt-4o"). */
    val shortName: String get() = model.substringAfterLast('/')
}

/** Everything the search sheet needs to render; derived by [CaptureScreen]. */
data class SearchSheetState(
    val conversation: ConversationState,
    val thumbnail: Bitmap?,
    /** Selected screen text being searched, shown as the query in place of a thumbnail. */
    val queryText: String?,
    val activeAction: QuickAction,
    /** A selection exists but nothing was sent because the user asked to confirm first. */
    val awaitingConfirmation: Boolean,
    /** The selection is being cropped and read before the request starts. */
    val preparing: Boolean,
    val providerLabel: String,
    val voiceAvailable: Boolean,
    /** Chat plus the Google pages that fit the selection. */
    val tabs: List<ResultTab> = listOf(ResultTab.Chat),
    val selectedTab: ResultTab = ResultTab.Chat,
    val googleLoading: Boolean = false,
    val googleError: String? = null,
    /** "Ask before sending" is on and this selection has not been sent to Google yet. */
    val googleAwaitingConfirmation: Boolean = false,
    /** The selection is held until the user types, picks an action, or presses Send. */
    val readyToAsk: Boolean = false,
    val imageSearch: Boolean = true,
    /** Images waiting to be sent with the next message (photos, extra screenshots). */
    val attachments: List<Bitmap> = emptyList(),
    /** Images already sent in the thread, by attachment id. */
    val attachmentImage: (String) -> Bitmap? = { null },
    /** Whether the chat can take attachments right now (Chat tab, something to chat about). */
    val canAttach: Boolean = false,
    /** Every saved model across providers, and the one new questions go to. */
    val models: List<ModelChoice> = emptyList(),
    val currentModel: ModelChoice? = null,
    /** Pictures answers asked for, and whether an image model is chosen at all. */
    val images: Map<ImageKey, TurnImage> = emptyMap(),
    val imagesEnabled: Boolean = false
)

/**
 * The results sheet shown once something on screen is selected.
 *
 * Top to bottom: drag handle, the search field (with the selection's thumbnail), a row of
 * action tabs, a hairline, then a centred results column. On wide screens the column is
 * capped and flanked by darker gutters so line length stays readable on a tablet.
 */
@Composable
fun SearchSheet(
    state: SearchSheetState,
    onToggleExpanded: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragStopped: (Float) -> Unit,
    onActionSelected: (QuickAction) -> Unit,
    onAsk: (String) -> Unit,
    onVoice: () -> Unit,
    onConfirmSend: () -> Unit,
    onCancel: () -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onTabSelected: (ResultTab) -> Unit = {},
    onGoogleConfirm: () -> Unit = {},
    onGoogleRetry: () -> Unit = {},
    onNewChat: () -> Unit = {},
    onAddPhotos: () -> Unit = {},
    onAddScreenshot: () -> Unit = {},
    onRemoveAttachment: (Int) -> Unit = {},
    /** Opens an image from the tray or the thread full screen. */
    onPreviewImage: (Bitmap) -> Unit = {},
    /** Switches the model new questions (and follow-ups) go to. */
    onSelectModel: (ModelChoice) -> Unit = {},
    /** Makes a failed picture again. */
    onRetryImage: (ImageKey) -> Unit = {},
    googleContent: @Composable () -> Unit = {}
) {
    val conversation = state.conversation
    val dragState = rememberDraggableState(onDrag)
    val onChat = state.selectedTab == ResultTab.Chat

    Surface(
        modifier = modifier.fillMaxSize(),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = AssistantCaptureColors.Sheet,
        contentColor = AssistantCaptureColors.Text
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
          // The whole header (handle, field, tabs) moves the sheet, like the reference.
          Column(
              Modifier.draggable(
                  state = dragState,
                  orientation = Orientation.Vertical,
                  onDragStopped = { velocity -> onDragStopped(velocity) }
              )
          ) {
            // ------------------------------------------------------------- handle
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = "Resize results",
                        onClick = onToggleExpanded
                    )
                    .semantics { contentDescription = "Drag handle" },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(width = 32.dp, height = 4.dp)
                        .background(AssistantCaptureColors.Handle, CircleShape)
                )
            }

            // -------------------------------------------------------- search field
            if (onChat && state.attachments.isNotEmpty()) {
                AttachmentTray(
                    images = state.attachments,
                    onRemove = onRemoveAttachment,
                    onPreview = onPreviewImage,
                    modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 8.dp)
                )
            }
            SheetSearchField(
                thumbnail = state.thumbnail,
                queryText = state.queryText,
                onAddPhotos = if (onChat && state.canAttach) onAddPhotos else null,
                onAddScreenshot = if (onChat && state.canAttach) onAddScreenshot else null,
                placeholder = when {
                    !onChat && state.imageSearch -> "Add to your search"
                    !onChat -> "Search"
                    conversation.hasConversation -> "Ask a follow-up"
                    else -> "Ask about this selection"
                },
                loading = if (onChat) {
                    state.preparing || (conversation.isStreaming && conversation.answerText.isEmpty())
                } else state.googleLoading,
                streaming = onChat && conversation.isStreaming,
                // A held selection, or attached images, can be sent with nothing typed.
                allowEmptySend = state.readyToAsk || (onChat && state.attachments.isNotEmpty()),
                voiceAvailable = state.voiceAvailable,
                onAsk = onAsk,
                onVoice = onVoice,
                onStop = onCancel,
                modifier = Modifier.padding(horizontal = 18.dp)
            )

            // ---------------------------------------------------------------- tabs
            ResultTabsRow(
                tabs = state.tabs,
                selected = state.selectedTab,
                onSelect = onTabSelected,
                modifier = Modifier.padding(top = 10.dp)
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(AssistantCaptureColors.Divider)
            )
          }

            // ------------------------------------------------------------- results
            if (!onChat) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    // A WebView that has never loaded a page can blank the rest of the sheet,
                    // so it joins the layout only once Google is allowed to load.
                    if (!state.googleAwaitingConfirmation) googleContent()
                    when {
                        state.googleAwaitingConfirmation -> Box(
                            Modifier
                                .fillMaxSize()
                                .background(AssistantCaptureColors.Sheet)
                        ) {
                            CenteredMessage(
                                title = if (state.readyToAsk) "Search Google" else "Search Google for this?",
                                body = if (state.imageSearch) {
                                    "The selected region will be sent to Google Lens. No account is used."
                                } else {
                                    "The selected text will be sent to Google Search. No account is used."
                                },
                                actionLabel = "Search Google",
                                onAction = onGoogleConfirm,
                                horizontalPadding = 32.dp
                            )
                        }

                        state.googleError != null -> Box(
                            Modifier
                                .fillMaxSize()
                                .background(AssistantCaptureColors.Sheet)
                        ) {
                            CenteredMessage(
                                title = "Google results didn't load",
                                body = state.googleError,
                                actionLabel = "Try again",
                                onAction = onGoogleRetry,
                                horizontalPadding = 32.dp,
                                isError = true
                            )
                        }
                    }
                }
            } else BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(AssistantCaptureColors.SheetGutter)
            ) {
                val wide = maxWidth > 720.dp
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxHeight()
                        .then(if (wide) Modifier.widthIn(max = 780.dp) else Modifier)
                        .fillMaxWidth()
                        .background(AssistantCaptureColors.Sheet)
                ) {
                  Column {
                    QuickActionChips(
                        selected = state.activeAction,
                        onSelect = onActionSelected,
                        horizontalPadding = if (wide) 32.dp else 20.dp,
                        models = state.models,
                        currentModel = state.currentModel,
                        onSelectModel = onSelectModel
                    )
                    ResultsBody(
                        state = state,
                        horizontalPadding = if (wide) 32.dp else 20.dp,
                        onConfirmSend = onConfirmSend,
                        onCopy = onCopy,
                        onShare = onShare,
                        onRetry = onRetry,
                        onNewChat = onNewChat,
                        onPreviewImage = onPreviewImage,
                        onRetryImage = onRetryImage
                    )
                  }
                }
            }
        }
    }
}

@Composable
private fun SheetSearchField(
    thumbnail: Bitmap?,
    queryText: String?,
    /** Shows a "+" menu to attach photos or a screenshot; null hides it. */
    onAddPhotos: (() -> Unit)?,
    onAddScreenshot: (() -> Unit)?,
    placeholder: String,
    loading: Boolean,
    streaming: Boolean,
    voiceAvailable: Boolean,
    onAsk: (String) -> Unit,
    onVoice: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    allowEmptySend: Boolean = false
) {
    var text by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val send = {
        val q = text.trim()
        if (q.isNotEmpty() || allowEmptySend) {
            onAsk(q)
            text = ""
            // Drop focus with the keyboard, so the next tap is a fresh focus that reliably
            // brings the keyboard back (a focused field with a hidden keyboard may not).
            focusManager.clearFocus()
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(AssistantCaptureColors.Field)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
          if (onAddPhotos != null && onAddScreenshot != null) {
              AttachMenuButton(onAddPhotos = onAddPhotos, onAddScreenshot = onAddScreenshot)
          }
          // Everything left of the buttons (logo, thumbnail, text, padding) is one tap target
          // that focuses the field and asks for the keyboard. It only observes the tap, so
          // the text field still places the cursor and handles selection itself.
          Row(
              modifier = Modifier
                  .weight(1f)
                  .fillMaxHeight()
                  .pointerInput(Unit) {
                      awaitEachGesture {
                          awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                          val up = waitForUpOrCancellation(PointerEventPass.Initial)
                          if (up != null) {
                              runCatching { focusRequester.requestFocus() }
                              keyboard?.show()
                          }
                      }
                  }
                  .padding(start = 10.dp),
              verticalAlignment = Alignment.CenterVertically
          ) {
            if (onAddPhotos == null) LenshiftMark(size = 26.dp)
            if (thumbnail != null) {
                val image = remember(thumbnail) { thumbnail.asImageBitmap() }
                Spacer(Modifier.width(8.dp))
                Image(
                    bitmap = image,
                    contentDescription = "Selected region",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 44.dp, height = 36.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
            }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) {
                    Text(
                        queryText?.replace('\n', ' ') ?: placeholder,
                        color = if (queryText != null) AssistantCaptureColors.Text else AssistantCaptureColors.Muted,
                        fontSize = 18.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = TextStyle(color = AssistantCaptureColors.Text, fontSize = 18.sp),
                    cursorBrush = SolidColor(AssistantCaptureColors.Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { send() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .semantics { contentDescription = placeholder }
                )
            }
          }
            when {
                streaming -> IconButton(onClick = onStop) {
                    Icon(Icons.Outlined.StopCircle, "Stop generating", tint = AssistantCaptureColors.Text)
                }
                text.isNotBlank() -> IconButton(onClick = send) {
                    Icon(Icons.AutoMirrored.Outlined.Send, "Send", tint = AssistantCaptureColors.Accent)
                }
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    if (voiceAvailable) {
                        IconButton(onClick = onVoice) {
                            Icon(Icons.Outlined.Mic, "Voice input", tint = AssistantCaptureColors.Text)
                        }
                    }
                    if (allowEmptySend) {
                        IconButton(onClick = send) {
                            Icon(Icons.AutoMirrored.Outlined.Send, "Send", tint = AssistantCaptureColors.Accent)
                        }
                    }
                }
            }
        }

        if (loading) {
            IndeterminateLine(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(3.dp)
            )
        }
    }
}

/** A thin bar whose bright segment sweeps left to right while a request is in flight. */
@Composable
private fun IndeterminateLine(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "loadingLine")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing)),
        label = "loadingLineT"
    )
    Canvas(modifier) {
        drawRect(AssistantCaptureColors.Divider)
        val segment = size.width * 0.38f
        val start = -segment + (size.width + segment) * t
        drawRect(
            brush = Brush.horizontalGradient(
                listOf(
                    AssistantCaptureColors.Accent.copy(alpha = 0f),
                    AssistantCaptureColors.Accent,
                    Color(0xFF4C8DFF)
                ),
                startX = start,
                endX = start + segment
            ),
            topLeft = Offset(start, 0f),
            size = Size(segment, size.height)
        )
    }
}

@Composable
private fun ResultTabsRow(
    tabs: List<ResultTab>,
    selected: ResultTab,
    onSelect: (ResultTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 46.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        tabs.forEach { tab ->
            val isSelected = tab == selected
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Tab) { onSelect(tab) }
                    .padding(horizontal = 12.dp)
                    .height(48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (tab == ResultTab.Chat) {
                        Icon(
                            Icons.Outlined.AutoAwesome,
                            contentDescription = null,
                            tint = if (isSelected) AssistantCaptureColors.Accent else AssistantCaptureColors.Muted,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = tab.label,
                        fontSize = 16.sp,
                        fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                        color = if (isSelected) AssistantCaptureColors.Text else AssistantCaptureColors.Muted,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .width(28.dp)
                        .height(3.dp)
                        .background(
                            if (isSelected) AssistantCaptureColors.Text else Color.Transparent,
                            RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)
                        )
                )
            }
        }
    }
}

/** Lenshift's quick actions, offered as chips inside the Chat tab. */
@Composable
private fun QuickActionChips(
    selected: QuickAction,
    onSelect: (QuickAction) -> Unit,
    horizontalPadding: Dp,
    models: List<ModelChoice>,
    currentModel: ModelChoice?,
    onSelectModel: (ModelChoice) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = horizontalPadding, end = horizontalPadding, top = 14.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (models.isNotEmpty()) ModelPickerChip(models, currentModel, onSelectModel)
        // Optional shortcuts; "no action" is the absence of a chip, not a chip of its own.
        QuickAction.entries.filter { it != QuickAction.ASK }.forEach { action ->
            val isSelected = action == selected
            Box(
                modifier = Modifier
                    .height(36.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) AssistantCaptureColors.Accent.copy(alpha = 0.18f) else AssistantCaptureColors.Field)
                    .clickable(role = Role.Button) { onSelect(action) }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    action.label,
                    fontSize = 14.sp,
                    color = if (isSelected) AssistantCaptureColors.Accent else AssistantCaptureColors.Text
                )
            }
        }
    }
}

@Composable
private fun ResultsBody(
    state: SearchSheetState,
    horizontalPadding: Dp,
    onConfirmSend: () -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onRetry: () -> Unit,
    onNewChat: () -> Unit,
    onPreviewImage: (Bitmap) -> Unit,
    onRetryImage: (ImageKey) -> Unit
) {
    val conversation = state.conversation
    val phase = conversation.phase
    val turns = conversation.turns

    /** The pictures of answer [turn], looked up by block. */
    fun visuals(turn: Int) = AnswerVisuals(
        image = { index ->
            when (val image = state.images[ImageKey(turn, index)]) {
                null -> null
                is TurnImage.Pending -> ImageCardState.Pending
                is TurnImage.Ready -> state.attachmentImage(image.id)?.let { ImageCardState.Ready(it, image.model) }
                    ?: ImageCardState.Failed("The picture is no longer available.")
                is TurnImage.Failed -> ImageCardState.Failed(image.message)
            }
        },
        onPreview = onPreviewImage,
        onRetry = { index -> onRetryImage(ImageKey(turn, index)) },
        imagesEnabled = state.imagesEnabled
    )
    val listState = rememberLazyListState()

    // The turn in flight, shown after the answered ones: a question waiting for (or
    // streaming) its answer, or one that failed or was stopped.
    val inFlight = phase is ConversationPhase.Streaming || phase is ConversationPhase.Failed ||
        phase is ConversationPhase.Cancelled ||
        (phase is ConversationPhase.Idle && conversation.question.isNotBlank())
    val pendingIndex = 1 + turns.size * 2

    // A new question scrolls to the top of the screen, with its answer streaming below it.
    LaunchedEffect(turns.size, inFlight, conversation.question) {
        if (inFlight && listState.layoutInfo.totalItemsCount > pendingIndex) {
            listState.animateScrollToItem(pendingIndex)
        }
    }

    // A reopened chat (chip, History, long-press) starts at its end, where the user left
    // off. Once the list knows its items, jump to the last one: the list stops where the
    // end of the conversation meets the bottom of the view.
    LaunchedEffect(conversation.threadId) {
        if (turns.isEmpty() || phase !is ConversationPhase.Complete) return@LaunchedEffect
        val count = snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
        listState.scrollToItem(count - 1)
    }

    val contentKey = when {
        state.awaitingConfirmation -> "confirm"
        state.preparing && turns.isEmpty() && !inFlight -> "loading"
        turns.isEmpty() && !inFlight -> "idle"
        else -> "thread"
    }

    AnimatedContent(
        targetState = contentKey,
        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
        label = "resultsBody"
    ) { key ->
        when (key) {
            "confirm" -> CenteredMessage(
                title = "Send this selection?",
                body = "The selected region will be sent to ${state.providerLabel.ifBlank { "your provider" }}.",
                actionLabel = "Search",
                onAction = onConfirmSend,
                horizontalPadding = horizontalPadding
            )

            "loading" -> LoadingSkeleton(horizontalPadding)

            "idle" -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = horizontalPadding, vertical = 24.dp)
            ) {
                Text(
                    if (state.readyToAsk) {
                        if (state.activeAction.prompt.isBlank()) {
                            "Type a question about this selection, or pick an action. " +
                                "Press Send with nothing typed to let the model decide what to do with it."
                        } else {
                            "Type a question about this selection, or pick an action. " +
                                "Pressing Send with nothing typed uses \u201c${state.activeAction.label}\u201d."
                        }
                    } else {
                        "Ask anything about this selection, or pick an action."
                    },
                    color = AssistantCaptureColors.Muted,
                    fontSize = 15.sp,
                    lineHeight = 22.sp
                )
            }

            // One selection area for the whole thread: questions, answers and maths can be
            // selected together and copied (maths copies as its LaTeX source).
            else -> AnswerTypography { SelectionContainer {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    // Interface labels stay out of text selection; only the conversation copies.
                    item(key = "header") {
                        DisableSelection {
                            AnswerHeader(
                                modelLabel = conversation.modelLabel,
                                onNewChat = onNewChat,
                                modifier = Modifier.padding(start = horizontalPadding, end = horizontalPadding - 8.dp, top = 14.dp)
                            )
                        }
                    }
                    turns.forEachIndexed { i, turn ->
                        item(key = "q$i") {
                            UserTurn(
                                text = turn.question,
                                images = listOfNotNull(if (i == 0 && conversation.hasImage) state.thumbnail else null) +
                                    turn.attachments.mapNotNull(state.attachmentImage),
                                horizontalPadding = horizontalPadding,
                                onPreview = onPreviewImage
                            )
                        }
                        item(key = "a$i") {
                            AssistantTurn(
                                answer = turn.answer,
                                reasoning = turn.reasoning,
                                isLatest = i == turns.lastIndex && phase is ConversationPhase.Complete,
                                conversation = conversation,
                                horizontalPadding = horizontalPadding,
                                onCopy = onCopy,
                                onShare = onShare,
                                onRetry = onRetry,
                                visuals = visuals(i)
                            )
                        }
                    }
                    if (inFlight) {
                        item(key = "q-pending") {
                            UserTurn(
                                text = conversation.question,
                                images = listOfNotNull(if (turns.isEmpty() && conversation.hasImage) state.thumbnail else null) +
                                    conversation.questionAttachments.mapNotNull(state.attachmentImage),
                                horizontalPadding = horizontalPadding,
                                onPreview = onPreviewImage
                            )
                        }
                        item(key = "a-pending") {
                            Column(Modifier.padding(horizontal = horizontalPadding, vertical = 12.dp)) {
                                when (phase) {
                                    is ConversationPhase.Streaming -> if (phase.answer.isEmpty()) {
                                        ThinkingLines(phase.reasoning)
                                    } else {
                                        MarkdownText(phase.answer, selectable = false, visuals = visuals(turns.size))
                                    }

                                    is ConversationPhase.Failed -> InlineError(phase.message, onRetry)

                                    ConversationPhase.Cancelled -> Text(
                                        "Stopped.",
                                        color = AssistantCaptureColors.Muted,
                                        fontSize = 15.sp
                                    )

                                    else -> ThinkingLines("")
                                }
                            }
                        }
                    }
                    if (!conversation.notice.isNullOrBlank()) {
                        item(key = "notice") {
                            Text(
                                conversation.notice,
                                fontSize = 13.sp,
                                color = Color(0xFFFDD663),
                                modifier = Modifier.padding(horizontal = horizontalPadding, vertical = 8.dp)
                            )
                        }
                    }
                    if (phase is ConversationPhase.Complete) {
                        item(key = "disclaimer") {
                            DisableSelection {
                                Text(
                                    "AI answers can be wrong. Check anything important.",
                                    fontSize = 13.sp,
                                    color = AssistantCaptureColors.Muted,
                                    modifier = Modifier.padding(horizontal = horizontalPadding, vertical = 12.dp)
                                )
                            }
                        }
                    }
                    // Room so the latest question can scroll to the top while its answer streams.
                    item(key = "tail") { Spacer(Modifier.navigationBarsPadding().height(if (inFlight) 360.dp else 24.dp)) }
                }
            } }
        }
    }
}

/** The user's side of a turn: right-aligned bubble, with the selection on the first one. */
@Composable
private fun UserTurn(text: String, images: List<Bitmap>, horizontalPadding: Dp, onPreview: (Bitmap) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = horizontalPadding + 48.dp, end = horizontalPadding, top = 18.dp),
        horizontalAlignment = Alignment.End
    ) {
        if (images.isNotEmpty()) {
            // The selection and any attached images, right-aligned; scrolls if they overflow.
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState(), reverseScrolling = true),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                images.forEach { image ->
                    val bitmap = remember(image) { image.asImageBitmap() }
                    val aspect = (image.width.toFloat() / image.height.coerceAtLeast(1)).coerceIn(0.4f, 2.5f)
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Image you sent",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .height(150.dp)
                            .width(150.dp * aspect)
                            .clip(RoundedCornerShape(18.dp))
                            .clickable(role = Role.Image, onClickLabel = "View image") { onPreview(image) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (text.isNotBlank()) {
            Text(
                text = text,
                color = AssistantCaptureColors.Text,
                fontSize = 17.sp,
                lineHeight = 24.sp,
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .background(
                        AssistantCaptureColors.Field,
                        RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 22.dp, bottomEnd = 6.dp)
                    )
                    .padding(horizontal = 18.dp, vertical = 11.dp)
            )
        }
    }
}

/** The assistant's side of a turn, with actions under the latest answer. */
@Composable
private fun AssistantTurn(
    answer: String,
    reasoning: String?,
    isLatest: Boolean,
    conversation: ConversationState,
    horizontalPadding: Dp,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onRetry: () -> Unit,
    visuals: AnswerVisuals
) {
    var showReasoning by remember { mutableStateOf(false) }
    Column(Modifier.padding(start = horizontalPadding, end = horizontalPadding, top = 14.dp)) {
        if (!reasoning.isNullOrBlank()) {
            DisableSelection {
                Text(
                    text = if (showReasoning) "Hide reasoning" else "Show reasoning",
                    fontSize = 14.sp,
                    color = AssistantCaptureColors.Accent,
                    modifier = Modifier
                        .clickable { showReasoning = !showReasoning }
                        .padding(vertical = 6.dp)
                )
            }
            AnimatedVisibility(showReasoning) {
                Text(
                    text = reasoning,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = AssistantCaptureColors.Muted,
                    modifier = Modifier
                        .padding(vertical = 6.dp)
                        .fillMaxWidth()
                        .background(AssistantCaptureColors.Field, RoundedCornerShape(14.dp))
                        .padding(14.dp)
                )
            }
        }
        MarkdownText(answer, selectable = false, visuals = visuals)
        DisableSelection { Row(
            modifier = Modifier.padding(top = 4.dp).offset(x = (-12).dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onCopy(answer) }) {
                Icon(Icons.Outlined.ContentCopy, "Copy", tint = AssistantCaptureColors.Muted)
            }
            IconButton(onClick = { onShare(answer) }) {
                Icon(Icons.Outlined.Share, "Share", tint = AssistantCaptureColors.Muted)
            }
            if (isLatest) {
                IconButton(onClick = onRetry) {
                    Icon(Icons.Outlined.Refresh, "Regenerate", tint = AssistantCaptureColors.Muted)
                }
                Spacer(Modifier.width(8.dp))
                MetricsLine(conversation)
            }
        } }
    }
}

/** Placeholder lines while the answer has not started, with live reasoning if any. */
@Composable
private fun ThinkingLines(reasoning: String) {
    val transition = rememberInfiniteTransition(label = "thinking")
    val shimmer by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "thinkingAlpha"
    )
    Column(
        modifier = Modifier.semantics { contentDescription = "Loading answer" },
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (reasoning.isNotBlank()) {
            Text(
                reasoning.takeLast(220),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = AssistantCaptureColors.Muted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        val color = AssistantCaptureColors.Skeleton.copy(alpha = shimmer)
        listOf(0.9f, 1f, 0.7f).forEach { fraction ->
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(16.dp)
                    .background(color, RoundedCornerShape(8.dp))
            )
        }
    }
}

@Composable
private fun InlineError(message: String, onRetry: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = AssistantCaptureColors.Error,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text("Couldn't get an answer", fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
        if (message.isNotBlank()) {
            Text(
                message,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = AssistantCaptureColors.Muted,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        Surface(
            shape = CircleShape,
            color = AssistantCaptureColors.Field,
            contentColor = AssistantCaptureColors.Text,
            modifier = Modifier
                .padding(top = 12.dp)
                .clickable(role = Role.Button, onClick = onRetry)
        ) {
            Text("Try again", fontSize = 15.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
        }
    }
}

/**
 * The model new questions go to, as a chip; tapping lists every saved model grouped by
 * provider. Switching mid-conversation sends the next follow-up to the new model.
 */
@Composable
private fun ModelPickerChip(
    models: List<ModelChoice>,
    current: ModelChoice?,
    onSelect: (ModelChoice) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .height(36.dp)
                .clip(CircleShape)
                .background(AssistantCaptureColors.Field)
                .clickable(role = Role.Button, onClickLabel = "Choose model") { open = true }
                .padding(start = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = AssistantCaptureColors.Accent,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                current?.shortName ?: "Choose model",
                fontSize = 14.sp,
                color = AssistantCaptureColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp)
            )
            Icon(
                Icons.Outlined.ArrowDropDown,
                contentDescription = null,
                tint = AssistantCaptureColors.Muted,
                modifier = Modifier.size(20.dp)
            )
        }
        DropdownMenu(open, onDismissRequest = { open = false }, containerColor = AssistantCaptureColors.Field) {
            models.groupBy { it.profileId }.values.forEachIndexed { group, choices ->
                if (group > 0) HorizontalDivider(color = AssistantCaptureColors.Divider)
                Text(
                    choices.first().providerName,
                    fontSize = 12.sp,
                    color = AssistantCaptureColors.Muted,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp)
                )
                choices.forEach { choice ->
                    val selected = choice == current
                    DropdownMenuItem(
                        text = {
                            Text(
                                choice.model,
                                color = AssistantCaptureColors.Text,
                                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        trailingIcon = {
                            if (selected) Icon(Icons.Outlined.Check, contentDescription = "Current", tint = AssistantCaptureColors.Accent)
                        },
                        onClick = {
                            open = false
                            if (!selected) onSelect(choice)
                        }
                    )
                }
            }
        }
    }
}

/** "+" in the chat field: attach photos from the gallery or a new screenshot. */
@Composable
private fun AttachMenuButton(onAddPhotos: () -> Unit, onAddScreenshot: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Outlined.Add, "Add photos or a screenshot", tint = AssistantCaptureColors.Text)
        }
        DropdownMenu(open, onDismissRequest = { open = false }, containerColor = AssistantCaptureColors.Field) {
            DropdownMenuItem(
                text = { Text("Upload photos", color = AssistantCaptureColors.Text) },
                leadingIcon = { Icon(Icons.Outlined.PhotoLibrary, null, tint = AssistantCaptureColors.Text) },
                onClick = {
                    open = false
                    onAddPhotos()
                }
            )
            DropdownMenuItem(
                text = { Text("Add a screenshot", color = AssistantCaptureColors.Text) },
                leadingIcon = { Icon(Icons.Outlined.Screenshot, null, tint = AssistantCaptureColors.Text) },
                onClick = {
                    open = false
                    onAddScreenshot()
                }
            )
        }
    }
}

/** Images waiting to be sent, each removable, shown just above the chat field. */
@Composable
private fun AttachmentTray(
    images: List<Bitmap>,
    onRemove: (Int) -> Unit,
    onPreview: (Bitmap) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        images.forEachIndexed { index, image ->
          // Keyed by the image so a new one traces its glow once and removing another does not replay it.
          key(image) {
            Box(Modifier.traceGlowOnAppear(14.dp)) {
                Image(
                    bitmap = remember(image) { image.asImageBitmap() },
                    contentDescription = "Attached image ${index + 1}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(role = Role.Image, onClickLabel = "View image") { onPreview(image) }
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.65f))
                        .clickable(role = Role.Button, onClickLabel = "Remove image") { onRemove(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
          }
        }
    }
}

/** "Chat · model" with a New chat button, where AI Mode puts "New thread". */
@Composable
private fun AnswerHeader(modelLabel: String, onNewChat: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Outlined.AutoAwesome,
            contentDescription = null,
            tint = AssistantCaptureColors.Accent,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text("Chat", fontSize = 17.sp, color = AssistantCaptureColors.Text)
        if (modelLabel.isNotBlank()) {
            Text(
                "  ·  $modelLabel",
                fontSize = 14.sp,
                color = AssistantCaptureColors.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = "Start a new chat", onClick = onNewChat)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.AddComment,
                contentDescription = null,
                tint = AssistantCaptureColors.Text,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text("New chat", fontSize = 14.sp, color = AssistantCaptureColors.Text)
        }
    }
}

@Composable
private fun MetricsLine(state: ConversationState) {
    val parts = listOfNotNull(
        state.firstTokenMs?.let { "first token ${it}ms" },
        state.totalMs?.let { "total ${it}ms" },
        state.outputTokens?.let { "$it tok" }
    )
    if (parts.isNotEmpty()) {
        Text(parts.joinToString("  ·  "), fontSize = 12.sp, color = AssistantCaptureColors.Muted)
    }
}

/** Larger reading sizes for streamed answers on the dark sheet. */
@Composable
private fun AnswerTypography(content: @Composable () -> Unit) {
    val base = MaterialTheme.typography
    val body = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, color = AssistantCaptureColors.Text)
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            onSurface = AssistantCaptureColors.Text,
            onSurfaceVariant = AssistantCaptureColors.Muted,
            surfaceVariant = AssistantCaptureColors.Field
        ),
        typography = Typography(
            titleMedium = base.titleMedium.copy(color = AssistantCaptureColors.Text, fontWeight = FontWeight.Medium),
            bodyMedium = body,
            labelSmall = base.labelSmall
        ),
        content = content
    )
}

@Composable
private fun LoadingSkeleton(horizontalPadding: Dp) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val shimmer by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "skeletonAlpha"
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = horizontalPadding, vertical = 22.dp)
            .semantics { contentDescription = "Loading answer" },
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val color = AssistantCaptureColors.Skeleton.copy(alpha = shimmer)
        listOf(0.42f, 1f, 0.92f, 0.66f).forEach { fraction ->
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(16.dp)
                    .background(color, RoundedCornerShape(8.dp))
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(2) {
                Canvas(
                    Modifier
                        .weight(1f)
                        .height(180.dp)
                ) {
                    drawRoundRect(color, cornerRadius = CornerRadius(28.dp.toPx()))
                }
            }
        }
    }
}

@Composable
private fun CenteredMessage(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    horizontalPadding: Dp,
    isError: Boolean = false
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = horizontalPadding, vertical = 28.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isError) {
                Icon(
                    Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = AssistantCaptureColors.Error,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        }
        if (body.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(body, fontSize = 15.sp, lineHeight = 22.sp, color = AssistantCaptureColors.Muted)
        }
        Spacer(Modifier.height(18.dp))
        Surface(
            shape = CircleShape,
            color = if (isError) AssistantCaptureColors.Field else AssistantCaptureColors.Accent,
            contentColor = if (isError) AssistantCaptureColors.Text else Color(0xFF06201B),
            modifier = Modifier.clickable(role = Role.Button, onClick = onAction)
        ) {
            Text(
                actionLabel,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp)
            )
        }
    }
}

/**
 * Lenshift's own mark: a mint ring with an upward chevron, echoing the launcher icon.
 * Drawn in code so no raster asset is needed.
 */
@Composable
fun LenshiftMark(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val stroke = this.size.minDimension * 0.12f
        val r = this.size.minDimension / 2f - stroke / 2f
        drawCircle(
            brush = Brush.sweepGradient(
                listOf(Color(0xFF5EEAD4), Color(0xFF4C8DFF), Color(0xFF5EEAD4))
            ),
            radius = r,
            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke)
        )
        val c = center
        val arm = r * 0.5f
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(c.x - arm, c.y + arm * 0.15f)
            lineTo(c.x, c.y - arm * 0.75f)
            lineTo(c.x + arm, c.y + arm * 0.15f)
            moveTo(c.x, c.y - arm * 0.75f)
            lineTo(c.x, c.y + arm * 0.95f)
        }
        drawPath(
            path,
            Color(0xFF5EEAD4),
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round
            )
        )
    }
}
