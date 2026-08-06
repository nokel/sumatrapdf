package com.sumatrapdf.reader.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Page
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min

// Port of audiobook/library/covers.py. The first page's candidate regions
// are ranked by CoverVision and the winner is rendered; a near-white result
// means the file opens on a title page, so the online art is preferred.

private const val TAG = "SumatraCovers"

const val COVER_HEIGHT = 520
const val POSTER_HEIGHT = 300
const val MIN_IMAGE_SHARE = 0.35
const val INSET_IMAGE_SHARE = 0.08
const val TEXT_PAGE_WHITE = 0.9
const val PLAIN_PAGE_WHITE = 0.82
const val COVER_MIN_BYTES = 1200

const val COVER_PICKER_VERSION = 2

private val buildLocks = mutableMapOf<String, Any>()

private fun lockFor(bookId: String): Any = synchronized(buildLocks) {
    buildLocks.getOrPut(bookId) { Any() }
}

fun coverDir(): File = LibraryCache.dir("covers")

fun coverPath(bookId: String): File = File(coverDir(), "$bookId.jpg")

fun posterPath(key: String): File = File(LibraryCache.dir("posters"), "${Net.keyFor(key)}.jpg")

fun haveCover(bookId: String): Boolean = coverPath(bookId).exists()

fun encodeBitmap(bitmap: Bitmap, height: Int = COVER_HEIGHT): ByteArray? {
    return try {
        val scaled = if (bitmap.height > height) {
            val scale = height / bitmap.height.toFloat()
            Bitmap.createScaledBitmap(
                bitmap,
                max(1, (bitmap.width * scale).toInt()),
                max(1, (bitmap.height * scale).toInt()),
                true,
            )
        } else {
            bitmap
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
        if (scaled !== bitmap) scaled.recycle()
        out.toByteArray()
    } catch (t: Throwable) {
        Log.w(TAG, "encode failed: ${t.message}")
        null
    }
}

fun decodeCoverBytes(data: ByteArray): Bitmap? = try {
    BitmapFactory.decodeByteArray(data, 0, data.size)
} catch (_: Throwable) {
    null
}

data class CoverFromFile(val data: ByteArray?, val white: Double, val embedded: Boolean)

private fun coverHeightFor(candidate: CoverCandidate?): Int {
    if (candidate == null || !candidate.fromEmbeddedImage) return COVER_HEIGHT
    if (candidate.sourceHeight <= 0) return COVER_HEIGHT
    return max(1, min(COVER_HEIGHT, candidate.sourceHeight))
}

fun coverFromFile(path: String, pageIndex: Int = 0, crop: PageRect? = null): CoverFromFile {
    var doc: Document? = null
    var page: Page? = null
    try {
        doc = Document.openDocument(path)
        if (doc.needsPassword()) return CoverFromFile(null, 1.0, false)
        if (doc.countPages() <= pageIndex) return CoverFromFile(null, 1.0, false)
        page = doc.loadPage(pageIndex)
        val bounds = page.bounds
        val whole = PageRect(bounds.x0, bounds.y0, bounds.x1, bounds.y1)
        val best = if (crop == null) pickCover(page) else null
        var rect = crop ?: best?.candidate?.rect ?: whole
        var embedded = crop == null && best?.candidate?.fromEmbeddedImage == true
        var bitmap = renderPageRegion(page, rect, coverHeightFor(best?.candidate))
        var data = bitmap?.let { encodeBitmap(it) }
        if (embedded && (data == null || data.size < COVER_MIN_BYTES)) {
            bitmap?.recycle()
            embedded = false
            rect = whole
            bitmap = renderPageRegion(page, whole, COVER_HEIGHT)
            data = bitmap?.let { encodeBitmap(it) }
        }
        val white = bitmap?.let { whiteShareOf(it) } ?: 1.0
        bitmap?.recycle()
        return CoverFromFile(data, white, embedded)
    } catch (t: Throwable) {
        Log.w(TAG, "coverFromFile($path): ${t.javaClass.simpleName}: ${t.message}")
        return CoverFromFile(null, 1.0, false)
    } finally {
        try { page?.destroy() } catch (_: Throwable) {}
        try { doc?.destroy() } catch (_: Throwable) {}
    }
}

fun coverFromOnline(title: String?, author: String?, isbn: String?, context: String?): ByteArray? {
    for (url in coverUrls(title, author, isbn, context)) {
        val data = Net.fetch(url, accept = "image/*")
        if (data != null && data.size >= COVER_MIN_BYTES) return data
    }
    return null
}

fun writeCover(bookId: String, data: ByteArray): File? {
    val path = coverPath(bookId)
    return try {
        val tmp = File(path.path + ".tmp")
        tmp.writeBytes(data)
        if (path.exists()) path.delete()
        if (tmp.renameTo(path)) path else null
    } catch (t: Throwable) {
        Log.w(TAG, "writeCover($bookId) failed: ${t.message}")
        null
    }
}

fun buildCover(book: Book, force: Boolean = false): File? {
    synchronized(lockFor(book.id)) {
        val path = coverPath(book.id)
        if (path.exists() && (!force || CoverChoices.isChosenByHand(book.id))) return path
        return try {
            val found = coverFromFile(book.path)
            val limit = if (found.embedded) TEXT_PAGE_WHITE else PLAIN_PAGE_WHITE
            var data = found.data
            if (data == null || found.white >= limit) {
                val isbn = cachedIsbn(book.id)
                data = coverFromOnline(book.title, book.author, isbn, book.series) ?: data
            }
            if (data == null) null else writeCover(book.id, data)
        } catch (t: Throwable) {
            Log.w(TAG, "buildCover(${book.title}) failed: ${t.message}")
            null
        }
    }
}

fun setCoverFromBitmap(book: Book, bitmap: Bitmap): File? {
    synchronized(lockFor(book.id)) {
        val data = encodeBitmap(bitmap) ?: return null
        return writeCover(book.id, data)
    }
}

private fun coverStamp(): File = File(LibraryCache.root, "cover_version.txt")

fun coversArePicked(): Boolean = try {
    coverStamp().takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() == COVER_PICKER_VERSION
} catch (_: Throwable) {
    false
}

fun markCoversPicked() {
    try {
        coverStamp().writeText(COVER_PICKER_VERSION.toString())
    } catch (t: Throwable) {
        Log.w(TAG, "cover stamp failed: ${t.message}")
    }
}

fun coverRefreshSweep(
    books: List<Book>,
    onCover: ((Book) -> Unit)? = null,
    stop: () -> Boolean = { false },
): Int {
    var made = 0
    for (b in books) {
        if (stop()) break
        if (CoverChoices.isChosenByHand(b.id)) continue
        if (buildCover(b, force = true) != null) {
            made++
            onCover?.invoke(b)
        }
    }
    return made
}

private fun cachedIsbn(bookId: String): String? {
    val file = File(LibraryCache.dir("meta"), "$bookId.json")
    if (!file.exists()) return null
    return try {
        metaFromJson(org.json.JSONObject(file.readText())).isbn
    } catch (_: Throwable) {
        null
    }
}

fun buildPoster(url: String, key: String = url): File? {
    val path = posterPath(key)
    if (path.exists()) return path
    val data = Net.fetch(url, accept = "image/*")
    if (data == null || data.size < COVER_MIN_BYTES) return null
    return try {
        val tmp = File(path.path + ".tmp")
        tmp.writeBytes(data)
        if (path.exists()) path.delete()
        if (tmp.renameTo(path)) path else null
    } catch (_: Throwable) {
        null
    }
}

fun coverSweep(books: List<Book>, log: ((String) -> Unit)? = null, stop: () -> Boolean = { false }): Int {
    var made = 0
    for (b in books) {
        if (stop()) break
        if (haveCover(b.id)) continue
        if (buildCover(b) != null) {
            made++
            log?.invoke("cover: ${b.title}")
        }
    }
    return made
}
