/* Copyright 2022 the SumatraPDF project authors (see AUTHORS file).
   License: GPLv3 */

#include "base/Base.h"
#include "base/File.h"
#include "base/Dict.h"
#include "base/AppendStore.h"
#include "base/SettingsUtil.h"

#define INCLUDE_LIBRARYSTORE_METADATA
#include "LibraryData.h"

#include "LibraryStore.h"

LibraryStore* LibraryStoreNew() {
    auto* store = (LibraryStore*)DeserializeStruct(&gLibraryStoreInfo, {});
    if (store) {
        store->version = kLibraryStoreVersion;
    }
    return store;
}

LibraryStore* LibraryStoreParse(Str data) {
    if (len(data) == 0) {
        return LibraryStoreNew();
    }
    auto* store = (LibraryStore*)DeserializeStruct(&gLibraryStoreInfo, data);
    if (!store) {
        return nullptr;
    }
    // an index written by a different version describes books we can't read;
    // the caller rescans instead of showing a half-understood library
    if (store->version != kLibraryStoreVersion) {
        LibraryStoreFree(store);
        return nullptr;
    }
    return store;
}

LibraryStore* LibraryStoreLoad(Str path) {
    if (!file::Exists(path)) {
        return nullptr;
    }
    Str data = file::ReadFile(path);
    LibraryStore* store = LibraryStoreParse(data);
    str::Free(data);
    return store;
}

Str LibraryStoreSerialize(LibraryStore* store) {
    if (!store) {
        return {};
    }
    store->version = kLibraryStoreVersion;
    return SerializeStruct(&gLibraryStoreInfo, store);
}

bool LibraryStoreSave(LibraryStore* store, Str path) {
    Str data = LibraryStoreSerialize(store);
    if (len(data) == 0) {
        return false;
    }
    bool ok = file::WriteFile(path, data);
    str::Free(data);
    return ok;
}

void LibraryStoreFree(LibraryStore* store) {
    if (!store) {
        return;
    }
    FreeStruct(&gLibraryStoreInfo, store);
}

bool LibraryRoamedMerge(LibraryRoamed* row, i64 lastReadAt, i64 timeSpentMs, i64 openCount, i64* addTimeMs,
                        i64* addOpens) {
    if (!row || !addTimeMs || !addOpens) {
        return false;
    }
    *addTimeMs = timeSpentMs - row->timeSpentMs;
    *addOpens = openCount - row->openCount;
    if (*addTimeMs <= 0 && *addOpens <= 0 && lastReadAt <= row->lastReadAt) {
        return false;
    }
    row->lastReadAt = std::max(row->lastReadAt, lastReadAt);
    row->timeSpentMs = std::max(row->timeSpentMs, timeSpentMs);
    row->openCount = std::max(row->openCount, openCount);
    return true;
}

// Thumbnails live in an AppendStore next to the index: the PNGs go in the data
// file (binary, so never the inline mode, which puts the bytes in the text
// index) and the book's id is the record's meta. It is append-only, so a cover
// that is regenerated is appended again and the later record wins.

constexpr int kThumbsMapInitialSize = 1024;

struct LibraryThumbs {
    AppendStore store;
    Vec<AppendStoreRecord*> recs;
    dict::MapStrToInt* byId = nullptr;
    bool opened = false;
};

static void OnThumbRecord(AppendStoreRecord* rec, Str, void* userData) {
    auto* thumbs = (LibraryThumbs*)userData;
    bool gone = str::Eq(rec->kind, StrL("coverGone"));
    if (!gone && !str::Eq(rec->kind, StrL("cover"))) {
        return;
    }
    int idx;
    if (thumbs->byId->Get(rec->meta, &idx)) {
        thumbs->recs[idx] = gone ? nullptr : rec;
        return;
    }
    if (gone) {
        return;
    }
    thumbs->recs.Append(rec);
    thumbs->byId->Insert(rec->meta, thumbs->recs.len - 1);
}

