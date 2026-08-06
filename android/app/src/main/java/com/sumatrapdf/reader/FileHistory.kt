package com.sumatrapdf.reader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// Port of src/FileHistory.cpp — the list of every document the user has
// ever opened, most recently opened first, with the per-file attributes
// the start page needs. The Win32 app keeps this in `gFileHistory` and
// serialises it into the FileStates array of SumatraPDF-settings.txt;
// here it is a JSON array in the same SharedPreferences the rest of the
// app uses.
//
// The two orderings (frequency and recency) and their tie-breaking are
// exact ports of `cmpOpenCount` / `cmpRecentlyOpened`, including
// pinned-first and the natural-order sort of pinned entries by base name.

const val kFileHistoryMaxRecent = 10
const val kFileHistoryMaxFiles = 1000

// The Android subset of `struct FileState` (src/Settings.h). Fields the
// port cannot populate yet are not stored: `scrollPos` (pan state is
// reset per page until the shared PanZoomState is fixed), `tocState` and
// `sidebarDx` (the sidebar neither collapses nor resizes), `displayR2L`
// (manga mode is still a no-op flag), `bgCol`/`tabCol` (no theme system),
// `favorites` (bookmarks are still a separate flat list), and
// `windowPos`/`windowState`/`iconIdx`, which are Windows-only.
//
// `encryptedPassword` is where Win32 keeps `decryptionKey`. mupdf's Java
// binding authenticates with the password itself rather than handing back
// key material, so that is what has to be remembered; it is sealed with
// an Android Keystore key (see SecretStore) instead of being written in
// the clear, because the app's preferences are included in cloud backup.
data class FileHistoryEntry(
    val path: String,
    val uri: String? = null,
    val displayName: String = path.substringAfterLast('/'),
    val isPinned: Boolean = false,
    val isMissing: Boolean = false,
    val openCount: Int = 0,
    val index: Int = 0,
    val useDefaultState: Boolean = true,
    val displayMode: String = kDisplayModeAutomatic,
    val pageNo: Int = 0,
    val zoom: String = kZoomFitWidth,
    val rotation: Int = 0,
    val showToc: Boolean = false,
    // src/Settings.h FileState::reparseIdx — a position that survives
    // repagination. mupdf calls it a bookmark; 0 means none.
    val reparseIdx: Long = 0L,
    val encryptedPassword: String? = null,
)

data class SessionTab(val path: String, val uri: String?)

// src/Settings.h::SessionData. Win32 carries a full TabState per tab
// because the same document can be open in two tabs; this port switches
// to the existing tab instead of opening a second one, so the ordered
// paths plus the selected index are the whole of it — everything else
// comes from the FileHistoryEntry for that path.
data class SessionData(val tabs: List<SessionTab>, val tabIndex: Int)

