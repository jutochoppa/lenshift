package dev.lenshift.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lenshift.app.ai.ApiProtocol
import dev.lenshift.app.ai.PromptBuilder
import dev.lenshift.app.ai.ReasoningMode
import dev.lenshift.app.conversation.ConversationController
import dev.lenshift.app.conversation.ConversationPhase
import dev.lenshift.app.data.security.SecretStore
import dev.lenshift.app.data.settings.AppSettings
import dev.lenshift.app.data.settings.QuickAction
import dev.lenshift.app.data.settings.SettingsRepository
import dev.lenshift.app.ocr.OcrEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/**
 * On-device tests for the parts that only work on Android: OCR, Keystore-backed
 * credentials, settings persistence, and the full conversation pipeline.
 */
@RunWith(AndroidJUnit4::class)
class DeviceBehaviourTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun clean() {
        context.getSharedPreferences("lenshift_settings", 0).edit().clear().commit()
        context.getSharedPreferences("lenshift_secrets", 0).edit().clear().commit()
    }

    // ------------------------------------------------------------------ OCR

    @Test
    fun ocrReadsRenderedText() = runBlocking {
        val settings = SettingsRepository(context)
        val engine = OcrEngine(context, settings)

        val bitmap = renderText("HELLO LENSHIFT", 720, 200)
        val result = engine.recognise(bitmap)

        assertFalse(
            "OCR should be available: ${result.unavailableReason}",
            result.unavailable
        )
        assertTrue(
            "expected recognised text, got '${result.fullText}'",
            result.fullText.uppercase().contains("HELLO") ||
                result.fullText.uppercase().contains("LENSHIFT")
        )
        assertTrue("OCR should report timing", result.durationMs >= 0)
        assertTrue("OCR should produce word boxes", result.blocks.isNotEmpty())
        engine.release()
    }

    @Test
    fun ocrOnACropReportsOffsetCoordinates() = runBlocking {
        val settings = SettingsRepository(context)
        val engine = OcrEngine(context, settings)

        val bitmap = renderText("OFFSET", 720, 400)
        val region = android.graphics.Rect(100, 100, 620, 300)
        val result = engine.recognise(bitmap, region = region)

        if (!result.unavailable && result.blocks.isNotEmpty()) {
            val box = result.blocks.first().bounds
            assertTrue(
                "boxes must be shifted back into full-image coordinates",
                box.top >= region.top - 20
            )
        }
        engine.release()
    }

    @Test
    fun ocrUnavailableIsReportedNotThrown() = runBlocking {
        // A settings object with an unknown language must fail gracefully.
        val settings = SettingsRepository(context)
        settings.updatePreferences { it.copy(ocrLanguages = listOf("zzz")) }
        val engine = OcrEngine(context, settings)

        val result = engine.recognise(renderText("X", 200, 100))
        assertTrue("must not throw for a missing language", result.unavailable)
        assertNotNull("must explain why", result.unavailableReason)
        engine.release()
    }

    // ------------------------------------------------------- secret storage

    @Test
    fun apiKeysRoundTripThroughTheKeystore() {
        val store = SecretStore(context)
        val secret = "sk-test-abcdef123456"

        store.put("profile-1", secret)
        assertEquals(secret, store.get("profile-1"))
        assertTrue(store.has("profile-1"))
    }

    @Test
    fun apiKeysAreNotStoredInPlaintext() {
        val store = SecretStore(context)
        store.put("profile-2", "sk-super-secret-value")

        val raw = context.getSharedPreferences("lenshift_secrets", 0)
            .all
            .values
            .joinToString("|")

        assertFalse(
            "the plaintext key must never appear on disk",
            raw.contains("sk-super-secret-value")
        )
    }

    @Test
    fun removingAKeyClearsIt() {
        val store = SecretStore(context)
        store.put("profile-3", "value")
        store.remove("profile-3")
        assertNull(store.get("profile-3"))
        assertFalse(store.has("profile-3"))
    }

    @Test
    fun differentSecretsProduceDifferentCiphertext() {
        val store = SecretStore(context)
        store.put("a", "same-value")
        store.put("b", "same-value")

        val prefs = context.getSharedPreferences("lenshift_secrets", 0)
        val a = prefs.getString("secret_a", null)
        val b = prefs.getString("secret_b", null)

        assertNotNull(a)
        assertNotNull(b)
        assertFalse(
            "a fresh IV per secret must make the ciphertext differ",
            a == b
        )
    }

    // ---------------------------------------------------------- settings

    @Test
    fun providerProfilesPersistAcrossInstances() {
        val first = SettingsRepository(context)
        val profile = first.newProfile(ApiProtocol.ANTHROPIC_MESSAGES).copy(
            displayName = "My Claude",
            baseUrl = "https://api.anthropic.com/v1",
            modelId = "claude-sonnet-4",
            reasoningMode = ReasoningMode.HIGH,
            maxOutputTokens = 8192
        )
        first.upsertProfile(profile)
        first.setActiveProfile(profile.id)
        first.setApiKey(profile.id, "sk-ant-test")

        // A brand-new repository instance simulates a process restart.
        val second = SettingsRepository(context)
        val restored = second.activeProfile()

        assertNotNull(restored)
        assertEquals("My Claude", restored!!.displayName)
        assertEquals("claude-sonnet-4", restored.modelId)
        assertEquals(ReasoningMode.HIGH, restored.reasoningMode)
        assertEquals(8192, restored.maxOutputTokens)
        assertEquals("sk-ant-test", second.apiKey(restored.id))
    }

    @Test
    fun deletingAProfileErasesItsKey() {
        val repo = SettingsRepository(context)
        val profile = repo.newProfile(ApiProtocol.OPENAI_CHAT).copy(modelId = "m")
        repo.upsertProfile(profile)
        repo.setApiKey(profile.id, "sk-delete-me")

        repo.deleteProfile(profile.id)

        assertNull(repo.apiKey(profile.id))
        assertTrue(repo.current.profiles.none { it.id == profile.id })
    }

    @Test
    fun slashedModelIdsSurviveSerialisation() {
        val repo = SettingsRepository(context)
        val profile = repo.newProfile(ApiProtocol.OPENAI_CHAT).copy(
            modelId = "openrouter/anthropic/claude-3.5-sonnet"
        )
        repo.upsertProfile(profile)

        val restored = SettingsRepository(context).activeProfile()
        assertEquals(
            "model ids must be preserved byte for byte",
            "openrouter/anthropic/claude-3.5-sonnet",
            restored?.modelId
        )
    }

    @Test
    fun historyIsOptInAndClearable() {
        val repo = SettingsRepository(context)
        assertFalse("history is off by default", repo.current.historyEnabled)

        repo.addHistoryEntry(
            dev.lenshift.app.data.settings.HistoryEntry(
                id = "1", timestamp = 0, question = "q",
                answerPreview = "a", modelId = "m"
            )
        )
        assertTrue("nothing recorded while disabled", repo.current.history.isEmpty())

        repo.setHistoryEnabled(true)
        repo.addHistoryEntry(
            dev.lenshift.app.data.settings.HistoryEntry(
                id = "2", timestamp = 1, question = "q2",
                answerPreview = "a2", modelId = "m"
            )
        )
        assertEquals(1, repo.current.history.size)

        repo.clearHistory()
        assertTrue(repo.current.history.isEmpty())
    }

    // --------------------------------------------------- conversation pipeline

    @Test
    fun conversationStreamsFromTheMockProvider() = runBlocking {
        val repo = configuredRepo(ApiProtocol.OPENAI_CHAT, "mock-gpt-4o")
        val controller = ConversationController(
            repo, dev.lenshift.app.ai.ProviderRegistry(repo), this
        )

        controller.startFresh(
            action = QuickAction.EXPLAIN,
            selectionText = "Welcome to Chrome",
            image = null,
            sourceLabel = "example.com"
        )

        val final = waitForTerminal(controller)
        assertTrue(
            "expected a streamed answer, got $final",
            final is ConversationPhase.Complete &&
                final.answer.contains("Welcome to Chrome")
        )
        assertNotNull("first-token latency must be measured", controller.state.value.firstTokenMs)
    }

    @Test
    fun conversationSurfacesAuthFailureClearly() = runBlocking {
        val repo = configuredRepo(ApiProtocol.OPENAI_CHAT, "mock-gpt-4o")
        val profile = repo.activeProfile()!!
        repo.upsertProfile(
            profile.copy(customHeaders = mapOf("X-Mock-Scenario" to "auth-fail"))
        )

        val controller = ConversationController(
            repo, dev.lenshift.app.ai.ProviderRegistry(repo), this
        )
        controller.startFresh(QuickAction.EXPLAIN, "text", null, null)

        val final = waitForTerminal(controller)
        assertTrue(final is ConversationPhase.Failed)
        assertTrue(
            "the message must tell the user the key was rejected",
            (final as ConversationPhase.Failed).message.contains("key", ignoreCase = true)
        )
    }

    @Test
    fun conversationWithoutAProviderExplainsWhatToDo() = runBlocking {
        val repo = SettingsRepository(context)
        val controller = ConversationController(
            repo, dev.lenshift.app.ai.ProviderRegistry(repo), this
        )
        controller.startFresh(QuickAction.EXPLAIN, "text", null, null)

        val phase = controller.state.value.phase
        assertTrue(phase is ConversationPhase.Failed)
        assertTrue(
            (phase as ConversationPhase.Failed).message.contains("settings", ignoreCase = true)
        )
    }

    @Test
    fun cancellationStopsTheStream() = runBlocking {
        val repo = configuredRepo(ApiProtocol.OPENAI_CHAT, "mock-gpt-4o")
        val profile = repo.activeProfile()!!
        repo.upsertProfile(
            profile.copy(customHeaders = mapOf("X-Mock-Scenario" to "slow"))
        )

        val controller = ConversationController(
            repo, dev.lenshift.app.ai.ProviderRegistry(repo), this
        )
        controller.startFresh(QuickAction.EXPLAIN, "text", null, null)

        // Let the first token arrive, then cancel.
        withTimeout(10_000) {
            while (controller.state.value.phase !is ConversationPhase.Streaming) {
                kotlinx.coroutines.delay(20)
            }
        }
        controller.cancel()

        val phase = controller.state.value.phase
        assertTrue("cancel must be reflected in state", phase is ConversationPhase.Cancelled)
    }

    // ------------------------------------------------------------ helpers

    private fun configuredRepo(protocol: ApiProtocol, model: String): SettingsRepository {
        val repo = SettingsRepository(context)
        val base = "$MOCK/v1"
        val profile = repo.newProfile(protocol).copy(
            displayName = "Mock",
            baseUrl = base,
            modelId = model,
            allowCleartext = true,
            streaming = true
        )
        repo.upsertProfile(profile)
        repo.setActiveProfile(profile.id)
        repo.setApiKey(profile.id, "test-key")
        return repo
    }

    private suspend fun waitForTerminal(
        controller: ConversationController
    ): ConversationPhase = withTimeout(30_000) {
        while (true) {
            val phase = controller.state.value.phase
            if (phase is ConversationPhase.Complete ||
                phase is ConversationPhase.Failed ||
                phase is ConversationPhase.Cancelled
            ) {
                return@withTimeout phase
            }
            kotlinx.coroutines.delay(25)
        }
        @Suppress("UNREACHABLE_CODE")
        controller.state.value.phase
    }

    private fun renderText(text: String, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply {
            color = Color.BLACK
            textSize = height * 0.45f
            isAntiAlias = true
            isFakeBoldText = true
        }
        val y = height / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, 20f, y, paint)
        return bitmap
    }

    private companion object {
        val MOCK: String by lazy {
            listOf("http://10.0.2.2:8787", "http://127.0.0.1:8787").firstOrNull { base ->
                runCatching {
                    val c = URL("$base/health").openConnection() as HttpURLConnection
                    c.connectTimeout = 2500
                    c.readTimeout = 2500
                    c.responseCode == 200
                }.getOrDefault(false)
            } ?: "http://10.0.2.2:8787"
        }
    }
}
