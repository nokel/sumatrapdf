# SUMATRAPDF LIBRARY AGENT OPERATING CONTRACT

These rules apply to every Library task unless the user explicitly overrides one.

Read this before making any change.

## 1. Authority

Priority order:

1. The user's current explicit requirement.
2. This operating contract.
3. Existing documented project architecture.
4. Existing tests.
5. Your interpretation.

If existing code or tests conflict with the user's requirement:

    do not silently preserve the conflicting behavior
    report the conflict
    implement the user's requirement

Do not reinterpret an explicit requirement into something easier to test.

---

## 2. Exact status meanings

Use these terms exactly:

PASS
    Every required acceptance condition was executed and passed.

FAIL
    At least one required acceptance condition failed.

INCOMPLETE
    A required acceptance condition was not executed or could not finish.

Never write:

    PASS with known issue
    PASS except
    mostly PASS
    expected failure
    functionally PASS

If one condition fails:

    status = FAIL

If one required test was not run:

    status = INCOMPLETE

A negative test that correctly rejects bad behavior is:

    PASS

Do not use "FAIL" to describe a successful negative test.

---

## 3. Never substitute evidence

If the task requires:

    actual configured Library

then you must use:

    actual configured Library

These are NOT equivalent:

    fixture
    copied Library
    synthetic Library
    reduced Library
    bench tool

If the required environment cannot be tested:

    status = INCOMPLETE

Do not substitute another environment and declare PASS.

---

## 4. Never infer a runtime result

Required runtime behavior must be observed directly.

These do not count:

    expected by control flow
    code inspection shows
    should work
    equivalent test passed
    not modified
    previously passed
    inferred from architecture

If the requirement says:

    real GUI does X

then prove it in the real GUI.

---

## 5. Do not change tests to obtain PASS

Tests are evidence, not obstacles.

Before modifying an existing test, determine whether:

    A. production behavior is wrong

or:

    B. the test is demonstrably inconsistent with the current explicit user requirement

Default assumption:

    production code is wrong until proven otherwise

Forbidden:

    broadening an assertion because current code fails
    accepting multiple outcomes when one exact outcome is required
    disabling a production subsystem only to make equality tests pass
    deleting a failing assertion
    changing fixtures solely to erase a failure
    calling a failing regression "pre-existing" and declaring PASS

If an existing test must change:

    report the old assertion
    report the new assertion
    quote the user requirement that makes the old assertion incorrect
    prove the production behavior first

If you cannot prove that:

    do not modify the test

---

## 6. Baseline first

Before production changes:

1. Identify the relevant existing implementation.
2. Run the relevant existing tests.
3. Record baseline failures.
4. Reproduce the requested defect.

Do not start by adding a new implementation when an owner already exists.

Search first.

---

## 7. One owner per behavior

Do not create parallel implementations.

For each changed behavior identify:

    current owner
    new owner if ownership changes

There must be one authoritative owner.

Examples:

    fingerprint identity
    catalogue state
    scan cancellation
    scan progress
    persistence
    Series hierarchy

Do not create two sources of truth.

---

## 8. One task per chunk

Do only the requested job.

Do not add unrelated:

    refactors
    cleanup
    new architecture
    extra features
    test frameworks
    compatibility layers

If another defect is discovered:

    record it
    do not fix it unless it blocks the current task

Exception:

If the discovered defect makes the requested result impossible or unsafe:

    fix the smallest blocking defect
    explain why it was required

---

## 9. Preserve product invariants

For Library work, these are permanent unless the user explicitly changes them:

    one Library scan traversal
    no second whole-Library processing pass
    each completed book becomes durable immediately
    progressive display may show each completed book immediately
    interrupted scans preserve completed work
    restart resumes completed work
    known valid fingerprint identity is reused
    no title/author/filename/path heuristic identity merge
    deferred work operates on explicit queued books
    deferred work does not rescan the whole Library

Do not trade one invariant for another.

---

## 10. Performance means measured production performance

If the user reports:

    scan takes 20 minutes

then the defect is:

    real scan takes 20 minutes

A fixture timing does not prove it fixed.

