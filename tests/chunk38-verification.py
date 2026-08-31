import glob
import hashlib
import html
import json
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile

CHATTERBOX, BENCH, SOURCE_ROOT = sys.argv[1:4]
sys.path.insert(0, CHATTERBOX)

import fitz
from audiobook.library.server import LibraryState

BASE = os.path.realpath(os.path.join(tempfile.gettempdir(), "SumatraPDF-tests", "chunk38"))
WORK = os.path.join(BASE, "work")
CACHE = os.path.join(BASE, "cache")
RESULTS = os.path.join(BASE, "results.json")
failures = []
results = {"inventory": [], "pairs": {}, "mutations": {}, "service": {}, "migration": {}}


def check(name, value, detail=""):
    print(("PASS " if value else "FAIL ") + name + (" " + detail if detail else ""))
    if not value:
        failures.append(name)


def approved(path):
    return os.path.commonpath((BASE, os.path.realpath(path))) == BASE


def reset(path):
    if not approved(path):
        raise RuntimeError("test destination is outside the approved temporary root")
    shutil.rmtree(path, ignore_errors=True)
    os.makedirs(path)


def fields(line):
    return dict(part.split("=", 1) for part in line.split() if "=" in part)


def bench(*args):
    run = subprocess.run([BENCH, *args], capture_output=True, text=True)
    lines = (run.stdout or run.stderr).strip().splitlines()
    data = {}
    for line in lines:
        data.update(fields(line))
    if run.returncode:
        raise RuntimeError((run.stdout + run.stderr).strip())
    return data


def identity(data):
    parts = data.get("fingerprint", "").split(":")
    return parts[1] if len(parts) == 3 else ""


def volume(path):
    name = os.path.basename(path)
    return int(name[:2]) if name[:2].isdigit() else 4


def scan_item(path, data, title=None):
    stat = os.stat(path)
    return {"path": path, "size": stat.st_size, "mtime": stat.st_mtime,
            "pages": int(data.get("pages", 0)), "ink": 100, "art": 0,
            "title": title or os.path.splitext(os.path.basename(path))[0], "author": "Douglas Adams",
            "toc": 1, "kind": "book", "sample": "substantive text", "fingerprint": data["fingerprint"]}


def make_epub(path, reading_path):
    text = open(reading_path, encoding="utf-8").read()
    body = "\n".join("<p>" + html.escape(line) + "</p>" for line in text.splitlines() if line.strip())
    container = '<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>'
    package = '<?xml version="1.0"?><package version="3.0" xmlns="http://www.idpf.org/2007/opf" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">chunk38</dc:identifier><dc:title>Temporary Test Copy</dc:title><dc:language>en</dc:language></metadata><manifest><item id="book" href="book.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="book"/></spine></package>'
    page = '<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>Temporary Test Copy</title></head><body>' + body + '</body></html>'
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("mimetype", "application/epub+zip", compress_type=zipfile.ZIP_STORED)
        archive.writestr("META-INF/container.xml", container)
        archive.writestr("OEBPS/content.opf", package)
        archive.writestr("OEBPS/book.xhtml", page)


reset(BASE)
reset(WORK)
os.makedirs(CACHE)
check("fixture root is under the operating-system temporary directory", approved(WORK), WORK)
blocked = False
try:
    reset(os.path.join(SOURCE_ROOT, "chunk38-forbidden"))
except RuntimeError:
    blocked = True
check("personal-root test write is blocked", blocked)

patterns = ("*Hitchhiker*.pdf", "*Hitchhiker*.mobi", "*Restaurant*.pdf", "*Restaurant*.mobi",
            "*Life, the Universe*.pdf", "*Life, the Universe*.mobi", "*So Long*.pdf", "*So Long*.mobi",
            "*Mostly Harmless*.pdf", "*Mostly Harmless*.mobi", "*Another Thing*.pdf", "*Another Thing*.mobi")
paths = []
for pattern in patterns:
    paths.extend(glob.glob(os.path.join(SOURCE_ROOT, "**", pattern), recursive=True))
paths = sorted(set(os.path.realpath(path) for path in paths if not path.lower().endswith(".sumatra")))
check("real Hitchhiker files found", len(paths) >= 17, str(len(paths)))

