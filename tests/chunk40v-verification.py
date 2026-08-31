import html
import hashlib
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile

import fitz
from PIL import Image, ImageDraw, ImageFont

CHATTERBOX, BENCH, WORK, FIXTURES = sys.argv[1:5]
sys.path.insert(0, CHATTERBOX)

from audiobook.library import shelf

failures = []


def check(name, value, detail=""):
    print(("PASS " if value else "FAIL ") + name + (" " + detail if detail else ""))
    if not value:
        failures.append(name)


def run(*args, env=None):
    result = subprocess.run([BENCH, *args], capture_output=True, text=True, env=env, timeout=600)
    fields = {}
    for line in result.stdout.splitlines():
        if line.startswith("OK "):
            for part in line.split():
                if "=" in part:
                    key, value = part.split("=", 1)
                    fields[key] = value
    return result.returncode, fields, result.stdout, result.stderr


def fingerprint(path, reading="", identity_text=""):
    code, data, out, err = run("fingerprint", path, reading, identity_text)
    if code:
        raise RuntimeError(out + err)
    return data


def identity(data):
    parts = data.get("fingerprint", "").split(":")
    return parts[1] if len(parts) == 3 else ""


def make_epub(path, text):
    body = "".join("<p>" + html.escape(line) + "</p>" for line in text.splitlines() if line.strip())
    container = '<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>'
    package = '<?xml version="1.0"?><package version="3.0" xmlns="http://www.idpf.org/2007/opf" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">chunk40v</dc:identifier><dc:title>Equivalent Fixture</dc:title><dc:language>en</dc:language></metadata><manifest><item id="book" href="book.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="book"/></spine></package>'
    page = '<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title></title></head><body>' + body + "</body></html>"
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("mimetype", "application/epub+zip", compress_type=zipfile.ZIP_STORED)
        archive.writestr("META-INF/container.xml", container)
        archive.writestr("OEBPS/content.opf", package)
        archive.writestr("OEBPS/book.xhtml", page)


def make_mobi(path, text):
    body = ("<html><head><title></title></head><body>" + "".join("<p>" + html.escape(line) + "</p>" for line in text.splitlines() if line.strip()) + "</body></html>").encode("utf-8")
    title = b"Equivalent Fixture"
    mobi = bytearray(116)
    mobi[0:4] = b"MOBI"
    struct.pack_into(">IIIII", mobi, 4, 116, 2, 65001, 40, 4)
    for offset in range(24, 64, 4):
        struct.pack_into(">I", mobi, offset, 0xFFFFFFFF)
    struct.pack_into(">IIIIIIIIIIIII", mobi, 64, 2, 132, len(title), 1033, 0, 0, 4, 2, 0, 0, 0, 0, 0)
    record0 = struct.pack(">HHIHHHH", 1, 0, len(body), 1, 4096, 0, 0) + mobi + title
    header = bytearray(78)
    header[0:32] = b"Equivalent Fixture".ljust(32, b"\0")
    header[60:68] = b"BOOKMOBI"
    struct.pack_into(">H", header, 76, 2)
    first = 94
    records = struct.pack(">IB3sIB3s", first, 0, b"\0\0\1", first + len(record0), 0, b"\0\0\2")
    with open(path, "wb") as stream:
        stream.write(header + records + record0 + body)


def entry(path, data, title="Equivalent Fixture", author="Fixture Author"):
    stat = os.stat(path)
    return {"path": path, "folder": os.path.dirname(path), "series_folder": None, "title": title,
            "author": author, "ext": os.path.splitext(path)[1].lower(), "size": stat.st_size,
            "mtime": stat.st_mtime, "pages": int(data.get("pages", 1)), "kind": 0, "volumes": []}


def dedupe(books):
    shelf._identity_cache.clear()
    shelf._hash_cache.clear()
    shelf._attach_analysis(books)
    return shelf._dedupe(books)


def set_metadata(path, title, author):
    doc = fitz.open(path)
    metadata = doc.metadata
    metadata["title"] = title
    metadata["author"] = author
    doc.set_metadata(metadata)
    doc.save(path + ".rewrite.pdf")
    doc.close()
    os.replace(path + ".rewrite.pdf", path)


