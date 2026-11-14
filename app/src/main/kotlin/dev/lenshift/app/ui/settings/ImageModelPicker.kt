package dev.lenshift.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lenshift.app.data.settings.AppSettings
import dev.lenshift.app.data.settings.ModelRef
import dev.lenshift.app.di.ServiceLocator

/** "Model · provider" for the chosen image model, or null when pictures are off. */
fun imageModelLabel(settings: AppSettings): String? {
    val ref = settings.imageModel ?: return null
    val profile = settings.profiles.firstOrNull { it.id == ref.profileId } ?: return "The chosen model was removed"
    return "${ref.model}  ·  ${profile.displayName}"
}

/**
 * Picks the model that makes the pictures chats ask for: any saved model of any provider
 * that can make images (picture models first), or one typed in. Whatever is picked is
 * saved to its provider and marked as making pictures, so nothing needs tagging first.
 * [onChosen] runs after a choice is saved.
 */
@Composable
fun ImageModelPicker(settings: AppSettings, onDismiss: () -> Unit, onChosen: (ModelRef?) -> Unit = {}) {
    val providers = settings.profiles.filter { it.protocol.canCreateImages }
    var adding by remember { mutableStateOf(false) }
    var addProvider by remember { mutableStateOf(providers.firstOrNull()?.id) }
    var addModel by remember { mutableStateOf("") }

    fun choose(ref: ModelRef?) {
        ServiceLocator.settings.setImageModel(ref)
        onChosen(ref)
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Image model") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Ask for a picture in any chat, like “make an image of…” or “turn this into a sketch”. " +
                        "Your chat model hands the request to this model and the picture appears in the answer. " +
                        "Each picture is a separate request, billed by this model's provider.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Choice("Off", "Chats can't make pictures", selected = settings.imageModel == null) { choose(null) }
                providers.forEach { profile ->
                    // Picture models first; the rest can still be picked, which marks them.
                    val models = profile.allModels.sortedByDescending { profile.createsImages(it) }
                    if (models.isEmpty()) return@forEach
                    Text(
                        profile.displayName,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)
                    )
                    models.forEach { model ->
                        val makesPictures = profile.createsImages(model)
                        Choice(
                            model,
                            if (makesPictures) profile.specFor(model)?.summary()?.takeIf { it.isNotBlank() } ?: "Makes pictures"
                            else "Not marked as making pictures",
                            selected = settings.imageModel == ModelRef(profile.id, model)
                        ) { choose(ModelRef(profile.id, model)) }
                    }
                }
                when {
                    providers.isEmpty() -> Text(
                        "None of your providers can make pictures. Add an OpenAI-compatible or Gemini provider " +
                            "(OpenRouter's google/gemini-2.5-flash-image works); Anthropic and Ollama can't.",
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    !adding -> TextButton(onClick = { adding = true }, modifier = Modifier.padding(top = 4.dp)) {
                        Text("Use a model that isn't listed")
                    }
                    else -> Column(Modifier.padding(top = 8.dp)) {
                        if (providers.size > 1) {
                            ChipRow(
                                options = providers.map { it.id to it.displayName },
                                selectedId = addProvider.orEmpty(),
                                onSelect = { addProvider = it }
                            )
                        }
                        OutlinedTextField(
                            value = addModel,
                            onValueChange = { addModel = it },
                            label = { Text("Model id", fontSize = 12.sp) },
                            placeholder = { Text("gpt-image-1", fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        TextButton(
                            onClick = { addProvider?.let { choose(ModelRef(it, addModel.trim())) } },
                            enabled = addModel.isNotBlank() && addProvider != null
                        ) { Text("Use this model") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun Choice(title: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            detail?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
