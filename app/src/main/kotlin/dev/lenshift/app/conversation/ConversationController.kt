package dev.lenshift.app.conversation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.lenshift.app.ai.ChatMessage
import dev.lenshift.app.ai.EncodedImage
import dev.lenshift.app.ai.ImageDetail
import dev.lenshift.app.ai.ImageGeneration
import dev.lenshift.app.ai.ImageRequest
import dev.lenshift.app.ai.ProviderProfile
import dev.lenshift.app.ai.ProviderRegistry
import dev.lenshift.app.ai.PromptBuilder
import dev.lenshift.app.ai.ReasoningMode
import dev.lenshift.app.ai.StreamEvent
import dev.lenshift.app.ai.AiError
import dev.lenshift.app.data.settings.QuickAction
import dev.lenshift.app.data.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import dev.lenshift.app.ai.transport.CallRegistry
import dev.lenshift.app.ai.transport.ImageEncoder
import dev.lenshift.app.visual.ImageBlocks
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns one question-answer exchange, including follow-ups.
 *
 * Responsibilities kept deliberately narrow:
 *  - hold the message history for this selection session
 *  - drive the adapter and translate [StreamEvent]s into UI state
 *  - enforce cancellation so "stop" actually stops the HTTP call
 *
 * It does NOT own the bitmap lifetime, the selection geometry, or navigation.
 */
