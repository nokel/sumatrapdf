/* Copyright 2026 the SumatraPDF project authors (see AUTHORS file).
   License: GPLv3 */

#include "base/Base.h"
#include "base/File.h"
#include "base/Http.h"
#include "base/JsonParser.h"
#include "JsonVisitor.h"

#include "AppTools.h"

#include "CoverOnline.h"

static OnlineCoverHit* OnlineHitAt(Vec<OnlineCoverHit>& out, int idx) {
    if (idx < 0 || idx >= kOnlineCoverMaxHits) {
        return nullptr;
    }
    while (len(out) <= idx) {
        OnlineCoverHit hit;
        hit.rank = len(out);
        out.Append(hit);
    }
    return &out[idx];
}

struct OnlineCoverParser : JsonVisitor {
    Vec<OnlineCoverHit>* hits = nullptr;
    int wantYear = 0;

    bool Visit(Str path, Str value, json::Type) override {
        if (!str::StartsWith(path, StrL("/docs["))) {
            return true;
        }
        const char* start = path.s + LenL("/docs[");
        int idx = ParseInt(Str(start, path.len - (int)(start - path.s)));
        const char* end = (const char*)memchr(start, ']', path.s + path.len - start);
        if (!end) {
            return true;
        }
        OnlineCoverHit* hit = OnlineHitAt(*hits, idx);
        if (!hit) {
            return true;
        }
        Str tail(end + 1, (int)(path.s + path.len - end - 1));
        int n = ParseInt(value);
        if (str::Eq(tail, StrL("/cover_i"))) {
            hit->coverId = n;
        } else if (str::Eq(tail, StrL("/first_publish_year"))) {
            hit->firstYear = n;
            hit->yearMatches = wantYear > 0 && n == wantYear;
        } else if (str::Eq(tail, StrL("/readinglog_count"))) {
            hit->readingCount = n;
        } else if (str::Eq(tail, StrL("/edition_count"))) {
            hit->editionCount = n;
        } else if (wantYear > 0 && str::StartsWith(tail, StrL("/publish_year[")) && n == wantYear) {
            hit->yearMatches = true;
        }
        return true;
    }
};

void CoverOnlineParseHits(Str json, int wantYear, Vec<OnlineCoverHit>& out) {
    out.Clear();
    OnlineCoverParser parser;
    parser.hits = &out;
    parser.wantYear = wantYear;
    JsonParseWithVisitor(json, &parser);
}

static bool OnlineHitBetter(const OnlineCoverHit& a, const OnlineCoverHit& b, int wantYear) {
    if (wantYear > 0 && a.yearMatches != b.yearMatches) {
        return a.yearMatches;
    }
    if (a.readingCount != b.readingCount) {
        return a.readingCount > b.readingCount;
    }
    if (a.editionCount != b.editionCount) {
        return a.editionCount > b.editionCount;
    }
    return a.rank < b.rank;
}

int CoverOnlinePickHit(const Vec<OnlineCoverHit>& hits, int wantYear) {
    int best = -1;
    for (int i = 0; i < len(hits); i++) {
        if (hits[i].coverId <= 0) {
            continue;
        }
        if (best < 0 || OnlineHitBetter(hits[i], hits[best], wantYear)) {
            best = i;
        }
    }
    return best;
}

TempStr CoverOnlineSearchUrlTemp(Str title, Str author, Str context) {
    TempStr titlePart = URLEncodeMayTruncateTemp(title, 1200);
    TempStr authorPart = URLEncodeMayTruncateTemp(author, 600);
    TempStr contextPart = URLEncodeMayTruncateTemp(context, 600);
    str::Builder query;
    query.Append(fmt("https://openlibrary.org/search.json?title=%s", titlePart));
    if (len(authorPart) > 0) {
        query.Append(fmt("&author=%s", authorPart));
    }
    if (len(contextPart) > 0) {
        query.Append(fmt("&q=%s", contextPart));
    }
    query.Append(fmt("&fields=cover_i,first_publish_year,publish_year,edition_count,readinglog_count&limit=%d&lang=en",
                     kOnlineCoverMaxHits));
    return str::DupTemp(ToStr(query));
}

