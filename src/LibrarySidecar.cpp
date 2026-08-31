/* Copyright 2026 the SumatraPDF project authors (see AUTHORS file).
   License: Simplified BSD (see COPYING.BSD) */

#include "base/Base.h"
#include "base/Archive.h"
#include "base/File.h"
#include "base/GuessFileType.h"
#include "base/Zip.h"

#include "BookFingerprint.h"
#include "BookBlob.h"
#include "PdfSidecar.h"

#include "LibrarySidecar.h"

static bool RecordIsBranded(const BookBlobRecord& rec);
static bool RecordHasNoTextState(const BookBlobRecord& rec);

// Performance counters: how many times the production hot path read or wrote
// a record. Tracked here, not in SumatraPDF's logging channel, because the
// bench harness needs to read them at arbitrary points without parsing log
// output. Kept as plain statics so the only API surface is one extern "C"
// function below.
static int gReadCount = 0;
static int gWriteCount = 0;

extern "C" void LibrarySidecarPerfCounters(int* readsOut, int* writesOut) {
    if (readsOut) {
        *readsOut = gReadCount;
    }
    if (writesOut) {
        *writesOut = gWriteCount;
    }
}

namespace {

void SidecarSetError(Str* out, Str value) {
    if (out) {
        str::ReplaceWithCopy(out, value);
    }
}

const u8 kSidecarFmtWebp = 0;
const u8 kSidecarFmtPng = 1;
const u8 kSidecarFmtJpeg = 2;

const char* FormatFromCode(u8 code) {
    if (code == kSidecarFmtWebp) {
        return kSidecarFormatWebp;
    }
    if (code == kSidecarFmtPng) {
        return kSidecarFormatPng;
    }
    if (code == kSidecarFmtJpeg) {
        return kSidecarFormatJpeg;
    }
    return nullptr;
}

const char* DetectImageFormat(Str data) {
    if (data.len >= 12 && memcmp(data.s, "RIFF", 4) == 0 && memcmp(data.s + 8, "WEBP", 4) == 0) {
        return kSidecarFormatWebp;
    }
    if (data.len >= 8 && (u8)data.s[0] == 0x89 && memcmp(data.s + 1, "PNG", 3) == 0) {
        return kSidecarFormatPng;
    }
    if (data.len >= 3 && (u8)data.s[0] == 0xff && (u8)data.s[1] == 0xd8 && (u8)data.s[2] == 0xff) {
        return kSidecarFormatJpeg;
    }
    return nullptr;
}

TempStr SidecarFilePath(Str bookPath) {
    return str::FormatTemp("%s%c%c%c%c%c%c%c%c", bookPath, kLibrarySidecarFileExt[0], kLibrarySidecarFileExt[1],
                           kLibrarySidecarFileExt[2], kLibrarySidecarFileExt[3], kLibrarySidecarFileExt[4],
                           kLibrarySidecarFileExt[5], kLibrarySidecarFileExt[6], kLibrarySidecarFileExt[7]);
}

const char* NormaliseFormat(const char* s) {
    if (!s || !*s) {
        return nullptr;
    }
    if (str::EqI(Str(s), StrL("webp"))) {
        return kSidecarFormatWebp;
    }
    if (str::EqI(Str(s), StrL("png"))) {
        return kSidecarFormatPng;
    }
    if (str::EqI(Str(s), StrL("jpg")) || str::EqI(Str(s), StrL("jpeg"))) {
        return kSidecarFormatJpeg;
    }
    return nullptr;
}

bool IsPdf(Str path) {
    if (!file::Exists(path)) {
        return false;
    }
    return GuessFileTypeFromName(path) == FileType::PDF;
}

bool IsZipBook(Str path) {
    FileType type = GuessFileTypeFromName(path, true);
    return type == FileType::Epub || type == FileType::Cbz || type == FileType::Fb2z || type == FileType::Xps ||
           type == FileType::Zip;
}

bool ReadZipRecord(Str bookPath, BookBlobRecord& recordOut) {
    Archive* archive = OpenArchiveFromFile(bookPath, true, {});
    if (!archive) {
        return false;
    }
    Archive::FileInfo* entry = archive->GetFileDataByName("META-INF/sumatra.book");
    bool ok = entry && entry->data && entry->fileSizeUncompressed > 0 &&
              BookBlobDecode((const u8*)entry->data, entry->fileSizeUncompressed, recordOut);
    delete archive;
    return ok;
}

bool WriteZipRecord(Str bookPath, Str blob, bool allowNoText, Str* errorOut) {
    Archive* archive = OpenArchiveFromFile(bookPath, true, {});
    if (!archive || archive->isEncrypted) {
        delete archive;
        SidecarSetError(errorOut, StrL("the archive could not be opened for rewriting"));
        return false;
    }
    TempStr temp = str::FormatTemp("%s.sumatra.tmp", bookPath);
    file::Delete(temp);
    ZipCreator zip(temp);
    bool isEpub = GuessFileTypeFromName(bookPath, true) == FileType::Epub;
    bool ok = true;
    if (isEpub) {
        Archive::FileInfo* mime = archive->GetFileDataByName("mimetype");
        ok = mime && mime->data &&
             zip.AddFileData(StrL("mimetype"), Str(mime->data, mime->fileSizeUncompressed), 0, false);
    }
    for (Archive::FileInfo* entry : archive->GetFileInfos()) {
        if (!ok || entry->isDir || str::Eq(entry->name, StrL("META-INF/sumatra.book")) ||
            (isEpub && str::Eq(entry->name, StrL("mimetype")))) {
            continue;
        }
        Archive::FileInfo* data = archive->GetFileDataById(entry->fileId);
        ok = data && data->data && zip.AddFileData(entry->name, Str(data->data, data->fileSizeUncompressed));
    }
    ok = ok && zip.AddFileData(StrL("META-INF/sumatra.book"), blob, 0, false) && zip.Finish();
    delete archive;
    if (!ok) {
        file::Delete(temp);
        SidecarSetError(errorOut, StrL("the archive could not be rebuilt"));
        return false;
    }
    BookBlobRecord check;
    if (!ReadZipRecord(temp, check) || (!RecordIsBranded(check) && !(allowNoText && RecordHasNoTextState(check)))) {
        file::Delete(temp);
        SidecarSetError(errorOut, StrL("the rebuilt archive did not verify"));
        return false;
    }
    if (!file::RenameReplace(bookPath, temp)) {
        file::Delete(temp);
        SidecarSetError(errorOut, StrL("the rebuilt archive could not replace the book"));
        return false;
    }
    return true;
}

bool ReadSidecarCover(Str bookPath, LibrarySidecarCover* coverOut) {
    TempStr path = SidecarFilePath(bookPath);
    if (!file::Exists(path)) {
        return false;
    }
    Str data = file::ReadFile(path);
    if (data.len < 5) {
        return false;
    }
    const char* fmt = FormatFromCode((u8)data.s[0]);
    if (!fmt) {
        return false;
    }
    int len = ((u8)data.s[1] << 24) | ((u8)data.s[2] << 16) | ((u8)data.s[3] << 8) | (u8)data.s[4];
    if (len < 0 || 5 + len > data.len) {
        return false;
    }
    coverOut->format = str::Dup(Str(fmt));
    coverOut->data = str::Dup(Str(data.s + 5, len));
    return true;
}

void DeleteSidecarFile(Str bookPath) {
    TempStr path = SidecarFilePath(bookPath);
    file::Delete(path);
}

} // namespace

