import json
import os
import shutil
import subprocess
import sys

ROOT, CHATTERBOX, BENCH, CACHE, APPDATA, PORT, APPROVED = sys.argv[1:8]
APPROVED = os.path.realpath(APPROVED)
for destination in (ROOT, CACHE, APPDATA):
    if os.path.commonpath((APPROVED, os.path.realpath(destination))) != APPROVED:
        raise RuntimeError(f"test destination is outside the fixture root: {destination}")
sys.path.insert(0, CHATTERBOX)
os.environ["SUMATRA_LIBRARY_CACHE_ROOT"] = CACHE

import fitz
from audiobook import pdfbook
from audiobook.library import desk, shelf
from audiobook.library.server import LibraryState

pdfbook.CACHE_ROOT = CACHE


def make_pdf(path, text, title, author, pages=2):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    doc = fitz.open()
    for page_no in range(pages):
        page = doc.new_page()
        if text:
            page.insert_text((72, 80), f"Chapter {page_no + 1}")
            page.insert_text((72, 110), f"{text} page {page_no + 1}")
            page.insert_text((72, 140), f"More substantive body text volume {page_no + 1} for fingerprint stability")
    doc.set_metadata({"title": title, "author": author})
    doc.save(path)
    doc.close()


def brand(path):
    run = subprocess.run([BENCH, "brand", path, os.path.join(CACHE, "fingerprints")], capture_output=True, text=True)
    if run.returncode:
        raise RuntimeError((run.stdout or run.stderr).strip())


def scan_item(path, kind="book"):
    stat = os.stat(path)
    item = {"path": path, "size": stat.st_size, "mtime": stat.st_mtime, "pages": 2,
            "ink": 100 if kind == "book" else 0, "art": 0, "title": os.path.splitext(os.path.basename(path))[0],
            "author": "Fixture Author", "toc": 1 if kind == "book" else 0, "kind": kind,
            "sample": "deterministic substantive sample" if kind == "book" else ""}
    run = subprocess.run([BENCH, "readrec", path], capture_output=True, text=True)
    marker = "fingerprint="
    if marker in run.stdout:
        tail = run.stdout.split(marker, 1)[1].strip().split()
        fingerprint = tail[0] if tail else ""
        if fingerprint:
            item["fingerprint"] = fingerprint
    return item


shutil.rmtree(ROOT, ignore_errors=True)
shutil.rmtree(CACHE, ignore_errors=True)
shutil.rmtree(APPDATA, ignore_errors=True)
os.makedirs(ROOT)
os.makedirs(CACHE)
os.makedirs(APPDATA)

long_series = os.path.join(ROOT, "Container Animorphs", "Animorphs")
long_books = []
NUM_LONG = 60
for idx in range(NUM_LONG):
    n = idx + 1
    path = os.path.join(long_series, f"Book {n:03d}.pdf")
    make_pdf(path, f"Animorphs substantive content volume {n}", f"Animorphs {n}", "K.A. Applegate")
    brand(path)
    long_books.append(path)

other_series = os.path.join(ROOT, "Container Other", "Goosebumps")
other_books = []
for idx in range(8):
    n = idx + 1
    path = os.path.join(other_series, f"Goosebumps {n:02d}.pdf")
    make_pdf(path, f"Goosebumps substantive content volume {n}", f"Goosebumps {n}", "R.L. Stine")
    brand(path)
    other_books.append(path)

all_books = long_books + other_books
files = [scan_item(path) for path in all_books]
state = LibraryState(roots=[ROOT])
ok, message = state.native_index({"roots": [ROOT], "files": files, "scope": shelf.SCAN_SCOPE})
if not ok:
    raise RuntimeError(message)

python = os.path.join(CHATTERBOX, ".venv-amd", "Scripts", "python.exe")
with open(os.path.join(APPDATA, "SumatraPDF-settings.txt"), "w", encoding="utf-8") as out:
    out.write("Audiobook [\n")
    out.write(f"\tPythonExe = {python}\n")
    out.write(f"\tChatterboxDir = {CHATTERBOX}\n")
    out.write(f"\tLibraryPort = {PORT}\n")
    out.write(f"\tLibraryRoots = {ROOT}\n")
    out.write("\tLibraryHome = true\n")
    out.write("]\n")
    out.write("HomePage [\n")
    out.write("\tHomePageViewMode = list\n")
    out.write("]\n")

manifest = {"root": ROOT, "cache": CACHE, "appdata": APPDATA, "port": int(PORT),
            "long_books": long_books, "other_books": other_books,
            "long_series": "Animorphs", "other_series": "Goosebumps"}
with open(os.path.join(ROOT, "manifest.json"), "w", encoding="utf-8") as out:
    json.dump(manifest, out, indent=2)
print(json.dumps(manifest))
