package dev.lenshift.app.ai.transport

import dev.lenshift.app.ai.AiError
import dev.lenshift.app.ai.ApiProtocol
import dev.lenshift.app.ai.ProviderProfile
import okhttp3.Headers.Companion.headersOf
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProviderLogTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val key = "sk-live-abcdefghijklmnop1234"
    private val proxySecret = "proxy-secret-123"

    private val profile = ProviderProfile(
        id = "p1",
        displayName = "Relay",
        protocol = ApiProtocol.OPENAI_RESPONSES,
        baseUrl = "https://relay.example/v1",
        modelId = "gpt-6",
        customHeaders = mapOf("X-Proxy-Auth" to proxySecret)
    )

    /** The body Lenshift sends for a chat with a screenshot, an earlier answer and Medium reasoning. */
    private val sent = JSONObject()
        .put("model", "gpt-6")
        .put("stream", true)
        .put("store", false)
        .put("max_output_tokens", 128000)
        .put("instructions", "SYSTEM PROMPT TEXT")
        .put("reasoning", JSONObject().put("effort", "medium").put("summary", "auto"))
        .put(
            "input", JSONArray()
                .put(
                    JSONObject().put("role", "user").put(
                        "content", JSONArray()
                            .put(JSONObject().put("type", "input_text").put("text", "what is on my screen"))
                            .put(
                                JSONObject().put("type", "input_image")
                                    .put("image_url", "data:image/jpeg;base64," + "Qk1".repeat(2000))
                                    .put("detail", "auto")
                            )
                    )
                )
                .put(JSONObject().put("role", "assistant").put("content", "earlier answer"))
        )

    private fun request(url: String = "https://relay.example/v1/responses") = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer $key")
        .header("X-Proxy-Auth", proxySecret)
        .post(sent.toString().toRequestBody("application/json".toMediaType()))
        .build()

    @After
    fun reset() {
        ProviderLog.directory = null
        ProviderLog.maxFileBytes = 256L * 1024
    }

    @Test
    fun `an http error records the status, the reply and what was sent`() {
        val entry = ProviderLog.httpErrorEntry(
            profile, request(), 400, "",
            headersOf("cf-ray", "abc123-YYZ", "set-cookie", "session=zzz", "content-type", "application/json"),
            """{"error":{"message":"Upstream request failed","type":"upstream_error"}}"""
        )

        assertTrue(entry, entry.startsWith("HTTP 400 · POST https://relay.example/v1/responses"))
        assertTrue(entry, entry.contains("provider: Relay (openai_responses), model gpt-6"))
        assertTrue(entry, entry.contains("Upstream request failed"))
        assertTrue(entry, entry.contains("cf-ray: abc123-YYZ"))
        assertTrue(entry, entry.contains("\"effort\":\"medium\""))
        assertTrue(entry, entry.contains("\"summary\":\"auto\""))
        assertTrue(entry, entry.contains("max_output_tokens=128000"))
        assertTrue(entry, entry.contains("instructions=[text 18 chars]"))
        assertTrue(entry, entry.contains("input=[2 items: user 1, assistant 1; text 34 chars; images 1"))
    }

    @Test
    fun `no key, header value, chat text or image reaches the log`() {
        val entry = ProviderLog.httpErrorEntry(
            profile, request(), 401, "Unauthorized", headersOf(),
            """{"error":{"message":"Incorrect API key provided: $key","auth":"Bearer $key"}}"""
        )
        listOf(key, proxySecret, "session=zzz", "what is on my screen", "earlier answer", "SYSTEM PROMPT TEXT", "Qk1Qk1Qk1")
            .forEach { assertFalse("leaked $it:\n$entry", entry.contains(it)) }
        assertTrue(entry, entry.contains("Incorrect API key provided: <hidden>"))
    }

    @Test
    fun `query values are hidden but their names stay`() {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-pro:streamGenerateContent" +
            "?alt=sse&key=AIzaSyA-secret-value-1234567890"
        val shown = ProviderLog.redactUrl(url)
        assertFalse(shown, shown.contains("AIza"))
        assertTrue(shown, shown.startsWith("https://generativelanguage.googleapis.com/v1beta/models/"))
        assertTrue(shown, shown.endsWith("?alt=…&key=…"))
        assertEquals("https://relay.example/v1/models", ProviderLog.redactUrl("https://user:pw@relay.example/v1/models"))
    }

    @Test
    fun `token limits stay readable while secret parameters are hidden`() {
        val shape = ProviderLog.describeBody(
            JSONObject()
                .put("model", "m")
                .put("max_tokens", 100)
                .put("api_key", "abc")
                .put("metadata", JSONObject().put("user_token", "zzz").put("tier", "pro"))
        )
        assertTrue(shape, shape.startsWith("model=\"m\""))
        assertTrue(shape, shape.contains("max_tokens=100"))
        assertTrue(shape, shape.contains("api_key=<hidden>"))
        assertTrue(shape, shape.contains("\"tier\":\"pro\""))
        assertFalse(shape, shape.contains("zzz"))
    }

    @Test
    fun `an error inside a stream records the event the provider sent`() {
        val entry = ProviderLog.streamErrorEntry(
            profile, "https://relay.example/v1/responses", key, sent,
            "event: response.failed\n{\"type\":\"response.failed\",\"response\":{\"error\":{\"message\":\"model overloaded\"}}}",
            AiError.Server(200, "model overloaded")
        )
        assertTrue(entry, entry.startsWith("STREAM ERROR · POST https://relay.example/v1/responses"))
        assertTrue(entry, entry.contains("received: event: response.failed"))
        assertTrue(entry, entry.contains("Provider error 200: model overloaded"))
        assertTrue(entry, entry.contains("reasoning={"))
    }

    @Test
    fun `a failure without an answer keeps its cause`() {
        val entry = ProviderLog.transportErrorEntry(
            profile, request(),
            java.net.SocketTimeoutException("timeout")
        )
        assertTrue(entry, entry.startsWith("FAILED SocketTimeoutException · POST https://relay.example/v1/responses"))
        assertTrue(entry, entry.contains("error: SocketTimeoutException: timeout"))
        assertTrue(entry, entry.contains("sent: model=\"gpt-6\" stream=true"))
    }

    @Test
    fun `entries go to a file that rolls over when full`() {
        ProviderLog.directory = File(folder.root, "debug")
        ProviderLog.maxFileBytes = 2_000
        val wasEnabled = ProviderLog.enabled
        ProviderLog.enabled = true
        try {
            repeat(30) { ProviderLog.failed("chat", profile, "The provider rejected the request. #$it") }
        } finally {
            ProviderLog.enabled = wasEnabled
        }
        val file = ProviderLog.file()!!
        assertTrue(file.readText().contains("CHAT FAILED, shown: The provider rejected the request. #29"))
        assertTrue(file.length() <= 2_000)
        assertTrue(File(file.parentFile, ProviderLog.OLD_FILE_NAME).exists())
    }
}
