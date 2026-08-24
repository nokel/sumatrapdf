/* Copyright 2022 the SumatraPDF project authors (see AUTHORS file).
   License: Simplified BSD (see COPYING.BSD) */

struct LibraryStore;
struct LibraryRoamed;

// v2: added TitleSource / AuthorSource / YearSource to LibraryBook so the
//     "user" override marker survives cold start. Older files describe
//     books that look like they have no user source, so we discard them
//     and let the next scan rebuild from scratch.
// v3: added SeriesSource to LibraryBook, for the same reason: without it
//     a user-edited series comes back from a cold start looking
//     auto-detected and the next generator run is free to replace it.
constexpr int kLibraryStoreVersion = 3;

#define kLibraryStoreFileName "SumatraLibrary.txt"

LibraryStore* LibraryStoreParse(Str data);
LibraryStore* LibraryStoreLoad(Str path);
Str LibraryStoreSerialize(LibraryStore* store);
bool LibraryStoreSave(LibraryStore* store, Str path);
LibraryStore* LibraryStoreNew();
void LibraryStoreFree(LibraryStore* store);
bool LibraryRoamedMerge(LibraryRoamed* row, i64 lastReadAt, i64 timeSpentMs, i64 openCount, i64* addTimeMs,
                        i64* addOpens);

struct LibraryThumbs;

LibraryThumbs* LibraryThumbsOpen(Str dataDir);
void LibraryThumbsClose(LibraryThumbs* thumbs);
bool LibraryThumbsPut(LibraryThumbs* thumbs, Str bookId, Str pngData);
Str LibraryThumbsGet(LibraryThumbs* thumbs, Str bookId);
bool LibraryThumbsHas(LibraryThumbs* thumbs, Str bookId);
bool LibraryThumbsRemove(LibraryThumbs* thumbs, Str bookId);
int LibraryThumbsCount(LibraryThumbs* thumbs);
Str LibraryThumbsError(LibraryThumbs* thumbs);
