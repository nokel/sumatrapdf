package com.sumatrapdf.library

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object Library {
    private const val INDEX_FILE = "library.json"
    private const val PREFS = "library"
    private const val KEY_SORT = "sort"
    private const val KEY_PAGE = "page:"

    private val work = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val stopping = AtomicBoolean(false)

    private lateinit var indexFile: File
    private lateinit var prefs: SharedPreferences
    lateinit var coverDir: File
        private set

    var books = ArrayList<Book>()
        private set
    var rows: List<Row> = emptyList()
        private set
    var roots: List<String> = emptyList()
        private set
    var scanned: Long = 0
        private set

    var sortOrder = SortOrder.ALPHA
        private set
    var filter: String? = null
    var filterName: String? = null

    var scanning = false
        private set
    var scanDone = 0
        private set
    var scanTotal = 0
        private set
    var scanTitle = ""
        private set

    var onChanged: (() -> Unit)? = null

    fun attach(context: Context) {
        val base = context.filesDir
        indexFile = File(base, INDEX_FILE)
        coverDir = File(context.cacheDir, "covers").also { it.mkdirs() }
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sortOrder = SortOrder.of(prefs.getString(KEY_SORT, SortOrder.ALPHA.key))
        Partitions.attach(base)
    }

    fun setSortOrder(order: SortOrder) {
        sortOrder = order
        prefs.edit().putString(KEY_SORT, order.key).apply()
        rows = Catalogue.sortSeries(rows, order)
        announce()
    }

    fun lastPage(bookId: String) = prefs.getInt(KEY_PAGE + bookId, 0)

    fun rememberPage(bookId: String, page: Int) {
        prefs.edit().putInt(KEY_PAGE + bookId, page).apply()
    }

    fun hasBooks() = books.isNotEmpty()

    fun visibleBooks(): List<Book> {
        val want = filter ?: return books
        return books.filter { want in it.seriesKeys }
    }

    fun rowOf(key: String?) = rows.firstOrNull { it.key == key }

    fun bookOf(id: String?) = books.firstOrNull { it.id == id }

    private fun readIndex(): LibraryIndex? {
        if (!indexFile.exists()) return null
        return try {
            LibraryIndex.fromJson(JSONObject(indexFile.readText()))
        } catch (e: Exception) {
            null
        }
    }

    private fun writeIndex(index: LibraryIndex) {
        val tmp = File(indexFile.parentFile, "$INDEX_FILE.tmp")
        tmp.writeText(index.toJson().toString())
        tmp.renameTo(indexFile)
    }

    private fun adopt(index: LibraryIndex) {
        val fresh = ArrayList(index.books)
        val built = Catalogue.sortSeries(Catalogue.build(fresh, index.roots), sortOrder)
        books = fresh
        rows = built
        roots = index.roots
        scanned = index.scanned
        if (filter != null && rowOf(filter) == null) {
            filter = null
            filterName = null
        }
    }

    private fun announce() {
        main.post { onChanged?.invoke() }
    }

    fun start(context: Context) {
        val app = context.applicationContext
        work.execute {
            val stored = readIndex()
            if (stored != null && stored.books.isNotEmpty()) {
                adopt(stored)
                announce()
            } else {
                rescan(app)
            }
        }
    }

    fun rescan(context: Context) {
        val app = context.applicationContext
        if (scanning) return
        scanning = true
        scanDone = 0
        scanTotal = 0
        scanTitle = ""
        stopping.set(false)
        announce()
        work.execute {
            try {
                val previous = readIndex()
                val kept = previous?.roots?.filter { File(it).isDirectory } ?: emptyList()
                val where = Scanner.discoverRoots(app, kept)
                val index = Scanner.scan(where, previous, { done, total, title ->
                    scanDone = done
                    scanTotal = total
                    scanTitle = title
                    if (done % 8 == 0 || done == total) announce()
                }, { stopping.get() })
                writeIndex(index)
                adopt(index)
            } catch (e: Throwable) {
                scanTitle = e.message ?: e.javaClass.simpleName
            } finally {
                scanning = false
                announce()
            }
        }
    }

    fun stopScan() {
        stopping.set(true)
    }

    fun recatalogue() {
        work.execute {
            val fresh = ArrayList(books)
            val built = Catalogue.sortSeries(Catalogue.build(fresh, roots), sortOrder)
            books = fresh
            rows = built
            if (filter != null && rowOf(filter) == null) {
                filter = null
                filterName = null
            }
            announce()
        }
    }
}
