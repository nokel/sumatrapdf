/* Copyright 2026 the SumatraPDF project authors (see AUTHORS file).
   License: GPLv3 */

#include "base/Base.h"
#include "base/Archive.h"
#include "base/File.h"
#include "base/GdiPlusUtil.h"
#include "base/GuessFileType.h"
#include "base/Pixmap.h"
#include "base/SettingsUtil.h"

#include <mupdf/pdf.h>

#include "Annotation.h"
#include "DocProperties.h"
#include "gui/UIModels.h"
#include "EngineBase.h"
#include "EngineMupdf.h"
#include "EngineAll.h"
#include "EbookBase.h"
#include "PalmDbReader.h"
#include "EbookDoc.h"
#include "MobiDoc.h"
#include "ImageReader.h"
#include "WebpWriter.h"
#include "AppTools.h"
#include "CoverOnline.h"

#define INCLUDE_COVERSTORE_METADATA
#include "CoverData.h"

#include "CoverVision.h"

constexpr i64 kCoverMaxRenderPixels = 40000000;

Pixmap* CoverRenderRegion(EngineBase* engine, int pageNo, RectF rect, int targetHeight) {
    if (!engine || targetHeight <= 0 || rect.dx <= 0 || rect.dy <= 0) {
        return nullptr;
    }
    float zoom = (float)targetHeight / rect.dy;
    i64 w = (i64)lround((double)rect.dx * (double)zoom);
    if (w < 1 || w * (i64)targetHeight > kCoverMaxRenderPixels) {
        return nullptr;
    }
    RenderPageArgs args(pageNo, zoom, 0, &rect);
    Pixmap* px = engine->RenderPage(args);
    if (!px) {
        return nullptr;
    }
    if (px->format == PixmapFormat::Native) {
        Pixmap* conv = PixmapCopyAs32bppDIB(px);
        FreePixmap(px);
        return conv;
    }
    return px;
}

static RectF CoverClampRect(RectF r, RectF outer) {
    return RectF::FromXY(std::max(r.x, outer.x), std::max(r.y, outer.y), std::min(r.Right(), outer.Right()),
                         std::min(r.Bottom(), outer.Bottom()));
}

static bool CoverRectContains(RectF outer, RectF inner) {
    return inner.x >= outer.x - 1.f && inner.y >= outer.y - 1.f && inner.Right() <= outer.Right() + 1.f &&
           inner.Bottom() <= outer.Bottom() + 1.f;
}

static void CoverCollectEmbeddedImages(EngineBase* engine, int pageNo, Vec<CoverCandidate>& out) {
    EngineMupdf* e = AsEngineMupdf(engine);
    if (!e) {
        return;
    }
    FzPageInfo* pageInfo = e->GetFzPageInfo(pageNo, false);
    if (!pageInfo) {
        return;
    }
    ScopedRecursiveMutex scope(&e->pagesLock);
    for (FitzPageImageInfo* img : pageInfo->images) {
        if (!img) {
            continue;
        }
        int w = 0;
        int h = 0;
        if (img->image) {
            if (img->image->imagemask) {
                continue;
            }
            w = img->image->w;
            h = img->image->h;
        }
        CoverCandidate c;
        c.rect = ToRectF(img->rect);
        c.fromEmbeddedImage = true;
        c.sourceWidth = w;
        c.sourceHeight = h;
        out.Append(c);
    }
}

