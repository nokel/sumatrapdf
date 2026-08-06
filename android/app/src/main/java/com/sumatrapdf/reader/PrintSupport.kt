package com.sumatrapdf.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.util.Log
import java.io.FileOutputStream

private const val printDpi = 150f

fun startPrintJob(context: Context, engine: DocumentEngine, jobName: String) {
    val manager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
    manager.print(jobName, MupdfPrintAdapter(engine, jobName), null)
}

private class MupdfPrintAdapter(
    private val engine: DocumentEngine,
    private val jobName: String,
) : PrintDocumentAdapter() {

    private val tag = "SumatraPrint"
    private val main = Handler(Looper.getMainLooper())
    private var pageCount = 0

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes?,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }
        pageCount = engine.pageCount
        if (pageCount <= 0) {
            callback.onLayoutFailed("There is no document to print")
            return
        }
        val info = PrintDocumentInfo.Builder(jobName)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(pageCount)
            .build()
        callback.onLayoutFinished(info, oldAttributes != newAttributes)
    }

    override fun onWrite(
        pages: Array<out PageRange>?,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback,
    ) {
        Thread {
            val wanted = expandRanges(pages, pageCount)
            val pdf = PdfDocument()
            var failure: String? = null
            try {
                for ((sheet, pageNo) in wanted.withIndex()) {
                    if (cancellationSignal?.isCanceled == true) {
                        main.post { callback.onWriteCancelled() }
                        pdf.close()
                        return@Thread
                    }
                    val size = engine.pageSize(pageNo)
                    if (size == null) {
                        Log.w(tag, "page $pageNo has no size, skipping")
                        continue
                    }
                    val widthPt = size.first.toInt().coerceAtLeast(1)
                    val heightPt = size.second.toInt().coerceAtLeast(1)
                    val bitmap = engine.renderPage(pageNo, printDpi / 144f, 0)
                    if (bitmap == null) {
                        Log.w(tag, "page $pageNo did not render, skipping")
                        continue
                    }
                    val pdfPage = pdf.startPage(
                        PdfDocument.PageInfo.Builder(widthPt, heightPt, sheet).create(),
                    )
                    val canvas: Canvas = pdfPage.canvas
                    canvas.drawBitmap(
                        bitmap,
                        Rect(0, 0, bitmap.width, bitmap.height),
                        Rect(0, 0, widthPt, heightPt),
                        null,
                    )
                    pdf.finishPage(pdfPage)
                    bitmap.recycle()
                }
                FileOutputStream(destination.fileDescriptor).use { out -> pdf.writeTo(out) }
            } catch (t: Throwable) {
                failure = t.message ?: t.javaClass.simpleName
                Log.e(tag, "print failed", t)
            } finally {
                pdf.close()
            }
            val err = failure
            main.post {
                if (err != null) {
                    callback.onWriteFailed(err)
                } else {
                    callback.onWriteFinished(pages ?: arrayOf(PageRange.ALL_PAGES))
                }
            }
        }.start()
    }
}

private fun expandRanges(pages: Array<out PageRange>?, pageCount: Int): List<Int> {
    if (pages == null || pages.isEmpty()) return (0 until pageCount).toList()
    val out = sortedSetOf<Int>()
    for (range in pages) {
        if (range == PageRange.ALL_PAGES) {
            return (0 until pageCount).toList()
        }
        for (p in range.start..range.end) {
            if (p in 0 until pageCount) out += p
        }
    }
    return out.toList()
}
