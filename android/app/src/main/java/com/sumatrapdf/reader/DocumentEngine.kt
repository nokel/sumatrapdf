package com.sumatrapdf.reader

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.StructuredText
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// Multi-document engine. The mupdf C library's threading rules say
// "no simultaneous calls to MuPDF in different threads are allowed to
// use the same document" (artifex.com/blog/multi-threaded-use-of-mupdf-in-java).
// The previous attempt that kept multiple Documents in a map crashed
// in mupdf's scudo allocator (`fz_free` of an invalid pointer) when
// one tab was closed while a `renderPage` on Dispatchers.IO was still
// inside `fz_load_chapter_page` for the same Document. The fix: every
// operation on a Document goes through a per-Document ReentrantLock.
// A close waits for any in-flight render to finish.
//
// The engine is otherwise thin: a `Map<Int, DocSlot>` indexed by
// handle, plus an `_activeHandle` that the toolbar / page surface
// bind to via Compose state. Tabs are handles into this map; opening
// a file adds a slot, closing a tab removes (and destroys) it,
// switching just moves the active handle.
//
// On Android we do NOT need multi-context — a single shared
// `Context.init()` is enough because we serialize all access via
// the per-doc lock.
class DocumentEngine {
    private val tag = "SumatraEngine"

    private val _activeHandle = mutableIntStateOf(0)
    val activeHandle: Int get() = _activeHandle.intValue

    private val _lastError = mutableStateOf<String?>(null)
    val lastError: String? get() = _lastError.value

    private val _layoutEpoch = mutableIntStateOf(0)
    val layoutEpoch: Int get() = _layoutEpoch.intValue

    private class DocSlot(
        val document: Document,
        val path: String,
        var pageCount: Int,
        val displayName: String,
        val sourceUri: String?,
        val lock: ReentrantLock = ReentrantLock(),
    ) {
        var textPage: Int = -1
        var text: StructuredText? = null

        // pageBounds/pageSize are read from composition on every frame
        // (layout needs the natural page size to size the slot). Going
        // to mupdf for that means loadPage under the per-doc lock on the
        // UI thread, which blocks behind any in-flight background render
        // — a pinch turns into a slideshow. Page bounds never change
        // except across a relayout, so they are cached; the map is
        // concurrent so a hit costs no lock at all.
        val boundsCache = java.util.concurrent.ConcurrentHashMap<Int, PageBounds>()

        fun dropText() {
            val t = text
            text = null
            textPage = -1
            if (t != null) {
                try { t.destroy() } catch (_: Throwable) {}
            }
        }
    }

    private val docs = mutableMapOf<Int, DocSlot>()
    private var nextHandle: Int = 1

    // Every page/tile rasterisation, counted. Rendering is the one
    // genuinely expensive thing the engine does, so this is the number
    // that says whether a gesture is asking for work it cannot afford.
    private val renders = java.util.concurrent.atomic.AtomicInteger(0)
    val renderCount: Int get() = renders.get()

    val isOpen: Boolean get() = docs.containsKey(_activeHandle.intValue)
    val pageCount: Int get() {
        _layoutEpoch.intValue
        return docs[_activeHandle.intValue]?.pageCount ?: 0
    }
    val path: String? get() = docs[_activeHandle.intValue]?.path

    fun clearError() { _lastError.value = null }

    fun titleFor(handle: Int): String = docs[handle]?.displayName ?: "?"

    fun listHandles(): List<Int> = docs.keys.toList()
    fun pathFor(handle: Int): String? = docs[handle]?.path
    fun uriFor(handle: Int): String? = docs[handle]?.sourceUri

    sealed class Opened {
        data class Ok(val handle: Int) : Opened()
        data class NeedsPassword(val path: String) : Opened()
        data class Failed(val message: String) : Opened()
    }

