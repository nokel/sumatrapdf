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
    return store;
}

LibraryStore* LibraryStoreParse(Str data) {
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
    if (!str::Eq(rec->kind, StrL("cover"))) {
        return;
    }
    int idx;
    if (thumbs->byId->Get(rec->meta, &idx)) {
        thumbs->recs[idx] = rec;
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
    if (!thumbs->byId->Get(bookId, &idx)) {
        return {};
    }
    return AppendStoreReadPayload(&thumbs->store, thumbs->recs[idx]);
}

bool LibraryThumbsHas(LibraryThumbs* thumbs, Str bookId) {
    if (!thumbs || len(bookId) == 0) {
        return false;
    }
    int idx;
    return thumbs->byId->Get(bookId, &idx);
}

int LibraryThumbsCount(LibraryThumbs* thumbs) {
    return thumbs ? thumbs->recs.len : 0;
}

Str LibraryThumbsError(LibraryThumbs* thumbs) {
    return thumbs ? AppendStoreError(&thumbs->store) : Str{};
}
