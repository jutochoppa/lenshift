package dev.lenshift.app.ui.capture

import android.media.ThumbnailUtils
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import dev.lenshift.app.conversation.ChatHistoryFormat
import dev.lenshift.app.conversation.ImageKey
import dev.lenshift.app.conversation.TurnImage
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.SweepGradient
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateListOf
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.material.icons.outlined.History
import dev.lenshift.app.conversation.ChatThread
import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin
import androidx.compose.animation.core.animate
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Velocity
import dev.lenshift.app.selection.ScreenText
import dev.lenshift.app.selection.ScreenWord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import dev.lenshift.app.capture.CaptureSource
import dev.lenshift.app.conversation.ConversationPhase
import dev.lenshift.app.conversation.ConversationState
import dev.lenshift.app.data.settings.QuickAction
import dev.lenshift.app.di.ServiceLocator
import dev.lenshift.app.selection.IntRect
import dev.lenshift.app.selection.PointF2
import dev.lenshift.app.selection.SelectionGeometry
import dev.lenshift.app.selection.SelectionMode
import dev.lenshift.app.ui.MainActivity
import dev.lenshift.app.ui.theme.AssistantCaptureColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

data class CaptureUiState(
    val screenshot: Bitmap?,
    val source: CaptureSource,
    val sourceLabel: String?,
    val preloadedText: String?,
    val defaultAction: QuickAction = QuickAction.ASK
)

/** Where the results sheet rests. */
private enum class SheetStop { HIDDEN, COLLAPSED, EXPANDED }

/** Fraction of the screen height at which the collapsed sheet's top edge rests. */
private const val COLLAPSED_TOP = 0.55f
private const val EXPANDED_TOP = 0.10f

/** Resting strength of the edge glow while waiting for a selection. */
private const val EDGE_GLOW_REST = 0.85f
private const val OVERLAY_PREFS = "lenshift_overlay_ui"
/** Most images one message can carry. */
private const val MAX_ATTACHMENTS = 6
private const val KEY_PILL_X = "pill_x"
private const val KEY_PILL_Y = "pill_y"

/**
 * The activated screen-search surface.
 *
 * Inactive → activated: the captured screen appears full-bleed exactly where the app was,
 * a colour glow runs around the display edge, and the top controls and the floating
 * search pill fade and rise in. Circling, scribbling or tapping selects; the screenshot
 * then shrinks into a rounded card above a results sheet while the selection is framed
 * with corner brackets and a glow. Back steps out one level at a time.
 */
