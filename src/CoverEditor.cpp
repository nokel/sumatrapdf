/* Copyright 2026 the SumatraPDF project authors (see AUTHORS file).
   License: Simplified BSD (see COPYING.BSD) */

#include "base/Base.h"
#include "base/ScopedWin.h"
#include "base/File.h"
#include "base/Win.h"
#include "base/GdiPlusUtil.h"
#include "base/GuessFileType.h"
#include "base/Pixmap.h"

#include "gui/Dpi.h"
#include "gui/UIModels.h"
#include "gui/PlatformFont.h"

#include "Settings.h"

#include "Annotation.h"
#include "DocProperties.h"
#include "EngineBase.h"
#include "EngineAll.h"
#include "ImageReader.h"
#include "MainWindow.h"
#include "Theme.h"
#include "LibraryPage.h"
#include "CoverVision.h"

#include "CoverEditor.h"

constexpr const char* kLinkCoverFile = "<Library,CoverFile>";
constexpr const char* kLinkCoverPage = "<Library,CoverPage>";
constexpr const char* kLinkCoverShut = "<Library,CoverShut>";
constexpr const char* kLinkCoverPrev = "<Library,CoverPrev>";
constexpr const char* kLinkCoverNext = "<Library,CoverNext>";
constexpr const char* kLinkCoverBack = "<Library,CoverBack>";
constexpr const char* kLinkCoverTake = "<Library,CoverTake>";

constexpr int kHandleReach = 22;
constexpr int kMinBox = 24;

enum class CoverStep {
    Shut,
    Source,
    Crop,
};

enum class CoverDrag {
    None,
    New,
    Move,
    TopLeft,
    TopRight,
    BottomLeft,
    BottomRight,
};

struct CoverBox {
    int x0 = 0;
    int y0 = 0;
    int x1 = 0;
    int y1 = 0;
};

static int BoxLeft(const CoverBox& b) {
    return b.x0 < b.x1 ? b.x0 : b.x1;
}

static int BoxTop(const CoverBox& b) {
    return b.y0 < b.y1 ? b.y0 : b.y1;
}

static int BoxRight(const CoverBox& b) {
    return b.x0 > b.x1 ? b.x0 : b.x1;
}

static int BoxBottom(const CoverBox& b) {
    return b.y0 > b.y1 ? b.y0 : b.y1;
}

static Rect BoxRect(const CoverBox& b) {
    return Rect(BoxLeft(b), BoxTop(b), BoxRight(b) - BoxLeft(b), BoxBottom(b) - BoxTop(b));
}

struct CoverEditorState {
    CoverStep step = CoverStep::Shut;
    HWND hwnd = nullptr;
    Str bookId;
    Str bookPath;
    Str title;

    EngineBase* engine = nullptr;
    bool engineTried = false;
    int pageNo = 1;
    int pageCount = 0;

    int wantPage = 0;
    int wantDx = 0;
    int wantDy = 0;
    bool rendering = false;
    Pixmap* shown = nullptr;
    int shownPage = 0;
    RectF shownPageRect;
    Rect shownAt;
    bool renderFailed = false;

    bool busy = false;
    Str note;

    bool hasBox = false;
    CoverBox box;
    CoverDrag drag = CoverDrag::None;
    Point dragLast;
};

static CRITICAL_SECTION gCoverEditLock;
static bool gCoverEditLockReady = false;
static CoverEditorState gEd;

static void EnterEdit() {
    if (!gCoverEditLockReady) {
        InitializeCriticalSection(&gCoverEditLock);
        gCoverEditLockReady = true;
    }
    EnterCriticalSection(&gCoverEditLock);
}

static void LeaveEdit() {
    LeaveCriticalSection(&gCoverEditLock);
}

static void EditRepaint() {
    if (gEd.hwnd && IsWindow(gEd.hwnd)) {
        InvalidateRect(gEd.hwnd, nullptr, FALSE);
    }
}

static void SetNote(Str s) {
    str::Free(gEd.note);
    gEd.note = str::Dup(s);
}

