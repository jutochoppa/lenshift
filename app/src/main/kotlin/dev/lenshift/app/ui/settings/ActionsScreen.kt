package dev.lenshift.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lenshift.app.data.settings.AppSettings
import dev.lenshift.app.data.settings.QuickAction
import dev.lenshift.app.di.ServiceLocator

/** The action chips: edit, reorder and delete them, add your own, and pick the default. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActionsScreen(settings: AppSettings, onBack: () -> Unit) {
    val repository = ServiceLocator.settings
    val actions = settings.quickActions
    /** The action being edited, or [QuickAction.ASK] standing in for a new one. */
    var editing by remember { mutableStateOf<QuickAction?>(null) }
    var pendingDelete by remember { mutableStateOf<QuickAction?>(null) }

    SettingsScaffold(title = "Actions", onBack = onBack) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                SectionHeader(
                    title = "Your actions",
                    subtitle = "Shown as chips above the chat, in this order. Each one sends its " +
                        "prompt with your selection. Tap one to edit it."
                )
                SettingsCard {
                    if (actions.isEmpty()) {
                        Text(
                            "No actions. Add one below, or bring back the stock ones.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                    actions.forEachIndexed { index, action ->
                        ActionRow(
                            action = action,
                            isDefault = action.id == settings.defaultActionId,
                            canMoveUp = index > 0,
                            canMoveDown = index < actions.lastIndex,
                            onEdit = { editing = action },
                            onMove = { by -> repository.moveQuickAction(action.id, by) },
                            onDelete = { pendingDelete = action }
                        )
                    }
                }
                FlowRow(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(onClick = { editing = QuickAction.ASK }) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add an action")
                    }
                    val deleted = settings.deletedStockActions
                    if (deleted.isNotEmpty()) {
                        TextButton(onClick = { repository.restoreStockActions() }) {
                            Text(
                                if (deleted.size == 1) "Bring back “${deleted.single().label}”"
                                else "Bring back ${deleted.size} stock actions"
                            )
                        }
                    }
                }
            }

            item {
                SectionHeader(
                    title = "Default action",
                    subtitle = "What Send does with nothing typed, and what runs when a selection " +
                        "searches straight away."
                )
                SettingsCard {
                    (listOf(QuickAction.ASK) + actions).forEach { action ->
                        SettingRow(
                            title = action.label,
                            trailing = if (action.id == settings.defaultQuickAction.id) {
                                {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = "Default",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            } else null,
                            onClick = { repository.setDefaultQuickAction(action) }
                        )
                    }
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }

    editing?.let { action ->
        val isNew = action.id == QuickAction.ASK.id
        ActionDialog(
            initial = action.takeUnless { isNew },
            onDismiss = { editing = null },
            onSave = { label, prompt ->
                repository.saveQuickAction(if (isNew) QuickAction.custom(label, prompt) else action.edited(label, prompt))
                editing = null
            }
        )
    }

    pendingDelete?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete “${action.label}”?", fontSize = 16.sp) },
            text = {
                Text(
                    if (action.isStock) "You can bring stock actions back from this screen."
                    else "Its prompt is deleted too.",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    repository.deleteQuickAction(action.id)
                    pendingDelete = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ActionRow(
    action: QuickAction,
    isDefault: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEdit: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(action.label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                if (isDefault) {
                    Spacer(Modifier.width(8.dp))
                    StatusPill(text = "Default", positive = true)
                }
            }
            Text(
                action.prompt,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move ${action.label} up")
        }
        IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move ${action.label} down")
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, contentDescription = "Delete ${action.label}", modifier = Modifier.size(20.dp))
        }
    }
}

/** Name and prompt of a new action ([initial] null) or an existing one. */
@Composable
private fun ActionDialog(
    initial: QuickAction?,
    onDismiss: () -> Unit,
    onSave: (label: String, prompt: String) -> Unit
) {
    var label by remember(initial?.id) { mutableStateOf(initial?.label.orEmpty()) }
    var prompt by remember(initial?.id) { mutableStateOf(initial?.prompt.orEmpty()) }
    val original = initial?.let { QuickAction.STOCK.firstOrNull { stock -> stock.id == it.id } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New action" else "Edit action", fontSize = 16.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it.take(QuickAction.MAX_PROMPT) },
                    label = { Text("Prompt", fontSize = 12.sp) },
                    placeholder = { Text("What the model should do with your selection", fontSize = 12.sp) },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(QuickAction.MAX_LABEL) },
                    label = { Text("Name on the chip", fontSize = 12.sp) },
                    placeholder = {
                        Text(QuickAction.labelFor("", prompt).ifEmpty { "Taken from the prompt" }, fontSize = 12.sp)
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Sent with your selection, as if you had typed it.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (original != null && (label != original.label || prompt != original.prompt)) {
                    TextButton(onClick = {
                        label = original.label
                        prompt = original.prompt
                    }) { Text("Reset to the stock wording") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(label, prompt) }, enabled = prompt.isNotBlank()) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** The Actions row on the settings home: how many there are and what Send does. */
internal fun actionsSummary(settings: AppSettings): String {
    val count = settings.quickActions.size
    val chips = when (count) {
        0 -> "No actions"
        1 -> "1 action"
        else -> "$count actions"
    }
    val default = settings.defaultQuickAction
    return if (default.id == QuickAction.ASK.id) "$chips · Send with nothing typed lets the model decide"
    else "$chips · Send with nothing typed: ${default.label}"
}
