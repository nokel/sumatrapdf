/* Copyright 2022 the SumatraPDF project authors (see AUTHORS file).
   License: GPLv3 */

#include "base/Base.h"
#include "base/Win.h"

#include "Settings.h"
#include "AppSettings.h"

#include "GlobalPrefs.h"

#include "SumatraPDF.h"
#include "resource.h"
#include "SumatraDialogs.h"
#include "Translations.h"
#include "Theme.h"
#include "DarkMode_win.h"

// http://msdn.microsoft.com/en-us/library/ms645398(v=VS.85).aspx
#pragma pack(push, 1)
struct DLGTEMPLATEEX {
    WORD dlgVer;    // 0x0001
    WORD signature; // 0xFFFF
    DWORD helpID;
    DWORD exStyle;
    DWORD style;
    WORD cDlgItems;
    short x, y, cx, cy;
    /*
    sz_Or_Ord menu;
    sz_Or_Ord windowClass;
    WCHAR     title[titleLen];
    WORD      pointsize;
    WORD      weight;
    BYTE      italic;
    BYTE      charset;
    WCHAR     typeface[stringLen];
    */
};
#pragma pack(pop)

static DLGTEMPLATE* DupTemplate(int dlgId) {
    HRSRC dialogRC = FindResourceW(nullptr, MAKEINTRESOURCE(dlgId), RT_DIALOG);
    ReportIf(!dialogRC);
    HGLOBAL dlgTemplate = LoadResource(nullptr, dialogRC);
    ReportIf(!dlgTemplate);
    void* orig = LockResource(dlgTemplate);
    int size = (int)SizeofResource(nullptr, dialogRC);
    ReportIf(size <= 0);
    DLGTEMPLATE* ret = (DLGTEMPLATE*)MemDup(nullptr, orig, (size_t)size);
    UnlockResource(orig);
    return ret;
}

/*
Type: sz_Or_Ord

A variable-length array of 16-bit elements that identifies a menu resource for the dialog box. If the first element of
this array is 0x0000, the dialog box has no menu and the array has no other elements. If the first element is 0xFFFF,
the array has one additional element that specifies the ordinal value of a menu resource in an executable file. If the
first element has any other value, the system treats the array as a null-terminated Unicode string that specifies the
name of a menu resource in an executable file.
*/
static u8* SkipSzOrOrd(u8* d) {
    WORD* pw = (WORD*)d;
    WORD w = *pw++;
    if (w == 0x0000) {
        // no menu
    } else if (w == 0xffff) {
        // menu id followed by another WORD item
        pw++;
    } else {
        // anything else: zero-terminated WCHAR*
        WCHAR* s = (WCHAR*)pw;
        while (*s) {
            s++;
        }
        s++;
        pw = (WORD*)s;
    }
    return (u8*)pw;
}

static u8* SkipSz(u8* d) {
    WCHAR* s = (WCHAR*)d;
    while (*s) {
        s++;
    }
    s++;
    return (u8*)s;
}

static bool IsDlgTemplateEx(DLGTEMPLATE* tpl) {
    return tpl->style == MAKELONG(0x0001, 0xFFFF);
}

static bool HasDlgTemplateExFont(DLGTEMPLATEEX* tpl) {
    DWORD style = tpl->style & (DS_SETFONT | DS_FIXEDSYS);
    return style != 0;
}

// gets a dialog template from the resources and sets the RTL flag
// cf. http://www.ureader.com/msg/1484387.aspx
static void SetDlgTemplateRtl(DLGTEMPLATE* tpl) {
    if (IsDlgTemplateEx(tpl)) {
        ((DLGTEMPLATEEX*)tpl)->exStyle |= WS_EX_LAYOUTRTL;
    } else {
        tpl->dwExtendedStyle |= WS_EX_LAYOUTRTL;
    }
}

static int ToFontPointSize(int fontSize) {
    int res = (fontSize * 72) / 96;
    return res;
}