static void FreeEditState() {
    if (gEd.engine) {
        gEd.engine->Release();
        gEd.engine = nullptr;
    }
    FreePixmap(gEd.shown);
    gEd.shown = nullptr;
    str::FreePtr(&gEd.bookId);
    str::FreePtr(&gEd.bookPath);
    str::FreePtr(&gEd.title);
    str::FreePtr(&gEd.note);
    gEd = CoverEditorState{};
}

bool CoverEditorActive() {
    EnterEdit();
    bool on = gEd.step != CoverStep::Shut;
    LeaveEdit();
    return on;
}

void CoverEditorOpen(MainWindow* win, Str bookId, Str bookPath, Str title) {
    if (len(bookId) == 0 || len(bookPath) == 0) {
        return;
    }
    EnterEdit();
    HWND hwnd = win ? win->hwndCanvas : nullptr;
    FreeEditState();
    gEd.step = CoverStep::Source;
    gEd.hwnd = hwnd;
    gEd.bookId = str::Dup(bookId);
    gEd.bookPath = str::Dup(bookPath);
    gEd.title = str::Dup(title);
    gEd.pageNo = 1;
    LeaveEdit();
    EditRepaint();
}

static void CloseLocked() {
    HWND hwnd = gEd.hwnd;
    FreeEditState();
    gEd.hwnd = hwnd;
    if (hwnd && IsWindow(hwnd)) {
        InvalidateRect(hwnd, nullptr, FALSE);
    }
}

void CoverEditorShutdown() {
    EnterEdit();
    FreeEditState();
    LeaveEdit();
}

static EngineBase* EngineLocked() {
    if (gEd.engine || gEd.engineTried) {
        return gEd.engine;
    }
    gEd.engineTried = true;
    TempStr path = str::DupTemp(gEd.bookPath);
    LeaveEdit();
    EngineBase* engine = CreateEngineFromFile(path, nullptr, false);
    EnterEdit();
    if (gEd.step == CoverStep::Shut) {
        if (engine) {
            engine->Release();
        }
        return nullptr;
    }
    gEd.engine = engine;
    if (engine) {
        gEd.pageCount = engine->pageCount;
        gEd.pageNo = limitValue(gEd.pageNo, 1, engine->pageCount);
    }
    return gEd.engine;
}

struct CoverEditJob {
    int pageNo = 0;
    int dx = 0;
    int dy = 0;
};

static void RenderThread(CoverEditJob* job) {
    AutoDelete del(job);
    EnterEdit();
    if (gEd.step != CoverStep::Crop) {
        gEd.rendering = false;
        LeaveEdit();
        return;
    }
    EngineBase* engine = EngineLocked();
    if (!engine || gEd.step != CoverStep::Crop) {
        gEd.rendering = false;
        gEd.renderFailed = true;
        LeaveEdit();
        EditRepaint();
        return;
    }
    int pageNo = limitValue(job->pageNo, 1, engine->pageCount);
    gEd.pageNo = pageNo;
    gEd.pageCount = engine->pageCount;
    engine->AddRef();
    LeaveEdit();

    RectF pageRect = engine->PageMediabox(pageNo);
    Pixmap* px = nullptr;
    if (pageRect.dx > 0 && pageRect.dy > 0 && job->dx > 0 && job->dy > 0) {
        float zx = (float)job->dx / pageRect.dx;
        float zy = (float)job->dy / pageRect.dy;
        float zoom = zx < zy ? zx : zy;
        RenderPageArgs args(pageNo, zoom, 0, &pageRect);
        px = engine->RenderPage(args);
        if (px && px->format == PixmapFormat::Native) {
            Pixmap* conv = PixmapCopyAs32bppDIB(px);
            FreePixmap(px);
            px = conv;
        }
    }
    engine->Release();

    EnterEdit();
    gEd.rendering = false;
    if (gEd.step != CoverStep::Crop) {
        FreePixmap(px);
        LeaveEdit();
        return;
    }
    FreePixmap(gEd.shown);
    gEd.shown = px;
    gEd.shownPage = pageNo;
    gEd.shownPageRect = pageRect;
    gEd.renderFailed = (px == nullptr);
    gEd.hasBox = false;
    gEd.drag = CoverDrag::None;
    LeaveEdit();
    EditRepaint();
}

