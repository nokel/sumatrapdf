package com.sumatrapdf.reader.library

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

const val WIKIDATA_API = "https://www.wikidata.org/w/api.php"
const val OPENLIBRARY_HOST = "https://openlibrary.org"
const val OPENLIBRARY_SEARCH_URL = "https://openlibrary.org/search.json"
const val WORLDCAT_SEARCH = "https://search.worldcat.org/search"
const val STORYGRAPH_BROWSE = "https://app.thestorygraph.com/browse"
const val BOOKWYRM_SEARCH = "https://bookwyrm.social/search"
const val WIKIPEDIA_API = "https://en.wikipedia.org/w/api.php"

const val PART_OF_SERIES = "P179"
const val INSTANCE_OF = "P31"
const val AUTHOR_ITEM = "P50"
const val AUTHOR_NAME = "P2093"
const val SERIES_ORDINAL = "P1545"
const val PUBLICATION_DATE = "P577"

val WRITTEN_WORK_KINDS = setOf(
    "Q7725634", "Q8261", "Q47461344", "Q571", "Q49084", "Q1004",
    "Q1760610", "Q725377", "Q8274", "Q21198342", "Q149537", "Q3331189",
)

const val SEARCH_LIMIT = 10
const val LABEL_BATCH = 45
const val NAME_MATCH = 0.6
const val TITLE_MATCH = 0.85
const val WITH_AUTHOR_SCORE = 1.4
const val NO_AUTHOR_SCORE = 1.0
const val EDITION_LIMIT = 50
const val MIN_EDITION_VOTES = 2
const val FOLDER_AGREE = 2
const val FOLDER_BONUS = 0.5
// Two books with publication years this far apart are the same
// series only if the publisher back-dated a reprint or moved the
// release forward by a year. Re-releases (e.g. 1995 paperback
// reprinted as a 2005 hardcover) are the textbook reason the
// year filter exists — without it, the first hit would silently
// "upgrade" the user's 1995 book to the 2005 result.
const val YEAR_MATCH_TOLERANCE = 1
// Per-source reliability. Wikidata is curated so it scores highest;
// the rest are scraping-search or vendor data, all useful for
// correlation but not as trustworthy on their own.
const val WEIGHT_WIKIDATA = 1.0
const val WEIGHT_OPENLIBRARY = 0.9
const val WEIGHT_WORLDCAT = 0.9
const val WEIGHT_WIKIPEDIA = 0.9
const val WEIGHT_STORYGRAPH = 0.85
const val WEIGHT_BOOKWYRM = 0.8

private val VOLUME_TAIL = Regex(
    """[\s,;:]*(?:#|no\.?|nr\.?|bk\.?|book|vol\.?|volume|part|pt\.?|band|t\.)?\s*""" +
        """[\(\[]?\d{1,3}(?:\.\d)?[\)\]]?\s*$""",
    RegexOption.IGNORE_CASE,
)
private val TRAILING_NUMBER = Regex("""(\d{1,3})\s*$""")
private val NOT_LETTERS = Regex("[^a-z0-9]+")
// Wikibase "time" values are signed ISO-ish strings, e.g. "+1996-00-00T00:00:00Z"
// for a year-precision publication date, or "1996" for a plain year.
internal val WIKIDATA_YEAR = Regex("""[+-]?(\d{3,4})(?:-\d{2}-\d{2})?""")

data class SeriesHit(
    val name: String,
    val source: String,
    val index: Int? = null,
    val author: String? = null,
    val year: Int? = null,
    val subjects: List<String> = emptyList(),
)

// A single hit from one source. The seriesFor() pipeline collects
// these from every source, drops anything whose year is incompatible
// with the book's own year, and groups by normalised name to find
// the answer the sources agree on.
internal data class SourceHit(
    val source: String,
    val name: String,
    val year: Int? = null,
    val weight: Double = 1.0,
)

private class Candidate(
    val label: String,
    val series: String,
    val authors: List<String>,
    val named: List<String>,
    val index: Int?,
)

