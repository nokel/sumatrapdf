# chunk 35: logical duplicate identity must come from the substantive
# text of the book, never from a whole-file hash.
#
# Sumatra stores metadata, the cover and read state inside the book file
# itself, so two files that are the same book stop being byte-identical
# the moment either one is touched. The tests below build those cases on
# disposable copies and check that shelf still calls them one book, that
# the whole-file sha1 really has diverged, and that two byte-identical
# copies with no fingerprint are NOT merged (the removed fallback).
#
#   py -3.13 tests/chunk35-identity.py <chatterbox-dir> <bench_library.exe> <work-dir> <book-a.pdf> <book-b.pdf>

import os
import shutil
import subprocess
import sys

CHATTERBOX, BENCH, WORK, BOOK_A, BOOK_B = sys.argv[1:6]
sys.path.insert(0, CHATTERBOX)

from audiobook import pdfbook
from audiobook.library import shelf

failures = []
notes = []


def say(s):
    print(s)
    notes.append(s)


def check(name, ok, detail=""):
    say(("PASS " if ok else "FAIL ") + name + (" " + detail if detail else ""))
    if not ok:
        failures.append(name)


def stamp(path, title, author, stats=False):
    args = [BENCH, "stamp", path, title, author] + (["stats"] if stats else [])
    out = subprocess.run(args, capture_output=True, text=True).stdout
    if "wrote=1" not in out:
        raise SystemExit("stamp failed for %s: %s" % (path, out.strip()))


def copy(src, name):
    dst = os.path.join(WORK, name)
    shutil.copyfile(src, dst)
    return dst


def entry(path, title, author, folder=None):
    st = os.stat(path)
    return {
        "path": path,
        "folder": folder or os.path.dirname(path),
        "series_folder": None,
        "title": title,
        "author": author,
        "ext": os.path.splitext(path)[1].lower(),
        "size": st.st_size,
        "mtime": st.st_mtime,
        "pages": 99,
        "kind": 0,
        "volumes": [],
    }


def identity_of(path):
    st = os.stat(path)
    shelf._identity_cache.clear()
    return shelf.book_identity(path, st.st_size, st.st_mtime)


def hash_of(path):
    shelf._hash_cache.clear()
    return pdfbook.book_hash(path)


def dedupe(entries):
    shelf._identity_cache.clear()
    shelf._hash_cache.clear()
    shelf._attach_analysis(entries)
    return shelf._dedupe(entries)


os.makedirs(WORK, exist_ok=True)
for f in os.listdir(WORK):
    os.remove(os.path.join(WORK, f))

# ---- fixtures -------------------------------------------------------
a = copy(BOOK_A, "a-original.pdf")
stamp(a, "CH35 Original", "CH35 Author")

b = copy(a, "b-metadata-changed.pdf")
stamp(b, "CH35 Renamed By User", "CH35 Author Two")

c = copy(a, "newlydownloaded.pdf")
stamp(c, "CH35 Third Name", "CH35 Author Three")

d = copy(a, "d-read-state.pdf")
stamp(d, "CH35 Fourth Name", "CH35 Author Four", stats=True)

other = copy(BOOK_B, "other-book.pdf")
stamp(other, "CH35 Different Book", "CH35 Other Author")

# A book that has never been branded. The library books on this machine
# all carry a sidecar already, so one is generated here instead: two
# byte-identical copies of a PDF with no Sumatra blob in it.
def make_plain_pdf(path, lines):
    import fitz
    doc = fitz.open()
    for n, body in enumerate(lines):
        page = doc.new_page()
        page.insert_text((72, 100), body)
        page.insert_text((72, 140), "page %d of the unbranded fixture" % (n + 1))
    doc.save(path)
    doc.close()


virgin1 = os.path.join(WORK, "virgin-one.pdf")
make_plain_pdf(virgin1, ["Wretched hive of scum and villainy.",
                         "The unbranded fixture has two pages."])
virgin2 = copy(virgin1, "virgin-two.pdf")

ident_a = identity_of(a)
ident_b = identity_of(b)
ident_c = identity_of(c)
ident_d = identity_of(d)
ident_other = identity_of(other)
ident_v1 = identity_of(virgin1)

hash_a = hash_of(a)
hash_b = hash_of(b)
hash_c = hash_of(c)
hash_d = hash_of(d)

say("identity A       %s" % ident_a)
say("identity B       %s" % ident_b)
say("identity C       %s" % ident_c)
say("identity D       %s" % ident_d)
say("identity other   %s" % ident_other)
say("identity virgin  %r" % ident_v1)
say("book_hash A      %s (%d bytes)" % (hash_a, os.path.getsize(a)))
say("book_hash B      %s (%d bytes)" % (hash_b, os.path.getsize(b)))
say("book_hash C      %s (%d bytes)" % (hash_c, os.path.getsize(c)))
say("book_hash D      %s (%d bytes)" % (hash_d, os.path.getsize(d)))

