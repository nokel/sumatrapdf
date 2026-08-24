/* Copyright 2026 the SumatraPDF project authors (see AUTHORS file).
   License: Simplified BSD (see COPYING.BSD) */

#include "base/Base.h"
#include "base/Pixmap.h"

#include "CoverVision.h"

#include "base/UtAssert.h"

static bool NearlyEq(double a, double b, double tolerance = 0.0001) {
    return fabs(a - b) <= tolerance;
}

static void FillPixmap(Pixmap* px, u8 r, u8 g, u8 b) {
    for (int y = 0; y < px->height; y++) {
        u8* row = px->data + (size_t)y * (size_t)px->stride;
        for (int x = 0; x < px->width; x++) {
            u8* p = row + (size_t)x * 4;
            p[0] = b;
            p[1] = g;
            p[2] = r;
            p[3] = 255;
        }
    }
}

static void SetPixel(Pixmap* px, int x, int y, u8 r, u8 g, u8 b) {
    u8* p = px->data + (size_t)y * (size_t)px->stride + (size_t)x * 4;
    p[0] = b;
    p[1] = g;
    p[2] = r;
    p[3] = 255;
}

static void CoverStatsTest() {
    Rect whole(0, 0, 20, 20);

    {
        Pixmap* px = AllocPixmap(20, 20);
        utassert(px != nullptr);
        FillPixmap(px, 255, 255, 255);
        CoverPatchStats s = CoverStatsOfRegion(px, whole);
        utassert(NearlyEq(s.white, 1.0));
        utassert(NearlyEq(s.dark, 0.0));
        utassert(NearlyEq(s.saturation, 0.0));
        utassert(NearlyEq(s.contrast, 0.0));
        utassert(NearlyEq(s.edges, 0.0));
        utassert(NearlyEq(s.colours, 1.0 / 400.0));
        FreePixmap(px);
    }

    {
        Pixmap* px = AllocPixmap(20, 20);
        FillPixmap(px, 0, 0, 0);
        CoverPatchStats s = CoverStatsOfRegion(px, whole);
        utassert(NearlyEq(s.white, 0.0));
        utassert(NearlyEq(s.dark, 1.0));
        utassert(NearlyEq(s.contrast, 0.0));
        FreePixmap(px);
    }

    {
        Pixmap* px = AllocPixmap(20, 20);
        FillPixmap(px, 233, 233, 233);
        utassert(NearlyEq(CoverStatsOfRegion(px, whole).white, 1.0));
        FillPixmap(px, 232, 232, 232);
        utassert(NearlyEq(CoverStatsOfRegion(px, whole).white, 0.0));
        FillPixmap(px, 25, 25, 25);
        utassert(NearlyEq(CoverStatsOfRegion(px, whole).dark, 1.0));
        FillPixmap(px, 26, 26, 26);
        utassert(NearlyEq(CoverStatsOfRegion(px, whole).dark, 0.0));
        FreePixmap(px);
    }

    {
        Pixmap* px = AllocPixmap(20, 20);
        FillPixmap(px, 200, 0, 0);
        CoverPatchStats s = CoverStatsOfRegion(px, whole);
        utassert(NearlyEq(s.saturation, 1.0));
        utassert(NearlyEq(s.white, 0.0));
        utassert(NearlyEq(s.dark, 0.0));
        FreePixmap(px);
    }

    {
        Pixmap* px = AllocPixmap(20, 20);
        for (int y = 0; y < 20; y++) {
            for (int x = 0; x < 20; x++) {
                u8 v = (x % 2 == 0) ? 0 : 255;
                SetPixel(px, x, y, v, v, v);
            }
        }
        CoverPatchStats s = CoverStatsOfRegion(px, whole);
        utassert(NearlyEq(s.edges, 1.0));
        utassert(s.contrast > 0.9);
        utassert(NearlyEq(s.white, 0.5));
        utassert(NearlyEq(s.dark, 0.5));
        FreePixmap(px);
    }

    {
        Pixmap* px = AllocPixmap(20, 20);
        FillPixmap(px, 255, 255, 255);
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 20; x++) {
                SetPixel(px, x, y, 0, 0, 0);
            }
        }
        utassert(NearlyEq(CoverStatsOfRegion(px, Rect(0, 0, 20, 10)).dark, 1.0));
        utassert(NearlyEq(CoverStatsOfRegion(px, Rect(0, 10, 20, 10)).white, 1.0));
        utassert(NearlyEq(CoverStatsOfRegion(px, whole).dark, 0.5));
        FreePixmap(px);
    }

    {
        Pixmap* px = AllocPixmap(20, 20);
        FillPixmap(px, 0, 0, 0);
        CoverPatchStats s = CoverStatsOfRegion(px, Rect(50, 50, 10, 10));
        utassert(NearlyEq(s.white, 1.0));
        utassert(NearlyEq(s.dark, 0.0));
        s = CoverStatsOfRegion(px, Rect(0, 0, 0, 0));
        utassert(NearlyEq(s.white, 1.0));
        s = CoverStatsOfRegion(nullptr, whole);
        utassert(NearlyEq(s.white, 1.0));
        s = CoverStatsOfRegion(px, Rect(10, 10, 100, 100));
        utassert(NearlyEq(s.dark, 1.0));
        FreePixmap(px);
    }
}

