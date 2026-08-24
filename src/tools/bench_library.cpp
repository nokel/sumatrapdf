/* Copyright 2026 the SumatraPDF project authors (see AUTHORS.BSD).
   License: GPLv3 */

// Standalone bench for the BookBlob / PdfSidecar / LibrarySidecar code path.
//
// Replays what SyncEmbeddedRecords() does in src/LibraryPage.cpp on every
// SumatraPDF startup when the LibraryHome is enabled, but without any GUI
// or SumatraControl pipe. That is the only way to get reliable timings
// because the real SumatraPDF stalls before the control pipe is created
// when LibraryHome is on, so the in-process TestBenchSync (id 62) can
// never be reached.
//
// Usage:
//   bench_library <corpus-dir> [passes]
//
// <corpus-dir> must contain a SumatraLibrary.txt. Each pass replays the
// SyncEmbeddedRecords hot path over the books in that file:
//   1. iterate every book
//   2. call LibrarySidecarHas(path) to test for an existing sidecar
//   3. (cold pass only) call LibrarySidecarWriteMetadata(path, ...)
//      for books that don't have a sidecar yet, to simulate the
//      migration of a fresh library
//
// Prints a single perf report to stdout on a line beginning with "OK"
// (so the test driver can parse it).

#include "base/Base.h"
#include "base/File.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "BookBlob.h"
#include "LibraryData.h"
#include "LibraryStore.h"
#include "LibrarySidecar.h"
#include "PdfSidecar.h"

static int CmpPaths(const void* a, const void* b) {
    auto* sa = (Str*)a;
    auto* sb = (Str*)b;
    if (sa->len != sb->len) {
        return sa->len < sb->len ? -1 : 1;
    }
    return memcmp(sa->s, sb->s, sa->len);
}

struct BookEntry {
    Str path;
    Str title;
    Str author;
    Str series;
    Str seriesParent;
    Str genre;
    Str subgenre;
    Str tags;
    Str partitions;
    int seriesIndex = 0;
    int year = 0;
    int pages = 0;
};

// Load <corpus-dir>\SumatraLibrary.txt and project it into a flat list of
// books with the metadata fields SyncEmbeddedRecords needs.
static void LoadCorpus(Str corpusDir, Vec<BookEntry>& out) {
    TempStr storePath = path::JoinTemp(corpusDir, StrL("SumatraLibrary.txt"));
    LibraryStore* store = LibraryStoreLoad(storePath);
    if (!store) {
        logf("bench_library: no SumatraLibrary.txt at %s\n", storePath);
        return;
    }
    Vec<LibraryBook*>* books = store->libraryBooks;
    if (!books) {
        LibraryStoreFree(store);
        return;
    }
    for (int i = 0; i < books->len; i++) {
        LibraryBook* b = books->els[i];
        if (!b || len(b->path) == 0) {
            continue;
        }
        BookEntry e;
        e.path = str::Dup(b->path);
        e.title = str::Dup(b->title);
        e.author = str::Dup(b->author);
        e.series = str::Dup(b->series);
        e.seriesParent = str::Dup(b->seriesParent);
        e.genre = str::Dup(b->genre);
        e.subgenre = str::Dup(b->subgenre);
        e.tags = str::Dup(b->tags);
        e.partitions = str::Dup(b->keys);
        e.seriesIndex = b->volume;
        e.year = b->year;
        e.pages = b->pages;
        out.Append(e);
    }
    LibraryStoreFree(store);
}

static void FreeCorpus(Vec<BookEntry>& books) {
    for (BookEntry& e : books) {
        str::Free(e.path);
        str::Free(e.title);
        str::Free(e.author);
        str::Free(e.series);
        str::Free(e.seriesParent);
        str::Free(e.genre);
        str::Free(e.subgenre);
        str::Free(e.tags);
        str::Free(e.partitions);
    }
}

struct PassResult {
    int books = 0;
    int missingFile = 0;
    int hasSidecar = 0;
    int wroteSidecar = 0;
    int writeFailed = 0;
    u64 durationMs = 0;
    int pdfCtx = 0;
    int pdfOpen = 0;
    int blobDecode = 0;
    int coverDecode = 0;
};

