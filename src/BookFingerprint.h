/* Copyright 2026 the SumatraPDF project authors (see AUTHORS file).
   License: Simplified BSD (see COPYING.BSD) */

constexpr const char* kBookFingerprintVersion = "fp2";
constexpr double kBookRunningLineShare = 0.25;
constexpr int kBookRunningLineMinPages = 4;
constexpr int kBookImageOnlyCharsPerPage = 40;
constexpr int kBookPageHashSide = 8;
constexpr int kBookPageHashScale = 4;
constexpr const char* kBookStextTextOptions = "preserve-ligatures,preserve-whitespace,use-cid-for-unknown-unicode";

struct BookFingerprint {
    Str fingerprint;
    u8 textMd5[16]{};
    i64 textLength = 0;
    Str readingText;
    int pages = 0;
    int images = 0;
    bool imageOnly = false;
    Vec<u64> pageHashes;
};

void BookFingerprintFree(BookFingerprint& fp);

bool BookFingerprintOfFile(Str path, BookFingerprint& out, int wantPageHashes = -1);
TempStr BookFingerprintOfPath(Str path);

Str BookReadingText(const StrVec& pages);
Str BookIdentityText(const StrVec& pages);
StrVec BookRunningLines(const StrVec& pages);
bool BookIsImageOnly(const StrVec& pages);

int BookPageHashDistance(u64 a, u64 b);
bool BookPageHashesLookAlike(const Vec<u64>& a, const Vec<u64>& b, int perPage = 10);
