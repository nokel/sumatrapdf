package com.sumatrapdf.reader.library

import android.util.Log
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Page
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.StructuredText
import com.artifex.mupdf.fitz.StructuredTextWalker
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

// Port of the scanning half of audiobook/library/shelf.py. The desktop
// gets this from a Python service over HTTP; here it runs in-process
// against the same mupdf the reader already uses.
//
// The index is written as library.json with the desktop's schema, so an
// index built on the phone can be diffed against one built on Windows
// for the same folder.

private const val TAG = "SumatraShelf"

data class Edition(
    val path: String,
    val ext: String,
    val size: Long,
    val pages: Int,
)

data class Book(
    val id: String,
    var path: String,
    val file: String,
    val ext: String,
    var title: String,
    var author: String?,
    val volumes: List<Int>,
    val year: Int?,
    val pages: Int,
    val ink: Int,
    val art: Double?,
    val sample: String,
    val size: Long,
    val mtime: Long,
    var folder: String,
    var seriesFolder: String?,
    var readable: Boolean,
    var authorGuessed: Boolean = false,
    var editions: List<Edition> = emptyList(),
    var hash: String? = null,
    var booknlp: Boolean = false,
    var facts: Boolean = false,
    var analyzed: Boolean = false,
    var genre: String? = null,
    var subgenre: String? = null,
    var series: String? = null,
    var seriesKey: String? = null,
    var seriesKeys: List<String> = emptyList(),
    var kind: String = "book",
    var apiSeries: String? = null,
    var apiSeriesIndex: Int? = null,
    var apiSeriesSource: String? = null,
    var apiAuthor: String? = null,
    var apiSubjects: List<String> = emptyList(),
)

data class LibraryIndex(
    val roots: List<String>,
    val scanned: Long,
    val books: List<Book>,
)

data class FileMeta(
    val pages: Int,
    val title: String?,
    val author: String?,
    val ink: Int,
    val art: Double?,
    val sample: String,
)

fun bookId(path: String): String {
    val abs = File(path).absolutePath.lowercase()
    val digest = MessageDigest.getInstance("SHA-1").digest(abs.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }.take(16)
}

// audiobook/pdfbook.py::book_hash — the key a BookNLP cache directory is
// named after, so the wiki can be found for a book that was analysed on
// the desktop and copied across.
fun contentHash(path: String): String? = try {
    val digest = MessageDigest.getInstance("SHA-1")
    File(path).inputStream().use { input ->
        val buffer = ByteArray(1 shl 20)
        while (true) {
            val n = input.read(buffer)
            if (n <= 0) break
            digest.update(buffer, 0, n)
        }
    }
    digest.digest().joinToString("") { "%02x".format(it) }.take(16)
} catch (t: Throwable) {
    Log.w(TAG, "contentHash($path) failed: ${t.message}")
    null
}

const val STEXT_TEXT_OPTIONS =
    "preserve-ligatures,preserve-whitespace,use-cid-for-unknown-unicode"

const val STEXT_IMAGE_OPTIONS = "preserve-images,clip=no"

fun cutCodePoints(text: String, limit: Int): String {
    if (limit <= 0) return ""
    if (text.length <= limit) return text
    if (text.codePointCount(0, text.length) <= limit) return text
    return text.substring(0, text.offsetByCodePoints(0, limit))
}

fun inkLength(text: String): Int = text.codePointCount(0, text.length)

fun isPythonSpace(c: Char): Boolean = when (c) {
    ' ', '\u0085', '\u00A0', '\u1680', '\u2028', '\u2029',
    '\u202F', '\u205F', '\u3000',
    -> true
    else -> c in '\u0009'..'\u000D' || c in '\u001C'..'\u001F' ||
        c in '\u2000'..'\u200A'
}

private val PYTHON_SPACE_RUN = Regex(
    "[\\u0009-\\u000D\\u001C-\\u001F \\u0085\\u00A0\\u1680" +
        "\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]+",
)

fun stripPythonSpace(text: String): String = text.trim { isPythonSpace(it) }

fun squeezePythonSpace(text: String): String = PYTHON_SPACE_RUN.replace(text, " ")