// One bench pass. If mode is "write", write sidecars for any book that does
// not already have one (mimics a fresh-library migration). If mode is
// "check", just walk the corpus and check for sidecars. If mode is "decode",
// also explicitly decode the blob/cover from every book that has one — this
// is the path the OLD PdfSidecarHasCover / PdfSidecarHasBlob implementations
// used to take (regressed in the Android port), so timing "decode" is the
// before-number and timing "check" is the after-number.
static PassResult RunPass(Vec<BookEntry>& books, const char* mode, const char* label) {
    PassResult r;
    r.books = books.len;
    int ctxBefore = 0, openBefore = 0, blobBefore = 0, coverBefore = 0;
    PdfSidecarPerfCounters(&ctxBefore, &openBefore, &blobBefore, &coverBefore);
    bool doWrite = str::Eq(mode, StrL("write"));
    bool doDecode = str::Eq(mode, StrL("decode"));
    u64 start = GetTickCount64();
    for (BookEntry& e : books) {
        if (len(e.path) == 0) {
            continue;
        }
        if (!file::Exists(e.path)) {
            r.missingFile++;
            continue;
        }
        bool has = LibrarySidecarHas(e.path);
        if (has) {
            r.hasSidecar++;
            if (doDecode) {
                // Replicate the OLD path: open the PDF, decode the blob
                // stream and the cover stream just to learn they exist. The
                // decoded data is thrown away. This is the cost the cheap
                // SidecarHasStream walks replaced.
                Vec<u8> blob;
                PdfSidecarReadBlob(e.path, blob);
                blob.Reset();
                Str format;
                Vec<u8> cover;
                PdfSidecarReadCover(e.path, &format, cover);
                str::Free(format);
                cover.Reset();
            }
            continue;
        }
        if (doWrite) {
            BlobStats stats;
            stats.lastReadAt = 0;
            stats.timeSpentMs = 0;
            stats.openCount = 0;
            stats.pageNo = 0;
            stats.percentRead = 0;
            bool ok = LibrarySidecarWriteMetadata(e.path, e.title, e.author, e.series, e.seriesParent, e.genre,
                                                  e.subgenre, e.tags, e.partitions, e.seriesIndex, e.year, e.pages,
                                                  &stats);
            if (ok) {
                r.wroteSidecar++;
            } else {
                r.writeFailed++;
            }
        }
    }
    r.durationMs = GetTickCount64() - start;
    int ctxAfter = 0, openAfter = 0, blobAfter = 0, coverAfter = 0;
    PdfSidecarPerfCounters(&ctxAfter, &openAfter, &blobAfter, &coverAfter);
    r.pdfCtx = ctxAfter - ctxBefore;
    r.pdfOpen = openAfter - openBefore;
    r.blobDecode = blobAfter - blobBefore;
    r.coverDecode = coverAfter - coverBefore;
    fprintf(stderr,
            "bench_library: %s pass: %d books, %d missing, %d had sidecar, %d wrote, %d failed; %llu ms; "
            "PdfSidecar ctx +%d, opened +%d, blob decoded +%d, cover decoded +%d\n",
            label, r.books, r.missingFile, r.hasSidecar, r.wroteSidecar, r.writeFailed,
            (unsigned long long)r.durationMs, r.pdfCtx, r.pdfOpen, r.blobDecode, r.coverDecode);
    return r;
}

static int PrintResult(const char* label, PassResult& r) {
    printf("OK %s books=%d missing=%d withSidecar=%d wrote=%d failed=%d ms=%llu pdfCtx=%d pdfOpen=%d "
           "blobDecode=%d coverDecode=%d\n",
           label, r.books, r.missingFile, r.hasSidecar, r.wroteSidecar, r.writeFailed,
           (unsigned long long)r.durationMs, r.pdfCtx, r.pdfOpen, r.blobDecode, r.coverDecode);
    return 0;
}

