# SumatraPDF on Android — 2.0

A native Android port of the SumatraPDF document reader, written in
Kotlin + Jetpack Compose on top of the same MuPDF engine the Windows
build uses. Same package name (`com.sumatrapdf.library`) so existing
intent filters and shortcuts keep working.

The previous Android build in this folder was a partial port of the
fork's *library/shelf* feature, with a minimal MuPDF-backed reader.
This 2.0 release replaces the reader with a full Compose shell that
aims to look and function as close to the Windows app as the form
factor allows: a tabbed document host, a bottom-nav shell, a settings
dialog and a separate advanced settings screen, recents, bookmarks,
favorites, and the same SumatraPDF light/dark themes.

## Build

```
gradlew assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`
(~59 MB; carries the MuPDF native libraries for arm64-v8a,
armeabi-v7a, x86, x86_64).

Requires JDK 17 with `jlink` (this build was developed against
Temurin 17.0.2; the JBR that ships with Android Studio / PyCharm
does not include `jlink` and will fail with a `jlink.exe does not
exist` error during the Java compile step).

## Shell

`MainActivity` is the launcher. It hosts a `NavigationBar` with five
sections that map to the Win32 menu structure:

| Section     | Win32 analog                                       |
|-------------|----------------------------------------------------|
| Library     | Bookshelf / Library wall                           |
| Recents     | File → Recent documents                            |
| Reader      | The document window, with a multi-tab strip on top |
| Favorites   | Favourites sidebar                                 |
| Settings    | Settings + Advanced Settings                       |

The Reader section is the multi-tab document host. Tapping a book in
the Library, a row in Recents, or a favorite opens it in a new tab;
tabs are shown as a horizontal strip at the top of the reader, with
an `×` on each one to close. The bottom of the reader has the page
slider and previous/next buttons; the top app bar holds the
SumatraPDF-style actions (find, go-to-page, layout, zoom, night
mode, rotate, open file, close all tabs).

