package dev.lenshift.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lenshift.app.ai.AiError
import dev.lenshift.app.ai.ApiProtocol
import dev.lenshift.app.ai.CapabilityInference
import dev.lenshift.app.ai.ChatMessage
import dev.lenshift.app.ai.ChatRequest
import dev.lenshift.app.ai.ModelCapabilities
import dev.lenshift.app.ai.ProviderProfile
import dev.lenshift.app.ai.ReasoningMode
import dev.lenshift.app.ai.StreamEvent
import dev.lenshift.app.ai.Tri
import dev.lenshift.app.ai.adapters.AnthropicAdapter
import dev.lenshift.app.ai.adapters.GeminiAdapter
import dev.lenshift.app.ai.adapters.OpenAiChatAdapter
import dev.lenshift.app.ai.adapters.OpenAiResponsesAdapter
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/**
 * End-to-end adapter tests against a real HTTP server.
 *
 * The mock provider (`tools/mock_provider.py`) runs on the host and is reached either
 * through the emulator's `10.0.2.2` loopback alias or through an `adb reverse` tunnel on
 * `127.0.0.1`. Running over real sockets is what makes these tests meaningful: they
 * exercise SSE framing, the JSON actually put on the wire, the image base64 payload, and
 * the error taxonomy — none of which a pure unit test can cover.
 *
 * Every test is skipped (never failed) when no mock server is reachable, so the suite
 * stays green on a physical device or a machine that has not started one.
 */
@RunWith(AndroidJUnit4::class)
class MockProviderEndToEndTest {

    // ---------------------------------------------------------------- fixtures

    /** Root of the mock server, without a version segment. */
    private fun root() = ROOT

    private fun profile(
        protocol: ApiProtocol,
        model: String,
        cleartext: Boolean = true,
        maxTokens: Int? = null
    ): ProviderProfile {
        val base = when (protocol) {
            ApiProtocol.GEMINI -> "$ROOT/v1beta"
            ApiProtocol.OLLAMA -> ROOT
            else -> "$ROOT/v1"
        }
        return ProviderProfile(
            id = "test",
            displayName = "Mock",
            protocol = protocol,
            baseUrl = base,
            modelId = model,
            streaming = true,
            maxOutputTokens = maxTokens,
            timeoutMs = 20_000,
            allowCleartext = cleartext
        )
    }

    private fun request(
        profile: ProviderProfile,
        text: String = "What does the sign say?",
        reasoning: ReasoningMode = ReasoningMode.AUTO,
        capabilities: ModelCapabilities =
            CapabilityInference.infer(profile.protocol, profile.modelId)
    ) = ChatRequest(
        profile = profile,
        apiKey = "test-key",
        messages = listOf(
            ChatMessage(ChatMessage.Role.SYSTEM, "You are terse."),
            ChatMessage(ChatMessage.Role.USER, text)
        ),
        capabilities = capabilities,
        reasoningMode = reasoning
    )

    // ------------------------------------------------------------------ setup

    @Test
    fun mockServerIsReachable() {
        assumeTrue("mock provider is not running (tried $ROOT)", serverUp())
        assertTrue(serverUp())
    }

    // ------------------------------------------------- openai chat completions

    @Test
    fun chatCompletionsStreamsText() = runBlocking {
        assumeTrue(serverUp())
        val events = OpenAiChatAdapter()
            .stream(request(profile(ApiProtocol.OPENAI_CHAT, "mock-gpt-4o")))
            .toList()

        val text = events.filterIsInstance<StreamEvent.TextDelta>()
            .joinToString("") { it.text }

        assertTrue("should stream the mock answer", text.contains("Welcome to Chrome"))
        assertTrue("stream must terminate", events.any { it == StreamEvent.Completed })
    }

    @Test
    fun chatCompletionsSendsBearerToken() = runBlocking {
        assumeTrue(serverUp())
        OpenAiChatAdapter().complete(request(profile(ApiProtocol.OPENAI_CHAT, "mock-gpt-4o")))

        val last = lastRequest()
        assertEquals("/v1/chat/completions", last.getString("path"))
        assertEquals("present", last.getString("authorization"))
    }