bool LibrarySidecarHas(Str bookPath) {
    if (IsPdf(bookPath) && (PdfSidecarHasCover(bookPath) || PdfSidecarHasBlob(bookPath))) {
        return true;
    }
    if (IsZipBook(bookPath)) {
        BookBlobRecord record;
        if (ReadZipRecord(bookPath, record)) {
            return true;
        }
    }
    TempStr path = SidecarFilePath(bookPath);
    return file::Exists(path);
}

static bool IsLowerHex(Str s, int want) {
    if (s.len != want) {
        return false;
    }
    for (int i = 0; i < s.len; i++) {
        char c = s.s[i];
        bool digit = c >= '0' && c <= '9';
        bool hex = c >= 'a' && c <= 'f';
        if (!digit && !hex) {
            return false;
        }
    }
    return true;
}

static bool RecordIsBranded(const BookBlobRecord& rec) {
    if (!rec.hasIdentity || !rec.identity.fingerprint) {
        return false;
    }
    Str fingerprint(rec.identity.fingerprint);
    TempStr prefix = str::FormatTemp("%s:", Str(kBookFingerprintVersion));
    if (!str::StartsWith(fingerprint, prefix)) {
        return false;
    }
    Str rest(fingerprint.s + prefix.len, fingerprint.len - prefix.len);
    int at = -1;
    for (int i = 0; i < rest.len; i++) {
        if (rest.s[i] == ':') {
            at = i;
            break;
        }
    }
    if (at < 0) {
        return false;
    }
    Str text(rest.s, at);
    Str shape(rest.s + at + 1, rest.len - at - 1);
    return IsLowerHex(text, 32) && !str::Eq(text, StrL("d41d8cd98f00b204e9800998ecf8427e")) && IsLowerHex(shape, 32);
}

