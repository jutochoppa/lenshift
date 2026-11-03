package dev.lenshift.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lenshift.app.ai.ApiProtocol
import dev.lenshift.app.ai.KnownModels
import dev.lenshift.app.ai.ModelCapabilities
import dev.lenshift.app.ai.ModelInfo
import dev.lenshift.app.ai.ModelSpec
import dev.lenshift.app.ai.ProviderProfile
import dev.lenshift.app.ai.ReasoningMapper
import dev.lenshift.app.ai.ReasoningMode
import dev.lenshift.app.ai.Tri

/**
 * The provider's saved models: pick the current one, remove any, and add more from the
 * endpoint's list (fetched when the add field is opened, filtered by what is typed) or by
 * typing an id. Every saved model shows up in the chat's picker.
 */
@Composable
fun ModelsEditor(
    profile: ProviderProfile,
    listed: List<ModelInfo>,
    loading: Boolean,
    listError: String?,
    onOpenList: () -> Unit,
    onChange: (ProviderProfile) -> Unit
) {
    val saved = profile.allModels
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            "Models",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Text(
            if (saved.isEmpty()) {
                "No models yet. Open the list below to pick from the endpoint's models, or type an id."
            } else {
                "The selected model answers new questions and its settings are shown below. " +
                    "Switch between saved models from the chat."
            },
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
        )

        saved.forEach { model ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.RadioButton) { onChange(profile.selecting(model)) }
                    .padding(start = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = model == profile.modelId, onClick = { onChange(profile.selecting(model)) })
                Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                    Text(model, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    specLine(profile, model)?.let {
                        Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = { onChange(profile.withoutModel(model)) }) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove $model", modifier = Modifier.size(18.dp))
                }
            }
        }

        AddModelField(profile, listed, loading, listError, onOpenList, onChange)
    }
}

/** "128K context · 16K output · images (typical)" for a saved model, or null if unknown. */
private fun specLine(profile: ProviderProfile, model: String): String? {
    val summary = profile.specFor(model)?.summary() ?: return null
    return if (model in profile.modelSpecs) summary else "$summary  (typical)"
}

/**
 * A text field that opens the endpoint's model list as a dropdown. Typing narrows the
 * list (every word must match); tapping a model saves or removes it and keeps the list
 * open for more; Done adds exactly what was typed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddModelField(
    profile: ProviderProfile,
    listed: List<ModelInfo>,
    loading: Boolean,
    listError: String?,
    onOpenList: () -> Unit,
    onChange: (ProviderProfile) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    val saved = profile.allModels.toSet()
    val matches = remember(listed, query) {
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        listed.filter { info ->
            val haystack = (info.id + " " + info.name.orEmpty()).lowercase()
            terms.all { it in haystack }
        }
    }
    val typed = query.trim()

    fun addTyped() {
        if (typed.isEmpty()) return
        onChange(profile.withModel(typed, listed.firstOrNull { it.id == typed }?.spec))
        query = ""
        expanded = false
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { open ->
            expanded = open
            if (open) onOpenList()
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                if (!expanded) {
                    expanded = true
                    onOpenList()
                }
            },
            label = { Text("Add a model", fontSize = 12.sp) },
            placeholder = { Text("Search the list or type a model id", fontSize = 12.sp) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { addTyped() }),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val status = when {
                loading && listed.isEmpty() -> "Loading models…"
                listed.isEmpty() -> listError ?: "No model list yet. Type a model id and press Done."
                matches.isEmpty() -> "No listed model matches “$typed”."
                else -> null
            }
            if (status != null) {
                DropdownMenuItem(
                    text = { Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = {},
                    enabled = false
                )
            }
            matches.take(MAX_SHOWN).forEach { info ->
                val isSaved = info.id in saved
                val summary = (info.spec?.orElse(KnownModels.lookup(info.id)) ?: KnownModels.lookup(info.id))?.summary()
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(info.id, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            listOfNotNull(info.name, summary).joinToString("  ·  ").takeIf { it.isNotEmpty() }?.let {
                                Text(
                                    it,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    trailingIcon = {
                        if (isSaved) Icon(Icons.Filled.Check, contentDescription = "Saved", tint = MaterialTheme.colorScheme.primary)
                    },
                    onClick = {
                        onChange(if (isSaved) profile.withoutModel(info.id) else profile.withModel(info.id, info.spec))
                    }
                )
            }
            if (matches.size > MAX_SHOWN) {
                DropdownMenuItem(
                    text = {
                        Text(
                            "${matches.size - MAX_SHOWN} more. Keep typing to narrow the list.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    onClick = {},
                    enabled = false
                )
            }
            // Below the matches, so a half-typed filter is not added by a stray tap.
            if (typed.isNotEmpty() && listed.none { it.id == typed } && typed !in saved) {
                DropdownMenuItem(
                    text = { Text("Add “$typed”", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    onClick = ::addTyped
                )
            }
        }
    }
}

/**
 * Settings of the current model, limited to what that model takes: its reasoning levels
 * (with what each sends), sampling parameters it accepts, and its limits as defaults.
 */
