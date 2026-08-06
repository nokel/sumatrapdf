package com.sumatrapdf.reader.library

import java.io.File
import java.util.IdentityHashMap

// Port of the catalogue half of audiobook/library/shelf.py — the folder
// tree becomes shelves, what is left is grouped by name, partitions are
// applied on top, and the whole thing is sorted the way the rail on the
// desktop shows it.

const val SORT_ALPHA = "alpha"
const val SORT_GENRE = "genre"
const val SORT_MOST = "most"
const val SORT_FEWEST = "fewest"

data class SeriesRow(
    val key: String,
    var name: String,
    var kind: String,
    var folder: String? = null,
    var parent: String? = null,
    var depth: Int = 0,
    var books: Int = 0,
    var direct: Int = 0,
    var booknlp: Int = 0,
    var pages: Int = 0,
    var guessed: String? = null,
    var author: String? = null,
    var wiki: String? = null,
    var facts: Int = 0,
    var genre: String = UNKNOWN_SHELF,
    var subgenre: String? = null,
    var head: String? = null,
    var subhead: String? = null,
    var chain: List<String> = emptyList(),
    val authors: LinkedHashMap<String, Int> = LinkedHashMap(),
    val members: MutableList<Book> = mutableListOf(),
)

private fun newRow(key: String, name: String, kind: String) = SeriesRow(key, name, kind)

private fun normCase(path: String): String = path.lowercase()

private fun parentOf(path: String): String = File(path).parent ?: path

private class BookIdentitySet {
    private val map = IdentityHashMap<Book, Boolean>()
    fun add(b: Book) { map[b] = true }
    operator fun contains(b: Book): Boolean = map.containsKey(b)
}

// shelf.py::_folder_shelves — every folder that directly holds books
// becomes a shelf, and so does any folder that holds two or more of
// those, unless its name is a generic word like "books" or a part of
// the user-path prefix that should never appear as a label.
fun folderShelves(books: List<Book>, roots: List<String>): LinkedHashMap<String, String> {
    val stops = roots.map { normCase(File(it).absolutePath) }.toSet()
    class Node(val folder: String) {
        val direct = mutableListOf<Book>()
        val subs = LinkedHashSet<String>()
    }
    val nodes = LinkedHashMap<String, Node>()
    for (b in books) {
        val home = b.seriesFolder ?: continue
        val key = normCase(home)
        val node = nodes.getOrPut(key) { Node(home) }
        node.direct.add(b)
        var here = parentOf(home)
        for (step in 0 until SERIES_TREE_DEPTH) {
            if (normCase(here) in stops) break
            val hereName = File(here).name
            if (isUserPathName(hereName) || genericFolder(hereName)) {
                val parent = parentOf(here)
                if (parent == here) break
                here = parent
                continue
            }
            val up = nodes.getOrPut(normCase(here)) { Node(here) }
            up.subs.add(key)
            val parent = parentOf(here)
            if (parent == here) break
            here = parent
        }
    }
    val keep = LinkedHashMap<String, String>()
    for ((key, node) in nodes) {
        val wanted = node.direct.isNotEmpty() || node.subs.size >= MIN_SERIES_GROUP
        if (!wanted) continue
        if (isUserPathName(File(node.folder).name)) continue
        if (genericFolder(File(node.folder).name)) continue
        keep[key] = node.folder
    }
    return keep
}

val USER_PATH_NAMES = setOf(
    "users", "user", "nokel", "home", "owner",
    "data", "storage", "emulated", "self", "primary", "0",
    "android", "data", "obb", "media", "sdcard", "mnt", "storage",
)

fun isUserPathName(name: String?): Boolean =
    (name ?: "").lowercase() in USER_PATH_NAMES

fun scanStops(roots: List<String>): Set<String> =
    roots.map { normCase(File(it).absolutePath) }.toSet()

private fun ownRows(rows: LinkedHashMap<String, SeriesRow>, rest: List<Book>) {
    for (b in rest) {
        val nameRaw = b.title.takeIf { it.isNotBlank() && it != "null" } ?: b.file
        val row = newRow(LOOSE_KEY + b.id, nameRaw.ifBlank { LOOSE_NAME }, "loose")
        row.members.add(b)
        rows[row.key] = row
    }
}

