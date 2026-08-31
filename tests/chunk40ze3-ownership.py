import json
import os
import shutil
import sys
import tempfile


cache = tempfile.mkdtemp(prefix="chunk40ze3-")
os.environ["SUMATRA_LIBRARY_CACHE_ROOT"] = cache
os.environ["SUMATRA_TEST_NO_SWEEP"] = "1"
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "Chatterbox-TTS-Extended-main")))

from audiobook.library import shelf
from audiobook.library.server import LibraryState


def item(path):
    return {"path": path, "size": 100, "mtime": 1.0, "pages": 10,
            "title": None, "author": "Test Author", "ink": 1,
            "art": None, "sample": "Test text", "toc": 0,
            "kind": "book"}


def index_for(root, row, generation):
    index = shelf.scan([root], [row])
    index["generation"] = generation
    return index


try:
    root = os.path.join(cache, "books")
    os.makedirs(root)
    stale_row = item(os.path.join(root, "Stale JSON Book.epub"))
    current_row = item(os.path.join(root, "Current SQLite Book.epub"))
    base = index_for(root, stale_row, "base-generation")
    shelf.save_index(base)

    state = LibraryState([root])
    assert state.load()
    writes = 0
    save_index = shelf.save_index

    def counted_save(index):
        global writes
        writes += 1
        return save_index(index)

    shelf.save_index = counted_save
    manifest = [{"path": stale_row["path"], "size": 100, "mtime": 1.0},
                {"path": current_row["path"], "size": 100, "mtime": 1.0}]
    assert state.native_manifest({"roots": [root], "manifest": manifest})[0]
    assert state.native_book({"roots": [root], "book": current_row})[0]
    assert writes == 0

    checkpoint_generation = shelf.load_checkpoint_meta()["generation"]
    resumed = LibraryState([root])
    assert resumed.load()
    resumed_paths = {row["path"] for row in resumed.index["books"]}
    assert current_row["path"] in resumed_paths
    assert stale_row["path"] in resumed_paths
    finalized = resumed.native_index({"roots": [root], "files": [],
                                      "manifest": manifest})
    assert finalized[0], finalized
    assert writes == 1
    assert shelf.load_index()["generation"] == checkpoint_generation
    assert shelf.load_checkpoint_meta() is None
    assert not os.path.exists(shelf.scan_db_path())

    shelf.save_index = save_index
    shelf.clear_checkpoint()
    json_row = item(os.path.join(root, "Current JSON Book.epub"))
    sqlite_row = item(os.path.join(root, "Stale SQLite Book.epub"))
    shelf.save_index(index_for(root, json_row, "json-current"))
    stale_entry = shelf.scan([root], [sqlite_row], finalize=False)["books"][0]
    shelf.save_checkpoint({"version": 1, "in_progress": True,
                           "roots": [os.path.abspath(root)], "scope": 0,
                           "generation": "stale-scan",
                           "base_generation": "json-old",
                           "started": 1.0, "manifest": [sqlite_row]})
    shelf.commit_scan_book(sqlite_row, "books", stale_entry)

    current = LibraryState([root])
    assert current.load()
    current_paths = {row["path"] for row in current.index["books"]}
    assert json_row["path"] in current_paths
    assert sqlite_row["path"] not in current_paths
    assert not current.status()["resume_pending"]
    known_paths = {row["path"] for row in current.known_files([root])}
    assert sqlite_row["path"] not in known_paths

    print(json.dumps({
        "sqlite_current_json_stale": "checkpoint generation replayed",
        "json_current_sqlite_stale": "mismatched checkpoint ignored",
        "per_book_full_catalogue_rewrites": 0,
        "final_full_catalogue_snapshot_writes": writes,
        "final_checkpoint_cleared": True
    }, indent=2))
finally:
    shelf.save_index = globals().get("save_index", shelf.save_index)
    shutil.rmtree(cache, ignore_errors=True)
