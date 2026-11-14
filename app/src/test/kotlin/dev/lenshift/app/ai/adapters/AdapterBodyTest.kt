package dev.lenshift.app.ai.adapters

import dev.lenshift.app.ai.ApiProtocol
import dev.lenshift.app.ai.CapabilityInference
import dev.lenshift.app.ai.ChatMessage
import dev.lenshift.app.ai.ChatRequest
import dev.lenshift.app.ai.ProviderProfile
import dev.lenshift.app.ai.ReasoningMode
import dev.lenshift.app.ai.Tri
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Request-body construction tests.
 *
 * These verify that each protocol produces its own genuinely different shape, which is
 * the core architectural claim in docs/architecture.md: the protocols are not
 * interchangeable JSON.
 */
class AdapterBodyTest {

    private fun profile(
        protocol: ApiProtocol,
        model: String = "test-model",
        maxTokens: Int? = 1024
    ) = ProviderProfile(
        id = "p1",
        displayName = "Test",
        protocol = protocol,
        baseUrl = "https://example.test/v1",
        modelId = model,
        maxOutputTokens = maxTokens,
        streaming = true
    )

    private fun request(
        profile: ProviderProfile,
        text: String = "hello",
        reasoning: ReasoningMode = ReasoningMode.AUTO,
        continuation: String? = null
    ) = ChatRequest(
        profile = profile,
        apiKey = "sk-test",
        messages = listOf(
            ChatMessage(ChatMessage.Role.SYSTEM, "be brief"),
            ChatMessage(ChatMessage.Role.USER, text)
        ),
        capabilities = CapabilityInference.infer(profile.protocol, profile.modelId),
        reasoningMode = reasoning,
        continuationToken = continuation
    )

    // ------------------------------------------------------- chat completions

    @Test
    fun `chat completions uses messages and max_tokens`() {
        val adapter = OpenAiChatAdapter()
        val body = adapter.buildBodyForTest(
            request(profile(ApiProtocol.OPENAI_CHAT))
        )

        assertEquals("test-model", body.getString("model"))
        assertTrue(body.getBoolean("stream"))
        assertTrue("must use messages[]", body.has("messages"))
        assertTrue("must use max_tokens", body.has("max_tokens"))
        assertFalse("must not use the Responses API input[]", body.has("input"))
        assertFalse("must not use instructions", body.has("instructions"))
    }

    @Test
    fun `chat completions preserves a slashed model id verbatim`() {
        val adapter = OpenAiChatAdapter()
        val body = adapter.buildBodyForTest(
            request(profile(ApiProtocol.OPENAI_CHAT, model = "openai/gpt-4o-mini"))
        )
        assertEquals("openai/gpt-4o-mini", body.getString("model"))
    }

    @Test
    fun `chat completions endpoint is chat completions`() {
        val adapter = OpenAiChatAdapter()
        assertEquals(
            "https://example.test/v1/chat/completions",
            adapter.endpoint(profile(ApiProtocol.OPENAI_CHAT))
        )
    }

    // ---------------------------------------------------------- responses API

    @Test
    fun `responses api uses input and instructions not messages`() {
        val adapter = OpenAiResponsesAdapter()
        val body = adapter.buildBodyForTest(
            request(profile(ApiProtocol.OPENAI_RESPONSES))
        )

        assertTrue("must use input[]", body.has("input"))
        assertEquals("be brief", body.getString("instructions"))
        assertTrue("must use max_output_tokens", body.has("max_output_tokens"))
        assertFalse("must not use messages[]", body.has("messages"))
        assertFalse("must not use max_tokens", body.has("max_tokens"))
    }

    @Test
    fun `responses api requests encrypted reasoning and disables storage`() {
        val adapter = OpenAiResponsesAdapter()
        val body = adapter.buildBodyForTest(
            request(profile(ApiProtocol.OPENAI_RESPONSES))
        )

        assertFalse("privacy default: do not retain server-side", body.getBoolean("store"))
        assertTrue(
            "stateless reasoning continuation needs encrypted content",
            body.getJSONArray("include").toString().contains("reasoning.encrypted_content")
        )
    }

