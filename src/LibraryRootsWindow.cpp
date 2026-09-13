/* Copyright 2026 the SumatraPDF project authors (see AUTHORS file).
   License: GPLv3 */

#include "base/Base.h"
#include "base/Win.h"
#include "base/File.h"
#include "base/ScopedWin.h"
#include "gui/Dpi.h"

#include "gui/UIModels.h"
#include "gui/Layout.h"
#include "gui/win/WinGui.h"
#include "gui/PlatformFont.h"
#include "gui/Gfx.h"
#include "gui/VirtCtrl.h"

#include "Settings.h"
#include "GlobalPrefs.h"
#include "AppSettings.h"
#include "MainWindow.h"
#include "SumatraPDF.h"
#include "Theme.h"
#include "SumatraConfig.h"
#include "Translations.h"
#include "LibraryScan.h"
#include "LibraryRootsWindow.h"

struct RootRow {
    Str path;
    Str label;
    bool enabled = false;
    uintptr_t userData = 0;
};

struct LibraryRootsModel : TreeModel {
    ~LibraryRootsModel() override;

    TreeItem Root() override;
    Str Text(TreeItem ti) override;
    TreeItem Parent(TreeItem ti) override;
    int ChildCount(TreeItem ti) override;
    TreeItem ChildAt(TreeItem ti, int idx) override;
    bool IsExpanded(TreeItem ti) override;
    bool IsChecked(TreeItem ti) override;
    void SetUserData(TreeItem ti, uintptr_t userData) override;
    uintptr_t GetUserData(TreeItem ti) override;

    RootRow* RowAt(TreeItem ti);

    Vec<RootRow*> rows;
};

LibraryRootsModel::~LibraryRootsModel() {
    for (RootRow* row : rows) {
        str::Free(row->path);
        str::Free(row->label);
        delete row;
    }
}

RootRow* LibraryRootsModel::RowAt(TreeItem ti) {
    int idx = (int)ti - 1;
    if (idx < 0 || idx >= len(rows)) {
        return nullptr;
    }
    return rows[idx];
}

TreeItem LibraryRootsModel::Root() {
    return TreeModel::kNullItem;
}

Str LibraryRootsModel::Text(TreeItem ti) {
    RootRow* row = RowAt(ti);
    return row ? row->label : Str{};
}

TreeItem LibraryRootsModel::Parent(TreeItem) {
    return TreeModel::kNullItem;
}

int LibraryRootsModel::ChildCount(TreeItem ti) {
    if (ti != TreeModel::kNullItem) {
        return 0;
    }
    return len(rows);
}

TreeItem LibraryRootsModel::ChildAt(TreeItem ti, int idx) {
    if (ti != TreeModel::kNullItem) {
        return TreeModel::kNullItem;
    }
    return (TreeItem)(idx + 1);
}

bool LibraryRootsModel::IsExpanded(TreeItem) {
    return false;
}

bool LibraryRootsModel::IsChecked(TreeItem ti) {
    RootRow* row = RowAt(ti);
    return row ? row->enabled : false;
}

void LibraryRootsModel::SetUserData(TreeItem ti, uintptr_t userData) {
    RootRow* row = RowAt(ti);
    if (row) {
        row->userData = userData;
    }
}

uintptr_t LibraryRootsModel::GetUserData(TreeItem ti) {
    RootRow* row = RowAt(ti);
    return row ? row->userData : 0;
}

constexpr UINT kMsgReloadRoots = WM_APP + 17;
constexpr UINT kMsgSyncChecks = WM_APP + 18;

struct LibraryRootsWnd : WindowBase {
    ~LibraryRootsWnd() override;

    MainWindow* win = nullptr;
    TreeView* tree = nullptr;
    LibraryRootsModel* model = nullptr;
    VirtText* hint = nullptr;
    VirtButton* btnAdd = nullptr;
    VirtButton* btnClose = nullptr;
    Str lastTooltip;
    ControlBase::WndProcHandler treeWndProc;
    TreeItem hoverItem = TreeModel::kNullItem;
    bool hoverRemove = false;
    bool trackingMouse = false;

