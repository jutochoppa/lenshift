package dev.lenshift.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lenshift.app.ai.ApiProtocol
import dev.lenshift.app.capture.CaptureSource
import dev.lenshift.app.di.ServiceLocator
import dev.lenshift.app.ui.settings.AdvancedSettingsScreen
import dev.lenshift.app.ui.settings.DiagnosticsScreen
import dev.lenshift.app.ui.settings.HomeScreen
import dev.lenshift.app.ui.settings.PrivacyScreen
import dev.lenshift.app.ui.settings.ProviderEditorScreen
import dev.lenshift.app.ui.settings.ProviderListScreen
import dev.lenshift.app.ui.theme.LenshiftTheme

/**
 * Settings and onboarding host.
 *
 * Navigation is an explicit back stack rather than navigation-compose: the app has six
 * destinations and a hand-rolled stack keeps the dependency count and binary size down.
 *
 * The stack is saveable and backed by [BackHandler], so the system Back gesture returns to
 * the parent screen instead of dropping the user out of settings entirely, and rotation or
 * process recreation keeps the current destination.
 */
class MainActivity : ComponentActivity() {

    private val context get() = this

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            LenshiftTheme {
                val settings by ServiceLocator.settings.state.collectAsStateWithLifecycle()

                var stack by rememberSaveable(stateSaver = ScreenStackSaver) {
                    mutableStateOf(listOf<Screen>(Screen.Assistant))
                }

                BackHandler(enabled = stack.size > 1) {
                    stack = stack.dropLast(1)
                }

                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background
                ) {
                    when (val current = stack.last()) {
                        Screen.Assistant -> AssistantHomeScreen(
                            settings = settings,
                            onOpenSettings = { stack = stack + Screen.Home },
                            onSearch = { query ->
                                context.startActivity(
                                    CaptureActivity.intentFor(
                                        context = context,
                                        source = CaptureSource.NONE,
                                        preloadedText = null,
                                        mechanism = "assistant_search"
                                    ).putExtra(CaptureActivity.EXTRA_TEXT, query.takeIf { it.isNotBlank() })
                                )
                            },
                            onQuickAction = { action ->
                                context.startActivity(
                                    CaptureActivity.intentFor(
                                        context = context,
                                        source = CaptureSource.NONE,
                                        mechanism = "assistant_quick_action"
                                    ).putExtra(CaptureActivity.EXTRA_DEFAULT_ACTION, action.id)
                                )
                            }
                        )

                        Screen.Home -> HomeScreen(
                            settings = settings,
                            onNavigate = { stack = stack + it }
                        )

                        Screen.Providers -> ProviderListScreen(
                            settings = settings,
                            onBack = { stack = stack.dropLast(1) },
                            onEdit = { stack = stack + Screen.ProviderEditor(it) },
                            onAdd = { protocol -> stack = stack + Screen.ProviderEditor(null, protocol) }
                        )

                        is Screen.ProviderEditor -> ProviderEditorScreen(
                            settings = settings,
                            profileId = current.profileId,
                            initialProtocol = current.protocol,
                            onBack = { stack = stack.dropLast(1) }
                        )

                        Screen.Advanced -> AdvancedSettingsScreen(
                            settings = settings,
                            onBack = { stack = stack.dropLast(1) }
                        )

                        Screen.Diagnostics -> DiagnosticsScreen(
                            onBack = { stack = stack.dropLast(1) }
                        )

                        Screen.Privacy -> PrivacyScreen(
                            onBack = { stack = stack.dropLast(1) }
                        )
                    }
                }
            }
        }
    }
}

sealed interface Screen {
    data object Assistant : Screen
    data object Home : Screen
    data object Providers : Screen
    data class ProviderEditor(
        val profileId: String?,
        val protocol: ApiProtocol? = null
    ) : Screen
    data object Advanced : Screen
    data object Diagnostics : Screen
    data object Privacy : Screen
}

/**
 * Persists the back stack across recreation.
 *
 * Each entry encodes to a single string so a corrupt or renamed destination can be dropped
 * individually instead of losing the whole stack.
 */
private val ScreenStackSaver = listSaver<List<Screen>, String>(
    save = { stack -> stack.map { it.encode() } },
    restore = { saved -> saved.mapNotNull { decodeScreen(it) }.ifEmpty { listOf(Screen.Assistant) } }
)

private fun Screen.encode(): String = when (this) {
    Screen.Assistant -> "assistant"
    Screen.Home -> "home"
    Screen.Providers -> "providers"
    Screen.Advanced -> "advanced"
    Screen.Diagnostics -> "diagnostics"
    Screen.Privacy -> "privacy"
    is Screen.ProviderEditor -> "editor\u001f${profileId.orEmpty()}\u001f${protocol?.id.orEmpty()}"
}

private fun decodeScreen(raw: String): Screen? {
    if (raw == "assistant") return Screen.Assistant
    if (raw == "home") return Screen.Home
    if (raw == "providers") return Screen.Providers
    if (raw == "advanced") return Screen.Advanced
    if (raw == "diagnostics") return Screen.Diagnostics
    if (raw == "privacy") return Screen.Privacy
    if (!raw.startsWith("editor\u001f")) return null
    val parts = raw.split('\u001f')
    if (parts.size != 3) return null
    return Screen.ProviderEditor(
        profileId = parts[1].takeIf { it.isNotBlank() },
        protocol = parts[2].takeIf { it.isNotBlank() }?.let { ApiProtocol.fromId(it) }
    )
}