    @Test
    fun `responses api asks for the reasoning summary inside reasoning`() {
        // A top-level reasoning_summary is not a Responses API parameter: lenient gateways
        // ignored it, strict OpenAI-compatible servers rejected every chat with a 400.
        val p = profile(ApiProtocol.OPENAI_RESPONSES, model = "o3").copy(reasoningMode = ReasoningMode.MEDIUM)
        val body = OpenAiResponsesAdapter().buildBodyForTest(request(p, reasoning = ReasoningMode.MEDIUM))
        assertFalse(body.has("reasoning_summary"))
        val reasoning = body.getJSONObject("reasoning")
        assertEquals("medium", reasoning.getString("effort"))
        assertEquals("auto", reasoning.getString("summary"))

        // Automatic sends no reasoning at all, as the connection test does.
        val auto = OpenAiResponsesAdapter().buildBodyForTest(request(profile(ApiProtocol.OPENAI_RESPONSES, model = "o3")))
        assertFalse(auto.has("reasoning"))
        assertFalse(auto.has("reasoning_summary"))
    }

    @Test
    fun `responses api replays earlier answers as plain assistant text`() {
        // Assistant content typed input_text is refused by strict servers; plain text is the
        // documented form for an earlier answer.
        val p = profile(ApiProtocol.OPENAI_RESPONSES)
        val body = OpenAiResponsesAdapter().buildBodyForTest(
            request(p).copy(
                messages = listOf(
                    ChatMessage(ChatMessage.Role.SYSTEM, "be brief"),
                    ChatMessage(ChatMessage.Role.USER, "first question"),
                    ChatMessage(ChatMessage.Role.ASSISTANT, "first answer"),
                    ChatMessage(ChatMessage.Role.USER, "follow-up")
                )
            )
        )
        val input = body.getJSONArray("input")
        assertEquals(3, input.length())
        val answer = input.getJSONObject(1)
        assertEquals("assistant", answer.getString("role"))
        assertEquals("first answer", answer.get("content"))
        val followUp = input.getJSONObject(2).getJSONArray("content").getJSONObject(0)
        assertEquals("input_text", followUp.getString("type"))
        assertEquals("follow-up", followUp.getString("text"))
    }

    @Test
    fun `responses api forwards a continuation token`() {
        val adapter = OpenAiResponsesAdapter()
        val body = adapter.buildBodyForTest(
            request(profile(ApiProtocol.OPENAI_RESPONSES), continuation = "resp_123")
        )
        assertEquals("resp_123", body.getString("previous_response_id"))
    }

    // -------------------------------------------------------------- anthropic

    @Test
    fun `anthropic uses a top level system field and requires max_tokens`() {
        val adapter = AnthropicAdapter()
        val body = adapter.buildBodyForTest(
            request(profile(ApiProtocol.ANTHROPIC_MESSAGES))
        )

        assertEquals("be brief", body.getString("system"))
        assertTrue("max_tokens is mandatory", body.has("max_tokens"))
        assertTrue("messages must not contain a system role", run {
            val messages = body.getJSONArray("messages")
            (0 until messages.length()).none {
                messages.getJSONObject(it).getString("role") == "system"
            }
        })
    }

    @Test
    fun `anthropic content is an array of typed blocks`() {
        val adapter = AnthropicAdapter()
        val body = adapter.buildBodyForTest(
            request(profile(ApiProtocol.ANTHROPIC_MESSAGES))
        )

        val content = body.getJSONArray("messages")
            .getJSONObject(0)
            .getJSONArray("content")
        assertEquals("text", content.getJSONObject(0).getString("type"))
    }

    @Test
    fun `anthropic endpoint is messages`() {
        assertEquals(
            "https://example.test/v1/messages",
            AnthropicAdapter().endpoint(profile(ApiProtocol.ANTHROPIC_MESSAGES))
        )
    }

    // ----------------------------------------------------------------- gemini

