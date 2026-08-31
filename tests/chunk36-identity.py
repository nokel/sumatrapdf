# chunk 36: a persisted fingerprint is the book's identity, for good.
#
# Once discovery has branded a book, nothing may recompute or replace that
# brand: not a fresh appdata, not a metadata write, not a new size or mtime,
# and not a different container. Two books are the same logical book only
# when their fingerprints are equal; title, author, filename and volume are
# no longer allowed to merge anything.
#
#   py -3.13 tests/chunk36-identity.py <chatterbox-dir> <bench_library.exe> <work-dir> <book-a.pdf> <book-b.pdf> <book.epub> <book.mobi>

import os
import shutil
import subprocess
import sys

CHATTERBOX, BENCH, WORK, BOOK_A, BOOK_B, BOOK_EPUB, BOOK_MOBI = sys.argv[1:8]
sys.path.insert(0, CHATTERBOX)

from audiobook.library import shelf

failures = []


def say(s):
    print(s)


def check(name, ok, detail=""):
    say(("PASS " if ok else "FAIL ") + name + (" " + detail if detail else ""))
    if not ok:
        failures.append(name)


def run(*args):
    out = subprocess.run([BENCH] + list(args), capture_output=True, text=True)
    line = (out.stdout or "").strip().splitlines()
    return line[-1] if line else (out.stderr or "").strip()


def counters(line):
    d = {}
    for kv in line.split():
        if "=" in kv:
            k, v = kv.split("=", 1)
            d[k] = v
    return d


def fresh_cache(name):
    d = os.path.join(WORK, name)
    if os.path.isdir(d):
        shutil.rmtree(d)
    os.makedirs(d)
    return d


def copy(src, name):
    dst = os.path.join(WORK, name)
    shutil.copyfile(src, dst)
    return dst


def stamp(path, title, author, stats=False):
    args = ["stamp", path, title, author] + (["stats"] if stats else [])
    line = run(*args)
    if "wrote=1" not in line:
        raise SystemExit("stamp failed for %s: %s" % (path, line))
    return line


def entry(path, title, author, volumes=()):
    st = os.stat(path)
    return {
        "path": path,
        "folder": os.path.dirname(path),
        "series_folder": None,
        "title": title,
        "author": author,
        "ext": os.path.splitext(path)[1].lower(),
        "size": st.st_size,
        "mtime": st.st_mtime,
        "pages": 99,
        "kind": 0,
        "volumes": list(volumes),
    }


def identity_of(path):
    st = os.stat(path)
    shelf._identity_cache.clear()
    return shelf.book_identity(path, st.st_size, st.st_mtime)


def dedupe(entries):
    shelf._identity_cache.clear()
    shelf._hash_cache.clear()
    shelf._attach_analysis(entries)
    return shelf._dedupe(entries)


def make_plain_pdf(path, lines):
    import fitz
    doc = fitz.open()
    for n, body in enumerate(lines):
        page = doc.new_page()
        page.insert_text((72, 100), body)
        page.insert_text((72, 140), "page %d" % (n + 1))
    doc.save(path)
    doc.close()


os.makedirs(WORK, exist_ok=True)
for f in os.listdir(WORK):
    p = os.path.join(WORK, f)
    shutil.rmtree(p) if os.path.isdir(p) else os.remove(p)

# ---- Test B: a branded non-PDF book, read with an empty cache -------
say("")
say("== Test B: branded non-PDF, fresh (empty) fingerprint cache ==")
def strip_zip_record(src, dst):
    import zipfile
    with zipfile.ZipFile(src) as zin:
        infos = [i for i in zin.infolist() if i.filename != "META-INF/sumatra.book"]
        with zipfile.ZipFile(dst, "w") as zout:
            for i in infos:
                zout.writestr(i, zin.read(i.filename),
                              zipfile.ZIP_STORED if i.filename == "mimetype"
                              else zipfile.ZIP_DEFLATED)
    return dst


