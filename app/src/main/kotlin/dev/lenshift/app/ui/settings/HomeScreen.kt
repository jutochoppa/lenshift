package dev.lenshift.app.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lenshift.app.activation.AssistantRole
import dev.lenshift.app.activation.LenshiftTileService
import dev.lenshift.app.data.settings.AppSettings
import dev.lenshift.app.data.settings.QuickAction
import dev.lenshift.app.di.ServiceLocator
import dev.lenshift.app.ui.AssistLaunchActivity
import dev.lenshift.app.ui.Screen

/**
 * Landing screen: setup status, activation methods, provider summary, and the doors to
 * the rest of the app.
 */
@Composable
fun HomeScreen(
    settings: AppSettings,
    onNavigate: (Screen) -> Unit
) {
    val context = LocalContext.current
    var roleHeld by remember { mutableStateOf(AssistantRole.isHeld(context)) }
    var currentAssistant by remember {
        mutableStateOf(AssistantRole.currentAssistantComponent(context))
    }
    var accessibilityOn by remember {
        mutableStateOf(dev.lenshift.app.capture.AccessibilityCaptureService.isConnected())
    }

    // Re-read the role on every resume. The role can change outside this app — the user
    // may grant it from system Settings, or another assistant may take it back — and a
    // `remember` computed once at first composition would then show a stale badge. That
    // is not cosmetic: the badge is how the user learns whether activation will work.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                roleHeld = AssistantRole.isHeld(context)
                currentAssistant = AssistantRole.currentAssistantComponent(context)
                accessibilityOn = dev.lenshift.app.capture.AccessibilityCaptureService.isConnected()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Android does not allow apps to request the assistant role with a popup (the role is
    // "not requestable"), so the only route is the system settings screen. Explain that
    // first, then open it, instead of firing an intent that silently does nothing.
    var showAssistantGuide by remember { mutableStateOf(false) }

    // Result of the Quick Settings "Add tile" system prompt, shown inline so the user
    // always sees an outcome.
    var tileMessage by remember { mutableStateOf<String?>(null) }

    if (showAssistantGuide) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showAssistantGuide = false },
            title = { Text("Make Lenshift your assistant") },
            text = {
                Text(
                    "Android doesn't let apps ask for this with a popup — you choose it " +
                        "in system settings.\n\n" +
                        "1. Tap Open settings.\n" +
                        "2. Tap \"Digital assistant app\" (it may say \"Default digital " +
                        "assistant app\").\n" +
                        "3. Pick Lenshift and confirm.\n" +
                        "4. Come back here — the badge turns Active."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showAssistantGuide = false
                    if (!AssistantRole.openAssistantSettings(context)) {
                        tileMessage = null
                        android.widget.Toast.makeText(
                            context,
                            "Open Settings › Apps › Default apps › Digital assistant app",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }) { Text("Open settings") }
            },
            dismissButton = {
                TextButton(onClick = { showAssistantGuide = false }) { Text("Not now") }
            }
        )
    }

    val activeProfile = settings.profiles.firstOrNull { it.id == settings.activeProfileId }
        ?: settings.profiles.firstOrNull()
    val hasKey = activeProfile?.let { ServiceLocator.settings.hasApiKey(it.id) } ?: false
    val configured = activeProfile != null && activeProfile.baseUrl.isNotBlank() &&
        activeProfile.modelId.isNotBlank()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item {
            Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp)) {
                Text(
                    text = "Lenshift",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    )
                )
                Text(
                    text = "Circle anything on screen. Ask any model you like.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // ------------------------------------------------------------ quick start
        item {
            Spacer(Modifier.height(20.dp))
            SectionHeader("Try it now")
            SettingsCard {
                SettingRow(
                    title = "Open the selection screen",
                    subtitle = "Grabs one screenshot and lets you circle a region",
                    onClick = {
                        context.startActivity(
                            AssistLaunchActivity.intent(context, "home_shortcut")
                        )
                    }
                )
            }
        }

        // -------------------------------------------------------- assistant setup
        item {
            SectionHeader(
                title = "Digital assistant",
                subtitle = "Lets the system's assist gesture open Lenshift"
            )
            SettingsCard {
                SettingRow(
                    title = "Assistant role",
                    subtitle = when {
                        !AssistantRole.isAvailable(context) ->
                            "This device does not expose an assistant role"
                        roleHeld ->
                            "Lenshift is your assistant — tap to change"
                        else ->
                            "Not set — tap for the 3-step setup"
                    },
                    trailing = {
                        StatusPill(
                            text = if (roleHeld) "Active" else "Inactive",
                            positive = roleHeld
                        )
                    },
                    onClick = {
                        // Active: jump straight to settings (e.g. to switch back).
                        // Inactive: explain the steps first, since there is no popup.
                        if (roleHeld) {
                            AssistantRole.openAssistantSettings(context)
                        } else {
                            showAssistantGuide = true
                        }
                    }
                )

                SettingRow(
                    title = "Current assistant",
                    subtitle = currentAssistant
                        ?.substringAfter('/')
                        ?: "Unknown"
                )

                Text(
                    text = "Note: on many phones Google routes the long-press gesture to " +
                        "Circle to Search instead of the assistant. If that is the case, " +
                        "the Quick Settings tile below is the reliable trigger.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        // ------------------------------------------------------------- activation
        item {
            SectionHeader("Activation methods")
            SettingsCard {
                SettingRow(
                    title = "Quick Settings tile",
                    subtitle = tileMessage ?: "Add Lenshift to the notification shade",
                    trailing = {
                        OutlinedButton(onClick = {
                            tileMessage = "Waiting for the system prompt…"
                            LenshiftTileService.requestAdd(context) { result ->
                                tileMessage = when (result) {
                                    LenshiftTileService.Companion.AddResult.ADDED ->
                                        "Added — swipe down twice to find it"
                                    LenshiftTileService.Companion.AddResult.ALREADY_ADDED ->
                                        "Already in your Quick Settings"
                                    LenshiftTileService.Companion.AddResult.NOT_ADDED ->
                                        "Not added. Tap Add again any time"
                                    LenshiftTileService.Companion.AddResult.UNSUPPORTED ->
                                        "Add it by hand: swipe down twice, tap the pencil " +
                                            "(Edit), then drag Lenshift into the tiles"
                                }
                            }
                        }) {
                            Text("Add", fontSize = 11.sp)
                        }
                    }
                )

                SettingRow(
                    title = "Home screen shortcut",
                    subtitle = "Long-press your launcher and pick Lenshift",
                    onClick = {
                        context.startActivity(
                            AssistLaunchActivity.intent(context, "launcher")
                        )
                    }
                )

                SettingRow(
                    title = "Accessibility screenshot fallback",
                    subtitle = if (accessibilityOn) {
                        "Enabled — captures without a consent dialog"
                    } else {
                        "Optional. Screenshot-only; reads no screen text"
                    },
                    trailing = {
                        StatusPill(
                            text = if (accessibilityOn) "On" else "Off",
                            positive = accessibilityOn
                        )
                    },
                    onClick = {
                        // The ON_RESUME observer re-reads the connection state when the
                        // user returns, so no immediate (and always-stale) re-read here.
                        context.startActivity(Intent(
                            android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS
                        ))
                    }
                )

                // Informational only: sharing is started from the *other* app's share
                // sheet, where Lenshift appears as a target. There is nothing to do here,
                // so the row is deliberately not clickable.
                SettingRow(
                    title = "Share to Lenshift",
                    subtitle = "In any app, tap Share on an image or text and pick Lenshift"
                )
            }
        }

        // --------------------------------------------------------------- provider
        item {
            SectionHeader("AI provider")
            SettingsCard {
                SettingRow(
                    title = activeProfile?.displayName ?: "No provider configured",
                    subtitle = when {
                        activeProfile == null -> "Add an endpoint to start asking questions"
                        !configured -> "Base URL or model id is missing"
                        !hasKey -> "No API key saved for this provider"
                        else -> "${activeProfile.modelId} · ${activeProfile.protocol.label}"
                    },
                    trailing = {
                        StatusPill(
                            text = if (configured) "Ready" else "Setup",
                            positive = configured
                        )
                    },
                    onClick = { onNavigate(Screen.Providers) }
                )
                if (settings.profiles.size > 1) {
                    SettingRow(
                        title = "${settings.profiles.size} saved providers",
                        subtitle = "Switch between endpoints",
                        onClick = { onNavigate(Screen.Providers) }
                    )
                }
            }
        }

        // ------------------------------------------------------------ preferences
        item {
            SectionHeader("Preferences")
            SettingsCard {
                SettingRow(
                    title = "Default action",
                    subtitle = settings.defaultQuickAction.label,
                    onClick = { onNavigate(Screen.Advanced) }
                )
                SwitchRow(
                    title = "Search as soon as you select",
                    subtitle = "Off: a selection waits for your question, an action, or Send",
                    checked = settings.searchOnSelect,
                    onCheckedChange = { on ->
                        ServiceLocator.settings.updatePreferences { it.copy(searchOnSelect = on) }
                    }
                )
                SwitchRow(
                    title = "Ask before sending",
                    subtitle = "Preview what leaves the device before each request",
                    checked = settings.requireConfirmationBeforeSend,
                    onCheckedChange = {
                        ServiceLocator.settings.setRequireConfirmationBeforeSend(it)
                    }
                )
                SwitchRow(
                    title = "Google results tabs",
                    subtitle = "Show All, Visual matches and more beside Chat. Sent to Google only when opened",
                    checked = settings.googleTabsEnabled,
                    onCheckedChange = { enabled ->
                        ServiceLocator.settings.updatePreferences { it.copy(googleTabsEnabled = enabled) }
                    }
                )
                if (settings.googleTabsEnabled) {
                    SwitchRow(
                        title = "Open Google results first",
                        subtitle = "Start on the All tab instead of Chat",
                        checked = settings.googleResultsFirst,
                        onCheckedChange = { first ->
                            ServiceLocator.settings.updatePreferences { it.copy(googleResultsFirst = first) }
                        }
                    )
                }
                SwitchRow(
                    title = "Offline OCR",
                    subtitle = "Extract text from the selection on-device",
                    checked = settings.ocrEnabled,
                    onCheckedChange = { enabled ->
                        ServiceLocator.settings.updatePreferences {
                            it.copy(ocrEnabled = enabled)
                        }
                    }
                )
                SwitchRow(
                    title = "Save chat history",
                    subtitle = "Keep conversations and their selection images on this device to reopen from History",
                    checked = settings.chatHistoryEnabled,
                    onCheckedChange = { enabled ->
                        ServiceLocator.settings.updatePreferences { it.copy(chatHistoryEnabled = enabled) }
                    }
                )
                var cleared by remember { mutableStateOf(false) }
                SettingRow(
                    title = if (cleared) "Chat history cleared" else "Clear chat history",
                    subtitle = "Delete every saved conversation from this device",
                    onClick = {
                        ServiceLocator.chatHistory.clear()
                        ServiceLocator.settings.clearHistory()
                        cleared = true
                    }
                )
            }
        }

        // -------------------------------------------------------- visual answers
        item {
            SectionHeader("Visual answers")
            SettingsCard {
                SwitchRow(
                    title = "Graphs and 3D models",
                    subtitle = "Answers can include exact graphs, geometry and 3D shapes you can drag, zoom and rotate",
                    checked = settings.graphsEnabled,
                    onCheckedChange = { on ->
                        ServiceLocator.settings.updatePreferences { it.copy(graphsEnabled = on) }
                    }
                )
                var picking by remember { mutableStateOf(false) }
                SettingRow(
                    title = "Image generation",
                    subtitle = imageModelLabel(settings) ?: "Off. Choose a model that creates images",
                    onClick = { picking = true }
                )
                if (picking) ImageModelPicker(settings, onDismiss = { picking = false })
            }
        }

        // ------------------------------------------------------------- advanced
        item {
            SectionHeader("More")
            SettingsCard {
                SettingRow(
                    title = "Advanced settings",
                    subtitle = "Parameters, reasoning, timeouts, prompts",
                    onClick = { onNavigate(Screen.Advanced) }
                )
                SettingRow(
                    title = "Diagnostics",
                    subtitle = "Which triggers work on this device",
                    onClick = { onNavigate(Screen.Diagnostics) }
                )
                SettingRow(
                    title = "Privacy",
                    subtitle = "What leaves the device and when",
                    onClick = { onNavigate(Screen.Privacy) }
                )
            }
        }

        item { Spacer(Modifier.height(28.dp)) }
    }
}
