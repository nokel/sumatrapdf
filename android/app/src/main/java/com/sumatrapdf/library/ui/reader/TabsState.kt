package com.sumatrapdf.library.ui.reader

import android.content.Context
import com.sumatrapdf.library.data.RecentsStore
import com.sumatrapdf.library.data.SettingsState
import com.sumatrapdf.library.engine.DocumentEngine
import com.sumatrapdf.library.engine.DocumentEngineFactory
import com.sumatrapdf.library.engine.DocumentOutlineEntry
import com.sumatrapdf.library.engine.DocumentSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// One open document = one OpenTab. The reader host keeps a list of these
// in a TabsState and shows them as a horizontal TabRow. Each tab owns its
// own DocumentEngine (MuPDF session for supported formats, the stub for
// the rest), its own current page, its own per-tab settings, and its own
// parsed outline. Switching tabs means switching which OpenTab is the
// "active" one the reader is rendering.
data class OpenTab(
    val id: String,
    val spec: DocumentSpec,
    var engine: DocumentEngine?,
    var currentPage: Int = 0,
    var pageCount: Int = 0,
    var outline: List<DocumentOutlineEntry> = emptyList(),
    var passwordAsked: Boolean = false,
    var failedMessage: String? = null,
    var rotation: Int = 0,
    var openedAt: Long = 0L,
    var pageText: String = "",
    var hits: Map<Int, List<android.graphics.RectF>> = emptyMap(),
    var needle: String = "",
)

class TabsState(private val appContext: Context) {

    val context: Context get() = appContext

    private val _tabs = MutableStateFlow<List<OpenTab>>(emptyList())
    val tabs: StateFlow<List<OpenTab>> = _tabs.asStateFlow()

    private val _active = MutableStateFlow<String?>(null)
    val active: StateFlow<String?> = _active.asStateFlow()

    private val _busy = MutableStateFlow(0L)
    val busy: StateFlow<Long> = _busy.asStateFlow()

    fun activeTab(): OpenTab? = _tabs.value.firstOrNull { it.id == _active.value }

    // Open a new tab for the given spec. If the same document is already
    // open, just focus the existing tab.
    fun open(spec: DocumentSpec) {
        val recents = RecentsStore.get(appContext)
        val rotation = recents.rotationOf(spec.origin)
        val remembered = recents.pageOf(spec.origin)
        val existing = _tabs.value.firstOrNull { it.spec.origin == spec.origin }
        if (existing != null) {
            _active.value = existing.id
            return
        }
        val engine = DocumentEngineFactory.forSpec(appContext, spec)
        val tab = OpenTab(
            id = spec.id,
            spec = spec,
            engine = engine,
            currentPage = remembered,
            rotation = rotation,
            openedAt = System.currentTimeMillis(),
        )
        _tabs.value = _tabs.value + tab
        _active.value = tab.id
        _busy.value = System.nanoTime()
        engine.open(
            onReady = {
                val live = _tabs.value.firstOrNull { it.id == tab.id } ?: return@open
                live.engine = engine
                live.pageCount = engine.pageCount
                val target = live.currentPage.coerceIn(0, maxOf(0, engine.pageCount - 1))
                live.currentPage = target
                live.failedMessage = null
                recents.remember(spec.origin, spec.name, target, engine.pageCount)
                engine.outline { found -> live.outline = found }
                emit()
                loadPageText(live, target)
            },
            onPassword = {
                val live = _tabs.value.firstOrNull { it.id == tab.id } ?: return@open
                live.passwordAsked = true
                emit()
            },
            onFailed = { why ->
                val live = _tabs.value.firstOrNull { it.id == tab.id } ?: return@open
                live.failedMessage = why
                live.pageCount = 0
                emit()
            },
        )
    }

    fun close(id: String) {
        val tab = _tabs.value.firstOrNull { it.id == id } ?: return
        tab.engine?.close()
        val rest = _tabs.value.filter { it.id != id }
        _tabs.value = rest
        if (_active.value == id) {
            _active.value = rest.lastOrNull()?.id
        }
    }

    fun closeOthers(id: String) {
        val keep = _tabs.value.firstOrNull { it.id == id } ?: return
        for (t in _tabs.value) if (t.id != id) t.engine?.close()
        _tabs.value = listOf(keep)
        _active.value = keep.id
    }

    fun closeAll() {
        for (t in _tabs.value) t.engine?.close()
        _tabs.value = emptyList()
        _active.value = null
    }

    fun focus(id: String) {
        if (_tabs.value.any { it.id == id }) _active.value = id
    }

    fun goTo(id: String, page: Int) {
        val tab = tabOf(id) ?: return
        val target = page.coerceIn(0, maxOf(0, tab.pageCount - 1))
        tab.currentPage = target
        RecentsStore.get(appContext).setPage(tab.spec.origin, target)
        loadPageText(tab, target)
        emit()
    }

    fun next(id: String) = goTo(id, current(id) + 1)
    fun previous(id: String) = goTo(id, current(id) - 1)

    fun current(id: String): Int = tabOf(id)?.currentPage ?: 0

