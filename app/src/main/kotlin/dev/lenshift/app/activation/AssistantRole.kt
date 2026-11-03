package dev.lenshift.app.activation

import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Thin, honest wrapper around `RoleManager.ROLE_ASSISTANT`.
 *
 * `android.permission.BIND_VOICE_INTERACTION` has `protectionLevel="signature"`, so an
 * ordinary app cannot be bound as an assistant on its own. The supported route is the
 * ASSISTANT role: the user grants it in Settings, and the role controller grants the
 * signature permission to the role holder on the app's behalf. This is the documented
 * third-party assistant path — no root, no privileged install.
 *
 * **The assistant role cannot be requested with a popup.** Unlike the browser, dialer or
 * SMS roles, the platform marks ASSISTANT as *not requestable*:
 * `RoleManager.createRequestRoleIntent(ROLE_ASSISTANT)` opens the permission controller,
 * which logs `Role is not requestable: android.app.role.ASSISTANT` and closes instantly.
 * The user sees nothing at all. The only route is the user choosing the app in the system
 * "Digital assistant app" settings screen, so that is what [openAssistantSettings] does.
 */
object AssistantRole {

    private const val TAG = "Lenshift/Role"

    // Note: RoleManager.ROLE_ASSISTANT is a static field on the platform class, not a
    // Kotlin compile-time constant, so this must be `val` rather than `const val`.
    val ROLE: String = RoleManager.ROLE_ASSISTANT

    fun isAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val rm = context.getSystemService(RoleManager::class.java) ?: return false
        return rm.isRoleAvailable(ROLE)
    }

    fun isHeld(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val rm = context.getSystemService(RoleManager::class.java) ?: return false
        return rm.isRoleHeld(ROLE)
    }

    /**
     * Opens the screen where the user picks the default digital assistant.
     *
     * Tries, in order: the assist settings screen ("Digital assistant app"), then the
     * system "Default apps" list, then the app's own details page. Each is a real system
     * screen; the first that resolves on this device wins.
     *
     * @return true when a settings screen was opened.
     */
    fun openAssistantSettings(context: Context): Boolean {
        val candidates = listOf(
            // "Digital assistant app" — the screen that actually lists Lenshift.
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            // The system "Default apps" list, one tap away from the assistant entry.
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            // Last resort: this app's info page, which links to "Default apps" too.
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", context.packageName, null)
            )
        )
        for (intent in candidates) {
            if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return true
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "No handler for ${intent.action}")
            } catch (e: SecurityException) {
                Log.w(TAG, "Not allowed to open ${intent.action}", e)
            }
        }
        return false
    }

    /**
     * The component currently configured as the assistant, for the diagnostics screen.
     * This reads the same secure setting SystemUI uses.
     */
    fun currentAssistantComponent(context: Context): String? {
        val raw = runCatching {
            Settings.Secure.getString(context.contentResolver, "assistant")
        }.getOrNull()
        return raw?.takeIf { it.isNotBlank() }
    }

    fun isLenshiftCurrentAssistant(context: Context): Boolean {
        val current = currentAssistantComponent(context) ?: return false
        return current.contains(context.packageName)
    }
}
