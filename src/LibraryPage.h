/* Copyright 2026 the SumatraPDF project authors (see AUTHORS file).
   License: Simplified BSD (see COPYING.BSD) */

struct MainWindow;

constexpr const char* kLinkLibraryPrefix = "<Library,";
constexpr int kLibraryServicePortDefault = 7863;

bool LibraryHomeEnabled();
void SetLibraryHomeEnabled(bool enabled);
bool LibraryHasBooks();

void DrawLibraryPage(MainWindow* win, HDC hdc);
bool LibraryOnLinkClicked(MainWindow* win, Str url);
bool LibraryOnRightClick(MainWindow* win, int x, int y);
bool LibraryOnLeftButtonDown(MainWindow* win, int x, int y);
bool LibraryOnMouseMove(MainWindow* win, int x, int y);
bool LibraryOnLeftButtonUp(MainWindow* win);
void LibraryOnCaptureLost(MainWindow* win);
void LibraryOnMouseWheel(MainWindow* win, int delta, int screenX, int screenY);
void LibraryOnVScroll(MainWindow* win, WPARAM wp);

void LibraryFreeCache();
void LibraryCoverReplace(Str bookId, Str png);
void LibraryCoverReplacePage(Str bookId, int pageNo, RectF rect, int rotation, Str png);
void LibraryCoverRevert(Str bookId);
void LibraryRefresh(MainWindow* win, bool rescan);

bool LibraryEnsureService();
int LibraryServicePort();

// Replays the SyncEmbeddedRecords() hot path on demand: walk every book
// in the in-memory model, ask whether it already has a sidecar, and write
// the metadata back. Returns a one-line report "OK books=N processed=N
// withSidecar=N wrote=N ms=N pdfCtx=N pdfOpen=N blobDecode=N coverDecode=N".
// Used by tests/bench-library.ts to time the new BookBlob / PdfSidecar
// path without depending on the SumatraLibrary service.
TempStr RunBenchSyncOnce();