// https://stackoverflow.com/questions/14370238/can-i-dynamically-change-the-font-size-of-a-dialog-window-created-with-c-in-vi
// TODO: if changing font name would have do more complicated dance of replacing
// variable string in the middle of the struct
static void SetDlgTemplateExFont(DLGTEMPLATE* tmp, bool isRtl, int fontSize) {
    ReportIf(!IsDlgTemplateEx(tmp));
    if (isRtl) {
        SetDlgTemplateRtl(tmp);
    }
    DLGTEMPLATEEX* tpl = (DLGTEMPLATEEX*)tmp;
    ReportIf(!HasDlgTemplateExFont(tpl));
    u8* d = (u8*)tpl;
    d += sizeof(DLGTEMPLATEEX);
    // sz_Or_Ord menu
    d = SkipSzOrOrd(d);
    // sz_Or_Ord windowClass;
    d = SkipSzOrOrd(d);
    // WCHAR[] title
    d = SkipSz(d);
    // WCHAR pointSize;
    WORD* wd = (WORD*)d;
    fontSize = ToFontPointSize(fontSize);
    *wd = (WORD)fontSize;
}

static DLGTEMPLATE* GetRtLDlgTemplate(int dlgId) {
    DLGTEMPLATE* tpl = DupTemplate(dlgId);
    SetDlgTemplateRtl(tpl);
    return tpl;
}

// creates a dialog box that dynamically gets a right-to-left layout if needed
[[maybe_unused]] static INT_PTR CreateDialogBox(int dlgId, HWND parent, DLGPROC DlgProc, LPARAM data) {
    bool isRtl = IsUIRtl();
    bool isDefaultFont = IsAppFontSizeDefault();
    if (!isRtl && isDefaultFont) {
        return DialogBoxParam(nullptr, MAKEINTRESOURCE(dlgId), parent, DlgProc, data);
    }

    DLGTEMPLATE* tpl = DupTemplate(dlgId);
    int fntSize = GetAppFontSize();
    if (isDefaultFont) {
        SetDlgTemplateRtl(tpl);
    } else {
        SetDlgTemplateExFont(tpl, isRtl, fntSize);
    }

    INT_PTR res = DialogBoxIndirectParamW(nullptr, tpl, parent, DlgProc, data);
    free(tpl);
    return res;
}

#ifndef ID_APPLY_NOW
#define ID_APPLY_NOW 0x3021
#endif

