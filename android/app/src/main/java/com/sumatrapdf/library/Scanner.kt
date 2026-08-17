package com.sumatrapdf.library

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Font
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.StructuredTextWalker
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

const val REFLOW_WIDTH = 612f
const val REFLOW_HEIGHT = 792f
const val REFLOW_EM = 11f

fun pathKey(path: String?) = (path ?: "").lowercase()

fun readableExt(ext: String) = ext.lowercase() in READABLE_EXTS

fun isBookFile(name: String): Boolean {
    val low = name.lowercase()
    return BOOK_EXTS.any { low.endsWith(it) }
}

fun skipDir(name: String) = name.startsWith(".") || name.lowercase() in SKIP_DIR_NAMES

object Scanner {

    fun storageVolumes(context: Context): List<File> {
        val out = ArrayList<File>()
        val seen = HashSet<String>()

        fun take(dir: File?) {
            if (dir == null || !dir.isDirectory) return
            val key = pathKey(dir.absolutePath)
            if (seen.add(key)) out.add(dir)
        }

        take(Environment.getExternalStorageDirectory())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val manager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            manager?.storageVolumes?.forEach { take(it.directory) }
        }
        return out
    }

    private fun hasBooks(folder: File): Boolean {
        val names = folder.list() ?: return false
        return names.any { isBookFile(it) }
    }

    private fun scanForLibraries(root: File, depth: Int, budget: IntArray): List<File> {
        val hits = ArrayList<File>()
        val stack = ArrayDeque<Pair<File, Int>>()
        stack.addLast(Pair(root, 0))
        while (stack.isNotEmpty() && budget[0] > 0) {
            val (folder, level) = stack.removeLast()
            budget[0] -= 1
            val names = folder.list() ?: continue
            if (names.any { isBookFile(it) }) {
                hits.add(folder)
                continue
            }
            if (level >= depth) continue
            for (n in names) {
                val sub = File(folder, n)
                if (skipDir(n) || !sub.isDirectory) continue
                stack.addLast(Pair(sub, level + 1))
            }
        }
        return hits
    }

    private fun outermost(roots: List<String>): List<String> {
        val out = ArrayList<String>()
        for (p in roots.sortedBy { it.length }) {
            val low = pathKey(p)
            if (out.any { low.startsWith(pathKey(it) + File.separator) }) continue
            out.add(p)
        }
        return out
    }

    fun discoverRoots(context: Context, extra: List<String> = emptyList()): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()

        fun take(path: String?) {
            if (path.isNullOrEmpty()) return
            val dir = File(path)
            if (!dir.isDirectory) return
            val key = pathKey(dir.absolutePath)
            if (seen.add(key)) out.add(dir.absolutePath)
        }

        for (p in extra) take(p)

        val volumes = storageVolumes(context)
        val bases = ArrayList<File>()
        for (v in volumes) {
            bases.add(v)
            bases.add(File(v, Environment.DIRECTORY_DOCUMENTS))
            bases.add(File(v, Environment.DIRECTORY_DOWNLOADS))
        }
        for (base in bases) {
            for (name in LIBRARY_DIR_NAMES) take(File(base, name).absolutePath)
        }

        if (out.isEmpty()) {
            val budget = intArrayOf(SCAN_BUDGET)
            for (volume in volumes) {
                for (hit in scanForLibraries(volume, SCAN_DEPTH, budget)) {
                    take(hit.absolutePath)
                }
            }
        }
        return outermost(out)
    }

    private fun walk(root: File): List<File> {
        val out = ArrayList<File>()
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val folder = stack.removeLast()
            val names = folder.list()?.sorted() ?: continue
            for (n in names) {
                val here = File(folder, n)
                if (here.isDirectory) {
                    if (!skipDir(n)) stack.addLast(here)
                } else if (isBookFile(n)) {
                    out.add(here)
                }
            }
        }
        return out
    }

    private fun seriesFolder(path: File, roots: Set<String>): String? {
        val folder = path.absoluteFile.parent ?: return null
        if (pathKey(folder) in roots) return null
        return folder
    }

    private class ImageBoxes : StructuredTextWalker {
        val boxes = ArrayList<Rect>()
        override fun onImageBlock(bbox: Rect, ctm: Matrix, img: Image) {
            boxes.add(bbox)
        }

        override fun beginTextBlock(bbox: Rect, flags: Int) {}
        override fun endTextBlock() {}
        override fun beginLine(bbox: Rect, wmode: Int, dir: Point) {}
        override fun endLine() {}
        override fun onChar(
            c: Int, origin: Point, font: Font, size: Float, quad: Quad,
            argb: Int, flags: Int, bidi: Int,
        ) {
        }

        override fun beginStruct(standard: String?, raw: String?, index: Int) {}
        override fun endStruct() {}
        override fun onVector(bbox: Rect, info: StructuredTextWalker.VectorInfo?, argb: Int) {}
    }

    class FileMeta {
        var pages = 0
        var title: String? = null
        var author: String? = null
        var ink = 0
        var art: Double? = null
        var sample = ""
    }

    private fun pageLook(doc: Document, meta: FileMeta) {
        val n = doc.countPages()
        if (n <= 0) return
        val step = maxOf(1, n - 1) / maxOf(1, PAGE_SAMPLES - 1).toFloat()
        val picks = sortedSetOf<Int>()
        for (i in 0 until PAGE_SAMPLES) picks.add(minOf(n - 1, (i * step).roundToInt()))
        val ink = ArrayList<Int>()
        val words = ArrayList<String>()
        var art = 0
        val slice = SAMPLE_CHARS / picks.size
        for (i in picks) {
            val page = try {
                doc.loadPage(i)
            } catch (e: Throwable) {
                continue
            }
            try {
                val structured = page.toStructuredText()
                val text = (structured.asText() ?: "").trim()
                ink.add(text.length)
                words.add(text.take(slice))
                val bounds = page.bounds
                val area = abs((bounds.x1 - bounds.x0) * (bounds.y1 - bounds.y0)).toDouble()
                    .let { if (it <= 0.0) 1.0 else it }
                val walker = ImageBoxes()
                structured.walk(walker)
                var big = 0.0
                for (box in walker.boxes) {
                    val wide = abs((box.x1 - box.x0) * (box.y1 - box.y0)).toDouble()
                    if (wide / area > big) big = wide / area
                }
                if (big >= ART_SHARE) art += 1
                structured.destroy()
            } catch (e: Throwable) {
                ink.add(0)
            } finally {
                page.destroy()
            }
        }
        if (ink.isEmpty()) return
        ink.sort()
        meta.ink = ink[ink.size / 2]
        meta.art = Math.round(art / ink.size.toDouble() * 100.0) / 100.0
        meta.sample = Regex("""\s+""").replace(words.joinToString(" "), " ").trim().take(SAMPLE_CHARS)
    }

    fun fileMeta(path: File): FileMeta {
        val meta = FileMeta()
        val doc = try {
            Document.openDocument(path.absolutePath)
        } catch (e: Throwable) {
            return meta
        }
        try {
            if (doc.isReflowable) doc.layout(REFLOW_WIDTH, REFLOW_HEIGHT, REFLOW_EM)
            meta.pages = doc.countPages()
            val stem = path.nameWithoutExtension
            meta.title = metaOk(doc.getMetaData(Document.META_INFO_TITLE), stem)
            meta.author = metaAuthor(doc.getMetaData(Document.META_INFO_AUTHOR), stem)
        } catch (e: Throwable) {
        }
        try {
            pageLook(doc, meta)
        } catch (e: Throwable) {
        } finally {
            doc.destroy()
        }
        return meta
    }

    fun scan(
        roots: List<String>,
        previous: LibraryIndex?,
        progress: ((Int, Int, String) -> Unit)? = null,
        stopped: () -> Boolean = { false },
    ): LibraryIndex {
        val rootKeys = roots.map { pathKey(it) }.toSet()
        val old = HashMap<String, Book>()
        previous?.books?.forEach { old[pathKey(it.path)] = it }

        val files = ArrayList<File>()
        for (r in roots) {
            val dir = File(r)
            if (dir.isDirectory) files.addAll(walk(dir))
        }
        files.sortBy { it.absolutePath }

        val books = ArrayList<Book>()
        for ((i, path) in files.withIndex()) {
            if (stopped()) break
            val size = path.length()
            val mtime = path.lastModified()
            if (mtime == 0L && size == 0L && !path.exists()) continue
            val prev = old[pathKey(path.absolutePath)]
            val fresh = prev != null && prev.size == size && abs(prev.mtime - mtime) < 1000
            val stem = path.nameWithoutExtension
            val entry: Book
            if (fresh) {
                entry = prev!!
                if (entry.authorGuessed) {
                    entry.author = null
                    entry.authorGuessed = false
                }
            } else {
                val info = fileMeta(path)
                val parsed = parseName(stem)
                var title = parsed.title
                if (info.title != null && stemIsPoor(stem)) {
                    title = titleCase(clean(info.title))
                }
                entry = Book(
                    id = bookId(path.absolutePath),
                    path = path.absolutePath,
                    file = path.name,
                    ext = "." + path.extension.lowercase(),
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
                )
            }
            entry.folder = path.absoluteFile.parent ?: ""
            entry.seriesFolder = seriesFolder(path, rootKeys)
            entry.readable = readableExt(entry.ext)
            books.add(entry)
            progress?.invoke(i + 1, files.size, entry.title)
        }

        fillSeriesAuthors(books)
        return LibraryIndex(roots, System.currentTimeMillis(), dedupe(books))
    }

    private fun fillSeriesAuthors(books: List<Book>) {
        val tally = HashMap<String, HashMap<String, Int>>()
        val total = HashMap<String, Int>()
        for (b in books) {
            val home = b.seriesFolder ?: continue
            val key = pathKey(home)
            total[key] = (total[key] ?: 0) + 1
            val author = b.author ?: continue
            val seen = tally.getOrPut(key) { HashMap() }
            seen[author] = (seen[author] ?: 0) + 1
        }
        for (b in books) {
            if (b.author != null) continue
            val home = b.seriesFolder ?: continue
            val key = pathKey(home)
            val seen = tally[key] ?: continue
            val best = seen.entries.maxByOrNull { it.value } ?: continue
            if (best.value >= 2 && best.value >= SERIES_AUTHOR_SHARE * (total[key] ?: 0)) {
                b.author = best.key
                b.authorGuessed = true
            }
        }
    }

    private class GroupKey(val title: String, val author: String, val volumes: List<Int>) {
        override fun equals(other: Any?) = other is GroupKey &&
            title == other.title && author == other.author && volumes == other.volumes

        override fun hashCode() = (title.hashCode() * 31 + author.hashCode()) * 31 + volumes.hashCode()
    }

    private fun folderAffinity(books: List<Book>): Map<Pair<String, String>, MutableSet<String>> {
        val out = HashMap<Pair<String, String>, MutableSet<String>>()
        for (b in books) {
            val author = b.author
            if (author.isNullOrEmpty()) continue
            val key = Pair(pathKey(b.folder), author)
            out.getOrPut(key) { HashSet() }.add(normKey(b.title, null).first)
        }
        return out
    }

    private fun compareVolumes(a: List<Int>, b: List<Int>): Int {
        val left = a.ifEmpty { listOf(9999) }
        val right = b.ifEmpty { listOf(9999) }
        for (i in 0 until minOf(left.size, right.size)) {
            val step = left[i].compareTo(right[i])
            if (step != 0) return step
        }
        return left.size.compareTo(right.size)
    }

    private fun dedupe(books: List<Book>): List<Book> {
        val affinity = folderAffinity(books)
        val groups = LinkedHashMap<GroupKey, ArrayList<Book>>()
        for (b in books) {
            val (title, author) = normKey(b.title, b.author)
            groups.getOrPut(GroupKey(title, author, b.volumes)) { ArrayList() }.add(b)
        }
        foldAuthorless(groups)

        val ranked = compareByDescending<Book> { if (it.ext == ".pdf") 1 else 0 }
            .thenByDescending { pathKey(it.folder).count { c -> c == File.separatorChar } }
            .thenByDescending { affinity[Pair(pathKey(it.folder), it.author ?: "")]?.size ?: 0 }
            .thenByDescending { it.pages }
            .thenByDescending { it.size }

        val out = ArrayList<Book>()
        for (group in groups.values) {
            group.sortWith(ranked)
            val primary = group.first()
            primary.editions = group.map { Edition(it.path, it.ext, it.size, it.pages) }
            out.add(primary)
        }
        out.sortWith(
            compareBy<Book> { (it.seriesFolder ?: "").lowercase() }
                .thenComparator { a, b -> compareVolumes(a.volumes, b.volumes) }
                .thenBy { it.title.lowercase() }
        )
        return out
    }

    private fun foldAuthorless(groups: LinkedHashMap<GroupKey, ArrayList<Book>>) {
        val authored = HashMap<Pair<String, List<Int>>, ArrayList<GroupKey>>()
        for (key in groups.keys) {
            if (key.author.isEmpty()) continue
            authored.getOrPut(Pair(key.title, key.volumes)) { ArrayList() }.add(key)
        }
        for (key in groups.keys.toList()) {
            if (key.author.isNotEmpty()) continue
            val hosts = authored[Pair(key.title, key.volumes)] ?: continue
            if (hosts.size != 1) continue
            val moved = groups.remove(key) ?: continue
            groups[hosts[0]]?.addAll(moved)
        }
    }
}
