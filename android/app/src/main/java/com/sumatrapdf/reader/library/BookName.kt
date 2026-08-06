package com.sumatrapdf.reader.library

// Port of the name-parsing half of audiobook/library/shelf.py. Everything
// here is pure text handling over a file's stem, kept separate from the
// scan so it can be tested without a filesystem.

val BOOK_EXTS = listOf(".pdf", ".epub", ".mobi", ".azw3", ".fb2", ".cbz", ".xps")
val READABLE_EXTS = listOf(".pdf", ".epub", ".mobi", ".fb2", ".xps", ".cbz")

const val INDEX_FILE = "library.json"
const val MIN_SERIES_GROUP = 1
const val SERIES_AUTHOR_SHARE = 0.75
const val SERIES_TREE_DEPTH = 8
const val MIN_SERIES_PREFIX = 5
const val PAGE_SAMPLES = 6
const val ART_SHARE = 0.5
const val SAMPLE_CHARS = 3000
const val LOOSE_KEY = "<loose>"
const val LOOSE_NAME = "standalones"

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

// The Windows list drops the drive-level entries that cannot occur under
// Android's shared storage and keeps the ones that can.
val SKIP_DIR_NAMES = setOf(
    "node_modules", ".git", "__pycache__", "cache", "temp", "tmp",
    "android", "lost.dir", ".thumbnails", ".trash",
)

val SMALL_WORDS = setOf(
    "a", "an", "and", "as", "at", "but", "by", "for", "from", "in",
    "into", "of", "on", "or", "the", "to", "vs", "with",
)

private val VOLUME = Regex(
    """^\s*(?:vol(?:ume)?|book|part|no|#)?\s*\.?\s*""" +
        """(\d{1,3}(?:\s*[,&]?\s+\d{1,3})*)\s*(?:[-–—:.)\]]\s*|\s+)""",
)
private val TRAIL_VOLUME = Regex(
    """[ ,]*\b(?:vol(?:ume)?|book|part)\.?\s*(\d{1,3})$""",
    RegexOption.IGNORE_CASE,
)
private val YEAR = Regex("""\(?\b(1[5-9]\d\d|20\d\d)\b\)?""")
private val NOISE = Regex(
    """\b(ebook|e-book|retail|epub|mobi|azw3|pdf|scan(?:ned)?|ocr|v\d|""" +
        """unabridged|complete|calibre|z-lib(?:rary)?|libgen|annas?[- ]archive)\b""",
    RegexOption.IGNORE_CASE,
)
private val BRACKETS = Regex("""[\[{][^\]}]*[\]}]""")
private val DASH = Regex("""\s+[-–—]\s+""")
private val PATHISH = Regex(
    """[\\/]{1,2}|^[A-Za-z]:|\.(?:pdf|epub|mobi|doc|docx|txt)\b""",
    RegexOption.IGNORE_CASE,
)
private val BY_AUTHOR = Regex(
    """\bby\s+(\S+(?:\s+\S+){0,3})\s*$""",
    RegexOption.IGNORE_CASE,
)
private val WHITESPACE = Regex("""\s+""")

private const val TRIM_CHARS = " -–—,.;"

data class ParsedName(
    val title: String,
    val author: String?,
    val volumes: List<Int>,
    val year: Int?,
)

fun cleanName(text: String?): String {
    var s = BRACKETS.replace(text ?: "", " ")
    s = s.replace("_", " ").replace("'", "'")
    s = NOISE.replace(s, " ")
    return WHITESPACE.replace(s, " ").trim().trim(*TRIM_CHARS.toCharArray())
}

fun titlecase(text: String): String {
    var s = text
    if (s.none { it.isLowerCase() }) s = s.lowercase()
    return s.split(" ").filter { it.isNotEmpty() }.mapIndexed { i, w ->
        val low = w.lowercase()
        when {
            w.drop(1).any { it.isUpperCase() } -> w
            i > 0 && low in SMALL_WORDS -> low
            else -> low.replaceFirstChar { it.uppercaseChar() }
        }
    }.joinToString(" ")
}

fun looksLikePerson(text: String): Boolean {
    val toks = text.split(" ").filter { it.isNotEmpty() }
    if (toks.size !in 2..4 || text.length > 40) return false
    if (text.any { it.isDigit() }) return false
    if (toks[0].lowercase() in SMALL_WORDS) return false
    return toks.all { t ->
        t.firstOrNull()?.isUpperCase() == true ||
            t.lowercase() in setOf("de", "van", "von", "der", "la")
    }
}

