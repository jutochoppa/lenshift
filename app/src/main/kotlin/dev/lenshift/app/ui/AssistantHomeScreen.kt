package dev.lenshift.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lenshift.app.data.settings.AppSettings
import dev.lenshift.app.data.settings.QuickAction

/**
 * Search-first landing surface. Settings stay behind the gear; the default app entry is the
 * useful assistant/search surface rather than a configuration dashboard.
 */
@Composable
fun AssistantHomeScreen(
    settings: AppSettings,
    onOpenSettings: () -> Unit,
    onSearch: (String) -> Unit,
    onQuickAction: (QuickAction) -> Unit
) {
    var query by remember { mutableStateOf("") }

    fun submit() {
        val value = query.trim()
        if (value.isNotEmpty()) onSearch(value)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AssistantColors.Background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Lenshift",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = AssistantColors.SecondaryText)
                }
            }

            Spacer(Modifier.weight(1f))

            Text(
                text = "Lenshift",
                color = Color.White,
                fontSize = 48.sp,
                fontWeight = FontWeight.Normal,
                letterSpacing = (-1.5).sp
            )
            Text(
                text = "Search your screen with any model",
                color = AssistantColors.SecondaryText,
                fontSize = 15.sp,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
            )

            SearchPill(
                value = query,
                onValueChange = { query = it },
                onSend = ::submit,
                onCamera = { onSearch("") }
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AssistantChip(
                    modifier = Modifier.weight(1.25f),
                    icon = { Icon(Icons.Filled.AutoAwesome, null, Modifier.size(20.dp)) },
                    label = "AI Mode",
                    onClick = { onQuickAction(QuickAction.EXPLAIN) }
                )
                AssistantChip(
                    modifier = Modifier.weight(1f),
                    icon = { Icon(Icons.Outlined.Translate, null, Modifier.size(20.dp)) },
                    label = "Translate",
                    onClick = { onQuickAction(QuickAction.TRANSLATE) }
                )
                AssistantChip(
                    modifier = Modifier.weight(1f),
                    icon = { Icon(Icons.Filled.CameraAlt, null, Modifier.size(20.dp)) },
                    label = "Lens",
                    onClick = { onSearch("") }
                )
            }

            Spacer(Modifier.weight(1.15f))

            Text(
                text = if (settings.profiles.isEmpty()) {
                    "Add a provider in Settings to start asking questions"
                } else {
                    "Circle, tap, or ask anything about the screen"
                },
                color = AssistantColors.SecondaryText,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 18.dp)
            )
        }
    }
}

@Composable
private fun SearchPill(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onCamera: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(34.dp),
        color = AssistantColors.SearchSurface
    ) {
        Row(
            modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSend() }),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text("Search or ask about this screen", color = AssistantColors.Placeholder, fontSize = 18.sp)
                        }
                        inner()
                    }
                }
            )
            IconButton(onClick = onSend, enabled = value.isNotBlank()) {
                Icon(Icons.Filled.Search, contentDescription = "Search", tint = if (value.isNotBlank()) Color.White else AssistantColors.Placeholder)
            }
            IconButton(onClick = onCamera) {
                Icon(Icons.Filled.CameraAlt, contentDescription = "Search with screen", tint = Color.White)
            }
            IconButton(onClick = {}) {
                Icon(Icons.Filled.MicNone, contentDescription = "Voice search", tint = Color.White)
            }
        }
    }
}

@Composable
private fun AssistantChip(
    modifier: Modifier,
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .height(58.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(30.dp),
        color = AssistantColors.SearchSurface,
        contentColor = Color.White
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            icon()
            Spacer(Modifier.width(8.dp))
            Text(label, fontSize = 14.sp, maxLines = 1)
        }
    }
}

object AssistantColors {
    val Background = Color(0xFF202124)
    val SearchSurface = Color(0xFF303134)
    val SecondaryText = Color(0xFFB8B9C0)
    val Placeholder = Color(0xFF9AA0A6)
}
