package dev.lenshift.app.ai

import android.graphics.Bitmap
import kotlinx.coroutines.flow.Flow

/**
 * One conversation turn as Lenshift understands it, independent of wire format.
 */
data class ChatMessage(
    val role: Role,
    val text: String,
    /** Images attached to this turn, in order (user turns only in practice). */
    val images: List<Bitmap> = emptyList()
) {
    /** A turn with at most one image. */
    constructor(role: Role, text: String, image: Bitmap?) : this(role, text, listOfNotNull(image))

    enum class Role { SYSTEM, USER, ASSISTANT }
}

/**
 * A streaming event. Adapters normalise their own wire protocol into this.
 */
sealed interface StreamEvent {
    /** Visible answer text. Concatenate in order. */
    data class TextDelta(val text: String) : StreamEvent

    /** Provider-exposed reasoning summary. Never invented by Lenshift. */
    data class ReasoningDelta(val text: String) : StreamEvent

    /** Usage counters when the provider reports them. */
    data class Usage(
        val inputTokens: Int?,
        val outputTokens: Int?,
        val reasoningTokens: Int?
    ) : StreamEvent

    /** Non-fatal provider notice worth showing in diagnostics. */
    data class Notice(val message: String) : StreamEvent

    data object Completed : StreamEvent
}

/**
 * Everything an adapter needs to build one request.
 */
data class ChatRequest(
    val profile: ProviderProfile,
    val apiKey: String?,
    val messages: List<ChatMessage>,
    val capabilities: ModelCapabilities,
    val reasoningMode: ReasoningMode,
    /** True when this is a follow-up that should reuse provider-side state. */
    val continuationToken: String? = null
)

/**
 * Result of a non-streaming completion, or of a fully drained stream.
 */
data class ChatResult(
    val text: String,
    val reasoningSummary: String?,
    /** Opaque token the next turn must echo back for valid continuation. */
    val continuationToken: String?,
    val usage: StreamEvent.Usage?,
    val finishReason: String?
)

/** A provider/transport problem with enough structure for the UI to act on. */
sealed class AiError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** 401/403 — the key is wrong, expired, or lacks scope. */
    class Auth(val detail: String) : AiError("Authentication failed: $detail")

    /** 404 — usually a wrong base URL or a model id that does not exist. */
    class NotFound(val detail: String) : AiError("Not found: $detail")

    /** 400/422 — the provider rejected our body (often an unsupported parameter). */
    class BadRequest(val detail: String, val rawBody: String?) :
        AiError("Request rejected: $detail")

    /** 429 — rate limited or out of quota. */
    class RateLimited(val detail: String) : AiError("Rate limited: $detail")

    /** 5xx. */
    class Server(val status: Int, val detail: String) : AiError("Provider error $status: $detail")

    /** DNS/socket/TLS failure. */
    class Network(val detail: String, cause: Throwable? = null) :
        AiError("Network error: $detail", cause)

    class Timeout(val detail: String) : AiError("Timed out: $detail")

    /** The URL is not a valid http(s) endpoint, or cleartext was not opted into. */
    class InvalidEndpoint(val detail: String) : AiError("Invalid endpoint: $detail")

    /** The response was well-formed HTTP but not the shape this protocol expects. */
    class Protocol(val detail: String) : AiError("Unexpected response: $detail")

    /** Reasoning consumed the whole output budget and no answer was produced. */
    class ReasoningStarved(val reasoningTokens: Int?) : AiError(
        if (reasoningTokens != null) {
            "The model spent all $reasoningTokens output tokens on reasoning and " +
                "produced no answer. Raise the maximum output tokens or lower the " +
                "reasoning effort."
        } else {
            "The model spent its entire output budget on reasoning and produced no " +
                "answer. Raise the maximum output tokens or lower the reasoning effort."
        }
    )

    class Cancelled : AiError("Cancelled")
}

/**
 * The contract every wire protocol implements.
 *
 * Adapters own:
 *  - request construction (body shape, image encoding, reasoning mapping)
 *  - stream framing (SSE, NDJSON, ...)
 *  - response normalisation into [StreamEvent]
 *  - continuation-state extraction
 *
 * Adapters do NOT own: retries, timeouts, UI, or persistence.
 */
interface ProviderAdapter {

    val protocol: ApiProtocol

    /** Build the URL for a chat/completion call. */
    fun endpoint(profile: ProviderProfile): String

    /**
     * Stream a completion. Implementations must be cancellable: cancelling the
     * collecting coroutine closes the HTTP call.
     */
    fun stream(request: ChatRequest): Flow<StreamEvent>

    /** Drain [stream] into a single result. */
    suspend fun complete(request: ChatRequest): ChatResult

    /**
     * Best-effort model listing, with whatever limits the provider reports per model.
     * Returns null when the provider has no usable model list, so the UI can fall back
     * to manual entry.
     */
    suspend fun listModels(profile: ProviderProfile, apiKey: String?): List<ModelInfo>? = null

    /**
     * Perform a real request to validate configuration. Must actually call the API
     * (a tiny completion or a models list), not merely probe the URL.
     */
    suspend fun testConnection(profile: ProviderProfile, apiKey: String?): ConnectionTest
}

data class ConnectionTest(
    val ok: Boolean,
    val headline: String,
    val detail: String,
    val latencyMs: Long,
    val models: List<String> = emptyList()
)