// Chunk 9 verification entrypoint: call the production
// LibrarySidecarWriteMetadata on a disposable copy of the test PDF
// with the 4 portable values (Title/Author/Series/Year) and then
// read the result back with LibrarySidecarReadRecord. Reports the
// round-tripped fields on stdout so the test driver can parse them.
static int RunChunk9(Str srcPath) {
    // Make a disposable copy next to the source so the test PDF itself
    // is never touched.
    TempStr copyPath = str::FormatTemp("%s.chunk9.pdf", srcPath);
    Str src = file::ReadFile(srcPath);
    if (len(src) == 0) {
        fprintf(stderr, "chunk9: could not read source %s\n", srcPath.s);
        return 1;
    }
    if (!file::WriteFile(copyPath, src)) {
        fprintf(stderr, "chunk9: could not write copy %s\n", copyPath.s);
        str::Free(src);
        return 1;
    }
    str::Free(src);

    // Call the production writer with the four exact chunk 9 values.
    Str title = str::Dup(StrL("CPP_TITLE_44444"));
    Str author = str::Dup(StrL("CPP_AUTHOR_55555"));
    Str series = str::Dup(StrL("CPP_SERIES_66666"));
    Str seriesParent = str::Dup(Str());
    Str genre = str::Dup(Str());
    Str subgenre = str::Dup(Str());
    Str tags = str::Dup(Str());
    Str partitions = str::Dup(Str());
    int seriesIndex = 0;
    int year = 2097;
    int pages = 60;
    bool ok = LibrarySidecarWriteMetadata(copyPath, title, author, series, seriesParent, genre, subgenre, tags,
                                          partitions, seriesIndex, year, pages, nullptr);
    if (!ok) {
        fprintf(stderr, "chunk9: LibrarySidecarWriteMetadata failed for %s\n", copyPath.s);
        str::Free(title);
        str::Free(author);
        str::Free(series);
        str::Free(seriesParent);
        str::Free(genre);
        str::Free(subgenre);
        str::Free(tags);
        str::Free(partitions);
        return 1;
    }

    // Read the result back with the production reader.
    BookBlobRecord rec;
    bool readOk = LibrarySidecarReadRecord(copyPath, rec);
    if (!readOk) {
        fprintf(stderr, "chunk9: LibrarySidecarReadRecord failed for %s\n", copyPath.s);
        str::Free(title);
        str::Free(author);
        str::Free(series);
        str::Free(seriesParent);
        str::Free(genre);
        str::Free(subgenre);
        str::Free(tags);
        str::Free(partitions);
        return 1;
    }

    // Emit the round-tripped values. The driver parses these.
    const char* gotTitle = rec.identity.title ? rec.identity.title : "(null)";
    const char* gotAuthor = rec.identity.author ? rec.identity.author : "(null)";
    const char* gotSeries = (rec.hasShelf && rec.shelf.series) ? rec.shelf.series : "(null)";
    int gotYear = rec.identity.year;
    printf("OK chunk9 title=%s author=%s series=%s year=%d hasIdentity=%d hasShelf=%d\n", gotTitle, gotAuthor,
           gotSeries, gotYear, rec.hasIdentity ? 1 : 0, rec.hasShelf ? 1 : 0);

    // Free the per-string allocations BookBlobRecord owns. The record
    // populates `strings` (Vec<Str>) and the Identity/Shelf pointers
    // point into that table. We free it via the helper below.
    str::Free(title);
    str::Free(author);
    str::Free(series);
    str::Free(seriesParent);
    str::Free(genre);
    str::Free(subgenre);
    str::Free(tags);
    str::Free(partitions);
    return 0;
}

// argv convention: argv[1] is the corpus dir, argv[2] is an optional
// pass count.
int main(int argc, char** argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: bench_library <corpus-dir> [passes]\n");
        fprintf(stderr, "       bench_library chunk9 <pdf-path>\n");
        return 2;
    }
    // Chunk 9 dispatch: bench_library chunk9 <pdf-path>
    if (argc >= 2 && str::Eq(Str(argv[1]), StrL("chunk9"))) {
        if (argc < 3) {
            fprintf(stderr, "usage: bench_library chunk9 <pdf-path>\n");
            return 2;
        }
        Str srcPath = Str(argv[2]);
        return RunChunk9(srcPath);
    }
    Str corpusDir = Str(argv[1]);
    int passes = 1;
    if (argc >= 3) {
        passes = atoi(argv[2]);
        if (passes < 1) {
            passes = 1;
        }
        if (passes > 10) {
            passes = 10;
        }
    }
    logf("bench_library: corpus=%.*s passes=%d", corpusDir.len, corpusDir, passes);

    Vec<BookEntry> books;
    LoadCorpus(corpusDir, books);
    if (books.len == 0) {
        fprintf(stderr, "bench_library: corpus is empty\n");
        FreeCorpus(books);
        return 3;
    }

    // sort by path so the iteration order is deterministic across passes
    qsort(books.els, books.len, sizeof(BookEntry), CmpPaths);

    // cold pass: writes sidecars (mimics first library open)
    PassResult cold = RunPass(books, "write", "cold");
    // hot pass: just walks, no writes
    PassResult hot = RunPass(books, "check", "hot");
    // decode pass: same as hot but also decodes the sidecar stream for every
    // book — this is the perf cost the old PdfSidecarHasCover/HasBlob
    // implementations used to pay on every SyncEmbeddedRecords call. The
    // ratio decode/hot is the speedup from making SidecarHasStream cheap.
    PassResult decode = RunPass(books, "decode", "decode");

    PrintResult("cold", cold);
    PrintResult("hot", hot);
    PrintResult("decode", decode);

    FreeCorpus(books);
    DestroyTempArena();
    return 0;
}