class FileHistory(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("sumatra", Context.MODE_PRIVATE)
    private val states = mutableListOf<FileHistoryEntry>()

    init {
        load()
    }

    private fun load() {
        states.clear()
        val raw = prefs.getString(KEY_FILE_STATES, null)
        if (raw != null) {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val path = obj.optString("path", "")
                    if (path.isBlank()) continue
                    states.add(
                        FileHistoryEntry(
                            path = path,
                            uri = obj.optString("uri", "").takeIf { it.isNotBlank() },
                            displayName = obj.optString("name", "")
                                .takeIf { it.isNotBlank() } ?: path.substringAfterLast('/'),
                            isPinned = obj.optBoolean("pinned", false),
                            isMissing = obj.optBoolean("missing", false),
                            openCount = obj.optInt("openCount", 0),
                            useDefaultState = obj.optBoolean("useDefaultState", true),
                            displayMode = obj.optString("displayMode", kDisplayModeAutomatic),
                            pageNo = obj.optInt("pageNo", 0),
                            zoom = obj.optString("zoom", kZoomFitWidth),
                            rotation = obj.optInt("rotation", 0),
                            showToc = obj.optBoolean("showToc", false),
                            reparseIdx = obj.optLong("reparseIdx", 0L),
                            encryptedPassword = obj.optString("pw", "").takeIf { it.isNotBlank() },
                        ),
                    )
                }
            } catch (_: Throwable) {
                states.clear()
            }
            migrateSessionKeys()
            return
        }
        migrateRecentFiles()
        migrateSessionKeys()
    }

    // Page/zoom/rotation/layout used to live in their own
    // "session_<pathHash>" preference keys. The hash is not reversible,
    // but every remembered path can be hashed the same way to find its
    // old entry, so the reading positions survive the move into
    // FileHistory. Keys with no matching path are dropped.
    private fun migrateSessionKeys() {
        val stale = prefs.all.keys.filter { it.startsWith(KEY_LEGACY_SESSION_PREFIX) }
        if (stale.isEmpty()) return
        var changed = false
        states.toList().forEachIndexed { i, e ->
            if (!e.useDefaultState) return@forEachIndexed
            val key = KEY_LEGACY_SESSION_PREFIX + e.path.hashCode().toString()
            val raw = prefs.getString(key, null) ?: return@forEachIndexed
            try {
                val obj = JSONObject(raw)
                val legacyZoom = obj.optDouble("zoom", 0.0).toFloat()
                states[i] = e.copy(
                    useDefaultState = false,
                    pageNo = obj.optInt("page", 0),
                    zoom = when {
                        legacyZoom < 0f -> kZoomFitPage
                        legacyZoom == 0f -> kZoomFitWidth
                        else -> legacyZoom.toString()
                    },
                    rotation = obj.optInt("rotation", 0),
                    displayMode = migrateLegacyLayout(obj.optString("layout", "Single")),
                )
                changed = true
            } catch (_: Throwable) {
            }
        }
        val edit = prefs.edit()
        stale.forEach { edit.remove(it) }
        edit.remove(KEY_LEGACY_LAST_PATH)
        edit.apply()
        if (changed) save()
    }

    // Before the start page existed the app kept a flat "recentFiles"
    // array of paths. Carry those over so an upgrade doesn't wipe the
    // user's history, then drop the old key.
    private fun migrateRecentFiles() {
        val legacy = prefs.getString(KEY_LEGACY_RECENT, null) ?: return
        try {
            val arr = JSONArray(legacy)
            for (i in 0 until arr.length()) {
                val path = arr.optString(i, "")
                if (path.isBlank()) continue
                states.add(FileHistoryEntry(path = path, openCount = 1))
            }
        } catch (_: Throwable) {
        }
        prefs.edit().remove(KEY_LEGACY_RECENT).apply()
        save()
    }

    private fun save() {
        val arr = JSONArray()
        states.forEach { e ->
            val obj = JSONObject()
            obj.put("path", e.path)
            if (e.uri != null) obj.put("uri", e.uri)
            obj.put("name", e.displayName)
            obj.put("pinned", e.isPinned)
            obj.put("missing", e.isMissing)
            obj.put("openCount", e.openCount)
            obj.put("useDefaultState", e.useDefaultState)
            if (!e.useDefaultState) {
                obj.put("displayMode", e.displayMode)
                obj.put("pageNo", e.pageNo)
                obj.put("zoom", e.zoom)
                obj.put("rotation", e.rotation)
                obj.put("showToc", e.showToc)
                if (e.reparseIdx != 0L) obj.put("reparseIdx", e.reparseIdx)
            }
            if (e.encryptedPassword != null) obj.put("pw", e.encryptedPassword)
            arr.put(obj)
        }
        prefs.edit().putString(KEY_FILE_STATES, arr.toString()).apply()
    }

    fun all(): List<FileHistoryEntry> = states.toList()

    fun findByPath(path: String): FileHistoryEntry? =
        states.lastOrNull { it.path.equals(path, ignoreCase = true) }

    fun markFileLoaded(path: String, uri: String? = null, displayName: String? = null): FileHistoryEntry {
        val existingIdx = states.indexOfLast { it.path.equals(path, ignoreCase = true) }
        val base = if (existingIdx >= 0) states.removeAt(existingIdx) else FileHistoryEntry(path = path)
        val updated = base.copy(
            uri = uri ?: base.uri,
            displayName = displayName ?: base.displayName,
            isMissing = false,
            openCount = base.openCount + 1,
        )
        states.add(0, updated)
        purge()
        save()
        return updated
    }

    // Win32 moves a file that has gone away towards the back of the list
    // rather than dropping it, so its state survives if the file comes
    // back, and quarters its open count so it sinks in the frequency
    // order too.
    fun markFileInexistent(path: String, hide: Boolean) {
        var idx = states.indexOfLast { it.path.equals(path, ignoreCase = true) }
        if (idx < 0) {
            states.add(FileHistoryEntry(path = path))
            idx = states.lastIndex
        }
        val entry = states[idx]
        val newIdx = if (hide) Int.MAX_VALUE else kFileHistoryMaxRecent - 1
        if (idx < newIdx && idx != states.lastIndex) {
            states.removeAt(idx)
            if (states.size <= newIdx) states.add(entry) else states.add(newIdx, entry)
            idx = states.indexOfLast { it.path.equals(path, ignoreCase = true) }
        }
        states[idx] = entry.copy(openCount = entry.openCount shr 2, isMissing = hide)
        save()
    }

    fun saveState(
        path: String,
        pageNo: Int,
        zoom: String,
        rotation: Int,
        displayMode: String,
        showToc: Boolean,
        reparseIdx: Long,
    ) {
        val idx = states.indexOfLast { it.path.equals(path, ignoreCase = true) }
        if (idx < 0) return
        val cur = states[idx]
        val next = cur.copy(
            useDefaultState = false,
            pageNo = pageNo,
            zoom = zoom,
            rotation = rotation,
            displayMode = displayMode,
            showToc = showToc,
            reparseIdx = reparseIdx,
        )
        if (next == cur) return
        states[idx] = next
        save()
    }

    fun rememberPassword(path: String, password: String) {
        val idx = states.indexOfLast { it.path.equals(path, ignoreCase = true) }
        if (idx < 0) return
        val sealed = SecretStore.seal(password) ?: return
        states[idx] = states[idx].copy(encryptedPassword = sealed)
        save()
    }

    fun passwordFor(path: String): String? {
        val blob = findByPath(path)?.encryptedPassword ?: return null
        return SecretStore.open(blob)
    }

    fun forgetPassword(path: String) {
        val idx = states.indexOfLast { it.path.equals(path, ignoreCase = true) }
        if (idx < 0 || states[idx].encryptedPassword == null) return
        states[idx] = states[idx].copy(encryptedPassword = null)
        save()
    }

    fun loadSessionData(): SessionData? {
        val raw = prefs.getString(KEY_SESSION_DATA, null) ?: return null
        return try {
            val obj = JSONObject(raw)
            val arr = obj.optJSONArray("tabs") ?: return null
            val tabs = (0 until arr.length()).mapNotNull { i ->
                val t = arr.optJSONObject(i) ?: return@mapNotNull null
                val p = t.optString("path", "")
                if (p.isBlank()) null
                else SessionTab(p, t.optString("uri", "").takeIf { it.isNotBlank() })
            }
            if (tabs.isEmpty()) null else SessionData(tabs, obj.optInt("tabIndex", 0))
        } catch (_: Throwable) {
            null
        }
    }

    fun saveSessionData(data: SessionData) {
        val arr = JSONArray()
        data.tabs.forEach { t ->
            val obj = JSONObject()
            obj.put("path", t.path)
            if (t.uri != null) obj.put("uri", t.uri)
            arr.put(obj)
        }
        val root = JSONObject()
        root.put("tabs", arr)
        root.put("tabIndex", data.tabIndex)
        prefs.edit().putString(KEY_SESSION_DATA, root.toString()).apply()
    }

    fun clearSessionData() {
        prefs.edit().remove(KEY_SESSION_DATA).apply()
    }

    fun setPinned(path: String, pinned: Boolean) {
        val idx = states.indexOfLast { it.path.equals(path, ignoreCase = true) }
        if (idx < 0) return
        states[idx] = states[idx].copy(isPinned = pinned)
        save()
    }

    fun remove(path: String) {
        if (states.removeAll { it.path.equals(path, ignoreCase = true) }) save()
    }

    fun clear() {
        states.clear()
        save()
    }

    private fun purge() {
        while (states.size > kFileHistoryMaxFiles) {
            val idx = states.indexOfLast { !it.isPinned }
            if (idx < 0) return
            states.removeAt(idx)
        }
    }

    // Both orderings drop entries known to be missing unless they are
    // pinned, and stamp each surviving entry with its position in the
    // recency list so the comparators can break ties on it.
    private fun visible(): List<FileHistoryEntry> =
        states.mapIndexed { i, e -> e.copy(index = i) }.filter { !it.isMissing || it.isPinned }

    fun frequencyOrder(): List<FileHistoryEntry> = visible().sortedWith(::cmpOpenCount)

    fun recentlyOpenedOrder(): List<FileHistoryEntry> = visible().sortedWith(::cmpRecentlyOpened)

    fun ordered(sortByFrequentlyRead: Boolean): List<FileHistoryEntry> =
        if (sortByFrequentlyRead) frequencyOrder() else recentlyOpenedOrder()

    // Marks entries whose file has gone away. Called off the main thread
    // from the start page, the Android equivalent of
    // RemoveNonExistentFilesAsync.
    fun refreshExistence(): Boolean {
        var changed = false
        states.toList().forEachIndexed { i, e ->
            if (e.isMissing) return@forEachIndexed
            if (File(e.path).exists()) return@forEachIndexed
            if (e.uri != null) return@forEachIndexed
            states[i] = e.copy(isMissing = true, openCount = e.openCount shr 2)
            changed = true
        }
        if (changed) save()
        return changed
    }

    companion object {
        private const val KEY_FILE_STATES = "fileStates"
        private const val KEY_SESSION_DATA = "sessionData"
        private const val KEY_LEGACY_RECENT = "recentFiles"
        private const val KEY_LEGACY_SESSION_PREFIX = "session_"
        private const val KEY_LEGACY_LAST_PATH = "lastPath"
    }
}