    bool Create(MainWindow* win);
    void Reload();
    void ApplyChecks();
    void Save();
    void EffectivePaths(StrVec& out);
    bool ShowsEffectivePaths();
    Rect RemoveRect(TreeItem ti);
    TreeItem ItemAtPoint(int x, int y);
    void InvalidateRow(TreeItem ti);
    void SetHover(TreeItem ti, bool onRemove);
    void OnRemove(TreeItem ti);
    void OnAddFolder(VirtMouseEvent* ev = nullptr);
    void OnCloseButton(VirtMouseEvent* ev = nullptr);
    void OnTreeClick(TreeView::ClickEvent* ev);
    void OnGetTooltip(TreeView::GetTooltipEvent* ev);
    void OnTreeWndProc(ControlBase::WndProcEvent* ev);
    void OnTreeCustomDraw(TreeView::CustomDrawEvent* ev);
    void OnSelfWndProc(WindowBase::WndProcEvent* ev);
};

static LibraryRootsWnd* gLibraryRootsWnd = nullptr;

LibraryRootsWnd::~LibraryRootsWnd() {
    str::Free(lastTooltip);
    delete model;
}

static TempStr FolderNameTemp(Str path) {
    int n = path.len;
    while (n > 0 && path::IsSep(path.s[n - 1])) {
        n--;
    }
    int start = n;
    while (start > 0 && !path::IsSep(path.s[start - 1])) {
        start--;
    }
    if (start >= n) {
        return str::DupTemp(path);
    }
    Str name{path.s + start, n - start};
    return str::DupTemp(name);
}

static void FillLabels(Vec<RootRow*>& rows) {
    for (RootRow* row : rows) {
        str::ReplaceWithCopy(&row->label, FolderNameTemp(row->path));
    }
    for (RootRow* row : rows) {
        int same = 0;
        for (RootRow* other : rows) {
            if (str::EqI(other->label, row->label)) {
                same++;
            }
        }
        if (same > 1) {
            str::ReplaceWithCopy(&row->label, row->path);
        }
    }
}

void LibraryRootsWnd::EffectivePaths(StrVec& out) {
    Vec<LibraryRoot*>* prefRows = gGlobalPrefs ? gGlobalPrefs->audiobook.libraryRoots : nullptr;
    if (!prefRows) {
        return;
    }
    for (LibraryRoot* pr : *prefRows) {
        if (!pr || pr->path.len == 0) {
            continue;
        }
        if (LibraryRootIsCoveredByAncestor(pr->path)) {
            continue;
        }
        Str real = LibraryRootCanonicalPath(pr->path);
        if (out.FindI(real) < 0) {
            out.Append(real);
        }
        str::Free(real);
    }
}

bool LibraryRootsWnd::ShowsEffectivePaths() {
    StrVec want;
    EffectivePaths(want);
    if (!model || want.size != len(model->rows)) {
        return false;
    }
    for (int i = 0; i < want.size; i++) {
        if (!str::EqI(want.At(i), model->rows[i]->path)) {
            return false;
        }
    }
    return true;
}

void LibraryRootsWnd::Reload() {
    auto* m = new LibraryRootsModel();
    StrVec paths;
    EffectivePaths(paths);
    Vec<LibraryRoot*>* prefRows = gGlobalPrefs->audiobook.libraryRoots;
    for (Str path : paths) {
        bool enabled = false;
        if (prefRows) {
            for (LibraryRoot* pr : *prefRows) {
                if (!pr) {
                    continue;
                }
                Str real = LibraryRootCanonicalPath(pr->path);
                bool same = str::EqI(real, path);
                str::Free(real);
                if (same && pr->enabled) {
                    enabled = true;
                    break;
                }
            }
        }
        auto* row = new RootRow();
        row->path = str::Dup(path);
        row->enabled = enabled;
        m->rows.Append(row);
    }
    FillLabels(m->rows);
    delete model;
    model = m;
    hoverItem = TreeModel::kNullItem;
    hoverRemove = false;
    tree->SetTreeModel(model);
    for (int i = 0; i < len(model->rows); i++) {
        tree->SetState((TreeItem)(i + 1), model->rows[i]->enabled);
    }
}

