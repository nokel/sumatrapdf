package com.sumatrapdf.library

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import com.artifex.mupdf.fitz.DisplayList
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Link
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Outline
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Page
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.StructuredText
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.sqrt

class PageShape(val width: Float, val height: Float) {
    val ratio: Float get() = if (width > 0f) height / width else 1.4f
}

class OutlineEntry(val title: String, val page: Int, val depth: Int)

class LinkTarget(val bounds: RectF, val uri: String, val page: Int) {
    val external: Boolean get() = page < 0
}

class SearchHit(val page: Int, val boxes: List<RectF>)

class AnnotationInfo(
    val slot: Int,
    val page: Int,
    val type: Int,
    val label: String,
    val bounds: RectF,
    val contents: String,
    val author: String,
)

class PageRender(
    val index: Int,
    val bitmap: Bitmap,
    val ctm: Matrix,
    val originX: Float,
    val originY: Float,
) {
    fun quadToBitmap(quad: Quad): RectF {
        val box = quad.transformed(ctm).toRect()
        return RectF(box.x0 - originX, box.y0 - originY, box.x1 - originX, box.y1 - originY)
    }

    fun rectToBitmap(rect: Rect): RectF {
        val box = Rect(rect).transform(ctm)
        return RectF(box.x0 - originX, box.y0 - originY, box.x1 - originX, box.y1 - originY)
    }

    fun bitmapToPage(x: Float, y: Float): Point =
        Point(x + originX, y + originY).transform(Matrix.Inverted(ctm))
}

class DocumentSession {

    private val work = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var doc: Document? = null
    private var pdf: PDFDocument? = null
    private var password: String? = null

    private class Leaf(val page: Page) {
        var list: DisplayList? = null
        var text: StructuredText? = null

        fun release() {
            try { text?.destroy() } catch (e: Throwable) { }
            try { list?.destroy() } catch (e: Throwable) { }
            try { page.destroy() } catch (e: Throwable) { }
        }
    }

