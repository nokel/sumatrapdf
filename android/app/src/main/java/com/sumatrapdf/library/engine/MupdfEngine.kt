package com.sumatrapdf.library.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import com.artifex.mupdf.fitz.Document as FitzDocument
import com.artifex.mupdf.fitz.Outline
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.StructuredText
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import kotlin.math.sqrt

// Real engine for everything MuPDF supports: PDF, EPUB, XPS, OXPS, FB2,
// CBZ, SVG, TIFF, PNG, JPEG. MupdfEngine reuses the same calls
// (Document.openDocument, page rendering via DisplayList, StructuredText
// for search) that the existing DocumentSession uses.
class MupdfEngine(override val spec: DocumentSpec) : DocumentEngine {

    private val work = Executors.newSingleThreadExecutor { r ->
        Thread(r, "MupdfEngine-${spec.id.take(6)}")
    }
    private val fitzRef = AtomicReference<FitzDocument?>(null)
    private var password: String? = null

    @Volatile override var pageCount: Int = 0
        private set
    @Volatile override var isReflowable: Boolean = false
        private set
    @Volatile override var isPdf: Boolean = false
        private set
    override val isStub: Boolean = false

    private var reflowWidth = 612f
    private var reflowHeight = 792f
    private var reflowEm = 11f

    override fun open(onReady: () -> Unit, onPassword: () -> Unit, onFailed: (String) -> Unit) {
        work.execute {
            val opened = try {
                FitzDocument.openDocument(spec.path)
            } catch (e: Throwable) {
                runOnUi { onFailed(e.message ?: "could not be read") }
                return@execute
            }
            if (opened == null) {
                runOnUi { onFailed("could not be read") }
                return@execute
            }
            try {
                if (opened.needsPassword()) {
                    if (password == null || !opened.authenticatePassword(password)) {
                        opened.destroy()
                        runOnUi { onPassword() }
                        return@execute
                    }
                }
                isReflowable = opened.isReflowable
                if (isReflowable) opened.layout(reflowWidth, reflowHeight, reflowEm)
                pageCount = opened.countPages()
                isPdf = opened.isPDF
                fitzRef.set(opened)
                runOnUi { onReady() }
            } catch (e: Throwable) {
                try { opened.destroy() } catch (_: Throwable) {}
                runOnUi { onFailed(e.message ?: "could not be read") }
            }
        }
    }

    override fun close() {
        work.execute {
            try { fitzRef.get()?.destroy() } catch (_: Throwable) {}
            fitzRef.set(null)
        }
        work.shutdown()
    }

    override fun setReflow(widthPt: Float, heightPt: Float, em: Float) {
        reflowWidth = widthPt
        reflowHeight = heightPt
        reflowEm = em
        val live = fitzRef.get() ?: return
        if (isReflowable) {
            work.execute {
                try { live.layout(widthPt, heightPt, em); pageCount = live.countPages() } catch (_: Throwable) {}
            }
        }
    }

    override fun outline(then: (List<DocumentOutlineEntry>) -> Unit) {
        work.execute {
            val out = ArrayList<DocumentOutlineEntry>()
            val live = fitzRef.get()
            if (live != null) {
                try {
                    val roots = live.loadOutline()
                    if (roots != null) walk(live, roots, 0, out)
                } catch (_: Throwable) {}
            }
            runOnUi { then(out) }
        }
    }

    private fun walk(live: FitzDocument, nodes: Array<Outline>, depth: Int, into: MutableList<DocumentOutlineEntry>) {
        for (node in nodes) {
            val where = try {
                if (node.uri == null) -1
                else live.pageNumberFromLocation(live.resolveLink(node))
            } catch (_: Throwable) { -1 }
            val title = node.title?.trim().orEmpty()
            if (title.isNotEmpty()) into.add(DocumentOutlineEntry(title, where, depth))
            val kids = node.down
            if (kids != null && depth < 6) walk(live, kids, depth + 1, into)
        }
    }

    override fun pageText(index: Int, then: (String) -> Unit) {
        work.execute {
            val live = fitzRef.get() ?: return@execute runOnUi { then("") }
            val text = try {
                val page = live.loadPage(index)
                val st = page.toStructuredText()
                val out = st.asText() ?: ""
                try { st.destroy() } catch (_: Throwable) {}
                try { page.destroy() } catch (_: Throwable) {}
                out
            } catch (_: Throwable) { "" }
            runOnUi { then(text) }
        }
    }

    override fun searchPage(index: Int, needle: String, then: (List<RectF>) -> Unit) {
        work.execute {
            val live = fitzRef.get() ?: return@execute runOnUi { then(emptyList()) }
            val out = ArrayList<RectF>()
            if (needle.isNotBlank()) {
                try {
                    val page = live.loadPage(index)
                    val groups = page.search(needle, StructuredText.SEARCH_IGNORE_CASE)
                    if (groups != null) for (group in groups) for (quad in group) {
                        val box = quad.toRect()
                        out.add(RectF(box.x0, box.y0, box.x1, box.y1))
                    }
                    try { page.destroy() } catch (_: Throwable) {}
                } catch (_: Throwable) {}
            }
            runOnUi { then(out) }
        }
    }

    override fun render(index: Int, targetWidth: Int, night: Boolean, then: (Bitmap?) -> Unit) {
        work.execute {
            val made = renderNow(index, targetWidth, 0, night)
            runOnUi { then(made) }
        }
    }

    override fun pageShape(index: Int, then: (Pair<Float, Float>?) -> Unit) {
        work.execute {
            val live = fitzRef.get() ?: return@execute runOnUi { then(null) }
            val shape = try {
                val page = live.loadPage(index)
                val b = page.bounds
                val s = Pair(b.x1 - b.x0, b.y1 - b.y0)
                try { page.destroy() } catch (_: Throwable) {}
                s
            } catch (_: Throwable) { null }
            runOnUi { then(shape) }
        }
    }

    private fun renderNow(index: Int, targetWidth: Int, rotation: Int, night: Boolean): Bitmap? {
        if (targetWidth <= 0) return null
        val live = fitzRef.get() ?: return null
        return try {
            val page = live.loadPage(index)
            try {
                val bounds = Rect(page.bounds)
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
                val ctm = com.artifex.mupdf.fitz.Matrix.Scale(scale)
                if (rotation != 0) ctm.rotate(rotation.toFloat())
                val box = Rect(bounds).transform(ctm)
                val w = ceil((box.x1 - box.x0).toDouble()).toInt().coerceAtLeast(1)
                val h = ceil((box.y1 - box.y0).toDouble()).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                val device = AndroidDrawDevice(bitmap, box.x0.toInt(), box.y0.toInt())
                try {
                    page.run(device, ctm)
                    if (night) device.invertLuminance()
                } finally {
                    try { device.close() } catch (_: Throwable) {}
                    device.destroy()
                }
                bitmap
            } finally { try { page.destroy() } catch (_: Throwable) {} }
        } catch (_: Throwable) { null }
    }

    private fun runOnUi(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }

    companion object {
        private const val MAX_PIXELS = 75_000_000.0
    }
}