void CoverCandidatesOfPage(EngineBase* engine, int pageNo, Vec<CoverCandidate>& out) {
    out.Clear();
    if (!engine) {
        return;
    }
    RectF pageRect = engine->PageMediabox(pageNo);
    double pageArea = CoverRectArea(pageRect);
    if (pageArea <= 0.0) {
        return;
    }

    Vec<CoverCandidate> images;
    CoverCollectEmbeddedImages(engine, pageNo, images);
    for (CoverCandidate& img : images) {
        if (img.sourceWidth < kCoverMinSourceWidth || img.sourceHeight < kCoverMinSourceHeight) {
            continue;
        }
        RectF r = CoverClampRect(img.rect, pageRect);
        if (r.dx <= 1.f || r.dy <= 1.f) {
            continue;
        }
        double share = CoverRectArea(r) / pageArea;
        double ratio = (double)r.dx / (double)r.dy;
        bool big = share >= kCoverMinImageShare;
        bool inset = share >= kCoverInsetImageShare && ratio >= kCoverRatioLow && ratio <= kCoverRatioHigh;
        if (!big && !inset && ratio <= kCoverSpreadRatio) {
            continue;
        }
        if (!big && ratio > kCoverSpreadRatio && share < kCoverMinImageShare) {
            continue;
        }
        CoverCandidate c = img;
        c.rect = r;
        out.Append(c);
    }
    CoverCandidate whole;
    whole.rect = pageRect;
    out.Append(whole);

    int n = out.len;
    for (int i = 0; i < n; i++) {
        CoverCandidate c = out[i];
        float ratio = (c.rect.dy > 0) ? c.rect.dx / c.rect.dy : 0.f;
        if (ratio <= kCoverSpreadRatio) {
            continue;
        }
        float mid = c.rect.x + c.rect.dx / 2.f;
        CoverCandidate left = c;
        left.rect = RectF::FromXY(c.rect.x, c.rect.y, mid, c.rect.Bottom());
        CoverCandidate right = c;
        right.rect = RectF::FromXY(mid, c.rect.y, c.rect.Right(), c.rect.Bottom());
        out.Append(left);
        out.Append(right);
    }

    Vec<CoverCandidate> unique;
    for (CoverCandidate& c : out) {
        bool dup = false;
        for (CoverCandidate& u : unique) {
            if (CoverRectsOverlap(u.rect, c.rect)) {
                dup = true;
                break;
            }
        }
        if (!dup) {
            unique.Append(c);
        }
    }
    out.Clear();
    for (CoverCandidate& c : unique) {
        out.Append(c);
    }
}

static int CoverScoreDescending(const ScoredCover* a, const ScoredCover* b) {
    if (a->score < b->score) {
        return 1;
    }
    if (a->score > b->score) {
        return -1;
    }
    return 0;
}

void CoverScoreCandidates(EngineBase* engine, int pageNo, const Vec<CoverCandidate>& in, Vec<ScoredCover>& out) {
    out.Clear();
    if (!engine || in.len == 0) {
        return;
    }
    RectF pageRect = engine->PageMediabox(pageNo);
    if (CoverRectArea(pageRect) <= 0.0) {
        return;
    }
    Pixmap* probe = CoverRenderRegion(engine, pageNo, pageRect, kCoverProbeHeight);
    if (!probe) {
        return;
    }
    double weights[kCoverFeatureCount];
    CoverModelGetWeights(weights);
    float scaleX = (float)probe->width / pageRect.dx;
    float scaleY = (float)probe->height / pageRect.dy;
    for (int i = 0; i < in.len; i++) {
        const CoverCandidate& c = in[i];
        int left = limitValue((int)lround((c.rect.x - pageRect.x) * scaleX), 0, probe->width - 1);
        int top = limitValue((int)lround((c.rect.y - pageRect.y) * scaleY), 0, probe->height - 1);
        int right = limitValue((int)lround((c.rect.Right() - pageRect.x) * scaleX), left + 1, probe->width);
        int bottom = limitValue((int)lround((c.rect.Bottom() - pageRect.y) * scaleY), top + 1, probe->height);
        int w = right - left;
        int h = bottom - top;
        if (w < 2 || h < 2) {
            continue;
        }
        ScoredCover sc;
        sc.candidate = c;
        sc.features = CoverFeaturesOf(c, pageRect, CoverStatsOfRegion(probe, Rect(left, top, w, h)));
        sc.score = CoverScoreOf(sc.features, weights);
        out.Append(sc);
    }
    FreePixmap(probe);
    VecSort(out, CoverScoreDescending);
}

static i64 CoverSourcePixels(const CoverCandidate& c) {
    if (!c.fromEmbeddedImage) {
        return 0;
    }
    return (i64)std::max(0, c.sourceWidth) * (i64)std::max(0, c.sourceHeight);
}