# ---- Test A: same book, metadata changed ----------------------------
check("A identity survives a metadata change", bool(ident_a) and ident_a == ident_b)
check("A whole-file hash does not", hash_a != hash_b)

# ---- Test B: filename / path difference -----------------------------
check("B identity survives a different filename", ident_c == ident_a)

# ---- Test C: read-state difference ----------------------------------
check("C identity survives a read-state write", ident_d == ident_a)
check("C read-state write did change the bytes", hash_d != hash_a)

# ---- Test D: whole-file hash divergence -----------------------------
check("D all four copies have distinct whole-file hashes",
      len({hash_a, hash_b, hash_c, hash_d}) == 4)
merged = dedupe([entry(a, "CH35 Original", "CH35 Author"),
                 entry(b, "CH35 Renamed By User", "CH35 Author Two")])
check("D shelf still calls them one book", len(merged) == 1,
      "-> %d book(s)" % len(merged))

# ---- Test G: duplicate merge ----------------------------------------
group = dedupe([entry(a, "CH35 Original", "CH35 Author"),
                entry(b, "CH35 Renamed By User", "CH35 Author Two"),
                entry(c, "CH35 Third Name", "CH35 Author Three"),
                entry(d, "CH35 Fourth Name", "CH35 Author Four")])
eds = group[0].get("editions") if len(group) == 1 else []
check("G four metadata-different copies merge into one logical book",
      len(group) == 1 and len(eds) == 4,
      "-> %d book(s), %d edition(s)" % (len(group), len(eds)))

# ---- Test H: distinct books -----------------------------------------
two = dedupe([entry(a, "CH35 Original", "CH35 Author"),
              entry(other, "CH35 Different Book", "CH35 Other Author")])
check("H two different books stay two books", len(two) == 2,
      "-> %d book(s)" % len(two))
check("H their identities differ", ident_a != ident_other and bool(ident_other))

# ---- Part A: the removed whole-file fallback ------------------------
check("unbranded copies have no logical identity", not ident_v1)
pair = dedupe([entry(virgin1, "CH35 Virgin One", "CH35 V"),
               entry(virgin2, "CH35 Virgin Two", "CH35 V")])
same_bytes = hash_of(virgin1) == hash_of(virgin2)
check("byte-identical unbranded copies are NOT merged by their bytes",
      same_bytes and len(pair) == 2,
      "-> identical bytes=%s, %d book(s)" % (same_bytes, len(pair)))

# ---- Test E: a new unbranded book is fingerprinted once -------------
def counters(out):
    return dict(kv.split("=", 1) for kv in out.split() if "=" in kv)


cache = os.path.join(WORK, "fpcache")
os.makedirs(cache, exist_ok=True)
fresh = os.path.join(WORK, "new-arrival.pdf")
make_plain_pdf(fresh, ["A book the library has never seen before.",
                       "It arrives with no Sumatra record at all."])
before = identity_of(fresh)
first = subprocess.run([BENCH, "stamp", fresh, "CH35 New Arrival", "CH35 New"],
                       capture_output=True, text=True).stdout.strip()
say("E first discovery : %s" % first)
cold = subprocess.run([BENCH, "readrec", fresh, cache],
                      capture_output=True, text=True).stdout.strip()
say("E second scan     : %s" % cold)
warm = subprocess.run([BENCH, "readrec", fresh, cache],
                      capture_output=True, text=True).stdout.strip()
say("E third scan      : %s" % warm)
after = identity_of(fresh)
c1, c2, c3 = counters(first), counters(cold), counters(warm)
check("E the new book had no identity before discovery", not before)
check("E discovery computed the fingerprint once", c1.get("full") == "1",
      "-> full=%s" % c1.get("full"))
check("E discovery persisted it", bool(after) and after != before)
# chunk 36 tightened this: the persisted brand is read and used, so a
# later scan opens no document at all, with or without a warm cache.
check("E the next scan recomputes nothing",
      c2.get("full") == "0" and c2.get("shape") == "0",
      "-> full=%s shape=%s seeded=%s" % (c2.get("full"), c2.get("shape"), c2.get("seeded")))
check("E the scan after that does not even reopen the book",
      c3.get("full") == "0" and c3.get("shape") == "0",
      "-> full=%s shape=%s cacheHits=%s" % (c3.get("full"), c3.get("shape"), c3.get("cacheHits")))
check("E the record is accepted for its own file", "accepted=1" in cold and "accepted=1" in warm)

print()
if failures:
    print("chunk35-identity: %d FAILURE(S): %s" % (len(failures), ", ".join(failures)))
    sys.exit(1)
print("chunk35-identity: ALL CHECKS PASSED")
