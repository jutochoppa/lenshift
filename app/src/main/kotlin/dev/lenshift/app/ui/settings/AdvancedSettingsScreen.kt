package dev.lenshift.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lenshift.app.ai.PromptBuilder
import dev.lenshift.app.data.settings.AppSettings
import dev.lenshift.app.data.settings.QuickAction
import dev.lenshift.app.di.ServiceLocator

@Composable
fun AdvancedSettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit
) {
    val active = settings.profiles.firstOrNull { it.id == settings.activeProfileId }
        ?: settings.profiles.firstOrNull()

    var instructions by remember {
        mutableStateOf(settings.customSystemInstructions)
    }
    var temperature by remember {
        mutableStateOf(active?.temperature?.toString().orEmpty())
    }
    var topP by remember { mutableStateOf(active?.topP?.toString().orEmpty()) }
    var topK by remember { mutableStateOf(active?.topK?.toString().orEmpty()) }
    var maxTokens by remember {
        mutableStateOf(active?.maxOutputTokens?.toString().orEmpty())
    }
    var contextOverride by remember {
        mutableStateOf(active?.contextWindowOverride?.toString().orEmpty())
    }
    var reasoningBudget by remember {
        mutableStateOf(active?.reasoningTokenBudget?.toString().orEmpty())
    }
    var imageEdge by remember {
        mutableStateOf(active?.maxImageEdgePx?.toString() ?: "1568")
    }
    var jpegQuality by remember {
        mutableStateOf(active?.jpegQuality?.toString() ?: "85")
    }
    var timeout by remember {
        mutableStateOf((active?.timeoutMs?.div(1000) ?: 90).toString())
    }
    var extraJson by remember {
        mutableStateOf(active?.extraJsonParameters.orEmpty())
    }

    fun saveProfileParameters() {
        val profile = active ?: return
        ServiceLocator.settings.upsertProfile(
            profile.copy(
                temperature = temperature.toFloatOrNull(),
                topP = topP.toFloatOrNull(),
                topK = topK.toIntOrNull(),
                maxOutputTokens = maxTokens.toIntOrNull(),
                contextWindowOverride = contextOverride.toIntOrNull(),
                reasoningTokenBudget = reasoningBudget.toIntOrNull(),
                maxImageEdgePx = imageEdge.toIntOrNull() ?: 1568,
                jpegQuality = jpegQuality.toIntOrNull() ?: 85,
                timeoutMs = (timeout.toLongOrNull() ?: 90L) * 1000L,
                extraJsonParameters = extraJson
            )
        )
        ServiceLocator.settings.updatePreferences {
            it.copy(customSystemInstructions = instructions)
        }
    }

    SettingsScaffold(title = "Advanced", onBack = onBack) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            // --------------------------------------------------------- parameters
            item {
                SectionHeader(
                    title = "Generation parameters",
                    subtitle = if (active == null) {
                        "Configure a provider first"
                    } else {
                        "Applies to ${active.displayName} · ${active.modelId.ifBlank { "no model" }}. " +
                            "Each saved model keeps its own."
                    }
                )
                SettingsCard {
                    SettingField(
                        value = temperature,
                        onValueChange = { temperature = it },
                        label = "Temperature",
                        placeholder = "Provider default",
                        supporting = "Blank means the provider decides. Values above 1.5 " +
                            "are rejected by most models."
                    )
                    SettingField(
                        value = topP,
                        onValueChange = { topP = it },
                        label = "Top-p",
                        placeholder = "Provider default"
                    )
                    SettingField(
                        value = topK,
                        onValueChange = { topK = it },
                        label = "Top-k",
                        placeholder = "Unsupported by most hosted providers"
                    )
                    SettingField(
                        value = maxTokens,
                        onValueChange = { maxTokens = it },
                        label = "Max output tokens",
                        placeholder = "Provider default",
                        supporting = "Reasoning models spend part of this on thinking."
                    )
                    SettingField(
                        value = contextOverride,
                        onValueChange = { contextOverride = it },
                        label = "Context window override",
                        placeholder = "Unknown",
                        supporting = "Lenshift never invents a context size. Set it only " +
                            "if you know the model's real limit."
                    )
                    SettingField(
                        value = reasoningBudget,
                        onValueChange = { reasoningBudget = it },
                        label = "Reasoning token budget",
                        placeholder = "Derived from the reasoning mode"
                    )
                }
            }

            // -------------------------------------------------------------- images
            item {
                SectionHeader(
                    title = "Images",
                    subtitle = "Controls what a vision request actually uploads"
                )
                SettingsCard {
                    SettingField(
                        value = imageEdge,
                        onValueChange = { imageEdge = it },
                        label = "Max image edge (px)",
                        placeholder = "1568",
                        supporting = "Larger crops are downscaled before upload."
                    )
                    SettingField(
                        value = jpegQuality,
                        onValueChange = { jpegQuality = it },
                        label = "JPEG quality",
                        placeholder = "85",
                        supporting = "40–100. PNG is used instead when detail is High."
                    )
                }
            }

            // ------------------------------------------------------------- network
            item {
                SectionHeader("Network")
                SettingsCard {
                    SettingField(
                        value = timeout,
                        onValueChange = { timeout = it },
                        label = "Request timeout (seconds)",
                        placeholder = "90"
                    )
                    SettingField(
                        value = extraJson,
                        onValueChange = { extraJson = it },
                        label = "Extra JSON parameters",
                        placeholder = "{\"seed\": 42}",
                        supporting = "Merged into the request body. Use for provider quirks " +
                            "Lenshift does not model.",
                        singleLine = false,
                        minLines = 2
                    )
                }
            }

            // ------------------------------------------------------------- prompts
            item {
                SectionHeader(
                    title = "System instructions",
                    subtitle = "Applies to every provider"
                )
                SettingsCard {
                    PromptBuilder.presets.forEach { preset ->
                        SettingRow(
                            title = preset.label,
                            subtitle = null,
                            onClick = { instructions = preset.body }
                        )
                    }
                    SettingField(
                        value = instructions,
                        onValueChange = { instructions = it },
                        label = "Custom instructions",
                        placeholder = "Leave blank to use the balanced default",
                        singleLine = false,
                        minLines = 6
                    )
                    Text(
                        text = "Screen text is always inserted as data between " +
                            "<screen_content> tags, never as instructions. Your custom " +
                            "text cannot change that.",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }

            // --------------------------------------------------------- default action
            item {
                SectionHeader("Default action")
                SettingsCard {
                    QuickAction.entries.forEach { action ->
                        SettingRow(
                            title = action.label,
                            subtitle = if (action.id == settings.defaultQuickAction.id) {
                                "Currently the default"
                            } else null,
                            onClick = {
                                ServiceLocator.settings.setDefaultQuickAction(action)
                            }
                        )
                    }
                }
            }

            item {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Button(
                        onClick = { saveProfileParameters(); onBack() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save")
                    }
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}
