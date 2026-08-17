package com.sumatrapdf.library

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

const val FIT_PAGE = 0
const val FIT_WIDTH = 1

class RecentDocument(
    val origin: String,
    val name: String,
    val page: Int,
    val pages: Int,
    val opened: Long,
)

object Reading {
    private const val PREFS = "reading"
    private const val KEY_RECENTS = "recents"
    private const val KEY_NIGHT = "night"
    private const val KEY_CONTINUOUS = "continuous"
    private const val KEY_FIT = "fit"
    private const val KEY_AWAKE = "awake"
    private const val KEY_LINKS = "showLinks"
    private const val KEY_EM = "em"
    private const val RECENT_LIMIT = 40

    private lateinit var prefs: SharedPreferences

    fun attach(context: Context) {
        if (!this::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    var night: Boolean
        get() = prefs.getBoolean(KEY_NIGHT, false)
        set(value) = prefs.edit().putBoolean(KEY_NIGHT, value).apply()

    var continuous: Boolean
        get() = prefs.getBoolean(KEY_CONTINUOUS, false)
        set(value) = prefs.edit().putBoolean(KEY_CONTINUOUS, value).apply()

    var fitMode: Int
        get() = prefs.getInt(KEY_FIT, FIT_PAGE)
        set(value) = prefs.edit().putInt(KEY_FIT, value).apply()

    var keepAwake: Boolean
        get() = prefs.getBoolean(KEY_AWAKE, false)
        set(value) = prefs.edit().putBoolean(KEY_AWAKE, value).apply()

    var showLinks: Boolean
        get() = prefs.getBoolean(KEY_LINKS, false)
        set(value) = prefs.edit().putBoolean(KEY_LINKS, value).apply()

    var textSize: Float
        get() = prefs.getFloat(KEY_EM, 11f)
        set(value) = prefs.edit().putFloat(KEY_EM, value).apply()

    fun rotationOf(key: String) = prefs.getInt("rotate:$key", 0)

    fun rememberRotation(key: String, degrees: Int) {
        prefs.edit().putInt("rotate:$key", degrees).apply()
    }

    fun pageOf(key: String) = prefs.getInt("page:$key", 0)

    fun rememberPage(key: String, page: Int) {
        prefs.edit().putInt("page:$key", page).apply()
    }

    fun recents(): List<RecentDocument> {
        val raw = prefs.getString(KEY_RECENTS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val out = ArrayList<RecentDocument>(array.length())
            for (i in 0 until array.length()) {
                val row = array.getJSONObject(i)
                out.add(
                    RecentDocument(
                        origin = row.getString("origin"),
                        name = row.optString("name"),
                        page = row.optInt("page"),
                        pages = row.optInt("pages"),
                        opened = row.optLong("opened"),
                    )
                )
            }
            out.sortedByDescending { it.opened }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun remember(source: DocumentSource, page: Int, pages: Int) {
        val kept = recents().filter { it.origin != source.origin }.take(RECENT_LIMIT - 1)
        val array = JSONArray()
        val head = JSONObject()
        head.put("origin", source.origin)
        head.put("name", source.name)
        head.put("page", page)
        head.put("pages", pages)
        head.put("opened", System.currentTimeMillis())
        array.put(head)
        for (one in kept) {
            val row = JSONObject()
            row.put("origin", one.origin)
            row.put("name", one.name)
            row.put("page", one.page)
            row.put("pages", one.pages)
            row.put("opened", one.opened)
            array.put(row)
        }
        prefs.edit().putString(KEY_RECENTS, array.toString()).apply()
    }

    fun forgetRecents() {
        prefs.edit().remove(KEY_RECENTS).apply()
    }
}