Rect LibraryRootsWnd::RemoveRect(TreeItem ti) {
    Rect out;
    Rect row;
    if (!tree || !IsWindow(tree->hwnd) || ti == TreeModel::kNullItem) {
        return out;
    }
    if (!tree->GetItemRect(ti, false, row) || row.dy <= 0) {
        return out;
    }
    int size = row.dy;
    int width = HwndClientRect(tree->hwnd).dx;
    out = {width - size - DpiScale(4), row.y, size, row.dy};
    return out;
}

TreeItem LibraryRootsWnd::ItemAtPoint(int x, int y) {
    if (!tree || !model || !IsWindow(tree->hwnd)) {
        return TreeModel::kNullItem;
    }
    int width = HwndClientRect(tree->hwnd).dx;
    for (int i = 0; i < len(model->rows); i++) {
        auto ti = (TreeItem)(i + 1);
        Rect row;
        if (!tree->GetItemRect(ti, false, row)) {
            continue;
        }
        row.x = 0;
        row.dx = width;
        if (row.Contains(x, y)) {
            return ti;
        }
    }
    return TreeModel::kNullItem;
}

void LibraryRootsWnd::InvalidateRow(TreeItem ti) {
    if (ti == TreeModel::kNullItem || !tree || !IsWindow(tree->hwnd)) {
        return;
    }
    Rect row;
    if (!tree->GetItemRect(ti, false, row)) {
        return;
    }
    row.x = 0;
    row.dx = HwndClientRect(tree->hwnd).dx;
    RECT rc = ToRECT(row);
    InvalidateRect(tree->hwnd, &rc, TRUE);
}

void LibraryRootsWnd::SetHover(TreeItem ti, bool onRemove) {
    if (hoverItem == ti && hoverRemove == onRemove) {
        return;
    }
    TreeItem was = hoverItem;
    hoverItem = ti;
    hoverRemove = onRemove;
    InvalidateRow(was);
    InvalidateRow(ti);
}

void LibraryRootsWnd::OnRemove(TreeItem ti) {
    RootRow* row = model ? model->RowAt(ti) : nullptr;
    if (!row) {
        return;
    }
    ApplyChecks();
    if (!LibraryRootsRemove(row->path)) {
        return;
    }
    SaveSettings();
    SetHover(TreeModel::kNullItem, false);
    PostMessageW(hwnd, kMsgReloadRoots, 0, 0);
}

void LibraryRootsWnd::OnTreeWndProc(ControlBase::WndProcEvent* ev) {
    UINT msg = ev->msg;
    if (msg == WM_MOUSEMOVE) {
        int x = GET_X_LPARAM(ev->lparam);
        int y = GET_Y_LPARAM(ev->lparam);
        TreeItem ti = ItemAtPoint(x, y);
        Rect box = RemoveRect(ti);
        SetHover(ti, box.dx > 0 && box.Contains(x, y));
        if (!trackingMouse) {
            TRACKMOUSEEVENT tme{sizeof(TRACKMOUSEEVENT), TME_LEAVE, tree->hwnd, 0};
            trackingMouse = TrackMouseEvent(&tme) != 0;
        }
    } else if (msg == WM_MOUSELEAVE) {
        trackingMouse = false;
        SetHover(TreeModel::kNullItem, false);
    } else if (msg == WM_LBUTTONDOWN) {
        int x = GET_X_LPARAM(ev->lparam);
        int y = GET_Y_LPARAM(ev->lparam);
        TreeItem ti = ItemAtPoint(x, y);
        Rect box = RemoveRect(ti);
        if (box.dx > 0 && box.Contains(x, y)) {
            ev->result = 0;
            ev->didHandle = true;
            OnRemove(ti);
            return;
        }
    }
    if (treeWndProc.IsValid()) {
        treeWndProc.Call(ev);
    }
}

