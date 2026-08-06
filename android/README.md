# SumatraPDF for Android

A native Android port of the SumatraPDF document reader, following the
same port pattern `src/mac/` uses for macOS: a plain-C bridge interface
(`SumatraAndroidEngine.h`) that exposes the engine to a platform-native UI.
Today the engine is the same MuPDF the Win32 app uses, exposed via the
official AAR; the Sumatra engine (the C++ layer that adds DjVu, image
dirs, comic book, etc.) slots in here by re-implementing the bridge .cpp
against `EngineAll.h` when its source is ported to NDK.

## What this build does

Opens PDF / EPUB / XPS / FB2 / CBZ files and reads them. The UI mirrors
the **Win32 SumatraPDF look**: a tab row with the menu bar's hamburger on
its left, the Win32 toolbar beneath it, the page area, and a status bar
with a page slider at the bottom.

Working today:

- **Start page** — with no document open (and on the Home tab) the canvas
  is the recently-read page from `src/HomePage.cpp`: a thumbnail grid or a
  list of every document you have opened, ordered by recency or by open
  count, with pin, forget, a name filter and a long-press context menu.
- **Reading** — Single / Facing / Book layout, continuous or paged, fit
  page / width / height / content / custom zoom, pinch-zoom and pan when
  zoomed in, rotation, edge-tap page turns.
- **Reflowable formats** — EPUB/FB2/MOBI are repaginated to the device's
  page box on open, so the page count reflects the actual screen.
- **Table of contents** — sidebar with Contents and Bookmarks tabs,
  tap a heading to jump. Chapter page numbers are resolved to absolute
  document pages, which matters for every chaptered format.
- **Find** — whole-document search with a hit counter and next/previous.
- **Links** — internal links jump, external links open in the browser.
  Link targets are shaded so they are visible on a touch screen.
- **Text selection** — long-press snaps to words via mupdf's
  `snapSelection`, then Copy / Share / Search / Translate.
- **Read Aloud** — the platform `TextToSpeech` engine reads from the
  current page onward, advancing pages as it goes, with voice selection.
- **Print** — via Android's `PrintManager`, rendering pages through mupdf.
- **Properties** — file, format, metadata, page size, permissions.
- **Password-protected documents** — prompts, re-prompts on a wrong
  password, and remembers the password so you are only asked once. It is
  sealed with an Android Keystore key, not written into the settings.
- **Session restore** — the tabs that were open last time come back on
  launch, on the page, zoom, layout and sidebar state each was left in.
  For EPUB and other reflowable formats the position is stored as a mupdf
  bookmark, so it survives the repagination that a fold, unfold or
  rotation causes.
- **Night mode** — dark chrome plus an inverted page.
- **Fullscreen / presentation**, **Save a copy** and **Share** via the
  storage access framework, **open next/previous file in folder**,
  **recent files**, **bookmarks**, per-file session restore, and
  back/forward page history.

## Build

```
cd android
gradlew assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`
(~45 MB, with `libmupdf_java.so` for arm64-v8a, armeabi-v7a, x86_64).

Requires JDK 17, the Android SDK with platform-35 + build-tools 35.0.0,
and NDK 26.1.10909125. The `mupdf` AAR is pulled from
`https://maven.ghostscript.com`.

Unit tests (pure logic — sentence splitting for Read Aloud, the web
lookup URLs, MIME mapping, folder navigation, the two start-page
orderings, the natural sort, the start-page filter):

```
gradlew testDebugUnitTest
```

## What's in here

```
sumatra-android/
  src/
    android/
      SumatraAndroidEngine.h    # plain C bridge interface (mirrors
                                 # src/mac/SumatraMacEngine.h)
  android/
    app/
      src/test/java/com/sumatrapdf/reader/
        ReaderLogicTest.kt       # JVM unit tests for the pure logic
      src/main/
        AndroidManifest.xml      # VIEW/SEND intents, FileProvider
        res/values/              # neutral grey theme; no Material blue
        res/values-night/        # dark chrome
        res/mipmap-*/            # adaptive launcher icon (the Sumatra logo)
        java/com/sumatrapdf/reader/
          MainActivity.kt        # launcher, file picker, save-a-copy,
                                 # immersive mode, TTS lifecycle
          StartPage.kt           # the recently-read page (HomePage.cpp)
          FileHistory.kt         # document history + both orderings
                                 # (FileHistory.cpp), natural sort, filter,
                                 # per-document FileState, SessionData
          SecretStore.kt         # Keystore-sealed document passwords
          Thumbnails.kt          # page-1 thumbnail render + PNG cache
                                 # (FileThumbnails.cpp)
          ReaderScreen.kt        # the whole screen, wired together
          PageSurface.kt         # page rendering, pan/zoom, link and
                                 # selection overlays, tap routing
          DocumentEngine.kt      # multi-document mupdf wrapper: open,
                                 # render, outline, search, links,
                                 # selection, metadata, reflow
          DocumentActions.kt     # clipboard, share, browser, folder walk
          ReadAloud.kt           # platform TextToSpeech reader
          PrintSupport.kt        # PrintDocumentAdapter over mupdf
          SumatraToolbar.kt      # the Win32 toolbar
          SumatraStatusBar.kt    # page slider + zoom/rotation/page count
          TabsRow.kt             # tab cards + the hamburger
          HamburgerMenu.kt       # the Win32 menu bar as a cascading popup
          MenuTypes.kt           # DisplayMode/ZoomLevel/MenuSection/MenuAction
          ToCSidebar.kt          # Contents + Bookmarks sidebar
          SearchBar.kt           # the find toolbar
          Settings.kt            # SharedPreferences: recents, bookmarks,
                                 # per-file session, view preferences
          SumTheme.kt            # Win32-style light and dark schemes
          MenuBar.kt             # unused: an earlier horizontal menu bar,
                                 # superseded by HamburgerMenu.kt
```