@Composable
fun CaptureScreen(
    state: CaptureUiState,
    conversationState: ConversationState,
    onDismiss: () -> Unit,
    /** Starts a chat: action, selection text, selection image, typed question, attached images. */
    onAsk: (QuickAction, String?, Bitmap?, String?, List<Bitmap>) -> Unit,
    /** Continues the conversation: the text sent, what the thread shows for it, attached images. */
    onFollowUp: (String, String, List<Bitmap>) -> Unit,
    /** Re-sends the last question after a failure, or regenerates its answer. */
    onRetryLast: () -> Unit,
    /** Reopens a saved chat with its selection image and its attached images by id. */
    onOpenThread: (ChatThread, Bitmap?, Map<String, Bitmap>) -> Unit,
    onCancelGeneration: () -> Unit,
    onResetConversation: () -> Unit,
    onRequestScreenCapture: () -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    /** An image attached earlier in the current chat, by id. */
    attachmentImage: (String) -> Bitmap? = { null },
    /** Pictures answers asked for, and how to make a failed one again. */
    images: Map<ImageKey, TurnImage> = emptyMap(),
    onRetryImage: (ImageKey) -> Unit = {},
    /** Hides the overlay so the user can go to another screen and capture it for the chat. */
    onRequestChatScreenshot: () -> Unit = {},
    /** A screenshot taken for the chat, waiting for the user to pick what to add. */
    chatScreenshot: Bitmap? = null,
    onChatScreenshotDone: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val bitmap = state.screenshot

    var mode by remember { mutableStateOf(SelectionMode.LASSO) }
    var stroke by remember { mutableStateOf<List<PointF2>>(emptyList()) }
    var boxDraft by remember { mutableStateOf<Rect?>(null) }
    var selection by remember { mutableStateOf<IntRect?>(null) }
    var thumbnail by remember { mutableStateOf<Bitmap?>(null) }
    var activeAction by remember { mutableStateOf(state.defaultAction) }
    var lastQuestion by remember { mutableStateOf<String?>(null) }
    var awaitingConfirmation by remember { mutableStateOf(false) }
    var textOnlyQuery by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }

    // Results tabs: Lenshift's Chat plus Google's public results pages (no account).
    var resultTab by remember { mutableStateOf<ResultTab>(ResultTab.Chat) }
    /** "Ask before sending": whether this selection may go to Google. */
    var googleApproved by remember { mutableStateOf(false) }
    /**
     * A selection is waiting for the user: nothing has been sent anywhere. Set when
     * "Search as soon as you select" is off; cleared by the first explicit send.
     */
    var readyToAsk by remember { mutableStateOf(false) }
    /** A Google tab is showing for a held selection and has not been asked to load. */
    var googleDeferred by remember { mutableStateOf(false) }
    val google = remember { GoogleResultsController(context) }
    DisposableEffect(google) { onDispose { google.destroy() } }

    // The idle search pill can be dragged anywhere; its position is remembered.
    val overlayPrefs = remember { context.getSharedPreferences(OVERLAY_PREFS, Context.MODE_PRIVATE) }
    var pillOffset by remember {
        mutableStateOf(Offset(overlayPrefs.getFloat(KEY_PILL_X, 0f), overlayPrefs.getFloat(KEY_PILL_Y, 0f)))
    }
    var pillSize by remember { mutableStateOf(IntSize.Zero) }
    var hintVisible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(4500)
        hintVisible = false
    }
    /** Cropping and OCR run before the request exists; show loading meanwhile. */
    var preparing by remember { mutableStateOf(false) }

    // Words on the whole screen, recognised once per capture in the background so tap,
    // long-press and "Select text" respond immediately.
    var screenWords by remember { mutableStateOf<List<ScreenWord>?>(null) }
    val screenWordsReady = remember(bitmap) { CompletableDeferred<List<ScreenWord>>() }
    LaunchedEffect(bitmap) {
        val words = if (bitmap != null && ServiceLocator.settings.current.ocrEnabled) {
            recogniseScreenWords(bitmap)
        } else emptyList()
        screenWords = words
        screenWordsReady.complete(words)
    }
    suspend fun words(): List<ScreenWord> = screenWords ?: screenWordsReady.await()

    /** Selected words (reading-order indices into [screenWords]) in text-selection mode. */
    var textRange by remember { mutableStateOf<IntRange?>(null) }
    var textAnchor by remember { mutableStateOf(0) }
    var textDragging by remember { mutableStateOf(false) }
    var longPressDown by remember { mutableStateOf(false) }
    var resizing by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    // Read at call time, not captured at composition: an action may set the range and
    // search in the same event, before the next recomposition.
    fun currentSelectedText(): String? =
        textRange?.let { r -> screenWords?.let { ScreenText.text(it, r) } }
    val selectedText = currentSelectedText()

    // Observed, so choices made here (the model picker) and in settings show immediately.
    val settings by ServiceLocator.settings.state.collectAsState()
    val providerLabel = (settings.profiles.firstOrNull { it.id == settings.activeProfileId }
        ?: settings.profiles.firstOrNull())?.displayName.orEmpty()

    // ------------------------------------------------------------------ animations
    val entrance = remember { Animatable(0f) }
    val edgeGlow = remember { Animatable(0f) }
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { entrance.animateTo(1f, tween(380, easing = FastOutSlowInEasing)) }
        launch {
            delay(120)
            sweep.animateTo(1f, tween(1300, easing = FastOutSlowInEasing))
        }
        // Flash on activation, then settle to a resting glow that marks search mode.
        edgeGlow.animateTo(
            EDGE_GLOW_REST,
            keyframes {
                durationMillis = 1100
                0f at 0
                1f at 300
                0.85f at 650
                EDGE_GLOW_REST at 1100
            }
        )
    }
    LaunchedEffect(closing) {
        if (closing) {
            entrance.animateTo(0f, tween(180))
            onDismiss()
        }
    }
    val glowSpin = rememberInfiniteTransition(label = "glowSpin")
    val glowPhase by glowSpin.animateFloat(
        0f, 1f, infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "glowPhase"
    )

    val conversationActive = conversationState.phase !is ConversationPhase.Idle
    // A chat reopened from History, shown without a live selection.
    var openedThread by remember { mutableStateOf<ChatThread?>(null) }
    /** Photos and screenshots waiting to go with the next message. */
    val pendingAttachments = remember { mutableStateListOf<Bitmap>() }
    /** An image shown full screen from the tray or the thread. */
    var previewImage by remember { mutableStateOf<Bitmap?>(null) }
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_ATTACHMENTS)
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val room = (MAX_ATTACHMENTS - pendingAttachments.size).coerceAtLeast(0)
            val images = withContext(Dispatchers.IO) {
                uris.take(room).mapNotNull { decodeAttachment(context, it) }
            }
            pendingAttachments.addAll(images)
            if (images.isNotEmpty()) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        }
    }
    var historyOpen by remember { mutableStateOf(false) }
    var historyThreads by remember { mutableStateOf<List<ChatThread>?>(null) }
    /** The newest saved chat: offered by the Continue chip and a long press on History. */
    var lastThread by remember { mutableStateOf<ChatThread?>(null) }
    var lastThumbnail by remember { mutableStateOf<Bitmap?>(null) }
    /** The screen Lenshift opened on, being cropped into a reopened chat ("Add a screenshot"). */
    var screenForChat by remember { mutableStateOf<Bitmap?>(null) }
    val sheetWanted = selection != null || textRange != null || conversationActive || textOnlyQuery ||
        openedThread != null
    var expanded by remember { mutableStateOf(false) }

    // Whenever the screen is idle again, look up the newest saved chat for the chip.
    LaunchedEffect(sheetWanted) {
        if (sheetWanted) return@LaunchedEffect
        if (!ServiceLocator.settings.current.chatHistoryEnabled) {
            lastThread = null
            return@LaunchedEffect
        }
        val (thread, thumb) = withContext(Dispatchers.IO) {
            val newest = ServiceLocator.chatHistory.list().firstOrNull()
            newest to newest?.takeIf { it.hasImage }?.let { t ->
                ServiceLocator.chatHistory.image(t.id)?.let { ThumbnailUtils.extractThumbnail(it, 96, 96) }
            }
        }
        lastThread = thread
        lastThumbnail = thumb
    }

    val stop = when {
        !sheetWanted -> SheetStop.HIDDEN
        expanded -> SheetStop.EXPANDED
        else -> SheetStop.COLLAPSED
    }

    // -------------------------------------------------------------------- actions
    fun cropOf(image: Bitmap, rect: IntRect): Bitmap? = runCatching {
        Bitmap.createBitmap(image, rect.left, rect.top, rect.width, rect.height)
    }.getOrNull()

    /** The waiting attachments, handed over to a message and cleared from the tray. */
    fun takeAttachments(): List<Bitmap> = pendingAttachments.toList().also { pendingAttachments.clear() }

    fun send(action: QuickAction, question: String?) {
        activeAction = action
        lastQuestion = question
        awaitingConfirmation = false
        readyToAsk = false
        val text = currentSelectedText()
        if (text != null) {
            // Selected text is a search query: a few words on their own give "Explain" no
            // context, so ask about them directly, as a search answer would.
            val query = question ?: if (action == QuickAction.EXPLAIN && text.length <= 80) {
                "What is \"${text.replace('\n', ' ')}\"? Give a concise overview."
            } else null
            onAsk(action, text, null, query, takeAttachments())
            return
        }
        val image = bitmap
        val rect = selection
        if (image == null || rect == null) {
            textOnlyQuery = true
            onAsk(action, state.preloadedText, null, question, takeAttachments())
            return
        }
        preparing = true
        val attachments = takeAttachments()
        scope.launch {
            val crop = withContext(Dispatchers.Default) { cropOf(image, rect) }
            val text = withContext(Dispatchers.Default) {
                if (!ServiceLocator.settings.current.ocrEnabled) return@withContext null
                runCatching {
                    ServiceLocator.ocr.recognise(image, region = rect.toAndroidRect()).fullText
                }.getOrNull()?.takeIf { it.isNotBlank() }
            }
            onAsk(action, text ?: state.preloadedText, crop, question, attachments)
            preparing = false
        }
    }

    fun isImageSearch(): Boolean = textRange == null && bitmap != null

    /** The tab a new selection opens on: a compatible Google tab is kept, else the default. */
    fun tabForNewSelection(image: Boolean): ResultTab {
        val current = resultTab
        if (current is ResultTab.Google && settings.googleTabsEnabled) {
            return if (current.page.lens == image) current
            else ResultTab.Google(if (image) GooglePage.LENS_ALL else GooglePage.ALL)
        }
        return if (settings.googleTabsEnabled && settings.googleResultsFirst) {
            ResultTab.Google(if (image) GooglePage.LENS_ALL else GooglePage.ALL)
        } else ResultTab.Chat
    }

    /** Loads the open Google tab for the current selection, unless confirmation is pending. */
    fun loadGoogle(refine: String? = null) {
        val page = (resultTab as? ResultTab.Google)?.page ?: return
        googleDeferred = false
        readyToAsk = false
        if (settings.requireConfirmationBeforeSend && !googleApproved) return
        if (page.lens) {
            val image = thumbnail ?: return
            google.showImage(image, selection ?: image, page, refine ?: lastQuestion)
        } else {
            val query = refine ?: currentSelectedText() ?: lastQuestion ?: state.preloadedText ?: return
            google.showText(query.replace('\n', ' '), page)
        }
    }

    /**
     * Runs the open tab for a fresh selection. Chat asks the configured model; a Google tab
     * loads Google's page. The other tabs stay idle until opened, so nothing is sent to a
     * service the user is not looking at.
     */
    fun runActiveTab(action: QuickAction, question: String?, explicit: Boolean = false) {
        lastQuestion = question
        // Hold the selection until the user asks, unless they asked already or chose to
        // search as soon as they select.
        if (!explicit && question == null && !settings.searchOnSelect) {
            readyToAsk = true
            googleDeferred = resultTab is ResultTab.Google
            return
        }
        if (resultTab == ResultTab.Chat) {
            if (settings.requireConfirmationBeforeSend) awaitingConfirmation = true else send(action, question)
        } else {
            loadGoogle(question)
        }
    }

    /** Fixes the selection, then searches straight away unless the user asked to confirm. */
    fun commit(
        rect: IntRect,
        question: String? = null,
        action: QuickAction = state.defaultAction,
        chat: Boolean = false,
        explicit: Boolean = false
    ) {
        val image = bitmap ?: return
        onCancelGeneration()
        onResetConversation()
        textRange = null
        selection = rect
        stroke = emptyList()
        boxDraft = null
        thumbnail = cropOf(image, rect)
        activeAction = action
        expanded = false
        googleApproved = false
        resultTab = if (chat) ResultTab.Chat else tabForNewSelection(image = true)
        runActiveTab(action, question, explicit)
    }

    fun fullScreen(): IntRect? = bitmap?.let { IntRect(0, 0, it.width, it.height) }

    /** Switches to text selection over [range] of [screenWords]. */
    fun enterTextSelection(range: IntRange, anchor: Int = range.first) {
        onCancelGeneration()
        onResetConversation()
        selection = null
        thumbnail = null
        stroke = emptyList()
        boxDraft = null
        expanded = false
        textRange = range
        textAnchor = anchor
    }

    /** Searches the selected text, unless the user asked to confirm first. */
    fun searchSelectedText() {
        if (textRange == null) return
        onCancelGeneration()
        onResetConversation()
        googleApproved = false
        resultTab = tabForNewSelection(image = false)
        activeAction = state.defaultAction
        runActiveTab(activeAction, null)
    }

    /** A tap selects the text line under the finger, or a lens-sized square around it. */
    fun selectAt(p: PointF2) {
        val image = bitmap ?: return
        scope.launch {
            val ws = words()
            val slop = image.width * 0.01f
            val hit = ScreenText.wordAt(ws, p.x, p.y, slop)
            val rect = hit?.let {
                val b = ScreenText.lineBounds(ws, it)
                val pad = (b.height * 0.35f).roundToInt()
                SelectionGeometry.clamp(
                    IntRect(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad),
                    image.width, image.height
                )
            } ?: SelectionGeometry.tapBounds(p.x, p.y, image.width, image.height)
            commit(rect)
        }
    }

    /** A question typed into the idle pill, or a pill action, searches the whole screen. */
    fun askFromPill(action: QuickAction, question: String?, chat: Boolean = false) {
        keyboard?.hide()
        val all = fullScreen()
        // Pill actions are explicit requests, so they always run.
        if (all != null) commit(all, question, action, chat, explicit = true) else send(action, question)
    }

    fun askFromSheet(question: String) {
        keyboard?.hide()
        val googleTab = resultTab as? ResultTab.Google
        if (question.isBlank() && googleTab == null && pendingAttachments.isNotEmpty()) {
            // Images with nothing typed: send them on their own.
            if (conversationState.hasConversation) onFollowUp("", "", takeAttachments())
            else if (!conversationState.isStreaming) send(activeAction, null)
            return
        }
        if (question.isBlank()) {
            // Send with nothing typed: run the held selection on the open tab.
            if (googleTab != null) {
                googleApproved = true
                loadGoogle()
            } else if (!conversationState.hasConversation && !conversationState.isStreaming) {
                send(activeAction, null)
            }
            return
        }
        if (googleTab != null) {
            lastQuestion = question
            if (GoogleUrls.isAiMode(googleTab.page)) {
                // Continue the AI Mode conversation; start a new one if its box is missing.
                google.followUpInAiMode(question) { loadGoogle(question) }
            } else if (googleTab.page.lens) {
                // Image tabs: add the text to the image search, as Google's own box does.
                google.refineInPage(question) { loadGoogle(question) }
            } else {
                // Text tabs: the typed text replaces the query.
                loadGoogle(question)
            }
            return
        }
        // Once an answer exists, typed text continues the conversation instead of
        // starting over.
        if (conversationState.hasConversation) {
            onFollowUp(question, question, takeAttachments())
        } else {
            send(activeAction, question)
        }
    }

    fun clearSelection() {
        onCancelGeneration()
        onResetConversation()
        selection = null
        textRange = null
        readyToAsk = false
        googleDeferred = false
        preparing = false
        thumbnail = null
        google.reset()
        googleApproved = false
        resultTab = ResultTab.Chat
        stroke = emptyList()
        boxDraft = null
        awaitingConfirmation = false
        textOnlyQuery = false
        openedThread = null
        pendingAttachments.clear()
        expanded = false
    }

    /**
     * Starts an empty chat about the same selection. A chat reopened from History has no
     * live selection, so New chat returns to the search screen to circle or type.
     */
    fun newChat() {
        if (selection == null && textRange == null && !textOnlyQuery) {
            clearSelection()
            return
        }
        onCancelGeneration()
        onResetConversation()
        awaitingConfirmation = false
        lastQuestion = null
        pendingAttachments.clear()
        resultTab = ResultTab.Chat
    }

    fun openThread(thread: ChatThread) {
        historyOpen = false
        clearSelection()
        scope.launch {
            val (image, attachments) = withContext(Dispatchers.IO) {
                val store = ServiceLocator.chatHistory
                val selectionImage = if (thread.hasImage) store.image(thread.id) else null
                val attached = thread.turns.flatMap { it.attachments + it.images.map { g -> g.id } }
                    .mapNotNull { id -> store.attachment(id)?.let { id to it } }
                    .toMap()
                selectionImage to attached
            }
            openedThread = thread
            thumbnail = image
            resultTab = ResultTab.Chat
            onOpenThread(thread, image, attachments)
            expanded = true
        }
    }

    // Load the saved chats whenever History opens.
    LaunchedEffect(historyOpen) {
        if (historyOpen) {
            historyThreads = withContext(Dispatchers.IO) { ServiceLocator.chatHistory.list() }
        }
    }

    var pendingVoiceTarget by remember { mutableStateOf<((String) -> Unit)?>(null) }
    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }
        val target = pendingVoiceTarget
        pendingVoiceTarget = null
        if (spoken != null) target?.invoke(spoken)
    }
    val voiceIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask about your screen")
    }
    val voiceAvailable = remember {
        voiceIntent.resolveActivity(context.packageManager) != null
    }
    fun listen(target: (String) -> Unit) {
        pendingVoiceTarget = target
        runCatching { voiceLauncher.launch(voiceIntent) }.onFailure { pendingVoiceTarget = null }
    }

    BackHandler {
        when {
            historyOpen -> historyOpen = false
            expanded -> expanded = false
            sheetWanted -> clearSelection()
            else -> closing = true
        }
    }

    // ---------------------------------------------------------------------- layout
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(AssistantCaptureColors.Backdrop)
    ) {
        val density = LocalDensity.current
        val fullHeightPx = with(density) { maxHeight.toPx() }
        val expandedPx = fullHeightPx * EXPANDED_TOP
        // In text mode the screen stays full size, so rest the sheet just below the selected
        // text (when it sits low) to keep the highlight and handles in view.
        val textBottomPx: Float? = run {
            val r = textRange
            val ws = screenWords
            val image = bitmap
            if (r == null || ws == null || image == null) null
            else ScreenText.highlights(ws, r).maxOfOrNull { it.bottom }
                ?.let { it * (with(density) { maxWidth.toPx() } / image.width) }
        }
        val collapsedPx = textBottomPx
            ?.let { (it + with(density) { 72.dp.toPx() }).coerceIn(fullHeightPx * COLLAPSED_TOP, fullHeightPx * 0.82f) }
            ?: (fullHeightPx * COLLAPSED_TOP)
        var sheetTopPx by remember { mutableFloatStateOf(fullHeightPx) }
        var settleJob by remember { mutableStateOf<Job?>(null) }
        fun settle(target: Float) {
            settleJob?.cancel()
            settleJob = scope.launch {
                animate(
                    sheetTopPx, target,
                    animationSpec = spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)
                ) { value, _ -> sheetTopPx = value }
            }
        }
        val targetTop = when (stop) {
            SheetStop.HIDDEN -> fullHeightPx
            SheetStop.COLLAPSED -> collapsedPx
            SheetStop.EXPANDED -> expandedPx
        }
        LaunchedEffect(targetTop) { settle(targetTop) }
        // Scrolling down Google's results expands the sheet, like scrolling the answer does.
        SideEffect {
            google.onScrollDown = { if (!expanded) expanded = true }
            // Keep the highlighted tab on the page Google is actually showing.
            google.onPageShown = { page ->
                if (resultTab is ResultTab.Google && ResultTab.Google(page) in
                    ResultTab.forSelection(isImageSearch(), settings.googleTabsEnabled)
                ) resultTab = ResultTab.Google(page)
            }
        }

        // Scrolling the answer moves the sheet first: up expands it, and pulling down at the
        // top of the answer collapses it again, like a standard bottom sheet.
        val sheetScroll = remember(fullHeightPx, collapsedPx) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (available.y < 0f && sheetTopPx > expandedPx + 0.5f) {
                        val newTop = (sheetTopPx + available.y).coerceAtLeast(expandedPx)
                        val used = newTop - sheetTopPx
                        settleJob?.cancel()
                        sheetTopPx = newTop
                        return Offset(0f, used)
                    }
                    return Offset.Zero
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (available.y > 0f && source == NestedScrollSource.UserInput && sheetTopPx < collapsedPx - 0.5f) {
                        val newTop = (sheetTopPx + available.y).coerceAtMost(collapsedPx)
                        val used = newTop - sheetTopPx
                        settleJob?.cancel()
                        sheetTopPx = newTop
                        return Offset(0f, used)
                    }
                    return Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    val between = sheetTopPx > expandedPx + 1f && sheetTopPx < collapsedPx - 1f
                    expanded = when {
                        !between -> sheetTopPx <= expandedPx + 1f
                        available.y < -300f -> true
                        available.y > 300f -> false
                        else -> sheetTopPx < (expandedPx + collapsedPx) / 2f
                    }
                    if (!between) return Velocity.Zero
                    settle(if (expanded) expandedPx else collapsedPx)
                    return available
                }
            }
        }

        // 0 when the screenshot is full-bleed, 1 once it sits as a card above the sheet.
        val openness = ((fullHeightPx - sheetTopPx) /
            (fullHeightPx * (1f - COLLAPSED_TOP))).coerceIn(0f, 1f)
        // Text selection keeps the screen full size under the sheet, as in the idle state.
        val card = if (textRange == null) openness else 0f
        // 1 at the collapsed stop, 0 when fully expanded: the preview card and wordmark
        // step aside rather than collide in the strip left above an expanded sheet.
        val previewVisibility = if (textRange != null) 1f else ((sheetTopPx - expandedPx) /
            (collapsedPx - expandedPx)).coerceIn(0f, 1f)

        // ----------------------------------------------------------- screenshot
        val previewTop = 12.dp * card
        val previewBottomGap = 14.dp * card
        val previewHeight = if (textRange != null) maxHeight
        else with(density) { sheetTopPx.toDp() } - previewTop - previewBottomGap

        if (bitmap != null) {
            val textHighlights = remember(textRange, screenWords) {
                val r = textRange
                val ws = screenWords
                if (r == null || ws == null) emptyList() else ScreenText.highlights(ws, r)
            }
            val textHandles = remember(textRange, screenWords) {
                val r = textRange
                val ws = screenWords
                if (r == null || ws == null || r.first !in ws.indices || r.last !in ws.indices) null
                else TextHandles(
                    start = PointF2(ws[r.first].bounds.left.toFloat(), ws[r.first].bounds.bottom.toFloat()),
                    end = PointF2(ws[r.last].bounds.right.toFloat(), ws[r.last].bounds.bottom.toFloat())
                )
            }
            val gestures = object : CanvasGestures {
                override fun onStrokeUpdate(points: List<PointF2>) {
                    if (textRange != null) textRange = null
                    stroke = points
                }

                override fun onStrokeEnd(points: List<PointF2>) {
                    if (points.size >= 2) {
                        commit(SelectionGeometry.lassoBounds(points, bitmap.width, bitmap.height))
                    } else {
                        stroke = emptyList()
                    }
                }

                override fun onBoxUpdate(rect: Rect?) {
                    boxDraft = rect
                }

                override fun onBoxEnd(rect: Rect?) {
                    boxDraft = null
                    if (rect != null && rect.width > 8f && rect.height > 8f) {
                        commit(
                            SelectionGeometry.clamp(
                                IntRect(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt()),
                                bitmap.width, bitmap.height
                            )
                        )
                    }
                }

                override fun onTap(point: PointF2) {
                    if (textRange != null) {
                        // A tap away from the text selection dismisses it.
                        clearSelection()
                        return
                    }
                    selectAt(point)
                }

                override fun onLongPress(point: PointF2) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    longPressDown = true
                    textDragging = true
                    scope.launch {
                        val ws = words()
                        val hit = ScreenText.wordAt(ws, point.x, point.y, bitmap.width * 0.008f)
                        if (hit != null) {
                            enterTextSelection(hit..hit)
                            // Recognition can finish after the finger lifts; search then.
                            if (!longPressDown) {
                                textDragging = false
                                searchSelectedText()
                            }
                        } else {
                            // Not on text: treat it as selecting the thing under the finger.
                            textDragging = false
                            selectAt(point)
                        }
                    }
                }

                override fun onLongPressDrag(point: PointF2) {
                    val ws = screenWords ?: return
                    if (textRange == null) return
                    val i = ScreenText.nearestWord(ws, point.x, point.y) ?: return
                    textRange = ScreenText.extend(textAnchor, i)
                }

                override fun onLongPressEnd() {
                    longPressDown = false
                    // Still waiting for recognition: the long-press handler searches.
                    if (!textDragging || textRange == null) return
                    textDragging = false
                    searchSelectedText()
                }

                override fun onResize(rect: IntRect) {
                    resizing = true
                    selection = rect
                }

                override fun onResizeEnd(rect: IntRect) {
                    resizing = false
                    commit(rect)
                }

                override fun onHandleDrag(isStart: Boolean, point: PointF2) {
                    val ws = screenWords ?: return
                    val range = textRange ?: return
                    textDragging = true
                    val i = ScreenText.nearestWord(ws, point.x, point.y) ?: return
                    val moved = ScreenText.moveHandle(range, isStart, i)
                    if (moved != range) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        textRange = moved
                    }
                }

                override fun onHandleEnd() {
                    textDragging = false
                    searchSelectedText()
                }
            }

            ScreenshotCanvas(
                bitmap = bitmap,
                mode = mode,
                stroke = stroke,
                boxDraft = boxDraft,
                selection = selection,
                textHighlights = textHighlights,
                textHandles = textHandles,
                cornerRadius = 26.dp * card,
                glowPhase = { glowPhase },
                gestures = gestures,
                modifier = Modifier
                    .offset { IntOffset(0, previewTop.roundToPx()) }
                    .graphicsLayer { alpha = previewVisibility }
                    .fillMaxWidth()
                    .height(previewHeight.coerceAtLeast(0.dp))
            ) { mapper ->
                val ws = screenWords
                val range = textRange
                if (range != null && ws != null && !textDragging && textHighlights.isNotEmpty()) {
                    val first = mapper.toCanvas(textHighlights.first())
                    val last = mapper.toCanvas(textHighlights.last())
                    AnchoredPopup(
                        centerX = first.center.x,
                        above = first.top - with(density) { 10.dp.toPx() },
                        below = last.bottom + with(density) { 34.dp.toPx() }
                    ) {
                        TextToolbar(
                            onCopy = { currentSelectedText()?.let(onCopy) },
                            onTranslate = {
                                resultTab = ResultTab.Chat
                                send(QuickAction.TRANSLATE, null)
                            }
                        )
                    }
                }
                val region = selection
                if (region != null && !resizing && range == null && ws != null && stroke.isEmpty()) {
                    val inside = remember(region, ws) { ScreenText.wordsIn(ws, region) }
                    if (inside != null) {
                        val frame = mapper.toCanvas(region)
                        AnchoredPopup(
                            centerX = frame.center.x,
                            above = frame.top - with(density) { 20.dp.toPx() },
                            below = frame.bottom + with(density) { 20.dp.toPx() },
                            preferBelow = true
                        ) {
                            SelectTextChip {
                                enterTextSelection(inside)
                                searchSelectedText()
                            }
                        }
                    }
                }
            }
        } else {
            EmptyCaptureState(
                source = state.source,
                hasText = !state.preloadedText.isNullOrBlank(),
                onRequestScreenCapture = onRequestScreenCapture,
                modifier = Modifier.alpha(1f - openness)
            )
        }

        // Neutral dim plus a soft colour wash at the top and bottom edges, so search mode
        // is unmistakable over any content while the screen stays readable.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = entrance.value * (1f - card) }
                .background(AssistantCaptureColors.IdleDim)
                .background(
                    Brush.verticalGradient(
                        0f to AssistantCaptureColors.IdleTintTop,
                        0.32f to Color.Transparent,
                        0.68f to Color.Transparent,
                        1f to AssistantCaptureColors.IdleTintBottom
                    )
                )
        )

        // Search-mode cues: a light band sweeps once across the screen on activation, and a
        // gently breathing colour glow rests on the display edge until something is selected.
        val idleness by animateFloatAsState(
            targetValue = if (sheetWanted || closing) 0f else 1f,
            animationSpec = tween(260),
            label = "idleness"
        )
        Canvas(Modifier.fillMaxSize()) {
            val t = sweep.value
            if (t <= 0f || t >= 1f) return@Canvas
            val diagonal = size.width + size.height
            val center = -0.25f * diagonal + t * 1.5f * diagonal
            val band = diagonal * 0.18f
            drawRect(
                Brush.linearGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.45f to Color.White.copy(alpha = 0.10f),
                        0.5f to AssistantCaptureColors.Accent.copy(alpha = 0.14f),
                        0.55f to Color(0xFF4C8DFF).copy(alpha = 0.10f),
                        1f to Color.Transparent
                    ),
                    start = Offset(center - band, center - band - size.height),
                    end = Offset(center + band, center + band - size.height)
                )
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            val breathing = if (edgeGlow.value <= EDGE_GLOW_REST + 0.01f) {
                0.75f + 0.25f * sin(glowPhase * 4f * PI.toFloat())
            } else 1f
            val a = edgeGlow.value * breathing * idleness
            if (a <= 0.01f) return@Canvas
            val colors = AssistantCaptureColors.Glow.map { it.toArgb() }.toIntArray()
            drawIntoCanvas { canvas ->
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = 22.dp.toPx()
                    shader = SweepGradient(center.x, center.y, colors, null).apply {
                        setLocalMatrix(android.graphics.Matrix().apply {
                            setRotate(glowPhase * 360f, center.x, center.y)
                        })
                    }
                    alpha = (a * 235).roundToInt()
                    maskFilter = BlurMaskFilter(22.dp.toPx(), BlurMaskFilter.Blur.NORMAL)
                }
                val r = 34.dp.toPx()
                val inset = 5.dp.toPx()
                canvas.nativeCanvas.drawRoundRect(inset, inset, size.width - inset, size.height - inset, r, r, paint)
            }
        }

        // ----------------------------------------------------- top scrim + controls
        Box(
            Modifier
                .fillMaxWidth()
                .height(170.dp)
                .alpha(entrance.value * (1f - card))
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.38f), Color.Black.copy(alpha = 0f))
                    )
                )
        )
        TopControls(
            openness = card,
            titleAlpha = previewVisibility,
            mode = mode,
            canSelectAll = bitmap != null,
            onHistory = { historyOpen = true },
            onHistoryLongPress = {
                val newest = lastThread
                if (newest != null) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    openThread(newest)
                } else {
                    historyOpen = true
                }
            },
            onClose = { closing = true },
            onSelectAll = { fullScreen()?.let { commit(it) } },
            onToggleBox = {
                mode = if (mode == SelectionMode.RECTANGLE) SelectionMode.LASSO else SelectionMode.RECTANGLE
            },
            onOpenSettings = {
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                closing = true
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .alpha(entrance.value)
        )

        // ------------------------------------------------------- floating pill
        AnimatedVisibility(
            visible = !sheetWanted && !closing,
            enter = fadeIn(tween(220)) + slideInVertically(tween(320, easing = FastOutSlowInEasing)) { it / 2 },
            exit = fadeOut(tween(140)) + slideOutVertically(tween(200)) { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 33.dp, start = 16.dp, end = 16.dp)
                .graphicsLayer {
                    val e = entrance.value
                    alpha = e
                    translationY = (1f - e) * 48.dp.toPx()
                }
        ) {
            val margin = with(density) { 12.dp.toPx() }
            val fullWidthPx = with(density) { maxWidth.toPx() }
            val bottomReserve = with(density) { 110.dp.toPx() }
            val topReserve = with(density) { 140.dp.toPx() }
            fun clampPill(o: Offset): Offset {
                val maxDx = ((fullWidthPx - pillSize.width) / 2f - margin).coerceAtLeast(0f)
                val minDy = -(fullHeightPx - pillSize.height - bottomReserve - topReserve).coerceAtLeast(0f)
                return Offset(o.x.coerceIn(-maxDx, maxDx), o.y.coerceIn(minDy, 0f))
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .offset { clampPill(pillOffset).let { IntOffset(it.x.roundToInt(), it.y.roundToInt()) } }
                    .onSizeChanged { pillSize = it }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { hintVisible = false },
                            onDragEnd = {
                                val o = clampPill(pillOffset)
                                pillOffset = o
                                overlayPrefs.edit().putFloat(KEY_PILL_X, o.x).putFloat(KEY_PILL_Y, o.y).apply()
                            }
                        ) { change, amount ->
                            change.consume()
                            pillOffset = clampPill(pillOffset + amount)
                        }
                    }
            ) {
            AnimatedVisibility(visible = hintVisible, enter = fadeIn(), exit = fadeOut(tween(400))) {
                Text(
                    text = "Circle, tap, or long-press text to search",
                    color = AssistantCaptureColors.Text,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .padding(bottom = 12.dp)
                        .clip(CircleShape)
                        .background(AssistantCaptureColors.Pill.copy(alpha = 0.72f))
                        .padding(horizontal = 18.dp, vertical = 9.dp)
                )
            }
            val resumable = lastThread?.let { ChatHistoryFormat.resumable(listOf(it), System.currentTimeMillis()) }
            AnimatedVisibility(
                visible = resumable != null,
                enter = fadeIn(tween(260)) + expandVertically(tween(260)),
                exit = fadeOut(tween(160)) + shrinkVertically(tween(160))
            ) {
                resumable?.let { thread ->
                    ContinueChip(
                        title = thread.title,
                        ago = ChatHistoryFormat.ago(thread.updatedAt, System.currentTimeMillis()),
                        thumbnail = lastThumbnail,
                        onClick = { openThread(thread) }
                    )
                }
            }
            SearchPill(
                voiceAvailable = voiceAvailable,
                onAsk = { q -> askFromPill(activeAction, q) },
                onVoice = { listen { q -> askFromPill(activeAction, q) } },
                onTranslate = { askFromPill(QuickAction.TRANSLATE, null, chat = true) },
                onExtractText = { askFromPill(QuickAction.COPY_TEXT, null, chat = true) }
            )
            }
        }

        // ------------------------------------------------------- results sheet
        if (sheetTopPx < fullHeightPx - 1f) {
            SearchSheet(
                state = SearchSheetState(
                    conversation = conversationState,
                    thumbnail = thumbnail,
                    queryText = selectedText,
                    activeAction = activeAction,
                    awaitingConfirmation = awaitingConfirmation,
                    preparing = preparing,
                    providerLabel = providerLabel,
                    voiceAvailable = voiceAvailable,
                    // A reopened chat has no live selection to send to Google.
                    tabs = ResultTab.forSelection(isImageSearch(), settings.googleTabsEnabled && openedThread == null),
                    selectedTab = resultTab,
                    googleLoading = google.loading,
                    googleError = google.error,
                    googleAwaitingConfirmation = resultTab is ResultTab.Google &&
                        (googleDeferred || (settings.requireConfirmationBeforeSend && !googleApproved)),
                    readyToAsk = readyToAsk,
                    imageSearch = isImageSearch(),
                    attachments = pendingAttachments.toList(),
                    attachmentImage = attachmentImage,
                    canAttach = resultTab == ResultTab.Chat,
                    models = settings.profiles.flatMap { p ->
                        p.allModels.filterNot { p.isImageOnly(it) }.map { ModelChoice(p.id, p.displayName, it) }
                    },
                    currentModel = (settings.profiles.firstOrNull { it.id == settings.activeProfileId }
                        ?: settings.profiles.firstOrNull())
                        ?.let { p -> p.modelId.takeIf(String::isNotBlank)?.let { ModelChoice(p.id, p.displayName, it) } },
                    images = images,
                    imagesEnabled = remember(settings) { ServiceLocator.settings.imageModel() != null }
                ),
                onAddPhotos = {
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onAddScreenshot = {
                    if (pendingAttachments.size < MAX_ATTACHMENTS) {
                        // A reopened chat asks about the screen Lenshift was opened on: it is
                        // already captured, so crop it here instead of stepping aside.
                        if (openedThread != null && bitmap != null) screenForChat = bitmap else onRequestChatScreenshot()
                    }
                },
                onRemoveAttachment = { index ->
                    if (index in pendingAttachments.indices) pendingAttachments.removeAt(index)
                },
                onPreviewImage = { previewImage = it },
                onSelectModel = { choice -> ServiceLocator.settings.selectModel(choice.profileId, choice.model) },
                onRetryImage = onRetryImage,
                onTabSelected = { tab ->
                    resultTab = tab
                    if (readyToAsk) {
                        // A held selection stays held; Google tabs offer their own Search button.
                        googleDeferred = tab is ResultTab.Google
                    } else if (tab == ResultTab.Chat) {
                        // Chat runs lazily: only now does the selection go to the model.
                        val hasSubject = selection != null || textRange != null || textOnlyQuery || bitmap == null
                        if (conversationState.phase is ConversationPhase.Idle && hasSubject &&
                            !preparing && !awaitingConfirmation
                        ) {
                            if (settings.requireConfirmationBeforeSend) awaitingConfirmation = true
                            else send(activeAction, lastQuestion)
                        }
                    } else {
                        loadGoogle()
                    }
                },
                onGoogleConfirm = {
                    googleApproved = true
                    loadGoogle()
                },
                onGoogleRetry = { google.retry() },
                googleContent = { GoogleResultsPane(google, Modifier.fillMaxSize()) },
                onToggleExpanded = { expanded = !expanded },
                onDrag = { delta ->
                    settleJob?.cancel()
                    sheetTopPx = (sheetTopPx + delta).coerceIn(expandedPx, fullHeightPx)
                },
                onDragStopped = { velocity ->
                    val y = sheetTopPx / fullHeightPx
                    when {
                        velocity > 2500f && !expanded || y > 0.8f -> clearSelection()
                        velocity < -1200f || y < (COLLAPSED_TOP + EXPANDED_TOP) / 2f -> expanded = true
                        else -> expanded = false
                    }
                    // Settle explicitly: the stop may not have changed.
                    settle(
                        when {
                            !sheetWanted -> fullHeightPx
                            expanded -> expandedPx
                            else -> collapsedPx
                        }
                    )
                },
                onActionSelected = { action ->
                    activeAction = action
                    when {
                        // In a conversation, an action is another turn about the same selection.
                        conversationState.hasConversation -> onFollowUp(action.prompt, action.label, takeAttachments())
                        selection != null || textRange != null || textOnlyQuery || bitmap == null -> send(action, null)
                    }
                },
                onAsk = ::askFromSheet,
                onVoice = { listen(::askFromSheet) },
                onConfirmSend = { send(activeAction, lastQuestion) },
                onCancel = onCancelGeneration,
                onCopy = onCopy,
                onShare = onShare,
                onRetry = {
                    if (conversationState.hasConversation) onRetryLast() else send(activeAction, lastQuestion)
                },
                onNewChat = ::newChat,
                modifier = Modifier
                    .offset { IntOffset(0, sheetTopPx.roundToInt()) }
                    .nestedScroll(sheetScroll)
                    .fillMaxWidth()
                    // Sized to what is visible, so answers and pages never sit off-screen.
                    .height(with(density) { (fullHeightPx - sheetTopPx).coerceAtLeast(0f).toDp() })
                    .imePadding()
            )
        }

        // ----------------------------------------------------------- history
        ChatHistoryPanel(
            visible = historyOpen,
            enabled = settings.chatHistoryEnabled,
            threads = historyThreads,
            loadImage = { id -> withContext(Dispatchers.IO) { ServiceLocator.chatHistory.image(id) } },
            onOpen = ::openThread,
            onDelete = { thread ->
                scope.launch {
                    historyThreads = withContext(Dispatchers.IO) {
                        ServiceLocator.chatHistory.delete(thread.id)
                        ServiceLocator.chatHistory.list()
                    }
                    if (lastThread?.id == thread.id) {
                        lastThread = historyThreads?.firstOrNull()
                        lastThumbnail = null
                    }
                }
            },
            onClearAll = {
                scope.launch {
                    withContext(Dispatchers.IO) { ServiceLocator.chatHistory.clear() }
                    historyThreads = emptyList()
                    lastThread = null
                    lastThumbnail = null
                }
            },
            onNewChat = {
                historyOpen = false
                newChat()
            },
            onClose = { historyOpen = false }
        )

        // ---------------------------------------------- screenshot for the chat
        val attachFrom = chatScreenshot ?: screenForChat
        if (attachFrom != null) {
            val done: () -> Unit = {
                if (chatScreenshot != null) {
                    onChatScreenshotDone()
                } else {
                    screenForChat = null
                }
            }
            AttachScreenshotLayer(
                screenshot = attachFrom,
                onAdd = { crop ->
                    if (pendingAttachments.size < MAX_ATTACHMENTS) {
                        pendingAttachments.add(crop)
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    }
                    done()
                },
                onCancel = done,
                onOtherScreen = if (chatScreenshot == null) {
                    {
                        screenForChat = null
                        onRequestChatScreenshot()
                    }
                } else {
                    null
                }
            )
        }

        previewImage?.let { image -> ImagePreview(image, onClose = { previewImage = null }) }
    }
}