TempStr CoverOnlineImageUrlTemp(int coverId) {
    return fmt("https://covers.openlibrary.org/b/id/%d-L.jpg?default=false", coverId);
}

// Disk cache TTLs — ported from the Android port (Net.kt) so an offline
// phone or a flaky network doesn't redownload the same image for every
// visible book on every launch.
static constexpr u64 kOnlineJsonTtlMs = 30ULL * 24 * 3600 * 1000;   // 30 days for hits
static constexpr u64 kOnlineMissTtlMs = 3ULL * 24 * 3600 * 1000;    // 3 days for misses
static constexpr u64 kOnlineOfflineAfter = 3;
static constexpr u64 kOnlineOfflineForMs = 120 * 1000;              // 2 min after 3 fails
static constexpr u64 kOnlineHostGapMs = 1000;                        // 1s between host hits
static constexpr int kOnlineMaxHostStrikes = 3;
static constexpr u64 kOnlineHostRestMs = 30ULL * 60 * 1000;         // 30 min after 3 strikes

static Mutex gOnlineCoverMutex;
static u64 gOnlineCoverAt = 0;          // next earliest allowed request tick
static int gOnlineHostStrikes = 0;        // consecutive failures
static u64 gOnlineOfflineUntil = 0;       // 0 = online, else GetTickCount64() deadline
static u64 gOnlineHostRestUntil = 0;      // back-off after a strike

// Hash a URL to a hex filename. We use the FNV-1a 64-bit hash because the
// Base.h helpers do not ship a public hash function and pulling in a new
// dependency for cache keys is overkill.
static u64 Fnv1a64(const char* s, int n) {
    u64 h = 0xcbf29ce484222325ULL;
    for (int i = 0; i < n; i++) {
        h ^= (u8)s[i];
        h *= 0x100000001b3ULL;
    }
    return h;
}

static TempStr CoverOnlineCachePath(const char* tag, Str url) {
    // Layout: <appdata>/cover-online/<tag>-<hash>.bin. The tag lets us
    // invalidate search vs image without re-hashing the whole URL.
    u64 h = Fnv1a64(url.s, url.len);
    char hex[20];
    snprintf(hex, sizeof(hex), "%s-%016llx", tag, (unsigned long long)h);
    return path::JoinTemp(GetAppDataDirTemp(), StrL("cover-online"), Str(hex));
}

// Returns the cached body if present and not expired. empty Str = no
// usable cache entry. The caller distinguishes "fresh hit" from "stale
// miss" by checking the file size separately if needed.
static Str CoverOnlineCacheRead(Str path, u64 ttlMs) {
    if (!file::Exists(path)) {
        return {};
    }
    WIN32_FILE_ATTRIBUTE_DATA fad{};
    if (!path::GetCachedAttributesEx(path, &fad)) {
        return {};
    }
    FILETIME ftNow{};
    GetSystemTimeAsFileTime(&ftNow);
    ULARGE_INTEGER a{}, b{};
    a.LowPart = fad.ftLastWriteTime.dwLowDateTime;
    a.HighPart = fad.ftLastWriteTime.dwHighDateTime;
    b.LowPart = ftNow.dwLowDateTime;
    b.HighPart = ftNow.dwHighDateTime;
    u64 ageMs = (b.QuadPart - a.QuadPart) / 10000ULL;
    if (ageMs > ttlMs) {
        return {};
    }
    return file::ReadFile(path);
}

static void CoverOnlineCacheWrite(Str path, Str data) {
    TempStr dir = path::GetDirTemp(path);
    dir::CreateForFile(dir);
    file::WriteFile(path, data);
}

static void CoverOnlineCacheWriteMiss(Str path) {
    // Empty file = "we already tried, don't try again soon".
    CoverOnlineCacheWrite(path, StrL(""));
}

static void CoverOnlineWait() {
    ScopedMutex lock(&gOnlineCoverMutex);
    if (gOnlineOfflineUntil > 0) {
        u64 now = GetTickCount64();
        if (now < gOnlineOfflineUntil) {
            // offline; do not make any request
            return;
        }
        gOnlineOfflineUntil = 0;
    }
    u64 now = GetTickCount64();
    if (gOnlineHostRestUntil > now) {
        Sleep((DWORD)(gOnlineHostRestUntil - now));
    }
    if (gOnlineCoverAt > now) {
        Sleep((DWORD)std::min<u64>(gOnlineCoverAt - now, 20000));
    }
    gOnlineCoverAt = GetTickCount64() + kOnlineHostGapMs;
}