static bool RecordHasEmptyTextBrand(const BookBlobRecord& rec) {
    if (!rec.hasIdentity || !rec.identity.fingerprint) {
        return false;
    }
    TempStr prefix = str::FormatTemp("%s:d41d8cd98f00b204e9800998ecf8427e:", Str(kBookFingerprintVersion));
    return str::StartsWith(Str(rec.identity.fingerprint), prefix);
}

static bool ReadUncheckedRecord(Str bookPath, BookBlobRecord& rec) {
    if (IsPdf(bookPath) && PdfSidecarReadRecord(bookPath, rec)) {
        return true;
    }
    if (IsZipBook(bookPath) && ReadZipRecord(bookPath, rec)) {
        return true;
    }
    TempStr path = SidecarFilePath(bookPath);
    Str blob = file::ReadFile(path);
    bool ok = len(blob) > 0 && BookBlobDecode((const u8*)blob.s, blob.len, rec);
    str::Free(blob);
    return ok;
}

static bool RecordHasNoTextState(const BookBlobRecord& rec) {
    return rec.hasIdentity && !rec.identity.fingerprint && rec.identity.ocrState == kBookOcrNoText;
}

static void SetRecordBookInfo(BookBlobRecord& rec, Str bookPath, Str title, Str author, Str series, int year) {
    rec.hasIdentity = true;
    if (len(title) > 0) {
        rec.identity.title = rec.strings.Append(title).s;
    }
    if (len(author) > 0) {
        rec.identity.author = rec.strings.Append(author).s;
    }
    if (len(bookPath) > 0) {
        rec.identity.source = rec.strings.Append(path::GetBaseNameTemp(bookPath)).s;
    }
    if (year > 0) {
        rec.identity.year = year;
    }
    if (len(series) > 0) {
        rec.hasShelf = true;
        rec.shelf.series = rec.strings.Append(series).s;
    }
}

static bool SetLegacyPdfCover(Str bookPath, BookBlobRecord& rec) {
    Str format;
    Vec<u8> data;
    if (!PdfSidecarReadCover(bookPath, &format, data)) {
        str::Free(format);
        return false;
    }
    rec.hasCover = true;
    rec.cover.kind = kBlobCoverImage;
    rec.cover.format = rec.strings.Append(format).s;
    rec.cover.data.Reset();
    rec.cover.data.Append(data.LendData(), data.len);
    str::Free(format);
    return true;
}

bool LibrarySidecarReadRecord(Str bookPath, BookBlobRecord& recordOut) {
    gReadCount++;
    if (IsPdf(bookPath)) {
        if (PdfSidecarReadRecord(bookPath, recordOut) && RecordIsBranded(recordOut)) {
            SetLegacyPdfCover(bookPath, recordOut);
            return true;
        }
        // A record we refused belongs to some other book. Wipe it before the
        // next attempt so none of its title, cover, cast or read state can
        // survive into a record we later write for this book.
        BookBlobRecordReset(recordOut);
    }
    if (IsZipBook(bookPath)) {
        if (ReadZipRecord(bookPath, recordOut) && RecordIsBranded(recordOut)) {
            return true;
        }
        BookBlobRecordReset(recordOut);
    }
    TempStr path = SidecarFilePath(bookPath);
    Str blob = file::ReadFile(path);
    if (len(blob) == 0) {
        str::Free(blob);
        if (IsPdf(bookPath)) {
            SetLegacyPdfCover(bookPath, recordOut);
        }
        return false;
    }
    bool ok = BookBlobDecode((const u8*)blob.s, blob.len, recordOut) && RecordIsBranded(recordOut);
    str::Free(blob);
    if (!ok) {
        BookBlobRecordReset(recordOut);
        return false;
    }
    if (IsPdf(bookPath)) {
        SetLegacyPdfCover(bookPath, recordOut);
    }
    return true;
}

