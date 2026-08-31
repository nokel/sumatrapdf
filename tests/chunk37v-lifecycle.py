import hashlib
import os
import re
import shutil
import subprocess
import sys

CHATTERBOX, BENCH, WORK, BRANDED_PDF, BRANDED_EPUB, BRANDED_MOBI = sys.argv[1:7]
sys.path.insert(0, CHATTERBOX)

import fitz
from audiobook import pdfbook
from audiobook.library import desk, shelf
from audiobook.library.server import LibraryState

failures = []


def check(name, value, detail=""):
    print(("PASS " if value else "FAIL ") + name + (" " + detail if detail else ""))
    if not value:
        failures.append(name)


def digest(path):
    with open(path, "rb") as stream:
        return hashlib.sha256(stream.read()).hexdigest()


def counters(line):
    return dict(part.split("=", 1) for part in line.split() if "=" in part)


def bench(*args):
    run = subprocess.run([BENCH, *args], text=True, capture_output=True)
    lines = (run.stdout or run.stderr).strip().splitlines()
    return run.returncode, counters(lines[-1] if lines else "")


def make_pdf(path, text=None, title=None, author=None):
    doc = fitz.open()
    for page_no in range(2):
        page = doc.new_page()
        if text:
            page.insert_textbox(fitz.Rect(72, 72, 540, 720), " ".join([f"{text} page {page_no + 1}"] * 20))
        else:
            page.draw_rect(fitz.Rect(72, 72, 300, 300), color=(page_no, 0, 1), fill=(0.2, 0.4, 0.6))
    if title or author:
        doc.set_metadata({"title": title or "", "author": author or ""})
    doc.save(path)
    doc.close()


def item(path, kind="book"):
    stat = os.stat(path)
    return {"path": path, "size": stat.st_size, "mtime": stat.st_mtime,
            "pages": 2, "ink": 100 if kind == "book" else 0, "art": 0,
            "title": os.path.splitext(os.path.basename(path))[0], "author": "",
            "toc": 1 if kind == "book" else 0, "kind": kind,
            "sample": "substantive sample" if kind == "book" else ""}


def reset_case(name):
    root = os.path.join(WORK, name)
    shutil.rmtree(root, ignore_errors=True)
    os.makedirs(root)
    pdfbook.CACHE_ROOT = os.path.join(root, "cache")
    shelf._identity_cache.clear()
    shelf._hash_cache.clear()
    return root, LibraryState(roots=[root])


def classify_and_brand(state, root, paths):
    payload = {"roots": [root], "files": paths}
    ok, message = state.native_index(payload)
    check("classification request completed", ok, message)
    queued = list(state.brandable)
    check("pending catalogue is not published before branding", not queued or state.pending_index is not None)
    calls = []
    for path in queued:
        calls.append((path, *bench("brand", path, os.path.join(root, "fingerprints"))))
    if queued:
        by_path = {path: data.get("fingerprint") for path, code, data in calls}
        ok, message = state.finish_branding([{"path": path, "fingerprint": by_path[path]} for path in queued])
        check("branding completion published catalogue", ok, message)
    return queued, calls, state.index


os.makedirs(WORK, exist_ok=True)

root, state = reset_case("stored")
for label, source in (("PDF", BRANDED_PDF), ("EPUB", BRANDED_EPUB), ("MOBI", BRANDED_MOBI)):
    target = os.path.join(root, "stored-" + label.lower() + os.path.splitext(source)[1])
    shutil.copyfile(source, target)
    if os.path.exists(source + ".sumatra"):
        shutil.copyfile(source + ".sumatra", target + ".sumatra")
    before = digest(target)
    code, data = bench("readrec", target, os.path.join(root, "fresh-" + label))
    check(f"stored {label} accepted", code == 0 and data.get("accepted") == "1")
    check(f"stored {label} zero recomputation", data.get("full") == "0" and data.get("shape") == "0")
    check(f"stored {label} unchanged", before == digest(target))

root, state = reset_case("boundary")
text_book = os.path.join(root, "new-text.pdf")
document = os.path.join(root, "manual.pdf")
ignored = os.path.join(root, "ignored.pdf")
no_text_a = os.path.join(root, "images-a.pdf")
no_text_b = os.path.join(root, "images-b.pdf")
make_pdf(text_book, "A newly admitted substantive book")
make_pdf(document, "A technical manual")
make_pdf(ignored, "An ignored supported candidate")
make_pdf(no_text_a)
make_pdf(no_text_b)
desk.tell([ignored], desk.IGNORED)
before = {path: digest(path) for path in (text_book, document, ignored, no_text_a, no_text_b)}
queued, calls, index = classify_and_brand(
    state, root,
    [item(text_book), item(document, "document"), item(ignored), item(no_text_a), item(no_text_b)])
