# AI Build Notes

Read only for build/generated-file/project-file work.

## Build

Normal debug build:

bun cmd/build.ts -debug

Debug exe:

out/dbg64/SumatraPDF.exe

Use `cmd/build.ts` as the build entry point.

## Formatting

After editing C/C++ under `src/`, run clang-format on touched files before building.

After editing generated-code TypeScript under `cmd/`, use the repository formatter/generator.

## Generated files

Settings/library schema:

- edit `cmd/gen-settings.ts`
- regenerate with `bun cmd/gen-settings.ts` or `bun cmd/gen-code.ts`

Commands:

- edit `cmd/gen-commands.ts`
- regenerate

Flags:

- edit `cmd/gen-flags.ts`
- regenerate

Do not hand-edit generated sections as the permanent fix.

## New src files

If adding a source file, register it in the existing VS/premake/mingw project lists.

## MuPDF

Changes under `ext/mupdf` must also have a matching patch under `ext/patches/`.

## Local toolchain

VS Build Tools 2022 is installed.

clang-format path:

C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Tools\Llvm\x64\bin\clang-format.exe

A fresh worktree may need:

bun cmd/gen-docs.ts