static INT_PTR CALLBACK Sheet_Print_Advanced_Proc(HWND hDlg, UINT msg, WPARAM wp, LPARAM lp) {
    Print_Advanced_Data* data;

    switch (msg) {
        //[ ACCESSKEY_GROUP Advanced Print Tab
        case WM_INITDIALOG:
            data = (Print_Advanced_Data*)((PROPSHEETPAGE*)lp)->lParam;
            SetWindowLongPtr(hDlg, GWLP_USERDATA, (LONG_PTR)data);
            DarkModeApplyToWindow(hDlg);
            HwndSetDlgItemText(hDlg, IDC_SECTION_PRINT_RANGE, _TRA("Print range"));
            HwndSetDlgItemText(hDlg, IDC_PRINT_RANGE_ALL, _TRA("&All selected pages"));
            HwndSetDlgItemText(hDlg, IDC_PRINT_RANGE_EVEN, _TRA("&Even pages only"));
            HwndSetDlgItemText(hDlg, IDC_PRINT_RANGE_ODD, _TRA("&Odd pages only"));
            HwndSetDlgItemText(hDlg, IDC_SECTION_PRINT_SCALE, _TRA("Page scaling"));
            HwndSetDlgItemText(hDlg, IDC_PRINT_SCALE_SHRINK, _TRA("&Shrink pages to printable area (if necessary)"));
            HwndSetDlgItemText(hDlg, IDC_PRINT_SCALE_FIT, _TRA("&Fit pages to printable area"));
            HwndSetDlgItemText(hDlg, IDC_PRINT_SCALE_STRETCH,
                               _TRA("S&tretch pages to fill paper (ignore aspect ratio)"));
            HwndSetDlgItemText(hDlg, IDC_PRINT_SCALE_NONE, _TRA("&Use original page sizes"));
            HwndSetDlgItemText(hDlg, IDC_PRINT_CENTER_HORIZONTALLY, _TRA("Center page hori&zontally on the paper"));
            HwndSetDlgItemText(hDlg, IDC_PRINT_PAPER_SOURCE_BY_SIZE,
                               _TRA("Choose &paper source by document page size"));
            HwndSetDlgItemText(hDlg, IDC_PRINT_PER_PAGE_PAPER_SIZE,
                               _TRA("Print each page at its &document page size (mixed sizes)"));
            HwndSetDlgItemText(hDlg, IDC_PRINT_ROTATE_LABEL, _TRA("&Rotate printout:"));
            {
                HWND hwndCb = GetDlgItem(hDlg, IDC_PRINT_ROTATE);
                CbAddString(hwndCb, _TRA("None"));
                CbAddString(hwndCb, "90°");
                CbAddString(hwndCb, "180°");
                CbAddString(hwndCb, "270°");
                int rotIdx = (data->extraRotation / 90) % 4;
                CbSetCurrentSelection(hwndCb, rotIdx);
            }
            HwndSetDlgItemText(hDlg, IDC_SECTION_PRINT_COMPATIBILITY, _TRA("Compatibility"));

            {
                int rangeId = IDC_PRINT_RANGE_ALL;
                if (data->range == PrintRangeAdv::Even) {
                    rangeId = IDC_PRINT_RANGE_EVEN;
                } else if (data->range == PrintRangeAdv::Odd) {
                    rangeId = IDC_PRINT_RANGE_ODD;
                }
                CheckRadioButton(hDlg, IDC_PRINT_RANGE_ALL, IDC_PRINT_RANGE_ODD, rangeId);
            }
            {
                int scaleId = IDC_PRINT_SCALE_NONE;
                if (data->scale == PrintScaleAdv::Fit) {
                    scaleId = IDC_PRINT_SCALE_FIT;
                } else if (data->scale == PrintScaleAdv::Stretch) {
                    scaleId = IDC_PRINT_SCALE_STRETCH;
                } else if (data->scale == PrintScaleAdv::Shrink) {
                    scaleId = IDC_PRINT_SCALE_SHRINK;
                }
                CheckRadioButton(hDlg, IDC_PRINT_SCALE_SHRINK, IDC_PRINT_SCALE_STRETCH, scaleId);
            }

            CheckDlgButton(hDlg, IDC_PRINT_CENTER_HORIZONTALLY, data->centerHorizontally ? BST_CHECKED : BST_UNCHECKED);
            CheckDlgButton(hDlg, IDC_PRINT_PAPER_SOURCE_BY_SIZE,
                           data->paperSourceByPageSize ? BST_CHECKED : BST_UNCHECKED);
            CheckDlgButton(hDlg, IDC_PRINT_PER_PAGE_PAPER_SIZE, data->perPagePaperSize ? BST_CHECKED : BST_UNCHECKED);

            return FALSE;
            //] ACCESSKEY_GROUP Advanced Print Tab

        case WM_NOTIFY:
            if (((LPNMHDR)lp)->code == PSN_APPLY) {
                data = (Print_Advanced_Data*)GetWindowLongPtr(hDlg, GWLP_USERDATA);
                if (IsDlgButtonChecked(hDlg, IDC_PRINT_RANGE_EVEN)) {
                    data->range = PrintRangeAdv::Even;
                } else if (IsDlgButtonChecked(hDlg, IDC_PRINT_RANGE_ODD)) {
                    data->range = PrintRangeAdv::Odd;
                } else {
                    data->range = PrintRangeAdv::All;
                }
                if (IsDlgButtonChecked(hDlg, IDC_PRINT_SCALE_FIT)) {
                    data->scale = PrintScaleAdv::Fit;
                } else if (IsDlgButtonChecked(hDlg, IDC_PRINT_SCALE_STRETCH)) {
                    data->scale = PrintScaleAdv::Stretch;
                } else if (IsDlgButtonChecked(hDlg, IDC_PRINT_SCALE_SHRINK)) {
                    data->scale = PrintScaleAdv::Shrink;
                } else {
                    data->scale = PrintScaleAdv::None;
                }
                data->centerHorizontally = IsDlgButtonChecked(hDlg, IDC_PRINT_CENTER_HORIZONTALLY) != 0;
                data->paperSourceByPageSize = IsDlgButtonChecked(hDlg, IDC_PRINT_PAPER_SOURCE_BY_SIZE) != 0;
                data->perPagePaperSize = IsDlgButtonChecked(hDlg, IDC_PRINT_PER_PAGE_PAPER_SIZE) != 0;
                int rotIdx = (int)SendDlgItemMessage(hDlg, IDC_PRINT_ROTATE, CB_GETCURSEL, 0, 0);
                data->extraRotation = rotIdx > 0 ? rotIdx * 90 : 0;
                return TRUE;
            }
            break;

        case WM_COMMAND:
            switch (LOWORD(wp)) {
                case IDC_PRINT_RANGE_ALL:
                case IDC_PRINT_RANGE_EVEN:
                case IDC_PRINT_RANGE_ODD:
                case IDC_PRINT_SCALE_SHRINK:
                case IDC_PRINT_SCALE_FIT:
                case IDC_PRINT_SCALE_STRETCH:
                case IDC_PRINT_SCALE_NONE:
                case IDC_PRINT_CENTER_HORIZONTALLY:
                case IDC_PRINT_PAPER_SOURCE_BY_SIZE:
                case IDC_PRINT_PER_PAGE_PAPER_SIZE: {
                    HWND hApplyButton = GetDlgItem(GetParent(hDlg), ID_APPLY_NOW);
                    EnableWindow(hApplyButton, TRUE);
                } break;
                case IDC_PRINT_ROTATE:
                    if (HIWORD(wp) == CBN_SELCHANGE) {
                        EnableWindow(GetDlgItem(GetParent(hDlg), ID_APPLY_NOW), TRUE);
                    }
                    break;
            }
    }
    return FALSE;
}

