import json
import os
import sys
import tempfile
import time

sys.path.insert(0, os.path.abspath(os.path.join(os.getcwd(), "..",
                                                "Chatterbox-TTS-Extended-main")))
from audiobook import pdfbook

pdfbook.CACHE_ROOT = tempfile.mkdtemp(prefix="chunk19rd4-base-")
from audiobook.library import desk, shelf

P = r"C:\one\alpha.pdf"
MARK = "1111aaaa2222bbbb"
OTHER = "3333cccc4444dddd"

with open(desk.store_path(), "w", encoding="utf-8") as f:
    json.dump({"kinds": {}, "pending": {},
               "excluded": {desk._key(P): {"path": P, "identity": MARK,
                                           "size": 10, "mtime": 20,
                                           "since": time.time(),
                                           "reason": "expired"}}}, f, indent=1)

store = desk.load()
with open(desk.store_path(), encoding="utf-8") as f:
    on_disk = json.load(f)

print("store fields: " + json.dumps(sorted(on_disk.keys())))
print("excluded as stored: " + json.dumps(on_disk.get("excluded"))[:200])
print("excludedFingerprints present: " + str("excludedFingerprints" in on_disk))

other = {"path": P, "kind": desk.BOOK, "fingerprint": "fp3:" + OTHER + ":cafe"}
piles = shelf._sort_out([other], store)
print("other content at the excluded path is admitted: "
      + str(len(piles[desk.BOOK]) == 1))
same = {"path": r"D:\elsewhere\renamed.pdf", "kind": desk.BOOK,
        "fingerprint": "fp3:" + MARK + ":cafe"}
piles = shelf._sort_out([same], store)
print("the same content elsewhere is refused: "
      + str(len(piles[desk.BOOK]) == 0))
