package com.sumatrapdf.library

import org.json.JSONObject
import java.io.File

class Partition(val key: String, var name: String, var parent: String?, val made: Long)

class PartitionStore {
    val partitions = ArrayList<Partition>()
    val rows = HashMap<String, String>()
    val books = HashMap<String, String>()
    val out = HashMap<String, String>()

    fun find(key: String?) = partitions.firstOrNull { it.key == key }
}

object Partitions {
    const val KEY_PREFIX = "part:"
    private const val STORE_FILE = "partitions.json"
    private const val MAX_NAME = 60

    private val SLUG_BREAK = Regex("[^a-z0-9]+")
    private val WHITESPACE = Regex("""\s+""")

    private lateinit var storeFile: File
    private val lock = Any()

    fun attach(dir: File) {
        storeFile = File(dir, STORE_FILE)
    }

    private fun prune(data: PartitionStore): PartitionStore {
        val live = data.partitions.map { it.key }.toSet()
        for (p in data.partitions) {
            if (p.parent !in live) p.parent = null
        }
        data.rows.entries.retainAll { it.value in live }
        data.books.entries.retainAll { it.value in live }
        data.out.entries.retainAll { it.value in live }
        return data
    }

    fun load(): PartitionStore {
        val data = PartitionStore()
        if (!storeFile.exists()) return data
        val root = try {
            JSONObject(storeFile.readText())
        } catch (e: Exception) {
            return data
        }
        val list = root.optJSONArray("partitions")
        if (list != null) {
            for (i in 0 until list.length()) {
                val o = list.getJSONObject(i)
                val key = o.optString("key")
                val name = o.optString("name")
                if (key.isEmpty() || name.isEmpty()) continue
                val parent = if (o.isNull("parent")) null else o.optString("parent").ifEmpty { null }
                data.partitions.add(Partition(key, name, parent, o.optLong("made")))
            }
        }
        for ((field, into) in listOf("rows" to data.rows, "books" to data.books, "out" to data.out)) {
            val o = root.optJSONObject(field) ?: continue
            for (key in o.keys()) into[key] = o.getString(key)
        }
        return prune(data)
    }

    private fun mapJson(from: Map<String, String>): JSONObject {
        val o = JSONObject()
        for ((k, v) in from) o.put(k, v)
        return o
    }

    fun save(data: PartitionStore) {
        val root = JSONObject()
        val list = org.json.JSONArray()
        for (p in data.partitions) {
            val o = JSONObject()
            o.put("key", p.key)
            o.put("name", p.name)
            o.put("parent", p.parent ?: JSONObject.NULL)
            o.put("made", p.made)
            list.put(o)
        }
        root.put("partitions", list)
        root.put("rows", mapJson(data.rows))
        root.put("books", mapJson(data.books))
        root.put("out", mapJson(data.out))
        val tmp = File(storeFile.parentFile, "$STORE_FILE.tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(storeFile)
    }

    private fun slug(name: String?): String {
        val text = SLUG_BREAK.replace((name ?: "").lowercase(), "-").trim('-')
        return text.ifEmpty { "partition" }
    }

    private fun freeKey(data: PartitionStore, name: String): String {
        val taken = data.partitions.map { it.key }.toSet()
        val stem = KEY_PREFIX + slug(name)
        if (stem !in taken) return stem
        for (n in 2 until 999) {
            val key = "$stem-$n"
            if (key !in taken) return key
        }
        return "$stem-${System.currentTimeMillis() / 1000}"
    }

    private fun cleanName(name: String?) =
        WHITESPACE.replace(name ?: "", " ").trim().take(MAX_NAME)

    private fun ancestors(data: PartitionStore, key: String?): List<String> {
        val seen = ArrayList<String>()
        var cur = key
        for (i in 0 until 16) {
            val p = data.find(cur) ?: break
            val parent = p.parent ?: break
            seen.add(parent)
            cur = parent
        }
        return seen
    }

    fun create(name: String, parent: String? = null): Partition? = synchronized(lock) {
        val clean = cleanName(name)
        if (clean.isEmpty()) return null
        val data = load()
        val already = data.partitions.firstOrNull {
            it.name.equals(clean, true) && it.parent == parent
        }
        if (already != null) return already
        val home = if (parent != null && data.find(parent) == null) null else parent
        val made = Partition(freeKey(data, clean), clean, home, System.currentTimeMillis() / 1000)
        data.partitions.add(made)
        save(data)
        return made
    }

    fun rename(key: String, name: String): Boolean = synchronized(lock) {
        val clean = cleanName(name)
        if (clean.isEmpty()) return false
        val data = load()
        val p = data.find(key) ?: return false
        p.name = clean
        save(data)
        return true
    }

    fun reparent(key: String, parent: String?): Boolean = synchronized(lock) {
        val data = load()
        val p = data.find(key) ?: return false
        if (parent != null && (parent == key || key in ancestors(data, parent))) return false
        p.parent = parent
        save(data)
        return true
    }

    fun remove(key: String): Boolean = synchronized(lock) {
        val data = load()
        val p = data.find(key) ?: return false
        for (child in data.partitions) {
            if (child.parent == key) child.parent = p.parent
        }
        data.partitions.removeAll { it.key == key }
        data.rows.entries.removeAll { it.value == key }
        data.books.entries.removeAll { it.value == key }
        data.out.entries.removeAll { it.value == key }
        save(data)
        return true
    }

    fun assign(key: String, rowKey: String? = null, bookIds: List<String> = emptyList()): Boolean =
        synchronized(lock) {
            val data = load()
            if (data.find(key) == null) return false
            if (rowKey != null) {
                data.rows[rowKey] = key
                data.out.remove(rowKey)
            }
            for (book in bookIds) data.books[book] = key
            save(data)
            return true
        }

    fun clear(rowKey: String? = null, bookIds: List<String> = emptyList(), outOf: String? = null) =
        synchronized(lock) {
            val data = load()
            if (rowKey != null) {
                data.rows.remove(rowKey)
                if (outOf != null && data.find(outOf) != null) data.out[rowKey] = outOf
            }
            for (book in bookIds) data.books.remove(book)
            save(data)
        }

    fun chosenFor(data: PartitionStore, row: Row): String? {
        data.rows[row.key]?.let { return it }
        val votes = HashMap<String, Int>()
        for (b in row.members) {
            val pick = data.books[b.id] ?: continue
            votes[pick] = (votes[pick] ?: 0) + 1
        }
        if (votes.isEmpty()) return null
        val best = votes.entries.maxByOrNull { it.value }!!
        return if (best.value * 2 > row.members.size) best.key else null
    }

    fun taught(data: PartitionStore, rows: List<Row>): Map<String, String> {
        val out = HashMap<String, String>()
        for (row in rows) {
            val key = chosenFor(data, row)
            if (key != null) out[row.key] = key
        }
        return out
    }
}
