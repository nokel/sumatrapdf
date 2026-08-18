This is a C++ program for Windows, using mostly win32 windows API functions. The full Windows GUI app remains Windows-only for now; we are porting **non-UI** code so it also compiles on macOS and Linux, starting with `src/base/`. There is also an early Cocoa macOS app under `src/mac/` that can open a command-line document, render the first page through the existing engines, and display it.

We don't use STL but our own string / helper / container functions implemented in src\base directory

Assume that Visual Studio command-line tools are available in the PATH environment variable (cl.exe, msbuild.exe etc.)

Our code is in src/ directory. External dependencies are in ext/ directory

`ext/mupdf` is vendored and we edit it in place. Every change we make there must
also be recorded as a patch in `ext/patches/` (one logical change per `.patch`,
against the mupdf revision in `ext/versions.txt`) in the **same commit** — see
`ext/patches/README.md`. A change that only lives in the vendored tree is one
the next mupdf update silently drops.

To build run: `bun cmd/build.ts -debug` (or `-release`, `-asan`, and the other modes shown by `bun cmd/build.ts -help`). Called with no options it prints usage and exits; unknown options print an error plus usage and exit unsuccessfully.

Keep `cmd/build.ts` as the single build entry point. Build-mode implementation modules live under `cmd/helper/` and are not invoked directly, except for internal delegation such as the WSL launcher.

This creates ./out/dbg64/SumatraPDF.exe executable. The static build target is SumatraPDF-static and produces ./out/<config>/SumatraPDF-static.exe.

To run a Linux build from Windows, use `bun cmd/build.ts -linux` (defaults to `-asan`) or add `-debug` / `-release`. To cross-compile the Windows exe with mingw inside WSL, use `bun cmd/build.ts -wine` (optional `-clean`, `-run`). The unified build command delegates these Windows-hosted modes to `cmd/helper/wsl-build.ts`. Both require a WSL distro named `Ubuntu` and bun in that distro; Linux deps are `sudo sh cmd/ubuntu-install-deps.sh`.

To run the macOS build on the remote Mac, use `bun cmd/build.ts -mac-remote -branch <temporary-branch> -debug` (or `-release` / `-asan`, optionally with `-clean`). It connects with `ssh kjk@macbook-pro-14`, changes to `src/sumatrapdf`, verifies that the remote checkout is clean, fetches and switches to the temporary branch, runs `cmd/build.ts -mac`, and restores the original remote checkout on success or failure. The macOS build compiles the dependency/base libraries, builds `out/mac-<config>64/test_util`, runs it with `-for-ai`, builds `test_engines`, and builds `SumatraPDF.app`.

To run unit tests with AI-friendly diagnostics, run `bun cmd/run-unit-tests.ts -dbg` (or `-rel` / `-asan`). It builds the 64-bit `test_util.exe`, runs it with `-for-ai`, captures output under the matching `out/<config>/unit-tests-*.txt`, and prints assertion/crash callstacks without waiting for debugger UI.

To debug run: `windbgx -Q -o -g ./out/dbg64/SumatraPDF.exe`

When launching SumatraPDF.exe for ad-hoc testing, always pass the `-for-testing` cmd-line flag. It starts a new instance (won't interfere with an already running SumatraPDF), doesn't restore the previous session (only loads files given on the cmd-line) and doesn't save settings (won't overwrite the settings of the user).

After making a change to a .cpp, .c or .h file under `src/` (and before running build.ts), run clang-format on those files to reformat them in place. Do **not** clang-format third-party / vendored code (`ext/`, etc.) — keep edits there minimal and match the existing local style.

After changing a .ts file under `cmd/` or `tests/`, run `bun cmd/format.ts` — it runs prettier over `cmd/**/*.ts` and `tests/**/*.ts` and then clang-formats the C/C++ sources. Use `bun cmd/format.ts -ts` to run only the prettier pass (no Visual Studio / clang-format needed). Prettier settings live in `.prettierrc.json` (`printWidth` 120, `endOfLine` lf) and `.prettierignore` (vendored code, build output, scratch `tmp/` dirs, and the generated `docs/md/Advanced-options-settings.md`). For other prettier-owned files (.js / .json / .md) run `bunx prettier --write <files>` on the files you touched.

Never commit changes automatically. Always wait for explicit command to commit changes.

When committing a fix for a GitHub issue, end the commit message's **first line** with `(fixes #<issue-no>)`, e.g. `fix crash on committing an empty zoom value (fixes #5909)`. That is the line GitHub shows everywhere, so the link belongs there, not buried in the body.

When committing work done with AI assistance, append the user prompt(s) that produced the change at the very end of the commit message as a single line: `prompt: ...`. If there were multiple prompts, squash them into one concise line. Record the substantive request only — omit meta-instructions such as "commit", "push", "check work", or "verify".

## Cross-platform porting (macOS / Linux)

We are making non-UI library code compile on macOS and Linux while keeping the Windows build working. **Start with `src/base/`**; other `src/` areas follow once base is portable.

### Scope

- **In scope:** platform-neutral logic and OS abstractions (files, paths, time, threading, memory, strings, etc.) under `src/base/` and later other non-UI `src/` trees; the early native macOS viewer under `src/mac/`.
- **Out of scope for now:** porting the full Windows UI, Win32 windowing, Windows menus, printing UI, installer, and anything that depends on those.

### macOS app (`src/mac/`)

`src/mac/` is an early Cocoa application, not a port of the Windows UI. Keep it small and native for now:

- Build it with `bun cmd/build.ts -mac -debug` (or `-release` / `-asan`). The app bundle is `out/mac-dbg64/SumatraPDF.app` for debug builds.
- Run it with a document path using `open out/mac-dbg64/SumatraPDF.app --args <path>`, for example `open out/mac-dbg64/SumatraPDF.app --args ./ext/a-zlib/zlib.3.pdf`. Relative paths from the repo should work; absolute paths are fine.
- The current app only opens the first command-line file, renders page 1 through the existing engine layer, displays it, and supports standard macOS Quit / `Cmd-Q`.
- Keep Objective-C / Cocoa code in `.mm` files under `src/mac/`. Do **not** include `base/Base.h` or other Sumatra headers in files that import Cocoa/AppKit: Apple headers define names such as `Size` that conflict with Sumatra types. Use a small C/C++ bridge (`SumatraMacEngine.*`) between Cocoa code and engine/base code.
- When adding mac-specific build inputs, update `MAC_APP_SOURCES` in `cmd/helper/mac-build.ts`.

### Platform-specific source files

When a source file needs platform-specific code, keep the files in the same module directory and use a platform suffix:

| Suffix   | Used on             | Purpose                                      |
| -------- | ------------------- | -------------------------------------------- |
| `_win`   | Windows             | Win32 and other Windows-only implementations |
| `_posix` | macOS **and** Linux | Code shared by both Unix-like targets        |
| `_mac`   | macOS only          | Darwin-specific code not shared with Linux   |
| `_linux` | Linux only          | Linux-specific code not shared with macOS    |

**`_posix` is for code common to Linux and macOS** — prefer it over duplicating the same logic in `_mac` and `_linux`. Use `_mac` or `_linux` only when the two Unix platforms genuinely diverge.

Example layout:

```
src/base/File.h          # shared declaration (platform-neutral API)
src/base/File.cpp        # shared implementation, if any
src/base/File_win.cpp    # Windows implementation
src/base/File_posix.cpp  # macOS + Linux implementation
src/base/File_mac.cpp    # macOS-only pieces (when posix isn't enough)
src/base/File_linux.cpp  # Linux-only pieces (when posix isn't enough)
```

Not every file needs all four platform variants — only split when the implementation is platform-dependent. Keep portable code in the unsuffixed file (`src/base/Foo.cpp`) and move **only** the non-portable parts into the appropriate `_win` / `_posix` / `_mac` / `_linux` file.

### Guidelines

- **Preserve the public API.** Headers at the module root (`src/base/Foo.h`) should expose the same functions/types on every platform; platform differences stay in suffixed `.cpp` files.
- **No `#ifdef` sprawl in shared headers** when a platform-specific `.cpp` split is clearer. Small include-guarded typedefs or macros in a shared header are fine.
- **Prefer POSIX APIs in `_posix` files** (`open`, `read`, `stat`, `pthread`, etc.) and native APIs in `_win` files (Win32). Use `_mac` / `_linux` files for OS-specific extensions (e.g. FSEvents vs inotify).
- **Keep Windows green.** Every change must still build and pass tests on Windows (`bun cmd/build.ts -debug`; use `bun cmd/run-unit-tests.ts -dbg` for base/test_util work). Do not break the existing Windows target while adding macOS/Linux support.
- **Keep macOS green.** `bun cmd/build.ts -mac -debug` builds the macOS dependency/base libraries, builds and runs `test_util` with `-for-ai`, builds `test_engines`, and links `SumatraPDF.app`. From Windows, use `-mac-remote` on a temporary branch for portability changes.
- **Keep Linux green.** From Windows, `bun cmd/build.ts -linux -debug` (or `-release` / `-asan`) uses the Ubuntu WSL distro. Use `bun cmd/build.ts -wine` to cross-compile the Windows exe with mingw inside WSL.
- **Make tests platform-aware.** Preserve shared behavior tests on every platform where possible. Guard Windows-only expectations (drive letters, backslash-only paths, Win32 command-line parsing, UI/printing behavior, and similar platform specifics) with `#if OS_WIN`, and add POSIX expectations when the behavior is meant to be portable.

### Remote macOS verification from Windows

When doing macOS/Linux portability changes from a Windows machine, test them on the remote Mac by building from a temporary branch:

1. Create a temporary branch locally, e.g. `git switch -c tmp/mac-port-<topic>`.
2. Commit the portability changes on that temporary branch and push it to origin, e.g. `git push -u origin tmp/mac-port-<topic>`. This temporary commit is for remote build verification; still do not make the final feature commit unless the user explicitly asks.
3. Run the remote build from Windows with `bun cmd/build.ts -mac-remote -branch tmp/mac-port-<topic> -debug`. The command aborts if the remote checkout is dirty, fetches and switches to the temporary branch, builds, runs macOS `test_util`, and restores the original remote branch or detached checkout on success or failure.

## C/C++ #include conventions

We rely on a controlled include order rather than self-sufficient headers (this is the inverse of the common "own header first" advice). In a `.cpp`/`.c` file:

- `#include "base/Base.h"` comes **first**. It pulls in `<windows.h>` plus many common C/C++ headers and our base string / container / `ByteSlice` helpers, which most other headers assume are already available.
- Then the remaining includes (other `base/` headers, then the rest of the project headers).
- The file's **own header goes at the end** of the include list (after the headers it depends on, since headers are not self-sufficient) — only `SumatraLog.h` may come after it.
- `SumatraLog.h`, if present, is the **last** include.

Do **not** use `#pragma once` in `.h` files.

## Put explanatory comments in `.cpp`, not `.h`

**Do not put prose function comments in headers.** Headers are re-parsed by every
translation unit that includes them, so comments in a `.h` cost compilation time
on every include. Keep the header declaration terse (ideally a single line) and
put the explaining comment on the **definition** in the corresponding `.cpp`.

For a function declared in `Foo.h` and defined in `Foo.cpp` (or `Foo_win.cpp` /
`Foo_posix.cpp` / etc.), the doc comment lives **only** above the definition in
the `.cpp` — not on the declaration in the `.h`.

```cpp
// Foo.h — declaration only, no prose comment
void CollectNonDefaultRegisteredExtensions(StrVec& out);

// Foo.cpp — comment on the definition
// Extensions we registered for Open With where something else is the default.
void CollectNonDefaultRegisteredExtensions(StrVec& out) {
    ...
}
```

Comments that have no `.cpp` counterpart stay in the header: those documenting a
struct/class, an `enum`, a macro, a constant, a section banner (`//--- …`), or an
`inline`/templated function that is _defined_ in the header (there is nowhere
else to put them). When in doubt for a free function or method with a `.cpp`
body: put the comment in the `.cpp`.

