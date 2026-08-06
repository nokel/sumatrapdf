package com.sumatrapdf.reader.library

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// Port of audiobook/library/partition.py. A partition is a shelf the
// reader made by hand; rows and books can be pinned into one, and a row
// can be explicitly kept out of one it was routed into.

const val PARTITION_STORE_FILE = "partitions.json"
const val PARTITION_KEY_PREFIX = "part:"
const val PARTITION_MAX_NAME = 60

data class PartitionRow(
    val key: String,
    var name: String,
    var parent: String?,
    val made: Double,
)

class PartitionStore {
    val partitions = mutableListOf<PartitionRow>()
    val rows = LinkedHashMap<String, String>()
    val books = LinkedHashMap<String, String>()
    val out = LinkedHashMap<String, String>()
}

private val partitionLock = Any()

fun partitionStorePath(): File = File(LibraryCache.root.also { it.mkdirs() }, PARTITION_STORE_FILE)

private fun prunePartitions(data: PartitionStore): PartitionStore {
    val live = data.partitions.map { it.key }.toSet()
    for (p in data.partitions) if (p.parent !in live) p.parent = null
    data.rows.entries.retainAll { it.value in live }
    data.books.entries.retainAll { it.value in live }
    data.out.entries.retainAll { it.value in live }
    return data
}

fun loadPartitions(): PartitionStore {
    val path = partitionStorePath()
    val out = PartitionStore()
    if (!path.exists()) return out
    val data = try {
        JSONObject(path.readText())
    } catch (_: Throwable) {
        return out
    }
    val arr = data.optJSONArray("partitions")
    if (arr != null) {
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            val key = row.optString("key", "")
            val name = row.optString("name", "")
            if (key.isBlank() || name.isBlank()) continue
            out.partitions.add(
                PartitionRow(
                    key = key,
                    name = name,
                    parent = row.optString("parent", "").takeIf { it.isNotBlank() },
                    made = row.optDouble("made", 0.0),
                ),
            )
        }
    }
    for ((field, into) in listOf("rows" to out.rows, "books" to out.books, "out" to out.out)) {
        val obj = data.optJSONObject(field) ?: continue
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            into[k] = obj.optString(k, "")
        }
    }
    return prunePartitions(out)
}

fun savePartitions(data: PartitionStore): PartitionStore {
    val root = JSONObject()
    val arr = JSONArray()
    for (p in data.partitions) {
        val o = JSONObject()
        o.put("key", p.key)
        o.put("name", p.name)
        o.put("parent", p.parent ?: JSONObject.NULL)
        o.put("made", p.made)
        arr.put(o)
    }
    root.put("partitions", arr)
    root.put("rows", JSONObject(data.rows as Map<*, *>))
    root.put("books", JSONObject(data.books as Map<*, *>))
    root.put("out", JSONObject(data.out as Map<*, *>))
    val path = partitionStorePath()
    val tmp = File(path.path + ".tmp")
    tmp.writeText(root.toString())
    if (path.exists()) path.delete()
    tmp.renameTo(path)
    return data
}

private fun slugOf(name: String?): String {
    val text = Regex("""[^a-z0-9]+""").replace((name ?: "").lowercase(), "-").trim('-')
    return text.ifEmpty { "partition" }
}

private fun freeKey(data: PartitionStore, name: String): String {
    val taken = data.partitions.map { it.key }.toSet()
    val stem = PARTITION_KEY_PREFIX + slugOf(name)
    if (stem !in taken) return stem
    for (n in 2 until 999) {
        val key = "$stem-$n"
        if (key !in taken) return key
    }
    return "$stem-${System.currentTimeMillis() / 1000}"
}

private fun cleanPartitionName(name: String?): String =
    Regex("""\s+""").replace(name ?: "", " ").trim().take(PARTITION_MAX_NAME)

fun findPartition(data: PartitionStore, key: String?): PartitionRow? =
    data.partitions.firstOrNull { it.key == key }

