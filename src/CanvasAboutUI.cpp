/* Copyright 2022 the SumatraPDF project authors (see AUTHORS file).
   License: GPLv3 */

#include "base/Base.h"
#include "base/File.h"
#include "base/Timer.h"
#include "base/Win.h"

#include "gui/UIModels.h"
#include "gui/Layout.h"
#include "gui/win/WinGui.h"
#include "gui/Gfx.h"

#include "Settings.h"
#include "GlobalPrefs.h"
#include "SumatraPDF.h"
#include "MainWindow.h"
#include "Commands.h"
#include "Canvas.h"
#include "Menu.h"
#include "HomePage.h"
#include "LibraryPage.h"
#include "Theme.h"
#include "FileHistory.h"
#include "AppSettings.h"

static void OnPaintAbout(MainWindow* win) {
    auto t = TimeGet();
    PAINTSTRUCT ps;
    HDC hdc = BeginPaint(win->hwndCanvas, &ps);
    if (!win->buffer) {
        EndPaint(win->hwndCanvas, &ps);
        return;
    }
    HDC bufDC = win->buffer->GetDC();
    GlobalPrefs* prefs = gGlobalPrefs;
    bool hasPerms = HasPermission(Perm::SavePreferences | Perm::DiskAccess);
    bool drawHome = hasPerms && prefs->rememberOpenedFiles && prefs->showStartPage;
    Gfx* gfx = GfxCreate(bufDC);
    if (LibraryHomeEnabled()) {
        HomePageDestroySearch(win);
        DrawLibraryPage(win, bufDC);
    } else if (drawHome) {
        DrawHomePage(win, gfx);
    } else {
        HomePageDestroySearch(win);
        // DrawAboutPage swaps the canvas root's child from the home page's
        // chrome to the About page's controls
        DrawAboutPage(win, gfx);
    }
    delete gfx;
    win->buffer->Flush(hdc);
    DrawCanvasKeyboardFocusIfNeeded(win, hdc);

    EndPaint(win->hwndCanvas, &ps);
    win->ShowFrameRateDur(TimeSinceInMs(t));
}

static void OnMouseMoveAbout(HWND hwnd) {
    TRACKMOUSEEVENT tme{sizeof(TRACKMOUSEEVENT)};
    tme.dwFlags = TME_LEAVE;
    tme.hwndTrack = hwnd;
    TrackMouseEvent(&tme);
}

// The library start page draws itself and keeps its own hit targets in
// win->staticLinks, so it handles the canvas mouse messages instead of the
// home page's VirtCtrl tree.
static bool IsLink(Str url) {
    if (str::StartsWithI(url, "http:")) {
        return true;
    }
    if (str::StartsWithI(url, "https:")) {
        return true;
    }
    if (str::StartsWithI(url, "mailto:")) {
        return true;
    }
    return false;
}

static void OnLibraryLeftButtonDown(MainWindow* win, int x, int y) {
    if (LibraryOnLeftButtonDown(win, x, y)) {
        str::FreePtr(&win->urlOnLastButtonDown);
        return;
    }
    // remember a link under so that on mouse up we only activate
    // link if mouse up is on the same link as mouse down
    str::ReplaceWithCopy(&win->urlOnLastButtonDown, GetStaticLinkAtTemp(win->staticLinks, x, y, nullptr));
}

static void OnLibraryLeftButtonUp(MainWindow* win, int x, int y) {
    if (LibraryOnLeftButtonUp(win)) {
        str::FreePtr(&win->urlOnLastButtonDown);
        return;
    }
    TempStr url = GetStaticLinkAtTemp(win->staticLinks, x, y, nullptr);
    bool clickedURL = url && str::Eq(url, win->urlOnLastButtonDown);
    str::FreePtr(&win->urlOnLastButtonDown);
    if (!clickedURL) {
        return;
    }
    if (LibraryOnLinkClicked(win, url)) {
        return;
    }
    if (str::Eq(url, kLinkOpenFile)) {
        HwndSendCommand(win->hwndFrame, CmdOpenFile);
    } else if (str::StartsWith(url, "Cmd")) {
        int cmdId = GetCommandIdByName(url);
        if (cmdId > 0) {
            HwndSendCommand(win->hwndFrame, cmdId);
        }
    } else if (IsLink(url)) {
        // documentation links open in the embedded manual browser
        if (!MaybeLaunchDocumentation(url)) {
            SumatraLaunchBrowser(url);
        }
    } else {
        // assume it's a thumbnail of a document
        auto path = url;
        ReportIf(!path);
        LoadArgs args(path, win);
        // ctrl forces always opening
        args.activateExisting = !IsCtrlPressed();
        args.activateExistingInWindow = true;
        StartLoadDocument(&args);
    }
}

// returns true when the library page consumed the message
static bool LibraryOnCanvasMessage(MainWindow* win, HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    if (!LibraryHomeEnabled()) {
        return false;
    }
    int x = GET_X_LPARAM(lp);
    int y = GET_Y_LPARAM(lp);
    switch (msg) {
        case WM_MOUSEMOVE:
            LibraryOnMouseMove(win, x, y);
            OnMouseMoveAbout(hwnd);
            return true;

        case WM_CAPTURECHANGED:
            LibraryOnCaptureLost(win);
            return true;

        case WM_LBUTTONDOWN:
        case WM_LBUTTONDBLCLK:
            OnLibraryLeftButtonDown(win, x, y);
            return true;

        case WM_LBUTTONUP:
            OnLibraryLeftButtonUp(win, x, y);
            return true;

        case WM_VSCROLL:
            LibraryOnVScroll(win, wp);
            return true;

        case WM_MOUSEWHEEL:
            LibraryOnMouseWheel(win, GET_WHEEL_DELTA_WPARAM(wp), (short)LOWORD(lp), (short)HIWORD(lp));
            return true;
    }
    return false;
}

