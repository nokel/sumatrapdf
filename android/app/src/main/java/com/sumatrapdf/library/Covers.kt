package com.sumatrapdf.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

object Covers {
    private const val COVER_WIDTH = 320
    private const val COVER_HEIGHT = 480
    private const val COVER_WORKERS = 3

    private val memory = LruCache<String, Bitmap>(48)
    private val work = Executors.newFixedThreadPool(COVER_WORKERS)
    private val main = Handler(Looper.getMainLooper())
    private val pending = HashSet<String>()
    private val missing = HashSet<String>()

    fun cached(id: String): Bitmap? = memory.get(id)

    fun known(id: String): Boolean = synchronized(missing) { id in missing }

    private fun file(id: String) = File(Library.coverDir, "$id.jpg")

    fun request(book: Book, onReady: (Bitmap?) -> Unit) {
        val hit = memory.get(book.id)
        if (hit != null) {
            onReady(hit)
            return
        }
        synchronized(pending) {
            if (!pending.add(book.id)) return
        }
        work.execute {
            val bitmap = load(book)
            if (bitmap != null) memory.put(book.id, bitmap)
            else synchronized(missing) { missing.add(book.id) }
            synchronized(pending) { pending.remove(book.id) }
            main.post { onReady(bitmap) }
        }
    }

    private fun load(book: Book): Bitmap? {
        val onDisk = file(book.id)
        if (onDisk.exists()) {
            val cached = BitmapFactory.decodeFile(onDisk.absolutePath)
            if (cached != null) return cached
            onDisk.delete()
        }
        val made = render(book) ?: return null
        try {
            FileOutputStream(onDisk).use { made.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        } catch (e: Exception) {
            onDisk.delete()
        }
        return made
    }

    private fun render(book: Book): Bitmap? {
        val doc = try {
            Document.openDocument(book.path)
        } catch (e: Throwable) {
            return null
        }
        try {
            if (doc.isReflowable) doc.layout(REFLOW_WIDTH, REFLOW_HEIGHT, REFLOW_EM)
            if (doc.countPages() <= 0) return null
            val page = doc.loadPage(0)
            try {
                return AndroidDrawDevice.drawPageFit(page, COVER_WIDTH, COVER_HEIGHT)
            } finally {
                page.destroy()
            }
        } catch (e: Throwable) {
            return null
        } finally {
            doc.destroy()
        }
    }

    fun forget() {
        memory.evictAll()
        synchronized(missing) { missing.clear() }
    }
}