private fun applyPartitions(rows: LinkedHashMap<String, SeriesRow>): RoutingReport {
    val store = loadPartitions()
    val shelved = rows.values.toList()
    val taught = taughtPartitions(store, shelved)
    val (picks, report) = routePartitions(shelved, taught, keptOut(store))
    for (p in store.partitions) {
        val row = newRow(p.key, p.name, "partition")
        row.parent = p.parent
        rows[p.key] = row
    }
    for (row in shelved) {
        if (row.parent != null) continue
        var key = taught[row.key]
        row.guessed = null
        if (key == null) {
            val found = picks[row.key]
            if (found != null) {
                key = found.partition
                row.guessed = found.on.joinToString(", ").ifEmpty { "what it is called" }
            }
        }
        if (key != null && rows.containsKey(key)) row.parent = key
    }
    return report
}

data class Catalogue(val rows: List<SeriesRow>, val routing: RoutingReport?)

fun buildCatalogue(books: MutableList<Book>, roots: List<String>): Catalogue {
    assignGenres(books)
    val shelves = folderShelves(books, roots)
    val rows = LinkedHashMap<String, SeriesRow>()
    for ((key, folder) in shelves) {
        val row = newRow(folder, titlecase(File(folder).name), "folder")
        row.folder = folder
        rows[key] = row
    }

    for (row in rows.values) {
        var here = parentOf(row.folder!!)
        for (step in 0 until SERIES_TREE_DEPTH) {
            if (isUserPathName(File(here).name) || normCase(here) in scanStops(roots)) {
                val parent = parentOf(here)
                if (parent == here) break
                here = parent
                continue
            }
            val found = rows[normCase(here)]
            if (found != null) {
                row.parent = found.key
                break
            }
            val parent = parentOf(here)
            if (parent == here) break
            here = parent
        }
    }

    val loose = mutableListOf<Book>()
    val byApiSeries = LinkedHashMap<String, SeriesRow>()
    for (b in books) {
        val home = b.seriesFolder
        val row = if (home != null) rows[normCase(home)] else null
        if (row != null) {
            row.members.add(b)
            continue
        }
        // If the online lookup already gave us a series, put the book
        // there. Otherwise fall back to the loose pool for the title
        // matcher and seriesGroups to handle.
        val api = b.apiSeries
        if (!api.isNullOrBlank()) {
            val key = "series:" + api.lowercase()
            val apiRow = byApiSeries.getOrPut(key) { newRow(key, api, "series") }
            apiRow.members.add(b)
        } else {
            loose.add(b)
        }
    }

    val named = LinkedHashMap<String, SeriesRow>()
    for (r in rows.values) {
        val key = squash(r.name)
        if (key.length >= MIN_SERIES_PREFIX) named[key] = r
    }
    val still = mutableListOf<Book>()
    for (b in loose) {
        val title = squash(b.title)
        var hit: Pair<String, SeriesRow>? = null
        for ((name, row) in named) {
            if (title.startsWith(name) && (hit == null || name.length > hit!!.first.length)) {
                hit = name to row
            }
        }
        if (hit != null) hit!!.second.members.add(b) else still.add(b)
    }

    val (found, _) = seriesGroups(still, books)
    for ((name, members) in found) {
        val row = newRow("title:" + name.lowercase(), name, "title")
        row.members.addAll(members)
        rows[row.key] = row
    }

    val grouped = BookIdentitySet()
    for (row in rows.values) for (b in row.members) grouped.add(b)
    for (r in byApiSeries.values) rows[r.key] = r
    for (b in byApiSeries.values.flatMap { it.members }) grouped.add(b)
    ownRows(rows, books.filter { it !in grouped })

    val routing = applyPartitions(rows)

    for (b in books) {
        b.series = null
        b.seriesKey = null
        b.seriesKeys = emptyList()
    }

    val byKey = LinkedHashMap<String, SeriesRow>()
    for (r in rows.values) byKey[r.key] = r
    for (row in rows.values) {
        val chain = mutableListOf<String>()
        var cur: SeriesRow? = row
        for (step in 0 until SERIES_TREE_DEPTH) {
            val here = cur ?: break
            chain.add(here.key)
            cur = here.parent?.let { byKey[it] }
            if (cur == null) break
        }
        chain.reverse()
        row.depth = chain.size - 1
        row.chain = chain
        for (b in row.members) {
            b.series = row.name
            b.seriesKey = row.key
            b.seriesKeys = chain.toList()
        }
    }

    val inside = LinkedHashMap<String, MutableList<Book>>()
    for (row in rows.values) {
        row.direct = row.members.size
        for (b in row.members) {
            val author = b.author ?: continue
            row.authors[author] = (row.authors[author] ?: 0) + 1
        }
        for (key in row.chain) {
            val up = byKey[key] ?: continue
            up.books += row.members.size
            up.pages += row.members.sumOf { it.pages }
            up.booknlp += row.members.count { it.booknlp }
            inside.getOrPut(key) { mutableListOf() }.addAll(row.members)
        }
    }

    val out = mutableListOf<SeriesRow>()
    for (row in rows.values) {
        val authors = row.authors.entries.sortedByDescending { it.value }
        val top = authors.firstOrNull()
        row.author = if (top != null && top.value >= 0.4 * maxOf(1, row.direct)) top.key else null
        val wiki = row.folder?.let { wikiSeriesFor(it, roots) }
        row.wiki = wiki
        row.facts = seriesMeta(wiki)?.facts ?: 0
        val (genre, sub) = mostCommonGenre(inside[row.key] ?: emptyList())
        row.genre = genre
        row.subgenre = sub
        out.add(row)
    }

    val done = LinkedHashMap<String, SeriesRow>()
    for (r in out) done[r.key] = r
    for (row in out.sortedBy { it.depth }) {
        if (row.genre != UNKNOWN_SHELF || row.parent == null) continue
        val up = done[row.parent] ?: continue
        row.genre = up.genre
        row.subgenre = up.subgenre
    }

    for (b in books) {
        if (b.genre != null && b.genre != UNKNOWN_SHELF) continue
        for (key in b.seriesKeys.reversed()) {
            val row = done[key] ?: continue
            if (row.genre != UNKNOWN_SHELF) {
                b.genre = row.genre
                b.subgenre = row.subgenre
                break
            }
        }
    }

    val ordered = sortSeries(out, SORT_ALPHA)
    val order = LinkedHashMap<String, Int>()
    ordered.forEachIndexed { i, r -> order[r.key] = i }
    val sorted = books.sortedWith(
        compareBy<Book> { order[it.seriesKey] ?: order.size }
            .thenBy { it.volumes.firstOrNull() ?: 9999 }
            .thenBy { it.title.lowercase() },
    )
    books.clear()
    books.addAll(sorted)
    return Catalogue(out, routing)
}