For performance work:

    profile the real production path
    identify measured owners
    fix the largest proven owner
    measure the same production path again

Do not declare performance PASS from synthetic data unless the user explicitly requested synthetic benchmarking.

---

## 11. Test before implementation when possible

For a defect:

    reproduce
    create or identify a failing test
    run it and observe failure
    implement smallest fix
    run it and observe success

Use red/green testing where practical.

Do not create a test that already passes and call that proof of the fix.

---

## 12. Manual GUI verification is mandatory for GUI behavior

Automated tests do not replace GUI verification.

For Library UI behavior use the compiled:

    SumatraPDF.exe

Exercise the actual feature.

For visual behavior capture direct evidence when practical.

Examples:

    progressive books appear
    progress bars move
    Back restores scroll
    scan remains responsive
    restart restores Library
    no crash

---

## 13. Do not weaken production behavior for tests

Test-only switches must not alter the behavior being tested.

Do not add environment variables that suppress relevant production behavior merely to get deterministic PASS results.

If nondeterministic production behavior interferes with a test:

    isolate the assertion
    control the external dependency
    or test the deterministic contract separately

Do not hide the production behavior.

---

## 14. Cancellation and shutdown

Do not:

    kill worker threads unsafely
    abandon unbounded worker threads
    use hard process kill as proof of clean shutdown

If the requirement concerns normal close:

    trigger normal application shutdown
    wait for owned workers
    verify process exit

A test harness kill is not equivalent to normal shutdown.

---

## 15. Production-code comments

NEVER add new comments in production code.

Existing comments can stay.

Do not waste scope deleting them unless the current change makes one directly false.

Put explanations in the report, not source.

---

## 16. Do not stop at the first green test

After the targeted test passes:

    run the relevant regression tests
    manually verify the affected production behavior
    compare against the exact acceptance conditions

Do not declare PASS until all required evidence exists.

---

## 17. Pre-existing failures

A pre-existing failure may be excluded only if:

1. It was reproduced before your change.
2. The exact same failure remains after your change.
3. The current task does not require that behavior to pass.

Report it as:

    BASELINE FAILURE — unchanged

If any of those three conditions is missing:

    do not exclude it

---

## 18. No self-created exceptions

You may not declare a requirement:

    out of scope
    equivalent
    unnecessary
    already proven
    pre-existing
    test-harness only

unless direct evidence supports that statement.

The user decides whether a requirement may be dropped.

---

## 19. Contradiction check before reporting

Before writing the final status, inspect your own report for contradictions.

Examples:

    "PASS" + "not tested"
        -> INCOMPLETE

    "PASS" + "known race"
        -> FAIL

    "PASS" + required regression failing
        -> FAIL

    "one pass" + final stage repeats per-book processing over all books
        -> FAIL

    "O(1)" + serializes N catalogue records per item
        -> statement is false

Correct the status before replying.

---

## 20. Evidence table is mandatory

Before declaring PASS create this table internally and include it in the report:

| Requirement | Evidence | Result |
|---|---|---|
| exact requirement | command/runtime observation | PASS/FAIL/NOT RUN |

Rules:

    every acceptance condition gets one row
    no evidence -> NOT RUN
    one FAIL -> overall FAIL
    one NOT RUN -> overall INCOMPLETE
    PASS only if every row is PASS

Do not summarize away failed rows.

---

## 21. Final report must separate facts from interpretation

Report:

OBSERVED
    direct runtime/test evidence

CHANGED
    exact production changes

NOT CHANGED
    relevant owners intentionally preserved

BASELINE FAILURES
    failures proven before this change

UNRESOLVED
    anything still failing or untested

STATUS
    PASS / FAIL / INCOMPLETE

Do not present assumptions as observations.

---

## 22. Stop rule

Do not invent follow-up work after satisfying the task.

Do not broaden the chunk.

If all acceptance conditions pass:

    report PASS
    stop

If one fails:

    continue fixing the current task

If blocked from obtaining required evidence:

    report INCOMPLETE
    state the exact blocker
    stop

Do not convert missing evidence into PASS.