    @Test
    fun chatCompletionsPreservesSlashedModelIdOnTheWire() = runBlocking {
        assumeTrue(serverUp())
        OpenAiChatAdapter()
            .complete(request(profile(ApiProtocol.OPENAI_CHAT, "openai/gpt-4o-mini")))

        val sent = lastRequest().getJSONObject("body").getString("model")
        assertEquals("openai/gpt-4o-mini", sent)
    }

    @Test
    fun chatCompletionsSendsImageAsDataUrl() = runBlocking {
        assumeTrue(serverUp())
        val p = profile(ApiProtocol.OPENAI_CHAT, "mock-gpt-4o")
        val req = ChatRequest(
            profile = p,
            apiKey = "k",
            messages = listOf(
                ChatMessage(ChatMessage.Role.USER, "what is this?", makeBitmap())
            ),
            capabilities = CapabilityInference.infer(p.protocol, p.modelId)
                .copy(vision = Tri.YES),
            reasoningMode = ReasoningMode.AUTO
        )
        OpenAiChatAdapter().complete(req)

        val body = lastRequest().getJSONObject("body")
        val messages = body.getJSONArray("messages")
        val content = messages.getJSONObject(messages.length() - 1).getJSONArray("content")

        var foundImage = false
        for (i in 0 until content.length()) {
            val part = content.getJSONObject(i)
            if (part.getString("type") == "image_url") {
                val url = part.getJSONObject("image_url").getString("url")
                assertTrue("image must be an inline data URL", url.startsWith("data:image/"))
                assertTrue("must carry base64 payload", url.contains(";base64,"))
                foundImage = true
            }
        }
        assertTrue("vision request must include the image", foundImage)
    }

    @Test
    fun textOnlyModelDoesNotReceiveAnImage() = runBlocking {
        assumeTrue(serverUp())
        val p = profile(ApiProtocol.OPENAI_CHAT, "mock-gpt-4o")
        val req = ChatRequest(
            profile = p,
            apiKey = "k",
            messages = listOf(ChatMessage(ChatMessage.Role.USER, "text only", makeBitmap())),
            capabilities = CapabilityInference.infer(p.protocol, p.modelId)
                .copy(vision = Tri.NO),
            reasoningMode = ReasoningMode.AUTO
        )
        OpenAiChatAdapter().complete(req)

        val body = lastRequest().getJSONObject("body").toString()
        assertTrue(
            "a non-vision model must not be sent image parts",
            !body.contains("image_url")
        )
    }

    @Test
    fun reasoningEffortIsSentForChatCompletions() = runBlocking {
        assumeTrue(serverUp())
        val p = profile(ApiProtocol.OPENAI_CHAT, "mock-reasoner")
        OpenAiChatAdapter().complete(request(p, reasoning = ReasoningMode.HIGH))
        val body = lastRequest().getJSONObject("body")
        assertEquals("high", body.getString("reasoning_effort"))
    }

    @Test
    fun autoReasoningSendsNoEffortParameter() = runBlocking {
        assumeTrue(serverUp())
        OpenAiChatAdapter()
            .complete(request(profile(ApiProtocol.OPENAI_CHAT, "mock-gpt-4o")))
        val body = lastRequest().getJSONObject("body")
        assertTrue("AUTO must omit the parameter", !body.has("reasoning_effort"))
        assertTrue(!body.has("reasoning"))
    }

    @Test
    fun reasoningOnlyResponseRaisesAUsefulError() = runBlocking {
        assumeTrue(serverUp())
        val p = profile(ApiProtocol.OPENAI_CHAT, "mock-reasoner")
            .copy(customHeaders = mapOf("X-Mock-Scenario" to "reasoning-only"))
        try {
            OpenAiChatAdapter().complete(request(p))
            fail("expected a ReasoningStarved error")
        } catch (e: AiError.ReasoningStarved) {
            assertTrue(
                "message must explain how to fix it",
                e.message!!.contains("output tokens", ignoreCase = true)
            )
        }
    }

    // ------------------------------------------------------------- responses

    @Test
    fun responsesApiStreamsNamedEvents() = runBlocking {
        assumeTrue(serverUp())
        val events = OpenAiResponsesAdapter()
            .stream(request(profile(ApiProtocol.OPENAI_RESPONSES, "mock-gpt-4o")))
            .toList()

        val text = events.filterIsInstance<StreamEvent.TextDelta>()
            .joinToString("") { it.text }
        assertTrue(text.contains("Welcome to Chrome"))
    }

