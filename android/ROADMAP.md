# Android port roadmap

This is the single source of truth for what is left of the Windows-to-Android
port. **The Windows app in this same tree is the spec** — when a Win32
subsystem is described, that is what has to ship on Android, in shape and
behaviour, not a "similar-looking" alternative. Every line of `src/` is a
target; every `.cpp`/`.h` is a porting obligation. Status is the state on
the **`library` branch** of the SumatraPDF fork at
`C:\Users\Nokel\Documents\AI_crap\chatterbox-AI\sumatra-android\`.

**The whole point of this fork is to have a full, end-to-end tested Android
build of SumatraPDF.** Nothing on this page is "done" until it runs on the
Galaxy Z Fold 4 (RFCT80H8FRJ), the user has touched it, and `gradlew
testDebugUnitTest` still passes.

> **Toggle rule.** Anything that is built specifically to be exercised in
> tests or via adb — uiautomator hooks, debug overlay tiles, `CmdDebug…`
> branches, extra logcat, `screencap` drivers, the `-for-testing` launcher
> shim — must live behind a single boolean in `Settings` (or, if it requires
> a build flag, behind one). The default is **off** for users, **on** when
> the agent is verifying. The point of the rule is that the test harness
> cannot leak into the production build and the production build cannot
> leak a debug back-door into the user's data.

---

## Headline numbers

| metric | Win32 | Android now | Android target |
|---|---:|---:|---:|
| `Cmd*` commands in `src/Commands.h` | **271** | **80** | 271 |
| `MenuDef` definitions in `src/Menu.cpp` | **21** | **10** (hamburger sections) + 0 context menus | 21 |
| `src/*.cpp` lines | **175 980** | 47 MB APK (Kotlin + AAR) | n/a |
| Files ported to Kotlin | 0 | **38** (`*.kt` in `android/app/src/main/`) | 50+ |
| Test classes | n/a | **10** | growing with each port |
| Unit tests | n/a | **50** (16 are oracle tests against the Python) | growing |
| `aar` for engine | bundled | `com.artifex.mupdf:fitz` | swap to NDK build of `mupdf/` |
| minSdk | n/a | **26** (Android 8.0) | n/a |
| targetSdk | n/a | 35 (Android 15) | n/a |
| Verified Android versions | n/a | **11** (Note 20 Ultra) + **13** (Z Fold 4) | n/a |

The 80-of-271 figure is from `MenuAction` values in
`MenuTypes.kt`. Win32 commands that exist purely on the Windows shell
(DDE, external viewers, the Windows previewer, `CmdListPrinters`,
`CmdSelectionHandler`, AI chat/translate CLIs) drop to zero by design —
they are listed in "Not planned" below. The other ~190 are real work.

The same APK runs on **Android 8.0 → Android 15** without
per-device build variants. `minSdk = 26`, `targetSdk = 35`. The
only two `Build.VERSION.SDK_INT` checks in the code
(`VibratorManager` on API 31+ and
`Environment.isExternalStorageManager()` on API 30+) both have
proper else branches. Verified on a Samsung Galaxy Z Fold 4
(Android 13) and a Samsung Galaxy Note 20 Ultra (Android 11) with
the same APK; no code changes were needed for the Android 11
port.

---

## Status by file

This is the file-level porting obligation. A Win32 file is one of:

- **ported** — its user-facing behaviour is reproduced in Kotlin and was
  verified on the device;
- **partial** — some commands or screens work, others throw a `TODO` or
  silently no-op;
- **absent** — not in the Android tree at all, the user sees nothing.

The source line counts are from `git show HEAD:src/<file>` and are
included so the size of each porting target is obvious.

### Library, history, settings — **partial**

| Win32 file | LoC | Android | status | gap |
|---|---:|---|---|---|
| `src/HomePage.cpp` | 1847 | `StartPage.kt` (477 LoC) + `FileHistory.kt` (474) + `Thumbnails.kt` | partial | tips/promotions band dropped; about panel stub; no `CmdRemoveDeletedFilesFromHistory` / `CmdClearHistory` / `CmdReopenLastClosedFile`. See PORTING-STATUS §0.1. |
| `src/FileHistory.cpp` | (in `HomePage.cpp`) | `FileHistory.kt` | partial | recents + pinned + forgotten, two orderings, MD5-keyed cache. |
| `src/FileThumbnails.cpp` | (in `HomePage.cpp`) | `Thumbnails.kt` | partial | page-1 PNG by MD5, in-memory `LruCache`. |
| `src/LibraryPage.cpp` | 2719 | `library/LibraryPage.kt` (954) + 15 sibling files | partial | library feature done (oracle tests pass on real 187-book corpus). Tabs, cover wall, 6 detail tabs all work. **Missing**: `CmdToggleLibraryHome`, `CmdLibraryRescan`, the 17 link verbs, library tile long-press menu. See PORTING-STATUS §0.3. |
| `src/Settings.h` | 1461 | `Settings.kt` (140 LoC) | **absent** | 7 SharedPreferences keys. Desktop has 27 structs, 87 fields. The whole settings editor is gone. |
| `src/AdvancedSettingsDialog.cpp` | 922 | — | absent | no GUI editor for any setting. |
| `src/AppSettings.cpp` | 848 | — | absent | read-only load on launch, no save, no migrate. |

### Engine and formats — **partial**

| Win32 file | LoC | Android | status | gap |
|---|---:|---|---|---|
| `src/EngineMupdf.cpp` | 5549 | `DocumentEngine.kt` (per-DocSlot `ReentrantLock`) | partial | mupdf AAR only. `DocumentEngine` wraps `com.artifex.mupdf.fitz.*`. EPUB / XPS / FB2 / CBZ / SVG / image formats the AAR handles work. |
| `src/EngineImages.cpp` | 2133 | — | absent | image-dir engine (zip/cb7/cbr/ora) is not in the AAR; manifest only advertises 5 MIME types. |
| `src/EngineDjvuDec.cpp` | 913 | — | absent | DjVu not in the AAR. |
| `src/EngineEbook.cpp` | 1674 | — | absent | EPUB/FB2/MOBI go through mupdf only. |
| `src/MobiDoc.cpp` | 991 | — | absent | same. |
| `src/ChmModel.cpp` + `src/ChmFile.cpp` | 1681 | — | absent | CHM unsupported. |
| `src/MarkdownModel.cpp` | 749 | — | absent | Markdown unsupported. |

### UI shell — **absent**

The Win32 UI is 50+ files of `wingui/` and per-window subclasses. The
Android port re-implements the parts the user touches in Compose, **not**
by wrapping Win32. The list below is what the Kotlin reader **does not
yet have** at all, and is the visible gap when a Windows user picks up
the phone.

| Win32 file | LoC | Android | status | gap |
|---|---:|---|---|---|
| `src/SumatraPDF.cpp` | 11002 | scattered across `MainActivity`, `ReaderScreen`, `SumatraToolbar`, `TabsRow`, `HamburgerMenu` | partial | app lifecycle, tab model, command dispatch, presentation mode, fullscreen, settings migration. |
| `src/Canvas.cpp` | 3888 | `PageSurface.kt` | partial | page render, zoom, pan, pinch, click-edge, find highlight. Continuous mode is virtualised with `LazyColumn` (§3.6) and backed by a byte-budgeted render cache + prefetch (§3.7). Links/selection are dead on rotated pages (§3.4). |
| `src/RenderCache.cpp` | 1416 | `PageSurface.kt` (`renderCache`, `PageTileOverlay`), `DocumentEngine.renderPageTile` | partial | byte-budgeted LRU keyed by `(doc, page, scale, rotation, tile)`; visible-region tiles at true zoom over a stretched low-res base. No quadtree tile resolutions, no background render queue or priorities. |
| `src/DisplayModel.cpp` | 1962 | inline in `PageSurface.kt` | partial | `Relayout()`, `CanScrollLeft/Right`, `GetScrollState` re-implemented; `displayR2L` (manga mode) now drives layout direction in Facing/BookView (PORTING-STATUS §3.2). |
| `src/Toolbar.cpp` | 1785 | `SumatraToolbar.kt` (220 LoC) | partial | toolbar button order matches `gToolbarButtons` exactly. The **toolbar** is done; what's missing is the **status bar** (Win32 has none) and the **tab bar**. |
| `src/Tabs.cpp` | 750 | `TabsRow.kt` | partial | open/close/switch works. **Missing**: `CmdCloseOtherTabs`, `CmdCloseTabsToTheRight/Left`, `CmdCloseAllTabs`, `CmdNextTab/PrevTab/NextTabSmart/PrevTabSmart`, `CmdMoveTabLeft/Right`, `CmdDuplicateInNewTab`, `CmdSetTabColor`, `CmdTabGroupSave/Restore` (PORTING-STATUS §2.8). |
| `src/Menu.cpp` | 2421 | `HamburgerMenu.kt` + `MenuTypes.kt` | partial | all 10 sections from `menuDefMenubar` are in the hamburger. **Missing**: every long-press context menu (`menuDefContext`, `menuDefDocumentOperations`, `menuDefCreateAnnotUnderCursor`, `menuDefSelection`, `menuDefZoomShort`, `menuDefContextImage`, `menuDefCreateAnnotFromSelection`, `menuDefContextStart`, `menuDefTabGroups`, `menuDefContextReadAloud`, `menuDefDocumentAIChat` — 11 definitions, ~70 commands, PORTING-STATUS §1.4). |
| `src/Accelerators.cpp` | 728 | `KeyboardShortcuts.kt` (345 LoC) | done | 82 commands bound to keys, mirrored as `KeyboardShortcuts.BINDINGS`. Routed through `MainActivity.dispatchKeyEvent` → `pendingShortcut` → Compose `LaunchedEffect`. The search-field pass-through and the DeX-keyboard `keyCodeToChar` map share the same files — see `docs/dex-search-field-issue.md`. |
| `src/CommandPalette.cpp` | 547 | — | absent | `CmdCommandPalette`, `CmdCommandPaletteTOC`, `CmdCommandPaletteFavorites` (PORTING-STATUS §1.6). |
| `src/TableOfContents.cpp` | 1498 | `ToCSidebar.kt` (150 LoC) | partial | tree view, click-to-jump, Contents/Bookmarks tabs. **Missing**: `CmdExpandAll`, `CmdCollapseAll`, `CmdExpandToCurrentPage`, `CmdTocExpandToLevel1/2/3`, `CmdTocCollapseSameLevel`, no persisted `tocState`/`showToc`/`sidebarDx`. Flattened — no collapse state. |
| `src/Favorites.cpp` | 1365 | — | partial | flat SharedPreferences list, dialog only. No panel, no next/prev, no sort, no page labels. |
| `src/FindWindow.cpp` + `src/TextSearch.cpp` | 1614 | `SearchBar.kt` | partial | find toolbar with hit counter, prev/next, close, yellow highlight overlay. **Missing**: `CmdFindToggleMatchCase/WholeWord`, `CmdFindNextSel/FindPrevSel`, no `SEARCH_IGNORE_DIACRITICS`/`SEARCH_REGEXP`/`SEARCH_KEEP_LINES` flags, no progress, no cancel, synchronous over all pages. |
| `src/Selection.cpp` + `src/TextSelection.cpp` | 1359 | inline in `PageSurface.kt` | partial | word-snap on a single page. **Missing**: drag handles, cross-page, area/rectangular selection, `CmdCopyImage`/`CopyLinkTarget`/`CopyComment`/`CopyFilePath`. |
| `src/Print.cpp` | 1887 | `PrintSupport.kt` (124 LoC) | partial | Android `PrintManager` at 150 dpi bitmap. **Missing**: page ranges, odd/even, scaling, print-as-image, duplex, copies, `PrinterDefaults`. |
| `src/TextToSpeech.cpp` | 1083 | `ReadAloud.kt` (190 LoC) | partial | Android TTS, page-by-page, sentence-aware. **Missing**: the **entire highlight subsystem** (`ReadAloudHighlight.cpp`, 726 LoC, §2.5) — the desktop highlights the words as it reads them, the port does not. No `CmdReadAloudSelection`, no `CmdTtsSpeed*`, no Read From Cursor. |
| `src/ReadAloudHighlight.cpp` | 726 | — | absent | pairs with `ReadAloud.kt`. |
| `src/Notifications.cpp` | 757 | snackbars (in `ReaderScreen.kt`) | partial | no persistent or progress notifications. |
| `src/Theme.cpp` | 837 | `SumTheme.kt` (80 LoC) | partial | one neutral grey theme. **Missing**: `CmdSetTheme`/`ChangeTheme`/`ToggleLightDark`, `CmdChangeBackgroundColor`, `CmdSetDocumentColorsFollowTheme`, `CmdInvertColors`, `CmdToggleEngineeringDrawingEnhance`, `CmdTogglePreservePdfImages`, `struct Theme`/`Themes`. |
| `src/OverlayScrollbar.cpp` | 891 | inline in `PageSurface.kt` | partial | drawn bars only, no `CmdChangeScrollbar`. |

### Document-level tools — **absent**

| Win32 file | LoC | Android | gap |
|---|---:|---|---|
| `src/Annotation.cpp` + `src/EditAnnotations.cpp` | 3156 | — | **27 commands** for the 19 annotation types, plus edit/save/discard. PORTING-STATUS §1.3. |
| `src/ImageSaveCropResize.cpp` | 2074 | — | `CmdCopyImage/Crop/Resize/Save/PasteClipboardImage/ConvertImageToPdf`. PORTING-STATUS §1.7. |
| `src/Screenshot.cpp` | 1516 | — | `CmdScreenshot` + `CmdSetScreenshotHotkey`. |
| `src/PdfTools.cpp` | 1637 | — | `CmdPdfCompress/Decompress/DeletePages/ExtractPages/Encrypt/Decrypt/Bake`, `CmdPdShowInfo`, `CmdDocumentExtractText`, `CmdDocumentShowOutline`. PORTING-STATUS §1.8. |
| `src/SelectionTranslate.cpp` | 1232 | `DocumentActions.kt` (Google + DeepL only) | Grok / Claude Code / OpenAI Codex are CLI on the desktop. |
| `src/SumatraProperties.cpp` | 902 | `Properties` dialog in `ReaderScreen.kt` | subset, no font list, no PDF internals. |
| `src/AppTools.cpp` | 606 | `DocumentActions.kt` (reveal-in-folder, share, open-with) | mostly ported. |
| `src/ExternalViewers.cpp` | 611 | — | Windows-only, correctly dropped. |
| `src/RefHoverDetect.cpp` + `src/RefHoverTextDetect.cpp` + `src/RefHoverCanvas.cpp` + `src/RefHoverPopup.cpp` + `src/RefHoverInternal.cpp` + `src/RefHoverRender.cpp` + `src/RefHoverText.cpp` + `src/RefHoverShow.cpp` | ~5000 | — | citation-preview popups. PORTING-STATUS §1.10. |
| `src/AIChatPanel.cpp` + `src/AIChatCommon.cpp` + `src/CodexBuild.cpp` + `src/ClaudeCode.cpp` + `src/GrokBuild.cpp` | ~3000 | — | CLI-backed on desktop, correctly dropped. |
| `src/PdfSync.cpp` | 829 | — | SyncTeX, cross-platform in principle. |
| `src/StressTesting.cpp` + `src/SumatraTest.cpp` + `src/Tests.cpp` + `src/SumatraUnitTests.cpp` + `src/AppUnitTests.cpp` | ~3500 | `gradlew testDebugUnitTest` | the port's regression net, growing. |
| `src/CrashHandler.cpp` | 906 | — | no crash reporting on Android. |
| `src/UpdateCheck.cpp` | 738 | — | — |
| `src/MainWindow.cpp` | 903 | `MainActivity.kt` | activity, intent routing, singleTop, `filePath` extra. |

### Not planned (Windows-only)

These are kept on the Windows side, **deliberately** dropped on Android:

- `src/regress/` — 3046 LoC of regression tests
- `src/Installer.cpp` + `src/InstallerCommon.cpp` + `src/Uninstaller.cpp` + `src/RegistryInstaller.cpp` + `src/RegistryPreview.cpp` + `src/RegistrySearchFilter.cpp`
- `src/SumatraControl.cpp` + `src/SumatraConfig.cpp`
- `src/PdfDarkMode*.cpp` (8 files)
- `src/PdfCadEnhanceDevice.cpp` + `src/PdfCadDetect.cpp`
- `src/wingui/` (40+ files) — the Win32 GUI primitives are replaced by Compose
- `src/ExternalViewers.cpp`
- `src/AvifReader.cpp`, `src/JxlReader.cpp`, `src/WebpReader.cpp` — AAR handles these
- `src/PalmDbReader.cpp`, `src/EnginePs.cpp`, `src/EngineDump.cpp`
- `src/CrashHandler.cpp`
- AI chat/translate CLIs
- DDE / `CmdExec`, `CmdSelectionHandler`, `CmdListPrinters`, prerelease update
- All `aar`/`adb` UI automation back-doors not behind a `Settings` toggle

---

## Phase plan

Phases are ordered so that each one unblocks the next. Phases 1–3 are
the ones that make the app **feel** like SumatraPDF rather than a
generic viewer. Phases 4–5 fill in the long tail.

### Phase 1 — foundations that everything else hangs off

These are the small frameworks that the rest of the port uses. Without
them, every new screen is bespoke.

1. **Long-press context menu framework** — the router that, given a
   context, picks a `MenuDef` and shows a cascading popup. PORTING-STATUS
   §1.4. 11 menus, ~70 commands ride on it.
2. ~~**Keyboard shortcut layer**~~ — done: 82 bindings in
   `KeyboardShortcuts.kt` (mirrors `gBuiltInAccelerators` in
   `src/Accelerators.cpp`), `MainActivity.dispatchKeyEvent` routes
   the matches into `pendingShortcut`, the Compose tree consumes
   them via `LaunchedEffect`. Toggle: `Settings.keyboardShortcuts =
   true` (default). When off, every shortcut is a no-op. The
   search-field pass-through and DeX-keyboard `keyCodeToChar` map
   that makes typing work in the find toolbar live in the same
   files — see `docs/dex-search-field-issue.md`.

### Phase 2 — correctness and performance of what exists

The 11 defects in PORTING-STATUS §3 are not new features; they are
"this works, but the moment you give it a 600-page PDF you find the
bug." Get them in order:

3. ~~**Continuous-mode virtualisation** (§3.6)~~ — done: `LazyColumn`.
4. ~~**Per-slot `PanZoomState`** (§3.9)~~ — superseded: zoom is a
   document property and panning is a shared viewport scroll.
5. ~~**Render cache + prefetch** (§3.7)~~ — done, with visible-region
   tiling above the single-bitmap budget.
6. **Rotation hit-testing** (§3.4) — model mupdf's render-matrix
   rotation in the screen→page mapping so links and selection work on
   rotated pages.
7. **`CmdToggleLinks` highlighting** (§3.1) — what the command does on
   the desktop, not link counting.
8. ~~**Manga mode** (§3.2) — actually render right-to-left.~~ **Done —
    `mangaMode: Boolean` on `PageSurface` reverses the page order in
    Facing/BookView (continuous + non-continuous), and
    `mangaAwarePrev`/`mangaAwareNext` reverse the navigation direction
    (toolbar Next/Prev, edge taps, `ScrollLeft`/`ScrollRight` shortcuts,
    and `NextPage`/`PrevPage` menu items). 11 new unit tests in
    `MangaModeTest.kt`. Verified on the Note 20 with
    `onimai_imnowyoursister1.pdf` (179 pages, Facing view).**
9. **Shrink-to-fit** (§3.3) — distinguish from fit-page.
10. **Search hit branch on facing/book** (§3.5) — fix the dead
    `searchHits = if (left == page)` so the left page also gets
    highlights.
11. **Async search with cancel + progress** (§3.8) — `Dispatchers.IO`
    per-page, `Job` cancellation on query change, progress bar in
    `SearchBar`.
12. **Search flags** — pass `SEARCH_IGNORE_CASE` / `…_DIACRITICS` /
    `…_REGEXP` / `…_KEEP_LINES` to mupdf from the toolbar toggles.
13. **ToC expand/collapse + persisted `tocState`/`showToc`/`sidebarDx`**
    (PORTING-STATUS §2.3) — `CmdExpandAll`, `CmdCollapseAll`,
    `CmdExpandToCurrentPage`, `CmdTocExpandToLevel1/2/3`,
    `CmdTocCollapseSameLevel`.
14. **Zoom presets + widen the clamp to 8.33–6400%** (§2.9) — render
    `MenuAction.ZoomPercent` in the Zoom menu.
15. **Tab commands** (§2.8) — `CmdCloseOtherTabs`,
    `CmdCloseTabsToTheRight/Left`, `CmdCloseAllTabs`, `CmdNextTab/
    PrevTab/NextTabSmart/PrevTabSmart`, `CmdMoveTabLeft/Right`,
    `CmdDuplicateInNewTab`, `CmdSetTabColor`.

### Phase 3 — the big absent features

16. **Annotations** — the 19 `CmdCreateAnnot*` types (Text, Link,
    FreeText, Line, Square, Circle, Polygon, PolyLine, Highlight,
    Underline, Squiggly, StrikeOut, Redact, Stamp, Caret, Ink, Popup,
    FileAttachment, ImageFromClipboard) + Edit / Delete / Save /
    SaveNewFile / Discard / Show / Hide / ToggleShow. The single
    biggest feature gap. PORTING-STATUS §1.3.
17. **Read-aloud highlighting** — port `ReadAloudHighlight.cpp` (726
    LoC) so the engine draws a yellow mark over the words as it
    reads. Add `CmdReadAloudSelection`, `CmdTtsSpeed*`, Read From
    Cursor. PORTING-STATUS §2.5.
18. **Real favorites** — per-page named favorites in a sidebar tree
    with next/previous navigation, sort, page labels. PORTING-STATUS
    §2.2.
19. **Selection** — drag handles, cross-page, area/rectangular
    selection, `CmdCopyImage/CopyLinkTarget/CopyComment/CopyFilePath`.
    PORTING-STATUS §2.6.

### Phase 4 — settings, formats, panels

20. **Settings editor** — `CmdOptions`, `CmdAdvancedSettings`,
    `CmdAdvancedOptions`, all 27 `struct`s. PORTING-STATUS §1.9.
21. **Theme system** — `CmdSetTheme`/`ChangeTheme`/`ToggleLightDark`,
    `CmdChangeBackgroundColor`, `CmdInvertColors`,
    `CmdToggleEngineeringDrawingEnhance`,
    `CmdTogglePreservePdfImages`, `struct Theme`/`Themes`. §2.11.
22. **Print options** — page ranges, odd/even, scaling, print-as-image,
    duplex, copies, `PrinterDefaults`. §2.10.
23. **Widen the manifest** — register for every MIME / path pattern
    mupdf can already open. §2.1.
24. **Library wire-up** — wire `CmdToggleLibraryHome`, `CmdLibraryRescan`,
    the 17 `<Library,…>` link verbs, the library tile long-press menu.
    §0.3 "Commands still absent" / "Settings absent".

### Phase 5 — long tail

25. **Command palette** — `CmdCommandPalette`,
    `CmdCommandPaletteTOC`, `CmdCommandPaletteFavorites`. §1.6.
26. **Image operations** — `CmdCopyImage/Crop/Resize/Save/
    PasteClipboardImage/ConvertImageToPdf`. §1.7.
27. **PDF tools** — `CmdPdfCompress/Decompress/DeletePages/
    ExtractPages/Encrypt/Decrypt/Bake`, `CmdPdShowInfo`,
    `CmdDocumentExtractText`, `CmdDocumentShowOutline`. §1.8.
28. **DjVu + CHM + CBR** — these are not in the mupdf AAR, so this is
    a build-system + dep change. §2.1.
29. **SyncTeX / inverse search** — cross-platform, but Win32-only
    today. §1.10.

### Not planned

Windows-only and correctly dropped: `src/regress/`, `src/wingui/`,
`src/Installer*.cpp`, `src/ExternalViewers.cpp`, DDE / `CmdExec`,
`CmdSelectionHandler`, `CmdListPrinters`, the AI chat/translate CLIs
(`src/AIChat*.cpp`, `src/CodexBuild.cpp`, `src/ClaudeCode.cpp`,
`src/GrokBuild.cpp`), `src/CrashHandler.cpp`, `src/UpdateCheck.cpp`,
PDF-dark-mode (8 files), and the Windows previewer / search filter.

---

## In this branch

- `library` — only the **library start page** of the three fork
  features. The Chatterbox read-aloud and square-corners features are
  in the `library-work` branch (the combined one); the user's
  day-to-day checkout in this folder is the `library` one. The setting
  struct is **`struct Library`**, not `struct Audiobook` —
  `Settings.kt::libraryHome` mirrors `LibraryHomeEnabled()`.

## What runs today

```powershell
# 1. Build (the SDK-processing warning trips $ErrorActionPreference=Stop;
#    use Start-Process to capture exit code + log to disk)
$env:Path = "C:\Users\Nokel\AppData\Local\Android\Sdk\platform-tools;" + $env:Path
$env:JAVA_HOME = "C:\Users\Nokel\jdk-17.0.2"
$env:ANDROID_HOME = "C:\Users\Nokel\AppData\Local\Android\Sdk"
Set-Location "C:\Users\Nokel\Documents\AI_crap\chatterbox-AI\sumatra-android\android"
$proc = Start-Process -FilePath ".\gradlew.bat" -ArgumentList "--no-daemon","assembleDebug" `
  -Wait -PassThru -NoNewWindow `
  -RedirectStandardOutput "$env:TEMP\sum-build-stdout.log" `
  -RedirectStandardError  "$env:TEMP\sum-build-stderr.log"
# Check log, not exit code
Select-String -Path "$env:TEMP\sum-build-stdout.log" -Pattern "BUILD SUCCESSFUL"

# 2. Tests
$proc = Start-Process -FilePath ".\gradlew.bat" -ArgumentList "--no-daemon","testDebugUnitTest" `
  -Wait -PassThru -NoNewWindow `
  -RedirectStandardOutput "$env:TEMP\sum-test-stdout.log" `
  -RedirectStandardError  "$env:TEMP\sum-test-stderr.log"
# 50 tests in 10 classes, 0 failures, 0 errors, ~9 s

# 3. Install + launch on the Fold 4
adb -s RFCT80H8FRJ install -r "app\build\outputs\apk\debug\app-debug.apk"
adb -s RFCT80H8FRJ push "C:\Users\Nokel\sample_book.pdf" /sdcard/Download/test.pdf
adb -s RFCT80H8FRJ shell am force-stop com.sumatrapdf.reader
adb -s RFCT80H8FRJ logcat -c
adb -s RFCT80H8FRJ shell am start -W -a android.intent.action.MAIN `
  -c android.intent.category.LAUNCHER `
  --es filePath /sdcard/Download/test.pdf `
  -n com.sumatrapdf.reader/.MainActivity
```

## Verification rule (load-bearing)

A change is **not** done until:

1. `gradlew testDebugUnitTest` passes (oracle tests for any refactor
   of `Learn.kt` / `Catalogue.kt` / `Genre.kt` / `BookName.kt`).
2. The APK builds (`gradlew assembleDebug`).
3. On the Fold 4, the feature works to the user. The phone is behind a
   secure bouncer, so the agent cannot screenshot the running app —
   the user unlocks and the agent drives via `adb shell input tap`,
   `screencap`, `uiautomator dump`. The agent checks the dump, not
   the eyeball.
4. The toggle rule (top of file) is honoured: any test-only
   instrumentation is in `Settings` or behind a `buildConfigField`,
   and the default is off for users.

The agent **does not** say "done" on the strength of the build alone.
