package com.sumatrapdf.library.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sumatrapdf.library.BuildConfig
import com.sumatrapdf.library.data.CloseBehavior
import com.sumatrapdf.library.data.PageLayout
import com.sumatrapdf.library.data.SettingsStore
import com.sumatrapdf.library.data.ZoomFit
import com.sumatrapdf.library.ui.theme.SumThemeMode
import com.sumatrapdf.library.ui.theme.SumTypography

// SettingsScreen is the bottom-nav Settings tab. It mirrors the Win32
// Settings dialog: a few radio groups for the most common choices, a
// switch panel for booleans, and a button that opens the full
// Advanced Settings screen. The Advanced screen is a separate Composable
// (AdvancedSettingsScreen) so it can be pushed onto a back stack.
@Composable
fun SettingsScreen(
    onOpenAdvanced: () -> Unit,
    onClearRecents: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val store = remember { SettingsStore.get(context) }
    val s by store.state.collectAsState()
    var showAbout by remember { mutableStateOf(false) }
    var confirmClearRecents by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Settings", style = SumTypography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { showAbout = true }) {
                    Icon(Icons.Outlined.Info, contentDescription = "About")
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
            Section("Theme") {
                ChoiceRow(
                    label = "System default",
                    selected = s.themeMode == SumThemeMode.System,
                    onClick = { store.setThemeMode(SumThemeMode.System) },
                )
                ChoiceRow(
                    label = "Light",
                    selected = s.themeMode == SumThemeMode.Light,
                    onClick = { store.setThemeMode(SumThemeMode.Light) },
                )
                ChoiceRow(
                    label = "Dark",
                    selected = s.themeMode == SumThemeMode.Dark,
                    onClick = { store.setThemeMode(SumThemeMode.Dark) },
                )
            }
            Section("Page layout") {
                PageLayout.entries.forEach { layout ->
                    ChoiceRow(
                        label = layout.label,
                        selected = s.pageLayout == layout,
                        onClick = { store.setPageLayout(layout) },
                    )
                }
            }
            Section("Default zoom") {
                ZoomFit.entries.forEach { zoom ->
                    ChoiceRow(
                        label = zoom.label,
                        selected = s.defaultZoom == zoom,
                        onClick = { store.setDefaultZoom(zoom) },
                    )
                }
            }
            Section("Reading") {
                SwitchRow(
                    label = "Show links in document",
                    description = "Highlight clickable links on the page.",
                    checked = s.showLinks,
                    onChange = { store.setShowLinks(it) },
                )
                SwitchRow(
                    label = "Keep screen on while reading",
                    description = "Disable the screen sleep timer.",
                    checked = s.keepAwake,
                    onChange = { store.setKeepAwake(it) },
                )
                SwitchRow(
                    label = "Invert colors in night mode",
                    description = "When night mode is on, invert the page colors so the page is dark and the text is light.",
                    checked = s.invertColorsNight,
                    onChange = { store.setInvertColorsNight(it) },
                )
                SwitchRow(
                    label = "Show scrollbar",
                    description = "Vertical scrollbar on continuous pages.",
                    checked = s.scrollbar,
                    onChange = { store.setScrollbar(it) },
                )
            }
            Section("Start-up") {
                SwitchRow(
                    label = "Remember open documents",
                    description = "Reopen the documents that were open when the app last closed.",
                    checked = s.rememberOpenDocs,
                    onChange = { store.setRememberOpenDocs(it) },
                )
                SwitchRow(
                    label = "Show bookshelf on start",
                    description = "Open to the library view. Otherwise, the reader shows the most recent tab.",
                    checked = s.showBookshelfOnStart,
                    onChange = { store.setShowBookshelfOnStart(it) },
                )
                ChoiceRow(
                    label = "On tab close",
                    selected = s.closeBehavior == CloseBehavior.CloseTab,
                    onClick = { store.setCloseBehavior(CloseBehavior.CloseTab) },
                )
                ChoiceRow(
                    label = "On tab close: close document",
                    selected = s.closeBehavior == CloseBehavior.CloseDocument,
                    onClick = { store.setCloseBehavior(CloseBehavior.CloseDocument) },
                )
            }
            Section("Actions") {
                ActionRow(
                    label = "Advanced Settings…",
                    description = "Scrollbars, page spacing, font lists, comic book layout, custom home path.",
                    onClick = onOpenAdvanced,
                )
                ActionRow(
                    label = "Clear recent documents",
                    description = "Empty the list of recently opened files.",
                    onClick = { confirmClearRecents = true },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showAbout) {
        AboutDialog(versionName = BuildConfig.VERSION_NAME ?: "?", onDismiss = { showAbout = false })
    }
    if (confirmClearRecents) {
        AlertDialog(
            onDismissRequest = { confirmClearRecents = false },
            title = { Text("Clear recents?") },
            text = { Text("This empties the list of recently opened documents. Open documents stay open.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearRecents = false
                    onClearRecents()
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearRecents = false }) { Text("Cancel") }
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
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = SumTypography.bodyLarge, modifier = Modifier.weight(1f))
        if (selected) {
            Text(
                text = "✓",
                style = SumTypography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
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
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
}

@Composable
private fun ActionRow(label: String, description: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = SumTypography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            if (description.isNotEmpty()) {
                Text(
                    text = description,
                    style = SumTypography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = "›",
            style = SumTypography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
}

private val PageLayout.label: String get() = when (this) {
    PageLayout.Single -> "Single page"
    PageLayout.Facing -> "Facing pages"
    PageLayout.Book -> "Book view"
}
private val ZoomFit.label: String get() = when (this) {
    ZoomFit.FitPage -> "Fit page"
    ZoomFit.FitWidth -> "Fit width"
    ZoomFit.Actual -> "Actual size"
}

@Composable
private fun AboutDialog(versionName: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("SumatraPDF") },
        text = {
            Column {
                Text("Version $versionName", style = SumTypography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "A faithful Android port of the SumatraPDF document reader. " +
                        "PDF, EPUB, XPS, OXPS, FB2, CBZ, SVG, TIFF, PNG and JPEG render through " +
                        "the MuPDF engine. DjVu, MOBI and AZW3 are recognized but not yet built; " +
                        "they open with an explanatory placeholder so the rest of the app keeps " +
                        "working.",
                    style = SumTypography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Underlying engine: MuPDF (AGPL, from maven.ghostscript.com).",
                    style = SumTypography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
