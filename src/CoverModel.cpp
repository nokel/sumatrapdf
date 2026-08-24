/* Copyright 2026 the SumatraPDF project authors (see AUTHORS file).
   License: GPLv3 */

#include "base/Base.h"
#include "base/Pixmap.h"

#include "CoverVision.h"

static const char* gCoverFeatureNames[kCoverFeatureCount] = {
    "bias", "share",    "ratioFit", "offCentreX", "offCentreY", "white",      "dark",
    "sat",  "contrast", "edges",    "colours",    "embedded",   "resolution",
};

static const double gCoverPriorWeights[kCoverFeatureCount] = {
    -1.0, 2.5, 2.0, -1.5, -1.0, -4.0, -2.0, 2.0, 1.0, 1.5, 2.5, 0.6, 0.8,
};

constexpr double kCoverLearningRate = 0.5;
constexpr double kCoverPriorPull = 0.05;
constexpr int kCoverTrainPasses = 400;
constexpr int kCoverShadeBuckets = 4096;

const char* CoverFeatureName(int idx) {
    if (idx < 0 || idx >= kCoverFeatureCount) {
        return "";
    }
    return gCoverFeatureNames[idx];
}

const double* CoverPriorWeights() {
    return gCoverPriorWeights;
}

static double ClampD(double v, double lo, double hi) {
    if (v < lo) {
        return lo;
    }
    if (v > hi) {
        return hi;
    }
    return v;
}

double CoverRectArea(RectF r) {
    return fabs((double)r.dx * (double)r.dy);
}

CoverPatchStats CoverStatsOfRegion(const Pixmap* px, Rect area) {
    CoverPatchStats res;
    if (!px || !px->data || px->format == PixmapFormat::Native) {
        return res;
    }
    int bpp = PixmapBytesPerPixel(px->format);
    int x0 = limitValue(area.x, 0, px->width);
    int y0 = limitValue(area.y, 0, px->height);
    int x1 = limitValue(area.x + area.dx, x0, px->width);
    int y1 = limitValue(area.y + area.dy, y0, px->height);
    int w = x1 - x0;
    int h = y1 - y0;
    if (w <= 0 || h <= 0) {
        return res;
    }
    int step = (int)lround(sqrt(((double)w * (double)h) / kCoverPatchSampleTarget));
    if (step < 1) {
        step = 1;
    }
    bool rgbaOrder = (px->format == PixmapFormat::RGBA8);
    i64 seen = 0;
    i64 white = 0;
    i64 dark = 0;
    i64 edges = 0;
    i64 edgesSeen = 0;
    double saturation = 0;
    double luminance = 0;
    double luminanceSquared = 0;
    u8 shades[kCoverShadeBuckets / 8] = {};
    int shadeCount = 0;
    for (int y = y0; y < y1; y += step) {
        const u8* row = px->data + (size_t)y * (size_t)px->stride;
        for (int x = x0; x < x1; x += step) {
            const u8* p = row + (size_t)x * (size_t)bpp;
            int r = rgbaOrder ? p[0] : p[2];
            int g = p[1];
            int b = rgbaOrder ? p[2] : p[0];
            seen++;
            if (r > 232 && g > 232 && b > 232) {
                white++;
            }
            if (r < 26 && g < 26 && b < 26) {
                dark++;
            }
            int hi = std::max(r, std::max(g, b));
            int lo = std::min(r, std::min(g, b));
            if (hi > 0) {
                saturation += (double)(hi - lo) / (double)hi;
            }
            double lum = 0.299 * r + 0.587 * g + 0.114 * b;
            luminance += lum;
            luminanceSquared += lum * lum;
            int shade = ((r >> 4) << 8) | ((g >> 4) << 4) | (b >> 4);
            u8 bit = (u8)(1 << (shade & 7));
            if ((shades[shade >> 3] & bit) == 0) {
                shades[shade >> 3] |= bit;
                shadeCount++;
            }
            int nx = x + step;
            if (nx < x1) {
                const u8* q = row + (size_t)nx * (size_t)bpp;
                int nr = rgbaOrder ? q[0] : q[2];
                int nb = rgbaOrder ? q[2] : q[0];
                double next = 0.299 * nr + 0.587 * q[1] + 0.114 * nb;
                edgesSeen++;
                if (fabs(next - lum) > 24.0) {
                    edges++;
                }
            }
        }
    }
    if (seen == 0) {
        return res;
    }
    double mean = luminance / (double)seen;
    double variance = std::max(0.0, luminanceSquared / (double)seen - mean * mean);
    res.white = (double)white / (double)seen;
    res.dark = (double)dark / (double)seen;
    res.saturation = saturation / (double)seen;
    res.contrast = std::min(1.0, sqrt(variance) / 128.0);
    res.edges = (edgesSeen == 0) ? 0.0 : (double)edges / (double)edgesSeen;
    res.colours = std::min(1.0, (double)shadeCount / (double)seen);
    return res;
}

