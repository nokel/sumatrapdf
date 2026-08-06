package com.sumatrapdf.reader

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.net.URLEncoder
import java.util.Locale

private const val tag = "SumatraActions"

const val kSumatraWebsite = "https://www.sumatrapdfreader.org/"
const val kSumatraManual = "https://www.sumatrapdfreader.org/docs/SumatraPDF-documentation"
const val kSumatraShortcuts = "https://www.sumatrapdfreader.org/docs/Keyboard-shortcuts"

enum class WebLookup { Google, Bing, Wikipedia, GoogleScholar, GoogleTranslate, DeepL }

fun lookupUrl(kind: WebLookup, selection: String): String {
    val q = URLEncoder.encode(selection, "UTF-8")
    val target = Locale.getDefault().language.ifBlank { "en" }
    return when (kind) {
        WebLookup.Google -> "https://www.google.com/search?q=$q"
        WebLookup.Bing -> "https://www.bing.com/search?q=$q"
        WebLookup.Wikipedia -> "https://wikipedia.org/w/index.php?search=$q"
        WebLookup.GoogleScholar -> "https://scholar.google.com/scholar?q=$q"
        WebLookup.GoogleTranslate ->
            "https://translate.google.com/?op=translate&sl=auto&tl=$target&text=$q"
        WebLookup.DeepL -> "https://www.deepl.com/translator#auto/$target/$q"
    }
}

fun openInBrowser(context: Context, url: String): Boolean {
    return try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (t: Throwable) {
        Log.w(tag, "openInBrowser($url) failed: ${t.message}", t)
        false
    }
}

fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

fun shareText(context: Context, text: String): Boolean {
    return try {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(
            Intent.createChooser(send, "Share text").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (t: Throwable) {
        Log.w(tag, "shareText failed: ${t.message}", t)
        false
    }
}

fun contentUriForDocument(context: Context, path: String): Uri? = try {
    FileProvider.getUriForFile(context, "${context.packageName}.files", File(path))
} catch (t: Throwable) {
    Log.w(tag, "FileProvider refused $path: ${t.message}", t)
    null
}

fun shareDocument(context: Context, path: String): Boolean {
    val uri = contentUriForDocument(context, path) ?: return false
    return try {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeTypeFor(path)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, "Share document").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (t: Throwable) {
        Log.w(tag, "shareDocument failed: ${t.message}", t)
        false
    }
}

fun revealDocument(context: Context, path: String): Boolean {
    val uri = contentUriForDocument(context, path) ?: return false
    return try {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "resource/folder")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(view)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (t: Throwable) {
        Log.w(tag, "revealDocument failed: ${t.message}", t)
        false
    }
}

val kDocumentExtensions = setOf(
    "pdf", "epub", "xps", "oxps", "fb2", "cbz", "cbr", "mobi", "azw", "azw3", "txt", "svg",
)

fun mimeTypeFor(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
    "pdf" -> "application/pdf"
    "epub" -> "application/epub+zip"
    "xps", "oxps" -> "application/oxps"
    "fb2" -> "application/x-fictionbook+xml"
    "cbz" -> "application/vnd.comicbook+zip"
    "mobi", "azw", "azw3" -> "application/x-mobipocket-ebook"
    else -> "application/octet-stream"
}

fun siblingDocuments(path: String): List<String> {
    val parent = File(path).parentFile ?: return emptyList()
    val siblings = parent.listFiles() ?: return emptyList()
    return siblings
        .filter { it.isFile && it.extension.lowercase() in kDocumentExtensions }
        .map { it.absolutePath }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.substringAfterLast('/') })
}

fun neighbourDocument(path: String, step: Int): String? {
    val all = siblingDocuments(path)
    if (all.isEmpty()) return null
    val here = all.indexOf(File(path).absolutePath)
    if (here < 0) return null
    val next = here + step
    if (next !in all.indices) return null
    return all[next]
}