## `fmt()` is the type-safe formatter

We use our own `Str` value type (a `char*` + `int len`) for strings instead of
raw `char*` / `std::string`. The most-used formatter is `fmt()`, a macro
`#define fmt(...) str::FormatTemp(__VA_ARGS__)` (base/StrFormatParse.h). It formats into
the temp arena and returns a `TempStr`, so call sites read `fmt("page %d", n)`.

`fmt()` is **type-safe**, not a raw `vsnprintf` wrapper:

- The **format string** is a plain `const char*` (almost always a string
  literal). When it's a `Str` instead — most commonly a `_TRA("...")`
  translation, which returns a `Str` — pass its `.s`, e.g.
  `fmt(_TRA("page %d").s, n)`.
- Each **variadic arg** is wrapped in a `str::FmtArg`, which has explicit
  constructors for `Str`, `WStr`, `char`, the integer/float types, and
  `const void*`. Raw `char*` / `const char*` / `wchar_t*` constructors are
  `= delete`d. So for a `%s` you pass the `Str`/`WStr` **object**, never a
  `char*` — e.g. `fmt("%s", name)` where `name` is a `Str`, and writing
  `fmt("%s", name.s)` is a compile error, not a footgun. `%s` accepts both
  `Str` and `WStr` (a `WStr` is auto-converted to UTF-8).
- Because args carry their own `.len`, a `Str` arg need not be NUL-terminated.

For a `%s` fed a **string literal**, wrap it with `StrL(...)` (see next section)
so the length is computed at compile time: `fmt("%s", StrL("done"))`.

`logf` is a variadic function template in `base/Base.h` that formats via
`str::FormatTemp(...)` and routes the result through `log()`, so it follows the
same type-safe rules. Base only declares `log()`; the app implements it
(`SumatraLog.cpp`, declared in `SumatraLog.h`).

Functions that take an already-formatted `Str` (so the caller formats with
`fmt(...)`): `str::Builder::Append`, `dbglayout`, `MaybeDelayedWarningNotification`.

## Make a `Str`/`WStr` from a string literal with `StrL` / `WStrL`

When constructing a `Str`/`WStr` from a **string literal**, use `StrL("...")` /
`WStrL(L"...")`, not `Str("...")` / `WStr(L"...")`. `StrL`/`WStrL` compute the
length at compile time (`sizeof(lit) - 1`), while `Str("...")` / `WStr(L"...")`
do a runtime `strlen`/`wcslen`. So e.g. `fmt("%s", StrL("done"))`, not
`fmt("%s", Str("done"))`. Use `Str(x)` / `WStr(x)` only when `x` is a runtime
`char*` / `wchar_t*` whose length isn't known at compile time.

## Use `len(x)`, not `x.len`

`Str`, `WStr`, `Vec<T>`, `StrVec`, `StrQueue` and `str::Builder` all expose a
`.len` field, and there is a free `len()` overload for each. Prefer the free
function:

    for (int i = 0; i < len(s); i++)     // yes
    for (int i = 0; i < s.len; i++)      // no

It reads the same for every container, it works for the ones whose `.len` is not
an `int` (`str::Builder::len` is a `u32`, so `.len` drags unsigned arithmetic
into comparisons), and a type that later hides or renames the field keeps
working. Reach for `.len` only when you need to **assign** to it.

## NUL-terminate `Str`/`WStr` before C/Win32 APIs

A `Str`/`WStr` is a `{ptr, len}` view and may be a **substring that is not
NUL-terminated** at `s[len]`. Passing its `.s` to a C runtime or Win32 API that
reads a NUL-terminated string (e.g. `CreateFileW`, `GetFileAttributesW`,
`CommandLineToArgvW`, `strlen`, `_wfopen`, `%s` in raw `printf`) can read past
the intended end.

To get a guaranteed NUL-terminated C-string, use `CStrTemp(Str)` → `char*` or
`CWStrTemp(WStr)` → `WCHAR*` (both copy into the temp arena). Prefer these over
`str::DupTemp(x).s` at the call site — the name states the intent:

    BOOL ok = GetFileAttributesEx(CWStrTemp(dir), GetFileExInfoStandard, &fi);

The encoding converters `ToWStrTemp(Str)` / `ToUtf8Temp(WStr)` already return
length-aware, NUL-terminated output — pass the **object** (`ToWStrTemp(path)`),
never `.s` (`ToWStrTemp(path.s)` re-introduces the `strlen`/over-read footgun).

Caveat: a `*Temp` result lives in the temp arena (reset each message loop), so
don't stash its pointer for later use (e.g. a `WNDCLASS::lpszClassName` kept
across calls) — keep the caller's stable string instead.

## Adding a new advanced setting

To add a new advanced setting:

- add definition in cmd/gen-settings.ts
- run "bun cmd/gen-code.ts" (or "bun cmd/gen-settings.ts") to regenerate src/Settings.h (it also re-emits the settings docs)

`gen-settings.ts` emits a **second** header from the same machinery:
`src/LibraryData.h`, the struct behind `SumatraLibrary.txt` (see the library
section below). Adding a field there is the same two steps; check
`git diff --quiet src/Settings.h docs/md/Advanced-options-settings.md` afterwards,
because both headers come out of one run.

`buildStruct` only *defines* a nested struct when the field's name matches the
struct's (`LibraryBooks` → `LibraryBook`); a field that merely names a struct
declared elsewhere (`PropWinPos` → `Point`) refers to it. Relaxing that guard
makes `Settings.h` emit its own `Size` and `Rect` over the ones in
`GeoTypes.h` — name the field to fit the convention instead.

## Adding a new command

To add a new command:

- add to cmd/gen-commands.ts, always at the end of the list (before the "CmdNone" command)
- run "bun cmd/gen-code.ts" (or "bun cmd/gen-commands.ts") to regenerate src/Commands.h and src/Commands.cpp
- document in docs/md/Commands.md
- add an entry to the **New commands** list at the end of the **next** section in docs/md/Version-history.md (see below)

## DocProp name maps are generated

The name↔`DocProp` `SeqStrNum` maps (`epubPropsMap`, `pdfCreatorPropsMap`, `mupdfPropsMap`, `pdfPropNames`) are generated by `cmd/gen-code.ts` between `// @gen-start docprop-*` / `// @gen-end docprop-*` markers — **don't hand-edit them**. Each `SeqStrNum` entry's number must be zigzag-LEB128 encoded (`varIntCString`); hand-writing raw bytes silently broke every lookup (title read as author, etc.). To change a mapping, edit the `docPropMaps` data in `cmd/gen-code.ts` and run `bun cmd/gen-code.ts`; it re-emits the maps with correct encoding and self-verifies first/middle/last of each. The same applies to `gVirtKeysNum` in `ShortcutParse.cpp` (`// @gen-start virt-keys-num`).

## Adding a new cmd-line flag

To add a new cmd-line flag:

- add to cmd/gen-flags.ts
- run "bun cmd/gen-code.ts" (or "bun cmd/gen-flags.ts") to regenerate src/Flags.cpp
- implement handling in Flags.cpp
- document in docs/md/Command-line-arguments.md when appropriate
- add an entry to the **New command-line arguments** list at the end of the **next** section in docs/md/Version-history.md (see below)

## Version history (docs/md/Version-history.md)

When documenting a release (usually the **next** section at the top):

- **Do not document bug fixes.** Version history is for features and behavior changes, not a changelog of defects. A plain `fix <something>` bullet does not belong there — the commit message and the GitHub issue already record it. Only mention a fix when it comes with a user-visible change worth describing on its own (a new setting, a new command, a different default), and then describe _that_, not the bug.
- Main bullets describe features and behavior changes in prose. Mention menus, shortcuts, and user-visible effects — not a stream of `add CmdFoo` / `add -flag` bullets.
- At the **end** of the version section, add consolidated lists (only for things **new** in that version):

  **New commands:**

  - `CmdFoo` : "Foo" — optional note (shortcut, palette-only, etc.)

  **New command-line arguments:**

  - `-foo <arg>` : brief description (include `(fixes #N)` here if relevant)

- New `-print-settings` tokens belong under **New command-line arguments** (e.g. `-print-settings` tokens: `stretch`, `center`, …).
- Command **changes** (renames, removals, new arguments on existing commands, shortcut rebinding) stay in the main bullets, not in **New commands**.
- Removed flags can be noted in the main bullets; do not list them under **New command-line arguments**.
- **Embedded app data (`.work/embedded.dat`):** one LzSA archive packed by `bun cmd/pack-embedded.ts` (also run from `gen-docs.ts` and the VS prebuild). Holds `translations.txt`, `marked.min.js`, `mermaid.min.js`, and the in-app manual files from `.work/docs/`. Linked as `IDR_EMBEDDED_PAK` via `src/SumatraPDF.rc`. After editing manual sources, run `bun cmd/gen-docs.ts` (or `pack-embedded.ts` if docs are already generated) before testing help/markdown mermaid locally; CI/release builds run gen-docs automatically. The app renders markdown on demand in WebView2 via `docs/gen_docs.render.js` and markdown-it. Use `bun cmd/gen-docs.ts --preview` to also emit pre-rendered HTML under `.work/www/` for offline browser preview.

## Bug reproduction / test files