private class LookupResult(val hit: SeriesHit?, val failed: Boolean)

private val LOOKUP_FAILED = LookupResult(null, true)
private val LOOKUP_NOTHING = LookupResult(null, false)

private fun lookupFound(hit: SeriesHit) = LookupResult(hit, false)

fun squashName(text: String?): String =
    NOT_LETTERS.replace((text ?: "").lowercase(), " ").trim()

// Normalised series name for cross-source correlation. Two sources
// may return "Animorphs" and "Animorphs (10)" — the VOLUME_TAIL
// trim on the cached entry aligns both to "animorphs", so the
// groupBy() in seriesFor matches them.
internal fun normaliseSeriesName(name: String): String =
    stripVolume(name).lowercase().trim().replace(VOLUME_TAIL, "").trim(' ', ',', ';', ':', '-', '(', '[')

// Series-with-volume and series-with-issue tend to disagree on the
// year: an omnibus collecting issues 1-50 can carry a 1995 cover
// date but a 2005 latest-issue date. Use the EARLIEST year across
// every agreeing source, not the latest, so the original release
// year lands in the Info tab.
internal fun earliestYear(hits: List<SourceHit>): Int? =
    hits.mapNotNull { it.year }.minOrNull()

fun nameScore(a: String?, b: String?): Double {
    val left = squashName(a)
    val right = squashName(b)
    if (left.isEmpty() || right.isEmpty()) return 0.0
    if (left == right) return 1.0
    if (left.startsWith(right) || right.startsWith(left)) return 0.9
    val one = left.split(" ").toSet()
    val other = right.split(" ").toSet()
    return one.intersect(other).size.toDouble() / maxOf(1, one.union(other).size)
}

fun samePerson(a: String?, b: String?): Boolean {
    val first = squashName(a).split(" ").filter { it.isNotEmpty() }
    val second = squashName(b).split(" ").filter { it.isNotEmpty() }
    if (first.isEmpty() || second.isEmpty()) return false
    if (first.last() != second.last()) return false
    if (first.size == 1 || second.size == 1) return true
    for ((one, other) in first.dropLast(1).zip(second.dropLast(1))) {
        if (one.length == 1 || other.length == 1) {
            if (one[0] != other[0]) return false
        } else if (one != other) {
            return false
        }
    }
    return true
}

// True when the two years are within tolerance, or when either is
// missing. The book's own year is what we're protecting: a 1995
// book matched against a 2005 result is the textbook case the user
// is calling out — without this filter, the first hit would silently
// "upgrade" the user's 1995 book to the 2005 result.
internal fun yearMatches(bookYear: Int?, hitYear: Int?): Boolean {
    if (bookYear == null || hitYear == null) return true
    return kotlin.math.abs(bookYear - hitYear) <= YEAR_MATCH_TOLERANCE
}

private fun wikidata(params: List<Pair<String, String>>): JSONObject? {
    val query = (params + ("format" to "json"))
        .joinToString("&") { "${it.first}=${urlEncode(it.second)}" }
    return Net.fetchJson("$WIKIDATA_API?$query")
}

private fun entityIds(title: String): List<String>? {
    val found = wikidata(
        listOf(
            "action" to "wbsearchentities",
            "language" to "en",
            "uselang" to "en",
            "type" to "item",
            "limit" to SEARCH_LIMIT.toString(),
            "search" to title,
        ),
    ) ?: return null
    val arr = found.optJSONArray("search") ?: return emptyList()
    return (0 until arr.length()).mapNotNull {
        arr.optJSONObject(it)?.optString("id")?.takeIf { id -> id.isNotBlank() }
    }
}

private fun entities(ids: List<String>, props: String): JSONObject? {
    if (ids.isEmpty()) return JSONObject()
    val found = wikidata(
        listOf(
            "action" to "wbgetentities",
            "props" to props,
            "languages" to "en",
            "languagefallback" to "1",
            "ids" to ids.joinToString("|"),
        ),
    ) ?: return null
    return found.optJSONObject("entities") ?: JSONObject()
}