static bool WriteRawRecord(Str bookPath, Str blob, bool allowNoText, Str* errorOut) {
    TempStr path = SidecarFilePath(bookPath);
    TempStr temp = str::FormatTemp("%s.tmp", path);
    if (!file::WriteFile(temp, blob)) {
        SidecarSetError(errorOut, str::FormatTemp("could not write %s", temp));
        return false;
    }
    Str check = file::ReadFile(temp);
    BookBlobRecord readBack;
    bool valid = len(check) == len(blob) && memcmp(check.s, blob.s, (size_t)blob.len) == 0 &&
                 BookBlobDecode((const u8*)check.s, check.len, readBack) &&
                 (RecordIsBranded(readBack) || (allowNoText && RecordHasNoTextState(readBack)));
    str::Free(check);
    if (!valid) {
        file::Delete(temp);
        SidecarSetError(errorOut, StrL("the sidecar did not verify after writing"));
        return false;
    }
    if (!file::RenameReplace(path, temp)) {
        file::Delete(temp);
        SidecarSetError(errorOut, str::FormatTemp("could not replace %s", path));
        return false;
    }
    return true;
}

bool LibrarySidecarWriteRecord(Str bookPath, const BookBlobRecord& record, Str* errorOut) {
    gWriteCount++;
    if (errorOut) {
        str::ReplaceWithCopy(errorOut, {});
    }
    TempStr invalid = BookRecordWhyInvalid(record);
    if (invalid) {
        SidecarSetError(errorOut, invalid);
        return false;
    }
    if (!RecordIsBranded(record)) {
        SidecarSetError(errorOut, StrL("the record fingerprint does not match the book"));
        return false;
    }
    Vec<u8> packed;
    if (!BookBlobEncode(record, packed)) {
        SidecarSetError(errorOut, StrL("the record could not be encoded"));
        return false;
    }
    Str blob((const char*)packed.LendData(), packed.len);
    if (IsPdf(bookPath)) {
        Str pdfError;
        if (PdfSidecarWriteBlob(bookPath, packed.LendData(), packed.len, Str(record.identity.fingerprint), nullptr,
                                &pdfError)) {
            DeleteSidecarFile(bookPath);
            str::Free(pdfError);
            return true;
        }
        SidecarSetError(errorOut, pdfError);
        str::Free(pdfError);
    }
    if (IsZipBook(bookPath) && WriteZipRecord(bookPath, blob, false, errorOut)) {
        DeleteSidecarFile(bookPath);
        return true;
    }
    return WriteRawRecord(bookPath, blob, false, errorOut);
}

static bool WriteNoTextRecord(Str bookPath, const BookBlobRecord& record, Str* errorOut) {
    gWriteCount++;
    if (!RecordHasNoTextState(record)) {
        SidecarSetError(errorOut, StrL("the record does not contain a portable no-text state"));
        return false;
    }
    Vec<u8> packed;
    if (!BookBlobEncode(record, packed)) {
        SidecarSetError(errorOut, StrL("the OCR state could not be encoded"));
        return false;
    }
    Str blob((const char*)packed.LendData(), packed.len);
    if (IsPdf(bookPath)) {
        Str pdfError;
        if (PdfSidecarWriteBlob(bookPath, packed.LendData(), packed.len, {}, nullptr, &pdfError)) {
            BookBlobRecord check;
            if (PdfSidecarReadRecord(bookPath, check) && RecordHasNoTextState(check)) {
                DeleteSidecarFile(bookPath);
                str::Free(pdfError);
                return true;
            }
        }
        SidecarSetError(errorOut, pdfError);
        str::Free(pdfError);
    }
    if (IsZipBook(bookPath) && WriteZipRecord(bookPath, blob, true, errorOut)) {
        DeleteSidecarFile(bookPath);
        return true;
    }
    return WriteRawRecord(bookPath, blob, true, errorOut);
}

