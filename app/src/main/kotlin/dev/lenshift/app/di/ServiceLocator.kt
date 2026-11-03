package dev.lenshift.app.di

import android.content.Context
import dev.lenshift.app.ai.ProviderRegistry
import dev.lenshift.app.capture.CaptureCoordinator
import dev.lenshift.app.data.settings.SettingsRepository
import dev.lenshift.app.ocr.OcrEngine
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A deliberately tiny, hand-rolled service locator.
 *
 * A full DI framework would add a code-generated dependency graph and startup cost for
 * an app with fewer than twenty collaborators. Everything here is constructed lazily on
 * first use so a cold assistant invocation only pays for what it touches.
 */
object ServiceLocator {

    private val initialised = AtomicBoolean(false)

    lateinit var appContext: Context
        private set

    val settings: SettingsRepository by lazy { SettingsRepository(appContext) }

    val providers: ProviderRegistry by lazy { ProviderRegistry(settings) }

    val ocr: OcrEngine by lazy { OcrEngine(appContext, settings) }

    val capture: CaptureCoordinator by lazy { CaptureCoordinator(appContext) }

    /** Saved chat threads, in app-private storage. */
    val chatHistory: dev.lenshift.app.conversation.ChatHistoryStore by lazy {
        dev.lenshift.app.conversation.ChatHistoryStore(java.io.File(appContext.filesDir, "chat_history"))
    }

    fun init(context: Context) {
        if (initialised.compareAndSet(false, true)) {
            appContext = context.applicationContext
        }
    }

    fun shutdown() {
        // No persistent connections are held, so there is nothing to release today.
        // Kept as an explicit hook so future adapters can clean up deterministically.
    }
}