    private val leaves = object : LinkedHashMap<Int, Leaf>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Leaf>?): Boolean {
            if (size <= LEAF_LIMIT) return false
            eldest?.value?.release()
            return true
        }
    }

    private val shapes = ConcurrentHashMap<Int, PageShape>()

    var source: DocumentSource? = null
        private set
    var pageCount = 0
        private set
    var reflowable = false
        private set
    var isPdf = false
        private set
    var canAnnotate = false
        private set
    var canPrint = true
        private set
    var canCopy = true
        private set

    var reflowWidth = 612f
    var reflowHeight = 792f
    var reflowEm = 11f

    fun open(
        wanted: DocumentSource,
        secret: String?,
        onReady: () -> Unit,
        onPassword: () -> Unit,
        onFailed: (String) -> Unit,
    ) {
        source = wanted
        password = secret
        work.execute {
            val opened = try {
                Document.openDocument(wanted.path)
            } catch (e: Throwable) {
                main.post { onFailed(e.message ?: "could not be read") }
                return@execute
            }
            if (opened == null) {
                main.post { onFailed("could not be read") }
                return@execute
            }
            try {
                if (opened.needsPassword()) {
                    if (secret == null || !opened.authenticatePassword(secret)) {
                        opened.destroy()
                        main.post { onPassword() }
                        return@execute
                    }
                }
                adoptNative(opened)
                main.post { onReady() }
            } catch (e: Throwable) {
                try { opened.destroy() } catch (ignored: Throwable) { }
                main.post { onFailed(e.message ?: "could not be read") }
            }
        }
    }

    private fun adoptNative(opened: Document) {
        reflowable = opened.isReflowable
        if (reflowable) opened.layout(reflowWidth, reflowHeight, reflowEm)
        pageCount = opened.countPages()
        isPdf = opened.isPDF
        pdf = if (isPdf) opened.asPDF() else null
        canPrint = !isPdf || opened.hasPermission(Document.PERMISSION_PRINT)
        canCopy = !isPdf || opened.hasPermission(Document.PERMISSION_COPY)
        canAnnotate = isPdf && opened.hasPermission(Document.PERMISSION_ANNOTATE) &&
            (source?.writable ?: false)
        doc = opened
    }

    fun close() {
        work.execute {
            forgetLeaves()
            try { doc?.destroy() } catch (e: Throwable) { }
            doc = null
            pdf = null
        }
        work.shutdown()
    }

    private fun forgetLeaves() {
        for (leaf in leaves.values) leaf.release()
        leaves.clear()
    }

    private fun leafOf(index: Int): Leaf? {
        val live = doc ?: return null
        leaves[index]?.let { return it }
        if (index < 0 || index >= pageCount) return null
        return try {
            val made = Leaf(live.loadPage(index))
            leaves[index] = made
            val bounds = made.page.bounds
            shapes[index] = PageShape(bounds.x1 - bounds.x0, bounds.y1 - bounds.y0)
            made
        } catch (e: Throwable) {
            null
        }
    }

    private fun listOf(leaf: Leaf): DisplayList? {
        leaf.list?.let { return it }
        return try {
            leaf.page.toDisplayList().also { leaf.list = it }
        } catch (e: Throwable) {
            null
        }
    }

    private fun textOf(leaf: Leaf): StructuredText? {
        leaf.text?.let { return it }
        return try {
            leaf.page.toStructuredText().also { leaf.text = it }
        } catch (e: Throwable) {
            null
        }
    }

    fun knownShape(index: Int): PageShape? = shapes[index]

    fun measure(index: Int, then: (PageShape?) -> Unit) {
        work.execute {
            leafOf(index)
            val found = shapes[index]
            main.post { then(found) }
        }
    }

    fun render(
        index: Int,
        targetWidth: Int,
        rotation: Int,
        night: Boolean,
        then: (PageRender?) -> Unit,
    ) {
        work.execute {
            val made = renderNow(index, targetWidth, rotation, night)
            main.post { then(made) }
        }
    }

    private fun renderNow(index: Int, targetWidth: Int, rotation: Int, night: Boolean): PageRender? {
        if (targetWidth <= 0) return null
        val leaf = leafOf(index) ?: return null
        return try {
            val bounds = Rect(leaf.page.bounds)
            val pw = bounds.x1 - bounds.x0
            val ph = bounds.y1 - bounds.y0
            if (pw <= 0f || ph <= 0f) return null
            val turned = rotation % 180 != 0
            val across = if (turned) ph else pw
            val down = if (turned) pw else ph
            var scale = targetWidth / across
            if (across * scale * down * scale > MAX_PIXELS) {
                scale *= sqrt(MAX_PIXELS / (across * scale * down * scale)).toFloat()
            }
            val ctm = Matrix.Scale(scale)
            if (rotation != 0) ctm.rotate(rotation.toFloat())
            val box = Rect(bounds).transform(ctm)
            val w = ceil((box.x1 - box.x0).toDouble()).toInt().coerceAtLeast(1)
            val h = ceil((box.y1 - box.y0).toDouble()).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            val drawing = listOf(leaf)
            val device = AndroidDrawDevice(bitmap, box.x0.toInt(), box.y0.toInt())
            try {
                if (drawing != null) drawing.run(device, ctm, null)
                else leaf.page.run(device, ctm)
                if (night) device.invertLuminance()
            } finally {
                try { device.close() } catch (e: Throwable) { }
                device.destroy()
            }
            PageRender(index, bitmap, ctm, box.x0, box.y0)
        } catch (e: Throwable) {
            null
        }
    }

    fun renderBlocking(index: Int, targetWidth: Int, rotation: Int, night: Boolean): PageRender? =
        try {
            work.submit<PageRender?> { renderNow(index, targetWidth, rotation, night) }.get()
        } catch (e: Throwable) {
            null
        }

    fun shapeBlocking(index: Int): PageShape? = try {
        work.submit<PageShape?> {
            leafOf(index)
            shapes[index]
        }.get()
    } catch (e: Throwable) {
        null
    }

    fun outline(then: (List<OutlineEntry>) -> Unit) {
        work.execute {
            val found = ArrayList<OutlineEntry>()
            val live = doc
            if (live != null) {
                try {
                    val roots = live.loadOutline()
                    if (roots != null) gatherOutline(live, roots, 0, found)
                } catch (e: Throwable) { }
            }
            main.post { then(found) }
        }
    }

    private fun gatherOutline(
        live: Document,
        nodes: Array<Outline>,
        depth: Int,
        into: ArrayList<OutlineEntry>,
    ) {
        for (node in nodes) {
            val where = try {
                if (node.uri == null) -1 else live.pageNumberFromLocation(live.resolveLink(node))
            } catch (e: Throwable) {
                -1
            }
            val title = node.title?.trim().orEmpty()
            if (title.isNotEmpty()) into.add(OutlineEntry(title, where, depth))
            val kids = node.down
            if (kids != null && depth < OUTLINE_DEPTH) gatherOutline(live, kids, depth + 1, into)
        }
    }

    fun links(index: Int, then: (List<LinkTarget>) -> Unit) {
        work.execute {
            val found = ArrayList<LinkTarget>()
            val live = doc
            val leaf = leafOf(index)
            if (live != null && leaf != null) {
                try {
                    val raw = leaf.page.links
                    if (raw != null) for (one in raw) {
                        val uri = one.uri ?: continue
                        val bounds = one.bounds
                        val where = if (Link.isExternal(uri)) -1 else try {
                            live.pageNumberFromLocation(live.resolveLink(one))
                        } catch (e: Throwable) {
                            -1
                        }
                        found.add(
                            LinkTarget(
                                RectF(bounds.x0, bounds.y0, bounds.x1, bounds.y1),
                                uri,
                                where,
                            )
                        )
                    }
                } catch (e: Throwable) { }
            }
            main.post { then(found) }
        }
    }

    fun searchPage(index: Int, needle: String, then: (SearchHit?) -> Unit) {
        work.execute {
            var hit: SearchHit? = null
            val leaf = leafOf(index)
            if (leaf != null && needle.isNotBlank()) {
                try {
                    val quads = leaf.page.search(needle, StructuredText.SEARCH_IGNORE_CASE)
                    if (quads != null && quads.isNotEmpty()) {
                        val boxes = ArrayList<RectF>()
                        for (group in quads) {
                            for (quad in group) {
                                val box = quad.toRect()
                                boxes.add(RectF(box.x0, box.y0, box.x1, box.y1))
                            }
                        }
                        if (boxes.isNotEmpty()) hit = SearchHit(index, boxes)
                    }
                } catch (e: Throwable) { }
            }
            main.post { then(hit) }
        }
    }

    fun wordAt(index: Int, x: Float, y: Float, then: (List<RectF>, String) -> Unit) {
        work.execute {
            var boxes: List<RectF> = emptyList()
            var text = ""
            val leaf = leafOf(index)
            val structured = if (leaf != null) textOf(leaf) else null
            if (structured != null) {
                try {
                    val spot = Point(x, y)
                    val snapped = structured.snapSelection(spot, Point(x, y), StructuredText.SELECT_WORDS)
                    if (snapped != null && snapped.isValid) {
                        val from = Point(snapped.ul_x, snapped.ul_y)
                        val to = Point(snapped.lr_x, snapped.lr_y)
                        boxes = quadsToBoxes(structured.highlight(from, to))
                        text = structured.copy(from, to) ?: ""
                    }
                } catch (e: Throwable) { }
            }
            main.post { then(boxes, text) }
        }
    }

    fun selectBetween(
        index: Int,
        fromX: Float, fromY: Float,
        toX: Float, toY: Float,
        then: (List<RectF>, String) -> Unit,
    ) {
        work.execute {
            var boxes: List<RectF> = emptyList()
            var text = ""
            val leaf = leafOf(index)
            val structured = if (leaf != null) textOf(leaf) else null
            if (structured != null) {
                try {
                    val from = Point(fromX, fromY)
                    val to = Point(toX, toY)
                    boxes = quadsToBoxes(structured.highlight(from, to))
                    text = structured.copy(from, to) ?: ""
                } catch (e: Throwable) { }
            }
            main.post { then(boxes, text) }
        }
    }

    private fun quadsToBoxes(quads: Array<Quad>?): List<RectF> {
        if (quads == null) return emptyList()
        val out = ArrayList<RectF>(quads.size)
        for (quad in quads) {
            val box = quad.toRect()
            out.add(RectF(box.x0, box.y0, box.x1, box.y1))
        }
        return out
    }

    fun pageText(index: Int, then: (String) -> Unit) {
        work.execute {
            val leaf = leafOf(index)
            val structured = if (leaf != null) textOf(leaf) else null
            val text = try { structured?.asText() ?: "" } catch (e: Throwable) { "" }
            main.post { then(text) }
        }
    }

    fun annotations(index: Int, then: (List<AnnotationInfo>) -> Unit) {
        work.execute {
            val found = readAnnotations(index)
            main.post { then(found) }
        }
    }

    private fun readAnnotations(index: Int): List<AnnotationInfo> {
        val leaf = leafOf(index) ?: return emptyList()
        val page = leaf.page as? PDFPage ?: return emptyList()
        return try {
            val raw = page.annotations ?: return emptyList()
            raw.mapIndexed { slot, one ->
                val bounds = one.bounds
                AnnotationInfo(
                    slot = slot,
                    page = index,
                    type = one.type,
                    label = annotationLabel(one.type),
                    bounds = RectF(bounds.x0, bounds.y0, bounds.x1, bounds.y1),
                    contents = try { one.contents ?: "" } catch (e: Throwable) { "" },
                    author = try { if (one.hasAuthor()) one.author ?: "" else "" } catch (e: Throwable) { "" },
                )
            }
        } catch (e: Throwable) {
            emptyList()
        }
    }

    fun markSelection(
        index: Int,
        type: Int,
        boxes: List<RectF>,
        colour: FloatArray,
        note: String?,
        then: (Boolean) -> Unit,
    ) {
        work.execute {
            val done = try {
                val leaf = leafOf(index)
                val page = leaf?.page as? PDFPage
                if (page == null || boxes.isEmpty()) false else {
                    pdf?.beginOperation("Mark")
                    val made = page.createAnnotation(type)
                    made.setQuadPoints(boxes.map { Quad(Rect(it.left, it.top, it.right, it.bottom)) }
                        .toTypedArray())
                    made.setColor(colour)
                    if (!note.isNullOrBlank()) made.setContents(note)
                    made.update()
                    pdf?.endOperation()
                    invalidate(index)
                    true
                }
            } catch (e: Throwable) {
                try { pdf?.abandonOperation() } catch (ignored: Throwable) { }
                false
            }
            main.post { then(done) }
        }
    }

    fun markInk(
        index: Int,
        strokes: List<List<android.graphics.PointF>>,
        colour: FloatArray,
        width: Float,
        then: (Boolean) -> Unit,
    ) {
        work.execute {
            val done = try {
                val leaf = leafOf(index)
                val page = leaf?.page as? PDFPage
                val useful = strokes.filter { it.size > 1 }
                if (page == null || useful.isEmpty()) false else {
                    pdf?.beginOperation("Draw")
                    val made = page.createAnnotation(PDFAnnotation.TYPE_INK)
                    made.setInkList(useful.map { stroke ->
                        stroke.map { Point(it.x, it.y) }.toTypedArray()
                    }.toTypedArray())
                    made.setColor(colour)
                    made.setBorderWidth(width)
                    made.update()
                    pdf?.endOperation()
                    invalidate(index)
                    true
                }
            } catch (e: Throwable) {
                try { pdf?.abandonOperation() } catch (ignored: Throwable) { }
                false
            }
            main.post { then(done) }
        }
    }

    fun addNote(index: Int, x: Float, y: Float, text: String, then: (Boolean) -> Unit) {
        work.execute {
            val done = try {
                val leaf = leafOf(index)
                val page = leaf?.page as? PDFPage
                if (page == null) false else {
                    pdf?.beginOperation("Note")
                    val made = page.createAnnotation(PDFAnnotation.TYPE_TEXT)
                    made.setRect(Rect(x, y, x + NOTE_SIZE, y + NOTE_SIZE))
                    made.setContents(text)
                    made.setColor(floatArrayOf(1f, 0.84f, 0.2f))
                    made.update()
                    pdf?.endOperation()
                    invalidate(index)
                    true
                }
            } catch (e: Throwable) {
                try { pdf?.abandonOperation() } catch (ignored: Throwable) { }
                false
            }
            main.post { then(done) }
        }
    }

    fun removeAnnotation(index: Int, slot: Int, then: (Boolean) -> Unit) {
        work.execute {
            val done = try {
                val leaf = leafOf(index)
                val page = leaf?.page as? PDFPage
                val raw = page?.annotations
                if (page == null || raw == null || slot < 0 || slot >= raw.size) false else {
                    pdf?.beginOperation("Delete")
                    page.deleteAnnotation(raw[slot])
                    pdf?.endOperation()
                    invalidate(index)
                    true
                }
            } catch (e: Throwable) {
                try { pdf?.abandonOperation() } catch (ignored: Throwable) { }
                false
            }
            main.post { then(done) }
        }
    }

    private fun invalidate(index: Int) {
        leaves[index]?.let {
            try { it.list?.destroy() } catch (e: Throwable) { }
            try { it.text?.destroy() } catch (e: Throwable) { }
            it.list = null
            it.text = null
        }
    }

    fun hasEdits(then: (Boolean) -> Unit) {
        work.execute {
            val dirty = try { pdf?.hasUnsavedChanges() ?: false } catch (e: Throwable) { false }
            main.post { then(dirty) }
        }
    }

    fun save(then: (String?) -> Unit) {
        work.execute {
            val problem = saveNow()
            main.post { then(problem) }
        }
    }

    private fun saveNow(): String? {
        val living = pdf ?: return "not a PDF"
        val where = source ?: return "no file"
        if (!where.writable) return "the file is read-only"
        return try {
            if (living.canBeSavedIncrementally()) {
                living.save(where.path, "incremental")
                null
            } else {
                val target = File(where.path)
                val temp = File(target.parentFile, target.name + ".saving")
                living.save(temp.absolutePath, "compress")
                forgetLeaves()
                try { doc?.destroy() } catch (e: Throwable) { }
                doc = null
                pdf = null
                target.delete()
                if (!temp.renameTo(target)) {
                    temp.delete()
                    reopenNow()
                    return "could not replace the file"
                }
                reopenNow()
                null
            }
        } catch (e: Throwable) {
            e.message ?: "could not be saved"
        }
    }

    private fun reopenNow() {
        val where = source ?: return
        val again = try { Document.openDocument(where.path) } catch (e: Throwable) { null } ?: return
        try {
            if (again.needsPassword()) password?.let { again.authenticatePassword(it) }
            adoptNative(again)
        } catch (e: Throwable) {
            try { again.destroy() } catch (ignored: Throwable) { }
        }
    }

    fun relayout(width: Float, height: Float, em: Float, atPage: Int, then: (Int) -> Unit) {
        work.execute {
            var landing = atPage
            val live = doc
            if (live != null && reflowable) {
                try {
                    val marker = live.makeBookmark(live.locationFromPageNumber(atPage))
                    forgetLeaves()
                    shapes.clear()
                    reflowWidth = width
                    reflowHeight = height
                    reflowEm = em
                    live.layout(width, height, em)
                    pageCount = live.countPages()
                    landing = live.pageNumberFromLocation(live.findBookmark(marker))
                    if (landing < 0) landing = 0
                } catch (e: Throwable) { }
            }
            val settled = landing
            main.post { then(settled) }
        }
    }

    fun resolve(uri: String, then: (Int) -> Unit) {
        work.execute {
            val where = try {
                val live = doc
                if (live == null) -1 else live.pageNumberFromLocation(live.resolveLink(uri))
            } catch (e: Throwable) {
                -1
            }
            main.post { then(where) }
        }
    }

    fun facts(then: (List<Pair<String, String>>) -> Unit) {
        work.execute {
            val rows = ArrayList<Pair<String, String>>()
            val live = doc
            val where = source
            if (live != null) {
                try {
                    fun add(label: String, key: String) {
                        val value = live.getMetaData(key)
                        if (!value.isNullOrBlank()) rows.add(Pair(label, value))
                    }
                    if (where != null) rows.add(Pair("File", where.name))
                    add("Title", Document.META_INFO_TITLE)
                    add("Author", Document.META_INFO_AUTHOR)
                    add("Subject", Document.META_INFO_SUBJECT)
                    add("Keywords", Document.META_INFO_KEYWORDS)
                    add("Creator", Document.META_INFO_CREATOR)
                    add("Producer", Document.META_INFO_PRODUCER)
                    add("Created", Document.META_INFO_CREATIONDATE)
                    add("Modified", Document.META_INFO_MODIFICATIONDATE)
                    add("Format", Document.META_FORMAT)
                    val locked = live.getMetaData(Document.META_ENCRYPTION)
                    rows.add(Pair("Encryption", if (locked.isNullOrBlank()) "None" else locked))
                    rows.add(Pair("Pages", pageCount.toString()))
                    if (where != null) {
                        val size = File(where.path).length()
                        rows.add(Pair("Size", readableSize(size)))
                    }
                    if (isPdf) {
                        rows.add(Pair("Printing", if (canPrint) "Allowed" else "Not allowed"))
                        rows.add(Pair("Copying", if (canCopy) "Allowed" else "Not allowed"))
                        rows.add(Pair("Annotating", if (canAnnotate) "Allowed" else "Not allowed"))
                    }
                    rows.add(Pair("Reflowable", if (reflowable) "Yes" else "No"))
                } catch (e: Throwable) { }
            }
            main.post { then(rows) }
        }
    }

    companion object {
        private const val LEAF_LIMIT = 4
        private const val MAX_PIXELS = 8_000_000f
        private const val OUTLINE_DEPTH = 6
        private const val NOTE_SIZE = 20f

        fun annotationLabel(type: Int): String = when (type) {
            PDFAnnotation.TYPE_HIGHLIGHT -> "Highlight"
            PDFAnnotation.TYPE_UNDERLINE -> "Underline"
            PDFAnnotation.TYPE_STRIKE_OUT -> "Strikeout"
            PDFAnnotation.TYPE_SQUIGGLY -> "Squiggly"
            PDFAnnotation.TYPE_INK -> "Drawing"
            PDFAnnotation.TYPE_TEXT -> "Note"
            PDFAnnotation.TYPE_FREE_TEXT -> "Text"
            PDFAnnotation.TYPE_SQUARE -> "Rectangle"
            PDFAnnotation.TYPE_CIRCLE -> "Ellipse"
            PDFAnnotation.TYPE_LINK -> "Link"
            else -> "Annotation"
        }

        fun readableSize(bytes: Long): String {
            if (bytes < 1024) return "$bytes bytes"
            val units = listOf("KB", "MB", "GB")
            var value = bytes.toDouble() / 1024.0
            var step = 0
            while (value >= 1024.0 && step < units.size - 1) {
                value /= 1024.0
                step += 1
            }
            return String.format("%.1f %s", value, units[step])
        }
    }
}
