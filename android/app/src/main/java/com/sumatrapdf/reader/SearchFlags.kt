package com.sumatrapdf.reader

// Search flags for mupdf's `page.search(needle, style)`.
//
// The 1.28.0 Java binding exposes only the 2-arg
// `Page.search(String, int)` and the 1-arg `Page.search(String)`.
// The int is the `fz_search_options` bitmask, NOT maxHits — a
// common confusion: the JNI layer calls `fz_match_page_cb` under
// the hood, which has a `maxHits`-less callback interface, and
// the int is passed straight through as the `options` flag.
//
// The bit values mirror the constants in the mupdf C API
// (`fz_search_ignore_case`, etc.) and the mupdf Java binding's
// `StructuredText.SEARCH_*` constants:
//
//   0  = EXACT             (case-sensitive, no diacritics, literal)
//   1  = IGNORE_CASE
//   2  = IGNORE_DIACRITICS
//   3  = IGNORE_CASE | IGNORE_DIACRITICS
//   4  = REGEXP
//   8  = KEEP_LINES
//  16  = KEEP_PARAGRAPHS
//  32  = KEEP_HYPHENS
//
// A bug, now fixed: the original Kotlin code passed a hard-coded
// `64` as the second arg, which made the search case-sensitive
// (since bit 6 is FZ_SEARCH_KEEP_HYPHENS, none of the case flags
// were set), so "brotli" never matched the document's "Brotli".
//
// `DocumentEngine.searchPage` accepts an `int style` (the same
// bitmask) so callers can layer flags via `toMask()`; the default
// is `FLAG_IGNORE_CASE` so the search matches the user-visible
// behaviour of the desktop and the 1-arg `page.search(needle)`
// (which calls the 2-arg with `SEARCH_IGNORE_CASE`).
//
// A custom mupdf build could re-expose `maxHits` by wrapping
// `fz_match_page_cb` to stop early, but the shipped 1.28.0 AAR
// does not.
data class SearchFlags(
    val ignoreCase: Boolean = true,
    val ignoreDiacritics: Boolean = false,
    val regex: Boolean = false,
    val keepLines: Boolean = false,
) {
    /** The mupdf bitmask for these flags. */
    fun toMask(): Int {
        var m = 0
        if (ignoreCase) m = m or FLAG_IGNORE_CASE
        if (ignoreDiacritics) m = m or FLAG_IGNORE_DIACRITICS
        if (regex) m = m or FLAG_REGEXP
        if (keepLines) m = m or FLAG_KEEP_LINES
        return m
    }

    companion object {
        const val FLAG_IGNORE_CASE = 1
        const val FLAG_IGNORE_DIACRITICS = 2
        const val FLAG_REGEXP = 4
        const val FLAG_KEEP_LINES = 8
        const val FLAG_KEEP_PARAGRAPHS = 16
        const val FLAG_KEEP_HYPHENS = 32

        /** Default flags — case-insensitive search, no other modifiers. */
        val DEFAULT = SearchFlags()
    }
}