static void WantPageLocked(int pageNo, int dx, int dy) {
    bool same = gEd.shown && gEd.shownPage == pageNo && gEd.wantDx == dx && gEd.wantDy == dy;
    if (same || gEd.rendering) {
        return;
    }
    if (gEd.wantPage == pageNo && gEd.wantDx == dx && gEd.wantDy == dy && gEd.renderFailed) {
        return;
    }
    gEd.wantPage = pageNo;
    gEd.wantDx = dx;
    gEd.wantDy = dy;
    gEd.rendering = true;
    gEd.renderFailed = false;
    auto* job = new CoverEditJob();
    job->pageNo = pageNo;
    job->dx = dx;
    job->dy = dy;
    RunAsync(MkFunc0<CoverEditJob>(RenderThread, job), "coverRender");
}

static void AddEditLink(MainWindow* win, Rect r, Str target, Str tip = {}) {
    if (r.dx <= 0 || r.dy <= 0) {
        return;
    }
    win->staticLinks.Append(new StaticLink(r, target, tip));
}

static void DrawTextInRect(HDC hdc, Rect r, Str text, UINT flags, COLORREF col) {
    if (len(text) == 0 || r.dy <= 0) {
        return;
    }
    TempWStr ws = ToWStrTemp(text);
    RECT rc = {r.x, r.y, r.x + r.dx, r.y + r.dy};
    SetTextColor(hdc, col);
    DrawTextW(hdc, ws.s, ws.len, &rc, flags);
}

static void FillRoundRect(HDC hdc, Rect r, COLORREF col, int radius) {
    AutoDeleteBrush br(CreateSolidBrush(col));
    AutoDeletePen pen(CreatePen(PS_SOLID, 1, col));
    ScopedSelectObject sb(hdc, br);
    ScopedSelectObject sp(hdc, pen);
    RoundRect(hdc, r.x, r.y, r.x + r.dx, r.y + r.dy, radius, radius);
}

static COLORREF MixColor(COLORREF a, COLORREF b, int pct) {
    int r = (GetRValue(a) * (100 - pct) + GetRValue(b) * pct) / 100;
    int g = (GetGValue(a) * (100 - pct) + GetGValue(b) * pct) / 100;
    int bl = (GetBValue(a) * (100 - pct) + GetBValue(b) * pct) / 100;
    return RGB(r, g, bl);
}

static Rect DrawButton(HDC hdc, MainWindow* win, Rect r, Str label, Str target, bool on, COLORREF bg, COLORREF text) {
    COLORREF face = on ? MixColor(bg, text, 12) : MixColor(bg, text, 5);
    FillRoundRect(hdc, r, face, DpiScale(8));
    COLORREF ink = on ? text : MixColor(text, bg, 55);
    DrawTextInRect(hdc, Rect(r.x, r.y + (r.dy - DpiScale(18)) / 2, r.dx, DpiScale(20)), label,
                   DT_CENTER | DT_SINGLELINE | DT_NOPREFIX | DT_END_ELLIPSIS, ink);
    if (on) {
        AddEditLink(win, r, target);
    }
    return r;
}

static void DrawSourceStep(MainWindow* win, HDC hdc, Rect rc, HFONT fontHead, HFONT fontBody) {
    COLORREF bg = ThemeMainWindowBackgroundColor();
    COLORREF text = ThemeWindowTextColor();
    int padX = DpiScale(40);
    int btnDx = DpiScale(280);
    if (btnDx > rc.dx - padX * 2) {
        btnDx = rc.dx - padX * 2;
    }
    int btnDy = DpiScale(38);
    int gap = DpiScale(12);
    int x = rc.x + (rc.dx - btnDx) / 2;
    int y = rc.y + DpiScale(60);

    SelectObject(hdc, fontHead);
    DrawTextInRect(hdc, Rect(rc.x + padX, y, rc.dx - padX * 2, DpiScale(60)), gEd.title,
                   DT_CENTER | DT_WORDBREAK | DT_NOPREFIX | DT_END_ELLIPSIS, text);
    y += DpiScale(60);

    SelectObject(hdc, fontBody);
    DrawTextInRect(hdc, Rect(rc.x + padX, y, rc.dx - padX * 2, DpiScale(24)), StrL("Where should the cover come from?"),
                   DT_CENTER | DT_SINGLELINE | DT_NOPREFIX, MixColor(text, bg, 40));
    y += DpiScale(40);

    bool on = !gEd.busy;
    DrawButton(hdc, win, Rect(x, y, btnDx, btnDy), StrL("Choose an image file"), Str(kLinkCoverFile), on, bg, text);
    y += btnDy + gap;
    DrawButton(hdc, win, Rect(x, y, btnDx, btnDy), StrL("Take it from a page"), Str(kLinkCoverPage), on, bg, text);
    y += btnDy + gap;
    y += gap;
    Str note = len(gEd.note) > 0 ? gEd.note : (gEd.busy ? StrL("Working...") : Str{});
    DrawTextInRect(hdc, Rect(rc.x + padX, y, rc.dx - padX * 2, DpiScale(24)), note,
                   DT_CENTER | DT_SINGLELINE | DT_NOPREFIX | DT_END_ELLIPSIS, MixColor(text, bg, 40));
    y += DpiScale(34);
    DrawButton(hdc, win, Rect(x, y, btnDx, btnDy), StrL("Cancel"), Str(kLinkCoverShut), on, bg, text);
}

