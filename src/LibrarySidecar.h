/* Copyright 2026 the SumatraPDF project authors (see AUTHORS file).
   License: Simplified BSD (see COPYING.BSD) */

constexpr const char* kLibrarySidecarFileExt = ".sumatra";

struct BookBlobRecord;
struct BlobStats;
typedef void (*LibrarySidecarProgressCb)(int done, int total, void* ctx);

constexpr const char* kSidecarFormatWebp = "webp";
constexpr const char* kSidecarFormatPng = "png";
constexpr const char* kSidecarFormatJpeg = "jpeg";
constexpr const char* kSidecarFormatJpg = "jpg";

struct LibrarySidecarCover {
    Str format;
    Str data;
    Str fingerprint;
    int kind = 0;
    int pageNo = 0;
    RectF rect;
    int rotation = 0;
};

bool LibrarySidecarHas(Str bookPath);
bool LibrarySidecarReadRecord(Str bookPath, BookBlobRecord& recordOut);
bool LibrarySidecarWriteRecord(Str bookPath, const BookBlobRecord& record, Str* errorOut);
bool LibrarySidecarReadCover(Str bookPath, LibrarySidecarCover* coverOut);
bool LibrarySidecarReadFingerprint(Str bookPath, Str* fingerprintOut);
bool LibrarySidecarReadOcrState(Str bookPath, int* stateOut, bool* brandedOut);
bool LibrarySidecarWriteCover(Str bookPath, Str coverFormat, Str coverData, Str title, Str author, Str series,
                              int year);
bool LibrarySidecarWriteCoverSpot(Str bookPath, int pageNo, RectF rect, int rotation, Str coverData, Str title,
                                  Str author, Str series, int year);
bool LibrarySidecarForgetCover(Str bookPath);
bool LibrarySidecarWriteInfo(Str bookPath, Str title, Str author, Str series);
int LibrarySidecarBrandIfUnbranded(Str bookPath, LibrarySidecarProgressCb progressCb = nullptr,
                                   void* progressCtx = nullptr, bool runOcr = true);
int LibrarySidecarRunOcrMigration(Str bookPath);

bool LibrarySidecarWriteMetadata(Str bookPath, Str title, Str author, Str series, Str seriesParent, Str genre,
                                 Str subgenre, Str tags, Str partitions, int seriesIndex, int year, int pages,
                                 const BlobStats* stats);

// Like LibrarySidecarWriteMetadata but reuses `existingRec` (if non-null) for
// the "what does the on-disk record already say?" comparison instead of
// re-reading it. This is the path SyncEmbeddedRecords takes when the
// LoadModelThread-scoped record cache already holds a copy of the record
// that was read for AdoptEmbeddedRecordFields a moment earlier.
//
// `existingRec` must point to a record whose fingerprint matches `bookPath`.
// Passing a stale or unrelated record is a programming error; the caller is
// responsible for invalidating the cache entry whenever it writes to the
// file.
//
// If `existingRec` is null, the function reads the record itself — same
// behavior as the original LibrarySidecarWriteMetadata. Callers that do not
// maintain a per-load cache can keep using the no-record overload.
bool LibrarySidecarWriteMetadataWithRec(Str bookPath, const BookBlobRecord* existingRec, Str title, Str author,
                                        Str series, Str seriesParent, Str genre, Str subgenre, Str tags, Str partitions,
                                        int seriesIndex, int year, int pages, const BlobStats* stats);

// Performance counters: total LibrarySidecarReadRecord and
// LibrarySidecarWriteRecord calls (successful and failed) since process
// start. Used by the bench harness to verify the chunk 30 record-reuse
// optimization is actually skipping duplicate reads inside one model load.
extern "C" void LibrarySidecarPerfCounters(int* readsOut, int* writesOut);
