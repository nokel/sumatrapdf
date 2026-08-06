# AGENTS.md — `sumatra-android/android/`

Compact reference for an AI agent working in this checkout. Full
context lives in `docs/dex-search-field-issue.md`, `PORTING-STATUS.md`,
and `ROADMAP.md`; this file is the cheat-sheet.

## What this is

A native Android port of the SumatraPDF document reader. The goal is
visual and programmatic parity with the **WINDOWS** app in `src/`. The
working folder is `C:\Users\Nokel\Documents\AI_crap\chatterbox-AI\sumatra-android\android\`.

This branch (`library-work`, the `sumatrapdf/` worktree) carries the
combined "library start page + Chatterbox read-aloud + square window
corners" additions on top of stock upstream. See `ROADMAP.md` §
"Branch: `library-work`" for the additive file list.

## Build environment (this machine)

- **JDK 17**: `C:\Users\Nokel\jdk-17.0.2`
- **Android SDK**: `C:\Users\Nokel\AppData\Local\Android\Sdk`
- **cl.exe / msbuild**: VS 2022 Build Tools at
  `C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\MSBuild\Current\Bin\MSBuild.exe`
  (`cl.exe` is **not** in PATH; the `cmd/build.ts` wrapper handles it)
- **clang-format**: `C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Tools\Llvm\x64\bin\clang-format.exe`
- Build: `bun ./cmd/build.ts` (produces `./out/dbg64/SumatraPDF.exe`-style outputs
  inside `out/`)
- Note 20 Ultra: `R5CR90XZH9P` (USB)
- Z Fold 4: `192.168.0.3:35267` (wireless)
- WSA: `127.0.0.1:58526` (requires manual auth dialog acceptance)

## Hard rules (do not violate)

- **NEVER ADD COMMENTS TO CODE.** No prose comments, no header
  blocks, no banner separators. The repo is a faithful port; the
  source-of-truth Win32 code in `src/` already has its own
  comments. The Kotlin side is expected to be self-explanatory.
- **No Co-Authored-By / AI attribution** in any commit, PR, or
  published content.
- **No `git push`** unless explicitly asked.
- **NEVER `git commit` automatically.** Wait for the explicit
  command. When the user does commit, the commit message format
  is:
  - Subject ≤ 72 chars
  - Body with `What / Why / Risk`
  - For bug fixes, include `(fixes #<issue-no>)` at the end of
    the subject
  - For AI-assisted work, append `prompt: <squashed prompt line>`
    at the end of the body

## Document conventions

- Markdown only; no docx, no pdf unless asked.
- `docs/dex-search-field-issue.md` is the deep-dive for the
  hardware-keyboard-on-Android fix. Read it before touching
  text fields.
- `ROADMAP.md` is the contract with the upstream; the "Phase N"
  sections are ordered by what unblocks the most downstream
  work. Strike through items as they land.
- `PORTING-STATUS.md` is the running audit. Update it on the
  same commit that closes the gap.

## The DeX keyboard fix — do not break

This is the most fragile piece of the port. It exists because
`KeyEvent.getUnicodeChar()` returns `0` for plain letter keycodes
on the Note 20's virtual "DeX keyboard" input device, which makes
Compose's `TextFieldKeyInput` drop every letter on the floor.

The fix has three pieces; all three must stay in place:

1. **`MainActivity.dispatchKeyEvent`** (line ~207) — when
   `anyTextFieldFocused.value` is true and the event is
   `ACTION_DOWN`, short-circuit to `super.dispatchKeyEvent(event)`.
   This is the only thing that stops the global shortcut layer
   from eating Backspace, PageUp, HJKL, etc. before the field
   sees them.

2. **`KeyEventHandler.kt`** — the `Modifier.textFieldKeyHandler`
   extension. Applied at every `OutlinedTextField` /
   `BasicTextField` site. Handles DEL (selection-aware), Enter
   (→ `onSubmit()`), Escape (→ `onClose()`), and a 70-entry
   hand-written US-QWERTY `keyCodeToChar` map for everything
   else. Returns `true` (consumes) on the keys it handles,
   `false` (falls through) for arrows / Home / End / PageUp /
   PageDown so Compose's defaults still work.

3. **All 7 sites must use the modifier.** The list, with QA status
   from WSA testing on `127.0.0.1:58526` (Android 13):
   - `SearchBar.kt:91` — find toolbar — **VERIFIED** (typed "hello", DEL/ESC/Enter all work)
   - `StartPage.kt:364` — home filter (`FilterField`) — **VERIFIED** (typed "h", "e" with 500ms gap, DEL works)
   - `ReaderScreen.kt:2160` — GoToPageDialog — **VERIFIED** (typed "3", DEL clears, ESC closes)
   - `ReaderScreen.kt:2202` — CustomZoomDialog — **VERIFIED** (typed "2", DEL clears, OK button submits with zoom change, ESC closes)
   - `ReaderScreen.kt:2036` — PasswordDialog — **VERIFIED** (typed 8-char password "hello123", Open decrypted the PDF, logcat shows `password=true` + `open: OK`)
   - `library/LibraryPage.kt:429` — NameDialog — **VERIFIED on Note 20** (long-press a rail row → context menu → "New partition from this" / "Rename partition" opens NameDialog, typing works, OK submits)
   - `library/LibraryPage.kt:521` — library search — **VERIFIED on WSA + Note 20** (typed "hitch" 5 chars with 500ms gaps, field shows "hitch", DEL works; placeholder "Search title, author or series")

   The Python-library-service launcher (`LibraryEnsureService`, port 7863) is **not** in the Android app, but the library itself does not need it — the Kotlin port reads the same scanner, genre classifier, series grouper, partition classifier, cover fetcher and lore wiki directly, in-process. Setting `libraryHome = true` in `shared_prefs/sumatra.xml` plus `libraryExtraRoots = /storage/emulated/0/Download` produces a working library page.

   The library has TWO screens: **Library** (books) and **Deskpan** (everything else — receipts, manuals, datasheets, invoices). `BookClassification.kt::looksLikeBook` is a port of `LibraryScan.cpp::LooksLikeBook` — a multi-signal score (container format, page count, art share, library/work directory name, book/document vocabulary, narrative marks, TOC size) with `BOOK_SCORE = 2` deciding book vs document. Every book carries a `kind` of `book` / `document` / `ignored` (`Desk.kt`, the port of `audiobook/library/desk.py`). The scan's guess is only a guess: anything the user says by hand is written to `<library cache>/deskpan.json` and re-applied over every later scan by `applyDeskKinds`, called from `LibraryModel.adopt()` before `buildCatalogue`.

   Tapping the rail's **Everything** / **Deskpan** rows toggles `model.deskpanOpen`. The Deskpan is its own screen (`LibraryPage.kt::DeskpanPage`), not a filtered cover wall — it matches `LibraryPage.cpp::DrawDeskpan` row for row: a "Shelves" back link on narrow screens, the `n files · n chosen` count line, **Documents** / **Ignored** chips, the actions row, then 58dp rows with a 14dp tick box, a 38x50 extension thumbnail, the name, the folder (`deskWhere` strips `/storage/emulated/<n>/`), page count + file size, and an "Open" link. Tap ticks, long-press extends the run from the anchor, and "Select all" / "Select none" flips the lot. The three actions are the Win32 pair-per-view: from Documents, **Move selected to library** / **Ignore file**; from Ignored, **Move selected to library** / **Remove from library**. A long-press on a cover in the library grid offers the same "Remove from library" and "Ignore file" (`CoverTile`'s `DropdownMenu` → `model.moveOneFile`). Series rows (`Animorphs`, `Discworld`, `Hitchhiker`, `Rick and Morty`) come from the user's actual folder layout — `Catalogue.kt::folderShelves` walks up the path but skips user-path folders (`Users`, `Nokel`, `home`, etc.) so the rail shows series names, not `Users > Nokel > Animorphs > Animorphs`. See `PORTING-STATUS.md` §0.3.1 (folder structure) and §0.3.2 (Deskpan) for the full layout and the rationale.

   Folders are only the *last* way a series is found. `SeriesLookup.kt` (the port of `audiobook/library/series.py`) asks the world first: Wikidata `wbsearchentities` on the title, then `wbgetentities` for `P179` (*part of the series*), gated by `P31` being a kind of written work and ranked on title similarity plus author agreement (`P50` labels, `P2093` strings, initials-aware via `samePerson`); `P1545` on the claim is the volume number. `languagefallback=1` is mandatory — without it Douglas Adams (Q42), whose label lives in `mul`, comes back with an empty English label and reads as an author mismatch. When Wikidata has nothing, OpenLibrary's editions for the matched work vote on a series name after the volume tail is stripped, needing `MIN_EDITION_VOTES` (2). `LibraryModel.seriesSweep` runs after the metadata sweep, writes `apiSeries` / `apiSeriesIndex` / `apiSeriesSource` onto each `Book`, and `Catalogue.kt` makes one `kind = "series"` row per name — the same key and kind the Win32 side uses, so the two rails match. The sweep must `reshelveNow()` *before* `store.saveIndex(...)`, or `library.json` is written with the pre-sweep rows. Per-book results are cached in `files/library/series/<id>.json`; a cache written by an older lookup will be replayed as-is, so delete that folder (and `http/`) when the lookup itself changes.

   `Chapters.kt` (the port of `chapters.py`) tries three things in order, because a book's own table of contents cannot be trusted. First the outline, but only if its destinations actually spread across the book: a PDF whose every bookmark resolves to page 1 (plenty do) fails `OUTLINE_PAGE_SPREAD` (0.5 — at least half as many distinct pages as entries) and is thrown away. Then the outline's *titles* are kept but its pages discarded, and each title is located by matching it against every page's first line, in order, accepting the result only if `OUTLINE_FOUND_SHARE` (0.5) of them were found. Only then does the heading regex scan every page. `source` says which of the three answered (`outline` / `outline titles` / `pages`), and the cache carries `CHAPTER_CACHE_VERSION` (2) so caches written by the old outline-only reader are discarded rather than replayed. On the detail page the chapter *title* is the link that opens the book at that page; the disclosure arrow is the only part that expands children. `PageSurface.kt` holds a `jumpingTo` latch so the continuous-scroll visible-page reporter cannot cancel a programmatic jump mid-scroll.

   When adding a new text field, the same `Modifier.textFieldKeyHandler`
   must be applied, the parent component must pass an
   `anyTextFieldFocused: MutableState<Boolean>` down (default
   `mutableStateOf(false)` at the top of the tree so it can be
   used standalone), and the field's state must be
   `TextFieldValue` (not `String`) so the cursor survives the
   `onPreviewKeyEvent` round-trip. For `BasicTextField`-based
   sites whose parent API still wants a `String`, use the
   `LaunchedEffect(prop) { if (internal.text != prop) ... }` bridge
   pattern that `StartPage` and `LibraryGrid` already use.