HPROPSHEETPAGE CreatePrintAdvancedPropSheet(Print_Advanced_Data* data, ScopedMem<DLGTEMPLATE>& dlgTemplate) {
    PROPSHEETPAGE psp{};

    psp.dwSize = sizeof(PROPSHEETPAGE);
    psp.dwFlags = PSP_USETITLE | PSP_PREMATURE;
    psp.pszTemplate = MAKEINTRESOURCE(IDD_PROPSHEET_PRINT_ADVANCED);
    psp.pfnDlgProc = Sheet_Print_Advanced_Proc;
    psp.lParam = (LPARAM)data;
    auto s = _TRA("Advanced");
    psp.pszTitle = CWStrTemp(s);

    if (IsUIRtl()) {
        dlgTemplate.Set(GetRtLDlgTemplate(IDD_PROPSHEET_PRINT_ADVANCED));
        psp.pResource = dlgTemplate.Get();
        psp.dwFlags |= PSP_DLGINDIRECT;
    }

    return CreatePropertySheetPage(&psp);
}

struct Dialog_PartitionName_Data {
    Str title;
    Str prompt;
    Str name;
    ~Dialog_PartitionName_Data() {
        str::Free(title);
        str::Free(prompt);
        str::Free(name);
    }
};

static INT_PTR CALLBACK Dialog_PartitionName_Proc(HWND hDlg, UINT msg, WPARAM wp, LPARAM lp) {
    if (WM_INITDIALOG == msg) {
        auto data = (Dialog_PartitionName_Data*)lp;
        SetWindowLongPtr(hDlg, GWLP_USERDATA, (LONG_PTR)data);
        DarkModeApplyToWindow(hDlg);
        HwndSetText(hDlg, data->title);
        HwndSetDlgItemText(hDlg, IDC_PARTITION_NAME_LABEL, data->prompt);
        HwndSetDlgItemText(hDlg, IDOK, _TRA("OK"));
        HwndSetDlgItemText(hDlg, IDCANCEL, _TRA("Cancel"));
        if (len(data->name) > 0) {
            HwndSetDlgItemText(hDlg, IDC_PARTITION_NAME_EDIT, data->name);
            EditSelectAll(GetDlgItem(hDlg, IDC_PARTITION_NAME_EDIT));
        }
        HwndCenterDialog(hDlg);
        HwndSetFocus(GetDlgItem(hDlg, IDC_PARTITION_NAME_EDIT));
        return FALSE;
    }
    if (WM_COMMAND == msg) {
        auto data = (Dialog_PartitionName_Data*)GetWindowLongPtr(hDlg, GWLP_USERDATA);
        WORD cmd = LOWORD(wp);
        if (IDOK == cmd) {
            TempStr name = HwndGetTextTemp(GetDlgItem(hDlg, IDC_PARTITION_NAME_EDIT));
            str::TrimWSInPlace(name, str::TrimOpt::Both);
            str::ReplaceWithCopy(&data->name, name);
            EndDialog(hDlg, IDOK);
            return TRUE;
        }
        if (IDCANCEL == cmd) {
            EndDialog(hDlg, IDCANCEL);
            return TRUE;
        }
    }
    return FALSE;
}

