/* Copyright 2026 the SumatraPDF project authors (see AUTHORS file).
   License: Simplified BSD (see COPYING.BSD) */

constexpr int kLibraryScanScope = 2;

struct LibraryScanProgress {
    int found = 0;
    int done = 0;
    int total = 0;
    bool reading = false;
    Str where;
};

typedef void (*LibraryScanNotifyCb)(const LibraryScanProgress&, void* ctx);

struct LibraryKnownFile {
    Str path;
    i64 size = 0;
    double mtime = 0;
};

StrVec LibraryStartingRoots();
StrVec LibraryWholeDeviceRoots();

Str LibraryScanToJson(const StrVec& roots, const Vec<LibraryKnownFile>& known, bool wholeDevice, LibraryScanNotifyCb cb,
                      void* ctx, const volatile bool* cancel);
