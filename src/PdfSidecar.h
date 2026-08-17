/* Copyright 2026 the SumatraPDF project authors (see AUTHORS file).
   License: Simplified BSD (see COPYING.BSD) */

struct fz_context;
struct pdf_document;

constexpr const char* kSidecarApp = "SumatraPDF";
constexpr int kSidecarVersion = 1;
constexpr const char* kInfoFingerprint = "SumatraFingerprint";
constexpr const char* kInfoSeries = "SumatraSeries";

struct PdfInfoField {
    const char* key;
    const char* value;
};

void PdfSidecarBlobXrefs(fz_context* ctx, pdf_document* pdf, Vec<int>& out);

TempStr PdfSidecarDate(i64 millis = 0);

bool PdfSidecarReadBlob(Str path, Vec<u8>& out);
bool PdfSidecarReadRecord(Str path, BookBlobRecord& out);
TempStr PdfSidecarReadFingerprint(Str path);
bool PdfSidecarHasBlob(Str path);

bool PdfSidecarWriteBlob(Str path, const u8* blob, int size, Str fingerprint, const Vec<PdfInfoField>* info,
                         Str* errOut);