/** Reads a picked photo, scaled so its longest side is at most 2048 px. */
private fun decodeAttachment(context: Context, uri: android.net.Uri): Bitmap? = runCatching {
    val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
    android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val longest = maxOf(info.size.width, info.size.height)
        if (longest > 2048) {
            val f = 2048f / longest
            decoder.setTargetSize((info.size.width * f).toInt(), (info.size.height * f).toInt())
        }
        decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
    }
}.getOrNull()

@Composable
private fun TopControls(
    openness: Float,
    titleAlpha: Float,
    mode: SelectionMode,
    canSelectAll: Boolean,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onHistory: () -> Unit,
    onHistoryLongPress: () -> Unit,
    onToggleBox: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }
    val chipColor = AssistantCaptureColors.FloatingButton.copy(
        alpha = AssistantCaptureColors.FloatingButton.alpha * (1f - openness)
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RoundButton(Icons.Outlined.Close, "Close", chipColor, onClose)
        Text(
            text = "Lenshift",
            modifier = Modifier
                .weight(1f)
                .alpha(titleAlpha),
            textAlign = TextAlign.Center,
            color = Color.White,
            style = TextStyle(
                fontSize = 27.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = (-0.3).sp,
                shadow = Shadow(Color.Black.copy(alpha = 0.35f * (1f - openness)), blurRadius = 12f)
            )
        )
        // History sits beside the menu, where the reference overlay places it.
        RoundButton(Icons.Outlined.History, "Chat history", chipColor, onHistory, onLongClick = onHistoryLongPress, longClickLabel = "Open your last chat")
        Spacer(Modifier.width(12.dp))
        Box {
            RoundButton(Icons.Outlined.MoreVert, "More options", chipColor, onClick = { menuOpen = true })
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = AssistantCaptureColors.Field
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            if (mode == SelectionMode.RECTANGLE) "Freehand select" else "Box select",
                            color = AssistantCaptureColors.Text
                        )
                    },
                    leadingIcon = {
                        Icon(
                            if (mode == SelectionMode.RECTANGLE) Icons.Outlined.Gesture else Icons.Outlined.CropSquare,
                            null,
                            tint = AssistantCaptureColors.Text
                        )
                    },
                    onClick = { menuOpen = false; onToggleBox() }
                )
                if (canSelectAll) {
                    DropdownMenuItem(
                        text = { Text("Select whole screen", color = AssistantCaptureColors.Text) },
                        leadingIcon = { Icon(Icons.Outlined.CropFree, null, tint = AssistantCaptureColors.Text) },
                        onClick = { menuOpen = false; onSelectAll() }
                    )
                }
                DropdownMenuItem(
                    text = { Text("Lenshift settings", color = AssistantCaptureColors.Text) },
                    leadingIcon = { Icon(Icons.Outlined.Settings, null, tint = AssistantCaptureColors.Text) },
                    onClick = { menuOpen = false; onOpenSettings() }
                )
            }
        }
    }
}

