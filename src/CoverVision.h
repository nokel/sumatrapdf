/* Copyright 2022 the SumatraPDF project authors (see AUTHORS file).
   License: GPLv3 */

class EngineBase;
struct Pixmap;

constexpr int kCoverFeatureCount = 13;
constexpr int kCoverStoreVersion = 1;

constexpr int kCoverProbeHeight = 420;
constexpr float kCoverSpreadRatio = 1.15f;
constexpr int kCoverMinSourceWidth = 120;
constexpr int kCoverMinSourceHeight = 160;
constexpr double kCoverAcceptScore = 0.5;
constexpr int kCoverPatchSampleTarget = 4000;
constexpr double kCoverRatioLow = 0.5;
constexpr double kCoverRatioHigh = 0.85;
constexpr double kCoverMinImageShare = 0.35;
constexpr double kCoverInsetImageShare = 0.08;
constexpr int kCoverHeight = 520;
constexpr int kCoverMaxHeight = 1200;
constexpr int kCoverPageHeight = 900;
constexpr int kCoverPageScanLimit = 3;
constexpr int kCoverMinBytes = 1200;
constexpr double kCoverTextPageWhite = 0.9;
constexpr double kCoverPlainPageWhite = 0.82;
constexpr double kCoverScoreTieBand = 0.05;
constexpr int kCoverWebpQuality = 90;

#define kCoverStoreFileName "SumatraCovers.txt"

struct CoverFeatures {
    double v[kCoverFeatureCount] = {};
};

struct CoverCandidate {
    RectF rect;
    bool fromEmbeddedImage = false;
    int sourceWidth = 0;
    int sourceHeight = 0;
};

struct ScoredCover {
    CoverCandidate candidate;
    CoverFeatures features;
    double score = 0;
};

struct CoverPatchStats {
    double white = 1.0;
    double dark = 0;
    double saturation = 0;
    double contrast = 0;
    double edges = 0;
    double colours = 0;
};

struct CoverBuildLocation {
    bool insideBook = false;
    int pageNo = 0;
    RectF rect;
    int rotation = 0;
};

const char* CoverFeatureName(int idx);
const double* CoverPriorWeights();

double CoverRectArea(RectF r);
CoverPatchStats CoverStatsOfRegion(const Pixmap* px, Rect area);
CoverFeatures CoverFeaturesOf(const CoverCandidate& c, RectF pageRect, const CoverPatchStats& stats);
double CoverScoreOf(const CoverFeatures& f, const double* weights);
bool CoverRectsOverlap(RectF a, RectF b);
void CoverTrainWeights(const CoverFeatures* rows, const bool* good, int nRows, double* weightsOut);

int CoverTargetHeightOf(const CoverCandidate& c);
Pixmap* CoverScaleToHeight(const Pixmap* src, int height);
Str CoverEncodePixmap(const Pixmap* px, int maxHeight = kCoverMaxHeight);
Str CoverImageResourceForBook(Str filePath);
Str CoverBuildForBook(Str filePath, Str bookId, Str title, Str author, Str series, int year,
                      CoverBuildLocation* location);
Str CoverBuildFromLocation(Str filePath, const CoverBuildLocation& location);

Pixmap* CoverRenderRegion(EngineBase* engine, int pageNo, RectF rect, int targetHeight);
void CoverCandidatesOfPage(EngineBase* engine, int pageNo, Vec<CoverCandidate>& out);
void CoverScoreCandidates(EngineBase* engine, int pageNo, const Vec<CoverCandidate>& in, Vec<ScoredCover>& out);
bool CoverPickOfPage(EngineBase* engine, int pageNo, ScoredCover* out);

void CoverModelGetWeights(double* out);
void CoverModelLearnFromCrop(EngineBase* engine, int pageNo, RectF rect);
void CoverModelLearnFromBookCrop(Str filePath, int pageNo, RectF rect);
void CoverModelLearnFromImage(Str imageData);
int CoverModelExampleCount();
void CoverStoreForget();

bool CoverChoiceIsByHand(Str bookId);
bool CoverChoiceGetPageCrop(Str bookId, int* pageNoOut, RectF* rectOut);
Str CoverChoiceGetFilePath(Str bookId);
void CoverChoiceRememberPageCrop(Str bookId, int pageNo, RectF rect);
void CoverChoiceRememberFile(Str bookId, Str fromPath);
void CoverChoiceRememberImage(Str bookId);
void CoverChoiceForget(Str bookId);