@Composable
fun CurrentModelSettings(
    profile: ProviderProfile,
    capabilities: ModelCapabilities,
    onChange: (ProviderProfile) -> Unit
) {
    val model = profile.modelId
    if (model.isBlank()) {
        Text(
            "Add a model to see and change its settings.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        return
    }
    val spec = profile.specFor(model)
    val reported = model in profile.modelSpecs
    // OpenRouter and the Responses API both take the nested reasoning.effort form.
    val nestedEffort = profile.protocol == ApiProtocol.OPENAI_RESPONSES ||
        profile.normalizedBase().contains("openrouter.ai", ignoreCase = true)

    Text(
        when {
            spec == null -> "Lenshift has no published limits for this model. Leave fields blank to use the provider's defaults."
            reported -> "Limits as reported by the endpoint. Blank fields use the model's defaults."
            else -> "Typical limits for this model. Blank fields use the model's defaults."
        },
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
    spec?.parameters?.takeIf { it.isNotEmpty() }?.let { parameters ->
        Text(
            "Accepts: " + parameters.sortedWith(compareBy({ it !in KEY_PARAMETERS }, { it })).joinToString(", "),
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
        )
    }

    // ---- reasoning
    Text(
        "Reasoning",
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp)
    )
    val levels = ReasoningMapper.levels(capabilities)
    if (levels.isEmpty()) {
        Text(
            ReasoningMapper.unsupportedReason(capabilities) ?: "This model takes no reasoning setting.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
    } else {
        val shown = ReasoningMapper.effectiveMode(profile.reasoningMode, capabilities)
        ChipRow(
            options = (listOf(ReasoningMode.AUTO) + levels).map { it.id to it.label },
            selectedId = shown.id,
            onSelect = { onChange(profile.copy(reasoningMode = ReasoningMode.fromId(it))) }
        )
        Text(
            ReasoningMapper.wireValue(shown, capabilities, nestedEffort, profile.reasoningTokenBudget),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
        )
    }

    // ---- sampling and limits
    if (capabilities.accepts(ModelSpec.TEMPERATURE)) {
        NumberField(
            model = model,
            value = profile.temperature?.toString().orEmpty(),
            label = "Temperature",
            placeholder = "Model default",
            decimal = true,
            onValue = { onChange(profile.copy(temperature = it.toFloatOrNull())) }
        )
    } else {
        Text(
            "This model does not accept temperature or top-p, so none is sent.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )
    }
    val outputLimit = spec?.maxOutputTokens
    NumberField(
        model = model,
        value = profile.maxOutputTokens?.toString().orEmpty(),
        label = "Max output tokens",
        placeholder = outputLimit?.let { "Model default (up to ${"%,d".format(it)})" } ?: "Provider default",
        supporting = when {
            outputLimit == null -> "Blank uses the provider's default. Reasoning models spend part of this on thinking."
            (profile.maxOutputTokens ?: 0) > outputLimit ->
                "Above this model's ${"%,d".format(outputLimit)}-token limit; the provider may reject it."
            else -> "Blank uses the model's default. Its limit is ${"%,d".format(outputLimit)} tokens."
        },
        onValue = { onChange(profile.copy(maxOutputTokens = it.toIntOrNull())) }
    )
    NumberField(
        model = model,
        value = profile.contextWindowOverride?.toString().orEmpty(),
        label = "Context window",
        placeholder = spec?.contextWindow?.let { "%,d tokens".format(it) } ?: "Unknown",
        supporting = spec?.contextWindow?.let {
            "Blank uses ${"%,d".format(it)} tokens (${if (reported) "reported" else "typical"}). Set it only if the real limit differs."
        } ?: "Unknown for this model. Set it only if you know its real limit.",
        onValue = { onChange(profile.copy(contextWindowOverride = it.toIntOrNull())) }
    )

    // ---- capability overrides
    OverrideChips(
        title = "Vision (accepts images)",
        current = profile.visionOverride,
        detected = spec?.vision?.takeIf { it != Tri.UNKNOWN } ?: capabilities.vision.takeIf { profile.visionOverride == Tri.UNKNOWN },
        onSelect = { onChange(profile.copy(visionOverride = it)) }
    )
    OverrideChips(
        title = "Reasoning (accepts reasoning settings)",
        current = profile.reasoningOverride,
        detected = spec?.reasoning?.takeIf { it != Tri.UNKNOWN } ?: capabilities.reasoning.takeIf { profile.reasoningOverride == Tri.UNKNOWN },
        onSelect = { onChange(profile.copy(reasoningOverride = it)) }
    )
    if (profile.protocol.canCreateImages) {
        OverrideChips(
            title = "Creates images (can be the image model)",
            current = profile.imageOutputOverride,
            detected = spec?.imageOutput?.takeIf { it != Tri.UNKNOWN },
            onSelect = { onChange(profile.copy(imageOutputOverride = it)) }
        )
    }
}

/** A numeric field that keeps what is typed ("0." on the way to "0.7") per [model]. */
@Composable
private fun NumberField(
    model: String,
    value: String,
    label: String,
    placeholder: String,
    onValue: (String) -> Unit,
    supporting: String? = null,
    decimal: Boolean = false
) {
    var text by remember(model) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            text = typed.filter { it.isDigit() || (decimal && it == '.') }
            onValue(text)
        },
        label = { Text(label, fontSize = 12.sp) },
        placeholder = { Text(placeholder, fontSize = 12.sp) },
        supportingText = supporting?.let { { Text(it, fontSize = 10.sp) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

/** Auto / Yes / No, with Auto naming what was detected ("Auto (yes)"). */
@Composable
private fun OverrideChips(title: String, current: Tri, detected: Tri?, onSelect: (Tri) -> Unit) {
    Text(title, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
    val auto = when (detected) {
        Tri.YES -> "Auto (yes)"
        Tri.NO -> "Auto (no)"
        else -> "Auto"
    }
    ChipRow(
        options = listOf(Tri.UNKNOWN.name to auto, Tri.YES.name to "Yes", Tri.NO.name to "No"),
        selectedId = current.name,
        onSelect = { onSelect(Tri.valueOf(it)) }
    )
}

/** Shown first in the "Accepts" line; the rest follow alphabetically. */
private val KEY_PARAMETERS = setOf(
    ModelSpec.TEMPERATURE, ModelSpec.TOP_P, ModelSpec.TOP_K, ModelSpec.MAX_TOKENS, ModelSpec.REASONING
)

private const val MAX_SHOWN = 60
