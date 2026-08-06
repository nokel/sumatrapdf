package com.sumatrapdf.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

// Port of src/FileThumbnails.cpp plus CreateThumbnailFromFileThread in
// src/SumatraPDF.cpp. A thumbnail is the top of page 1 rendered to at
// most kThumbnailDx x kThumbnailDy, cached as a PNG named after the MD5
// of the document's path, in the app's own cache directory (the Win32
// app uses %APPDATA%\SumatraPDF\sumatrapdfcache).
//
// The document is opened with its own short-lived mupdf Document rather
// than through DocumentEngine, exactly as the Win32 code uses a
// temporary engine: a thumbnail must be renderable for a file that is
// not open in any tab.

const val kThumbnailDx = 212
const val kThumbnailDy = 150

object Thumbnails {
    private const val TAG = "SumatraThumbs"
    private const val RENDER_SCALE = 2

    private val memory = object : LruCache<String, Bitmap>(48) {
        override fun sizeOf(key: String, value: Bitmap) = 1
    }
    private val failed = mutableSetOf<String>()

    fun cacheDir(context: Context): File =
        File(context.applicationContext.filesDir, "sumatrapdfcache")

    fun fileFor(context: Context, docPath: String): File? {
        if (docPath.isEmpty()) return null
        val digest = MessageDigest.getInstance("MD5").digest(docPath.toByteArray())
        val hex = digest.joinToString("") { "%02x".format(it) }
        return File(cacheDir(context), "$hex.png")
    }

    fun cached(docPath: String): Bitmap? = memory.get(docPath)

    // Returns the thumbnail for a document, rendering and caching it on
    // first use. Blocking — callers run it on Dispatchers.IO.
    fun get(context: Context, docPath: String): Bitmap? {
        memory.get(docPath)?.let { return it }
        synchronized(failed) { if (docPath in failed) return null }

        val target = fileFor(context, docPath) ?: return null
        if (target.exists()) {
            val decoded = try {
                BitmapFactory.decodeFile(target.absolutePath)
            } catch (t: Throwable) {
                Log.w(TAG, "decode ${target.name} failed: ${t.message}")
                null
            }
            if (decoded != null) {
                memory.put(docPath, decoded)
                return decoded
            }
            target.delete()
        }

        val rendered = render(docPath)
        if (rendered == null) {
            synchronized(failed) { failed.add(docPath) }
            return null
        }
        try {
            target.parentFile?.mkdirs()
            FileOutputStream(target).use { out ->
                rendered.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "save ${target.name} failed: ${t.message}")
        }
        memory.put(docPath, rendered)
        return rendered
    }

    fun delete(context: Context, docPath: String) {
        memory.remove(docPath)
        synchronized(failed) { failed.remove(docPath) }
        fileFor(context, docPath)?.delete()
    }

    fun forget(docPath: String) {
        memory.remove(docPath)
        synchronized(failed) { failed.remove(docPath) }
    }

    // zoom is chosen so the page is exactly kThumbnailDx wide; a page
    // taller than kThumbnailDy at that zoom is cropped at the top rather
    // than squashed, which is what the Win32 renderer does by clipping
    // the page rect to kThumbnailDy / zoom before rendering.
    private fun render(docPath: String): Bitmap? {
        if (!File(docPath).exists()) return null
        var doc: Document? = null
        return try {
            doc = Document.openDocument(docPath)
            if (doc.needsPassword()) return null
            if (doc.isReflowable) {
                doc.layout(kThumbnailDx.toFloat() * 2, kThumbnailDy.toFloat() * 2, 11f)
            }
            if (doc.countPages() < 1) return null
            val page = doc.loadPage(0) ?: return null
            try {
                val b = page.bounds
                val pw = b.x1 - b.x0
                val ph = b.y1 - b.y0
                if (pw <= 0f || ph <= 0f) return null
                val zoom = (kThumbnailDx * RENDER_SCALE) / pw
                val ctm = Matrix(zoom, 0f, 0f, zoom, -b.x0 * zoom, -b.y0 * zoom)
                val full = AndroidDrawDevice.drawPage(page, ctm) ?: return null
                val maxDy = kThumbnailDy * RENDER_SCALE
                if (full.height <= maxDy) {
                    full
                } else {
                    val cropped = Bitmap.createBitmap(full, 0, 0, full.width, maxDy)
                    if (cropped !== full) full.recycle()
                    cropped
                }
            } finally {
                try { page.destroy() } catch (_: Throwable) {}
            }
        } catch (t: Throwable) {
            Log.w(TAG, "render '$docPath' failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        } finally {
            try { doc?.destroy() } catch (_: Throwable) {}
        }
    }
}
