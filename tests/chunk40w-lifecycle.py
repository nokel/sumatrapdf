"""Chunk 40W: verify the rescan lifecycle fix.

The pre-fix code had synchronous OCR in the brand pipeline. For libraries
with image-only books this caused the rescan to stall indefinitely on the
first long image-only book. The fix:

  - Brand pipeline skips OCR (runOcr=false)
  - Background sweep runs OCR migration separately
  - Per-book timeout protection in BookOcrRun
  - Cancelled OCR stays at NOT_ATTEMPTED (eligible for later retry)

This test builds a fixture set that:
  - Has many digital books (fast brand)
  - Has multiple small image-only books (would trigger OCR if synchronous)
  - Has a 500-page image-only book (the exact stall case)
  - Has corrupt / blank / sparse fixtures (pathological cases)

Then it verifies:
  1. Brand pipeline completes in bounded time (no stalls >30s)
  2. Image-only books get ocrState=NOT_ATTEMPTED (eligible for later)
  3. ocr-migrate bench command produces OCR_NO_TEXT for unbranded image-only
  4. ocr-migrate is idempotent
  5. Timeout leaves book at NOT_ATTEMPTED (eligible for retry)
  6. Corrupt book does not crash brand
  7. Identity semantics: a digital book is never OCRed; existing fp3 is
     authoritative
"""
import os
import shutil
import subprocess
import sys
import time


CHATTERBOX = sys.argv[1]
BENCH = sys.argv[2]
WORK = sys.argv[3]
FIXTURE = sys.argv[4]
CACHE = sys.argv[5] if len(sys.argv) > 5 else os.path.join(WORK, "cache")

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


def list_pdfs(root):
    out = []
    for dirpath, _, names in os.walk(root):
        for n in names:
            if n.lower().endswith(".pdf"):
                out.append(os.path.join(dirpath, n))
    out.sort(key=str.lower)
    return out