// src/DisplayMode.cpp. The port splits Win32's single DisplayMode enum
// into (DisplayMode, continuous), so these convert between that pair and
// the strings the settings file uses.
const val kDisplayModeAutomatic = "automatic"

fun displayModeToString(mode: DisplayMode, continuous: Boolean): String = when {
    mode == DisplayMode.SinglePage && !continuous -> "single page"
    mode == DisplayMode.Facing && !continuous -> "facing"
    mode == DisplayMode.BookView && !continuous -> "book view"
    mode == DisplayMode.SinglePage -> "continuous"
    mode == DisplayMode.Facing -> "continuous facing"
    else -> "continuous book view"
}

fun displayModeFromString(s: String?): Pair<DisplayMode, Boolean>? = when (s?.lowercase()) {
    "single page" -> DisplayMode.SinglePage to false
    "facing" -> DisplayMode.Facing to false
    "book view" -> DisplayMode.BookView to false
    "continuous", "continuous single page" -> DisplayMode.SinglePage to true
    "continuous facing" -> DisplayMode.Facing to true
    "continuous book view" -> DisplayMode.BookView to true
    else -> null
}

// The layout names the port wrote before it adopted the settings-file
// spelling.
internal fun migrateLegacyLayout(s: String): String = when (s) {
    "Facing" -> "facing"
    "Book" -> "book view"
    "SingleContinuous" -> "continuous"
    "FacingContinuous" -> "continuous facing"
    "BookContinuous" -> "continuous book view"
    else -> "single page"
}