LibraryThumbs* LibraryThumbsOpen(Str dataDir) {
    auto* thumbs = new LibraryThumbs();
    thumbs->byId = new dict::MapStrToInt(kThumbsMapInitialSize);
    thumbs->store.dataDir = dataDir;
    thumbs->store.indexFileName = StrL("SumatraLibraryThumbs.txt");
    thumbs->store.dataFileName = StrL("SumatraLibraryThumbs.dat");
    thumbs->store.onRecord = OnThumbRecord;
    thumbs->store.userData = thumbs;
    if (!AppendStoreOpen(&thumbs->store)) {
        logf("LibraryThumbsOpen: %s\n", AppendStoreError(&thumbs->store));
        delete thumbs->byId;
        delete thumbs;
        return nullptr;
    }
    thumbs->opened = true;
    return thumbs;
}

void LibraryThumbsClose(LibraryThumbs* thumbs) {
    if (!thumbs) {
        return;
    }
    if (thumbs->opened) {
        AppendStoreClose(&thumbs->store);
    }
    delete thumbs->byId;
    delete thumbs;
}

bool LibraryThumbsPut(LibraryThumbs* thumbs, Str bookId, Str pngData) {
    if (!thumbs || len(bookId) == 0 || len(pngData) == 0) {
        return false;
    }
    AppendStoreAppendOptions opts;
    opts.mode = AppendStoreMode::DataFile;
    opts.kind = StrL("cover");
    opts.meta = bookId;
    opts.data = pngData;
    AppendStoreRecord* rec = nullptr;
    if (!AppendStoreAppend(&thumbs->store, opts, &rec)) {
        return false;
    }
    OnThumbRecord(rec, {}, thumbs);
    return true;
}

Str LibraryThumbsGet(LibraryThumbs* thumbs, Str bookId) {
    if (!thumbs || len(bookId) == 0) {
        return {};
    }
    int idx;
    if (!thumbs->byId->Get(bookId, &idx) || !thumbs->recs[idx]) {
        return {};
    }
    return AppendStoreReadPayload(&thumbs->store, thumbs->recs[idx]);
}

bool LibraryThumbsHas(LibraryThumbs* thumbs, Str bookId) {
    if (!thumbs || len(bookId) == 0) {
        return false;
    }
    int idx;
    return thumbs->byId->Get(bookId, &idx) && thumbs->recs[idx] != nullptr;
}

int LibraryThumbsCount(LibraryThumbs* thumbs) {
    if (!thumbs) {
        return 0;
    }
    int n = 0;
    for (int i = 0; i < thumbs->recs.len; i++) {
        if (thumbs->recs[i]) {
            n++;
        }
    }
    return n;
}

bool LibraryThumbsRemove(LibraryThumbs* thumbs, Str bookId) {
    if (!thumbs || len(bookId) == 0) {
        return false;
    }
    int idx;
    if (!thumbs->byId->Get(bookId, &idx) || !thumbs->recs[idx]) {
        return false;
    }
    AppendStoreAppendOptions opts;
    opts.mode = AppendStoreMode::Inline;
    opts.kind = StrL("coverGone");
    opts.meta = bookId;
    AppendStoreRecord* rec = nullptr;
    if (!AppendStoreAppend(&thumbs->store, opts, &rec)) {
        return false;
    }
    OnThumbRecord(rec, {}, thumbs);
    return true;
}

Str LibraryThumbsError(LibraryThumbs* thumbs) {
    return thumbs ? AppendStoreError(&thumbs->store) : Str{};
}

// A book's fingerprint is a pure function of the file's bytes, but computing
// it opens the document, extracts the text of every page and MD5s every
// stream object in the xref. That is seconds per book, and the sidecar layer
// recomputes it every time it reads a record. This store remembers the answer
// keyed by the file's size and modification time, so an unchanged file is
// never parsed twice.

constexpr int kFingerprintsMapInitialSize = 1024;

struct LibraryFingerprintEntry {
    i64 fileSize = 0;
    i64 modifiedTicks = 0;
    Str fingerprint;
};

struct LibraryFingerprints {
    AppendStore store;
    Vec<LibraryFingerprintEntry> entries;
    dict::MapStrToInt* byPath = nullptr;
    bool opened = false;
};