    @Test
    fun `gemini puts the model in the path and uses contents`() {
        val adapter = GeminiAdapter()
        val body = adapter.buildBodyForTest(request(profile(ApiProtocol.GEMINI)))

        assertTrue("must use contents[]", body.has("contents"))
        assertTrue(body.has("systemInstruction"))
        assertFalse("must not use messages[]", body.has("messages"))

        val endpoint = adapter.endpoint(profile(ApiProtocol.GEMINI, model = "gemini-2.5-pro"))
        assertTrue(endpoint.contains("/models/gemini-2.5-pro:"))
        assertTrue("streaming uses alt=sse", endpoint.contains("alt=sse"))
    }

    @Test
    fun `gemini strips a models prefix from the model id`() {
        val adapter = GeminiAdapter()
        val endpoint = adapter.endpoint(
            profile(ApiProtocol.GEMINI, model = "models/gemini-2.5-flash")
        )
        assertFalse("must not double the prefix", endpoint.contains("models/models/"))
    }

    @Test
    fun `gemini assistant role is renamed to model`() {
        val adapter = GeminiAdapter()
        val request = ChatRequest(
            profile = profile(ApiProtocol.GEMINI),
            apiKey = null,
            messages = listOf(
                ChatMessage(ChatMessage.Role.USER, "hi"),
                ChatMessage(ChatMessage.Role.ASSISTANT, "hello")
            ),
            capabilities = CapabilityInference.infer(ApiProtocol.GEMINI, "gemini-2.5-pro"),
            reasoningMode = ReasoningMode.AUTO
        )
        val body = adapter.buildBodyForTest(request)
        val contents = body.getJSONArray("contents")
        assertEquals("model", contents.getJSONObject(1).getString("role"))
    }

    // ----------------------------------------------------------------- ollama

    @Test
    fun `ollama uses api chat and options`() {
        val adapter = OllamaAdapter()
        val p = profile(ApiProtocol.OLLAMA)
        assertEquals("https://example.test/v1/api/chat", adapter.endpoint(p))

        val body = adapter.buildBodyForTest(request(p))
        assertTrue(body.has("messages"))
        assertTrue("Ollama nests sampling under options", body.has("options"))
    }

    // ------------------------------------------------------- capability logic

    @Test
    fun `unknown capability never becomes yes`() {
        val caps = CapabilityInference.infer(ApiProtocol.OPENAI_CHAT, "some-random-model")
        assertEquals(Tri.UNKNOWN, caps.vision)
        assertNull("must never invent a context window", caps.contextWindow)
    }

    @Test
    fun `known vision family is detected`() {
        val caps = CapabilityInference.infer(ApiProtocol.OPENAI_CHAT, "gpt-4o-mini")
        assertEquals(Tri.YES, caps.vision)
    }

    @Test
    fun `known reasoning family is detected with the right mechanism`() {
        val caps = CapabilityInference.infer(ApiProtocol.ANTHROPIC_MESSAGES, "claude-sonnet-4")
        assertEquals(Tri.YES, caps.reasoning)
        assertEquals(dev.lenshift.app.ai.ReasoningMechanism.ANTHROPIC_BUDGET, caps.mechanism)
    }

    // ------------------------------------------------------ per-model parameters

    @Test
    fun `parameters a model does not accept are left out`() {
        val o3 = profile(ApiProtocol.OPENAI_CHAT, model = "o3").copy(temperature = 0.3f, topP = 0.9f)
        val body = OpenAiChatAdapter().buildBodyForTest(
            request(o3).copy(capabilities = CapabilityInference.forProfile(o3))
        )
        assertFalse("o3 rejects temperature", body.has("temperature"))
        assertFalse(body.has("top_p"))

        val gpt4o = o3.copy(modelId = "gpt-4o")
        val plain = OpenAiChatAdapter().buildBodyForTest(
            request(gpt4o).copy(capabilities = CapabilityInference.forProfile(gpt4o))
        )
        assertEquals(0.3, plain.getDouble("temperature"), 1e-6)
    }

    @Test
    fun `first party openai gets max_completion_tokens`() {
        val p = profile(ApiProtocol.OPENAI_CHAT).copy(baseUrl = "https://api.openai.com/v1")
        val body = OpenAiChatAdapter().buildBodyForTest(request(p))
        assertEquals(1024, body.getInt("max_completion_tokens"))
        assertFalse(body.has("max_tokens"))
    }
}