static void OnMouseRightButtonDownAbout(MainWindow* win, int x, int y, WPARAM /*key*/) {
    // lf("Right button clicked on %d %d", x, y);
    HwndSetFocus(win->hwndFrame);
    win->dragStart = Point(x, y);
}

static void OnMouseRightButtonUpAbout(MainWindow* win, int x, int y, WPARAM /*key*/) {
    int isDrag = IsDragDistance(x, win->dragStart.x, y, win->dragStart.y);
    if (isDrag) {
        return;
    }
    if (LibraryOnRightClick(win, x, y)) {
        return;
    }
    OnAboutContextMenu(win, x, y);
}

static LRESULT OnSetCursorAbout(MainWindow* win, HWND hwnd) {
    LRESULT res = 0;
    if (HomePageOnCanvasMessage(win, WM_SETCURSOR, 0, 0, res)) {
        return TRUE;
    }
    Point pt = HwndGetCursorPos(hwnd);
    if (pt.IsEmpty()) {
        win->DeleteToolTip();
        return FALSE;
    }
    // not over any virtual control: plain arrow, and drop the hover tip. The
    // keyboard selection outline stays
    win->DeleteToolTip();
    SetCursorCached(IDC_ARROW);
    return TRUE;
}

LRESULT WndProcCanvasAbout(MainWindow* win, HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    int x = GET_X_LPARAM(lp);
    int y = GET_Y_LPARAM(lp);
    // the library start page draws itself and owns the canvas mouse input
    // while it is on
    if (LibraryOnCanvasMessage(win, hwnd, msg, wp, lp)) {
        return 0;
    }
    // the home page's virtual controls (header, view buttons, "Open a
    // document...", help button) handle their own hover / click / cursor
    switch (msg) {
        case WM_MOUSEMOVE:
        case WM_MOUSELEAVE:
        case WM_LBUTTONDOWN:
        case WM_LBUTTONUP:
        case WM_LBUTTONDBLCLK: {
            LRESULT res = 0;
            if (HomePageOnCanvasMessage(win, msg, wp, lp, res)) {
                return res;
            }
            break;
        }
    }
    // the search box's own messages (WM_CTLCOLOREDIT, EN_CHANGE, focus) are
    // reflected back to it by WndProcCanvas; it colors itself from SetColors()
    // and calls the handlers HomePage.cpp gave it
    switch (msg) {
        case WM_KEYDOWN:
            // keyboard navigation of the file list (issue #1136). These keys are
            // routed here by MaybeTranslateAccelerator instead of scrolling
            switch (wp) {
                case VK_LEFT:
                    HomePageMoveSelection(win, -1, 0);
                    return 0;
                case VK_RIGHT:
                    HomePageMoveSelection(win, 1, 0);
                    return 0;
                case VK_UP:
                    HomePageMoveSelection(win, 0, -1);
                    return 0;
                case VK_DOWN:
                    HomePageMoveSelection(win, 0, 1);
                    return 0;
                case VK_RETURN: {
                    Str path = HomePageSelectedFilePathTemp(win);
                    if (!path) {
                        return 0;
                    }
                    LoadArgs args(path, win);
                    // ctrl forces always opening, as for a click
                    args.activateExisting = !IsCtrlPressed();
                    args.activateExistingInWindow = true;
                    StartLoadDocument(&args);
                    return 0;
                }
                case VK_DELETE: {
                    // remove the keyboard-selected entry from file history (not from disk)
                    Str path = HomePageSelectedFilePathTemp(win);
                    if (path) {
                        ForgetFileFromFrequentlyRead(win, path);
                    }
                    return 0;
                }
            }
            break;

        case WM_MOUSEMOVE:
            OnMouseMoveAbout(hwnd);
            return 0;

        case WM_MOUSELEAVE:
            HomePageClearActiveEntry(win);
            return 0;

        case WM_RBUTTONDOWN:
            OnMouseRightButtonDownAbout(win, x, y, wp);
            return 0;

        case WM_RBUTTONUP:
            OnMouseRightButtonUpAbout(win, x, y, wp);
            return 0;

        case WM_SETCURSOR:
            if (OnSetCursorAbout(win, hwnd)) {
                return TRUE;
            }
            return DefWindowProc(hwnd, msg, wp, lp);

        case WM_CONTEXTMENU:
            OnAboutContextMenu(win, 0, 0);
            return 0;

        case WM_PAINT:
            if (gRedrawLog) {
                logf("redraw: WM_PAINT hwnd=0x%p (canvas-about)\n", hwnd);
            }
            OnPaintAbout(win);
            return 0;

        case WM_VSCROLL:
            HomePageClearActiveEntry(win);
            HomePageOnVScroll(win, wp);
            return 0;

        case WM_MOUSEWHEEL: {
            HomePageClearActiveEntry(win);
            int delta = GET_WHEEL_DELTA_WPARAM(wp);
            HomePageOnMouseWheel(win, delta);
            return 0;
        }

        default:
            return DefWindowProc(hwnd, msg, wp, lp);
    }
    return DefWindowProc(hwnd, msg, wp, lp);
}
