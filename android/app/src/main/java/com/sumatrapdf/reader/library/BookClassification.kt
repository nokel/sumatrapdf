package com.sumatrapdf.reader.library

import java.io.File

const val BOOK_SCORE = 2
const val ART_SHARE_BOOK = 0.5
const val MARKER_CAP = 3
const val NARRATIVE_QUOTES = 6
const val NARRATIVE_PRONOUNS = 3

val EBOOK_CONTAINER_EXTS = listOf(".epub", ".mobi", ".azw3", ".fb2", ".cbz")

val LIBRARY_DIR_HINTS = listOf(
    "ebooks", "ebook", "books", "book", "library", "calibre library",
    "audiobooks", "novels", "manga", "comics", "reading", "epub", "kindle",
    "animorphs", "discworld", "hitchhiker", "hitchhikers", "rick and morty",
)

val WORK_DIR_HINTS = listOf(
    "ext", "tests", "test", "doc", "docs", "vendor", "third_party",
    "vcpkg", "samples", "sample", "node_modules", "src", "build", "buildtrees",
    "download", "downloads",
    "music", "songs", "tracks", "lyrics", "liner",
    "system", "framework", "frameworks", "kernel", "drivers", "driver",
    "logs", "log", "tmp", "temp", "cache", "backup", "backups", "old",
    "app", "apps", "application", "applications", "installer", "installers",
    "receipts", "invoices", "bills", "orders", "statements",
)

val BOOK_WORDS = listOf(
    "isbn",
    "library of congress",
    "first published",
    "first edition",
    "printed in the united",
    "cataloguing in publication",
    "publishing",
    "publishers",
    "chapter one",
    "chapter 1",
    "prologue",
    "epilogue",
    "to be continued",
    "illustrated by",
    "translated by",
    "cover art",
)

val DOC_WORDS = listOf(
    "invoice",
    "amount due",
    "purchase order",
    "work order",
    "bill to",
    "ship to",
    "subtotal",
    "tax invoice",
    "order number",
    "tracking number",
    "return label",
    "shipping label",
    "curriculum vitae",
    "resume",
    "consent form",
    "date of birth",
    "policy number",
    "claim number",
    "account number",
    "statement period",
    "receipt",
    "datasheet",
    "data sheet",
    "absolute maximum",
    "electrical characteristics",
    "ordering information",
    "part number",
    "privacy policy",
    "user manual",
    "owner s manual",
    "owners manual",
    "service manual",
    "instruction manual",
    "operating instructions",
    "operating manual",
    "this manual",
    "installation guide",
    "quick start",
    "safety instructions",
    "precautions",
    "specification",
    "internet draft",
    "microsoft powerpoint",
    "this document was generated",
    "signature",
    "synopsis",
    "see also",
    "abstract",
    "et al",
    "yours sincerely",
    "booklet",
    "liner notes",
    "lyrics",
    "tracklist",
    "track list",
    "song list",
    "album",
    "deluxe edition",
    "remastered",
)

fun normalizeWords(text: String): String {
    val sb = StringBuilder(text.length + 2)
    sb.append(' ')
    var space = true
    for (ch in text.lowercase()) {
        if (ch in 'a'..'z' || ch in '0'..'9') {
            sb.append(ch)
            space = false
        } else if (!space) {
            sb.append(' ')
            space = true
        }
    }
    if (!space) sb.append(' ')
    return sb.toString()
}

fun pathHasDirName(path: String, names: List<String>): Boolean {
    var start = 0
    for (i in path.indices) {
        if (path[i] == '/' || path[i] == '\\') {
            val seg = path.substring(start, i)
            start = i + 1
            if (seg.isEmpty()) continue
            for (n in names) {
                if (seg.equals(n, ignoreCase = true)) return true
            }
        }
    }
    return false
}

fun countWord(words: String, word: String): Int {
    val padded = " $word "
    if (padded.length > 64) return 0
    var found = 0
    var rest = words
    while (true) {
        val at = rest.indexOf(padded, ignoreCase = true)
        if (at < 0) return found
        found++
        val step = at + padded.length - 1
        rest = rest.substring(step)
    }
}

fun markerHits(words: String, markers: List<String>, cap: Int): Int {
    var hits = 0
    for (m in markers) {
        if (hits >= cap) break
        if (countWord(words, m) >= 1) hits++
    }
    return hits
}

fun countQuotes(text: String): Int {
    var quotes = 0
    for (c in text) {
        if (c == '"' || c == '\u201C' || c == '\u201D' ||
            c == '\u00AB' || c == '\u00BB' ||
            c == '\u300C' || c == '\u300D') {
            quotes++
        }
    }
    return quotes
}

fun narrativeMarks(sampleWords: String, sampleRaw: String): Int {
    var marks = 0
    if (countWord(sampleWords, "said") >= 1) marks++
    if (countWord(sampleWords, "he") + countWord(sampleWords, "she") >= NARRATIVE_PRONOUNS) marks++
    if (countQuotes(sampleRaw) >= NARRATIVE_QUOTES) marks++
    return marks
}

fun pageScore(pages: Int): Int = when {
    pages <= 2 -> -8
    pages <= 6 -> -5
    pages <= 12 -> -3
    pages <= 20 -> -1
    pages <= 40 -> 1
    pages <= 80 -> 2
    pages <= 150 -> 3
    else -> 4
}

fun looksLikeBook(
    path: String,
    ext: String,
    pages: Int,
    art: Double?,
    sample: String,
    title: String,
    author: String?,
    file: String,
    toc: Int = 0,
): Boolean {
    var score = 0
    val lowExt = ext.lowercase()
    if (lowExt in EBOOK_CONTAINER_EXTS) score += 3
    score += pageScore(pages)
    if (art != null && art >= ART_SHARE_BOOK && pages >= 12) score += 4
    if (pathHasDirName(path, LIBRARY_DIR_HINTS)) score += 3
    if (pathHasDirName(path, WORK_DIR_HINTS)) score -= 3
    val joined = buildString {
        append(sample); append(' ')
        append(title); append(' ')
        append(author ?: ""); append(' ')
        append(file)
    }
    val words = normalizeWords(joined)
    val sampleWords = normalizeWords(sample)
    score += 2 * markerHits(words, BOOK_WORDS, MARKER_CAP)
    score -= 3 * markerHits(words, DOC_WORDS, MARKER_CAP)
    score += 2 * narrativeMarks(sampleWords, sample)
    if (toc >= 5) score += 1
    return score >= BOOK_SCORE
}