bool CoverPickOfPage(EngineBase* engine, int pageNo, ScoredCover* out) {
    if (!out) {
        return false;
    }
    Vec<CoverCandidate> candidates;
    CoverCandidatesOfPage(engine, pageNo, candidates);
    Vec<ScoredCover> scored;
    CoverScoreCandidates(engine, pageNo, candidates, scored);
    if (scored.len == 0) {
        return false;
    }
    int best = 0;
    if (scored[0].score < kCoverAcceptScore) {
        best = -1;
        for (int i = 0; i < scored.len; i++) {
            if (!scored[i].candidate.fromEmbeddedImage) {
                best = i;
                break;
            }
        }
        if (best < 0) {
            *out = scored[0];
            return true;
        }
    }
    double top = scored[best].score;
    i64 bestPixels = CoverSourcePixels(scored[best].candidate);
    for (int i = 0; i < scored.len; i++) {
        if (scored[i].score < top - kCoverScoreTieBand) {
            continue;
        }
        i64 pixels = CoverSourcePixels(scored[i].candidate);
        if (pixels > bestPixels) {
            best = i;
            bestPixels = pixels;
        }
    }
    *out = scored[best];
    return true;
}

int CoverTargetHeightOf(const CoverCandidate& c) {
    if (!c.fromEmbeddedImage || c.sourceHeight < 1) {
        return kCoverPageHeight;
    }
    return std::min(c.sourceHeight, kCoverMaxHeight);
}

Pixmap* CoverScaleToHeight(const Pixmap* src, int height) {
    if (!src || height <= 0 || src->height <= 0 || src->width <= 0) {
        return nullptr;
    }
    if (src->height <= height) {
        return ClonePixmap(src);
    }
    double scale = (double)height / (double)src->height;
    int dx = std::max(1, (int)lround((double)src->width * scale));
    Gdiplus::Bitmap* from = WrapPixmapGdiplus(src);
    if (!from) {
        return nullptr;
    }
    auto* to = new Gdiplus::Bitmap(dx, height, PixelFormat32bppARGB);
    Pixmap* out = nullptr;
    if (to->GetLastStatus() == Gdiplus::Ok) {
        Gdiplus::Graphics g(to);
        g.SetInterpolationMode(Gdiplus::InterpolationModeHighQualityBicubic);
        g.SetPixelOffsetMode(Gdiplus::PixelOffsetModeHighQuality);
        g.DrawImage(from, Gdiplus::Rect(0, 0, dx, height));
        out = PixmapFromGdiplus(to);
    }
    delete to;
    delete from;
    return out;
}

Str CoverEncodePixmap(const Pixmap* px, int maxHeight) {
    if (!px) {
        return {};
    }
    Pixmap* readable = nullptr;
    if (px->format == PixmapFormat::Native || !px->data) {
        readable = PixmapCopyAs32bppDIB(px);
        if (!readable) {
            return {};
        }
        px = readable;
    }
    Pixmap* sized = CoverScaleToHeight(px, maxHeight);
    Str data = webp::EncodeFromPixmap(sized ? sized : px, kCoverWebpQuality);
    FreePixmap(sized);
    FreePixmap(readable);
    return data;
}

static Str CoverFromChosenFile(Str fromPath) {
    if (len(fromPath) == 0 || !file::Exists(fromPath)) {
        return {};
    }
    Str data = file::ReadFile(fromPath);
    Pixmap* px = len(data) > 0 ? PixmapFromData(data) : nullptr;
    str::Free(data);
    Str out = CoverEncodePixmap(px);
    FreePixmap(px);
    return out;
}

