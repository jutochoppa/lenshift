package dev.lenshift.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lenshift.app.assist.AssistTriggerLog
import dev.lenshift.app.assist.CaptureHandoff
import dev.lenshift.app.assist.LenshiftVoiceInteractionService
import dev.lenshift.app.capture.CaptureSource
import dev.lenshift.app.capture.MediaProjectionCapture
import dev.lenshift.app.capture.ScreenCaptureService
import dev.lenshift.app.conversation.ConversationController
import dev.lenshift.app.data.settings.QuickAction
import dev.lenshift.app.di.ServiceLocator
import dev.lenshift.app.ui.capture.CaptureScreen
import dev.lenshift.app.ui.capture.CaptureUiState
import dev.lenshift.app.ui.theme.LenshiftTheme
import kotlinx.coroutines.launch

/**
 * The selection surface.
 *
 * Lifecycle:
 *  1. Take the bitmap handed over by the assist session (or a share intent).
 *  2. Show it frozen, full-bleed, with the selection overlay on top.
 *  3. On confirm: crop, OCR the crop, and ask the configured model.
 *  4. On dismiss: finish and return the user to whatever was underneath.
 *
 * The activity is excluded from recents and has no task affinity, so dismissing it
 * returns the user to the app they were in rather than to Lenshift.
 */
class CaptureActivity : ComponentActivity() {

    private lateinit var conversation: ConversationController

    private var screenshot by mutableStateOf<Bitmap?>(null)
    private var source by mutableStateOf(CaptureSource.NONE)
    private var sourceLabel by mutableStateOf<String?>(null)
    private var preloadedText by mutableStateOf<String?>(null)
    private var defaultAction by mutableStateOf(QuickAction.ASK)

    /**
     * Bumped for every activation. The activity is `singleTask`, so a second assist while
     * it is alive arrives through [onNewIntent] instead of a fresh onCreate. Keying the
     * capture UI on this value discards the previous selection, OCR text and error state,
     * which would otherwise be re-applied to the new screenshot.
     */
    private var invocationId by mutableStateOf(0)

    /** Set while the user is away picking a screen to add to the current chat. */
    private var awaitingChatScreenshot = false
    private var chatScreenshot by mutableStateOf<Bitmap?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Keep the screen awake only while selecting; this is a short interaction.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        conversation = ConversationController(
            settings = ServiceLocator.settings,
            providers = ServiceLocator.providers,
            scope = lifecycleScope,
            historyStore = ServiceLocator.chatHistory
        )

        applyInvocation(intent)