private fun ancestorsOf(data: PartitionStore, key: String?): List<String> {
    val seen = mutableListOf<String>()
    var cur = key
    repeat(16) {
        val p = findPartition(data, cur)
        val parent = p?.parent ?: return seen
        seen.add(parent)
        cur = parent
    }
    return seen
}

fun createPartition(name: String?, parent: String? = null): Pair<PartitionRow?, String> = synchronized(partitionLock) {
    val clean = cleanPartitionName(name)
    if (clean.isEmpty()) return null to "a partition needs a name"
    val data = loadPartitions()
    for (p in data.partitions) {
        if (p.name.equals(clean, ignoreCase = true) && p.parent == parent) return p to "already there"
    }
    val useParent = if (parent != null && findPartition(data, parent) == null) null else parent
    val made = PartitionRow(freeKey(data, clean), clean, useParent, System.currentTimeMillis() / 1000.0)
    data.partitions.add(made)
    savePartitions(data)
    return made to "made"
}

fun renamePartition(key: String, name: String?): Pair<Boolean, String> = synchronized(partitionLock) {
    val clean = cleanPartitionName(name)
    if (clean.isEmpty()) return false to "a partition needs a name"
    val data = loadPartitions()
    val p = findPartition(data, key) ?: return false to "no such partition"
    p.name = clean
    savePartitions(data)
    return true to "renamed"
}

fun reparentPartition(key: String, parent: String?): Pair<Boolean, String> = synchronized(partitionLock) {
    val data = loadPartitions()
    val p = findPartition(data, key) ?: return false to "no such partition"
    if (parent != null && (parent == key || key in ancestorsOf(data, parent))) {
        return false to "a partition cannot hold itself"
    }
    p.parent = parent
    savePartitions(data)
    return true to "moved"
}

fun removePartition(key: String): Pair<Boolean, String> = synchronized(partitionLock) {
    val data = loadPartitions()
    val p = findPartition(data, key) ?: return false to "no such partition"
    for (child in data.partitions) if (child.parent == key) child.parent = p.parent
    data.partitions.removeAll { it.key == key }
    data.rows.entries.retainAll { it.value != key }
    data.books.entries.retainAll { it.value != key }
    data.out.entries.retainAll { it.value != key }
    savePartitions(data)
    return true to "removed"
}

fun assignPartition(key: String, rowKey: String? = null, bookIds: List<String> = emptyList()): Pair<Boolean, String> =
    synchronized(partitionLock) {
        val data = loadPartitions()
        if (findPartition(data, key) == null) return false to "no such partition"
        if (!rowKey.isNullOrBlank()) {
            data.rows[rowKey] = key
            data.out.remove(rowKey)
        }
        for (book in bookIds) data.books[book] = key
        savePartitions(data)
        return true to "assigned"
    }

fun clearPartition(
    rowKey: String? = null,
    bookIds: List<String> = emptyList(),
    outOf: String? = null,
): Pair<Boolean, String> = synchronized(partitionLock) {
    val data = loadPartitions()
    if (!rowKey.isNullOrBlank()) {
        data.rows.remove(rowKey)
        if (!outOf.isNullOrBlank() && findPartition(data, outOf) != null) data.out[rowKey] = outOf
    }
    for (book in bookIds) data.books.remove(book)
    savePartitions(data)
    return true to "cleared"
}

fun keptOut(data: PartitionStore): Map<String, String> = LinkedHashMap(data.out)

fun chosenFor(data: PartitionStore, row: SeriesRow): String? {
    data.rows[row.key]?.let { return it }
    val votes = LinkedHashMap<String, Int>()
    for (b in row.members) {
        val pick = data.books[b.id] ?: continue
        votes[pick] = (votes[pick] ?: 0) + 1
    }
    if (votes.isEmpty()) return null
    val best = votes.entries.maxByOrNull { it.value }!!
    return if (best.value * 2 > row.members.size) best.key else null
}

fun taughtPartitions(data: PartitionStore, rows: List<SeriesRow>): LinkedHashMap<String, String> {
    val out = LinkedHashMap<String, String>()
    for (row in rows) {
        val key = chosenFor(data, row)
        if (key != null) out[row.key] = key
    }
    return out
}