    @Test
    fun responsesApiSendsInputNotMessages() = runBlocking {
        assumeTrue(serverUp())
        OpenAiResponsesAdapter()
            .complete(request(profile(ApiProtocol.OPENAI_RESPONSES, "mock-gpt-4o")))

        val body = lastRequest().getJSONObject("body")
        assertTrue("must use input[]", body.has("input"))
        assertTrue("must not use messages[]", !body.has("messages"))
        assertEquals("You are terse.", body.getString("instructions"))
        assertTrue("privacy default: do not retain", !body.getBoolean("store"))
    }

    // ------------------------------------------------------------- anthropic

    @Test
    fun anthropicStreamsTextDeltas() = runBlocking {
        assumeTrue(serverUp())
        val events = AnthropicAdapter()
            .stream(request(profile(ApiProtocol.ANTHROPIC_MESSAGES, "claude-sonnet-4")))
            .toList()

        val text = events.filterIsInstance<StreamEvent.TextDelta>()
            .joinToString("") { it.text }
        assertTrue(text.contains("Welcome to Chrome"))
    }

    @Test
    fun anthropicUsesXApiKeyAndTopLevelSystem() = runBlocking {
        assumeTrue(serverUp())
        AnthropicAdapter()
            .complete(request(profile(ApiProtocol.ANTHROPIC_MESSAGES, "claude-sonnet-4")))

        val last = lastRequest()
        assertEquals("/v1/messages", last.getString("path"))
        assertEquals("present", last.getString("x_api_key"))
        assertEquals("absent", last.getString("authorization"))

        val body = last.getJSONObject("body")
        assertEquals("You are terse.", body.getString("system"))
        assertTrue("max_tokens is mandatory", body.has("max_tokens"))
    }

    @Test
    fun anthropicThinkingRaisesMaxTokensOnTheWire() = runBlocking {
        assumeTrue(serverUp())
        val p = profile(ApiProtocol.ANTHROPIC_MESSAGES, "claude-sonnet-4", maxTokens = 512)
        AnthropicAdapter().complete(request(p, reasoning = ReasoningMode.HIGH))

        val body = lastRequest().getJSONObject("body")
        assertTrue(body.has("thinking"))
        val budget = body.getJSONObject("thinking").getInt("budget_tokens")
        val maxTokens = body.getInt("max_tokens")
        assertTrue("Anthropic requires budget < max_tokens", maxTokens > budget)
    }

    // ---------------------------------------------------------------- gemini

    @Test
    fun geminiStreamsWithModelInPath() = runBlocking {
        assumeTrue(serverUp())
        val events = GeminiAdapter()
            .stream(request(profile(ApiProtocol.GEMINI, "gemini-2.5-flash")))
            .toList()

        val text = events.filterIsInstance<StreamEvent.TextDelta>()
            .joinToString("") { it.text }
        assertTrue(text.contains("Welcome to Chrome"))

        val path = lastRequest().getString("path")
        assertTrue("model belongs in the path", path.contains("/models/gemini-2.5-flash:"))
        assertTrue("streaming needs alt=sse", path.contains("alt=sse"))
    }

    @Test
    fun geminiUsesGoogApiKeyHeader() = runBlocking {
        assumeTrue(serverUp())
        GeminiAdapter().complete(request(profile(ApiProtocol.GEMINI, "gemini-2.5-flash")))
        assertEquals("present", lastRequest().getString("x_goog_api_key"))
    }

    // ---------------------------------------------------------------- errors

    @Test
    fun authFailureIsReportedAsAuthError() = runBlocking {
        assumeTrue(serverUp())
        val p = profile(ApiProtocol.OPENAI_CHAT, "mock-gpt-4o")
            .copy(customHeaders = mapOf("X-Mock-Scenario" to "auth-fail"))
        try {
            OpenAiChatAdapter().complete(request(p))
            fail("expected Auth")
        } catch (e: AiError.Auth) {
            assertTrue(e.message!!.contains("Invalid API key", ignoreCase = true))
        }
    }

    @Test
    fun notFoundIsDistinguishedFromAuth() = runBlocking {
        assumeTrue(serverUp())
        val p = profile(ApiProtocol.OPENAI_CHAT, "mock-gpt-4o")
            .copy(customHeaders = mapOf("X-Mock-Scenario" to "not-found"))
        try {
            OpenAiChatAdapter().complete(request(p))
            fail("expected NotFound")
        } catch (e: AiError.NotFound) {
            assertTrue(e.message!!.contains("base URL", ignoreCase = true))
        }
    }

