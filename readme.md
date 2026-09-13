[![Build](https://github.com/nokel/sumatrapdf/actions/workflows/build.yml/badge.svg?branch=master)](https://github.com/nokel/sumatrapdf/actions/workflows/build.yml)
## SumatraPDF Reader

SumatraPDF is a multi-format (PDF, EPUB, MOBI, CBZ, CBR, FB2, CHM, XPS, DjVu) reader
for Windows under (A)GPLv3 license, with some code under BSD license (see
AUTHORS).

More Information:
* [Website](https://www.sumatrapdfreader.org/free-pdf-reader)
* [Manual](https://www.sumatrapdfreader.org/manual)
* [Developer Information](https://www.sumatrapdfreader.org/docs/Contribute-to-SumatraPDF)

---

## This fork: a library start page and natural-speech Read Aloud

Two additions to SumatraPDF. The start page becomes a **library** of your books
instead of a list of recently opened files, and Read Aloud gains a second engine
that reads in **per-character voices**.

`master` is the whole thing: upstream SumatraPDF as of 18 Aug 2026
(`e6073b26`) with both additions merged on top. Upstream reorganised its UI
layer shortly before that — `src/wingui/` became `src/gui/`, and `Wnd`,
`Splitter` and `LabelWithCloseWnd` gave way to `VirtCtrl`, `VirtSplitter` and
`NewLabelWithClose` — so the fork's own windows are written against those newer
APIs. `library-work` is the same commit; it is where the work is done before it
lands on `master`.

### The library start page

**View ▸ Library start page** (`CmdToggleLibraryHome`) replaces the Frequently
Read list with a wall of cover art and a sidebar of the series, collections and
partitions in your library. The links at the bottom of the sidebar rescan,
choose whether finished books appear while a scan is still running
(`Audiobook.ProgressiveLibraryScan`), add a single book by hand, and switch back
to the classic Frequently Read page.

**Where it looks.** **Settings ▸ Library Indexing…** lists the folders the
Library searches, with *Add folder…* and remove. The list is
`Audiobook.LibraryRoots`. Until you edit it, the Library works it out on first
use: folders that already hold books in the library, then Documents, Downloads
and Desktop, then a bounded scan of every fixed drive. Removing every folder is
remembered as your choice, not treated as "never set up".

**The scan runs inside SumatraPDF.** **Rescan the library**
(`CmdLibraryRescan`) walks those folders, skipping system and program folders,
and opens every PDF, EPUB, MOBI, AZW3, FB2, CBZ and XPS it finds. Each file is
judged a book or a document from its page count, how much of it is pictures, the
folder it sits in and the wording on its front pages. Books go into the library.
Everything else (manuals, invoices, forms) goes on the **Deskpan**.

**A book carries its own identity.** The scan fingerprints every book from its
text layer. It skips OCR to stay fast, and the built-in Tesseract OCR reads
scanned books that have no text layer. The fingerprint and the book's library
record (shelf, series, partitions, cover, chapters, characters, reading stats)
are written into the book file itself:

* PDF: the document's private `PieceInfo` data
* EPUB, CBZ, zipped FB2 and XPS: a `META-INF/sumatra.book` entry in the archive
* other formats: a `<book>.sumatra` file beside it

Once a book has a fingerprint it keeps that identity. Moving, renaming or
re-tagging the file, or losing the local caches, does not make it a new book.

**Organising.** Series and collections are worked out from the books
themselves. Folders are a hint, not the rule. Sort the list A–Z, by genre, most
books first or fewest (`Audiobook.LibrarySort`). Right-click a book or series
for *Move to* a series or one of your own **partitions** (*New partition…*,
*Rename partition…*, *Delete partition*). Once a partition has members it files
matching books into it on its own. *Take out of …* removes a book, and the book
stays out. *Rename series…* and *Restore automatic series* / *Restore automatic
parent* undo manual moves.

**A book's page.** Click a cover to open the book where you left off, or its
title for the book's own page: *Edit metadata…* for title, author, series and
position in series, plus the chapter list, the characters and families and the
lore wiki (who knows about what) that the BookNLP analyser builds when the book
is read aloud, any films and TV series adapted from it, and when you last read
it, for how long and how often. *Change the cover* takes a picture file or a
region you drag out on one of the book's pages; when no cover can be found in
the book, one is looked up on Open Library. Right-click a cover for *Open book
from last page read*, *Open book from beginning* and *Play as Audio Book*.

**The Deskpan** has two piles, *Documents* and *Ignored*. Select files and *Move
to library* if the scan got one wrong, or *Ignore file*. *Remove from library*
on a book sends it to *Ignored*, and *Put back on the desk* undoes that. A file
left in *Ignored* longer than **Settings ▸ Options… ▸ Keep removed Library items
in Ignored for** (`Audiobook.LibraryIgnoreDays`, 30 days) becomes a permanent
exclusion. Automatic scans skip it, and only a manual add brings it back.

**Adding one book by hand.** *Manually add book to library…* picks a single
file, shows what the Library made of it, lets you add it as a book or a
document, and adds it.

**Your library is kept on this machine, next to the settings file.** The index
of books and series is `SumatraLibrary.txt`, the cover art is
`SumatraLibraryThumbs.txt` / `.dat`, and known fingerprints are
`SumatraLibraryFingerprints.txt` / `.dat`. SumatraPDF writes all of them. The
page reads them before anything else, so it opens straight onto your books and
still shows them when nothing else is running. Deleting these files is safe:
the records inside the books survive, and a rescan rebuilds the index.

**What still needs the Chatterbox install.** The series, genre and partition
grouping, the Deskpan piles and the single-book import are still worked out by
a small local service in the Chatterbox install (`audiobook/library`).
SumatraPDF starts it on demand on `Audiobook.LibraryPort` (7863) and sends it
what the scan found. Without it, the page still draws your library from the
files above, but the automatic rescan at startup is skipped.

### The Chatterbox audiobook engine

This build adds a second Read Aloud engine. Instead of the built-in Windows TTS, 
it hands the document to a local [Chatterbox TTS](https://github.com/nokel/Chatterbox-TTS-Extended) 
install, which reads the book in **per-character voices** — a local LLM works out 
who speaks each line once per book, each character is cast to a trained voice, and 
the narrator reads everything else. The reading is highlighted in the window as it goes.

SumatraPDF is the whole UI for it. There is no separate Python window: the
engine runs headless and SumatraPDF drives it.

* **Read Aloud ▸ Voices ▸ Use Chatterbox voices** — the engine switch. Ticked,
  Read Aloud uses Chatterbox and the Windows voices grey out; unticked, you get
  ordinary Windows TTS. Only one of the two ever speaks.
* **The playback bar** — play from the top, previous line, pause, play, stop,
  skip line, skip page. Stop remembers where you were, so Read Aloud picks up
  from that line next time.
* **Read Aloud ▸ Audiobook Characters** — a panel docked down the left, beside
  the page. Every character the LLM found, the voice each one is cast to, and
  buttons to test or train a voice. Opening it starts the engine (loading the
  TTS model) without reading anything.

  Analysis is a button there, never a surprise on the way to reading: a novel
  is hundreds of LLM calls and takes minutes. You choose how much of the book
  to do, and Stop keeps what it has worked out so far — unanalysed lines just
  read in the narrator's voice.

**Analysing on more than one computer.** The panel's *Analyse on* list shows
every machine sharing the work, each with the model it would use and whether it
answers. *Look for computers on my network* finds them (see `audiobook/discover.py`);
you can also type an address in. Both LM Studio and Ollama work, and a computer
running both is two workers, not one — they use different ports and don't know
about each other.

**No path to configure.** SumatraPDF finds the Chatterbox install itself
(`AudiobookResolveDir` in `src/SumatraPDF.cpp`): the saved setting if it still
resolves, then the usual spots under Documents and the user profile, then a
bounded scan of the root of every fixed drive. It scans drive roots because the
models run to gigabytes and get put on a real disk, never in OneDrive.

If it can't find one, it says so in the window, and you can set the folder
yourself in **Settings ▸ Advanced Settings…**. Type `audiobook` in the filter
box to narrow the list to these sixteen:

| setting | meaning |
|---|---|
| `Audiobook.UseChatterbox` | same switch as the Voices menu item |
| `Audiobook.ChatterboxDir` | the install folder; auto-detected |
| `Audiobook.PythonExe` | empty = `<ChatterboxDir>\.venv-amd\Scripts\pythonw.exe` |
| `Audiobook.TtsServerPort` | port of the headless TTS server (7861) |
| `Audiobook.LmStudioUrl` | the local LLM used to work out who speaks each line |
| `Audiobook.LmModel` | which model to analyse with; empty = decide automatically |
| `Audiobook.LmUrls` | other computers to analyse on; the panel fills this in |
| `Audiobook.NarratorVoice` | empty = the first available trained voice |
| `Audiobook.Analyzer` | who works out the speakers: `llm` or `booknlp` |
| `Audiobook.CharSort` | how the Characters panel orders the cast |
| `Audiobook.LibraryHome` | same switch as the View menu's Library start page |
| `Audiobook.LibraryRoots` | folders to look for books in; edited in Settings ▸ Library Indexing… |
| `Audiobook.LibraryPort` | port of the library service (7863) |
| `Audiobook.LibrarySort` | how the library orders the series list |
| `Audiobook.ProgressiveLibraryScan` | show finished books while a scan is still running |
| `Audiobook.LibraryIgnoreDays` | days a removed file stays in Deskpan ▸ Ignored before it is excluded for good (30) |

(`Audiobook.SidebarDx`, the width of the Characters panel, and
`Audiobook.LibraryRootsConfigured`, which records that the folder list has been
set up, are kept for you and are not shown in the dialog.)

All are optional; `ChatterboxDir` fills itself in once the install is found.
Double-click a setting to edit it (a bool toggles, Enter confirms, Esc cancels),
and bold marks a value you've changed. Save writes the settings file and reloads
it.

**Settings ▸ Advanced Options…** opens the same settings as raw text in your
editor, if you prefer that. Note that the text file omits settings whose value
is empty, so `PythonExe` and `NarratorVoice` won't appear there until they're
set — the Advanced Settings dialog always lists them.

Without a Chatterbox install, or with the box unticked, this is just SumatraPDF.

## Building from source

**Prerequisites**

* Visual Studio 2022 (the free **Build Tools** are enough — the full IDE is not
  required). Install the *Desktop development with C++* workload.
* [bun](https://bun.sh), for the build and code-generation scripts.

Everything else — MakeLZSA, premake, nasm and the rest — is checked into `bin/`.
The OCR libraries are vendored too: Leptonica and Tesseract are built from
`ext/a-leptonica` and `ext/a-tesseract` as ordinary projects in the solution, and
`ext/a-tesseract/tessdata/eng.traineddata` is the English model. There is no
separate CMake step.

**The normal build**

```
bun ./cmd/build.ts
```

This produces `out/dbg64/SumatraPDF.exe`. That is the binary to run and test; it
loads `libsumatrapdf.dll`, which the build puts beside it, and `build.ts` copies
`tessdata/eng.traineddata` beside it for OCR. A build made with msbuild alone
finds the model in `ext/a-tesseract/tessdata` instead. (Through 3.6 that DLL
was called `libmupdf.dll`; a stale copy under the old name may still be sitting
in `out/`, and is not used.) The statically linked target is a *different* one
called `SumatraPDF-static`
(`vs2022/SumatraPDF-static.vcxproj`), which `build.ts` does not update, so it
can be stale.

**Generated resources.** One file under `.work/` is compiled into the exe and is
not in git: `.work/embedded.dat`, the `IDR_EMBEDDED_PAK` resource. It has to be
produced once before the first build of a fresh clone, or the resource compiler
stops with `RC2135: file not found`:

```
bun cmd/gen-docs.ts
```

That packs the built-in manual, `marked.min.js`, `mermaid.min.js` and
`.work/translations.txt` into `.work/embedded.dat` (via `cmd/pack-embedded.ts`,
which the VS prebuild also runs). It creates an empty `translations.txt` if none
is there, so the build works without any extra command and the UI is
English-only. To fill it in:

```
bun cmd/trans-dl.ts
```

Without the upstream maintainer's `TRANS_UPLOAD_SECRET` that script makes no
network calls at all and writes the same empty file — the normal outcome for
anyone who isn't the maintainer. Don't set that variable to get around it: with
it set, the script uploads this fork's strings to apptranslator.org.

**Building a single target with msbuild**

Faster when you only touched `src/` and just want the app relinked:

```
msbuild vs2022\SumatraPDF.sln -t:SumatraPDF -p:Configuration=Debug -p:Platform=x64
```

Use `-p:Configuration=Release` for the optimized build. If the link fails with
`LNK1104: cannot open file 'libsumatrapdf.dll'`, a copy of SumatraPDF is still
running and holding the DLL — close it and build again.

The build treats warnings as errors, so a warning fails the build.

**Code generation.** Commands, settings and command-line flags are generated,
not hand-written. After editing `cmd/gen-commands.ts`, `cmd/gen-settings.ts` or
`cmd/gen-flags.ts`, regenerate `src/Commands.*`, `src/Settings.*` and
`src/Flags.cpp` before building. `bun cmd/gen-code.ts` runs the lot, but its
virtual-key step shells out to `cl` and dies with `Executable not found in
$PATH: cl` unless bun can resolve the compiler; running the one generator you
need — `bun cmd/gen-commands.ts` or `bun cmd/gen-settings.ts` — always works. A
new command goes at the *end* of the list in `cmd/gen-commands.ts`, just before
`CmdNone`, so existing ids don't shift.

`gen-settings.ts` writes two headers, not one: `src/Settings.h` for the settings
file, and `src/LibraryData.h` for the library index (`SumatraLibrary.txt`), which
is stored with the same SquareTree code. One run emits both, so check
`git diff src/Settings.h docs/md/Advanced-options-settings.md` after touching the
library structs.

The project files in `vs2022/` are generated. New source files are registered in
`premake5.files.lua` (or `premake5.lua` for a whole new project), and
`bun cmd/premake.ts` regenerates the `.sln`, `.vcxproj` and `.vcxproj.filters`
files; don't edit those by hand. `vs2022/SumatraPDF-dll.vcxproj` no longer
exists. Anything under `src/` also has to be listed in
`cmd/helper/mingw-build.ts` — the Linux/Wine cross-compile keeps its own source
list — or excluded there deliberately. `bun tests/lint-mingw-sources.ts` checks
this; it runs in `run-almost-all` and in the Linux CI job, so a forgotten file
reddens both.

## Building the installer

The installer is a real Windows installer executable, not a script. It is the
application itself: a copy of the app exe whose filename contains `install` runs
as the installer. So building the installer means building the Release app and
renaming a copy.

```
msbuild vs2022\SumatraPDF.sln -t:SumatraPDF -p:Configuration=Release -p:Platform=x64
copy out\rel64\SumatraPDF.exe out\rel64\SumatraPDF-install.exe
```

Two things have to be true, and `IsInstallerAndNamedAsSuch()` in
`src/SumatraStartup.cpp` checks both: the filename must contain `install` (and
not `uninstall`, which is the uninstaller's job), **and** the exe must actually
carry the installer payload. Only the `SumatraPDF` target does; `SumatraPDF-static`
is built without the `INSTALL_PAYLOAD_ZIP` define, so renaming a static build
gets you an app with a misleading name and nothing else. `SumatraPDF.exe -install`
does the same job without the rename, and stops with *Not a valid installer* if
the payload isn't there.

The payload is embedded at compile time. A pre-build step packs
`libsumatrapdf.dll`, `PdfFilter.dll`, `PdfPreview.dll` and
`sumatrapdf-tool.exe` into `out/rel64/InstallerData.dat` with
`bin/MakeLZSA.exe`, and the `INSTALL_PAYLOAD_ZIP` define compiles that archive
in as the `IDR_DLL_PAK` resource. Nothing extra needs to ship alongside it.

`out/rel64/SumatraPDF-install.exe` is then a self-contained installer — a byte-for-byte
copy of the app, which is why the two files are the same size. Run it to
install normally, or `SumatraPDF-install.exe -s` to install silently. Run it
with `-h` for the full list of installer options.

**The same exe is also portable.** It no longer decides it must be the installer
merely because `libsumatrapdf.dll` isn't sitting next to it: `gSingleExe` is
true, so it unpacks the DLL out of its own payload into its data folder — or
into its own directory, if anti-virus blocks the first — and carries on as the
app.
Installing is something you ask for, by the filename or by `-install`, not the
default for a lone exe. (The old behaviour is still in the tree, as
`ForceRunningAsInstaller()` behind `!gSingleExe`.)

To uninstall, use Windows' Apps & Features, or run the installed
`SumatraPDF.exe -uninstall`.

## Running and testing

Pass `-for-testing` whenever you launch a build for an ad-hoc check:

```
out\dbg64\SumatraPDF.exe -for-testing <file.pdf>
```

It starts a separate instance, only opens the files you name, and — importantly —
**does not save settings**, so a test run can't overwrite your real
configuration.

Other things worth knowing:

* `-log-to-file <path>` writes a log; the audiobook engine keeps its own at
  `Chatterbox-TTS-Extended-main/audiobook/cache/engine.log`.
* `-appdata <dir>` puts the settings file and the library index somewhere else,
  which is how to try the library on a throwaway catalogue. Point `PythonExe`
  in that settings file at a path that doesn't exist and the library service
  can't start, so the page can only draw from `SumatraLibrary.txt`.
* `SumatraPDF.exe -dde "[CmdName]"` fires a command at a running instance.
* `bun cmd/run-unit-tests.ts -dbg` runs the unit tests with readable output —
  but it looks only for Visual Studio 2026 and gives up if it isn't installed.
  With Build Tools 2022, build the `test_util` target with msbuild by its full
  path and run `out\dbg64\test_util.exe -for-ai` instead.
* `bun tests/run-almost-all.ts` is the fast regression suite; `bun tests/run-all.ts`
  runs that and then the slow tests, stopping at the first failure. A single test
  runs on its own, e.g. `bun tests/issue-1136.ts`.