def make_cover_pair(path_a, path_b, source_a, source_b):
    cover_doc = fitz.open()
    page = cover_doc.new_page(width=1200, height=1600)
    page.insert_text((180, 700), "THE IDENTICAL COVER", fontsize=48)
    cover = page.get_pixmap(matrix=fitz.Matrix(1, 1), alpha=False).tobytes("png")
    cover_doc.close()
    for path, source in ((path_a, source_a), (path_b, source_b)):
        body = fitz.open(source)
        doc = fitz.open()
        page = doc.new_page(width=1200, height=1600)
        page.insert_image(page.rect, stream=cover)
        doc.insert_pdf(body)
        doc.set_metadata({"title": "Same Cover", "author": "Fixture Author"})
        doc.save(path)
        doc.close()
        body.close()


def stored_identity(path):
    stat = os.stat(path)
    shelf._identity_cache.clear()
    return shelf.book_identity(path, stat.st_size, stat.st_mtime)


def make_long_pair(digital_path, image_path):
    width = 1200
    height = 1600
    text = "    ".join(["TEST"] * 12)
    image = Image.new("RGB", (width, height), "white")
    draw = ImageDraw.Draw(image)
    font = ImageFont.truetype(r"C:\Windows\Fonts\arial.ttf", 20)
    for line in range(50):
        draw.text((60, 40 + line * 30), text, fill="black", font=font)
    image_bytes = os.path.join(WORK, "long-page.png")
    image.save(image_bytes)
    digital = fitz.open()
    scanned = fitz.open()
    for page_no in range(64):
        digital_page = digital.new_page(width=width, height=height)
        scanned_page = scanned.new_page(width=width, height=height)
        for line in range(50):
            digital_page.insert_text((60, 58 + line * 30), text, fontsize=20)
        scanned_page.insert_image(scanned_page.rect, filename=image_bytes)
    digital.save(digital_path)
    scanned.save(image_path)
    digital.close()
    scanned.close()


work_real = os.path.realpath(WORK)
temp_real = os.path.realpath(tempfile.gettempdir())
if os.path.commonpath((work_real, temp_real)) != temp_real:
    raise RuntimeError("work directory must be under the operating-system temporary directory")
shutil.rmtree(work_real, ignore_errors=True)
os.makedirs(work_real)
FIXTURES = os.path.join(work_real, "fixtures")
fixture_result = subprocess.run([sys.executable, os.path.join(os.path.dirname(__file__), "chunk40u-fixture.py"), FIXTURES], capture_output=True, text=True)
if fixture_result.returncode:
    raise RuntimeError(fixture_result.stdout + fixture_result.stderr)
pdf = os.path.join(FIXTURES, "fixture_A_digital.pdf")
ocr_pdf = os.path.join(FIXTURES, "fixture_B_image_only.pdf")
reading = os.path.join(WORK, "equivalent.txt")
pdf_data = fingerprint(pdf, reading)
ocr_data = fingerprint(ocr_pdf)
with open(reading, encoding="utf-8") as stream:
    text = stream.read()
epub = os.path.join(WORK, "equivalent.epub")
mobi = os.path.join(WORK, "equivalent.mobi")
make_epub(epub, text)
make_mobi(mobi, text)
epub_data = fingerprint(epub)
mobi_data = fingerprint(mobi)
values = [identity(pdf_data), identity(ocr_data), identity(epub_data), identity(mobi_data)]
check("digital PDF identity succeeds without OCR", bool(values[0]) and pdf_data.get("ocrState") == "0", repr(pdf_data))
check("image-only PDF identity succeeds through OCR", bool(values[1]) and ocr_data.get("ocrState") == "1", repr(ocr_data))
check("digital and image-only PDF identities match", values[0] == values[1], repr(values))
check("EPUB identity matches", values[2] == values[0], repr(values))
check("MOBI identity matches", values[3] == values[0], repr(values))
books = [entry(pdf, pdf_data), entry(ocr_pdf, ocr_data), entry(epub, epub_data), entry(mobi, mobi_data)]
for book, value in zip(books, values):
    book["fingerprint"] = "fp3:" + value + ":fixture"
merged = dedupe(books)
editions = merged[0].get("editions") or [] if len(merged) == 1 else []
check("four formats dedupe to one logical book", len(merged) == 1, str(len(merged)))
check("four-format result has four editions", len(editions) == 4, str(len(editions)))
negative_a = os.path.join(WORK, "same-title-a.pdf")
negative_b = os.path.join(WORK, "same-title-b.pdf")
shutil.copyfile(ocr_pdf, negative_a)
shutil.copyfile(os.path.join(FIXTURES, "fixture_C_unrelated.pdf"), negative_b)
set_metadata(negative_a, "The Exact Same Title", "The Exact Same Author")
set_metadata(negative_b, "The Exact Same Title", "The Exact Same Author")
negative_a_data = fingerprint(negative_a)
negative_b_data = fingerprint(negative_b)
negative_books = [entry(negative_a, negative_a_data, "The Exact Same Title", "The Exact Same Author"),
                  entry(negative_b, negative_b_data, "The Exact Same Title", "The Exact Same Author")]
