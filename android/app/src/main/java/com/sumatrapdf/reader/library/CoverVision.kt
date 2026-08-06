package com.sumatrapdf.reader.library

import android.graphics.Bitmap
import android.util.Log
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Page
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val TAG = "SumatraCoverVision"

const val PROBE_HEIGHT = 420
const val SPREAD_RATIO = 1.15f
const val MIN_SOURCE_WIDTH = 120
const val MIN_SOURCE_HEIGHT = 160
const val COVER_ACCEPT_SCORE = 0.5
const val PATCH_SAMPLE_TARGET = 4000
const val COVER_RATIO_LOW = 0.5
const val COVER_RATIO_HIGH = 0.85

data class PageRect(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
    val width: Float get() = x1 - x0
    val height: Float get() = y1 - y0
    val area: Double get() = abs(width.toDouble() * height.toDouble())

    fun clampedTo(other: PageRect): PageRect = PageRect(
        max(x0, other.x0),
        max(y0, other.y0),
        min(x1, other.x1),
        min(y1, other.y1),
    )
}

data class CoverCandidate(
    val rect: PageRect,
    val fromEmbeddedImage: Boolean,
    val sourceWidth: Int,
    val sourceHeight: Int,
)

data class ScoredCover(val candidate: CoverCandidate, val score: Double, val features: DoubleArray)

class PatchStats(
    val white: Double,
    val dark: Double,
    val saturation: Double,
    val contrast: Double,
    val edges: Double,
    val colours: Double,
)

val COVER_FEATURE_NAMES = listOf(
    "bias", "share", "ratioFit", "offCentreX", "offCentreY", "white", "dark",
    "saturation", "contrast", "edges", "colours", "embedded", "resolution",
)

val COVER_PRIOR_WEIGHTS = doubleArrayOf(
    -1.0, 2.5, 2.0, -1.5, -1.0, -4.0, -2.0, 2.0, 1.0, 1.5, 2.5, 0.6, 0.8,
)

