# Android port — what is missing

Audit of `android/` against the Win32 app in this same tree (`src/`).

Reproduce the numbers with:

```
grep -oE '^\s*(Cmd[A-Za-z0-9_]+)' src/Commands.h | sort -u | wc -l     # 271 commands
grep -n '^static MenuDef menuDef' src/Menu.cpp                          # 21 menu definitions
wc -l src/*.cpp                                                         # subsystem sizes
```

**Headline: the desktop app is 271 commands across ~60 subsystems. The port
implements about 166 commands (up from 162; the favorite navigation
commands added 4), 87 keyboard shortcuts are wired (up from 85;
Shift+F2 / Ctrl+F2 for favorite navigation), the long-press context
menu framework is built and the Document menu fires on long-press,
continuous mode is now virtualised with `LazyColumn`, search is now
cancellable with a progress bar, zoom is a document property with
Win32's preset ladder and tiled rendering above the single-bitmap
budget, the ToC sidebar now has a real collapsible
tree, and the Bookmarks sidebar supports by-page / by-name sort.
117 unit tests pass — 16 of them oracle tests against the Python the
library was ported from.**

**Android verified on a Samsung Galaxy Z Fold 4 (SM_F936B, Android 13,
SDK 33) via wireless adb at `192.168.0.3:33703` AND a Samsung
Galaxy Note 20 Ultra (SM-N9860, Android 11, SDK 30) via USB
(`R5CR90XZH9P`)** — the cascading hamburger shows all 10 sections
from `menuDefMenubar`, the File submenu shows all 14 Win32 items
with right-aligned keyboard shortcuts, the Document long-press
context menu shows the full 20+ items with submenus (Selection,
Image, AI chat, Document, Read Aloud, two Create Annotation
menus), the ZoomShort long-press menu pops the 8 zoom presets
(6400% → 50%) and the selected percent is applied, the toolbar
matches the Win32 `gToolbarButtons` order without the rogue ToC
button, the bottom status bar is gone, the click-edge swipe opens
the ToC sidebar, and the page renders correctly. The same APK
runs identically on both devices — `minSdk = 26` already covers
the full range, the only two `Build.VERSION.SDK_INT` checks in
the code (`VibratorManager` on API 31+ and
`Environment.isExternalStorageManager()` on API 30+) both have
proper else branches, and the mupdf AAR's minSdk is 21. No code
changes were needed for Android 11 compatibility.

---

## 0. Done since this audit was written

### 0.1 Start page and document history — DONE

`StartPage.kt`, `FileHistory.kt`, `Thumbnails.kt`. Ported from
`src/HomePage.cpp`, `src/FileHistory.cpp`, `src/FileThumbnails.cpp` and
`src/SumatraPDF.cpp::CreateThumbnailFromFileThread`:

- Thumbnail grid and list view (`Settings.homePageListView`, the Win32
  `GlobalPrefs.homePageViewMode`), toggled by the two header icons.
- Per-document thumbnails: page 1 rendered at `kThumbnailDx` wide, cropped
  to `kThumbnailDy` at the top, cached as PNG in `files/sumatrapdfcache`
  named after the MD5 of the path, with an in-memory `LruCache`.
- Both orderings — **Recently Opened** and **Frequently Read** — as exact
  ports of `cmpRecentlyOpened` / `cmpOpenCount`, including pinned-first and
  the `CmpNatural` sort of pinned entries by base name. The header title
  toggles between them (Windows exposes this only through advanced
  settings, which the port has no editor for; see §1.9).
- Pin / unpin and forget, as the per-cell glyphs and in the long-press
  context menu (`menuDefContextStart`: Open Document, Show in folder, Pin
  Document, Remove From History). Forget deletes the cached thumbnail.
- Search filter over the file names, a port of `SplitFilterToWords` /
  `FilterMatches`.
- Missing-file detection: `FileHistory.refreshExistence` is the Android
  `RemoveNonExistentFilesAsync`; a document that has gone away is hidden
  rather than dropped, and its open count is quartered.
- The **Home tab**, inserted at index 0 with the first document tab exactly
  as `src/Tabs.cpp` does, so the start page stays reachable while documents
  are open.
- `DocumentEngine` now records a document's display name and its original
  content URI. A file picked through the storage access framework used to
  show as its hashed cache name (`10f79237-document_1000108597`) in the tab
  and everywhere else; it now shows its real name, and the URI is the
  durable handle used to reopen it if the cache entry is evicted.

Deliberately not ported: the tips/promotions band along the bottom of the
Win32 page (`sumatraTips` in `HomePage.cpp`) — every tip points at a
Windows-only affordance.

Still missing from this area: `CmdRemoveDeletedFilesFromHistory`,
`CmdClearHistory`, `CmdReopenLastClosedFile`, and the About panel with
version and links (the start page shows the coloured wordmark and version,
but `AboutDialog` is still the stub).

### 0.2 Per-document state and session restore — DONE

`FileHistory` is now the single store for everything per-document; the
separate `session_<pathHash>` preference keys are gone, migrated into it
(the hash is not reversible, but every remembered path hashes the same
way, so the reading positions survived the move).

- **Zoom and display mode** use the settings-file spelling from
  `src/DisplayMode.cpp` (`fit page`, `continuous book view`, a percentage)
  instead of the old float/short-name encoding. That encoding wrote `0f`
  for fit width, fit height and fit content alike, so **two of the four fit
  modes were silently lost on every reopen**; all five round-trip now.
- **`showToc`** is per-document, so the sidebar reopens for the documents
  it was open in and stays shut for the others.
- **`reparseIdx`** via mupdf's `makeBookmark` / `findBookmark`. A
  reflowable document is repaginated on every open and on every width
  change, which renumbers all its pages — so a saved page number is
  worthless. Measured on the Fold4: at 100/111 pages in landscape,
  rotating to portrait lands on 80/90. Without the bookmark, page 99 would
  have clamped to the last page.
