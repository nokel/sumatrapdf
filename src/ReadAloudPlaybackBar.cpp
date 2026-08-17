/* Copyright 2022 the SumatraPDF project authors (see AUTHORS file).
   License: GPLv3 */

#include "base/Base.h"
#include "gui/Dpi.h"
#include "base/Win.h"

#include "gui/UIModels.h"
#include "gui/Layout.h"
#include "gui/win/WinGui.h"
#include "gui/PlatformFont.h"
#include "gui/Gfx.h"
#include "gui/GuiColors.h"
#include "gui/VirtCtrl.h"

#include "Settings.h"
#include "AppSettings.h"
#include "Translations.h"
#include "DocController.h"
#include "EngineBase.h"
#include "DisplayModel.h"
#include "TextToSpeech.h"
#include "WindowTab.h"
#include "MainWindow.h"
#include "ReadAloudHighlight.h"
#include "SumatraPDF.h"
#include "Commands.h"
#include "AudiobookCharacters.h"
#include "Theme.h"
#include "ReadAloudPlaybackBar.h"

// The transport buttons, in the order they appear.
enum RaBtn {
    kBtnRestart = 0,   // <<<  play from the beginning
    kBtnPrev,          // <<   previous sentence
    kBtnPause,         // ||   pause
    kBtnPlay,          // >    play / resume
    kBtnStop,          // []   stop (remembers the place)
    kBtnNext,          // >>   skip a sentence
    kBtnPage,          // >>>  skip a page
    kBtnSpeed,         // 1.0x (Windows TTS only - Chatterbox has no rate control)
    kBtnCount
};

struct ReadAloudPlaybackBar : WindowBase {
    ReadAloudPlaybackBar() = default;
    ~ReadAloudPlaybackBar() override = default;

    HWND Create(HWND parentCanvas);
    void SetSession(WindowTab* tab);
    void BuildLayout();
    void SyncLabels();
    void SyncColors();
    void UpdateLayout();
    void OnPaint(WindowBase::PaintEvent* ev);
    void OnClick(int btn);

    WindowTab* sessionTab = nullptr;
    VirtButton* btn[kBtnCount] = {};
    VirtText* status = nullptr;
    bool enabled[kBtnCount] = {};
    bool showResume = false;
    bool isAudiobook = false;   // Chatterbox engine, not Windows TTS
};

// Labels are drawn as text, so they must exist in the UI font. Segoe UI has
// these; the media glyphs (â®ï¸ etc) are emoji-font only and render as boxes.
static const char* kBtnLabels[kBtnCount] = {
    "|<<", "<<", "||", ">", "[]", ">>", ">>|",
    nullptr /* speed: label is computed */,
};

constexpr int kBarMargin = 8;
constexpr int kBarPadX = 12;
constexpr int kBarPadY = 6;
constexpr int kBtnGap = 8;
constexpr int kBtnPadX = 10;
constexpr int kBtnPadY = 3;

static Str ReadAloudScopeLabel(WindowTab* tab) {
    if (!tab) {
        return {};
    }
    switch (tab->readAloudScope) {
        case WindowTab::ReadAloudScopeSelection:
            return _TRA("Selection");
        case WindowTab::ReadAloudScopeViewport:
            return _TRA("From top");
        case WindowTab::ReadAloudScopeCursor:
            return _TRA("From cursor");
        case WindowTab::ReadAloudScopeSmart:
        default:
            return _TRA("Smart start");
    }
}

static TempStr ReadAloudPlaybackBarTextTemp(WindowTab* tab) {
    if (!tab) {
        return {};
    }

    Str docName = tab->GetTabTitle();
    if (len(docName) == 0) {
        docName = _TRA("document");
    }

    if (AudiobookIsRunning()) {
        // the engine owns the position; page/scope here are Windows TTS state
        // and would be wrong for it
        return fmt(_TRA("Reading \xC2\xB7 %s \xC2\xB7 character voices").s, docName);
    }

    int pageNo = 0;
    int pageCount = 0;
    bool hasPage = ReadAloudGetProgressPage(tab, &pageNo, &pageCount);
    Str scope = ReadAloudScopeLabel(tab);

    if (hasPage && pageCount > 0) {
        return fmt(_TRA("Reading \xC2\xB7 %s \xC2\xB7 page %d of %d \xC2\xB7 %s").s, docName, pageNo, pageCount, scope);
    }
    return fmt(_TRA("Reading \xC2\xB7 %s \xC2\xB7 %s").s, docName, scope);
}