bool Dialog_PartitionName(HWND hwnd, Str title, Str prompt, Str& name) {
    Dialog_PartitionName_Data data;
    data.title = str::Dup(title);
    data.prompt = str::Dup(prompt);
    data.name = str::Dup(name);
    INT_PTR res = CreateDialogBox(IDD_DIALOG_PARTITION_NAME, hwnd, Dialog_PartitionName_Proc, (LPARAM)&data);
    if (IDCANCEL == res || len(data.name) == 0) {
        return false;
    }
    str::ReplaceWithCopy(&name, data.name);
    return true;
}

// ===== Rename Series =====
struct Dialog_RenameSeries_Data {
    Str label;
    Str currentName;
    Str newName;
    ~Dialog_RenameSeries_Data() {
        str::Free(label);
        str::Free(currentName);
        str::Free(newName);
    }
};

static INT_PTR CALLBACK Dialog_RenameSeries_Proc(HWND hDlg, UINT msg, WPARAM wp, LPARAM lp) {
    if (WM_INITDIALOG == msg) {
        auto data = (Dialog_RenameSeries_Data*)lp;
        SetWindowLongPtr(hDlg, GWLP_USERDATA, (LONG_PTR)data);
        DarkModeApplyToWindow(hDlg);
        HwndSetText(hDlg, _TRA("Rename series"));
        HwndSetDlgItemText(hDlg, IDC_RENAME_SERIES_LABEL, data->label);
        HwndSetDlgItemText(hDlg, IDOK, _TRA("OK"));
        HwndSetDlgItemText(hDlg, IDCANCEL, _TRA("Cancel"));
        HwndSetDlgItemText(hDlg, IDC_RENAME_SERIES_EDIT, data->currentName);
        EditSelectAll(GetDlgItem(hDlg, IDC_RENAME_SERIES_EDIT));
        HwndCenterDialog(hDlg);
        HwndSetFocus(GetDlgItem(hDlg, IDC_RENAME_SERIES_EDIT));
        return FALSE;
    }
    if (WM_COMMAND == msg) {
        auto data = (Dialog_RenameSeries_Data*)GetWindowLongPtr(hDlg, GWLP_USERDATA);
        WORD cmd = LOWORD(wp);
        if (IDOK == cmd) {
            TempStr name = HwndGetTextTemp(GetDlgItem(hDlg, IDC_RENAME_SERIES_EDIT));
            str::TrimWSInPlace(name, str::TrimOpt::Both);
            str::ReplaceWithCopy(&data->newName, name);
            EndDialog(hDlg, IDOK);
            return TRUE;
        }
        if (IDCANCEL == cmd) {
            EndDialog(hDlg, IDCANCEL);
            return TRUE;
        }
    }
    return FALSE;
}