private val PYTHON_LINE_BREAK = Regex(
    "\\r\\n|[\\n\\r\\u000B\\u000C\\u001C\\u001D\\u001E\\u0085\\u2028\\u2029]",
)

fun pythonLines(text: String): List<String> = PYTHON_LINE_BREAK.split(text)

fun pageText(page: Page): String {
    val stext = try {
        page.toStructuredText(STEXT_TEXT_OPTIONS)
    } catch (t: Throwable) {
        Log.w(TAG, "toStructuredText failed: ${t.message}")
        return ""
    }
    return try {
        val out = StringBuilder()
        for (block in stext.blocks ?: emptyArray()) {
            for (line in block.lines ?: emptyArray()) {
                var last = 0
                for (ch in line.chars ?: emptyArray()) {
                    out.appendCodePoint(ch.c)
                    last = ch.c
                }
                if (last != '\n'.code && last > 0) out.append('\n')
            }
        }
        out.toString()
    } catch (t: Throwable) {
        Log.w(TAG, "pageText failed: ${t.message}")
        ""
    } finally {
        try { stext.destroy() } catch (_: Throwable) {}
    }
}

// shelf.py::_page_look - samples pages across the document for how much
// text they carry (ink), how much of the page a single image covers
// (art), and a text sample the genre rules read.
private fun pageLook(doc: Document): Triple<Int, Double?, String> {
    val n = doc.countPages()
    if (n <= 0) return Triple(0, null, "")
    val step = max(1, n - 1) / max(1, PAGE_SAMPLES - 1).toFloat()
    val picks = (0 until PAGE_SAMPLES)
        .map { minOf(n - 1, (it * step).roundToInt()) }
        .distinct()
        .sorted()
    val ink = mutableListOf<Int>()
    val words = mutableListOf<String>()
    var art = 0
    val perPick = SAMPLE_CHARS / max(1, picks.size)

    for (i in picks) {
        val page = try { doc.loadPage(i) } catch (_: Throwable) { null } ?: continue
        try {
            val text = stripPythonSpace(pageText(page))
            ink.add(inkLength(text))
            words.add(cutCodePoints(text, perPick))

            val bounds = page.bounds
            val area = abs(
                (bounds.x1 - bounds.x0).toDouble() * (bounds.y1 - bounds.y0).toDouble(),
            ).takeIf { it > 0.0 } ?: 1.0
            var biggest = 0.0
            val shapes = try {
                page.toStructuredText(STEXT_IMAGE_OPTIONS)
            } catch (t: Throwable) {
                Log.w(TAG, "image stext failed: ${t.message}")
                null
            }
            if (shapes != null) {
                try {
                    shapes.walk(object : ImageBlockWalker() {
                        override fun onImageBlock(rect: Rect?, ctm: Matrix?, img: Image?) {
                            if (rect == null) return
                            val wide = abs(
                                (rect.x1 - rect.x0).toDouble() *
                                    (rect.y1 - rect.y0).toDouble(),
                            )
                            biggest = max(biggest, wide / area)
                        }
                    })
                } catch (_: Throwable) {
                } finally {
                    try { shapes.destroy() } catch (_: Throwable) {}
                }
            }
            if (biggest >= ART_SHARE) art++
        } finally {
            try { page.destroy() } catch (_: Throwable) {}
        }
    }
    if (ink.isEmpty()) return Triple(0, null, "")
    ink.sort()
    val sample = cutCodePoints(
        stripPythonSpace(squeezePythonSpace(words.joinToString(" "))),
        SAMPLE_CHARS,
    )
    return Triple(
        ink[ink.size / 2],
        ((art / ink.size.toDouble()) * 100).roundToInt() / 100.0,
        sample,
    )
}