static Str CoverFromMetadata(Str filePath) {
    FileType kind = GuessFileType(filePath, true);
    Str data;
    if (EpubDoc::IsSupportedFileType(kind)) {
        EpubDoc* doc = EpubDoc::CreateFromFile(filePath);
        if (doc) {
            data = doc->GetCoverImage();
            Pixmap* px = len(data) > 0 ? PixmapFromData(data) : nullptr;
            Str out = CoverEncodePixmap(px);
            FreePixmap(px);
            delete doc;
            return out;
        }
    } else if (Fb2Doc::IsSupportedFileType(kind)) {
        Fb2Doc* doc = Fb2Doc::CreateFromFile(filePath);
        if (doc) {
            data = doc->GetCoverImage();
            Pixmap* px = len(data) > 0 ? PixmapFromData(data) : nullptr;
            Str out = CoverEncodePixmap(px);
            FreePixmap(px);
            delete doc;
            return out;
        }
    } else if (MobiDoc::IsSupportedFileType(kind)) {
        MobiDoc* doc = MobiDoc::CreateFromFile(filePath);
        if (doc) {
            data = doc->GetCoverImage();
            Pixmap* px = len(data) > 0 ? PixmapFromData(data) : nullptr;
            Str out = CoverEncodePixmap(px);
            FreePixmap(px);
            delete doc;
            return out;
        }
    }
    return {};
}

static double CoverImageRank(const Pixmap* px, int order) {
    if (!px || px->width < kCoverMinSourceWidth || px->height < kCoverMinSourceHeight) {
        return -1;
    }
    double ratio = (double)px->width / (double)px->height;
    double distance = 0;
    if (ratio < kCoverRatioLow) {
        distance = kCoverRatioLow - ratio;
    } else if (ratio > kCoverRatioHigh) {
        distance = ratio - kCoverRatioHigh;
    }
    if (distance > 0.25) {
        return -1;
    }
    double pixels = (double)px->width * (double)px->height;
    return log(1.0 + pixels) - distance * 12.0 - (double)order * 0.0001;
}

static void CoverConsiderImage(Str data, int order, double* bestRank, Str* best) {
    Pixmap* px = len(data) > 0 ? PixmapFromData(data) : nullptr;
    double rank = CoverImageRank(px, order);
    if (rank > *bestRank) {
        if (len(data) >= kCoverMinBytes) {
            str::Free(*best);
            *best = str::Dup(data);
            *bestRank = rank;
        }
    }
    FreePixmap(px);
}

Str CoverImageResourceForBook(Str filePath) {
    FileType kind = GuessFileType(filePath, true);
    Str best;
    double bestRank = -1;
    if (EpubDoc::IsSupportedFileType(kind)) {
        EpubDoc* doc = EpubDoc::CreateFromFile(filePath);
        if (!doc) {
            return {};
        }
        for (int i = 0; i < len(doc->images); i++) {
            CoverConsiderImage(doc->GetImageDataByIndex(i), i, &bestRank, &best);
        }
        delete doc;
        return best;
    }
    if (Fb2Doc::IsSupportedFileType(kind)) {
        Fb2Doc* doc = Fb2Doc::CreateFromFile(filePath);
        if (!doc) {
            return {};
        }
        for (int i = 0; i < len(doc->images); i++) {
            CoverConsiderImage(doc->images[i].base, i, &bestRank, &best);
        }
        delete doc;
        return best;
    }
    if (MobiDoc::IsSupportedFileType(kind)) {
        MobiDoc* doc = MobiDoc::CreateFromFile(filePath);
        if (!doc) {
            return {};
        }
        for (int i = 1; i <= doc->imagesCount; i++) {
            CoverConsiderImage(doc->GetImage(i), i - 1, &bestRank, &best);
        }
        delete doc;
    }
    return best;
}

static Str CoverFromPages(EngineBase* engine, CoverBuildLocation* location) {
    int last = std::min(engine->pageCount, kCoverPageScanLimit);
    for (int pageNo = 1; pageNo <= last; pageNo++) {
        ScoredCover sc;
        if (!CoverPickOfPage(engine, pageNo, &sc)) {
            continue;
        }
        RectF pageRect = engine->PageMediabox(pageNo);
        Pixmap* px = CoverRenderRegion(engine, pageNo, sc.candidate.rect, CoverTargetHeightOf(sc.candidate));
        bool embedded = sc.candidate.fromEmbeddedImage;
        if (!px && embedded) {
            embedded = false;
            px = CoverRenderRegion(engine, pageNo, pageRect, kCoverPageHeight);
        }
        if (!px) {
            continue;
        }
        double white = CoverStatsOfRegion(px, Rect(0, 0, px->width, px->height)).white;
        Str data = CoverEncodePixmap(px);
        FreePixmap(px);
        if (len(data) < kCoverMinBytes) {
            str::Free(data);
            continue;
        }
        double limit = embedded ? kCoverTextPageWhite : kCoverPlainPageWhite;
        if (white < limit) {
            if (location) {
                location->insideBook = true;
                location->pageNo = pageNo;
                location->rect = embedded ? sc.candidate.rect : pageRect;
            }
            return data;
        }
        str::Free(data);
    }
    return {};
}

