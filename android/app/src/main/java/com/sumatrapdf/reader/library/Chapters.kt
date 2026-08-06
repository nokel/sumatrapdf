package com.sumatrapdf.reader.library

import android.util.Log
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Outline
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// Port of audiobook/library/chapters.py. The document's own outline is
// used when it has one; otherwise every page's first line is tested
// against the heading shapes a novel actually uses.

private const val TAG = "SumatraChapters"

const val MIN_CHAPTERS = 2
const val MAX_HEADING_CHARS = 70
const val OUTLINE_PAGE_SPREAD = 0.5
const val OUTLINE_FOUND_SHARE = 0.5
const val CHAPTER_CACHE_VERSION = 2

val SKIP_TITLES = setOf(
    "contents", "table of contents", "title page", "copyright",
    "cover", "about the author", "acknowledgements",
    "acknowledgments", "dedication", "also by",
)

private val HEADING = Regex(
    """^(?:(chapter|part|book|section|act|episode)\s*""" +
        """([0-9]{1,3}|[ivxlcdm]{1,7}|one|two|three|four|five|six|seven|eight|nine|""" +
        """ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|""" +
        """nineteen|twenty)\b|(prologue|epilogue|interlude|foreword|afterword))""" +
        """[\s.:;,-]*(.{0,60})$""",
    RegexOption.IGNORE_CASE,
)

private val SPACE_RUN = Regex("""\s+""")

data class ChapterRow(
    val title: String,
    val page: Int,
    val children: MutableList<ChapterRow> = mutableListOf(),
)

data class ChapterList(val chapters: List<ChapterRow>, val source: String, val count: Int)

private fun pythonTitle(text: String): String {
    val out = StringBuilder()
    var newWord = true
    for (c in text) {
        if (c.isLetter()) {
            out.append(if (newWord) c.uppercaseChar() else c.lowercaseChar())
            newWord = false
        } else {
            out.append(c)
            newWord = true
        }
    }
    return out.toString()
}

private fun nest(flat: List<Triple<Int, String, Int>>): List<ChapterRow> {
    val out = mutableListOf<ChapterRow>()
    val stack = mutableListOf<Pair<Int, ChapterRow>>()
    for ((level, title, page) in flat) {
        val row = ChapterRow(title, page)
        while (stack.isNotEmpty() && stack.last().first >= level) stack.removeAt(stack.size - 1)
        if (stack.isNotEmpty()) stack.last().second.children.add(row) else out.add(row)
        stack.add(level to row)
    }
    return out
}

private fun flattenOutline(doc: Document, items: Array<Outline>?, level: Int, into: MutableList<Triple<Int, String, Int>>) {
    if (items == null) return
    for (o in items) {
        val title = (o.title ?: "").trim()
        val page = try {
            doc.pageNumberFromLocation(doc.resolveLink(o)) + 1
        } catch (_: Throwable) {
            0
        }
        into.add(Triple(level, title, page))
        flattenOutline(doc, o.down, level + 1, into)
    }
}

private fun outlineEntries(doc: Document): List<Triple<Int, String, Int>> {
    val out = mutableListOf<Triple<Int, String, Int>>()
    flattenOutline(doc, try { doc.loadOutline() } catch (_: Throwable) { null }, 1, out)
    return out
}

private fun pagesUsable(flat: List<Triple<Int, String, Int>>, seen: Int): Boolean {
    if (flat.isEmpty()) return false
    val pages = flat.map { it.third }.toSet()
    return pages.size >= maxOf(2, (seen * OUTLINE_PAGE_SPREAD).toInt())
}

private fun fromOutline(doc: Document): List<ChapterRow> {
    val entries = outlineEntries(doc)
    val flat = entries.filter { it.second.isNotEmpty() && it.third >= 1 }
    if (flat.size < MIN_CHAPTERS || !pagesUsable(flat, entries.size)) return emptyList()
    return nest(flat)
}

private fun plain(text: String): String = SPACE_RUN.replace(text.trim(), " ").lowercase()

private fun fromOutlineText(doc: Document): List<ChapterRow> {
    val titled = outlineEntries(doc).filter { it.second.isNotEmpty() }
    if (titled.size < MIN_CHAPTERS) return emptyList()
    val firsts = (0 until doc.countPages()).map { plain(firstLine(doc, it)) }
    val flat = mutableListOf<Triple<Int, String, Int>>()
    var at = 0
    for ((level, title, _) in titled) {
        val want = plain(title)
        for (i in at until firsts.size) {
            if (firsts[i] == want) {
                flat.add(Triple(level, title, i + 1))
                at = i + 1
                break
            }
        }
    }
    if (flat.size < MIN_CHAPTERS || flat.size < titled.size * OUTLINE_FOUND_SHARE) return emptyList()
    return nest(flat)
}

