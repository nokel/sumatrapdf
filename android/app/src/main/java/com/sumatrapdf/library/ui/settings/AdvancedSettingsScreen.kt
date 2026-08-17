package com.sumatrapdf.library.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sumatrapdf.library.data.SettingsStore
import com.sumatrapdf.library.ui.theme.SumTypography

// AdvancedSettingsScreen is the deeper half of the Win32 Settings
// dialog. Every toggle here changes a less-common preference; that's
// why it's behind a separate screen rather than on the main Settings
// tab. Sliders control page spacing (px between pages in continuous
// mode) and EPUB text size; switches and text fields map onto the
// SumatraPDF advanced settings keys 1:1.
@Composable
fun AdvancedSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val store = remember { SettingsStore.get(context) }
    val s by store.state.collectAsState()
    var confirmReset by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(
                    text = "Advanced Settings",
                    style = SumTypography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { confirmReset = true }) {
                    Text("Reset")
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Section("Reading") {
                SwitchRow(
                    label = "Show scrollbar",
                    description = "Display a vertical scrollbar in continuous mode.",
                    checked = s.scrollbar,
                    onChange = { store.setScrollbar(it) },
                )
                SliderRow(
                    label = "Page spacing",
                    description = "Vertical spacing (dp) between pages in continuous mode.",
                    value = s.pageSpacingDp.toFloat(),
                    range = 0f..32f,
                    onValueChange = { store.setPageSpacing(it.toInt()) },
                )
                SliderRow(
                    label = "EPUB text size",
                    description = "Font size used for reflowed EPUB pages (pt).",
                    value = s.textSizePt,
                    range = 8f..32f,
                    onValueChange = { store.setTextSize(it) },
                )
            }
            Section("Comic book") {
                SwitchRow(
                    label = "Stretch small images",
                    description = "Stretch pages smaller than the window to fit width.",
                    checked = s.cbStretch,
                    onChange = { store.setCbStretch(it) },
                )
                SwitchRow(
                    label = "Two pages side by side",
                    description = "Show two comic pages on each row by default.",
                    checked = s.cbTwoPages,
                    onChange = { store.setCbTwoPages(it) },
                )
                SwitchRow(
                    label = "Continuous scroll",
                    description = "Scroll vertically through pages rather than swipe horizontally.",
                    checked = s.cbScrollContinuous,
                    onChange = { store.setCbScrollContinuous(it) },
                )
                SwitchRow(
                    label = "Allow CJK fonts",
                    description = "Include Chinese, Japanese and Korean fallback fonts in the comic book engine.",
                    checked = s.cbAllowCJK,
                    onChange = { store.setCbAllowCJK(it) },
                )
                TextFieldRow(
                    label = "Font list",
                    description = "Comma-separated list of preferred fonts, e.g. 'Helvetica, Arial'.",
                    value = s.cbFontList,
                    onChange = { store.setCbFontList(it) },
                )
                TextFieldRow(
                    label = "Default font",
                    description = "Font used when the document does not specify one.",
                    value = s.cbDefaultFont,
                    onChange = { store.setCbDefaultFont(it) },
                )
                TextFieldRow(
                    label = "Monospace font",
                    description = "Font used for fixed-width text such as code samples.",
                    value = s.cbMonoFont,
                    onChange = { store.setCbMonoFont(it) },
                )
            }
            Section("Custom home") {
                TextFieldRow(
                    label = "Custom home page path",
                    description = "When set, the reader opens this PDF or EPUB on launch instead of the bookshelf. Leave empty to open the bookshelf.",
                    value = s.customHomePath,
                    onChange = { store.setCustomHomePath(it) },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset advanced settings?") },
            text = { Text("This restores the defaults for every advanced setting. It does not touch theme, layout or recent files.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    store.resetAdvanced()
                }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun Section(label: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(8.dp))
    Text(
        text = label,
        style = SumTypography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
    )
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
    ) {
        Column { content() }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = SumTypography.bodyLarge)
            if (description.isNotEmpty()) {
                Text(
                    text = description,
                    style = SumTypography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.padding(horizontal = 4.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
}

@Composable
private fun SliderRow(
    label: String,
    description: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(label, style = SumTypography.bodyLarge)
        if (description.isNotEmpty()) {
            Text(
                text = description,
                style = SumTypography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = range,
                modifier = Modifier.weight(1f).padding(top = 4.dp),
            )
            Spacer(Modifier.padding(horizontal = 4.dp))
            Text(
                text = if (range.endInclusive <= 100f) value.toInt().toString() else "%.1f".format(value),
                style = SumTypography.labelLarge,
                modifier = Modifier.padding(start = 8.dp).width(48.dp),
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
}

@Composable
private fun TextFieldRow(
    label: String,
    description: String,
    value: String,
    onChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(label, style = SumTypography.bodyLarge)
        if (description.isNotEmpty()) {
            Text(
                text = description,
                style = SumTypography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
}