fun patchStats(pixels: IntArray, width: Int, height: Int): PatchStats {
    if (width <= 0 || height <= 0 || pixels.size < width * height) {
        return PatchStats(1.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    }
    val step = max(1, sqrt((width.toDouble() * height) / PATCH_SAMPLE_TARGET).roundToInt())
    var seen = 0
    var white = 0
    var dark = 0
    var edges = 0
    var edgesSeen = 0
    var saturation = 0.0
    var luminance = 0.0
    var luminanceSquared = 0.0
    val shades = HashSet<Int>()
    var y = 0
    while (y < height) {
        var x = 0
        val row = y * width
        while (x < width) {
            val p = pixels[row + x]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            seen++
            if (r > 232 && g > 232 && b > 232) white++
            if (r < 26 && g < 26 && b < 26) dark++
            val hi = max(r, max(g, b))
            val lo = min(r, min(g, b))
            if (hi > 0) saturation += (hi - lo) / hi.toDouble()
            val lum = 0.299 * r + 0.587 * g + 0.114 * b
            luminance += lum
            luminanceSquared += lum * lum
            shades.add(((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4))
            val nx = x + step
            if (nx < width) {
                val q = pixels[row + nx]
                val next = 0.299 * ((q shr 16) and 0xFF) +
                    0.587 * ((q shr 8) and 0xFF) +
                    0.114 * (q and 0xFF)
                edgesSeen++
                if (abs(next - lum) > 24.0) edges++
            }
            x += step
        }
        y += step
    }
    if (seen == 0) return PatchStats(1.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    val mean = luminance / seen
    val variance = max(0.0, luminanceSquared / seen - mean * mean)
    return PatchStats(
        white = white / seen.toDouble(),
        dark = dark / seen.toDouble(),
        saturation = saturation / seen,
        contrast = min(1.0, sqrt(variance) / 128.0),
        edges = if (edgesSeen == 0) 0.0 else edges / edgesSeen.toDouble(),
        colours = min(1.0, shades.size / seen.toDouble()),
    )
}

fun patchStatsOf(bitmap: Bitmap): PatchStats {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 0 || h <= 0) return PatchStats(1.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    return patchStats(pixels, w, h)
}

fun whiteShareOf(bitmap: Bitmap): Double = patchStatsOf(bitmap).white

private fun ratioFitOf(ratio: Double): Double {
    if (ratio <= 0.0) return 0.0
    val off = when {
        ratio < COVER_RATIO_LOW -> COVER_RATIO_LOW - ratio
        ratio > COVER_RATIO_HIGH -> ratio - COVER_RATIO_HIGH
        else -> 0.0
    }
    return max(0.0, 1.0 - off / 0.5)
}

fun coverFeatures(
    candidate: CoverCandidate,
    pageRect: PageRect,
    stats: PatchStats,
): DoubleArray {
    val pageArea = pageRect.area.takeIf { it > 0.0 } ?: 1.0
    val share = (candidate.rect.area / pageArea).coerceIn(0.0, 1.0)
    val ratio = if (candidate.rect.height > 0f) {
        candidate.rect.width.toDouble() / candidate.rect.height
    } else {
        0.0
    }
    val pageMidX = (pageRect.x0 + pageRect.x1) / 2.0
    val pageMidY = (pageRect.y0 + pageRect.y1) / 2.0
    val midX = (candidate.rect.x0 + candidate.rect.x1) / 2.0
    val midY = (candidate.rect.y0 + candidate.rect.y1) / 2.0
    val offX = if (pageRect.width > 0f) min(1.0, abs(midX - pageMidX) * 2.0 / pageRect.width) else 0.0
    val offY = if (pageRect.height > 0f) min(1.0, abs(midY - pageMidY) * 2.0 / pageRect.height) else 0.0
    val pixels = candidate.sourceWidth.toDouble() * candidate.sourceHeight
    val resolution = if (pixels <= 0.0) 0.0 else min(1.0, sqrt(pixels) / 1200.0)
    return doubleArrayOf(
        1.0,
        share,
        ratioFitOf(ratio),
        offX,
        offY,
        stats.white,
        stats.dark,
        stats.saturation,
        stats.contrast,
        stats.edges,
        stats.colours,
        if (candidate.fromEmbeddedImage) 1.0 else 0.0,
        resolution,
    )
}

private class ImageBlockCollector(private val out: MutableList<CoverCandidate>) : ImageBlockWalker() {
    override fun onImageBlock(rect: Rect?, ctm: Matrix?, img: Image?) {
        if (rect == null || img == null) return
        val mask = try { img.imageMask } catch (_: Throwable) { false }
        if (mask) return
        val w = try { img.width } catch (_: Throwable) { 0 }
        val h = try { img.height } catch (_: Throwable) { 0 }
        out.add(
            CoverCandidate(
                rect = PageRect(rect.x0, rect.y0, rect.x1, rect.y1),
                fromEmbeddedImage = true,
                sourceWidth = w,
                sourceHeight = h,
            ),
        )
    }
}

internal fun overlaps(a: PageRect, b: PageRect): Boolean {
    val left = max(a.x0, b.x0)
    val top = max(a.y0, b.y0)
    val right = min(a.x1, b.x1)
    val bottom = min(a.y1, b.y1)
    if (right <= left || bottom <= top) return false
    val shared = (right - left).toDouble() * (bottom - top)
    val union = a.area + b.area - shared
    return union > 0.0 && shared / union > 0.94
}

fun coverCandidates(page: Page): List<CoverCandidate> {
    val bounds = page.bounds
    val pageRect = PageRect(bounds.x0, bounds.y0, bounds.x1, bounds.y1)
    if (pageRect.area <= 0.0) return emptyList()
    val images = mutableListOf<CoverCandidate>()
    val shapes = try {
        page.toStructuredText(STEXT_IMAGE_OPTIONS)
    } catch (t: Throwable) {
        Log.w(TAG, "image stext failed: ${t.message}")
        null
    }
    if (shapes != null) {
        try {
            shapes.walk(ImageBlockCollector(images))
        } catch (t: Throwable) {
            Log.w(TAG, "walk failed: ${t.message}")
        } finally {
            try { shapes.destroy() } catch (_: Throwable) {}
        }
    }

    val out = mutableListOf<CoverCandidate>()
    for (image in images) {
        if (image.sourceWidth < MIN_SOURCE_WIDTH || image.sourceHeight < MIN_SOURCE_HEIGHT) continue
        val rect = image.rect.clampedTo(pageRect)
        if (rect.width <= 1f || rect.height <= 1f) continue
        val share = rect.area / pageRect.area
        val ratio = rect.width.toDouble() / rect.height
        val big = share >= MIN_IMAGE_SHARE
        val inset = share >= INSET_IMAGE_SHARE && ratio >= COVER_RATIO_LOW && ratio <= COVER_RATIO_HIGH
        if (!big && !inset && ratio <= SPREAD_RATIO) continue
        if (!big && ratio > SPREAD_RATIO && share < MIN_IMAGE_SHARE) continue
        out.add(image.copy(rect = rect))
    }
    out.add(CoverCandidate(pageRect, false, 0, 0))

    val halves = mutableListOf<CoverCandidate>()
    for (c in out) {
        val ratio = if (c.rect.height > 0f) c.rect.width / c.rect.height else 0f
        if (ratio <= SPREAD_RATIO) continue
        val mid = (c.rect.x0 + c.rect.x1) / 2f
        halves.add(c.copy(rect = PageRect(c.rect.x0, c.rect.y0, mid, c.rect.y1)))
        halves.add(c.copy(rect = PageRect(mid, c.rect.y0, c.rect.x1, c.rect.y1)))
    }
    out.addAll(halves)

    val unique = mutableListOf<CoverCandidate>()
    for (c in out) {
        if (unique.any { overlaps(it.rect, c.rect) }) continue
        unique.add(c)
    }
    return unique
}

fun renderPageRegion(page: Page, rect: PageRect, targetHeight: Int): Bitmap? {
    if (rect.width <= 0f || rect.height <= 0f || targetHeight <= 0) return null
    val scale = targetHeight / rect.height
    val w = max(1, (rect.width * scale).roundToInt())
    val h = max(1, targetHeight)
    if (w.toLong() * h > 40_000_000L) return null
    return try {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        val ctm = Matrix(scale, 0f, 0f, scale, -rect.x0 * scale, -rect.y0 * scale)
        val device = AndroidDrawDevice(bitmap, 0, 0, false)
        try {
            page.run(device, ctm, null)
            device.close()
        } finally {
            try { device.destroy() } catch (_: Throwable) {}
        }
        bitmap
    } catch (t: Throwable) {
        Log.w(TAG, "renderPageRegion failed: ${t.message}")
        null
    }
}

fun scoreCandidates(page: Page, candidates: List<CoverCandidate>): List<ScoredCover> {
    if (candidates.isEmpty()) return emptyList()
    val bounds = page.bounds
    val pageRect = PageRect(bounds.x0, bounds.y0, bounds.x1, bounds.y1)
    val probe = renderPageRegion(page, pageRect, PROBE_HEIGHT) ?: return emptyList()
    val weights = CoverModel.weights()
    return try {
        val scaleX = probe.width / pageRect.width
        val scaleY = probe.height / pageRect.height
        candidates.mapNotNull { c ->
            val left = ((c.rect.x0 - pageRect.x0) * scaleX).roundToInt().coerceIn(0, probe.width - 1)
            val top = ((c.rect.y0 - pageRect.y0) * scaleY).roundToInt().coerceIn(0, probe.height - 1)
            val right = ((c.rect.x1 - pageRect.x0) * scaleX).roundToInt().coerceIn(left + 1, probe.width)
            val bottom = ((c.rect.y1 - pageRect.y0) * scaleY).roundToInt().coerceIn(top + 1, probe.height)
            val w = right - left
            val h = bottom - top
            if (w < 2 || h < 2) return@mapNotNull null
            val pixels = IntArray(w * h)
            probe.getPixels(pixels, 0, w, left, top, w, h)
            val features = coverFeatures(c, pageRect, patchStats(pixels, w, h))
            ScoredCover(c, CoverModel.score(features, weights), features)
        }.sortedByDescending { it.score }
    } finally {
        probe.recycle()
    }
}

fun pickCover(page: Page): ScoredCover? {
    val scored = scoreCandidates(page, coverCandidates(page))
    val best = scored.firstOrNull() ?: return null
    if (best.score < COVER_ACCEPT_SCORE) {
        val whole = scored.firstOrNull { !it.candidate.fromEmbeddedImage }
        return whole ?: best
    }
    return best
}

data class RenderedPage(val bitmap: Bitmap, val pageRect: PageRect, val index: Int, val pages: Int)

fun renderBookPage(path: String, pageIndex: Int, maxWidth: Int, maxHeight: Int): RenderedPage? {
    var doc: Document? = null
    var page: Page? = null
    try {
        doc = Document.openDocument(path)
        if (doc.needsPassword()) return null
        val pages = doc.countPages()
        if (pages < 1) return null
        val index = pageIndex.coerceIn(0, pages - 1)
        page = doc.loadPage(index)
        val bounds = page.bounds
        val rect = PageRect(bounds.x0, bounds.y0, bounds.x1, bounds.y1)
        if (rect.width <= 0f || rect.height <= 0f) return null
        val scale = min(maxWidth / rect.width, maxHeight / rect.height)
        val height = max(1, (rect.height * scale).roundToInt())
        val bitmap = renderPageRegion(page, rect, height) ?: return null
        return RenderedPage(bitmap, rect, index, pages)
    } catch (t: Throwable) {
        Log.w(TAG, "renderBookPage($path, $pageIndex): ${t.message}")
        return null
    } finally {
        try { page?.destroy() } catch (_: Throwable) {}
        try { doc?.destroy() } catch (_: Throwable) {}
    }
}

fun pageRectOfCrop(
    rendered: RenderedPage,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
): PageRect {
    val w = rendered.bitmap.width.toFloat()
    val h = rendered.bitmap.height.toFloat()
    if (w <= 0f || h <= 0f) return rendered.pageRect
    val page = rendered.pageRect
    fun mapX(v: Float) = page.x0 + (v / w) * page.width
    fun mapY(v: Float) = page.y0 + (v / h) * page.height
    return PageRect(
        mapX(min(left, right)),
        mapY(min(top, bottom)),
        mapX(max(left, right)),
        mapY(max(top, bottom)),
    ).clampedTo(page)
}

private fun contains(outer: PageRect, inner: PageRect): Boolean =
    inner.x0 >= outer.x0 - 1f && inner.y0 >= outer.y0 - 1f &&
        inner.x1 <= outer.x1 + 1f && inner.y1 <= outer.y1 + 1f

fun learnCoverCrop(bookPath: String, pageIndex: Int, rect: PageRect) {
    var doc: Document? = null
    var page: Page? = null
    try {
        doc = Document.openDocument(bookPath)
        if (doc.needsPassword() || doc.countPages() <= pageIndex) return
        page = doc.loadPage(pageIndex)
        val others = coverCandidates(page)
        val holder = others.firstOrNull { it.fromEmbeddedImage && contains(it.rect, rect) }
        val chosen = if (holder == null) {
            CoverCandidate(rect, false, 0, 0)
        } else {
            val fx = if (holder.rect.width > 0f) rect.width / holder.rect.width else 1f
            val fy = if (holder.rect.height > 0f) rect.height / holder.rect.height else 1f
            CoverCandidate(
                rect,
                true,
                max(1, (holder.sourceWidth * fx).roundToInt()),
                max(1, (holder.sourceHeight * fy).roundToInt()),
            )
        }
        val scored = scoreCandidates(page, listOf(chosen) + others)
        val good = scored.firstOrNull { it.candidate === chosen } ?: return
        val bad = scored.filter { it.candidate !== chosen && !overlaps(it.candidate.rect, rect) }
        CoverModel.learn(listOf(good.features), bad.map { it.features })
    } catch (t: Throwable) {
        Log.w(TAG, "learnCoverCrop($bookPath): ${t.message}")
    } finally {
        try { page?.destroy() } catch (_: Throwable) {}
        try { doc?.destroy() } catch (_: Throwable) {}
    }
}

object CoverModel {
    private const val LEARNING_RATE = 0.5
    private const val PRIOR_PULL = 0.05
    private const val PASSES = 400

    private val lock = Any()
    private var cached: DoubleArray? = null
    private var examples: MutableList<Pair<DoubleArray, Boolean>>? = null

    private fun file(): File = File(LibraryCache.root.also { it.mkdirs() }, "cover_model.json")

    fun score(features: DoubleArray, weights: DoubleArray = weights()): Double {
        var sum = 0.0
        val n = min(features.size, weights.size)
        for (i in 0 until n) sum += features[i] * weights[i]
        return 1.0 / (1.0 + exp(-sum))
    }

    fun weights(): DoubleArray = synchronized(lock) {
        cached?.let { return it }
        load()
        cached ?: COVER_PRIOR_WEIGHTS.copyOf()
    }

    fun exampleCount(): Int = synchronized(lock) {
        load()
        examples?.size ?: 0
    }

    fun forget() = synchronized(lock) {
        cached = null
        examples = null
    }

    private fun load() {
        if (examples != null && cached != null) return
        val loaded = mutableListOf<Pair<DoubleArray, Boolean>>()
        var stored: DoubleArray? = null
        val f = file()
        if (f.exists()) {
            try {
                val root = JSONObject(f.readText())
                val w = root.optJSONArray("weights")
                if (w != null && w.length() == COVER_PRIOR_WEIGHTS.size) {
                    stored = DoubleArray(w.length()) { w.optDouble(it, COVER_PRIOR_WEIGHTS[it]) }
                }
                val arr = root.optJSONArray("examples")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val fa = o.optJSONArray("features") ?: continue
                        if (fa.length() != COVER_PRIOR_WEIGHTS.size) continue
                        loaded.add(
                            DoubleArray(fa.length()) { fa.optDouble(it, 0.0) } to o.optBoolean("good", false),
                        )
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "cover model load failed: ${t.message}")
            }
        }
        examples = loaded
        cached = stored ?: COVER_PRIOR_WEIGHTS.copyOf()
    }

    private fun save(weights: DoubleArray, rows: List<Pair<DoubleArray, Boolean>>) {
        try {
            val root = JSONObject()
            val w = JSONArray()
            weights.forEach { w.put(it) }
            root.put("weights", w)
            root.put("names", JSONArray(COVER_FEATURE_NAMES))
            val arr = JSONArray()
            for ((features, good) in rows) {
                val o = JSONObject()
                val fa = JSONArray()
                features.forEach { fa.put(it) }
                o.put("features", fa)
                o.put("good", good)
                arr.put(o)
            }
            root.put("examples", arr)
            val f = file()
            val tmp = File(f.path + ".tmp")
            tmp.writeText(root.toString())
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        } catch (t: Throwable) {
            Log.w(TAG, "cover model save failed: ${t.message}")
        }
    }

    fun learn(good: List<DoubleArray>, bad: List<DoubleArray>) = synchronized(lock) {
        load()
        val rows = examples ?: mutableListOf()
        good.forEach { rows.add(it to true) }
        bad.forEach { rows.add(it to false) }
        examples = rows
        val trained = train(rows)
        cached = trained
        save(trained, rows)
    }

    fun train(rows: List<Pair<DoubleArray, Boolean>>): DoubleArray {
        val w = COVER_PRIOR_WEIGHTS.copyOf()
        if (rows.isEmpty()) return w
        val n = rows.size.toDouble()
        repeat(PASSES) {
            val grad = DoubleArray(w.size)
            for ((features, good) in rows) {
                val p = score(features, w)
                val err = p - if (good) 1.0 else 0.0
                for (i in w.indices) grad[i] += err * features[i] / n
            }
            for (i in w.indices) {
                grad[i] += PRIOR_PULL * (w[i] - COVER_PRIOR_WEIGHTS[i])
                w[i] -= LEARNING_RATE * grad[i]
            }
        }
        return w
    }
}

object CoverChoices {
    private val lock = Any()
    private var chosen: MutableMap<String, JSONObject>? = null

    private fun file(): File = File(LibraryCache.root.also { it.mkdirs() }, "cover_choices.json")

    private fun load(): MutableMap<String, JSONObject> {
        chosen?.let { return it }
        val map = mutableMapOf<String, JSONObject>()
        val f = file()
        if (f.exists()) {
            try {
                val root = JSONObject(f.readText())
                for (key in root.keys()) {
                    root.optJSONObject(key)?.let { map[key] = it }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "cover choices load failed: ${t.message}")
            }
        }
        chosen = map
        return map
    }

    private fun save(map: Map<String, JSONObject>) {
        try {
            val root = JSONObject()
            for ((key, value) in map) root.put(key, value)
            val f = file()
            val tmp = File(f.path + ".tmp")
            tmp.writeText(root.toString())
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        } catch (t: Throwable) {
            Log.w(TAG, "cover choices save failed: ${t.message}")
        }
    }

    fun isChosenByHand(bookId: String): Boolean = synchronized(lock) { load().containsKey(bookId) }

    fun rememberPageCrop(bookId: String, pageIndex: Int, rect: PageRect) = synchronized(lock) {
        val map = load()
        map[bookId] = JSONObject().apply {
            put("kind", "page")
            put("page", pageIndex)
            put("x0", rect.x0.toDouble())
            put("y0", rect.y0.toDouble())
            put("x1", rect.x1.toDouble())
            put("y1", rect.y1.toDouble())
        }
        save(map)
    }

    fun rememberFile(bookId: String, from: String) = synchronized(lock) {
        val map = load()
        map[bookId] = JSONObject().apply {
            put("kind", "file")
            put("from", from)
        }
        save(map)
    }

    fun forget(bookId: String) = synchronized(lock) {
        val map = load()
        if (map.remove(bookId) != null) save(map)
    }
}