const val kZoomFitPage = "fit page"
const val kZoomFitWidth = "fit width"
const val kZoomFitHeight = "fit height"
const val kZoomFitContent = "fit content"

// src/DisplayMode.cpp::ZoomToString / ZoomFromString. The old
// float encoding collapsed fit height and fit content onto fit width,
// so those two modes were silently lost on every reopen.
fun zoomToString(zoom: ZoomLevel, customZoom: Float): String = when (zoom) {
    ZoomLevel.FitPage -> kZoomFitPage
    ZoomLevel.FitWidth -> kZoomFitWidth
    ZoomLevel.FitHeight -> kZoomFitHeight
    ZoomLevel.FitContent -> kZoomFitContent
    ZoomLevel.Custom -> (customZoom * 100f).toString()
}

fun zoomFromString(s: String?): Pair<ZoomLevel, Float>? = when (s?.lowercase()) {
    null -> null
    kZoomFitPage -> ZoomLevel.FitPage to 1f
    kZoomFitWidth -> ZoomLevel.FitWidth to 1f
    kZoomFitHeight -> ZoomLevel.FitHeight to 1f
    kZoomFitContent -> ZoomLevel.FitContent to 1f
    else -> s.toFloatOrNull()
        ?.takeIf { it in 8.33f..6400f }
        ?.let { ZoomLevel.Custom to it / 100f }
}