We keep test and reproduction files for bugs in `C:\Users\kjk\OneDrive\!sumatra\bugs\`, named after the GitHub issue number:

- a single file is `bug-<bug-no><rest>` (e.g. `bug-534.pdf`)
- if a repro needs more than one file, use a directory `bug-<bug-no><rest>\`

When fixing a bug, look there first for an existing repro file for that issue and use it. When you create a test/repro file while working on a bug, save it there (using the naming above) after fixing the bug.

## Writing tests

Tests live in tests/ and are run with bun (e.g. `bun tests/issue-5633.ts`). Naming convention, keyed by the GitHub issue number being tested:

- the test script is `tests/issue-<number>.ts`
- if it needs a small number (one or two) of extra files, name them `tests/issue-<number>.<rest>`
- if it needs more files, or a file must live in a directory, put them in `tests/issue-<number>-data/`

### Which tests to run after a change

Do **not** run the full suites (`tests/run-almost-all.ts`, `tests/run-all.ts`, `tests/run-pre-release.ts`, or unrelated
unit tests) to verify a change. The release process runs them; that is what it is for.
A full run costs ~4-5 minutes and tells you almost nothing about the ten lines you just
edited. Run broader suites only when the user explicitly asks.

Instead, run only what the change can plausibly break:

- a named issue fix → `bun tests/issue-<number>.ts`
- a change to code an existing test covers → that test, found by grepping `tests/` for the
  feature, command id, or setting name involved
- base/`test_util` work → `bun cmd/run-unit-tests.ts -dbg`
- nothing covers it → say so instead of running everything as a substitute. A targeted
  manual check (launch with `-for-testing`, screenshot, probe log) is worth more than a
  green suite that never touched the code.

Never edit `tests/run-almost-all.ts` or `tests/run-all.ts` to skip tests so a run gets further. If a test fails and you
suspect it is unrelated, check it out on a clean tree (`git stash`) and run it standalone
several times - some are environment- and focus-dependent and fail intermittently
regardless of the change (`issue-1136` and `issue-2254` have both done this). One passing
run and one failing run is not evidence; compare several runs on each side.

Structure of each test (so they compose in tests/run-almost-all.ts / tests/run-all.ts):

- each `tests/issue-<number>.ts` exports `export async function testit(): Promise<void>` that runs the test logic and THROWS on failure (returns normally on success). It must NOT call `process.exit` or build the app itself.
- end the file with a standalone runner so it can still be run directly:
  ```ts
  if (import.meta.main) {
    await runStandalone(testit);
  }
  ```
  `runStandalone` (from `tests/util.ts`) builds the app (unless `--no-build`), runs `testit()`, and exits 0 on pass / 1 on failure.
- shared helpers (`EXE` path, `buildApp`, `runStandalone`) live in `tests/util.ts` — use them instead of re-implementing per file.
- register every new test in `tests/run-almost-all.ts` (import its `testit` and add it to the `tests` array). If the test cannot be made faster (print-to-PDF, LaTeX, a measured wait, high-zoom tile settle, a huge fixture, copying the exe next to restrict.ini), add it to `slowTests` in `tests/run-all.ts` instead. `bun tests/run-almost-all.ts` is the fast suite; `bun tests/run-all.ts` runs that then the slow tests, stopping at the first failure.
- the daily GitHub Actions job (`.github/workflows/windows-daily.yml`) builds the debug ASan target and runs `bun tests/run-github-ci.ts`. That runner takes the `run-all.ts` list minus `excludedTests` (each with the reason it can't run on a hosted runner), runs **all** of them instead of stopping at the first failure, prints the failures at the end and exits non-zero. It doesn't build: the workflow does, and points `SUMATRA_TEST_EXE` at `out/dbg64_asan/SumatraPDF-static.exe` (`tests/util.ts` `EXE` reads that env var at import time). If a test can't work on a runner, add it to `excludedTests` with the reason rather than deleting it.
- the runner picks the window layout with `setTestWindowLayout()` (`tests/winapi.ts`): `"quarter"` (the default, what `run-almost-all.ts` asks for) keeps the window out of a developer's way and renders/captures fewer pixels; `"workArea"` (what `run-github-ci.ts` asks for) uses the whole work area, because a runner's screen is small and nobody is looking at it. Every launch path (`launchSumatra`, `launchControlled`, `withControlledSumatra`) takes its geometry from `testWindowPos()`, so that one call covers all of them.
- an ASan build is 2-3x slower, so a test that sleeps a fixed number of ms for a repaint or a layout passes locally and fails in CI. Poll for the condition (see `sampleStableChrome` / `waitForChromeRestored` in `tests/issue-5866.ts`) instead of sleeping longer.
- a test that reads pixels off the document should call `client.setNotificationsEnabled(false)` (`-dbg-control`) first: notifications are drawn over the page and linger ~2s, so otherwise the test waits them out. Disabling also takes down any already on screen (e.g. the `Zoom: N%` one that `-zoom` shows). It was 8 of the 15 seconds `tests/issue-1195.ts` used to take.

### Ad-hoc tests

Some checks are too slow, need large external corpora, or require network/git and should **not** run on every `tests/run-almost-all.ts` invocation. Put those in `tests/ad-hoc-<name>.ts` (not `issue-<n>.ts`):

- export `async function testit()` the same way as regular tests
- end with the usual `if (import.meta.main) { await runStandalone(testit); }` standalone runner
- do **not** register them in `tests/run-almost-all.ts` or `tests/run-all.ts`
- run ad-hoc tests directly when working on that area: `bun tests/ad-hoc-<name>.ts`
- the pre-release suite is `bun tests/run-pre-release.ts` (run-almost-all + LaTeX)

Example: `tests/ad-hoc-exif.ts` clones/updates `../exif-py` and compares `-dump-exif` output to exif-py's `dump.txt`.

Guidelines for test scripts:

- build the app the same way cmd/build.ts does (via `buildApp`/`runStandalone` in tests/util.ts) and test the resulting out/dbg64/SumatraPDF.exe
- if a needed external tool (e.g. MiKTeX) isn't installed, don't fail the test: print a clear message (with instructions to install it) and skip that part, returning normally so `tests/run-almost-all.ts` continues
- a good test fails when the fix is reverted (verify this) — not just passes with the fix present
- write ad-hoc GUI automation (driving the app via window messages, screenshots) in **Bun TypeScript, not PowerShell** — bun has FFI. Put raw Win32 wrappers in `tests/winapi.ts` and higher-level actions in `tests/win-automation.ts`; extend and reuse those rather than re-declaring FFI per script. The ad-hoc scripts themselves don't need to be checked in, but the reusable helpers in those two files do.
  - `tests/winapi.ts` = raw winapi: FFI bindings + thin wrappers + constants (enum/find windows, SendMessage/PostMessage, getWindow{Text,Rect}, sendText, `captureWindowToPng` which uses PrintWindow+GDI+ so it works on occluded/background windows).
  - `tests/win-automation.ts` = high-level actions built on winapi: `launchSumatra` (passes `-for-testing`), `waitForFrame`/`findCanvas`, `clickAt`, `pressEnter/Tab/Escape`, `typeIntoInput` / `fillFormFieldAt`, `openContextMenu`/`waitForContextMenu`, `sendCommand`.
  - on this machine injected SendInput mouse/keyboard is dropped, but posting (and sending) window messages cross-process works. There is no interactive desktop, so real-cursor probes prove nothing, and a canvas text selection can't be driven at all — check a suspicious result against a clean build before blaming your change.
- resolve command ids by name with `cmdId("CmdName")` (from `tests/util.ts`), never hardcode the numeric id. Command ids are auto-numbered in `src/Commands.h` and shift whenever commands are added or removed, so a hardcoded constant silently starts sending a _different_ command. This broke `tests/issue-5780.ts` (it sent `CmdCommandPalette` instead of `CmdOpenNextFileInFolder` after ids shifted) and had stale ids lurking in several ad-hoc tests. `tests/lint-command-ids.ts` (runs first in `tests/run-almost-all.ts`) enforces this — it fails the suite on any hardcoded `const Cmd... = <number>` or numeric `sendCommand(win, <number>)`.
- prefer driving the app through `-dbg-control <named-pipe>` and `tests/control.ts` over GUI automation or adding new test-only command-line flags. Tests should pick a unique pipe name, launch `SumatraPDF.exe -for-testing -dbg-control <name>`, send binary request/response commands, and quit the app through the control client.
- `-dbg-control` protocol: requests are `[u32 payloadSize][u16 command][u16 requestId][args...]`; responses are `[u32 payloadSize][u16 requestId][results...]`. Arguments/results are encoded as `[u16 type]` where `0=end`, `1=i32` plus 4 bytes, `2=bytes` plus u32 length and data, `3=utf8 string` plus u32 length, bytes, and a zero terminator, and `4=list` plus u16 element count followed by encoded elements.
- never write runtime scratch / result files directly into `tests/` — that leaves the repo dirty. Write them under `tests/tmp/` (gitignored), using `tmpPath("name")` from `tests/util.ts` (it creates the dir on demand); the OS temp dir (`os.tmpdir()`) is also fine if you clean up after
- if a binary test fixture (e.g. a .pdf) is generated from source (LaTeX, a script, etc.), commit the source alongside it (e.g. `tests/issue-<number>.tex` next to `tests/issue-<number>.pdf`) with a comment on how to regenerate it, so the fixture can be modified later

## Windows Shell Safety

The Bash tool runs under Git Bash (MSYS2), **not** cmd.exe. This causes critical issues with Windows-style commands:

- **NEVER use `2>nul`** — Bash interprets this literally and creates a file called `nul`. On Windows NTFS, `nul` is a reserved device name, making the file extremely difficult to delete (requires UAC/admin privileges). Use `2>/dev/null` instead.
- **NEVER use `rmdir /s /q`** — Bash `rmdir` does not understand cmd.exe flags. Use `rm -rf` instead.
- **NEVER use `del`** — Not available in Bash. Use `rm` instead.
- **NEVER use `dir`** — Use `ls` instead.
- **For Windows-native commands**, wrap in `cmd /c "..."` explicitly.
- In general, always use Unix-style commands and paths in the Bash tool.

## This fork (local, not upstream)

`agents.md` is gitignored here and maintained per branch, so each checkout can
describe only what it actually contains. Do not assume another branch's features
exist in this one — check the "This branch" section below.

### The four checkouts

This repo is one `.git` with five `git worktree` folders, siblings under
`Documents/AI_crap/chatterbox-AI/`. Each is on its own branch and has its own
`out/`, so building one never overwrites another:

| folder | branch | adds over upstream |
|---|---|---|
| `sumatrapdf/` | `library-work` | library start page + Chatterbox read-aloud + square corners |
| `sumatra-library/` | `library` | library start page only |
| `sumatra-chatterbox/` | `chatterbox-read-aloud` | Chatterbox read-aloud only |
| `sumatra-square-corners/` | `square-window-corners` | square window corners only |
| `sumatra-android/` | `library-work` (with the Android port uncommitted on top) | a complete Android port of the library: the Win32 library service reimplemented in Kotlin against the C++ Sumatra source via `src/android/SumatraAndroidEngine.h`, plus the full reader (PDF / EPUB / XPS / FB2 / CBZ via mupdf AAR) with the Win32 toolbar, the cascading hamburger menu, tabs, sidebar, ToC, search, read-aloud. See `sumatra-android/agents.md` for the port's design contract, `sumatra-android/android/PORTING-STATUS.md` for what is ported vs. stubbed, and `sumatra-android/android/ROADMAP.md` for the phased plan. |

`sumatra-361/` and `sumatra-37clean/` are also worktrees of this repo, on
detached HEADs, kept for comparison against stock 3.6.1 / 3.7.

### Toolchain on this machine (corrects the note near the top of this file)

- **Only VS Build Tools 2022 is installed** — no full Visual Studio, and
  **`cl.exe` is not in PATH**. msbuild lives at
  `C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\MSBuild\Current\Bin\MSBuild.exe`.
  `cmd/util.ts` and `cmd/build.ts` are patched locally to accept the
  `BuildTools` edition and to fall back to VS 2022 when VS 2026 is absent;
  without that patch `bun ./cmd/build.ts` dies with
  `error: couldn't find vs 2026 msbuild.exe`.
- **`clang-format` is not in PATH either.** Use
  `C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Tools\Llvm\x64\bin\clang-format.exe`.
- **A fresh worktree must run `bun cmd/gen-docs.ts` before its first build.**
  It writes the gitignored `.work/manual.dat` that `src/SumatraPDF.rc` embeds;
  without it the build fails with
  `RC2135: file not found: ..\.work\manual.dat`. `cmd/build.ts` does not run
  gen-docs (CI does).
- The engine DLL is **`libsumatrapdf.dll`** — upstream renamed it from
  `libmupdf.dll`. A stale `libmupdf.dll` may still sit in an old `out/dbg64`;
  nothing loads it.

### Local patch to vendored mupdf

`mupdf/` is vendored in-tree and compiles into `libsumatrapdf.dll`, so a bug in
it is a bug in the app. One fix lives here and is **not** upstream:
`source/fitz/stext-device.c`, `do_extract_within_actualtext()` asserted
`z != 0` when a span's ActualText string is a strict prefix of its glyphs. On a
debug build that is a modal assert dialog, and a whole-device scan that touches
such a PDF stops dead. It now clears `mt->text` and returns, which is what the
function's own entry guard does for the empty case. Do not "clean up" that early
return, and re-apply it after pulling a new mupdf.

### Adding a source file under `src/`

Register it in **all** of these or something breaks later, often not locally:
`premake5.files.lua`, `vs2022/SumatraPDF.vcxproj`,
`vs2022/SumatraPDF-static.vcxproj`, both matching `.vcxproj.filters`, and
`cmd/build-with-mingw.ts`. That last list is **hand-maintained** and drives the
Linux/Wine GitHub check; omitting a file there gives a link error in CI while
the Windows build is green. It also has no `-MMD` header dependency tracking, so
delete `obj/` before reusing a mingw build tree across branches.

### After merging upstream

Upstream does mechanical renames across the tree, and they **cannot reach
fork-only files** — upstream does not know they exist. Merging `origin/master`
therefore compiles upstream fine and breaks our files. Grep the fork's own
sources for the old names and port them. The last merge needed
`ClientRect`->`HwndClientRect`, `HwndSetVisibility`->`HwndSetVisible`,
`CenterDialog`->`HwndCenterDialog`, `CreateSimpleFont`->`HdcCreateSimpleFont`,
`FillRect`->`HdcFillRect` and an added `base/Pixmap.h` include. Check each call
site rather than blind-replacing: some `FillRect` calls are genuine Win32
(`RECT r = ToRECT(rc); FillRect(hdc, &r, brush)`) and must not change.

