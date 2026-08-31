/* Copyright 2026 the SumatraPDF project authors (see AUTHORS file).
   License: GPLv3 */

#include "base/Base.h"
#include "base/Win.h"
#include "base/File.h"
#include "gui/Dpi.h"

#include "gui/UIModels.h"
#include "gui/Layout.h"
#include "gui/win/WinGui.h"
#include "gui/PlatformFont.h"
#include "gui/Gfx.h"
#include "gui/VirtCtrl.h"

#include "Settings.h"
#include "AppSettings.h"
#include "GlobalPrefs.h"
#include "MainWindow.h"
#include "SumatraPDF.h"
#include "Theme.h"
#include "SumatraConfig.h"
#include "Translations.h"
#include "LibraryImportWindow.h"

constexpr const char* kKindBookLabel = "Book";
constexpr const char* kKindDocumentLabel = "Document (Deskpan)";
constexpr const char* kNoPartition = "(none)";

struct LibraryImportWnd : WindowBase {
    ~LibraryImportWnd() override;

    MainWindow* win = nullptr;
    LibraryImportData* data = nullptr;
    LibraryImportCommitCb onCommit = nullptr;

    Edit* editTitle = nullptr;
    Edit* editAuthor = nullptr;
    Edit* editSeries = nullptr;
    Edit* editIndex = nullptr;
    Edit* editYear = nullptr;
    Edit* editGenre = nullptr;
    Edit* editSubgenre = nullptr;
    DropDown* dropKind = nullptr;
    DropDown* dropPartition = nullptr;

    VirtButton* btnCancel = nullptr;
    VirtButton* btnAdd = nullptr;

    bool Create(MainWindow* win);
    void ReadFields();
    void OnCancel(VirtMouseEvent* ev = nullptr);
    void OnAdd(VirtMouseEvent* ev = nullptr);
};

static LibraryImportWnd* gLibraryImportWnd = nullptr;

void FreeLibraryImportData(LibraryImportData* data) {
    if (!data) {
        return;
    }
    LibraryImportField* fields[] = {&data->title,  &data->author,   &data->series, &data->seriesIndex,
                                    &data->year,   &data->genre,    &data->subgenre};
    for (LibraryImportField* f : fields) {
        str::Free(f->value);
        str::Free(f->source);
        str::Free(f->original);
        *f = LibraryImportField{};
    }
    str::Free(data->path);
    str::Free(data->bookId);
    str::Free(data->bookJson);
    str::Free(data->proposedKind);
    str::Free(data->chosenKind);
    str::Free(data->already);
    str::Free(data->ext);
    str::Free(data->partitionKey);
    data->partitionKeys.Reset();
    data->partitionNames.Reset();
    delete data;
}

LibraryImportWnd::~LibraryImportWnd() {
    FreeLibraryImportData(data);
    data = nullptr;
}

static void ClearLibraryImportWnd() {
    gLibraryImportWnd = nullptr;
}

static void OnWndClose(WindowBase::CloseEvent*) {
    if (gLibraryImportWnd) {
        gLibraryImportWnd->OnCancel();
    }
}

static void OnWndDestroy(WindowBase::DestroyEvent*) {
    if (gLibraryImportWnd) {
        gLibraryImportWnd->ScheduleDelete();
    }
}

static TempStr SourceLabelTemp(const LibraryImportField& f) {
    if (len(f.source) == 0) {
        return str::DupTemp(StrL("not found"));
    }
    return fmt("from %s", Str(f.source));
}

static TempStr SizeLabelTemp(i64 size) {
    if (size >= 1024 * 1024) {
        return fmt("%.1f MB", (double)size / (1024.0 * 1024.0));
    }
    if (size >= 1024) {
        return fmt("%d KB", (int)(size / 1024));
    }
    return fmt("%d bytes", (int)size);
}

static Edit* MakeEdit(HWND parent, PlatformFont* font, bool isRtl, Str text, bool enabled) {
    Edit::CreateArgs args;
    args.parent = parent;
    args.font = font;
    args.withBorder = true;
    args.selectAllOnFocus = enabled;
    args.isRtl = isRtl;
    args.idealWidthChars = 44;
    args.text = text;
    auto* c = new Edit();
    c->Create(args);
    if (!enabled) {
        c->SetIsEnabled(false);
    }
    return c;
}