// Python compares tuples element by element and lets the shorter one win
// a tie, which is what the rail's ordering depends on.
private fun compareKeys(a: List<Any?>, b: List<Any?>): Int {
    val n = minOf(a.size, b.size)
    for (i in 0 until n) {
        val c = compareOne(a[i], b[i])
        if (c != 0) return c
    }
    return a.size.compareTo(b.size)
}

private fun compareOne(x: Any?, y: Any?): Int = when {
    x is List<*> && y is List<*> -> compareKeys(x, y)
    x is Boolean && y is Boolean -> x.compareTo(y)
    x is Int && y is Int -> x.compareTo(y)
    x is String && y is String -> x.compareTo(y)
    else -> 0
}

fun sortSeries(rows: List<SeriesRow>, how: String = SORT_ALPHA): List<SeriesRow> {
    val byKey = LinkedHashMap<String, SeriesRow>()
    for (r in rows) byKey[r.key] = r

    fun rank(row: SeriesRow): List<Any?> = when (how) {
        SORT_MOST -> listOf(-row.books, row.name.lowercase())
        SORT_FEWEST -> listOf(row.books, row.name.lowercase())
        else -> listOf(row.name.lowercase())
    }

    fun path(row: SeriesRow): List<Any?> {
        val chain = (row.chain.ifEmpty { listOf(row.key) })
        val steps = chain.map { key ->
            val step = byKey[key]
            if (step != null) rank(step) else listOf<Any?>(key)
        }
        val top = byKey[chain.first()] ?: row
        val last = if (top.kind == "loose") 1 else 0
        val head = if (how == SORT_GENRE) {
            val name = top.genre
            val sub = top.subgenre ?: ""
            listOf<Any?>(shelfRank(name), name, sub.isNotEmpty(), sub, last)
        } else {
            listOf<Any?>(last)
        }
        return listOf(head) + steps
    }

    val keyed = rows.map { it to path(it) }
    val out = keyed.sortedWith { a, b -> compareKeys(a.second, b.second) }.map { it.first }

    var head: String? = null
    var sub: String? = null
    for (row in out) {
        row.head = null
        row.subhead = null
        if (how != SORT_GENRE || row.depth != 0) continue
        val name = row.genre
        val below = row.subgenre
        if (name != head) {
            row.head = name
            head = name
            sub = null
        }
        if (below != null && below != sub) row.subhead = below
        sub = below
    }
    return out
}
