package com.sumatrapdf.reader.library

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

// Port of audiobook/library/net.py. Every online lookup goes through
// here, so a phone that is offline degrades the same way the desktop
// does: three failures in a row and nothing is attempted for two
// minutes, and every JSON answer (including a miss) is kept on disk.

private const val TAG = "SumatraLibNet"

const val USER_AGENT = "SumatraPDF-Library/1.0 (local ebook library; contact: local user)"
const val NET_TIMEOUT_MS = 12_000
const val JSON_TTL_MS = 30L * 24 * 3600 * 1000
const val MISS_TTL_MS = 3L * 24 * 3600 * 1000
const val OFFLINE_AFTER = 3
const val OFFLINE_FOR_MS = 120_000L

object LibraryCache {
    @Volatile
    var root: File = File(System.getProperty("java.io.tmpdir") ?: ".", "sumatra-library")

    fun dir(kind: String): File = File(root, kind).also { it.mkdirs() }
}

object Net {
    private val lock = Any()
    private var fails = 0
    private var offlineUntil = 0L

    @Volatile
    var allowNetwork = true

    fun keyFor(text: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }.take(20)
    }

    fun offline(): Boolean = synchronized(lock) {
        !allowNetwork || System.currentTimeMillis() < offlineUntil
    }

    fun forgetOffline() = synchronized(lock) {
        fails = 0
        offlineUntil = 0L
    }

    private fun note(ok: Boolean) = synchronized(lock) {
        if (ok) {
            fails = 0
            offlineUntil = 0L
        } else {
            fails++
            if (fails >= OFFLINE_AFTER) offlineUntil = System.currentTimeMillis() + OFFLINE_FOR_MS
        }
    }

    fun fetch(url: String, timeoutMs: Int = NET_TIMEOUT_MS, accept: String? = null): ByteArray? {
        if (offline()) return null
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                if (accept != null) setRequestProperty("Accept", accept)
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                note(code >= 500 || code == 429)
                null
            } else {
                val data = conn.inputStream.use { it.readBytes() }
                note(true)
                data
            }
        } catch (t: Throwable) {
            note(false)
            null
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun cached(path: File, ttl: Long): Pair<Boolean, JSONObject?> {
        if (!path.exists()) return false to null
        return try {
            val blob = JSONObject(path.readText())
            val at = (blob.optDouble("at", 0.0) * 1000).toLong()
            val value = blob.optJSONObject("value")
            val age = System.currentTimeMillis() - at
            if (age < (if (value != null) ttl else MISS_TTL_MS)) true to value else false to null
        } catch (_: Throwable) {
            false to null
        }
    }

    private fun store(path: File, url: String, value: JSONObject?) {
        try {
            val blob = JSONObject()
            blob.put("at", System.currentTimeMillis() / 1000.0)
            blob.put("url", url)
            blob.put("value", value ?: JSONObject.NULL)
            path.writeText(blob.toString())
        } catch (t: Throwable) {
            Log.w(TAG, "cache write failed: ${t.message}")
        }
    }

    fun fetchJson(url: String, ttl: Long = JSON_TTL_MS, timeoutMs: Int = NET_TIMEOUT_MS): JSONObject? {
        val path = File(dirHttp(), keyFor(url) + ".json")
        val (hit, value) = cached(path, ttl)
        if (hit) return value
        val raw = fetch(url, timeoutMs, "application/json")
        var parsed: JSONObject? = null
        if (raw != null) {
            parsed = try {
                JSONObject(String(raw, Charsets.UTF_8))
            } catch (_: Throwable) {
                null
            }
        }
        if (raw == null) return null
        store(path, url, parsed)
        return parsed
    }

    fun postJson(
        url: String,
        payload: JSONObject,
        ttl: Long = JSON_TTL_MS,
        timeoutMs: Int = NET_TIMEOUT_MS,
        headers: Map<String, String> = emptyMap(),
    ): JSONObject? {
        val body = payload.toString().toByteArray(Charsets.UTF_8)
        val path = File(dirHttp(), keyFor(url + "|" + String(body, Charsets.UTF_8)) + ".json")
        val (hit, value) = cached(path, ttl)
        if (hit) return value
        if (offline()) return null
        var conn: HttpURLConnection? = null
        var parsed: JSONObject? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Content-Type", "application/json")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code in 200..299) {
                val text = conn.inputStream.use { it.readBytes() }
                parsed = JSONObject(String(text, Charsets.UTF_8))
                note(true)
            } else {
                note(code >= 500 || code == 429)
            }
        } catch (_: Throwable) {
            note(false)
            if (offline()) return null
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
        store(path, url, parsed)
        return parsed
    }

    private fun dirHttp(): File = LibraryCache.dir("http")
}

fun urlEncode(text: String): String = java.net.URLEncoder.encode(text, "UTF-8").replace("+", "%20")