fun fileMeta(path: String): FileMeta {
    var doc: Document? = null
    var pages = 0
    var title: String? = null
    var author: String? = null
    var ink = 0
    var art: Double? = null
    var sample = ""
    try {
        doc = Document.openDocument(path)
        if (doc.needsPassword()) return FileMeta(0, null, null, 0, null, "")
        pages = doc.countPages()
        val stem = File(path).nameWithoutExtension
        title = metaOk(runCatching { doc.getMetaData(Document.META_INFO_TITLE) }.getOrNull(), stem)
        author = metaAuthor(runCatching { doc.getMetaData(Document.META_INFO_AUTHOR) }.getOrNull(), stem)
        val look = pageLook(doc)
        ink = look.first
        art = look.second
        sample = look.third
    } catch (t: Throwable) {
        Log.w(TAG, "fileMeta($path) failed: ${t.javaClass.simpleName}: ${t.message}")
    } finally {
        try { doc?.destroy() } catch (_: Throwable) {}
    }
    return FileMeta(pages, title, author, ink, art, sample)
}

// shelf.py::_walk — depth-first, names sorted inside each folder, with
// the skip list pruned before descending. `seen` is shared across the
// roots of one scan so a folder reached from two of them is walked once.
fun walkBooks(
    root: File,
    seen: MutableSet<String> = mutableSetOf(),
    stop: () -> Boolean = { false },
    onFound: ((File) -> Unit)? = null,
): List<File> {
    val out = mutableListOf<File>()
    fun visit(dir: File) {
        if (stop()) return
        if (!seen.add(dir.absolutePath.lowercase())) return
        val names = dir.listFiles() ?: return
        val files = names.filter { it.isFile }.sortedBy { it.name }
        for (f in files) {
            if (!isBookFile(f.name)) continue
            out.add(f)
            onFound?.invoke(f)
        }
        val dirs = names.filter { it.isDirectory && !skipDir(it.name) }.sortedBy { it.name }
        for (d in dirs) {
            if (stop()) return
            if (isRedirect(d)) continue
            visit(d)
        }
    }
    visit(root)
    return out
}

fun seriesFolderOf(path: String, roots: List<String>): String? {
    val stops = roots.map { File(it).absolutePath.lowercase() }.toSet()
    val folder = File(path).absoluteFile.parent ?: return null
    if (folder.lowercase() in stops) return null
    return folder
}

fun interface ScanProgress {
    fun onProgress(done: Int, total: Int, title: String)
}

fun scan(
    roots: List<String>,
    previous: LibraryIndex? = null,
    progress: ScanProgress? = null,
    stop: () -> Boolean = { false },
): LibraryIndex {
    val absRoots = roots.map { File(it).absolutePath }
    val old = (previous?.books ?: emptyList()).associateBy { it.path.lowercase() }

    val files = mutableListOf<File>()
    val seen = mutableSetOf<String>()
    for (r in absRoots) {
        val dir = File(r)
        if (!dir.isDirectory) continue
        walkBooks(dir, seen, stop) { found ->
            files.add(found)
            if (files.size % 25 == 0) {
                progress?.onProgress(files.size, 0, found.parentFile?.name ?: "")
            }
        }
    }

    val books = mutableListOf<Book>()
    for ((i, f) in files.withIndex()) {
        if (stop()) break
        val path = f.absolutePath
        val size = f.length()
        val mtime = f.lastModified()
        val prev = old[path.lowercase()]
        // shelf.py reuses the previous entry when size and mtime match,
        // which is what makes a re-scan cheap; a guessed author is
        // dropped so _fill_series_authors can redo it.
        val fresh = prev != null && prev.size == size && abs(prev.mtime - mtime) < 1000L
        val stem = f.nameWithoutExtension

        val entry: Book = if (fresh) {
            prev!!.copy(
                author = if (prev.authorGuessed) null else prev.author,
                authorGuessed = false,
            )
        } else {
            val info = fileMeta(path)
            val parsed = parseName(stem)
            var title = parsed.title
            if (info.title != null && stemIsPoor(stem)) {
                title = titlecase(cleanName(info.title))
            }
            Book(
                id = bookId(path),
                path = path,
                file = f.name,
                ext = "." + f.extension.lowercase(),
                title = title,
                author = parsed.author ?: info.author,
                volumes = parsed.volumes,
                year = parsed.year,
                pages = info.pages,
                ink = info.ink,
                art = info.art,
                sample = info.sample,
                size = size,
                mtime = mtime,
                folder = f.parent ?: "",
                seriesFolder = null,
                readable = false,
            )
        }
        entry.folder = f.parent ?: ""
        entry.seriesFolder = seriesFolderOf(path, absRoots)
        entry.readable = entry.ext in READABLE_EXTS
        // Try the cached online series lookup; the sweep refreshes this
        // in the background, but most libraries hit a warm cache here.
        cachedSeries(entry.id)?.let { hit ->
            entry.apiSeries = hit.name
            entry.apiSeriesIndex = hit.index
            entry.apiSeriesSource = hit.source
            entry.apiAuthor = hit.author
            entry.apiSubjects = hit.subjects
        }
        entry.kind = if (looksLikeBook(
                path = entry.path,
                ext = entry.ext,
                pages = entry.pages,
                art = entry.art,
                sample = entry.sample,
                title = entry.title,
                author = entry.author,
                file = entry.file,
            )) "book" else "document"
        books.add(entry)
        progress?.onProgress(i + 1, files.size, entry.title)
    }

    attachAnalysis(books)
    fillSeriesAuthors(books)
    val kept = dedupe(books).toMutableList()
    buildCatalogue(kept, absRoots)
    return LibraryIndex(
        roots = absRoots,
        scanned = System.currentTimeMillis(),
        books = kept,
    )
}