static bool ParseFingerprintPayload(Str data, LibraryFingerprintEntry& out) {
    StrVec parts;
    Split(&parts, data, StrL(" "), true);
    if (len(parts) != 3) {
        return false;
    }
    out.fileSize = ParseInt64(parts[0]);
    out.modifiedTicks = ParseInt64(parts[1]);
    if (out.fileSize < 0 || out.modifiedTicks <= 0 || len(parts[2]) == 0) {
        return false;
    }
    // parts owns its strings and dies with this call, so the entry keeps a copy
    out.fingerprint = str::Dup(parts[2]);
    return true;
}

static void OnFingerprintRecord(AppendStoreRecord* rec, Str data, void* userData) {
    auto* prints = (LibraryFingerprints*)userData;
    if (!str::Eq(rec->kind, StrL("fingerprint"))) {
        return;
    }
    LibraryFingerprintEntry entry;
    if (!ParseFingerprintPayload(data, entry)) {
        return;
    }
    int idx;
    if (prints->byPath->Get(rec->meta, &idx)) {
        str::Free(prints->entries[idx].fingerprint);
        prints->entries[idx] = entry;
        return;
    }
    prints->entries.Append(entry);
    prints->byPath->Insert(rec->meta, prints->entries.len - 1);
}

LibraryFingerprints* LibraryFingerprintsOpen(Str dataDir) {
    auto* prints = new LibraryFingerprints();
    prints->byPath = new dict::MapStrToInt(kFingerprintsMapInitialSize);
    prints->store.dataDir = dataDir;
    prints->store.indexFileName = StrL("SumatraLibraryFingerprints.txt");
    prints->store.dataFileName = StrL("SumatraLibraryFingerprints.dat");
    prints->store.onRecord = OnFingerprintRecord;
    prints->store.userData = prints;
    if (!AppendStoreOpen(&prints->store)) {
        logf("LibraryFingerprintsOpen: %s\n", AppendStoreError(&prints->store));
        delete prints->byPath;
        delete prints;
        return nullptr;
    }
    prints->opened = true;
    return prints;
}

void LibraryFingerprintsClose(LibraryFingerprints* prints) {
    if (!prints) {
        return;
    }
    if (prints->opened) {
        AppendStoreClose(&prints->store);
    }
    for (LibraryFingerprintEntry& e : prints->entries) {
        str::Free(e.fingerprint);
    }
    delete prints->byPath;
    delete prints;
}

Str LibraryFingerprintsGet(LibraryFingerprints* prints, Str bookPath, i64 fileSize, i64 modifiedTicks) {
    if (!prints || len(bookPath) == 0 || modifiedTicks <= 0) {
        return {};
    }
    int idx;
    if (!prints->byPath->Get(bookPath, &idx)) {
        return {};
    }
    const LibraryFingerprintEntry& e = prints->entries[idx];
    if (e.fileSize != fileSize || e.modifiedTicks != modifiedTicks) {
        return {};
    }
    return str::Dup(e.fingerprint);
}

bool LibraryFingerprintsPut(LibraryFingerprints* prints, Str bookPath, i64 fileSize, i64 modifiedTicks,
                            Str fingerprint) {
    if (!prints || len(bookPath) == 0 || modifiedTicks <= 0 || len(fingerprint) == 0) {
        return false;
    }
    if (str::ContainsChar(bookPath, '\n') || str::ContainsChar(bookPath, '\r')) {
        return false;
    }
    int idx;
    if (prints->byPath->Get(bookPath, &idx)) {
        const LibraryFingerprintEntry& have = prints->entries[idx];
        if (have.fileSize == fileSize && have.modifiedTicks == modifiedTicks &&
            str::Eq(have.fingerprint, fingerprint)) {
            return true;
        }
    }
    TempStr payload = str::FormatTemp("%lld %lld %s", (long long)fileSize, (long long)modifiedTicks, fingerprint);
    AppendStoreAppendOptions opts;
    opts.mode = AppendStoreMode::Inline;
    opts.kind = StrL("fingerprint");
    opts.meta = bookPath;
    opts.data = payload;
    AppendStoreRecord* rec = nullptr;
    if (!AppendStoreAppend(&prints->store, opts, &rec)) {
        return false;
    }
    OnFingerprintRecord(rec, payload, prints);
    return true;
}

int LibraryFingerprintsCount(LibraryFingerprints* prints) {
    return prints ? prints->entries.len : 0;
}