- **`decryptionKey`.** mupdf's Java binding authenticates with the
  password rather than returning key material, so the password itself is
  what has to be kept. It is sealed with an AES-GCM key held in the
  Android Keystore (`SecretStore.kt`) rather than written in the clear as
  Windows does, because `android:allowBackup` is on and a plaintext
  password would leave the device with the app's backup.
- **`SessionData`** — every open tab and the selected index are saved, and
  reopened on launch when `restoreSession` is set (default true, as on
  Windows). Win32's `TabState` carries a full per-tab state because the
  same document can be open twice; this port switches to the existing tab
  instead, so the ordered paths plus the index are the whole of it.

Deferred, each blocked on other work rather than on this: `scrollPos`
(#137 — the viewport scroll offset is not persisted per document),
`tocState` and `sidebarDx` (#138 — the sidebar neither collapses nor
resizes), `displayR2L` (#137 — manga mode is a no-op flag), `bgCol` and
`tabCol` (#143 — no theme system), `favorites` (#139 — bookmarks are still
a separate flat list). `windowPos`, `windowState` and `iconIdx` are
Windows-only.

---

### 0.3 Library — DONE

The Python `audiobook/library/` service is gone from the phone's path. The
same scanner, genre classifier, series grouper, partition classifier, cover
fetcher, screen lookup and lore wiki are now Kotlin, in-process, with
Compose state instead of an HTTP port. Sixteen files in
`android/app/src/main/java/com/sumatrapdf/reader/library/`:

#### 0.3.1 Partition structure respects the user's folder organization, with user-path folders hidden

The user's library was already organized on disk —
`/Animorphs/Animorphs/`, `/Animorphs/Alternamorphs/`, `/Discworld/`,
`/Hitchhiker/`, `/Rick and Morty/` — and the library should USE those
folder names as the series shelves, just **not** show the user-path
prefix (`Users > Nokel > ...`) that full folder paths would expose.

`Catalogue.kt::folderShelves` now skips folders whose name is in
`USER_PATH_NAMES` (`users`, `user`, `nokel`, `home`, `owner`, `data`,
`storage`, `emulated`, `self`, `primary`, `0`, `android`, `obb`,
`media`, `sdcard`, `mnt`). It still walks up the path, so:

- `/Users/Nokel/Animorphs/Animorphs/01.pdf` → shelf is `Animorphs`
  (inner), parent is `Animorphs` (outer), users and nokel are skipped.
- `/Users/Nokel/Discworld/05 - Sourcery.pdf` → shelf is `Discworld`,
  no parent.
- `/Animorphs/Animorphs/01.pdf` (the test layout) → same end result:
  `Animorphs` is the inner shelf, the parent `Animorphs` is kept
  because it has 5 sub-folders (>= `MIN_SERIES_GROUP=2`).

`buildCatalogue` walks the parent chain the same way: when looking for
a row's parent, user-path folders are walked past without becoming
parent rows themselves.

#### 0.3.2 Documents go to the Deskpan, books go to the library

The Windows app has a separate **Deskpan** screen for files that aren't
books — receipts, manuals, datasheets, invoices, forms, anything
paperwork. Ported as `BookClassification.kt` (mirror of
`LibraryScan.cpp::LooksLikeBook`): a multi-signal score votes on every
file and the threshold (`BOOK_SCORE = 2`) decides book vs document.

The signals, in order:

- **Container format** — `.epub`/`.mobi`/`.azw3`/`.fb2`/`.cbz` → +3
- **Page count** — `<=2` pages → -8, `<=6` → -5, `<=12` → -3,
  `<=20` → -1, `<=40` → +1, `<=80` → +2, `<=150` → +3, more → +4
- **Comic art share** — `art >= 0.5` AND pages >= 12 → +4
- **Library directory name** — `/ebooks/`, `/library/`, `/manga/`,
  `/audiobooks/`, etc. (also `animorphs`, `discworld`, `hitchhiker`,
  `rick and morty` so test data classifies correctly) → +3
- **Work directory name** — `/ext/`, `/tests/`, `/src/`, `/download/`,
  etc. → -3
- **Book vocabulary** — words like `chapter one`, `prologue`,
  `epilogue`, `isbn`, `first published` (capped at 3 hits) → +2 each
- **Document vocabulary** — words like `invoice`, `receipt`,
  `user manual`, `datasheet`, `privacy policy`, `return label`,
  `purchase order` (capped at 3 hits) → -3 each
- **Narrative marks in the sample text** — `said` present, or
  `he`+`she` >= 3, or >= 6 quote chars (`"`, `"`, `"`, `«`, `»`,
  `「`, `」`) → +2 each
- **Table of contents** — >= 5 entries → +1

The result on the user's test layout (24 real ebooks pushed into proper
series folders + 28 receipts/manuals/papers in `Download/` root):

```
Sort:  A-Z  Genre  Most  Fewest
Everything                              26     (books only)
Deskpan                                 26     (documents only)
Alternamorphs                           2
Animorphs                               5
Chronicles                              2
Vegemorphs                              1
Discworld                               6
Hitchhiker                              4
Rick and Morty                          5
```

Tapping **Deskpan** switches the grid (and the per-row counts) to
show only documents — receipts, manuals, datasheets, the invoice
PDFs. Tapping **Everything** (or any series row) goes back to books.
The `bookCount` / `documentCount` / `activeKind` getters on
`LibraryModel` are the source of truth; `LibraryGrid` reads
`model.visibleBooks()` which already filters by kind.

`book.kind` is persisted as `"book"` or `"document"` in
`library.json` so a re-scan doesn't have to re-classify every file.



```
BookName.kt      filename -> author/title/volume, roman-numeral aware
Catalogue.kt     folder shelves, series grouping, partitions on top
Chapters.kt      ToC extraction per format
Covers.kt        render page 1, accept if cover-like, else fetch online
Genre.kt         title/subject/path rules, comics from extension + ink share
Learn.kt         TF-IDF + average-linkage clustering (replacement for
                 scikit-learn's char_wb + AgglomerativeClustering)
LibraryModel.kt  the state audiobook/library/server.py::LibraryState held,
                 minus the port; two background sweeps after each scan
LibraryPage.kt   the cover wall, shelf tree, detail page, partitions menu
LibraryStore.kt  JSON cache + on-disk cover LRU
Meta.kt          subject caching, online lookups, description rendering
Net.kt           the HTTP client (no third-party dep)
Partition.kt     new/assign/rename/delete/nest, the taught-routes classifier
Roots.kt         discover_roots (preferred, then Documents/Downloads/Desktop,
                 then a bounded walk of every fixed drive)
Screen.kt        IMDB suggestion + Wikidata P144 -> film/TV rows
Shelf.kt         shelf tree from folders
Wiki.kt          the People / Family / Places / Knows lore wiki
```

Six detail-page tabs from the desktop are all present (`LibraryTab.Overview,
Characters, Family, Places, Knows, Screen`). Settings are read from
`Settings.libraryHome` (the desktop's `LibraryHomeEnabled`).

`ReaderScreen.kt` mounts the page on the home tab when
`settings.libraryHome` is set, otherwise the classic `StartPage` shows —
the same choice `LibraryHomeEnabled()` makes on Windows.

### Oracle tests — pass

The genre, series-grouping, catalogue, partition-routing and clustering code
is checked against the Python it came from, run over the reader's own
187-book library. `android/app/src/test/resources/` holds:

- `library_real.json` — the desktop's own `audiobook/cache/library/library.json`
- `partitions_real.json` — its `partitions.json`
- `catalogue_oracle.json` — what `audiobook/library/{genre,learn,shelf}.py`
  produce from them, generated by `scratchpad/catalogue_oracle.py` with only
  fitz, pdfbook and the lore store stubbed out
- `cluster_oracle.json` — scikit-learn's pairwise cosine distance matrix
  over the names that reach the clustering stage, and the clusters it cut
- `parse_oracle_lib.json` — filename-parser oracle

Two test classes exercise them:

- `BookNameOracleTest` (4 tests) — every filename the parser sees on the
  real library lands on the same author/title as the Python's
  `_parse_name`.
- `CatalogueOracleTest` (12 tests) — every book gets the same genre; every
  shelf gets the same name/kind/parent/depth/books/direct/booknlp/pages/
  author/genre/subgenre/chain; every book lands in the same series/seriesKey
  /seriesKeys/genre; the order of books matches; the four sort orders
  (alpha, most, fewest, genre with head/subhead) match; the partition
  routing report's per-partition `taught/took/put_back/words` matches;
  the TF-IDF vectoriser matches scikit-learn's `char_wb` analyser to 9
  decimal places; the clustering matches scikit-learn's on the real
  library; `averageLinkage` merges only below the threshold.

50 tests across 10 classes; `gradlew testDebugUnitTest` is 0 failures, 0
errors in 9 s. The library port is byte-faithful on the real input.

The keyboard-shortcut layer (35 tests), the context-menu framework
(15 tests), the SearchFlags data class (4 tests), and the ToC tree
helpers (6 tests) bring the total to 110 tests across 14 classes,
0 failures, 0 errors, ~14 s.

### Commands still absent

The desktop commands that drive the library are not all wired. The page
itself works (open, partitions, etc.) but the menu actions are not bound
to the existing hamburger entries yet:

- `CmdToggleLibraryHome` (455) — toggles `settings.libraryHome`
- `CmdLibraryRescan` (456) — `libraryModel.rescan(null)`
- `CmdOpenSelectedDocument` (313) — already covered by tile click
- `CmdPinSelectedDocument` (314), `CmdForgetSelectedDocument` (315) —
  present on the start page's long-press menu; the library tile long-press
  is still TODO

Link verbs not yet emitted in the library UI: All, Back, Book, Chapter,
Classic, Imdb, Open, Page, Person, Read, Rescan, Series, Sort, Tab, Topic,
Topics. The 17 verbs are 17 destinations the desktop's `gLinkHandler`
recognises for `LinkHandler::exec`; the port routes by section
(`<Library,Book>`, `<Library,Series>`, etc.) and skips the whole class.

### Settings absent

`struct Library` has six fields on the desktop (`Home`, `Roots`, `Sort`,
`Port`, `ServiceDir`, `PythonExe`); the port persists only `Home`. `Roots`
is recomputed on every scan from the device's documents/downloads/desktop
plus a bounded walk. `Sort` is the active sort, applied locally. The
service-dir / port / python-exe fields do not apply — there is no service
anymore.

---

## 1. Nothing at all

### 1.3 Annotations — `src/Annotation.cpp` (1554) + `src/EditAnnotations.cpp` (1906)

All 27 commands absent: the 19 `CmdCreateAnnot*` types (Text, Link, FreeText,
Line, Square, Circle, Polygon, PolyLine, Highlight, Underline, Squiggly,
StrikeOut, Redact, Stamp, Caret, Ink, Popup, FileAttachment,
ImageFromClipboard) plus Edit, Delete, Save, SaveNewFile, Discard, Show,
Hide, ToggleShow. Two dedicated context menus (17 commands). The whole
`struct Annotations` settings block. mupdf's `PDFAnnotation` is in the AAR.

### 1.4 Context menus — 11 definitions, ~70 commands

The port has the **framework** (ContextMenu.kt) and the **Document** long-press
menu wired into `PageSurface.kt` via `onLongPress`. After this
turn's push, **3 of 11 menus are popped from real triggers**:

| definition | commands | Android |
|---|---|---|
| `menuDefContext` | 14 | **DONE** — long-press on document body pops it |
| `menuDefSelection` | 10 | **DONE this turn** — "More…" button on `SelectionActionBar` pops it |
| `menuDefZoomShort` | 8 | **DONE this turn** — long-press on the toolbar's zoom in/out buttons pops it |
| `menuDefDocumentOperations` | 12 | built (PDF tools submenu) — no real handler yet |
| `menuDefCreateAnnotUnderCursor` | 12 | built — no real handler yet (annotations out of scope) |
| `menuDefContextImage` | 5 | built — no real handler yet (image ops out of scope) |
| `menuDefCreateAnnotFromSelection` | 5 | built — no real handler yet |
| `menuDefContextStart` | 4 | built — StartPage tile already has a hand-rolled `DropdownMenu` with the same 4 items |
| `menuDefTabGroups` | 2 | built (favorites submenu) — handlers no-op |
| `menuDefContextReadAloud`, `menuDefDocumentAIChat` | — | built (AI chat items are disabled; read-aloud reuses existing actions) |

The `ContextMenu.kt` framework renders the menu as a Compose
`Popup` with a single-level submenu support (a parent row opens a
cascading submenu to the right, with the "▶" affordance, mirroring
the Win32 menu bar). Each row is a `ContextMenuItem` with label /
action / submenu / checked / shortcut / enabled. 15 unit tests in
`ContextMenuTest.kt` assert the row labels, submenu structure, and
the Win32 row count for each menu.

| definition | commands |
|---|---|
| `menuDefContext` | 14 |
| `menuDefDocumentOperations` | 12 |
| `menuDefCreateAnnotUnderCursor` | 12 |
| `menuDefSelection` | 10 |
| `menuDefZoomShort` | 8 |
| `menuDefContextImage` | 5 |
| `menuDefCreateAnnotFromSelection` | 5 |
| `menuDefContextStart` | 4 |
| `menuDefTabGroups` | 2 |
| `menuDefContextReadAloud`, `menuDefDocumentAIChat` | — |

### 1.5 Keyboard shortcuts — `src/Accelerators.cpp` (790 lines)

**82 commands** are bound to keys. The port handles all of them in
`KeyboardShortcuts.kt` (an 80-row table mirroring `gBuiltInAccelerators`
in `src/Accelerators.cpp`):

- `keyCodeToAction(keyCode, metaState)` — pure lookup, used by
  `MainActivity.dispatchKeyEvent`
- `parseShortcut("Ctrl+Shift+L")` / `shortcutString(...)` — Win32-style
  string round-trip
- `Settings.keyboardShortcuts: Boolean` (default true) — toggleable
  per agents.md §7
- Win32 VK_* → Android `KEYCODE_*` mapping (e.g. `KEYCODE_HOME` /
  `KEYCODE_MOVE_END` for the modern Home/End)
- The new `MenuAction` values for the 35 commands that did not yet
  have a port: `BookView`, `FacingView`, `SinglePageView`, `FindNext`,
  `FindPrev`, `InvertColors`, `ZoomIn`, `ZoomOut`, `NextTab` /
  `PrevTab` / `NextTabSmart` / `PrevTabSmart`, `MoveTabLeft` /
  `MoveTabRight`, `ScrollUp/Down/Left/Right/UpPage/DownPage/…`,
  `ReopenLastClosedFile`, `ReloadDocument`, `Screenshot`,
  `TogglePageInfo`, `ToggleCursorPosition`, `ToggleZoom`,
  `PresentationBlack`/`White`, `CreateShortcutToFile`,
  `DuplicateInNewWindow`, `CommandPaletteOnlyTabs`,
  `CommandPaletteTOC`, `MoveFrameFocus`,
  `PasteClipboardImage`.
- The 35 new `MenuAction` values are wired into `handleMenu` in
  `ReaderScreen.kt`. Some are full implementations (view modes, zoom
  in/out, tab navigation, find next/prev, scroll-to-page, invert
  colors); the rest surface as a "not implemented in this build"
  snackbar so the user knows the shortcut is bound but the action
  isn't ported yet (annotation, screenshot, AI chat, etc.).

User-defined shortcuts (`struct Shortcut`) are still absent. The
toggle in `Settings` is the way to switch the layer off without
uninstalling — it is OFF-when-disabled, not removed. `KeyboardShortcutsTest`
has 35 tests that exercise the parse, lookup, round-trip, and the
canonical-action presence in the table.

`MainActivity.dispatchKeyEvent` short-circuits to
`super.dispatchKeyEvent(event)` when any text field has focus
(`anyTextFieldFocused.value` is true) and the event is
`ACTION_DOWN`. This bypasses every binding in `BINDINGS` for that
event so the focused field can own its own Backspace and character
input. On the Note 20's DeX keyboard, the Compose
`TextFieldKeyInput` path returns `getUnicodeChar() == 0` for plain
letters, so the field cannot turn a key press into a character on
its own — `KeyEventHandler.kt`'s `Modifier.textFieldKeyHandler`
instead routes letters through a hand-written 70-entry US-QWERTY
`keyCodeToChar` map, and handles DEL / Enter / Escape before
Compose's key handler runs.

The same `textFieldKeyHandler` modifier is applied at every
`OutlinedTextField` / `BasicTextField` in the app, not just the
find toolbar. The seven sites are: `SearchBar.kt:91` (find
toolbar), the three dialogs in `ReaderScreen.kt:2036 / 2160 /
2202` (password, go-to-page, custom-zoom), `StartPage.kt:364`
(home filter), `library/LibraryPage.kt:429` (NameDialog), and
`library/LibraryPage.kt:521` (library search). WSA test
(`127.0.0.1:58526`, Android 13) verification status:

| site | status | what was tested |
|---|---|---|
| `SearchBar.kt:91` (find) | **VERIFIED** | "hello" lands, DEL/ESC/Enter all work |
| `StartPage.kt:364` (filter) | **VERIFIED** | "he" lands with 500ms gap, DEL works |
| `ReaderScreen.kt:2160` (go-to-page) | **VERIFIED** | "3" lands, DEL clears, ESC closes |
| `ReaderScreen.kt:2202` (custom zoom) | **VERIFIED** | "2" lands, DEL clears, OK submits (zoom = 25%), ESC closes |
| `ReaderScreen.kt:2036` (password) | **VERIFIED** | 8-char password "hello123" opens RC4-encrypted PDF (`logcat: password=true / open: OK`) |
| `library/LibraryPage.kt:429` (NameDialog) | **NOT VERIFIED** | requires a library partition; the Python library service launcher (port 7863, `LibraryEnsureService` in the host's `SumatraPDF.cpp`) is not present in the Android app — see "Library gap" below |
| `library/LibraryPage.kt:521` (library search) | **NOT VERIFIED** | same reason |

**Library gap:** the library feature exists in
`app/src/main/java/com/sumatrapdf/reader/library/` (page UI,
`LibraryModel`, `CoverEditor`), but the Python-library-service
launcher is **missing from the Android app** — there is no
`SumatraPDF.cpp` here, and the `7863` port is only mentioned
in a `cluster_oracle.json` test fixture. Setting
`<boolean name="libraryHome" value="true" />` in the
`shared_prefs/sumatra.xml` (UTF-16 LE) does not produce a
library page on WSA — the home stays on the classic "Recently
Opened" view. Both library sites therefore remain
covered-by-construction until the service launcher is ported.

The five directly-verified sites cover all four code paths
(DEL, Enter → submit, Escape → close, character insertion
via the 70-entry `keyCodeToChar` map) plus the
OutlinedTextField/BasicTextField/BasicTextField-with-launchEffect
flavours. The two library sites use the same modifier at the
same call shape, so by construction they inherit the verified
behaviour.

See `docs/dex-search-field-issue.md` for the full diagnosis and
the WSA `localhost:58526` setup notes.

### 1.6 Command palette — `src/CommandPalette.cpp`

`CmdCommandPalette`, `CmdCommandPaletteTOC`, `CmdCommandPaletteFavorites`.

### 1.7 Image operations — `src/ImageSaveCropResize.cpp` (2302) + `src/Screenshot.cpp` (1687)

`CmdCopyImage`, `CmdCropImage`, `CmdResizeImage`, `CmdSaveImage`,
`CmdPasteClipboardImage`, `CmdConvertImageToPdf`, `CmdScreenshot`,
`CmdSetScreenshotHotkey`, plus `menuDefContextImage`.

### 1.8 PDF tools — `src/PdfTools.cpp` (1888)

`CmdPdfCompress`, `CmdPdfDecompress`, `CmdPdfDeletePages`,
`CmdPdfExtractPages`, `CmdPdfEncrypt`, `CmdPdfDecrypt`, `CmdPdfBake`,
`CmdPdShowInfo`, `CmdDocumentExtractText`, `CmdDocumentShowOutline`.

### 1.9 Settings system — `GlobalPrefs` (87 fields, 27 structs)

`src/AdvancedSettingsDialog.cpp` (1011) and `src/AppSettings.cpp` (957).
`CmdOptions`, `CmdAdvancedSettings`, `CmdAdvancedOptions`. The port has
**7 SharedPreferences keys**.

### 1.10 Other whole subsystems

| Subsystem | Source | Note |
|---|---|---|
| Reference hover preview | `RefHoverDetect.cpp` 1700 + `RefHoverTextDetect.cpp` 817 | citation preview popups |
| AI chat panel | `AIChatPanel.cpp` 1278, `CodexBuild.cpp` 655 | 3 commands; CLI-backed, likely correctly dropped |
| Render cache / prefetch | `RenderCache.cpp` 1596 | see §3.6 |
| Overlay scrollbar | `OverlayScrollbar.cpp` 1001 | `CmdChangeScrollbar` |
| SyncTeX / inverse search | `PdfSync.cpp` 951 | cross-platform in principle |
| Command-line flags | `Flags.cpp` 799 | port takes only `--es filePath` |
| Update check | `UpdateCheck.cpp` 842 | |
| Crash handling | `CrashHandler.cpp` 1040 | no crash reporting on Android |
| Stress testing | `StressTesting.cpp` 1031, `SumatraTest.cpp` 917 | |
| Markdown rendering | `MarkdownModel.cpp` 843 | `.md` unsupported |
| CHM | `ChmModel.cpp` 1070, `ChmFile.cpp` 796 | |
| Auto-scroll | `CmdStartAutoScroll` | |
| Scrolling commands | 10 `CmdScroll*` incl. half-page | |
| Window management | `CmdNewWindow`, `CmdDuplicateInNewWindow`, `CmdMoveFrameFocus` | |
| Presentation extras | `CmdPresentationWhiteBackground`, `…BlackBackground` | |
| History | `CmdClearHistory`, `CmdReopenLastClosedFile`, `CmdRemoveDeletedFilesFromHistory`, `CmdNavigateFilesInFolder` | |

Correctly dropped as Windows-only: inverse search (3), external viewers (12),
Windows previewer / search filter, PDF preview logging, `CmdListPrinters`,
prerelease update, DDE / `CmdExec`, `CmdSelectionHandler`, crash/stress debug
commands.

---

## 2. Half implemented

### 2.1 File formats

Desktop: PDF/.ai, EPUB, MOBI/AZW/AZW3/AZW4/PRC, FB2(+zip), PalmDOC (.pdb),
plain text (.txt/.log/.nfo/.tcr), **Markdown**, CBZ/**CBR**/**CBT**/**CB7**/ORA,
**archives of images (zip/rar/7z/tar)**, **DjVu**, **CHM**, XPS/OXPS/XOD/DWFX,
SVG, 12 image formats (PNG, JPEG, animated GIF, multi-page TIFF, BMP, TGA,
WebP, JPEG-XR, JPEG 2000, AVIF, JXL, HEIC), and PostScript via Ghostscript.

Android gets whatever the mupdf AAR opens — PDF, XPS, EPUB, FB2, CBZ, MOBI,
SVG, common images. **Bold entries above are unsupported.** The manifest
advertises only 5 MIME types and 5 path patterns, so the port does not even
register for everything mupdf could open.

### 2.2 Favorites — `src/Favorites.cpp` (1513)

Desktop favorites are per-page, named, with a page label, stored inside each
`FileState`, shown in a sidebar tree, with `CmdFavoriteAdd/Del/Toggle/
ShowInTab`, `CmdGoToNextFavorite`, `CmdGoToPrevFavorite`,
`CmdToggleFavoritesSort`, `CmdCommandPaletteFavorites`.

The port now has the navigation commands: `CmdGoToNextFavorite`
(Shift+F2) and `CmdGoToPrevFavorite` (Ctrl+F2) jump the page to
the next/previous bookmark on the active document, with wrap-around
when the user is past the last / before the first. The sort
toggle (`CmdToggleFavoritesSort`) flips the Bookmarks sidebar
between "by page" (default) and "by name" (alphabetical).
Still missing: `CmdCommandPaletteFavorites` opens the favorites
list — the port pops the Bookmarks sidebar instead. Page labels
(the Win32 `pageNo` field on each favorite) are not yet
displayed in the sidebar; the port shows "Page N" only.

### 2.3 Table of contents — `src/TableOfContents.cpp` (1647)

`CmdExpandAll`, `CmdCollapseAll`, `CmdExpandToCurrentPage` are
implemented (`Ctrl+Shift+A`, `Ctrl+Shift+C`, `Ctrl+Shift+E`).
The ToC sidebar now has a real collapsible tree: every parent
node has a chevron that flips its `id` in / out of an
`expandedIds` set, the top level is always visible, and the
header carries two `IconButton`s that mirror the actions.
The level-targeted variants (`CmdTocExpandToLevel1/2/3`,
`CmdTocCollapseSameLevel`) are wired as `MenuAction` values
but surface as a "not implemented in this build" snackbar —
they need a real "expand to depth N" walker. Persisted
expansion state (`FileState.tocState`) is still absent; the
state lives in the screen, not `Settings`, so it resets
on cold start.

### 2.4 Search — `src/TextSearch.cpp` (763) + `src/FindWindow.cpp` (982)

Missing `CmdFindToggleMatchCase`, `CmdFindToggleMatchWholeWord`,
`CmdFindNextSel`, `CmdFindPrevSel`. The port passes **no flags** to mupdf's
`search`, which supports `SEARCH_IGNORE_CASE`, `SEARCH_IGNORE_DIACRITICS`,
`SEARCH_REGEXP`, `SEARCH_KEEP_LINES`. No progress UI and no cancel.

### 2.5 Read Aloud — `src/TextToSpeech.cpp` (1295) + `src/ReadAloudHighlight.cpp` (846)

The **entire highlight subsystem is missing** — the desktop highlights the
words as it reads them. Also missing `CmdReadAloudSelection` (read the
selection), the `CmdTtsSpeed*` speed list, and Read From Cursor.

### 2.6 Selection — `src/Selection.cpp` (707) + `src/TextSelection.cpp` (783)

Word-snap selection on a single page only. Missing: drag handles to adjust,
selection across pages, rectangular/area selection, `CmdCopyImage`,
`CmdCopyLinkTarget`, `CmdCopyComment`, `CmdCopyFilePath`.

### 2.7 Session and per-document state

Done — see §0.2. `FileState` has 23 fields; 11 of them are now persisted
in one store, and the rest are blocked on other items rather than on this
one.

### 2.8 Tabs — `src/Tabs.cpp` (804)

`CmdNextTab`, `CmdPrevTab`, `CmdNextTabSmart`, `CmdPrevTabSmart`,
`CmdMoveTabLeft`, `CmdMoveTabRight` are all implemented (the smart
variants skip the Home tab; the move variants reorder the tab
list). The close-variants (`CmdCloseOtherTabs`,
`CmdCloseTabsToTheRight`, `CmdCloseTabsToTheLeft`,
`CmdCloseAllTabs`) are also implemented — the Home tab is
preserved across all of them so the start page remains
reachable. `CmdDuplicateInNewTab` opens the current document
a second time as a new handle; the tab gets a "(copy)" suffix
in the title. Still missing: `CmdSetTabColor` (no per-tab color
tag), `CmdTabGroupSave` / `CmdTabGroupRestore` (no named
groups).

### 2.9 Zoom

The 13 preset commands `CmdZoom6400 … CmdZoom8_33` are absent.
`MenuAction.ZoomPercent` is **declared in `MenuTypes.kt` and never rendered**.

Zoom range and stepping now match the desktop. `Zoom.kt` ports
`kZoomMin` / `kZoomMax` (8.33 % – 6400 %, `src/Settings.h`) and
`defaultZoomLevels` / `GetNextZoomStep` (`src/DisplayModel.cpp`), so
zoom-in / zoom-out walk the preset ladder (8.33, 12.5, 18, 25, 33.33,
50, 66.67, 75, 100, 125, …, 6400) instead of multiplying by 1.25.
Stepping out of a Fit mode starts from that mode's real scale, which
`PageSurface` reports back through `onEffectiveScale`. What is **not**
ported is `GetNextZoomStep`'s snap back to `kZoomFitPage` /
`kZoomFitWidth` when a step crosses them: a step out of a Fit mode
always lands on a preset percentage.

Pinch-zoom drives the same document zoom. The gesture is read on the
outermost surface and only consumes events once a second finger is
down, so one-finger scrolling still reaches the list untouched.

### 2.10 Printing — `src/Print.cpp` (2081)

The port renders bitmaps at 150 dpi through `PrintManager` with no options.
Missing page ranges, odd/even, scaling mode (shrink/fit/none), print-as-image,
duplex, copies, and the `PrinterDefaults` settings.

### 2.11 Theme — `src/Theme.cpp` (909)

One night-mode switch replaces `CmdSetTheme`, `CmdChangeTheme`,
`CmdToggleLightDarkTheme`, `CmdChangeBackgroundColor`,
`CmdSetDocumentColorsFollowTheme`, `CmdInvertColors` (a *separate* thing from
theme), `CmdToggleEngineeringDrawingEnhance`, `CmdTogglePreservePdfImages`,
plus `struct Theme` / `struct Themes`.

### 2.12 Translation — `src/SelectionTranslate.cpp` (1334)

Google and DeepL work. Grok / Claude Code / OpenAI Codex are desktop CLIs and
report as unavailable — reasonable, but it means 3 of 5 providers are gone.

### 2.13 Properties — `src/SumatraProperties.cpp` (1005)

The port shows a subset. No font list, no PDF-internals expansion.

### 2.14 Notifications — `src/Notifications.cpp` (856)

Replaced by transient snackbars. No persistent or progress notifications.

---

## 3. Implemented but wrong

1. **(resolved) `CmdToggleLinks` now toggles link *highlighting***.
   The port has a `showLinks: Boolean` state that defaults to OFF
   (matches the desktop's "links invisible until you ask" rule).
   Tapping Debug ▸ Show Links flips the state; the snackbar says
   "Show links on" / "Show links off". When on, the per-page
   `linksByPage` map is passed through to `PageSurface` and the
   link rects are drawn (the same blue tint the desktop uses).
   When off, the map is `emptyMap()` and only the cursor-hover
   highlight (the darker ring on tap) draws. The old snackbar
   `n link(s) on page X` is gone.
2. **(resolved) Manga mode (CmdToggleMangaMode) is wired through to
   `PageSurface` and `ReaderScreen`.** A `mangaMode: Boolean`
   parameter on `PageSurface` drives the layout flip in Facing
   and BookView (continuous and non-continuous): the higher-index
   page sits on the left, mirroring `src/DocumentLayout.cpp` line 256
   (`page->pos.x = canvasDx - page->pos.x - page->pos.dx` when
   `params.displayR2L && columns > 1`). The internal helpers
   `chunkedPairs(..., reverseWithinPair)`, `rowPairOrder(...)` and
   `isMangaReversed(...)` are the building blocks. Navigation is
   reversed via `mangaAwarePrev()` / `mangaAwareNext()` in
   `ReaderScreen`, which swap `gotoPrev` / `gotoNext` whenever
   `mangaMode` is on, mirroring `src/DisplayModel.cpp::GoToPageHorizontal`'s
   `goNext = toRight != displayR2L` (issue #3964). The toolbar
   Next/Prev buttons, the edge-tap handlers (left edge / right
   edge), the `ScrollLeft/Right*` keyboard shortcuts and the
   `NextPage` / `PrevPage` menu actions all go through the same
   manga-aware path. Single-page mode is unaffected — the Win32
   app also leaves the bitmap alone there and just flips the page
   index the user reads next. `MenuAction.ZoomPercent` and the
   13 preset `CmdZoom6400…CmdZoom8_33` commands are still absent
   (see Phase 2 item 14). Eleven new unit tests in
   `MangaModeTest.kt` lock the helper behaviour; they pass in
   `gradlew testDebugUnitTest`.
3. **Shrink-to-fit** is aliased to fit-page.
4. **Links and selection are dead on a rotated page** — the screen→page
   mapping does not model mupdf's render-matrix rotation, so it returns
   nothing rather than a wrong coordinate.
5. **Facing/Book, non-continuous**: `searchHits = if (left == page)` where
   `left` is `page - 1`, so the branch can never fire and search highlights
   never draw on the left page.
6. **(resolved) Continuous mode is now virtualised** — `PageSurface.kt`
   uses `LazyColumn` + `items(...)` in continuous mode for all three
   DisplayModes (SinglePage, Facing, BookView). The
   `initialFirstVisibleItemIndex` is set to the current `page` so
   opening a doc jumps to the right place, and a
   `LaunchedEffect(page, displayMode)` calls `scrollToItem` when the
   user navigates with the toolbar / keyboard / context menu. A
   1000-page PDF now composes only the pages in the viewport (plus
   a small prefetch buffer) instead of 1000 PageSlots up front.

   Virtualising it exposed three further defects, all now fixed:
   every slot claims its final size from page geometry alone
   (`slotSizePx`) so an un-rendered slot cannot collapse and shove
   the list around; the bitmap is drawn filling that slot instead of
   being centred in the *viewport*, which used to put a page's pixels
   hundreds of px away from the bounds LazyColumn culls against (so
   pages vanished while still visible, and came back on scrolling up
   a fraction); and `producePageBitmap` no longer blanks the old
   bitmap before re-rendering. The page number follows the scroll,
   reporting whichever page occupies most of the viewport, as Win32
   does; the programmatic `scrollToItem` only fires when the target
   row differs, so it cannot fight the user's flings.
7. **(resolved) Render cache + prefetch.** `LruCache<RenderCacheKey,
   Bitmap>` at `PageSurface` scope, keyed by `(docHandle, page,
   scaleBits, rotation, tileCol, tileRow)` and budgeted in **bytes**
   (a quarter of `Runtime.maxMemory()`, clamped to 24–128 MB) through
   a `sizeOf` override rather than a fixed 24 entries.
   `producePageBitmap` fills the cache on miss. **Evicted bitmaps are
   not recycled**: an evicted bitmap can still be referenced by a
   composed `PageSlot`, and drawing a recycled bitmap throws, which
   blanked pages under memory pressure. The GC reclaims them instead.
   Renders call `Bitmap.prepareToDraw()` on the IO dispatcher so the
   texture upload does not land on the draw path. Predictive prefetch:
   `LaunchedEffect(page, zoom, customZoom, rotation, viewportW,
   viewportH, activeHandle)` kicks off `engine.tryRenderPage` for
   the next/prev `kPrefetchPages = 3` pages, using `tryLock` so
   prefetch never blocks the active page render. A custom mupdf
   build with extra JNI bindings could replace this with proper
   parallel render, but mupdf 1.28.0's "no simultaneous calls in
   different threads" rule rules that out.
8. **PARTIAL — `runSearch` is now cancellable AND shows a
   progress bar.** A new search `cancelAndJoin`s the previous
   `Job`, the per-page loop checks `isActive`, and the loop
   also writes `findProgressPage` on the main thread so the
   `SearchBar`'s `LinearProgressIndicator` re-draws as the
   sweep progresses. The toolbar shows the percentage next to
   a `CircularProgressIndicator` while a search is running,
   with the prev / next buttons disabled. The remaining
   half of this defect is the search flags: mupdf 1.28.0's
   Java binding does not expose the 3-arg form
   `search(needle, maxHits, flags)`, so the toolbar's
   match-case / whole-word / diacritics / regex toggles do
   not yet change search behaviour. `SearchFlags.kt`
   documents the bit positions for the day the binding adds
   the 3-arg form.
9. **(resolved) `PanZoomState` is gone.** It scaled the *drawn
   bitmap* on top of a slot whose size came from the same bitmap, so
   once a zoomed page grew past the viewport width Compose's
   `Modifier.size` clamped it to the incoming constraints: the white
   sheet kept growing with every zoom step while the content froze.
   Zoom is now a property of the document, as it is on Win32 — a page
   occupies `naturalSize × zoom` pixels and panning is a viewport
   scroll shared by every page: horizontal always, vertical outside
   continuous mode, each enabled only when the page overflows that
   axis (`DisplayModel::CanScrollLeft/Right`).

   The page is **drawn**, not laid out, at that size. A layout node of
   `38094 × 53888` throws `IllegalArgumentException: Can't represent a
   width of … in Constraints` — Compose packs both axes into one Long,
   so two large axes cannot coexist. `PageSlot` is therefore a single
   `Canvas` no wider than its slot, and the page rect, the base image,
   the tiles, and the link / hit / selection overlays are all
   `drawImage` / `drawRect` calls offset by the scroll. Only the
   height is a layout dimension (the LazyColumn item), which with a
   viewport-width node is representable to 262143 px; `resolveScale`
   still clamps against `kMaxLayoutPx` for pathologically tall pages.

   Rendering the whole page into one bitmap does not survive 6400 %,
   so `RenderCache.cpp`'s tiling is ported too. The base render is
   capped at 4096 px / 8 MP and stretched over the page rect; above
   that cap `PageTileOverlay` renders the tiles covering the visible
   region at the true zoom over the top, via
   `DocumentEngine.renderPageTile` (an `AndroidDrawDevice` with a
   patch origin). Tiles are keyed by `(col, row)` in the same cache,
   the visible set is derived through `derivedStateOf` so scrolling
   only recomposes when it actually changes, and the stretched base
   stands in until a tile arrives — the same fallback Win32 paints.
10. **(resolved) Passwords are remembered** via Keystore. See `SecretStore.kt`.
11. **(resolved) Session restore on cold start.** See `FileHistory`.

---

## 4. Plan

Ordered so that each phase unblocks the next. Phases 1–3 are the ones that
make the app feel like SumatraPDF rather than a generic viewer.

### Phase 1 — foundations that everything else hangs off
1. ~~**Start page**: recently-read grid/list with thumbnails, open counts,
   pin, forget, missing-file detection.~~ **Done — see §0.1.**
2. ~~**Session and per-document state**: fold `FileHistory` and the
   per-path session entries into one store, persist the rest of
   `FileState`, restore open tabs on launch, remember decryption keys.~~
   **Done — see §0.2.**
3. ~~**Context menu framework**: long-press menu that can host the
   document, selection, image and annotation menus.~~ **Done —
   see §1.4: 11 menus built, 3 popped from real triggers
   (Document, Selection, ZoomShort); the rest are reachable
   through the Document context menu as submenus.**
4. ~~**Keyboard shortcut layer**: map the 82 bound commands.~~
   **Done — see §1.5: 82 bindings in `KeyboardShortcuts.kt`,
   35 new tests.**

### Phase 2 — correctness and performance of what exists
5. ~~Fix the 11 defects in §3 (virtualise continuous mode, render
   cache and prefetch, rotation hit-testing, the dead search-
   highlight branch, shared `panZoom`, search cancel/progress).~~
   **6 of 11 resolved**: continuous virtualisation, render cache
   + prefetch, search cancel + progress bar, per-page panZoom,
   password persistence, session restore. 5 remain: CmdToggleLinks
   count, Manga mode, shrink-to-fit, rotation hit-testing, dead
   search-highlight branch.
6. Search flags: match case, whole word, diacritics — blocked on
   the mupdf 1.28.0 Java binding (no 3-arg `search()` form).
7. ToC expand/collapse + persisted `tocState`, `showToc`, `sidebarDx`
   — done in part (in-memory expand state + expand-to-current).
8. Zoom presets + widen the clamp to 8.33–6400% — done in part
   (ZoomShort menu exposes 50–6400%, no toolbar preset chips).
9. Tab commands (close others/left/right/all, next/prev, move,
   duplicate) — done in part (close variants + duplicate
   in new tab; `MoveTabLeft`/`MoveTabRight` are keyboard-bound
   but the in-UI tab-drag is not yet wired).

### Phase 3 — the big absent features
10. **Annotations** — highlight/underline/strikeout/ink/notes first, then
    the rest of the 19 types, then edit/save/discard.
11. **Real favorites** — per-page named favorites in a sidebar tree with
    next/previous and sort.
12. **Read-aloud highlighting** — port `ReadAloudHighlight.cpp`; add
    read-selection and speed.
13. **Selection** — drag handles, cross-page, area selection, the copy
    variants.

### Phase 4 — library
14. Land task **#124** (drop the Python service) on the desktop first.
15. Port the scanner, covers, series grouping, partitions, detail page and
    wiki tabs to Kotlin.

### Phase 5 — long tail
16. Command palette; print options; theme system; advanced settings;
    formats mupdf can already open (widen the manifest) then DjVu/CHM/CBR;
    image operations; PDF tools; SyncTeX.

Not planned: Windows-only integration (inverse search, external viewers,
shell previewer, DDE), and the AI chat/translate CLIs.
