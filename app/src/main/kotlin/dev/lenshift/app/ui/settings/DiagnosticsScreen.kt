package dev.lenshift.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lenshift.app.activation.AssistantRole
import dev.lenshift.app.assist.AssistTriggerLog
import dev.lenshift.app.capture.AccessibilityCaptureService
import dev.lenshift.app.di.ServiceLocator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostics: which activation mechanisms actually reached Lenshift on *this* device.
 *
 * This is the honest counterpart to the architecture document. Instead of claiming a
 * gesture works, the app records what really fired and shows it here.
 */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var events by remember { mutableStateOf(AssistTriggerLog.recent(context)) }
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    SettingsScaffold(title = "Diagnostics", onBack = onBack) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                SectionHeader("Device state")
                SettingsCard {
                    SettingRow(
                        title = "Android",
                        subtitle = "${android.os.Build.VERSION.RELEASE} (API " +
                            "${android.os.Build.VERSION.SDK_INT}) · ${android.os.Build.MODEL}"
                    )
                    SettingRow(
                        title = "Assistant role held",
                        subtitle = if (AssistantRole.isHeld(context)) "Yes" else "No",
                        trailing = {
                            StatusPill(
                                text = if (AssistantRole.isHeld(context)) "Yes" else "No",
                                positive = AssistantRole.isHeld(context)
                            )
                        }
                    )
                    SettingRow(
                        title = "Configured assistant",
                        subtitle = AssistantRole.currentAssistantComponent(context)
                            ?: "Unknown"
                    )
                    SettingRow(
                        title = "Accessibility capture",
                        subtitle = if (AccessibilityCaptureService.isConnected()) {
                            "Connected"
                        } else {
                            "Not enabled"
                        },
                        trailing = {
                            StatusPill(
                                text = if (AccessibilityCaptureService.isConnected()) "On" else "Off",
                                positive = AccessibilityCaptureService.isConnected()
                            )
                        }
                    )
                    SettingRow(
                        title = "OCR engine",
                        subtitle = ServiceLocator.ocr.availableLanguages()
                            .joinToString(", ")
                            .ifBlank { "No traineddata bundled" }
                    )
                }
            }

            item {
                SectionHeader(
                    title = "Activation log",
                    subtitle = "What actually reached Lenshift, newest first"
                )
                SettingsCard {
                    if (events.isEmpty()) {
                        Text(
                            text = "No activations recorded yet. Trigger Lenshift once " +
                                "and come back.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    } else {
                        events.take(20).forEach { event ->
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) {
                                Text(
                                    text = "${formatter.format(Date(event.timestamp))}  " +
                                        event.kind,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = event.detail,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            item {
                SectionHeader("How activation really works")
                SettingsCard {
                    DiagnosticNote(
                        "Assistant gesture",
                        "Reaches Lenshift only while Lenshift holds the assistant role. " +
                            "On many phones Google routes the same gesture to Circle to " +
                            "Search, which is a separate privileged integration."
                    )
                    DiagnosticNote(
                        "Circle to Search",
                        "Uses a system-only 'contextual search' path. Third-party apps " +
                            "cannot register for it, so Lenshift cannot replace it " +
                            "without root or a system build."
                    )
                    DiagnosticNote(
                        "Quick Settings tile",
                        "Always available and needs no role or permission."
                    )
                    DiagnosticNote(
                        "Accessibility screenshot",
                        "Optional. Enables prompt-free capture; reads no screen text."
                    )
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        AssistTriggerLog.clear(context)
                        events = emptyList()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                ) {
                    Text("Clear activation log", fontSize = 12.sp)
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

@Composable
private fun DiagnosticNote(title: String, body: String) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(
            body,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
