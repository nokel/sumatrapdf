package com.sumatrapdf.reader

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.sumatrapdf.reader.library.LibraryModel
import com.sumatrapdf.reader.library.LibraryPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// The main frame. Layout, top to bottom:
//
//   TabsRow        (tab cards + [+] for "new tab")
//   MenuBar        (File | View | Go To | Zoom | Favorites | Settings | Help)
//   SumatraToolbar (Win32 toolbar order: Open, Print, [page-info], Prev, Next, ...)
//   ErrorBanner    (only when engine.lastError is set)
//   [Find bar]     (only when showFind is true)
//   ToCSidebar | PageSurface | (nothing)   <-- the body row
//   SnackbarHost   (transient feedback for "not implemented" actions)
//
// The state model:
//   * `tabs: SnapshotStateList<Tab>` — every open document, in order
//   * `activeIndex: Int` — which tab drives the toolbar / page surface
//   * Each `Tab` carries its own (page, displayMode, continuous, zoom,
//     customZoom, rotation). When the user switches tabs, we save the
//     current values into the old tab and pull the new ones from the
//     new tab. Session restore is also per-tab, not global.
private const val kSaveSettleMs = 400L

@Composable
fun ReaderScreen(
    engine: DocumentEngine,
    settings: Settings,
    readAloud: ReadAloud,
    onOpenFile: () -> Unit,
    pendingOpen: OpenRequest?,
    onOpenHandled: () -> Unit,
    onSaveCopy: (String) -> Unit,
    nightMode: Boolean,
    onNightModeChange: (Boolean) -> Unit,
    immersive: Boolean,
    onImmersiveChange: (Boolean) -> Unit,
    onCloseFile: () -> Unit = {},
    pendingShortcut: MenuAction? = null,
    onShortcutHandled: () -> Unit = {},
    onSearchFieldFocusChange: (Boolean) -> Unit = {},
    anyTextFieldFocused: androidx.compose.runtime.MutableState<Boolean>,
    backHeld: Int = 0,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val tabs = remember { mutableStateListOf<Tab>() }
    var activeIndex by remember { mutableIntStateOf(0) }
    // The active tab's state is mirrored into local vars for ergonomic
    // access in callbacks; the actual values of record live on the Tab
    // object so switching tabs preserves them.
    val active = tabs.getOrNull(activeIndex)
    var page by remember(active?.docHandle) { mutableIntStateOf(active?.page ?: 0) }
    var displayMode by remember(active?.docHandle) { mutableStateOf(active?.displayMode ?: DisplayMode.SinglePage) }
    var continuous by remember(active?.docHandle) { mutableStateOf(active?.continuous ?: false) }
    var zoom by remember(active?.docHandle) { mutableStateOf(active?.zoom ?: ZoomLevel.FitWidth) }
    var customZoom by remember(active?.docHandle) { mutableFloatStateOf(active?.customZoom ?: 1f) }
    var effectiveScale by remember(active?.docHandle) { mutableFloatStateOf(1f) }
    fun stepZoom(towards: Float) {
        val currentPercent =
            if (zoom == ZoomLevel.Custom) customZoom * 100f else effectiveScale * 100f
        zoom = ZoomLevel.Custom
        customZoom = nextZoomStep(currentPercent, towards) / 100f
    }
    var rotation by remember(active?.docHandle) { mutableIntStateOf(active?.rotation ?: 0) }
    var mangaMode by remember { mutableStateOf(false) }
    var showGoToPage by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showRecent by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    // FileState::showToc is per-document, so the sidebar's visibility
    // belongs to the tab, not to the screen.
    var showToCSidebar by remember(active?.docHandle) { mutableStateOf(active?.showToc ?: false) }
    var showCustomZoom by remember { mutableStateOf(false) }
    var showProperties by remember { mutableStateOf(false) }
    var properties by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var showVoicePicker by remember { mutableStateOf(false) }
    var voices by remember { mutableStateOf<List<String>>(emptyList()) }
    // Deliberately NOT keyed on the doc handle. A `remember(handle)` here
    // is reset by the recomposition that follows opening a document, which
    // happens after the coroutine that loads the outline has already
    // written to the previous state object — so the sidebar stayed empty.
    var outline by remember { mutableStateOf<List<OutlineNode>?>(null) }
    var bookmarks by remember { mutableStateOf(settings.getBookmarks()) }
    // Bookmarks sidebar sort: false = by page (default, matches
    // Win32), true = by name (alphabetical). Held in the screen
    // (not Settings) so the toggle is per-session, like the ToC
    // expand state.
    var bookmarksSortByName by remember { mutableStateOf(false) }

    // ToC expand/collapse state. The set is the set of expanded
    // node ids. The state is held in the screen (not Settings) so
    // it lives for the session and resets when the user opens a
    // different document. Mutating it from the toolbar's
    // ExpandAll / CollapseAll / ExpandToCurrentPage actions
    // pushes the change to ToCSidebar via the new
    // tocExpandTrigger / tocCollapseTrigger state, which
    // ToCSidebar watches.
    var tocExpandAllTrigger by remember { mutableIntStateOf(0) }
    var tocCollapseAllTrigger by remember { mutableIntStateOf(0) }
    var tocExpandToCurrentTrigger by remember { mutableIntStateOf(0) }

    // Start page state. `historyVersion` is bumped after every mutation
    // of the file history so the ordered list is recomputed; the history
    // itself is a plain in-memory list, not Compose state.
    val history = remember { FileHistory(context) }
    var historyVersion by remember { mutableIntStateOf(0) }
    var homeSortFrequent by remember { mutableStateOf(settings.homePageSortByFrequentlyRead) }
    var homeListView by remember { mutableStateOf(settings.homePageListView) }
    var homeFilter by remember { mutableStateOf("") }
    val homeEntries = remember(historyVersion, homeSortFrequent) {
        history.ordered(homeSortFrequent)
    }

    var renderEpoch by remember { mutableIntStateOf(0) }
    var invertPages by remember { mutableStateOf(settings.invertPageColors) }
    var presentation by remember { mutableStateOf(false) }

    // Password prompt state, raised when the engine reports that the
    // document it just tried to open is encrypted.
    var passwordFor by remember { mutableStateOf<OpenRequest?>(null) }
    var passwordRetry by remember { mutableStateOf(false) }
    var sessionRestored by remember { mutableStateOf(false) }

    // Back / forward page history, the Win32 CmdNavigateBack pair.
    val backStack = remember { mutableStateListOf<Int>() }
    val forwardStack = remember { mutableStateListOf<Int>() }

    var selection by remember(active?.docHandle) { mutableStateOf<Selection?>(null) }
    var linksByPage by remember(active?.docHandle) { mutableStateOf<Map<Int, List<PageLink>>>(emptyMap()) }
    // Win32's Debug ▸ Show Links is a toggle: when ON, every
    // page-link rectangle is drawn in addition to the cursor-hover
    // highlight. When OFF, links are invisible until the user taps
    // one. Default OFF — toggled by `MenuAction.ShowLinks`.
    // (PORTING-STATUS §3.1.)
    var showLinks by remember { mutableStateOf(false) }

    // Find state — per-tab so switching tabs doesn't lose your hits.
    var showFind by remember { mutableStateOf(false) }
    var findQuery by remember(active?.docHandle) { mutableStateOf(TextFieldValue("")) }
    var findHitsByPage by remember(active?.docHandle) { mutableStateOf<Map<Int, List<Quad>>>(emptyMap()) }
    var findCurrentHit by remember(active?.docHandle) { mutableIntStateOf(0) }
    var findSearching by remember { mutableStateOf(false) }
    // Which page the search loop is currently on. The
    // SearchBar reads it as `findProgressPage / pageCount` to
    // draw a LinearProgressIndicator. Updated inside runSearch
    // and reset to 0 when the search ends.
    var findProgressPage by remember { mutableIntStateOf(0) }
    // The current search coroutine. Replaced on every runSearch
    // so a new search cancels an in-flight one (the previous
    // job's `isActive` flips false mid-loop, so the page loop
    // exits early). The page loop also checks `isActive` per
    // page so a 1000-page PDF's search yields in a few
    // keystrokes.
    var findJob by remember { mutableStateOf<Job?>(null) }
    // The flags the user picked in the find toolbar. mupdf 1.28.0
    // does not expose them through the Java binding (see
    // DocumentEngine.searchPage), but the data class is here so
    // the toolbar can persist the user's choice and we can wire
    // it the day the binding adds the 3-arg form.
    var findFlags by remember { mutableStateOf(SearchFlags.DEFAULT) }

    val pageCount = engine.pageCount
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // The home tab is either the poster-wall library or the classic
    // frequently-read page, the same choice LibraryHomeEnabled() makes on
    // Windows. A chapter tapped in the library opens the book and then
    // jumps, which needs the page held until the document is active.
    var libraryHome by remember { mutableStateOf(settings.libraryHome) }
    val libraryModel = remember { LibraryModel(context, scope) }
    var libraryJumpPage by remember { mutableIntStateOf(-1) }
    var libraryRailOpen by remember { mutableStateOf(false) }
    var libraryColumns by remember { mutableIntStateOf(settings.libraryColumns) }
    var libraryTitleScale by remember { mutableFloatStateOf(settings.libraryTitleScale) }
    var coverEditFor by remember { mutableStateOf<com.sumatrapdf.reader.library.Book?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    // Armed by a three-second hold on the back button (or by a back
    // press with nowhere left to go). The next back press leaves.
    var exitArmed by remember { mutableStateOf(false) }

    val findTotal = findHitsByPage.values.sumOf { it.size }
    val findCurrentPageHits: List<Quad> by remember(findHitsByPage, page) {
        derivedStateOf { findHitsByPage[page] ?: emptyList() }
    }

    // Engine errors (e.g. openUri failed) shown as a persistent banner
    // at the top of the page, not a snackbar.
    var errorBanner by remember { mutableStateOf<String?>(null) }

    // Long-press context-menu state. The anchor is the screen-space
    // Offset where the long-press happened; the id is which port
    // of the Win32 menu to show. null = no menu. PageSurface's
    // onLongPress sets the values; tapping outside, on a
    // separator, or on the dismiss affordance clears them.
    var contextMenuAnchor by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    var contextMenuId by remember { mutableStateOf<ContextMenuId?>(null) }

    fun say(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    // Persist the current state into the tab whenever any of the
    // per-tab state vars change. This means: switch tabs → values
    // saved into old tab; switch back → values restored.
    LaunchedEffect(
        active?.docHandle, page, displayMode, continuous, zoom, customZoom, rotation, showToCSidebar,
    ) {
        val i = activeIndex
        if (i in tabs.indices) {
            tabs[i] = tabs[i].copy(
                page = page,
                displayMode = displayMode,
                continuous = continuous,
                zoom = zoom,
                customZoom = customZoom,
                rotation = rotation,
                showToc = showToCSidebar,
            )
        }
        val cur = tabs.getOrNull(i) ?: return@LaunchedEffect
        if (cur.isHome || cur.path.isEmpty()) return@LaunchedEffect
        // A pinch changes customZoom on every pointer frame. Without the
        // settle delay this wrote the history file — and took the mupdf
        // lock for bookmarkFor — sixty times a second, on the main
        // thread. The effect restarts on each change, so the delay
        // collapses a whole gesture into one save.
        delay(kSaveSettleMs)
        val mark = withContext(Dispatchers.IO) { engine.bookmarkFor(page) }
        withContext(Dispatchers.IO) {
            history.saveState(
                path = cur.path,
                pageNo = page,
                zoom = zoomToString(zoom, customZoom),
                rotation = rotation,
                displayMode = displayModeToString(displayMode, continuous),
                showToc = showToCSidebar,
                reparseIdx = mark,
            )
        }
    }

    LaunchedEffect(engine.lastError) {
        val msg = engine.lastError
        if (msg != null) {
            errorBanner = msg
            engine.clearError()
        }
    }

    // EPUB/FB2/MOBI have no fixed pages — mupdf paginates them to a box we
    // choose, so without this they use mupdf's default and ignore the screen.
    // Must run before the outline loads: repagination changes what page each
    // chapter starts on, and bumping layoutEpoch re-triggers that load.
    LaunchedEffect(active?.docHandle, configuration.screenWidthDp, configuration.screenHeightDp) {
        if (!engine.isOpen || !engine.isReflowable) return@LaunchedEffect
        val widthPt = configuration.screenWidthDp * 0.45f
        val heightPt = configuration.screenHeightDp * 0.45f
        // The page number the tab carries is meaningless after
        // repagination; the saved bookmark is what survives it. This is
        // also what keeps the reading position when the Fold4 is opened
        // or closed, which relays out at a different width.
        val here = tabs.getOrNull(activeIndex)
        val mark = if (here != null && !here.isHome) {
            history.findByPath(here.path)?.reparseIdx ?: 0L
        } else {
            0L
        }
        val landed = withContext(Dispatchers.IO) { engine.relayout(widthPt, heightPt, 11f, mark) }
        if (landed >= 0) page = landed.coerceIn(0, maxOf(0, engine.pageCount - 1))
    }

    LaunchedEffect(active?.docHandle, engine.layoutEpoch) {
        outline = if (engine.isOpen) {
            withContext(Dispatchers.IO) { engine.getOutline() }
        } else {
            null
        }
    }

    // Links for the pages currently on screen. Kept off the main
    // thread: getLinks() loads the page, which for a big PDF is not
    // free, and it runs again on every page turn.
    LaunchedEffect(active?.docHandle, page, pageCount, displayMode, continuous) {
        if (!engine.isOpen || pageCount <= 0) {
            linksByPage = emptyMap()
            return@LaunchedEffect
        }
        val wanted = when {
            continuous -> (page - 1..page + 1)
            displayMode == DisplayMode.SinglePage -> (page..page)
            else -> (page - 1..page)
        }.filter { it in 0 until pageCount }
        linksByPage = withContext(Dispatchers.IO) {
            wanted.associateWith { engine.links(it) }.filterValues { it.isNotEmpty() }
        }
    }

    DisposableEffect(Unit) {
        onDispose { engine.closeAll() }
    }

    // src/FileHistory.cpp::RemoveNonExistentFilesAsync — documents that
    // have been deleted or moved since we last saw them get hidden from
    // the start page rather than dropped.
    LaunchedEffect(Unit) {
        val changed = withContext(Dispatchers.IO) { history.refreshExistence() }
        if (changed) historyVersion++
    }


    fun goToPage(target: Int, recordHistory: Boolean = true) {
        val clamped = target.coerceIn(0, maxOf(0, pageCount - 1))
        if (clamped == page) return
        if (recordHistory) {
            backStack.add(page)
            forwardStack.clear()
        }
        selection = null
        page = clamped
    }

    fun gotoPrev() { if (page > 0) { selection = null; page-- } }
    fun gotoNext() { if (page < pageCount - 1) { selection = null; page++ } }
    fun gotoFirst() { goToPage(0) }
    fun gotoLast() { goToPage(maxOf(0, pageCount - 1)) }

    // Manga (R2L) navigation: src/DisplayModel.cpp::GoToPageHorizontal
    // flips the user's "toRight" into the page direction. In manga mode
    // the visual right edge is the lower page index, so the same edge
    // tap that called gotoNext() in LTR calls gotoPrev() here.
    fun mangaAwarePrev() { if (mangaMode) gotoNext() else gotoPrev() }
    fun mangaAwareNext() { if (mangaMode) gotoPrev() else gotoNext() }

    fun navigateBack() {
        val target = backStack.removeLastOrNull()
        if (target == null) {
            say("Nothing to go back to")
            return
        }
        forwardStack.add(page)
        selection = null
        page = target.coerceIn(0, maxOf(0, pageCount - 1))
    }

    fun navigateForward() {
        val target = forwardStack.removeLastOrNull()
        if (target == null) {
            say("Nothing to go forward to")
            return
        }
        backStack.add(page)
        selection = null
        page = target.coerceIn(0, maxOf(0, pageCount - 1))
    }

    // Move the active find hit by `step` (1 = next, -1 = previous)
    // and jump the page to whichever page the new hit is on. The
    // find state lives in `findHitsByPage` and `findCurrentHit`; the
    // search itself is run from a coroutine in the LaunchedEffect
    // that watches `findQuery` (see below).
    fun moveFindHit(step: Int) {
        if (findTotal == 0) {
            say("No matches")
            return
        }
        val next = ((findCurrentHit - 1 + step) % findTotal + findTotal) % findTotal
        findCurrentHit = next + 1
        // Walk `findHitsByPage` in key order to find the page that
        // contains the (next+1)-th hit. Key order matches the page
        // order so the user always scrolls forward / back through
        // the document, never jumps randomly.
        var remaining = next
        for ((pageNo, hits) in findHitsByPage) {
            if (remaining < hits.size) {
                goToPage(pageNo, recordHistory = true)
                return
            }
            remaining -= hits.size
        }
    }

    // Move the active tab to a new position in the tab strip, in the
    // bounds 0..tabs.size-1. Win32's CmdMoveTabLeft/Right does this
    // over the actual tab order; the Android port mirrors the
    // same `move(from, to)` semantics.
    fun moveTab(from: Int, delta: Int) {
        val to = from + delta
        if (from !in tabs.indices) return
        if (to !in tabs.indices) return
        if (from == to) return
        val moved = tabs.removeAt(from)
        tabs.add(to, moved)
        activeIndex = to
    }

    fun applyFitWidthContinuous() {
        displayMode = DisplayMode.SinglePage
        continuous = true
        zoom = ZoomLevel.FitWidth
    }

    fun applyFitSinglePage() {
        displayMode = DisplayMode.SinglePage
        continuous = false
        zoom = ZoomLevel.FitPage
    }

    fun runSearch() {
        val q = findQuery.text.trim()
        if (q.isEmpty() || !engine.isOpen) {
            findHitsByPage = emptyMap()
            findCurrentHit = 0
            findProgressPage = 0
            return
        }
        findSearching = true
        findProgressPage = 0
        // Cancel any in-flight search before launching a new one.
        // The previous launch's job is replaced; the new launch
        // owns the search until it completes. This is the toggle
        // for the search synchronous-over-every-page defect (§3.8):
        // a new keystroke mid-search now cancels the in-flight
        // loop rather than racing it.
        val prevJob = findJob
        findJob = scope.launch {
            prevJob?.cancelAndJoin()
            android.util.Log.d("SumatraSearch", "runSearch START q='$q' pages=$pageCount")
            val result = withContext(Dispatchers.IO) {
                val map = mutableMapOf<Int, List<Quad>>()
                val count = engine.pageCount
                for (p in 0 until count) {
                    if (!isActive) return@withContext map
                    val hits = engine.searchPage(p, q, SearchFlags.FLAG_IGNORE_CASE)
                    if (hits.isNotEmpty()) map[p] = hits
                    // Update progress on the main thread so the
                    // SearchBar's LinearProgressIndicator re-draws.
                    withContext(Dispatchers.Main) {
                        findProgressPage = p + 1
                    }
                }
                map
            }
            findHitsByPage = result
            findCurrentHit = 0
            findSearching = false
            findProgressPage = 0
            val firstPage = result.keys.minOrNull()
            android.util.Log.d("SumatraSearch", "runSearch END result.keys=${result.keys} totalHits=${result.values.sumOf { it.size }} firstPage=$firstPage page=$page")
            if (firstPage != null && firstPage != page) goToPage(firstPage)
            if (result.isEmpty()) {
                say("No matches for \"$q\"")
            }
        }
    }

    fun gotoHit(direction: Int) {
        val flat = findHitsByPage.entries.sortedBy { it.key }
            .flatMap { (p, list) -> list.map { p to it } }
        if (flat.isEmpty()) return
        val newIdx = ((findCurrentHit + direction) % flat.size + flat.size) % flat.size
        findCurrentHit = newIdx
        val (targetPage, _) = flat[newIdx]
        if (targetPage != page) goToPage(targetPage)
    }

    // Jump to the next/previous bookmark on the active document.
    // Win32's CmdGoToNextFavorite/PrevFavorite — works on the
    // per-document bookmark list (the same list the ToC
    // sidebar's Bookmarks tab shows). Bookmarks on the current
    // page count as the "current" position so the user can step
    // through them with repeated Next presses; if the current
    // page is on a bookmark we step from that one, otherwise we
    // step from the nearest bookmark in the requested
    // direction.
    fun gotoFavorite(direction: Int) {
        val path = tabs.getOrNull(activeIndex)?.path ?: return
        val list = bookmarks.filter { it.path == path }.sortedBy { it.page }
        if (list.isEmpty()) {
            say("No bookmarks in this document")
            return
        }
        // Find the next bookmark page that is strictly after
        // the current page (for +1) or strictly before (for
        // -1). If there isn't one, wrap to the opposite end
        // of the list — the same wrap behaviour as the find
        // toolbar's "next hit".
        val target = if (direction > 0) {
            list.firstOrNull { it.page > page } ?: list.first()
        } else {
            list.lastOrNull { it.page < page } ?: list.last()
        }
        if (target.page != page) goToPage(target.page, recordHistory = true)
    }

    fun openInNewTab(handle: Int) {
        if (handle <= 0) return
        val path = engine.pathFor(handle) ?: return
        // Check if this path is already a tab — just switch to it. The
        // handle we were given is then a second Document for the same
        // file (the launch intent racing the session restore, say), and
        // has to be closed or it leaks until the app exits.
        val existing = tabs.indexOfFirst { !it.isHome && it.path == path }
        if (existing >= 0) {
            val keep = tabs[existing].docHandle
            if (handle != keep) engine.closeHandle(handle)
            activeIndex = existing
            engine.setActive(keep)
            return
        }
        // src/Tabs.cpp inserts the Home tab at index 0 alongside the
        // first document tab, not before there is anything to switch
        // back from.
        if (tabs.isEmpty()) {
            tabs.add(Tab(title = "Home", path = "", docHandle = 0, isHome = true))
        }
        // Restore the document's own state. `useDefaultState` is Win32's
        // flag for "never closed with a position worth keeping"; those
        // documents open on the app defaults instead.
        val saved = history.findByPath(path)?.takeIf { !it.useDefaultState }
        val savedLayout = displayModeFromString(saved?.displayMode)
        val savedZoom = zoomFromString(saved?.zoom)
        val newTab = Tab(
            title = engine.titleFor(handle),
            path = path,
            docHandle = handle,
            page = (saved?.pageNo ?: 0).coerceAtLeast(0),
            displayMode = savedLayout?.first ?: DisplayMode.SinglePage,
            continuous = savedLayout?.second ?: false,
            zoom = savedZoom?.first ?: ZoomLevel.FitWidth,
            customZoom = savedZoom?.second ?: 1f,
            rotation = saved?.rotation ?: 0,
            showToc = saved?.showToc ?: false,
        )
        tabs.add(newTab)
        activeIndex = tabs.lastIndex
        history.markFileLoaded(path, engine.uriFor(handle), engine.titleFor(handle))
        historyVersion++
        bookmarks = settings.getBookmarks()
        renderEpoch++
        backStack.clear()
        forwardStack.clear()
        findHitsByPage = emptyMap()
        findQuery = TextFieldValue("")
        findCurrentHit = 0
    }

    fun activate(handle: Int) {
        if (handle <= 0) return
        engine.setActive(handle)
        openInNewTab(handle)
    }

    // A document we have opened before may have its password remembered
    // (FileState::decryptionKey). Try that once before asking again, and
    // drop it if it no longer works — the file may have been re-encrypted.
    fun finishOpen(
        first: DocumentEngine.Opened,
        reopen: (String) -> DocumentEngine.Opened,
        prompt: OpenRequest,
    ) {
        var outcome = first
        if (outcome is DocumentEngine.Opened.NeedsPassword) {
            val remembered = history.passwordFor(outcome.path)
            if (remembered != null) {
                val retried = reopen(remembered)
                if (retried is DocumentEngine.Opened.Ok) {
                    outcome = retried
                } else {
                    history.forgetPassword(outcome.path)
                }
            }
        }
        when (val settled = outcome) {
            is DocumentEngine.Opened.Ok -> activate(settled.handle)
            is DocumentEngine.Opened.NeedsPassword -> {
                passwordRetry = false
                passwordFor = prompt
            }
            is DocumentEngine.Opened.Failed -> errorBanner = settled.message
        }
    }

    fun openPath(path: String) {
        val already = tabs.indexOfFirst { !it.isHome && it.path == path }
        if (already >= 0) {
            activeIndex = already
            engine.setActive(tabs[already].docHandle)
            return
        }
        finishOpen(
            engine.open(path),
            { pw -> engine.open(path, pw) },
            OpenRequest.DirectFile(path),
        )
    }

    // Opening from the start page. A file picked through the storage
    // access framework lives in our cache under a hashed name, which the
    // system is free to evict — the original content URI is the durable
    // handle, so fall back to it before declaring the file missing.
    fun openEntry(entry: FileHistoryEntry) {
        if (File(entry.path).exists()) {
            openPath(entry.path)
            return
        }
        val uri = entry.uri
        if (uri == null) {
            history.markFileInexistent(entry.path, true)
            historyVersion++
            say("“${entry.displayName}” could not be found")
            return
        }
        scope.launch {
            val parsed = Uri.parse(uri)
            val outcome = withContext(Dispatchers.IO) { engine.openUri(context, parsed) }
            if (outcome is DocumentEngine.Opened.Failed) {
                history.markFileInexistent(entry.path, true)
                historyVersion++
                errorBanner = outcome.message
                return@launch
            }
            finishOpen(
                outcome,
                { pw -> engine.openUri(context, parsed, pw) },
                OpenRequest.ContentUri(parsed),
            )
        }
    }

    fun forgetEntry(entry: FileHistoryEntry) {
        history.remove(entry.path)
        Thumbnails.delete(context, entry.path)
        historyVersion++
    }

    fun togglePin(entry: FileHistoryEntry) {
        history.setPinned(entry.path, !entry.isPinned)
        historyVersion++
    }

    // Switch to another open tab. The engine already has the Document
    // for every tab in memory — we just point the active handle at
    // the new one. No I/O, no reload, no chance of a race.
    fun switchTab(index: Int) {
        if (index !in tabs.indices) return
        if (index == activeIndex) return
        // Save the current tab's state into its Tab object before
        // we lose it.
        val cur = tabs[activeIndex]
        tabs[activeIndex] = cur.copy(
            page = page,
            displayMode = displayMode,
            continuous = continuous,
            zoom = zoom,
            customZoom = customZoom,
            rotation = rotation,
        )
        val next = tabs[index]
        activeIndex = index
        engine.setActive(next.docHandle)
        renderEpoch++
        backStack.clear()
        forwardStack.clear()
    }

    fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        // Save the closing tab's state into its Tab object so it can
        // be re-opened later (if the user re-adds the file).
        val tab = tabs[index]
        if (index == activeIndex) {
            tabs[index] = tab.copy(
                page = page,
                displayMode = displayMode,
                continuous = continuous,
                zoom = zoom,
                customZoom = customZoom,
                rotation = rotation,
            )
        }
        val wasActive = index == activeIndex
        val activeHandleToClose = tab.docHandle
        // If this is the active tab and there are other tabs, the
        // engine will pick a new active handle for us when we close
        // (the most-recently-opened remaining doc).
        if (wasActive && tabs.size > 1) {
            // Pre-pick a new active tab so the activeIndex is
            // consistent after the list shrinks.
            val newActive = if (index + 1 < tabs.size) index else tabs.size - 2
            val newHandle = tabs[newActive].docHandle
            tabs.removeAt(index)
            engine.closeHandle(activeHandleToClose)
            engine.setActive(newHandle)
            activeIndex = newActive
        } else {
            tabs.removeAt(index)
            engine.closeHandle(activeHandleToClose)
            if (index < activeIndex) activeIndex--
        }
        renderEpoch++
    }

    // Close every tab except the active one. The Win32
    // CmdCloseOtherTabs does the same — useful for "I want to
    // focus on this one document" workflows. The Home tab is
    // kept open because the start page lives there; closing it
    // would also clear the recent-files list visually.
    fun closeOtherTabs() {
        if (tabs.size < 2) return
        val activeHandle = tabs[activeIndex].docHandle
        val keep = tabs.filter { it.isHome || it.docHandle == activeHandle }
        val drop = tabs.filter { !it.isHome && it.docHandle != activeHandle }
        for (t in drop) engine.closeHandle(t.docHandle)
        // SnapshotStateList is a val, so we mutate in place.
        tabs.clear()
        tabs.addAll(keep)
        activeIndex = tabs.indexOfFirst { it.docHandle == activeHandle }.coerceAtLeast(0)
    }

    // Close every tab to the right of the active one. Home tab
    // is always at index 0 and is never removed.
    fun closeTabsToTheRight() {
        if (activeIndex >= tabs.size - 1) return
        for (i in (activeIndex + 1) until tabs.size) {
            engine.closeHandle(tabs[i].docHandle)
        }
        // Drop everything after activeIndex.
        while (tabs.size > activeIndex + 1) {
            tabs.removeAt(tabs.size - 1)
        }
    }

    // Close every tab to the left of the active tab. Home tab
    // stays at index 0.
    fun closeTabsToTheLeft() {
        val hasHome = tabs.firstOrNull()?.isHome == true
        val start = if (hasHome) 1 else 0
        if (activeIndex <= start) return
        for (i in start until activeIndex) {
            engine.closeHandle(tabs[i].docHandle)
        }
        // Remove tabs [start, activeIndex). Home (index 0) stays.
        repeat(activeIndex - start) {
            if (tabs.size > 1) tabs.removeAt(start)
        }
        activeIndex = if (hasHome) 1 else 0
    }

    // Close every tab. Win32's CmdCloseAllTabs — used when the
    // user wants a clean slate. The Home tab stays so the start
    // page remains reachable.
    fun closeAllTabs() {
        for (i in tabs.indices) {
            if (!tabs[i].isHome) engine.closeHandle(tabs[i].docHandle)
        }
        // Drop everything except the Home tab.
        while (tabs.size > 1) {
            tabs.removeAt(tabs.size - 1)
        }
        activeIndex = 0
    }

    // Open the current document a second time. Win32's
    // CmdDuplicateInNewTab — useful for "open the same book
    // twice so I can compare page A and page B side by side."
    fun duplicateInNewTab() {
        val current = tabs.getOrNull(activeIndex) ?: return
        if (current.isHome) return
        // Re-open the file as a new engine handle and append a
        // tab for it. The session restore treats a duplicate
        // path as the same document, so a future session reopen
        // deduplicates it back to one tab — which is the Win32
        // behaviour (one tab per file path in the session).
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                engine.open(current.path)
            }
            if (outcome is DocumentEngine.Opened.Ok) {
                val newTab = Tab(
                    title = current.title + " (copy)",
                    docHandle = outcome.handle,
                    path = current.path,
                    page = current.page,
                    displayMode = current.displayMode,
                    continuous = current.continuous,
                    zoom = current.zoom,
                    customZoom = current.customZoom,
                    rotation = current.rotation,
                    isHome = false,
                )
                tabs.add(newTab)
                engine.setActive(outcome.handle)
                activeIndex = tabs.lastIndex
            } else if (outcome is DocumentEngine.Opened.Failed) {
                say(outcome.message)
            }
        }
    }

    // Opening happens here, not in the activity, because a
    // password-protected document needs a dialog and the dialog lives
    // in this composition.
    LaunchedEffect(pendingOpen, sessionRestored) {
        if (!sessionRestored) return@LaunchedEffect
        val req = pendingOpen ?: return@LaunchedEffect
        // The session restore may already have reopened this file. Do
        // not open it a second time just to throw the result away.
        if (req is OpenRequest.DirectFile) {
            val already = tabs.indexOfFirst { !it.isHome && it.path == req.absolutePath }
            if (already >= 0) {
                switchTab(already)
                onOpenHandled()
                return@LaunchedEffect
            }
        }
        val outcome = withContext(Dispatchers.IO) {
            when (req) {
                is OpenRequest.DirectFile -> engine.open(req.absolutePath)
                is OpenRequest.ContentUri -> engine.openUri(context, req.uri)
            }
        }
        finishOpen(
            outcome,
            { pw ->
                when (req) {
                    is OpenRequest.DirectFile -> engine.open(req.absolutePath, pw)
                    is OpenRequest.ContentUri -> engine.openUri(context, req.uri, pw)
                }
            },
            req,
        )
        onOpenHandled()
    }


    // src/Settings.h::SessionData — reopen the tabs that were open when
    // the app was last used. The pendingOpen effect waits on
    // `sessionRestored`, so a file opened from a file manager lands as an
    // extra tab on top of the restored ones rather than racing them.
    LaunchedEffect(Unit) {
        val data = if (settings.restoreSession) history.loadSessionData() else null
        if (data != null) {
            for (t in data.tabs) {
                val outcome = withContext(Dispatchers.IO) {
                    when {
                        File(t.path).exists() -> engine.open(t.path, history.passwordFor(t.path))
                        t.uri != null ->
                            engine.openUri(context, Uri.parse(t.uri), history.passwordFor(t.path))
                        else -> DocumentEngine.Opened.Failed("${baseName(t.path)} is no longer there")
                    }
                }
                if (outcome is DocumentEngine.Opened.Ok) activate(outcome.handle)
            }
            // A document that has gone away since last time is not worth
            // a banner on launch; it is already hidden from the start page.
            engine.clearError()
            errorBanner = null
            if (tabs.isNotEmpty()) {
                switchTab(data.tabIndex.coerceIn(0, tabs.lastIndex))
            }
        }
        sessionRestored = true
    }

    LaunchedEffect(sessionRestored, tabs.size, activeIndex) {
        if (!sessionRestored) return@LaunchedEffect
        val open = tabs.filter { !it.isHome && it.path.isNotEmpty() }
        if (open.isEmpty()) {
            history.clearSessionData()
        } else {
            history.saveSessionData(
                SessionData(
                    tabs = open.map { t -> SessionTab(t.path, history.findByPath(t.path)?.uri) },
                    tabIndex = activeIndex,
                ),
            )
        }
    }

    fun withSelection(what: String, body: (String) -> Unit) {
        val text = selection?.text?.trim()
        if (text.isNullOrEmpty()) {
            say("Long-press a word to select text first")
            return
        }
        if (!engine.canCopy()) {
            say("This document does not allow copying text")
            return
        }
        body(text)
        if (what.isNotEmpty()) say(what)
    }

    fun startReadAloud(fromPage: Int) {
        if (!engine.isOpen) {
            say("Open a document first")
            return
        }
        settings.readAloudVoice?.let { readAloud.useVoice(it) }
        readAloud.start(
            fromPage = fromPage,
            lastPageIndex = maxOf(0, pageCount - 1),
            pageText = { p -> engine.pageText(p) },
            onPage = { p -> page = p.coerceIn(0, maxOf(0, pageCount - 1)) },
        )
    }

    fun openNeighbour(step: Int) {
        val here = tabs.getOrNull(activeIndex)?.path
        if (here == null) {
            say("Open a document first")
            return
        }
        val next = neighbourDocument(here, step)
        if (next == null) {
            say(if (step > 0) "No next file in this folder" else "No previous file in this folder")
            return
        }
        openPath(next)
    }

    fun handleMenu(action: MenuAction) {
        val currentPath = tabs.getOrNull(activeIndex)?.path
        when (action) {
            // File
            MenuAction.NewWindow ->
                say("New window: Android runs one reader window")
            MenuAction.Open -> onOpenFile()
            MenuAction.ShowRecent -> showRecent = true
            MenuAction.Close -> {
                if (tabs.isNotEmpty()) closeTab(activeIndex)
                else onCloseFile()
            }
            MenuAction.ShowInFolder -> {
                if (currentPath == null) say("Open a document first")
                else if (!revealDocument(context, currentPath)) {
                    say("No app on this device can show a folder")
                }
            }
            MenuAction.OpenNext -> openNeighbour(1)
            MenuAction.OpenPrev -> openNeighbour(-1)
            MenuAction.SaveAs -> {
                if (currentPath == null) say("Open a document first")
                else onSaveCopy(currentPath)
            }
            MenuAction.ShareDocument -> {
                if (currentPath == null) say("Open a document first")
                else if (!shareDocument(context, currentPath)) say("Could not share this document")
            }
            MenuAction.SaveAnnotations, MenuAction.Rename, MenuAction.Delete ->
                say("Not implemented in this build")
            MenuAction.Print -> {
                if (!engine.isOpen) say("Open a document first")
                else if (!engine.canPrint()) say("This document does not allow printing")
                else startPrintJob(context, engine, currentPath?.substringAfterLast('/') ?: "Document")
            }
            MenuAction.Properties -> {
                if (!engine.isOpen) {
                    say("Open a document first")
                } else {
                    scope.launch {
                        properties = withContext(Dispatchers.IO) { engine.properties() }
                        showProperties = true
                    }
                }
            }
            MenuAction.Exit -> onCloseFile()

            // View
            MenuAction.CommandPalette ->
                say("Command Palette: not implemented in this build")
            is MenuAction.SetDisplayMode -> {
                displayMode = action.mode
            }
            MenuAction.ToggleContinuous -> continuous = !continuous
            MenuAction.ToggleMangaMode -> {
                mangaMode = !mangaMode
                say("Manga mode: ${if (mangaMode) "on" else "off"}")
            }
            MenuAction.RotateLeft -> { rotation = (rotation - 90 + 360) % 360; selection = null }
            MenuAction.RotateRight -> { rotation = (rotation + 90) % 360; selection = null }
            MenuAction.Presentation -> {
                presentation = !presentation
                onImmersiveChange(presentation)
                if (presentation) {
                    displayMode = DisplayMode.SinglePage
                    continuous = false
                    zoom = ZoomLevel.FitPage
                }
            }
            MenuAction.Fullscreen -> onImmersiveChange(!immersive)
            // Win32 "Show Bookmarks" (CmdToggleBookmarks) calls
            // ToggleTocBox — it is the table-of-contents sidebar, not
            // the favourites list, which is Favorites ▸ Show Favorites.
            MenuAction.ShowBookmarks -> showToCSidebar = !showToCSidebar
            MenuAction.ShowMenu, MenuAction.ShowToolbar -> { /* no-op on touch */ }

            // Go To — manga mode swaps the user's "next" / "prev"
            // intent with the document's actual direction (R2L).
            MenuAction.NextPage -> mangaAwareNext()
            MenuAction.PrevPage -> mangaAwarePrev()
            MenuAction.FirstPage -> gotoFirst()
            MenuAction.LastPage -> gotoLast()
            MenuAction.GoToPage -> showGoToPage = true
            MenuAction.NavigateBack -> navigateBack()
            MenuAction.NavigateForward -> navigateForward()
            MenuAction.FindFirst -> {
                showFind = !showFind
                if (!showFind) {
                    findHitsByPage = emptyMap()
                    findQuery = TextFieldValue("")
                    findCurrentHit = 0
                }
            }

            // Zoom
            MenuAction.FitPage -> { zoom = ZoomLevel.FitPage }
            MenuAction.ActualSize -> { zoom = ZoomLevel.Custom; customZoom = 1f }
            MenuAction.FitWidth -> { zoom = ZoomLevel.FitWidth }
            MenuAction.FitHeight -> { zoom = ZoomLevel.FitHeight }
            MenuAction.FitByOrientation -> {
                val size = engine.pageSize(page)
                zoom = if (size != null && size.first > size.second) ZoomLevel.FitWidth
                       else ZoomLevel.FitPage
            }
            MenuAction.FitContent -> { zoom = ZoomLevel.FitContent }
            MenuAction.ShrinkToFit -> { zoom = ZoomLevel.FitPage }
            MenuAction.CustomZoom -> showCustomZoom = true
            is MenuAction.ZoomPercent -> { zoom = ZoomLevel.Custom; customZoom = action.percent / 100f }

            // Favorites
            MenuAction.AddBookmark -> {
                if (currentPath != null) {
                    val b = Bookmark(path = currentPath, page = page, name = "")
                    settings.addBookmark(b)
                    bookmarks = settings.getBookmarks()
                    say("Bookmark added for page ${page + 1}")
                } else {
                    say("Open a document first")
                }
            }
            MenuAction.RemoveBookmark -> {
                if (currentPath != null) {
                    val current = settings.getBookmarks().firstOrNull { it.path == currentPath }
                    if (current != null) {
                        settings.removeBookmark(current)
                        bookmarks = settings.getBookmarks()
                        say("Bookmark removed")
                    } else {
                        say("No bookmark for this document")
                    }
                } else {
                    say("Open a document first")
                }
            }
            MenuAction.ListBookmarks -> showBookmarks = true
            MenuAction.ShowFavoritesInTab -> showBookmarks = true
            // Favorite navigation: jump to the next/previous
            // bookmark on the active page, sorted by page
            // number. If the active page is on a bookmark,
            // that one is the "current" and we step from it.
            MenuAction.GoToNextFavorite -> gotoFavorite(+1)
            MenuAction.GoToPrevFavorite -> gotoFavorite(-1)
            // Sort toggle: re-orders the Bookmarks sidebar
            // between "by page" (default) and "by name". The
            // state is held in the screen, not Settings, so it
            // resets on cold start — same shape as the ToC
            // expand state.
            MenuAction.ToggleFavoritesSort -> bookmarksSortByName = !bookmarksSortByName
            MenuAction.CommandPaletteFavorites -> showBookmarks = true
            MenuAction.SaveTabGroup, MenuAction.RestoreTabGroup ->
                say("Tab groups: not implemented in this build")

            // Selection
            MenuAction.CopySelection -> withSelection("Copied to clipboard") { text ->
                copyToClipboard(context, "SumatraPDF", text)
            }
            MenuAction.TranslateGoogle -> withSelection("") { text ->
                openInBrowser(context, lookupUrl(WebLookup.GoogleTranslate, text))
            }
            MenuAction.TranslateDeepL -> withSelection("") { text ->
                openInBrowser(context, lookupUrl(WebLookup.DeepL, text))
            }
            MenuAction.TranslateGrokBuild, MenuAction.TranslateClaudeCode,
            MenuAction.TranslateOpenAICodex ->
                say("The AI translation backends are desktop CLIs, not available on Android")
            MenuAction.SearchGoogle -> withSelection("") { text ->
                openInBrowser(context, lookupUrl(WebLookup.Google, text))
            }
            MenuAction.SearchBing -> withSelection("") { text ->
                openInBrowser(context, lookupUrl(WebLookup.Bing, text))
            }
            MenuAction.SearchWikipedia -> withSelection("") { text ->
                openInBrowser(context, lookupUrl(WebLookup.Wikipedia, text))
            }
            MenuAction.SearchGoogleScholar -> withSelection("") { text ->
                openInBrowser(context, lookupUrl(WebLookup.GoogleScholar, text))
            }
            MenuAction.SelectAll -> {
                if (!engine.isOpen) {
                    say("Open a document first")
                } else {
                    scope.launch {
                        val text = withContext(Dispatchers.IO) { engine.pageText(page) }
                        if (text.isBlank()) {
                            say("This page has no text")
                        } else {
                            copyToClipboard(context, "SumatraPDF", text)
                            say("Copied all text on page ${page + 1}")
                        }
                    }
                }
            }

            // Read Aloud
            MenuAction.StartReadingTop -> startReadAloud(page)
            MenuAction.PauseReading -> readAloud.pause()
            MenuAction.ResumeReading -> readAloud.resume()
            MenuAction.StopReading -> readAloud.stop()
            MenuAction.ChangeVoice -> {
                voices = readAloud.voiceNames()
                if (voices.isEmpty()) say("No text-to-speech voices are installed")
                else showVoicePicker = true
            }

            // Settings
            MenuAction.ChangeLanguage ->
                say("SumatraPDF for Android follows the system language")
            MenuAction.Options, MenuAction.AdvancedSettings, MenuAction.AdvancedOptions ->
                showSettings = true
            MenuAction.Theme -> {
                val next = !nightMode
                onNightModeChange(next)
                invertPages = next
                settings.invertPageColors = next
            }

            // Help
            MenuAction.Manual, MenuAction.ManualOnWebsite ->
                openInBrowser(context, kSumatraManual)
            MenuAction.KeyboardShortcuts ->
                openInBrowser(context, kSumatraShortcuts)
            MenuAction.VisitWebsite ->
                openInBrowser(context, kSumatraWebsite)
            MenuAction.CheckUpdate ->
                say("This build does not check for updates")
            MenuAction.ToggleRenderInfo ->
                say("Render queue info: not implemented in this build")
            MenuAction.ToggleCacheInfo ->
                say("Cache info: not implemented in this build")
            MenuAction.About -> showAbout = true

            // Debug
            MenuAction.ShowLinks -> {
                // Win32's ShowLinks toggles a "show link rectangles"
                // mode. Default is OFF — the user only sees links
                // when this is on. (PORTING-STATUS §3.1.)
                showLinks = !showLinks
                say(if (showLinks) "Show links on" else "Show links off")
            }
            MenuAction.DownloadSymbols, MenuAction.TestApp, MenuAction.ShowNotification ->
                say("Not implemented in this build")

            // Keyboard-shortcut layer. Each branch is a Win32
            // Cmd*; we either handle it (zoom, view-mode, tab
            // navigation, scroll) or surface the unimplemented half
            // to the user so the shortcut visibly maps to the
            // desktop's behaviour. (The shortcut layer itself is
            // a no-op if Settings.keyboardShortcuts is off; see
            // MainActivity.dispatchKeyEvent.)
            MenuAction.BookView -> handleMenu(MenuAction.SetDisplayMode(DisplayMode.BookView))
            MenuAction.FacingView -> handleMenu(MenuAction.SetDisplayMode(DisplayMode.Facing))
            MenuAction.SinglePageView -> handleMenu(MenuAction.SetDisplayMode(DisplayMode.SinglePage))
            MenuAction.FindNext -> moveFindHit(+1)
            MenuAction.FindPrev -> moveFindHit(-1)
            MenuAction.FindNextSel, MenuAction.FindPrevSel ->
                say("Find in selection: not implemented in this build")
            MenuAction.InvertColors -> {
                val next = !invertPages
                invertPages = next
                settings.invertPageColors = next
            }
            MenuAction.ZoomIn -> stepZoom(kZoomMax)
            MenuAction.ZoomOut -> stepZoom(kZoomMin)
            MenuAction.MoveTabLeft -> moveTab(activeIndex, -1)
            MenuAction.MoveTabRight -> moveTab(activeIndex, +1)
            MenuAction.NextTab -> {
                if (tabs.isNotEmpty()) activeIndex = (activeIndex + 1) % tabs.size
            }
            MenuAction.PrevTab -> {
                if (tabs.isNotEmpty()) activeIndex = (activeIndex - 1 + tabs.size) % tabs.size
            }
            MenuAction.NextTabSmart, MenuAction.PrevTabSmart -> {
                // Win32: Smart = skip the Home tab. With one document
                // open there's no difference; with several, the next
                // index is the next document tab.
                if (tabs.size < 2) return
                val n = tabs.size
                var i = activeIndex
                repeat(n - 1) {
                    i = (i + if (action is MenuAction.NextTabSmart) 1 else -1)
                    if (i < 0) i += n
                    if (i >= n) i -= n
                    if (!tabs[i].isHome) { activeIndex = i; return@repeat }
                }
            }
            MenuAction.CloseOtherTabs -> closeOtherTabs()
            MenuAction.CloseTabsToTheRight -> closeTabsToTheRight()
            MenuAction.CloseTabsToTheLeft -> closeTabsToTheLeft()
            MenuAction.CloseAllTabs -> closeAllTabs()
            MenuAction.DuplicateInNewTab -> duplicateInNewTab()
            MenuAction.SetTabColor ->
                say("Tab colors: not implemented in this build")

            // ToC commands. The trigger counters are picked up by
            // the ToCSidebar's LaunchedEffect, which mutates
            // its own expandedIds state. The handleMenu side
            // here just bumps the counter.
            MenuAction.ExpandAll -> tocExpandAllTrigger++
            MenuAction.CollapseAll -> tocCollapseAllTrigger++
            MenuAction.ExpandToCurrentPage -> tocExpandToCurrentTrigger++
            // The level-targeted variants need a real
            // "expand to depth N" helper in ToCSidebar; for
            // now they surface as a snackbar.
            MenuAction.TocExpandToLevel1, MenuAction.TocExpandToLevel2,
            MenuAction.TocExpandToLevel3 -> say("Expand to level: not implemented in this build")
            MenuAction.TocCollapseSameLevel ->
                say("Collapse same level: not implemented in this build")
            MenuAction.Screenshot ->
                say("Screenshot: not implemented in this build")
            MenuAction.ScrollUp, MenuAction.ScrollUpPage, MenuAction.ScrollUpHalfPage -> {
                // Without page-level scroll offset (TODO §3.6), the
                // best behaviour is to move to the previous page in
                // non-continuous mode and step back a quarter page in
                // continuous mode. For now, we always step a page —
                // matches the Win32 CmdScrollUpPage.
                mangaAwarePrev()
            }
            MenuAction.ScrollDown, MenuAction.ScrollDownPage, MenuAction.ScrollDownHalfPage -> mangaAwareNext()
            MenuAction.ScrollLeft, MenuAction.ScrollLeftPage -> {
                // Win32: facing/book non-continuous, scroll the left
                // page out of view. Without that layout in this port
                // (#3.6), step to the previous page. Manga mode flips
                // it (issue #3964: Left advances).
                mangaAwarePrev()
            }
            MenuAction.ScrollRight, MenuAction.ScrollRightPage -> mangaAwareNext()
            MenuAction.PasteClipboardImage ->
                say("Paste image: not implemented in this build")
            MenuAction.ReopenLastClosedFile ->
                say("Reopen last closed: not implemented in this build")
            MenuAction.ReloadDocument ->
                say("Reload: not implemented in this build")
            MenuAction.TogglePageInfo ->
                say("Page info overlay: not implemented in this build")
            MenuAction.ToggleCursorPosition ->
                say("Cursor position: not implemented in this build")
            MenuAction.ToggleZoom ->
                say("Zoom box: not implemented in this build")
            MenuAction.PresentationBlack, MenuAction.PresentationWhite ->
                say("Presentation backgrounds: not implemented in this build")
            MenuAction.CreateShortcutToFile ->
                say("Create shortcut: not implemented in this build")
            MenuAction.DuplicateInNewWindow ->
                say("Duplicate in new window: Android runs one reader window")
            MenuAction.CommandPaletteOnlyTabs ->
                say("Command palette (tabs only): not implemented in this build")
            MenuAction.CommandPaletteTOC ->
                say("Command palette (ToC): not implemented in this build")
            MenuAction.MoveFrameFocus ->
                say("Move frame focus: not implemented in this build")

            // Context-menu items (src/Menu.cpp::menuDefContext and
            // its submenus). Most of these map to features that
            // aren't in the Android port yet — annotations, image
            // ops, PDF tools, the AI chat CLIs. The port shows the
            // full menu (ContextMenu.kt) so the user sees the same
            // surface as on Windows, and the dispatch lands here as
            // a "not implemented" snackbar. As each subsystem lands
            // (annotations, image ops, PDF tools, …) the matching
            // branches become real implementations.
            MenuAction.OpenSelectedDocument -> {
                // Same as the start-page tile click: open the
                // currently-selected entry. For now, no selection
                // state is held, so the user gets a hint instead.
                say("Open the file from the start page or recent files")
            }
            MenuAction.PinSelectedDocument, MenuAction.ForgetSelectedDocument ->
                say("Open the start page to pin or forget a document")
            MenuAction.CopyLinkTarget, MenuAction.CopyComment ->
                say("Copy link / comment: long-press a link to use it")
            MenuAction.SaveAttachment -> say("Save attachment: not implemented in this build")
            MenuAction.ShowErrors -> say("No errors to show")
            MenuAction.EditAnnotations, MenuAction.DeleteAnnotation ->
                say("Annotation edit / delete: not implemented in this build")
            MenuAction.CopyImage, MenuAction.CropImage, MenuAction.ResizeImage,
            MenuAction.SaveImage, MenuAction.PasteImageFromClipboard ->
                say("Image ops: not implemented in this build")
            MenuAction.ShowPdfInfo, MenuAction.DocumentShowOutline,
            MenuAction.DocumentExtractText, MenuAction.PdfExtractPages,
            MenuAction.PdfDeletePages, MenuAction.PdfCompress,
            MenuAction.PdfDecompress, MenuAction.PdfEncrypt,
            MenuAction.PdfDecrypt, MenuAction.PdfBake ->
                say("PDF tools: not implemented in this build")
            // The favorite variants (FavoriteAdd / Del / Toggle /
            // ShowInTab) map to the existing bookmark code on
            // the desktop. The Android port uses "Bookmark" in
            // the hamburger menu; the context menu's "favorite"
            // wording is the Win32 / older SumatraPDF spelling.
            MenuAction.FavoriteAdd -> handleMenu(MenuAction.AddBookmark)
            MenuAction.FavoriteDel -> handleMenu(MenuAction.RemoveBookmark)
            MenuAction.FavoriteToggle -> handleMenu(MenuAction.ShowBookmarks)
            MenuAction.FavoriteShowInTab -> handleMenu(MenuAction.ShowFavoritesInTab)
            // Translate / search the current text selection: the
            // shared selection-translate / search paths in the
            // hamburger do the same thing. Reuse them.
            MenuAction.TranslateSelectionGoogle -> handleMenu(MenuAction.TranslateGoogle)
            MenuAction.TranslateSelectionDeepL -> handleMenu(MenuAction.TranslateDeepL)
            MenuAction.TranslateSelectionGrokBuild -> handleMenu(MenuAction.TranslateGrokBuild)
            MenuAction.TranslateSelectionClaudeCode -> handleMenu(MenuAction.TranslateClaudeCode)
            MenuAction.TranslateSelectionOpenAICodex -> handleMenu(MenuAction.TranslateOpenAICodex)
            MenuAction.SearchSelectionGoogle -> handleMenu(MenuAction.SearchGoogle)
            MenuAction.SearchSelectionBing -> handleMenu(MenuAction.SearchBing)
            MenuAction.SearchSelectionWikipedia -> handleMenu(MenuAction.SearchWikipedia)
            MenuAction.SearchSelectionGoogleScholar -> handleMenu(MenuAction.SearchGoogleScholar)
        }
    }

    // The keyboard-shortcut layer. MainActivity.dispatchKeyEvent
    // writes a MenuAction to `pendingShortcut`; this LaunchedEffect
    // routes it to the same `handleMenu` the hamburger menu uses, so
    // a key press behaves exactly like a menu tap.
    //
    // We watch the action's identity (its toString) so the effect
    // re-fires for the same action when MainActivity writes a new
    // instance after a previous one was handled.
    LaunchedEffect(pendingShortcut.toString()) {
        val action = pendingShortcut ?: return@LaunchedEffect
        handleMenu(action)
        onShortcutHandled()
    }

    LaunchedEffect(active?.docHandle, pageCount, libraryJumpPage) {
        if (libraryJumpPage > 0 && pageCount > 0 && active?.isHome == false) {
            page = (libraryJumpPage - 1).coerceIn(0, pageCount - 1)
            libraryJumpPage = -1
        }
    }

    val chromeVisible = !immersive && !presentation
    // No tabs at all, or the Home tab selected: the canvas is the start
    // page, exactly as src/Canvas.cpp routes WM_PAINT to DrawHomePage
    // when the window has no loaded document.
    val onHome = active?.isHome ?: true

    // Holding the back button for three seconds drops everything and
    // lands on the frequently-read page; the press after that leaves.
    LaunchedEffect(backHeld) {
        if (backHeld <= 0) return@LaunchedEffect
        showFind = false
        showToCSidebar = false
        showSettings = false
        coverEditFor = null
        selection = null
        contextMenuId = null
        contextMenuAnchor = null
        presentation = false
        onImmersiveChange(false)
        libraryRailOpen = false
        libraryModel.closeDetail()
        libraryHome = false
        settings.libraryHome = false
        exitArmed = true
    }

    // One back press, one step back. Nothing here ever leaves the app
    // except the branch that a hold (or a back press with nowhere left
    // to go) has already armed.
    androidx.activity.compose.BackHandler {
        val armed = exitArmed
        exitArmed = false
        when {
            showSettings -> showSettings = false
            coverEditFor != null -> coverEditFor = null
            showFind -> {
                showFind = false
                findJob?.cancel()
            }
            selection != null -> selection = null
            contextMenuId != null -> {
                contextMenuId = null
                contextMenuAnchor = null
            }
            presentation -> {
                presentation = false
                onImmersiveChange(false)
            }
            immersive -> onImmersiveChange(false)
            showToCSidebar -> showToCSidebar = false
            !onHome && backStack.isNotEmpty() -> {
                val to = backStack.removeAt(backStack.size - 1)
                forwardStack.add(page)
                selection = null
                page = to.coerceIn(0, maxOf(0, pageCount - 1))
            }
            !onHome -> closeTab(activeIndex)
            libraryHome && libraryRailOpen -> libraryRailOpen = false
            libraryHome && libraryModel.detail != null -> libraryModel.closeDetail()
            libraryHome && libraryModel.search.isNotBlank() -> libraryModel.search = ""
            libraryHome && libraryModel.filterKey != null -> libraryModel.selectRow(null)
            libraryHome -> {
                libraryHome = false
                settings.libraryHome = false
                exitArmed = true
            }
            armed -> onCloseFile()
            else -> {
                exitArmed = true
                say("Press back again to leave SumatraPDF")
            }
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (chromeVisible) {
                    TabsRow(
                        tabs = tabs.toList(),
                        activeIndex = activeIndex.coerceAtMost(maxOf(0, tabs.size - 1)),
                        onSelect = ::switchTab,
                        onClose = ::closeTab,
                        onMenuAction = ::handleMenu,
                        displayMode = displayMode,
                        continuous = continuous,
                        zoom = zoom,
                    )
                    SumatraToolbar(
                        page = page + 1,
                        pageCount = pageCount,
                        enabled = engine.isOpen && pageCount > 0,
                        displayMode = displayMode,
                        continuous = continuous,
                        zoom = zoom,
                        onOpen = onOpenFile,
                        onPrint = { handleMenu(MenuAction.Print) },
                        onPrev = ::mangaAwarePrev,
                        onNext = ::mangaAwareNext,
                        onNavigateBack = ::navigateBack,
                        onNavigateForward = ::navigateForward,
                        onReadAloud = {
                            when (readAloud.state) {
                                ReadAloudState.Speaking -> readAloud.pause()
                                ReadAloudState.Paused -> readAloud.resume()
                                else -> startReadAloud(page)
                            }
                        },
                        onFitWidthContinuous = ::applyFitWidthContinuous,
                        onFitSinglePage = ::applyFitSinglePage,
                        onRotateLeft = { handleMenu(MenuAction.RotateLeft) },
                        onRotateRight = { handleMenu(MenuAction.RotateRight) },
                        onZoomOut = { stepZoom(kZoomMin) },
                        onZoomIn = { stepZoom(kZoomMax) },
                        onFind = { handleMenu(MenuAction.FindFirst) },
                        onPageStatus = { showGoToPage = true },
                        readAloudActive = readAloud.state == ReadAloudState.Speaking,
                        // Long-press on the zoom buttons pops the
                        // Win32 quick-zoom list (6400% → 50% in
                        // halvings). The popup is anchored at the
                        // bottom of the toolbar (we just use the
                        // top-left of the screen as a known safe
                        // point — the cascading menu opens a
                        // DropdownMenu-style overlay).
                        onZoomLongPress = {
                            contextMenuId = ContextMenuId.ZoomShort
                            // Anchor the popup below the toolbar; the
                            // user expects the menu to appear near
                            // the button. We use a fixed top offset
                            // that sits below the tab bar + toolbar
                            // (36dp + 40dp = 76dp, so 80dp is safe).
                            contextMenuAnchor = androidx.compose.ui.geometry.Offset(0f, 0f)
                        },
                    )
                }
                if (errorBanner != null) {
                    ErrorBanner(
                        message = errorBanner!!,
                        onDismiss = { errorBanner = null },
                    )
                }
                if (showFind && chromeVisible && !onHome) {
                    SearchBar(
                        query = findQuery,
                        onQueryChange = { findQuery = it },
                        onSubmit = ::runSearch,
                        onNext = { gotoHit(1) },
                        onPrev = { gotoHit(-1) },
                        onClose = {
                            showFind = false
                            findHitsByPage = emptyMap()
                            findQuery = TextFieldValue("")
                            findCurrentHit = 0
                        },
                        currentHit = (findCurrentHit + 1).coerceAtLeast(0),
                        totalHits = findTotal,
                        visible = showFind,
                        isSearching = findSearching,
                        searchProgress = if (findSearching && pageCount > 0) {
                            (findProgressPage.toFloat() / pageCount).coerceIn(0f, 1f)
                        } else null,
                        anyTextFieldFocused = anyTextFieldFocused,
                    )
                }
                Row(modifier = Modifier.fillMaxSize().weight(1f)) {
                    if (showToCSidebar && chromeVisible && !onHome) {
                        ToCSidebar(
                            outline = outline ?: emptyList(),
                            bookmarks = bookmarks,
                            currentPage = page,
                            currentPath = tabs.getOrNull(activeIndex)?.path ?: "",
                            pageCount = pageCount,
                            onJumpToPage = { p -> goToPage(p) },
                            onClose = { showToCSidebar = false },
                            onRemoveBookmark = { b ->
                                settings.removeBookmark(b)
                                bookmarks = settings.getBookmarks()
                            },
                            expandAllTrigger = tocExpandAllTrigger,
                            collapseAllTrigger = tocCollapseAllTrigger,
                            expandToCurrentTrigger = tocExpandToCurrentTrigger,
                            sortBookmarksByName = bookmarksSortByName,
                            onToggleBookmarkSort = { bookmarksSortByName = !bookmarksSortByName },
                        )
                    }
                    Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                        if (onHome) {
                            if (libraryHome) {
                                LibraryPage(
                                    model = libraryModel,
                                    look = com.sumatrapdf.reader.library.LibraryLook(
                                        columns = libraryColumns,
                                        titleScale = libraryTitleScale,
                                        onColumns = {
                                            libraryColumns = it
                                            settings.libraryColumns = it
                                        },
                                    ),
                                    onOpen = { p -> openPath(p) },
                                    onOpenAt = { p, pg ->
                                        libraryJumpPage = pg
                                        openPath(p)
                                    },
                                    onClassicHome = {
                                        libraryHome = false
                                        settings.libraryHome = false
                                    },
                                    onEditCover = { coverEditFor = it },
                                    railOpen = libraryRailOpen,
                                    onRailOpen = { libraryRailOpen = it },
                                    anyTextFieldFocused = anyTextFieldFocused,
                                )
                                return@Box
                            }
                            StartPage(
                                entries = homeEntries,
                                listView = homeListView,
                                sortByFrequentlyRead = homeSortFrequent,
                                filter = homeFilter,
                                onFilterChange = { homeFilter = it },
                                onSetListView = {
                                    homeListView = it
                                    settings.homePageListView = it
                                },
                                onToggleSort = {
                                    homeSortFrequent = !homeSortFrequent
                                    settings.homePageSortByFrequentlyRead = homeSortFrequent
                                },
                                onOpenDocument = onOpenFile,
                                onOpenEntry = ::openEntry,
                                onPin = ::togglePin,
                                onForget = ::forgetEntry,
                                onShowInFolder = { entry ->
                                    if (!revealDocument(context, entry.path)) {
                                        say("No app on this device can show a folder")
                                    }
                                },
                                onShowLibrary = {
                                    libraryHome = true
                                    settings.libraryHome = true
                                },
                                titleScale = libraryTitleScale,
                                anyTextFieldFocused = anyTextFieldFocused,
                            )
                            return@Box
                        }
                        PageSurface(
                            engine = engine,
                            page = page,
                            pageCount = pageCount,
                            displayMode = displayMode,
                            continuous = continuous,
                            zoom = zoom,
                            customZoom = customZoom,
                            rotation = rotation,
                            renderEpoch = renderEpoch,
                            searchHits = if (showFind) findCurrentPageHits else emptyList(),
                            searchCurrentHit = if (showFind) {
                                val list = findCurrentPageHits
                                if (list.isEmpty()) -1
                                else {
                                    val flat = findHitsByPage.entries.sortedBy { it.key }
                                        .flatMap { (p, lst) -> lst.map { p to it } }
                                    val startOfPage = flat.indexOfFirst { it.first == page }
                                    if (startOfPage < 0) -1
                                    else findCurrentHit - startOfPage
                                }
                            } else -1,
                            linksByPage = if (showLinks) linksByPage else emptyMap(),
                            selection = selection,
                            invertColors = invertPages,
                            mangaMode = mangaMode,
                            onTap = { tap ->
                                if (tap == Tap.Single) {
                                    if (selection != null) selection = null
                                    else if (presentation) onImmersiveChange(!immersive)
                                }
                            },
                            onEdgeClick = { edge ->
                                // Manga mode: the visual right edge is the
                                // lower page index, so taps there navigate
                                // backwards in document order.
                                if (edge == PageEdge.Left) mangaAwarePrev() else mangaAwareNext()
                            },
                            onVisiblePageChange = { visible ->
                                if (visible != page) page = visible
                            },
                            onEffectiveScale = { effectiveScale = it },
                            onPinchZoom = { factor, activeScale ->
                                val base = if (zoom == ZoomLevel.Custom) customZoom else activeScale
                                zoom = ZoomLevel.Custom
                                customZoom = (base * factor)
                                    .coerceIn(kZoomMin / 100f, kZoomMax / 100f)
                            },
                            onPagePoint = { pt ->
                                val hit = linksByPage[pt.page]?.firstOrNull { link ->
                                    pt.x >= link.bounds.x0 && pt.x <= link.bounds.x1 &&
                                        pt.y >= link.bounds.y0 && pt.y <= link.bounds.y1
                                }
                                when {
                                    hit == null -> false
                                    hit.isExternal -> {
                                        if (!openInBrowser(context, hit.uri)) {
                                            say("Nothing on this device can open ${hit.uri}")
                                        }
                                        true
                                    }
                                    hit.targetPage >= 0 -> {
                                        goToPage(hit.targetPage)
                                        true
                                    }
                                    else -> false
                                }
                            },
                            onSelectDrag = { anchor, current ->
                                scope.launch {
                                    selection = withContext(Dispatchers.IO) {
                                        engine.selectWords(
                                            anchor.page, anchor.x, anchor.y, current.x, current.y,
                                        )
                                    }
                                }
                            },
                            onSelectEnd = {
                                val text = selection?.text?.trim()
                                if (!text.isNullOrEmpty()) {
                                    say("${text.take(30)}${if (text.length > 30) "…" else ""} selected")
                                }
                            },
                            onLongPress = { off ->
                                // Long-press on the page background
                                // pops the Win32 menuDefContext. The
                                // selection drag still starts on
                                // long-press-then-drag (the second
                                // pointerInput block); this is the
                                // stationary long-press, which on
                                // Windows opens the document context
                                // menu.
                                contextMenuAnchor = off
                                contextMenuId = ContextMenuId.Document
                            },
                        )
                    }
                }
            }
            if (selection != null && chromeVisible) {
                SelectionActionBar(
                    onCopy = { handleMenu(MenuAction.CopySelection) },
                    onShare = {
                        withSelection("") { text -> shareText(context, text) }
                    },
                    onSearch = { handleMenu(MenuAction.SearchGoogle) },
                    onTranslate = { handleMenu(MenuAction.TranslateGoogle) },
                    onDismiss = { selection = null },
                    onMore = {
                        // The full Win32 menuDefSelection pops here —
                        // the user can pick a different search engine
                        // (Bing / Wikipedia / Scholar) or a different
                        // translation CLI (DeepL / Grok / Claude /
                        // Codex). Anchor at the center of the bar so
                        // the user can see what they long-pressed.
                        contextMenuId = ContextMenuId.Selection
                        contextMenuAnchor = androidx.compose.ui.geometry.Offset(120f, 120f)
                    },
                    // Clear of the tab row (36dp) and toolbar (40dp), or it
                    // sits on top of them and hides the tab title.
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 84.dp),
                )
            }
            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp),
            ) { data -> Snackbar(snackbarData = data) }

            // Long-press context menu (port of Win32 menuDef*).
            // Renders a popup at the long-press position; tapping
            // a row dispatches the MenuAction through handleMenu,
            // the same path the hamburger menu uses.
            val anchor = contextMenuAnchor
            val id = contextMenuId
            if (anchor != null && id != null) {
                ContextMenuPopup(
                    definition = ContextMenus.byId(id),
                    anchor = anchor,
                    onPick = { action -> handleMenu(action) },
                    onDismiss = {
                        contextMenuAnchor = null
                        contextMenuId = null
                    },
                )
            }
        }
    }

    val prompt = passwordFor
    if (prompt != null) {
        PasswordDialog(
            name = when (prompt) {
                is OpenRequest.DirectFile -> prompt.absolutePath.substringAfterLast('/')
                is OpenRequest.ContentUri -> prompt.uri.lastPathSegment ?: "document"
            },
            wrongPassword = passwordRetry,
            anyTextFieldFocused = anyTextFieldFocused,
            onDismiss = {
                passwordFor = null
                passwordRetry = false
            },
            onSubmit = { entered ->
                scope.launch {
                    val outcome = withContext(Dispatchers.IO) {
                        when (prompt) {
                            is OpenRequest.DirectFile -> engine.open(prompt.absolutePath, entered)
                            is OpenRequest.ContentUri -> engine.openUri(context, prompt.uri, entered)
                        }
                    }
                    when (outcome) {
                        is DocumentEngine.Opened.Ok -> {
                            passwordFor = null
                            passwordRetry = false
                            activate(outcome.handle)
                            // activate() is what puts the document in the
                            // history, so the password can only be attached
                            // to it afterwards.
                            engine.pathFor(outcome.handle)?.let { p ->
                                history.rememberPassword(p, entered)
                            }
                        }
                        is DocumentEngine.Opened.NeedsPassword -> passwordRetry = true
                        is DocumentEngine.Opened.Failed -> {
                            passwordFor = null
                            errorBanner = outcome.message
                        }
                    }
                }
            },
        )
    }

    if (showGoToPage) {
        GoToPageDialog(
            page = page + 1,
            pageCount = pageCount,
            anyTextFieldFocused = anyTextFieldFocused,
            onDismiss = { showGoToPage = false },
            onSubmit = { p ->
                showGoToPage = false
                goToPage(p - 1)
            },
        )
    }

    if (showCustomZoom) {
        CustomZoomDialog(
            currentPercent = (customZoom * 100).toInt(),
            anyTextFieldFocused = anyTextFieldFocused,
            onDismiss = { showCustomZoom = false },
            onSubmit = { p ->
                showCustomZoom = false
                zoom = ZoomLevel.Custom
                customZoom = (p / 100f).coerceIn(kZoomMin / 100f, kZoomMax / 100f)
            },
        )
    }

    if (showProperties) {
        PropertiesDialog(
            rows = properties,
            onDismiss = { showProperties = false },
        )
    }

    if (showVoicePicker) {
        VoicePickerDialog(
            voices = voices,
            current = readAloud.currentVoiceName(),
            onDismiss = { showVoicePicker = false },
            onPick = { name ->
                showVoicePicker = false
                readAloud.useVoice(name)
                settings.readAloudVoice = name
                say("Voice: $name")
            },
        )
    }

    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }

    if (showRecent) {
        RecentDialog(
            recent = homeEntries.take(kFileHistoryMaxRecent),
            onDismiss = { showRecent = false },
            onPick = { entry ->
                showRecent = false
                openEntry(entry)
            },
            onRemove = ::forgetEntry,
        )
    }

    if (showBookmarks) {
        BookmarksDialog(
            bookmarks = bookmarks,
            currentPath = tabs.getOrNull(activeIndex)?.path ?: "",
            onDismiss = { showBookmarks = false },
            onJump = { b ->
                showBookmarks = false
                if (b.path == tabs.getOrNull(activeIndex)?.path) {
                    goToPage(b.page)
                } else {
                    openPath(b.path)
                }
            },
            onRemove = { b ->
                settings.removeBookmark(b)
                bookmarks = settings.getBookmarks()
            },
        )
    }

    if (showSettings) {
        SettingsDialog(
            settings = settings,
            libraryColumns = libraryColumns,
            onLibraryColumns = {
                libraryColumns = it
                settings.libraryColumns = it
            },
            titleScale = libraryTitleScale,
            onTitleScale = {
                libraryTitleScale = it
                settings.libraryTitleScale = it
            },
            onDismiss = { showSettings = false },
        )
    }

    coverEditFor?.let { book ->
        com.sumatrapdf.reader.library.CoverEditor(
            model = libraryModel,
            book = book,
            onClose = { coverEditFor = null },
        )
    }
}