diagnostics = {}
for path in paths:
    data = bench("fingerprint", path)
    diagnostics[path] = data
    fp = data["fingerprint"].split(":")
    stored = None
    if path.lower().endswith(".pdf"):
        try:
            stored = bench("rawpdf", path).get("fingerprint")
        except RuntimeError:
            stored = None
    results["inventory"].append({"path": path, "format": os.path.splitext(path)[1].lower(),
                                 "size": os.path.getsize(path), "mtime": os.path.getmtime(path),
                                 "stored": stored, "current": data["fingerprint"], "identity": fp[1],
                                 "shape": fp[2], "pages": int(data["pages"]),
                                 "readingLength": int(data["readingLength"]),
                                 "identityLength": int(data["identityLength"]),
                                 "runningLines": int(data["runningLines"]),
                                 "imageOnly": data["imageOnly"] == "1"})

for vol in range(1, 7):
    group = [path for path in paths if volume(path) == vol]
    ids = {identity(diagnostics[path]) for path in group}
    results["pairs"][str(vol)] = {"files": group, "identities": sorted(ids)}
    check("volume %d equivalent files share one identity" % vol, len(ids) == 1, repr(sorted(ids)))

volume_ids = [results["pairs"][str(vol)]["identities"][0] for vol in range(1, 7)]
check("different Hitchhiker volumes stay separate", len(set(volume_ids)) == 6, repr(volume_ids))

source_pdf = next(path for path in paths if volume(path) == 1 and path.lower().endswith(".pdf") and os.path.dirname(path) == os.path.realpath(SOURCE_ROOT))
source_mobi = next(path for path in paths if volume(path) == 1 and path.lower().endswith(".mobi"))
migration_pdf = os.path.join(WORK, "migration.pdf")
shutil.copyfile(source_pdf, migration_pdf)
source_parts = diagnostics[source_pdf]["fingerprint"].split(":")
bench("obsolete", migration_pdf, "fp2:" + diagnostics[source_pdf]["readingMd5"] + ":" + source_parts[2])
before_raw = bench("rawpdf", migration_pdf)
before_meta = hashlib.sha256(open(migration_pdf, "rb").read()).hexdigest()
migrated = bench("migrate", migration_pdf, CACHE)
after_raw = bench("rawpdf", migration_pdf)
again = bench("migrate", migration_pdf, os.path.join(BASE, "fresh-cache"))
results["migration"] = {"before": before_raw, "after": after_raw, "first": migrated, "second": again,
                        "bytesChanged": before_meta != hashlib.sha256(open(migration_pdf, "rb").read()).hexdigest()}
check("obsolete fp2 is detected", before_raw.get("fingerprint", "").startswith("fp2:"))
check("migration writes fp3 once", migrated.get("fingerprint", "").startswith("fp3:") and migrated.get("full") == "1")
check("migration preserves shelf state", before_raw.get("hasShelf") == after_raw.get("hasShelf") == "1")
check("migration preserves cover state", before_raw.get("hasCover") == after_raw.get("hasCover") == "1")
check("migration preserves read state", before_raw.get("hasStats") == after_raw.get("hasStats") and before_raw.get("percentRead") == after_raw.get("percentRead") and before_raw.get("openCount") == after_raw.get("openCount"))
check("migrated brand has zero repeat work", again.get("full") == "0" and again.get("shape") == "0")

original = identity(migrated)
filename_variant = os.path.join(WORK, "different filename.pdf")
path_dir = os.path.join(WORK, "different-path")
os.makedirs(path_dir)
path_variant = os.path.join(path_dir, "book.pdf")
shutil.copyfile(migration_pdf, filename_variant)
shutil.copyfile(migration_pdf, path_variant)
filename_identity = identity(bench("fingerprint", filename_variant))
path_identity = identity(bench("fingerprint", path_variant))
stable = bench("stability", migration_pdf)
metadata_identity = identity(bench("fingerprint", migration_pdf))
cover = bench("cover", migration_pdf)
cover_identity = identity(bench("fingerprint", migration_pdf))
sidecar_copy = os.path.join(WORK, "sidecar-copy.mobi")
shutil.copyfile(source_mobi, sidecar_copy)
if os.path.exists(source_mobi + ".sumatra"):
    shutil.copyfile(source_mobi + ".sumatra", sidecar_copy + ".sumatra")