Str CoverBuildForBook(Str filePath, Str bookId, Str title, Str author, Str series, int year,
                      CoverBuildLocation* location) {
    if (location) {
        *location = {};
    }
    if (len(filePath) == 0 || !file::Exists(filePath)) {
        return {};
    }
    Str chosen = CoverChoiceGetFilePath(bookId);
    Str res = CoverFromChosenFile(chosen);
    str::Free(chosen);
    if (len(res) > 0) {
        return res;
    }

    res = CoverFromMetadata(filePath);
    if (len(res) > 0) {
        if (location) {
            location->insideBook = true;
        }
        return res;
    }

    Str resource = CoverImageResourceForBook(filePath);
    if (len(resource) > 0) {
        Pixmap* resourcePx = PixmapFromData(resource);
        res = CoverEncodePixmap(resourcePx);
        FreePixmap(resourcePx);
        str::Free(resource);
    }
    if (len(res) > 0) {
        if (location) {
            location->insideBook = true;
        }
        return res;
    }

    EngineBase* engine = CreateEngineFromFile(filePath, nullptr, false);
    if (engine) {
        if (engine->pageCount > 0) {
            int cropPage = 0;
            RectF cropRect;
            if (CoverChoiceGetPageCrop(bookId, &cropPage, &cropRect) && cropPage >= 1 &&
                cropPage <= engine->pageCount && cropRect.dx > 0 && cropRect.dy > 0) {
                Pixmap* px = CoverRenderRegion(engine, cropPage, cropRect, kCoverMaxHeight);
                res = CoverEncodePixmap(px);
                FreePixmap(px);
                if (len(res) > 0 && location) {
                    location->insideBook = true;
                    location->pageNo = cropPage;
                    location->rect = cropRect;
                }
            }
            if (len(res) == 0) {
                res = CoverFromPages(engine, location);
            }
        }
        engine->Release();
    }
    if (len(res) > 0) {
        return res;
    }

    Str online = CoverOnlineFetch(title, author, series, year);
    Pixmap* px = len(online) > 0 ? PixmapFromData(online) : nullptr;
    str::Free(online);
    res = CoverEncodePixmap(px);
    FreePixmap(px);
    return res;
}

Str CoverBuildFromLocation(Str filePath, const CoverBuildLocation& location) {
    if (location.pageNo < 1 || location.rect.dx <= 0 || location.rect.dy <= 0) {
        return {};
    }
    EngineBase* engine = CreateEngineFromFile(filePath, nullptr, false);
    if (!engine || location.pageNo > engine->pageCount) {
        if (engine) {
            engine->Release();
        }
        return {};
    }
    Pixmap* px = CoverRenderRegion(engine, location.pageNo, location.rect, kCoverMaxHeight);
    Str result = CoverEncodePixmap(px);
    FreePixmap(px);
    engine->Release();
    return result;
}

static Mutex gCoverStoreMutex;
static CoverStore* gCoverStore = nullptr;
static bool gCoverWeightsValid = false;
static double gCoverWeights[kCoverFeatureCount];

static TempStr CoverStorePathTemp() {
    return GetPathInAppDataDirTemp(StrL(kCoverStoreFileName));
}

static CoverStore* CoverStoreLoadLocked() {
    if (gCoverStore) {
        return gCoverStore;
    }
    Str path = Str(CoverStorePathTemp());
    CoverStore* store = nullptr;
    if (file::Exists(path)) {
        Str data = file::ReadFile(path);
        store = (CoverStore*)DeserializeStruct(&gCoverStoreInfo, data);
        str::Free(data);
        if (store && store->version != kCoverStoreVersion) {
            FreeStruct(&gCoverStoreInfo, store);
            store = nullptr;
        }
    }
    if (!store) {
        store = (CoverStore*)DeserializeStruct(&gCoverStoreInfo, {});
    }
    gCoverStore = store;
    return store;
}