private fun zoomLabelFor(zoom: ZoomLevel, customZoom: Float): String = when (zoom) {
    ZoomLevel.FitPage -> "Fit Page"
    ZoomLevel.FitWidth -> "Fit Width"
    ZoomLevel.FitHeight -> "Fit Height"
    ZoomLevel.FitContent -> "Fit Content"
    ZoomLevel.Custom -> "${(customZoom * 100).toInt()}%"
}

@Composable
private fun SelectionActionBar(
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onSearch: () -> Unit,
    onTranslate: () -> Unit,
    onDismiss: () -> Unit,
    // The Win32 selection context menu (menuDefSelection) has
    // 10 items: 5 translate engines (Google, DeepL, Grok,
    // Claude, Codex) and 4 search engines (Google, Bing,
    // Wikipedia, Google Scholar) — the bar's "Search" and
    // "Translate" buttons only use the first one of each.
    // The "More" button pops the full context menu so the
    // user can reach the rest. (PORTING-STATUS §1.4.)
    onMore: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 6.dp,
        modifier = modifier.padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCopy) { Text("Copy") }
            TextButton(onClick = onShare) { Text("Share") }
            TextButton(onClick = onSearch) { Text("Search") }
            TextButton(onClick = onTranslate) { Text("Translate") }
            if (onMore != null) {
                TextButton(onClick = onMore) { Text("More…") }
            }
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFFFF3E0))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF6D4C41),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) {
            Text("Dismiss", color = Color(0xFF6D4C41))
        }
    }
}

