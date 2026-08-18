package com.sumatrapdf.library.ui.reader

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.FormatAlignCenter
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.RotateLeft
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sumatrapdf.library.data.Bookmark
import com.sumatrapdf.library.data.BookmarksStore
import com.sumatrapdf.library.data.Favorite
import com.sumatrapdf.library.data.FavoritesStore
import com.sumatrapdf.library.data.PageLayout
import com.sumatrapdf.library.data.SettingsStore
import com.sumatrapdf.library.data.ZoomFit
import com.sumatrapdf.library.engine.DocumentOutlineEntry
import com.sumatrapdf.library.ui.theme.SumTypography
import kotlinx.coroutines.launch

// ReaderScreen is the multi-tab document host. It shows:
//   1. a horizontal TabRow of every open document, with a close X on each
//   2. a top app bar with the SumatraPDF-style menu (View / Go / Search)
//   3. a left drawer for the table of contents, bookmarks, and favorites
//   4. the page area in the middle (PageSurface)
//   5. a bottom bar with page nav, zoom, layout, and night mode
//
// It is Compose-only; the existing ReaderActivity (a Views-based single-
// document reader) is kept for the legacy intent filter path but the
// Compose shell is the new default.
@Composable
fun ReaderScreen(
    tabs: TabsState,
    settings: SettingsStore,
    onOpenFile: () -> Unit,
    onCloseAll: () -> Unit,
    onRequestExit: () -> Unit,
) {
    val tabsList by tabs.tabs.collectAsState()
    val activeId by tabs.active.collectAsState()
    val settingsState by settings.state.collectAsState()
    val active = tabsList.firstOrNull { it.id == activeId }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    var showSearch by remember { mutableStateOf(false) }
    var showGoToPage by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    var showFailed by remember { mutableStateOf<String?>(null) }

    val activeTab = active
    val night = settingsState.themeMode == com.sumatrapdf.library.ui.theme.SumThemeMode.Dark ||
        activeTab?.id?.let { NightPreference.isNight(it) } == true

    if (activeTab != null) {
        LaunchedEffect(activeTab.id) {
            NightPreference.setActive(activeTab.id, night)
        }
    }

    LaunchedEffect(activeTab?.id) {
        if (activeTab?.failedMessage != null) {
            showFailed = activeTab.failedMessage
        }
    }
    LaunchedEffect(activeTab?.id) {
        if (activeTab?.passwordAsked == true) {
            showPassword = true
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SidePanel(
                tab = activeTab,
                tabs = tabs,
                favoritesStore = FavoritesStore.get(rememberAndroidContext()),
                bookmarksStore = BookmarksStore.get(rememberAndroidContext()),
                onClose = { scope.launch { drawerState.close() } },
            )
        },
    ) {
        Scaffold(
            topBar = {
                ReaderTopBar(
                    tab = activeTab,
                    showSearch = showSearch,
                    onToggleSearch = { showSearch = !showSearch },
                    onGoToPage = { showGoToPage = true },
                    onOpenSide = { scope.launch { drawerState.open() } },
                    onOpenFile = onOpenFile,
                    onCloseAll = onCloseAll,
                    onCycleLayout = { tabs.toggleLayout(activeTab) },
                    onCycleZoom = { tabs.toggleZoom(activeTab) },
                    onToggleNight = {
                        NightPreference.toggle(activeTab)
                    },
                    onRotateLeft = { activeTab?.let { tabs.rotate(it.id, -90) } },
                    onRotateRight = { activeTab?.let { tabs.rotate(it.id, 90) } },
                    onExit = onRequestExit,
                )
            },
            bottomBar = {
                active?.let { tab ->
                    ReaderBottomBar(
                        tab = tab,
                        onPrev = { tabs.previous(tab.id) },
                        onNext = { tabs.next(tab.id) },
                        onSeek = { tabs.goTo(tab.id, it) },
                        nightActive = night,
                    )
                }
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background),
            ) {
                if (tabsList.isEmpty()) {
                    EmptyReaderState(onOpenFile = onOpenFile)
                } else if (active != null) {
                    TabsStrip(
                        tabs = tabsList,
                        activeId = activeId,
                        onSelect = { tabs.focus(it) },
                        onClose = { tabs.close(it) },
                    )
                    if (showSearch) {
                        SearchBar(
                            needle = active.needle,
                            onChange = { tabs.search(active.id, it) },
                            onNext = { tabs.nextSearchHit(active.id) },
                            onPrev = { tabs.previousSearchHit(active.id) },
                            onClose = {
                                showSearch = false
                                tabs.search(active.id, "")
                            },
                        )
                    }
                    if (showGoToPage) {
                        GoToPageDialog(
                            page = active.currentPage + 1,
                            pageCount = maxOf(1, active.pageCount),
                            onDismiss = { showGoToPage = false },
                            onSubmit = { p ->
                                showGoToPage = false
                                tabs.goTo(active.id, p - 1)
                            },
                        )
                    }
                    if (showPassword) {
                        PasswordDialog(
                            onDismiss = { showPassword = false },
                            onSubmit = { secret ->
                                showPassword = false
                                tabs.password(active.id, secret)
                            },
                        )
                    }
                    showFailed?.let { msg ->
                        FailedDialog(message = msg, onDismiss = { showFailed = null })
                    }
                    PageSurface(
                        engine = active.engine,
                        pageIndex = active.currentPage,
                        pageCount = active.pageCount,
                        layout = settingsState.pageLayout,
                        zoom = settingsState.defaultZoom,
                        night = night,
                        onTap = { kind ->
                            when (kind) {
                                TapKind.Single -> { /* future: toggle bars */ }
                                TapKind.Double -> tabs.zoomCycle(active.id)
                                TapKind.Long -> { /* future: word lookup */ }
                            }
                        },
                        onPageChange = { tabs.goTo(active.id, it) },
                        onSwipeOut = { /* ignored in tabbed host */ },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyReaderState(onOpenFile: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.MenuBook,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text("No open documents", style = SumTypography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Open a PDF, EPUB or XPS file to start reading.",
            style = SumTypography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        AssistChip(
            onClick = onOpenFile,
            label = { Text("Open a file…") },
            leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
        )
    }
}

@Composable
private fun TabsStrip(
    tabs: List<OpenTab>,
    activeId: String?,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
) {
    if (tabs.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth()) {
        TabRow(
            selectedTabIndex = tabs.indexOfFirst { it.id == activeId }.coerceAtLeast(0),
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            divider = {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline,
                    thickness = 0.5.dp,
                )
            },
        ) {
            tabs.forEach { tab ->
                val selected = tab.id == activeId
                Tab(
                    selected = selected,
                    onClick = { onSelect(tab.id) },
                    modifier = Modifier.height(40.dp),
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = tab.spec.name.substringBeforeLast('.', tab.spec.name),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = SumTypography.bodySmall,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            Spacer(Modifier.width(4.dp))
                            IconButton(
                                onClick = { onClose(tab.id) },
                                modifier = Modifier.size(20.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Close tab",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ReaderTopBar(
    tab: OpenTab?,
    showSearch: Boolean,
    onToggleSearch: () -> Unit,
    onGoToPage: () -> Unit,
    onOpenSide: () -> Unit,
    onOpenFile: () -> Unit,
    onCloseAll: () -> Unit,
    onCycleLayout: () -> Unit,
    onCycleZoom: () -> Unit,
    onToggleNight: () -> Unit,
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    onExit: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 0.dp,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onOpenSide) {
                    Icon(
                        imageVector = Icons.Filled.MenuBook,
                        contentDescription = "Contents, bookmarks, favorites",
                    )
                }
                Spacer(Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tab?.spec?.name ?: "SumatraPDF",
                        style = SumTypography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (tab != null && tab.pageCount > 0) {
                        Text(
                            text = "Page ${tab.currentPage + 1} of ${tab.pageCount}",
                            style = SumTypography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = onToggleSearch) {
                    Icon(Icons.Filled.Search, contentDescription = "Find")
                }
                IconButton(onClick = onGoToPage) {
                    Icon(Icons.Outlined.FitScreen, contentDescription = "Go to page")
                }
                IconButton(onClick = onCycleLayout) {
                    Icon(Icons.Outlined.FormatAlignCenter, contentDescription = "Layout")
                }
                IconButton(onClick = onCycleZoom) {
                    Icon(Icons.Outlined.ZoomIn, contentDescription = "Zoom")
                }
                IconButton(onClick = onToggleNight) {
                    Icon(Icons.Outlined.NightsStay, contentDescription = "Night mode")
                }
                IconButton(onClick = onRotateLeft) {
                    Icon(Icons.Outlined.RotateLeft, contentDescription = "Rotate left")
                }
                IconButton(onClick = onRotateRight) {
                    Icon(Icons.Outlined.RotateRight, contentDescription = "Rotate right")
                }
                IconButton(onClick = onOpenFile) {
                    Icon(Icons.Filled.Add, contentDescription = "Open file")
                }
                IconButton(onClick = onCloseAll) {
                    Icon(Icons.Filled.Close, contentDescription = "Close all tabs")
                }
            }
        }
    }
}

@Composable
private fun ReaderBottomBar(
    tab: OpenTab,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Int) -> Unit,
    nightActive: Boolean,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${tab.currentPage + 1}",
                style = SumTypography.labelLarge,
                modifier = Modifier.width(40.dp),
            )
            Slider(
                value = tab.currentPage.toFloat(),
                onValueChange = { onSeek(it.toInt()) },
                valueRange = 0f..(maxOf(0, tab.pageCount - 1).toFloat()),
                steps = if (tab.pageCount > 2) tab.pageCount - 2 else 0,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${maxOf(1, tab.pageCount)}",
                style = SumTypography.labelLarge,
                modifier = Modifier.width(40.dp),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onPrev) {
                Icon(Icons.Outlined.RotateLeft, contentDescription = "Previous page")
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Outlined.RotateRight, contentDescription = "Next page")
            }
        }
    }
}

@Composable
private fun SearchBar(
    needle: String,
    onChange: (String) -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 0.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = needle,
                onValueChange = onChange,
                singleLine = true,
                placeholder = { Text("Find in document") },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onPrev) { Text("Prev") }
            TextButton(onClick = onNext) { Text("Next") }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close search") }
        }
    }
}

