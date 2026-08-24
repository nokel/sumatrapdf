/* Copyright 2026 the SumatraPDF project authors (see AUTHORS file).
   License: GPLv3 */

constexpr int kOnlineCoverMaxHits = 20;
constexpr int kOnlineCoverMaxBytes = 8 << 20;

struct OnlineCoverHit {
    int coverId = 0;
    int firstYear = 0;
    int readingCount = 0;
    int editionCount = 0;
    int rank = 0;
    bool yearMatches = false;
};

void CoverOnlineParseHits(Str json, int wantYear, Vec<OnlineCoverHit>& out);
int CoverOnlinePickHit(const Vec<OnlineCoverHit>& hits, int wantYear);
TempStr CoverOnlineSearchUrlTemp(Str title, Str author, Str context);
TempStr CoverOnlineImageUrlTemp(int coverId);

Str CoverOnlineFetch(Str title, Str author, Str context, int year);