### Verifying a change here

- `-for-testing` **bypasses the reuseInstance handoff**, so a bug that only
  appears when launching normally will not reproduce under it.
- Posted `WM_LBUTTONDOWN`/`WM_LBUTTONUP` do not activate toolbar buttons on this
  machine. Read button state with `TB_GETSTATE` and fire commands with
  `sendCommand`. `TB_GETRECT` needs `VirtualAllocEx`/`ReadProcessMemory` — a
  RECT pointer is not marshalled across processes and crashes the app.
- Prefer the app's own log as the observable: launch with
  `-log -log-to-file <path>` and assert on lines such as
  `HandleExecuteCmds: '[AudiobookHighlight(...)]'` (the Chatterbox engine is
  really reading) or `WinTtsStartPlayback: playing <n> bytes` (stock Windows
  TTS). Counting new child windows is unreliable — a toolbar resize looks like a
  new window.
- Judge a build by its **exit code** and a moved exe mtime, never by grepping
  output for `error C`.

#### Driving a library rescan from a script

Getting this wrong produces a screenshot of a rail that was never rebuilt, which
looks exactly like a fix that did not work.

- **Start the service yourself, with `--log`.** `LibraryEnsureService()` launches
  `pythonw.exe -m audiobook.library --port 7863 --parent-pid <pid>` and passes
  **no `--log`**, so a service the app started has no readable log. Start
  `python -m audiobook.library --port 7863 --log <path>` first;
  `LibraryServiceAnswers()` makes the app reuse whatever is already listening,
  and a service you started outlives the app because it has no `--parent-pid`.
- **Wait on `/status`, not on `library.json`'s mtime.** The background sweep
  rewrites the index on its own schedule, so an mtime change proves nothing about
  the scan. `scanning` stays `false` for the whole native walk — the service only
  learns a scan happened when `POST /index` arrives — so a naive
  "wait until `scanning == false`" loop exits before the app has sent anything.
  Wait for the index to arrive (`books > 0`, `scan_done == scan_total`) *and
  then* for it to go quiet.
- The two `RunOneScan` passes each POST once, so the service logs
  `library: N books, … from N files` **twice** per rescan. One line means the
  whole-device pass has not landed yet.
- The sweeps (covers → genre → series) run in the service after the POST and
  take far longer than the scan — a full series sweep is minutes of
  rate-limited traffic. Closing the app does not stop them. Wait for
  `library: N book(s) asked about their series` before judging series or genre.
- To force every file to be re-read (a change to what the scan *measures*, not
  to how it is shelved), move `library.json` aside first: a file whose size and
  mtime are unchanged is sent as a placeholder and keeps its stored metadata, so
  a plain rescan will not re-measure anything.

### Local conventions

- **Never `git push` unless explicitly asked.**
- **No Co-Authored-By or any AI/Claude attribution** in commits, PRs or
  published content.
- Build the normal way (`bun ./cmd/build.ts`, msbuild); do not add wrapper or
  setup scripts. Configuration belongs in the app's settings.

### The view mode is global, not per book

The user's words: *"Books will also open straight to single page even though
continuous reading was selected. The views should not be 'per book' they should
be 'until they are changed.'"* Android already behaved this way.

- `ReplaceDocumentInCurrentTab` starts from `gGlobalPrefs->defaultDisplayModeEnum`
  and **must not** overwrite it from `fs->displayMode`. That one line was the bug:
  a book you had once left on single page reopened on single page forever, no
  matter what you picked afterwards.
- `RememberDefaultDisplayMode(win)` writes the chosen mode back to
  `defaultDisplayModeEnum` and calls `SaveSettings()`. It is called from the four
  places the *user* picks a view — `CmdToggleContinuousView`, `CmdSinglePageView`,
  `CmdFacingView`, `CmdBookView` and the two `ChangeZoomLevel` branches — and
  reads the mode back off the controller, so `keepContinuous` resolves first.
- Do **not** hook `SwitchToDisplayMode` itself. Startup `-view` flags, session
  restore (`SetTabState`) and the internal book-view page flip all go through it,
  and letting them rewrite the default makes the setting drift on its own.
- Zoom, `displayR2L` (manga mode) and scroll position stay per file. Only the
  view mode became global.
- `tests/ad-hoc-display-mode-sticky.ts` is the regression: it toggles the view,
  opens a never-seen document (must follow the new view), toggles again, and
  reopens the first document (must follow the new view, not its own saved one).
  It reads `getScrollInfo()` on the canvas — continuous spans every page, single
  page collapses to one screen — so it needs no screenshots.

### This branch: `library-work` (all three features)

The combined branch, and the one whose `out/dbg64` is the app the user runs day
to day. It has everything the other three branches have, and the sections below
describe each addition in full: what it is for, how it works, and where it is
wired into the app.

| addition | new source | commands |
|---|---|---|
| library start page | `src/LibraryPage.cpp` / `.h`, `src/LibraryScan.cpp` / `.h`, `src/LibraryStore.cpp` / `.h`, `src/LibraryData.h` | `CmdToggleLibraryHome` 457, `CmdLibraryRescan` 458 |
| Chatterbox read-aloud | `src/AudiobookCharacters.cpp` / `.h` | `CmdToggleAudiobookVoices` 455, `CmdAudiobookCharacters` 456 |
| square window corners | none — `src/SumatraPDF.cpp` only | none (unconditional) |

The two features are independent but they do meet in one place: the library's
right-click **Play as Audio Book** opens the book and fires
`CmdReadAloudFromTopPage`, which then routes through `Audiobook.UseChatterbox`
like any other Read Aloud — so on this branch that menu item really can start the
Chatterbox engine.

---

#### What the library start page is

Upstream's start page is **Frequently Read**: a short list of files you opened
recently, and nothing else. It only knows about books you have already opened in
SumatraPDF.

This branch replaces it with a **library**: the app's own catalogue of every book
on the machine, whether or not it has ever been opened here. The page is a wall
of cover posters with a rail of shelves down the left, and every book has a
detail page behind it — metadata, table of contents, the characters/family/places
found in it, and the films and TV series based on it.

The point is that a big folder of ebooks is unusable as a folder. The library
turns it into something you can look at: covers instead of filenames, series
grouped together instead of scattered across directories, and a way to say "this
shelf belongs with that one" that sticks.

#### How the page is put together

It is **the home page**, not a panel or a window. `src/CanvasAboutUI.cpp` — the
about/home canvas — asks `LibraryHomeEnabled()` first at every entry point and
hands off to this file: `DrawLibraryPage`, `LibraryOnLeftButtonDown`,
`LibraryOnMouseMove`, `LibraryOnLeftButtonUp`, `LibraryOnRightClick`,
`LibraryOnMouseWheel`, `LibraryOnVScroll`, `LibraryOnLinkClicked`.

**The consequence matters when testing**: the page can only be on screen when no
document is open. `CmdToggleLibraryHome` while a document is open flips the
preference and redraws, and nothing visible happens. Launch with no file to see
it.

Everything is drawn with GDI onto the existing canvas — there are no child
windows, so an unused library costs nothing. Every clickable thing is a synthetic
link URL starting with `<Library,` (`kLinkLibraryPrefix`), reusing the canvas's
existing link hit-testing and hand cursor; `LibraryOnLinkClicked` dispatches on
the prefix (`<Library,Series>`, `<Library,Book>`, `<Library,Person>`,
`<Library,Sort>`, `<Library,Page>`, …).

Layout:

- **Left rail** — `All books (n)`, then `Deskpan (n)`, then the shelf tree:
  partitions, then series shelves, then *Standalones*, indented by depth,
  with a sort row at the top (A-Z, by genre, most books, fewest books). Clicking
  a shelf filters the wall; right-clicking one opens the partition menu. The rail
  scrolls independently of the wall. Its footer has *Rescan library* and
  *Frequently read*.
- **Main area** — the poster wall: one tile per book, cover image with title and
  a subtitle (author / volume / year), laid out to fit the width.
- **Book detail page** — replaces the wall when a tile is clicked. Tabs are
  `LibTab::Overview / People / Family / Places / Knows / Screen`, plus a chapter
  tree, a description, subjects, "Read" and page links, and a back link. A
  chapter's title opens the book at that chapter's page; the `+`/`-` box on a
  chapter that has children is the only part of the row that expands it.
- **Right-click a tile** — *Open book from last page read* (uses
  `gFileHistory`), *Open book from beginning*, *Play as Audio Book*, then the
  partition commands.

Both the rail and the main area have their own drawn scrollbars (`LibScrollBar`,
`LayoutBar`/`DrawBar`/`PosFromBar`) because neither is a real window with a
native one.

**Getting back from the classic page.** The rail's *Frequently read*
(`<Library,Classic>`) turns `LibraryHomeEnabled()` off and saves, which hands the
canvas to upstream's Recently Opened page. That page therefore has to offer the
way back or the switch is one-way: it draws a **Library** link
(`kLinkHomeLibrary`, `src/HomePage.cpp`) beside *Open a document...*, and the
About page — the classic page with the file list hidden — repeats it bottom
right. `src/CanvasAboutUI.cpp` handles it by turning the preference back on.
`CmdToggleLibraryHome` still flips it from the menu.

#### The index on disk (`SumatraLibrary.txt`)

**The page draws from disk, not from the service.** `LoadModelThread` reads the
index before it contacts anything, and writes it back after every successful
fetch, so a cold start paints the last scan's books immediately and a run with
no Python at all still shows them. The service is what *refreshes* the index,
not what serves it. Until a scan has written one there is nothing to draw, and
the page says *the library service is not answering* as it always did.

Two files in the app data directory (`-appdata` moves them):

- **`SumatraLibrary.txt`** — the index. Same SquareTree format as
  `SumatraPDF-settings.txt` and the same code path: `src/LibraryData.h` is a
  *second* top-level struct emitted by `cmd/gen-settings.ts`, so
  `SerializeStruct` / `DeserializeStruct` (`base/SettingsUtil.h`) handle it with
  nothing hand-written. `src/LibraryStore.cpp` wraps that as
  `LibraryStoreLoad` / `Save` / `Parse` / `Serialize`.
- **`SumatraLibraryThumbs.txt` + `.dat`** — the covers, in a
  `base/AppendStore.h` store keyed by book id. Append-only, so a regenerated
  cover is appended again and the later record wins on replay.

`Version` is checked on load: an index written by a different version is
discarded whole rather than half-read, so the page falls through to the service
and the next fetch writes a fresh one. `Total` and `Documents` are stored beside
the books because the rail prints both counts and they have to be right when the
page draws from disk.

`CoverWorker` asks the thumb store before it asks the service, and writes back
what the service returns. Posters and desk covers are deliberately **not**
cached: a poster comes from the web, and a desk cover is of a file that is not
in the library yet.

Only the main wall reads from disk; the desk, book detail, chapters and screen
panes are still service-only.

To test any of this without touching the user's setup, run with
`-appdata <scratchdir>` and a settings file whose `PythonExe` names a path that
does not exist: that fails the `file::Exists(python)` check in
`LibraryEnsureService`, so nothing is launched and the page can only draw from
disk. Move `SumatraLibrary.txt` aside for the negative control — the page should
fall back to *the library service is not answering*.

#### The service behind it

The catalogue is not *built* in-process. A small local HTTP service does the
metadata, covers and online lookups, and the page is a client that caches its
answers in the files above:

```
python -m audiobook.library --port 7863 --parent-pid <pid> [--root <dir> ...]
```

`LibraryEnsureService()` (in `src/SumatraPDF.cpp`) does the whole dance: if
something already answers on the port, use it; otherwise resolve the install
folder, launch `pythonw.exe` with `CREATE_NO_WINDOW`, and poll for up to 10 s
(40 × 250 ms) for it to come up. `--parent-pid` makes the service exit when this
app does, so a crash cannot leave an orphan holding the port.

Endpoints the page uses:

