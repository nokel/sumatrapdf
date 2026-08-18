/* Copyright 2022 the SumatraPDF project authors (see AUTHORS file).
   License: Simplified BSD (see COPYING.BSD) */

#include "base/Base.h"
#include "base/File.h"
#include "base/Dict.h"
#include "base/AppendStore.h"
#include "base/SettingsUtil.h"

#include "LibraryData.h"
#include "LibraryStore.h"

// must be last due to assert() over-write
#include "base/UtAssert.h"

static LibraryBook* AddBook(LibraryStore* store, Str id, Str title) {
    auto* book = new LibraryBook();
    book->id = str::Dup(id);
    book->title = str::Dup(title);
    book->libraryOutOf = new Vec<LibraryOutOf*>();
    store->libraryBooks->Append(book);
    return book;
}

static LibraryBook* FindBook(LibraryStore* store, Str id) {
    for (LibraryBook* b : *store->libraryBooks) {
        if (str::Eq(b->id, id)) {
            return b;
        }
    }
    return nullptr;
}

static void LibraryStoreParseTest() {
    // an empty file is a valid, empty library, not a corrupt one
    {
        LibraryStore* store = LibraryStoreParse({});
        utassert(store != nullptr);
        utassert(store->version == kLibraryStoreVersion);
        utassert(store->libraryBooks->len == 0);
        utassert(store->librarySeries->len == 0);
        LibraryStoreFree(store);
    }

    // an index from a version we don't understand is discarded, so the caller
    // rescans instead of showing a half-read library
    {
        LibraryStore* store = LibraryStoreParse(StrL("Version = 2\n"));
        utassert(store == nullptr);
    }
    {
        LibraryStore* store = LibraryStoreParse(StrL("Version = 0\n"));
        utassert(store == nullptr);
    }
}

static void LibraryStoreRoundTripTest() {
    LibraryStore* store = LibraryStoreNew();
    utassert(store != nullptr);
    store->scannedAtMs = 1750000000000LL;

    LibraryBook* dune = AddBook(store, StrL("id-dune"), StrL("Dune"));
    dune->author = str::Dup(StrL("Frank Herbert"));
    dune->series = str::Dup(StrL("Dune"));
    dune->seriesKey = str::Dup(StrL("k-dune"));
    dune->path = str::Dup(StrL("C:\\books\\Dune.pdf"));
    dune->ext = str::Dup(StrL("pdf"));
    dune->pages = 412;
    dune->year = 1965;
    dune->volume = 1;
    dune->bookNlp = true;
    dune->cover = true;

    auto* outOf = new LibraryOutOf();
    outOf->key = str::Dup(StrL("k-dune"));
    outOf->name = str::Dup(StrL("Dune"));
    dune->libraryOutOf->Append(outOf);

    // a book with no series and no OutOf entries: the common case for a loose
    // file, and the one that would break if an empty array were skipped
    LibraryBook* loose = AddBook(store, StrL("id-loose"), StrL("Notes"));
    loose->pages = 3;

    // values SquareTree has to escape to survive: a leading and a trailing
    // space, the escape character itself, and an embedded newline
    LibraryBook* odd = AddBook(store, StrL("id-odd"), StrL(" spaced title "));
    odd->author = str::Dup(StrL("a$b"));
    odd->wiki = str::Dup(StrL("line1\nline2"));

    auto* series = new LibrarySeries();
    series->key = str::Dup(StrL("k-dune"));
    series->name = str::Dup(StrL("Dune"));
    series->author = str::Dup(StrL("Frank Herbert"));
    series->books = 1;
    series->bookNlp = 1;
    series->depth = 0;
    store->librarySeries->Append(series);

    Str data = LibraryStoreSerialize(store);
    utassert(len(data) > 0);

    LibraryStore* back = LibraryStoreParse(data);
    utassert(back != nullptr);
    utassert(back->version == kLibraryStoreVersion);
    utassert(back->scannedAtMs == 1750000000000LL);
    utassert(back->libraryBooks->len == 3);
    utassert(back->librarySeries->len == 1);

    LibraryBook* duneBack = FindBook(back, StrL("id-dune"));
    utassert(duneBack != nullptr);
    utassert(str::Eq(duneBack->title, StrL("Dune")));
    utassert(str::Eq(duneBack->author, StrL("Frank Herbert")));
    utassert(str::Eq(duneBack->path, StrL("C:\\books\\Dune.pdf")));
    utassert(duneBack->pages == 412);
    utassert(duneBack->year == 1965);
    utassert(duneBack->volume == 1);
    utassert(duneBack->bookNlp);
    utassert(duneBack->cover);
    utassert(duneBack->libraryOutOf->len == 1);
    utassert(str::Eq((*duneBack->libraryOutOf)[0]->name, StrL("Dune")));

    LibraryBook* looseBack = FindBook(back, StrL("id-loose"));
    utassert(looseBack != nullptr);
    utassert(looseBack->pages == 3);
    utassert(looseBack->libraryOutOf->len == 0);
    utassert(len(looseBack->seriesKey) == 0);
    utassert(!looseBack->bookNlp);

    LibraryBook* oddBack = FindBook(back, StrL("id-odd"));
    utassert(oddBack != nullptr);
    utassert(str::Eq(oddBack->title, StrL(" spaced title ")));
    utassert(str::Eq(oddBack->author, StrL("a$b")));
    utassert(str::Eq(oddBack->wiki, StrL("line1\nline2")));

    utassert(str::Eq((*back->librarySeries)[0]->name, StrL("Dune")));
    utassert((*back->librarySeries)[0]->books == 1);

    str::Free(data);
    LibraryStoreFree(store);
    LibraryStoreFree(back);
}