static void CoverOverlapTest() {
    RectF page = RectF::FromXY(0, 0, 100, 100);
    utassert(CoverRectsOverlap(page, page));
    utassert(!CoverRectsOverlap(page, RectF::FromXY(200, 200, 300, 300)));
    utassert(!CoverRectsOverlap(page, RectF::FromXY(100, 0, 200, 100)));
    utassert(CoverRectsOverlap(page, RectF::FromXY(0, 0, 97, 97)));
    utassert(!CoverRectsOverlap(page, RectF::FromXY(0, 0, 90, 90)));
    utassert(!CoverRectsOverlap(page, RectF::FromXY(10, 10, 50, 50)));
}

static void CoverFeaturesTest() {
    RectF page = RectF::FromXY(0, 0, 100, 200);
    CoverPatchStats stats;
    stats.white = 0.25;
    stats.dark = 0.1;
    stats.saturation = 0.4;
    stats.contrast = 0.5;
    stats.edges = 0.6;
    stats.colours = 0.7;

    CoverCandidate c;
    c.rect = RectF::FromXY(25, 65, 75, 135);
    c.fromEmbeddedImage = true;
    c.sourceWidth = 1200;
    c.sourceHeight = 1200;
    CoverFeatures f = CoverFeaturesOf(c, page, stats);
    utassert(NearlyEq(f.v[0], 1.0));
    utassert(NearlyEq(f.v[1], 3500.0 / 20000.0));
    utassert(NearlyEq(f.v[2], 1.0));
    utassert(NearlyEq(f.v[3], 0.0));
    utassert(NearlyEq(f.v[4], 0.0));
    utassert(NearlyEq(f.v[5], stats.white));
    utassert(NearlyEq(f.v[6], stats.dark));
    utassert(NearlyEq(f.v[7], stats.saturation));
    utassert(NearlyEq(f.v[8], stats.contrast));
    utassert(NearlyEq(f.v[9], stats.edges));
    utassert(NearlyEq(f.v[10], stats.colours));
    utassert(NearlyEq(f.v[11], 1.0));
    utassert(NearlyEq(f.v[12], 1.0));

    CoverCandidate whole;
    whole.rect = page;
    CoverFeatures wf = CoverFeaturesOf(whole, page, stats);
    utassert(NearlyEq(wf.v[1], 1.0));
    utassert(NearlyEq(wf.v[11], 0.0));
    utassert(NearlyEq(wf.v[12], 0.0));

    CoverCandidate corner;
    corner.rect = RectF::FromXY(0, 0, 20, 40);
    CoverFeatures cf = CoverFeaturesOf(corner, page, stats);
    utassert(cf.v[3] > 0.7);
    utassert(cf.v[4] > 0.7);

    CoverCandidate wide;
    wide.rect = RectF::FromXY(0, 0, 100, 100);
    utassert(NearlyEq(CoverFeaturesOf(wide, page, stats).v[2], 1.0 - 0.15 / 0.5));
    CoverCandidate veryWide;
    veryWide.rect = RectF::FromXY(0, 0, 100, 50);
    utassert(NearlyEq(CoverFeaturesOf(veryWide, page, stats).v[2], 0.0));
}

static void CoverScoreTest() {
    CoverFeatures f;
    for (int i = 0; i < kCoverFeatureCount; i++) {
        f.v[i] = 1.0;
    }
    double zeros[kCoverFeatureCount] = {};
    utassert(NearlyEq(CoverScoreOf(f, zeros), 0.5));

    double positive[kCoverFeatureCount] = {};
    positive[0] = 10.0;
    utassert(CoverScoreOf(f, positive) > 0.99);
    double negative[kCoverFeatureCount] = {};
    negative[0] = -10.0;
    utassert(CoverScoreOf(f, negative) < 0.01);

    CoverCandidate blank;
    blank.rect = RectF::FromXY(0, 0, 100, 200);
    CoverPatchStats blankStats;
    blankStats.white = 1.0;
    CoverFeatures bf = CoverFeaturesOf(blank, blank.rect, blankStats);
    utassert(CoverScoreOf(bf, CoverPriorWeights()) < kCoverAcceptScore);
}

static void CoverTrainTest() {
    double weights[kCoverFeatureCount];

    CoverTrainWeights(nullptr, nullptr, 0, weights);
    for (int i = 0; i < kCoverFeatureCount; i++) {
        utassert(NearlyEq(weights[i], CoverPriorWeights()[i]));
    }

    CoverFeatures rows[2];
    bool good[2] = {true, false};
    for (int i = 0; i < kCoverFeatureCount; i++) {
        rows[0].v[i] = 0.0;
        rows[1].v[i] = 0.0;
    }
    rows[0].v[0] = 1.0;
    rows[1].v[0] = 1.0;
    rows[0].v[5] = 0.0;
    rows[1].v[5] = 1.0;

    CoverTrainWeights(rows, good, 2, weights);
    utassert(CoverScoreOf(rows[0], weights) > CoverScoreOf(rows[1], weights));
    utassert(CoverScoreOf(rows[0], weights) > CoverScoreOf(rows[0], CoverPriorWeights()));
    utassert(weights[5] < CoverPriorWeights()[5]);

    for (int i = 0; i < kCoverFeatureCount; i++) {
        utassert(fabs(weights[i]) < 100.0);
    }
}

static void CoverFeatureNameTest() {
    utassert(str::Eq(CoverFeatureName(0), "bias"));
    utassert(str::Eq(CoverFeatureName(kCoverFeatureCount - 1), "resolution"));
    utassert(str::Eq(CoverFeatureName(-1), ""));
    utassert(str::Eq(CoverFeatureName(kCoverFeatureCount), ""));
}

void CoverModelTest() {
    CoverStatsTest();
    CoverOverlapTest();
    CoverFeaturesTest();
    CoverScoreTest();
    CoverTrainTest();
    CoverFeatureNameTest();
}