static void DimRect(HDC hdc, Rect r) {
    if (r.dx <= 0 || r.dy <= 0) {
        return;
    }
    static const WORD checker[8] = {0xaaaa, 0x5555, 0xaaaa, 0x5555, 0xaaaa, 0x5555, 0xaaaa, 0x5555};
    HBITMAP bmp = CreateBitmap(8, 8, 1, 1, checker);
    if (!bmp) {
        return;
    }
    HBRUSH br = CreatePatternBrush(bmp);
    if (!br) {
        DeleteObject(bmp);
        return;
    }
    HGDIOBJ oldBrush = SelectObject(hdc, br);
    COLORREF oldText = SetTextColor(hdc, RGB(0, 0, 0));
    COLORREF oldBk = SetBkColor(hdc, RGB(255, 255, 255));
    int oldMode = SetBkMode(hdc, OPAQUE);
    PatBlt(hdc, r.x, r.y, r.dx, r.dy, 0x00A000C9);
    SetBkMode(hdc, oldMode);
    SetBkColor(hdc, oldBk);
    SetTextColor(hdc, oldText);
    SelectObject(hdc, oldBrush);
    DeleteObject(br);
    DeleteObject(bmp);
}

static void DrawCropOverlay(HDC hdc, Rect at, const CoverBox& box) {
    int left = BoxLeft(box);
    int top = BoxTop(box);
    int right = BoxRight(box);
    int bottom = BoxBottom(box);
    DimRect(hdc, Rect(at.x, at.y, at.dx, top - at.y));
    DimRect(hdc, Rect(at.x, bottom, at.dx, at.y + at.dy - bottom));
    DimRect(hdc, Rect(at.x, top, left - at.x, bottom - top));
    DimRect(hdc, Rect(right, top, at.x + at.dx - right, bottom - top));

    AutoDeletePen pen(CreatePen(PS_SOLID, DpiScale(2), RGB(255, 255, 255)));
    ScopedSelectObject sp(hdc, pen);
    ScopedSelectObject sb(hdc, GetStockObject(NULL_BRUSH));
    Rectangle(hdc, left, top, right, bottom);

    AutoDeleteBrush white(CreateSolidBrush(RGB(255, 255, 255)));
    ScopedSelectObject sw(hdc, white);
    int rad = DpiScale(6);
    int cx[4] = {left, right, left, right};
    int cy[4] = {top, top, bottom, bottom};
    for (int i = 0; i < 4; i++) {
        Ellipse(hdc, cx[i] - rad, cy[i] - rad, cx[i] + rad, cy[i] + rad);
    }
}