@Composable
private fun GoToPageDialog(
    page: Int,
    pageCount: Int,
    onDismiss: () -> Unit,
    onSubmit: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(page.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to page") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { txt -> text = txt.filter { it.isDigit() }.take(6) },
                singleLine = true,
                label = { Text("Page (1-$pageCount)") },
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val p = text.toIntOrNull()?.coerceIn(1, pageCount) ?: return@TextButton
                onSubmit(p)
            }) { Text("Go") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PasswordDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("This document is password protected") },
        text = {
            OutlinedTextField(
                value = pw,
                onValueChange = { pw = it },
                singleLine = true,
                label = { Text("Password") },
            )
        },
        confirmButton = { TextButton(onClick = { onSubmit(pw) }) { Text("Unlock") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FailedDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Could not open document") },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

@Composable
private fun SidePanel(
    tab: OpenTab?,
    tabs: TabsState,
    favoritesStore: FavoritesStore,
    bookmarksStore: BookmarksStore,
    onClose: () -> Unit,
) {
    var section by remember { mutableStateOf(SideSection.Contents) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(8.dp),
    ) {
        Text(
            text = "Document panel",
            style = SumTypography.titleMedium,
            modifier = Modifier.padding(8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SideSection.entries.forEach { s ->
                AssistChip(
                    onClick = { section = s },
                    label = { Text(s.label) },
                    colors = if (section == s) AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ) else AssistChipDefaults.assistChipColors(),
                )
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        when (section) {
            SideSection.Contents -> ContentsList(tab, tabs, onClose)
            SideSection.Bookmarks -> BookmarksList(tab, bookmarksStore, onClose)
            SideSection.Favorites -> FavoritesList(favoritesStore, onClose)
        }
    }
}

private enum class SideSection(val label: String) {
    Contents("Contents"),
    Bookmarks("Bookmarks"),
    Favorites("Favorites"),
}

@Composable
private fun ContentsList(
    tab: OpenTab?,
    tabs: TabsState,
    onClose: () -> Unit,
) {
    if (tab == null) {
        Text("Open a document to see its contents.",
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    if (tab.outline.isEmpty()) {
        Text("This document has no table of contents.",
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(tab.outline, key = { it.title + "_" + it.page + "_" + it.depth }) { entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (entry.page >= 0) tabs.goTo(tab.id, entry.page)
                        onClose()
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = entry.title,
                    style = SumTypography.bodyMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = (8 * entry.depth).dp),
                )
                if (entry.page >= 0) {
                    Text(
                        text = (entry.page + 1).toString(),
                        style = SumTypography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
        }
    }
}

@Composable
private fun BookmarksList(
    tab: OpenTab?,
    bookmarksStore: BookmarksStore,
    onClose: () -> Unit,
) {
    if (tab == null) {
        Text("Open a document to manage bookmarks.",
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val list = remember(tab.spec.origin) { bookmarksStore.list(tab.spec.origin) }
    if (list.isEmpty()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("No bookmarks in this document yet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(
                "Use the star button on the bottom bar to add a bookmark on the current page.",
                style = SumTypography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(list, key = { it.page.toString() + "_" + it.label }) { bm ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onClose() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text("${bm.page + 1}. ${bm.label}", style = SumTypography.bodyMedium)
                }
                IconButton(onClick = { bookmarksStore.remove(tab.spec.origin, bm.page) }) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove bookmark")
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
        }
    }
}

@Composable
private fun FavoritesList(
    favoritesStore: FavoritesStore,
    onClose: () -> Unit,
) {
    val list = remember { favoritesStore.list() }
    if (list.isEmpty()) {
        Text("No favorites yet. Pin a page from the reader to add one.",
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(list, key = { it.id }) { fav ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onClose() }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(fav.label, style = SumTypography.bodyMedium)
                    Text(
                        fav.name,
                        style = SumTypography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
        }
    }
}

@Composable
private fun rememberAndroidContext(): android.content.Context {
    return androidx.compose.ui.platform.LocalContext.current
}

// Helpers that hang off TabsState without inflating it.
// (moved into TabsState class itself)

// Night-mode toggle lives in a static map so it survives composition.
// Each tab keeps its own on/off state, like a per-document preference.
object NightPreference {
    private val map = HashMap<String, Boolean>()
    fun isNight(id: String): Boolean = map[id] ?: false
    fun setActive(id: String, value: Boolean) { map[id] = value }
    fun toggle(tab: OpenTab?) {
        if (tab == null) return
        val cur = isNight(tab.id)
        setActive(tab.id, !cur)
    }
}