class ConversationController(
    private val settings: SettingsRepository,
    private val providers: ProviderRegistry,
    private val scope: CoroutineScope,
    /** Where finished turns are saved when chat history is on; null disables saving. */
    private val historyStore: ChatHistoryStore? = null
) {

    private val _state = MutableStateFlow(ConversationState())
    val state: StateFlow<ConversationState> = _state.asStateFlow()

    private var job: Job? = null

    /**
     * Monotonic id for the current exchange. Bumped by every new request and by
     * [cancel]; an older coroutine compares against it before touching state, so a
     * cancelled or superseded request cannot repaint the UI.
     */
    private val requestGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    /** Opaque provider continuation token, when the protocol supports server state. */
    private var continuation: String? = null

    private var history: List<ChatMessage> = emptyList()

    // The thread being built: one id per conversation, saved after every answer.
    private var threadId: String? = null
    private var threadCreatedAt = 0L
    private var threadImage: Bitmap? = null
    private var threadImageSaved = false
    private var threadSourceLabel: String? = null
    private var firstQuestionTyped = false
    /** Fixed once the thread is first saved, so it does not change as the chat grows. */
    private var threadTitle: String? = null
    @Volatile
    private var turns: List<ChatTurn> = emptyList()

    /** The question shown for the turn in flight. */
    private var pendingQuestion = ""

    // Images of this thread by id: attached by the user, or made by the image model.
    private val attachmentImages = ConcurrentHashMap<String, Bitmap>()
    private val savedAttachments = ConcurrentHashMap.newKeySet<String>()

    private val _images = MutableStateFlow<Map<ImageKey, TurnImage>>(emptyMap())

    /** Pictures answers asked for, by answer and block. Kept apart from [state] so the
     *  background jobs that make them never race the streaming answer. */
    val images: StateFlow<Map<ImageKey, TurnImage>> = _images.asStateFlow()
    private val imageJobs = ConcurrentHashMap<ImageKey, Job>()
    private val imageRequests = ConcurrentHashMap<ImageKey, ImageBlocks.Request>()
    private var pendingAttachmentIds: List<String> = emptyList()

    /** An attached image of the current thread, for display. */
    fun attachment(id: String): Bitmap? = attachmentImages[id]

    private fun register(images: List<Bitmap>): List<String> = images.map { bitmap ->
        java.util.UUID.randomUUID().toString().also { attachmentImages[it] = bitmap }
    }

    /**
     * Starts a new exchange for a fresh selection, discarding any previous one.
     */
    fun startFresh(
        action: QuickAction,
        selectionText: String?,
        image: Bitmap?,
        sourceLabel: String?,
        customQuestion: String? = null,
        /** Extra images the user attached to the first question (photos, screenshots). */
        attachments: List<Bitmap> = emptyList()
    ) {
        cancel()
        cancelImages { true }
        continuation = null
        attachmentImages.clear()
        savedAttachments.clear()

        val typed = customQuestion?.takeIf { it.isNotBlank() }
        val question = typed ?: action.prompt
        val built = PromptBuilder.build(
            systemInstructions = settings.current.customSystemInstructions,
            selectionText = selectionText,
            question = question,
            hasImage = image != null,
            sourceLabel = sourceLabel
        )
        // The selection stays attached to the first user turn for the whole conversation,
        // so every follow-up still shows the model what was selected.
        history = built.map {
            if (it.role == ChatMessage.Role.USER) it.copy(images = listOfNotNull(image) + attachments) else it
        }
        pendingAttachmentIds = register(attachments)

        threadId = java.util.UUID.randomUUID().toString()
        threadCreatedAt = System.currentTimeMillis()
        threadImage = image
        threadImageSaved = false
        threadSourceLabel = sourceLabel
        firstQuestionTyped = typed != null
        threadTitle = null
        turns = emptyList()
        // A blank Ask shows just the selection, with no text bubble.
        pendingQuestion = typed ?: if (action.prompt.isBlank()) "" else action.label

        _state.value = ConversationState(
            phase = ConversationPhase.Idle,
            question = pendingQuestion,
            action = action,
            hasImage = image != null,
            threadId = threadId,
            questionAttachments = pendingAttachmentIds
        )

        send()
    }

    /**
     * Continues the conversation. [display] is what the thread shows for the user's turn
     * (a quick action sends its prompt but shows its label).
     */
    fun followUp(question: String, display: String = question, attachments: List<Bitmap> = emptyList()) {
        if ((question.isBlank() && attachments.isEmpty()) || history.isEmpty()) return
        cancel()
        // A question that never got an answer (failed or stopped) is replaced, so user
        // and assistant turns keep alternating.
        if (turns.isNotEmpty() && history.lastOrNull()?.role == ChatMessage.Role.USER) {
            history = history.dropLast(1)
        }
        // Images sent with nothing typed still need a little text for some providers.
        val text = question.ifBlank { if (attachments.size == 1) "(Image attached.)" else "(Images attached.)" }
        history = PromptBuilder.appendFollowUp(history, text).let { list ->
            list.dropLast(1) + list.last().copy(images = attachments)
        }
        pendingAttachmentIds = register(attachments)
        pendingQuestion = display.trim()
        _state.value = _state.value.copy(
            phase = ConversationPhase.Idle,
            question = pendingQuestion,
            questionAttachments = pendingAttachmentIds,
            notice = null
        )
        send()
    }

    /**
     * Retries the last question: re-sends it after a failure, or replaces its answer
     * (regenerate) when it completed.
     */
    fun retry() {
        if (history.isEmpty()) return
        cancel()
        if (history.last().role == ChatMessage.Role.ASSISTANT) {
            val replaced = turns.lastIndex
            cancelImages { it.turn == replaced }
            history = history.dropLast(1)
            pendingQuestion = turns.lastOrNull()?.question ?: pendingQuestion
            pendingAttachmentIds = turns.lastOrNull()?.attachments ?: pendingAttachmentIds
            turns = turns.dropLast(1)
        }
        _state.value = _state.value.copy(
            phase = ConversationPhase.Idle,
            question = pendingQuestion,
            questionAttachments = pendingAttachmentIds,
            turns = turns,
            notice = null
        )
        send()
    }

    /**
     * Reopens a saved thread so the user can read it and keep asking. [attachments] holds the
     * thread's saved attached images by id; any that could not be loaded are left out.
     */
    fun load(thread: ChatThread, image: Bitmap?, attachments: Map<String, Bitmap> = emptyMap()) {
        cancel()
        cancelImages { true }
        continuation = null
        attachmentImages.clear()
        attachmentImages.putAll(attachments)
        savedAttachments.clear()
        savedAttachments.addAll(attachments.keys)
        pendingAttachmentIds = emptyList()
        val system = settings.current.customSystemInstructions.trim().ifBlank { PromptBuilder.DEFAULT_SYSTEM.trim() }
        history = buildList {
            add(ChatMessage(ChatMessage.Role.SYSTEM, system))
            thread.turns.forEachIndexed { i, turn ->
                val images = turn.attachments.mapNotNull { attachments[it] }
                if (i == 0) {
                    add(ChatMessage(ChatMessage.Role.USER, thread.contextPrompt, listOfNotNull(image) + images))
                } else {
                    add(ChatMessage(ChatMessage.Role.USER, turn.question.ifBlank { "(Image attached.)" }, images))
                }
                add(ChatMessage(ChatMessage.Role.ASSISTANT, turn.answer))
            }
        }
        threadId = thread.id
        threadCreatedAt = thread.createdAt
        threadImage = image
        threadImageSaved = true
        threadSourceLabel = thread.sourceLabel
        firstQuestionTyped = true
        threadTitle = thread.title
        turns = thread.turns
        _images.value = thread.turns.flatMapIndexed { i, turn ->
            turn.images.map { g ->
                ImageKey(i, g.index) to if (g.id in attachments) {
                    TurnImage.Ready(g.prompt, g.id, g.model)
                } else {
                    TurnImage.Failed(g.prompt, "This picture is no longer saved.")
                }
            }
        }.toMap()
        pendingQuestion = thread.turns.last().question
        _state.value = ConversationState(
            phase = ConversationPhase.Complete(thread.turns.last().answer),
            question = pendingQuestion,
            action = dev.lenshift.app.data.settings.QuickAction.fromId(thread.actionId),
            hasImage = thread.hasImage,
            modelLabel = thread.modelLabel,
            threadId = thread.id,
            turns = thread.turns
        )
    }

    fun cancel() {
        if (_state.value.phase is ConversationPhase.Streaming) {
            val inFlight = turns.size
            cancelImages { it.turn >= inFlight }
        }
        // Invalidate first, so any event already in flight from the stopped request is
        // discarded rather than flipping the UI back to Streaming or Complete.
        requestGeneration.incrementAndGet()
        job?.cancel()
        // Coroutine cancellation does not interrupt OkHttp's blocking execute(), so also
        // abort the socket. Otherwise the provider keeps generating and billing after Stop.
        dev.lenshift.app.ai.transport.CallRegistry.cancelAll()
        job = null
        if (_state.value.phase is ConversationPhase.Streaming) {
            _state.value = _state.value.copy(phase = ConversationPhase.Cancelled)
        }
    }

    fun reset() {
        cancel()
        cancelImages { true }
        continuation = null
        history = emptyList()
        threadId = null
        threadImage = null
        threadTitle = null
        turns = emptyList()
        pendingQuestion = ""
        attachmentImages.clear()
        savedAttachments.clear()
        pendingAttachmentIds = emptyList()
        _state.value = ConversationState()
    }

    /** Saves the thread after an answer, when chat history is on. Runs off the main thread. */
    @Synchronized
    private fun persist() {
        val store = historyStore ?: return
        val id = threadId ?: return
        if (!settings.current.chatHistoryEnabled || turns.isEmpty()) return
        val first = turns.first()
        val title = threadTitle ?: ChatHistoryFormat.title(first.question, firstQuestionTyped, first.answer)
        threadTitle = title
        val ready = _images.value
        val saved = turns.mapIndexed { i, turn ->
            turn.copy(images = ready.entries
                .filter { it.key.turn == i && it.value is TurnImage.Ready }
                .map { (key, image) -> (image as TurnImage.Ready).let { GeneratedImage(key.index, it.id, it.prompt, it.model) } }
                .sortedBy { it.index })
        }
        val thread = ChatThread(
            id = id,
            createdAt = threadCreatedAt,
            updatedAt = System.currentTimeMillis(),
            title = title,
            actionId = _state.value.action.id,
            modelLabel = _state.value.modelLabel,
            sourceLabel = threadSourceLabel,
            contextPrompt = history.firstOrNull { it.role == ChatMessage.Role.USER }?.text.orEmpty(),
            hasImage = threadImage != null,
            turns = saved
        )
        val unsaved = attachmentImages.filterKeys { key ->
            key !in savedAttachments && saved.any { t -> key in t.attachments || t.images.any { it.id == key } }
        }
        runCatching { store.save(thread, if (threadImageSaved) null else threadImage, unsaved) }
            .onSuccess {
                threadImageSaved = true
                savedAttachments.addAll(unsaved.keys)
            }
            .onFailure { android.util.Log.w(TAG, "Could not save chat history", it) }
    }

    private fun send() {
        val active = providers.active()
        if (active == null) {
            android.util.Log.w(TAG, "No active provider; profiles=${settings.current.profiles.size}")
            _state.value = _state.value.copy(
                phase = ConversationPhase.Failed(
                    "No AI provider is configured yet. Open Lenshift settings to add one."
                )
            )
            return
        }
        android.util.Log.i(
            TAG,
            "Sending to ${active.profile.protocol.id} ${active.profile.modelId} " +
                "vision=${active.capabilities.vision} images=${history.sumOf { it.images.size }}"
        )

        val profile = active.profile
        if (profile.baseUrl.isBlank() || profile.modelId.isBlank()) {
            _state.value = _state.value.copy(
                phase = ConversationPhase.Failed(
                    "The active provider is missing a base URL or model id."
                )
            )
            return
        }

        // Text-only models must not receive an image part; PromptBuilder already told the
        // model no image is attached, so drop it here rather than silently discarding.
        val visionAllowed = active.capabilities.vision != dev.lenshift.app.ai.Tri.NO
        val hasImage = history.any { it.images.isNotEmpty() }

        if (hasImage && !visionAllowed) {
            _state.value = _state.value.copy(
                notice = "This model is not marked as vision-capable, so only the " +
                    "extracted text was sent."
            )
        }

        // The selection image rides on the first user turn (see startFresh).
        val visuals = PromptBuilder.visualInstructions(
            graphs = settings.current.graphsEnabled,
            images = settings.imageModel() != null
        )
        val messages = (if (visionAllowed) history else history.map { it.copy(images = emptyList()) }).mapIndexed { i, m ->
            if (i == 0 && m.role == ChatMessage.Role.SYSTEM && visuals.isNotEmpty()) m.copy(text = m.text + "\n\n" + visuals) else m
        }
        val turnIndex = turns.size

        _state.value = _state.value.copy(
            phase = ConversationPhase.Streaming(answer = "", reasoning = ""),
            modelLabel = profile.modelId,
            providerLabel = profile.displayName
        )

        // Claim the generation on the calling thread, before launching. Capturing it inside
        // the IO coroutine would race with a cancel() issued before the body first runs.
        val generation = requestGeneration.incrementAndGet()

        job = scope.launch(Dispatchers.IO) {
            // Identify this exchange. A superseded request must never write state or clear
            // the shared `job` field, or it would make the *next* request uncancellable.
            val answer = StringBuilder()
            val reasoning = StringBuilder()
            val started = System.currentTimeMillis()
            var firstTokenMs: Long? = null

            /** True while this exchange is still the current one. */
            fun current() = generation == requestGeneration.get()

            try {
                val request = dev.lenshift.app.ai.ChatRequest(
                    profile = profile,
                    apiKey = active.apiKey,
                    messages = messages,
                    capabilities = active.capabilities,
                    reasoningMode = profile.reasoningMode,
                    continuationToken = continuation
                )

                active.adapter.stream(request).collect { event ->
                    // A cancelled or superseded exchange must not repaint the UI: a late
                    // delta arriving after Stop would otherwise flip Cancelled back to
                    // Streaming, which looks like the Stop button did nothing.
                    if (!current()) return@collect
                    when (event) {
                        is StreamEvent.TextDelta -> {
                            if (firstTokenMs == null) {
                                firstTokenMs = System.currentTimeMillis() - started
                            }
                            answer.append(event.text)
                            // A picture starts as soon as its block closes.
                            if (event.text.contains('`') || event.text.contains('\n')) startImages(turnIndex, answer.toString(), final = false)
                            _state.value = _state.value.copy(
                                phase = ConversationPhase.Streaming(
                                    answer = answer.toString(),
                                    reasoning = reasoning.toString()
                                ),
                                firstTokenMs = firstTokenMs
                            )
                        }

                        is StreamEvent.ReasoningDelta -> {
                            reasoning.append(event.text)
                            _state.value = _state.value.copy(
                                phase = ConversationPhase.Streaming(
                                    answer = answer.toString(),
                                    reasoning = reasoning.toString()
                                )
                            )
                        }

                        is StreamEvent.Usage -> _state.value = _state.value.copy(
                            inputTokens = event.inputTokens,
                            outputTokens = event.outputTokens,
                            reasoningTokens = event.reasoningTokens
                        )

                        is StreamEvent.Notice -> _state.value = _state.value.copy(
                            notice = event.message
                        )

                        StreamEvent.Completed -> Unit
                    }
                }

                val finalText = answer.toString()
                if (!current()) return@launch
                if (finalText.isBlank()) {
                    val starved = reasoning.isNotEmpty()
                    _state.value = _state.value.copy(
                        phase = ConversationPhase.Failed(
                            if (starved) {
                                "The model produced reasoning but no answer. Raise the " +
                                    "maximum output tokens or lower the reasoning effort."
                            } else {
                                "The provider returned an empty answer."
                            }
                        )
                    )
                    return@launch
                }

                startImages(turnIndex, finalText, final = true)
                // Keep the assistant turn so follow-ups have context.
                history = history + ChatMessage(ChatMessage.Role.ASSISTANT, finalText)
                turns = turns + ChatTurn(pendingQuestion, finalText, reasoning.toString().ifBlank { null }, pendingAttachmentIds)

                val elapsed = System.currentTimeMillis() - started
                _state.value = _state.value.copy(
                    phase = ConversationPhase.Complete(finalText),
                    totalMs = elapsed,
                    reasoningSummary = reasoning.toString().ifBlank { null },
                    turns = turns
                )
                persist()
            } catch (e: CancellationException) {
                // Only the exchange the user actually stopped may report Cancelled. An
                // older request torn down by a retry would otherwise stamp Cancelled over
                // the new request's live stream.
                if (current()) {
                    _state.value = _state.value.copy(phase = ConversationPhase.Cancelled)
                }
                throw e
            } catch (e: AiError) {
                android.util.Log.w(TAG, "Provider error: ${e::class.simpleName}", e)
                if (current()) {
                    _state.value = _state.value.copy(phase = ConversationPhase.Failed(describe(e)))
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Unexpected failure", e)
                if (current()) {
                    _state.value = _state.value.copy(
                        phase = ConversationPhase.Failed(e.message ?: "Unexpected error")
                    )
                }
            } finally {
                // Clear the shared handle only if it still belongs to this exchange.
                // Unconditionally nulling it here let a superseded request erase the new
                // request's job, after which Stop could never cancel anything again.
                if (current()) job = null
            }
        }
    }

    // ------------------------------------------------------------------ pictures

    /** Retries a picture that failed, or makes it again. */
    fun retryImage(key: ImageKey) {
        val request = imageRequests[key] ?: return
        imageJobs.remove(key)?.cancel()
        val model = settings.imageModel()
        if (model == null) {
            setImage(key, TurnImage.Failed(request.prompt, "No image model is chosen in settings."))
            return
        }
        makeImage(key, request, model.first, model.second)
    }

    /** Stops everything, including pictures being made; the overlay is going away. */
    fun close() {
        cancel()
        cancelImages { true }
    }

    /**
     * Starts a picture for every closed ```image block of answer [turn] not started yet
     * (every block once the answer is [final]), up to [MAX_IMAGES] per answer.
     */
    private fun startImages(turn: Int, answer: String, final: Boolean) {
        val model = settings.imageModel() ?: return
        for (block in ImageBlocks.find(answer)) {
            if (!block.complete && !final) continue
            val key = ImageKey(turn, block.index)
            if (imageRequests.putIfAbsent(key, block) != null) continue
            if (block.index >= MAX_IMAGES) {
                setImage(key, TurnImage.Failed(block.prompt, "Only $MAX_IMAGES pictures are made per answer."))
                continue
            }
            if (block.prompt.isBlank()) {
                setImage(key, TurnImage.Failed(block.prompt, "The picture has no description."))
                continue
            }
            makeImage(key, block, model.first, model.second)
        }
    }

    private fun makeImage(key: ImageKey, block: ImageBlocks.Request, profile: ProviderProfile, apiKey: String?) {
        setImage(key, TurnImage.Pending(block.prompt))
        val callKey = "image-" + java.util.UUID.randomUUID()
        val job = scope.launch(Dispatchers.IO) {
            try {
                val inputs = when (block.input) {
                    "selection" -> listOfNotNull(threadImage)
                    "previous" -> listOfNotNull(previousImage(key))
                    else -> emptyList()
                }.map { bitmap ->
                    val encoded = ImageEncoder.encode(bitmap, maxEdgePx = 1536, jpegQuality = 90, detail = ImageDetail.AUTO)
                    EncodedImage(encoded.base64, encoded.mimeType)
                }
                val bytes = ImageGeneration.generate(profile, apiKey, ImageRequest(block.prompt, block.aspect, inputs), callKey)
                val bitmap = decode(bytes.first()) ?: throw AiError.Protocol("The picture could not be read.")
                val id = java.util.UUID.randomUUID().toString()
                attachmentImages[id] = bitmap
                setImage(key, TurnImage.Ready(block.prompt, id, profile.modelId))
                persist()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiError) {
                android.util.Log.w(TAG, "Image failed: ${e::class.simpleName}", e)
                setImage(key, TurnImage.Failed(block.prompt, describe(e)))
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Image failed", e)
                setImage(key, TurnImage.Failed(block.prompt, e.message ?: "Something went wrong."))
            }
        }
        job.invokeOnCompletion { cause ->
            imageJobs.remove(key, job)
            if (cause is CancellationException) CallRegistry.cancelBackground(callKey)
        }
        imageJobs[key] = job
    }

    private fun setImage(key: ImageKey, image: TurnImage) = _images.update { it + (key to image) }

    /** The latest picture made before [key], for "input": "previous". */
    private fun previousImage(key: ImageKey): Bitmap? = _images.value.entries
        .filter { (k, v) -> v is TurnImage.Ready && (k.turn < key.turn || (k.turn == key.turn && k.index < key.index)) }
        .maxWithOrNull(compareBy({ it.key.turn }, { it.key.index }))
        ?.let { attachmentImages[(it.value as TurnImage.Ready).id] }

    private fun cancelImages(which: (ImageKey) -> Boolean) {
        imageJobs.keys.filter(which).forEach { imageJobs.remove(it)?.cancel() }
        imageRequests.keys.removeIf(which)
        _images.update { images -> images.filterKeys { !which(it) } }
    }

    /** Decodes a picture, scaled down to at most [MAX_IMAGE_EDGE] pixels on its long side. */
    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_IMAGE_EDGE) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun describe(error: AiError): String = when (error) {
        is AiError.Auth ->
            "The API key was rejected. Check the key and its permissions."
        is AiError.NotFound ->
            "The endpoint or model was not found. ${error.message}"
        is AiError.BadRequest ->
            "The provider rejected the request. ${error.message}"
        is AiError.RateLimited ->
            "Rate limited or out of quota. ${error.message}"
        is AiError.Server ->
            "The provider had an error. ${error.message}"
        is AiError.Network ->
            "Could not reach the provider. ${error.message}"
        is AiError.Timeout ->
            "The request timed out. ${error.message}"
        is AiError.InvalidEndpoint ->
            error.message ?: "Invalid endpoint."
        is AiError.Protocol ->
            error.message ?: "Unexpected response."
        is AiError.ReasoningStarved ->
            error.message ?: "No answer was produced."
        is AiError.Cancelled -> "Cancelled."
    }

    private companion object {
        const val TAG = "Lenshift/Conversation"
        const val MAX_IMAGES = 3
        const val MAX_IMAGE_EDGE = 2048
    }
}