@Composable
private fun RoundButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    color: Color,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .combinedClickable(role = Role.Button, onLongClickLabel = longClickLabel, onLongClick = onLongClick, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(40.dp)
                .background(color, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
    }
}

/**
 * Above the search pill: the newest chat, with its selection as a thumbnail, its title and
 * how long ago it was. One tap reopens it.
 */
@Composable
private fun ContinueChip(title: String, ago: String, thumbnail: Bitmap?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(bottom = 10.dp)
            .widthIn(max = 460.dp)
            .clip(CircleShape)
            .background(AssistantCaptureColors.Pill.copy(alpha = 0.86f))
            .clickable(role = Role.Button, onClickLabel = "Continue chat", onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "Continue your last chat: $title, $ago" }
            .padding(start = 6.dp, end = 18.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (thumbnail != null) {
            val image = remember(thumbnail) { thumbnail.asImageBitmap() }
            Image(
                image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(34.dp).clip(CircleShape)
            )
        } else {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(AssistantCaptureColors.Sheet),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.History, contentDescription = null, tint = AssistantCaptureColors.Accent, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Text("Continue", color = AssistantCaptureColors.Accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text("  ·  ", color = AssistantCaptureColors.Muted, fontSize = 15.sp)
        Text(
            title,
            color = AssistantCaptureColors.Text,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Text("  ·  $ago", color = AssistantCaptureColors.Muted, fontSize = 15.sp, maxLines = 1)
    }
}

/**
 * The floating pill shown while nothing is selected: an ask field plus two one-tap
 * whole-screen actions. Capped at 460dp so it floats centred on a tablet.
 */
@Composable
private fun SearchPill(
    voiceAvailable: Boolean,
    onAsk: (String) -> Unit,
    onVoice: () -> Unit,
    onTranslate: () -> Unit,
    onExtractText: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    val send = {
        val q = text.trim()
        if (q.isNotEmpty()) {
            onAsk(q)
            text = ""
        }
    }
    Row(
        modifier = Modifier
            .widthIn(max = 460.dp)
            .fillMaxWidth()
            .clip(CircleShape)
            .background(AssistantCaptureColors.Pill)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .clip(CircleShape)
                .background(AssistantCaptureColors.Sheet)
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LenshiftMark(size = 26.dp)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) {
                    Text("Ask about screen", color = AssistantCaptureColors.Muted, fontSize = 18.sp, maxLines = 1)
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
                        .semantics { contentDescription = "Ask about screen" }
                )
            }
            if (text.isNotBlank()) {
                PillIcon(Icons.AutoMirrored.Outlined.Send, "Send", Color.Transparent, AssistantCaptureColors.Accent, send)
            } else if (voiceAvailable) {
                PillIcon(Icons.Outlined.Mic, "Voice input", Color.Transparent, AssistantCaptureColors.Text, onVoice)
            }
        }
        PillIcon(Icons.Outlined.Translate, "Translate screen", AssistantCaptureColors.Sheet, AssistantCaptureColors.Text, onTranslate)
        PillIcon(Icons.Outlined.TextFields, "Extract text", AssistantCaptureColors.Sheet, AssistantCaptureColors.Text, onExtractText)
    }
}