private fun firstLine(doc: Document, index: Int): String {
    val page = try { doc.loadPage(index) } catch (_: Throwable) { null } ?: return ""
    return try {
        pythonLines(pageText(page))
            .asSequence()
            .map { stripPythonSpace(it) }
            .firstOrNull { it.isNotEmpty() } ?: ""
    } finally {
        try { page.destroy() } catch (_: Throwable) {}
    }
}

fun headingOf(line: String): String? {
    if (line.isEmpty() || line.length > MAX_HEADING_CHARS) return null
    val m = HEADING.find(line) ?: return null
    if (m.range.first != 0) return null
    val tail = m.groupValues[4].trim().trim(' ', '.', ':', ';', ',', '-')
    val lead = m.groupValues[3].trim()
    val head = if (lead.isNotEmpty()) {
        pythonTitle(lead)
    } else {
        "${pythonTitle(m.groupValues[1])} ${m.groupValues[2]}"
    }
    return if (tail.isNotEmpty()) "$head · $tail" else head
}

private fun fromPages(doc: Document): List<ChapterRow> {
    val flat = mutableListOf<Triple<Int, String, Int>>()
    val n = doc.countPages()
    for (i in 0 until n) {
        val title = headingOf(firstLine(doc, i))
        if (title != null) flat.add(Triple(1, title, i + 1))
    }
    if (flat.size < MIN_CHAPTERS) return emptyList()
    return nest(flat)
}

private fun prune(rows: List<ChapterRow>): List<ChapterRow> {
    val out = mutableListOf<ChapterRow>()
    for (row in rows) {
        if (row.title.trim().lowercase() in SKIP_TITLES && row.children.isEmpty()) continue
        val kids = prune(row.children)
        row.children.clear()
        row.children.addAll(kids)
        out.add(row)
    }
    return out
}

private fun countRows(rows: List<ChapterRow>): Int = rows.sumOf { 1 + countRows(it.children) }

fun readChapters(path: String): Pair<List<ChapterRow>, String> {
    var doc: Document? = null
    try {
        doc = Document.openDocument(path)
        if (doc.needsPassword()) return emptyList<ChapterRow>() to ""
        var rows = fromOutline(doc)
        var source = if (rows.isNotEmpty()) "outline" else ""
        if (rows.isEmpty()) {
            rows = fromOutlineText(doc)
            source = if (rows.isNotEmpty()) "outline titles" else ""
        }
        if (rows.isEmpty()) {
            rows = fromPages(doc)
            source = if (rows.isNotEmpty()) "pages" else ""
        }
        rows = prune(rows)
        if (countRows(rows) < MIN_CHAPTERS) return emptyList<ChapterRow>() to ""
        return rows to source
    } catch (t: Throwable) {
        Log.w(TAG, "readChapters($path): ${t.javaClass.simpleName}: ${t.message}")
        return emptyList<ChapterRow>() to ""
    } finally {
        try { doc?.destroy() } catch (_: Throwable) {}
    }
}

private fun rowsToJson(rows: List<ChapterRow>): JSONArray {
    val arr = JSONArray()
    for (r in rows) {
        val o = JSONObject()
        o.put("title", r.title)
        o.put("page", r.page)
        o.put("children", rowsToJson(r.children))
        arr.put(o)
    }
    return arr
}

private fun rowsFromJson(arr: JSONArray?): MutableList<ChapterRow> {
    val out = mutableListOf<ChapterRow>()
    if (arr == null) return out
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val row = ChapterRow(o.optString("title", ""), o.optInt("page", 0))
        row.children.addAll(rowsFromJson(o.optJSONArray("children")))
        out.add(row)
    }
    return out
}

private fun chapterPathFor(bookId: String): File = File(LibraryCache.dir("chapters"), "$bookId.json")

fun chaptersOf(book: Book, refresh: Boolean = false): ChapterList {
    val cache = chapterPathFor(book.id)
    if (cache.exists() && !refresh) {
        try {
            val o = JSONObject(cache.readText())
            if (o.optInt("version", 0) == CHAPTER_CACHE_VERSION) {
                return ChapterList(
                    chapters = rowsFromJson(o.optJSONArray("chapters")),
                    source = o.optString("source", ""),
                    count = o.optInt("count", 0),
                )
            }
        } catch (_: Throwable) {
        }
    }
    val (rows, source) = readChapters(book.path)
    val out = ChapterList(rows, source, countRows(rows))
    try {
        val o = JSONObject()
        o.put("chapters", rowsToJson(rows))
        o.put("source", source)
        o.put("count", out.count)
        o.put("version", CHAPTER_CACHE_VERSION)
        cache.writeText(o.toString())
    } catch (_: Throwable) {
    }
    return out
}