void LibraryRootsWnd::OnTreeCustomDraw(TreeView::CustomDrawEvent* ev) {
    ev->result = CDRF_DODEFAULT;
    NMCUSTOMDRAW* cd = &ev->nm->nmcd;
    if (cd->dwDrawStage == CDDS_PREPAINT) {
        ev->result = CDRF_NOTIFYITEMDRAW;
        return;
    }
    if (cd->dwDrawStage == CDDS_ITEMPREPAINT) {
        if (ev->treeItem == hoverItem) {
            ev->result = CDRF_NOTIFYPOSTPAINT;
        }
        return;
    }
    if (cd->dwDrawStage != CDDS_ITEMPOSTPAINT || ev->treeItem != hoverItem) {
        return;
    }
    Rect box = RemoveRect(ev->treeItem);
    if (box.dx <= 0) {
        return;
    }
    Color txt = tree->textColor;
    if (txt == kColorUnset || IsSpecialColor(txt)) {
        txt = ThemeWindowTextColor();
    }
    GfxHdc gfx(cd->hdc);
    if (hoverRemove) {
        gfx.FillRect(box, ThemeHotBackgroundColor());
    }
    Rect glyph = box;
    glyph.Inflate(-DpiScale(5), -DpiScale(5));
    if (glyph.dx <= 1 || glyph.dy <= 1) {
        return;
    }
    int right = glyph.x + glyph.dx;
    int bottom = glyph.y + glyph.dy;
    gfx.DrawLineAA({glyph.x, glyph.y}, {right, bottom}, txt, 1.4f);
    gfx.DrawLineAA({right, glyph.y}, {glyph.x, bottom}, txt, 1.4f);
}

void LibraryRootsWnd::OnSelfWndProc(WindowBase::WndProcEvent* ev) {
    if (ev->msg == kMsgSyncChecks) {
        Save();
        if (!ShowsEffectivePaths()) {
            Reload();
        }
        ev->result = 0;
        ev->didHandle = true;
        return;
    }
    if (ev->msg != kMsgReloadRoots) {
        return;
    }
    Reload();
    ev->result = 0;
    ev->didHandle = true;
}

void LibraryRootsWnd::ApplyChecks() {
    if (!model || !tree || !IsWindow(tree->hwnd)) {
        return;
    }
    Vec<LibraryRoot*>* prefRows = gGlobalPrefs->audiobook.libraryRoots;
    if (!prefRows) {
        return;
    }
    for (int i = 0; i < len(model->rows); i++) {
        RootRow* row = model->rows[i];
        row->enabled = tree->GetState((TreeItem)(i + 1));
        for (LibraryRoot* pr : *prefRows) {
            if (!pr) {
                continue;
            }
            Str real = LibraryRootCanonicalPath(pr->path);
            if (str::EqI(real, row->path)) {
                pr->enabled = row->enabled;
            }
            str::Free(real);
        }
    }
}

void LibraryRootsWnd::Save() {
    ApplyChecks();
    SaveSettings();
}

static TempStr PickFolderTemp(HWND parent) {
    ScopedComPtr<IFileOpenDialog> dlg;
    HRESULT hr = CoCreateInstance(CLSID_FileOpenDialog, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&dlg));
    if (FAILED(hr) || !dlg) {
        logf("PickFolderTemp: CoCreateInstance(CLSID_FileOpenDialog) failed: 0x%x\n", (uint)hr);
        return {};
    }
    DWORD opts = 0;
    dlg->GetOptions(&opts);
    dlg->SetOptions(opts | FOS_PICKFOLDERS | FOS_FORCEFILESYSTEM | FOS_PATHMUSTEXIST);
    hr = dlg->Show(parent);
    if (FAILED(hr)) {
        return {};
    }
    ScopedComPtr<IShellItem> item;
    hr = dlg->GetResult(&item);
    if (FAILED(hr) || !item) {
        return {};
    }
    PWSTR pathW = nullptr;
    hr = item->GetDisplayName(SIGDN_FILESYSPATH, &pathW);
    if (FAILED(hr) || !pathW) {
        return {};
    }
    TempStr res = ToUtf8Temp(WStr(pathW));
    CoTaskMemFree(pathW);
    return res;
}

void LibraryRootsWnd::OnAddFolder(VirtMouseEvent*) {
    ApplyChecks();
    TempStr picked = PickFolderTemp(hwnd);
    if (len(picked) == 0) {
        return;
    }
    int res = LibraryRootsAdd(Str(picked));
    if (res == kLibraryRootAddFailed) {
        return;
    }
    SaveSettings();
    Reload();
    Str pickedReal = LibraryRootCanonicalPath(Str(picked));
    for (int i = 0; i < len(model->rows); i++) {
        if (str::EqI(model->rows[i]->path, pickedReal)) {
            tree->SelectItem((TreeItem)(i + 1));
            break;
        }
    }
    str::Free(pickedReal);
    HwndSetFocus(tree->hwnd);
}