@Composable
private fun PasswordDialog(
    name: String,
    wrongPassword: Boolean,
    anyTextFieldFocused: androidx.compose.runtime.MutableState<Boolean>,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var text by remember { mutableStateOf(TextFieldValue("")) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Password required") },
        text = {
            Column {
                Text("“$name” is protected.")
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text("Password") },
                    isError = wrongPassword,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .focusRequester(focusRequester)
                        .textFieldKeyHandler(
                            value = text,
                            onValueChange = { text = it },
                            anyTextFieldFocused = anyTextFieldFocused,
                            onSubmit = { onSubmit(text.text) },
                            onClose = onDismiss,
                        ),
                )
                if (wrongPassword) {
                    Text(
                        "That password was not accepted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(text.text) }) { Text("Open") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PropertiesDialog(
    rows: List<Pair<String, String>>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Properties") },
        text = {
            if (rows.isEmpty()) {
                Text("No properties are available for this document.")
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    rows.forEach { (label, value) ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(0.4f),
                            )
                            Text(
                                text = value,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(0.6f),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun VoicePickerDialog(
    voices: List<String>,
    current: String?,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change Voice") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                voices.forEach { name ->
                    TextButton(
                        onClick = { onPick(name) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = if (name == current) "✓  $name" else name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun GoToPageDialog(
    page: Int,
    pageCount: Int,
    anyTextFieldFocused: androidx.compose.runtime.MutableState<Boolean>,
    onDismiss: () -> Unit,
    onSubmit: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(TextFieldValue(page.toString(), TextRange(page.toString().length))) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val submit = {
        val p = text.text.toIntOrNull()?.coerceIn(1, pageCount)
        if (p != null) onSubmit(p)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to page") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { txt -> text = txt.copy(text = txt.text.filter { it.isDigit() }.take(6)) },
                singleLine = true,
                label = { Text("Page (1-$pageCount)") },
                modifier = Modifier
                    .focusRequester(focusRequester)
                    .textFieldKeyHandler(
                        value = text,
                        onValueChange = { txt -> text = txt.copy(text = txt.text.filter { it.isDigit() }.take(6)) },
                        anyTextFieldFocused = anyTextFieldFocused,
                        onSubmit = { submit() },
                        onClose = onDismiss,
                    ),
            )
        },
        confirmButton = {
            TextButton(onClick = { submit() }) { Text("Go") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CustomZoomDialog(
    currentPercent: Int,
    anyTextFieldFocused: androidx.compose.runtime.MutableState<Boolean>,
    onDismiss: () -> Unit,
    onSubmit: (Int) -> Unit,
) {
    val initial = currentPercent.toString()
    var text by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val submit = {
        val p = text.text.toIntOrNull()?.coerceIn(25, 800)
        if (p != null) onSubmit(p)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom Zoom") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { txt -> text = txt.copy(text = txt.text.filter { it.isDigit() }.take(4)) },
                singleLine = true,
                label = { Text("Zoom (25-800 %)") },
                modifier = Modifier
                    .focusRequester(focusRequester)
                    .textFieldKeyHandler(
                        value = text,
                        onValueChange = { txt -> text = txt.copy(text = txt.text.filter { it.isDigit() }.take(4)) },
                        anyTextFieldFocused = anyTextFieldFocused,
                        onSubmit = { submit() },
                        onClose = onDismiss,
                    ),
            )
        },
        confirmButton = {
            TextButton(onClick = { submit() }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("About SumatraPDF") },
        text = {
            Column {
                Text("SumatraPDF for Android", style = MaterialTheme.typography.titleMedium)
                Text("A native port of the SumatraPDF document reader.", modifier = Modifier.padding(top = 6.dp))
                Text("Engine: mupdf (AAR).", modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

@Composable
private fun RecentDialog(
    recent: List<FileHistoryEntry>,
    onDismiss: () -> Unit,
    onPick: (FileHistoryEntry) -> Unit,
    onRemove: (FileHistoryEntry) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Recent files") },
        text = {
            if (recent.isEmpty()) {
                Text("No recent files yet. Open a document to get started.")
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    recent.forEach { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = entry.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { onRemove(entry) }) { Text("Remove") }
                            TextButton(onClick = { onPick(entry) }) { Text("Open") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun BookmarksDialog(
    bookmarks: List<Bookmark>,
    currentPath: String,
    onDismiss: () -> Unit,
    onJump: (Bookmark) -> Unit,
    onRemove: (Bookmark) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bookmarks") },
        text = {
            if (bookmarks.isEmpty()) {
                Text("No bookmarks yet.")
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    bookmarks.forEach { b ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(b.displayName, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    b.path.substringAfterLast('/', b.path),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { onRemove(b) }) { Text("Remove") }
                            TextButton(onClick = { onJump(b) }) { Text("Open") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
