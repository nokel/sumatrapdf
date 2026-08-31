import json
import os
import sys
import tempfile
import time

sys.path.insert(0, os.path.abspath(os.path.join(os.getcwd(), "..",
                                                "Chatterbox-TTS-Extended-main")))
from audiobook import pdfbook

pdfbook.CACHE_ROOT = tempfile.mkdtemp(prefix="chunk19rd4-")
from audiobook.library import desk, shelf

results = []


def check(name, ok, detail):
    results.append((name, ok))
    print(("  PASS  " if ok else "  FAIL  ") + name)
    print("        " + detail)


BRANDED = os.environ.get(
    "RD4_BRANDED",
    r"C:\Users\Nokel\Documents\ebooks\manga_novels\Animorphs\Animorphs"
    r"\02-The Visitor.pdf")
A = r"C:\one\alpha.pdf"
MOVED = r"D:\elsewhere\alpha renamed.pdf"
TWIN = r"C:\two\alpha copy.pdf"
PLAIN = r"C:\one\nothing.pdf"
MARK = "1111aaaa2222bbbb"
OTHER = "3333cccc4444dddd"
FP = "fp3:" + MARK + ":cafe"


def write_raw(data):
    with open(desk.store_path(), "w", encoding="utf-8") as f:
        json.dump(data, f, indent=1)


def raw():
    with open(desk.store_path(), encoding="utf-8") as f:
        return json.load(f)


def index_with(rows):
    shelf.save_index({"roots": [r"C:\one"], "scanned": time.time(),
                      "books": rows, "documents": [], "ignored": []})


def catalogue_row(path, fingerprint=FP):
    return {"path": path, "file": os.path.basename(path),
            "folder": os.path.dirname(path), "kind": desk.BOOK,
            "size": 10, "mtime": 20, "fingerprint": fingerprint}


desk.configure(desk.DEFAULT_IGNORE_DAYS)

index_with([catalogue_row(A)])
write_raw({"kinds": {}, "pending": {}, desk.EXCLUDED: []})
desk.hold([{"path": A, "identity": MARK}], 1)
ripe = desk.ripen(time.time() + desk.DAY + 1)
store = desk.load()
check("expiry puts the fingerprint identity in the exclusion list",
      store[desk.EXCLUDED] == [MARK] and desk._key(A) not in store["kinds"]
      and desk._key(A) not in store["pending"],
      "list=%s kind=%s pending=%s" % (store[desk.EXCLUDED],
                                      store["kinds"].get(desk._key(A)),
                                      store["pending"].get(desk._key(A))))

on_disk = raw()
check("the store holds a plain list of identities and no exclusion path",
      on_disk[desk.EXCLUDED] == [MARK]
      and "alpha.pdf" not in json.dumps(on_disk.get(desk.EXCLUDED)),
      "%s = %s" % (desk.EXCLUDED, json.dumps(on_disk[desk.EXCLUDED])))

renamed = {"path": r"C:\one\alpha with another name.pdf", "kind": desk.BOOK,
           "fingerprint": FP}
moved = {"path": MOVED, "kind": desk.BOOK, "fingerprint": FP}
piles = shelf._sort_out([renamed, moved], store)
check("a renamed and a moved copy are both refused by the list",
      all(len(v) == 0 for v in piles.values()),
      "books=%d documents=%d ignored=%d" % (len(piles[desk.BOOK]),
                                            len(piles[desk.DOCUMENT]),
                                            len(piles[desk.IGNORED])))

twin = {"path": TWIN, "kind": desk.BOOK, "fingerprint": "fp3:" + MARK + ":beef"}
piles = shelf._sort_out([twin], store)
check("another edition of the same logical book is refused",
      len(piles[desk.BOOK]) == 0,
      "shape differs, identity is the same: %s" % twin["fingerprint"])

same_path = {"path": A, "kind": desk.BOOK, "fingerprint": "fp3:" + OTHER + ":cafe"}
no_mark = {"path": A, "kind": desk.BOOK}
piles = shelf._sort_out([same_path, no_mark], store)
check("the path is not the key: other content at that path is admitted",
      len(piles[desk.BOOK]) == 2,
      "admitted=%d" % len(piles[desk.BOOK]))

