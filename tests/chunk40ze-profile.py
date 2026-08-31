"""Chunk 40Z-E: Profile the real Library scan.

Measures per-stage timing for: walk, ReadDocLook, fingerprint, /book,
/manifest, /index, merge_partial, save_index, save_checkpoint.
"""
import json
import os
import shutil
import sys
import time
from pathlib import Path

CHATTERBOX, EXE, CACHE, APPDATA, LIBRARY, PORT, BENCH = sys.argv[1:8]
sys.path.insert(0, CHATTERBOX)

from audiobook.library import shelf

import urllib.request
import urllib.error


def reset_dirs():
    if os.path.exists(CACHE):
        shutil.rmtree(CACHE)
    os.makedirs(os.path.join(CACHE, "library"), exist_ok=True)
    if os.path.exists(APPDATA):
        shutil.rmtree(APPDATA)
    os.makedirs(APPDATA, exist_ok=True)


def write_settings():
    settings = (
        "Audiobook [\n"
        f"\tPythonExe = {CHATTERBOX}\\.venv-amd\\Scripts\\python.exe\n"
        f"\tChatterboxDir = {CHATTERBOX}\n"
        f"\tLibraryPort = {PORT}\n"
        f"\tLibraryRoots = {LIBRARY}\n"
        "\tLibraryHome = true\n"
        "]\n"
        "HomePage [\n"
        "\tHomePageViewMode = list\n"
        "]\n"
    )
    Path(APPDATA, "SumatraPDF-settings.txt").write_text(settings, "utf8")
    Path(CACHE, "library", "scan_scope.txt").write_text("2", "utf8")


def count_files():
    n = 0
    for root, dirs, files in os.walk(LIBRARY):
        for f in files:
            if f.lower().endswith((".pdf", ".epub", ".mobi", ".azw3", ".fb2", ".cbz", ".xps")):
                n += 1
    return n


def instrument_shelf():
    """Monkey-patch shelf to count and time each operation."""
    stats = {
        "scan_calls": 0,
        "scan_total_ms": 0.0,
        "scan_files_in_calls": 0,
        "scan_max_ms": 0.0,
        "merge_calls": 0,
        "merge_total_ms": 0.0,
        "merge_files_in_calls": 0,
        "merge_max_ms": 0.0,
        "save_index_calls": 0,
        "save_index_total_ms": 0.0,
        "save_index_max_ms": 0.0,
        "save_checkpoint_calls": 0,
        "save_checkpoint_total_ms": 0.0,
        "save_checkpoint_max_ms": 0.0,
        "load_index_calls": 0,
        "load_index_total_ms": 0.0,
    }

    orig_scan = shelf.scan

    def timed_scan(*args, **kwargs):
        files = args[1] if len(args) > 1 else kwargs.get("files", [])
        n = len(files) if files else 0
        t0 = time.perf_counter()
        try:
            r = orig_scan(*args, **kwargs)
        finally:
            dt = (time.perf_counter() - t0) * 1000.0
            stats["scan_calls"] += 1
            stats["scan_files_in_calls"] += n
            stats["scan_total_ms"] += dt
            stats["scan_max_ms"] = max(stats["scan_max_ms"], dt)
        return r

    shelf.scan = timed_scan

    orig_merge = shelf.merge_partial

    def timed_merge(*args, **kwargs):
        partial = args[1] if len(args) > 1 else kwargs.get("partial", {})
        n = len((partial or {}).get("books") or []) + len((partial or {}).get("documents") or []) + len(
            (partial or {}).get("ignored") or []
        )
        t0 = time.perf_counter()
        try:
            r = orig_merge(*args, **kwargs)
        finally:
            dt = (time.perf_counter() - t0) * 1000.0
            stats["merge_calls"] += 1
            stats["merge_files_in_calls"] += n
            stats["merge_total_ms"] += dt
            stats["merge_max_ms"] = max(stats["merge_max_ms"], dt)
        return r

    shelf.merge_partial = timed_merge

    orig_save = shelf.save_index

    def timed_save(*args, **kwargs):
        idx = args[0] if args else kwargs.get("index")
        n = len((idx or {}).get("books") or [])
        t0 = time.perf_counter()
        try:
            r = orig_save(*args, **kwargs)
        finally:
            dt = (time.perf_counter() - t0) * 1000.0
            stats["save_index_calls"] += 1
            stats["save_index_total_ms"] += dt
            stats["save_index_max_ms"] = max(stats["save_index_max_ms"], dt)
        return r

    shelf.save_index = timed_save

    orig_save_ck = shelf.save_checkpoint

    def timed_save_ck(*args, **kwargs):
        t0 = time.perf_counter()
        try:
            r = orig_save_ck(*args, **kwargs)
        finally:
            dt = (time.perf_counter() - t0) * 1000.0
            stats["save_checkpoint_calls"] += 1
            stats["save_checkpoint_total_ms"] += dt
            stats["save_checkpoint_max_ms"] = max(stats["save_checkpoint_max_ms"], dt)
        return r

    shelf.save_checkpoint = timed_save_ck

    orig_load = shelf.load_index

    def timed_load(*args, **kwargs):
        t0 = time.perf_counter()
        try:
            r = orig_load(*args, **kwargs)
        finally:
            dt = (time.perf_counter() - t0) * 1000.0
            stats["load_index_calls"] += 1
            stats["load_index_total_ms"] += dt
        return r

    shelf.load_index = timed_load

    return stats


