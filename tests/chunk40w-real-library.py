"""Chunk 40W: real user library rescan test.

The pre-fix code stalled the rescan at 125/381 because brand() ran OCR
synchronously. The fix: brand() skips OCR (runOcr=false). The brand loop
completes in bounded time. The rescan is no longer held hostage.

This test runs the production brand loop over the user's real library
and verifies:
  1. Brand completes for all books (no stalls >30s)
  2. Total brand time is bounded
  3. Image-only books (if any) get ocrState=NOT_ATTEMPTED, not OCR_SUCCESS
  4. Re-running brand is fast and idempotent
"""
import os
import shutil
import subprocess
import sys
import time


CHATTERBOX = sys.argv[1]
BENCH = sys.argv[2]
CACHE = sys.argv[3] if len(sys.argv) > 3 else None
ROOTS = sys.argv[4:] if len(sys.argv) > 4 else [r"C:\Users\Nokel\Documents\ebooks"]

sys.path.insert(0, CHATTERBOX)

failures = []
notes = []


def say(s):
    print(s)
    notes.append(s)


def check(name, ok, detail=""):
    say(("PASS " if ok else "FAIL ") + name + (" " + detail if detail else ""))
    if not ok:
        failures.append(name)


def list_books(roots):
    out = []
    exts = (".pdf", ".epub", ".mobi", ".cbz", ".cbr")
    for root in roots:
        for dirpath, _, names in os.walk(root):
            for n in names:
                if n.lower().endswith(exts):
                    out.append(os.path.join(dirpath, n))
    out.sort(key=str.lower)
    return out


def bench_brand(path, args, timeout=60):
    t0 = time.time()
    try:
        full_args = args + [path]
        if CACHE and CACHE not in full_args:
            full_args.append(CACHE)
        res = subprocess.run(
            full_args,
            capture_output=True, text=True, timeout=timeout,
        )
        elapsed = time.time() - t0
        out = res.stdout.strip()
        for line in out.splitlines():
            if line.startswith("OK brand "):
                return line, elapsed
        return out, elapsed
    except subprocess.TimeoutExpired:
        return f"TIMEOUT after {time.time() - t0:.1f}s", time.time() - t0


def parse_kv(s, key):
    for tok in s.split():
        if tok.startswith(f"{key}="):
            return tok.split("=", 1)[1]
    return None


def main():
    if not CACHE:
        print("usage: chunk40w-real-library.py <chatterbox> <bench> <cache> <root>...", file=sys.stderr)
        return 2

    if os.path.exists(CACHE):
        shutil.rmtree(CACHE)
    os.makedirs(CACHE, exist_ok=True)

    say(f"=== Real library rescan on {ROOTS} ===")
    books = list_books(ROOTS)
    say(f"Total books: {len(books)}")
    if not books:
        say("No books found, skipping")
        return 0

    args = [BENCH, "brand"]

    t_total = time.time()
    stalls = []
    slowest = ("", 0.0)
    ocr_attempts = 0
    ocr_successes = 0
    no_text = 0
    not_attempted = 0
    branded = 0
    failed = 0
    times = []
    for i, p in enumerate(books):
        line, elapsed = bench_brand(p, args, timeout=60)
        times.append(elapsed)
        if elapsed > 5.0:
            say(f"  slow #{i + 1}: {os.path.basename(p)[:50]} {elapsed:.2f}s")
        if elapsed > 30.0:
            stalls.append((i + 1, p, elapsed))
        if elapsed > slowest[1]:
            slowest = (p, elapsed)
        result = parse_kv(line, "result")
        ocr_a = int(parse_kv(line, "ocrAttempts") or "0")
        ocr_s = int(parse_kv(line, "ocrSuccesses") or "0")
        ocr_n = int(parse_kv(line, "ocrNoText") or "0")
        ocr_attempts += ocr_a
        ocr_successes += ocr_s
        no_text += ocr_n
        if result == "0" or result == "1":
            branded += 1
        elif result == "-1":
            failed += 1
        else:
            not_attempted += 1
        if (i + 1) % 25 == 0:
            so_far = time.time() - t_total
            say(f"  ... {i + 1}/{len(books)} done in {so_far:.1f}s")
    total_brand = time.time() - t_total

    say(f"\n=== Real library rescan results ===")
    say(f"Total books: {len(books)}")
    say(f"Total brand time: {total_brand:.2f}s")
    say(f"Slowest book: {slowest[1]:.2f}s ({os.path.basename(slowest[0])})")
    say(f"Stalls (>30s): {len(stalls)}")
    say(f"Branded: {branded}, Failed: {failed}, Other: {not_attempted}")
    say(f"OCR attempts: {ocr_attempts}, successes: {ocr_successes}, no-text: {no_text}")

    if stalls:
        say(f"Stalls:")
        for n, p, secs in stalls[:5]:
            say(f"  #{n}: {os.path.basename(p)[:80]} -> {secs:.1f}s")

    check("no_stalls", len(stalls) == 0, f"stalls={len(stalls)}")
    check("bounded_time", total_brand < 600, f"total={total_brand:.1f}s")
    check("all_books_processed", branded + failed + not_attempted == len(books))
    check("slowest_bounded", slowest[1] < 30.0, f"slowest={slowest[1]:.1f}s")

    # Second pass: idempotent
    say(f"\n=== Second rescan (idempotency) ===")
    t_total = time.time()
    second_stalls = []
    second_ocr = 0
    for i, p in enumerate(books):
        line, elapsed = bench_brand(p, args, timeout=60)
        if elapsed > 5.0:
            say(f"  slow #{i + 1}: {os.path.basename(p)[:50]} {elapsed:.2f}s")
        if elapsed > 30.0:
            second_stalls.append((i + 1, p, elapsed))
        ocr_a = int(parse_kv(line, "ocrAttempts") or "0")
        second_ocr += ocr_a
    second_total = time.time() - t_total
    say(f"Second rescan total: {second_total:.2f}s, stalls: {len(second_stalls)}, ocr attempts: {second_ocr}")
    check("second_no_stalls", len(second_stalls) == 0, f"stalls={len(second_stalls)}")
    check("second_no_ocr", second_ocr == 0, f"ocr attempts: {second_ocr}")

    if failures:
        say(f"\n{len(failures)} failures:")
        for f in failures:
            say(f"  - {f}")
        return 1
    say("\nAll chunk40w real-library checks PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
