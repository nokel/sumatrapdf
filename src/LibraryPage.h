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
void LibraryRequestScanCancel();
void LibraryRequestScanCancelAndWait();
void LibraryCoverReplace(Str bookId, Str png);
void LibraryCoverReplacePage(Str bookId, int pageNo, RectF rect, int rotation, Str png);
void LibraryCoverRevert(Str bookId);
void LibraryRefresh(MainWindow* win, bool rescan);
void LibraryResumeInterruptedScan();

bool LibraryEnsureService();
void LibraryImportBook(MainWindow* win);
int LibraryServicePort();
int LibraryIgnoreDays();

// Replays the SyncEmbeddedRecords() hot path on demand: walk every book
// in the in-memory model, ask whether it already has a sidecar, and write
// the metadata back. Returns a one-line report "OK books=N processed=N
// withSidecar=N wrote=N ms=N pdfCtx=N pdfOpen=N blobDecode=N coverDecode=N".
// Used by tests/bench-library.ts to time the new BookBlob / PdfSidecar
// path without depending on the SumatraLibrary service.
TempStr RunBenchSyncOnce();

// chunk 31: returns the most recent LoadModelThread's perf summary as a
// one-line "OK adoptMs=... syncMs=... reads=... writes=... pdfOpen=...
// books=... skippedEmbedded=..." string. The bench / test harness reads
// this after triggering a user action (kind move, partition change,
// metadata edit) and waiting for the model to settle, to measure the
// actual user-facing cost.
TempStr LastLoadPerfOnce();

// chunk 31: returns the number of LoadModelThread invocations since
// process start. Used by the test harness to count how many full model
// loads a user action actually triggered.
int LoadModelThreadStartCount();

// chunk 34: returns the number of LoadModelThread invocations that
// have FULLY COMPLETED since process start. The test harness waits
// for this counter to catch up to LoadModelThreadStartCount before
// reading perf, so a slow adopt loop from a previous load cannot
// race with the snapshot of the load the test just triggered.
int LoadModelThreadCompleteCount();

// chunk 31R: observation helpers for the regression test. These call
// the SAME handlers the real UI does (PostPartition, PostBookEdit) so
// the test exercises the production path, not a synthetic wrapper. Also
// exposes a way to read the in-memory state of a single book, so the
// test can confirm the in-memory model agrees with the service after
// each user action.
TempStr TestBookStateTemp(Str bookId);
void TestTriggerPartition(Str url, Str body);
void TestTriggerBookEdit(Str body, Str bookId);
void TestTriggerUserFieldEdit(Str bookId, Str field, Str value, Str exactKey);
int CurrentLibScrollY();
void TestLibScrollToY(int y);
void TestLibForceScrollY(int y);
void TestLibOpenBookById(Str bookId);
void TestLibClickBack();
void TestLibClickAllBooks();
void TestLibClickSeries(Str seriesKey);
void TestLibRescan();
TempStr TestLibScanStatus();
void TestLibToggleProgressive();