books = index["books"]
book_paths = {book["path"] for book in books}
check("only admitted books queued", set(queued) == {text_book, no_text_a, no_text_b})
check("document classified outside books", document in {row["path"] for row in index["documents"]})
check("ignored candidate classified outside books", ignored in {row["path"] for row in index["ignored"]})
call_data = {path: data for path, code, data in calls}
check("new text book computed exactly once", call_data[text_book].get("full") == "1")
check("new text brand persisted", bool(shelf.book_identity(text_book, os.stat(text_book).st_size,
                                                           os.stat(text_book).st_mtime)))
check("document never changed", before[document] == digest(document))
check("ignored candidate never changed", before[ignored] == digest(ignored))
check("no-text books stay visible", no_text_a in book_paths and no_text_b in book_paths)
check("no-text books stay unbranded", not shelf.book_identity(no_text_a) and not shelf.book_identity(no_text_b))
check("no-text books stay separate", len([b for b in books if b["path"] in (no_text_a, no_text_b)]) == 2)
no_text_branded = all(call_data[path].get("ocrState") in ("2", "1") and call_data[path].get("portable") == "1" for path in (no_text_a, no_text_b))
no_text_files_unchanged = before[no_text_a] == digest(no_text_a) and before[no_text_b] == digest(no_text_b)
check("no-text books are deferred for async OCR", no_text_branded or no_text_files_unchanged,
      f"branded={no_text_branded} unchanged={no_text_files_unchanged}")
check("no empty identity persisted", all("d41d8cd98f00b204e9800998ecf8427e" not in str(b.get("identity")) for b in books))

queued2, calls2, index2 = classify_and_brand(
    state, root,
    [item(text_book), item(document, "document"), item(ignored), item(no_text_a), item(no_text_b)])
check("known rescan queues no books", queued2 == [])
check("known rescan performs no branding calls", calls2 == [])

root, state = reset_case("duplicates")
dup_a = os.path.join(root, "alpha.pdf")
dup_b = os.path.join(root, "different-name.pdf")
other_a = os.path.join(root, "same-title-a.pdf")
other_b = os.path.join(root, "same-title-b.pdf")
make_pdf(dup_a, "Identical substantive duplicate content", "First Name", "First Author")
make_pdf(dup_b, "Identical substantive duplicate content", "Other Name", "Other Author")
make_pdf(other_a, "First different substantive work", "Shared Title", "Shared Author")
make_pdf(other_b, "Second different substantive work", "Shared Title", "Shared Author")
queued_a, calls_a, index_a = classify_and_brand(state, root, [item(dup_a)])
check("positive duplicate A is already branded", queued_a == [dup_a] and calls_a[0][2].get("full") == "1")
queued, calls, index = classify_and_brand(state, root, [item(p) for p in (dup_a, dup_b, other_a, other_b)])
check("positive duplicate B alone enters branding", dup_a not in queued and dup_b in queued)
call_data = {path: data for path, code, data in calls}
check("positive duplicate B computed once", call_data[dup_b].get("full") == "1")
ident_a = shelf.book_identity(dup_a)
ident_b = shelf.book_identity(dup_b)
check("positive duplicate identities equal", bool(ident_a) and ident_a == ident_b)
duplicate_rows = [b for b in index["books"] if b.get("identity") == ident_a]
check("positive duplicate merged with two editions",
      len(duplicate_rows) == 1 and len(duplicate_rows[0].get("editions") or []) == 2)
other_ident_a = shelf.book_identity(other_a)
other_ident_b = shelf.book_identity(other_b)
check("same-title different content identities differ", bool(other_ident_a) and other_ident_a != other_ident_b)
check("same-title different content stays separate",
      len([b for b in index["books"] if b.get("identity") in (other_ident_a, other_ident_b)]) == 2)

print()
if failures:
    print(f"chunk37v-lifecycle: {len(failures)} FAILURE(S): {', '.join(failures)}")
    sys.exit(1)
print("chunk37v-lifecycle: ALL CHECKS PASSED")