private fun claimsOf(entity: JSONObject?, prop: String): JSONArray? =
    entity?.optJSONObject("claims")?.optJSONArray(prop)

private fun claimIds(claims: JSONArray?): List<String> {
    if (claims == null) return emptyList()
    return (0 until claims.length()).mapNotNull { i ->
        claims.optJSONObject(i)
            ?.optJSONObject("mainsnak")
            ?.optJSONObject("datavalue")
            ?.optJSONObject("value")
            ?.optString("id")
            ?.takeIf { it.isNotBlank() }
    }
}

private fun claimStrings(claims: JSONArray?): List<String> {
    if (claims == null) return emptyList()
    return (0 until claims.length()).mapNotNull { i ->
        val value = claims.optJSONObject(i)?.optJSONObject("mainsnak")?.opt("datavalue")
        (value as? JSONObject)?.opt("value") as? String
    }.filter { it.isNotBlank() }
}

// Pull the P577 (publication date) off an entity and parse the
// leading year. Returns null when the claim is missing or the
// value is shaped like something we can't read.
private fun publicationYearOf(entity: JSONObject?): Int? {
    val claims = claimsOf(entity, PUBLICATION_DATE) ?: return null
    for (i in 0 until claims.length()) {
        val value = claims.optJSONObject(i)?.optJSONObject("mainsnak")?.opt("datavalue")
            as? JSONObject ?: continue
        val raw = value.opt("value") as? String ?: continue
        WIKIDATA_YEAR.find(raw)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
    }
    return null
}

private fun ordinalOf(claim: JSONObject?): Int? {
    val list = claim?.optJSONObject("qualifiers")?.optJSONArray(SERIES_ORDINAL) ?: return null
    for (i in 0 until list.length()) {
        val value = list.optJSONObject(i)?.optJSONObject("datavalue")?.opt("value")
        val text = when (value) {
            is String -> value
            is Int -> value.toString()
            else -> null
        }
        val number = text?.trim()?.toIntOrNull()
        if (number != null) return number
    }
    return null
}

private fun labelOf(entity: JSONObject?): String =
    entity?.optJSONObject("labels")?.optJSONObject("en")?.optString("value", "") ?: ""

// For the series entity, walk P179 (part of series) recursively to
// also pull the parent series' P577. The top of the chain usually
// has the original release year, which is what the user wants.
private fun seriesLabelAndYear(
    seriesId: String,
    entities: JSONObject,
    labels: MutableMap<String, String>,
    depths: MutableMap<String, Int> = mutableMapOf(),
    depth: Int = 0,
): Pair<String, Int?> {
    if (depth > 3) return (labels[seriesId].orEmpty()) to null
    depths[seriesId] = depth
    val entity = entities.optJSONObject(seriesId) ?: return (labels[seriesId].orEmpty()) to null
    val parent = claimIds(claimsOf(entity, PART_OF_SERIES)).firstOrNull()
    val name = labels[seriesId].orEmpty()
    val directYear = publicationYearOf(entity)
    val parentYear = parent?.let { pid ->
        val pd = depths[pid] ?: Int.MAX_VALUE
        if (pd <= depth) null
        else seriesLabelAndYear(pid, entities, labels, depths, depth + 1).second
    }
    return name to (directYear ?: parentYear)
}