bool LibrarySidecarReadCover(Str bookPath, LibrarySidecarCover* coverOut) {
    if (!coverOut) {
        return false;
    }
    str::ReplaceWithCopy(&coverOut->format, {});
    str::ReplaceWithCopy(&coverOut->data, {});
    str::ReplaceWithCopy(&coverOut->fingerprint, {});

    // Do NOT migrate the legacy "cover in PDF PieceInfo" path here. The
    // previous code rewrote the PDF on every cover read (the cover
    // worker calls this once per visible book, on every repaint) which
    // fans the user's machine. SyncEmbeddedRecords already does a
    // "LibrarySidecarHas + LibrarySidecarWriteMetadata" pass on load;
    // the legacy cover gets migrated then. This is just the read path.
    BookBlobRecord record;
    if (LibrarySidecarReadRecord(bookPath, record)) {
        if (record.hasCover) {
            coverOut->kind = record.cover.kind;
            coverOut->pageNo = record.cover.page;
            coverOut->rect = RectF::FromXY((float)record.cover.x0, (float)record.cover.y0, (float)record.cover.x1,
                                           (float)record.cover.y1);
            coverOut->rotation = record.cover.rotation;
        }
        if (record.hasCover && record.cover.data.len > 0) {
            coverOut->format = str::Dup(Str(record.cover.format ? record.cover.format : ""));
            coverOut->data = str::Dup(Str((const char*)record.cover.data.LendData(), record.cover.data.len));
        }
        if (record.hasIdentity && record.identity.fingerprint) {
            coverOut->fingerprint = str::Dup(Str(record.identity.fingerprint));
        }
        return record.hasCover;
    }
    if (IsPdf(bookPath)) {
        Str embeddedFormat;
        Vec<u8> embedded;
        if (PdfSidecarReadCover(bookPath, &embeddedFormat, embedded)) {
            coverOut->format = embeddedFormat;
            coverOut->data = str::Dup(Str((const char*)embedded.LendData(), embedded.len));
            TempStr fp = PdfSidecarReadFingerprint(bookPath);
            if (fp) {
                coverOut->fingerprint = str::Dup(fp);
            }
            return true;
        }
        str::Free(embeddedFormat);
    }
    if (ReadSidecarCover(bookPath, coverOut)) {
        return true;
    }
    if (!IsPdf(bookPath)) {
        return false;
    }
    BookBlobRecord rec;
    if (!PdfSidecarReadRecord(bookPath, rec)) {
        return false;
    }
    if (rec.hasCover && rec.cover.kind == kBlobCoverImage && rec.cover.data.len > 0) {
        coverOut->format = str::Dup(Str(rec.cover.format ? rec.cover.format : ""));
        coverOut->data = str::Dup(Str((const char*)rec.cover.data.LendData(), rec.cover.data.len));
    }
    if (rec.hasIdentity && rec.identity.fingerprint) {
        coverOut->fingerprint = str::Dup(Str(rec.identity.fingerprint));
    }
    return true;
}

bool LibrarySidecarReadFingerprint(Str bookPath, Str* fingerprintOut) {
    if (!fingerprintOut) {
        return false;
    }
    str::ReplaceWithCopy(fingerprintOut, {});
    BookBlobRecord rec;
    if (!ReadUncheckedRecord(bookPath, rec)) {
        return false;
    }
    if (!RecordIsBranded(rec) && rec.hasIdentity && rec.identity.fingerprint &&
        str::StartsWith(Str(rec.identity.fingerprint), StrL("fp2:"))) {
        if (LibrarySidecarBrandIfUnbranded(bookPath) < 0) {
            return false;
        }
        BookBlobRecordReset(rec);
        if (!ReadUncheckedRecord(bookPath, rec)) {
            return false;
        }
    }
    if (!RecordIsBranded(rec)) {
        return false;
    }
    *fingerprintOut = str::Dup(Str(rec.identity.fingerprint));
    return true;
}

bool LibrarySidecarReadOcrState(Str bookPath, int* stateOut, bool* brandedOut) {
    if (stateOut) {
        *stateOut = kBookOcrNotAttempted;
    }
    if (brandedOut) {
        *brandedOut = false;
    }
    BookBlobRecord rec;
    if (!ReadUncheckedRecord(bookPath, rec)) {
        return false;
    }
    if (stateOut && rec.hasIdentity) {
        *stateOut = rec.identity.ocrState;
    }
    if (brandedOut) {
        *brandedOut = RecordIsBranded(rec);
    }
    return rec.hasIdentity;
}

bool LibrarySidecarWriteCover(Str bookPath, Str coverFormat, Str coverData, Str title, Str author, Str series,
                              int year) {
    const char* fmt = NormaliseFormat(coverFormat.s);
    if (!fmt) {
        fmt = DetectImageFormat(coverData);
    }
    if (!fmt || coverData.len <= 0) {
        return false;
    }
    BookBlobRecord rec;
    if (!LibrarySidecarReadRecord(bookPath, rec)) {
        return false;
    }
    SetRecordBookInfo(rec, bookPath, title, author, series, year);
    rec.hasCover = true;
    rec.cover.kind = kBlobCoverImage;
    rec.cover.format = rec.strings.Append(Str(fmt)).s;
    rec.cover.data.Reset();
    rec.cover.data.Append((const u8*)coverData.s, coverData.len);
    Str err;
    bool ok = LibrarySidecarWriteRecord(bookPath, rec, &err);
    if (!ok) {
        logf("LibrarySidecar: could not write a cover for '%s': %s\n", bookPath, err);
    }
    str::Free(err);
    return ok;
}