static void DrawCropStep(MainWindow* win, HDC hdc, Rect rc, HFONT fontHead, HFONT fontBody) {
    COLORREF bg = ThemeMainWindowBackgroundColor();
    COLORREF text = ThemeWindowTextColor();
    COLORREF dim = MixColor(text, bg, 45);
    COLORREF link = ThemeWindowLinkColor();
    int padX = DpiScale(24);
    int y = rc.y + DpiScale(14);

    SelectObject(hdc, fontBody);
    int headDy = DpiScale(24);
    bool canPrev = gEd.pageNo > 1 && !gEd.busy;
    bool canNext = (gEd.pageCount == 0 || gEd.pageNo < gEd.pageCount) && !gEd.busy;
    Rect rcPrev(rc.x + padX, y, DpiScale(90), headDy);
    DrawTextInRect(hdc, rcPrev, StrL("Previous"), DT_LEFT | DT_SINGLELINE | DT_NOPREFIX, canPrev ? link : dim);
    if (canPrev) {
        AddEditLink(win, rcPrev, Str(kLinkCoverPrev));
    }
    Rect rcNext(rc.x + rc.dx - padX - DpiScale(90), y, DpiScale(90), headDy);
    DrawTextInRect(hdc, rcNext, StrL("Next"), DT_RIGHT | DT_SINGLELINE | DT_NOPREFIX, canNext ? link : dim);
    if (canNext) {
        AddEditLink(win, rcNext, Str(kLinkCoverNext));
    }
    Str where = gEd.pageCount > 0 ? Str(fmt("Page %d of %d", gEd.pageNo, gEd.pageCount)) : StrL("Loading...");
    DrawTextInRect(hdc, Rect(rc.x + padX + DpiScale(100), y, rc.dx - padX * 2 - DpiScale(200), headDy), where,
                   DT_CENTER | DT_SINGLELINE | DT_NOPREFIX, text);
    y += headDy + DpiScale(6);

    DrawTextInRect(hdc, Rect(rc.x + padX, y, rc.dx - padX * 2, DpiScale(22)),
                   StrL("Drag across the part of the page you want, then drag the corners to adjust it."),
                   DT_CENTER | DT_SINGLELINE | DT_NOPREFIX | DT_END_ELLIPSIS, dim);
    y += DpiScale(28);

    int btnDy = DpiScale(38);
    int footDy = btnDy + DpiScale(28);
    Rect area(rc.x + padX, y, rc.dx - padX * 2, rc.y + rc.dy - footDy - y);
    if (area.dy > 0 && area.dx > 0) {
        WantPageLocked(gEd.pageNo, area.dx, area.dy);
    }

    Pixmap* shown = gEd.shown;
    if (shown && area.dy > 0 && area.dx > 0) {
        Rect at(area.x + (area.dx - shown->width) / 2, area.y + (area.dy - shown->height) / 2, shown->width,
                shown->height);
        gEd.shownAt = at;
        BlitPixmap(shown, hdc, at);
        if (gEd.hasBox) {
            DrawCropOverlay(hdc, at, gEd.box);
        }
    } else {
        gEd.shownAt = Rect();
        Str msg = gEd.renderFailed ? StrL("That page could not be drawn.") : StrL("Drawing the page...");
        DrawTextInRect(hdc, Rect(area.x, area.y + area.dy / 2 - DpiScale(12), area.dx, DpiScale(24)), msg,
                       DT_CENTER | DT_SINGLELINE | DT_NOPREFIX, dim);
    }

    SelectObject(hdc, fontBody);
    int btnDx = DpiScale(140);
    int gap = DpiScale(20);
    int btnY = rc.y + rc.dy - btnDy - DpiScale(14);
    int btnX = rc.x + (rc.dx - btnDx * 2 - gap) / 2;
    DrawButton(hdc, win, Rect(btnX, btnY, btnDx, btnDy), StrL("Cancel"), Str(kLinkCoverBack), !gEd.busy, bg, text);
    Str take = gEd.busy ? StrL("Saving...") : StrL("Accept");
    bool canTake = gEd.hasBox && gEd.shown != nullptr && !gEd.busy;
    DrawButton(hdc, win, Rect(btnX + btnDx + gap, btnY, btnDx, btnDy), take, Str(kLinkCoverTake), canTake, bg, text);
    (void)fontHead;
}