void LibraryRootsWnd::OnCloseButton(VirtMouseEvent*) {
    Save();
    ScheduleDelete();
}

void LibraryRootsWnd::OnTreeClick(TreeView::ClickEvent*) {
    PostMessageW(hwnd, kMsgSyncChecks, 0, 0);
}

void LibraryRootsWnd::OnGetTooltip(TreeView::GetTooltipEvent* ev) {
    RootRow* row = model ? model->RowAt(ev->treeItem) : nullptr;
    if (!row || !ev->info || ev->info->cchTextMax <= 0) {
        return;
    }
    str::BufSet(ev->info->pszText, ev->info->cchTextMax, row->path);
    str::ReplaceWithCopy(&lastTooltip, row->path);
}

TempStr TestLibraryIndexingStatusTemp() {
    LibraryRootsWnd* w = gLibraryRootsWnd;
    str::Builder b;
    if (!w || !w->tree || !w->model || !IsWindow(w->tree->hwnd)) {
        b.Append(StrL("OK open=0 rows=0 tooltip=\n"));
        return ToStrTemp(b);
    }
    int n = len(w->model->rows);
    int hover = w->hoverItem == TreeModel::kNullItem ? -1 : (int)w->hoverItem - 1;
    b.Append(fmt("OK open=1 rows=%d tooltip=%s hover=%d onRemove=%d\n", n, w->lastTooltip, hover,
                 w->hoverRemove ? 1 : 0));
    for (int i = 0; i < n; i++) {
        RootRow* row = w->model->rows[i];
        auto ti = (TreeItem)(i + 1);
        bool checked = w->tree->GetState(ti);
        Rect box = w->RemoveRect(ti);
        b.Append(fmt("row %d checked=%d remove=%d,%d,%d,%d shown=%d label=%s path=%s\n", i, checked ? 1 : 0, box.x,
                     box.y, box.dx, box.dy, ti == w->hoverItem ? 1 : 0, row->label, row->path));
    }
    return ToStrTemp(b);
}

static void ClearLibraryRootsWnd() {
    gLibraryRootsWnd = nullptr;
}

static void OnWndClose(WindowBase::CloseEvent*) {
    if (gLibraryRootsWnd) {
        gLibraryRootsWnd->Save();
        gLibraryRootsWnd->ScheduleDelete();
    }
}

static void OnWndDestroy(WindowBase::DestroyEvent*) {
    if (gLibraryRootsWnd) {
        gLibraryRootsWnd->ScheduleDelete();
    }
}

static void PositionDialog(HWND hwnd, HWND hwndRelative) {
    Rect rRelative = HwndWindowRect(hwndRelative);
    Rect r = HwndWindowRect(hwnd);
    int x = rRelative.x + ((rRelative.dx - r.dx) / 2);
    int y = rRelative.y + ((rRelative.dy - r.dy) / 3);
    Rect r2 = ShiftRectToWorkArea({x, y, r.dx, r.dy}, hwndRelative, true);
    SetWindowPos(hwnd, nullptr, r2.x, r2.y, 0, 0, SWP_NOZORDER | SWP_NOSIZE);
}

