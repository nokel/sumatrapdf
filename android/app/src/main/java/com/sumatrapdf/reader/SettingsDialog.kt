package com.sumatrapdf.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

@Composable
fun SettingsDialog(
    settings: Settings,
    libraryColumns: Int,
    onLibraryColumns: (Int) -> Unit,
    titleScale: Float,
    onTitleScale: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var restoreSession by remember { mutableStateOf(settings.restoreSession) }
    var showStartPage by remember { mutableStateOf(settings.showStartPage) }
    var keyboardShortcuts by remember { mutableStateOf(settings.keyboardShortcuts) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surface)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("Settings", style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
            Spacer(Modifier.height(16.dp))

            Text(
                "Title size on the library and frequently-read pages",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = titleScale,
                    onValueChange = onTitleScale,
                    valueRange = Settings.MIN_TITLE_SCALE..Settings.MAX_TITLE_SCALE,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "${(titleScale * 100).toInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                )
            }
            Text(
                "The quick brown fox",
                fontSize = 14.sp * titleScale,
                color = colors.onSurfaceVariant,
            )

            Spacer(Modifier.height(18.dp))
            Text(
                "Books across a row on the library wall",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ColumnChoice("Fit", libraryColumns == 0) { onLibraryColumns(0) }
                for (n in Settings.MIN_LIBRARY_COLUMNS..Settings.MAX_LIBRARY_COLUMNS) {
                    ColumnChoice(n.toString(), libraryColumns == n) { onLibraryColumns(n) }
                }
            }
            Text(
                "Pinching the library wall changes this too.",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )

            Spacer(Modifier.height(18.dp))
            SettingSwitch("Restore the last session on start", restoreSession) {
                restoreSession = it
                settings.restoreSession = it
            }
            SettingSwitch("Show the start page", showStartPage) {
                showStartPage = it
                settings.showStartPage = it
            }
            SettingSwitch("Keyboard shortcuts", keyboardShortcuts) {
                keyboardShortcuts = it
                settings.keyboardShortcuts = it
            }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}

@Composable
private fun ColumnChoice(label: String, selected: Boolean, onPick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) colors.outlineVariant else Color.Transparent)
            .clickable { onPick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) colors.onSurface else colors.primary,
        )
    }
}

@Composable
private fun SettingSwitch(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = on, onCheckedChange = onChange)
    }
}