## WSA test setup (verified working)

WSA on this machine is **build 2407.40000.4.0**, the final Microsoft
build (Microsoft ended support on 5 March 2025; WSABuilds is the
actively maintained fork). `127.0.0.1:58526` is the fixed WSA
loopback ADB port. To get `adb` to talk to it:

1. WSA Settings → Advanced settings → **Developer mode** ON.
   (Turn on **Optional diagnostic data** first if the IP-address
   field is empty — Microsoft hides it until that toggle is on.)
2. WSA Settings → **Manage developer settings** → in the
   in-VM Android Settings app, toggle **USB debugging** off and
   back on. This is what makes the "Allow USB debugging?" dialog
   re-appear behind the WSA window.
3. WSA Settings → click the **Files** icon to the left of the
   "Files" box. This launches the WSA VM; the port is not bound
   until an Android app is actually running.
4. `adb connect 127.0.0.1:58526`. Accept the dialog inside the
   WSA window with "Always allow from this computer" checked.
5. `adb devices` should show `127.0.0.1:58526  device`.

Once authorized, the find-toolbar test is:

```
adb -s 127.0.0.1:58526 install -r app-debug.apk
adb -s 127.0.0.1:58526 shell am start -n com.sumatrapdf.reader/.MainActivity \
  -d "file:///data/data/com.sumatrapdf.reader/cache/test.pdf"
adb -s 127.0.0.1:58526 shell input keycombination KEYCODE_CTRL_LEFT KEYCODE_F
adb -s 127.0.0.1:58526 shell input text "h"; sleep 0.4
adb -s 127.0.0.1:58526 shell input text "e"; sleep 0.4
adb -s 127.0.0.1:58526 shell input text "l"; sleep 0.4
adb -s 127.0.0.1:58526 shell input text "l"; sleep 0.4
adb -s 127.0.0.1:58526 shell input text "o"
adb -s 127.0.0.1:58526 shell input keyevent 67   # DEL
adb -s 127.0.0.1:58526 shell input keyevent 111  # ESC
```