static void CoverStoreSaveLocked() {
    if (!gCoverStore) {
        return;
    }
    gCoverStore->version = kCoverStoreVersion;
    Str data = SerializeStruct(&gCoverStoreInfo, gCoverStore);
    if (len(data) > 0) {
        if (!file::WriteFile(Str(CoverStorePathTemp()), data)) {
            logf("CoverStoreSaveLocked: failed to write %s\n", StrL(kCoverStoreFileName));
        }
    }
    str::Free(data);
}

static void CoverWeightsRefreshLocked() {
    if (gCoverWeightsValid) {
        return;
    }
    for (int i = 0; i < kCoverFeatureCount; i++) {
        gCoverWeights[i] = CoverPriorWeights()[i];
    }
    CoverStore* store = CoverStoreLoadLocked();
    if (store && store->weights && store->weights->len == kCoverFeatureCount) {
        for (int i = 0; i < kCoverFeatureCount; i++) {
            gCoverWeights[i] = (double)(*store->weights)[i];
        }
    }
    gCoverWeightsValid = true;
}

void CoverModelGetWeights(double* out) {
    if (!out) {
        return;
    }
    ScopedMutex scope(&gCoverStoreMutex);
    CoverWeightsRefreshLocked();
    for (int i = 0; i < kCoverFeatureCount; i++) {
        out[i] = gCoverWeights[i];
    }
}

int CoverModelExampleCount() {
    ScopedMutex scope(&gCoverStoreMutex);
    CoverStore* store = CoverStoreLoadLocked();
    if (!store || !store->coverExamples) {
        return 0;
    }
    return store->coverExamples->len;
}

void CoverStoreForget() {
    ScopedMutex scope(&gCoverStoreMutex);
    if (gCoverStore) {
        FreeStruct(&gCoverStoreInfo, gCoverStore);
        gCoverStore = nullptr;
    }
    gCoverWeightsValid = false;
}

static void CoverStoreAppendExampleLocked(CoverStore* store, const CoverFeatures& f, bool good) {
    auto* ex = (CoverExample*)DeserializeStruct(&gCoverExampleInfo, {});
    if (!ex) {
        return;
    }
    ex->good = good;
    ex->features->Reset();
    for (int i = 0; i < kCoverFeatureCount; i++) {
        ex->features->Append((float)f.v[i]);
    }
    store->coverExamples->Append(ex);
}

static void CoverModelRetrainLocked(CoverStore* store) {
    int total = store->coverExamples ? store->coverExamples->len : 0;
    CoverFeatures* rows = nullptr;
    bool* flags = nullptr;
    int nRows = 0;
    if (total > 0) {
        rows = AllocArray<CoverFeatures>(total);
        flags = AllocArray<bool>(total);
    }
    for (int i = 0; rows && flags && i < total; i++) {
        CoverExample* ex = (*store->coverExamples)[i];
        if (!ex || !ex->features || ex->features->len != kCoverFeatureCount) {
            continue;
        }
        for (int k = 0; k < kCoverFeatureCount; k++) {
            rows[nRows].v[k] = (double)(*ex->features)[k];
        }
        flags[nRows] = ex->good;
        nRows++;
    }
    CoverTrainWeights(rows, flags, nRows, gCoverWeights);
    gCoverWeightsValid = true;
    free(rows);
    free(flags);

    store->weights->Reset();
    for (int i = 0; i < kCoverFeatureCount; i++) {
        store->weights->Append((float)gCoverWeights[i]);
    }
}

static void CoverModelLearnExamples(const Vec<CoverFeatures>& good, const Vec<CoverFeatures>& bad) {
    if (good.len == 0 && bad.len == 0) {
        return;
    }
    ScopedMutex scope(&gCoverStoreMutex);
    CoverStore* store = CoverStoreLoadLocked();
    if (!store) {
        return;
    }
    for (int i = 0; i < good.len; i++) {
        CoverStoreAppendExampleLocked(store, good[i], true);
    }
    for (int i = 0; i < bad.len; i++) {
        CoverStoreAppendExampleLocked(store, bad[i], false);
    }
    CoverModelRetrainLocked(store);
    CoverStoreSaveLocked();
}