static TempStr SpeedLabelTemp() {
    return ReadAloudSpeedLabelTemp(TtsGetSpeed());
}

// every button shares one handler; which one was clicked is in userData, and
// ev->button tells a right-click from a left-click (speed and skip-page go
// backwards on right-click)
static void OnBtnClicked(ReadAloudPlaybackBar* bar, VirtMouseEvent* ev) {
    int idx = (int)ev->target->userData;
    bool isRight = ev->button == 1;
    if (isRight) {
        if (idx == kBtnSpeed && bar->enabled[kBtnSpeed]) {
            ReadAloudPlaybackCycleSpeed(-1);
            bar->UpdateLayout();
            HwndRepaintNow(bar->hwnd);
        } else if (idx == kBtnPage && bar->enabled[kBtnPage]) {
            AudiobookSendCommand(StrL("/page"), StrL("{\"dir\":-1}"));
        }
        return;
    }
    bar->OnClick(idx);
}

HWND ReadAloudPlaybackBar::Create(HWND parentCanvas) {
    onPaint = MkMethod1<ReadAloudPlaybackBar, WindowBase::PaintEvent*, &ReadAloudPlaybackBar::OnPaint>(this);
    CreateCustomArgs args;
    args.parent = parentCanvas;
    args.style = WS_CHILD | SS_CENTER;
    args.exStyle = WS_EX_TOPMOST;
    args.font = GetAppBiggerFont();
    args.visible = false;
    args.isRtl = IsUIRtl();
    CreateCustom(args);
    if (hwnd) {
        BuildLayout();
    }
    return hwnd;
}

// [|<<] [<<] [||] [>] [[]] [>>] [>>|] [1.0x] [status…]. The HWND is
// WS_EX_LAYOUTRTL, but we paint into a DoubleBuffer DC that is not mirrored,
// so HBox.rtl (not GDI's flip) is what reverses the row. Buttons the engine
// can't do are collapsed, and HBox drops their gap with them.
void ReadAloudPlaybackBar::BuildLayout() {
    PlatformFont* pf = font;
    int gap = DpiScale(kBtnGap);
    int padX = DpiScale(kBarPadX);
    int padY = DpiScale(kBarPadY);
    int btnPadX = DpiScale(kBtnPadX);
    int btnPadY = DpiScale(kBtnPadY);
    Insets btnPad{btnPadY, btnPadX, btnPadY, btnPadX};

    auto* row = new HBox();
    row->alignCross = CrossAxisAlign::CrossCenter;
    row->rtl = IsUIRtl();
    row->gap = gap;

    for (int i = 0; i < kBtnCount; i++) {
        Str label = kBtnLabels[i] ? Str(kBtnLabels[i]) : Str{};
        auto* b = new VirtButton(label, pf);
        b->textPadding = btnPad;
        b->flags &= ~vwfFocusable;
        b->userData = (uintptr_t)i;
        b->onClick = MkFunc1(OnBtnClicked, this);
        btn[i] = b;
        row->AddChild(b);
    }

    status = NewVirtText({
        .font = pf,
        .isRtl = IsUIRtl(),
        .ellipsis = true,
    });
    row->AddChild(status, 1);
    layout = new Padding(row, Insets{padY, padX, padY, padX});
}

