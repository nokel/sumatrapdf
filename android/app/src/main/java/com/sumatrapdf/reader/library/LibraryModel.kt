package com.sumatrapdf.reader.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

// The state audiobook/library/server.py::LibraryState keeps, held in
// Compose state instead of behind an HTTP port. The two background
// sweeps (covers, then online metadata) run after a scan exactly as the
// desktop's _sweep does.

enum class LibraryTab { Overview, Characters, Family, Places, Knows, Screen }

class BookDetail(val book: Book) {
    var meta by mutableStateOf<BookMeta?>(null)
    var metaLoading by mutableStateOf(false)
    var chapters by mutableStateOf<ChapterList?>(null)
    var chaptersLoading by mutableStateOf(false)
    var screen by mutableStateOf<List<ScreenTitle>?>(null)
    var screenLoading by mutableStateOf(false)
    var wikiName by mutableStateOf<String?>(null)
    var summary by mutableStateOf<WikiSummary?>(null)
    var person by mutableStateOf<WikiPerson?>(null)
    var topic by mutableStateOf<String?>(null)
    var knowers by mutableStateOf<List<WikiKnower>>(emptyList())
    var tab by mutableStateOf(LibraryTab.Overview)
    var openChapters by mutableStateOf<Set<String>>(emptySet())
}