## Why this looks like the Win32 app

`SumatraToolbar.kt` follows `src/Toolbar.cpp::gToolbarButtons` — the same
items in the same order, with the checkable layout buttons showing the
same pressed state, and the page-status box in the middle opening "Go to
page". `HamburgerMenu.kt` is a direct port of
`src/Menu.cpp::menuDefMenubar`, with each section's items popping out to
the right like a Win32 cascading menu. `SumTheme.kt` deliberately uses
neutral greys (`#F2F2F2` bar, `#EDEDED` background, `#1A1A1A` text,
`#2C5AA0` accent) rather than Material 3 defaults, so the result looks
like a small Win32 app, not a Material app.

Where Win32 behaviour and touch conflict, Win32 wins on naming: View ▸
"Show Bookmarks" is `CmdToggleBookmarks`, which in the Windows app calls
`ToggleTocBox` — so it toggles the table-of-contents sidebar, not the
favourites list. Favourites are under Favorites ▸ Show Favorites.

## What's still missing

1. **The Sumatra engine layer (`EngineAll.h`, `EngineMupdf.h`, etc.).**
   The bridge is structured so a port of `src/EngineAll.cpp` to NDK can
   replace the mupdf-direct implementation. The C interface in
   `SumatraAndroidEngine.h` matches `SumatraMacEngine.h` 1:1, and nothing
   implements it yet — the Kotlin `DocumentEngine` talks to mupdf's Java
   bindings directly.
2. **Annotations** — highlight, underline, strikeout, ink and notes are
   not implemented. mupdf's `PDFAnnotation` is available in the AAR.
3. **Link and selection hit-testing are disabled on a rotated page.**
   Rotation is applied inside mupdf's render matrix, and the screen →
   page mapping in `PageSurface.kt` does not model it, so rather than
   return a quietly wrong coordinate it returns none. Taps still turn
   pages; only links and selection are affected.
4. **The library start page** — the poster wall, scanner, series
   grouping and BookNLP wiki tabs from the desktop `library` branch.
   That code is `src/LibraryPage.cpp` talking to a local Python service;
   on Android it needs rewriting in Kotlin.
5. **Chatterbox read-aloud** is a Python + GPU engine and is not portable
   to the phone as-is. Read Aloud here uses the platform TTS, which is
   the Android equivalent of the Windows SAPI path.
6. **Still stubs**: command palette, manga mode (toggles a flag only),
   save annotations, rename, delete, tab groups, check for updates,
   render/cache info. The AI translation backends (Grok / Claude Code /
   OpenAI Codex) are desktop CLIs and report as unavailable.

## Local-from-source mupdf

The repo's own mupdf source at `mupdf/` is what Sumatra wraps on
Windows. To build the Android native lib from the local source instead
of pulling the AAR:

1. Build `mupdf_java.so` with `ndk-build` using
   `mupdf/platform/java/Android.mk`. See `mupdf/platform/java/Makefile`
   for the wrapper.
2. Drop the produced `libmupdf_java.so` into
   `android/app/src/main/jniLibs/<abi>/`.
3. Copy the Java bindings from
   `mupdf/platform/java/src/com/artifex/mupdf/fitz/` into
   `android/app/src/main/java/com/artifex/mupdf/fitz/`.
4. Build with `./gradlew assembleDebug -PmupdfLocal=true`, which omits
   the AAR dependency so the `.so` under `jniLibs` and the copied
   bindings are what the build uses. Steps 2 and 3 are manual — the
   build script only drops the dependency, it does not fetch or wire
   anything for you.

This is the path to a fully-from-source Android build; the AAR exists
to keep the build simple in the meantime.
