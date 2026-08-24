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

static bool RecordMatchesBook(Str bookPath, const BookBlobRecord& rec);

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

bool WriteZipRecord(Str bookPath, Str blob, Str* errorOut) {
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
    if (!ReadZipRecord(temp, check) || !RecordMatchesBook(bookPath, check)) {
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

static bool RecordMatchesBook(Str bookPath, const BookBlobRecord& rec) {
    if (!rec.hasIdentity || !rec.identity.fingerprint) {
        return false;
    }
    BookFingerprint fp;
    if (!BookFingerprintOfFile(bookPath, fp, 0)) {
        return false;
    }
    bool same = str::Eq(Str(rec.identity.fingerprint), fp.fingerprint);
    BookFingerprintFree(fp);
    return same;
}

static bool SetRecordIdentity(Str bookPath, BookBlobRecord& rec) {
    BookFingerprint fp;
    if (!BookFingerprintOfFile(bookPath, fp, 0)) {
        return false;
    }
    rec.hasIdentity = true;
    rec.identity.fingerprint = rec.strings.Append(fp.fingerprint).s;
    for (int i = 0; i < 16; i++) {
        rec.identity.textMd5.Append(fp.textMd5[i]);
    }
    rec.identity.textLength = fp.textLength;
    rec.identity.pages = fp.pages;
    BookFingerprintFree(fp);
    return true;
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
    if (IsPdf(bookPath)) {
        if (PdfSidecarReadRecord(bookPath, recordOut) && RecordMatchesBook(bookPath, recordOut)) {
            SetLegacyPdfCover(bookPath, recordOut);
            return true;
        }
    }
    if (IsZipBook(bookPath) && ReadZipRecord(bookPath, recordOut) && RecordMatchesBook(bookPath, recordOut)) {
        return true;
    }
    TempStr path = SidecarFilePath(bookPath);
    Str blob = file::ReadFile(path);
    if (len(blob) == 0) {
        str::Free(blob);
        if (!IsPdf(bookPath) || !SetRecordIdentity(bookPath, recordOut)) {
            return false;
        }
        return SetLegacyPdfCover(bookPath, recordOut);
    }
    bool ok = BookBlobDecode((const u8*)blob.s, blob.len, recordOut) && RecordMatchesBook(bookPath, recordOut);
    str::Free(blob);
    if (ok && IsPdf(bookPath)) {
        SetLegacyPdfCover(bookPath, recordOut);
    }
    return ok;
}

static bool WriteRawRecord(Str bookPath, Str blob, Str* errorOut) {
    TempStr path = SidecarFilePath(bookPath);
    TempStr temp = str::FormatTemp("%s.tmp", path);
    if (!file::WriteFile(temp, blob)) {
        SidecarSetError(errorOut, str::FormatTemp("could not write %s", temp));
        return false;
    }
    Str check = file::ReadFile(temp);
    BookBlobRecord readBack;
    bool valid = len(check) == len(blob) && memcmp(check.s, blob.s, (size_t)blob.len) == 0 &&
                 BookBlobDecode((const u8*)check.s, check.len, readBack) && RecordMatchesBook(bookPath, readBack);
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
    if (errorOut) {
        str::ReplaceWithCopy(errorOut, {});
    }
    TempStr invalid = BookRecordWhyInvalid(record);
    if (invalid) {
        SidecarSetError(errorOut, invalid);
        return false;
    }
    if (!RecordMatchesBook(bookPath, record)) {
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
    if (IsZipBook(bookPath) && WriteZipRecord(bookPath, blob, errorOut)) {
        DeleteSidecarFile(bookPath);
        return true;
    }
    return WriteRawRecord(bookPath, blob, errorOut);
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
    if (!IsPdf(bookPath)) {
        return false;
    }
    TempStr fp = PdfSidecarReadFingerprint(bookPath);
    if (!fp) {
        return false;
    }
    *fingerprintOut = str::Dup(fp);
    return true;
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
        BookFingerprint fp;
        if (!BookFingerprintOfFile(bookPath, fp, 0)) {
            return false;
        }
        rec.hasIdentity = true;
        rec.identity.fingerprint = rec.strings.Append(fp.fingerprint).s;
        for (int i = 0; i < 16; i++) {
            rec.identity.textMd5.Append(fp.textMd5[i]);
        }
        rec.identity.textLength = fp.textLength;
        rec.identity.pages = fp.pages;
        BookFingerprintFree(fp);
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
        BookFingerprint fp;
        if (!BookFingerprintOfFile(bookPath, fp, 0)) {
            return false;
        }
        rec.hasIdentity = true;
        rec.identity.fingerprint = rec.strings.Append(fp.fingerprint).s;
        for (int i = 0; i < 16; i++) {
            rec.identity.textMd5.Append(fp.textMd5[i]);
        }
        rec.identity.textLength = fp.textLength;
        rec.identity.pages = fp.pages;
        BookFingerprintFree(fp);
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
        BookFingerprint fp;
        if (!BookFingerprintOfFile(bookPath, fp, 0)) {
            return false;
        }
        rec.hasIdentity = true;
        rec.identity.fingerprint = rec.strings.Append(fp.fingerprint).s;
        for (int i = 0; i < 16; i++) {
            rec.identity.textMd5.Append(fp.textMd5[i]);
        }
        rec.identity.textLength = fp.textLength;
        rec.identity.pages = fp.pages;
        BookFingerprintFree(fp);
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
    BookBlobRecord rec;
    bool had = LibrarySidecarReadRecord(bookPath, rec);
    bool changed = !had;
    if (!had) {
        if (!SetRecordIdentity(bookPath, rec)) {
            return false;
        }
    } else {
        changed = changed || !str::Eq(Str(rec.identity.title ? rec.identity.title : ""), title);
        changed = changed || !str::Eq(Str(rec.identity.author ? rec.identity.author : ""), author);
        changed = changed || !rec.hasShelf || !str::Eq(Str(rec.shelf.series ? rec.shelf.series : ""), series);
        changed = changed || !rec.hasShelf ||
                  !str::Eq(Str(rec.shelf.seriesParent ? rec.shelf.seriesParent : ""), seriesParent);
        changed = changed || !rec.hasShelf || !str::Eq(Str(rec.shelf.genre ? rec.shelf.genre : ""), genre);
        changed = changed || !rec.hasShelf || !str::Eq(Str(rec.shelf.subgenre ? rec.shelf.subgenre : ""), subgenre);
        str::Builder haveTags;
        if (rec.hasShelf) {
            for (const char* tag : rec.shelf.tags) {
                if (len(haveTags) > 0) {
                    haveTags.Append(StrL(";"));
                }
                haveTags.Append(Str(tag));
            }
        }
        changed = changed || !str::Eq(ToStr(haveTags), tags);
        str::Builder havePartitions;
        if (rec.hasShelf) {
            for (const char* partition : rec.shelf.partitions) {
                if (len(havePartitions) > 0) {
                    havePartitions.Append(StrL(";"));
                }
                havePartitions.Append(Str(partition));
            }
        }
        changed = changed || !str::Eq(ToStr(havePartitions), partitions);
        changed = changed || !rec.hasShelf || rec.shelf.seriesIndex != seriesIndex;
        // Not `year > 0 &&`: the caller passes 0 to say "this year is not the
        // user's any more, take it out of the record". Skipping the write in
        // that case would leave the old year behind, still looking manual.
        changed = changed || rec.identity.year != year;
        changed = changed || (pages > 0 && rec.identity.pages != pages);
        changed = changed || (stats && (!rec.hasStats || 0 != memcmp(&rec.stats, stats, sizeof(*stats))));
    }
    if (!changed) {
        return true;
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
