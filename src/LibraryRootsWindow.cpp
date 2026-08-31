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

struct LibraryRootsWnd : WindowBase {
    ~LibraryRootsWnd() override;

    MainWindow* win = nullptr;
    TreeView* tree = nullptr;
    LibraryRootsModel* model = nullptr;
    VirtText* hint = nullptr;
    VirtButton* btnAdd = nullptr;
    VirtButton* btnClose = nullptr;
    Str lastTooltip;

    bool Create(MainWindow* win);
    void Reload();
    void ApplyChecks();
    void Save();
    void OnAddFolder(VirtMouseEvent* ev = nullptr);
    void OnCloseButton(VirtMouseEvent* ev = nullptr);
    void OnTreeClick(TreeView::ClickEvent* ev);
    void OnGetTooltip(TreeView::GetTooltipEvent* ev);
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

void LibraryRootsWnd::Reload() {
    auto* m = new LibraryRootsModel();
    Vec<LibraryRoot*>* prefRows = gGlobalPrefs->audiobook.libraryRoots;
    if (prefRows) {
        for (LibraryRoot* pr : *prefRows) {
            if (!pr || pr->path.len == 0) {
                continue;
            }
            auto* row = new RootRow();
            row->path = str::Dup(pr->path);
            row->enabled = pr->enabled;
            m->rows.Append(row);
        }
    }
    FillLabels(m->rows);
    delete model;
    model = m;
    tree->SetTreeModel(model);
    for (int i = 0; i < len(model->rows); i++) {
        tree->SetState((TreeItem)(i + 1), model->rows[i]->enabled);
    }
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
            if (pr && str::EqI(pr->path, row->path)) {
                pr->enabled = row->enabled;
                break;
            }
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
    for (int i = 0; i < len(model->rows); i++) {
        if (str::EqI(model->rows[i]->path, Str(picked))) {
            tree->SelectItem((TreeItem)(i + 1));
            break;
        }
    }
    HwndSetFocus(tree->hwnd);
}

void LibraryRootsWnd::OnCloseButton(VirtMouseEvent*) {
    Save();
    ScheduleDelete();
}

void LibraryRootsWnd::OnTreeClick(TreeView::ClickEvent*) {
    Save();
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
    b.Append(fmt("OK open=1 rows=%d tooltip=%s\n", n, w->lastTooltip));
    for (int i = 0; i < n; i++) {
        RootRow* row = w->model->rows[i];
        bool checked = w->tree->GetState((TreeItem)(i + 1));
        b.Append(fmt("row %d checked=%d label=%s path=%s\n", i, checked ? 1 : 0, row->label, row->path));
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
    bool ok = wnd->Create(win);
    if (!ok) {
        delete wnd;
        return;
    }
    gLibraryRootsWnd = wnd;
}
