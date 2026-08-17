package com.sumatrapdf.library

import java.io.File

object Catalogue {

    private class Node(val folder: String) {
        val direct = ArrayList<Book>()
        val subs = HashSet<String>()
    }

    private fun folderShelves(books: List<Book>, roots: List<String>): Map<String, String> {
        val stops = roots.map { pathKey(File(it).absolutePath) }.toSet()
        val nodes = LinkedHashMap<String, Node>()
        for (b in books) {
            val home = b.seriesFolder ?: continue
            val key = pathKey(home)
            nodes.getOrPut(key) { Node(home) }.direct.add(b)
            var here = File(home).parent ?: continue
            for (i in 0 until SERIES_TREE_DEPTH) {
                if (pathKey(here) in stops) break
                nodes.getOrPut(pathKey(here)) { Node(here) }.subs.add(key)
                val parent = File(here).parent ?: break
                if (parent == here) break
                here = parent
            }
        }
        val keep = LinkedHashMap<String, String>()
        for ((key, node) in nodes) {
            val wanted = node.direct.isNotEmpty() || node.subs.size >= MIN_SERIES_GROUP
            if (!wanted) continue
            if (genericFolder(File(node.folder).name) && node.subs.size >= MIN_SERIES_GROUP) continue
            keep[key] = node.folder
        }
        return keep
    }

    private fun ownRows(rows: LinkedHashMap<String, Row>, rest: List<Book>) {
        for (b in rest) {
            val row = Row(LOOSE_KEY + b.id, b.title.ifEmpty { b.file }, ROW_LOOSE)
            row.members.add(b)
            rows[row.key] = row
        }
    }

    private fun applyPartitions(rows: LinkedHashMap<String, Row>) {
        val store = Partitions.load()
        val shelved = rows.values.toList()
        val taught = Partitions.taught(store, shelved)
        for (p in store.partitions) {
            val row = Row(p.key, p.name, ROW_PARTITION)
            row.parent = p.parent
            rows[p.key] = row
        }
        for (row in shelved) {
            if (row.parent != null) continue
            row.guessed = null
            val key = taught[row.key] ?: continue
            if (rows.containsKey(key)) row.parent = key
        }
    }