void CoverModelLearnFromCrop(EngineBase* engine, int pageNo, RectF rect) {
    if (!engine || rect.dx <= 0 || rect.dy <= 0) {
        return;
    }
    Vec<CoverCandidate> others;
    CoverCandidatesOfPage(engine, pageNo, others);

    CoverCandidate chosen;
    chosen.rect = rect;
    for (int i = 0; i < others.len; i++) {
        const CoverCandidate& c = others[i];
        if (!c.fromEmbeddedImage || !CoverRectContains(c.rect, rect)) {
            continue;
        }
        float fx = (c.rect.dx > 0) ? rect.dx / c.rect.dx : 1.f;
        float fy = (c.rect.dy > 0) ? rect.dy / c.rect.dy : 1.f;
        chosen.fromEmbeddedImage = true;
        chosen.sourceWidth = std::max(1, (int)lround((double)c.sourceWidth * fx));
        chosen.sourceHeight = std::max(1, (int)lround((double)c.sourceHeight * fy));
        break;
    }

    Vec<CoverCandidate> all;
    all.Append(chosen);
    for (int i = 0; i < others.len; i++) {
        all.Append(others[i]);
    }
    Vec<ScoredCover> scored;
    CoverScoreCandidates(engine, pageNo, all, scored);

    Vec<CoverFeatures> good;
    Vec<CoverFeatures> bad;
    for (int i = 0; i < scored.len; i++) {
        if (scored[i].candidate.rect == chosen.rect) {
            if (good.len == 0) {
                good.Append(scored[i].features);
            }
            continue;
        }
        if (CoverRectsOverlap(scored[i].candidate.rect, rect)) {
            continue;
        }
        bad.Append(scored[i].features);
    }
    if (good.len == 0) {
        return;
    }
    CoverModelLearnExamples(good, bad);
}

void CoverModelLearnFromBookCrop(Str filePath, int pageNo, RectF rect) {
    EngineBase* engine = CreateEngineFromFile(filePath, nullptr, false);
    if (!engine) {
        return;
    }
    CoverModelLearnFromCrop(engine, pageNo, rect);
    engine->Release();
}

void CoverModelLearnFromImage(Str imageData) {
    Pixmap* px = PixmapFromData(imageData);
    if (!px) {
        return;
    }
    if (px->format == PixmapFormat::Native) {
        Pixmap* converted = PixmapCopyAs32bppDIB(px);
        FreePixmap(px);
        px = converted;
    }
    if (!px || px->width < 1 || px->height < 1) {
        FreePixmap(px);
        return;
    }
    CoverCandidate candidate;
    candidate.rect = RectF(0, 0, (float)px->width, (float)px->height);
    candidate.fromEmbeddedImage = true;
    candidate.sourceWidth = px->width;
    candidate.sourceHeight = px->height;
    CoverPatchStats stats = CoverStatsOfRegion(px, Rect(0, 0, px->width, px->height));
    CoverFeatures features = CoverFeaturesOf(candidate, candidate.rect, stats);
    Vec<CoverFeatures> good;
    Vec<CoverFeatures> bad;
    good.Append(features);
    CoverModelLearnExamples(good, bad);
    FreePixmap(px);
}

static CoverChoice* CoverChoiceFindLocked(CoverStore* store, Str bookId, int* idxOut) {
    if (!store || !store->coverChoices || len(bookId) == 0) {
        return nullptr;
    }
    for (int i = 0; i < store->coverChoices->len; i++) {
        CoverChoice* c = (*store->coverChoices)[i];
        if (c && str::Eq(c->bookId, bookId)) {
            if (idxOut) {
                *idxOut = i;
            }
            return c;
        }
    }
    return nullptr;
}