// shelf.py::_fill_series_authors — a book with no author of its own
// inherits the folder's dominant author, but only when that author owns
// most of the folder.
fun fillSeriesAuthors(books: List<Book>) {
    val tally = mutableMapOf<String, MutableMap<String, Int>>()
    val total = mutableMapOf<String, Int>()
    for (b in books) {
        val home = b.seriesFolder ?: continue
        val key = home.lowercase()
        total[key] = (total[key] ?: 0) + 1
        val author = b.author ?: continue
        val seen = tally.getOrPut(key) { mutableMapOf() }
        seen[author] = (seen[author] ?: 0) + 1
    }
    for (b in books) {
        if (b.author != null) continue
        val home = b.seriesFolder ?: continue
        val seen = tally[home.lowercase()] ?: continue
        val best = seen.maxByOrNull { it.value } ?: continue
        if (best.value >= 2 && best.value >= SERIES_AUTHOR_SHARE * (total[home.lowercase()] ?: 0)) {
            b.author = best.key
            b.authorGuessed = true
        }
    }
}

private fun folderAffinity(books: List<Book>): Map<Pair<String, String?>, Set<String>> {
    val out = mutableMapOf<Pair<String, String?>, MutableSet<String>>()
    for (b in books) {
        val author = b.author ?: continue
        val key = b.folder.lowercase() to author
        out.getOrPut(key) { mutableSetOf() }.add(normKey(b.title, null).first)
    }
    return out
}

// shelf.py::_rank — which edition of the same book becomes the primary:
// one with a BookNLP analysis wins, then a PDF, then the deeper folder,
// then the author's biggest presence in that folder, then pages, size.
private fun rankOf(book: Book, affinity: Map<Pair<String, String?>, Set<String>>): List<Long> {
    val folder = book.folder.lowercase()
    return listOf(
        if (book.booknlp) 1L else 0L,
        if (book.ext == ".pdf") 1L else 0L,
        folder.count { it == File.separatorChar }.toLong(),
        (affinity[folder to book.author]?.size ?: 0).toLong(),
        book.pages.toLong(),
        book.size,
    )
}

private fun compareRanks(a: List<Long>, b: List<Long>): Int {
    for (i in a.indices) {
        val c = a[i].compareTo(b[i])
        if (c != 0) return c
    }
    return 0
}

// shelf.py::_fold_authorless — an authorless copy joins its authored
// twin, but only when exactly one twin exists.
private fun foldAuthorless(
    groups: MutableMap<Pair<Pair<String, String>, List<Int>>, MutableList<Book>>,
) {
    val authored = mutableMapOf<Pair<String, List<Int>>, MutableList<Pair<Pair<String, String>, List<Int>>>>()
    for ((key, _) in groups) {
        val (norm, volumes) = key
        if (norm.second.isNotEmpty()) {
            authored.getOrPut(norm.first to volumes) { mutableListOf() }.add(key)
        }
    }
    for (key in groups.keys.toList()) {
        val (norm, volumes) = key
        if (norm.second.isNotEmpty()) continue
        val hosts = authored[norm.first to volumes] ?: continue
        if (hosts.size != 1) continue
        val moved = groups.remove(key) ?: continue
        groups[hosts[0]]?.addAll(moved)
    }
}