The `~400 ms` between characters is important: `adb shell input
text` fires characters back-to-back, and if you read the field
before the next Compose recomposition, you'll see "llo" instead
of "hello". Physical-keyboard input has human-paced timing, so
it works fine.

To push a test PDF where the app can read it, bypass scoped
storage:

```
adb -s 127.0.0.1:58526 push test.pdf /data/local/tmp/test.pdf
adb -s 127.0.0.1:58526 shell chmod 666 /data/local/tmp/test.pdf
adb -s 127.0.0.1:58526 shell "run-as com.sumatrapdf.reader cp /data/local/tmp/test.pdf /data/data/com.sumatrapdf.reader/cache/test.pdf"
adb -s 127.0.0.1:58526 shell am force-stop com.sumatrapdf.reader
adb -s 127.0.0.1:58526 shell am start -n com.sumatrapdf.reader/.MainActivity \
  -d "file:///data/data/com.sumatrapdf.reader/cache/test.pdf"
```

`adb shell input keyevent` is the right primitive for DEL / ENTER /
ESCAPE; `adb shell input keycombination KEYCODE_CTRL_LEFT KEYCODE_<X>`
is the right primitive for Ctrl+letter shortcuts (the
`--metastate` form was unreliable on WSA in testing).

### CustomZoomDialog recipe (HamburgerMenu → Zoom → Custom Zoom…)

