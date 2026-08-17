package com.sumatrapdf.library

import java.security.MessageDigest

val BOOK_EXTS = listOf(".pdf", ".epub", ".mobi", ".azw3", ".fb2", ".cbz", ".xps")
val READABLE_EXTS = listOf(".pdf", ".epub", ".mobi", ".fb2", ".xps", ".cbz")

const val MIN_SERIES_GROUP = 2
const val SERIES_AUTHOR_SHARE = 0.75
const val SERIES_TREE_DEPTH = 8
const val MIN_SERIES_PREFIX = 5
const val PAGE_SAMPLES = 6
const val ART_SHARE = 0.5
const val SAMPLE_CHARS = 3000
const val LOOSE_KEY = "<loose>"
const val SCAN_DEPTH = 5
const val SCAN_BUDGET = 40000

val GENERIC_FOLDER_WORDS = setOf(
    "book", "books", "ebook", "ebooks", "novel", "novels", "manga", "comic",
    "comics", "library", "libraries", "audiobook", "audiobooks", "collection",
    "collections", "misc", "other", "unsorted", "reading", "kindle", "epub",
    "pdf", "pdfs", "documents", "downloads", "fiction", "nonfiction", "non",
    "series", "author", "authors", "new", "old", "stuff", "files", "archive",
    "archives", "shelf", "shelves", "to", "read", "sorted",
)

val LIBRARY_DIR_NAMES = listOf(
    "ebooks", "ebook", "books", "book", "library", "calibre library",
    "audiobooks", "novels", "manga", "comics", "reading", "epub", "kindle",
)

val SKIP_DIR_NAMES = setOf(
    "android", "lost.dir", "node_modules", ".git", "__pycache__",
    "cache", "temp", "tmp", "venv", ".venv", ".thumbnails", ".trashed",
)

val SMALL_WORDS = setOf(
    "a", "an", "and", "as", "at", "but", "by", "for", "from", "in", "into",
    "of", "on", "or", "the", "to", "vs", "with",
)

private val NAME_PARTICLES = setOf("de", "van", "von", "der", "la")

private val VOLUME = Regex(
    """^\s*(?:vol(?:ume)?|book|part|no|#)?\s*\.?\s*""" +
        """(\d{1,3}(?:\s*[,&]?\s+\d{1,3})*)\s*(?:[-–—:.)\]]\s*|\s+)""")
private val TRAIL_VOLUME = Regex(
    """[ ,]*\b(?:vol(?:ume)?|book|part)\.?\s*(\d{1,3})$""",
    RegexOption.IGNORE_CASE)
private val YEAR = Regex("""\(?\b(1[5-9]\d\d|20\d\d)\b\)?""")
private val NOISE = Regex(
    """\b(ebook|e-book|retail|epub|mobi|azw3|pdf|scan(?:ned)?|ocr|v\d|""" +
        """unabridged|complete|calibre|z-lib(?:rary)?|libgen|annas?[- ]archive)\b""",
    RegexOption.IGNORE_CASE)
private val BRACKETS = Regex("""[\[{][^\]}]*[\]}]""")
private val DASH = Regex("""\s+[-–—]\s+""")
private val PATHISH = Regex(
    """[\\/]{1,2}|^[A-Za-z]:|\.(?:pdf|epub|mobi|doc|docx|txt)\b""",
    RegexOption.IGNORE_CASE)
private val BY_AUTHOR = Regex("""\bby\s+(\S+(?:\s+\S+){0,3})\s*$""",
    RegexOption.IGNORE_CASE)
private val WHITESPACE = Regex("""\s+""")
private val NOT_ALNUM = Regex("[^a-z0-9]")
private val NOT_ALNUM_RUN = Regex("[^a-z0-9]+")
private val DIGIT_RUN = Regex("""\d{1,3}""")
private val AUTHOR_SPLIT = Regex("""[;&]| and """)
private val WORD_BREAK = Regex("""[\s_-]+""")

private const val EDGE_CHARS = " -–—,.;"

private fun String.trimEdges() = trim { it in EDGE_CHARS }

private fun String.words() = split(WHITESPACE).filter { it.isNotEmpty() }

fun clean(text: String?): String {
    var s = BRACKETS.replace(text ?: "", " ")
    s = s.replace("_", " ").replace('’', '\'')
    s = NOISE.replace(s, " ")
    return WHITESPACE.replace(s, " ").trimEdges()
}

fun titleCase(text: String): String {
    var source = text
    if (source.none { it.isLowerCase() }) {
        source = source.lowercase()
    }
    val out = ArrayList<String>()
    for ((i, w) in source.words().withIndex()) {
        val low = w.lowercase()
        when {
            w.drop(1).any { it.isUpperCase() } -> out.add(w)
            i > 0 && low in SMALL_WORDS -> out.add(low)
            else -> out.add(low.replaceFirstChar { it.uppercaseChar() })
        }
    }
    return out.joinToString(" ")
}