    // Open a document directly from a path. The active handle is NOT
    // changed — the caller (ReaderScreen) decides when to activate.
    fun open(
        path: String,
        password: String? = null,
        displayName: String? = null,
        sourceUri: String? = null,
    ): Opened {
        Log.i(tag, "open: $path (password=${password != null})")
        // Each new document's outline starts at id 1 again, so
        // any expand/collapse state from a previous document
        // does not accidentally apply to a fresh tree.
        resetOutlineIdCounter()
        var opened: Document? = null
        return try {
            opened = Document.openDocument(path)
            if (opened.needsPassword()) {
                if (password == null || !opened.authenticatePassword(password)) {
                    try { opened.destroy() } catch (_: Throwable) {}
                    Log.i(tag, "open: password required for $path")
                    return Opened.NeedsPassword(path)
                }
            }
            val slot = DocSlot(
                document = opened,
                path = path,
                pageCount = opened.countPages(),
                displayName = displayName
                    ?: path.substringAfterLast('/').substringAfterLast('\\').ifEmpty { path },
                sourceUri = sourceUri,
            )
            val h = nextHandle++
            docs[h] = slot
            _lastError.value = null
            Log.i(tag, "open: OK, handle=$h pageCount=${slot.pageCount}")
            Opened.Ok(h)
        } catch (t: Throwable) {
            try { opened?.destroy() } catch (_: Throwable) {}
            val msg = "Could not open ${path.substringAfterLast('/')}: ${t.message ?: t.javaClass.simpleName}"
            Log.e(tag, msg, t)
            _lastError.value = msg
            Opened.Failed(msg)
        }
    }

    // Content-URI open. Materialise the URI to a private file in
    // cacheDir, then open that as a path. The cache key is the URI's
    // hash so re-opening the same URI is a no-op.
    fun openUri(context: Context, uri: Uri, password: String? = null): Opened {
        val local = materialise(context, uri)
            ?: return Opened.Failed(_lastError.value ?: "could not read $uri")
        return open(local, password, displayNameFor(context, uri), uri.toString())
    }

