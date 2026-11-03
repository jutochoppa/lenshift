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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
 * Picks the model that makes pictures for answers: any saved model, of any provider,
 * tagged as creating images (by its provider's model list, Lenshift's table, or the
 * model's "Creates images" setting). "Off" turns pictures off.
 */
@Composable
fun ImageModelPicker(settings: AppSettings, onDismiss: () -> Unit) {
    val candidates = settings.profiles.flatMap { p -> p.allModels.filter { p.createsImages(it) }.map { p to it } }
    fun choose(ref: ModelRef?) {
        ServiceLocator.settings.setImageModel(ref)
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Image generation") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Answers can ask this model for pictures that can't be drawn exactly. Each picture is a " +
                        "separate request to the model's provider, which bills it.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Choice("Off", null, selected = settings.imageModel == null) { choose(null) }
                candidates.groupBy { it.first.id }.values.forEach { group ->
                    val profile = group.first().first
                    Text(
                        profile.displayName,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)
                    )
                    group.forEach { (p, model) ->
                        Choice(
                            model,
                            p.specFor(model)?.summary(),
                            selected = settings.imageModel == ModelRef(p.id, model)
                        ) { choose(ModelRef(p.id, model)) }
                    }
                }
                if (candidates.isEmpty()) {
                    Text(
                        "No saved model is marked as creating images yet. Open a provider and add one marked " +
                            "“creates images” from its Models list (on OpenRouter, for example " +
                            "google/gemini-2.5-flash-image), or set “Creates images” to Yes in a model's settings.",
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 12.dp)
                    )
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