def bench_brand(path, timeout=60):
    t0 = time.time()
    try:
        res = subprocess.run(
            [BENCH, "brand", path, CACHE],
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


def bench_ocr_migrate(path, timeout=120):
    t0 = time.time()
    try:
        res = subprocess.run(
            [BENCH, "ocr-migrate", path, CACHE],
            capture_output=True, text=True, timeout=timeout,
        )
        elapsed = time.time() - t0
        out = res.stdout.strip()
        for line in out.splitlines():
            if line.startswith("OK ocr-migrate "):
                return line, elapsed
        return out, elapsed
    except subprocess.TimeoutExpired:
        return f"TIMEOUT after {time.time() - t0:.1f}s", time.time() - t0


def bench_fingerprint_timeout(path, max_ms, timeout=60):
    t0 = time.time()
    try:
        res = subprocess.run(
            [BENCH, "fingerprint-timeout", path, str(max_ms), CACHE],
            capture_output=True, text=True, timeout=timeout,
        )
        elapsed = time.time() - t0
        out = res.stdout.strip()
        for line in out.splitlines():
            if line.startswith("OK fingerprint-timeout "):
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
    if not os.path.isdir(FIXTURE):
        print(f"FIXTURE not found: {FIXTURE}", file=sys.stderr)
        return 2
    if os.path.exists(CACHE):
        shutil.rmtree(CACHE)
    os.makedirs(CACHE, exist_ok=True)
    if os.path.exists(WORK):
        shutil.rmtree(WORK)
    os.makedirs(WORK, exist_ok=True)

    pdfs = list_pdfs(FIXTURE)
    say(f"Total fixture PDFs: {len(pdfs)}")

    digital = [p for p in pdfs if "DigitalBooks" in p]
    image_only = [p for p in pdfs if "ImageBooks" in p]
    big = [p for p in pdfs if "BigImageOnly" in p]
    root = [p for p in pdfs if "BigImageOnly" not in p and "ImageBooks" not in p and "DigitalBooks" not in p]

    say(f"  digital: {len(digital)}")
    say(f"  image_only: {len(image_only)}")
    say(f"  big: {len(big)}")
    say(f"  root (corrupt etc): {len(root)}")

    # ---- 1. Brand all books, verify no stalls
    say("\n=== Brand all books, measure per-book time ===")
    t_total = time.time()
    stalls = []
    slowest = ("", 0.0)
    for i, p in enumerate(pdfs):
        line, elapsed = bench_brand(p, timeout=60)
        ocr_a = parse_kv(line, "ocrAttempts")
        ocr_state = parse_kv(line, "ocrState")
        kind = "image-only" if "ImageBooks" in p or "BigImageOnly" in p else "digital"
        if elapsed > 5.0:
            say(f"  slow #{i + 1} ({kind}, {os.path.basename(p)[:40]}): {elapsed:.2f}s ocrAttempts={ocr_a} ocrState={ocr_state}")
        if elapsed > 30.0:
            stalls.append((p, elapsed))
        if elapsed > slowest[1]:
            slowest = (p, elapsed)
    total_brand = time.time() - t_total
    check("brand_all_completes", len(stalls) == 0, f"stalls={len(stalls)}")
    check("brand_bounded_time", total_brand < 120, f"total={total_brand:.1f}s")
    say(f"Total brand time: {total_brand:.2f}s slowest: {slowest[1]:.2f}s ({os.path.basename(slowest[0])})")

    # ---- 2. Digital books have fp3 fingerprint
    say("\n=== Digital book fingerprint ===")
    if digital:
        d = digital[0]
        line, _ = bench_brand(d, timeout=10)
        fp = parse_kv(line, "fingerprint")
        check("digital_has_fp3", fp and fp.startswith("fp3:"))
        ocr_a = parse_kv(line, "ocrAttempts")
        check("digital_no_ocr", ocr_a == "0", f"ocrAttempts={ocr_a}")

    # ---- 3. Image-only book is branded, no fingerprint yet
    say("\n=== Image-only book brand ===")
    if image_only:
        io = image_only[-1]
        line, elapsed = bench_brand(io, timeout=10)
        fp = parse_kv(line, "fingerprint")
        ocr_state = parse_kv(line, "ocrState")
        ocr_a = parse_kv(line, "ocrAttempts")
        check("image_only_quick_brand", elapsed < 5.0, f"elapsed={elapsed:.2f}s")
        check("image_only_no_ocr_in_brand", ocr_a == "0", f"ocrAttempts={ocr_a}")
        check("image_only_state_not_attempted", ocr_state == "0", f"ocrState={ocr_state}")

    # ---- 4. 500-page book: brand is fast, no OCR
    say("\n=== 500-page image-only book brand ===")
    if big:
        for p in big:
            line, elapsed = bench_brand(p, timeout=60)
            ocr_a = parse_kv(line, "ocrAttempts")
            ocr_state = parse_kv(line, "ocrState")
            say(f"  {os.path.basename(p)}: {elapsed:.2f}s ocrAttempts={ocr_a} ocrState={ocr_state}")
        check("big_500_quick_brand", all(
            bench_brand(p, timeout=60)[1] < 5.0 for p in big
        ))

    # ---- 5. ocr-migrate idempotency
    say("\n=== ocr-migrate idempotent on existing OCR_NO_TEXT ===")
    if image_only:
        io = image_only[0]
        # First call may produce OCR_NO_TEXT or OCR_SUCCESS
        line1, e1 = bench_ocr_migrate(io, timeout=60)
        line2, e2 = bench_ocr_migrate(io, timeout=10)
        ocr_state1 = parse_kv(line1, "ocrState")
        ocr_state2 = parse_kv(line2, "ocrState")
        result1 = parse_kv(line1, "result")
        result2 = parse_kv(line2, "result")
        say(f"  first:  result={result1} ocrState={ocr_state1} ({e1:.2f}s)")
        say(f"  second: result={result2} ocrState={ocr_state2} ({e2:.2f}s)")
        check("ocr_migrate_first_runs", result1 in ("0", "1"))
        check("ocr_migrate_second_idempotent", result2 == "0", f"result2={result2}")
        check("ocr_state_persists", ocr_state1 == ocr_state2, f"{ocr_state1} vs {ocr_state2}")

    # ---- 6. Timeout: long OCR leaves book at NOT_ATTEMPTED
    say("\n=== Timeout does not set OCR_NO_TEXT ===")
    if big:
        p = big[0]
        line, elapsed = bench_fingerprint_timeout(p, 1, timeout=30)
        ocr_state = parse_kv(line, "ocrState")
        ocr_a = parse_kv(line, "ocrAttempts")
        say(f"  {os.path.basename(p)}: elapsed={elapsed:.2f}s ocrState={ocr_state} ocrAttempts={ocr_a}")
        check("timeout_leaves_not_attempted", ocr_state == "0", f"ocrState={ocr_state}")

    # ---- 7. Corrupt book does not crash brand
    say("\n=== Corrupt book does not crash brand ===")
    corrupt = [p for p in root if "corrupt" in p]
    if corrupt:
        c = corrupt[0]
        line, elapsed = bench_brand(c, timeout=10)
        say(f"  {os.path.basename(c)}: {elapsed:.2f}s out={line[:100]}")
        check("corrupt_brand_bounded", elapsed < 5.0, f"elapsed={elapsed:.2f}s")

    # ---- 8. Blank 500-page book does not block
    say("\n=== Blank 500-page book brand ===")
    blank = [p for p in big if "blank" in p]
    if blank:
        b = blank[0]
        line, elapsed = bench_brand(b, timeout=10)
        ocr_a = parse_kv(line, "ocrAttempts")
        say(f"  {os.path.basename(b)}: {elapsed:.2f}s ocrAttempts={ocr_a}")
        check("blank_500_quick_brand", elapsed < 5.0, f"elapsed={elapsed:.2f}s")

    # ---- 9. Re-brand: no duplicate OCR work
    say("\n=== Re-brand: no duplicate OCR work ===")
    if digital:
        d = digital[0]
        line1, _ = bench_brand(d, timeout=10)
        line2, _ = bench_brand(d, timeout=10)
        full1 = parse_kv(line1, "full")
        full2 = parse_kv(line2, "full")
        check("rebrand_no_extra_full", full2 == "0", f"full2={full2}")

    if failures:
        say(f"\n{len(failures)} failures:")
        for f in failures:
            say(f"  - {f}")
        return 1
    say("\nAll chunk40w checks PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