@Composable
private fun PillIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    background: Color,
    tint: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun EmptyCaptureState(
    source: CaptureSource,
    hasText: Boolean,
    onRequestScreenCapture: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (hasText) "Ask about the shared text" else "No screenshot available",
            color = AssistantCaptureColors.Text,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = when {
                hasText -> "Type a question below or pick an action."
                source == CaptureSource.NONE ->
                    "The system did not hand Lenshift a screenshot. Grant one-time screen " +
                        "capture, or enable the accessibility fallback in settings for a " +
                        "prompt-free capture."
                else -> "The capture did not produce an image."
            },
            color = AssistantCaptureColors.Muted,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 520.dp)
        )
        if (!hasText) {
            Spacer(Modifier.height(20.dp))
            TextButton(onClick = onRequestScreenCapture) {
                Text("Capture screen once", color = AssistantCaptureColors.Accent, fontSize = 16.sp)
            }
        }
    }
}

/** Words recognised on the whole screen, in reading order, with line numbers. */
private suspend fun recogniseScreenWords(bitmap: Bitmap): List<ScreenWord> {
    val result = runCatching { ServiceLocator.ocr.recognise(bitmap) }.getOrNull() ?: return emptyList()
    val words = mutableListOf<ScreenWord>()
    var line = 0
    result.blocks.forEach { block ->
        block.lines.forEach { l ->
            l.words.forEach { w ->
                // Low-confidence tokens are usually OCR noise from images and icons.
                if (w.text.isNotBlank() && w.confidence >= 35f) {
                    words += ScreenWord(w.text, IntRect.fromAndroid(w.bounds), line)
                }
            }
            line++
        }
    }
    return ScreenText.normalize(words)
}