// ======================================================================== state

data class ConversationState(
    val phase: ConversationPhase = ConversationPhase.Idle,
    val question: String = "",
    val action: QuickAction = QuickAction.ASK,
    val hasImage: Boolean = false,
    val modelLabel: String = "",
    val providerLabel: String = "",
    val notice: String? = null,
    val firstTokenMs: Long? = null,
    val totalMs: Long? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val reasoningTokens: Int? = null,
    val reasoningSummary: String? = null,
    /** The conversation so far: every answered turn, oldest first. */
    val turns: List<ChatTurn> = emptyList(),
    /** Attachment ids of the question in flight. */
    val questionAttachments: List<String> = emptyList(),
    val threadId: String? = null
) {
    /** True once at least one answer exists, so typed text continues the conversation. */
    val hasConversation: Boolean get() = turns.isNotEmpty()

    val isStreaming: Boolean get() = phase is ConversationPhase.Streaming
    val isBusy: Boolean get() = phase is ConversationPhase.Streaming

    val answerText: String
        get() = when (val p = phase) {
            is ConversationPhase.Streaming -> p.answer
            is ConversationPhase.Complete -> p.answer
            else -> ""
        }

    val reasoningText: String
        get() = when (val p = phase) {
            is ConversationPhase.Streaming -> p.reasoning
            else -> reasoningSummary.orEmpty()
        }
}

sealed interface ConversationPhase {
    data object Idle : ConversationPhase
    data class Streaming(val answer: String, val reasoning: String) : ConversationPhase
    data class Complete(val answer: String) : ConversationPhase
    data class Failed(val message: String) : ConversationPhase
    data object Cancelled : ConversationPhase
}