    // The materialised cache file is named after the URI's hash, so
    // without asking the provider for OpenableColumns.DISPLAY_NAME the
    // tab and the start page would both show that mangled name.
    private fun displayNameFor(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
        )?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    } catch (t: Throwable) {
        Log.w(tag, "displayNameFor($uri) failed: ${t.message}")
        null
    }

    private fun materialise(context: Context, uri: Uri): String? {
        Log.i(tag, "materialise: $uri")
        val cacheDir = try {
            File(context.cacheDir, "engine-opened").also { it.mkdirs() }
        } catch (t: Throwable) {
            _lastError.value = "openUri cacheDir failed: ${t.message}"
            Log.e(tag, _lastError.value!!, t)
            return null
        }
        val name = uri.lastPathSegment?.replace(Regex("[^A-Za-z0-9._-]"), "_") ?: "doc"
        val target = File(cacheDir, "${uri.toString().hashCode().toString(16)}-$name")
        try {
            if (!target.exists() || target.length() == 0L) {
                val input: InputStream = context.contentResolver.openInputStream(uri)
                    ?: run {
                        _lastError.value = "could not open uri stream"
                        return null
                    }
                input.use { src ->
                    FileOutputStream(target).use { dst -> src.copyTo(dst) }
                }
            }
        } catch (t: Throwable) {
            _lastError.value = "openUri copy failed: ${t.javaClass.simpleName}: ${t.message}"
            Log.e(tag, _lastError.value!!, t)
            return null
        }
        return target.absolutePath
    }

    // Switch the active document. No I/O happens — the active doc was
    // already opened via open/openUri.
    // Handle 0 means "no document" — the state the Home tab needs, so
    // the toolbar, page count and status bar all read as empty while it
    // is selected without any document actually being closed.
    fun setActive(handle: Int): Boolean {
        if (handle == 0) {
            _activeHandle.intValue = 0
            return true
        }
        if (!docs.containsKey(handle)) return false
        _activeHandle.intValue = handle
        return true
    }

    // Close the active document. The lock is acquired first, so any
    // concurrent renderPage on the same Document completes before we
    // destroy it. If there are no other open docs, activeHandle goes
    // to 0.
    fun closeActive() {
        val h = _activeHandle.intValue
        if (h == 0) return
        closeHandle(h)
        if (!docs.containsKey(_activeHandle.intValue)) {
            _activeHandle.intValue = 0
        }
    }

    fun closeAll() {
        docs.keys.toList().forEach { closeHandle(it) }
        _activeHandle.intValue = 0
    }

    // Close a specific handle. Acquires the doc's lock before
    // destroying it. Safe to call from any thread.
    fun closeHandle(handle: Int) {
        val slot = docs.remove(handle) ?: return
        slot.lock.withLock {
            slot.dropText()
            try { slot.document.destroy() } catch (_: Throwable) {}
        }
        Log.i(tag, "closeHandle: $handle (${slot.path})")
        if (_activeHandle.intValue == handle) {
            // Pick the next-most-recently-opened handle as active.
            val next = docs.keys.maxOrNull() ?: 0
            _activeHandle.intValue = next
        }
    }

    val isReflowable: Boolean
        get() {
            val slot = activeSlot() ?: return false
            return slot.lock.withLock {
                try { slot.document.isReflowable } catch (_: Throwable) { false }
            }
        }

    // Repaginating a reflowable document renumbers every page, so a saved
    // page number is worthless across a relayout — which happens on every
    // reopen and on every fold/unfold. `keepBookmark` is mupdf's answer:
    // an opaque position token (FileState::reparseIdx on Windows) that
    // survives repagination. Returns the page it now lands on, or -1.
    fun relayout(widthPt: Float, heightPt: Float, emPt: Float, keepBookmark: Long = 0L): Int {
        val slot = activeSlot() ?: return -1
        var page = -1
        slot.lock.withLock {
            try {
                if (!slot.document.isReflowable) return@withLock
                slot.dropText()
                slot.boundsCache.clear()
                slot.document.layout(widthPt, heightPt, emPt)
                slot.pageCount = slot.document.countPages()
                if (keepBookmark != 0L) {
                    page = try {
                        slot.document.pageNumberFromLocation(
                            slot.document.findBookmark(keepBookmark),
                        )
                    } catch (t: Throwable) {
                        Log.w(tag, "findBookmark failed: ${t.message}")
                        -1
                    }
                }
                Log.i(tag, "relayout: ${widthPt}x$heightPt em=$emPt -> ${slot.pageCount} pages, page=$page")
            } catch (t: Throwable) {
                Log.w(tag, "relayout failed: ${t.message}", t)
            }
        }
        _layoutEpoch.intValue++
        return page
    }

    fun bookmarkFor(pageNo: Int): Long {
        val slot = activeSlot() ?: return 0L
        if (pageNo < 0 || pageNo >= slot.pageCount) return 0L
        return slot.lock.withLock {
            try {
                if (!slot.document.isReflowable) return@withLock 0L
                slot.document.makeBookmark(slot.document.locationFromPageNumber(pageNo))
            } catch (t: Throwable) {
                Log.w(tag, "makeBookmark($pageNo) failed: ${t.message}")
                0L
            }
        }
    }

    fun pageSize(pageNo: Int): Pair<Float, Float>? {
        val b = pageBounds(pageNo) ?: return null
        return Pair(b.x1 - b.x0, b.y1 - b.y0)
    }

    fun pageBounds(pageNo: Int): PageBounds? {
        val slot = activeSlot() ?: return null
        if (pageNo < 0 || pageNo >= slot.pageCount) return null
        slot.boundsCache[pageNo]?.let { return it }
        return slot.lock.withLock {
            slot.boundsCache[pageNo]?.let { return@withLock it }
            val page = slot.document.loadPage(pageNo) ?: return@withLock null
            try {
                val b = page.bounds
                PageBounds(b.x0, b.y0, b.x1, b.y1).also { slot.boundsCache[pageNo] = it }
            } finally {
                try { page.destroy() } catch (_: Throwable) {}
            }
        }
    }

    fun renderPage(pageNo: Int, zoom: Float, rotation: Int): Bitmap? {
        val slot = activeSlot() ?: return null
        if (pageNo < 0 || pageNo >= slot.pageCount) return null
        renders.incrementAndGet()
        return slot.lock.withLock {
            val page = slot.document.loadPage(pageNo) ?: return@withLock null
            try {
                val bounds = page.bounds
                val pw = bounds.x1 - bounds.x0
                val ph = bounds.y1 - bounds.y0
                if (pw <= 0f || ph <= 0f) return@withLock null
                val baseScale = zoom * 2f
                val ctm = com.artifex.mupdf.fitz.Matrix(
                    baseScale, 0f, 0f, baseScale,
                    -bounds.x0 * baseScale, -bounds.y0 * baseScale,
                )
                if (rotation != 0) ctm.rotate(rotation.toFloat())
                com.artifex.mupdf.fitz.android.AndroidDrawDevice.drawPage(page, ctm)
            } finally {
                try { page.destroy() } catch (_: Throwable) {}
            }
        }
    }

    // Best-effort render that does not block: returns null if the
    // engine is already rendering (someone holds the per-doc
    // ReentrantLock). Used by the prefetch pass in PageSurface so
    // the prefetch never starves the active page render. The lock
    // is still acquired non-blocking via tryLock, so MuPDF's "no
    // simultaneous calls in different threads" rule is respected.
    fun tryRenderPage(pageNo: Int, zoom: Float, rotation: Int): Bitmap? {
        val slot = activeSlot() ?: return null
        if (pageNo < 0 || pageNo >= slot.pageCount) return null
        if (!slot.lock.tryLock()) return null
        var result: Bitmap? = null
        try {
            val page = slot.document.loadPage(pageNo)
            if (page != null) {
                try {
                    val bounds = page.bounds
                    val pw = bounds.x1 - bounds.x0
                    val ph = bounds.y1 - bounds.y0
                    if (pw > 0f && ph > 0f) {
                        val baseScale = zoom * 2f
                        val ctm = com.artifex.mupdf.fitz.Matrix(
                            baseScale, 0f, 0f, baseScale,
                            -bounds.x0 * baseScale, -bounds.y0 * baseScale,
                        )
                        if (rotation != 0) ctm.rotate(rotation.toFloat())
                        result = com.artifex.mupdf.fitz.android.AndroidDrawDevice.drawPage(page, ctm)
                    }
                } finally {
                    try { page.destroy() } catch (_: Throwable) {}
                }
            }
        } finally {
            slot.lock.unlock()
        }
        return result
    }

    fun renderPageTile(
        pageNo: Int,
        zoom: Float,
        rotation: Int,
        tileX: Int,
        tileY: Int,
        tileW: Int,
        tileH: Int,
    ): Bitmap? {
        val slot = activeSlot() ?: return null
        if (pageNo < 0 || pageNo >= slot.pageCount) return null
        if (tileW <= 0 || tileH <= 0) return null
        renders.incrementAndGet()
        return slot.lock.withLock {
            val page = slot.document.loadPage(pageNo) ?: return@withLock null
            try {
                val bounds = page.bounds
                val pw = bounds.x1 - bounds.x0
                val ph = bounds.y1 - bounds.y0
                if (pw <= 0f || ph <= 0f) return@withLock null
                val baseScale = zoom * 2f
                val ctm = com.artifex.mupdf.fitz.Matrix(
                    baseScale, 0f, 0f, baseScale,
                    -bounds.x0 * baseScale, -bounds.y0 * baseScale,
                )
                if (rotation != 0) ctm.rotate(rotation.toFloat())
                val bmp = Bitmap.createBitmap(tileW, tileH, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                val device = com.artifex.mupdf.fitz.android.AndroidDrawDevice(bmp, tileX, tileY, false)
                try {
                    page.run(device, ctm, null)
                    device.close()
                } finally {
                    try { device.destroy() } catch (_: Throwable) {}
                }
                bmp
            } finally {
                try { page.destroy() } catch (_: Throwable) {}
            }
        }
    }

    fun getOutline(): List<OutlineNode>? {
        val slot = activeSlot() ?: return null
        return slot.lock.withLock {
            val arr = slot.document.loadOutline() ?: return@withLock null
            arr.mapNotNull { convertOutline(it, slot.document) }
        }
    }

    fun searchPage(
        pageNo: Int,
        needle: String,
        style: Int = SearchFlags.FLAG_IGNORE_CASE,
    ): List<Quad> {
        val slot = activeSlot() ?: return emptyList()
        if (pageNo < 0 || pageNo >= slot.pageCount) return emptyList()
        if (needle.isEmpty()) return emptyList()
        return slot.lock.withLock {
            val page = slot.document.loadPage(pageNo) ?: return@withLock emptyList()
            try {
                // mupdf 1.28.0's Java binding exposes only the 2-arg
                // `Page.search(String needle, int style)` and the
                // 1-arg `Page.search(String needle)`. The int is the
                // `fz_search_options` bitmask, NOT `maxHits` (the
                // binding has no maxHits). Bits 0/1/2/3/4/5 are
                // EXACT/IGNORE_CASE/IGNORE_DIACRITICS/REGEXP/
                // KEEP_LINES/KEEP_PARAGRAPHS; bit 6 is KEEP_HYPHENS.
                // A custom mupdf build could re-expose maxHits by
                // wrapping `fz_match_page_cb` to stop early, but the
                // shipped 1.28.0 AAR does not. Default is
                // IGNORE_CASE so the search matches "Brotli" when the
                // user types "brotli".
                val quads = page.search(needle, style) ?: return@withLock emptyList()
                quads.map { Quad(it) }
            } finally {
                try { page.destroy() } catch (_: Throwable) {}
            }
        }
    }

    fun pageText(pageNo: Int): String {
        val slot = activeSlot() ?: return ""
        if (pageNo < 0 || pageNo >= slot.pageCount) return ""
        return slot.lock.withLock {
            val page = slot.document.loadPage(pageNo) ?: return@withLock ""
            try {
                page.toStructuredText().asText()
            } finally {
                try { page.destroy() } catch (_: Throwable) {}
            }
        }
    }

    // mupdf reports a link target as a (chapter, page) Location, so
    // reading .page off it gives the page WITHIN its chapter — an
    // EPUB's chapter 5 page 0 is not document page 0.
    // pageNumberFromLocation is what makes it a document page.
    fun links(pageNo: Int): List<PageLink> {
        val slot = activeSlot() ?: return emptyList()
        if (pageNo < 0 || pageNo >= slot.pageCount) return emptyList()
        return slot.lock.withLock {
            val page = slot.document.loadPage(pageNo) ?: return@withLock emptyList()
            try {
                val links = page.links ?: return@withLock emptyList()
                links.mapNotNull { link ->
                    val uri = link.uri ?: return@mapNotNull null
                    val b = link.bounds ?: return@mapNotNull null
                    val external = try { link.isExternal } catch (_: Throwable) { false }
                    val target = if (external) -1 else {
                        try {
                            slot.document.pageNumberFromLocation(slot.document.resolveLink(link))
                        } catch (_: Throwable) { -1 }
                    }
                    PageLink(
                        bounds = PageBounds(b.x0, b.y0, b.x1, b.y1),
                        uri = uri,
                        isExternal = external,
                        targetPage = target,
                    )
                }
            } finally {
                try { page.destroy() } catch (_: Throwable) {}
            }
        }
    }

    private fun withText(slot: DocSlot, pageNo: Int, body: (StructuredText) -> Unit) {
        slot.lock.withLock {
            if (slot.textPage != pageNo || slot.text == null) {
                slot.dropText()
                val page = slot.document.loadPage(pageNo) ?: return@withLock
                try {
                    slot.text = page.toStructuredText()
                    slot.textPage = pageNo
                } catch (t: Throwable) {
                    Log.w(tag, "toStructuredText($pageNo) failed: ${t.message}", t)
                    return@withLock
                } finally {
                    try { page.destroy() } catch (_: Throwable) {}
                }
            }
            val t = slot.text ?: return@withLock
            body(t)
        }
    }

    fun selectWords(pageNo: Int, fromX: Float, fromY: Float, toX: Float, toY: Float): Selection? {
        val slot = activeSlot() ?: return null
        if (pageNo < 0 || pageNo >= slot.pageCount) return null
        var result: Selection? = null
        withText(slot, pageNo) { text ->
            val a = com.artifex.mupdf.fitz.Point(fromX, fromY)
            val b = com.artifex.mupdf.fitz.Point(toX, toY)
            try {
                val snapped = text.snapSelection(a, b, StructuredText.SELECT_WORDS)
                val p0 = com.artifex.mupdf.fitz.Point(snapped.ul_x, snapped.ul_y)
                val p1 = com.artifex.mupdf.fitz.Point(snapped.lr_x, snapped.lr_y)
                val quads = text.highlight(p0, p1) ?: emptyArray()
                if (quads.isEmpty()) return@withText
                val copied = text.copy(p0, p1) ?: ""
                result = Selection(
                    page = pageNo,
                    quads = quads.map { Quad(arrayOf(it)) },
                    text = copied,
                    startX = snapped.ul_x,
                    startY = snapped.ul_y,
                    endX = snapped.lr_x,
                    endY = snapped.lr_y,
                )
            } catch (t: Throwable) {
                Log.w(tag, "selectWords($pageNo) failed: ${t.message}", t)
            }
        }
        return result
    }

    // The Win32 Properties dialog is src/SumatraProperties.cpp; this is
    // the subset mupdf surfaces on Android.
    fun properties(): List<Pair<String, String>> {
        val slot = activeSlot() ?: return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        val file = File(slot.path)
        out += "File" to file.name
        if (file.exists()) {
            out += "File size" to formatBytes(file.length())
        }
        slot.lock.withLock {
            val doc = slot.document
            fun meta(key: String, label: String) {
                val v = try { doc.getMetaData(key) } catch (_: Throwable) { null }
                if (!v.isNullOrBlank()) out += label to v
            }
            meta(Document.META_FORMAT, "Format")
            meta(Document.META_INFO_TITLE, "Title")
            meta(Document.META_INFO_AUTHOR, "Author")
            meta(Document.META_INFO_SUBJECT, "Subject")
            meta(Document.META_INFO_KEYWORDS, "Keywords")
            meta(Document.META_INFO_CREATOR, "Application")
            meta(Document.META_INFO_PRODUCER, "PDF Producer")
            meta(Document.META_INFO_CREATIONDATE, "Created")
            meta(Document.META_INFO_MODIFICATIONDATE, "Modified")
            meta(Document.META_ENCRYPTION, "Encryption")
            out += "Pages" to slot.pageCount.toString()
            val page = try { doc.loadPage(0) } catch (_: Throwable) { null }
            if (page != null) {
                try {
                    val b = page.bounds
                    val wPt = b.x1 - b.x0
                    val hPt = b.y1 - b.y0
                    out += "Page size" to String.format(
                        "%.2f x %.2f in (%.0f x %.0f pt)",
                        wPt / 72.0, hPt / 72.0, wPt, hPt,
                    )
                } catch (_: Throwable) {
                } finally {
                    try { page.destroy() } catch (_: Throwable) {}
                }
            }
            val perms = buildList {
                if (runCatching { doc.hasPermission(Document.PERMISSION_PRINT) }.getOrDefault(true)) add("print")
                if (runCatching { doc.hasPermission(Document.PERMISSION_COPY) }.getOrDefault(true)) add("copy")
                if (runCatching { doc.hasPermission(Document.PERMISSION_ANNOTATE) }.getOrDefault(true)) add("annotate")
            }
            out += "Allowed" to if (perms.isEmpty()) "nothing" else perms.joinToString(", ")
        }
        return out
    }

    fun canCopy(): Boolean {
        val slot = activeSlot() ?: return false
        return slot.lock.withLock {
            runCatching { slot.document.hasPermission(Document.PERMISSION_COPY) }.getOrDefault(true)
        }
    }

    fun canPrint(): Boolean {
        val slot = activeSlot() ?: return false
        return slot.lock.withLock {
            runCatching { slot.document.hasPermission(Document.PERMISSION_PRINT) }.getOrDefault(true)
        }
    }

    private fun activeSlot(): DocSlot? = docs[_activeHandle.intValue]
}