for label, src, name in (("EPUB", BOOK_EPUB, "b.epub"), ("MOBI", BOOK_MOBI, "b.mobi")):
    # The library copy already carries a record; discovery is only
    # interesting on a book that has never been identified.
    book = (strip_zip_record(src, os.path.join(WORK, name)) if label == "EPUB"
            else copy(src, name))
    brand = run("brand", book, fresh_cache("cache-b-brand-" + label))
    say("  %-5s discovery : %s" % (label, brand))
    reread = run("readrec", book, fresh_cache("cache-b-read-" + label))
    say("  %-5s reread    : %s" % (label, reread))
    b, r = counters(brand), counters(reread)
    check("B %s is branded by discovery" % label,
          b.get("result") == "1" and b.get("branded") == "1")
    check("B %s discovery computed the fingerprint once" % label,
          b.get("full") == "1", "-> full=%s" % b.get("full"))
    check("B %s reuses the persisted fingerprint" % label,
          r.get("accepted") == "1" and r.get("fingerprint") == b.get("fingerprint"))
    check("B %s recomputes nothing on a fresh cache" % label,
          r.get("full") == "0" and r.get("shape") == "0",
          "-> full=%s shape=%s" % (r.get("full"), r.get("shape")))
    again = run("brand", book, fresh_cache("cache-b-again-" + label))
    a = counters(again)
    check("B %s discovery leaves an already-branded book alone" % label,
          a.get("result") == "0" and a.get("full") == "0",
          "-> result=%s full=%s" % (a.get("result"), a.get("full")))

# ---- Test C: metadata-mutated PDF -----------------------------------
say("")
say("== Test C: metadata-mutated PDF (the chunk 35 A/B reproducer) ==")
a_pdf = copy(BOOK_A, "c-a.pdf")
stamp(a_pdf, "CH36 Original", "CH36 Author")
b_pdf = copy(a_pdf, "c-b.pdf")
stamp(b_pdf, "CH36 Renamed", "CH36 Other Author", stats=True)
ident_a, ident_b = identity_of(a_pdf), identity_of(b_pdf)
read_a = counters(run("readrec", a_pdf, fresh_cache("cache-c-a")))
read_b = counters(run("readrec", b_pdf, fresh_cache("cache-c-b")))
say("  stored A : %s" % read_a.get("fingerprint"))
say("  stored B : %s" % read_b.get("fingerprint"))
say("  bytes    : %d vs %d" % (os.path.getsize(a_pdf), os.path.getsize(b_pdf)))
check("C the stored identities are equal",
      bool(ident_a) and ident_a == ident_b, "-> %s" % ident_a)
check("C the metadata write really did change the file",
      os.path.getsize(a_pdf) != os.path.getsize(b_pdf))
check("C establishing equality recomputed nothing",
      read_a.get("full") == "0" and read_a.get("shape") == "0" and
      read_b.get("full") == "0" and read_b.get("shape") == "0",
      "-> A full=%s shape=%s, B full=%s shape=%s" % (
          read_a.get("full"), read_a.get("shape"), read_b.get("full"), read_b.get("shape")))
merged = dedupe([entry(a_pdf, "CH36 Original", "CH36 Author"),
                 entry(b_pdf, "CH36 Renamed", "CH36 Other Author")])
check("C the two copies are still one logical book", len(merged) == 1,
      "-> %d book(s)" % len(merged))

# ---- Test D: same title + author, different content -----------------
say("")
say("== Test D: same title, same author, different books ==")
d1 = copy(BOOK_A, "d-one.pdf")
d2 = copy(BOOK_B, "d-two.pdf")
stamp(d1, "The Same Title", "The Same Author")
stamp(d2, "The Same Title", "The Same Author")
fp1 = counters(run("readrec", d1, fresh_cache("cache-d1"))).get("fingerprint")
fp2 = counters(run("readrec", d2, fresh_cache("cache-d2"))).get("fingerprint")
say("  fingerprint A : %s" % fp1)
say("  fingerprint B : %s" % fp2)
check("D the two books have different fingerprints", bool(fp1) and fp1 != fp2)
pair = dedupe([entry(d1, "The Same Title", "The Same Author"),
               entry(d2, "The Same Title", "The Same Author")])
check("D same title and author do NOT merge them", len(pair) == 2,
      "-> %d book(s)" % len(pair))

# ---- Test E: same title, different volume ---------------------------
say("")
say("== Test E: same series title, different volumes ==")
e1 = copy(BOOK_A, "e-vol1.pdf")
e2 = copy(BOOK_B, "e-vol2.pdf")
stamp(e1, "Starting Life In Another World", "Tappei Nagatsuki")
stamp(e2, "Starting Life In Another World", "Tappei Nagatsuki")
vols = dedupe([entry(e1, "Starting Life In Another World", "Tappei Nagatsuki", (1,)),
               entry(e2, "Starting Life In Another World", "Tappei Nagatsuki", (2,))])
check("E different volumes of one series stay separate", len(vols) == 2,
      "-> %d book(s)" % len(vols))
