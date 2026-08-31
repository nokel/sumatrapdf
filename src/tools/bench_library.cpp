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
#include "BookFingerprint.h"
#include "BookOcr.h"

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
    // chunk 30: how many times the production LibrarySidecarReadRecord
    // entry point fired during the pass. withcache should be ~hasSidecar
    // (one read per book that has a sidecar); no-cache should be ~2x
    // hasSidecar (adopt + sync each re-read the same record).
    int libReads = 0;
    int libWrites = 0;
};

// One bench pass. If mode is "write", write sidecars for any book that does
// not already have one (mimics a fresh-library migration). If mode is
// "check", just walk the corpus and check for sidecars. If mode is "decode",
// also explicitly decode the blob/cover from every book that has one — this
// is the path the OLD PdfSidecarHasCover / PdfSidecarHasBlob implementations
// used to take (regressed in the Android port), so timing "decode" is the
// before-number and timing "check" is the after-number.
//
// chunk 30 adds two new modes that replay the production LoadModelThread
// path:
//
//   mode "adopt"    : walk the corpus, for every book with a sidecar call
//                     LibrarySidecarReadRecord (the adopt pass on its own).
//                     This is the cost the new model-load record cache
//                     pays once per book.
//
//   mode "sync"     : walk the corpus, for every book with new metadata
//                     call LibrarySidecarWriteMetadata (the sync pass on
//                     its own). This is what the SyncEmbeddedRecords sweep
//                     did before chunk 30, and it pays one
//                     LibrarySidecarReadRecord per book on top of the
//                     write decision.
//
//   mode "withcache": same corpus walk as sync, but the adopt's record
//                     is reused for the sync's "what's already on disk?"
//                     comparison. This is the new, optimized path. The
//                     libReads counter should be ~hasSidecar (one read
//                     per book total), down from ~2*hasSidecar for sync.
static PassResult RunPass(Vec<BookEntry>& books, const char* mode, const char* label) {
    PassResult r;
    r.books = books.len;
    int ctxBefore = 0, openBefore = 0, blobBefore = 0, coverBefore = 0;
    PdfSidecarPerfCounters(&ctxBefore, &openBefore, &blobBefore, &coverBefore);
    int libReadsBefore = 0, libWritesBefore = 0;
    LibrarySidecarPerfCounters(&libReadsBefore, &libWritesBefore);
    bool doWrite = str::Eq(mode, StrL("write"));
    bool doDecode = str::Eq(mode, StrL("decode"));
    bool doAdopt = str::Eq(mode, StrL("adopt"));
    bool doSync = str::Eq(mode, StrL("sync"));
    bool doWithCache = str::Eq(mode, StrL("withcache"));
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
        }
        if (doDecode && has) {
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
        if (doAdopt && has) {
            // Adopt pass on its own: read every record once, like the
            // production adopt loop does before chunk 30's cache.
            BookBlobRecord rec;
            LibrarySidecarReadRecord(e.path, rec);
        }
        if (doWithCache && has) {
            // The "adopt then sync with cache" pass: read the record once
            // (the adopt half), then call WriteMetadataWithRec with that
            // cached record (the sync half skips its own read). For books
            // without a record the cache has no entry, so we pass nullptr
            // — exactly the production SyncEmbeddedRecords path: if there
            // is nothing cached, WriteMetadata falls back to reading the
            // file and may write a fresh record.
            BookBlobRecord rec;
            const BookBlobRecord* cached = nullptr;
            if (LibrarySidecarReadRecord(e.path, rec)) {
                cached = &rec;
            }
            BlobStats stats;
            stats.lastReadAt = 0;
            stats.timeSpentMs = 0;
            stats.openCount = 0;
            stats.pageNo = 0;
            stats.percentRead = 0;
            bool hasNewMetadata = len(e.series) > 0 || len(e.seriesParent) > 0 || len(e.genre) > 0 ||
                                  len(e.subgenre) > 0 || len(e.tags) > 0 || len(e.partitions) > 0;
            // Match the production SyncEmbeddedRecords `carries` rule:
            // only call WriteMetadata if there is new metadata, or the
            // file has a sidecar.
            if (hasNewMetadata || has) {
                bool ok = LibrarySidecarWriteMetadataWithRec(e.path, cached, e.title, e.author, e.series,
                                                             e.seriesParent, e.genre, e.subgenre, e.tags, e.partitions,
                                                             e.seriesIndex, e.year, e.pages, &stats);
                if (ok) {
                    r.wroteSidecar++;
                } else {
                    r.writeFailed++;
                }
            }
        }
        if (doSync && has) {
            // Old sync-only path: WriteMetadata reads the record itself
            // to compare. This is the work chunk 30 removes.
            BlobStats stats;
            stats.lastReadAt = 0;
            stats.timeSpentMs = 0;
            stats.openCount = 0;
            stats.pageNo = 0;
            stats.percentRead = 0;
            bool hasNewMetadata = len(e.series) > 0 || len(e.seriesParent) > 0 || len(e.genre) > 0 ||
                                  len(e.subgenre) > 0 || len(e.tags) > 0 || len(e.partitions) > 0;
            if (hasNewMetadata || has) {
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
        if (!has && doWrite) {
            BlobStats stats;
            stats.lastReadAt = 0;
            stats.timeSpentMs = 0;
            stats.openCount = 0;
            stats.pageNo = 0;
            stats.percentRead = 0;
            bool ok =
                LibrarySidecarWriteMetadata(e.path, e.title, e.author, e.series, e.seriesParent, e.genre, e.subgenre,
                                            e.tags, e.partitions, e.seriesIndex, e.year, e.pages, &stats);
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
    int libReadsAfter = 0, libWritesAfter = 0;
    LibrarySidecarPerfCounters(&libReadsAfter, &libWritesAfter);
    r.libReads = libReadsAfter - libReadsBefore;
    r.libWrites = libWritesAfter - libWritesBefore;
    r.pdfCtx = ctxAfter - ctxBefore;
    r.pdfOpen = openAfter - openBefore;
    r.blobDecode = blobAfter - blobBefore;
    r.coverDecode = coverAfter - coverBefore;
    fprintf(stderr,
            "bench_library: %s pass: %d books, %d missing, %d had sidecar, %d wrote, %d failed; %llu ms; "
            "PdfSidecar ctx +%d, opened +%d, blob decoded +%d, cover decoded +%d; "
            "LibrarySidecar reads +%d, writes +%d\n",
            label, r.books, r.missingFile, r.hasSidecar, r.wroteSidecar, r.writeFailed,
            (unsigned long long)r.durationMs, r.pdfCtx, r.pdfOpen, r.blobDecode, r.coverDecode, r.libReads,
            r.libWrites);
    return r;
}

static int PrintResult(const char* label, PassResult& r) {
    printf(
        "OK %s books=%d missing=%d withSidecar=%d wrote=%d failed=%d ms=%llu pdfCtx=%d pdfOpen=%d "
        "blobDecode=%d coverDecode=%d libReads=%d libWrites=%d\n",
        label, r.books, r.missingFile, r.hasSidecar, r.wroteSidecar, r.writeFailed, (unsigned long long)r.durationMs,
        r.pdfCtx, r.pdfOpen, r.blobDecode, r.coverDecode, r.libReads, r.libWrites);
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
    LibrarySidecarBrandIfUnbranded(copyPath);
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

// Chunk 34R verification entrypoint: prove that a syntactically valid
// stored fingerprint remains authoritative without read-time content
// or shape recomputation. Runs entirely on disposable copies.
//
//   bench_library chunk34r <book-a.pdf> <book-b.pdf> <cache-dir>
static bool CopyBookFile(Str src, Str dst) {
    Str bytes = file::ReadFile(src);
    if (len(bytes) == 0) {
        return false;
    }
    bool ok = file::WriteFile(dst, bytes);
    str::Free(bytes);
    return ok;
}

static int RunChunk34R(Str bookA, Str bookB, Str cacheDir) {
    BookFingerprintCacheOpen(cacheDir);

    TempStr stalePath = str::FormatTemp("%s.c34r-own.pdf", bookA);
    TempStr graftPath = str::FormatTemp("%s.c34r-graft.pdf", bookA);
    TempStr mutPath = str::FormatTemp("%s.c34r-mut.pdf", bookA);
    if (!CopyBookFile(bookA, stalePath) || !CopyBookFile(bookB, graftPath)) {
        fprintf(stderr, "chunk34r: could not make disposable copies\n");
        return 1;
    }

    Str title = str::Dup(StrL("C34R_TITLE_A"));
    Str author = str::Dup(StrL("C34R_AUTHOR_A"));
    Str empty = str::Dup(Str());
    LibrarySidecarBrandIfUnbranded(stalePath);
    bool wrote = LibrarySidecarWriteMetadata(stalePath, title, author, empty, empty, empty, empty, empty, empty, 0,
                                             2091, 0, nullptr);
    if (!wrote) {
        fprintf(stderr, "chunk34r: could not write the record into the copy of book A\n");
        return 1;
    }

    // 1. positive control: the record we just wrote belongs to this file,
    // so the reader must accept it and hand back the title.
    BookBlobRecord own;
    bool ownOk = LibrarySidecarReadRecord(stalePath, own);
    bool ownTitle = ownOk && own.identity.title && str::Eq(Str(own.identity.title), StrL("C34R_TITLE_A"));

    // 2. transplanted sidecar: take the record bytes out of the copy of
    // book A and graft them into book B. The persisted brand remains
    // authoritative and is read without content revalidation.
    Vec<u8> blob;
    bool gotBlob = PdfSidecarReadBlob(stalePath, blob);
    TempStr fpOfA = PdfSidecarReadFingerprint(stalePath);
    Str writeErr;
    bool grafted = gotBlob && PdfSidecarWriteBlob(graftPath, blob.LendData(), blob.len, fpOfA, nullptr, &writeErr);
    BookBlobRecord graft;
    bool graftAccepted = grafted && LibrarySidecarReadRecord(graftPath, graft);
    bool graftLeaked =
        graftAccepted && graft.identity.title && str::Eq(Str(graft.identity.title), StrL("C34R_TITLE_A"));

    // 3. same path, changed size/mtime: overwrite the file with book B's
    // bytes carrying book A's stored record. Size and mtime do not
    // invalidate or recompute the persisted identity.
    bool mutSetup = CopyBookFile(stalePath, mutPath);
    BookBlobRecord warm;
    bool warmOk = mutSetup && LibrarySidecarReadRecord(mutPath, warm);
    bool overwrote = mutSetup && CopyBookFile(graftPath, mutPath);
    BookBlobRecord changed;
    bool changedAccepted = overwrote && LibrarySidecarReadRecord(mutPath, changed);
    int full = 0, shape = 0, hits = 0, seeded = 0;
    BookFingerprintPerfCounters(&full, &shape, &hits, &seeded, nullptr, nullptr, nullptr, nullptr);

    printf(
        "OK chunk34r ownAccepted=%d ownTitle=%d grafted=%d graftAccepted=%d graftTitle=%d warm=%d "
        "changeAccepted=%d full=%d shape=%d\n",
        ownOk ? 1 : 0, ownTitle ? 1 : 0, grafted ? 1 : 0, graftAccepted ? 1 : 0, graftLeaked ? 1 : 0, warmOk ? 1 : 0,
        changedAccepted ? 1 : 0, full, shape);
    if (len(writeErr) > 0) {
        fprintf(stderr, "chunk34r: graft write error: %s\n", writeErr.s);
    }

    str::Free(title);
    str::Free(author);
    str::Free(empty);
    file::Delete(stalePath);
    file::Delete(graftPath);
    file::Delete(mutPath);
    BookFingerprintCacheClose();
    bool pass = ownOk && ownTitle && grafted && graftAccepted && graftLeaked && warmOk && changedAccepted &&
                full == 0 && shape == 0;
    return pass ? 0 : 1;
}

static int RunStamp(Str bookPath, Str title, Str author, bool withStats) {
    Str t = str::Dup(title);
    Str a = str::Dup(author);
    Str empty = str::Dup(Str());
    BlobStats stats;
    stats.lastReadAt = 1787000000;
    stats.timeSpentMs = 987654;
    stats.openCount = 7;
    stats.pageNo = 42;
    stats.percentRead = 33;
    LibrarySidecarBrandIfUnbranded(bookPath);
    bool ok = LibrarySidecarWriteMetadata(bookPath, t, a, empty, empty, empty, empty, empty, empty, 0, 0, 0,
                                          withStats ? &stats : nullptr);
    TempStr fp = PdfSidecarReadFingerprint(bookPath);
    int fpFull = 0, fpShape = 0, fpHits = 0, fpSeeded = 0;
    BookFingerprintPerfCounters(&fpFull, &fpShape, &fpHits, &fpSeeded, nullptr, nullptr, nullptr, nullptr);
    printf("OK stamp wrote=%d full=%d shape=%d cacheHits=%d seeded=%d fingerprint=%s\n", ok ? 1 : 0, fpFull, fpShape,
           fpHits, fpSeeded, len(fp) > 0 ? fp.s : "");
    str::Free(t);
    str::Free(a);
    str::Free(empty);
    return ok ? 0 : 1;
}

static int RunReadRec(Str bookPath, Str cacheDir) {
    if (len(cacheDir) > 0) {
        BookFingerprintCacheOpen(cacheDir);
    }
    BookBlobRecord rec;
    bool ok = LibrarySidecarReadRecord(bookPath, rec);
    int fpFull = 0, fpShape = 0, fpHits = 0, fpSeeded = 0;
    BookFingerprintPerfCounters(&fpFull, &fpShape, &fpHits, &fpSeeded, nullptr, nullptr, nullptr, nullptr);
    printf(
        "OK readrec accepted=%d title=%s full=%d shape=%d cacheHits=%d seeded=%d fingerprint=%s hasShelf=%d "
        "hasStats=%d hasCover=%d pages=%d percentRead=%lld openCount=%lld\n",
        ok ? 1 : 0, (ok && rec.identity.title) ? rec.identity.title : "", fpFull, fpShape, fpHits, fpSeeded,
        (ok && rec.identity.fingerprint) ? rec.identity.fingerprint : "", ok && rec.hasShelf ? 1 : 0,
        ok && rec.hasStats ? 1 : 0, ok && rec.hasCover ? 1 : 0, ok ? rec.identity.pages : 0,
        (long long)(ok && rec.hasStats ? rec.stats.percentRead : 0),
        (long long)(ok && rec.hasStats ? rec.stats.openCount : 0));
    BookFingerprintCacheClose();
    return ok ? 0 : 1;
}

static int RunMigrate(Str bookPath, Str cacheDir) {
    if (len(cacheDir) > 0) {
        BookFingerprintCacheOpen(cacheDir);
    }
    Str fingerprint;
    bool ok = LibrarySidecarReadFingerprint(bookPath, &fingerprint);
    BookBlobRecord rec;
    bool readOk = ok && LibrarySidecarReadRecord(bookPath, rec);
    int fpFull = 0, fpShape = 0, fpHits = 0, fpSeeded = 0;
    BookFingerprintPerfCounters(&fpFull, &fpShape, &fpHits, &fpSeeded, nullptr, nullptr, nullptr, nullptr);
    printf(
        "OK migrate accepted=%d full=%d shape=%d cacheHits=%d seeded=%d fingerprint=%s hasShelf=%d hasStats=%d "
        "hasCover=%d pages=%d percentRead=%lld openCount=%lld\n",
        readOk ? 1 : 0, fpFull, fpShape, fpHits, fpSeeded, fingerprint.s ? fingerprint.s : "",
        readOk && rec.hasShelf ? 1 : 0, readOk && rec.hasStats ? 1 : 0, readOk && rec.hasCover ? 1 : 0,
        readOk ? rec.identity.pages : 0, (long long)(readOk && rec.hasStats ? rec.stats.percentRead : 0),
        (long long)(readOk && rec.hasStats ? rec.stats.openCount : 0));
    str::Free(fingerprint);
    BookFingerprintCacheClose();
    return readOk ? 0 : 1;
}

static int RunOcrMigrate(Str bookPath, Str cacheDir) {
    if (len(cacheDir) > 0) {
        BookFingerprintCacheOpen(cacheDir);
    }
    BookFingerprintResetCounters();
    int result = LibrarySidecarRunOcrMigration(bookPath);
    BookBlobRecord rec;
    bool ok = LibrarySidecarReadRecord(bookPath, rec);
    int ocrState = 0;
    bool portableBranded = false;
    bool portable = LibrarySidecarReadOcrState(bookPath, &ocrState, &portableBranded);
    int fpFull = 0, fpShape = 0, fpHits = 0, fpSeeded = 0;
    int ocrAttempts = 0, ocrPages = 0, ocrSuccesses = 0, ocrNoText = 0;
    BookFingerprintPerfCounters(&fpFull, &fpShape, &fpHits, &fpSeeded, &ocrAttempts, &ocrPages, &ocrSuccesses,
                                &ocrNoText);
    printf(
        "OK ocr-migrate result=%d branded=%d full=%d shape=%d cacheHits=%d seeded=%d fingerprint=%s portable=%d "
        "portableBranded=%d ocrState=%d ocrAttempts=%d ocrPages=%d ocrSuccesses=%d ocrNoText=%d\n",
        result, ok ? 1 : 0, fpFull, fpShape, fpHits, fpSeeded,
        (ok && rec.identity.fingerprint) ? rec.identity.fingerprint : "", portable ? 1 : 0,
        portableBranded ? 1 : 0, ocrState, ocrAttempts, ocrPages, ocrSuccesses, ocrNoText);
    BookFingerprintCacheClose();
    return result < 0 ? 1 : 0;
}

static int RunFingerprintWithTimeout(Str bookPath, Str cacheDir, i64 maxMs) {
    if (len(cacheDir) > 0) {
        BookFingerprintCacheOpen(cacheDir);
    }
    BookFingerprintResetCounters();
    BookOcrSetMaxBookMs(maxMs);
    BookFingerprint fp;
    u64 t0 = GetTickCount64();
    bool ok = BookFingerprintOfFile(bookPath, fp, 0, false, true);
    u64 ms = GetTickCount64() - t0;
    BookOcrSetMaxBookMs(0);
    int fpFull = 0, fpShape = 0, fpHits = 0, fpSeeded = 0;
    int ocrAttempts = 0, ocrPages = 0, ocrSuccesses = 0, ocrNoText = 0;
    BookFingerprintPerfCounters(&fpFull, &fpShape, &fpHits, &fpSeeded, &ocrAttempts, &ocrPages, &ocrSuccesses,
                                &ocrNoText);
    printf(
        "OK fingerprint-timeout ok=%d ms=%llu pages=%d imageOnly=%d ocrState=%d ocrPages=%d ocrSkipped=%d "
        "fingerprint=%s ocrAttempts=%d\n",
        ok ? 1 : 0, (unsigned long long)ms, fp.pages, fp.imageOnly ? 1 : 0, fp.ocrState, fp.ocrPages,
        fp.ocrPagesSkipped, fp.fingerprint.s ? fp.fingerprint.s : "", ocrAttempts);
    BookFingerprintFree(fp);
    BookFingerprintCacheClose();
    return ok ? 0 : 1;
}

static int RunRawPdfRec(Str bookPath) {
    BookBlobRecord rec;
    bool ok = PdfSidecarReadRecord(bookPath, rec);
    printf(
        "OK rawpdf accepted=%d fingerprint=%s hasShelf=%d hasStats=%d hasCover=%d pages=%d percentRead=%lld "
        "openCount=%lld\n",
        ok ? 1 : 0, ok && rec.identity.fingerprint ? rec.identity.fingerprint : "", ok && rec.hasShelf ? 1 : 0,
        ok && rec.hasStats ? 1 : 0, ok && rec.hasCover ? 1 : 0, ok ? rec.identity.pages : 0,
        (long long)(ok && rec.hasStats ? rec.stats.percentRead : 0),
        (long long)(ok && rec.hasStats ? rec.stats.openCount : 0));
    return ok ? 0 : 1;
}

static int RunObsolete(Str bookPath, Str fingerprint) {
    BookBlobRecord rec;
    if (!PdfSidecarReadRecord(bookPath, rec)) {
        return 1;
    }
    rec.identity.fingerprint = fingerprint.s;
    Vec<u8> packed;
    Str error;
    bool encoded = BookBlobEncode(rec, packed);
    bool ok = encoded && PdfSidecarWriteBlob(bookPath, packed.LendData(), packed.len, fingerprint, nullptr, &error);
    printf("OK obsolete wrote=%d fingerprint=%s error=%s\n", ok ? 1 : 0, fingerprint.s, error.s ? error.s : "");
    str::Free(error);
    return ok ? 0 : 1;
}

static int RunFingerprint(Str bookPath, Str textPath, Str identityPath) {
    BookFingerprint fp;
    BookFingerprintResetCounters();
    bool ok = BookFingerprintOfFile(bookPath, fp, 0, len(identityPath) > 0, true);
    if (!ok) {
        return 1;
    }
    char reading[33];
    static const char* hex = "0123456789abcdef";
    for (int i = 0; i < 16; i++) {
        reading[i * 2] = hex[fp.textMd5[i] >> 4];
        reading[i * 2 + 1] = hex[fp.textMd5[i] & 0xf];
    }
    reading[32] = 0;
    if (len(textPath) > 0 && !file::WriteFile(textPath, fp.readingText)) {
        BookFingerprintFree(fp);
        return 1;
    }
    if (len(identityPath) > 0 && !file::WriteFile(identityPath, fp.identityText)) {
        BookFingerprintFree(fp);
        return 1;
    }
    int ocrA = 0, ocrP = 0, ocrS = 0, ocrN = 0;
    BookFingerprintPerfCounters(nullptr, nullptr, nullptr, nullptr, &ocrA, &ocrP, &ocrS, &ocrN);
    printf(
        "OK fingerprint pages=%d images=%d imageOnly=%d ocrState=%d ocrPages=%d ocrSkipped=%d ocrTokens=%d "
        "readingLength=%lld identityLength=%lld runningLines=%d readingMd5=%s fingerprint=%s "
        "ocrAttempts=%d ocrPagesRun=%d ocrSuccesses=%d ocrNoText=%d\n",
        fp.pages, fp.images, fp.imageOnly ? 1 : 0, fp.ocrState, fp.ocrPages, fp.ocrPagesSkipped, fp.ocrTokens,
        (long long)fp.textLength, (long long)fp.identityLength,
        fp.runningLines, reading, fp.fingerprint.s, ocrA, ocrP, ocrS, ocrN);
    BookFingerprintFree(fp);
    return 0;
}

// Chunk 36: the discovery entry point on its own. Reports whether the book
// was branded by this call (1), was already branded (0) or could not be
// branded (-1), plus the fingerprint counters the call spent.
static int RunBrand(Str bookPath, Str cacheDir) {
    if (len(cacheDir) > 0) {
        BookFingerprintCacheOpen(cacheDir);
    }
    int result = LibrarySidecarBrandIfUnbranded(bookPath, nullptr, nullptr, false);
    BookBlobRecord rec;
    bool ok = LibrarySidecarReadRecord(bookPath, rec);
    int ocrState = 0;
    bool portableBranded = false;
    bool portable = LibrarySidecarReadOcrState(bookPath, &ocrState, &portableBranded);
    int fpFull = 0, fpShape = 0, fpHits = 0, fpSeeded = 0;
    int ocrAttempts = 0, ocrPages = 0, ocrSuccesses = 0, ocrNoText = 0;
    BookFingerprintPerfCounters(&fpFull, &fpShape, &fpHits, &fpSeeded, &ocrAttempts, &ocrPages, &ocrSuccesses,
                                &ocrNoText);
    printf("OK brand result=%d branded=%d full=%d shape=%d cacheHits=%d seeded=%d fingerprint=%s portable=%d "
           "portableBranded=%d ocrState=%d ocrAttempts=%d ocrPages=%d ocrSuccesses=%d ocrNoText=%d\n",
           result, ok ? 1 : 0, fpFull, fpShape, fpHits, fpSeeded,
           (ok && rec.identity.fingerprint) ? rec.identity.fingerprint : "", portable ? 1 : 0,
           portableBranded ? 1 : 0, ocrState, ocrAttempts, ocrPages, ocrSuccesses, ocrNoText);
    BookFingerprintCacheClose();
    return result < 0 ? 1 : 0;
}

static int RunCover(Str bookPath) {
    BookBlobRecord before;
    bool readBefore = LibrarySidecarReadRecord(bookPath, before);
    Str fpBefore = str::Dup(readBefore && before.identity.fingerprint ? Str(before.identity.fingerprint) : Str());
    const u8 png[1500] = {0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d, 0x49, 0x48,
                          0x44, 0x52, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00,
                          0x00, 0x1f, 0x15, 0xc4, 0x89, 0x00, 0x00, 0x00, 0x0d, 0x49, 0x44, 0x41, 0x54, 0x08,
                          0xd7, 0x63, 0xf8, 0xcf, 0xc0, 0xf0, 0x1f, 0x00, 0x05, 0x00, 0x01, 0xff, 0x89, 0x99,
                          0x3d, 0x1d, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4e, 0x44, 0xae, 0x42, 0x60, 0x82};
    bool wrote = LibrarySidecarWriteCover(bookPath, StrL("png"), Str((const char*)png, dimof(png)), StrL("Cover Test"),
                                          StrL("Fixture Author"), StrL("Fixture Series"), 2026);
    BookBlobRecord after;
    bool readAfter = LibrarySidecarReadRecord(bookPath, after);
    Str fpAfter = str::Dup(readAfter && after.identity.fingerprint ? Str(after.identity.fingerprint) : Str());
    int full = 0, shape = 0, hits = 0, seeded = 0, ocrAttempts = 0;
    BookFingerprintPerfCounters(&full, &shape, &hits, &seeded, &ocrAttempts, nullptr, nullptr, nullptr);
    bool same = len(fpBefore) > 0 && str::Eq(fpBefore, fpAfter);
    bool cover =
        readAfter && after.hasCover && after.cover.kind == kBlobCoverImage && after.cover.data.len == dimof(png);
    printf("OK cover wrote=%d changed=%d sameFingerprint=%d full=%d shape=%d ocrAttempts=%d before=%s after=%s\n",
           wrote ? 1 : 0, cover ? 1 : 0, same ? 1 : 0, full, shape, ocrAttempts, fpBefore.s ? fpBefore.s : "",
           fpAfter.s ? fpAfter.s : "");
    str::Free(fpBefore);
    str::Free(fpAfter);
    return wrote && cover && same && full == 0 && shape == 0 && ocrAttempts == 0 ? 0 : 1;
}

static int RunStability(Str bookPath) {
    BookBlobRecord before;
    if (!LibrarySidecarReadRecord(bookPath, before)) {
        return 1;
    }
    Str fingerprint = str::Dup(Str(before.identity.fingerprint));
    BlobStats stats;
    stats.lastReadAt = 1787000001;
    stats.timeSpentMs = 123456;
    stats.openCount = 9;
    stats.pageNo = 1;
    stats.percentRead = 50;
    bool title =
        LibrarySidecarWriteMetadata(bookPath, StrL("StableTitle"), {}, {}, {}, {}, {}, {}, {}, 0, 0, 0, nullptr);
    BookBlobRecord a;
    bool titleSame = LibrarySidecarReadRecord(bookPath, a) && str::Eq(fingerprint, Str(a.identity.fingerprint));
    bool author = LibrarySidecarWriteMetadata(bookPath, StrL("StableTitle"), StrL("StableAuthor"), {}, {}, {}, {}, {},
                                              {}, 0, 0, 0, nullptr);
    BookBlobRecord b;
    bool authorSame = LibrarySidecarReadRecord(bookPath, b) && str::Eq(fingerprint, Str(b.identity.fingerprint));
    bool series = LibrarySidecarWriteMetadata(bookPath, StrL("StableTitle"), StrL("StableAuthor"), StrL("StableSeries"),
                                              {}, {}, {}, {}, {}, 0, 0, 0, nullptr);
    BookBlobRecord c;
    bool seriesSame = LibrarySidecarReadRecord(bookPath, c) && str::Eq(fingerprint, Str(c.identity.fingerprint));
    bool readState = LibrarySidecarWriteMetadata(bookPath, StrL("StableTitle"), StrL("StableAuthor"),
                                                 StrL("StableSeries"), {}, {}, {}, {}, {}, 0, 0, 0, &stats);
    BookBlobRecord d;
    bool readSame = LibrarySidecarReadRecord(bookPath, d) && str::Eq(fingerprint, Str(d.identity.fingerprint));
    int full = 0, shape = 0, hits = 0, seeded = 0, ocrAttempts = 0;
    BookFingerprintPerfCounters(&full, &shape, &hits, &seeded, &ocrAttempts, nullptr, nullptr, nullptr);
    printf("OK stability title=%d author=%d series=%d read=%d full=%d shape=%d ocrAttempts=%d before=%s after=%s\n",
           title && titleSame ? 1 : 0, author && authorSame ? 1 : 0, series && seriesSame ? 1 : 0,
           readState && readSame ? 1 : 0, full, shape, ocrAttempts, fingerprint.s, d.identity.fingerprint);
    bool pass = title && titleSame && author && authorSame && series && seriesSame && readState && readSame &&
                full == 0 && shape == 0 && ocrAttempts == 0;
    str::Free(fingerprint);
    return pass ? 0 : 1;
}

static int RunChunk35(Str corpusDir, bool doFull) {
    Vec<BookEntry> books;
    LoadCorpus(corpusDir, books);
    if (books.len == 0) {
        fprintf(stderr, "bench_library: corpus is empty\n");
        FreeCorpus(books);
        return 3;
    }
    qsort(books.els, books.len, sizeof(BookEntry), CmpPaths);
    int branded = 0, unbranded = 0, shapeOk = 0, shapeBad = 0, shapeFail = 0, fullOk = 0, fullBad = 0;
    u64 shapeMs = 0, fullMs = 0;
    for (int i = 0; i < books.len; i++) {
        Str bookPath = books.els[i].path;
        TempStr fp = PdfSidecarReadFingerprint(bookPath);
        if (len(fp) == 0) {
            unbranded++;
            continue;
        }
        branded++;
        char shape[33];
        int pages = 0;
        u64 t0 = GetTickCount64();
        bool got = BookShapeOfFile(bookPath, shape, &pages);
        shapeMs += GetTickCount64() - t0;
        if (!got) {
            shapeFail++;
            continue;
        }
        int at = -1;
        for (int k = 0; k < fp.len; k++) {
            if (fp.s[k] == ':') {
                at = k;
            }
        }
        Str want = at >= 0 ? Str(fp.s + at + 1, fp.len - at - 1) : Str();
        if (str::Eq(Str(shape), want)) {
            shapeOk++;
        } else {
            shapeBad++;
            logf("chunk35: shape mismatch %s\n", bookPath);
        }
        if (doFull) {
            BookFingerprint full;
            u64 t1 = GetTickCount64();
            bool okFull = BookFingerprintOfFile(bookPath, full, 0);
            fullMs += GetTickCount64() - t1;
            if (okFull) {
                if (str::Eq(full.fingerprint, fp)) {
                    fullOk++;
                } else {
                    fullBad++;
                    logf("chunk35: full fingerprint mismatch %s\n", bookPath);
                }
                BookFingerprintFree(full);
            }
        }
    }
    printf(
        "OK chunk35 books=%d branded=%d unbranded=%d shapeOk=%d shapeBad=%d shapeFail=%d shapeMs=%llu fullOk=%d "
        "fullBad=%d fullMs=%llu\n",
        books.len, branded, unbranded, shapeOk, shapeBad, shapeFail, (unsigned long long)shapeMs, fullOk, fullBad,
        (unsigned long long)fullMs);
    FreeCorpus(books);
    return (shapeBad == 0 && shapeFail == 0 && fullBad == 0) ? 0 : 1;
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
    if (argc >= 2 && str::Eq(Str(argv[1]), StrL("readrec"))) {
        if (argc < 3) {
            fprintf(stderr, "usage: bench_library readrec <book.pdf> [cache-dir]\n");
            return 2;
        }
        return RunReadRec(Str(argv[2]), argc >= 4 ? Str(argv[3]) : Str());
    }
    if (argc >= 2 && str::Eq(Str(argv[1]), StrL("fingerprint"))) {
        if (argc < 3) {
            fprintf(stderr, "usage: bench_library fingerprint <book>\n");
            return 2;
        }
        return RunFingerprint(Str(argv[2]), argc >= 4 ? Str(argv[3]) : Str(), argc >= 5 ? Str(argv[4]) : Str());
    }
    if (argc >= 2 && str::Eq(Str(argv[1]), StrL("migrate"))) {
        if (argc < 3) {
            fprintf(stderr, "usage: bench_library migrate <book> [cache-dir]\n");
            return 2;
        }
        return RunMigrate(Str(argv[2]), argc >= 4 ? Str(argv[3]) : Str());
    }
    if (argc >= 2 && str::Eq(Str(argv[1]), StrL("ocr-migrate"))) {
        if (argc < 3) {
            fprintf(stderr, "usage: bench_library ocr-migrate <book> [cache-dir]\n");
            return 2;
        }
        return RunOcrMigrate(Str(argv[2]), argc >= 4 ? Str(argv[3]) : Str());
    }
    if (argc >= 2 && str::Eq(Str(argv[1]), StrL("fingerprint-timeout"))) {
        if (argc < 4) {
            fprintf(stderr, "usage: bench_library fingerprint-timeout <book> <max-ms> [cache-dir]\n");
            return 2;
        }
        i64 maxMs = atoll(argv[3]);
        return RunFingerprintWithTimeout(Str(argv[2]), argc >= 5 ? Str(argv[4]) : Str(), maxMs);
    }
    if (argc >= 2 && str::Eq(Str(argv[1]), StrL("rawpdf"))) {
        if (argc < 3) {
            fprintf(stderr, "usage: bench_library rawpdf <book.pdf>\n");
            return 2;
        }
        return RunRawPdfRec(Str(argv[2]));
    }
    if (argc >= 2 && str::Eq(Str(argv[1]), StrL("obsolete"))) {
        if (argc < 4) {
            fprintf(stderr, "usage: bench_library obsolete <book> <fingerprint>\n");
            return 2;
        }
        return RunObsolete(Str(argv[2]), Str(argv[3]));
    }
    if (argc >= 2 && str::Eq(Str(argv[1]), StrL("brand"))) {
        if (argc < 3) {
            fprintf(stderr, "usage: bench_library brand <book> [cache-dir]\n");
            return 2;
        }
        return RunBrand(Str(argv[2]), argc >= 4 ? Str(argv[3]) : Str());
    }
    if (argc >= 2 && str::Eq(Str(argv[1]), StrL("cover"))) {
        if (argc < 3) {
            fprintf(stderr, "usage: bench_library cover <book>\n");
            return 2;
        }
        return RunCover(Str(argv[2]));
    }
    if (argc >= 2 && str::Eq(Str(argv[1]), StrL("stability"))) {
        if (argc < 3) {
            fprintf(stderr, "usage: bench_library stability <book>\n");
            return 2;
        }
        return RunStability(Str(argv[2]));
    }
    if (argc >= 2 && str::Eq(Str(argv[1]), StrL("stamp"))) {
        if (argc < 5) {
            fprintf(stderr, "usage: bench_library stamp <book.pdf> <title> <author> [stats]\n");
            return 2;
        }
        bool withStats = argc >= 6 && str::Eq(Str(argv[5]), StrL("stats"));
        return RunStamp(Str(argv[2]), Str(argv[3]), Str(argv[4]), withStats);
    }
    if (argc >= 2 && str::Eq(Str(argv[1]), StrL("chunk35"))) {
        if (argc < 3) {
            fprintf(stderr, "usage: bench_library chunk35 <corpus-dir> [full]\n");
            return 2;
        }
        bool doFull = argc >= 4 && str::Eq(Str(argv[3]), StrL("full"));
        return RunChunk35(Str(argv[2]), doFull);
    }
    if (argc >= 2 && str::Eq(Str(argv[1]), StrL("chunk34r"))) {
        if (argc < 5) {
            fprintf(stderr, "usage: bench_library chunk34r <book-a.pdf> <book-b.pdf> <cache-dir>\n");
            return 2;
        }
        return RunChunk34R(Str(argv[2]), Str(argv[3]), Str(argv[4]));
    }
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
    // chunk 30 passes:
    //   adopt     — replay the adopt half of LoadModelThread in isolation.
    //               The cache populates here; the read cost is unavoidable.
    //   sync      — replay SyncEmbeddedRecords the OLD way. WriteMetadata
    //               reads the record itself to compare. libReads must be
    //               roughly the book count (one read per book).
    //   withcache — replay the NEW LoadModelThread path: adopt reads
    //               once, sync reuses that record. libReads must match
    //               the adopt pass, NOT double it. This is the perf win.
    // The (sync.ms - withcache.ms) delta on a hot corpus is the chunk 30
    // saving for a single model load. The (sync.libReads - withcache.libReads)
    // delta is the saved I/O.
    PassResult adopt = RunPass(books, "adopt", "adopt");
    PassResult sync = RunPass(books, "sync", "sync");
    PassResult withcache = RunPass(books, "withcache", "withcache");

    PrintResult("cold", cold);
    PrintResult("hot", hot);
    PrintResult("decode", decode);
    PrintResult("adopt", adopt);
    PrintResult("sync", sync);
    PrintResult("withcache", withcache);

    FreeCorpus(books);
    DestroyTempArena();
    return 0;
}