static void LibraryStoreFileTest() {
    Str dir = str::Dup(GetTempFilePathTemp(StrL("libstore")));
    utassert(dir.len > 0);
    file::Delete(dir);
    utassert(dir::Create(dir));
    TempStr path = path::JoinTemp(dir, StrL(kLibraryStoreFileName));

    // nothing written yet: the caller has to be able to tell "no index" from
    // "an empty index"
    utassert(LibraryStoreLoad(path) == nullptr);

    LibraryStore* store = LibraryStoreNew();
    AddBook(store, StrL("id-1"), StrL("One"));
    utassert(LibraryStoreSave(store, path));
    LibraryStoreFree(store);

    LibraryStore* back = LibraryStoreLoad(path);
    utassert(back != nullptr);
    utassert(back->libraryBooks->len == 1);
    utassert(str::Eq((*back->libraryBooks)[0]->title, StrL("One")));
    LibraryStoreFree(back);

    utassert(dir::RemoveAll(dir));
    str::Free(dir);
}

// a PNG starts with a NUL-containing signature and is full of NUL bytes, so
// the thumbnails have to survive as bytes, not as a C string
static Str MakeFakePng(char tag) {
    u8 buf[64];
    memset(buf, 0, sizeof(buf));
    buf[0] = 0x89;
    buf[1] = 'P';
    buf[2] = 'N';
    buf[3] = 'G';
    buf[4] = '\r';
    buf[5] = '\n';
    buf[6] = 0x1a;
    buf[7] = '\n';
    buf[20] = (u8)tag;
    buf[63] = (u8)tag;
    return str::Dup(Str{(char*)buf, (int)sizeof(buf)});
}

static void LibraryThumbsTest() {
    Str dir = str::Dup(GetTempFilePathTemp(StrL("libthumbs")));
    utassert(dir.len > 0);
    file::Delete(dir);
    utassert(dir::Create(dir));

    LibraryThumbs* thumbs = LibraryThumbsOpen(dir);
    utassert(thumbs != nullptr);
    utassert(LibraryThumbsCount(thumbs) == 0);
    utassert(!LibraryThumbsHas(thumbs, StrL("id-1")));

    Str missing = LibraryThumbsGet(thumbs, StrL("id-1"));
    utassert(missing.s == nullptr);

    Str pngA = MakeFakePng('A');
    Str pngB = MakeFakePng('B');
    utassert(LibraryThumbsPut(thumbs, StrL("id-1"), pngA));
    utassert(LibraryThumbsPut(thumbs, StrL("id-2"), pngB));
    utassert(LibraryThumbsCount(thumbs) == 2);
    utassert(LibraryThumbsHas(thumbs, StrL("id-1")));

    // an empty id or empty data is a caller bug, not something to store
    utassert(!LibraryThumbsPut(thumbs, StrL(""), pngA));
    utassert(!LibraryThumbsPut(thumbs, StrL("id-3"), {}));
    utassert(LibraryThumbsCount(thumbs) == 2);

    Str gotA = LibraryThumbsGet(thumbs, StrL("id-1"));
    utassert(gotA.len == pngA.len);
    utassert(memcmp(gotA.s, pngA.s, (size_t)pngA.len) == 0);
    str::Free(gotA);

    LibraryThumbsClose(thumbs);

    // reopening replays the index: the covers are still there, still byte-exact
    LibraryThumbs* reopened = LibraryThumbsOpen(dir);
    utassert(reopened != nullptr);
    utassert(LibraryThumbsCount(reopened) == 2);
    Str gotB = LibraryThumbsGet(reopened, StrL("id-2"));
    utassert(gotB.len == pngB.len);
    utassert(memcmp(gotB.s, pngB.s, (size_t)pngB.len) == 0);
    str::Free(gotB);

    // the store is append-only, so a regenerated cover is appended again and
    // the later record has to win, both now and after another reopen
    Str pngC = MakeFakePng('C');
    utassert(LibraryThumbsPut(reopened, StrL("id-1"), pngC));
    utassert(LibraryThumbsCount(reopened) == 2);
    Str gotC = LibraryThumbsGet(reopened, StrL("id-1"));
    utassert(gotC.len == pngC.len);
    utassert(memcmp(gotC.s, pngC.s, (size_t)pngC.len) == 0);
    str::Free(gotC);
    LibraryThumbsClose(reopened);

    LibraryThumbs* again = LibraryThumbsOpen(dir);
    utassert(again != nullptr);
    utassert(LibraryThumbsCount(again) == 2);
    Str gotC2 = LibraryThumbsGet(again, StrL("id-1"));
    utassert(gotC2.len == pngC.len);
    utassert(memcmp(gotC2.s, pngC.s, (size_t)pngC.len) == 0);
    str::Free(gotC2);
    LibraryThumbsClose(again);

    str::Free(pngA);
    str::Free(pngB);
    str::Free(pngC);
    utassert(dir::RemoveAll(dir));
    str::Free(dir);
}

void LibraryStoreTest() {
    LibraryStoreParseTest();
    LibraryStoreRoundTripTest();
    LibraryStoreFileTest();
    LibraryThumbsTest();
}
