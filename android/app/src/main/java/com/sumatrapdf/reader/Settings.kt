package com.sumatrapdf.reader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// App-wide settings, stored in SharedPreferences "sumatra". This is the
// `GlobalPrefs` half of SumatraPDF's `SumatraPDF-settings.txt`; the
// per-document half (`FileStates`, `SessionData`) lives in FileHistory,
// which uses the same preferences file.
//
// Everything is stored as plain strings or JSON; no binary blobs. Keys:
//   bookmarks             - JSON array of {path, page, name}
//   nightMode, invertPageColors, readAloudVoice
//   showStartPage, homePageViewMode, homePageSortByFrequentlyRead
//   restoreSession
class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("sumatra", Context.MODE_PRIVATE)

    // ---- Bookmarks ----

    fun getBookmarks(): List<Bookmark> {
        val raw = prefs.getString(KEY_BOOKMARKS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val path = obj.optString("path", "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val page = obj.optInt("page", -1)
                if (page < 0) return@mapNotNull null
                val name = obj.optString("name", "")
                Bookmark(path, page, name)
            }
        } catch (_: Throwable) { emptyList() }
    }

    fun getBookmarksFor(path: String): List<Bookmark> =
        getBookmarks().filter { it.path == path }

    fun addBookmark(b: Bookmark) {
        if (b.path.isBlank()) return
        val current = getBookmarks().toMutableList()
        current.removeAll { it.path == b.path && it.page == b.page }
        current.add(0, b)
        writeBookmarks(current)
    }

    fun removeBookmark(b: Bookmark) {
        val current = getBookmarks().toMutableList()
        if (current.removeAll { it.path == b.path && it.page == b.page }) {
            writeBookmarks(current)
        }
    }

    private fun writeBookmarks(list: List<Bookmark>) {
        val arr = JSONArray()
        list.forEach { b ->
            val obj = JSONObject()
            obj.put("path", b.path)
            obj.put("page", b.page)
            obj.put("name", b.name)
            arr.put(obj)
        }
        prefs.edit().putString(KEY_BOOKMARKS, arr.toString()).apply()
    }

    // ---- App-wide view preferences ----

    var nightMode: Boolean
        get() = prefs.getBoolean(KEY_NIGHT_MODE, false)
        set(value) { prefs.edit().putBoolean(KEY_NIGHT_MODE, value).apply() }

    var invertPageColors: Boolean
        get() = prefs.getBoolean(KEY_INVERT_PAGES, false)
        set(value) { prefs.edit().putBoolean(KEY_INVERT_PAGES, value).apply() }

    // GlobalPrefs::LibraryHomeEnabled — the home tab is either the
    // poster-wall library or the classic frequently-read page.
    var libraryHome: Boolean
        get() = prefs.getBoolean(KEY_LIBRARY_HOME, false)
        set(value) { prefs.edit().putBoolean(KEY_LIBRARY_HOME, value).apply() }

    var readAloudVoice: String?
        get() = prefs.getString(KEY_VOICE, null)
        set(value) { prefs.edit().putString(KEY_VOICE, value).apply() }

    // ---- Start page (GlobalPrefs::showStartPage, homePageViewMode,
    // homePageSortByFrequentlyRead in src/Settings.h) ----

    var showStartPage: Boolean
        get() = prefs.getBoolean(KEY_SHOW_START_PAGE, true)
        set(value) { prefs.edit().putBoolean(KEY_SHOW_START_PAGE, value).apply() }

    var homePageSortByFrequentlyRead: Boolean
        get() = prefs.getBoolean(KEY_HOME_SORT_FREQUENT, false)
        set(value) { prefs.edit().putBoolean(KEY_HOME_SORT_FREQUENT, value).apply() }

    var homePageListView: Boolean
        get() = prefs.getString(KEY_HOME_VIEW_MODE, "thumbnails") == "list"
        set(value) {
            prefs.edit().putString(KEY_HOME_VIEW_MODE, if (value) "list" else "thumbnails").apply()
        }

    // GlobalPrefs::restoreSession, default true on Windows too.
    var restoreSession: Boolean
        get() = prefs.getBoolean(KEY_RESTORE_SESSION, true)
        set(value) { prefs.edit().putBoolean(KEY_RESTORE_SESSION, value).apply() }

    // Keyboard-shortcut layer (src/Accelerators.cpp). Default true
    // (matches the Win32 behaviour: shortcuts are always on). The
    // toggle is here so a Bluetooth page-turner or a kiosk-mode
    // launcher can switch the layer off without the user having to
    // uninstall the app.
    var keyboardShortcuts: Boolean
        get() = prefs.getBoolean(KEY_KEYBOARD_SHORTCUTS, true)
        set(value) { prefs.edit().putBoolean(KEY_KEYBOARD_SHORTCUTS, value).apply() }

    // Books across a row on the library wall, set by pinching it.
    var libraryColumns: Int
        get() = prefs.getInt(KEY_LIBRARY_COLUMNS, 0).coerceIn(0, MAX_LIBRARY_COLUMNS)
        set(value) {
            prefs.edit().putInt(KEY_LIBRARY_COLUMNS, value.coerceIn(0, MAX_LIBRARY_COLUMNS)).apply()
        }

    // Title size on the library wall and the frequently-read page,
    // relative to the size the tile width would give on its own.
    var libraryTitleScale: Float
        get() = prefs.getFloat(KEY_LIBRARY_TITLE_SCALE, 1f).coerceIn(MIN_TITLE_SCALE, MAX_TITLE_SCALE)
        set(value) {
            prefs.edit()
                .putFloat(KEY_LIBRARY_TITLE_SCALE, value.coerceIn(MIN_TITLE_SCALE, MAX_TITLE_SCALE))
                .apply()
        }

    companion object {
        const val MIN_LIBRARY_COLUMNS = 1
        const val MAX_LIBRARY_COLUMNS = 5
        const val MIN_TITLE_SCALE = 0.6f
        const val MAX_TITLE_SCALE = 1.8f

        private const val KEY_BOOKMARKS = "bookmarks"
        private const val KEY_NIGHT_MODE = "nightMode"
        private const val KEY_INVERT_PAGES = "invertPageColors"
        private const val KEY_VOICE = "readAloudVoice"
        private const val KEY_SHOW_START_PAGE = "showStartPage"
        private const val KEY_HOME_SORT_FREQUENT = "homePageSortByFrequentlyRead"
        private const val KEY_HOME_VIEW_MODE = "homePageViewMode"
        private const val KEY_RESTORE_SESSION = "restoreSession"
        private const val KEY_LIBRARY_HOME = "libraryHome"
        private const val KEY_LIBRARY_COLUMNS = "libraryColumns"
        private const val KEY_LIBRARY_TITLE_SCALE = "libraryTitleScale"
        private const val KEY_KEYBOARD_SHORTCUTS = "keyboardShortcuts"
    }
}

data class Bookmark(val path: String, val page: Int, val name: String) {
    val displayName: String get() = name.ifBlank { "Page ${page + 1}" }
}
