import json
import os
import sys
import tempfile
import time

sys.path.insert(0, os.path.abspath(os.path.join(os.getcwd(), "..",
                                                "Chatterbox-TTS-Extended-main")))
from audiobook import pdfbook

pdfbook.CACHE_ROOT = tempfile.mkdtemp(prefix="chunk19rd2-")
from audiobook.library import desk, shelf

results = []


def check(name, ok, detail):
    results.append((name, ok))
    print(("  PASS  " if ok else "  FAIL  ") + name)
    print("        " + detail)


A = r"C:\one\alpha.pdf"
B = r"C:\one\beta.pdf"
C = r"C:\one\gamma.pdf"


def write_raw(data):
    path = desk.store_path()
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=1)


def raw():
    with open(desk.store_path(), encoding="utf-8") as f:
        return json.load(f)


def legacy_only():
    write_raw({"kinds": {desk._key(A): desk.IGNORED},
               "pending": {}, "excluded": {}})


desk.configure(desk.DEFAULT_IGNORE_DAYS)
legacy_only()
before = raw()
store = desk.load()
rec = store["pending"].get(desk._key(A))
check("a legacy untimed Ignored record receives a timer on load",
      not before["pending"] and bool(rec)
      and store["kinds"].get(desk._key(A)) == desk.IGNORED,
      "before pending=%s after pending=%s" % (before["pending"], rec))

check("the migrated timer uses the configured days",
      rec["days"] == desk.DEFAULT_IGNORE_DAYS
      and abs(rec["expires"] - rec["since"] - desk.DEFAULT_IGNORE_DAYS * desk.DAY) < 1.0,
      "days=%s span=%.0f" % (rec["days"], rec["expires"] - rec["since"]))

check("the migration is written to the store",
      bool(raw()["pending"].get(desk._key(A))),
      "stored pending=%s" % json.dumps(raw()["pending"]))

first = dict(rec)
time.sleep(1.1)
again = desk.load()["pending"].get(desk._key(A))
once_more = desk.load()["pending"].get(desk._key(A))
check("repeated loads do not restart the migrated timer",
      again["since"] == first["since"] and again["expires"] == first["expires"]
      and once_more["since"] == first["since"]
      and once_more["expires"] == first["expires"],
      "since %r -> %r -> %r" % (first["since"], again["since"],
                                once_more["since"]))

desk.configure(7)
legacy_only()
rec = desk.load()["pending"].get(desk._key(A))
check("a configured period of 7 days is used",
      rec["days"] == 7 and abs(rec["expires"] - rec["since"] - 7 * desk.DAY) < 1.0,
      "days=%s span=%.0f" % (rec["days"], rec["expires"] - rec["since"]))
desk.configure(desk.DEFAULT_IGNORE_DAYS)

old = time.time() - 3 * desk.DAY
mixed = {"kinds": {desk._key(A): desk.IGNORED, desk._key(B): desk.IGNORED},
         "pending": {desk._key(B): {"path": B, "identity": "beta",
                                    "size": 11, "mtime": 12,
                                    "since": old, "days": 7,
                                    "expires": old + 7 * desk.DAY}},
         desk.EXCLUDED: ["gamma"]}
write_raw(mixed)
store = desk.load()
a = store["pending"].get(desk._key(A))
b = store["pending"].get(desk._key(B))
c = store[desk.EXCLUDED]
check("in a mixed store only the untimed record is migrated",
      bool(a) and a["since"] > old + 1,
      "A pending=%s" % a)
check("an existing timer keeps its own since and expires",
      b["since"] == mixed["pending"][desk._key(B)]["since"]
      and b["expires"] == mixed["pending"][desk._key(B)]["expires"]
      and b["days"] == 7,
      "B before since=%r expires=%r after since=%r expires=%r"
      % (mixed["pending"][desk._key(B)]["since"],
         mixed["pending"][desk._key(B)]["expires"],
         b["since"], b["expires"]))
check("an existing exclusion is unchanged",
      c == ["gamma"],
      "C after=%s" % c)

write_raw({"kinds": {desk._key(A): desk.IGNORED},
           "pending": {desk._key(A): dict(a, identity="alpha")},
           desk.EXCLUDED: ["gamma"]})
ripe = desk.ripen(a["expires"] + 1)
store = desk.load()
check("a migrated timer expires out of Ignored into an exclusion",
      len(ripe) >= 1 and desk._key(A) not in store["kinds"]
      and desk._key(A) not in store["pending"]
      and desk.is_excluded(store, "alpha"),
      "kind=%s pending=%s excluded=%s"
      % (store["kinds"].get(desk._key(A)),
         store["pending"].get(desk._key(A)),
         store[desk.EXCLUDED]))

entry = {"path": A, "kind": desk.BOOK, "fingerprint": "fp3:alpha:1"}
piles = shelf._sort_out([entry], store)
check("a scan cannot put an excluded file back into a pile",
      all(len(v) == 0 for v in piles.values()),
      "books=%d documents=%d ignored=%d"
      % (len(piles[desk.BOOK]), len(piles[desk.DOCUMENT]),
         len(piles[desk.IGNORED])))

dropped = desk.readmit(["alpha"])
store = desk.load()
desk.tell([A], desk.BOOK)
store = desk.load()
piles = shelf._sort_out([entry], store)
check("manual readmission clears the exclusion and restores the book",
      len(dropped) == 1 and not desk.is_excluded(store, "alpha")
      and len(piles[desk.BOOK]) == 1,
      "excluded=%s kind=%s books=%d"
      % (store[desk.EXCLUDED], store["kinds"].get(desk._key(A)),
         len(piles[desk.BOOK])))

write_raw({"kinds": {desk._key(C): desk.BOOK},
           "pending": {},
           desk.EXCLUDED: ["gamma"]})
store = desk.load()
check("an exclusion list survives a plain load",
      not store["pending"] and store[desk.EXCLUDED] == ["gamma"],
      "pending=%d excluded=%s"
      % (len(store["pending"]), store[desk.EXCLUDED]))

bad = sum(1 for _, ok in results if not ok)
print("\n%d checks, %d failed" % (len(results), bad))
sys.exit(1 if bad else 0)
