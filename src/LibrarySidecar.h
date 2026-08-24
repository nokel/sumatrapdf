/* Copyright 2026 the SumatraPDF project authors (see AUTHORS file).
   License: Simplified BSD (see COPYING.BSD) */

constexpr const char* kLibrarySidecarFileExt = ".sumatra";

struct BookBlobRecord;
struct BlobStats;

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
bool LibrarySidecarWriteCover(Str bookPath, Str coverFormat, Str coverData, Str title, Str author, Str series,
                              int year);
bool LibrarySidecarWriteCoverSpot(Str bookPath, int pageNo, RectF rect, int rotation, Str coverData, Str title,
                                  Str author, Str series, int year);
bool LibrarySidecarForgetCover(Str bookPath);
bool LibrarySidecarWriteInfo(Str bookPath, Str title, Str author, Str series);
bool LibrarySidecarWriteMetadata(Str bookPath, Str title, Str author, Str series, Str seriesParent, Str genre,
                                 Str subgenre, Str tags, Str partitions, int seriesIndex, int year, int pages,
                                 const BlobStats* stats);
