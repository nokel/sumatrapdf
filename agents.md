# Agent Rules

This is primarily a C++ Windows program using Win32 APIs.

## Core rules

- Do only the requested task/chunk.
- Make the smallest change needed.
- Do not start unrelated refactors, cleanup, or investigations.
- Stop when the requested result is proven.
- Do not claim PASS unless every required acceptance test passed.
- A regression in previously working behaviour means FAIL.
- Do not commit or push unless explicitly asked.

## Existing code first

Before adding code, search for an existing or partial implementation.

For Windows SumatraPDF functionality:

- C++ is the default.
- Reuse/complete existing C++ code before adding Python behaviour.
- Do not replace native functionality with Python because it is easier.
- Check existing helpers, tests, dbg-control, bench tools, and partial features before creating new scripts or infrastructure.

## Regression safety

When changing an existing subsystem:

- identify what already works;
- test the current fix;
- re-test affected previously working behaviour;
- do not bypass or disable an existing working path to fix another bug.

## Scope

If a task names allowed files, stay within them unless another file is directly required.

If you must touch an extra file, make the smallest required change and explain why.

## Testing

Prefer existing direct observables:

- unit tests;
- existing issue/ad-hoc tests;
- dbg-control;
- logs;
- persisted files;
- existing API responses;
- production reader/writer round-trips.

If one GUI automation method fails twice, stop using it.

Do not create new test infrastructure when an existing mechanism can answer the question.

## Reports

Keep reports concise:

CHUNK STATUS: PASS / FAIL

FILES CHANGED:
- ...

EXISTING CODE CHECKED:
- ...

ROOT CAUSE:
- ...

EXACT CHANGES:
- ...

TESTS:
- ...

REGRESSION CHECKS:
- ...

UNRESOLVED ISSUES:
- None

## Reference docs

Read only when relevant:

- `docs/AI-BUILD-NOTES.md`
- `docs/AI-TESTING.md`
- `docs/LIBRARY-NOTES.md`
- `docs/book-record.md`
- `docs/dict-support.md`
- `docs/direct2d.md`
- `docs/djvudec-threading.md`
- `docs/info-about-comicinfo-xml.md`
- `docs/linux-port.md`
- `docs/linux-port-progress.md`
- `docs/mac-port-plan.md`
- `docs/mac-port-progress.md`
- `docs/ocr-ux-ideas.md`
- `docs/pdf-form-filling-plan.md`
- `docs/plus-merge.md`
- `docs/read-aloud-ux-ideas.md`
- `docs/super-themes.md`
- `docs/update-bzip2.md`
- `docs/update-extract.md`
- `docs/update-gumbo.md`
- `docs/update-jbig2dec.md`
- `docs/update-mujs.md`
- `docs/update-openjpeg.md`
- `docs/update-zlib.md`
- `docs/update-zopfli.md`
- `docs/upgrade-chmdec.md`
- `docs/upgrade-djvudec.md`