The hamburger menu is a Compose `Popup` that does **not** appear
in `uiautomator dump` (only the outer activity does), so the menu
items are not enumerable. The reliable path is to read the
UI-dump XML for `text="<section>"` and tap the parent
`clickable="true"` bounds directly. Verified menu item bounds
on the WSA 2560x1600 layout:

| item | clickable bounds | center |
|---|---|---|
| Menu (hamburger) | `[1126,38][1186,80]` | (1156, 59) |
| Zoom row | `[1151,196][1196,304]` | (1173, 250) — but `text="Zoom"` reports `[1151,284][1196,304]`, so tap `(1173, 294)` |
| Custom Zoom… row | `[1426,512][1751,560]` | (1588, 536) |

Test sequence:

```
adb -s 127.0.0.1:58526 shell input tap 1156 59    # open hamburger
sleep 0.5
adb -s 127.0.0.1:58526 shell input tap 1173 294   # Zoom row → submenu
sleep 0.5
adb -s 127.0.0.1:58526 shell input tap 1588 536   # Custom Zoom…
sleep 0.5
adb -s 127.0.0.1:58526 shell input tap 1760 778   # tap field to focus
adb -s 127.0.0.1:58526 shell input text "2"        # "2" is added (default "100" is at the 4-char limit)
sleep 0.5
adb -s 127.0.0.1:58526 shell input keyevent 67    # DEL removes the "2" back to "100"
adb -s 127.0.0.1:58526 shell input keyevent 111   # ESC closes the dialog
```

The field has a 4-character `take(4)` limit and a digit-only
filter, so multi-digit typing past the 4th char appears to
"swallow" the keystroke — but the modifier is still running;
the OutlinedTextField's `onValueChange` is what truncates.
The OK button at `(1899, 895)` always submits (verified: zoom
changed to 25% after typing "2" and tapping OK).

### PasswordDialog recipe (push an encrypted PDF)

The `pypdf` Python lib (installed under `py -3.12`) can build an
RC4-encrypted PDF from a `reportlab`-generated plaintext PDF.
AES-256 needs the `cryptography` package, which the system
Python doesn't have; RC4-128 is enough to trigger the password
prompt in mupdf.

