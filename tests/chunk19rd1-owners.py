import os
import sys
import tempfile
import time

sys.path.insert(0, os.path.abspath(os.path.join(os.getcwd(), "..",
                                                "Chatterbox-TTS-Extended-main")))
from audiobook import pdfbook

pdfbook.CACHE_ROOT = tempfile.mkdtemp(prefix="chunk19rd1-")
from audiobook.library import desk, shelf

results = []


def check(name, ok, detail):
    results.append((name, ok))
    print(("  PASS  " if ok else "  FAIL  ") + name)
    print("        " + detail)


def wipe():
    desk.save(desk._blank())


def catalogue():
    shelf.save_index({"roots": [r"C:\one"], "scanned": time.time(),
                      "books": [{"path": A, "file": "alpha.pdf",
                                 "folder": r"C:\one", "kind": desk.BOOK,
                                 "size": 1, "mtime": 1.0,
                                 "fingerprint": "fp3:idA:x"}],
                      "documents": [], "ignored": []})



A = r"C:\one\alpha.pdf"
B = r"C:\one\beta.pdf"

catalogue()

wipe()
desk.tell([A], desk.IGNORED)
store = desk.load()
rec = store["pending"].get(desk._key(A))
check("desk.tell(ignored) creates a timer",
      bool(rec) and rec["days"] == desk.DEFAULT_IGNORE_DAYS
      and store["kinds"].get(desk._key(A)) == desk.IGNORED,
      "kind=%s pending=%s" % (store["kinds"].get(desk._key(A)), rec))

wipe()
desk.tell([A], desk.IGNORED, 7)
rec = desk.load()["pending"].get(desk._key(A))
check("desk.tell(ignored) honours the configured days",
      bool(rec) and rec["days"] == 7 and
      abs(rec["expires"] - rec["since"] - 7 * desk.DAY) < 1.0,
      "days=%s span=%.0f" % (rec and rec["days"],
                             rec and rec["expires"] - rec["since"]))

wipe()
desk.tell([A], desk.DOCUMENT)
store = desk.load()
check("desk.tell(document) creates no timer",
      store["kinds"].get(desk._key(A)) == desk.DOCUMENT
      and not store["pending"],
      "kind=%s pending=%d" % (store["kinds"].get(desk._key(A)),
                              len(store["pending"])))

wipe()
desk.tell([A], desk.IGNORED, 1)
ripe = desk.ripen(time.time() + desk.DAY + 1)
store = desk.load()
check("a ripe timer leaves Ignored and becomes an exclusion",
      len(ripe) == 1 and not store["pending"]
      and desk._key(A) not in store["kinds"]
      and desk.is_excluded(store, "ida"),
      "ripe=%d kinds=%d pending=%d excluded=%s"
      % (len(ripe), len(store["kinds"]), len(store["pending"]),
         store[desk.EXCLUDED]))

index = {"books": [{"id": "b1", "path": A, "kind": "book", "size": 1,
                    "mtime": 1.0, "fingerprint": "fp3:idA:x"}],
         "documents": [{"id": "d1", "path": B, "kind": "document", "size": 1,
                        "mtime": 1.0, "fingerprint": "fp3:idB:x"}],
         "ignored": [], "roots": [r"C:\one"]}
wipe()
moved, msg = shelf.recategorize(index, ["b1"], desk.IGNORED, 5)
store = desk.load()
rec = store["pending"].get(desk._key(A))
check("shelf.recategorize to ignored always holds",
      moved == 1 and bool(rec) and rec["days"] == 5
      and rec["identity"] == "ida",
      "moved=%d msg=%s pending=%s" % (moved, msg, rec))

moved, msg = shelf.recategorize(index, ["d1"], desk.DOCUMENT)
check("a move to another pile keeps the other timer",
      len(desk.load()["pending"]) == 1,
      "pending=%d" % len(desk.load()["pending"]))

wipe()
fresh = {"books": [{"id": "b1", "path": A, "kind": "book", "size": 1,
                    "mtime": 1.0, "fingerprint": "fp3:idA:x"}],
         "documents": [], "ignored": [], "roots": [r"C:\one"]}
shelf.recategorize(fresh, ["b1"], desk.IGNORED)
rec = desk.load()["pending"].get(desk._key(A))
check("a request without days uses the default",
      bool(rec) and rec["days"] == desk.DEFAULT_IGNORE_DAYS,
      "days=%s" % (rec and rec["days"]))

wipe()
desk.tell([A], desk.IGNORED, 1)
desk.ripen(time.time() + desk.DAY + 1)
store = desk.load()
entry = {"path": A, "fingerprint": "fp3:idA:x"}
piles = shelf._sort_out([entry], store)
check("an excluded file is refused by the scan sorter",
      not piles[desk.BOOK] and not piles[desk.DOCUMENT]
      and not piles[desk.IGNORED],
      "books=%d documents=%d ignored=%d"
      % (len(piles[desk.BOOK]), len(piles[desk.DOCUMENT]),
         len(piles[desk.IGNORED])))

dropped = desk.readmit(["ida"])
store = desk.load()
check("manual readmission clears the exclusion",
      len(dropped) == 1 and not store[desk.EXCLUDED],
      "dropped=%d excluded=%s" % (len(dropped), store[desk.EXCLUDED]))

bad = sum(1 for name, ok in results if not ok)
print("\n%d check(s), %d failed" % (len(results), bad))
sys.exit(1 if bad else 0)