| method | path | what it returns |
|---|---|---|
| GET | `/library?limit=&sort=` | the whole catalogue: shelf rows + books |
| GET | `/partitions` | user-made partitions and their nesting |
| GET | `/status` | `scanning`, `scan_done`, `scan_total` (progress) |
| GET | `/book?id=` | one book's detail: description, subjects, people, places, topics |
| GET | `/chapters?id=` | the table of contents as a depth tree, with real page numbers |
| GET | `/cover?id=[&desk=1]` | JPEG cover bytes; `desk=1` renders page 1 whole and never goes online. Asked only when the thumb store has no cover for that id |
| GET | `/poster?url=&key=` | JPEG poster bytes for an adaptation |
| GET | `/screen?id=` | film/TV adaptations of this book |
| GET | `/wiki?q=character\|family\|knows&series=&name=\|topic=` | the lore wiki |
| GET | `/known` | every path already indexed, with size and mtime |
| GET | `/deskpan?show=documents\|ignored&offset=&limit=` | the desk pile: files the scan judged not to be books |
| POST | `/index` | `{scope, roots, files[]}` — the result of a native scan |
| POST | `/kind` | `{paths[]\|ids[], kind}` — move files between book / document / ignored |
| POST | `/partition/new\|assign\|rename\|delete\|nest` | edit partitions |

There is no `POST /refresh` any more: rescanning is `GET /known` followed by one
`POST /index`, both driven by the app (below).

Nothing HTTP happens on the UI thread. Every fetch is a `RunAsync` job
(`libModel`, `libRescan`, `libCover`, `libDetail`, …) that parses JSON with
`base/JsonParser.h` into a global model under one critical section
(`EnterLib`/`LeaveLib`), then invalidates the canvas. The model is fixed-size
arrays, not allocations: `kMaxBooks` 4096, `kMaxSeries` 256, `kMaxCovers` 400,
`kMaxChapters` 512, and `kCoverWorkers` 3 threads pulling cover images so the
wall fills in progressively rather than blocking on the first tile.

Stale-response guards are deliberate: a detail/person/chapter thread checks that
`gDetail.id` (or `gDetail.person`) is *still* the thing it was fetching before
storing anything, so clicking through books quickly cannot land an old answer on
a new page.

#### The scan runs in the app, not the service

Windows Media Center did not need Python to find your media and neither does
this. `src/LibraryScan.cpp` walks the machine with `FindFirstFileW` and reads
every candidate with the engines already linked into the app
(`CreateEngineFromFile`), so the service never opens a document.

The handoff is two calls. The app asks `GET /known` for everything already
indexed (path, size, mtime), walks the disks, and sends **one** `POST /index`
with `{scope, roots, files[]}`; a file whose size and mtime are unchanged is sent
as a placeholder and keeps its stored metadata, so only new or edited files are
opened and measured. `LibraryScanToJson` takes a cancel flag and a progress
callback, which is what feeds `scan_done`/`scan_total` on the page while it runs.

Roots come in two widths: `LibraryStartingRoots()` (Documents, Downloads,
Desktop and anything configured) for the quick pass, and
`LibraryWholeDeviceRoots()` — **every fixed drive** — for the full one.
`kLibraryScanScope` is stored with the index so the page knows which width the
current catalogue was built at; `scope_current` in `/status` says whether it
still matches what the app would scan now.

Engine bugs surface here as a hung scan, so `ReadDocLook` logs
`library scan: reading '<path>'` before every open: the last line in the log
names the file that broke it.

#### Deskpan: books versus paperwork

Not every PDF on a machine is a book. `LooksLikeBook` in `src/LibraryScan.cpp`
votes on each file — page count, measured art share, ebook container extension,
library-ish vs work-ish folder names, book words vs document words in the front
matter and sampled text, narrative punctuation, a real table of contents — and
scores it. Books go on the shelf; everything else (manuals, invoices, forms,
specs, résumés) goes to the **Deskpan**, and the kind travels in the `/index`
payload as `"kind": "book" | "document"`.

`audiobook/library/desk.py` stores the pile in `deskpan.json` with three kinds
(`book`, `document`, `ignored`). A kind set by hand is an override and survives
rescans — the scan may not overrule the user.

`kLibraryDirNames`, `kWorkDirNames` and `kDocWords` here and
`LIBRARY_DIR_HINTS`, `WORK_DIR_HINTS` and `DOC_WORDS` in the Android
`BookClassification.kt` are **one list kept in two places**. When they drift the
same file lands on a shelf on one platform and on the desk on the other, which is
exactly what happened — Android had the music, downloads and hardware entries and
Windows did not.

Two rules the scoring earned the hard way:

- The art-share bonus (`look.art >= kArtShare && pages >= 12`) exists to catch
  manga and comics, and only applies when the file is an ebook/comic container or
  sits under a reading directory. Ungated it also matches *scanned* paperwork: a
  22-page pay-rate table with an 0.83 art share hit the threshold exactly and
  became a shelf of its own.
- `"motherboard"` and `"bios"` are document words. A 194-page mainboard manual
  otherwise scores +4 for length and +2 for the phrase "Chapter 1" — manuals have
  chapters too — and shipped as the shelf `E10343 Maximus Viii Hero Um Web`.

Prove any change to the scoring against **both** real libraries before keeping
it: re-implement the score over the records already stored in the desktop's
`audiobook/cache/library/library.json` and the phone's `library.json`, confirm it
reproduces the stored `kind` for every record first, then diff the flips. The
change above flips three Android records, all paperwork, and no desktop records.
Adding plausible words in bulk is what does the damage — `kMarkerCap` is 3, so an
extra word can push an unrelated file over the cap and cost you a real book.

The view is the second row in the left rail (`Deskpan (n)`), drawn by
`DrawDeskpan`. It is **the same poster wall as the library**, not a list: the
tile metrics come straight from `DrawGrid` (132x240 tiles, pad 20, gapX 20,
gapY 22) and `DrawDeskTile` mirrors `DrawCoverTile` — aspect-fit picture in a
rounded box, file name as the title, `pages · size · folder` as the subtitle.

A document has no cover art to look up, so its picture is **its own first page
rendered whole**: `CoverBitmap` is asked for `desk:<id>`, the `desk:` prefix
makes `CoverWorker` fetch `/cover?id=<id>&desk=1`, and the service answers from
`covers.build_desk` — `from_file(path, whole_page=True)`, never the embedded-image
picker and never the online lookup. When there is no picture (a format MuPDF
cannot open) the tile falls back to the shell icon plus the extension.

Selecting is a **mode**, so it ports to Android where there is no ctrl: the
right-click menu offers *Select*, which turns on `gDesk.selecting`, and from then
on a tick box sits in the top-left of every tile, a plain click toggles that tile
(shift+click takes a run from the anchor), and a chosen tile shrinks its picture
by 5%. Un-ticking the last tile leaves the mode. Outside the mode a click just
opens the file. Both walls share one link (`<Library,Pick><index>`) covering the
whole tile.

Three actions appear above the wall **only while selecting**:
*Move selected to library*, *Ignore file*, and — in the Ignored pile —
*Remove from library*, plus *Select all* / *Select none*. Each posts `POST /kind`
for the whole selection at once and then leaves the mode. The same three sit in
the right-click menu and act on one file when the mode is off. Books on the wall
get *Remove from library* and *Ignore file* on their own right-click menu, so a
mistake in either direction is one click to undo. The `Documents` / `Ignored`
toggle switches which pile is listed and clears the selection.

#### Where the data comes from

**Finding books** (`src/LibraryScan.cpp`): the walk is native and bounded —
`kScanDepth` 5, `kScanBudget` 40000 directories — over `.pdf .epub .mobi .azw3
.fb2 .cbz .xps`, skipping `windows`, `program files*`, `programdata`, `appdata`,
`node_modules`, `$recycle.bin`, `system volume information`, `site-packages`,
`venv`, `cache`, `steamapps` and friends (`kSkipDirNames`), and favouring
directories named like libraries (`kLibraryDirNames`: `ebooks`, `books`,
`calibre library`, `audiobooks`, `manga`, …) over ones that hold a project's own
paperwork (`kWorkDirNames`: `docs`, `ext`, `tests`, `vendor`, …).
`shelf.scan(roots, files, …)` on the service side no longer discovers anything
of its own: it takes the file list the app sends and turns it into the index.

**Naming a book**: embedded document metadata when it is any good
(`_meta_ok`/`_meta_author` reject the junk that PDF producers leave in Title),
otherwise the filename is parsed (`_parse_name`) into author / title / volume,
including roman numerals. Pages are sampled (`kPageSamples` 6, in the app) to
measure how much of each page is image, which is what separates a comic from a
novel; the measurement arrives in the `/index` payload. `content_hash` dedupes
the same book found twice in different folders.

**`art` is load-bearing, and it is easy to measure as zero without noticing.**
`ReadPageLook` counts a sampled page as art when its largest image element
covers `kArtShare` (0.5) of the mediabox, and `art` is the share of sampled
pages that qualified. Everything downstream hangs off it: `LooksLikeBook`
scores it, `genre.py` calls a book a comic on it, and `series.looks_graphic`
uses it to decide whether MangaDex and the GCD are asked *at all*. But
`EngineMupdf::GetElements` goes through `GetFzPageInfoFast`, which returns
**nothing at all unless the page is already `fullyLoaded`**, and
`ExtractPageText` loads pages *quick* — so the obvious `ExtractPageText` then
`GetElements` sequence returns an empty element list on every page and every
book in the library reports `art = 0.0`. Nothing fails, nothing logs; the whole
library just quietly reads as prose and 71 comics and manga lose their genre and
their series. `ReadPageLook` therefore calls `engine->BenchLoadPage(pageNo)`
first — the public `EngineBase` way to force the full load without building a
display list. When you touch this code, check the numbers, not the absence of
errors: over this library the healthy distribution is roughly 71 books at
`art >= 0.5`, 71 between, 51 at zero. All-zero means you broke it.

The result is written to `library.json` in the cache root, so the next start
paints immediately and only a rescan pays for the walk. The app keeps its own
copy of what it was sent in `SumatraLibrary.txt` (above) — `library.json` is the
service's working state, `SumatraLibrary.txt` is what the page draws.

**Grouping into series** happens three ways and they cooperate: the online
lookup (`series.py`), the folder tree, and filename analysis in `learn.py`
(shared title phrases, then a character-level clustering pass at
`CLUSTER_DISTANCE` 0.62 for the ones phrases miss). An author holding
`SERIES_AUTHOR_SHARE` (0.75) of a shelf becomes the shelf's author.

The clustering half is **scikit-learn**, imported inside a `try`/`except
ImportError` that quietly yields no clusters when it is missing. It lives only
in `.venv-amd`, which is the interpreter the app launches
(`.venv-amd\Scripts\pythonw.exe`, `LibraryEnsureService`). Run any script that
touches `learn.series_groups` with that interpreter and no other — a bare
`python` gives you a library with 11 groups where the app sees 14, and nothing
anywhere says so.

**`booktitle.MIN_SEGMENT = 12` is measured. Do not lower it to chase the
remaining run-together titles.** Over all 193 real titles, 10 also splits
`Wintersmith` into `winter smith`, 9 also splits `The Pretender` into `The pre
tender` and `Discworld` into `disc world`, 8 also splits `Sourcery` into
`source ry` — for one correct gain (`Raspberrypi` → `raspberry pi`). Titles
like `Kalilinux an Ethical Hackers Cookbook` and `Working with Grep Sedandawk`
stay wrong for a different reason: no database recognises the mangled name, so
`for_book` gets no title to adopt. `series.for_book` will take a database title
whenever `_tight(found) == _tight(book["title"])` — same letters, better
spacing — which is safe because it cannot rename the book; making it fix the
rest needs the lookup to be *asked* with candidate splits.