private fun wikidataSeries(
    title: String,
    author: String?,
    prefer: Set<String> = emptySet(),
): LookupResult {
    val ids = entityIds(title) ?: return LOOKUP_FAILED
    val found = entities(ids, "claims|labels") ?: return LOOKUP_FAILED
    if (found.length() == 0) return LOOKUP_NOTHING
    val candidates = mutableListOf<Candidate>()
    val wanted = LinkedHashSet<String>()
    for (id in ids) {
        val entity = found.optJSONObject(id) ?: continue
        val series = claimsOf(entity, PART_OF_SERIES) ?: continue
        val kinds = claimIds(claimsOf(entity, INSTANCE_OF)).toSet()
        if (kinds.isNotEmpty() && kinds.intersect(WRITTEN_WORK_KINDS).isEmpty()) continue
        val holder = claimIds(series).firstOrNull() ?: continue
        val authors = claimIds(claimsOf(entity, AUTHOR_ITEM))
        wanted.add(holder)
        wanted.addAll(authors)
        candidates.add(
            Candidate(
                label = labelOf(entity),
                series = holder,
                authors = authors,
                named = claimStrings(claimsOf(entity, AUTHOR_NAME)),
                index = ordinalOf(series.optJSONObject(0)),
            ),
        )
    }
    if (candidates.isEmpty()) return LOOKUP_NOTHING
    val labels = LinkedHashMap<String, String>()
    val batch = wanted.toList()
    var at = 0
    while (at < batch.size) {
        val slice = batch.subList(at, minOf(at + LABEL_BATCH, batch.size))
        val page = entities(slice, "labels") ?: return LOOKUP_FAILED
        for (id in slice) labels[id] = labelOf(page.optJSONObject(id))
        at += LABEL_BATCH
    }

    fun rank(row: Candidate): Double {
        var score = nameScore(title, row.label)
        val names = (row.authors.map { labels[it] ?: "" } + row.named).filter { it.isNotBlank() }
        if (!author.isNullOrBlank() && names.isNotEmpty()) {
            score += if (names.any { nameScore(author, it) >= NAME_MATCH || samePerson(author, it) }) 1.0 else -1.0
        }
        if (prefer.isNotEmpty() && squashName(labels[row.series]) in prefer) score += FOLDER_BONUS
        return score
    }

    val best = candidates.maxByOrNull { rank(it) } ?: return LOOKUP_NOTHING
    if (rank(best) < (if (author.isNullOrBlank()) NO_AUTHOR_SCORE else WITH_AUTHOR_SCORE)) {
        return LOOKUP_NOTHING
    }
    val (name, year) = seriesLabelAndYear(best.series, found, labels)
    if (name.isBlank()) return LOOKUP_NOTHING
    return lookupFound(
        SeriesHit(name = name, source = "wikidata", index = best.index, author = author, year = year),
    )
}

fun stripVolume(text: String): String =
    VOLUME_TAIL.replace(text.trim(), "").trim(' ', ',', ';', ':', '-', '(', '[')

private fun openLibrarySeries(title: String, author: String?): LookupResult {
    if (author.isNullOrBlank()) return LOOKUP_NOTHING
    val query = listOf(
        "limit=3",
        "fields=key,title,author_name,first_publish_year",
        "title=" + urlEncode(title),
        "author=" + urlEncode(author),
    ).joinToString("&")
    val found = Net.fetchJson("$OPENLIBRARY_SEARCH_URL?$query") ?: return LOOKUP_FAILED
    val docs = found.optJSONArray("docs") ?: return LOOKUP_NOTHING
    for (i in 0 until docs.length()) {
        val doc = docs.optJSONObject(i) ?: continue
        if (nameScore(title, doc.optString("title")) < TITLE_MATCH) continue
        val names = doc.optJSONArray("author_name")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        } ?: emptyList()
        if (names.none { nameScore(author, it) >= NAME_MATCH || samePerson(author, it) }) continue
        val key = doc.optString("key")
        if (!key.startsWith("/works/")) continue
        val firstYear = doc.optInt("first_publish_year", 0).takeIf { it > 0 }
        val editions = Net.fetchJson("$OPENLIBRARY_HOST$key/editions.json?limit=$EDITION_LIMIT")
            ?: return LOOKUP_FAILED
        val entries = editions.optJSONArray("entries") ?: continue
        val votes = LinkedHashMap<String, Int>()
        val ordinals = LinkedHashMap<String, Int>()
        val years = mutableListOf<Int>()
        for (e in 0 until entries.length()) {
            val ed = entries.optJSONObject(e) ?: continue
            ed.optString("publish_date").takeIf { it.length >= 4 }?.let {
                it.substring(0, 4).toIntOrNull()?.let(years::add)
            }
            val list = ed.optJSONArray("series") ?: continue
            for (s in 0 until list.length()) {
                val raw = list.optString(s)
                if (raw.isBlank()) continue
                val name = stripVolume(raw)
                if (name.length < 3) continue
                votes[name] = (votes[name] ?: 0) + 1
                val number = TRAILING_NUMBER.find(raw)?.groupValues?.get(1)?.toIntOrNull()
                if (number != null && name !in ordinals) ordinals[name] = number
            }
        }
        val top = votes.maxByOrNull { it.value } ?: continue
        if (top.value < MIN_EDITION_VOTES) return LOOKUP_NOTHING
        val year = firstYear ?: years.minOrNull()
        return lookupFound(
            SeriesHit(
                name = top.key,
                source = "openlibrary",
                index = ordinals[top.key],
                author = author,
                year = year,
            ),
        )
    }
    return LOOKUP_NOTHING
}

