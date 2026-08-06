package com.sumatrapdf.reader.library

import android.content.Context
import android.util.Log
import java.io.File

// shelf.py's load_index / save_index / refresh plus the state
// audiobook/library/server.py keeps in LibraryState — the catalogue rows,
// the scan progress, and the two background sweeps that fill in covers
// and online metadata. There is no HTTP layer here: the desktop's UI
// talks to this over 127.0.0.1, the phone's calls it directly.

private const val TAG = "SumatraLibrary"

data class LibraryStatus(
    val roots: List<String> = emptyList(),
    val books: Int = 0,
    val series: Int = 0,
    val scanned: Long = 0,
    val scanning: Boolean = false,
    val scanDone: Int = 0,
    val scanTotal: Int = 0,
    val sweeping: Boolean = false,
    val error: String? = null,
)

class LibraryStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("sumatra", Context.MODE_PRIVATE)

    init {
        LibraryCache.root = File(app.filesDir, "library").also { it.mkdirs() }
    }

    val cacheRoot: File
        get() = LibraryCache.root.also { it.mkdirs() }

    val indexFile: File
        get() = File(cacheRoot, INDEX_FILE)

    var extraRoots: List<String>
        get() = prefs.getString(KEY_EXTRA_ROOTS, "")
            ?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
        set(value) {
            prefs.edit().putString(KEY_EXTRA_ROOTS, value.joinToString("\n")).apply()
        }

    var scanScope: Int
        get() = prefs.getInt(KEY_SCAN_SCOPE, 0)
        set(value) {
            prefs.edit().putInt(KEY_SCAN_SCOPE, value).apply()
        }

    val scanScopeIsCurrent: Boolean
        get() = scanScope >= SCAN_SCOPE

    fun addRoot(path: String) {
        if (path.isBlank()) return
        val cur = extraRoots.toMutableList()
        if (cur.any { it.equals(path, ignoreCase = true) }) return
        cur.add(path)
        extraRoots = cur
    }

    fun removeRoot(path: String) {
        extraRoots = extraRoots.filterNot { it.equals(path, ignoreCase = true) }
    }

    fun loadIndex(): LibraryIndex? {
        val f = indexFile
        if (!f.exists()) return null
        return try {
            indexFromJson(f.readText())
        } catch (t: Throwable) {
            Log.w(TAG, "loadIndex failed: ${t.message}")
            null
        }
    }

    fun saveIndex(index: LibraryIndex) {
        try {
            val tmp = File(indexFile.path + ".tmp")
            tmp.writeText(indexToJson(index))
            if (indexFile.exists()) indexFile.delete()
            tmp.renameTo(indexFile)
        } catch (t: Throwable) {
            Log.w(TAG, "saveIndex failed: ${t.message}")
        }
    }

    // Blocking. The caller runs it off the main thread and gets progress
    // per file; a re-scan reuses every entry whose size and mtime are
    // unchanged, so only new or edited files are opened.
    fun refresh(
        roots: List<String>? = null,
        progress: ScanProgress? = null,
        stop: () -> Boolean = { false },
        stage: ((LibraryIndex) -> Unit)? = null,
    ): LibraryIndex {
        val previous = loadIndex()
        if (roots != null) {
            val index = scan(roots, previous, progress, stop)
            saveIndex(index)
            return index
        }
        val starting = mainRoots(app, extraRoots)
        var index = scan(starting, previous, progress, stop)
        saveIndex(index)
        if (stop()) return index
        stage?.invoke(index)

        val everywhere = discoverRoots(app, extraRoots)
        if (everywhere.size > starting.size) {
            index = scan(everywhere, index, progress, stop)
            saveIndex(index)
        }
        if (!stop()) scanScope = SCAN_SCOPE
        return index
    }

    fun find(index: LibraryIndex, idOrPath: String): Book? =
        index.books.firstOrNull { it.id == idOrPath }
            ?: index.books.firstOrNull { it.path.equals(idOrPath, ignoreCase = true) }
            ?: index.books.firstOrNull { b ->
                b.editions.any { it.path.equals(idOrPath, ignoreCase = true) }
            }

    fun discoverRootsNow(): List<String> = discoverRoots(app, extraRoots)

    companion object {
        private const val KEY_EXTRA_ROOTS = "libraryExtraRoots"
        private const val KEY_SCAN_SCOPE = "libraryScanScope"
    }
}

// server.py::LibraryState.books — the same filtering the desktop's
// /library endpoint does.
fun filterBooks(books: List<Book>, seriesKey: String?, term: String?): List<Book> {
    var rows = books
    if (!seriesKey.isNullOrBlank()) rows = rows.filter { seriesKey in it.seriesKeys }
    if (!term.isNullOrBlank()) {
        val t = term.lowercase()
        rows = rows.filter {
            t in it.title.lowercase() ||
                t in (it.author ?: "").lowercase() ||
                t in (it.series ?: "").lowercase()
        }
    }
    return rows
}

// server.py::LibraryState.partitions — how many books sit under each
// partition, counted through the rows that name it in their chain.
fun partitionCounts(rows: List<SeriesRow>): Map<String, Int> {
    val counts = LinkedHashMap<String, Int>()
    for (row in rows) {
        for (key in row.chain) {
            if (key != row.key) counts[key] = (counts[key] ?: 0) + row.direct
        }
    }
    return counts
}