void CoverEditorDraw(MainWindow* win, HDC hdc, Rect rc) {
    EnterEdit();
    if (gEd.step == CoverStep::Shut) {
        LeaveEdit();
        return;
    }
    gEd.hwnd = win->hwndCanvas;
    COLORREF bg = ThemeMainWindowBackgroundColor();
    HdcFillRect(hdc, rc, bg);
    SetBkMode(hdc, TRANSPARENT);
    HFONT fontHead = HdcCreateSimpleFont(hdc, "MS Shell Dlg", 18)->GetHFont();
    HFONT fontBody = HdcCreateSimpleFont(hdc, "MS Shell Dlg", 13)->GetHFont();
    if (gEd.step == CoverStep::Source) {
        DrawSourceStep(win, hdc, rc, fontHead, fontBody);
    } else {
        DrawCropStep(win, hdc, rc, fontHead, fontBody);
    }
    SelectObject(hdc, GetStockObject(SYSTEM_FONT));
    LeaveEdit();
}

static bool NearPoint(int x, int y, int px, int py) {
    int reach = DpiScale(kHandleReach);
    return abs(x - px) <= reach && abs(y - py) <= reach;
}

static CoverDrag DragKindAt(int x, int y) {
    if (!gEd.hasBox) {
        return CoverDrag::New;
    }
    int left = BoxLeft(gEd.box);
    int top = BoxTop(gEd.box);
    int right = BoxRight(gEd.box);
    int bottom = BoxBottom(gEd.box);
    if (NearPoint(x, y, left, top)) {
        return CoverDrag::TopLeft;
    }
    if (NearPoint(x, y, right, top)) {
        return CoverDrag::TopRight;
    }
    if (NearPoint(x, y, left, bottom)) {
        return CoverDrag::BottomLeft;
    }
    if (NearPoint(x, y, right, bottom)) {
        return CoverDrag::BottomRight;
    }
    bool inside = x >= left && x <= right && y >= top && y <= bottom;
    return inside ? CoverDrag::Move : CoverDrag::New;
}

bool CoverEditorOnLeftButtonDown(MainWindow* win, int x, int y) {
    EnterEdit();
    if (gEd.step != CoverStep::Crop || gEd.busy || !gEd.shown || gEd.shownAt.dx <= 0) {
        LeaveEdit();
        return false;
    }
    if (!gEd.shownAt.Contains(Point(x, y))) {
        LeaveEdit();
        return false;
    }
    gEd.drag = DragKindAt(x, y);
    if (gEd.drag == CoverDrag::New) {
        gEd.hasBox = true;
        gEd.box = CoverBox{x, y, x, y};
    }
    gEd.dragLast = Point(x, y);
    LeaveEdit();
    SetCapture(win->hwndCanvas);
    EditRepaint();
    return true;
}

bool CoverEditorOnMouseMove(MainWindow* win, int x, int y) {
    EnterEdit();
    if (gEd.step != CoverStep::Crop || gEd.drag == CoverDrag::None) {
        bool over = gEd.step == CoverStep::Crop;
        LeaveEdit();
        return over;
    }
    Rect at = gEd.shownAt;
    int lo = at.x;
    int hi = at.x + at.dx;
    int top = at.y;
    int bot = at.y + at.dy;
    int minBox = DpiScale(kMinBox);
    int dx = x - gEd.dragLast.x;
    int dy = y - gEd.dragLast.y;
    gEd.dragLast = Point(x, y);
    CoverBox& b = gEd.box;
    switch (gEd.drag) {
        case CoverDrag::New:
            b.x1 = limitValue(b.x1 + dx, lo, hi);
            b.y1 = limitValue(b.y1 + dy, top, bot);
            break;
        case CoverDrag::Move: {
            int moveX = limitValue(dx, lo - BoxLeft(b), hi - BoxRight(b));
            int moveY = limitValue(dy, top - BoxTop(b), bot - BoxBottom(b));
            b.x0 += moveX;
            b.x1 += moveX;
            b.y0 += moveY;
            b.y1 += moveY;
            break;
        }
        case CoverDrag::TopLeft:
            b.x0 = limitValue(b.x0 + dx, lo, b.x1 - minBox);
            b.y0 = limitValue(b.y0 + dy, top, b.y1 - minBox);
            break;
        case CoverDrag::TopRight:
            b.x1 = limitValue(b.x1 + dx, b.x0 + minBox, hi);
            b.y0 = limitValue(b.y0 + dy, top, b.y1 - minBox);
            break;
        case CoverDrag::BottomLeft:
            b.x0 = limitValue(b.x0 + dx, lo, b.x1 - minBox);
            b.y1 = limitValue(b.y1 + dy, b.y0 + minBox, bot);
            break;
        case CoverDrag::BottomRight:
            b.x1 = limitValue(b.x1 + dx, b.x0 + minBox, hi);
            b.y1 = limitValue(b.y1 + dy, b.y0 + minBox, bot);
            break;
        case CoverDrag::None:
            break;
    }
    LeaveEdit();
    EditRepaint();
    return true;
}