static void CoverOnlineRecordFailure() {
    ScopedMutex lock(&gOnlineCoverMutex);
    gOnlineHostStrikes++;
    if (gOnlineHostStrikes >= kOnlineMaxHostStrikes) {
        // Three strikes, you are out. Per the Android port, go offline
        // for two minutes AND back off the host for 30 more. This is
        // the single biggest win on a phone with a flaky network: the
        // radio stops being pummeled while the user is on the train.
        gOnlineOfflineUntil = GetTickCount64() + kOnlineOfflineForMs;
        gOnlineHostRestUntil = GetTickCount64() + kOnlineHostRestMs;
        gOnlineHostStrikes = 0;
    }
}

static void CoverOnlineRecordSuccess() {
    ScopedMutex lock(&gOnlineCoverMutex);
    gOnlineHostStrikes = 0;
    gOnlineHostRestUntil = 0;
}

static int CoverOnlineFind(Str title, Str author, Str context, int year, Vec<OnlineCoverHit>& hits) {
    // Cache the search result. Re-asking openlibrary for the same
    // title/author for a different book is a real scenario: most of
    // the Animorphs share a search, every Discworld novel does, etc.
    TempStr searchUrl = CoverOnlineSearchUrlTemp(title, author, context);
    TempStr cachePath = CoverOnlineCachePath("s", searchUrl);
    Str cached = CoverOnlineCacheRead(cachePath, kOnlineJsonTtlMs);
    if (cached.s) {
        if (len(cached) == 0) {
            // cached miss
            return -1;
        }
        CoverOnlineParseHits(cached, year, hits);
        return CoverOnlinePickHit(hits, year);
    }
    CoverOnlineWait();
    HttpRsp search;
    if (!HttpGet(searchUrl, &search) || !IsHttpRspOk(&search)) {
        CoverOnlineRecordFailure();
        CoverOnlineCacheWriteMiss(cachePath);
        return -1;
    }
    CoverOnlineRecordSuccess();
    Str body = ToStr(search.data);
    CoverOnlineCacheWrite(cachePath, body);
    CoverOnlineParseHits(body, year, hits);
    return CoverOnlinePickHit(hits, year);
}

Str CoverOnlineFetch(Str title, Str author, Str context, int year) {
    if (len(title) == 0) {
        return {};
    }
    {
        ScopedMutex lock(&gOnlineCoverMutex);
        if (gOnlineOfflineUntil > 0 && GetTickCount64() < gOnlineOfflineUntil) {
            return {};
        }
    }
    Vec<OnlineCoverHit> hits;
    int best = CoverOnlineFind(title, author, context, year, hits);
    if (best < 0 && len(author) > 0) {
        best = CoverOnlineFind(title, {}, context, year, hits);
    }
    if (best < 0) {
        return {};
    }
    TempStr imageUrl = CoverOnlineImageUrlTemp(hits[best].coverId);
    TempStr cachePath = CoverOnlineCachePath("i", imageUrl);
    Str cached = CoverOnlineCacheRead(cachePath, kOnlineJsonTtlMs);
    if (cached.s) {
        if (len(cached) == 0) {
            return {};
        }
        if (len(cached) < 1200 || len(cached) > kOnlineCoverMaxBytes) {
            return {};
        }
        return str::Dup(cached);
    }
    CoverOnlineWait();
    HttpRsp image;
    if (!HttpGet(imageUrl, &image) || !IsHttpRspOk(&image)) {
        CoverOnlineRecordFailure();
        CoverOnlineCacheWriteMiss(cachePath);
        return {};
    }
    CoverOnlineRecordSuccess();
    Str data = ToStr(image.data);
    if (len(data) < 1200 || len(data) > kOnlineCoverMaxBytes) {
        CoverOnlineCacheWriteMiss(cachePath);
        return {};
    }
    CoverOnlineCacheWrite(cachePath, data);
    return str::Dup(data);
}
