package com.sumatrapdf.library

import org.json.JSONArray
import org.json.JSONObject

class Edition(val path: String, val ext: String, val size: Long, val pages: Int) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("path", path)
        o.put("ext", ext)
        o.put("size", size)
        o.put("pages", pages)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject) = Edition(
            o.getString("path"), o.optString("ext"), o.optLong("size"), o.optInt("pages"))
    }
}

class Book(
    var id: String,
    var path: String,
    var file: String,
    var ext: String,
    var title: String,
    var author: String?,
    var volumes: List<Int>,
    var year: Int?,
    var pages: Int,
    var ink: Int,
    var art: Double?,
    var sample: String,
    var size: Long,
    var mtime: Long,
) {
    var folder: String = ""
    var seriesFolder: String? = null
    var readable: Boolean = false
    var authorGuessed: Boolean = false
    var editions: List<Edition> = emptyList()
    var series: String? = null
    var seriesKey: String? = null
    var seriesKeys: List<String> = emptyList()
    var genre: String? = null
    var subgenre: String? = null

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("path", path)
        o.put("file", file)
        o.put("ext", ext)
        o.put("title", title)
        o.put("author", author ?: JSONObject.NULL)
        o.put("volumes", JSONArray(volumes))
        o.put("year", year ?: JSONObject.NULL)
        o.put("pages", pages)
        o.put("ink", ink)
        o.put("art", art ?: JSONObject.NULL)
        o.put("sample", sample)
        o.put("size", size)
        o.put("mtime", mtime)
        o.put("author_guessed", authorGuessed)
        o.put("folder", folder)
        o.put("series_folder", seriesFolder ?: JSONObject.NULL)
        o.put("readable", readable)
        val made = JSONArray()
        for (e in editions) made.put(e.toJson())
        o.put("editions", made)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): Book {
            val volumes = ArrayList<Int>()
            val raw = o.optJSONArray("volumes")
            if (raw != null) for (i in 0 until raw.length()) volumes.add(raw.getInt(i))
            return Book(
                id = o.getString("id"),
                path = o.getString("path"),
                file = o.getString("file"),
                ext = o.getString("ext"),
                title = o.getString("title"),
                author = if (o.isNull("author")) null else o.getString("author"),
                volumes = volumes,
                year = if (o.isNull("year")) null else o.getInt("year"),
                pages = o.optInt("pages"),
                ink = o.optInt("ink"),
                art = if (o.isNull("art")) null else o.getDouble("art"),
                sample = o.optString("sample"),
                size = o.optLong("size"),
                mtime = o.optLong("mtime"),
            ).also {
                it.authorGuessed = o.optBoolean("author_guessed")
                it.folder = o.optString("folder")
                it.seriesFolder = if (o.isNull("series_folder")) null else o.getString("series_folder")
                it.readable = o.optBoolean("readable")
                val made = ArrayList<Edition>()
                val rawEditions = o.optJSONArray("editions")
                if (rawEditions != null) {
                    for (i in 0 until rawEditions.length()) {
                        made.add(Edition.fromJson(rawEditions.getJSONObject(i)))
                    }
                }
                it.editions = made
            }
        }
    }
}

const val ROW_FOLDER = "folder"
const val ROW_TITLE = "title"
const val ROW_LOOSE = "loose"
const val ROW_PARTITION = "partition"

class Row(val key: String, var name: String, val kind: String) {
    var folder: String? = null
    var parent: String? = null
    var depth: Int = 0
    var books: Int = 0
    var direct: Int = 0
    var pages: Int = 0
    var guessed: String? = null
    var author: String? = null
    var genre: String = Genre.UNKNOWN
    var subgenre: String? = null
    var head: String? = null
    var subhead: String? = null
    var chain: List<String> = emptyList()
    val authors = HashMap<String, Int>()
    val members = ArrayList<Book>()
}

class LibraryIndex(val roots: List<String>, val scanned: Long, val books: List<Book>) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("roots", JSONArray(roots))
        o.put("scanned", scanned)
        val arr = JSONArray()
        for (b in books) arr.put(b.toJson())
        o.put("books", arr)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): LibraryIndex {
            val roots = ArrayList<String>()
            val rawRoots = o.optJSONArray("roots")
            if (rawRoots != null) for (i in 0 until rawRoots.length()) roots.add(rawRoots.getString(i))
            val books = ArrayList<Book>()
            val rawBooks = o.optJSONArray("books")
            if (rawBooks != null) {
                for (i in 0 until rawBooks.length()) {
                    books.add(Book.fromJson(rawBooks.getJSONObject(i)))
                }
            }
            return LibraryIndex(roots, o.optLong("scanned"), books)
        }
    }
}

enum class SortOrder(val key: String, val label: String) {
    ALPHA("alpha", "A-Z"),
    GENRE("genre", "Genre"),
    MOST("most", "Most"),
    FEWEST("fewest", "Fewest");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: ALPHA
    }
}
