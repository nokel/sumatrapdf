package com.sumatrapdf.reader

import android.graphics.Bitmap
import android.util.Log
import androidx.collection.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// Renders the current page (or pages) via the engine, with the Win32
// SumatraPDF display model:
//
//   * Zoom is a property of the document, not of the drawn bitmap:
//     each page occupies naturalSize * zoom pixels and the viewport
//     scrolls over that canvas, as DisplayModel::Relayout does.
//   * A page too wide (or, outside continuous mode, too tall) for the
//     viewport is panned by dragging; when it fits, the drag is
//     disabled, matching DisplayModel::CanScrollLeft/Right.
//   * Above the single-bitmap budget the page is rendered as tiles at
//     the true zoom over a stretched low-resolution base, which is
//     what RenderCache.cpp does with TilePosition.
//   * The display layout (Single / Facing / Book) decides how many
//     pages are visible at once and how they're positioned; the
//     `continuous` flag decides whether the document is laid out
//     vertically (continuous scrolling) or as a single window per
//     page-set (no scrolling).
//
// searchHits are drawn as semi-transparent yellow rectangles on top
// of the bitmap; searchCurrentHit (zero-based within the visible
// page) is drawn with full opacity.
@Composable
fun PageSurface(
    engine: DocumentEngine?,
    page: Int,
    pageCount: Int,
    displayMode: DisplayMode,
    continuous: Boolean,
    zoom: ZoomLevel,
    customZoom: Float,
    rotation: Int,
    renderEpoch: Int = 0,
    searchHits: List<Quad> = emptyList(),
    searchCurrentHit: Int = -1,
    linksByPage: Map<Int, List<PageLink>> = emptyMap(),
    selection: Selection? = null,
    invertColors: Boolean = false,
    mangaMode: Boolean = false,
    onTap: (Tap) -> Unit,
    onEdgeClick: (PageEdge) -> Unit,
    onPagePoint: (PagePoint) -> Boolean = { false },
    onSelectDrag: (PagePoint, PagePoint) -> Unit = { _, _ -> },
    onSelectEnd: () -> Unit = {},
    onLongPress: (androidx.compose.ui.geometry.Offset) -> Unit = {},
    onVisiblePageChange: (Int) -> Unit = {},
    onPinchZoom: (Float, Float) -> Unit = { _, _ -> },
    onEffectiveScale: (Float) -> Unit = {},
) {
    val bg = if (isSystemInDarkTheme()) kCanvasBgDark else kCanvasBgLight
    val placements = remember { PagePlacements() }
    var scrollX by remember { mutableFloatStateOf(0f) }
    var scrollY by remember { mutableFloatStateOf(0f) }

    // The zoom the bitmaps are rendered at lags the zoom the page is
    // laid out at. A pinch changes the layout zoom on every pointer
    // frame; re-rendering per frame means a full mupdf render (and a
    // fresh cache key, so a blank page) 60 times a second, all of it
    // queued behind the per-document lock. Win32 has the same split:
    // DisplayModel::Relayout moves the page immediately and RenderCache
    // paints whatever bitmap it already has, stretched, until the
    // background render for the new zoom arrives. So while a pinch is
    // in flight the layout scale moves and the render scale holds, and
    // the existing bitmap is simply drawn to the new rect.
    var zoomGesture by remember { mutableStateOf(false) }
    var renderZoomState by remember { mutableStateOf(RenderZoom(zoom, customZoom, false)) }
    LaunchedEffect(zoom, customZoom, zoomGesture) {
        if (zoomGesture) {
            renderZoomState = renderZoomState.copy(deferred = true)
            delay(kZoomSettleMs)
        }
        renderZoomState = RenderZoom(zoom, customZoom, false)
    }

    // Render cache. The Compose-level `remember(page, scale, ...)`
    // inside producePageBitmap is a per-PageSlot cache: when
    // LazyColumn removes a slot from composition (the user
    // scrolled past it), the bitmap is forgotten. This LRU
    // cache lives at the PageSurface level so it survives
    // across slot lifecycles. The key is
    // (docHandle, page, scale, rotation) so different
    // documents and different zoom levels don't collide.
    val renderCache = remember {
        val budget = (Runtime.getRuntime().maxMemory() / 4)
            .coerceIn(24L * 1024 * 1024, 128L * 1024 * 1024)
            .toInt()
        object : LruCache<RenderCacheKey, Bitmap>(budget) {
            override fun sizeOf(key: RenderCacheKey, value: Bitmap): Int =
                value.byteCount.coerceAtLeast(1)
        }
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
            .clipToBounds(),
    ) {
        val viewportW = with(LocalDensity.current) { maxWidth.toPx() }
        val viewportH = with(LocalDensity.current) { maxHeight.toPx() }
        if (engine == null || !engine.isOpen || pageCount <= 0) {
            EmptyState()
            return@BoxWithConstraints
        }

        // Prefetch the next/prev kPrefetchPages pages at the
        // active scale/rotation, populating the render cache so
        // the user lands on a warm bitmap when they scroll. The
        // engine's tryRenderPage uses tryLock so prefetch never
        // blocks the active page render (it yields). The
        // LaunchedEffect re-runs (cancelling the previous pass)
        // whenever the active page / zoom / rotation / doc
        // changes, so a navigation, doc switch or zoom change
        // automatically restarts prefetch against the new state.
        // (PORTING-STATUS §3.7.)
        LaunchedEffect(page, renderZoomState, rotation, viewportW, viewportH, engine?.activeHandle) {
            val activeEngine = engine ?: return@LaunchedEffect
            if (!activeEngine.isOpen || pageCount <= 0) return@LaunchedEffect
            if (renderZoomState.deferred) return@LaunchedEffect
            val activeSize = activeEngine.pageSize(page) ?: return@LaunchedEffect
            val activeScale = resolveScale(
                renderZoomState.zoom, renderZoomState.customZoom, activeSize.first, activeSize.second,
                viewportW, viewportH, slotIsContinuousColumn = false,
            )
            val docHandle = activeEngine.activeHandle
            val prefetchScale = renderScaleFor(activeSize.first, activeSize.second, activeScale)
            val scaleBits = prefetchScale.toRawBits()
            val toPrefetch = buildList {
                for (off in 1..kPrefetchPages) {
                    val ahead = page + off
                    if (ahead in 0 until pageCount) add(ahead)
                }
                for (off in 1..kPrefetchPages) {
                    val behind = page - off
                    if (behind in 0 until pageCount) add(behind)
                }
            }
            for (p in toPrefetch) {
                val key = RenderCacheKey(docHandle, p, scaleBits, rotation)
                if (renderCache.get(key) != null) continue
                val bmp = withContext(Dispatchers.IO) {
                    try {
                        activeEngine.tryRenderPage(p, prefetchScale, rotation)?.also {
                            it.prepareToDraw()
                        }
                    } catch (t: Throwable) {
                        Log.w("SumatraPage", "prefetch($p, $activeScale, $rotation) failed: ${t.message}")
                        null
                    }
                }
                if (bmp != null) renderCache.put(key, bmp)
            }
        }

        // Edge tap lives at the outermost layer so it works whether or
        // not we're scrolling / panning. 8% of the viewport width on
        // each side.
        val activeScale = engine.pageSize(page)?.let { (w, h) ->
            resolveScale(zoom, customZoom, w, h, viewportW, viewportH, slotIsContinuousColumn = false)
        } ?: 1f
        val liveScale = rememberUpdatedState(activeScale)

        // Only the fit modes need reporting: stepZoom reads this to find
        // the percentage a Fit view is actually at. In Custom zoom the
        // caller already owns the number, and pushing it back on every
        // pinch frame would recompose the whole screen a second time.
        LaunchedEffect(activeScale, zoom) {
            if (zoom != ZoomLevel.Custom) onEffectiveScale(activeScale)
        }

        val activeDisplay = slotSizePx(engine, page, zoom, customZoom, rotation, viewportW, viewportH)
        val slotViewportW = if (displayMode == DisplayMode.SinglePage) viewportW else viewportW / 2f
        val overflowX = (((activeDisplay?.first ?: 0f) - slotViewportW) / 2f).coerceAtLeast(0f)
        val overflowY = if (continuous) 0f
                        else (((activeDisplay?.second ?: 0f) - viewportH) / 2f).coerceAtLeast(0f)
        val panX = scrollX.coerceIn(-overflowX, overflowX)
        val panY = scrollY.coerceIn(-overflowY, overflowY)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { placements.surfaceOrigin = it.positionInWindow() }
                .draggable(
                    orientation = Orientation.Horizontal,
                    enabled = overflowX > 0.5f,
                    state = rememberDraggableState { delta ->
                        scrollX = (panX - delta).coerceIn(-overflowX, overflowX)
                    },
                )
                .draggable(
                    orientation = Orientation.Vertical,
                    enabled = overflowY > 0.5f,
                    state = rememberDraggableState { delta ->
                        scrollY = (panY - delta).coerceIn(-overflowY, overflowY)
                    },
                )
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var pinching = false
                        try {
                            do {
                                val event = awaitPointerEvent()
                                if (event.changes.count { it.pressed } >= 2) {
                                    val factor = event.calculateZoom()
                                    if (factor != 1f) {
                                        if (!pinching) {
                                            pinching = true
                                            zoomGesture = true
                                        }
                                        onPinchZoom(factor, liveScale.value)
                                    }
                                    if (pinching) {
                                        event.changes.forEach { if (it.pressed) it.consume() }
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                        } finally {
                            if (pinching) zoomGesture = false
                        }
                    }
                }
                .pointerInput(displayMode, continuous, page, rotation, linksByPage) {
                    detectTapGestures(
                        onTap = { off ->
                            val hit = placements.toPagePoint(off, rotation)
                            if (hit != null && onPagePoint(hit)) return@detectTapGestures
                            val w = size.width.toFloat()
                            when {
                                off.x < w * 0.08f -> onEdgeClick(PageEdge.Left)
                                off.x > w * 0.92f -> onEdgeClick(PageEdge.Right)
                                else -> onTap(Tap.Single)
                            }
                        },
                        onLongPress = { off -> onLongPress(off) },
                        onDoubleTap = { onTap(Tap.Double) },
                    )
                }
                .pointerInput(displayMode, continuous, page, rotation) {
                    var anchor: PagePoint? = null
                    detectDragGesturesAfterLongPress(
                        onDragStart = { off ->
                            anchor = placements.toPagePoint(off, rotation)
                            val a = anchor
                            if (a != null) onSelectDrag(a, a)
                        },
                        onDrag = { change, _ ->
                            val a = anchor ?: return@detectDragGesturesAfterLongPress
                            val here = placements.toPagePoint(change.position, rotation)
                                ?: return@detectDragGesturesAfterLongPress
                            if (here.page != a.page) return@detectDragGesturesAfterLongPress
                            onSelectDrag(a, here)
                            change.consume()
                        },
                        onDragEnd = { onSelectEnd() },
                        onDragCancel = { anchor = null },
                    )
                },
        ) {
            if (continuous) {
                // Continuous: all pages stacked vertically, scrollable
                // via LazyColumn so only visible pages are composed. A
                // 1000-page PDF used to build 1000 PageSlot composables
                // in a Column; now only the ones in the viewport (plus
                // a small prefetch buffer) are realised. The first
                // visible item is the current `page` so opening a doc
                // jumps to the right place.
                val pagesToShow = (0 until pageCount).toList()
                val lazyState = rememberLazyListState(
                    initialFirstVisibleItemIndex = page.coerceAtLeast(0),
                )
                fun rowOf(p: Int): Int = when (displayMode) {
                    DisplayMode.SinglePage -> p
                    else -> p / 2
                }
                fun pageOf(row: Int): Int = when (displayMode) {
                    DisplayMode.SinglePage -> row
                    else -> (row * 2 - 1).coerceAtLeast(0)
                }

                val visibleRow by remember(displayMode) {
                    derivedStateOf {
                        val info = lazyState.layoutInfo
                        val items = info.visibleItemsInfo
                        if (items.isEmpty()) return@derivedStateOf -1
                        val top = info.viewportStartOffset
                        val bottom = info.viewportEndOffset
                        items.maxByOrNull { item ->
                            val visible = min(item.offset + item.size, bottom) -
                                max(item.offset, top)
                            visible.coerceAtLeast(0)
                        }?.index ?: -1
                    }
                }

                var jumpingTo by remember(displayMode) { mutableIntStateOf(-1) }

                LaunchedEffect(visibleRow, displayMode) {
                    if (visibleRow >= 0 && jumpingTo < 0) {
                        val p = pageOf(visibleRow).coerceIn(0, pageCount - 1)
                        if (p != page) onVisiblePageChange(p)
                    }
                }

                LaunchedEffect(page, displayMode) {
                    if (page in 0 until pageCount) {
                        val target = rowOf(page)
                        if (target != visibleRow && !lazyState.isScrollInProgress) {
                            jumpingTo = target
                            try {
                                lazyState.scrollToItem(target)
                            } catch (_: Throwable) {
                            }
                            withTimeoutOrNull(2000) {
                                snapshotFlow { lazyState.firstVisibleItemIndex }.first { it == target }
                            }
                            jumpingTo = -1
                        }
                    }
                }
                LazyColumn(
                    state = lazyState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = kWindowMarginY),
                    verticalArrangement = Arrangement.spacedBy(kPageSpacingY),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    when (displayMode) {
                        DisplayMode.SinglePage -> {
                            items(pagesToShow, key = { it }) { p ->
                                PageSlot(
                                    engine = engine,
                                    page = p,
                                    viewportW = viewportW,
                                    viewportH = viewportH,
                                    zoom = zoom,
                                    customZoom = customZoom,
                                    renderZoom = renderZoomState,
                                    rotation = rotation,
                                    scrollX = panX,
                                    scrollY = panY,
                                    renderEpoch = renderEpoch, cache = renderCache,
                                    placements = placements,
                                    modifier = Modifier,
                                    searchHits = if (p == page) searchHits else emptyList(),
                                    searchCurrentHit = if (p == page) searchCurrentHit else -1,
                                    links = linksByPage[p].orEmpty(),
                                    selection = selection?.takeIf { it.page == p },
                                    invertColors = invertColors,
                                    inContinuousColumn = true,
                                )
                            }
                        }
                        DisplayMode.Facing -> {
                            // Pairs: (0, 1), (2, 3), ... page 0 alone.
                            val rows = chunkedPairs(pagesToShow, firstAlone = true, reverseWithinPair = mangaMode)
                            items(rows.size, key = { i -> "facing-$i" }) { i ->
                                val (left, right) = rows[i]
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (left >= 0) {
                                        PageSlot(
                                            engine = engine, page = left, viewportW = viewportW,
                                            viewportH = viewportH, zoom = zoom, customZoom = customZoom,
                                            renderZoom = renderZoomState,
                                            rotation = rotation,
                                            scrollX = panX,
                                            scrollY = panY,
                                            renderEpoch = renderEpoch, cache = renderCache, placements = placements,
                                            modifier = Modifier.weight(1f),
                                            searchHits = if (left == page) searchHits else emptyList(),
                                            searchCurrentHit = if (left == page) searchCurrentHit else -1,
                                            links = linksByPage[left].orEmpty(),
                                            selection = selection?.takeIf { it.page == left },
                                            invertColors = invertColors,
                                            inContinuousColumn = true,
                                        )
                                    } else {
                                        Box(Modifier.weight(1f))
                                    }
                                    if (right >= 0) {
                                        PageSlot(
                                            engine = engine, page = right, viewportW = viewportW,
                                            viewportH = viewportH, zoom = zoom, customZoom = customZoom,
                                            renderZoom = renderZoomState,
                                            rotation = rotation,
                                            scrollX = panX,
                                            scrollY = panY,
                                            renderEpoch = renderEpoch, cache = renderCache, placements = placements,
                                            modifier = Modifier.weight(1f),
                                            searchHits = if (right == page) searchHits else emptyList(),
                                            searchCurrentHit = if (right == page) searchCurrentHit else -1,
                                            links = linksByPage[right].orEmpty(),
                                            selection = selection?.takeIf { it.page == right },
                                            invertColors = invertColors,
                                            inContinuousColumn = true,
                                        )
                                    } else {
                                        Box(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                        DisplayMode.BookView -> {
                            // Pairs: (0, _) cover, (1, 2), (3, 4), ...  page 1 is on the right of the cover.
                            val rows = mutableListOf<Pair<Int, Int>>()
                            rows.add(0 to -1)
                            var i = 1
                            while (i < pageCount) {
                                val a = i
                                val b = if (i + 1 < pageCount) i + 1 else -1
                                if (mangaMode && b >= 0) rows.add(b to a) else rows.add(a to b)
                                i += 2
                            }
                            items(rows.size, key = { i -> "book-$i" }) { idx ->
                                val (left, right) = rows[idx]
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (left == 0) {
                                        BookCover(engine.path ?: "", Modifier.weight(1f))
                                    } else if (left >= 0) {
                                        PageSlot(
                                            engine = engine, page = left, viewportW = viewportW,
                                            viewportH = viewportH, zoom = zoom, customZoom = customZoom,
                                            renderZoom = renderZoomState,
                                            rotation = rotation,
                                            scrollX = panX,
                                            scrollY = panY,
                                            renderEpoch = renderEpoch, cache = renderCache, placements = placements,
                                            modifier = Modifier.weight(1f),
                                            searchHits = if (left == page) searchHits else emptyList(),
                                            searchCurrentHit = if (left == page) searchCurrentHit else -1,
                                            links = linksByPage[left].orEmpty(),
                                            selection = selection?.takeIf { it.page == left },
                                            invertColors = invertColors,
                                            inContinuousColumn = true,
                                        )
                                    }
                                    if (right >= 0) {
                                        PageSlot(
                                            engine = engine, page = right, viewportW = viewportW,
                                            viewportH = viewportH, zoom = zoom, customZoom = customZoom,
                                            renderZoom = renderZoomState,
                                            rotation = rotation,
                                            scrollX = panX,
                                            scrollY = panY,
                                            renderEpoch = renderEpoch, cache = renderCache, placements = placements,
                                            modifier = Modifier.weight(1f),
                                            searchHits = if (right == page) searchHits else emptyList(),
                                            searchCurrentHit = if (right == page) searchCurrentHit else -1,
                                            links = linksByPage[right].orEmpty(),
                                            selection = selection?.takeIf { it.page == right },
                                            invertColors = invertColors,
                                            inContinuousColumn = true,
                                        )
                                    } else if (left == 0) {
                                        Box(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Non-continuous: only the current page-set, no scroll.
                when (displayMode) {
                    DisplayMode.SinglePage -> {
                        PageSlot(
                            engine = engine, page = page, viewportW = viewportW,
                            viewportH = viewportH, zoom = zoom, customZoom = customZoom,
                                            renderZoom = renderZoomState,
                            rotation = rotation,
                            scrollX = panX,
                            scrollY = panY,
                            renderEpoch = renderEpoch, cache = renderCache, placements = placements,
                            modifier = Modifier.fillMaxSize(),
                            searchHits = searchHits,
                            searchCurrentHit = searchCurrentHit,
                            links = linksByPage[page].orEmpty(),
                            selection = selection?.takeIf { it.page == page },
                            invertColors = invertColors,
                        )
                    }
                    DisplayMode.Facing -> {
                        // LTR: left = page-1, right = page. Manga (R2L):
                        // the higher-numbered page sits on the left, so
                        // left = page and right = page-1. The current
                        // page is always `page`, so the search hits and
                        // edge-tap mapping swap sides with it.
                        val pair = if (mangaMode) page to (if (page == 0) -1 else page - 1)
                                   else (if (page == 0) -1 else page - 1) to page
                        val (left, right) = pair
                        Row(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (left >= 0) {
                                PageSlot(
                                    engine = engine, page = left, viewportW = viewportW,
                                    viewportH = viewportH, zoom = zoom, customZoom = customZoom,
                                            renderZoom = renderZoomState,
                                    rotation = rotation,
                                    scrollX = panX,
                                    scrollY = panY,
                                    renderEpoch = renderEpoch, cache = renderCache, placements = placements,
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                    searchHits = if (left == page) searchHits else emptyList(),
                                    searchCurrentHit = if (left == page) searchCurrentHit else -1,
                                    links = linksByPage[left].orEmpty(),
                                    selection = selection?.takeIf { it.page == left },
                                    invertColors = invertColors,
                                )
                            } else {
                                Box(Modifier.weight(1f))
                            }
                            if (right >= 0) {
                                PageSlot(
                                    engine = engine, page = right, viewportW = viewportW,
                                    viewportH = viewportH, zoom = zoom, customZoom = customZoom,
                                                renderZoom = renderZoomState,
                                    rotation = rotation,
                                    scrollX = panX,
                                    scrollY = panY,
                                    renderEpoch = renderEpoch, cache = renderCache, placements = placements,
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                    searchHits = if (right == page) searchHits else emptyList(),
                                    searchCurrentHit = if (right == page) searchCurrentHit else -1,
                                    links = linksByPage[right].orEmpty(),
                                    selection = selection?.takeIf { it.page == right },
                                    invertColors = invertColors,
                                )
                            } else {
                                Box(Modifier.weight(1f))
                            }
                        }
                    }
                    DisplayMode.BookView -> {
                        // LTR: cover/left = page-1, right = page. Manga (R2L):
                        // left = page, right = page-1. Search hits ride the
                        // current page, which is always `page`.
                        val pair = if (page == 0) {
                            if (mangaMode) (0 to -1) else (0 to -1)
                        } else {
                            if (mangaMode) page to (page - 1) else (page - 1) to page
                        }
                        val (leftPage, rightPage) = pair
                        Row(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (leftPage == 0 && rightPage == -1) {
                                BookCover(engine.path ?: "", Modifier.weight(1f).fillMaxHeight())
                            } else {
                                if (leftPage >= 0) {
                                    PageSlot(
                                        engine = engine, page = leftPage, viewportW = viewportW,
                                        viewportH = viewportH, zoom = zoom, customZoom = customZoom,
                                                renderZoom = renderZoomState,
                                        rotation = rotation,
                                        scrollX = panX,
                                        scrollY = panY,
                                        renderEpoch = renderEpoch, cache = renderCache, placements = placements,
                                        modifier = Modifier.weight(1f).fillMaxHeight(),
                                        searchHits = if (leftPage == page) searchHits else emptyList(),
                                        searchCurrentHit = if (leftPage == page) searchCurrentHit else -1,
                                        links = linksByPage[leftPage].orEmpty(),
                                        selection = selection?.takeIf { it.page == leftPage },
                                        invertColors = invertColors,
                                    )
                                }
                                if (rightPage >= 0) {
                                    PageSlot(
                                        engine = engine, page = rightPage, viewportW = viewportW,
                                        viewportH = viewportH, zoom = zoom, customZoom = customZoom,
                                                renderZoom = renderZoomState,
                                        rotation = rotation,
                                        scrollX = panX,
                                        scrollY = panY,
                                        renderEpoch = renderEpoch, cache = renderCache, placements = placements,
                                        modifier = Modifier.weight(1f).fillMaxHeight(),
                                        searchHits = if (rightPage == page) searchHits else emptyList(),
                                        searchCurrentHit = if (rightPage == page) searchCurrentHit else -1,
                                        links = linksByPage[rightPage].orEmpty(),
                                        selection = selection?.takeIf { it.page == rightPage },
                                        invertColors = invertColors,
                                    )
                                } else {
                                    Box(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

enum class Tap { Single, Double }
enum class PageEdge { Left, Right }

data class PagePoint(val page: Int, val x: Float, val y: Float)

private const val kPxPerPoint = 2f           // baseline dpi factor (144 dpi)
private const val kMinRenderScale = 0.02f
private const val kMinCustomZoom = kZoomMin / 100f
private const val kMaxCustomZoom = kZoomMax / 100f
private const val kMaxBitmapDim = 4096f
private const val kMaxBitmapPixels = 8_000_000f
private const val kMinTilePx = 512
private const val kMaxTilePx = 1600
private const val kMaxLayoutPx = 200_000f
// How long the zoom has to hold still mid-pinch before the bitmaps are
// re-rendered at the new scale. Short enough that pausing a pinch
// sharpens the page, long enough that a moving pinch never queues one.
private const val kZoomSettleMs = 220L
// Prefetch the next/prev N pages around the active page so the user
// lands on a warm cache when they scroll or arrow-key through the
// document. Picked to match what fits comfortably in a viewport
// (SinglePage: 3 ahead + 3 behind; continuous mode: 1-2 ahead is
// usually visible). (PORTING-STATUS §3.7.)
private const val kPrefetchPages = 3

private val kCanvasBgLight = Color(0xFFF2F2F2)
private val kCanvasBgDark = Color(0xFF000000)
private val kPageEdgeLight = Color(0xFFC0C0C0)
private val kPageEdgeDark = Color(0xFF374151)
private val kPageSheetColor = Color(0xFFFFFFFF)
private val kPageSpacingY = 4.dp
private val kWindowMarginY = 5.dp
private val kHitColor = Color(0xFFFFEB3B)
private val kHitCurrentColor = Color(0xFFFFA000)
private val kSelectionColor = Color(0xFF2C5AA0)
private val kLinkColor = Color(0xFF2C5AA0)

private class SlotPlacement(
    val originInWindow: Offset,
    val displayWidth: Float,
    val displayHeight: Float,
    val bounds: PageBounds,
    val displayScale: Float,
)

// Where each visible page bitmap ended up on screen, so a tap can be
// turned back into a point on the page. Only rotation 0 is invertible
// here: the render matrix rotates inside mupdf, and this mapping does
// not model that, so callers get null for a rotated view rather than a
// coordinate that is quietly wrong.
private class PagePlacements {
    var surfaceOrigin: Offset = Offset.Zero
    private val slots = mutableMapOf<Int, SlotPlacement>()

    fun put(page: Int, placement: SlotPlacement) {
        slots[page] = placement
    }

    fun toPagePoint(offsetInSurface: Offset, rotation: Int): PagePoint? {
        if (rotation != 0) return null
        val window = offsetInSurface + surfaceOrigin
        for ((page, s) in slots) {
            val left = s.originInWindow.x
            val top = s.originInWindow.y
            val right = left + s.displayWidth
            val bottom = top + s.displayHeight
            if (window.x < left || window.x > right || window.y < top || window.y > bottom) continue
            val pxPerPoint = kPxPerPoint * s.displayScale
            if (pxPerPoint <= 0f) continue
            return PagePoint(
                page = page,
                x = (window.x - left) / pxPerPoint + s.bounds.x0,
                y = (window.y - top) / pxPerPoint + s.bounds.y0,
            )
        }
        return null
    }
}

// Decide the layout scale for a page.  `zoom` selects the fit mode;
// the returned scale is the absolute scale the page is laid out at.
// For Custom zoom, customZoom (1.0 = 100%) is the absolute scale.
private fun resolveScale(
    zoom: ZoomLevel,
    customZoom: Float,
    naturalW: Float,
    naturalH: Float,
    viewportW: Float,
    viewportH: Float,
    slotIsContinuousColumn: Boolean,
): Float {
    val s = when (zoom) {
        ZoomLevel.FitPage -> {
            val vw = viewportW * 0.98f
            val vh = viewportH * 0.98f
            min(vw / (naturalW * kPxPerPoint), vh / (naturalH * kPxPerPoint))
        }
        ZoomLevel.FitWidth -> {
            (viewportW * 0.98f) / (naturalW * kPxPerPoint)
        }
        ZoomLevel.FitHeight -> {
            (viewportH * 0.98f) / (naturalH * kPxPerPoint)
        }
        ZoomLevel.FitContent -> {
            val vw = viewportW * 0.98f
            val vh = viewportH * 0.98f
            min(vw / (naturalW * kPxPerPoint), vh / (naturalH * kPxPerPoint))
        }
        ZoomLevel.Custom -> customZoom
    }
    val tallest = kMaxLayoutPx / (max(naturalW, naturalH) * kPxPerPoint)
    return s.coerceIn(kMinCustomZoom, min(kMaxCustomZoom, tallest))
}

private fun renderScaleFor(naturalW: Float, naturalH: Float, layoutScale: Float): Float {
    val w = naturalW * kPxPerPoint * layoutScale
    val h = naturalH * kPxPerPoint * layoutScale
    if (w <= 0f || h <= 0f) return layoutScale
    var factor = 1f
    if (w > kMaxBitmapDim) factor = min(factor, kMaxBitmapDim / w)
    if (h > kMaxBitmapDim) factor = min(factor, kMaxBitmapDim / h)
    val pixels = (w * factor) * (h * factor)
    if (pixels > kMaxBitmapPixels) factor *= sqrt(kMaxBitmapPixels / pixels)
    return (layoutScale * factor).coerceAtLeast(kMinRenderScale)
}

private fun slotSizePx(
    engine: DocumentEngine,
    page: Int,
    zoom: ZoomLevel,
    customZoom: Float,
    rotation: Int,
    viewportW: Float,
    viewportH: Float,
): Pair<Float, Float>? {
    val natural = engine.pageSize(page) ?: return null
    val scale = resolveScale(
        zoom, customZoom, natural.first, natural.second,
        viewportW, viewportH, slotIsContinuousColumn = false,
    )
    val w = natural.first * kPxPerPoint * scale
    val h = natural.second * kPxPerPoint * scale
    val quarterTurn = (((rotation % 360) + 360) % 360) % 180 != 0
    return if (quarterTurn) h to w else w to h
}

// The key for the per-document render cache. The
// fields are the (docHandle, page, scale, rotation) tuple
// that uniquely identifies a render. Bitmap identity is
// not part of the key because mupdf is deterministic for
// the same input.
data class RenderCacheKey(
    val docHandle: Int,
    val page: Int,
    val scaleBits: Int,    // scale.toRawBits() — round-trip stable
    val rotation: Int,
    val tileCol: Int = -1,
    val tileRow: Int = -1,
)

private class TileImage(
    val col: Int,
    val row: Int,
    val width: Int,
    val height: Int,
    val image: ImageBitmap?,
)

// The zoom the bitmaps are rendered at, which trails the zoom the page
// is laid out at while a pinch is in flight. `deferred` is true for the
// span of the gesture: tiles that are already in the cache still draw,
// but no new render is started until the zoom settles.
data class RenderZoom(
    val zoom: ZoomLevel,
    val customZoom: Float,
    val deferred: Boolean,
)

@Composable
private fun PageSlot(
    engine: DocumentEngine,
    page: Int,
    viewportW: Float,
    viewportH: Float,
    zoom: ZoomLevel,
    customZoom: Float,
    renderZoom: RenderZoom,
    rotation: Int,
    scrollX: Float = 0f,
    scrollY: Float = 0f,
    renderEpoch: Int = 0,
    placements: PagePlacements? = null,
    modifier: Modifier = Modifier,
    searchHits: List<Quad> = emptyList(),
    searchCurrentHit: Int = -1,
    links: List<PageLink> = emptyList(),
    selection: Selection? = null,
    invertColors: Boolean = false,
    cache: LruCache<RenderCacheKey, Bitmap>? = null,
    inContinuousColumn: Boolean = false,
) {
    val naturalSize = engine.pageSize(page)
    val bounds = engine.pageBounds(page)
    val density = LocalDensity.current

    val displayPx = slotSizePx(engine, page, zoom, customZoom, rotation, viewportW, viewportH)
    val displayW = displayPx?.first ?: viewportW
    val displayH = displayPx?.second ?: viewportH
    val displayHDp = with(density) { displayH.toDp() }

    val outerModifier = modifier
        .then(
            if (inContinuousColumn) Modifier.fillMaxWidth().height(displayHDp)
            else Modifier.fillMaxSize(),
        )
        .clipToBounds()

    if (naturalSize == null || bounds == null) {
        Box(modifier = outerModifier, contentAlignment = Alignment.Center) {
            Text(
                text = "Page ${page + 1} (unavailable)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val scale = resolveScale(
        zoom, customZoom, naturalSize.first, naturalSize.second,
        viewportW, viewportH, slotIsContinuousColumn = false,
    )
    // What the bitmaps are actually rendered at. Equal to `scale` once
    // the zoom settles; during a pinch it holds the pre-gesture zoom and
    // everything below is drawn stretched by `stretch`.
    val bitmapScale = resolveScale(
        renderZoom.zoom, renderZoom.customZoom, naturalSize.first, naturalSize.second,
        viewportW, viewportH, slotIsContinuousColumn = false,
    )
    val stretch = if (bitmapScale > 0f) scale / bitmapScale else 1f
    val renderScale = renderScaleFor(naturalSize.first, naturalSize.second, bitmapScale)
    val bmp = producePageBitmap(engine, page, renderScale, rotation, renderEpoch, cache)
    val baseImage = bmp?.let { remember(it) { it.asImageBitmap() } }
    val needsTiles = renderScale < bitmapScale * 0.999f

    val slotOrigin = remember(page) { mutableStateOf(Offset.Zero) }
    val slotWidth = remember(page) { mutableFloatStateOf(viewportW) }
    val slotHeight = remember(page) { mutableFloatStateOf(displayH) }

    // Tile geometry lives in the bitmap's own space, so a tile keeps its
    // identity (and its cache entry) while the layout zoom moves under
    // it; the draw multiplies back up by `stretch`.
    val tiledW = displayW / stretch
    val tiledH = displayH / stretch
    val tileW = viewportW.roundToInt().coerceIn(kMinTilePx, kMaxTilePx)
    val tileH = viewportH.roundToInt().coerceIn(kMinTilePx, kMaxTilePx)
    val tiles by remember(
        page, bitmapScale, rotation, tiledW, tiledH, tileW, tileH, needsTiles,
        scrollX, scrollY, stretch,
    ) {
        derivedStateOf {
            if (!needsTiles) return@derivedStateOf emptyList<Pair<Int, Int>>()
            val left = pageLeftIn(slotWidth.floatValue, displayW, scrollX) + slotOrigin.value.x
            val top = pageTopIn(
                slotHeight.floatValue, displayH, scrollY, inContinuousColumn,
            ) + slotOrigin.value.y
            val x0 = ((-left).coerceIn(0f, displayW)) / stretch
            val x1 = ((-left + viewportW).coerceIn(0f, displayW)) / stretch
            val y0 = ((-top).coerceIn(0f, displayH)) / stretch
            val y1 = ((-top + viewportH).coerceIn(0f, displayH)) / stretch
            if (x1 <= x0 || y1 <= y0) return@derivedStateOf emptyList<Pair<Int, Int>>()
            buildList {
                for (row in (y0 / tileH).toInt()..((y1 - 1f) / tileH).toInt()) {
                    for (col in (x0 / tileW).toInt()..((x1 - 1f) / tileW).toInt()) add(col to row)
                }
            }
        }
    }
    val tileImages = tiles.map { (col, row) ->
        key(col, row) {
            val w = min(tileW.toFloat(), tiledW - col * tileW).roundToInt()
            val h = min(tileH.toFloat(), tiledH - row * tileH).roundToInt()
            val tile = if (w > 0 && h > 0) {
                producePageTile(
                    engine, page, bitmapScale, rotation,
                    col, row, tileW, tileH, w, h, renderEpoch, renderZoom.deferred, cache,
                )
            } else {
                null
            }
            TileImage(col, row, w, h, tile?.let { remember(it) { it.asImageBitmap() } })
        }
    }

    val edgeColor = if (isSystemInDarkTheme()) kPageEdgeDark else kPageEdgeLight
    val filter = if (invertColors) kNightFilter else null

    Canvas(
        modifier = outerModifier
            .semantics { contentDescription = "page ${page + 1}" }
            .onGloballyPositioned { coords ->
            slotOrigin.value = coords.positionInWindow() -
                (placements?.surfaceOrigin ?: Offset.Zero)
            slotWidth.floatValue = coords.size.width.toFloat()
            slotHeight.floatValue = coords.size.height.toFloat()
            val left = pageLeftIn(coords.size.width.toFloat(), displayW, scrollX)
            val top = pageTopIn(
                coords.size.height.toFloat(), displayH, scrollY, inContinuousColumn,
            )
            placements?.put(
                page,
                SlotPlacement(
                    originInWindow = coords.positionInWindow() + Offset(left, top),
                    displayWidth = displayW,
                    displayHeight = displayH,
                    bounds = bounds,
                    displayScale = scale,
                ),
            )
        },
    ) {
        val left = pageLeftIn(size.width, displayW, scrollX)
        val top = pageTopIn(size.height, displayH, scrollY, inContinuousColumn)
        drawRect(
            color = if (invertColors) kCanvasBgDark else kPageSheetColor,
            topLeft = Offset(left, top),
            size = GeoSize(displayW, displayH),
        )
        if (baseImage != null) {
            drawImage(
                image = baseImage,
                dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                dstSize = IntSize(displayW.roundToInt(), displayH.roundToInt()),
                colorFilter = filter,
                filterQuality = FilterQuality.Low,
            )
        }
        tileImages.forEach { tile ->
            val image = tile.image ?: return@forEach
            drawImage(
                image = image,
                dstOffset = IntOffset(
                    (left + tile.col * tileW * stretch).roundToInt(),
                    (top + tile.row * tileH * stretch).roundToInt(),
                ),
                dstSize = IntSize(
                    (tile.width * stretch).roundToInt(),
                    (tile.height * stretch).roundToInt(),
                ),
                colorFilter = filter,
            )
        }
        drawRect(
            color = edgeColor,
            topLeft = Offset(left, top),
            size = GeoSize(displayW, displayH),
            style = Stroke(width = 1f * density.density),
        )
        if (rotation == 0 && (searchHits.isNotEmpty() || links.isNotEmpty() || selection != null)) {
            val origin = Offset(left, top)
            links.forEach { link ->
                val r = boundsToDisplayRect(link.bounds, bounds, scale)
                drawRect(
                    color = kLinkColor.copy(alpha = 0.10f),
                    topLeft = Offset(r.left, r.top) + origin,
                    size = GeoSize(r.width, r.height),
                )
            }
            searchHits.forEachIndexed { i, hit ->
                val r = hitToDisplayRect(hit, bounds, scale)
                drawRect(
                    color = if (i == searchCurrentHit) kHitCurrentColor.copy(alpha = 0.55f)
                            else kHitColor.copy(alpha = 0.30f),
                    topLeft = Offset(r.left, r.top) + origin,
                    size = GeoSize(r.width, r.height),
                )
            }
            selection?.quads?.forEach { q ->
                val r = hitToDisplayRect(q, bounds, scale)
                drawRect(
                    color = kSelectionColor.copy(alpha = 0.35f),
                    topLeft = Offset(r.left, r.top) + origin,
                    size = GeoSize(r.width, r.height),
                )
            }
        }
    }
}

private fun pageLeftIn(slotW: Float, displayW: Float, scrollX: Float): Float =
    (slotW - displayW) / 2f - scrollX

private fun pageTopIn(
    slotH: Float,
    displayH: Float,
    scrollY: Float,
    inContinuousColumn: Boolean,
): Float = if (inContinuousColumn) 0f else (slotH - displayH) / 2f - scrollY

@Composable
private fun producePageTile(
    engine: DocumentEngine,
    page: Int,
    scale: Float,
    rotation: Int,
    col: Int,
    row: Int,
    stepW: Int,
    stepH: Int,
    tileW: Int,
    tileH: Int,
    renderEpoch: Int,
    deferred: Boolean,
    cache: LruCache<RenderCacheKey, Bitmap>?,
): Bitmap? {
    val key = RenderCacheKey(engine.activeHandle, page, scale.toRawBits(), rotation, col, row)
    val cached = remember(key) { cache?.get(key) }
    var bmp by remember(key) { mutableStateOf(cached) }
    LaunchedEffect(key, renderEpoch, deferred) {
        val hit = cache?.get(key)
        if (hit != null) {
            bmp = hit
            return@LaunchedEffect
        }
        if (deferred) return@LaunchedEffect
        val fresh = withContext(Dispatchers.IO) {
            try {
                engine.renderPageTile(
                    page, scale, rotation,
                    col * stepW, row * stepH, tileW, tileH,
                )?.also { it.prepareToDraw() }
            } catch (t: Throwable) {
                Log.w("SumatraPage", "renderPageTile($page, $col, $row) failed: ${t.message}", t)
                null
            }
        }
        if (fresh != null && cache != null) {
            cache.put(key, fresh)
        }
        bmp = fresh
    }
    return bmp
}

private val kNightFilter = androidx.compose.ui.graphics.ColorFilter.colorMatrix(
    androidx.compose.ui.graphics.ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)

// Render the bitmap for one page. The bitmap comes from the
// shared LRU cache (see `renderCache` at the top of the
// PageSurface composable); the LaunchedEffect refills the
// cache on a miss. We keep the per-slot Compose state for
// the displayed bitmap (so the slot re-draws when the cache
// lookup finds a hit) but the bitmap itself is owned by
// the cache. (PORTING-STATUS §3.7.)
@Composable
private fun producePageBitmap(
    engine: DocumentEngine,
    page: Int,
    scale: Float,
    rotation: Int,
    renderEpoch: Int = 0,
    cache: LruCache<RenderCacheKey, Bitmap>? = null,
): Bitmap? {
    // The active docHandle changes when the user switches
    // documents. Looking it up here is cheap (a single
    // field read on the engine) and means a doc switch
    // invalidates every entry in the cache.
    val docHandle = engine.activeHandle
    val key = RenderCacheKey(docHandle, page, scale.toRawBits(), rotation)
    // Keyed on the page rather than the scale, so a zoom change does not
    // drop the bitmap we are already showing: the stale one keeps being
    // drawn, stretched to the new size, until the render at the new
    // scale lands. RenderCache::Paint does the same with StretchDIBits
    // rather than leaving the window blank.
    var bmp by remember(docHandle, page, rotation) { mutableStateOf(cache?.get(key)) }
    LaunchedEffect(key, renderEpoch) {
        val hit = cache?.get(key)
        if (hit != null) {
            bmp = hit
            return@LaunchedEffect
        }
        val fresh = withContext(Dispatchers.IO) {
            try {
                engine.renderPage(page, scale, rotation)?.also { it.prepareToDraw() }
            } catch (t: Throwable) {
                Log.w("SumatraPage", "renderPage($page, $scale, $rotation) failed: ${t.message}", t)
                null
            }
        }
        if (fresh != null) {
            cache?.put(key, fresh)
            bmp = fresh
        }
    }
    return bmp
}

@Composable
private fun EmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "SumatraPDF",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Open a document to start reading.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun BookCover(name: String, modifier: Modifier) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.substringBeforeLast('.', name),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// Convert a search hit (in page-point coordinates) to a rectangle in
// the page's own display space, which is what the overlay Canvas draws
// into.
private fun hitToDisplayRect(
    hit: Quad,
    bounds: PageBounds,
    displayScale: Float,
): androidx.compose.ui.geometry.Rect =
    boundsToDisplayRect(
        PageBounds(hit.x0, hit.y0, hit.x1, hit.y1),
        bounds,
        displayScale,
    )

private fun boundsToDisplayRect(
    box: PageBounds,
    bounds: PageBounds,
    displayScale: Float,
): androidx.compose.ui.geometry.Rect {
    val ptsToPx = kPxPerPoint * displayScale
    val x0 = (box.x0 - bounds.x0) * ptsToPx
    val y0 = (box.y0 - bounds.y0) * ptsToPx
    val x1 = (box.x1 - bounds.x0) * ptsToPx
    val y1 = (box.y1 - bounds.y0) * ptsToPx
    return androidx.compose.ui.geometry.Rect(
        left = min(x0, x1),
        top = min(y0, y1),
        right = maxOf(x0, x1),
        bottom = maxOf(y0, y1),
    )
}

// Pages in facing-view are laid out as pairs.  Page 0 is alone on its
// row; pages 1+2, 3+4, ... are paired.  Returns one entry per row.
internal fun chunkedPairs(
    pages: List<Int>,
    firstAlone: Boolean,
    reverseWithinPair: Boolean = false,
): List<Pair<Int, Int>> {
    val rows = mutableListOf<Pair<Int, Int>>()
    var i = 0
    if (firstAlone && pages.isNotEmpty()) {
        rows.add(pages[0] to -1)
        i = 1
    }
    while (i < pages.size) {
        val a = pages[i]
        val b = if (i + 1 < pages.size) pages[i + 1] else -1
        rows.add(if (reverseWithinPair && b >= 0) (b to a) else (a to b))
        i += 2
    }
    return rows
}

internal fun rowPairOrder(left: Int, right: Int, mangaMode: Boolean): Pair<Int, Int> {
    if (mangaMode && right >= 0) return right to left
    return left to right
}

internal fun isMangaReversed(displayMode: DisplayMode, mangaMode: Boolean): Boolean =
    mangaMode && displayMode != DisplayMode.SinglePage
