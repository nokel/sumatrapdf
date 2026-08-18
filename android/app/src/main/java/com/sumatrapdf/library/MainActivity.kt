package com.sumatrapdf.library

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sumatrapdf.library.data.RecentsStore
import com.sumatrapdf.library.data.SettingsStore
import com.sumatrapdf.library.engine.DocumentSpec
import com.sumatrapdf.library.ui.favorites.FavoritesScreen
import com.sumatrapdf.library.ui.library.LibraryScreen
import com.sumatrapdf.library.ui.reader.ReaderScreen
import com.sumatrapdf.library.ui.reader.TabsState
import com.sumatrapdf.library.ui.recents.RecentsScreen
import com.sumatrapdf.library.ui.settings.AdvancedSettingsScreen
import com.sumatrapdf.library.ui.settings.SettingsScreen
import com.sumatrapdf.library.ui.theme.SumatraPDFTheme
import com.sumatrapdf.library.ui.theme.SumTypography

// MainActivity is the new Compose-based launcher. It hosts the
// bottom-nav shell: Library, Recents, Reader, Favorites, Settings. The
// Reader section is the multi-tab document host. Tapping a book in the
// library or a recent file opens a new tab; tabs are shown as a
// horizontal strip and can be closed individually or all at once.
class MainActivity : ComponentActivity() {

    private val tabs by lazy { TabsState(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.artifex.mupdf.fitz.Context.init()
        Reading.attach(this)
        Library.attach(this)
        SettingsStore.get(this)
        RecentsStore.get(this)

        val initialSpec = intent?.let { resolveInitialSpec(it) }

        setContent {
            val settingsStore = remember { SettingsStore.get(this) }
            val mode = settingsStore.state.collectAsState().value.themeMode
            SumatraPDFTheme(mode = mode) {
                Shell(
                    tabs = tabs,
                    settings = settingsStore,
                    initialSpec = initialSpec,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val spec = resolveInitialSpec(intent) ?: return
        tabs.open(spec)
    }

    private fun resolveInitialSpec(intent: Intent): DocumentSpec? {
        val data: Uri? = intent.data
        if (data != null) return DocumentSpec.fromUri(this, data)
        val extra = intent.getStringExtra(ReaderActivity.EXTRA_PATH)
        if (extra != null) return DocumentSpec.fromFile(extra)
        return null
    }
}

private enum class ShellSection(
    val label: String,
    val iconFilled: ImageVector,
    val iconOutlined: ImageVector,
) {
    Library("Library", Icons.Filled.MenuBook, Icons.Outlined.LibraryBooks),
    Recents("Recents", Icons.Filled.MenuBook, Icons.Outlined.History),
    Reader("Reader", Icons.Filled.MenuBook, Icons.Outlined.MenuBook),
    Favorites("Favorites", Icons.Filled.MenuBook, Icons.Outlined.Bookmark),
    Settings("Settings", Icons.Filled.Settings, Icons.Outlined.Settings);
}

@Composable
private fun Shell(
    tabs: TabsState,
    settings: SettingsStore,
    initialSpec: DocumentSpec?,
) {
    val context = LocalContext.current
    val activeSection = remember { mutableStateOf(ShellSection.Library) }
    val showAdvanced = remember { mutableStateOf(false) }
    val settingsState by settings.state.collectAsState()

    LaunchedEffect(initialSpec) {
        if (initialSpec != null) {
            tabs.open(initialSpec)
            activeSection.value = ShellSection.Reader
        } else if (!settingsState.showBookshelfOnStart && tabs.tabs.value.isNotEmpty()) {
            activeSection.value = ShellSection.Reader
        }
    }

    val openLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            val spec = DocumentSpec.fromUri(context, uri) ?: return@rememberLauncherForActivityResult
            tabs.open(spec)
            activeSection.value = ShellSection.Reader
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (activeSection.value) {
                    ShellSection.Library -> LibraryScreen(
                        tabs = tabs,
                        onOpen = { spec ->
                            tabs.open(spec)
                            activeSection.value = ShellSection.Reader
                        },
                    )
                    ShellSection.Recents -> RecentsScreen(tabs = tabs)
                    ShellSection.Reader -> ReaderScreen(
                        tabs = tabs,
                        settings = settings,
                        onOpenFile = { openLauncher.launch(arrayOf("*/*")) },
                        onCloseAll = { tabs.closeAll() },
                        onRequestExit = { activeSection.value = ShellSection.Library },
                    )
                    ShellSection.Favorites -> FavoritesScreen(tabs = tabs)
                    ShellSection.Settings -> SettingsScreen(
                        onOpenAdvanced = { showAdvanced.value = true },
                        onClearRecents = { RecentsStore.get(context).clear() },
                    )
                }
            }
            if (showAdvanced.value) {
                AdvancedSettingsScreen(onBack = { showAdvanced.value = false })
            } else {
                BottomNav(active = activeSection.value, onSelect = { activeSection.value = it })
            }
        }
    }
}

@Composable
private fun BottomNav(active: ShellSection, onSelect: (ShellSection) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                tonalElevation = 0.dp,
            ) {
                ShellSection.entries.forEach { section ->
                    NavigationBarItem(
                        selected = active == section,
                        onClick = { onSelect(section) },
                        icon = {
                            Icon(
                                imageVector = if (active == section) section.iconFilled else section.iconOutlined,
                                contentDescription = section.label,
                            )
                        },
                        label = { Text(section.label, style = SumTypography.labelSmall) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                }
            }
        }
    }
}
