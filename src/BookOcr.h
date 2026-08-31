/* Copyright 2026 the SumatraPDF project authors (see AUTHORS file).
   License: Simplified BSD (see COPYING.BSD) */

struct fz_context;
struct fz_document;

struct OcrResult {
    bool tessdataFound = false;
    bool ocrAttempted = false;
    bool ocrSucceeded = false;
    int pagesOcred = 0;
    int pagesSkipped = 0;
    int recognizedTokens = 0;
    int totalPages = 0;
    bool cancelled = false;
    Str collectedText;
    StrVec pageTexts;
};

TempStr BookOcrTessdataPath();
OcrResult BookOcrRun(fz_context* ctx, fz_document* doc, const char* tessdataPath);
void BookOcrSetMaxBookMs(i64 ms);
i64 BookOcrGetMaxBookMs();
