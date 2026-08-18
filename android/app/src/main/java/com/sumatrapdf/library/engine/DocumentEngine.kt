package com.sumatrapdf.library.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF

// The engine interface the Compose reader works against. Every engine
// (MuPDF for PDF/EPUB/XPS, the stub for DjVu/MOBI/AZW3) must produce a
// page count, render pages to bitmaps, walk the outline, and answer a
// page-text query for search. Engines are created by DocumentEngineFactory
// and live for the duration of one open tab.
interface DocumentEngine {
    val spec: DocumentSpec
    val pageCount: Int
    val isReflowable: Boolean
    val isPdf: Boolean
    val isStub: Boolean

    fun open(onReady: () -> Unit, onPassword: () -> Unit, onFailed: (String) -> Unit)
    fun close()
    fun outline(then: (List<DocumentOutlineEntry>) -> Unit)
    fun pageText(index: Int, then: (String) -> Unit)
    fun searchPage(index: Int, needle: String, then: (List<RectF>) -> Unit)
    fun render(index: Int, targetWidth: Int, night: Boolean, then: (Bitmap?) -> Unit)
    fun pageShape(index: Int, then: (Pair<Float, Float>?) -> Unit)
    fun setReflow(widthPt: Float, heightPt: Float, em: Float)
}

data class DocumentOutlineEntry(val title: String, val page: Int, val depth: Int)

object DocumentEngineFactory {
    private const val COPY_DIR = "engine-opened"

    fun forSpec(context: Context, spec: DocumentSpec): DocumentEngine {
        val resolvedPath = if (spec.isContentUri) {
            materializeUri(context, spec) ?: spec.path
        } else spec.path
        val effective = spec.copy(path = resolvedPath)
        return if (effective.format.supported) MupdfEngine(effective) else StubEngine(effective)
    }

    // MuPDF wants a real file path. We copy content:// URIs into the app's
    // private cache so the engine can mmap them. The copy survives across
    // app launches keyed by the URI's id, so re-opening is instant.
    private fun materializeUri(context: Context, spec: DocumentSpec): String? {
        return try {
            val uri = android.net.Uri.parse(spec.path)
            val holding = java.io.File(context.cacheDir, COPY_DIR).also { it.mkdirs() }
            val target = java.io.File(holding, spec.id + "-" + safeName(spec.name))
            if (!target.exists() || target.length() == 0L) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: return null
            }
            target.absolutePath
        } catch (_: Exception) { null }
    }

    private fun safeName(name: String): String {
        val cleaned = name.map {
            if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_'
        }.joinToString("")
        return if (cleaned.length > 80) cleaned.takeLast(80) else cleaned
    }
}