**A folder is never a shelf of its own — it only speaks for a series.**
`_folder_series` looks at each folder that directly holds books and asks what
series it stands for: whichever series at least half its books already belong to,
or, when no lookup placed them, its own name provided it holds at least
`MIN_SERIES_GROUP` (2) books and is not a generic word like `books`, `misc`,
`ebooks`. Every row it yields is a `series` row named after the *series*. That is
why there is no longer a `Users` shelf and a `Nokel` shelf sitting above
everything: the old `_folder_shelves` walked the path upward and turned each
ancestor into its own shelf, so the rail read `Users > Nokel > Animorphs >
Animorphs` instead of `Animorphs`. Do not reintroduce the ancestor walk — a
folder that holds books is evidence for a series, not a shelf.

**A mixed folder speaks for nobody, and evidence never outranks an answer.** Two
rules, and both are needed. First, a folder only gets a voice when its books
overwhelmingly agree: on top of "at least half the books", the winner must hold
`FOLDER_MAJORITY` (0.7) of the books in that folder that a database actually
placed. Second, `_catalogue` reads `name = b["api_series"] or said`, in that
order, and attaches the folder to the row only when the row is the one the folder
named.

`ebooks\manga_novels` is why. It holds 13 books, 7 of them *ONIMAI*: 7 × 2 ≥ 13,
so under the old half-the-folder rule it spoke for ONIMAI, and because
`_catalogue` read `said or api_series` it swallowed *Wool*, *The Silo Saga
Omnibus*, *Sugar Dog Life*, *Hitorijime My Hero* and *An Older Guy's VR First
Love* — five books each already placed correctly by MangaDex/AniList/Wikipedia.
That is exactly what "onimai is sitting on a shelf it shouldn't be sitting on"
meant. Its real share is 8/13 = 0.62, so it now speaks for nobody. `animorphs`
sat at 39/52 = 0.75 before the correcting pass and 52/52 after, so it still does.
Those two numbers are what 0.7 is set from; measure them again before moving it.

`agree_with_folder` may still *overwrite* a wrong series — that is its whole job
(*The Change* → "The Changeling", *The Discovery* → "ElfQuest: The Discovery",
*The Solution* → "The Cinderella Solution", 13 of them in the Animorphs folder
alone) — and it is safe to let it, because dominance is what decides whether a
folder gets to say anything at all. Do not "fix" this by making the pass
gap-only instead: that leaves those 13 on junk one-book shelves and takes them
off the Animorphs shelf, which is the same complaint from the other direction.

**A filename stub is not a question any database can answer.** `2345.pdf` and
`E10343.pdf` carry no title, and a fuzzy search engine always returns *something*
— MangaDex answered "2345" with *ONIMAI: I'm Now Your Sister!* and put it on that
shelf on the Fold 4. So `look_up` refuses to ask at all unless the query holds a
run of `MIN_QUERY_WORD` (3) consecutive letters, and `plausible_series` refuses a
name without a run of `MIN_NAME_WORD` (2), or a single-token name containing a
digit (`E10343`, `B0007FZQ9C`). The length check that was there before this
(`len(_squash(query)) < 3`) does not cover it: `_squash` keeps digits, so "2345"
squashed to four characters and sailed through. This is the same defect as the
one-word shelf names — a stub gets asked, the database guesses, and the guess
becomes a shelf.

**A lookup that comes back empty must erase the old answer, not leave it.**
`for_book` assigns `book["api_series"] = (hit or {}).get("name")` unconditionally,
and the Android `seriesSweep` has an `else { forgetSeries(b) }` branch that clears
`apiSeries`, `apiSeriesIndex`, `apiSeriesSource`, `apiSeriesParent` and
`apiSubjects`. Android used to assign only `if (hit != null)` while still
refreshing the asked-marker, so a book whose lookup stopped answering kept its
previous wrong shelf forever and `already_asked` never looked at it again. That is
literally the "as though they were manually sorted before" symptom: `2345.pdf`
stayed on the ONIMAI shelf on the Fold 4 after the stub gate above had already
stopped it being asked. Whenever a rule change makes a book *un*-answerable, the
sweep has to be able to take the answer away, so the clear-on-null branch and the
`ASK_RULES` bump are two halves of one fix — neither works alone.

Changing any of those rules makes every `api_series` already on disk untrustworthy,
and `already_asked` would otherwise never re-ask. That is what `ASK_RULES` is for:
it is part of the string `asked_with` builds, so bumping it invalidates every
stored answer in one move and the next sweep re-derives the library. Bump it —
and `SeriesLookup.SERIES_CACHE_VERSION` on Android, which invalidates the
per-book cache files — whenever the meaning of a stored answer changes.

One folder does become a row, and only this one: `_group_by_parent_folder`, run
after `_apply_partitions` and before `_fold_loose`, looks at the folder directly
above each parentless shelf and makes it the shelf they all sit under when it
holds at least `MIN_SERIES_GROUP` (2) of them and its own name is not generic.
`ebooks\manga_novels\Animorphs\{Animorphs, Alternamorphs, Megamorphs,
Chronicles, Vegemorphs}` is the case it exists for: those are five sibling
series, and without the group the rail listed them as five unrelated shelves,
alphabetically scattered, when the disk plainly says they are one thing. The row
is `kind="folder"`, keyed `folder:<normcased path>` so it cannot collide with the
`series:animorphs` row for the sub-series of the same name — the parent shelf and
the main series are both called *Animorphs* and both must exist. If a shelf
already speaks for that folder (books live in it directly) it is used as the host
instead of making a second row. The pass repeats up to `SERIES_TREE_DEPTH` times
so a group can itself be grouped, and it never touches a row a partition already
claimed, so what the user taught still wins. This is not the old ancestor walk:
it fires on the *parent of two or more shelves*, never on the folder a book sits
in, and `_generic_folder` still rejects `manga_novels`, `ebooks`, `documents`,
which is what keeps `Users` and `Nokel` off the rail.

Books that join nothing go through `_own_rows` into per-book `loose` rows purely
so `_apply_partitions` can route each one, and then `_fold_loose` immediately
collapses them: a loose row that landed in a partition hands its book straight to
that partition row, and whatever is left merges into a single `<loose>` shelf
named *Standalones*. Without that fold the rail showed one shelf per standalone
book — twenty of them under *Learning* alone, each reading `<title> 1`, which is
what "why are the shelves looking like that" meant. Run `_fold_loose` *after*
`_apply_partitions`, never before, or the routing has nothing to route.

