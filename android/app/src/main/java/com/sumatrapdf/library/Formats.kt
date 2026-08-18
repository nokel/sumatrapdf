package com.sumatrapdf.library

val VIEWER_EXTS = listOf(
    ".pdf", ".xps", ".oxps",
    ".epub", ".mobi", ".azw", ".azw3", ".fb2", ".fbz",
    ".cbz", ".cbt", ".tar", ".zip",
    ".txt", ".text", ".html", ".htm", ".xhtml", ".svg", ".md",
    ".png", ".jpg", ".jpeg", ".jfif", ".gif", ".bmp",
    ".tif", ".tiff", ".webp", ".pnm", ".pam", ".jpx", ".jp2",
)

val VIEWER_MIME_TYPES = listOf(
    "application/pdf",
    "application/x-pdf",
    "application/oxps",
    "application/vnd.ms-xpsdocument",
    "application/epub+zip",
    "application/x-mobipocket-ebook",
    "application/x-fictionbook+xml",
    "application/x-cbz",
    "application/vnd.comicbook+zip",
    "text/plain",
    "text/html",
    "image/svg+xml",
    "image/png",
    "image/jpeg",
    "image/gif",
    "image/bmp",
    "image/tiff",
    "image/webp",
)

fun looksViewable(name: String): Boolean {
    val low = name.lowercase()
    return VIEWER_EXTS.any { low.endsWith(it) }
}

fun formatLabel(ext: String): String = when (ext.lowercase().removePrefix(".")) {
    "pdf" -> "PDF"
    "xps", "oxps" -> "XPS"
    "epub" -> "EPUB"
    "mobi", "azw", "azw3" -> "MOBI"
    "fb2", "fbz" -> "FictionBook"
    "cbz", "cbt", "tar", "zip" -> "Comic book archive"
    "txt", "text", "md" -> "Text"
    "html", "htm", "xhtml" -> "HTML"
    "svg" -> "SVG"
    else -> ext.uppercase().removePrefix(".")
}
