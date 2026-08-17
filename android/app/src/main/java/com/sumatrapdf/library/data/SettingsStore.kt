package com.sumatrapdf.library.data

import android.content.Context
import android.content.SharedPreferences
import com.sumatrapdf.library.ui.theme.SumThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

// All user-tunable settings, persisted as SharedPreferences. The flow
// pattern means Compose can observe changes and the multi-tab reader
// picks up theme / layout / zoom changes without each tab re-querying.

// SumatraPDF on Windows has these layouts; we mirror them.
enum class PageLayout { Single, Facing, Book }

// Where the reader opens in a new tab by default.
enum class ZoomFit { FitPage, FitWidth, Actual }

// What to do when the user taps the document close button on a single tab.
enum class CloseBehavior { CloseTab, CloseDocument }

// The single, observable settings bag. Compose subscribes to flows and the
// reader host listens to change events.
class SettingsStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    private val _events = MutableStateFlow(0L)
    val events: StateFlow<Long> = _events.asStateFlow()

    private fun load(): SettingsState = SettingsState(
        themeMode = SumThemeMode.entries.firstOrNull { it.name == prefs.getString(KEY_THEME, null) } ?: SumThemeMode.System,
        rememberOpenDocs = prefs.getBoolean(KEY_REMEMBER, true),
        showBookshelfOnStart = prefs.getBoolean(KEY_SHOW_SHELF, true),
        pageLayout = PageLayout.entries.firstOrNull { it.name == prefs.getString(KEY_LAYOUT, null) } ?: PageLayout.Single,
        defaultZoom = ZoomFit.entries.firstOrNull { it.name == prefs.getString(KEY_ZOOM, null) } ?: ZoomFit.FitWidth,
        showLinks = prefs.getBoolean(KEY_LINKS, false),
        keepAwake = prefs.getBoolean(KEY_AWAKE, false),
        invertColorsNight = prefs.getBoolean(KEY_INVERT, true),
        scrollbar = prefs.getBoolean(KEY_SCROLLBAR, true),
        pageSpacingDp = prefs.getInt(KEY_SPACING, 8),
        textSizePt = prefs.getFloat(KEY_TEXT_SIZE, 14f),
        cbStretch = prefs.getBoolean(KEY_CB_STRETCH, false),
        cbTwoPages = prefs.getBoolean(KEY_CB_TWO_PAGES, false),
        cbScrollContinuous = prefs.getBoolean(KEY_CB_CONT, true),
        cbAllowCJK = prefs.getBoolean(KEY_CB_CJK, true),
        cbFontList = prefs.getString(KEY_CB_FONTS, "") ?: "",
        cbDefaultFont = prefs.getString(KEY_CB_DEFAULT, "") ?: "",
        cbMonoFont = prefs.getString(KEY_CB_MONO, "") ?: "",
        customHomePath = prefs.getString(KEY_HOME_PATH, "") ?: "",
        closeBehavior = CloseBehavior.entries.firstOrNull { it.name == prefs.getString(KEY_CLOSE_BEHAVIOR, null) } ?: CloseBehavior.CloseTab,
    )

    fun setThemeMode(mode: SumThemeMode) = update { it.copy(themeMode = mode) }
    fun setRememberOpenDocs(value: Boolean) = update { it.copy(rememberOpenDocs = value) }
    fun setShowBookshelfOnStart(value: Boolean) = update { it.copy(showBookshelfOnStart = value) }
    fun setPageLayout(layout: PageLayout) = update { it.copy(pageLayout = layout) }
    fun setDefaultZoom(zoom: ZoomFit) = update { it.copy(defaultZoom = zoom) }
    fun setShowLinks(value: Boolean) = update { it.copy(showLinks = value) }
    fun setKeepAwake(value: Boolean) = update { it.copy(keepAwake = value) }
    fun setInvertColorsNight(value: Boolean) = update { it.copy(invertColorsNight = value) }
    fun setScrollbar(value: Boolean) = update { it.copy(scrollbar = value) }
    fun setPageSpacing(dp: Int) = update { it.copy(pageSpacingDp = dp.coerceIn(0, 32)) }
    fun setTextSize(pt: Float) = update { it.copy(textSizePt = pt.coerceIn(8f, 32f)) }
    fun setCbStretch(value: Boolean) = update { it.copy(cbStretch = value) }
    fun setCbTwoPages(value: Boolean) = update { it.copy(cbTwoPages = value) }
    fun setCbScrollContinuous(value: Boolean) = update { it.copy(cbScrollContinuous = value) }
    fun setCbAllowCJK(value: Boolean) = update { it.copy(cbAllowCJK = value) }
    fun setCbFontList(value: String) = update { it.copy(cbFontList = value) }
    fun setCbDefaultFont(value: String) = update { it.copy(cbDefaultFont = value) }
    fun setCbMonoFont(value: String) = update { it.copy(cbMonoFont = value) }
    fun setCustomHomePath(value: String) = update { it.copy(customHomePath = value) }
    fun setCloseBehavior(value: CloseBehavior) = update { it.copy(closeBehavior = value) }

    fun resetAdvanced() {
        prefs.edit()
            .remove(KEY_SCROLLBAR).remove(KEY_SPACING).remove(KEY_TEXT_SIZE)
            .remove(KEY_CB_STRETCH).remove(KEY_CB_TWO_PAGES).remove(KEY_CB_CONT)
            .remove(KEY_CB_CJK).remove(KEY_CB_FONTS).remove(KEY_CB_DEFAULT)
            .remove(KEY_CB_MONO).remove(KEY_HOME_PATH)
            .apply()
        _state.value = load()
        _events.value = System.nanoTime()
    }

    private inline fun update(transform: (SettingsState) -> SettingsState) {
        val cur = _state.value
        val next = transform(cur)
        prefs.edit().apply {
            putString(KEY_THEME, next.themeMode.name)
            putBoolean(KEY_REMEMBER, next.rememberOpenDocs)
            putBoolean(KEY_SHOW_SHELF, next.showBookshelfOnStart)
            putString(KEY_LAYOUT, next.pageLayout.name)
            putString(KEY_ZOOM, next.defaultZoom.name)
            putBoolean(KEY_LINKS, next.showLinks)
            putBoolean(KEY_AWAKE, next.keepAwake)
            putBoolean(KEY_INVERT, next.invertColorsNight)
            putBoolean(KEY_SCROLLBAR, next.scrollbar)
            putInt(KEY_SPACING, next.pageSpacingDp)
            putFloat(KEY_TEXT_SIZE, next.textSizePt)
            putBoolean(KEY_CB_STRETCH, next.cbStretch)
            putBoolean(KEY_CB_TWO_PAGES, next.cbTwoPages)
            putBoolean(KEY_CB_CONT, next.cbScrollContinuous)
            putBoolean(KEY_CB_CJK, next.cbAllowCJK)
            putString(KEY_CB_FONTS, next.cbFontList)
            putString(KEY_CB_DEFAULT, next.cbDefaultFont)
            putString(KEY_CB_MONO, next.cbMonoFont)
            putString(KEY_HOME_PATH, next.customHomePath)
            putString(KEY_CLOSE_BEHAVIOR, next.closeBehavior.name)
        }.apply()
        _state.value = next
        _events.value = System.nanoTime()
    }

    companion object {
        private const val PREFS = "sumatra"
        private const val KEY_THEME = "theme"
        private const val KEY_REMEMBER = "rememberOpen"
        private const val KEY_SHOW_SHELF = "showShelf"
        private const val KEY_LAYOUT = "layout"
        private const val KEY_ZOOM = "zoom"
        private const val KEY_LINKS = "showLinks"
        private const val KEY_AWAKE = "keepAwake"
        private const val KEY_INVERT = "invertNight"
        private const val KEY_SCROLLBAR = "scrollbar"
        private const val KEY_SPACING = "pageSpacing"
        private const val KEY_TEXT_SIZE = "textSize"
        private const val KEY_CB_STRETCH = "cbStretch"
        private const val KEY_CB_TWO_PAGES = "cbTwo"
        private const val KEY_CB_CONT = "cbCont"
        private const val KEY_CB_CJK = "cbCjk"
        private const val KEY_CB_FONTS = "cbFonts"
        private const val KEY_CB_DEFAULT = "cbDefault"
        private const val KEY_CB_MONO = "cbMono"
        private const val KEY_HOME_PATH = "homePath"
        private const val KEY_CLOSE_BEHAVIOR = "closeBehavior"

        @Volatile private var instance: SettingsStore? = null
        fun get(context: Context): SettingsStore =
            instance ?: synchronized(this) {
                instance ?: SettingsStore(context).also { instance = it }
            }
    }
}