`series.py` asks the world what series a book belongs to, and its answer wins
over the filename analysis: Wikidata `wbsearchentities` on the title, then
`wbgetentities` for `P179` (*part of the series*), gated by `P31` being a kind of
written work and ranked on title similarity plus author agreement (`P50` labels
and `P2093` strings, initials-aware, so "K. A. Applegate" matches "Katherine
Applegate"). `P1545` on the claim gives the volume number. `languagefallback=1`
is not optional — without it an entity whose label lives in `mul` (Douglas Adams,
Q42) comes back with an empty English label and reads as an author mismatch. When
Wikidata has nothing, OpenLibrary's editions for the matched work vote on a
series name after the volume tail is stripped, needing `MIN_EDITION_VOTES` (2).
The sweep runs after the metadata sweep, writes `api_series` /
`api_series_index` / `api_series_source` onto the book, and `_catalogue` makes
one `kind="series"` row per name. Books it could not place fall through to the
folder and filename passes as before.

**Which databases are askable, and which are not.** `_rounds_for` picks the
round order from `looks_graphic`: graphic books ask Wikidata + MangaDex first
and fall back to comics.org, OpenLibrary, AniList, Google Books; text books ask
Wikidata + OpenLibrary first and fall back to Google Books, MangaDex,
comics.org, AniList. Adding a source is one function returning `work` dicts plus
an entry in the source-weight table — the plumbing is not the problem, reachability
is. Measured on 2026-08-09 with the project User-Agent:

| site | result | usable |
| --- | --- | --- |
| openlibrary.org | JSON API, 200 | yes, wired |
| comics.org | JSON API, 200 | yes, wired |
| wikidata / wikipedia | JSON API, 200 | yes, wired |
| mangadex.org / anilist.co | JSON API, 200 | yes, wired |
| goodreads.com | AWS WAF JS challenge, 2.4 KB stub | no |
| app.thestorygraph.com | HTTP 403 | no |
| bookwyrm.social | Anubis proof-of-work interstitial | no |
| search.worldcat.org | 200 HTML, but `__NEXT_DATA__` carries no records | no |
| inventaire.io | JSON API, 200 — but returns `wd:Q…` Wikidata URIs | pointless, already have Wikidata |

Goodreads' *search results page* is the one that would be worth having: the row
title is literally `The Invasion (Animorphs, #1)`, series name and volume number
in one string, with the author and publication year beside it. It is only
reachable by sending a browser User-Agent to get past the WAF, which is bot
evasion; do not wire it that way. Goodreads retired its public API in December
2020 and IBDB is a Goodreads discussion topic, not a database. If a Goodreads
key or an official feed ever appears, `_goodreads_works(query)` parsing that
parenthetical is the shape to write. The four wired sources already place 147
of the 193 books; the 46 they miss are magazines, manuals and one-off documents
that are not in a series at all.

Two things keep that sweep honest. Wikidata **rate-limits us** — a 193-book
sweep at two requests a second earns `HTTP 429, Retry-After: 18` on most of
them — so `net.fetch` holds a per-host turnstile (`HOST_GAP` 1s), reads
`Retry-After` on 429/503, doubles that host's gap up to `MAX_HOST_GAP` (20s),
retries `BUSY_TRIES` (3) times and lets the gap decay back on success. Without
it about 85% of the library came back unanswered, which is what "the shelves
aren't grouping" actually looked like. And a lookup that *failed* is no longer
written down as "this book has no series": `_wikidata` / `_openlibrary` return
`LOOKUP_FAILED` (distinct from `None`, which means "asked, no series"), and
`for_book` then leaves `api_series` unset so the next sweep asks again.

What `sweep` skips on is `already_asked(book)`, and "we already asked" means
more than "the `api_series` key exists". `for_book` writes down
`api_series_asked` — `asked_with(book)`, the title, the author and whether
`looks_graphic` said graphic or text — plus `api_series_when`. The next sweep
re-asks when any of those three inputs has changed, and re-asks a *miss* once
`ASK_AGAIN` (7 days) has passed; only a book that was found, on the same
inputs, is left alone. The signature is the part that matters. Onimai is the
worked example: a scanner bug measured its art share as 0, so `looks_graphic`
said "text", so `_rounds_for` never asked MangaDex, so seven manga were written
down as "asked, no series" — and under the old rule, that answer was permanent
even after the art measurement was fixed. Any input that steers the lookup
belongs in `asked_with`; if you add one, add it there too. Finally
`agree_with_folder` runs a second pass: any folder where `FOLDER_AGREE` (2)
books already agree on a series name lends that name as a hint to its
stragglers, worth `FOLDER_BONUS` (0.5) in the ranking. That is what stops
*The Stranger* (Animorphs #7, no author in the file) from landing on a
one-book shelf called *The strange writer* because some unrelated entity
carries the same title. The pass re-queries every book whose own answer differs
from its folder's winner, not just the ones with no answer at all — thirteen
Animorphs books look up as *The Changeling*, *ElfQuest: The Discovery*, *The
Cinderella Solution* and the like, and a hints-only pass leaves every one of
them on its own junk shelf. The overwrite is safe only because
`FOLDER_MAJORITY` already threw out folders whose winner does not dominate.

**Genre** (`genre.py`) votes a shelf and a sub-shelf out of title, subject and
path rules, and detects comics from extension plus measured ink/art share. That
is what the "genre" sort groups by.

**Partitions** are the user's own grouping, on top of all of the above, stored in
`partitions.json`. Right-click a shelf to create one, move a shelf in or out,
rename or delete it. Once at least `PART_MIN_TAUGHT` (2) shelves have been put
into a partition by hand, `learn.route_partitions()` routes the remaining shelves
by the rare words in their names and their measured features (page counts, how
they are shelved), and the page says *why* it guessed — the `guessed` field is
rendered as the terms it matched on. Taught assignments always beat guesses, and
`kept_out` remembers anything you explicitly pulled back out so it is not
re-guessed into the same place.

**Covers** (`covers.py`): render the book's own first page and accept it only if
it looks like cover art rather than a text page (`MIN_IMAGE_SHARE`,
`TEXT_PAGE_WHITE`), else fetch art online. Encoded to JPEG at
`COVER_HEIGHT` 520 and cached under the cache root, so `/cover?id=` is a file
read after the first time.

**Adaptations** (`screen.py`): IMDb's suggestion endpoint plus a Wikidata SPARQL
query on `P144` (*based on*) find films and series made from the book; results
are filtered by title similarity (`MIN_MATCH` 0.6) and video games, episodes and
podcasts are dropped. Each entry carries kind, year, stars, poster and IMDb id —
the Screen tab lists them, and the title links out to IMDb.

**Chapters** (`chapters.py`) tries three things in order, because a book's own
table of contents cannot be trusted. First the outline, but only if its
destinations actually spread across the book: a PDF whose every bookmark
resolves to page 1 (plenty do) fails `OUTLINE_PAGE_SPREAD` (0.5 — at least half
as many distinct pages as entries) and is thrown away. Then the outline's
*titles* are kept but its pages discarded, and each title is located by matching
it against every page's first line, in order, accepting the result only if
`OUTLINE_FOUND_SHARE` (0.5) of them were found. Only then does the heading regex
scan every page.

An outline that survives is still incomplete more often than not — the
Hitchhiker PDFs each skip two to four chapters — so `_merge_pages` runs the
page scan anyway and folds in whatever the outline missed. Only a flat list is
merged (a nested outline is trusted as-is), and a page-scan row is dropped when
it repeats an outline row rather than filling a gap: same heading head
("Chapter 4" out of "Chapter 4 · Programming with Scratch"), or a page within
one of an outline row's, which is how an outline entry pointing at a chapter's
title page and the page scan finding its first text page collide. Running
headers are why `_heading` returns a `(head, title)` pair and strips the printed
page number off the tail, and why `_from_pages` skips a heading whose head
repeats the one it just kept — without that, `Chapter 1 · Introducing the
Raspberry Pi 9` on 213 consecutive pages reads as 213 distinct chapters.

`source` on the response says which answered (`outline` / `outline titles` /
`pages`, with ` and pages` appended when the merge added anything), and the
cached JSON carries `version` (3) so caches written by an older reader are
discarded rather than replayed.

**The wiki tabs** (People / Family / Places / Knows) come from the lore
`FactStore` that `audiobook/lore` builds from BookNLP output for a book or a
whole series: `/wiki?q=character` gives traits, speech, voice, kin, places, a
representative quote and its page; `q=family` gives the kin tree; `q=knows`
answers "who knows about X, and from what page". Books with no analysis simply do
not show those tabs — the `booknlp` flag on the row says whether there is
anything to show.

---

#### What the Chatterbox read-aloud addon is

Upstream Read Aloud is Windows SAPI: one synthetic voice reads the whole
document, narration and dialogue alike, in the same tone.

This branch adds a second engine behind the same command. With
`Audiobook [ UseChatterbox = true ]`, Read Aloud instead reads the book like an
audiobook: it works out **who speaks each line**, gives each character their own
cloned voice, reads the rest as the narrator, and highlights the words in the
page as they are spoken. The voices come from Chatterbox TTS, a local
voice-cloning model — so a character can be given a voice built from a real
performance rather than picked from a list of system voices.

Nothing about it is on by default. `UseChatterbox` starts false, and with it
false this branch behaves exactly like upstream.

#### The two processes and who does what

SumatraPDF does not synthesise anything. The reading happens in a separate
Python process, and **SumatraPDF is its UI**:

```
SumatraPDF.exe
  │  launches (CREATE_NO_WINDOW), one per machine
  ▼
audiobook/engine.py  ── resident engine, control API on 7862
  │  HTTP
  ▼
tts_server.py        ── Chatterbox model host, on 7861
```

The engine is launched by `AudiobookLaunch()` in `src/SumatraPDF.cpp`:

```
pythonw.exe audiobook\engine.py --pdf <book> --sumatra-exe <our exe>
            --tts-port 7861 --lm-url <llm> --control-port 7862
            --parent-pid <our pid> [--narrator V] [--lm-model M]
            [--lm-urls A,B] [--analyzer booknlp] [--play] [--from-start]
            [--start-text <selection> --start-page N]
```

- `--parent-pid` exists because the engine has no window. An orphan would keep
  reading the book aloud with no way to stop it, so the engine watches for our
  process going away and exits.
- `--sumatra-exe` is how it talks *back* to us (see the highlight below).
- The install folder is found automatically: the saved
  `Audiobook.ChatterboxDir` if it still contains `audiobook\engine.py`, then a
  few well-known relative paths, then a bounded depth-6 search with a 6000-entry
  budget. Once found it is saved, so the search happens once.

**The engine is resident, not a playback job.** It starts, brings the TTS server
up, loads the cast, and then waits. Reading, analysing and casting are all
*requests* that arrive on its control API. Two things follow from that, and both
are deliberate:

- The Characters panel can open and show the cast **without a word being read** —
  it just starts the engine idle (`AudiobookEnsureEngineForCurrentTab()`).
- **Stop** stops the reading and keeps the process, so the next Read Aloud does
  not pay the model load again. Quitting the engine is a different operation
  (`AudiobookQuit`, on window close), and it saves the reading position first.

Hence two separate questions in the code: `AudiobookProcAlive()` ("is the engine
there") and `gAudiobookPlaying` ("is it reading"). `AudiobookIsRunning()` is
both.

#### The control API (`audiobook/control.py`, port 7862)

Everything the app can do to the engine goes through here. `GET /state` is the
one the panel polls (once a second while open); the rest are POSTs:

| path | what it does |
|---|---|
| `GET /state` | cast, voices, LLM models, endpoints, analysis progress, which book |
| `GET /wiki?…` | the lore wiki for this book |
| `/play` | start/continue reading — `unit`, `from_start`, `text`, `page`, `analyze_first` |
| `/pause` `/resume` | the playback bar's Pause / Continue |
| `/stop` | stop reading, remember the place, keep the engine |
| `/quit` | exit the engine |
| `/restart` `/prev` `/next` `/page` | the playback bar's ⏮ ◀◀ ▶▶ and page skip |
| `/analyze` `/analyze_stop` | work out the speakers (`percent`, `force`, `analyzer`) |
| `/cast` `/test` | set a character's voice; speak a sample line in it |
| `/model` `/endpoints` `/scan` `/scan_stop` | which LLM, and which machines to share analysis over |
| `/wiki_build` | build the lore wiki for this book or series |

The app's Read Aloud commands map straight onto these: `CmdReadAloudPause` →
`/pause`, `CmdStopReadAloud` → `/stop`, and the playback bar's transport buttons
drive `/restart`, `/prev`, `/next`, `/page`. Read Aloud **with a selection**
sends the selected text as `--start-text` / `/play {text,page}`, and the engine
finds the matching unit rather than starting from the top.

#### Working out who speaks each line

This is the part that makes it an audiobook rather than a robot. Two engines,
chosen by `Audiobook.Analyzer` and selectable in the panel:

- **`llm`** (default) — a local LLM (LM Studio, Ollama, anything
  OpenAI-compatible, `Audiobook.LmStudioUrl`) is asked, chunk by chunk, who says
  each quoted line. `Audiobook.LmUrls` can list extra machines and the book's
  chunks are shared out across all of them, so a second box roughly halves the
  wall time; the panel can even scan the network to find them. Unreachable
  endpoints are skipped.
- **`booknlp`** — a local BookNLP model, one pass, no server needed and
  considerably faster. `audiobook/booknlp_attribution.py` classifies every quote
  the way the attribution literature does: **explicit** (speech verb with a named
  subject — *"…," said Bernard*), **anaphoric** (speech verb with a pronoun,
  resolved through BookNLP's own coreference), and **implicit** (no speech tag at
  all). It trusts BookNLP exactly where BookNLP is strong and no further: a
  *beat* is not a tag — *"…playing at, Marie?" / Jahns felt her temperature
  rise.* has no speech verb, and the name in the beat is precisely the trap,
  because she is reacting, not speaking. Only genuinely implicit quotes fall
  through to turn-taking, and only inside paragraph structure that really marks
  the turns. Names are canonicalised through an identity matrix so aliases and a
  later reveal read as one voice.

Analysis costs minutes on a real book, which is why it is opt-in and not on the
path to reading. It runs in the background with progress reported through
`/state`, can be limited to the first 10/25/50% of the book, and can be stopped
early — `/analyze_stop` compiles what finished rather than throwing it away. The
result is cached per book, so it is paid once.

#### Speaking it

`audiobook/synth.py` walks the analysed units and asks the TTS server for audio,
prefetching `PREFETCH` (3) units ahead in a worker thread while the current one
plays through `sounddevice`. Casting is a map from character name to voice plus
its `exaggeration` / `cfg_weight` / `temperature` / `seed`; anything uncast, and
`Unknown`, falls back to the narrator voice. Small pauses are inserted between
units (`GAP_UNIT_SEC` 0.18) and larger ones at paragraph changes
(`GAP_PARA_SEC` 0.45) so it does not sound like a queue of sentences.

`tts_server.py` (port 7861) hosts the Chatterbox model and a **VoiceRouter**:
voices are loaded on demand, the narrator is pinned, capacity is worked out from
available RAM, and the next voices in the book are warmed ahead
(`/plan`, `/warm`) so a character's first line does not stall while a model
loads. Eviction only happens when it is forced to. Generated chunks are checked
before they are handed back (`_chunk_check` against a Whisper transcription) so a
hallucinated chunk is regenerated instead of read aloud.

#### The highlight (how the engine draws in our window)

While reading, the engine highlights the words being spoken **inside
SumatraPDF's own window**. It does that through two DDE commands this branch adds
in `src/SearchAndDDE.cpp`:

```
[AudiobookHighlight("<pdf>",<page>,"x0 y0 x1 y1;x0 y0 x1 y1;...")]
[AudiobookClear("<pdf>")]
```

They draw a persistent forward-search-style mark and scroll it into view.
`audiobook/sumatra.py` delivers them with the stock `SumatraPDF.exe -dde "<cmd>"`
mechanism — so the engine needs no DDE client code, and word rects are merged
into one bar per text line before sending, for shorter commands and a cleaner
mark.

This is also the **best available observable that the Chatterbox engine really
read something**: launch with `-log -log-to-file` and count
`HandleExecuteCmds: '[AudiobookHighlight(...)]'` lines. Stock Windows TTS logs
`WinTtsStartPlayback: playing <n> bytes, <hz> Hz, <n> word cues` instead, and the
two never appear together.

#### The Audiobook Characters panel

`src/AudiobookCharacters.cpp` — the cast list, docked on the left of the frame
beside the document with a splitter, built like the Bookmarks panel (header with
close button, scrolling body, footer). Not a window of its own; `CmdAudiobookCharacters`
toggles it and `RelayoutAudiobookPanel` is called from the frame's relayout.

One row per character: name, line count, a voice dropdown, **Test** (speak a
sample of that character's own dialogue in the chosen voice) and **Train** (opens
the Voice Lab, `audiobook/reader.py --voice-lab --character <name> <book>`, to
build a voice). The narrator is a cast slot too and is always the first row.
Sort order is user-chosen and persisted in `Audiobook.CharSort`: by first or last
appearance, most or fewest lines, or name A-Z / Z-A.

The footer carries the analysis controls: which analyzer, which LLM model, how
much of the book, Analyse / Stop, a progress bar, and the LLM endpoint list with
a network scan to find more.

Three details in here are worth knowing before changing it, because each one
fixes a real failure:

- **The voice and model lists are only replaced when non-empty.** The engine
  re-asks the TTS server for the voice list on every `/state`, and a busy moment
  there answers with none — blanking every dropdown mid-poll, which reads as
  "the voice was lost".
- **One engine per machine, but a panel in every window.** If `/state` reports a
  different book than this tab's, the panel shows nothing rather than someone
  else's cast (`st->otherBook`).
- **"Already added" for a discovered endpoint is decided locally**, not taken
  from the engine, because the engine decided it when the scan ran and anything
  added since — by this panel or another window — would still be offered as new.

#### Where it is wired into the app

- `CmdReadAloud` / `CmdReadAloudFromTopPage` / read-from-cursor branch on
  `gGlobalPrefs->audiobook.useChatterbox` — Chatterbox engine or Windows TTS.
  The playback bar is the same one either way; it just drives a different thing.
- `CmdToggleAudiobookVoices` is the switch, in the Read Aloud ▸ Voice menu as
  *"Use Chatterbox voices"* — a checkable item, because Chatterbox is a voice
  choice like any other. Turning it on greys the Windows voices out; the engine
  is quit on the way past so the next read starts on the new setting.
- `CmdAudiobookCharacters` is in the Read Aloud menu as *"Audiobook Characters"*,
  greyed while Chatterbox is off (Windows TTS has no characters).
- `MainWindow` carries `hwndAudiobookBox`, `audiobookSplitter`, `audiobookDx`
  and `uiState.audiobookVisible`; the frame relayout places the panel after the
  toc/favorites sidebar.
- Closing the window calls `AudiobookQuit`, which stops the reading (saving the
  position) and then waits briefly for the process to go.

---

#### What the square-corners change is

SumatraPDF 3.6.1 had classic square window corners. 3.7 has the two top corners
rounded by 3px. This branch puts the square corners back, unconditionally — there
is no setting for it.

It looks like a one-liner and is not, because **three different mechanisms round
the frame** and all three have to be answered. All of it lives in
`src/SumatraPDF.cpp`; no new source files.

#### 1. The window style, at creation

`CreateMainWindow()` creates the frame with **no** `WS_EX_APPWINDOW`:

```cpp
HWND hwndFrame = CreateWindowExW(0, clsName.s, title.s, style, ...);
```

With that bit set, Windows gives the frame a window *region* that rounds the top
corners. 3.6 created the frame without it and got square corners for free. The
window still appears on the taskbar without it, because it is a normal top-level
window with no owner.

This is also the cheapest **negative control** when verifying: read
`GetWindowLongPtrW(hwnd, GWL_EXSTYLE)` and check `WS_EX_APPWINDOW` (0x00040000)
— absent on this branch, present on a stock build.

#### 2. DWM's corner preference

Upstream re-enables DWM rounding after its custom `WM_NCCALCSIZE` frame disables
it. Here that is inverted, and made unconditional:

```cpp
SetWindowRoundedCorners(hwndFrame, false);
```

Unconditional on purpose: upstream skips the call under Wine, but the custom
NCCALCSIZE frame otherwise picks up DWM's default rounding, and the preference
has to be applied there too. `ExitFullScreen()` re-asserts it, so leaving
fullscreen or presentation does not come back rounded.

On Windows 10 this call does nothing useful —
`DWMWA_WINDOW_CORNER_PREFERENCE` is a Windows 11 attribute and returns
`E_INVALIDARG` here — which is exactly why the third part exists.

#### 3. Stripping the window region, repeatedly

Windows applies the rounding region **asynchronously**: roughly 40 ms after the
window is shown, and again after a resize or a frame repaint. Stripping it only
from the message that caused the change loses the race, so:

```cpp
static void EnsureSquareCorners(HWND hwnd, MainWindow* win);   // strip if set
static void ScheduleSquareCorners(HWND hwnd, MainWindow* win); // strip + 50ms timer
```

`ScheduleSquareCorners` is called from four places, and the `kSquareCornersTimerId`
(0x101) timer catches the late re-apply:

| message | why |
|---|---|
| `WM_SIZE` | the frame changed size |
| `WM_WINDOWPOSCHANGED` | moved or restacked (after `DefWindowProc`) |
| `WM_EXITSIZEMOVE` | the end of an interactive drag-resize |
| `WM_NCACTIVATE` | drawing the frame is itself what brings the region back |

Three guards in `EnsureSquareCorners` are load-bearing:

- **It only strips when a region is actually set.** `SetWindowRgn()` repaints the
  frame, so an unconditional call from a paint path would re-enter and loop
  forever.
- **Maximized and minimized windows keep their region.** Windows sizes the
  maximized one to the work area, and dropping it would let the frame cover the
  taskbar.
- **Fullscreen and presentation are left alone.**

#### Verifying it

The observable is the window region itself, and it discriminates cleanly:

```ts
const rgn = gdi32.CreateRectRgn(0, 0, 0, 0);
const type = user32.GetWindowRgn(frame, rgn);  // 0/ERROR = square
                                               // 3/COMPLEXREGION = rounded
```

`GetWindowRgn` returns `ERROR` (0) on this branch and `COMPLEXREGION` (3) on a
stock build. Screenshots are the *wrong* tool here: `captureWindowToPng`
(`PrintWindow`) does not capture the non-client area, so a rounded and a square
frame can produce the same image.

---

#### Settings on this branch

Everything the fork adds lives in **`struct Audiobook`** (`src/Settings.h`) —
one `Audiobook [ ... ]` section in `SumatraPDF-settings.txt`, covering both
features:

| setting | what it does |
|---|---|
| `UseChatterbox` | read with the Chatterbox engine instead of Windows TTS (default **false**) |
| `ChatterboxDir` | the Chatterbox-TTS-Extended install; found automatically, set only if that fails |
| `PythonExe` | python for that install; empty ⇒ `<ChatterboxDir>\.venv-amd\Scripts\pythonw.exe` |
| `TtsServerPort` | headless TTS server (7861) |
| `LmStudioUrl` | local LLM used to work out who speaks each line |
| `LmUrls` | extra LLM machines to share the analysis over, comma-separated |
| `LmModel` | which model; empty ⇒ pick it automatically |
| `Analyzer` | `llm` (default) or `booknlp` |
| `NarratorVoice` | default narrator; empty ⇒ first available trained voice |
| `CharSort` | cast order in the Characters panel |
| `SidebarDx` | width of the docked Characters panel |
| `LibraryHome` | the start page is the library rather than Frequently Read |
| `LibraryRoots` | folders to look for books in, `;`-separated; empty ⇒ work them out |
| `LibraryPort` | the library service (7863) |
| `LibrarySort` | shelf order on the library page |

The engine's control API port is **not** a setting: it is
`kAudiobookControlPort` = 7862 in `src/SumatraPDF.cpp` (and
`kAudiobookControlPortDefault` in `src/AudiobookCharacters.cpp`).

**The settings file is not fully interchangeable with the `library` branch.**
That branch keeps the same library options in its own `struct Library`
(`Library [ Home = … ]`); this one reads `Audiobook [ LibraryHome = … ]`. The
user's current file happens to contain both sections, so each branch finds its
own field — but copying a settings file from one to the other will not carry the
setting across.

---

## The Android port of the library

`sumatra-android/` is a full Android port of the Win32 SumatraPDF **reader +
library**. It is not a separate product — it is the same fork (`library-work`),
built for an Android device instead of Windows. The Windows build still works
(`bun ./cmd/build.ts`); the Android port adds a second target.

### Where it lives

- `sumatra-android/agents.md` — the port's design contract, the rules the
  user has yelled about, the project layout, the test setup, the build loop,
  the hard rules for the UI and the library.
- `sumatra-android/android/PORTING-STATUS.md` — the file-by-file audit of
  what is ported and what is still stubbed, against the Win32 source.
- `sumatra-android/android/ROADMAP.md` — the phased plan to unfork the
  remaining stubs.
- `sumatra-android/android/app/src/main/java/com/sumatrapdf/reader/`
  — the Kotlin reader + library. The library lives in `library/`:
  - `Shelf.kt` — the `Book` data class and the `scan()` walk that fills it
  - `BookName.kt` — filename parsing (`parseName`, `cleanName`, `stemIsPoor`)
  - `BookClassification.kt` — the port of `LooksLikeBook` (book vs document)
  - `Catalogue.kt` — the `buildCatalogue` pass that groups books into
    `SeriesRow`s for the rail
  - `LibraryModel.kt` — the Compose state holder; the `seriesSweep`,
    `coverSweep`, and `metaSweep` background jobs
  - `LibraryPage.kt` — the home-page Compose UI (the rail + the wall +
    the book detail)
  - `Net.kt` — `fetchJson` + a per-host turnstile, disk cache in
    `<app.filesDir>/library/http/`
  - `SeriesLookup.kt` — the online series detection (OpenLibrary,
    Grand Comics Database, Google Books)

### What the port shares with Windows

- **The same C++ Sumatra source** under `sumatra-android/src/`, compiled
  into the engine the Android port calls through
  `src/android/SumatraAndroidEngine.h` (the plain-C bridge that mirrors
  `src/mac/SumatraMacEngine.h` 1:1).
- **The same library rules** — `Catalogue.kt` and `BookName.kt` are
  straight ports of `audiobook/library/shelf.py` and
  `audiobook/library/bookname.py` (filenames, generic-folder words,
  user-path words, group-by-parent, no Standalones, online series
  detection). When the user changes a rule in one, it has to change
  in the other too.

### What the port does NOT share

- **The Python library service is not used.** The Android port runs the
  scanner, the cover fetcher, the series lookup, the BookNLP-free
  classification, and the partition store entirely in-process in
  Kotlin. There is no `pythonw.exe` launch, no `localhost:7863`, no
  service to babysit. The user has been clear they want the phone to
  work standalone.
- **BookNLP is not used on Android.** The lore wiki (People / Family /
  Places / Knows tabs) is stubbed. The phone can read aloud, but not
  with the Chatterbox voice-cloning engine.
- **The Win32 toolbar, menu bar, status bar, tabs, sidebar, ToC
  sidebar, search bar, annotation layer, all live in
  `sumatra-android/android/app/src/main/java/com/sumatrapdf/reader/`
  as Compose. The "Win32 UI 1:1" rule in `sumatra-android/agents.md`
  applies — the toolbar order, the menu sections, the keyboard
  shortcuts, the page-pan / fit-mode rules all match the Win32 source.

### Design contract for the port

The full rules the user has corrected multiple times live in
`sumatra-android/agents.md`. The three that come up most often:

1. **There is no "Standalones" shelf.** A book that does not match a
   real series folder becomes its own rail row, named after the
   book. The Windows Chatterbox rail had this and the user wants it
   gone.
2. **Each book IS its own series title.** A book in `Download/`
   titled `Onimai` is the *Onimai* series. The folder name, the
   book title, and the API-detected series are all valid row names.
3. **Switching shelves closes any open book detail** — both
   `LibraryModel.selectRow()` and `toggleDeskpan()` set
   `detail = null`, so clicking a different shelf in the rail
   always clears the detail view that was open.

### Test setup

- **Two Android devices** are required: Note 20 Ultra (USB,
  `R5CR90XZH9P`, Android 11, 1080x2316, density 420) and Z Fold 4
  (wireless, `192.168.0.3:42325`, Android 13, 904x2316, density 344).
  A fix is not done until both have been tested with a screenshot.
- **Screenshots are the source of truth**, not the on-disk
  `library.json` or `series/*.json` files. The build can succeed,
  the cache can be correct, and the UI can still show something
  different. If the screenshot doesn't match the code, fix the
  code.
- **`clang-format` is OFF-LIMITS for `*.kt` files.** The C++ Visual
  Studio style mangles Kotlin — it breaks `package` declarations,
  merges imports, removes newlines after functions. Never run
  `clang-format` on `*.kt`; if a Kotlin file looks wrong, fix the
  formatting by hand.
- **No comments in code.** The user has explicitly said "NEVER ADD
  COMMENTS TO CODE" multiple times. Code should be self-explanatory
  by its structure and naming.
- **2-attempt rule**: after two failed attempts on the same
  problem, STOP and search the web. The user has stated this as a
  top-priority rule.