    @Test
    fun rateLimitIsDistinguished() = runBlocking {
        assumeTrue(serverUp())
        val p = profile(ApiProtocol.OPENAI_CHAT, "mock-gpt-4o")
            .copy(customHeaders = mapOf("X-Mock-Scenario" to "rate-limit"))
        try {
            OpenAiChatAdapter().complete(request(p))
            fail("expected RateLimited")
        } catch (e: AiError.RateLimited) {
            // expected
        }
    }

    @Test
    fun redirectsAreRefusedSoKeysCannotLeak() = runBlocking {
        assumeTrue(serverUp())
        val p = profile(ApiProtocol.OPENAI_CHAT, "mock-gpt-4o")
            .copy(customHeaders = mapOf("X-Mock-Scenario" to "redirect"))
        try {
            OpenAiChatAdapter().complete(request(p))
            fail("expected the redirect to be refused")
        } catch (e: AiError.Protocol) {
            assertTrue(
                "must explain why following would leak the key",
                e.message!!.contains("API key", ignoreCase = true)
            )
        }
    }

    @Test
    fun cleartextIsRefusedWithoutExplicitOptIn() = runBlocking {
        assumeTrue(serverUp())
        val p = profile(ApiProtocol.OPENAI_CHAT, "mock-gpt-4o", cleartext = false)
        try {
            OpenAiChatAdapter().complete(request(p))
            fail("expected InvalidEndpoint")
        } catch (e: AiError.InvalidEndpoint) {
            assertTrue(e.message!!.contains("HTTP", ignoreCase = true))
        }
    }

    // ----------------------------------------------------------- model listing

    @Test
    fun modelDiscoveryReturnsIds() = runBlocking {
        assumeTrue(serverUp())
        val models = OpenAiChatAdapter()
            .listModels(profile(ApiProtocol.OPENAI_CHAT, "mock-gpt-4o"), "k")
        assertNotNull(models)
        assertTrue(models!!.contains("mock-gpt-4o"))
        assertTrue(models.contains("openai/gpt-4o-mini"))
    }

    @Test
    fun geminiDiscoveryFiltersNonGeneratingModels() = runBlocking {
        assumeTrue(serverUp())
        val models = GeminiAdapter()
            .listModels(profile(ApiProtocol.GEMINI, "gemini-2.5-flash"), "k")
        assertNotNull(models)
        assertTrue(models!!.contains("gemini-2.5-flash"))
        assertTrue(
            "embedding-only models must be filtered out",
            models.none { it.contains("embedding") }
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun makeBitmap(): android.graphics.Bitmap =
        android.graphics.Bitmap.createBitmap(64, 48, android.graphics.Bitmap.Config.ARGB_8888)
            .apply { eraseColor(android.graphics.Color.RED) }

    private fun lastRequest(): JSONObject {
        val raw = httpGet("$ROOT/__requests")
        val requests = JSONObject(raw).getJSONArray("requests")
        assertTrue("mock server must have recorded a request", requests.length() > 0)
        return requests.getJSONObject(requests.length() - 1)
    }

    private fun httpGet(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 3000
        connection.readTimeout = 3000
        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    companion object {
        /**
         * Candidate roots, probed once. `10.0.2.2` is the emulator's alias for the host
         * loopback; `127.0.0.1` works when an `adb reverse` tunnel is active.
         */
        private val CANDIDATES = listOf(
            "http://10.0.2.2:8787",
            "http://127.0.0.1:8787"
        )

        @Volatile
        private var root: String? = null

        /** The reachable root, or the first candidate when none answered. */
        private val ROOT: String get() = root ?: CANDIDATES.first()

        @BeforeClass
        @JvmStatic
        fun probe() {
            root = CANDIDATES.firstOrNull { candidate ->
                runCatching {
                    val connection =
                        URL("$candidate/health").openConnection() as HttpURLConnection
                    connection.connectTimeout = 2500
                    connection.readTimeout = 2500
                    connection.responseCode == 200
                }.getOrDefault(false)
            }
        }

        private fun serverUp(): Boolean = root != null
    }
}
