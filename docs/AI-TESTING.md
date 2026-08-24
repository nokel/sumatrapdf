# AI Testing Notes

Read only when testing or reproducing bugs.

## Prefer targeted tests

Do not run broad suites unless explicitly needed.

Use:

- existing issue test for issue-specific work;
- existing affected regression test;
- `bun cmd/run-unit-tests.ts -dbg` for relevant C++ unit-test work;
- targeted manual evidence when no test exists.

## App launch

For isolated ad-hoc testing, normally use:

-for-testing

Remember that `-for-testing` bypasses normal instance reuse.

## Prefer existing infrastructure

Use existing:

- `tests/control.ts`
- `-dbg-control`
- `tests/winapi.ts`
- `tests/win-automation.ts`
- existing logs/probes/tests

before creating anything new.

Never hardcode command IDs; use `cmdId("CmdName")`.

## GUI automation

Prefer dbg-control/log/state inspection over mouse automation.

If the same GUI method fails twice, stop using it.

## Scratch files

Use `tests/tmp/` or OS temp.

Do not leave runtime scratch files directly in `tests/`.

## Build/test success

Judge builds/tests by exit code and actual observed result, not by grepping output text.