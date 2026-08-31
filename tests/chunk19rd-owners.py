import json, os, sys, time, urllib.request, urllib.error
sys.path.insert(0, os.getcwd())
from audiobook.library import desk, shelf

PORT = 7863
TARGET = r"C:\Users\Nokel\Documents\ebooks\manga_novels\Animorphs\Animorphs\02-The Visitor.pdf"
IGNORED = r"C:\Users\Nokel\Documents\ebooks\manga_novels\Animorphs\Animorphs\03-The Encounter.pdf"
STALE = sys.argv[1]
results = []


def check(name, ok, detail):
    results.append((name, ok, detail))
    print(("  PASS  " if ok else "  FAIL  ") + name)
    print("        " + detail)


def piles():
    index = shelf.load_index()
    out = {}
    for name in ("books", "documents", "ignored"):
        for row in index.get(name) or ():
            for path in [row.get("path")] + [e.get("path") for e in row.get("editions") or ()]:
                if path:
                    out[os.path.abspath(path).lower()] = name
    return index, out


def where(seen, path):
    return seen.get(os.path.abspath(path).lower(), "absent")


def service_pile(path):
    key = os.path.abspath(path).lower()
    try:
        with urllib.request.urlopen(
                "http://127.0.0.1:%d/library?limit=5000" % PORT, timeout=60) as r:
            lib = json.loads(r.read().decode("utf-8"))
    except Exception as exc:
        return "unreachable: %r" % (exc,)
    for row in lib.get("books") or ():
        for one in [row.get("path")] + [e.get("path")
                                        for e in row.get("editions") or ()]:
            if one and os.path.abspath(one).lower() == key:
                return "books"
    return "absent"


def post(path, body):
    req = urllib.request.Request("http://127.0.0.1:%d%s" % (PORT, path),
                                 data=json.dumps(body).encode("utf-8"),
                                 headers={"Content-Type": "application/json"},
                                 method="POST")
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return r.status, json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode("utf-8"))


print("--- 11. a permanent Ignore never becomes an exclusion ---")
store = desk.load()
before_excluded = len(store["excluded"])
ripe = desk.ripen(time.time() + 100 * 365 * 86400)
store = desk.load()
check("ripen 100 years ahead adds no exclusion for an ignored file",
      ripe == [] and len(store["excluded"]) == before_excluded
      and desk.told(store, IGNORED) == desk.IGNORED,
      "ripen returned %r; %s is still %r; exclusions %d -> %d"
      % (ripe, os.path.basename(IGNORED), desk.told(store, IGNORED),
         before_excluded, len(store["excluded"])))

print("--- 9a. a stale /book publication cannot override the exclusion ---")
payload = json.load(open(STALE, encoding="utf-8"))
index, seen = piles()
was = where(seen, TARGET)
status, answer = post("/book", payload)
index, seen = piles()
now = where(seen, TARGET)
check("POST /book with the real stale scan result is refused",
      not answer.get("ok") and now == "absent",
      "the scan said kind=%r pages=%s; the service answered %d %r; the pile went %s -> %s"
      % (payload["book"].get("kind"), payload["book"].get("pages"), status,
         answer.get("message"), was, now))

print("--- 9b. a stale scan-journal entry cannot override the exclusion ---")
roots = payload["roots"]
item = payload["book"]
kept = desk.load()
saved = dict(kept["excluded"])
kept["excluded"] = {}
desk.save(kept)
partial = shelf.scan(roots, [item], previous=shelf.load_index(), finalize=False)
entry = (partial.get("books") or [None])[0]
back = desk.load()
back["excluded"] = saved
desk.save(back)
made = entry is not None and "02-The Visitor" in (entry or {}).get("path", "")
index = shelf.load_index()
applied = shelf.apply_scan_entry(index, roots, "books", entry) if made else None
index2, seen2 = piles()
check("replaying the journal entry made before the exclusion is refused",
      made and applied is False and where(seen2, TARGET) == "absent",
      "the entry was built while the file was still admissible (title %r); "
      "apply_scan_entry returned %r; the pile is %s"
      % ((entry or {}).get("title"), applied, where(seen2, TARGET)))

print("--- 10. the physical file was never touched ---")
check("both files are still on disk, unchanged in size",
      os.path.exists(TARGET) and os.path.exists(IGNORED)
      and os.path.getsize(TARGET) == payload["book"]["size"],
      "%s is %d bytes; %s exists=%s"
      % (os.path.basename(TARGET), os.path.getsize(TARGET),
         os.path.basename(IGNORED), os.path.exists(IGNORED)))

print("--- 12. only an explicit import clears the exclusion ---")
status, answer = post("/kind", {"kind": "book", "paths": [TARGET]})
index, seen = piles()
check("a plain /kind book on an excluded file does not bring it back",
      not answer.get("ok") and where(seen, TARGET) == "absent"
      and desk.is_excluded(desk.load(), TARGET),
      "the service answered %d %r; the pile is %s; still excluded=%s"
      % (status, answer.get("message"), where(seen, TARGET),
         desk.is_excluded(desk.load(), TARGET)))

status, answer = post("/kind", {"kind": "book", "paths": [TARGET], "import": True})
store = desk.load()
check("a manual import clears the exclusion and records user authority",
      answer.get("ok") and answer.get("readmitted") == 1
      and not desk.is_excluded(store, TARGET)
      and desk.told(store, TARGET) == desk.BOOK,
      "the service answered %d %r readmitted=%s; still excluded=%s; "
      "the user record now says %r"
      % (status, answer.get("message"), answer.get("readmitted"),
         desk.is_excluded(store, TARGET), desk.told(store, TARGET)))

status, answer = post("/book", payload)
serving = service_pile(TARGET)
check("after the import the same publication is admitted as a book",
      answer.get("ok") and serving == "books",
      "the service answered %d %r; the running Library now serves it from %s"
      % (status, answer.get("message"), serving))

bad = [name for name, ok, _ in results if not ok]
print("\n%d checks, %d failed" % (len(results), len(bad)))
sys.exit(1 if bad else 0)