data class SettingsState(
    val themeMode: SumThemeMode,
    val rememberOpenDocs: Boolean,
    val showBookshelfOnStart: Boolean,
    val pageLayout: PageLayout,
    val defaultZoom: ZoomFit,
    val showLinks: Boolean,
    val keepAwake: Boolean,
    val invertColorsNight: Boolean,
    val scrollbar: Boolean,
    val pageSpacingDp: Int,
    val textSizePt: Float,
    val cbStretch: Boolean,
    val cbTwoPages: Boolean,
    val cbScrollContinuous: Boolean,
    val cbAllowCJK: Boolean,
    val cbFontList: String,
    val cbDefaultFont: String,
    val cbMonoFont: String,
    val customHomePath: String,
    val closeBehavior: CloseBehavior,
)

// Most-recently-opened documents. Stored as JSON in SharedPreferences. The
// Compose host reads this for the recents and favorites surfaces.
class RecentsStore private constructor(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(): List<RecentDoc> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<RecentDoc>(arr.length())
            for (i in 0 until arr.length()) {
                val row = arr.getJSONObject(i)
                out.add(
                    RecentDoc(
                        origin = row.getString("origin"),
                        name = row.optString("name"),
                        page = row.optInt("page"),
                        pages = row.optInt("pages"),
                        opened = row.optLong("opened"),
                    )
                )
            }
            out.sortedByDescending { it.opened }
        } catch (_: Exception) { emptyList() }
    }

    fun remember(origin: String, name: String, page: Int, pages: Int) {
        val kept = list().filter { it.origin != origin }.take(MAX - 1)
        val arr = JSONArray()
        val head = JSONObject().apply {
            put("origin", origin)
            put("name", name)
            put("page", page)
            put("pages", pages)
            put("opened", System.currentTimeMillis())
        }
        arr.put(head)
        for (one in kept) {
            arr.put(JSONObject().apply {
                put("origin", one.origin)
                put("name", one.name)
                put("page", one.page)
                put("pages", one.pages)
                put("opened", one.opened)
            })
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun pageOf(origin: String): Int =
        list().firstOrNull { it.origin == origin }?.page ?: 0

    fun setPage(origin: String, page: Int) {
        val kept = list().filter { it.origin != origin }.take(MAX - 1)
        val arr = JSONArray()
        val head = JSONObject().apply {
            put("origin", origin)
            put("name", "")
            put("page", page)
            put("pages", 0)
            put("opened", System.currentTimeMillis())
        }
        arr.put(head)
        for (one in kept) {
            arr.put(JSONObject().apply {
                put("origin", one.origin)
                put("name", one.name)
                put("page", one.page)
                put("pages", one.pages)
                put("opened", one.opened)
            })
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun rotationOf(origin: String): Int = prefs.getInt("rotate:$origin", 0)
    fun rememberRotation(origin: String, degrees: Int) {
        prefs.edit().putInt("rotate:$origin", degrees).apply()
    }

    fun clear() = prefs.edit().remove(KEY).apply()

    companion object {
        private const val PREFS = "recents"
        private const val KEY = "items"
        private const val MAX = 40

        @Volatile private var instance: RecentsStore? = null
        fun get(context: Context): RecentsStore =
            instance ?: synchronized(this) {
                instance ?: RecentsStore(context).also { instance = it }
            }
    }
}

data class RecentDoc(
    val origin: String,
    val name: String,
    val page: Int,
    val pages: Int,
    val opened: Long,
)

// User-pinned pages, surfaced in the Favorites tab. Free-form across
// documents: a favorite is just (file origin, page number, label, added).
class FavoritesStore private constructor(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(): List<Favorite> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<Favorite>(arr.length())
            for (i in 0 until arr.length()) {
                val row = arr.getJSONObject(i)
                out.add(
                    Favorite(
                        id = row.getString("id"),
                        origin = row.getString("origin"),
                        name = row.optString("name"),
                        page = row.optInt("page"),
                        label = row.optString("label"),
                        added = row.optLong("added"),
                    )
                )
            }
            out.sortedByDescending { it.added }
        } catch (_: Exception) { emptyList() }
    }

    fun contains(origin: String, page: Int): Boolean =
        list().any { it.origin == origin && it.page == page }

    fun add(origin: String, name: String, page: Int, label: String?): Favorite {
        val existing = list().filter { !(it.origin == origin && it.page == page) }
        val fav = Favorite(
            id = origin + ":" + page,
            origin = origin,
            name = name,
            page = page,
            label = label?.takeIf { it.isNotBlank() } ?: "Page ${page + 1}",
            added = System.currentTimeMillis(),
        )
        val arr = JSONArray()
        arr.put(JSONObject().apply {
            put("id", fav.id); put("origin", fav.origin); put("name", fav.name)
            put("page", fav.page); put("label", fav.label); put("added", fav.added)
        })
        for (one in existing) {
            arr.put(JSONObject().apply {
                put("id", one.id); put("origin", one.origin); put("name", one.name)
                put("page", one.page); put("label", one.label); put("added", one.added)
            })
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
        return fav
    }

    fun remove(id: String) {
        val kept = list().filter { it.id != id }
        val arr = JSONArray()
        for (one in kept) {
            arr.put(JSONObject().apply {
                put("id", one.id); put("origin", one.origin); put("name", one.name)
                put("page", one.page); put("label", one.label); put("added", one.added)
            })
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun rename(id: String, label: String) {
        val all = list()
        val arr = JSONArray()
        for (one in all) {
            val next = if (one.id == id) one.copy(label = label) else one
            arr.put(JSONObject().apply {
                put("id", next.id); put("origin", next.origin); put("name", next.name)
                put("page", next.page); put("label", next.label); put("added", next.added)
            })
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun clear() = prefs.edit().remove(KEY).apply()

    companion object {
        private const val PREFS = "favorites"
        private const val KEY = "items"

        @Volatile private var instance: FavoritesStore? = null
        fun get(context: Context): FavoritesStore =
            instance ?: synchronized(this) {
                instance ?: FavoritesStore(context).also { instance = it }
            }
    }
}

data class Favorite(
    val id: String,
    val origin: String,
    val name: String,
    val page: Int,
    val label: String,
    val added: Long,
)

// Per-document bookmarks. Kept separately from favorites so a user can
// remember "I was reading here" without pinning the page globally.
class BookmarksStore private constructor(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(origin: String) = "bm:" + origin

    fun list(origin: String): List<Bookmark> {
        val raw = prefs.getString(key(origin), null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<Bookmark>(arr.length())
            for (i in 0 until arr.length()) {
                val row = arr.getJSONObject(i)
                out.add(
                    Bookmark(
                        page = row.optInt("page"),
                        label = row.optString("label"),
                        added = row.optLong("added"),
                    )
                )
            }
            out.sortedBy { it.page }
        } catch (_: Exception) { emptyList() }
    }

    fun toggle(origin: String, page: Int, suggested: String): Bookmark? {
        val all = list(origin)
        val existing = all.firstOrNull { it.page == page }
        if (existing != null) {
            save(origin, all.filter { it.page != page })
            return null
        }
        val added = Bookmark(page = page, label = suggested, added = System.currentTimeMillis())
        save(origin, all + added)
        return added
    }

    fun rename(origin: String, page: Int, label: String) {
        val all = list(origin).map { if (it.page == page) it.copy(label = label) else it }
        save(origin, all)
    }

    fun remove(origin: String, page: Int) {
        save(origin, list(origin).filter { it.page != page })
    }

    private fun save(origin: String, list: List<Bookmark>) {
        val arr = JSONArray()
        for (one in list) {
            arr.put(JSONObject().apply {
                put("page", one.page); put("label", one.label); put("added", one.added)
            })
        }
        prefs.edit().putString(key(origin), arr.toString()).apply()
    }

    companion object {
        private const val PREFS = "bookmarks"

        @Volatile private var instance: BookmarksStore? = null
        fun get(context: Context): BookmarksStore =
            instance ?: synchronized(this) {
                instance ?: BookmarksStore(context).also { instance = it }
            }
    }
}

data class Bookmark(val page: Int, val label: String, val added: Long)
