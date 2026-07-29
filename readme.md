[![Build](https://github.com/sumatrapdfreader/sumatrapdf/actions/workflows/build.yml/badge.svg?branch=master)](https://github.com/sumatrapdfreader/sumatrapdf/actions/workflows/build.yml)
## SumatraPDF Reader

SumatraPDF is a multi-format (PDF, EPUB, MOBI, CBZ, CBR, FB2, CHM, XPS, DjVu) reader
for Windows under (A)GPLv3 license, with some code under BSD license (see
AUTHORS).

More Information:
* [Website](https://www.sumatrapdfreader.org/free-pdf-reader)
* [Manual](https://www.sumatrapdfreader.org/manual)
* [Developer Information](https://www.sumatrapdfreader.org/docs/Contribute-to-SumatraPDF)

---

## This fork: a library start page

**View ▸ Library start page** (`CmdToggleLibraryHome`) replaces the Frequently
Read list with a wall of cover art — taken from the file itself, or fetched
online when the file hasn't got any — and a sidebar of the series it found.
Point it at your books with the `Library.Roots` setting; left empty it
works them out itself (folders already analysed, then Documents, Downloads and
Desktop, then a bounded scan of every fixed drive). **Rescan the library**
(`CmdLibraryRescan`) picks up newly added books.

Folders are a hint, not the rule: a folder holding one series becomes that
series, a folder that merely holds other series (`Books`, `manga`, `ebooks`) is
skipped, and books sitting loose on disk are grouped by the wording they
actually carry. Sort the list A–Z, by genre, most books first or fewest — the
choice is remembered in `Library.Sort`.

You can also make your own **partitions**. Right-click any series, book or cover
for *Move to partition*, and pick an existing one or make a new one. Once two
things are in a partition it works out what they have in common — a distinctive
word in the titles, or the author, subjects, shelf and page make-up — and files
the rest of the matching books there on its own. Hovering a row it filed says
what it went on, *Take out of…* puts a book back and it stays out, and
partitions survive a rescan.

Click a cover to open the book where you left off, or its title for the book's
own page: author, year, page count, blurb, subjects, chapter list, the
characters, families and places BookNLP found in it, and the films and TV series
adapted from it. Right-click a cover for *Open book from last page read*, *Open
book from beginning* and *Play as Audio Book*.

The pages come from a small local service (`audiobook/library`) that SumatraPDF
starts on demand, on `Library.Port` (7863). It ships inside the
[Chatterbox TTS](https://github.com/nokel/Chatterbox-TTS-Extended) install;
SumatraPDF finds that folder itself, and `Library.ServiceDir` overrides it if
the search fails. Without the service the start page is the stock Frequently
Read list.

## Building from source

**Prerequisites**

* Visual Studio 2022 (the free **Build Tools** are enough — the full IDE is not
  required). Install the *Desktop development with C++* workload.
* [bun](https://bun.sh), for the build and code-generation scripts.

Everything else — MakeLZSA, premake, nasm and the rest — is checked into `bin/`.

**The normal build**

```
bun ./cmd/build.ts
```

This produces `out/dbg64/SumatraPDF.exe`. That is the binary to run and test; it
loads `libmupdf.dll`, which the build puts beside it. The statically linked
target is a *different* one called `SumatraPDF-static`
(`vs2022/SumatraPDF-static.vcxproj`), which `build.ts` does not update, so it
can be stale.

**Generated resources.** Two files under `.work/` are compiled into the exe and
are not in git. The built-in manual has to be produced once before the first
build of a fresh clone, or the resource compiler stops with `RC2135: file not
found`:

```
bun cmd/gen-docs.ts
```

That writes `.work/manual.dat`. The other file is `.work/translations.txt`,
which a pre-build step packs into `.work/translations.txt.lzsa`. That step
creates an empty `translations.txt` if none is there, so the build works
without any extra command and the UI is English-only. To fill it in:

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
`LNK1104: cannot open file 'libmupdf.dll'`, a copy of SumatraPDF is still
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

New source files must be registered in `premake5.files.lua`,
`vs2022/SumatraPDF.vcxproj`, `vs2022/SumatraPDF-static.vcxproj` and both of
their `.vcxproj.filters`. `vs2022/SumatraPDF-dll.vcxproj` no longer exists.

See `agents.md` for the rest of the house style (the `Str` type, `fmt()`,
include order, tests).

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

The payload is embedded at compile time. A pre-build step packs `libmupdf.dll`,
`PdfFilter.dll`, `PdfPreview.dll` and `sumatrapdf-tool.exe` into
`out/rel64/InstallerData.dat` with `bin/MakeLZSA.exe`, and the `INSTALL_PAYLOAD_ZIP`
define compiles that archive in as the `IDR_DLL_PAK` resource. Nothing extra
needs to ship alongside it.

`out/rel64/SumatraPDF-install.exe` is then a self-contained installer — a byte-for-byte
copy of the app, which is why the two files are the same size. Run it to
install normally, or `SumatraPDF-install.exe -s` to install silently. Run it
with `-h` for the full list of installer options.

**The same exe is also portable.** It no longer decides it must be the installer
merely because `libmupdf.dll` isn't sitting next to it: `gSingleExe` is true, so
it unpacks the DLL out of its own payload into its data folder — or into its own
directory, if anti-virus blocks the first — and carries on as the app.
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

* `-log-to-file <path>` writes a log.
* `SumatraPDF.exe -dde "[CmdName]"` fires a command at a running instance.
* `bun cmd/run-unit-tests.ts -dbg` runs the unit tests with readable output —
  but it looks only for Visual Studio 2026 and gives up if it isn't installed.
  With Build Tools 2022, build the `test_util` target with msbuild by its full
  path and run `out\dbg64\test_util.exe -for-ai` instead.
* `bun tests/all.ts` runs the regression tests.