    fun build(books: MutableList<Book>, roots: List<String>): List<Row> {
        Genre.assign(books)
        val rows = LinkedHashMap<String, Row>()
        for ((key, folder) in folderShelves(books, roots)) {
            val row = Row(folder, titleCase(File(folder).name), ROW_FOLDER)
            row.folder = folder
            rows[key] = row
        }

        for (row in rows.values) {
            var here = File(row.folder!!).parent ?: continue
            for (i in 0 until SERIES_TREE_DEPTH) {
                val found = rows[pathKey(here)]
                if (found != null) {
                    row.parent = found.key
                    break
                }
                val parent = File(here).parent ?: break
                if (parent == here) break
                here = parent
            }
        }

        val loose = ArrayList<Book>()
        for (b in books) {
            val home = b.seriesFolder
            val row = if (home != null) rows[pathKey(home)] else null
            if (row == null) loose.add(b) else row.members.add(b)
        }

        val named = LinkedHashMap<String, Row>()
        for (row in rows.values) {
            val name = squash(row.name)
            if (name.length >= MIN_SERIES_PREFIX) named[name] = row
        }
        val still = ArrayList<Book>()
        for (b in loose) {
            val title = squash(b.title)
            var hitName: String? = null
            var hitRow: Row? = null
            for ((name, row) in named) {
                if (title.startsWith(name) && (hitName == null || name.length > hitName.length)) {
                    hitName = name
                    hitRow = row
                }
            }
            if (hitRow != null) hitRow.members.add(b) else still.add(b)
        }

        val (found, _) = Phrases.seriesGroups(still, books)
        for ((name, members) in found) {
            val row = Row("title:${name.lowercase()}", name, ROW_TITLE)
            row.members.addAll(members)
            rows[row.key] = row
        }

        val grouped = HashSet<Book>()
        for (row in rows.values) grouped.addAll(row.members)
        ownRows(rows, books.filter { it !in grouped })
        applyPartitions(rows)

        for (b in books) {
            b.series = null
            b.seriesKey = null
            b.seriesKeys = emptyList()
        }

        val byKey = HashMap<String, Row>()
        for (row in rows.values) byKey[row.key] = row
        for (row in rows.values) {
            val chain = ArrayList<String>()
            var cur: Row? = row
            for (i in 0 until SERIES_TREE_DEPTH) {
                chain.add(cur!!.key)
                val parent = cur.parent
                cur = if (parent != null) byKey[parent] else null
                if (cur == null) break
            }
            chain.reverse()
            row.depth = chain.size - 1
            row.chain = chain
            for (b in row.members) {
                b.series = row.name
                b.seriesKey = row.key
                b.seriesKeys = chain
            }
        }

        val inside = HashMap<String, ArrayList<Book>>()
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
                inside.getOrPut(key) { ArrayList() }.addAll(row.members)
            }
        }

        val out = ArrayList<Row>()
        for (row in rows.values) {
            val top = row.authors.entries.sortedByDescending { it.value }.firstOrNull()
            row.author = if (top != null && top.value >= 0.4 * maxOf(1, row.direct)) top.key else null
            val (genre, sub) = Genre.mostCommon(inside[row.key] ?: emptyList())
            row.genre = genre
            row.subgenre = sub
            out.add(row)
        }

        val done = HashMap<String, Row>()
        for (row in out) done[row.key] = row
        for (row in out.sortedBy { it.depth }) {
            if (row.genre != Genre.UNKNOWN) continue
            val parent = row.parent ?: continue
            val up = done[parent] ?: continue
            row.genre = up.genre
            row.subgenre = up.subgenre
        }

        for (b in books) {
            val genre = b.genre
            if (genre != null && genre != Genre.UNKNOWN) continue
            for (key in b.seriesKeys.reversed()) {
                val row = done[key] ?: continue
                if (row.genre != Genre.UNKNOWN) {
                    b.genre = row.genre
                    b.subgenre = row.subgenre
                    break
                }
            }
        }

        val order = HashMap<String, Int>()
        for ((i, row) in sortSeries(out, SortOrder.ALPHA).withIndex()) order[row.key] = i
        val sorted = books.sortedWith(
            compareBy<Book> { order[it.seriesKey] ?: order.size }
                .thenComparator { a, b -> compareVolumes(a.volumes, b.volumes) }
                .thenBy { it.title.lowercase() }
        )
        books.clear()
        books.addAll(sorted)
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

    private fun compareCell(a: Any, b: Any): Int = when {
        a is Int && b is Int -> a.compareTo(b)
        a is Boolean && b is Boolean -> a.compareTo(b)
        a is String && b is String -> a.compareTo(b)
        else -> 0
    }

    private fun compareTuple(a: List<Any>, b: List<Any>): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val step = compareCell(a[i], b[i])
            if (step != 0) return step
        }
        return a.size.compareTo(b.size)
    }

    private fun compareSortKey(a: List<List<Any>>, b: List<List<Any>>): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val step = compareTuple(a[i], b[i])
            if (step != 0) return step
        }
        return a.size.compareTo(b.size)
    }

    fun sortSeries(rows: List<Row>, how: SortOrder): List<Row> {
        val byKey = HashMap<String, Row>()
        for (r in rows) byKey[r.key] = r

        fun rank(row: Row): List<Any> = when (how) {
            SortOrder.MOST -> listOf(-row.books, row.name.lowercase())
            SortOrder.FEWEST -> listOf(row.books, row.name.lowercase())
            else -> listOf(row.name.lowercase())
        }

        fun sortKey(row: Row): List<List<Any>> {
            val chainKeys = row.chain.ifEmpty { listOf(row.key) }
            val chain = chainKeys.map { key ->
                byKey[key]?.let { rank(it) } ?: listOf<Any>(key)
            }
            val top = byKey[chainKeys.first()] ?: row
            val last = if (top.kind == ROW_LOOSE) 1 else 0
            if (how == SortOrder.GENRE) {
                val head = top.genre
                val sub = top.subgenre ?: ""
                return listOf(
                    listOf<Any>(Genre.shelfRank(head), head, sub.isNotEmpty(), sub, last)
                ) + chain
            }
            return listOf(listOf<Any>(last)) + chain
        }

        val keys = HashMap<String, List<List<Any>>>()
        for (r in rows) keys[r.key] = sortKey(r)
        val out = rows.sortedWith { a, b -> compareSortKey(keys[a.key]!!, keys[b.key]!!) }

        var head: String? = null
        var sub: String? = null
        for (row in out) {
            row.head = null
            row.subhead = null
            if (how != SortOrder.GENRE || row.depth != 0) continue
            val name = row.genre
            val below = row.subgenre?.ifEmpty { null }
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
}