static CoverChoice* CoverChoiceGetOrAddLocked(CoverStore* store, Str bookId) {
    if (!store || len(bookId) == 0) {
        return nullptr;
    }
    CoverChoice* c = CoverChoiceFindLocked(store, bookId, nullptr);
    if (c) {
        return c;
    }
    c = (CoverChoice*)DeserializeStruct(&gCoverChoiceInfo, {});
    if (!c) {
        return nullptr;
    }
    str::Free(c->bookId);
    c->bookId = str::Dup(bookId);
    store->coverChoices->Append(c);
    return c;
}

bool CoverChoiceIsByHand(Str bookId) {
    ScopedMutex scope(&gCoverStoreMutex);
    CoverStore* store = CoverStoreLoadLocked();
    return CoverChoiceFindLocked(store, bookId, nullptr) != nullptr;
}

bool CoverChoiceGetPageCrop(Str bookId, int* pageNoOut, RectF* rectOut) {
    ScopedMutex scope(&gCoverStoreMutex);
    CoverStore* store = CoverStoreLoadLocked();
    CoverChoice* c = CoverChoiceFindLocked(store, bookId, nullptr);
    if (!c || !str::Eq(c->kind, StrL("page")) || !c->rect || c->rect->len != 4) {
        return false;
    }
    if (pageNoOut) {
        *pageNoOut = c->page;
    }
    if (rectOut) {
        *rectOut = RectF::FromXY((*c->rect)[0], (*c->rect)[1], (*c->rect)[2], (*c->rect)[3]);
    }
    return true;
}

Str CoverChoiceGetFilePath(Str bookId) {
    ScopedMutex scope(&gCoverStoreMutex);
    CoverStore* store = CoverStoreLoadLocked();
    CoverChoice* c = CoverChoiceFindLocked(store, bookId, nullptr);
    if (!c || !str::Eq(c->kind, StrL("file"))) {
        return {};
    }
    return str::Dup(c->fromPath);
}

void CoverChoiceRememberPageCrop(Str bookId, int pageNo, RectF rect) {
    ScopedMutex scope(&gCoverStoreMutex);
    CoverStore* store = CoverStoreLoadLocked();
    CoverChoice* c = CoverChoiceGetOrAddLocked(store, bookId);
    if (!c) {
        return;
    }
    str::Free(c->kind);
    c->kind = str::Dup(StrL("page"));
    c->page = pageNo;
    str::Free(c->fromPath);
    c->fromPath = str::Dup(StrL(""));
    c->rect->Reset();
    c->rect->Append(rect.x);
    c->rect->Append(rect.y);
    c->rect->Append(rect.Right());
    c->rect->Append(rect.Bottom());
    CoverStoreSaveLocked();
}

void CoverChoiceRememberFile(Str bookId, Str fromPath) {
    ScopedMutex scope(&gCoverStoreMutex);
    CoverStore* store = CoverStoreLoadLocked();
    CoverChoice* c = CoverChoiceGetOrAddLocked(store, bookId);
    if (!c) {
        return;
    }
    str::Free(c->kind);
    c->kind = str::Dup(StrL("file"));
    c->page = 0;
    str::Free(c->fromPath);
    c->fromPath = str::Dup(fromPath);
    c->rect->Reset();
    CoverStoreSaveLocked();
}

void CoverChoiceRememberImage(Str bookId) {
    ScopedMutex scope(&gCoverStoreMutex);
    CoverStore* store = CoverStoreLoadLocked();
    CoverChoice* c = CoverChoiceGetOrAddLocked(store, bookId);
    if (!c) {
        return;
    }
    str::ReplaceWithCopy(&c->kind, StrL("image"));
    c->page = 0;
    c->rect->Reset();
    str::ReplaceWithCopy(&c->fromPath, {});
    CoverStoreSaveLocked();
}

void CoverChoiceForget(Str bookId) {
    ScopedMutex scope(&gCoverStoreMutex);
    CoverStore* store = CoverStoreLoadLocked();
    int idx = -1;
    CoverChoice* c = CoverChoiceFindLocked(store, bookId, &idx);
    if (!c || idx < 0) {
        return;
    }
    store->coverChoices->RemoveAt(idx);
    FreeStruct(&gCoverChoiceInfo, c);
    CoverStoreSaveLocked();
}
