package com.sumatrapdf.reader.library

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// Port of audiobook/library/screen.py — which films and TV series came
// from this book. IMDb's suggestion endpoint proposes titles, the
// writer credits confirm the author wrote it, and Wikidata's "based on"
// relation adds the ones IMDb's own search misses.
//
// screen.py::cast is not ported: it reaches into the desktop project's
// cast_lookup module, which is not part of the app.

const val IMDB_SUGGESTION = "https://v3.sg.media-imdb.com/suggestion/x/%s.json"
const val IMDB_GRAPHQL = "https://api.graphql.imdb.com/"
const val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"

const val WRITER_QUERY = "query W(\$id: ID!) {" +
    "  title(id: \$id) {" +
    "    credits(first: 30, filter: {categories: [\"writer\", \"creator\"]}) {" +
    "      edges { node { name { nameText { text } } } }" +
    "    }" +
    "  }" +
    "}"

const val TITLE_QUERY = "query T(\$id: ID!) {" +
    "  title(id: \$id) {" +
    "    titleText { text }" +
    "    releaseYear { year }" +
    "    titleType { id text }" +
    "    primaryImage { url }" +
    "  }" +
    "}"

const val WDQS = "https://query.wikidata.org/sparql"
const val BASED_ON_QUERY = """SELECT ?workLabel ?typeLabel ?year ?imdb WHERE {
  ?book rdfs:label ?bt .
  FILTER(LANG(?bt) = "en" && LCASE(STR(?bt)) = "%s")
  ?book wdt:P50 ?person . ?person rdfs:label ?pn .
  FILTER(LANG(?pn) = "en" && CONTAINS(LCASE(STR(?pn)), "%s"))
  ?work wdt:P144 ?book . ?work wdt:P31 ?type .
  OPTIONAL { ?work wdt:P345 ?imdb }
  OPTIONAL { ?work wdt:P577 ?d . BIND(YEAR(?d) AS ?year) }
  SERVICE wikibase:label { bd:serviceParam wikibase:language "en". }
} LIMIT 20"""

const val CHECK_LIMIT = 8
const val WIKIDATA_TTL_MS = 60L * 24 * 3600 * 1000
const val SCREEN_TTL_MS = 21L * 24 * 3600 * 1000
const val MIN_SCREEN_MATCH = 0.6
const val MAX_SCREEN_RESULTS = 8

val SCREEN_KINDS = mapOf(
    "movie" to "Film",
    "tvSeries" to "TV series",
    "tvMiniSeries" to "TV mini-series",
    "tvMovie" to "TV film",
    "tvSpecial" to "TV special",
    "video" to "Video",
    "short" to "Short",
)

val DROP_KINDS = setOf("videoGame", "tvEpisode", "musicVideo", "podcastSeries", "podcastEpisode")

data class ScreenTitle(
    val imdbId: String,
    val title: String?,
    val kind: String,
    val kindId: String,
    val year: Int?,
    val poster: String?,
    val stars: List<String>,
    val rank: Int,
    var match: Double = 0.0,
    var via: String? = null,
    var confirmed: Boolean = false,
)

private val NOT_QUERY_CHAR = Regex("""[^a-zA-Z0-9 ']""")
private val RUN_SPACES = Regex("""\s+""")
private val POSTER_SIZE = Regex("""\._V1_.*?(\.[a-z]+)$""")

private fun screenQuery(text: String?): String {
    var q = NOT_QUERY_CHAR.replace(text ?: "", " ")
    q = RUN_SPACES.replace(q, " ").trim().lowercase()
    return urlEncode(q)
}

private fun posterUrl(row: JSONObject): String? {
    val url = row.optJSONObject("i")?.optString("imageUrl", "")?.takeIf { it.isNotBlank() }
        ?: return null
    return POSTER_SIZE.replace(url) { "._V1_SX300" + it.groupValues[1] }
}

private fun yearOf(row: JSONObject): Int? {
    val y = row.optInt("y", 0)
    if (y > 0) return y
    val m = Regex("""^(\d{4})""").find(row.optString("yr", ""))
    return m?.groupValues?.get(1)?.toIntOrNull()
}

fun suggestTitles(text: String?): List<JSONObject> {
    val q = screenQuery(text)
    if (q.isEmpty()) return emptyList()
    val data = Net.fetchJson(IMDB_SUGGESTION.format(q), ttl = SCREEN_TTL_MS) ?: return emptyList()
    val arr = data.optJSONArray("d") ?: return emptyList()
    return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
}