fun looksLikePerson(text: String): Boolean {
    val toks = text.words()
    if (toks.size !in 2..4 || text.length > 40) return false
    if (text.any { it.isDigit() }) return false
    if (toks[0].lowercase() in SMALL_WORDS) return false
    return toks.all { it.firstOrNull()?.isUpperCase() == true || it.lowercase() in NAME_PARTICLES }
}

class ParsedName(
    val title: String,
    val author: String?,
    val volumes: List<Int>,
    val year: Int?,
)

fun parseName(stem: String): ParsedName {
    var volumes = emptyList<Int>()
    var s = clean(stem)
    val head = VOLUME.find(s)
    if (head != null && s.substring(head.range.last + 1).isNotBlank()) {
        volumes = DIGIT_RUN.findAll(head.groupValues[1]).map { it.value.toInt() }.toList()
        s = s.substring(head.range.last + 1).trim()
    }
    var year: Int? = null
    val stamp = YEAR.find(s)
    if (stamp != null) {
        year = stamp.groupValues[1].toInt()
        s = s.substring(0, stamp.range.first) + " " + s.substring(stamp.range.last + 1)
    }
    s = s.replace("(", " ").replace(")", " ")
    s = WHITESPACE.replace(s, " ").trimEdges()

    var author: String? = null
    val parts = DASH.split(s).map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.size >= 2) {
        val first = parts.first()
        val last = parts.last()
        if (looksLikePerson(last) && !looksLikePerson(first)) {
            author = last
            s = parts.dropLast(1).joinToString(" - ")
        } else if (looksLikePerson(first) && !looksLikePerson(last)) {
            author = first
            s = parts.drop(1).joinToString(" - ")
        } else if (looksLikePerson(last)) {
            author = last
            s = parts.dropLast(1).joinToString(" - ")
        } else {
            s = parts.joinToString(" - ")
        }
    }

    if (author == null) {
        val by = BY_AUTHOR.find(s)
        if (by != null && looksLikePerson(by.groupValues[1])) {
            author = by.groupValues[1]
            s = s.substring(0, by.range.first).trimEdges()
        }
    }

    val trail = TRAIL_VOLUME.find(s)
    if (trail != null) {
        if (volumes.isEmpty()) volumes = listOf(trail.groupValues[1].toInt())
        s = s.substring(0, trail.range.first).trim()
    }
    if (s.isEmpty()) s = clean(stem)
    return ParsedName(titleCase(s), author, volumes, year)
}

fun metaOk(value: String?, stem: String): String? {
    val v = clean(value)
    if (v.length < 3 || v.length > 200) return null
    val low = v.lowercase()
    if (low.startsWith("microsoft word") || low.startsWith("untitled") ||
        low.startsWith("document") || low.startsWith("chapter")
    ) {
        return null
    }
    if (low in setOf("pdf", "book", "ebook", "unknown", "none")) return null
    if (low == stem.lowercase()) return null
    if (v.none { it.isLetter() }) return null
    if (PATHISH.containsMatchIn(v)) return null
    return v
}

fun metaAuthor(value: String?, stem: String): String? {
    var v = metaOk(value, stem) ?: return null
    v = AUTHOR_SPLIT.split(v).first().trim()
    val comma = v.indexOf(',')
    if (comma >= 0) {
        val last = v.substring(0, comma)
        val first = v.substring(comma + 1).trim()
        if (first.isNotEmpty()) v = "$first ${last.trim()}"
    }
    val toks = v.words()
    if (toks.size < 2 || toks.size > 5) return null
    val low = v.lowercase()
    if (low.startsWith("www.") || low.contains(".com")) return null
    if (!toks.all { it.firstOrNull()?.isLetter() == true }) return null
    return v
}

fun stemIsPoor(stem: String): Boolean {
    val words = WORD_BREAK.split(clean(stem)).filter { it.isNotEmpty() }
    val letters = words.filter { w -> w.any { it.isLetter() } }
    if (letters.isEmpty()) return true
    return letters.size < 2 && letters[0].length > 12
}

fun normKey(title: String?, author: String?): Pair<String, String> {
    val t = NOT_ALNUM.replace((title ?: "").lowercase(), "")
    val surname = author?.words()?.lastOrNull()?.lowercase() ?: ""
    return Pair(t, NOT_ALNUM.replace(surname, ""))
}

fun squash(text: String?): String =
    NOT_ALNUM_RUN.replace((text ?: "").lowercase(), " ").trim()

fun genericFolder(name: String?): Boolean {
    val tokens = NOT_ALNUM_RUN.split((name ?: "").lowercase()).filter { it.isNotEmpty() }
    return tokens.isNotEmpty() && tokens.all { it in GENERIC_FOLDER_WORDS }
}

fun bookId(path: String): String {
    val digest = MessageDigest.getInstance("SHA-1")
        .digest(path.lowercase().toByteArray(Charsets.UTF_8))
    val out = StringBuilder(40)
    for (b in digest) out.append("%02x".format(b))
    return out.substring(0, 16)
}