dropped = desk.readmit([MARK])
store = desk.load()
piles = shelf._sort_out([moved], store)
check("readmission by fingerprint clears the exclusion",
      dropped == [MARK] and store[desk.EXCLUDED] == []
      and len(piles[desk.BOOK]) == 1,
      "dropped=%s list=%s admitted=%d" % (dropped, store[desk.EXCLUDED],
                                          len(piles[desk.BOOK])))

check("readmission of an identity that is not there changes nothing",
      desk.readmit([MARK]) == [] and desk.load()[desk.EXCLUDED] == [],
      "list=%s" % desk.load()[desk.EXCLUDED])

write_raw({"kinds": {}, "pending": {},
           "excluded": {desk._key(A): {"path": A, "identity": MARK,
                                       "since": 1, "reason": "expired"}}})
store = desk.load()
check("a legacy exclusion record with an identity is converted",
      store[desk.EXCLUDED] == [MARK] and not store[desk.LEGACY_EXCLUDED]
      and raw().get("excluded") == {},
      "list=%s legacy=%s" % (store[desk.EXCLUDED],
                             json.dumps(raw().get("excluded"))))

index_with([catalogue_row(A)])
write_raw({"kinds": {}, "pending": {},
           "excluded": {desk._key(A): {"path": A, "identity": "",
                                       "since": 1, "reason": "expired"}}})
store = desk.load()
check("a legacy record with no identity takes the catalogue fingerprint",
      store[desk.EXCLUDED] == [MARK] and not store[desk.LEGACY_EXCLUDED],
      "list=%s" % store[desk.EXCLUDED])

index_with([])
write_raw({"kinds": {}, "pending": {},
           "excluded": {desk._key(BRANDED): {"path": BRANDED, "identity": "",
                                             "since": 1,
                                             "reason": "expired"}}})
store = desk.load()
record = pdfbook.sumatra_record_from_path(BRANDED) or {}
want = shelf.fingerprint_identity(record.get("fingerprint"))
check("a legacy record with no identity takes the file's own fingerprint",
      bool(want) and store[desk.EXCLUDED] == [want],
      "file fingerprint=%r list=%s" % (record.get("fingerprint"),
                                       store[desk.EXCLUDED]))

index_with([])
write_raw({"kinds": {}, "pending": {},
           "excluded": {desk._key(PLAIN): {"path": PLAIN, "identity": "",
                                           "since": 1, "reason": "expired"}}})
store = desk.load()
left = desk.without_fingerprint(store)
check("a legacy record with no fingerprint is reported and not authoritative",
      store[desk.EXCLUDED] == [] and len(left) == 1
      and left[0]["path"] == PLAIN,
      "list=%s reported=%s" % (store[desk.EXCLUDED], json.dumps(left)))

index_with([catalogue_row(A)])
write_raw({"kinds": {}, "pending": {}, desk.EXCLUDED: []})
desk.hold([{"path": A}], 1)
ripe = desk.ripen(time.time() + desk.DAY + 1)
store = desk.load()
check("expiry with an empty identity reuses the persisted fingerprint",
      store[desk.EXCLUDED] == [MARK] and ripe[0]["identity"] == MARK,
      "list=%s ripe=%s" % (store[desk.EXCLUDED], json.dumps(ripe)))

index_with([])
write_raw({"kinds": {}, "pending": {}, desk.EXCLUDED: []})
desk.hold([{"path": PLAIN}], 1)
ripe = desk.ripen(time.time() + desk.DAY + 1)
store = desk.load()
check("expiry with no fingerprint anywhere adds nothing and says so",
      store[desk.EXCLUDED] == [] and ripe[0]["identity"] == ""
      and desk._key(PLAIN) not in store["kinds"],
      "list=%s ripe=%s" % (store[desk.EXCLUDED], json.dumps(ripe)))

index_with([catalogue_row(r"C:\one\file that is not there.pdf")])
marks = shelf.identities_for_paths([r"C:\one\file that is not there.pdf"])
check("a catalogue fingerprint is reused without opening the file",
      list(marks.values()) == [MARK]
      and not os.path.exists(r"C:\one\file that is not there.pdf"),
      "resolved=%s" % json.dumps(marks))

bad = sum(1 for _, ok in results if not ok)
print("\n%d checks, %d failed" % (len(results), bad))
sys.exit(1 if bad else 0)