bool LibrarySidecarWriteCoverSpot(Str bookPath, int pageNo, RectF rect, int rotation, Str coverData, Str title,
                                  Str author, Str series, int year) {
    BookBlobRecord rec;
    if (!LibrarySidecarReadRecord(bookPath, rec)) {
        return false;
    }
    SetRecordBookInfo(rec, bookPath, title, author, series, year);
    rec.hasCover = true;
    rec.cover.kind = kBlobCoverPage;
    const char* fmt = DetectImageFormat(coverData);
    rec.cover.format = fmt ? rec.strings.Append(Str(fmt)).s : nullptr;
    rec.cover.data.Reset();
    if (coverData.len > 0) {
        rec.cover.data.Append((const u8*)coverData.s, coverData.len);
    }
    rec.cover.page = pageNo;
    rec.cover.x0 = rect.x;
    rec.cover.y0 = rect.y;
    rec.cover.x1 = rect.Right();
    rec.cover.y1 = rect.Bottom();
    rec.cover.rotation = rotation;
    Str err;
    bool ok = LibrarySidecarWriteRecord(bookPath, rec, &err);
    if (!ok) {
        logf("LibrarySidecar: could not write a cover position for '%s': %s\n", bookPath, err);
    }
    str::Free(err);
    return ok;
}

bool LibrarySidecarForgetCover(Str bookPath) {
    BookBlobRecord rec;
    if (!LibrarySidecarReadRecord(bookPath, rec)) {
        DeleteSidecarFile(bookPath);
        return true;
    }
    rec.hasCover = false;
    rec.cover.data.Reset();
    rec.cover.format = nullptr;
    rec.cover.kind = kBlobCoverNone;
    rec.cover.page = 0;
    rec.cover.x0 = rec.cover.y0 = rec.cover.x1 = rec.cover.y1 = 0;
    rec.cover.rotation = 0;
    Str err;
    bool ok = LibrarySidecarWriteRecord(bookPath, rec, &err);
    if (!ok) {
        logf("LibrarySidecar: could not remove the cover for '%s': %s\n", bookPath, err);
    }
    str::Free(err);
    return ok;
}

bool LibrarySidecarWriteInfo(Str bookPath, Str title, Str author, Str series) {
    if (title.len == 0 && author.len == 0 && series.len == 0) {
        return false;
    }
    BookBlobRecord rec;
    if (!LibrarySidecarReadRecord(bookPath, rec)) {
        return false;
    }
    if (title.len > 0) {
        rec.identity.title = rec.strings.Append(title).s;
    }
    if (author.len > 0) {
        rec.identity.author = rec.strings.Append(author).s;
    }
    if (series.len > 0) {
        rec.hasShelf = true;
        rec.shelf.series = rec.strings.Append(series).s;
    }
    Str err;
    bool ok = LibrarySidecarWriteRecord(bookPath, rec, &err);
    if (!ok) {
        logf("LibrarySidecar: could not write metadata for '%s': %s\n", bookPath, err);
    }
    str::Free(err);
    return ok;
}

bool LibrarySidecarWriteMetadata(Str bookPath, Str title, Str author, Str series, Str seriesParent, Str genre,
                                 Str subgenre, Str tags, Str partitions, int seriesIndex, int year, int pages,
                                 const BlobStats* stats) {
    return LibrarySidecarWriteMetadataWithRec(bookPath, nullptr, title, author, series, seriesParent, genre, subgenre,
                                              tags, partitions, seriesIndex, year, pages, stats);
}

