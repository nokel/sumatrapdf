import json
import os
import sys
import tempfile
import time

sys.path.insert(0, os.path.abspath(os.path.join(os.getcwd(), "..",
                                                "Chatterbox-TTS-Extended-main")))
from audiobook import pdfbook

pdfbook.CACHE_ROOT = tempfile.mkdtemp(prefix="chunk19rd3-")
from audiobook.library import desk, shelf

results = []


def check(name, ok, detail):
    results.append((name, ok))
    print(("  PASS  " if ok else "  FAIL  ") + name)
    print("        " + detail)


BRANDED = os.environ.get(
    "RD3_BRANDED",
    r"C:\Users\Nokel\Documents\ebooks\manga_novels\Animorphs\Animorphs"
    r"\02-The Visitor.pdf")
A = r"C:\one\alpha.pdf"
EDITION = r"C:\one\alpha copy.pdf"
PLAIN = r"C:\one\nothing.pdf"


def write_raw(data):
    with open(desk.store_path(), "w", encoding="utf-8") as f:
        json.dump(data, f, indent=1)


def legacy(*paths):
    write_raw({"kinds": {desk._key(p): desk.IGNORED for p in paths},
               "pending": {}, "excluded": {}})


def index_with(rows):
    shelf.save_index({"roots": [r"C:\one"], "scanned": time.time(),
                      "books": rows, "documents": [], "ignored": []})


desk.configure(desk.DEFAULT_IGNORE_DAYS)

index_with([{"path": A, "file": "alpha.pdf", "folder": r"C:\one",
             "kind": desk.BOOK, "size": 10, "mtime": 20,
             "fingerprint": "fp3:1111aaaa2222bbbb:cafe"}])
legacy(A)
rec = desk.load()["pending"].get(desk._key(A))
check("a legacy record takes its identity from the catalogue record",
      rec["identity"] == "1111aaaa2222bbbb" and rec["path"] == desk._key(A),
      "identity=%r path=%r" % (rec["identity"], rec["path"]))

index_with([{"path": A, "file": "alpha.pdf", "folder": r"C:\one",
             "kind": desk.BOOK, "size": 10, "mtime": 20,
             "fingerprint": "fp3:1111aaaa2222bbbb:cafe",
             "editions": [{"path": EDITION, "size": 11, "mtime": 21}]}])
legacy(EDITION)
rec = desk.load()["pending"].get(desk._key(EDITION))
check("a legacy record takes its identity from an edition record",
      rec["identity"] == "1111aaaa2222bbbb",
      "identity=%r" % rec["identity"])

index_with([])
legacy(BRANDED)
rec = desk.load()["pending"].get(desk._key(BRANDED))
record = pdfbook.sumatra_record_from_path(BRANDED) or {}
want = shelf.fingerprint_identity(record.get("fingerprint"))
check("a legacy record with no catalogue row reads the file's own record",
      bool(want) and rec["identity"] == want,
      "file fingerprint=%r identity=%r" % (record.get("fingerprint"),
                                           rec["identity"]))

index_with([{"path": A, "file": "alpha.pdf", "folder": r"C:\one",
             "kind": desk.BOOK, "size": 10, "mtime": 20,
             "title": "Alpha", "author": "Someone",
             "fingerprint": "fp3:1111aaaa2222bbbb:cafe"}])
legacy(PLAIN)
rec = desk.load()["pending"].get(desk._key(PLAIN))
check("a name that matches a catalogued book gives no identity",
      rec["identity"] == "",
      "identity=%r for %s" % (rec["identity"], PLAIN))

index_with([{"path": A, "file": "alpha.pdf", "folder": r"C:\one",
             "kind": desk.BOOK, "size": 10, "mtime": 20,
             "fingerprint": "fp3:1111aaaa2222bbbb:cafe"}])
legacy(A)
rec = desk.load()["pending"].get(desk._key(A))
ripe = desk.ripen(rec["expires"] + 1)
store = desk.load()
check("expiry keeps the identity in the exclusion list",
      store[desk.EXCLUDED] == ["1111aaaa2222bbbb"]
      and ripe[0]["identity"] == "1111aaaa2222bbbb"
      and ripe[0]["path"] == desk._key(A),
      "excluded=%s ripe=%s" % (store[desk.EXCLUDED], json.dumps(ripe)))

moved = {"path": r"C:\two\renamed alpha.pdf", "kind": desk.BOOK,
         "fingerprint": "fp3:1111aaaa2222bbbb:cafe"}
piles = shelf._sort_out([moved], store)
check("the same content under a new name stays out of every pile",
      all(len(v) == 0 for v in piles.values())
      and desk.is_excluded(store, "1111aaaa2222bbbb"),
      "books=%d documents=%d ignored=%d is_excluded=%s"
      % (len(piles[desk.BOOK]), len(piles[desk.DOCUMENT]),
         len(piles[desk.IGNORED]),
         desk.is_excluded(store, "1111aaaa2222bbbb")))

dropped = desk.readmit(["1111aaaa2222bbbb"])
desk.tell([moved["path"]], desk.BOOK)
store = desk.load()
piles = shelf._sort_out([moved], store)
check("manual readmission lets the renamed file back in",
      len(dropped) == 1 and len(piles[desk.BOOK]) == 1
      and not store[desk.EXCLUDED],
      "dropped=%d books=%d excluded=%s"
      % (len(dropped), len(piles[desk.BOOK]), store[desk.EXCLUDED]))

index = {"roots": [r"C:\one"], "scanned": time.time(),
         "books": [{"path": A, "file": "alpha.pdf", "folder": r"C:\one",
                    "kind": desk.BOOK, "size": 10, "mtime": 20,
                    "fingerprint": "fp3:1111aaaa2222bbbb:cafe"}],
         "documents": [], "ignored": []}
legacy(A)
store = desk.load()
count = shelf.resort(index, store)
check("the Ignored pile is rebuilt without a scan",
      count == 1 and len(index["ignored"]) == 1 and len(index["books"]) == 0
      and index["ignored"][0]["path"] == A,
      "moved=%d books=%d ignored=%d" % (count, len(index["books"]),
                                        len(index["ignored"])))

again = shelf.resort(index, store)
check("a second refresh moves nothing",
      again == 0 and len(index["ignored"]) == 1,
      "moved=%d ignored=%d" % (again, len(index["ignored"])))

old = time.time() - 3 * desk.DAY
write_raw({"kinds": {desk._key(A): desk.IGNORED,
                     desk._key(EDITION): desk.IGNORED},
           "pending": {desk._key(EDITION): {"path": EDITION,
                                            "identity": "beta",
                                            "size": 11, "mtime": 12,
                                            "since": old, "days": 7,
                                            "expires": old + 7 * desk.DAY}},
           desk.EXCLUDED: ["gamma"]})
store = desk.load()
kept = store["pending"][desk._key(EDITION)]
shut = store[desk.EXCLUDED]
check("existing timers and exclusions are untouched by identity migration",
      kept["since"] == old and kept["expires"] == old + 7 * desk.DAY
      and kept["identity"] == "beta" and shut == ["gamma"],
      "timer=%s exclusion=%s" % (json.dumps(kept), json.dumps(shut)))

bad = sum(1 for _, ok in results if not ok)
print("\n%d checks, %d failed" % (len(results), bad))
sys.exit(1 if bad else 0)