```
py -3.12 -c "from reportlab.pdfgen import canvas; \
  c = canvas.Canvas('plain.pdf'); \
  c.drawString(100, 700, 'Hello - this is a test PDF for password testing'); \
  c.showPage(); c.save()"
py -3.12 -c "from pypdf import PdfReader, PdfWriter; \
  r = PdfReader('plain.pdf'); w = PdfWriter(); \
  [w.add_page(p) for p in r.pages]; \
  w.encrypt('hello123', algorithm='RC4-128'); \
  open('enc.pdf', 'wb').write(bytes(w.write()))"
```

Then push to WSA and launch (shared settings file is UTF-16 LE,
see "WSA quirks" below):

```
adb -s 127.0.0.1:58526 push enc.pdf /data/local/tmp/enc.pdf
adb -s 127.0.0.1:58526 shell chmod 666 /data/local/tmp/enc.pdf
adb -s 127.0.0.1:58526 shell "run-as com.sumatrapdf.reader cp /data/local/tmp/enc.pdf /data/data/com.sumatrapdf.reader/cache/enc.pdf"
adb -s 127.0.0.1:58526 shell am force-stop com.sumatrapdf.reader
adb -s 127.0.0.1:58526 shell am start -n com.sumatrapdf.reader/.MainActivity \
  -d "file:///data/data/com.sumatrapdf.reader/cache/enc.pdf"
```

The app opens the dialog with the field already focused, label
"Password", and the message `'"enc.pdf" is protected.'`. Type
the password with 400ms gaps (per-character):

```
for c in h e l l o 1 2 3; do
  adb -s 127.0.0.1:58526 shell "input text '$c'"
  sleep 0.4
done
adb -s 127.0.0.1:58526 shell input tap 1899 895   # Open
```

`logcat -d` will then show `SumatraEngine: open: ... (password=true)` and `open: OK, handle=N pageCount=1` — the PDF decrypted and rendered.

## WSA quirks (discovered while testing)

- **The WSA display goes to sleep** after ~15 s of no input.
  `adb shell screencap` returns an all-black PNG (size 19838 bytes)
  and `dumpsys window` shows `isOnScreen=false isVisible=false`
  for every window. Fix: `adb shell input keyevent KEYCODE_WAKEUP`
  before any UI dump. To keep it awake across a multi-step
  sequence, intersperse a no-op input (`input tap` on an empty
  area, or `keyevent KEYCODE_WAKEUP`) every few seconds.
- **The display position drifts when the WSA window is resized
  or wakes from sleep.** Coordinates from an earlier dump
  may land on the wrong control after a wake. Re-take the UI
  dump and re-compute tap centers from the current XML.
- **`uiautomator dump /sdcard/dump.xml` succeeds but the file
  isn't there on WSA.** Pull to `/data/local/tmp/dump.xml` and
  then `adb pull` from there. The `dump` command does not error
  when the destination filesystem is unwriteable.
- **`run-as ... cp /data/local/tmp/X /data/data/.../cache/Y`
  silently fails when the destination directory's group/owner
  doesn't match.** Use the long form: `cp SRC DST` in a single
  shell-quoted string, never `cat SRC > DST` (the redirect
  happens as `shell` user, not `run-as` user, and fails with
  "Permission denied" in the middle of the pipeline).
- **The shared-prefs XML is UTF-16 LE on Android** (you'll see
  the `戀漀漀氀攀愀渀` mojibake in PowerShell). Patch by
  byte-replacement, not text replacement: open the file in
  binary mode, find the `keyCodeToChar`-style bytes
  (`name="libraryHome" value="false"` as UTF-16-LE), and write
  the new bytes. Sample patcher in `.work/patch_settings.py`.

## Testing on the phone

- DeX on the Note 20 is the ground-truth test for the
  hardware-keyboard fix. The WSA path reproduces the same
  `KeyEvent` stream, so it's a fast proxy during development.