void ReadAloudPlaybackBar::SyncLabels() {
    isAudiobook = AudiobookIsRunning();
    showResume = !isAudiobook && sessionTab && CanContinueReadAloud(sessionTab) && !TtsIsSpeaking();

    // Chatterbox reads a unit at a time so it can seek; it has no rate control.
    // Windows TTS is the opposite: it can change speed but not step by sentence.
    for (int i = 0; i < kBtnCount; i++) {
        enabled[i] = true;
    }
    enabled[kBtnPrev] = isAudiobook;
    enabled[kBtnNext] = isAudiobook;
    enabled[kBtnPage] = isAudiobook;
    enabled[kBtnSpeed] = !isAudiobook;

    for (int i = 0; i < kBtnCount; i++) {
        btn[i]->SetIsVisible(enabled[i]);
    }
    btn[kBtnSpeed]->SetText(SpeedLabelTemp());
    status->SetText(ReadAloudPlaybackBarTextTemp(sessionTab));
}

void ReadAloudPlaybackBar::SyncColors() {
    Color colBg = ThemeNotificationsBackgroundColor();
    Color colTxt = ThemeNotificationsTextColor();
    Color colBorder = kColGray;
    Color colBtnBg = AccentColor(colBg, 8, -8);
    Color colBtnHover = AccentColor(colBg, 16, -16);
    for (VirtButton* b : btn) {
        b->SetColor(kColBtnBg, colBtnBg);
        b->SetColor(kColBtnBgHover, colBtnHover);
        b->SetColor(kColBtnBorder, colBorder);
        b->SetColor(kColBtnText, colTxt);
    }
    status->SetColor(kColText, colTxt);
}

void ReadAloudPlaybackBar::SetSession(WindowTab* tab) {
    sessionTab = tab;
    if (!tab || !hwnd) {
        return;
    }

    UpdateLayout();
    ShowWindow(hwnd, SW_SHOW);
    BringWindowToTop(hwnd);
    HwndRepaintNow(hwnd);
}

void ReadAloudPlaybackBar::OnClick(int btnIdx) {
    if (isAudiobook) {
        switch (btnIdx) {
            case kBtnRestart:
                AudiobookSendCommand(StrL("/restart"));
                break;
            case kBtnPrev:
                AudiobookSendCommand(StrL("/prev"));
                break;
            case kBtnPause:
                AudiobookSendCommand(StrL("/pause"));
                break;
            case kBtnPlay:
                AudiobookSendCommand(StrL("/resume"));
                break;
            case kBtnStop:
                // stops and remembers the place; the frame clears the highlight
                HwndSendCommand(GetParent(GetParent(hwnd)), CmdStopReadAloud);
                return;
            case kBtnNext:
                AudiobookSendCommand(StrL("/next"));
                break;
            case kBtnPage:
                AudiobookSendCommand(StrL("/page"), StrL("{\"dir\":1}"));
                break;
        }
        HwndRepaintNow(hwnd);
        return;
    }

    // Windows TTS
    switch (btnIdx) {
        case kBtnRestart:
            HwndSendCommand(GetParent(GetParent(hwnd)), CmdReadAloudFromTopPage);
            break;
        case kBtnPause:
            if (!showResume) {
                ReadAloudPlaybackPauseOrResume();
            }
            break;
        case kBtnPlay:
            if (showResume) {
                ReadAloudPlaybackPauseOrResume();
            }
            break;
        case kBtnStop:
            ReadAloudPlaybackStop();
            break;
        case kBtnSpeed:
            ReadAloudPlaybackCycleSpeed(+1);
            break;
    }
    UpdateLayout();
    HwndRepaintNow(hwnd);
}

void ReadAloudPlaybackBar::UpdateLayout() {
    if (!hwnd || !layout) {
        return;
    }

    SyncLabels();

    HWND parent = GetParent(hwnd);
    Rect canvas = HwndClientRect(parent);
    int margin = DpiScale(kBarMargin);
    int barDx = std::max(canvas.dx - (2 * margin), 0);
    Size natural = layout->Layout(ExpandInf());
    int barDy = natural.dy;

    int x = margin;
    int y = canvas.dy - barDy - margin;
    y = std::max(y, margin);

    uint flags = SWP_NOZORDER | SWP_NOACTIVATE;
    SetWindowPos(hwnd, nullptr, x, y, barDx, barDy, flags);
    DoLayout({barDx, barDy});
}