for book, data in zip(negative_books, (negative_a_data, negative_b_data)):
    book["fingerprint"] = data["fingerprint"]
negative_merged = dedupe(negative_books)
check("same title and author are embedded in both OCR books", all(fitz.open(path).metadata.get("title") == "The Exact Same Title" and fitz.open(path).metadata.get("author") == "The Exact Same Author" for path in (negative_a, negative_b)))
check("same-title same-author OCR identities differ", identity(negative_a_data) != identity(negative_b_data), repr([identity(negative_a_data), identity(negative_b_data)]))
check("same-title same-author OCR books remain separate", len(negative_merged) == 2, str(len(negative_merged)))
cover_a = os.path.join(WORK, "same-cover-a.pdf")
cover_b = os.path.join(WORK, "same-cover-b.pdf")
make_cover_pair(cover_a, cover_b, ocr_pdf, os.path.join(FIXTURES, "fixture_C_unrelated.pdf"))
cover_a_doc = fitz.open(cover_a)
cover_b_doc = fitz.open(cover_b)
cover_a_hash = hashlib.sha256(cover_a_doc[0].get_pixmap(alpha=False).samples).hexdigest()
cover_b_hash = hashlib.sha256(cover_b_doc[0].get_pixmap(alpha=False).samples).hexdigest()
cover_a_doc.close()
cover_b_doc.close()
cover_a_data = fingerprint(cover_a)
cover_b_data = fingerprint(cover_b)
cover_books = [entry(cover_a, cover_a_data, "Same Cover", "Fixture Author"),
               entry(cover_b, cover_b_data, "Same Cover", "Fixture Author")]
for book, data in zip(cover_books, (cover_a_data, cover_b_data)):
    book["fingerprint"] = data["fingerprint"]