- `adb -s R5CR90XZH9P shell "getevent -lt /dev/input/event11"`
  shows the raw kernel events for the DeX keyboard (path
  `/dev/input/event11`, `Sources: 0x00000701`). The kernel
  delivers the right `KEYCODE_*`; the failure was downstream
  in `KeyEvent.getUnicodeChar()`, which the modifier now
  bypasses.

## Recently completed (since the last AGENTS.md edit)

- **Manga mode (CmdToggleMangaMode)** — `PageSurface.kt` now takes a
  `mangaMode: Boolean` parameter. When on, Facing and BookView
  (continuous and non-continuous) render the higher-index page on
  the left, mirroring `src/DocumentLayout.cpp::BuildDocLayout` line
  256 (`page->pos.x = canvasDx - page->pos.x - page->pos.dx` when
  `params.displayR2L && columns > 1`). Navigation is reversed via
  `mangaAwarePrev()` / `mangaAwareNext()` in `ReaderScreen.kt`, which
  swap `gotoPrev` / `gotoNext` whenever `mangaMode` is on, mirroring
  `src/DisplayModel.cpp::GoToPageHorizontal`'s `goNext = toRight != displayR2L`
  (issue #3964: Left advances in R2L). The toolbar Next/Prev buttons,
  the edge-tap handlers (left edge / right edge), the `ScrollLeft/Right*`
  keyboard shortcuts and the `NextPage` / `PrevPage` menu actions all
  go through the same manga-aware path. Single-page mode is unaffected.
  Building blocks are `internal fun chunkedPairs(..., reverseWithinPair)`,
  `internal fun rowPairOrder(left, right, mangaMode)` and
  `internal fun isMangaReversed(displayMode, mangaMode)`. 11 unit
  tests in `MangaModeTest.kt` lock the helper behaviour. Verified
  visually on the Note 20 with `onimai_imnowyoursister1.pdf` (179
  pages, Facing view, continuous mode).

## File layout (where things live)

```
app/src/main/java/com/sumatrapdf/reader/
  MainActivity.kt          # dispatchKeyEvent guard, intent dispatch
  KeyboardShortcuts.kt     # the 82-binding (keyCode, modifiers) table
  KeyEventHandler.kt       # Modifier.textFieldKeyHandler — the fix
  SearchBar.kt             # the find toolbar (line 91: modifier)
  ReaderScreen.kt          # the document view + 3 dialogs (2036, 2160, 2202)
  StartPage.kt             # the home / library start page (line 364: filter)
  library/
    LibraryPage.kt         # the library cover wall (line 429: NameDialog, 521: search)
docs/
  dex-search-field-issue.md  # the fix in depth
  ui-automation-research.md  # adb / UiAutomator gotchas
PORTING-STATUS.md          # the running audit
ROADMAP.md                 # the phase plan + branch deltas
```

## Don't

- Don't replace the hand-written `keyCodeToChar` map with
  `KeyCharacterMap.load(VIRTUAL_KEYBOARD)` — the broken KCM is
  the original bug. The map is the only thing that works on
  DeX.
- Don't add the modifier to a text field whose parent doesn't
  also pass an `anyTextFieldFocused` through — the modifier
  uses the `onFocusChanged` half to flip the global state, and
  if no one reads it, the `dispatchKeyEvent` guard will not
  fire for that field.
- Don't move the modifier behind the field's own `onKeyEvent`
  — `onPreviewKeyEvent` runs first, which is what stops Compose
  from also processing the event and double-inserting.
- Don't change `String` to `TextFieldValue` for a text field
  whose parent component also reads the value as a `String` —
  use the `LaunchedEffect` bridge.
- Don't wire `KeyEvent.KEYCODE_F` to anything other than
  `MenuAction.FindFirst` — Win32 parity is the point.
- Don't mutate a `Book` in place and expect the screen to
  redraw. `Book` and `LibraryIndex` are data classes, so the
  default `structuralEqualityPolicy` sees the rebuilt
  `LibraryIndex` as equal to the old one (it holds the same,
  already-mutated `Book` objects) and drops the write. That is
  why `LibraryModel.index` and `.rows` are declared with
  `neverEqualPolicy()`; without it "Remove from library" left
  the cover sitting on the wall until the next app start.