sidecar_migrated = bench("migrate", sidecar_copy, os.path.join(BASE, "mobi-cache"))
sidecar_identity = identity(bench("fingerprint", sidecar_copy))
results["mutations"] = {"original": original, "filename": filename_identity, "path": path_identity,
                        "metadata": metadata_identity, "read": metadata_identity, "cover": cover_identity,
                        "sidecar": sidecar_identity, "containerRewrite": metadata_identity}
check("filename mutation is stable", filename_identity == original)
check("path mutation is stable", path_identity == original)
check("title author Series and read mutations are stable", stable.get("title") == stable.get("author") == stable.get("series") == stable.get("read") == "1" and metadata_identity == original)
check("cover mutation is stable", cover.get("sameFingerprint") == "1" and cover_identity == original)
check("BookBlob and sidecar write is stable", identity(sidecar_migrated) == original and sidecar_identity == original)
check("container rewrite is stable", results["migration"]["bytesChanged"] and metadata_identity == original)

reading_path = os.path.join(WORK, "real-reading.txt")
bench("fingerprint", source_pdf, reading_path)
epub_path = os.path.join(WORK, "equivalent.epub")
make_epub(epub_path, reading_path)
epub_data = bench("fingerprint", epub_path)
check("PDF EPUB MOBI equivalent content shares one identity", identity(epub_data) == original == identity(diagnostics[source_mobi]))
results["crossFormat"] = {"pdf": original, "epub": identity(epub_data), "mobi": identity(diagnostics[source_mobi])}

other_a = os.path.join(WORK, "same-title-a.pdf")
other_b = os.path.join(WORK, "same-title-b.pdf")
shutil.copy2(source_pdf, other_a)
shutil.copy2(next(path for path in paths if volume(path) == 2 and path.lower().endswith(".pdf")), other_b)
for path in (other_a, other_b):
    bench("migrate", path, os.path.join(BASE, "negative-cache"))
    bench("stamp", path, "The Hitchhiker's Guide to the Galaxy", "Douglas Adams")
negative = [bench("fingerprint", other_a), bench("fingerprint", other_b)]
check("same-title different-content identities differ", identity(negative[0]) != identity(negative[1]))
negative_ids = {identity(data) for data in negative}

service_paths = paths
service_data = diagnostics
state = LibraryState(roots=[SOURCE_ROOT])
ok, message = state.native_index({"roots": [SOURCE_ROOT], "files": [scan_item(path, service_data[path], "The Hitchhiker's Guide to the Galaxy" if identity(service_data[path]) in negative_ids else None) for path in service_paths]})
check("native service index completes", ok, message)
books = state.index.get("books") or []
logical = [book for book in books if book.get("identity") in set(volume_ids)]
volume_one = [book for book in logical if book.get("identity") == original]
check("six real volumes become six logical books", len(logical) == 6, str(len(logical)))
check("real volume one becomes one logical row", len(volume_one) == 1, str(len(volume_one)))
expected_editions = len(results["pairs"]["1"]["files"])
check("real volume one keeps every physical edition", len(volume_one[0].get("editions") or []) == expected_editions, "%d/%d" % (len(volume_one[0].get("editions") or []), expected_editions))
check("same-title negative remains two logical books", len([book for book in books if book.get("identity") in negative_ids]) == 2)
results["service"] = {"physical": len(paths), "logicalVolumes": len(logical), "volumeOneRows": len(volume_one),
                      "volumeOneEditions": len(volume_one[0].get("editions") or []), "negativeRows": len([book for book in books if book.get("identity") in negative_ids])}

with open(RESULTS, "w", encoding="utf-8") as stream:
    json.dump(results, stream, indent=2)
print("RESULTS " + RESULTS)
if failures:
    raise SystemExit("chunk38: %d failure(s): %s" % (len(failures), ", ".join(failures)))
print("chunk38: ALL CHECKS PASSED")