bool Dialog_RenameSeries(HWND hwnd, Str currentName, Str& newName) {
    Dialog_RenameSeries_Data data;
    data.label = str::Dup(_TRA("&New series name:"));
    data.currentName = str::Dup(currentName);
    data.newName = str::Dup(currentName);
    INT_PTR res = CreateDialogBox(IDD_DIALOG_RENAME_SERIES, hwnd, Dialog_RenameSeries_Proc, (LPARAM)&data);
    if (IDCANCEL == res) {
        return false;
    }
    str::ReplaceWithCopy(&newName, data.newName);
    return true;
}

// ===== Edit Book Metadata =====
struct Dialog_BookMetadata_Data {
    // the BookMetadataEdit belongs to the caller and outlives the dialog:
    // freeing its strings here left the caller reading freed memory, which
    // the heap had already handed to something else by the time it looked.
    // FreeBookMetadataEdit() is what releases them, when the caller is done.
    BookMetadataEdit* edit = nullptr;
};

static void SetFieldSourceLabel(HWND hDlg, int ctrlId, BookMetadataField& f) {
    TempStr label = nullptr;
    if (f.overridden && len(f.original) > 0) {
        label = fmt("source: %s (was: %s)", Str(f.source), Str(f.original));
    } else if (len(f.source) > 0) {
        label = fmt("source: %s", Str(f.source));
    } else {
        label = str::DupTemp(StrL("source: (auto)"));
    }
    HwndSetDlgItemText(hDlg, ctrlId, label);
}

static void Dialog_BookMetadata_FillFields(HWND hDlg, Dialog_BookMetadata_Data* data) {
    HwndSetDlgItemText(hDlg, IDC_BOOK_TITLE_EDIT, data->edit->title.value);
    HwndSetDlgItemText(hDlg, IDC_BOOK_AUTHOR_EDIT, data->edit->author.value);
    HwndSetDlgItemText(hDlg, IDC_BOOK_SERIES_EDIT, data->edit->series.value);
    HwndSetDlgItemText(hDlg, IDC_BOOK_YEAR_EDIT, data->edit->year.value);
    SetFieldSourceLabel(hDlg, IDC_BOOK_TITLE_SOURCE, data->edit->title);
    SetFieldSourceLabel(hDlg, IDC_BOOK_AUTHOR_SOURCE, data->edit->author);
    SetFieldSourceLabel(hDlg, IDC_BOOK_SERIES_SOURCE, data->edit->series);
    SetFieldSourceLabel(hDlg, IDC_BOOK_YEAR_SOURCE, data->edit->year);
    if (len(data->edit->filePath) > 0) {
        TempStr label = fmt("Path: %s", Str(data->edit->filePath));
        HwndSetDlgItemText(hDlg, IDC_BOOK_PATH_LABEL, label);
    }
}

static void Dialog_BookMetadata_ReadFields(HWND hDlg, Dialog_BookMetadata_Data* data) {
    str::ReplaceWithCopy(&data->edit->title.value, HwndGetTextTemp(GetDlgItem(hDlg, IDC_BOOK_TITLE_EDIT)));
    str::ReplaceWithCopy(&data->edit->author.value, HwndGetTextTemp(GetDlgItem(hDlg, IDC_BOOK_AUTHOR_EDIT)));
    str::ReplaceWithCopy(&data->edit->series.value, HwndGetTextTemp(GetDlgItem(hDlg, IDC_BOOK_SERIES_EDIT)));
    str::ReplaceWithCopy(&data->edit->year.value, HwndGetTextTemp(GetDlgItem(hDlg, IDC_BOOK_YEAR_EDIT)));
}