fun dedupe(books: List<Book>): List<Book> {
    val affinity = folderAffinity(books)
    val groups = mutableMapOf<Pair<Pair<String, String>, List<Int>>, MutableList<Book>>()
    for (b in books) {
        val key = normKey(b.title, b.author) to b.volumes
        groups.getOrPut(key) { mutableListOf() }.add(b)
    }
    foldAuthorless(groups)

    val out = mutableListOf<Book>()
    for ((_, group) in groups) {
        group.sortWith { x, y -> compareRanks(rankOf(y, affinity), rankOf(x, affinity)) }
        val primary = group.first()
        primary.editions = group.map {
            Edition(path = it.path, ext = it.ext, size = it.size, pages = it.pages)
        }
        primary.booknlp = group.any { it.booknlp }
        group.firstOrNull { it.booknlp }?.let { g ->
            primary.hash = g.hash
            primary.path = g.path
            primary.folder = g.folder
            primary.seriesFolder = g.seriesFolder
        }
        out.add(primary)
    }
    out.sortWith(
        compareBy<Book> { (it.seriesFolder ?: "").lowercase() }
            .thenBy { it.volumes.firstOrNull() ?: 9999 }
            .thenBy { it.title.lowercase() },
    )
    return out
}

// ---- library.json, the desktop's schema ----

fun bookToJson(b: Book): JSONObject {
    val o = JSONObject()
    o.put("id", b.id)
    o.put("path", b.path)
    o.put("file", b.file)
    o.put("ext", b.ext)
    o.put("title", b.title)
    o.put("author", b.author ?: JSONObject.NULL)
    o.put("volumes", JSONArray(b.volumes))
    o.put("year", b.year ?: JSONObject.NULL)
    o.put("pages", b.pages)
    o.put("ink", b.ink)
    o.put("art", b.art ?: JSONObject.NULL)
    o.put("sample", b.sample)
    o.put("size", b.size)
    o.put("mtime", b.mtime / 1000.0)
    o.put("folder", b.folder)
    o.put("series_folder", b.seriesFolder ?: JSONObject.NULL)
    o.put("readable", b.readable)
    if (b.authorGuessed) o.put("author_guessed", true)
    o.put("hash", b.hash ?: JSONObject.NULL)
    o.put("booknlp", b.booknlp)
    o.put("facts", b.facts)
    o.put("analyzed", b.analyzed)
    o.put("genre", b.genre ?: JSONObject.NULL)
    o.put("subgenre", b.subgenre ?: JSONObject.NULL)
    o.put("series", b.series ?: JSONObject.NULL)
    o.put("series_key", b.seriesKey ?: JSONObject.NULL)
    o.put("series_keys", JSONArray(b.seriesKeys))
    o.put("kind", b.kind)
    o.put("api_series", b.apiSeries ?: JSONObject.NULL)
    o.put("api_series_index", b.apiSeriesIndex ?: JSONObject.NULL)
    o.put("api_series_source", b.apiSeriesSource ?: JSONObject.NULL)
    o.put("api_author", b.apiAuthor ?: JSONObject.NULL)
    o.put("api_subjects", JSONArray(b.apiSubjects))
    val eds = JSONArray()
    b.editions.forEach { e ->
        val eo = JSONObject()
        eo.put("path", e.path)
        eo.put("ext", e.ext)
        eo.put("size", e.size)
        eo.put("pages", e.pages)
        eds.put(eo)
    }
    o.put("editions", eds)
    return o
}