class LibraryModel(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    val store = LibraryStore(app)

    var index by mutableStateOf<LibraryIndex?>(null, neverEqualPolicy())
        private set
    var rows by mutableStateOf<List<SeriesRow>>(emptyList(), neverEqualPolicy())
        private set
    var routing by mutableStateOf<RoutingReport?>(null)
        private set
    var partitions by mutableStateOf<List<PartitionRow>>(emptyList())
        private set

    var loading by mutableStateOf(false)
    var scanning by mutableStateOf(false)
    var scanDone by mutableStateOf(0)
    var scanTotal by mutableStateOf(0)
    var sweeping by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)

    var sort by mutableStateOf(SORT_ALPHA)
    var filterKey by mutableStateOf<String?>(null)
    var filterName by mutableStateOf<String?>(null)
    var search by mutableStateOf("")
    var deskpanOpen by mutableStateOf(false)
    var deskShowIgnored by mutableStateOf(false)
    var deskChosen by mutableStateOf<Set<String>>(emptySet())
    var deskAnchor by mutableStateOf(-1)
    var deskWorking by mutableStateOf(false)
    var detail by mutableStateOf<BookDetail?>(null)

    private var sweepJob: Job? = null
    private var stopSweep = false

    val books: List<Book> get() = index?.books ?: emptyList()

    val bookCount: Int get() = books.count { it.kind == KIND_BOOK }
    val documentCount: Int get() = books.count { it.kind == KIND_DOCUMENT }
    val ignoredCount: Int get() = books.count { it.kind == KIND_IGNORED }
    val activeKind: String
        get() = when {
            !deskpanOpen -> KIND_BOOK
            deskShowIgnored -> KIND_IGNORED
            else -> KIND_DOCUMENT
        }

    fun sortedRows(): List<SeriesRow> = sortSeries(rows, sort)

    fun visibleBooks(): List<Book> {
        val kind = activeKind
        val pool = books.filter { it.kind == kind }
        return filterBooks(pool, filterKey, search.takeIf { it.isNotBlank() })
    }

    fun rowCount(row: SeriesRow): Int {
        val seen = HashSet<String>()
        var count = 0
        for (r in rows) {
            if (row.key !in r.chain) continue
            for (b in r.members) {
                if (b.kind == activeKind && seen.add(b.id)) count++
            }
        }
        return count
    }

    fun toggleDeskpan() {
        deskpanOpen = !deskpanOpen
        filterKey = null
        filterName = null
        deskChosen = emptySet()
        deskAnchor = -1
    }

    fun deskFiles(): List<Book> {
        val kind = if (deskShowIgnored) KIND_IGNORED else KIND_DOCUMENT
        return books.filter { it.kind == kind }
            .sortedWith(compareBy({ it.folder.lowercase() }, { it.file.lowercase() }))
    }

    fun deskShow(ignored: Boolean) {
        if (deskShowIgnored == ignored) return
        deskShowIgnored = ignored
        deskChosen = emptySet()
        deskAnchor = -1
    }

    fun deskTick(files: List<Book>, at: Int) {
        val f = files.getOrNull(at) ?: return
        deskChosen = if (f.path in deskChosen) deskChosen - f.path else deskChosen + f.path
        deskAnchor = at
    }

    fun deskPick(files: List<Book>, at: Int, run: Boolean) {
        val f = files.getOrNull(at) ?: return
        if (run && deskAnchor in files.indices) {
            val from = minOf(deskAnchor, at)
            val to = maxOf(deskAnchor, at)
            deskChosen = deskChosen + files.subList(from, to + 1).map { it.path }
            return
        }
        val onlyOne = f.path in deskChosen && deskChosen.size == 1
        deskChosen = if (onlyOne) emptySet() else setOf(f.path)
        deskAnchor = at
    }

    fun deskPickAll(files: List<Book>) {
        deskChosen = if (deskChosen.isEmpty()) files.map { it.path }.toSet() else emptySet()
        deskAnchor = -1
    }

    fun moveOneFile(path: String, kind: String) = moveFiles(setOf(path), kind)

    fun moveDeskChosen(kind: String) = moveFiles(deskChosen, kind)

    private fun moveFiles(wanted: Set<String>, kind: String) {
        if (wanted.isEmpty() || deskWorking) return
        deskWorking = true
        scope.launch {
            withContext(Dispatchers.IO) { Desk.tell(wanted, kind) }
            val have = index
            if (have != null) {
                for (b in have.books) {
                    if (b.path in wanted) b.kind = kind
                }
                withContext(Dispatchers.IO) { store.saveIndex(have) }
            }
            deskChosen = emptySet()
            deskAnchor = -1
            deskWorking = false
            reshelve()
        }
    }

    fun load() {
        if (loading) return
        loading = true
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { store.loadIndex() }
            if (loaded != null) adopt(loaded)
            loading = false
            if (loaded == null || !store.scanScopeIsCurrent) rescan(null) else startSweep()
        }
    }

    private suspend fun adopt(loaded: LibraryIndex) {
        val kept = loaded.books.toMutableList()
        withContext(Dispatchers.IO) { applyDeskKinds(kept) }
        val catalogue = withContext(Dispatchers.Default) { buildCatalogue(kept, loaded.roots) }
        index = LibraryIndex(loaded.roots, loaded.scanned, kept)
        rows = catalogue.rows
        routing = catalogue.routing
        partitions = loadPartitions().partitions
    }

    // server.py::LibraryState.reshelve — rebuild the rows from the books
    // already in memory, which is what a partition edit needs.
    fun reshelve() {
        scope.launch { reshelveNow() }
    }

    private suspend fun reshelveNow() {
        val have = index ?: return
        val kept = have.books.toMutableList()
        val catalogue = withContext(Dispatchers.Default) { buildCatalogue(kept, have.roots) }
        index = LibraryIndex(have.roots, have.scanned, kept)
        rows = catalogue.rows
        routing = catalogue.routing
        partitions = loadPartitions().partitions
    }

    fun rescan(roots: List<String>?) {
        if (scanning) return
        scanning = true
        scanDone = 0
        scanTotal = 0
        error = null
        stopSweep = true
        scope.launch {
            try {
                val fresh = withContext(Dispatchers.IO) {
                    store.refresh(
                        roots,
                        progress = { done, total, _ ->
                            scanDone = done
                            scanTotal = total
                        },
                        stage = { staged -> scope.launch { adopt(staged) } },
                    )
                }
                adopt(fresh)
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
            } finally {
                scanning = false
            }
            startSweep()
        }
    }

    fun startSweep() {
        if (sweepJob?.isActive == true) return
        val have = index ?: return
        stopSweep = false
        sweepJob = scope.launch(Dispatchers.IO) {
            sweeping = "covers"
            val stale = !coversArePicked()
            val made = if (stale) {
                coverRefreshSweep(have.books, onCover = { CoverCache.forget(it.id) }, stop = { stopSweep })
            } else {
                coverSweep(have.books, stop = { stopSweep })
            }
            if (stale && !stopSweep) markCoversPicked()
            sweeping = if (stopSweep) null else "meta"
            if (!stopSweep) {
                val looked = metaSweep(have.books, stop = { stopSweep })
                if (looked > 0) withContext(Dispatchers.Main) { reshelve() }
            }
            sweeping = if (stopSweep) null else "series"
            if (!stopSweep) {
                val found = seriesSweep(have.books, stop = { stopSweep })
                if (found > 0) {
                    withContext(Dispatchers.Main) { reshelveNow() }
                    store.saveIndex(have)
                }
            }
            if (made > 0) CoverCache.forget()
            sweeping = null
        }
    }

    // seriesSweep — hit the online sources for any book that has no
    // cached series yet, then reshelve so the rail can group by the
    // real series name. Stops early if offline.
    private suspend fun seriesSweep(books: List<Book>, stop: () -> Boolean = { false }): Int {
        if (Net.offline()) return 0
        var found = 0
        for (b in books) {
            if (stop()) break
            if (b.apiSeries != null) continue
            val hit = seriesFor(b) ?: continue
            b.apiSeries = hit.name
            b.apiSeriesIndex = hit.index
            b.apiSeriesSource = hit.source
            b.apiAuthor = hit.author
            b.apiSubjects = hit.subjects
            found++
        }
        return found
    }

    // A hand-picked cover retrains the cover model, so every cover the
    // model chose by itself is derived again with the new weights. The
    // old art stays on screen until its replacement is on disk.
    fun relearnCovers() {
        val have = index ?: return
        stopSweeping()
        stopSweep = false
        sweepJob = scope.launch(Dispatchers.IO) {
            sweeping = "covers"
            coverRefreshSweep(have.books, onCover = { CoverCache.forget(it.id) }, stop = { stopSweep })
            sweeping = null
            withContext(Dispatchers.Main) { CoverCache.forget() }
        }
    }

    fun coverChanged(book: Book) {
        CoverCache.forget(book.id)
    }

    fun stopSweeping() {
        stopSweep = true
        sweepJob?.cancel()
        sweepJob = null
        sweeping = null
    }

    fun selectRow(row: SeriesRow?) {
        filterKey = row?.key
        filterName = row?.name
    }

    fun openBook(book: Book) {
        val d = BookDetail(book)
        detail = d
        d.wikiName = wikiSeriesFor(book.seriesFolder ?: book.folder, index?.roots ?: emptyList())
        d.metaLoading = true
        scope.launch {
            val info = withContext(Dispatchers.IO) { metaForBook(book) }
            d.meta = info
            d.metaLoading = false
        }
        scope.launch(Dispatchers.IO) {
            val store = d.wikiName?.let { seriesStore(it) } ?: bookStore(book.hash)
            val summary = wikiSummary(store)
            withContext(Dispatchers.Main) { d.summary = summary }
        }
    }

    fun closeDetail() {
        detail = null
    }

    fun ensureChapters(d: BookDetail) {
        if (d.chapters != null || d.chaptersLoading) return
        d.chaptersLoading = true
        scope.launch {
            val list = withContext(Dispatchers.IO) { chaptersOf(d.book) }
            d.chapters = list
            d.chaptersLoading = false
        }
    }

    fun ensureScreen(d: BookDetail) {
        if (d.screen != null || d.screenLoading) return
        d.screenLoading = true
        scope.launch {
            val rows = withContext(Dispatchers.IO) {
                screenForBook(
                    d.book,
                    series = d.book.series,
                    year = d.meta?.year ?: d.book.year,
                    author = d.meta?.author ?: d.book.author,
                )
            }
            d.screen = rows
            d.screenLoading = false
        }
    }

    private fun factStore(d: BookDetail): FactStore? =
        d.wikiName?.let { seriesStore(it) } ?: bookStore(d.book.hash)

    fun openPerson(d: BookDetail, name: String) {
        scope.launch {
            val page = withContext(Dispatchers.IO) { factStore(d)?.let { wikiCharacter(it, name) } }
            d.person = page ?: WikiPerson(name, emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyList())
            d.tab = LibraryTab.Characters
        }
    }

    fun openTopic(d: BookDetail, topic: String) {
        scope.launch {
            val who = withContext(Dispatchers.IO) { factStore(d)?.let { wikiWhoKnows(it, topic) } ?: emptyList() }
            d.topic = topic
            d.knowers = who
            d.tab = LibraryTab.Knows
        }
    }

    fun clearPerson(d: BookDetail) {
        d.person = null
    }

    // ---- partitions ----

    fun booksOfRow(key: String): List<String> = books.filter { key in it.seriesKeys }.map { it.id }

    fun newPartition(name: String, row: SeriesRow?) {
        scope.launch(Dispatchers.IO) {
            val (made, _) = createPartition(name, null)
            if (made != null && row != null) {
                assignPartition(made.key, row.key, booksOfRow(row.key))
            }
            withContext(Dispatchers.Main) { reshelve() }
        }
    }

    fun moveToPartition(row: SeriesRow, key: String) {
        scope.launch(Dispatchers.IO) {
            assignPartition(key, row.key, booksOfRow(row.key))
            withContext(Dispatchers.Main) { reshelve() }
        }
    }

    fun takeOutOfPartition(row: SeriesRow) {
        scope.launch(Dispatchers.IO) {
            clearPartition(row.key, booksOfRow(row.key), row.parent)
            withContext(Dispatchers.Main) { reshelve() }
        }
    }

    fun renamePartitionRow(row: SeriesRow, name: String) {
        scope.launch(Dispatchers.IO) {
            renamePartition(row.key, name)
            withContext(Dispatchers.Main) { reshelve() }
        }
    }

    fun deletePartitionRow(row: SeriesRow) {
        scope.launch(Dispatchers.IO) {
            removePartition(row.key)
            withContext(Dispatchers.Main) { reshelve() }
        }
    }

    fun addRootAndRescan(path: String) {
        store.addRoot(path)
        rescan(store.extraRoots + (index?.roots ?: emptyList()))
    }
}

