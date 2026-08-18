package com.sumatrapdf.library.engine

// The set of formats SumatraPDF renders on Windows, plus where each is
// implemented in this build. MuPDF (the official AAR from ghostscript.com)
// handles most of them; MOBI / AZW3 are Amazon-proprietary and DjVu needs a
// separate codec, so those three are stubs.
enum class DocumentFormat(val ext: String, val label: String, val supported: Boolean) {
    PDF("pdf", "PDF", true),
    XPS("xps", "XPS", true),
    OXPS("oxps", "OXPS", true),
    EPUB("epub", "EPUB", true),
    FB2("fb2", "FictionBook", true),
    CBZ("cbz", "Comic Book ZIP", true),
    CBT("cbt", "Comic Book TAR", true),
    SVG("svg", "SVG", true),
    TIFF("tiff", "TIFF", true),
    PNG("png", "PNG image", true),
    JPEG("jpg", "JPEG image", true),
    DJVU("djvu", "DjVu", false),
    MOBI("mobi", "Mobipocket", false),
    AZW3("azw3", "Kindle", false);

    companion object {
        fun fromExtension(name: String): DocumentFormat {
            val dot = name.lastIndexOf('.')
            if (dot < 0) return PDF
            val ext = name.substring(dot + 1).lowercase()
            return entries.firstOrNull { it.ext == ext } ?: PDF
        }
    }
}