fun bookFromJson(o: JSONObject): Book? {
    val path = o.optString("path", "")
    if (path.isBlank()) return null
    val volumes = o.optJSONArray("volumes")?.let { arr ->
        (0 until arr.length()).map { arr.optInt(it) }
    } ?: emptyList()
    fun strOrEmpty(key: String): String =
        if (o.isNull(key)) "" else o.optString(key, "")
    fun strOrNull(key: String): String? =
        if (o.isNull(key)) null else o.optString(key, "").takeIf { it.isNotBlank() && it != "null" }
    val b = Book(
        id = o.optString("id", bookId(path)),
        path = path,
        file = strOrEmpty("file").ifBlank { File(path).name },
        ext = strOrEmpty("ext").ifBlank { "." + File(path).extension.lowercase() },
        title = strOrEmpty("title").ifBlank { File(path).nameWithoutExtension },
        author = strOrNull("author"),
        volumes = volumes,
        year = if (o.isNull("year")) null else o.optInt("year"),
        pages = o.optInt("pages", 0),
        ink = o.optInt("ink", 0),
        art = if (o.isNull("art")) null else o.optDouble("art"),
        sample = strOrEmpty("sample"),
        size = o.optLong("size", 0L),
        mtime = (o.optDouble("mtime", 0.0) * 1000).toLong(),
        folder = strOrEmpty("folder").ifBlank { File(path).parent ?: "" },
        seriesFolder = strOrNull("series_folder"),
        readable = o.optBoolean("readable", false),
        authorGuessed = o.optBoolean("author_guessed", false),
    )
    b.hash = strOrNull("hash")
    b.booknlp = o.optBoolean("booknlp", false)
    b.facts = o.optBoolean("facts", false)
    b.analyzed = o.optBoolean("analyzed", false)
    b.genre = strOrNull("genre")
    b.subgenre = strOrNull("subgenre")
    b.series = strOrNull("series")
    b.seriesKey = strOrNull("series_key")
    b.seriesKeys = o.optJSONArray("series_keys")?.let { arr ->
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() && s != "null" } }
    } ?: emptyList()
    b.kind = strOrEmpty("kind").ifBlank { "book" }
    b.apiSeries = strOrNull("api_series")
    b.apiSeriesIndex = if (o.isNull("api_series_index")) null else o.optInt("api_series_index").takeIf { it > 0 }
    b.apiSeriesSource = strOrNull("api_series_source")
    b.apiAuthor = strOrNull("api_author")
    b.apiSubjects = o.optJSONArray("api_subjects")?.let { arr ->
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { it.isNotBlank() } }
    } ?: emptyList()
    b.editions = o.optJSONArray("editions")?.let { arr ->
        (0 until arr.length()).mapNotNull { i ->
            val eo = arr.optJSONObject(i) ?: return@mapNotNull null
            Edition(
                path = eo.optString("path", ""),
                ext = eo.optString("ext", ""),
                size = eo.optLong("size", 0L),
                pages = eo.optInt("pages", 0),
            )
        }
    } ?: emptyList()
    return b
}

fun indexToJson(index: LibraryIndex): String {
    val root = JSONObject()
    root.put("roots", JSONArray(index.roots))
    root.put("scanned", index.scanned / 1000.0)
    val arr = JSONArray()
    index.books.forEach { arr.put(bookToJson(it)) }
    root.put("books", arr)
    return root.toString()
}

fun indexFromJson(text: String): LibraryIndex? = try {
    val root = JSONObject(text)
    val roots = root.optJSONArray("roots")?.let { arr ->
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    } ?: emptyList()
    val books = root.optJSONArray("books")?.let { arr ->
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { bookFromJson(it) } }
    } ?: emptyList()
    LibraryIndex(roots, (root.optDouble("scanned", 0.0) * 1000).toLong(), books)
} catch (t: Throwable) {
    Log.w(TAG, "indexFromJson failed: ${t.message}")
    null
}

// The walker interface has ten members and only the image block matters
// here; this gives the rest empty bodies so call sites stay readable.
internal abstract class ImageBlockWalker : StructuredTextWalker {
    override fun beginTextBlock(rect: Rect?, flags: Int) {}
    override fun endTextBlock() {}
    override fun beginLine(rect: Rect?, wmode: Int, direction: Point?) {}
    override fun endLine() {}
    override fun onChar(
        c: Int, origin: Point?, font: com.artifex.mupdf.fitz.Font?, size: Float,
        quad: Quad?, argb: Int, flags: Int, bidi: Int,
    ) {}
    override fun beginStruct(standard: String?, raw: String?, index: Int) {}
    override fun endStruct() {}
    override fun onVector(rect: Rect?, info: StructuredTextWalker.VectorInfo?, argb: Int) {}
}