// Covers are built on demand, three at a time, the same ceiling
// LibraryPage.cpp's kCoverWorkers uses.
object CoverCache {
    private const val MAX_COVERS = 96
    private val cache = object : LruCache<String, Bitmap>(MAX_COVERS) {}
    private val gate = Semaphore(3)
    private val missing = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    var revision by mutableStateOf(0)
        private set

    fun cached(key: String): Bitmap? = cache.get(key)

    fun forget() {
        cache.evictAll()
        missing.clear()
        revision++
    }

    fun forget(key: String) {
        cache.remove(key)
        missing.remove(key)
        revision++
    }

    suspend fun cover(book: Book): Bitmap? {
        cache.get(book.id)?.let { return it }
        if (book.id in missing) return null
        return gate.withPermit {
            cache.get(book.id)?.let { return@withPermit it }
            val bitmap = withContext(Dispatchers.IO) {
                buildCover(book)?.let { decode(it) }
            }
            if (bitmap == null) missing.add(book.id) else cache.put(book.id, bitmap)
            bitmap
        }
    }

    suspend fun poster(url: String): Bitmap? {
        val key = "poster:" + Net.keyFor(url)
        cache.get(key)?.let { return it }
        if (key in missing) return null
        return gate.withPermit {
            cache.get(key)?.let { return@withPermit it }
            val bitmap = withContext(Dispatchers.IO) {
                buildPoster(url)?.let { decode(it) }
            }
            if (bitmap == null) missing.add(key) else cache.put(key, bitmap)
            bitmap
        }
    }

    private fun decode(file: File): Bitmap? = try {
        BitmapFactory.decodeFile(file.path)
    } catch (_: Throwable) {
        null
    }
}
