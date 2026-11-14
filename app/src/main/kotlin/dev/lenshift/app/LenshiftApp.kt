package dev.lenshift.app

import android.app.Application
import dev.lenshift.app.data.settings.SettingsRepository
import dev.lenshift.app.di.ServiceLocator

/**
 * Application entry point.
 *
 * Intentionally does no work at startup beyond constructing the settings repository:
 * Lenshift must never wake up, phone home, or pre-warm anything the user did not ask for.
 */
class LenshiftApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Debug builds only: provider errors are kept in files/debug for adb to read.
        dev.lenshift.app.ai.transport.ProviderLog.init(filesDir)
        ServiceLocator.init(this)
        // Touch the settings store once so the encrypted credential keystore is ready
        // before the first assistant invocation, which keeps cold-start latency low.
        ServiceLocator.settings.prime()
    }

    override fun onTerminate() {
        ServiceLocator.shutdown()
        super.onTerminate()
    }
}