fun splitVolumes(text: String): List<Int> =
    Regex("""\d{1,3}""").findAll(text).map { it.value.toInt() }.toList()

fun parseName(stem: String): ParsedName {
    var volumes: List<Int> = emptyList()
    var s = cleanName(stem)

    val m = VOLUME.find(s)
    if (m != null && m.range.first == 0 && s.substring(m.range.last + 1).isNotBlank()) {
        volumes = splitVolumes(m.groupValues[1])
        s = s.substring(m.range.last + 1).trim()
    }

    var year: Int? = null
    val ym = YEAR.find(s)
    if (ym != null) {
        year = ym.groupValues[1].toInt()
        s = s.substring(0, ym.range.first) + " " + s.substring(ym.range.last + 1)
    }
    s = s.replace(Regex("""[()]"""), " ")
    s = WHITESPACE.replace(s, " ").trim().trim(*TRIM_CHARS.toCharArray())

    var author: String? = null
    val parts = DASH.split(s).map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.size >= 2) {
        val first = parts.first()
        val last = parts.last()
        when {
            looksLikePerson(last) && !looksLikePerson(first) -> {
                author = last
                s = parts.dropLast(1).joinToString(" - ")
            }
            looksLikePerson(first) && !looksLikePerson(last) -> {
                author = first
                s = parts.drop(1).joinToString(" - ")
            }
            looksLikePerson(last) -> {
                author = last
                s = parts.dropLast(1).joinToString(" - ")
            }
            else -> s = parts.joinToString(" - ")
        }
    }

    if (author == null) {
        val bm = BY_AUTHOR.find(s)
        if (bm != null && looksLikePerson(bm.groupValues[1])) {
            author = bm.groupValues[1]
            s = s.substring(0, bm.range.first).trim().trim(*TRIM_CHARS.toCharArray())
        }
    }

    val tm = TRAIL_VOLUME.find(s)
    if (tm != null) {
        if (volumes.isEmpty()) volumes = listOf(tm.groupValues[1].toInt())
        s = s.substring(0, tm.range.first).trim()
    }
    if (s.isEmpty()) s = cleanName(stem)
    return ParsedName(titlecase(s), author, volumes, year)
}

// A document's own metadata is only trusted when it is not obviously the
// producing application's boilerplate or a restatement of the file name.
fun metaOk(value: String?, stem: String): String? {
    val v = cleanName(value)
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
    v = v.split(Regex("""[;&]| and """))[0].trim()
    if (v.contains(",")) {
        val last = v.substringBefore(",")
        val first = v.substringAfter(",").trim()
        if (first.isNotEmpty()) v = "$first ${last.trim()}"
    }
    val toks = v.split(" ").filter { it.isNotEmpty() }
    if (toks.size < 2 || toks.size > 5) return null
    val low = v.lowercase()
    if (low.startsWith("www.") || low.contains(".com")) return null
    if (!toks.all { it.firstOrNull()?.isLetter() == true }) return null
    return v
}

// A stem with only one long run-together word tells us nothing, so the
// document's own title is preferred when it has one.
fun stemIsPoor(stem: String): Boolean {
    val words = cleanName(stem).split(Regex("""[\s_-]+""")).filter { it.isNotEmpty() }
    val letters = words.filter { w -> w.any { it.isLetter() } }
    if (letters.isEmpty()) return true
    return letters.size < 2 && letters[0].length > 12
}

fun normKey(title: String?, author: String?): Pair<String, String> {
    val t = (title ?: "").lowercase().replace(Regex("""[^a-z0-9]"""), "")
    val surname = author?.split(" ")?.filter { it.isNotEmpty() }?.lastOrNull() ?: ""
    val a = surname.lowercase().replace(Regex("""[^a-z0-9]"""), "")
    return t to a
}

fun squash(text: String?): String =
    Regex("""[^a-z0-9]+""").replace((text ?: "").lowercase(), " ").trim()

fun genericFolder(name: String?): Boolean {
    val tokens = Regex("""[^a-z0-9]+""")
        .split((name ?: "").lowercase())
        .filter { it.isNotEmpty() }
    return tokens.isNotEmpty() && tokens.all { it in GENERIC_FOLDER_WORDS }
}

fun skipDir(name: String): Boolean {
    val low = name.lowercase()
    return low.startsWith(".") || low in SKIP_DIR_NAMES
}

fun isBookFile(name: String): Boolean {
    val low = name.lowercase()
    return BOOK_EXTS.any { low.endsWith(it) }
}