def call_endpoint(port, path, body=None):
    url = f"http://127.0.0.1:{port}{path}"
    if body is None:
        req = urllib.request.Request(url, method="GET")
    else:
        req = urllib.request.Request(
            url,
            data=body.encode("utf8"),
            method="POST",
            headers={"Content-Type": "application/json"},
        )
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=600) as r:
            r.read()
        dt = (time.perf_counter() - t0) * 1000.0
        return True, dt
    except Exception as exc:
        dt = (time.perf_counter() - t0) * 1000.0
        return False, dt


def main():
    reset_dirs()
    write_settings()

    n_files = count_files()
    print(f"Library: {LIBRARY}")
    print(f"Books: {n_files}")

    import subprocess

    svc = subprocess.Popen(
        [sys.executable, "-m", "audiobook.library", "--port", str(PORT), "--root", LIBRARY],
        cwd=CHATTERBOX,
        env={**os.environ, "SUMATRA_LIBRARY_CACHE_ROOT": CACHE, "SUMATRA_BENCH_LIBRARY": BENCH},
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )

    stats = instrument_shelf()
    endpoint_stats = {
        "/manifest": {"calls": 0, "total_ms": 0.0, "max_ms": 0.0},
        "/book": {"calls": 0, "total_ms": 0.0, "max_ms": 0.0},
        "/index": {"calls": 0, "total_ms": 0.0, "max_ms": 0.0},
        "/index-preview": {"calls": 0, "total_ms": 0.0, "max_ms": 0.0},
    }

    for i in range(160):
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{PORT}/status", timeout=2) as r:
                if r.status == 200:
                    break
        except Exception:
            pass
        time.sleep(0.25)
    else:
        print("service did not start")
        svc.kill()
        return 1

    print(f"Service ready on port {PORT}")

    manifest = {
        "roots": [LIBRARY],
        "scope": 2,
        "manifest": [
            {"path": "fake1.pdf", "size": 100, "mtime": 0},
            {"path": "fake2.epub", "size": 200, "mtime": 0},
        ],
    }
    manifest_body = json.dumps(manifest)

    print("\n=== Simulating old behaviour: /index-preview per book ===")
    n_books = n_files
    t_total = time.perf_counter()
    for i in range(n_books):
        body = json.dumps(
            {
                "roots": [LIBRARY],
                "scope": 2,
                "files": [{"path": f"book_{i}.pdf", "size": 1000 + i, "mtime": i * 1.0}],
            }
        )
        ok, dt = call_endpoint(PORT, "/index-preview", body)
        endpoint_stats["/index-preview"]["calls"] += 1
        endpoint_stats["/index-preview"]["total_ms"] += dt
        endpoint_stats["/index-preview"]["max_ms"] = max(endpoint_stats["/index-preview"]["max_ms"], dt)
    t_old = time.perf_counter() - t_total

    print(f"Old (per-book /index-preview): {t_old:.2f}s for {n_books} calls")
    print(f"  /index-preview total: {endpoint_stats['/index-preview']['total_ms']:.0f}ms")
    print(f"  shelf.scan total: {stats['scan_total_ms']:.0f}ms ({stats['scan_calls']} calls)")
    print(f"  shelf.merge total: {stats['merge_total_ms']:.0f}ms ({stats['merge_calls']} calls)")
    print(f"  shelf.save_index total: {stats['save_index_total_ms']:.0f}ms ({stats['save_index_calls']} calls)")
    print(f"  shelf.save_checkpoint total: {stats['save_checkpoint_total_ms']:.0f}ms ({stats['save_checkpoint_calls']} calls)")
    print(f"  shelf.load_index total: {stats['load_index_total_ms']:.0f}ms ({stats['load_index_calls']} calls)")

    stats2 = instrument_shelf()
    endpoint_stats2 = {
        "/manifest": {"calls": 0, "total_ms": 0.0, "max_ms": 0.0},
        "/book": {"calls": 0, "total_ms": 0.0, "max_ms": 0.0},
        "/index": {"calls": 0, "total_ms": 0.0, "max_ms": 0.0},
    }

    if os.path.exists(os.path.join(CACHE, "library", "library.json")):
        os.remove(os.path.join(CACHE, "library", "library.json"))
    if os.path.exists(os.path.join(CACHE, "library", "scan_checkpoint.json")):
        os.remove(os.path.join(CACHE, "library", "scan_checkpoint.json"))

    print("\n=== Simulating new behaviour: /book per book + /manifest once ===")
    ok, dt = call_endpoint(PORT, "/manifest", manifest_body)
    endpoint_stats2["/manifest"]["calls"] += 1
    endpoint_stats2["/manifest"]["total_ms"] += dt
    endpoint_stats2["/manifest"]["max_ms"] = max(endpoint_stats2["/manifest"]["max_ms"], dt)
    print(f"/manifest call: {dt:.0f}ms")

    t_total = time.perf_counter()
    for i in range(n_books):
        body = json.dumps(
            {
                "roots": [LIBRARY],
                "scope": 2,
                "book": {"path": f"book_{i}.pdf", "size": 1000 + i, "mtime": i * 1.0},
            }
        )
        ok, dt = call_endpoint(PORT, "/book", body)
        endpoint_stats2["/book"]["calls"] += 1
        endpoint_stats2["/book"]["total_ms"] += dt
        endpoint_stats2["/book"]["max_ms"] = max(endpoint_stats2["/book"]["max_ms"], dt)
    t_new = time.perf_counter() - t_total

    print(f"New (per-book /book): {t_new:.2f}s for {n_books} calls")
    print(f"  /book total: {endpoint_stats2['/book']['total_ms']:.0f}ms")
    print(f"  /manifest total: {endpoint_stats2['/manifest']['total_ms']:.0f}ms")
    print(f"  shelf.scan total: {stats2['scan_total_ms']:.0f}ms ({stats2['scan_calls']} calls)")
    print(f"  shelf.merge total: {stats2['merge_total_ms']:.0f}ms ({stats2['merge_calls']} calls)")
    print(f"  shelf.save_index total: {stats2['save_index_total_ms']:.0f}ms ({stats2['save_index_calls']} calls)")
    print(f"  shelf.save_checkpoint total: {stats2['save_checkpoint_total_ms']:.0f}ms ({stats2['save_checkpoint_calls']} calls)")
    print(f"  shelf.load_index total: {stats2['load_index_total_ms']:.0f}ms ({stats2['load_index_calls']} calls)")

    print(f"\n=== Speedup: {t_old/t_new:.1f}x ===")
    print(f"Old per-book mean: {endpoint_stats['/index-preview']['total_ms']/n_books:.1f}ms")
    print(f"New per-book mean: {endpoint_stats2['/book']['total_ms']/n_books:.1f}ms")

    svc.kill()
    svc.wait()

    return 0


if __name__ == "__main__":
    sys.exit(main())
