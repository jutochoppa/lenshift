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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lenshift.app.di.ServiceLocator

/**
 * Plain-language privacy statement describing what Lenshift actually does.
 *
 * Written to be accurate rather than flattering: the app cannot make an external model
 * private, and it says so.
 */
@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    SettingsScaffold(title = "Privacy", onBack = onBack) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                SectionHeader("What stays on your device")
                SettingsCard {
                    PrivacyPoint(
                        "Screenshots",
                        "Held in memory only for the duration of one selection. Nothing " +
                            "is written to storage, and the bitmap is dropped when the " +
                            "selection screen closes."
                    )
                    PrivacyPoint(
                        "API keys",
                        "Encrypted with a hardware-backed Android Keystore key " +
                            "(AES-256-GCM). Excluded from cloud backup and device transfer."
                    )
                    PrivacyPoint(
                        "OCR",
                        "Runs entirely on-device with a bundled Tesseract model. No " +
                            "network access, no Google Play services."
                    )
                    PrivacyPoint(
                        "History",
                        "Off by default. When enabled, only your question and a short " +
                            "answer preview are stored, locally."
                    )
                }
            }

            item {
                SectionHeader("What leaves your device")
                SettingsCard {
                    PrivacyPoint(
                        "Only when you ask",
                        "Lenshift contacts exactly one host: the AI endpoint you " +
                            "configured. There is no other network traffic."
                    )
                    PrivacyPoint(
                        "What is sent",
                        "The cropped selection image and/or its OCR text, plus your " +
                            "question. Nothing else — no device id, no account, no " +
                            "screenshot of the whole screen unless you selected all of it."
                    )
                    PrivacyPoint(
                        "Your provider sees it",
                        "The model you configured receives that content. Its own privacy " +
                            "policy applies. Lenshift cannot make external inference " +
                            "private."
                    )
                }
            }

            item {
                SectionHeader("What Lenshift does not do")
                SettingsCard {
                    PrivacyPoint("No account", "There is no sign-in and no Google account use.")
                    PrivacyPoint(
                        "No analytics",
                        "No Firebase, no Google Analytics, no advertising SDK, no crash " +
                            "reporting service."
                    )
                    PrivacyPoint(
                        "No background capture",
                        "Lenshift never records the screen continuously and never uploads " +
                            "anything without a selection."
                    )
                    PrivacyPoint(
                        "No image hosting",
                        "Images are posted directly to your provider. They are never sent " +
                            "to an intermediate host."
                    )
                    PrivacyPoint(
                        "No redirects",
                        "HTTP redirects are refused, so an API key can never be forwarded " +
                            "to a different host."
                    )
                }
            }

            item {
                SectionHeader("Your controls")
                SettingsCard {
                    SettingRow(
                        title = "Require confirmation before sending",
                        subtitle = "On the home screen under Preferences",
                        onClick = { }
                    )
                    TextButton(
                        onClick = {
                            ServiceLocator.chatHistory.clear()
                            ServiceLocator.settings.clearHistory()
                        },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    ) {
                        Text("Clear local history", fontSize = 12.sp)
                    }
                }
            }

            item { Spacer(Modifier.height(28.dp)) }
        }
    }
}

@Composable
private fun PrivacyPoint(title: String, body: String) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp)) {
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(
            body,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