private fun screenRows(text: String, wantTitle: String, notBefore: Int?): List<ScreenTitle> {
    val out = mutableListOf<ScreenTitle>()
    for (row in suggestTitles(text)) {
        val kind = row.optString("qid", "")
        val id = row.optString("id", "")
        if (kind in DROP_KINDS || !id.startsWith("tt")) continue
        val score = titleScore(wantTitle, row.optString("l"))
        if (score < MIN_SCREEN_MATCH) continue
        val year = yearOf(row)
        if (notBefore != null && year != null && year < notBefore - 1) continue
        out.add(
            ScreenTitle(
                imdbId = id,
                title = row.optString("l", "").takeIf { it.isNotBlank() },
                kind = SCREEN_KINDS[kind] ?: kind.ifBlank { "Title" },
                kindId = kind,
                year = year,
                poster = posterUrl(row),
                stars = row.optString("s", "").split(", ").filter { it.isNotBlank() },
                rank = row.optInt("rank", 999999).takeIf { it > 0 } ?: 999999,
                match = Math.round(score * 1000) / 1000.0,
            ),
        )
    }
    return out
}

fun screenWriters(imdbId: String?): List<String> {
    if (imdbId.isNullOrBlank()) return emptyList()
    val payload = JSONObject()
    payload.put("query", WRITER_QUERY)
    payload.put("variables", JSONObject().put("id", imdbId))
    val data = Net.postJson(
        IMDB_GRAPHQL, payload, ttl = SCREEN_TTL_MS,
        headers = mapOf("User-Agent" to BROWSER_UA),
    ) ?: return emptyList()
    if (data.has("errors")) return emptyList()
    val title = data.optJSONObject("data")?.optJSONObject("title") ?: return emptyList()
    val edges = title.optJSONObject("credits")?.optJSONArray("edges") ?: return emptyList()
    val out = mutableListOf<String>()
    for (i in 0 until edges.length()) {
        val name = edges.optJSONObject(i)?.optJSONObject("node")
            ?.optJSONObject("name")?.optJSONObject("nameText")
            ?.optString("text", "")?.takeIf { it.isNotBlank() }
        if (name != null) out.add(name)
    }
    return out
}

fun titleCard(imdbId: String): ScreenTitle? {
    val payload = JSONObject()
    payload.put("query", TITLE_QUERY)
    payload.put("variables", JSONObject().put("id", imdbId))
    val data = Net.postJson(
        IMDB_GRAPHQL, payload, ttl = SCREEN_TTL_MS,
        headers = mapOf("User-Agent" to BROWSER_UA),
    ) ?: return null
    val node = data.optJSONObject("data")?.optJSONObject("title") ?: return null
    val kindObj = node.optJSONObject("titleType")
    val kind = kindObj?.optString("id", "") ?: ""
    val image = node.optJSONObject("primaryImage")?.optString("url", "")?.takeIf { it.isNotBlank() }
    return ScreenTitle(
        imdbId = imdbId,
        title = node.optJSONObject("titleText")?.optString("text", "")?.takeIf { it.isNotBlank() },
        kind = SCREEN_KINDS[kind] ?: kindObj?.optString("text", "")?.takeIf { it.isNotBlank() } ?: "Title",
        kindId = kind,
        year = node.optJSONObject("releaseYear")?.optInt("year", 0)?.takeIf { it > 0 },
        poster = image?.let { POSTER_SIZE.replace(it) { m -> "._V1_SX300" + m.groupValues[1] } },
        stars = emptyList(),
        rank = 0,
    )
}

fun basedOn(title: String?, author: String?): List<ScreenTitle> {
    val surname = surnameOf(author)
    if (title.isNullOrBlank() || surname.isEmpty()) return emptyList()
    val query = BASED_ON_QUERY.format(title.lowercase().replace("\"", ""), surname)
    val url = WDQS + "?format=json&query=" + urlEncode(query)
    val data = Net.fetchJson(url, ttl = WIKIDATA_TTL_MS, timeoutMs = 25_000) ?: return emptyList()
    val rows = data.optJSONObject("results")?.optJSONArray("bindings") ?: return emptyList()
    val out = mutableListOf<ScreenTitle>()
    for (i in 0 until rows.length()) {
        val row = rows.optJSONObject(i) ?: continue
        val imdbId = row.optJSONObject("imdb")?.optString("value", "") ?: ""
        if (!imdbId.startsWith("tt")) continue
        val card = titleCard(imdbId) ?: ScreenTitle(
            imdbId = imdbId,
            title = row.optJSONObject("workLabel")?.optString("value", ""),
            kind = row.optJSONObject("typeLabel")?.optString("value", "")?.takeIf { it.isNotBlank() } ?: "Title",
            kindId = "",
            year = null,
            poster = null,
            stars = emptyList(),
            rank = 0,
        )
        val year = card.year ?: row.optJSONObject("year")?.optString("value", "")?.toIntOrNull()
        val filled = card.copy(year = year)
        filled.via = "based-on"
        filled.match = 1.0
        filled.confirmed = true
        out.add(filled)
    }
    return out
}

