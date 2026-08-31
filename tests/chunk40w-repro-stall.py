"""Reproduce the 125/381 rescan stall and verify the fix.

This script simulates the production Library rescan lifecycle:
  1. Python shelf.scan classifies all books (fast)
  2. C++ brand pipeline runs LibrarySidecarBrandIfUnbranded for each
     brandable book (this is where the OCR stall happens)

We measure:
  - Time to classify
  - Time per book to brand
  - Total time to complete the rescan
  - Any books that exceed a sane per-book budget (the actual stall)
"""
import os
import sys
import time
import subprocess
import json
import shutil
import threading
import socket
import urllib.request
import tempfile

CHATTERBOX = sys.argv[1]
sys.path.insert(0, CHATTERBOX)
os.environ["SUMATRA_LIBRARY_CACHE_ROOT"] = sys.argv[4] if len(sys.argv) > 4 else os.path.join(tempfile.gettempdir(), "chunk40w-cache")

from audiobook import pdfbook
from audiobook.library import shelf


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


def start_library_service(roots, cache_dir, appdata_dir, port):
    env = os.environ.copy()
    env["SUMATRA_LIBRARY_CACHE_ROOT"] = cache_dir
    proc = subprocess.Popen(
        [
            sys.executable, "-m", "audiobook.library",
            "--port", str(port),
            "--root", str(roots[0]),
        ],
        cwd=CHATTERBOX, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    for _ in range(80):
        try:
            urllib.request.urlopen(f"http://127.0.0.1:{port}/status", timeout=1)
            return proc
        except Exception:
            time.sleep(0.25)
    proc.kill()
    raise RuntimeError("library service did not start")


def stop_library_service(proc, port):
    try:
        urllib.request.urlopen(urllib.request.Request(
            f"http://127.0.0.1:{port}/quit", method="POST",
            headers={"Content-Type": "application/json"}, data=b"{}"), timeout=2)
    except Exception:
        pass
    try:
        proc.wait(timeout=5)
    except Exception:
        proc.kill()


def list_files(roots):
    out = []
    for root in roots:
        for dirpath, dirnames, filenames in os.walk(root):
            for f in filenames:
                ext = os.path.splitext(f)[1].lower()
                if ext in (".pdf", ".epub", ".mobi", ".cbz", ".cbr"):
                    path = os.path.join(dirpath, f)
                    st = os.stat(path)
                    out.append({"path": path, "size": st.st_size, "mtime": st.st_mtime})
    out.sort(key=lambda x: x["path"].lower())
    return out


def bench_fingerprint(path, bench_exe):
    t0 = time.time()
    res = subprocess.run(
        [bench_exe, "fingerprint", path],
        capture_output=True, text=True, timeout=600,
    )
    elapsed = time.time() - t0
    info = {}
    for line in res.stdout.splitlines():
        if line.startswith("OK fingerprint "):
            for tok in line.split():
                if "=" in tok:
                    k, v = tok.split("=", 1)
                    info[k] = v
            break
    return info, elapsed


def bench_brand(path, bench_exe, timeout=60):
    t0 = time.time()
    try:
        res = subprocess.run(
            [bench_exe, "brand", path],
            capture_output=True, text=True, timeout=timeout,
        )
        elapsed = time.time() - t0
        return res.stdout, elapsed
    except subprocess.TimeoutExpired:
        elapsed = time.time() - t0
        return f"TIMEOUT after {elapsed:.1f}s", elapsed


def main():
    if len(sys.argv) < 4:
        print("usage: chunk40w-repro-stall.py <chatterbox> <fixture> <bench> [cache]", file=sys.stderr)
        sys.exit(2)
    fixture = sys.argv[2]
    bench = sys.argv[3]
    cache = sys.argv[4] if len(sys.argv) > 4 else os.path.join(tempfile.gettempdir(), "chunk40w-cache")
    if os.path.exists(cache):
        shutil.rmtree(cache)
    os.makedirs(cache, exist_ok=True)

    print(f"=== Repro rescan stall on {fixture} ===")
    files = list_files([fixture])
    print(f"Total files: {len(files)}")

    t0 = time.time()
    pdfbook.CACHE_ROOT = cache
    shelf._identity_cache.clear()
    shelf._hash_cache.clear()

    t_classify = time.time()
    index = shelf.scan([fixture], files, previous=None, progress=None, finalize=False)
    print(f"Classification: {time.time() - t_classify:.2f}s for {len(index['books'])} books")

    print("Brandable (unbranded) books:")
    brandable = [b["path"] for b in index["books"] if not b.get("fingerprint") and not b.get("identity")]
    print(f"  total: {len(brandable)}")
    for i, path in enumerate(brandable[:10]):
        print(f"  {i + 1}. {os.path.basename(path)}")
    if len(brandable) > 10:
        print(f"  ... and {len(brandable) - 10} more")

    print("\nSimulating brand pipeline (with per-book budget check):")
    print(f"  Per-book soft budget: 30s (anything over is a stall)")
    stalled = []
    successes = 0
    total_brand_t = 0.0
    t_brand_start = time.time()
    for i, path in enumerate(brandable):
        bstdout, belapsed = bench_brand(path, bench)
        total_brand_t += belapsed
        if belapsed > 5.0:
            print(f"  SLOW #{i + 1}: {os.path.basename(path)[:60]} took {belapsed:.1f}s")
        if belapsed > 30.0:
            stalled.append((i + 1, path, belapsed))
            print(f"  STALL #{i + 1}: {os.path.basename(path)[:60]} took {belapsed:.1f}s")
        if "OK brand result=1" in bstdout:
            successes += 1
        if (i + 1) % 5 == 0:
            elapsed_so_far = time.time() - t_brand_start
            print(f"  ... {i + 1}/{len(brandable)} processed in {elapsed_so_far:.1f}s")
    total_elapsed = time.time() - t0

    print(f"\n=== Summary ===")
    print(f"Total time: {total_elapsed:.2f}s")
    print(f"Brand pipeline: {total_brand_t:.2f}s across {len(brandable)} books")
    print(f"  successes: {successes}")
    print(f"  stalled (>30s): {len(stalled)}")
    if stalled:
        print(f"\n  Stall repro confirmed. Top stalls:")
        for n, path, secs in stalled[:5]:
            print(f"    #{n}: {os.path.basename(path)[:80]} -> {secs:.1f}s")
        return 1
    print("\n  No stalls. (The pre-fix reproduction did not produce a stall on this fixture.)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