/**
 * Places [content] centred on [centerX], above [above] when it fits (or below [below] when
 * [preferBelow]), falling back to the other side and staying inside the parent.
 */
@Composable
private fun AnchoredPopup(
    centerX: Float,
    above: Float,
    below: Float,
    preferBelow: Boolean = false,
    content: @Composable () -> Unit
) {
    val margin = with(LocalDensity.current) { 8.dp.roundToPx() }
    Layout(content = content, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
        val placeable = measurables.first().measure(Constraints())
        val maxLeft = (constraints.maxWidth - placeable.width - margin).coerceAtLeast(margin)
        val left = (centerX - placeable.width / 2f).roundToInt().coerceIn(margin, maxLeft)
        val aboveTop = (above - placeable.height).roundToInt()
        val belowTop = below.roundToInt()
        val fitsAbove = aboveTop >= margin
        val fitsBelow = belowTop + placeable.height <= constraints.maxHeight - margin
        val top = when {
            preferBelow && fitsBelow -> belowTop
            !preferBelow && fitsAbove -> aboveTop
            fitsBelow -> belowTop
            else -> aboveTop.coerceAtLeast(margin)
        }
        layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(left, top) }
    }
}

/** The floating actions shown above a text selection. */
@Composable
private fun TextToolbar(onCopy: () -> Unit, onTranslate: () -> Unit) {
    Row(
        modifier = Modifier
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .background(AssistantCaptureColors.Toolbar)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToolbarAction("Copy", onCopy)
        ToolbarAction("Translate", onTranslate)
    }
}

@Composable
private fun ToolbarAction(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = AssistantCaptureColors.Text, fontSize = 16.sp)
    }
}

/** Offered under a region selection that contains recognised text. */
@Composable
private fun SelectTextChip(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(48.dp)
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .background(AssistantCaptureColors.Toolbar)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Text("Select text", color = AssistantCaptureColors.Text, fontSize = 16.sp)
    }
}