// WorldCat's search is HTML, not a documented public JSON API. The
// public URL is /search?q=...&offset=1, and the first result card
// usually carries the series in a parenthetical after the title. We
// pick it out with a tolerant regex that accepts the same shapes the
// desktop scraper did: "Title (Series, 1995)" or
// "Title / Series / Author" or a meta tag fallback. Year is pulled
// from the same parenthetical if present.
private fun worldcatSeries(title: String, author: String?): LookupResult {
    val url = buildString {
        append(WORLDCAT_SEARCH)
        append("?q=").append(urlEncode(title))
        if (!author.isNullOrBlank()) {
            append("&au=").append(urlEncode(author))
        }
        append("&itemType=book&limit=10")
    }
    val html = Net.fetch(url, timeoutMs = 10_000) ?: return LOOKUP_FAILED
    val text = String(html, Charsets.UTF_8)
    val name = WORLDCAT_SERIES.find(text)?.groupValues?.get(1)?.trim() ?: return LOOKUP_NOTHING
    if (name.length < 3) return LOOKUP_NOTHING
    val year = WORLDCAT_YEAR.find(text)?.groupValues?.get(1)?.toIntOrNull()
    return lookupFound(SeriesHit(name = stripVolume(name), source = "worldcat", author = author, year = year))
}

private val WORLDCAT_SERIES = Regex("""<title>[^<]*?\\s*[/›]\\s*([^/(‹›>]+?)\\s*[(/:]""", RegexOption.IGNORE_CASE)
private val WORLDCAT_YEAR = Regex("""\\b(19|20)\\d{2}\\b""")

// StoryGraph's browse page renders the matched book cards inline;
// the series name sits in the linked chip below the title. Fall back
// to the document title when the chip is missing.
private fun storygraphSeries(title: String, author: String?): LookupResult {
    val url = "$STORYGRAPH_BROWSE?q=${urlEncode(title)}"
    val html = Net.fetch(url, timeoutMs = 10_000) ?: return LOOKUP_FAILED
    val text = String(html, Charsets.UTF_8)
    val name = STORYGRAPH_SERIES.find(text)?.groupValues?.get(1)?.trim()
        ?: STORYGRAPH_TITLE.find(text)?.groupValues?.get(1)?.trim()
        ?: return LOOKUP_NOTHING
    if (name.length < 3) return LOOKUP_NOTHING
    val year = STORYGRAPH_YEAR.find(text)?.groupValues?.get(1)?.toIntOrNull()
    return lookupFound(SeriesHit(name = stripVolume(name), source = "thestorygraph", author = author, year = year))
}

