package com.sumatrapdf.reader.library

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.round

// Port of audiobook/library/meta.py — Open Library first, Google Books to
// fill the gaps. The answer is cached per book id so a second look at the
// same book costs nothing and works offline.

const val OPENLIBRARY_SEARCH = "https://openlibrary.org/search.json"
const val OPENLIBRARY_WORK = "https://openlibrary.org/works/%s.json"
const val OPENLIBRARY_RATINGS = "https://openlibrary.org/works/%s/ratings.json"
const val OPENLIBRARY_COVER_ID = "https://covers.openlibrary.org/b/id/%s-L.jpg"
const val OPENLIBRARY_COVER_ISBN = "https://covers.openlibrary.org/b/isbn/%s-L.jpg"
const val GOOGLE_BOOKS = "https://www.googleapis.com/books/v1/volumes"

const val SEARCH_FIELDS = "key,title,author_name,first_publish_year,cover_i,isbn," +
    "subject,number_of_pages_median,edition_count"
val META_STOP = setOf("the", "a", "an", "of", "and", "in", "to")
const val MIN_TITLE_SCORE = 0.6

data class BookMeta(
    var source: String? = null,
    var title: String? = null,
    var author: String? = null,
    var description: String? = null,
    var year: Int? = null,
    var subjects: List<String> = emptyList(),
    var isbn: String? = null,
    var coverId: String? = null,
    var key: String? = null,
    var pages: Int? = null,
    var googleCover: String? = null,
    var rating: Double? = null,
    var ratingCount: Int? = null,
    var ratingSource: String? = null,
)

private val WORD = Regex("""[a-z0-9']+""")
private val NON_ALNUM = Regex("""[^a-z0-9]""")
private val SPLIT_NAME = Regex("""[\s.]+""")
private val SOURCE_NOTE = Regex("""\(\[?source]?[^)]*\)""", RegexOption.IGNORE_CASE)
private val TAIL_RULE = Regex("""-{3,}.*$""", RegexOption.DOT_MATCHES_ALL)
private val RUN_OF_SPACE = Regex("""\s+""")

private fun metaTokens(text: String?): List<String> =
    WORD.findAll((text ?: "").lowercase()).map { it.value }.filter { it !in META_STOP }.toList()

private fun metaSquash(text: String?): String = NON_ALNUM.replace((text ?: "").lowercase(), "")

fun titleScore(a: String?, b: String?): Double {
    val ta = metaTokens(a).toSet()
    val tb = metaTokens(b).toSet()
    if (ta.isEmpty() || tb.isEmpty()) return 0.0
    val jaccard = ta.intersect(tb).size / (ta union tb).size.toDouble()
    val sa = metaSquash(metaTokens(a).joinToString(""))
    val sb = metaSquash(metaTokens(b).joinToString(""))
    if (sa.isNotEmpty() && sa == sb) return 1.0
    if (sa.isNotEmpty() && sb.isNotEmpty() && (sa in sb || sb in sa)) return max(jaccard, 0.85)
    return jaccard
}

fun surnameOf(author: String?): String {
    val parts = SPLIT_NAME.split((author ?: "").trim()).filter { it.isNotEmpty() }
    return parts.lastOrNull()?.lowercase() ?: ""
}

private fun authorOk(want: String?, names: List<String>): Boolean {
    if (want.isNullOrBlank()) return true
    val sn = surnameOf(want)
    return sn.isNotEmpty() && names.any { sn in it.lowercase() }
}

fun describeText(value: Any?): String? {
    val raw = when (value) {
        null, JSONObject.NULL -> ""
        is JSONObject -> value.optString("value", "")
        else -> value.toString()
    }
    var text = raw.trim()
    text = SOURCE_NOTE.replace(text, " ")
    text = TAIL_RULE.replace(text, " ")
    text = RUN_OF_SPACE.replace(text, " ").trim()
    return text.ifEmpty { null }
}

private fun stringList(arr: JSONArray?): List<String> {
    if (arr == null) return emptyList()
    return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
}