void ReadAloudPlaybackBar::OnPaint(WindowBase::PaintEvent* ev) {
    Rect rc = HwndClientRect(hwnd);

    Color colBg = ThemeNotificationsBackgroundColor();
    Color colBorder = kColGray;

    SyncColors();
    Gfx* gfx = GfxCreateWithDoubleBuffer(this, ev->hdc);
    gfx->FillRect(rc, colBg);
    if (vroot) {
        vroot->Paint(gfx, rc);
    }
    gfx->DrawRect(rc, colBorder);
    delete gfx;
}

static ReadAloudPlaybackBar* ReadAloudPlaybackBarEnsure(MainWindow* win) {
    if (!win || !win->hwndCanvas) {
        return nullptr;
    }
    if (!win->readAloudPlaybackBar) {
        win->readAloudPlaybackBar = new ReadAloudPlaybackBar();
        win->readAloudPlaybackBar->Create(win->hwndCanvas);
    }
    return win->readAloudPlaybackBar;
}

void ReadAloudPlaybackBarDestroy(MainWindow* win) {
    if (!win || !win->readAloudPlaybackBar) {
        return;
    }
    delete win->readAloudPlaybackBar;
    win->readAloudPlaybackBar = nullptr;
}

void ReadAloudPlaybackBarHide(MainWindow* win) {
    if (!win || !win->readAloudPlaybackBar || !win->readAloudPlaybackBar->hwnd) {
        return;
    }
    win->readAloudPlaybackBar->sessionTab = nullptr;
    ShowWindow(win->readAloudPlaybackBar->hwnd, SW_HIDE);
}

// the tab is going away; the bar has no reason to exist without it
void ReadAloudPlaybackBarForgetTab(MainWindow* win, WindowTab* tab) {
    ReadAloudPlaybackBar* bar = win ? win->readAloudPlaybackBar : nullptr;
    if (!bar || bar->sessionTab != tab) {
        return;
    }
    bar->sessionTab = nullptr;
    if (bar->hwnd) {
        ShowWindow(bar->hwnd, SW_HIDE);
    }
}

void ReadAloudPlaybackBarRelayout(HWND hwndCanvas) {
    MainWindow* win = FindMainWindowByHwnd(hwndCanvas);
    if (!win || !win->readAloudPlaybackBar || !win->readAloudPlaybackBar->hwnd) {
        return;
    }
    if (!HwndIsVisible(win->readAloudPlaybackBar->hwnd)) {
        return;
    }
    win->readAloudPlaybackBar->UpdateLayout();
    HwndRepaintNow(win->readAloudPlaybackBar->hwnd);
}

void ReadAloudPlaybackBarUpdateSession(WindowTab* tab) {
    if (!tab) {
        // no read-aloud source any more (callers pass GetReadAloudSourceTab()),
        // so no bar should be up. Hiding also drops the tab each bar points at,
        // which is about to be deleted on the tab-close path
        for (MainWindow* win : gWindows) {
            ReadAloudPlaybackBarHide(win);
        }
        return;
    }
    // readAloudText is Windows TTS's session state and stays empty for the
    // Chatterbox engine, which keeps the text in its own process - so ask it
    // whether it's reading rather than infer from TTS state.
    bool audiobook = AudiobookIsRunning();
    if (!tab->win || (!audiobook && len(tab->readAloudText) == 0)) {
        ReadAloudPlaybackBarHide(tab->win);
        return;
    }

    ReadAloudPlaybackBar* bar = ReadAloudPlaybackBarEnsure(tab->win);
    if (!bar) {
        return;
    }
    bar->SetSession(tab);

    // hide bars on other windows
    for (MainWindow* win : gWindows) {
        if (win != tab->win && win->readAloudPlaybackBar && HwndIsVisible(win->readAloudPlaybackBar->hwnd)) {
            ReadAloudPlaybackBarHide(win);
        }
    }
}
