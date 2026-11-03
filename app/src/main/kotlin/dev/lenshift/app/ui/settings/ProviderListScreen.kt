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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lenshift.app.ai.ApiProtocol
import dev.lenshift.app.data.settings.AppSettings
import dev.lenshift.app.di.ServiceLocator

@Composable
fun ProviderListScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onAdd: (ApiProtocol) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    SettingsScaffold(title = "Providers", onBack = onBack) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Text(
                    text = "Each provider is an independent endpoint, key and model. " +
                        "Lenshift talks only to the endpoint you configure.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }

            items(settings.profiles, key = { it.id }) { profile ->
                val isActive = profile.id == settings.activeProfileId
                val hasKey = ServiceLocator.settings.hasApiKey(profile.id)

                SettingsCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = profile.displayName,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (isActive) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = "Active",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.height(14.dp)
                                    )
                                }
                            }
                            Text(
                                text = profile.protocol.label,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = when (val extra = profile.allModels.size - 1) {
                                    -1 -> "No model set"
                                    0 -> profile.modelId
                                    else -> "${profile.modelId}  ·  +$extra more"
                                },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (!hasKey && profile.protocol != ApiProtocol.OLLAMA) {
                                Text(
                                    text = "No API key saved",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }

                        if (!isActive) {
                            TextButton(onClick = {
                                ServiceLocator.settings.setActiveProfile(profile.id)
                            }) {
                                Text("Use", fontSize = 11.sp)
                            }
                        }
                        IconButton(onClick = { onEdit(profile.id) }) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = "Edit",
                                modifier = Modifier.height(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { pendingDelete = profile.id }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Delete",
                                modifier = Modifier.height(18.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(12.dp))
                TextButton(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.padding(horizontal = 12.dp)
                ) {
                    Text("Add a provider", color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Choose a protocol", fontSize = 16.sp) },
            text = {
                Column {
                    ApiProtocol.entries.forEach { protocol ->
                        SettingRow(
                            title = protocol.label,
                            subtitle = protocol.description,
                            onClick = {
                                showAddDialog = false
                                onAdd(protocol)
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
            }
        )
    }

    pendingDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete provider?", fontSize = 16.sp) },
            text = { Text("The saved API key for this provider is erased too.", fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    ServiceLocator.settings.deleteProfile(id)
                    pendingDelete = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}