static INT_PTR CALLBACK Dialog_BookMetadata_Proc(HWND hDlg, UINT msg, WPARAM wp, LPARAM lp) {
    if (WM_INITDIALOG == msg) {
        auto data = (Dialog_BookMetadata_Data*)lp;
        SetWindowLongPtr(hDlg, GWLP_USERDATA, (LONG_PTR)data);
        DarkModeApplyToWindow(hDlg);
        HwndSetDlgItemText(hDlg, IDOK, _TRA("Save"));
        HwndSetDlgItemText(hDlg, IDCANCEL, _TRA("Cancel"));
        HwndSetDlgItemText(hDlg, IDC_BOOK_TITLE_LABEL, _TRA("&Title:"));
        HwndSetDlgItemText(hDlg, IDC_BOOK_AUTHOR_LABEL, _TRA("&Author:"));
        HwndSetDlgItemText(hDlg, IDC_BOOK_SERIES_LABEL, _TRA("&Series:"));
        HwndSetDlgItemText(hDlg, IDC_BOOK_YEAR_LABEL, _TRA("&Year:"));
        Dialog_BookMetadata_FillFields(hDlg, data);
        HwndCenterDialog(hDlg);
        return TRUE;
    }
    if (WM_COMMAND == msg) {
        auto data = (Dialog_BookMetadata_Data*)GetWindowLongPtr(hDlg, GWLP_USERDATA);
        WORD cmd = LOWORD(wp);
        if (IDOK == cmd) {
            Dialog_BookMetadata_ReadFields(hDlg, data);
            // Mark each field as overridden only if the user changed it
            // (compared to the value we showed at init). The Revert
            // buttons mark it as "not overridden" by clearing source.
            if (!str::Eq(data->edit->title.value, data->edit->title.original)) {
                str::ReplaceWithCopy(&data->edit->title.source, StrL("user"));
                data->edit->title.overridden = true;
                data->edit->title.cleared = false;
            }
            if (!str::Eq(data->edit->author.value, data->edit->author.original)) {
                str::ReplaceWithCopy(&data->edit->author.source, StrL("user"));
                data->edit->author.overridden = true;
                data->edit->author.cleared = false;
            }
            if (!str::Eq(data->edit->series.value, data->edit->series.original)) {
                str::ReplaceWithCopy(&data->edit->series.source, StrL("user"));
                data->edit->series.overridden = true;
                data->edit->series.cleared = false;
            }
            if (!str::Eq(data->edit->year.value, data->edit->year.original)) {
                str::ReplaceWithCopy(&data->edit->year.source, StrL("user"));
                data->edit->year.overridden = true;
                data->edit->year.cleared = false;
            }
            EndDialog(hDlg, IDOK);
            return TRUE;
        }
        if (IDCANCEL == cmd) {
            EndDialog(hDlg, IDCANCEL);
            return TRUE;
        }
        if (IDC_BOOK_REVERT_TITLE == cmd) {
            str::ReplaceWithCopy(&data->edit->title.value, data->edit->title.original);
            str::ReplaceWithCopy(&data->edit->title.source, StrL(""));
            data->edit->title.overridden = false;
            data->edit->title.cleared = true;
            HwndSetDlgItemText(hDlg, IDC_BOOK_TITLE_EDIT, data->edit->title.value);
            SetFieldSourceLabel(hDlg, IDC_BOOK_TITLE_SOURCE, data->edit->title);
            return TRUE;
        }
        if (IDC_BOOK_REVERT_AUTHOR == cmd) {
            str::ReplaceWithCopy(&data->edit->author.value, data->edit->author.original);
            str::ReplaceWithCopy(&data->edit->author.source, StrL(""));
            data->edit->author.overridden = false;
            data->edit->author.cleared = true;
            HwndSetDlgItemText(hDlg, IDC_BOOK_AUTHOR_EDIT, data->edit->author.value);
            SetFieldSourceLabel(hDlg, IDC_BOOK_AUTHOR_SOURCE, data->edit->author);
            return TRUE;
        }
        if (IDC_BOOK_REVERT_SERIES == cmd) {
            str::ReplaceWithCopy(&data->edit->series.value, data->edit->series.original);
            str::ReplaceWithCopy(&data->edit->series.source, StrL(""));
            data->edit->series.overridden = false;
            data->edit->series.cleared = true;
            HwndSetDlgItemText(hDlg, IDC_BOOK_SERIES_EDIT, data->edit->series.value);
            SetFieldSourceLabel(hDlg, IDC_BOOK_SERIES_SOURCE, data->edit->series);
            return TRUE;
        }
        if (IDC_BOOK_REVERT_YEAR == cmd) {
            str::ReplaceWithCopy(&data->edit->year.value, data->edit->year.original);
            str::ReplaceWithCopy(&data->edit->year.source, StrL(""));
            data->edit->year.overridden = false;
            data->edit->year.cleared = true;
            HwndSetDlgItemText(hDlg, IDC_BOOK_YEAR_EDIT, data->edit->year.value);
            SetFieldSourceLabel(hDlg, IDC_BOOK_YEAR_SOURCE, data->edit->year);
            return TRUE;
        }
        if (IDC_BOOK_CLEAR_OVERRIDES == cmd) {
            // Revert all four fields to the auto-detected values
            str::ReplaceWithCopy(&data->edit->title.value, data->edit->title.original);
            str::ReplaceWithCopy(&data->edit->title.source, StrL(""));
            data->edit->title.overridden = false;
            str::ReplaceWithCopy(&data->edit->author.value, data->edit->author.original);
            str::ReplaceWithCopy(&data->edit->author.source, StrL(""));
            data->edit->author.overridden = false;
            str::ReplaceWithCopy(&data->edit->series.value, data->edit->series.original);
            str::ReplaceWithCopy(&data->edit->series.source, StrL(""));
            data->edit->series.overridden = false;
            str::ReplaceWithCopy(&data->edit->year.value, data->edit->year.original);
            str::ReplaceWithCopy(&data->edit->year.source, StrL(""));
            data->edit->year.overridden = false;
            data->edit->title.cleared = true;
            data->edit->author.cleared = true;
            data->edit->series.cleared = true;
            data->edit->year.cleared = true;
            Dialog_BookMetadata_FillFields(hDlg, data);
            return TRUE;
        }
    }
    return FALSE;
}

