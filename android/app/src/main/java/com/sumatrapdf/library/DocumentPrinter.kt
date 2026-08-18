package com.sumatrapdf.library

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.print.pdf.PrintedPdfDocument
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class DocumentPrinter(
    private val context: Context,
    private val session: DocumentSession,
    private val title: String,
) : PrintDocumentAdapter() {

    private var attributes: PrintAttributes? = null

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes?,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback?,
        extras: Bundle?,
    ) {
        attributes = newAttributes
        if (cancellationSignal?.isCanceled == true) {
            callback?.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder(safeName())
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(session.pageCount)
            .build()
        callback?.onLayoutFinished(info, true)
    }

    override fun onWrite(
        pages: Array<out PageRange>?,
        destination: ParcelFileDescriptor?,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback?,
    ) {
        if (destination == null || callback == null) {
            callback?.onWriteFailed("no destination")
            return
        }
        Thread {
            try {
                if (session.isPdf) copyOriginal(destination, callback)
                else drawPages(pages, destination, cancellationSignal, callback)
            } catch (e: Throwable) {
                callback.onWriteFailed(e.message ?: "could not be printed")
            }
        }.start()
    }

    private fun copyOriginal(destination: ParcelFileDescriptor, callback: WriteResultCallback) {
        val where = session.source
        if (where == null) {
            callback.onWriteFailed("no file")
            return
        }
        FileInputStream(File(where.path)).use { input ->
            FileOutputStream(destination.fileDescriptor).use { output ->
                input.copyTo(output)
            }
        }
        callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
    }

    private fun drawPages(
        pages: Array<out PageRange>?,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback,
    ) {
        val settings = attributes
        if (settings == null) {
            callback.onWriteFailed("no page setup")
            return
        }
        val printed = PrintedPdfDocument(context, settings)
        val written = ArrayList<PageRange>()
        try {
            for (index in 0 until session.pageCount) {
                if (cancellationSignal?.isCanceled == true) {
                    printed.close()
                    callback.onWriteCancelled()
                    return
                }
                if (!wanted(pages, index)) continue
                val sheet = printed.startPage(index)
                val area: Rect = sheet.info.contentRect ?: Rect(0, 0, sheet.info.pageWidth, sheet.info.pageHeight)
                val made = session.renderBlocking(index, area.width(), 0, false)
                if (made != null) {
                    val scale = minOf(
                        area.width().toFloat() / made.bitmap.width,
                        area.height().toFloat() / made.bitmap.height,
                    )
                    val drawWidth = made.bitmap.width * scale
                    val drawHeight = made.bitmap.height * scale
                    val left = area.left + (area.width() - drawWidth) / 2f
                    val top = area.top + (area.height() - drawHeight) / 2f
                    val target = android.graphics.RectF(left, top, left + drawWidth, top + drawHeight)
                    sheet.canvas.drawBitmap(made.bitmap, null, target, null)
                }
                printed.finishPage(sheet)
                written.add(PageRange(index, index))
            }
            FileOutputStream(destination.fileDescriptor).use { printed.writeTo(it) }
            callback.onWriteFinished(
                if (written.isEmpty()) arrayOf(PageRange.ALL_PAGES) else written.toTypedArray()
            )
        } finally {
            printed.close()
        }
    }

    private fun wanted(pages: Array<out PageRange>?, index: Int): Boolean {
        if (pages == null || pages.isEmpty()) return true
        for (range in pages) {
            if (range == PageRange.ALL_PAGES) return true
            if (index >= range.start && index <= range.end) return true
        }
        return false
    }

    private fun safeName(): String {
        val cleaned = title.ifBlank { "document" }
        return if (cleaned.length > 60) cleaned.take(60) else cleaned
    }

    companion object {
        fun start(context: Context, session: DocumentSession, title: String) {
            val manager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
            manager.print(title.ifBlank { "document" }, DocumentPrinter(context, session, title), null)
        }
    }
}
