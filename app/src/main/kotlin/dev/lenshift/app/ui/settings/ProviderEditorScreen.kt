package dev.lenshift.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lenshift.app.ai.ApiProtocol
import dev.lenshift.app.ai.CapabilityInference
import dev.lenshift.app.ai.ConnectionTest
import dev.lenshift.app.ai.ModelInfo
import dev.lenshift.app.ai.ProviderProfile
import dev.lenshift.app.data.settings.AppSettings
import dev.lenshift.app.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Provider editor.
 *
 * Every field is editable after creation, including the protocol — switching protocol
 * rewrites the endpoint path but preserves the key and model id.
 */
@Composable
fun ProviderEditorScreen(
    settings: AppSettings,
    profileId: String?,
    initialProtocol: ApiProtocol?,
    onBack: () -> Unit
) {
    // The editor's local state is keyed to the provider it edits. Without this, navigating
    // from provider A to provider B at the same call site would keep A's fields, key and
    // discovered-model list, and saving could write A's values into B's slot.
    key(profileId, initialProtocol) {
        ProviderEditorContent(
            settings = settings,
            profileId = profileId,
            initialProtocol = initialProtocol,
            onBack = onBack
        )
    }
}

@Composable
private fun ProviderEditorContent(
    settings: AppSettings,
    profileId: String?,
    initialProtocol: ApiProtocol?,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()

    val existing = remember(profileId) {
        profileId?.let { id -> settings.profiles.firstOrNull { it.id == id } }
    }

    // Create-on-first-edit so a brand-new provider has a stable id for its key slot.
    val working: ProviderProfile = remember(profileId, initialProtocol) {
        existing ?: ServiceLocator.settings.newProfile(
            initialProtocol ?: ApiProtocol.OPENAI_CHAT
        )
    }

    var profile by remember { mutableStateOf(working) }
    var apiKey by remember { mutableStateOf(ServiceLocator.settings.apiKey(working.id).orEmpty()) }
    var headersText by remember {
        mutableStateOf(
            working.customHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        )
    }
    var testResult by remember { mutableStateOf<ConnectionTest?>(null) }
    var testing by remember { mutableStateOf(false) }
    // The endpoint's model list, loaded once per endpoint and key.
    var listed by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
    var listedFor by remember { mutableStateOf<String?>(null) }
    var listError by remember { mutableStateOf<String?>(null) }
    var loadingModels by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(existing != null) }

    // The same resolution requests use: this model's overrides, then its reported or
    // documented spec, then the name-based guess.
    val capabilities = remember(profile) { CapabilityInference.forProfile(profile) }

    fun persist() {
        val parsedHeaders = headersText.lineSequence()
            .mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx <= 0) null
                else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }
            .filter { it.first.isNotBlank() && it.second.isNotBlank() }
            .toMap()

        val updated = profile.copy(customHeaders = parsedHeaders)
        ServiceLocator.settings.upsertProfile(updated)
        ServiceLocator.settings.setApiKey(updated.id, apiKey)
        if (ServiceLocator.settings.current.activeProfileId == null) {
            ServiceLocator.settings.setActiveProfile(updated.id)
        }
        profile = updated
        saved = true
    }

    fun runTest() {
        persist()
        testing = true
        testResult = null
        scope.launch {
            val adapter = ServiceLocator.providers.adapterFor(profile.protocol)
            val result = withContext(Dispatchers.IO) {
                runCatching { adapter.testConnection(profile, apiKey.ifBlank { null }) }
                    .getOrElse {
                        ConnectionTest(
                            ok = false,
                            headline = "Error",
                            detail = it.message ?: "Unknown failure",
                            latencyMs = 0
                        )
                    }
            }
            testing = false
            testResult = result
            if (result.models.isNotEmpty() && listed.isEmpty()) listed = result.models.map { ModelInfo(it) }
        }
    }

    /**
     * Loads the endpoint's models (with whatever limits it reports) unless they were
     * already loaded for this endpoint and key. Reported limits of saved models are
     * recorded on the profile.
     */
    fun loadModels() {
        if (profile.baseUrl.isBlank()) {
            listError = "Enter the base URL first, or type a model id and press Done."
            return
        }
        val forKey = "${profile.protocol.id}|${profile.normalizedBase()}|$apiKey"
        if (loadingModels || forKey == listedFor) return
        loadingModels = true
        listError = null
        val requested = profile
        val key = apiKey.ifBlank { null }
        scope.launch {
            val adapter = ServiceLocator.providers.adapterFor(requested.protocol)
            val result = withContext(Dispatchers.IO) {
                runCatching { adapter.listModels(requested, key) }
            }
            loadingModels = false
            result.onSuccess { list ->
                listedFor = forKey
                when {
                    list == null -> listError = "This endpoint doesn't list its models. Type the model id and press Done."
                    list.isEmpty() -> listError = "The endpoint returned no models. Type the model id and press Done."
                    else -> {
                        listed = list
                        profile = profile.withSpecs(list)
                    }
                }
            }.onFailure { e ->
                // Not remembered, so opening the list again retries.
                listError = "Couldn't load models: ${e.message ?: e::class.simpleName}"
            }
        }
    }

    // A saved provider's list loads right away so its models show their limits.
    LaunchedEffect(Unit) {
        if (existing != null && profile.baseUrl.isNotBlank()) loadModels()
    }

    SettingsScaffold(
        title = if (saved) profile.displayName else "New provider",
        onBack = { persist(); onBack() }
    ) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            // ---------------------------------------------------------- identity
            item {
                SectionHeader("Identity")
                SettingsCard {
                    SettingField(
                        value = profile.displayName,
                        onValueChange = { profile = profile.copy(displayName = it) },
                        label = "Display name",
                        placeholder = "My provider"
                    )

                    Text(
                        text = "Protocol",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp)
                    )
                    ChipRow(
                        options = ApiProtocol.entries.map { it.id to it.label },
                        selectedId = profile.protocol.id,
                        onSelect = { id ->
                            val next = ApiProtocol.fromId(id)
                            val previous = profile.protocol
                            profile = profile.copy(
                                protocol = next,
                                // Rewrite the endpoint only when it is still whatever we
                                // generated for the old protocol. A URL the user typed is
                                // preserved, because silently repointing a custom gateway
                                // at a different host is worse than an obviously wrong one.
                                baseUrl = if (profile.baseUrl.isBlank() ||
                                    profile.baseUrl == defaultBaseFor(previous)
                                ) {
                                    defaultBaseFor(next)
                                } else {
                                    profile.baseUrl
                                }
                            )
                        }
                    )
                    Text(
                        text = profile.protocol.description,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }

            // ------------------------------------------------------------ endpoint
            item {
                SectionHeader("Endpoint")
                SettingsCard {
                    SettingField(
                        value = profile.baseUrl,
                        onValueChange = { profile = profile.copy(baseUrl = it) },
                        label = "Base URL",
                        placeholder = placeholderFor(profile.protocol),
                        supporting = "Do not include the protocol path. Lenshift appends " +
                            pathFor(profile.protocol) + "."
                    )

                    SecretField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = "API key",
                        placeholder = if (profile.protocol == ApiProtocol.OLLAMA) {
                            "Not required for local Ollama"
                        } else {
                            "sk-…"
                        },
                        supporting = "Encrypted with the Android Keystore. Never logged."
                    )


                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { runTest() },
                            enabled = !testing && profile.baseUrl.isNotBlank()
                        ) {
                            if (testing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.height(14.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Text("Test connection", fontSize = 11.sp)
                            }
                        }
                    }

                    ModelsEditor(
                        profile = profile,
                        listed = listed,
                        loading = loadingModels,
                        listError = listError,
                        onOpenList = ::loadModels,
                        onChange = { profile = it }
                    )

                    testResult?.let { result ->
                        Surface(
                            color = if (result.ok) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            } else {
                                MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "${result.headline} · ${result.latencyMs} ms",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (result.ok) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error
                                )
                                Text(result.detail, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            // ------------------------------------------------------ model settings
            item {
                SectionHeader(
                    title = "Model settings",
                    subtitle = profile.modelId.ifBlank { null }?.let { "For $it. Each saved model keeps its own." }
                )
                SettingsCard {
                    CurrentModelSettings(
                        profile = profile,
                        capabilities = capabilities,
                        onChange = { profile = it }
                    )
                }
            }

            // ---------------------------------------------------------- behaviour
            item {
                SectionHeader("Behaviour")
                SettingsCard {
                    SwitchRow(
                        title = "Stream responses",
                        subtitle = "Show text as it arrives instead of waiting",
                        checked = profile.streaming,
                        onCheckedChange = { profile = profile.copy(streaming = it) }
                    )

                    SwitchRow(
                        title = "Allow plain HTTP",
                        subtitle = "Only for local endpoints such as 10.0.2.2 or 192.168.x.x",
                        checked = profile.allowCleartext,
                        onCheckedChange = { profile = profile.copy(allowCleartext = it) }
                    )

                    SettingField(
                        value = headersText,
                        onValueChange = { headersText = it },
                        label = "Custom headers",
                        placeholder = "HTTP-Referer: https://example.com\nX-Title: Lenshift",
                        supporting = "One per line as Name: value. Overrides built-in headers.",
                        singleLine = false,
                        minLines = 2
                    )
                }
            }

            item {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { persist(); onBack() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save provider")
                    }
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }

    LaunchedEffect(profile.protocol) {
        if (profile.baseUrl.isBlank()) {
            profile = profile.copy(baseUrl = defaultBaseFor(profile.protocol))
        }
    }
}

private fun placeholderFor(protocol: ApiProtocol): String = when (protocol) {
    ApiProtocol.OPENAI_CHAT -> "https://api.openai.com/v1"
    ApiProtocol.OPENAI_RESPONSES -> "https://api.openai.com/v1"
    ApiProtocol.ANTHROPIC_MESSAGES -> "https://api.anthropic.com/v1"
    ApiProtocol.GEMINI -> "https://generativelanguage.googleapis.com/v1beta"
    ApiProtocol.OLLAMA -> "http://10.0.2.2:11434"
}

private fun defaultBaseFor(protocol: ApiProtocol): String = when (protocol) {
    ApiProtocol.OLLAMA -> "http://10.0.2.2:11434"
    else -> ""
}

private fun pathFor(protocol: ApiProtocol): String = when (protocol) {
    ApiProtocol.OPENAI_CHAT -> "/chat/completions"
    ApiProtocol.OPENAI_RESPONSES -> "/responses"
    ApiProtocol.ANTHROPIC_MESSAGES -> "/messages"
    ApiProtocol.GEMINI -> "/models/{model}:generateContent"
    ApiProtocol.OLLAMA -> "/api/chat"
}