    fun rotate(id: String, delta: Int) {
        val tab = tabOf(id) ?: return
        tab.rotation = (tab.rotation + delta) % 360
        if (tab.rotation < 0) tab.rotation += 360
        RecentsStore.get(appContext).rememberRotation(tab.spec.origin, tab.rotation)
        emit()
    }

    fun password(id: String, secret: String) {
        val tab = tabOf(id) ?: return
        // MupdfEngine keeps the password in the open() retry path. The
        // simplest way to re-try is to close and re-open.
        tab.engine?.close()
        val engine = DocumentEngineFactory.forSpec(appContext, tab.spec)
        tab.engine = engine
        tab.passwordAsked = false
        _busy.value = System.nanoTime()
        emit()
        engine.open(
            onReady = {
                tab.engine = engine
                tab.pageCount = engine.pageCount
                tab.failedMessage = null
                emit()
            },
            onPassword = { tab.passwordAsked = true; emit() },
            onFailed = { why -> tab.failedMessage = why; tab.pageCount = 0; emit() },
        )
    }

    fun search(id: String, needle: String) {
        val tab = tabOf(id) ?: return
        if (needle.isBlank()) {
            tab.needle = ""
            tab.hits = emptyMap()
            tab.pageText = ""
            emit()
            return
        }
        tab.needle = needle
        tab.hits = emptyMap()
        val target = tab.currentPage
        tab.engine?.searchPage(target, needle) { boxes ->
            if (boxes.isNotEmpty()) {
                tab.hits = tab.hits + (target to boxes)
            }
            emit()
        }
    }

    fun nextSearchHit(id: String): Int? {
        val tab = tabOf(id) ?: return null
        if (tab.hits.isEmpty()) return null
        val keys = tab.hits.keys.sorted()
        val next = keys.firstOrNull { it > tab.currentPage } ?: keys.first()
        goTo(id, next)
        return next
    }

    fun previousSearchHit(id: String): Int? {
        val tab = tabOf(id) ?: return null
        if (tab.hits.isEmpty()) return null
        val keys = tab.hits.keys.sorted()
        val prev = keys.lastOrNull { it < tab.currentPage } ?: keys.last()
        goTo(id, prev)
        return prev
    }

    fun toggleLayout(tab: OpenTab?) {
        if (tab == null) return
        val s = com.sumatrapdf.library.data.SettingsStore.get(appContext)
        val next = when (s.state.value.pageLayout) {
            com.sumatrapdf.library.data.PageLayout.Single ->
                com.sumatrapdf.library.data.PageLayout.Facing
            com.sumatrapdf.library.data.PageLayout.Facing ->
                com.sumatrapdf.library.data.PageLayout.Book
            com.sumatrapdf.library.data.PageLayout.Book ->
                com.sumatrapdf.library.data.PageLayout.Single
        }
        s.setPageLayout(next)
    }

    fun toggleZoom(tab: OpenTab?) {
        if (tab == null) return
        val s = com.sumatrapdf.library.data.SettingsStore.get(appContext)
        val next = when (s.state.value.defaultZoom) {
            com.sumatrapdf.library.data.ZoomFit.FitPage ->
                com.sumatrapdf.library.data.ZoomFit.FitWidth
            com.sumatrapdf.library.data.ZoomFit.FitWidth ->
                com.sumatrapdf.library.data.ZoomFit.Actual
            com.sumatrapdf.library.data.ZoomFit.Actual ->
                com.sumatrapdf.library.data.ZoomFit.FitPage
        }
        s.setDefaultZoom(next)
    }

    fun zoomCycle(id: String) {
        toggleZoom(tabOf(id))
    }

    private fun loadPageText(tab: OpenTab, page: Int) {
        tab.engine?.pageText(page) { text ->
            tab.pageText = text
            emit()
        }
    }

    private fun tabOf(id: String): OpenTab? = _tabs.value.firstOrNull { it.id == id }

    private fun emit() { _tabs.value = _tabs.value }
}

// The Composable-side helpers for the reader host. Settings come from the
// SettingsStore, not the tabs state, so changing theme in Settings
// immediately repaints the reader.
data class ReaderPrefs(
    val layout: com.sumatrapdf.library.data.PageLayout,
    val defaultZoom: com.sumatrapdf.library.data.ZoomFit,
    val nightInvert: Boolean,
    val showLinks: Boolean,
    val keepAwake: Boolean,
    val showScrollbar: Boolean,
    val pageSpacingDp: Int,
    val textSizePt: Float,
    val invertColorsNight: Boolean,
)

fun readerPrefs(settings: SettingsState): ReaderPrefs = ReaderPrefs(
    layout = settings.pageLayout,
    defaultZoom = settings.defaultZoom,
    nightInvert = settings.invertColorsNight,
    showLinks = settings.showLinks,
    keepAwake = settings.keepAwake,
    showScrollbar = settings.scrollbar,
    pageSpacingDp = settings.pageSpacingDp,
    textSizePt = settings.textSizePt,
    invertColorsNight = settings.invertColorsNight,
)