bool CoverEditorOnLeftButtonUp(MainWindow* win) {
    EnterEdit();
    if (gEd.step != CoverStep::Crop || gEd.drag == CoverDrag::None) {
        LeaveEdit();
        return false;
    }
    gEd.drag = CoverDrag::None;
    Rect r = BoxRect(gEd.box);
    int minBox = DpiScale(kMinBox);
    if (r.dx < minBox || r.dy < minBox) {
        gEd.hasBox = false;
    } else {
        gEd.box = CoverBox{r.x, r.y, r.x + r.dx, r.y + r.dy};
    }
    LeaveEdit();
    ReleaseCapture();
    EditRepaint();
    return true;
}

void CoverEditorOnCaptureLost() {
    EnterEdit();
    if (gEd.drag != CoverDrag::None) {
        gEd.drag = CoverDrag::None;
    }
    LeaveEdit();
}

static TempStr AskForImageFile(MainWindow* win) {
    WCHAR fileName[MAX_PATH + 1]{};
    str::Builder filter(128);
    filter.Append(
        "Picture "
        "files\1*.png;*.jpg;*.jpeg;*.jpe;*.jfif;*.gif;*.bmp;*.dib;*.tif;*.tiff;*.webp;*.jxl;*.heic;*.heif;*.avif;*.jp2;"
        "*.j2k;*.jpf;*.jpx;*.ico;*.tga;*.pbm;*.pgm;*.ppm;*.pnm;*.pam\1");
    filter.Append("All files\1*.*\1");
    Str filterStr = ToStr(filter);
    str::TransCharsInPlace(filterStr, StrL("\1"), StrL("\0"));
    OPENFILENAME ofn{};
    ofn.lStructSize = sizeof(ofn);
    ofn.hwndOwner = win ? win->hwndFrame : nullptr;
    ofn.lpstrFile = fileName;
    ofn.nMaxFile = dimof(fileName);
    ofn.lpstrFilter = CWStrTemp(filterStr);
    ofn.nFilterIndex = 1;
    ofn.Flags = OFN_PATHMUSTEXIST | OFN_FILEMUSTEXIST | OFN_HIDEREADONLY | OFN_EXPLORER;
    if (!GetOpenFileNameW(&ofn)) {
        return nullptr;
    }
    return ToUtf8Temp(WStr(fileName));
}

static void TakeFromFile(Str path) {
    EnterEdit();
    Str bookId = str::Dup(gEd.bookId);
    LeaveEdit();
    if (len(bookId) == 0) {
        str::Free(bookId);
        return;
    }

    Str data = file::ReadFile(path);
    Pixmap* px = len(data) > 0 ? PixmapFromData(data) : nullptr;
    str::Free(data);
    Str png = CoverEncodePixmap(px);
    FreePixmap(px);

    EnterEdit();
    if (len(png) == 0) {
        SetNote(StrL("That file is not a picture we can read."));
        LeaveEdit();
        str::Free(bookId);
        str::Free(png);
        EditRepaint();
        return;
    }
    LeaveEdit();

    CoverModelLearnFromImage(png);
    CoverChoiceRememberFile(bookId, path);
    LibraryCoverReplace(bookId, png);
    str::Free(bookId);
    str::Free(png);

    EnterEdit();
    CloseLocked();
    LeaveEdit();
}