private fun byAuthor(row: ScreenTitle, surname: String): Boolean {
    if (surname.isEmpty()) return false
    if (surname in (row.title ?: "").lowercase()) return true
    return screenWriters(row.imdbId).any { surname in it.lowercase() }
}

fun adaptations(
    title: String?,
    author: String? = null,
    year: Int? = null,
    series: String? = null,
    limit: Int = MAX_SCREEN_RESULTS,
    verify: Boolean = true,
): List<ScreenTitle> {
    val found = LinkedHashMap<String, ScreenTitle>()
    for ((text, via) in listOf(title to "book", series to "series")) {
        if (text.isNullOrBlank()) continue
        for (row in screenRows(text, text, year)) {
            row.via = via
            val keep = found[row.imdbId]
            if (keep == null || row.match > keep.match) found[row.imdbId] = row
        }
    }
    val rows = found.values.sortedWith(compareByDescending<ScreenTitle> { it.match }.thenBy { it.rank })
    if (!verify) return rows.take(limit)
    val surname = surnameOf(author)
    val out = mutableListOf<ScreenTitle>()
    for (row in rows.take(CHECK_LIMIT)) {
        if (byAuthor(row, surname)) {
            row.confirmed = true
            out.add(row)
            if (out.size >= limit) break
        }
    }
    val known = out.map { it.imdbId }.toSet()
    for (row in basedOn(title, author)) {
        if (row.imdbId !in known) out.add(row)
    }
    return out.take(limit)
}

fun screenToJson(rows: List<ScreenTitle>): JSONArray {
    val arr = JSONArray()
    for (r in rows) {
        val o = JSONObject()
        o.put("imdb_id", r.imdbId)
        o.put("title", r.title ?: JSONObject.NULL)
        o.put("kind", r.kind)
        o.put("kind_id", r.kindId)
        o.put("year", r.year ?: JSONObject.NULL)
        o.put("poster", r.poster ?: JSONObject.NULL)
        o.put("stars", JSONArray(r.stars))
        o.put("rank", r.rank)
        o.put("match", r.match)
        o.put("via", r.via ?: JSONObject.NULL)
        o.put("confirmed", r.confirmed)
        arr.put(o)
    }
    return arr
}

fun screenFromJson(arr: JSONArray?): List<ScreenTitle> {
    if (arr == null) return emptyList()
    val out = mutableListOf<ScreenTitle>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val stars = o.optJSONArray("stars")
        val row = ScreenTitle(
            imdbId = o.optString("imdb_id", ""),
            title = o.optString("title", "").takeIf { it.isNotBlank() },
            kind = o.optString("kind", "Title"),
            kindId = o.optString("kind_id", ""),
            year = if (o.isNull("year")) null else o.optInt("year").takeIf { it > 0 },
            poster = o.optString("poster", "").takeIf { it.isNotBlank() },
            stars = if (stars == null) emptyList() else (0 until stars.length()).map { stars.optString(it) },
            rank = o.optInt("rank", 0),
            match = o.optDouble("match", 0.0),
            via = o.optString("via", "").takeIf { it.isNotBlank() },
            confirmed = o.optBoolean("confirmed", false),
        )
        if (row.imdbId.isNotBlank()) out.add(row)
    }
    return out
}

private fun screenPathFor(key: String): File = File(LibraryCache.dir("screen"), "${Net.keyFor(key)}.json")

fun screenForBook(
    book: Book,
    series: String? = null,
    year: Int? = null,
    author: String? = null,
    refresh: Boolean = false,
): List<ScreenTitle> {
    val who = author ?: book.author
    val key = "${book.title}|$who|${series ?: ""}"
    val path = screenPathFor(key)
    if (path.exists() && !refresh) {
        try {
            return screenFromJson(JSONArray(path.readText()))
        } catch (_: Throwable) {
        }
    }
    val rows = adaptations(book.title, who, year = year ?: book.year, series = series)
    if (Net.offline() && rows.isEmpty()) return emptyList()
    try {
        path.writeText(screenToJson(rows).toString())
    } catch (_: Throwable) {
    }
    return rows
}