static double CoverRatioFit(double ratio) {
    if (ratio <= 0.0) {
        return 0.0;
    }
    double off = 0.0;
    if (ratio < kCoverRatioLow) {
        off = kCoverRatioLow - ratio;
    } else if (ratio > kCoverRatioHigh) {
        off = ratio - kCoverRatioHigh;
    }
    return std::max(0.0, 1.0 - off / 0.5);
}

CoverFeatures CoverFeaturesOf(const CoverCandidate& c, RectF pageRect, const CoverPatchStats& stats) {
    CoverFeatures f;
    double pageArea = CoverRectArea(pageRect);
    if (pageArea <= 0.0) {
        pageArea = 1.0;
    }
    double share = ClampD(CoverRectArea(c.rect) / pageArea, 0.0, 1.0);
    double ratio = (c.rect.dy > 0) ? (double)c.rect.dx / (double)c.rect.dy : 0.0;
    double pageMidX = (double)pageRect.x + (double)pageRect.dx / 2.0;
    double pageMidY = (double)pageRect.y + (double)pageRect.dy / 2.0;
    double midX = (double)c.rect.x + (double)c.rect.dx / 2.0;
    double midY = (double)c.rect.y + (double)c.rect.dy / 2.0;
    double offX = (pageRect.dx > 0) ? std::min(1.0, fabs(midX - pageMidX) * 2.0 / (double)pageRect.dx) : 0.0;
    double offY = (pageRect.dy > 0) ? std::min(1.0, fabs(midY - pageMidY) * 2.0 / (double)pageRect.dy) : 0.0;
    double pixels = (double)c.sourceWidth * (double)c.sourceHeight;
    double resolution = (pixels <= 0.0) ? 0.0 : std::min(1.0, sqrt(pixels) / 1200.0);
    f.v[0] = 1.0;
    f.v[1] = share;
    f.v[2] = CoverRatioFit(ratio);
    f.v[3] = offX;
    f.v[4] = offY;
    f.v[5] = stats.white;
    f.v[6] = stats.dark;
    f.v[7] = stats.saturation;
    f.v[8] = stats.contrast;
    f.v[9] = stats.edges;
    f.v[10] = stats.colours;
    f.v[11] = c.fromEmbeddedImage ? 1.0 : 0.0;
    f.v[12] = resolution;
    return f;
}

double CoverScoreOf(const CoverFeatures& f, const double* weights) {
    if (!weights) {
        weights = gCoverPriorWeights;
    }
    double sum = 0;
    for (int i = 0; i < kCoverFeatureCount; i++) {
        sum += f.v[i] * weights[i];
    }
    return 1.0 / (1.0 + exp(-sum));
}

bool CoverRectsOverlap(RectF a, RectF b) {
    float left = std::max(a.x, b.x);
    float top = std::max(a.y, b.y);
    float right = std::min(a.Right(), b.Right());
    float bottom = std::min(a.Bottom(), b.Bottom());
    if (right <= left || bottom <= top) {
        return false;
    }
    double shared = (double)(right - left) * (double)(bottom - top);
    double unionArea = CoverRectArea(a) + CoverRectArea(b) - shared;
    return unionArea > 0.0 && shared / unionArea > 0.94;
}

void CoverTrainWeights(const CoverFeatures* rows, const bool* good, int nRows, double* weightsOut) {
    for (int i = 0; i < kCoverFeatureCount; i++) {
        weightsOut[i] = gCoverPriorWeights[i];
    }
    if (!rows || !good || nRows <= 0) {
        return;
    }
    double n = (double)nRows;
    for (int pass = 0; pass < kCoverTrainPasses; pass++) {
        double grad[kCoverFeatureCount] = {};
        for (int r = 0; r < nRows; r++) {
            double p = CoverScoreOf(rows[r], weightsOut);
            double err = p - (good[r] ? 1.0 : 0.0);
            for (int i = 0; i < kCoverFeatureCount; i++) {
                grad[i] += err * rows[r].v[i] / n;
            }
        }
        for (int i = 0; i < kCoverFeatureCount; i++) {
            grad[i] += kCoverPriorPull * (weightsOut[i] - gCoverPriorWeights[i]);
            weightsOut[i] -= kCoverLearningRate * grad[i];
        }
    }
}