internal fun cmpOpenCount(a: FileHistoryEntry, b: FileHistoryEntry): Int {
    if (a.isPinned != b.isPinned) return if (a.isPinned) -1 else 1
    if (a.isPinned) return compareNatural(baseName(a.path), baseName(b.path))
    if (a.openCount != b.openCount) return b.openCount - a.openCount
    return if (a.index < b.index) -1 else 1
}

internal fun cmpRecentlyOpened(a: FileHistoryEntry, b: FileHistoryEntry): Int {
    if (a.isPinned != b.isPinned) return if (a.isPinned) -1 else 1
    if (a.isPinned) return compareNatural(baseName(a.path), baseName(b.path))
    return if (a.index < b.index) -1 else 1
}

internal fun baseName(path: String): String =
    path.substringAfterLast('/').substringAfterLast('\\').ifEmpty { path }

internal fun parentDir(path: String): String {
    val cut = path.lastIndexOfAny(charArrayOf('/', '\\'))
    return if (cut <= 0) "" else path.substring(0, cut)
}

// Port of str::CmpNatural (src/base/Str.cpp): whitespace-insensitive,
// case-insensitive, with runs of digits compared as numbers so
// "chapter 2" sorts before "chapter 10".
fun compareNatural(aIn: String, bIn: String): Int {
    var ai = 0
    var bi = 0
    var diff = 0

    fun at(s: String, i: Int): Char = if (i >= s.length) Char(0) else s[i]

    while (diff == 0) {
        if (ai == 0 || bi == 0 || ai >= aIn.length || bi >= bIn.length ||
            (aIn[ai].isWhitespace() && bIn[bi].isWhitespace())
        ) {
            while (ai < aIn.length && aIn[ai].isWhitespace()) ai++
            while (bi < bIn.length && bIn[bi].isWhitespace()) bi++
        }
        if (ai >= aIn.length && bi >= bIn.length) return compareLexicographic(aIn, bIn)

        val ca = at(aIn, ai)
        val cb = at(bIn, bi)

        if (ca.isDigit() && cb.isDigit()) {
            while (ai < aIn.length && aIn[ai] == '0') ai++
            while (bi < bIn.length && bIn[bi] == '0') bi++
            diff = 0
            while (at(aIn, ai).isDigit() || at(bIn, bi).isDigit()) {
                if (!at(aIn, ai).isDigit()) return -1
                if (!at(bIn, bi).isDigit()) return 1
                if (diff == 0) diff = aIn[ai].code - bIn[bi].code
                ai++
                bi++
            }
            ai--
            bi--
        } else if (ca.isLetterOrDigit() && cb.isLetterOrDigit()) {
            diff = ca.lowercaseChar().code - cb.lowercaseChar().code
        } else if (ca.isLetterOrDigit()) {
            return 1
        } else if (cb.isLetterOrDigit()) {
            return -1
        } else {
            diff = ca.code - cb.code
        }
        ai++
        bi++
    }
    return diff
}

private fun compareLexicographic(a: String, b: String): Int {
    val minLen = minOf(a.length, b.length)
    for (i in 0 until minLen) {
        if (a[i] != b[i]) return a[i].code - b[i].code
    }
    return a.length - b.length
}

// Port of SplitFilterToWords / FilterMatches (src/FilterHighlightDraw.cpp):
// every whitespace-separated word of the query must appear somewhere in
// the candidate, case-insensitively.
fun splitFilterToWords(filter: String): List<String> =
    filter.split(Regex("\\s+")).filter { it.isNotEmpty() }.distinct()

fun filterMatches(candidate: String, words: List<String>): Boolean =
    words.all { candidate.contains(it, ignoreCase = true) }
