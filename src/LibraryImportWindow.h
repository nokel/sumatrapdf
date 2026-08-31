/* Copyright 2026 the SumatraPDF project authors (see AUTHORS file).
   License: GPLv3 */

struct MainWindow;

struct LibraryImportField {
    Str value;
    Str source;
    Str original;
    bool overridden = false;
};

struct LibraryImportData {
    Str path;
    Str bookId;
    Str bookJson;
    LibraryImportField title;
    LibraryImportField author;
    LibraryImportField series;
    LibraryImportField seriesIndex;
    LibraryImportField year;
    LibraryImportField genre;
    LibraryImportField subgenre;
    Str proposedKind;
    Str chosenKind;
    Str already;
    Str ext;
    Str partitionKey;
    StrVec partitionKeys;
    StrVec partitionNames;
    bool excluded = false;
    bool hasWriting = true;
    bool scrapedMeta = false;
    bool scrapedSeries = false;
    int pages = 0;
    i64 size = 0;
};

typedef void (*LibraryImportCommitCb)(MainWindow* win, LibraryImportData* data);

void ShowLibraryImportWindow(MainWindow* win, LibraryImportData* data, LibraryImportCommitCb onCommit);
void FreeLibraryImportData(LibraryImportData* data);
TempStr TestLibraryImportStateTemp();
