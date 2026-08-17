package com.sumatrapdf.library.engine

import android.content.Context
import android.net.Uri
import com.sumatrapdf.library.DocumentSource
import java.io.File

// A DocumentSpec is the small, UI-friendly handle the Compose layer works
// with. It captures the file (or content uri) we want to open, the format
// we believe it to be, and the stable id we use for "did we open this
// before?" lookups. It is the value passed to the engine factory.
data class DocumentSpec(
    val path: String,
    val name: String,
    val id: String,
    val origin: String,
    val format: DocumentFormat,
    val isContentUri: Boolean,
) {
    companion object {
        fun fromFile(path: String): DocumentSpec {
            val file = File(path)
            val name = file.name
            val id = stableId(file.absolutePath)
            return DocumentSpec(
                path = file.absolutePath,
                name = name,
                id = id,
                origin = file.absolutePath,
                format = DocumentFormat.fromExtension(name),
                isContentUri = false,
            )
        }

        fun fromUri(context: Context, uri: Uri): DocumentSpec? {
            val name = displayName(context, uri) ?: uri.lastPathSegment ?: return null
            val id = stableId(uri.toString())
            return DocumentSpec(
                path = uri.toString(),
                name = name,
                id = id,
                origin = uri.toString(),
                format = DocumentFormat.fromExtension(name),
                isContentUri = true,
            )
        }

        fun fromSource(source: DocumentSource): DocumentSpec =
            DocumentSpec(
                path = source.path,
                name = source.name,
                id = source.key,
                origin = source.origin,
                format = DocumentFormat.fromExtension(source.name),
                isContentUri = source.origin.startsWith("content://"),
            )

        private fun displayName(context: Context, uri: Uri): String? {
            return try {
                context.contentResolver.query(uri, null, null, null, null)?.use { row ->
                    val idx = row.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && row.moveToFirst()) {
                        val v = row.getString(idx)
                        if (!v.isNullOrBlank()) v else null
                    } else null
                }
            } catch (_: Exception) { null }
        }

        private fun stableId(s: String): String {
            val md = java.security.MessageDigest.getInstance("SHA-1")
            val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