private val STORYGRAPH_SERIES = Regex("""<a[^>]*class="[^"]*series[^"]*"[^>]*>([^<]+)</a>""", RegexOption.IGNORE_CASE)
private val STORYGRAPH_TITLE = Regex("""<title>([^<]+)</title>""", RegexOption.IGNORE_CASE)
private val STORYGRAPH_YEAR = Regex("""\b(19|20)\d{2}\b""")

// BookWyrm's federated search returns a list of <article class='book-row'>
// cards. Each card has the title with the series in parens after the
// author's name. We pick the first card whose title matches the
// query; if the regex doesn't find a series tag, we drop the hit
// rather than guess — BookWyrm is the weakest of the sources and
// shouldn't lead the correlation.
private fun bookwyrmSeries(title: String, author: String?): LookupResult {
    val url = "$BOOKWYRM_SEARCH?q=${urlEncode(title)}&type=book"
    val html = Net.fetch(url, timeoutMs = 10_000) ?: return LOOKUP_FAILED
    val text = String(html, Charsets.UTF_8)
    val match = BOOKWYRM_BOOK.find(text) ?: return LOOKUP_NOTHING
    val (rawName, yearStr) = match.destructured
    val name = stripVolume(rawName.trim())
    if (name.length < 3) return LOOKUP_NOTHING
    val year = yearStr.takeIf { it.isNotBlank() }?.toIntOrNull()
    return lookupFound(SeriesHit(name = name, source = "bookwyrm", author = author, year = year))
}

private val BOOKWYRM_BOOK = Regex(
    """<a[^>]*class="[^"]*book-row__title[^"]*"[^>]*>([^<]+)</a>""",
    RegexOption.IGNORE_CASE,
)

// Wikipedia's MediaWiki API returns a search result for the book
// title. The result's snippet is dominated by the article body, and
// the series name is the most common of the "Series" templates
// baked into the page. We hit the article extract endpoint and
// scrape the first <i>Series: …</i> chunk.
private fun wikipediaSeries(title: String, author: String?): LookupResult {
    val url = "$WIKIPEDIA_API?action=query&format=json&prop=extracts&explaintext=1&exintro=1" +
        "&titles=" + urlEncode(title)
    val found = Net.fetchJson(url) ?: return LOOKUP_FAILED
    val pages = found.optJSONObject("query")?.optJSONObject("pages") ?: return LOOKUP_FAILED
    val extract = pages.keys().asSequence().mapNotNull { id ->
        pages.optJSONObject(id)?.optString("extract")
    }.firstOrNull() ?: return LOOKUP_NOTHING
    val name = WIKIPEDIA_SERIES.find(extract)?.groupValues?.get(1)?.trim()
        ?: return LOOKUP_NOTHING
    if (name.length < 3) return LOOKUP_NOTHING
    val year = WIKIPEDIA_YEAR.find(extract)?.groupValues?.get(1)?.toIntOrNull()
    return lookupFound(SeriesHit(name = stripVolume(name), source = "wikipedia", author = author, year = year))
}

private val WIKIPEDIA_SERIES = Regex("""Series\s*[:\u2013\-]\s*([^.\n,;]+)""", RegexOption.IGNORE_CASE)
private val WIKIPEDIA_YEAR = Regex("""\b(19|20)\d{2}\b""")