        setContent {
            LenshiftTheme {
                val conversationState by conversation.state.collectAsStateWithLifecycle()
                val images by conversation.images.collectAsStateWithLifecycle()
                key(invocationId) {
                    CaptureScreen(
                        state = CaptureUiState(
                            screenshot = screenshot,
                            source = source,
                            sourceLabel = sourceLabel,
                            preloadedText = preloadedText,
                             defaultAction = defaultAction
                        ),
                        conversationState = conversationState,
                        onDismiss = { finishAndRemoveTask() },
                        onAsk = { action, selectionText, croppedImage, question, attachments ->
                            conversation.startFresh(
                                action = action,
                                selectionText = selectionText,
                                image = croppedImage,
                                sourceLabel = sourceLabel,
                                customQuestion = question,
                                attachments = attachments
                            )
                        },
                        onFollowUp = { question, display, attachments ->
                            conversation.followUp(question, display, attachments)
                        },
                        onRetryLast = { conversation.retry() },
                        onOpenThread = { thread, image, attachments -> conversation.load(thread, image, attachments) },
                        attachmentImage = conversation::attachment,
                        images = images,
                        onRetryImage = conversation::retryImage,
                        onImageModelChosen = conversation::retryImagesNeedingModel,
                        onRequestChatScreenshot = ::awaitChatScreenshot,
                        chatScreenshot = chatScreenshot,
                        onChatScreenshotDone = { chatScreenshot = null },
                        onCancelGeneration = { conversation.cancel() },
                        onResetConversation = { conversation.reset() },
                        onRequestScreenCapture = { requestMediaProjection() },
                        onCopy = { text -> copyToClipboard(text) },
                        onShare = { text -> shareText(text) }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        conversation.close()
        // Drop the bitmap as soon as the surface goes away; nothing is persisted.
        screenshot = null
        super.onDestroy()
    }

    /**
     * "Add a screenshot": steps aside, then asks the system for a fresh screenshot of the
     * screen underneath, exactly as if the assistant had been triggered again. That new
     * activation brings this chat back with the screenshot to pick from (see
     * [onNewIntent]). If the assistant session cannot be started, the user is asked to
     * trigger it themselves, which takes the same path.
     */
    private fun awaitChatScreenshot() {
        awaitingChatScreenshot = true
        moveTaskToBack(true)
        // Let the overlay finish leaving the screen so it is not in the screenshot.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!awaitingChatScreenshot) return@postDelayed
            if (!LenshiftVoiceInteractionService.requestScreenshot()) {
                android.widget.Toast.makeText(
                    this,
                    "Open Lenshift again on the screen you want to add",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }, SCREENSHOT_AFTER_HIDE_MS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (awaitingChatScreenshot) {
            awaitingChatScreenshot = false
            setIntent(intent)
            val shot = CaptureHandoff.take()
            if (shot != null) {
                chatScreenshot = shot
            } else {
                android.widget.Toast.makeText(this, "No screenshot was captured", android.widget.Toast.LENGTH_SHORT).show()
            }
            return
        }
        // A new activation replaces everything from the previous one. Without this the
        // old answer sheet, old selection and — when no new bitmap arrived — the *old
        // screenshot* stayed live and could be submitted against the new request.
        setIntent(intent)
        conversation.reset()
        applyInvocation(intent)
    }

    /**
     * Reads one activation's inputs from [launchIntent] and the out-of-band handoff.
     * Every field is overwritten, never merged, so nothing leaks between activations.
     */
    private fun applyInvocation(launchIntent: Intent?) {
        screenshot = null
        source = CaptureSource.fromId(launchIntent?.getStringExtra(EXTRA_SOURCE))
        sourceLabel = launchIntent?.getStringExtra(EXTRA_SOURCE_LABEL)
        preloadedText = launchIntent?.getStringExtra(EXTRA_TEXT)
        // A launcher shortcut may name an action; otherwise use the one chosen in settings.
        // A shortcut keeps working after the user deletes its stock action from the chips.
        val settings = ServiceLocator.settings.current
        defaultAction = launchIntent?.getStringExtra(EXTRA_DEFAULT_ACTION)
            ?.let { id -> settings.action(id) ?: QuickAction.stock(id) }
            ?: settings.defaultQuickAction

        // The assist session hands the bitmap over out-of-band.
        val handedOff = CaptureHandoff.take()
        if (handedOff != null) {
            screenshot = handedOff
            if (source == CaptureSource.NONE) source = CaptureSource.ASSIST_SCREENSHOT
        } else if (source == CaptureSource.SHARED_IMAGE) {
            loadSharedImage()
        }

        invocationId += 1
        AssistTriggerLog.recordCapture(this, source.id, ok = screenshot != null)
    }

    private fun requestMediaProjection() {
        val intent = MediaProjectionCapture.createRequestIntent(this) ?: return
        startActivityForResult(intent, REQUEST_MEDIA_PROJECTION)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_MEDIA_PROJECTION) return

        MediaProjectionCapture.recordConsent(resultCode, data)
        if (!MediaProjectionCapture.hasConsent()) {
            AssistTriggerLog.recordCapture(this, "media_projection", ok = false)
            return
        }

        // The capture cannot happen here. Android 14+ requires a mediaProjection
        // foreground service to be running before getMediaProjection(), and a virtual
        // display mirrors what is on screen — so capturing from this activity would
        // photograph Lenshift's own UI. Finish first, then let the service capture and
        // bring the selection surface back with the frame.
        ScreenCaptureService.start(this)
        finishAndRemoveTask()
    }

    private fun loadSharedImage() {
        val uri = intent?.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM) ?: return
        lifecycleScope.launch {
            val bitmap = runCatching {
                contentResolver.openInputStream(uri)?.use { stream ->
                    android.graphics.BitmapFactory.decodeStream(stream)
                }
            }.getOrNull()

            if (bitmap != null) {
                screenshot = bitmap
                source = CaptureSource.SHARED_IMAGE
            } else {
                // Plain text share: no image, but we can still ask about the text.
                val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
                if (!text.isNullOrBlank()) preloadedText = text
            }
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Lenshift", text))
    }

    private fun shareText(text: String) {
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(share, "Share answer"))
    }

    companion object {
        private const val TAG = "Lenshift/Capture"
        private const val REQUEST_MEDIA_PROJECTION = 1001
        private const val SCREENSHOT_AFTER_HIDE_MS = 450L

        const val EXTRA_SOURCE = "dev.lenshift.app.extra.SOURCE"
        const val EXTRA_DEFAULT_ACTION = "dev.lenshift.app.extra.DEFAULT_ACTION"
        const val EXTRA_TEXT = "dev.lenshift.app.extra.TEXT"
        const val EXTRA_SOURCE_LABEL = "dev.lenshift.app.extra.SOURCE_LABEL"
        const val EXTRA_MECHANISM = "dev.lenshift.app.extra.MECHANISM"

        fun intentFor(
            context: Context,
            source: CaptureSource,
            preloadedText: String? = null,
            sourceLabel: String? = null,
            mechanism: String? = null
        ): Intent = Intent(context, CaptureActivity::class.java).apply {
            putExtra(EXTRA_SOURCE, source.id)
            putExtra(EXTRA_TEXT, preloadedText)
            putExtra(EXTRA_SOURCE_LABEL, sourceLabel)
            putExtra(EXTRA_MECHANISM, mechanism)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }
}
