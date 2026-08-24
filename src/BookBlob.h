/* Copyright 2026 the SumatraPDF project authors (see AUTHORS file).
   License: Simplified BSD (see COPYING.BSD) */

constexpr int kBookBlobVersion = 1;

constexpr int kBlobSectionIdentity = 1;
constexpr int kBlobSectionShelf = 2;
constexpr int kBlobSectionCover = 3;
constexpr int kBlobSectionCharacters = 4;
constexpr int kBlobSectionSpeakers = 5;
constexpr int kBlobSectionLore = 6;
constexpr int kBlobSectionChapters = 7;
constexpr int kBlobSectionAdaptations = 8;
constexpr int kBlobSectionStats = 9;
constexpr int kBlobSectionEntities = 10;

constexpr int kBlobCoverNone = 0;
constexpr int kBlobCoverImage = 1;
constexpr int kBlobCoverPage = 2;

constexpr int kBlobOffsetBookNlpTokens = 0;
constexpr int kBlobOffsetTextBytes = 1;

constexpr u32 kBookBlobDictSize = 1u << 22;

struct BlobIdentity {
    const char* fingerprint = nullptr;
    Vec<u8> textMd5;
    i64 textLength = 0;
    int offsetBasis = kBlobOffsetBookNlpTokens;
    const char* title = nullptr;
    const char* author = nullptr;
    const char* source = nullptr;
    int pages = 0;
    int year = 0;
};

struct BlobShelf {
    const char* genre = nullptr;
    const char* subgenre = nullptr;
    const char* series = nullptr;
    const char* seriesParent = nullptr;
    int seriesIndex = -1;
    const char* collection = nullptr;
    Vec<const char*> partitions;
    Vec<const char*> tags;
};

struct BlobCover {
    int kind = kBlobCoverNone;
    const char* format = nullptr;
    Vec<u8> data;
    int page = 0;
    double x0 = 0;
    double y0 = 0;
    double x1 = 0;
    double y1 = 0;
    int rotation = 0;
};

struct BlobPerson {
    const char* name;
    int lines;
    const char* voice;
    int aliasAt;
    int aliasCount;
};

struct BlobCast {
    const char* narratorVoice = nullptr;
    Vec<BlobPerson> people;
    Vec<const char*> aliases;
};

struct BlobQuote {
    int start;
    int end;
    int mentionStart;
    int mentionEnd;
    int character;
};

struct BlobMention {
    int start;
    int end;
    int coref;
    const char* prop;
    const char* cat;
};

struct BlobEvidence {
    int offset;
    int page;
    int para;
    const char* note;
};

struct BlobFact {
    const char* subject;
    const char* predicate;
    const char* object;
    double confidence;
    int count;
    bool inferred;
    int evidenceAt;
    int evidenceCount;
};

struct BlobChapter {
    const char* title;
    int offset;
    int page;
    int depth;
};

struct BlobShow {
    const char* title;
    const char* kind;
    int year;
    const char* ref;
};

struct BlobStats {
    i64 lastReadAt = 0;
    i64 timeSpentMs = 0;
    i64 openCount = 0;
    i64 pageNo = 0;
    i64 scrollX = 0;
    i64 scrollY = 0;
    i64 percentRead = 0;
    i64 unit = 0;
};

struct BookBlobRecord {
    StrVec strings;

    bool hasIdentity = false;
    BlobIdentity identity;

    bool hasShelf = false;
    BlobShelf shelf;

    bool hasCover = false;
    BlobCover cover;

    bool hasCast = false;
    BlobCast cast;

    Vec<BlobQuote> speakers;
    Vec<BlobMention> entities;
    Vec<BlobFact> lore;
    Vec<BlobEvidence> evidence;
    Vec<BlobChapter> chapters;
    Vec<BlobShow> adaptations;

    bool hasStats = false;
    BlobStats stats;
};

bool BookBlobPayload(const BookBlobRecord& rec, Vec<u8>& out);
bool BookBlobUnpayload(const u8* data, int size, BookBlobRecord& out);
bool BookBlobCompress(const u8* data, int size, Vec<u8>& out);
bool BookBlobDecompress(const u8* data, int size, int rawSize, Vec<u8>& out);
bool BookBlobEncode(const BookBlobRecord& rec, Vec<u8>& out);
bool BookBlobDecode(const u8* data, int size, BookBlobRecord& out);

void BookBlobTextGuard(Str text, u8 digest[16]);
bool BookBlobGuardMatches(const BookBlobRecord& rec, Str text);

constexpr int kBookFieldMaxBytes = 4096;
constexpr int kBookCoverMinBytes = 1200;
constexpr int kBookCoverMaxBytes = 4 << 20;

const char* BookCoverFormatOfBytes(const u8* data, int size);

TempStr BookRecordWhyInvalid(const BookBlobRecord& rec);

TempStr BookCoverSpotEncode(const BlobCover& cover);
bool BookCoverSpotDecode(Str s, BlobCover* out);