bool LibraryRootsWnd::Create(MainWindow* mainWin) {
    win = mainWin;

    {
        CreateCustomArgs args;
        args.title = _TRA("Library Indexing");
        args.visible = false;
        args.style = WS_POPUPWINDOW | WS_CAPTION;
        args.font = GetFont();
        args.icon = LoadIconW(GetModuleHandleW(nullptr), MAKEINTRESOURCEW(GetAppIconID()));
        CreateCustom(args);
    }
    if (!hwnd) {
        return false;
    }

    bool isRtl = IsUIRtl();

    auto* vbox = new VBox();
    vbox->alignMain = MainAxisAlign::MainStart;
    vbox->alignCross = CrossAxisAlign::Stretch;

    hint = NewVirtText({
        .s = _TRA("Folders the Library looks for books in"),
        .font = font,
        .isRtl = isRtl,
        .padding = DpiScaledInsets(0, 0, 6, 0),
    });
    vbox->AddChild(hint);

    {
        TreeView::CreateArgs args;
        args.parent = hwnd;
        args.font = GetFont();
        args.isRtl = isRtl;
        args.fullRowSelect = true;
        auto* c = new TreeView();
        c->Create(args);
        HwndSetWindowStyle(c->hwnd, TVS_CHECKBOXES, true);
        HwndSetWindowStyle(c->hwnd, TVS_HASLINES | TVS_LINESATROOT | TVS_HASBUTTONS, false);
        c->idealSize = {DpiScale(440), DpiScale(300)};
        c->onGetTooltip = MkMethod1<LibraryRootsWnd, TreeView::GetTooltipEvent*, &LibraryRootsWnd::OnGetTooltip>(this);
        c->onClick = MkMethod1<LibraryRootsWnd, TreeView::ClickEvent*, &LibraryRootsWnd::OnTreeClick>(this);
        c->onCustomDraw =
            MkMethod1<LibraryRootsWnd, TreeView::CustomDrawEvent*, &LibraryRootsWnd::OnTreeCustomDraw>(this);
        treeWndProc = c->onWndProc;
        c->onWndProc = MkMethod1<LibraryRootsWnd, ControlBase::WndProcEvent*, &LibraryRootsWnd::OnTreeWndProc>(this);
        tree = c;
        vbox->AddChild(c);
    }

    {
        auto* hbox = new HBox();
        hbox->alignMain = MainAxisAlign::MainEnd;
        hbox->alignCross = CrossAxisAlign::CrossCenter;
        hbox->gap = font->averageCharWidth;
        auto pad = Insets{8, 0, 4, 0};

        btnAdd = NewThemedButton(hwnd, _TRA("Add folder..."), font, false);
        btnAdd->onClick = MkMethod1<LibraryRootsWnd, VirtMouseEvent*, &LibraryRootsWnd::OnAddFolder>(this);
        hbox->AddChild(new Padding(btnAdd, pad));

        btnClose = NewThemedButton(hwnd, _TRA("Close"), font, true);
        btnClose->onClick = MkMethod1<LibraryRootsWnd, VirtMouseEvent*, &LibraryRootsWnd::OnCloseButton>(this);
        hbox->AddChild(new Padding(btnClose, pad));
        vbox->AddChild(hbox);
    }

    auto* padding = new Padding(vbox, DpiScaledInsets(8, 10));
    layout = padding;

    Reload();

    int dx = DpiScale(480);
    LayoutAndSizeToContent(layout, dx, 0, hwnd);
    DoLayout(HwndClientRect(hwnd).Size());
    PositionDialog(hwnd, win->hwndFrame);
    UpdateTheme();

    SetIsVisible(true);
    HwndSetFocus(tree->hwnd);
    return true;
}

void ShowLibraryIndexingWindow(MainWindow* win) {
    if (!HasPermission(Perm::SavePreferences)) {
        return;
    }
    if (gLibraryRootsWnd) {
        HwndToForeground(gLibraryRootsWnd->hwnd);
        HwndSetFocus(gLibraryRootsWnd->hwnd);
        return;
    }
    if (LibraryRootsSeedIfEmpty()) {
        SaveSettings();
    }
    auto* wnd = new LibraryRootsWnd();
    wnd->closeOnEsc = true;
    wnd->onBeforeDelete = MkFunc0Void(ClearLibraryRootsWnd);
    wnd->onClose = MkFunc1Void<WindowBase::CloseEvent*>(OnWndClose);
    wnd->onDestroy = MkFunc1Void<WindowBase::DestroyEvent*>(OnWndDestroy);
    wnd->SetFont(GetAppFont());
    wnd->onWndProc = MkMethod1<LibraryRootsWnd, WindowBase::WndProcEvent*, &LibraryRootsWnd::OnSelfWndProc>(wnd);
    bool ok = wnd->Create(win);
    if (!ok) {
        delete wnd;
        return;
    }
    gLibraryRootsWnd = wnd;
}