private fun searchDocs(query: Map<String, String>, limit: Int): List<JSONObject> {
    val params = query + mapOf("limit" to limit.toString(), "fields" to SEARCH_FIELDS)
    val url = OPENLIBRARY_SEARCH + "?" +
        params.entries.joinToString("&") { "${urlEncode(it.key)}=${urlEncode(it.value)}" }
    val data = Net.fetchJson(url) ?: return emptyList()
    val docs = data.optJSONArray("docs") ?: return emptyList()
    return (0 until docs.length()).mapNotNull { docs.optJSONObject(it) }
}

private fun bestDoc(docs: List<JSONObject>, title: String, author: String?): JSONObject? {
    val want = metaSquash(title)
    data class Ranked(
        val exact: Int, val editions: Int, val score: Double,
        val year: Int, val cover: Int, val doc: JSONObject,
    )
    val ranked = mutableListOf<Ranked>()
    for (d in docs) {
        val s = titleScore(title, d.optString("title"))
        if (s < MIN_TITLE_SCORE) continue
        if (!authorOk(author, stringList(d.optJSONArray("author_name")))) continue
        ranked.add(
            Ranked(
                exact = if (metaSquash(d.optString("title", "")) == want) 1 else 0,
                editions = d.optInt("edition_count", 0),
                score = round(s * 1000) / 1000.0,
                year = -d.optInt("first_publish_year", 0),
                cover = if (d.has("cover_i") && !d.isNull("cover_i")) 1 else 0,
                doc = d,
            ),
        )
    }
    if (ranked.isEmpty()) return null
    return ranked.sortedWith(
        compareByDescending<Ranked> { it.exact }
            .thenByDescending { it.editions }
            .thenByDescending { it.score }
            .thenByDescending { it.year }
            .thenByDescending { it.cover },
    ).first().doc
}

fun searchOpenLibrary(title: String?, author: String? = null, limit: Int = 10, context: String? = null): JSONObject? {
    if (title.isNullOrBlank()) return null
    val attempts = mutableListOf<Map<String, String>>()
    if (!author.isNullOrBlank()) attempts.add(mapOf("q" to "$title $author"))
    if (!context.isNullOrBlank() && context != title) attempts.add(mapOf("q" to "$title $context"))
    attempts.add(mapOf("q" to title))
    if (!author.isNullOrBlank()) attempts.add(mapOf("title" to title, "author" to author))
    attempts.add(mapOf("title" to title))
    for (query in attempts) {
        val hit = bestDoc(searchDocs(query, limit), title, author)
        if (hit != null) return hit
    }
    return null
}

fun workDescription(key: String?): String? {
    if (key.isNullOrBlank()) return null
    val ident = key.substringAfterLast('/')
    val data = Net.fetchJson(OPENLIBRARY_WORK.format(ident)) ?: return null
    val value: Any? = if (data.isNull("description")) null else data.opt("description")
    return describeText(value)
}

fun workRating(key: String?): Pair<Double, Int>? {
    if (key.isNullOrBlank()) return null
    val ident = key.substringAfterLast('/')
    val data = Net.fetchJson(OPENLIBRARY_RATINGS.format(ident)) ?: return null
    val summary = data.optJSONObject("summary") ?: return null
    if (summary.isNull("average")) return null
    val average = summary.optDouble("average", 0.0)
    if (average <= 0.0) return null
    return average to summary.optInt("count", 0)
}

fun googleBooks(title: String?, author: String? = null): JSONObject? {
    if (title.isNullOrBlank()) return null
    val terms = mutableListOf("intitle:\"$title\"")
    if (!author.isNullOrBlank()) terms.add("inauthor:\"$author\"")
    val url = GOOGLE_BOOKS + "?" + listOf(
        "q" to terms.joinToString(" "),
        "maxResults" to "3",
        "printType" to "books",
    ).joinToString("&") { "${urlEncode(it.first)}=${urlEncode(it.second)}" }
    val data = Net.fetchJson(url) ?: return null
    val items = data.optJSONArray("items") ?: return null
    for (i in 0 until items.length()) {
        val info = items.optJSONObject(i)?.optJSONObject("volumeInfo") ?: continue
        if (titleScore(title, info.optString("title")) < MIN_TITLE_SCORE) continue
        if (!authorOk(author, stringList(info.optJSONArray("authors")))) continue
        return info
    }
    return null
}