private fun formatBytes(n: Long): String = when {
    n >= 1L shl 30 -> String.format("%.2f GB", n / (1L shl 30).toDouble())
    n >= 1L shl 20 -> String.format("%.2f MB", n / (1L shl 20).toDouble())
    n >= 1L shl 10 -> String.format("%.2f KB", n / (1L shl 10).toDouble())
    else -> "$n bytes"
}

data class OutlineNode(
    val id: Long,
    val title: String,
    val page: Int,
    val children: List<OutlineNode>,
)

data class PageBounds(
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float,
)

data class PageLink(
    val bounds: PageBounds,
    val uri: String,
    val isExternal: Boolean,
    val targetPage: Int,
)

data class Selection(
    val page: Int,
    val quads: List<Quad>,
    val text: String,
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
)

data class Quad(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
    constructor(quads: Array<com.artifex.mupdf.fitz.Quad>) : this(
        x0 = quads.minOf { it.ul_x },
        y0 = quads.minOf { it.ul_y },
        x1 = quads.maxOf { it.lr_x },
        y1 = quads.maxOf { it.lr_y },
    )
}

private var outlineNextId: Long = 0L
private val outlineIdLock = Any()

private fun nextOutlineId(): Long = synchronized(outlineIdLock) {
    outlineNextId += 1
    outlineNextId
}

/** Reset the outline ID counter so a fresh document starts from 1. */
internal fun resetOutlineIdCounter() {
    synchronized(outlineIdLock) { outlineNextId = 0L }
}

private fun convertOutline(
    outline: com.artifex.mupdf.fitz.Outline,
    doc: com.artifex.mupdf.fitz.Document,
): OutlineNode? {
    val title = outline.title ?: return null
    val page = try {
        doc.pageNumberFromLocation(doc.resolveLink(outline))
    } catch (_: Throwable) { 0 }
    val id = nextOutlineId()
    val children = outline.down?.mapNotNull { convertOutline(it, doc) } ?: emptyList()
    return OutlineNode(id, title, page, children)
}
