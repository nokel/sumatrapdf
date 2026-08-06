package com.sumatrapdf.reader.library

import android.util.Log
import org.json.JSONObject
import java.io.File

const val KIND_BOOK = "book"
const val KIND_DOCUMENT = "document"
const val KIND_IGNORED = "ignored"

private const val TAG = "SumatraLibrary"
private const val DESK_FILE = "deskpan.json"

object Desk {
    val KINDS = setOf(KIND_BOOK, KIND_DOCUMENT, KIND_IGNORED)

    private val lock = Any()

    private val storeFile: File
        get() = File(LibraryCache.root.also { it.mkdirs() }, DESK_FILE)

    private fun key(path: String): String = path.trim().lowercase()

    fun load(): MutableMap<String, String> {
        val out = LinkedHashMap<String, String>()
        val f = storeFile
        if (!f.exists()) return out
        try {
            val kinds = JSONObject(f.readText()).optJSONObject("kinds") ?: return out
            for (name in kinds.keys()) {
                val kind = kinds.optString(name, "")
                if (name.isNotBlank() && kind in KINDS) out[key(name)] = kind
            }
        } catch (t: Throwable) {
            Log.w(TAG, "desk load failed: ${t.message}")
        }
        return out
    }

    private fun save(data: Map<String, String>) {
        try {
            val kinds = JSONObject()
            for ((path, kind) in data) kinds.put(path, kind)
            val root = JSONObject().put("kinds", kinds)
            val tmp = File(storeFile.path + ".tmp")
            tmp.writeText(root.toString())
            if (storeFile.exists()) storeFile.delete()
            tmp.renameTo(storeFile)
        } catch (t: Throwable) {
            Log.w(TAG, "desk save failed: ${t.message}")
        }
    }

    fun told(data: Map<String, String>, path: String): String? = data[key(path)]

    fun tell(paths: Collection<String>, kind: String): Int {
        if (kind !in KINDS) return 0
        synchronized(lock) {
            val data = load()
            for (path in paths) if (path.isNotBlank()) data[key(path)] = kind
            save(data)
            return data.size
        }
    }

    fun forget(paths: Collection<String>): Int {
        synchronized(lock) {
            val data = load()
            for (path in paths) data.remove(key(path))
            save(data)
            return data.size
        }
    }
}

fun applyDeskKinds(books: List<Book>) {
    val told = Desk.load()
    if (told.isEmpty()) return
    for (b in books) {
        val where = Desk.told(told, b.path) ?: continue
        b.kind = where
    }
}