fun lookupMeta(title: String?, author: String? = null, context: String? = null): BookMeta {
    val out = BookMeta(title = title, author = author)
    val doc = searchOpenLibrary(title, author, context = context)
    if (doc != null) {
        out.source = "openlibrary"
        out.title = doc.optString("title", "").takeIf { it.isNotBlank() } ?: title
        val names = stringList(doc.optJSONArray("author_name"))
        out.author = names.firstOrNull() ?: author
        out.year = doc.optInt("first_publish_year", 0).takeIf { it > 0 }
        out.subjects = stringList(doc.optJSONArray("subject")).take(12)
        out.isbn = stringList(doc.optJSONArray("isbn")).firstOrNull()
        out.coverId = if (doc.isNull("cover_i")) null else doc.opt("cover_i")?.toString()
        out.key = doc.optString("key", "").takeIf { it.isNotBlank() }
        out.pages = doc.optInt("number_of_pages_median", 0).takeIf { it > 0 }
        out.description = workDescription(out.key)
        workRating(out.key)?.let { (average, count) ->
            out.rating = average
            out.ratingCount = count
            out.ratingSource = "openlibrary"
        }
    }
    if (out.description == null || out.author == null || out.rating == null) {
        val info = googleBooks(out.title, out.author ?: author)
        if (info != null) {
            if (out.source == null) out.source = "googlebooks"
            if (out.description == null) {
                out.description = describeText(if (info.isNull("description")) null else info.opt("description"))
            }
            val authors = stringList(info.optJSONArray("authors"))
            if (out.author == null && authors.isNotEmpty()) out.author = authors.first()
            if (out.year == null) {
                val published = info.optString("publishedDate", "")
                val m = Regex("""(\d{4})""").find(published)
                out.year = m?.groupValues?.get(1)?.toIntOrNull()
            }
            if (out.subjects.isEmpty()) out.subjects = stringList(info.optJSONArray("categories")).take(12)
            if (out.rating == null) {
                val average = info.optDouble("averageRating", 0.0)
                if (average > 0.0) {
                    out.rating = average
                    out.ratingCount = info.optInt("ratingsCount", 0)
                    out.ratingSource = "googlebooks"
                }
            }
            val links = info.optJSONObject("imageLinks")
            out.googleCover = links?.optString("thumbnail", "")?.takeIf { it.isNotBlank() }
                ?: links?.optString("smallThumbnail", "")?.takeIf { it.isNotBlank() }
        }
    }
    return out
}

fun coverUrls(title: String?, author: String? = null, isbn: String? = null, context: String? = null): List<String> {
    val urls = mutableListOf<String>()
    if (!isbn.isNullOrBlank()) {
        urls.add(OPENLIBRARY_COVER_ISBN.format(Regex("""[^0-9Xx]""").replace(isbn, "")))
    }
    val info = lookupMeta(title, author, context)
    info.coverId?.let { urls.add(OPENLIBRARY_COVER_ID.format(it)) }
    info.isbn?.let { urls.add(OPENLIBRARY_COVER_ISBN.format(it)) }
    info.googleCover?.let { urls.add(it.replace("http://", "https://")) }
    return urls.distinct()
}

fun metaToJson(m: BookMeta): JSONObject {
    val o = JSONObject()
    o.put("source", m.source ?: JSONObject.NULL)
    o.put("title", m.title ?: JSONObject.NULL)
    o.put("author", m.author ?: JSONObject.NULL)
    o.put("description", m.description ?: JSONObject.NULL)
    o.put("year", m.year ?: JSONObject.NULL)
    o.put("subjects", JSONArray(m.subjects))
    o.put("isbn", m.isbn ?: JSONObject.NULL)
    o.put("cover_id", m.coverId ?: JSONObject.NULL)
    o.put("key", m.key ?: JSONObject.NULL)
    o.put("pages", m.pages ?: JSONObject.NULL)
    if (m.googleCover != null) o.put("google_cover", m.googleCover)
    o.put("rating", m.rating ?: JSONObject.NULL)
    o.put("rating_count", m.ratingCount ?: JSONObject.NULL)
    o.put("rating_source", m.ratingSource ?: JSONObject.NULL)
    o.put("rating_asked", true)
    return o
}