A left drawer behind the reader is the "Document panel" with three
sections: Contents (the document's outline, parsed from MuPDF),
Bookmarks (per-document page markers), and Favorites (cross-document
pinned pages).

## Document engines

`engine/DocumentEngine.kt` is the common interface the Compose reader
works against. Every engine produces a page count, renders pages to
bitmaps, walks the outline, and answers a page-text query for search.

| Format         | Engine        | Notes                                            |
|----------------|---------------|--------------------------------------------------|
| `.pdf`         | `MupdfEngine` | Real rendering via MuPDF AAR.                    |
| `.epub`        | `MupdfEngine` | Real reflowable rendering via MuPDF.             |
| `.xps` `.oxps` | `MupdfEngine` | Real rendering via MuPDF.                        |
| `.fb2`         | `MupdfEngine` | Real rendering via MuPDF.                        |
| `.cbz` `.cbt`  | `MupdfEngine` | Real rendering via MuPDF.                        |
| `.svg`         | `MupdfEngine` | Real rendering via MuPDF.                        |
| `.tiff`        | `MupdfEngine` | Real rendering via MuPDF.                        |
| `.png` `.jpg`  | `MupdfEngine` | Real rendering via MuPDF.                        |
| `.djvu`        | `StubEngine`  | Not in the standard MuPDF AAR; placeholder page. |
| `.mobi`        | `StubEngine`  | Amazon-proprietary; placeholder page.            |
| `.azw3`        | `StubEngine`  | Amazon-proprietary; placeholder page.            |

The three stubbed formats still get a normal tab, an entry in Recents,
and a clear "this format is not yet supported on Android" page, so the
app never crashes on a `.mobi` and the user can always close the tab
and move on. Adding DjVu / MOBI / AZW3 for real is a multi-week
engineering effort (DjVu needs a separate codec; MOBI / AZW3 need
either a license from Amazon or a clean re-implementation of the
Kindle unpacker).

## What works in this build

- Multi-tab document host with per-tab close (× on each tab, "close
  all" in the top app bar)
- Single-page / Facing / Book layout modes (Win32 has all three;
  Book adds a cover page on the first spread)
- Pinch zoom, pan, double-tap cycle fit-page → fit-width → actual
- Page nav: previous/next, slider, jump-to-page dialog, swipe between
  pages, page memory per document
- Find in document: matches are highlighted, next/previous jumps
  between hits
- Rotation (left/right, 90° steps) with per-document memory
- Night mode per tab; if "invert colors in night mode" is on, the
  rendered bitmap is inverted
- TOC / outline (from MuPDF's `loadOutline`)
- Bookmarks (per-document, stored in SharedPreferences)
- Favorites (cross-document, stored in SharedPreferences)
- Recents (most-recently-opened, stored in SharedPreferences, last
  page remembered per document)
- Library scanner (the existing Kotlin reimplementation of the
  Python `audiobook/library/shelf.py` code; reads storage volumes,
  finds books, generates covers, groups by series, sorts by A–Z,
  Genre, Most, Fewest)
- Settings dialog: theme, default page layout, default zoom, show
  links, keep awake, invert colors in night, show scrollbar, remember
  open docs, show bookshelf on start, on-close behavior, clear
  recents
- Advanced Settings: page spacing, EPUB text size, comic book
  stretch / 2-pages / continuous / CJK / font lists / default font /
  monospace font, custom home path, "Reset to defaults"
- About dialog with version, engine attribution
- System file picker integration (`OpenDocument` launcher) so a PDF
  opened from a file manager lands in the reader
- Content-URI handling (downloaded documents from Drive, etc.)
- The legacy `ReaderActivity` (Views-based single-document reader)
  and `LibraryActivity` are still wired up; they receive the legacy
  `Intent.ACTION_VIEW` and `Intent.ACTION_SEND` filters as
  fallbacks, so anything that hard-coded those activity names keeps
  working

## What's stubbed and what to do next

This is one session's worth of work. The bones are right, but
several pieces of the Win32 reader are still thinner than they
should be:

1. **Pin current page from the reader.** A "★ Favorite" button on
   the bottom bar of the reader is the obvious next step. The
   `FavoritesStore` already exists; the reader just doesn't
   surface it. The `FavoritesScreen` already reads from it.
2. **Bookmark toggle on the bottom bar.** The same story; the
   store exists, the UI doesn't call it from the reader yet.
3. **Continuous / scroll mode.** The Compose `PageSurface` only
   does one page at a time (or two for Facing / Book). Win32's
   continuous mode renders a vertical strip of pages; that wants a
   `LazyColumn` over `SinglePageBitmap`. The
   `cbScrollContinuous` switch is in the advanced settings already.
4. **Annotations.** The legacy `PageView` has highlight /
   underline / strikeout / squiggly / ink / sticky-note. The
   Compose reader doesn't expose them. A future pass wraps
   `PageView` in `AndroidView` and reuses those gesture paths.
5. **Text selection and copy.** Same story; the Win32 reader has a
   marquee selection that the Compose reader doesn't replicate yet.
6. **Print.** `DocumentSession` exposes `canPrint`; the Compose
   shell needs an `ACTION_PRINT` integration with the system print
   framework.
7. **Comic book engine.** The settings have all the comic book
   toggles, but the actual rendering goes through the same MuPDF
   pipeline as everything else. Win32's comic book engine
   (stretch, 2-page, CJK font fallback) is not implemented.
8. **Online metadata.** The Windows app fetches genre metadata from
   OpenLibrary / Google Books. The Compose shell doesn't.
9. **The bookNLP-driven Read Aloud.** The fork adds a
   Chatterbox-powered read-aloud that hands each character to a
   different voice. That's Python + GPU; not portable as-is. The
   README is the right answer for now.
10. **Drag-and-drop tab reorder.** Win32 lets you drag tabs to
    reorder them. The Compose `TabRow` doesn't.

## File map

The new code lives under
`app/src/main/java/com/sumatrapdf/library/`:

```
MainActivity.kt                    Compose shell, bottom nav, intent routing
ui/theme/                          Colors, typography, theme (light + dark)
ui/shell/                          (placeholder; shell lives in MainActivity)
ui/library/LibraryScreen.kt        Compose port of LibraryActivity
ui/recents/RecentsScreen.kt        Recent documents list
ui/reader/ReaderScreen.kt          Multi-tab reader host, top app bar, drawer
ui/reader/PageSurface.kt           One / two page render with zoom + nav
ui/reader/TabsState.kt             Open tabs, page memory, search, rotation
ui/favorites/FavoritesScreen.kt    Favorites list (cross-document)
ui/settings/SettingsScreen.kt      Settings (theme, layout, zoom, switches)
ui/settings/AdvancedSettingsScreen.kt  Advanced Settings (fonts, scrollbar, comic)
engine/DocumentEngine.kt           Common engine interface
engine/MupdfEngine.kt              MuPDF-backed engine for supported formats
engine/StubEngine.kt               Placeholder engine for DjVu / MOBI / AZW3
engine/DocumentFormat.kt           Format enum
engine/DocumentSpec.kt             File / URI -> DocumentSpec
data/SettingsStore.kt              Settings + Recents + Favorites + Bookmarks stores
```

The existing `DocumentSession.kt`, `Library.kt`, `Model.kt`,
`Covers.kt`, `Scanner.kt`, `Catalogue.kt`, `Phrases.kt`, `Genre.kt`,
`Partitions.kt`, `Names.kt`, `LibraryActivity.kt`, `ReaderActivity.kt`,
`PageView.kt`, `ZoomBox.kt`, `PageAdapter.kt`, `OutlineAdapter.kt`,
`Formats.kt`, `PageImage*.kt`, `Reading.kt`, `DocumentSource.kt`
are kept and used by the new shell.