cover_merged = dedupe(cover_books)
check("same-cover rendered pixels are equal", cover_a_hash == cover_b_hash, cover_a_hash)
check("same-cover body identities differ", identity(cover_a_data) != identity(cover_b_data), repr([identity(cover_a_data), identity(cover_b_data)]))
check("same-cover different-content books remain separate", len(cover_merged) == 2, str(len(cover_merged)))
scan_a = os.path.join(WORK, "same-scan-digital.pdf")
scan_b = os.path.join(WORK, "same-scan-image.pdf")
shutil.copyfile(pdf, scan_a)
shutil.copyfile(ocr_pdf, scan_b)
code_a, brand_a, out_a, err_a = run("brand", scan_a, os.path.join(WORK, "same-scan-cache-a"))
code_b, brand_b, out_b, err_b = run("brand", scan_b, os.path.join(WORK, "same-scan-cache-b"))
scan_a_identity = stored_identity(scan_a)
scan_b_identity = stored_identity(scan_b)
scan_books = [entry(scan_a, brand_a), entry(scan_b, brand_b)]
scan_books[0]["fingerprint"] = scan_a_identity
scan_books[1]["fingerprint"] = scan_b_identity
scan_merged = dedupe(scan_books)
scan_editions = scan_merged[0].get("editions") or [] if len(scan_merged) == 1 else []
check("same-scan image book OCR runs once", code_b == 0 and brand_b.get("ocrAttempts") == "1" and brand_b.get("ocrSuccesses") == "1", repr(brand_b))
check("same-scan persisted identity refreshes", bool(scan_b_identity) and scan_b_identity == scan_a_identity, repr([scan_a_identity, scan_b_identity]))
check("same-scan dedupe produces one logical book", len(scan_merged) == 1, str(len(scan_merged)))
check("same-scan dedupe keeps two editions", len(scan_editions) == 2, str(len(scan_editions)))
long_digital = os.path.join(WORK, "long-digital.pdf")
long_image = os.path.join(WORK, "long-image.pdf")
make_long_pair(long_digital, long_image)
long_digital_data = fingerprint(long_digital)
long_image_data = fingerprint(long_image)
check("long OCR fixture stops before the last page", int(long_image_data.get("ocrPages", 0)) < int(long_image_data.get("pages", 0)), repr(long_image_data))
check("long OCR fixture reports skipped pages", int(long_image_data.get("ocrSkipped", 0)) > 0, repr(long_image_data))
check("long OCR fixture reaches the identity token limit", int(long_image_data.get("ocrTokens", 0)) == 32768, repr(long_image_data))
check("long digital and OCR identities match", identity(long_digital_data) == identity(long_image_data), repr([identity(long_digital_data), identity(long_image_data)]))
no_text = os.path.join(WORK, "no-text-persistence.pdf")
shutil.copyfile(os.path.join(FIXTURES, "fixture_D_no_text.pdf"), no_text)
no_text_cache_a = os.path.join(WORK, "no-text-cache-a")
no_text_cache_b = os.path.join(WORK, "no-text-cache-b")
no_text_cache_c = os.path.join(WORK, "no-text-appdata-c")
first_code, first_no_text, first_out, first_err = run("brand", no_text, no_text_cache_a)
shutil.rmtree(no_text_cache_a, ignore_errors=True)
second_code, second_no_text, second_out, second_err = run("brand", no_text, no_text_cache_b)
third_code, third_no_text, third_out, third_err = run("brand", no_text, no_text_cache_c)
visible_no_text = dedupe([entry(no_text, first_no_text, "No Text Library Book", "Fixture Author")])
check("no-text first scan attempts OCR once", first_code == 0 and first_no_text.get("ocrAttempts") == "1" and first_no_text.get("ocrNoText") == "1", repr(first_no_text))
check("no-text persists without a fingerprint", first_no_text.get("fingerprint") == "" and first_no_text.get("portable") == "1" and first_no_text.get("portableBranded") == "0" and first_no_text.get("ocrState") == "2", repr(first_no_text))
check("no-text survives deleted local cache", second_code == 0 and second_no_text.get("full") == "0" and second_no_text.get("ocrAttempts") == "0", repr(second_no_text))
check("no-text survives fresh appdata and restart", third_code == 0 and third_no_text.get("full") == "0" and third_no_text.get("ocrAttempts") == "0" and third_no_text.get("ocrState") == "2", repr(third_no_text))
check("no-text book remains visible", len(visible_no_text) == 1, str(len(visible_no_text)))
success = os.path.join(WORK, "success-persistence.pdf")
shutil.copyfile(ocr_pdf, success)
success_code_a, success_a, success_out_a, success_err_a = run("brand", success, os.path.join(WORK, "success-cache-a"))
success_code_b, success_b, success_out_b, success_err_b = run("brand", success, os.path.join(WORK, "success-cache-b"))
success_code_c, success_c, success_out_c, success_err_c = run("brand", success, os.path.join(WORK, "success-appdata-c"))
check("OCR success persists a valid fp3", success_code_a == 0 and success_a.get("ocrAttempts") == "1" and success_a.get("fingerprint", "").startswith("fp3:"), repr(success_a))
check("OCR success survives deleted local cache", success_code_b == 0 and success_b.get("full") == "0" and success_b.get("ocrAttempts") == "0" and success_b.get("fingerprint") == success_a.get("fingerprint"), repr(success_b))
check("OCR success survives fresh appdata and restart", success_code_c == 0 and success_c.get("full") == "0" and success_c.get("ocrAttempts") == "0" and success_c.get("fingerprint") == success_a.get("fingerprint"), repr(success_c))
engine = os.path.join(WORK, "engine-retry.pdf")
shutil.copyfile(ocr_pdf, engine)
missing_env = os.environ.copy()
missing_env["TESSDATA_PREFIX"] = os.path.join(WORK, "missing-tessdata")
engine_code_a, engine_a, engine_out_a, engine_err_a = run("brand", engine, os.path.join(WORK, "engine-cache-a"), env=missing_env)
restored_env = os.environ.copy()
restored_env.pop("TESSDATA_PREFIX", None)
engine_code_b, engine_b, engine_out_b, engine_err_b = run("brand", engine, os.path.join(WORK, "engine-cache-b"), env=restored_env)
check("engine unavailable writes no permanent state", engine_code_a != 0 and engine_a.get("fingerprint") == "" and engine_a.get("portable") == "0" and engine_a.get("ocrNoText") == "0", repr(engine_a))
check("restored tessdata retries OCR", engine_code_b == 0 and engine_b.get("ocrAttempts") == "1" and engine_b.get("ocrSuccesses") == "1" and engine_b.get("fingerprint", "").startswith("fp3:"), repr(engine_b))
if failures:
    raise SystemExit("chunk40v: %d failure(s): %s" % (len(failures), ", ".join(failures)))
print("chunk40v: ALL CHECKS PASSED")
