/* Copyright 2026 the SumatraPDF project authors (see AUTHORS file).
   License: Simplified BSD (see COPYING.BSD) */

constexpr const char* kBookFingerprintVersion = "fp3";
constexpr int kBookIdentityTokenLimit = 32768;
constexpr double kBookRunningLineShare = 0.25;
constexpr int kBookRunningLineMinPages = 4;
constexpr int kBookImageOnlyCharsPerPage = 40;
constexpr int kBookPageHashSide = 8;
constexpr int kBookPageHashScale = 4;
constexpr const char* kBookStextTextOptions = "preserve-ligatures,preserve-whitespace,use-cid-for-unknown-unicode";

constexpr int kBookOcrMinTokensForIdentity = 64;
constexpr int kBookOcrNotAttempted = 0;
constexpr int kBookOcrSuccess = 1;
constexpr int kBookOcrNoText = 2;
constexpr int kBookOcrEngineUnavailable = 3;

struct BookFingerprint {
    Str fingerprint;
    u8 textMd5[16]{};
    i64 textLength = 0;
    i64 identityLength = 0;
    int runningLines = 0;
    Str readingText;
    Str identityText;
    int pages = 0;
    int images = 0;
    bool imageOnly = false;
    Vec<u64> pageHashes;
    int ocrState = kBookOcrNotAttempted;
    int ocrPages = 0;
    int ocrPagesSkipped = 0;
    int ocrTokens = 0;
};

typedef void (*BookFingerprintProgressCb)(int done, int total, void* ctx);

void BookFingerprintFree(BookFingerprint& fp);

bool BookFingerprintOfFile(Str path, BookFingerprint& out, int wantPageHashes = -1, bool keepIdentityText = false,
                            bool runOcr = true, BookFingerprintProgressCb progressCb = nullptr,
                            void* progressCtx = nullptr);
TempStr BookFingerprintOfPath(Str path);
bool BookShapeOfFile(Str path, char shapeOut[33], int* pagesOut);
bool BookFingerprintConfirms(Str path, Str claimed, int claimedPages);
void BookFingerprintPerfCounters(int* fullCalls, int* shapeCalls, int* cacheHits, int* seeded, int* ocrAttempts, int* ocrPages, int* ocrSuccesses, int* ocrNoText);
void BookFingerprintResetCounters();

void BookFingerprintCacheOpen(Str dataDir);
void BookFingerprintCacheClose();

Str BookReadingText(const StrVec& pages);
Str BookIdentityText(const StrVec& pages, int* runningLines = nullptr, int* identityTokens = nullptr);
StrVec BookRunningLines(const StrVec& pages);
bool BookIsImageOnly(const StrVec& pages);

int BookPageHashDistance(u64 a, u64 b);
bool BookPageHashesLookAlike(const Vec<u64>& a, const Vec<u64>& b, int perPage = 10);