static DropDown* MakeDropDown(HWND parent, PlatformFont* font, bool isRtl) {
    DropDown::CreateArgs args;
    args.parent = parent;
    args.font = font;
    args.isRtl = isRtl;
    args.isEditable = false;
    auto* c = new DropDown();
    c->Create(args);
    return c;
}

static void AddFieldRow(Table* table, int row, PlatformFont* font, bool isRtl, Str label, ControlBase* ctrl,
                        const LibraryImportField& f) {
    auto* lab = NewVirtText({
        .s = label,
        .font = font,
        .isRtl = isRtl,
        .prefix = true,
    });
    auto& lc = table->SetCell(row, 0, lab);
    lc.alignV = CrossAxisAlign::CrossCenter;
    auto& ec = table->SetCell(row, 1, ctrl);
    ec.alignH = CrossAxisAlign::Stretch;
    ec.alignV = CrossAxisAlign::CrossCenter;
    auto* src = NewVirtText({
        .s = Str(SourceLabelTemp(f)),
        .font = font,
        .isRtl = isRtl,
    });
    auto& sc = table->SetCell(row, 2, src);
    sc.alignV = CrossAxisAlign::CrossCenter;
}

bool LibraryImportWnd::Create(MainWindow* mainWin) {
    win = mainWin;
    {
        CreateCustomArgs args;
        args.title = StrL("Manually add book to library");
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

    {
        auto* c = NewVirtText({
            .s = Str(fmt("File: %s", Str(data->path))),
            .font = font,
            .isRtl = isRtl,
            .pathEllipsis = true,
            .padding = DpiScaledInsets(0, 0, 2, 0),
        });
        vbox->AddChild(c);
    }
    {
        str::Builder facts;
        facts.Append(fmt("%d pages", data->pages));
        if (len(data->ext) > 0) {
            facts.Append(fmt(" · %s", Str(data->ext)));
        }
        facts.Append(fmt(" · %s", Str(SizeLabelTemp(data->size))));
        facts.Append(data->hasWriting ? StrL(" · writing found") : StrL(" · no writing found"));
        facts.Append(fmt(" · the Library reads it as %s", Str(data->proposedKind)));
        if (data->scrapedMeta || data->scrapedSeries) {
            facts.Append(StrL(" · looked up online"));
        }
        Str line = facts.TakeStr();
        auto* c = NewVirtText({
            .s = line,
            .font = font,
            .isRtl = isRtl,
            .padding = DpiScaledInsets(0, 0, 6, 0),
        });
        vbox->AddChild(c);
        str::Free(line);
    }
    if (data->excluded) {
        auto* c = NewVirtText({
            .s = StrL("This file was taken out of the Library and automatic scans skip it. Adding it here overrides "
                      "that."),
            .font = font,
            .isRtl = isRtl,
            .padding = DpiScaledInsets(0, 0, 6, 0),
        });
        vbox->AddChild(c);
    }
    if (len(data->already) > 0) {
        auto* c = NewVirtText({
            .s = Str(fmt("The Library already lists this file under %s.", Str(data->already))),
            .font = font,
            .isRtl = isRtl,
            .padding = DpiScaledInsets(0, 0, 6, 0),
        });
        vbox->AddChild(c);
    }

    editTitle = MakeEdit(hwnd, GetFont(), isRtl, data->title.value, true);
    editAuthor = MakeEdit(hwnd, GetFont(), isRtl, data->author.value, true);
    editSeries = MakeEdit(hwnd, GetFont(), isRtl, data->series.value, true);
    editIndex = MakeEdit(hwnd, GetFont(), isRtl, data->seriesIndex.value, true);
    editYear = MakeEdit(hwnd, GetFont(), isRtl, data->year.value, true);
    editGenre = MakeEdit(hwnd, GetFont(), isRtl, data->genre.value, false);
    editSubgenre = MakeEdit(hwnd, GetFont(), isRtl, data->subgenre.value, false);
    dropKind = MakeDropDown(hwnd, GetFont(), isRtl);
    dropPartition = MakeDropDown(hwnd, GetFont(), isRtl);

    {
        StrVec kinds;
        kinds.Append(StrL(kKindBookLabel));
        kinds.Append(StrL(kKindDocumentLabel));
        dropKind->SetItems(kinds);
        dropKind->SetCurrentSelection(str::Eq(data->chosenKind, StrL("document")) ? 1 : 0);
    }
    {
        StrVec names;
        names.Append(StrL(kNoPartition));
        for (int i = 0; i < len(data->partitionNames); i++) {
            names.Append(data->partitionNames.At(i));
        }
        dropPartition->SetItems(names);
        dropPartition->SetCurrentSelection(0);
    }

    auto* table = new Table();
    table->SetSize(9, 3);
    table->colGap = DpiScale(8);
    table->rowGap = DpiScale(5);
    AddFieldRow(table, 0, font, isRtl, StrL("&Title:"), editTitle, data->title);
    AddFieldRow(table, 1, font, isRtl, StrL("&Author:"), editAuthor, data->author);
    AddFieldRow(table, 2, font, isRtl, StrL("&Series:"), editSeries, data->series);
    AddFieldRow(table, 3, font, isRtl, StrL("Position in series:"), editIndex, data->seriesIndex);
    AddFieldRow(table, 4, font, isRtl, StrL("&Year:"), editYear, data->year);
    AddFieldRow(table, 5, font, isRtl, StrL("Genre:"), editGenre, data->genre);
    AddFieldRow(table, 6, font, isRtl, StrL("Subgenre:"), editSubgenre, data->subgenre);
    {
        LibraryImportField kindField;
        kindField.source = str::Dup(Str(fmt("the Library says %s", Str(data->proposedKind))));
        AddFieldRow(table, 7, font, isRtl, StrL("Add it as:"), dropKind, kindField);
        str::Free(kindField.source);
    }
    {
        LibraryImportField partField;
        partField.source = str::Dup(StrL("your own shelf"));
        AddFieldRow(table, 8, font, isRtl, StrL("Category:"), dropPartition, partField);
        str::Free(partField.source);
    }
    vbox->AddChild(table);

    {
        auto* c = NewVirtText({
            .s = StrL("Genre and subgenre are worked out by the Library and cannot be typed here."),
            .font = font,
            .isRtl = isRtl,
            .padding = DpiScaledInsets(6, 0, 0, 0),
        });
        vbox->AddChild(c);
    }

    {
        auto* hbox = new HBox();
        hbox->alignMain = MainAxisAlign::MainEnd;
        hbox->alignCross = CrossAxisAlign::CrossCenter;
        hbox->gap = font->averageCharWidth;
        auto pad = Insets{8, 0, 4, 0};
        btnCancel = NewThemedButton(hwnd, StrL("Cancel"), font, false);
        btnCancel->onClick = MkMethod1<LibraryImportWnd, VirtMouseEvent*, &LibraryImportWnd::OnCancel>(this);
        hbox->AddChild(new Padding(btnCancel, pad));
        btnAdd = NewThemedButton(hwnd, StrL("Add to library"), font, true);
        btnAdd->onClick = MkMethod1<LibraryImportWnd, VirtMouseEvent*, &LibraryImportWnd::OnAdd>(this);
        hbox->AddChild(new Padding(btnAdd, pad));
        vbox->AddChild(hbox);
    }

    auto* padding = new Padding(vbox, DpiScaledInsets(8, 12));
    layout = padding;

    int dx = DpiScale(720);
    LayoutAndSizeToContent(layout, dx, 0, hwnd);
    DoLayout(HwndClientRect(hwnd).Size());
    HwndCenterDialog(hwnd, win ? win->hwndFrame : nullptr);
    UpdateTheme();
    SetIsVisible(true);
    HwndToForeground(hwnd);
    if (editTitle) {
        HwndSetFocus(editTitle->hwnd);
    }
    return true;
}

static void TakeField(Edit* ctrl, LibraryImportField& f) {
    if (!ctrl) {
        return;
    }
    TempStr now = ctrl->GetTextTemp();
    str::ReplaceWithCopy(&f.value, now);
    f.overridden = !str::Eq(f.value, f.original);
    if (f.overridden) {
        str::ReplaceWithCopy(&f.source, StrL("user"));
    }
}

void LibraryImportWnd::ReadFields() {
    TakeField(editTitle, data->title);
    TakeField(editAuthor, data->author);
    TakeField(editSeries, data->series);
    TakeField(editIndex, data->seriesIndex);
    TakeField(editYear, data->year);
    int kind = dropKind ? dropKind->GetCurrentSelection() : 0;
    str::ReplaceWithCopy(&data->chosenKind, kind == 1 ? StrL("document") : StrL("book"));
    str::ReplaceWithCopy(&data->partitionKey, Str());
    int part = dropPartition ? dropPartition->GetCurrentSelection() : 0;
    if (part > 0 && part - 1 < len(data->partitionKeys)) {
        str::ReplaceWithCopy(&data->partitionKey, data->partitionKeys.At(part - 1));
    }
}

void LibraryImportWnd::OnCancel(VirtMouseEvent*) {
    logf("LibraryImport: cancelled, nothing was written\n");
    ScheduleDelete();
}

void LibraryImportWnd::OnAdd(VirtMouseEvent*) {
    ReadFields();
    if (onCommit) {
        onCommit(win, data);
    }
    ScheduleDelete();
}

void ShowLibraryImportWindow(MainWindow* win, LibraryImportData* data, LibraryImportCommitCb onCommit) {
    if (!data) {
        return;
    }
    if (gLibraryImportWnd) {
        HwndToForeground(gLibraryImportWnd->hwnd);
        FreeLibraryImportData(data);
        return;
    }
    auto* wnd = new LibraryImportWnd();
    wnd->closeOnEsc = true;
    wnd->data = data;
    wnd->onCommit = onCommit;
    wnd->onBeforeDelete = MkFunc0Void(ClearLibraryImportWnd);
    wnd->onClose = MkFunc1Void<WindowBase::CloseEvent*>(OnWndClose);
    wnd->onDestroy = MkFunc1Void<WindowBase::DestroyEvent*>(OnWndDestroy);
    wnd->SetFont(GetAppFont());
    if (!wnd->Create(win)) {
        delete wnd;
        return;
    }
    gLibraryImportWnd = wnd;
}

TempStr TestLibraryImportStateTemp() {
    LibraryImportWnd* w = gLibraryImportWnd;
    if (!w || !w->data) {
        return str::DupTemp(StrL("open=0\n"));
    }
    LibraryImportData* d = w->data;
    str::Builder b;
    b.Append(StrL("open=1\n"));
    b.Append(fmt("path=%s\n", Str(d->path)));
    b.Append(fmt("proposed_kind=%s\n", Str(d->proposedKind)));
    b.Append(fmt("excluded=%d\n", d->excluded ? 1 : 0));
    b.Append(fmt("has_writing=%d\n", d->hasWriting ? 1 : 0));
    b.Append(fmt("pages=%d\n", d->pages));
    b.Append(fmt("scraped_meta=%d\n", d->scrapedMeta ? 1 : 0));
    b.Append(fmt("scraped_series=%d\n", d->scrapedSeries ? 1 : 0));
    b.Append(fmt("already=%s\n", Str(d->already)));
    b.Append(fmt("title=%s|%s\n", w->editTitle ? Str(w->editTitle->GetTextTemp()) : Str(), Str(d->title.source)));
    b.Append(fmt("author=%s|%s\n", w->editAuthor ? Str(w->editAuthor->GetTextTemp()) : Str(), Str(d->author.source)));
    b.Append(fmt("series=%s|%s\n", w->editSeries ? Str(w->editSeries->GetTextTemp()) : Str(), Str(d->series.source)));
    b.Append(fmt("series_index=%s|%s\n", w->editIndex ? Str(w->editIndex->GetTextTemp()) : Str(),
                 Str(d->seriesIndex.source)));
    b.Append(fmt("year=%s|%s\n", w->editYear ? Str(w->editYear->GetTextTemp()) : Str(), Str(d->year.source)));
    b.Append(fmt("genre=%s|%s\n", w->editGenre ? Str(w->editGenre->GetTextTemp()) : Str(), Str(d->genre.source)));
    b.Append(fmt("subgenre=%s|%s\n", w->editSubgenre ? Str(w->editSubgenre->GetTextTemp()) : Str(),
                 Str(d->subgenre.source)));
    b.Append(fmt("kind_selected=%d\n", w->dropKind ? w->dropKind->GetCurrentSelection() : -1));
    b.Append(fmt("partitions=%d\n", len(d->partitionKeys)));
    Str out = b.TakeStr();
    TempStr res = str::DupTemp(out);
    str::Free(out);
    return res;
}