static void TakeFromPage() {
    EnterEdit();
    if (!gEd.hasBox || !gEd.shown || gEd.shownAt.dx <= 0) {
        LeaveEdit();
        return;
    }
    Rect at = gEd.shownAt;
    Rect sel = BoxRect(gEd.box);
    RectF pageRect = gEd.shownPageRect;
    int pageNo = gEd.shownPage;
    Str bookId = str::Dup(gEd.bookId);
    EngineBase* engine = gEd.engine;
    if (engine) {
        engine->AddRef();
    }
    LeaveEdit();
    EditRepaint();

    float sx = pageRect.dx / (float)at.dx;
    float sy = pageRect.dy / (float)at.dy;
    RectF crop = RectF::FromXY(pageRect.x + (float)(sel.x - at.x) * sx, pageRect.y + (float)(sel.y - at.y) * sy,
                               pageRect.x + (float)(sel.x + sel.dx - at.x) * sx,
                               pageRect.y + (float)(sel.y + sel.dy - at.y) * sy);
    crop = crop.Intersect(pageRect);

    Str png;
    if (engine && crop.dx > 0 && crop.dy > 0) {
        Pixmap* px = CoverRenderRegion(engine, pageNo, crop, kCoverMaxHeight);
        png = CoverEncodePixmap(px);
        FreePixmap(px);
    }

    if (len(png) > 0) {
        CoverModelLearnFromImage(png);
        CoverChoiceRememberPageCrop(bookId, pageNo, crop);
        if (engine) {
            CoverModelLearnFromCrop(engine, pageNo, crop);
        }
        LibraryCoverReplacePage(bookId, pageNo, crop, 0, png);
    }
    if (engine) {
        engine->Release();
    }
    str::Free(bookId);

    EnterEdit();
    if (len(png) == 0) {
        SetNote(StrL("That part of the page could not be turned into a cover."));
        LeaveEdit();
        str::Free(png);
        EditRepaint();
        return;
    }
    CloseLocked();
    LeaveEdit();
    str::Free(png);
}

struct CoverActionJob {
    int what = 0;
    Str path;
};

static void ActionThread(CoverActionJob* job) {
    AutoDelete del(job);
    switch (job->what) {
        case 0:
            TakeFromFile(job->path);
            break;
        case 2:
            TakeFromPage();
            break;
    }
    str::Free(job->path);
    EnterEdit();
    if (gEd.step != CoverStep::Shut) {
        gEd.busy = false;
    }
    LeaveEdit();
    EditRepaint();
}

static void RunAction(int what, Str path = {}) {
    EnterEdit();
    gEd.busy = true;
    SetNote({});
    LeaveEdit();
    EditRepaint();
    auto* job = new CoverActionJob();
    job->what = what;
    job->path = str::Dup(path);
    RunAsync(MkFunc0<CoverActionJob>(ActionThread, job), "coverAction");
}

bool CoverEditorOnLink(MainWindow* win, Str url) {
    EnterEdit();
    if (gEd.step == CoverStep::Shut) {
        LeaveEdit();
        return false;
    }
    bool busy = gEd.busy;
    LeaveEdit();

    if (str::Eq(url, Str(kLinkCoverShut))) {
        EnterEdit();
        CloseLocked();
        LeaveEdit();
        return true;
    }
    if (busy) {
        return true;
    }
    if (str::Eq(url, Str(kLinkCoverFile))) {
        TempStr path = AskForImageFile(win);
        if (path && len(path) > 0) {
            RunAction(0, Str(path));
        }
        return true;
    }
    if (str::Eq(url, Str(kLinkCoverPage))) {
        EnterEdit();
        gEd.step = CoverStep::Crop;
        gEd.hasBox = false;
        gEd.renderFailed = false;
        SetNote({});
        LeaveEdit();
        EditRepaint();
        return true;
    }
    if (str::Eq(url, Str(kLinkCoverBack))) {
        EnterEdit();
        gEd.step = CoverStep::Source;
        gEd.hasBox = false;
        SetNote({});
        LeaveEdit();
        EditRepaint();
        return true;
    }
    if (str::Eq(url, Str(kLinkCoverPrev)) || str::Eq(url, Str(kLinkCoverNext))) {
        EnterEdit();
        int step = str::Eq(url, Str(kLinkCoverPrev)) ? -1 : 1;
        int most = gEd.pageCount > 0 ? gEd.pageCount : gEd.pageNo + 1;
        gEd.pageNo = limitValue(gEd.pageNo + step, 1, most);
        gEd.hasBox = false;
        gEd.renderFailed = false;
        LeaveEdit();
        EditRepaint();
        return true;
    }
    if (str::Eq(url, Str(kLinkCoverTake))) {
        RunAction(2);
        return true;
    }
    return false;
}
