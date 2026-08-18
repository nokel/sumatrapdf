/* Copyright 2022 the SumatraPDF project authors (see AUTHORS file).
   License: Simplified BSD (see COPYING.BSD) */

struct LibraryStore;

constexpr int kLibraryStoreVersion = 1;

#define kLibraryStoreFileName "SumatraLibrary.txt"

LibraryStore* LibraryStoreParse(Str data);
LibraryStore* LibraryStoreLoad(Str path);
Str LibraryStoreSerialize(LibraryStore* store);
bool LibraryStoreSave(LibraryStore* store, Str path);
LibraryStore* LibraryStoreNew();
void LibraryStoreFree(LibraryStore* store);

struct LibraryThumbs;

LibraryThumbs* LibraryThumbsOpen(Str dataDir);
void LibraryThumbsClose(LibraryThumbs* thumbs);
bool LibraryThumbsPut(LibraryThumbs* thumbs, Str bookId, Str pngData);
Str LibraryThumbsGet(LibraryThumbs* thumbs, Str bookId);
bool LibraryThumbsHas(LibraryThumbs* thumbs, Str bookId);
int LibraryThumbsCount(LibraryThumbs* thumbs);
Str LibraryThumbsError(LibraryThumbs* thumbs);
