package dev.lenshift.app.ai.transport

import dev.lenshift.app.ai.AiError
import dev.lenshift.app.ai.ApiProtocol
import dev.lenshift.app.ai.ProviderProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The endpoint policy is a security boundary: it decides whether an API key may leave the
 * device over plain HTTP. These tests pin it down at the single place every authenticated
 * request is built.
 *
 * The bug they guard against: the policy used to run only on chat requests. Model
 * discovery built its request directly, so "Refresh models" sent an `x-api-key` /
 * `x-goog-api-key` in cleartext to a public `http://` host that chat itself refused.
 */
class EndpointPolicyTest {

    private fun profile(baseUrl: String, allowCleartext: Boolean = false) = ProviderProfile(
        id = "p1",
        displayName = "Test",
        protocol = ApiProtocol.ANTHROPIC_MESSAGES,
        baseUrl = baseUrl,
        modelId = "m",
        allowCleartext = allowCleartext
    )

    private fun assertRefused(p: ProviderProfile) {
        try {
            HttpSupport.requestBuilder(p, "${p.baseUrl}/models", "secret", HttpSupport.AuthScheme.X_API_KEY)
            fail("a request carrying an API key must not be built for ${p.baseUrl}")
        } catch (e: AiError.InvalidEndpoint) {
            // expected
        }
    }

    @Test
    fun `https is always allowed`() {
        val p = profile("https://api.anthropic.com/v1")
        val request = HttpSupport.requestBuilder(
            p, "${p.baseUrl}/models", "secret", HttpSupport.AuthScheme.X_API_KEY
        ).get().build()
        assertEquals("secret", request.header("x-api-key"))
    }

    @Test
    fun `cleartext to a public host is refused even with the opt-in`() {
        assertRefused(profile("http://example.com/v1", allowCleartext = true))
    }

    @Test
    fun `cleartext is refused without the opt-in even for a private host`() {
        assertRefused(profile("http://192.168.1.20:11434", allowCleartext = false))
    }

    @Test
    fun `cleartext to loopback and private ranges is allowed with the opt-in`() {
        listOf(
            "http://10.0.2.2:11434",
            "http://127.0.0.1:8080",
            "http://localhost:8080",
            "http://192.168.1.20:11434",
            "http://10.1.2.3:8000",
            "http://172.16.0.5:8000",
            "http://172.31.255.1:8000"
        ).forEach { url ->
            val p = profile(url, allowCleartext = true)
            HttpSupport.requestBuilder(p, "$url/models", null, HttpSupport.AuthScheme.NONE)
        }
    }

    @Test
    fun `addresses just outside the private ranges are refused`() {
        // 172.15 and 172.32 sit either side of the 172.16/12 block; neither is private.
        assertRefused(profile("http://172.15.0.1:8000", allowCleartext = true))
        assertRefused(profile("http://172.32.0.1:8000", allowCleartext = true))
    }

    @Test
    fun `non-http schemes are refused`() {
        assertRefused(profile("ftp://example.com/v1", allowCleartext = true))
    }

    @Test
    fun `model discovery cannot bypass the policy`() {
        // The exact call shape the adapters' listModels() use.
        val p = profile("http://evil.example/v1", allowCleartext = false)
        try {
            HttpSupport.requestBuilder(p, "${p.baseUrl}/models", "sk-live", HttpSupport.AuthScheme.X_GOOG_API_KEY)
            fail("listModels must not be able to build a cleartext request with a key")
        } catch (e: AiError.InvalidEndpoint) {
            assertTrue(e.message.orEmpty().isNotBlank())
        }
    }
}