bool Dialog_BookMetadata(HWND hwnd, BookMetadataEdit& edit) {
    Dialog_BookMetadata_Data data;
    data.edit = &edit;
    // Pre-fill "original" with the current value so the comparison
    // works when no override is in place yet.
    if (len(edit.title.original) == 0) {
        str::ReplaceWithCopy(&edit.title.original, edit.title.value);
    }
    if (len(edit.author.original) == 0) {
        str::ReplaceWithCopy(&edit.author.original, edit.author.value);
    }
    if (len(edit.series.original) == 0) {
        str::ReplaceWithCopy(&edit.series.original, edit.series.value);
    }
    if (len(edit.year.original) == 0) {
        str::ReplaceWithCopy(&edit.year.original, edit.year.value);
    }
    INT_PTR res = CreateDialogBox(IDD_DIALOG_BOOK_METADATA, hwnd, Dialog_BookMetadata_Proc, (LPARAM)&data);
    return res == IDOK;
}

static void FreeBookMetadataField(BookMetadataField& f) {
    str::Free(f.label);
    str::Free(f.value);
    str::Free(f.source);
    str::Free(f.original);
    f = BookMetadataField{};
}

void FreeBookMetadataEdit(BookMetadataEdit& edit) {
    str::Free(edit.bookId);
    str::Free(edit.filePath);
    edit.bookId = {};
    edit.filePath = {};
    FreeBookMetadataField(edit.title);
    FreeBookMetadataField(edit.author);
    FreeBookMetadataField(edit.series);
    FreeBookMetadataField(edit.year);
}