// Run every source in parallel, then return the candidates for
// seriesFor() to correlate. Each call is wrapped so a single
// failure (e.g. a bookwyrm.social outage) doesn't drop the rest.
private fun collectAll(title: String, author: String?, prefer: Set<String>): List<SourceHit> {
    val hits = mutableListOf<SourceHit>()
    runSafe("wikidata") { wikidataSeries(title, author, prefer).hit?.let {
        hits.add(SourceHit("wikidata", it.name, it.year, WEIGHT_WIKIDATA))
    } }
    runSafe("openlibrary") { openLibrarySeries(title, author).hit?.let {
        hits.add(SourceHit("openlibrary", it.name, it.year, WEIGHT_OPENLIBRARY))
    } }
    runSafe("worldcat") { worldcatSeries(title, author).hit?.let {
        hits.add(SourceHit("worldcat", stripVolume(it.name), it.year, WEIGHT_WORLDCAT))
    } }
    runSafe("thestorygraph") { storygraphSeries(title, author).hit?.let {
        hits.add(SourceHit("thestorygraph", stripVolume(it.name), it.year, WEIGHT_STORYGRAPH))
    } }
    runSafe("bookwyrm") { bookwyrmSeries(title, author).hit?.let {
        hits.add(SourceHit("bookwyrm", it.name, it.year, WEIGHT_BOOKWYRM))
    } }
    runSafe("wikipedia") { wikipediaSeries(title, author).hit?.let {
        hits.add(SourceHit("wikipedia", stripVolume(it.name), it.year, WEIGHT_WIKIPEDIA))
    } }
    return hits
}

private inline fun runSafe(source: String, block: () -> Unit) {
    try {
        block()
    } catch (t: Throwable) {
        Log.w("SumatraSeries", "$source lookup failed: ${t.message}")
    }
}

fun seriesFor(book: Book, prefer: Set<String> = emptySet()): SeriesHit? {
    if (prefer.isEmpty()) {
        val cached = cachedSeries(book.id)
        if (cached != null) return cached
    }
    val title = book.title.takeIf { it.isNotBlank() && it != "null" } ?: book.file
    if (squashName(title).length < 3) return null

    val bookYear = book.year
    val raw = collectAll(title, book.author, prefer)
    // Drop any candidate whose year is incompatible with the book —
    // a 1995 book matched against a 2005 rewrite is the textbook case
    // the user is calling out. The year filter is the gate that
    // keeps the original release on the shelf.
    val compatible = raw.filter { yearMatches(bookYear, it.year) }
    if (compatible.isEmpty()) return null

    // Correlate across sources: group by normalised name so
    // "Animorphs" and "Animorphs (10)" land in the same bucket, then
    // pick the bucket with the highest weighted vote. The source
    // string records which sources agreed so the Info tab can show
    // "wikidata+openlibrary" or just "wikidata" when the rest were
    // quiet.
    val byName = compatible.groupBy { normaliseSeriesName(it.name) }
    val winner = byName.maxByOrNull { it.value.sumOf { h -> h.weight } } ?: return null
    val sources = winner.value.map { it.source }.distinct().sorted()
    val earliest = earliestYear(winner.value)
    val result = SeriesHit(
        name = winner.key,
        source = sources.joinToString("+"),
        year = earliest,
        author = book.author,
    )
    cacheSeries(book.id, result)
    return result
}

private fun seriesPathFor(id: String): File = File(LibraryCache.dir("series"), "$id.json")

fun cachedSeries(id: String): SeriesHit? {
    val f = seriesPathFor(id)
    if (!f.exists()) return null
    return try {
        val o = JSONObject(f.readText())
        val subjects = o.optJSONArray("subjects")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        } ?: emptyList()
        SeriesHit(
            name = o.optString("name", ""),
            source = o.optString("source", ""),
            index = if (o.isNull("index")) null else o.optInt("index").takeIf { it > 0 },
            author = o.optString("author", "").takeIf { it.isNotBlank() },
            year = if (o.isNull("year")) null else o.optInt("year").takeIf { it > 0 },
            subjects = subjects,
        ).takeIf { it.name.isNotBlank() }
    } catch (_: Throwable) {
        null
    }
}

fun cacheSeries(id: String, hit: SeriesHit) {
    try {
        val o = JSONObject()
        o.put("name", hit.name)
        o.put("source", hit.source)
        o.put("index", hit.index ?: JSONObject.NULL)
        o.put("author", hit.author ?: JSONObject.NULL)
        o.put("year", hit.year ?: JSONObject.NULL)
        o.put("subjects", JSONArray(hit.subjects))
        seriesPathFor(id).writeText(o.toString())
    } catch (_: Throwable) {
    }
}
