import json
import os
import shutil
import subprocess
import sys
import zipfile

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


def make_pdf(path, text, title, author, pages=30):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    doc = fitz.open()
    for page_no in range(pages):
        page = doc.new_page()
        if text:
            page.insert_text((72, 80), f"Chapter {page_no + 1}")
            page.insert_text((72, 110), f"{text} page {page_no + 1}")
            page.insert_text((72, 140), f"More substantive body text volume {page_no + 1} for fingerprint stability")
        else:
            page.draw_rect(fitz.Rect(72, 72, 300, 300), color=(0.1 * (page_no % 10), 0.5, 0.7), fill=(0.3, 0.5, 0.7))
    doc.set_metadata({"title": title, "author": author})
    doc.save(path)
    doc.close()


def make_epub(path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with zipfile.ZipFile(path, "w") as out:
        out.writestr("mimetype", "application/epub+zip", compress_type=zipfile.ZIP_STORED)
        out.writestr("META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
        manifest = "".join(f'<item id="c{i}" href="chapter{i}.xhtml" media-type="application/xhtml+xml"/>' for i in range(1, 31))
        spine = "".join(f'<itemref idref="c{i}"/>' for i in range(1, 31))
        opf = f'''<?xml version="1.0"?><package version="2.0" xmlns="http://www.idpf.org/2007/opf" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Fixture EPUB</dc:title><dc:identifier id="id">fixture</dc:identifier><dc:language>en</dc:language></metadata><manifest>{manifest}</manifest><spine>{spine}</spine></package>'''
        out.writestr("OEBPS/content.opf", opf)
        for i in range(1, 31):
            body = " ".join(f"Deterministic substantive chapter {i} paragraph {p} for stable book classification and branding." for p in range(1, 21))
            out.writestr(f"OEBPS/chapter{i}.xhtml", f"<html xmlns='http://www.w3.org/1999/xhtml'><body><h1>Chapter {i}</h1><p>{body}</p></body></html>")


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

series_a = os.path.join(ROOT, "Container Example", "Series Alpha")
series_b = os.path.join(ROOT, "Container Other", "Series Beta")
books = []
for idx in range(3):
    path = os.path.join(series_a, f"Alpha {idx + 1}.pdf")
    make_pdf(path, f"Alpha substantive content volume {idx + 1}", f"Alpha {idx + 1}", "Fixture Author")
    brand(path)
    books.append(path)
for idx in range(3):
    path = os.path.join(series_b, f"Beta {idx + 1}.pdf")
    make_pdf(path, f"Beta substantive content volume {idx + 1}", f"Beta {idx + 1}", "Fixture Author")
    brand(path)
    books.append(path)

no_text_a = os.path.join(ROOT, "Books", "Image A.pdf")
no_text_b = os.path.join(ROOT, "Books", "Image B.pdf")
document = os.path.join(ROOT, "Documents", "Manual.pdf")
ignored = os.path.join(ROOT, "Documents", "Ignored.pdf")
new_text = os.path.join(APPROVED, "templates", "chunk37w-new-text-template.pdf")
if os.path.exists(new_text):
    os.remove(new_text)
make_pdf(no_text_a, "", "Image A", "")
make_pdf(no_text_b, "", "Image B", "")
make_pdf(document, "Technical reference document", "Manual", "", pages=2)
make_pdf(ignored, "Ignored supported content", "Ignored", "", pages=2)
make_pdf(new_text, "Newly admitted substantive fixture text", "New Arrival", "Fixture Author")
desk.tell([ignored], desk.IGNORED)

epub = os.path.join(ROOT, "Books", "Fixture.epub")
make_epub(epub)
brand(epub)

mobi_source = os.environ.get("SUMATRA_TEST_MOBI_SOURCE", "")
if not os.path.exists(mobi_source):
    mobi_source = os.path.expanduser("~/Documents/ebooks/manga_novels/01 The Hitchhiker's Guide to the Galaxy - Douglas Adams.mobi")
if not os.path.exists(mobi_source):
    raise RuntimeError("SUMATRA_TEST_MOBI_SOURCE does not name a readable MOBI file")
mobi = os.path.join(ROOT, "Books", "Fixture.mobi")
shutil.copyfile(mobi_source, mobi)
brand(mobi)

files = [scan_item(path) for path in books + [no_text_a, no_text_b, epub, mobi]]
files += [scan_item(document, "document"), scan_item(ignored)]
state = LibraryState(roots=[ROOT])
ok, message = state.native_index({"roots": [ROOT], "files": files, "scope": shelf.SCAN_SCOPE})
if not ok:
    raise RuntimeError(message)
if state.brandable:
    brands = []
    for path in state.brandable:
        subprocess.run([BENCH, "brand", path, os.path.join(CACHE, "fingerprints")], capture_output=True)
        brands.append({"path": path, "fingerprint": scan_item(path).get("fingerprint")})
    ok, message = state.finish_branding(brands)
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
            "books": books, "series_a": "Series Alpha", "series_b": "Series Beta",
            "no_text": [no_text_a, no_text_b], "document": document, "ignored": ignored,
            "epub": epub, "mobi": mobi, "new_text": new_text}
with open(os.path.join(ROOT, "manifest.json"), "w", encoding="utf-8") as out:
    json.dump(manifest, out, indent=2)
print(json.dumps(manifest))
