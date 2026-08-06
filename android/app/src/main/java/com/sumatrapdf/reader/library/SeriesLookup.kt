package com.sumatrapdf.reader.library

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

const val WIKIDATA_API = "https://www.wikidata.org/w/api.php"
const val OPENLIBRARY_HOST = "https://openlibrary.org"
const val OPENLIBRARY_SEARCH_URL = "https://openlibrary.org/search.json"

const val PART_OF_SERIES = "P179"
const val INSTANCE_OF = "P31"
const val AUTHOR_ITEM = "P50"
const val AUTHOR_NAME = "P2093"
const val SERIES_ORDINAL = "P1545"

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

private val VOLUME_TAIL = Regex(
    """[\s,;:]*(?:#|no\.?|nr\.?|bk\.?|book|vol\.?|volume|part|pt\.?|band|t\.)?\s*""" +
        """[\(\[]?\d{1,3}(?:\.\d)?[\)\]]?\s*$""",
    RegexOption.IGNORE_CASE,
)
private val TRAILING_NUMBER = Regex("""(\d{1,3})\s*$""")
private val NOT_LETTERS = Regex("[^a-z0-9]+")

data class SeriesHit(
    val name: String,
    val source: String,
    val index: Int? = null,
    val author: String? = null,
    val year: Int? = null,
    val subjects: List<String> = emptyList(),
)

private class Candidate(
    val label: String,
    val series: String,
    val authors: List<String>,
    val named: List<String>,
    val index: Int?,
)

fun squashName(text: String?): String =
    NOT_LETTERS.replace((text ?: "").lowercase(), " ").trim()

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

private fun wikidata(params: List<Pair<String, String>>): JSONObject? {
    val query = (params + ("format" to "json"))
        .joinToString("&") { "${it.first}=${urlEncode(it.second)}" }
    return Net.fetchJson("$WIKIDATA_API?$query")
}

private fun entityIds(title: String): List<String> {
    val found = wikidata(
        listOf(
            "action" to "wbsearchentities",
            "language" to "en",
            "uselang" to "en",
            "type" to "item",
            "limit" to SEARCH_LIMIT.toString(),
            "search" to title,
        ),
    ) ?: return emptyList()
    val arr = found.optJSONArray("search") ?: return emptyList()
    return (0 until arr.length()).mapNotNull {
        arr.optJSONObject(it)?.optString("id")?.takeIf { id -> id.isNotBlank() }
    }
}

private fun entities(ids: List<String>, props: String): JSONObject? {
    if (ids.isEmpty()) return null
    val found = wikidata(
        listOf(
            "action" to "wbgetentities",
            "props" to props,
            "languages" to "en",
            "languagefallback" to "1",
            "ids" to ids.joinToString("|"),
        ),
    ) ?: return null
    return found.optJSONObject("entities")
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

private fun wikidataSeries(title: String, author: String?): SeriesHit? {
    val ids = entityIds(title)
    val found = entities(ids, "claims|labels") ?: return null
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
    if (candidates.isEmpty()) return null
    val labels = LinkedHashMap<String, String>()
    val batch = wanted.toList()
    var at = 0
    while (at < batch.size) {
        val slice = batch.subList(at, minOf(at + LABEL_BATCH, batch.size))
        val page = entities(slice, "labels")
        for (id in slice) labels[id] = labelOf(page?.optJSONObject(id))
        at += LABEL_BATCH
    }

    fun rank(row: Candidate): Double {
        var score = nameScore(title, row.label)
        val names = (row.authors.map { labels[it] ?: "" } + row.named).filter { it.isNotBlank() }
        if (!author.isNullOrBlank() && names.isNotEmpty()) {
            score += if (names.any { nameScore(author, it) >= NAME_MATCH || samePerson(author, it) }) 1.0 else -1.0
        }
        return score
    }

    val best = candidates.maxByOrNull { rank(it) } ?: return null
    if (rank(best) < (if (author.isNullOrBlank()) NO_AUTHOR_SCORE else WITH_AUTHOR_SCORE)) return null
    val name = labels[best.series].orEmpty()
    if (name.isBlank()) return null
    return SeriesHit(name = name, source = "wikidata", index = best.index, author = author)
}

fun stripVolume(text: String): String =
    VOLUME_TAIL.replace(text.trim(), "").trim(' ', ',', ';', ':', '-', '(', '[')

private fun openLibrarySeries(title: String, author: String?): SeriesHit? {
    if (author.isNullOrBlank()) return null
    val query = listOf(
        "limit=3",
        "fields=key,title,author_name",
        "title=" + urlEncode(title),
        "author=" + urlEncode(author),
    ).joinToString("&")
    val found = Net.fetchJson("$OPENLIBRARY_SEARCH_URL?$query") ?: return null
    val docs = found.optJSONArray("docs") ?: return null
    for (i in 0 until docs.length()) {
        val doc = docs.optJSONObject(i) ?: continue
        if (nameScore(title, doc.optString("title")) < TITLE_MATCH) continue
        val names = doc.optJSONArray("author_name")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        } ?: emptyList()
        if (names.none { nameScore(author, it) >= NAME_MATCH || samePerson(author, it) }) continue
        val key = doc.optString("key")
        if (!key.startsWith("/works/")) continue
        val editions = Net.fetchJson("$OPENLIBRARY_HOST$key/editions.json?limit=$EDITION_LIMIT")
        val entries = editions?.optJSONArray("entries") ?: continue
        val votes = LinkedHashMap<String, Int>()
        val ordinals = LinkedHashMap<String, Int>()
        for (e in 0 until entries.length()) {
            val list = entries.optJSONObject(e)?.optJSONArray("series") ?: continue
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
        if (top.value < MIN_EDITION_VOTES) return null
        return SeriesHit(
            name = top.key,
            source = "openlibrary",
            index = ordinals[top.key],
            author = author,
        )
    }
    return null
}

fun seriesFor(book: Book): SeriesHit? {
    val cached = cachedSeries(book.id)
    if (cached != null) return cached
    val title = book.title.takeIf { it.isNotBlank() && it != "null" } ?: book.file
    if (squashName(title).length < 3) return null
    val hit = wikidataSeries(title, book.author) ?: openLibrarySeries(title, book.author)
    if (hit != null) cacheSeries(book.id, hit)
    return hit
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