fun metaFromJson(o: JSONObject): BookMeta = BookMeta(
    source = o.optString("source", "").takeIf { it.isNotBlank() },
    title = o.optString("title", "").takeIf { it.isNotBlank() },
    author = o.optString("author", "").takeIf { it.isNotBlank() },
    description = o.optString("description", "").takeIf { it.isNotBlank() },
    year = if (o.isNull("year")) null else o.optInt("year").takeIf { it > 0 },
    subjects = stringList(o.optJSONArray("subjects")),
    isbn = o.optString("isbn", "").takeIf { it.isNotBlank() },
    coverId = o.optString("cover_id", "").takeIf { it.isNotBlank() },
    key = o.optString("key", "").takeIf { it.isNotBlank() },
    pages = if (o.isNull("pages")) null else o.optInt("pages").takeIf { it > 0 },
    googleCover = o.optString("google_cover", "").takeIf { it.isNotBlank() },
    rating = if (o.isNull("rating")) null else o.optDouble("rating").takeIf { it > 0.0 },
    ratingCount = if (o.isNull("rating_count")) null else o.optInt("rating_count").takeIf { it > 0 },
    ratingSource = o.optString("rating_source", "").takeIf { it.isNotBlank() },
)

private fun metaPathFor(bookId: String): File = File(LibraryCache.dir("meta"), "$bookId.json")

fun metaForBook(book: Book, refresh: Boolean = false, context: String? = null): BookMeta {
    val where = context ?: book.series
    val path = metaPathFor(book.id)
    var cached: BookMeta? = null
    if (path.exists() && !refresh) {
        try {
            val stored = JSONObject(path.readText())
            cached = metaFromJson(stored)
            if (stored.optBoolean("rating_asked", false)) return cached
        } catch (_: Throwable) {
        }
    }
    val info = lookupMeta(book.title, book.author, where)
    if (Net.offline() && info.source == null) return cached ?: info
    try {
        path.writeText(metaToJson(info).toString())
    } catch (_: Throwable) {
    }
    return info
}

fun metaSweep(books: List<Book>, log: ((String) -> Unit)? = null, stop: () -> Boolean = { false }): Int {
    var done = 0
    for (b in books) {
        if (stop()) break
        if (cachedSubjects(b.id) != null) continue
        if (Net.offline()) break
        metaForBook(b)
        done++
        log?.invoke("meta: ${b.title}")
    }
    return done
}

// The cache key is the book id (the file path), so re-adding a deleted
// book (different path) and adding a duplicate copy of a book with
// a different filename both miss the cache. The MD5 of the contents
// (`b.checksum`) survives that — two files with the same content
// hash to the same string regardless of what they're called or where
// they live. Before the meta sweep runs, walk the index once and
// for every book whose own cache file is missing but whose checksum
// matches a book that already has one, copy the cached JSON into
// place. The existing `cachedSubjects(b.id)` skip in `metaSweep` then
// finds it on the next iteration, so the online lookup never runs.
// audiobook/pdfbook.py::book_hash is the same idea — the BookNLP
// cache directory is keyed on the content hash for the same reason.
internal fun promoteChecksumCache(books: List<Book>, log: ((String) -> Unit)? = null): Int {
    val byChecksum = books.mapNotNull { b ->
        b.checksum?.takeIf { it.isNotBlank() }?.let { it to b }
    }.groupBy({ it.first }, { it.second })
    var promoted = 0
    for (b in books) {
        val sum = b.checksum?.takeIf { it.isNotBlank() } ?: continue
        val myCache = metaPathFor(b.id)
        if (myCache.exists()) continue
        val peers = byChecksum[sum].orEmpty().filter { it.id != b.id }
        for (peer in peers) {
            val peerCache = metaPathFor(peer.id)
            if (!peerCache.exists()) continue
            try {
                myCache.parentFile?.mkdirs()
                myCache.writeText(peerCache.readText())
                promoted++
                log?.invoke("cache promote: ${b.title} ← ${peer.title}")
                break
            } catch (_: Throwable) {
            }
        }
    }
    return promoted
}