bool LibrarySidecarWriteMetadataWithRec(Str bookPath, const BookBlobRecord* existingRec, Str title, Str author,
                                        Str series, Str seriesParent, Str genre, Str subgenre, Str tags, Str partitions,
                                        int seriesIndex, int year, int pages, const BlobStats* stats) {
    BookBlobRecord rec;
    // existingRec is a chance to skip one LibrarySidecarReadRecord per book
    // per model load (chunk 30). On a 231-book warm load that drops the
    // SyncEmbeddedRecords sweep's read count from N to 0 — the adopt pass
    // already read each record a moment earlier, and AdoptEmbeddedRecordFields
    // is happy to share it with us. Callers that have no cached record pass
    // nullptr, and we fall back to the read path exactly like before.
    bool had = false;
    if (existingRec) {
        had = true;
    } else {
        had = LibrarySidecarReadRecord(bookPath, rec);
    }
    const BookBlobRecord& base = existingRec ? *existingRec : rec;
    const char* why = nullptr;
    if (!had) {
        return false;
    } else {
        str::Builder haveTags;
        if (base.hasShelf) {
            for (const char* tag : base.shelf.tags) {
                if (len(haveTags) > 0) {
                    haveTags.Append(StrL(";"));
                }
                haveTags.Append(Str(tag));
            }
        }
        str::Builder havePartitions;
        if (base.hasShelf) {
            for (const char* partition : base.shelf.partitions) {
                if (len(havePartitions) > 0) {
                    havePartitions.Append(StrL(";"));
                }
                havePartitions.Append(Str(partition));
            }
        }
        if (!str::Eq(Str(base.identity.title ? base.identity.title : ""), title)) {
            why = "title";
        } else if (!str::Eq(Str(base.identity.author ? base.identity.author : ""), author)) {
            why = "author";
        } else if (!base.hasShelf) {
            why = "no shelf block";
        } else if (!str::Eq(Str(base.shelf.series ? base.shelf.series : ""), series)) {
            why = "series";
        } else if (!str::Eq(Str(base.shelf.seriesParent ? base.shelf.seriesParent : ""), seriesParent)) {
            why = "series parent";
        } else if (!str::Eq(Str(base.shelf.genre ? base.shelf.genre : ""), genre)) {
            why = "genre";
        } else if (!str::Eq(Str(base.shelf.subgenre ? base.shelf.subgenre : ""), subgenre)) {
            why = "subgenre";
        } else if (!str::Eq(ToStr(haveTags), tags)) {
            why = "tags";
        } else if (!str::Eq(ToStr(havePartitions), partitions)) {
            why = "partitions";
        } else if (base.shelf.seriesIndex != seriesIndex) {
            why = "series index";
        } else if (base.identity.year != year) {
            // Not `year > 0 &&`: the caller passes 0 to say "this year is not
            // the user's any more, take it out of the record". Skipping the
            // write in that case would leave the old year behind, still
            // looking manual.
            why = "year";
        } else if (pages > 0 && base.identity.pages != pages) {
            why = "pages";
        } else if (stats && (!base.hasStats || 0 != memcmp(&base.stats, stats, sizeof(*stats)))) {
            why = "reading stats";
        }
    }
    if (!why) {
        return true;
    }
    logf("LibrarySidecarWriteMetadata: rewriting %s because the %s differs\n", bookPath, Str(why));
    if (existingRec) {
        BookBlobRecordClone(*existingRec, rec);
    }
    SetRecordBookInfo(rec, bookPath, title, author, series, year);
    rec.identity.title = len(title) > 0 ? rec.strings.Append(title).s : nullptr;
    rec.identity.author = len(author) > 0 ? rec.strings.Append(author).s : nullptr;
    rec.identity.year = year;
    rec.hasShelf = true;
    rec.shelf.series = len(series) > 0 ? rec.strings.Append(series).s : nullptr;
    rec.shelf.seriesParent = len(seriesParent) > 0 ? rec.strings.Append(seriesParent).s : nullptr;
    rec.shelf.genre = len(genre) > 0 ? rec.strings.Append(genre).s : nullptr;
    rec.shelf.subgenre = len(subgenre) > 0 ? rec.strings.Append(subgenre).s : nullptr;
    rec.shelf.seriesIndex = seriesIndex;
    rec.shelf.tags.Reset();
    if (len(tags) > 0) {
        StrVec parts;
        Split(&parts, tags, StrL(";"), true);
        for (Str tag : parts) {
            rec.shelf.tags.Append(rec.strings.Append(tag).s);
        }
    }
    rec.shelf.partitions.Reset();
    if (len(partitions) > 0) {
        StrVec parts;
        Split(&parts, partitions, StrL(";"), true);
        for (Str partition : parts) {
            rec.shelf.partitions.Append(rec.strings.Append(partition).s);
        }
    }
    if (pages > 0) {
        rec.identity.pages = pages;
    }
    if (stats) {
        rec.hasStats = true;
        rec.stats = *stats;
    }
    Str err;
    bool ok = LibrarySidecarWriteRecord(bookPath, rec, &err);
    if (!ok) {
        logf("LibrarySidecar: could not write metadata for '%s': %s\n", bookPath, err);
    }
    str::Free(err);
    return ok;
}

