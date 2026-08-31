/* Copyright 2026 the SumatraPDF project authors (see AUTHORS file).
   License: Simplified BSD (see COPYING.BSD) */

constexpr int kLibraryScanScope = 2;

struct LibraryScanProgress {
    int found = 0;
    int done = 0;
    int total = 0;
    bool reading = false;
    Str where;
    Str item;
    Str itemAction;
    int itemDone = 0;
    int itemTotal = 0;
    bool itemIndeterminate = false;
};

typedef void (*LibraryScanNotifyCb)(const LibraryScanProgress&, void* ctx);
typedef void (*LibraryScanSnapshotCb)(Str bookJson, void* ctx);
typedef void (*LibraryScanManifestCb)(Str manifestJson, void* ctx);

struct LibraryKnownFile {
    Str path;
    i64 size = 0;
    double mtime = 0;
    bool completed = false;
    bool resumeCandidate = false;
    bool placeholder = false;
    bool indexed = false;
    bool detailsPending = false;
    Str scanJson;
};

constexpr int kLibraryRootAdded = 0;
constexpr int kLibraryRootAddEnabled = 1;
constexpr int kLibraryRootAddPresent = 2;
constexpr int kLibraryRootAddCovered = 3;
constexpr int kLibraryRootAddFailed = 4;

StrVec LibraryStartingRoots();
StrVec LibraryWholeDeviceRoots();
bool LibraryHasExplicitRoots();
StrVec LibraryDiscoveredRoots();
bool LibraryRootIsCovered(Str path);
bool LibraryRootsSeedIfEmpty();
int LibraryRootsAdd(Str path);

Str LibraryScanToJson(const StrVec& roots, const Vec<LibraryKnownFile>& known, bool wholeDevice, LibraryScanNotifyCb cb,
                      void* ctx, volatile LONG* cancel, LibraryScanSnapshotCb snapshotCb = nullptr,
                      void* snapshotCtx = nullptr, LibraryScanManifestCb manifestCb = nullptr,
                      void* manifestCtx = nullptr);

struct LibraryAutoMeta {
    Str title;
    Str titleSource;
    Str author;
    Str authorSource;
    Str yearSource;
    int year = 0;
};

Str LibraryScanOneFileToJson(Str path);

bool LibraryReadAutoMeta(Str bookPath, LibraryAutoMeta& out);
void LibraryAutoMetaFree(LibraryAutoMeta& meta);