same_vol = dedupe([entry(e1, "Starting Life In Another World", "Tappei Nagatsuki", (1,)),
                   entry(e2, "Starting Life In Another World", "Tappei Nagatsuki", (1,))])
check("E and so do two different books labelled the same volume",
      len(same_vol) == 2, "-> %d book(s)" % len(same_vol))

# ---- Test F: positive duplicate -------------------------------------
say("")
say("== Test F: the same book twice ==")
f1 = copy(BOOK_A, "f-one.pdf")
stamp(f1, "CH36 Duplicate Source", "CH36 Author")
f2 = copy(f1, "totally-different-filename.pdf")
stamp(f2, "CH36 Duplicate Copy", "CH36 Someone Else", stats=True)
dup = dedupe([entry(f1, "CH36 Duplicate Source", "CH36 Author"),
              entry(f2, "CH36 Duplicate Copy", "CH36 Someone Else")])
eds = dup[0].get("editions") if len(dup) == 1 else []
check("F one logical book with two editions",
      len(dup) == 1 and len(eds) == 2,
      "-> %d book(s), %d edition(s)" % (len(dup), len(eds)))

# ---- Test G: unbranded books with the same title ---------------------
say("")
say("== Test G: unbranded books that share a title ==")
g1 = os.path.join(WORK, "g-one.pdf")
g2 = os.path.join(WORK, "g-two.pdf")
make_plain_pdf(g1, ["An unbranded book.", "Second page."])
make_plain_pdf(g2, ["A different unbranded book.", "Also a second page."])
g3 = copy(g1, "g-three.pdf")
check("G none of them has an identity yet",
      not identity_of(g1) and not identity_of(g2) and not identity_of(g3))
alone = dedupe([entry(g1, "Shared Title", "Shared Author"),
                entry(g2, "Shared Title", "Shared Author"),
                entry(g3, "Shared Title", "Shared Author")])
check("G they stay separate until discovery brands them", len(alone) == 3,
      "-> %d book(s)" % len(alone))

# ---- Test H: one newly discovered unbranded book --------------------
say("")
say("== Test H: a book the library has never seen ==")
h = os.path.join(WORK, "h-new-arrival.pdf")
make_plain_pdf(h, ["A book the library has never seen before.",
                   "It arrives with no Sumatra record at all."])
before = identity_of(h)
first = run("brand", h, fresh_cache("cache-h1"))
say("  first discovery : %s" % first)
second = run("brand", h, fresh_cache("cache-h2"))
say("  next startup    : %s" % second)
third = run("readrec", h, fresh_cache("cache-h3"))
say("  and again       : %s" % third)
c1, c2, c3 = counters(first), counters(second), counters(third)
after = identity_of(h)
check("H it had no identity before discovery", not before)
check("H discovery computed the fingerprint exactly once",
      c1.get("result") == "1" and c1.get("full") == "1",
      "-> result=%s full=%s" % (c1.get("result"), c1.get("full")))
check("H the fingerprint was persisted into the book",
      bool(after) and after != before)
check("H the next startup computes nothing for it",
      c2.get("result") == "0" and c2.get("full") == "0" and c2.get("shape") == "0",
      "-> full=%s shape=%s" % (c2.get("full"), c2.get("shape")))
check("H and neither does the one after",
      c3.get("accepted") == "1" and c3.get("full") == "0" and c3.get("shape") == "0",
      "-> full=%s shape=%s" % (c3.get("full"), c3.get("shape")))

# ---- Test H2: a branded book whose file grew ------------------------
say("")
say("== Test H2: a branded book that changed size and mtime ==")
grown = copy(BOOK_A, "h2-grown.pdf")
stamp(grown, "CH36 Grown", "CH36 Author")
kept = counters(run("readrec", grown, fresh_cache("cache-h2a"))).get("fingerprint")
with open(grown, "ab") as fh:
    fh.write(b"\n%% chunk36 trailing bytes\n")
now = counters(run("readrec", grown, fresh_cache("cache-h2b")))
check("H2 a bigger, newer file keeps its brand",
      now.get("accepted") == "1" and now.get("fingerprint") == kept,
      "-> %s" % now.get("fingerprint"))
check("H2 and is not re-fingerprinted",
      now.get("full") == "0" and now.get("shape") == "0",
      "-> full=%s shape=%s" % (now.get("full"), now.get("shape")))

print()
if failures:
    print("chunk36-identity: %d FAILURE(S): %s" % (len(failures), ", ".join(failures)))
    sys.exit(1)
print("chunk36-identity: ALL CHECKS PASSED")
