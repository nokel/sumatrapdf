package com.sumatrapdf.library

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

class DocumentSource(
    val path: String,
    val name: String,
    val key: String,
    val writable: Boolean,
    val origin: String,
) {
    val ext: String
        get() {
            val dot = name.lastIndexOf('.')
            return if (dot >= 0) name.substring(dot).lowercase() else ""
        }

    companion object {
        private const val COPY_DIR = "opened"

        fun ofFile(path: String): DocumentSource {
            val file = File(path)
            return DocumentSource(
                path = file.absolutePath,
                name = file.name,
                key = bookId(file.absolutePath),
                writable = file.canWrite(),
                origin = file.absolutePath,
            )
        }

        fun ofUri(context: Context, uri: Uri): DocumentSource? {
            if (uri.scheme.equals("file", true)) {
                val direct = uri.path ?: return null
                if (File(direct).canRead()) return ofFile(direct)
            }
            val shown = displayName(context, uri) ?: "document"
            val holding = File(context.cacheDir, COPY_DIR).also { it.mkdirs() }
            val stable = bookId(uri.toString())
            val target = File(holding, stable + "-" + safeName(shown))
            if (!target.exists() || target.length() == 0L) {
                val copied = try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                        true
                    } ?: false
                } catch (e: Exception) {
                    false
                }
                if (!copied) {
                    target.delete()
                    return null
                }
            }
            return DocumentSource(
                path = target.absolutePath,
                name = shown,
                key = stable,
                writable = false,
                origin = uri.toString(),
            )
        }

        fun restore(context: Context, origin: String): DocumentSource? {
            if (origin.startsWith("content://")) return ofUri(context, Uri.parse(origin))
            val file = File(origin)
            return if (file.canRead()) ofFile(origin) else null
        }

        private fun displayName(context: Context, uri: Uri): String? {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { row ->
                    val column = row.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (column >= 0 && row.moveToFirst()) {
                        val found = row.getString(column)
                        if (!found.isNullOrBlank()) return found
                    }
                }
            } catch (e: Exception) {
                return uri.lastPathSegment
            }
            return uri.lastPathSegment
        }

        private fun safeName(name: String): String {
            val cleaned = name.map { if (it.isLetterOrDigit() || it == '.' || it == '-') it else '_' }
                .joinToString("")
            return if (cleaned.length > 80) cleaned.takeLast(80) else cleaned
        }
    }
}