int LibrarySidecarBrandIfUnbranded(Str bookPath, LibrarySidecarProgressCb progressCb, void* progressCtx, bool runOcr) {
    BookBlobRecord rec;
    bool found = ReadUncheckedRecord(bookPath, rec);
    if (found && RecordIsBranded(rec)) {
        return 0;
    }
    if (found && RecordHasNoTextState(rec)) {
        return 0;
    }
    if (found && RecordHasEmptyTextBrand(rec)) {
        rec.identity.fingerprint = nullptr;
        rec.identity.textMd5.Reset();
        rec.identity.textLength = 0;
        rec.identity.ocrState = kBookOcrNoText;
        Str err;
        bool ok = WriteNoTextRecord(bookPath, rec, &err);
        if (!ok) {
            logf("LibrarySidecar: could not replace empty OCR brand '%s': %s\n", bookPath, err);
        }
        str::Free(err);
        return ok ? 1 : -1;
    }
    if (!found) {
        BookBlobRecordReset(rec);
    }
    BookFingerprint fp;
    if (!BookFingerprintOfFile(bookPath, fp, 0, false, runOcr, progressCb, progressCtx)) {
        logf("LibrarySidecar: unreadable book, not branded: '%s'\n", bookPath);
        return -1;
    }
    rec.hasIdentity = true;
    rec.identity.fingerprint = nullptr;
    rec.identity.textMd5.Reset();
    rec.identity.textLength = 0;
    rec.identity.pages = fp.pages;
    rec.identity.ocrState = fp.ocrState;
    if (len(fp.fingerprint) > 0) {
        rec.identity.fingerprint = rec.strings.Append(fp.fingerprint).s;
        for (int i = 0; i < 16; i++) {
            rec.identity.textMd5.Append(fp.textMd5[i]);
        }
        rec.identity.textLength = fp.textLength;
    }
    BookFingerprintFree(fp);
    if (rec.identity.ocrState == kBookOcrEngineUnavailable) {
        return -1;
    }
    Str err;
    bool ok = RecordHasNoTextState(rec) ? WriteNoTextRecord(bookPath, rec, &err)
                                         : LibrarySidecarWriteRecord(bookPath, rec, &err);
    if (!ok) {
        logf("LibrarySidecar: could not brand '%s': %s\n", bookPath, err);
    }
    str::Free(err);
    return ok ? 1 : -1;
}

int LibrarySidecarRunOcrMigration(Str bookPath) {
    BookBlobRecord rec;
    if (!ReadUncheckedRecord(bookPath, rec)) {
        BookBlobRecordReset(rec);
    }
    if (RecordIsBranded(rec)) {
        return 0;
    }
    if (rec.identity.ocrState == kBookOcrSuccess || rec.identity.ocrState == kBookOcrNoText) {
        return 0;
    }
    BookFingerprint fp;
    if (!BookFingerprintOfFile(bookPath, fp, 0, false, true)) {
        BookFingerprintFree(fp);
        return -1;
    }
    rec.hasIdentity = true;
    rec.identity.fingerprint = nullptr;
    rec.identity.textMd5.Reset();
    rec.identity.textLength = 0;
    rec.identity.pages = fp.pages;
    rec.identity.ocrState = fp.ocrState;
    if (len(fp.fingerprint) == 0) {
        BookFingerprintFree(fp);
        if (fp.ocrState == kBookOcrEngineUnavailable) {
            return -1;
        }
        Str err;
        bool ok = WriteNoTextRecord(bookPath, rec, &err);
        if (!ok) {
            logf("LibrarySidecar: could not write OCR no-text for '%s': %s\n", bookPath, err);
        }
        str::Free(err);
        BookFingerprintFree(fp);
        return ok ? 1 : -1;
    }
    rec.identity.fingerprint = rec.strings.Append(fp.fingerprint).s;
    for (int i = 0; i < 16; i++) {
        rec.identity.textMd5.Append(fp.textMd5[i]);
    }
    rec.identity.textLength = fp.textLength;
    Str err;
    bool ok = LibrarySidecarWriteRecord(bookPath, rec, &err);
    if (!ok) {
        logf("LibrarySidecar: could not write OCR brand for '%s': %s\n", bookPath, err);
    }
    str::Free(err);
    BookFingerprintFree(fp);
    return ok ? 1 : -1;
}
