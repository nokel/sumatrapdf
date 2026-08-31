/* Copyright 2026 the SumatraPDF project authors (see AUTHORS file).
   License: Simplified BSD (see COPYING.BSD) */

#include "base/Base.h"
#include "base/ScopedWin.h"
#include "gui/Dpi.h"
#include "base/File.h"
#include "base/Win.h"
#include "base/GdiPlusUtil.h"
#include "base/Pixmap.h"
#include "base/Http.h"
#include "base/UITask.h"
#include "base/JsonParser.h"
#include "JsonVisitor.h"
#include "LibraryImportWindow.h"
#include "base/Crypto.h"

#include "gui/UIModels.h"
#include "gui/Layout.h"
#include "gui/win/WinGui.h"
#include "gui/PlatformFont.h"

#include "Settings.h"
#include "DocController.h"
#include "GlobalPrefs.h"
#include "AppSettings.h"
#include "SumatraPDF.h"
#include "MainWindow.h"
#include "WindowTab.h"
#include "Commands.h"
#include "SumatraDialogs.h"
#include "Theme.h"
#include "Translations.h"
#include "FileHistory.h"
#include "Toolbar.h"
#include "HomePage.h"
#include "ImageReader.h"
#include "AppTools.h"
#include "LibraryScan.h"
#include "LibraryData.h"
#include "LibraryStore.h"
#include "LibraryPage.h"
#include "BookBlob.h"
#include "LibrarySidecar.h"
#include "CoverVision.h"
#include "CoverEditor.h"
#include "BookFingerprint.h"
#include "PdfSidecar.h"

constexpr int kMaxBooks = 4096;
constexpr int kMaxSeries = 256;
constexpr int kSeriesTreeDepth = 8;
constexpr int kMaxPeople = 60;
constexpr int kMaxScreen = 8;
constexpr int kMaxFamily = 24;
constexpr int kMaxCovers = 400;
constexpr int kCoverWorkers = 3;

constexpr const char* kLinkSeries = "<Library,Series>";
constexpr const char* kLinkBook = "<Library,Book>";
constexpr const char* kLinkBack = "<Library,Back>";
constexpr const char* kLinkRead = "<Library,Read>";
constexpr const char* kLinkTab = "<Library,Tab>";
constexpr const char* kLinkPerson = "<Library,Person>";
constexpr const char* kLinkRescan = "<Library,Rescan>";
constexpr const char* kLinkProgressiveScan = "<Library,ProgressiveScan>";
constexpr const char* kLinkImportBook = "<Library,ImportBook>";
constexpr const char* kLinkClassic = "<Library,Classic>";
constexpr const char* kLinkAllBooks = "<Library,All>";
constexpr const char* kLinkEditMetadata = "<Library,EditMetadata>";

// Inline edit links. Each of the four fields in the detail view
// (title, author, series, year) is clickable to enter an in-place
// rename, like F2 in Windows Explorer. The URL encodes the field
// name and the book id, so the click handler can dispatch to the
// right inline-edit slot.
constexpr const char* kLinkEditTitle = "<Library,EditTitle>";
constexpr const char* kLinkEditAuthor = "<Library,EditAuthor>";
constexpr const char* kLinkEditSeries = "<Library,EditSeries>";
constexpr const char* kLinkEditYear = "<Library,EditYear>";

// Forward decls so the click handler at the top of the file can call
// helpers defined later (RunEditBookMetadata + BookById).
struct LibBook;
static void RunEditBookMetadata(MainWindow* win, LibBook* book);
static LibBook* BookById(Str id);
static TempStr BuildTitleTip();
// Inline edit machinery is defined further down (state struct +
// wndproc + Start/Commit/Cancel). Forward-declare the bits the
// early click handler at LibraryOnLeftButtonDown needs to call
// when the user clicks outside the EDITTEXT — without these,
// committing on click-out doesn't work because the canvas never
// takes focus on its own (so WM_KILLFOCUS never fires).
enum class InlineField {
    Title,
    Author,
    Series,
    Year
};
struct InlineEdit;
static void CommitInlineEdit(bool commit);
static void StartInlineEdit(MainWindow* win, LibBook* book, InlineField field, Rect fieldRect);
static void PostBookEdit(const char* path, Str body, Str bookId, int reverted = 0);
struct LibSeries;
static LibSeries* RowByKey(Str key);
constexpr const char* kLinkOpen = "<Library,Open>";
constexpr const char* kLinkPage = "<Library,Page>";
constexpr const char* kLinkChapter = "<Library,Chapter>";
constexpr const char* kLinkTopic = "<Library,Topic>";
constexpr const char* kLinkTopicList = "<Library,Topics>";
constexpr const char* kLinkScreenTitle = "<Library,Imdb>";
constexpr const char* kLinkSort = "<Library,Sort>";
constexpr const char* kLinkDeskpan = "<Library,Deskpan>";
constexpr const char* kLinkDeskShow = "<Library,DeskShow>";
constexpr const char* kLinkDeskPick = "<Library,Pick>";
constexpr const char* kLinkDeskPickAll = "<Library,PickAll>";
constexpr const char* kLinkDeskMove = "<Library,Move>";
constexpr const char* kLinkChangeCover = "<Library,Cover>";
constexpr const char* kDeskCoverKey = "desk:";

constexpr int kMaxChapters = 512;
constexpr int kMaxKnowers = 40;
constexpr int kMaxDeskFiles = 4096;
constexpr int kMaxRoamed = 4096;

constexpr int kMenuOpenResume = 1;
constexpr int kMenuOpenStart = 2;
constexpr int kMenuPlayAudiobook = 3;
constexpr int kMenuNewPartition = 4;
constexpr int kMenuTakeOutOfPartition = 5;
constexpr int kMenuRenamePartition = 6;
constexpr int kMenuDeletePartition = 7;
constexpr int kMenuMoveToLibrary = 8;
constexpr int kMenuRemoveFromLibrary = 9;
constexpr int kMenuIgnoreFile = 10;
constexpr int kMenuOpenDocument = 11;
constexpr int kMenuSelectFiles = 12;
constexpr int kMenuLeaveSeries = 13;
constexpr int kMenuRenameSeries = 14;
constexpr int kMenuEditBookMetadata = 15;
constexpr int kMenuRestoreAutoSeries = 16;
constexpr int kMenuRestoreAutoParent = 17;
constexpr int kMenuRejoinFirst = 60;
constexpr int kMenuPartitionFirst = 100;
constexpr int kMenuSeriesFirst = 1000;
constexpr int kMenuRowParentFirst = 2000;
constexpr int kMaxPartitions = 64;

constexpr const char* kKindBook = "book";
constexpr const char* kKindDocument = "document";
constexpr const char* kKindIgnored = "ignored";

enum class LibTab {
    Overview,
    People,
    Family,
    Places,
    Knows,
    Screen,
    Info
};

constexpr int kLibTabCount = 7;

constexpr int kMaxOutOf = 4;

struct LibPulled {
    Str key;
    Str name;
};

struct LibBook {
    Str id;
    Str title;
    Str author;
    Str series;
    Str seriesParent;
    Str seriesKey;
    Str keys;
    Str genre;
    Str subgenre;
    Str tags;
    LibPulled outOf[kMaxOutOf];
    int nOutOf = 0;
    Str path;
    Str file; // basename only — shown in the metadata dialog
    Str ext;
    Str wiki;
    Str titleSource; // "filename" / "pdf-meta" / "cover" / "nlp" /
                     // "wikipedia" / "imdb" / "openlibrary" /
                     // "googlebooks" / "wikidata" / "user" / "auto"
    Str authorSource;
    Str yearSource;
    Str seriesSource;
    Str contentHash; // sha1 of the PDF content (for dup detection)
    int pages = 0;
    int year = 0;
    int volume = 0;
    bool booknlp = false;
    bool cover = false;
};

struct LibSeries {
    Str key;
    Str name;
    Str author;
    Str parent;
    Str wiki;
    Str genre;
    Str sub;
    Str head;
    Str subhead;
    Str kind;
    Str guessed;
    Str parentSource;
    int books = 0;
    int booknlp = 0;
    int facts = 0;
    int depth = 0;
};

struct LibPartition {
    Str key;
    Str name;
    Str parent;
    int books = 0;
    int depth = 0;
};

struct LibChapter {
    Str title;
    int page = 0;
    int depth = 0;
    int parent = -1;
    int kids = 0;
    bool open = false;
};

struct LibKnower {
    Str name;
    Str book;
    int page = 0;
    int mentions = 0;
};

struct LibScreen {
    Str title;
    Str kind;
    Str poster;
    Str stars;
    Str via;
    Str imdbId;
    int year = 0;
};

struct LibFamilyRow {
    Str relation;
    Str name;
    int idx = 0;
    int mentions = 0;
};

struct LibModel {
    LibBook books[kMaxBooks];
    int nBooks = 0;
    LibSeries series[kMaxSeries];
    int nSeries = 0;
    LibraryRoamed roamed[kMaxRoamed];
    int nRoamed = 0;
    Str filter;
    Str filterName;
    Str error;
    bool loaded = false;
    bool loading = false;
    bool loadFailed = false;
    bool scanning = false;
    bool scopeCurrent = true;
    bool resumePending = false;
    int scanDone = 0;
    int scanTotal = 0;
    int scanItemDone = 0;
    int scanItemTotal = 0;
    bool scanItemIndeterminate = false;
    Str scanItem;
    Str scanItemAction;
    int scanTraversals = 0;
    int scanFilesDiscovered = 0;
    int scanFilesProcessed = 0;
    int total = 0;
    int documents = 0;
    int ignored = 0;
};

struct LibDeskFile {
    Str id;
    Str title;
    Str file;
    Str folder;
    Str path;
    Str ext;
    int pages = 0;
    i64 size = 0;
    bool chosen = false;
    HIMAGELIST himl = nullptr;
    int iconIdx = -1;
};

struct LibDesk {
    LibDeskFile files[kMaxDeskFiles];
    int nFiles = 0;
    int total = 0;
    bool showIgnored = false;
    bool loaded = false;
    bool loading = false;
    bool working = false;
    bool selecting = false;
    int anchor = -1;
};

static bool gNativeScanning = false;
static bool gAutoSweepStarted = false;
static volatile LONG gScanCancel = 0;
static volatile bool gSweepActive = false;

struct LibDetail {
    Str id;
    Str title;
    Str author;
    Str series;
    Str wiki;
    Str path;
    Str description;
    Str subjects;
    Str person;
    Str personTraits;
    Str personNot;
    Str personKin;
    Str personSpeech;
    Str personVoice;
    Str personPlaces;
    Str personKnows;
    Str personQuote;
    Str personQuoteBook;
    int personQuotePage = 0;
    int personBooks = 0;
    bool personLoaded = false;
    int year = 0;
    int pages = 0;
    bool booknlp = false;
    bool loading = false;
    bool screenLoading = false;
    bool screenDone = false;
    LibTab tab = LibTab::Overview;
    Str people[kMaxPeople];
    int nPeople = 0;
    Str places[kMaxPeople];
    int nPlaces = 0;
    Str topics[kMaxPeople];
    int nTopics = 0;
    LibFamilyRow family[kMaxFamily];
    int nFamily = 0;
    LibScreen screen[kMaxScreen];
    int nScreen = 0;
    LibChapter chapters[kMaxChapters];
    int nChapters = 0;
    bool chaptersLoading = false;
    bool chaptersDone = false;
    Str topic;
    LibKnower knowers[kMaxKnowers];
    int nKnowers = 0;
    bool topicLoaded = false;
    Str ext;
    Str genre;
    Str sub;
    Str checksum;
    Str mark;
    Str ratingSource;
    double rating = 0;
    int ratingCount = 0;
    i64 created = 0;
    i64 mtime = 0;
    i64 size = 0;
    i64 words = -1;
    bool infoWorking = false;
    bool infoDone = false;
};

struct CoverSlot {
    Str key;
    Str bytes;
    RenderedBitmap* bmp = nullptr;
    bool wanted = false;
    bool fetching = false;
    bool failed = false;
    bool decoded = false;
    int gen = 0;
};

static LibModel gModel;
static LibDetail gDetail;

// Title rect in canvas coords, captured during DrawDetail so the
// click handler can convert to screen coords for the inline edit.
static Rect gDetailTitleRect{};

// Inline-edit state. Defined here (instead of below with the rest of
// the inline-edit code) so the early click handler at
// LibraryOnLeftButtonDown can see the global when checking whether
// there's an active edit to commit on click-out.
struct InlineEdit {
    HWND hwnd = nullptr;
    WNDPROC prevWndProc = nullptr;
    LibBook* book = nullptr;
    MainWindow* win = nullptr;
    InlineField field = InlineField::Title;
    Str original{};
    HFONT font = nullptr;
    bool committing = false;
};
static InlineEdit gInlineEdit;
static LibDesk gDesk;
static bool gDeskOpen = false;
static LibPartition gPartitions[kMaxPartitions];
static int gNPartitions = 0;
static CoverSlot gCovers[kMaxCovers];
static int gNCovers = 0;
static CRITICAL_SECTION gLock;
static bool gLockReady = false;
static Mutex gLoadModelMutex;
static int gWorkers = 0;
static HWND gNotifyHwnd = nullptr;
static UINT_PTR gScanAnimationTimer = 0;
static bool gDetailOpen = false;
static int gPort = 0;

// chunk 31: total number of LoadModelThread invocations since process
// start. Used by the bench/test harness to count how many full model
// loads a user action actually triggers (one of the chunk's required
// metrics: "number of LoadModelThread starts" per user operation).
static AtomicInt gLoadModelThreadStarts = 0;
// chunk 34 regression: incremented at the very end of LoadModelThread
// (after the snapshot is written). The test harness uses this counter
// to wait for a triggered load to FULLY COMPLETE before reading perf,
// instead of just waiting for the load to START. Without this, a slow
// adopt loop from a previous load (e.g. the initial Full load on a
// large library) can finish after a subsequent catalogue-only load,
// overwriting the snapshot the test is about to read.
static AtomicInt gLoadModelThreadCompletes = 0;
// chunk 34 regression: the generation of the most recently STARTED
// LoadModelThread. A load that is still completing checks this at
// the end and only writes its perf snapshot if it is still the most
// recent load. Otherwise a slow initial Full load would overwrite
// the snapshot of a fast catalogue-only load that started after it.
static AtomicInt gLoadModelCurrentGeneration = 0;

// chunk 34 regression: atomic snapshot of the most recently COMPLETED
// LoadModelThread. Written at the end of LoadModelThread, read by
// LastLoadPerfOnce. Prevents the test from seeing a torn snapshot
// (e.g. skippedEmbedded from load N + adoptMs from load N-1) when a
// slow adopt loop from a previous load is still running while a
// catalogue-only load completes.
static u64 gLastCompletedLoadAdoptMs = 0;
static u64 gLastCompletedLoadSyncMs = 0;
static int gLastCompletedLoadReads = 0;
static int gLastCompletedLoadWrites = 0;
static int gLastCompletedLoadPdfOpen = 0;
static int gLastCompletedLoadBooks = 0;
static bool gLastCompletedLoadSkippedEmbedded = false;
static int gLastCompletedLoadGeneration = 0; // matches gLoadModelThreadStarts at completion

// Test hook: returns the most recent COMPLETED LoadModelThread's perf
// summary as a one-line "OK ..." string. Called by the chunk 31 /
// chunk 34 control commands after they trigger a user action and
// wait for the model to settle.
TempStr LastLoadPerfOnce() {
    return str::FormatTemp("OK adoptMs=%llu syncMs=%llu reads=%d writes=%d pdfOpen=%d books=%d skippedEmbedded=%d\n",
                           (unsigned long long)gLastCompletedLoadAdoptMs, (unsigned long long)gLastCompletedLoadSyncMs,
                           gLastCompletedLoadReads, gLastCompletedLoadWrites, gLastCompletedLoadPdfOpen,
                           gLastCompletedLoadBooks, gLastCompletedLoadSkippedEmbedded ? 1 : 0);
}

int LoadModelThreadStartCount() {
    return AtomicIntGet(&gLoadModelThreadStarts);
}

int LoadModelThreadCompleteCount() {
    return AtomicIntGet(&gLoadModelThreadCompletes);
}

// Model-load-scoped record cache (chunk 30).
//
// One warm LoadModelThread run previously read each book's record from disk
// twice: once for AdoptEmbeddedRecordFields, then again inside
// LibrarySidecarWriteMetadata during the SyncEmbeddedRecords sweep. With a
// 231-book library that is ~402 extra fz_open_document + LZMA2 decode
// round-trips per load — measurable seconds. The fix is a path-keyed cache
// that lives for exactly one LoadModelThread run:
//
//   adopt loop:
//     LibrarySidecarReadRecord(bookPath, rec)
//     AdoptEmbeddedRecordFields(..., rec)
//     cache.Put(bookPath, rec)         // remember what we just read
//
//   sync sweep (SyncEmbeddedRecords, in the same thread):
//     cached = cache.Find(bookPath)
//     LibrarySidecarWriteMetadataWithRec(bookPath, cached, ...)
//       if (write actually happened)
//         cache.Invalidate(bookPath)   // our cached copy is now stale
//
// Lifetime is the body of LoadModelThread; the destructor deletes every
// entry, and each BookBlobRecord frees the StrVec pages it owns. Entries
// are heap-allocated so that a Vec growth cannot move a record that a
// caller is still holding a pointer to. Lookup is O(N) — N is the book count, in
// the low hundreds at most, and SyncEmbeddedRecords already walks the books
// in index order, so we keep entries in insert order to avoid reshuffling.
//
// The cache is keyed on book path (not book id) by design. Two different
// book ids can point at the same file (e.g. after a rename and a rescan
// that have not yet reconciled), but the file on disk has exactly one
// record. The path is the right identity for the question "what is on
// disk for this file right now".
struct LoadRecordCache {
    struct Entry {
        Str path;
        BookBlobRecord rec;
    };
    Vec<Entry*> entries;

    ~LoadRecordCache() {
        for (int i = 0; i < entries.len; i++) {
            str::Free(entries[i]->path);
            delete entries[i];
        }
    }

    const BookBlobRecord* Find(Str bookPath) {
        for (int i = 0; i < entries.len; i++) {
            if (str::Eq(entries[i]->path, bookPath)) {
                return &entries[i]->rec;
            }
        }
        return nullptr;
    }

    // Store a record by path. BookBlobRecordClone gives the entry its own
    // StrVec and re-points every string at it, so the cached record stays
    // readable after the caller's record is destroyed at the end of the
    // adopt loop iteration. The function intentionally does not check for
    // an existing entry: the same book should only be Put() at most once
    // per model load.
    void Put(Str bookPath, const BookBlobRecord& rec) {
        Entry* e = new Entry();
        e->path = str::Dup(bookPath);
        BookBlobRecordClone(rec, e->rec);
        entries.Append(e);
    }

    // Drop the cached record for bookPath because the file on disk was just
    // rewritten and our copy no longer matches reality. The next caller
    // will re-read from disk on demand.
    void Invalidate(Str bookPath) {
        for (int i = 0; i < entries.len; i++) {
            if (str::Eq(entries[i]->path, bookPath)) {
                str::Free(entries[i]->path);
                delete entries[i];
                entries.RemoveAt(i);
                return;
            }
        }
    }
};

static void SyncEmbeddedRecords(LoadRecordCache* recordCache = nullptr);
static void SaveModelToStore(const LibModel* m);

static void EnterLib() {
    if (!gLockReady) {
        InitializeCriticalSection(&gLock);
        gLockReady = true;
    }
    EnterCriticalSection(&gLock);
}

static void LeaveLib() {
    LeaveCriticalSection(&gLock);
}

bool LibraryHomeEnabled() {
    return gGlobalPrefs && gGlobalPrefs->audiobook.libraryHome;
}

void SetLibraryHomeEnabled(bool enabled) {
    if (!gGlobalPrefs) {
        return;
    }
    gGlobalPrefs->audiobook.libraryHome = enabled;
}

bool LibraryHasBooks() {
    return gModel.loaded && gModel.nBooks > 0;
}

int LibraryServicePort() {
    if (gPort > 0) {
        return gPort;
    }
    int p = gGlobalPrefs ? gGlobalPrefs->audiobook.libraryPort : 0;
    gPort = (p > 0) ? p : kLibraryServicePortDefault;
    return gPort;
}

static void Repaint() {
    if (gNotifyHwnd && IsWindow(gNotifyHwnd)) {
        InvalidateRect(gNotifyHwnd, nullptr, FALSE);
    }
}

static void CALLBACK ScanAnimationTimerProc(HWND hwnd, UINT, UINT_PTR timerId, DWORD) {
    EnterLib();
    bool scanning = gModel.scanning;
    LeaveLib();
    if (!scanning) {
        KillTimer(hwnd, timerId);
        if (gScanAnimationTimer == timerId) {
            gScanAnimationTimer = 0;
        }
        return;
    }
    InvalidateRect(hwnd, nullptr, FALSE);
}

static void EnsureScanAnimationTimer(HWND hwnd) {
    if (gScanAnimationTimer == 0) {
        gScanAnimationTimer = SetTimer(hwnd, 0, 33, ScanAnimationTimerProc);
    }
}

static TempStr UrlEncodeTemp(Str s) {
    str::Builder b;
    for (int i = 0; i < len(s); i++) {
        u8 c = (u8)s.s[i];
        bool safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' ||
                    c == '_' || c == '.' || c == '~';
        if (safe) {
            char one[2] = {(char)c, 0};
            b.Append(Str(one, 1));
        } else {
            b.Append(fmt("%%%02X", (int)c));
        }
    }
    return str::DupTemp(ToStr(b));
}

static bool ServiceGet(Str path, HttpRsp* rsp) {
    TempStr url = fmt("http://127.0.0.1:%d%s", LibraryServicePort(), path);
    if (!HttpGet(Str(url), rsp)) {
        return false;
    }
    return IsHttpRspOk(rsp);
}

static TempStr ServiceGetTextTemp(Str path) {
    HttpRsp rsp;
    if (!ServiceGet(path, &rsp)) {
        return nullptr;
    }
    return str::DupTemp(ToStr(rsp.data));
}

static bool ServicePost(const char* path, Str body) {
    str::Builder hdrs;
    hdrs.Append("Content-Type: application/json\r\n");
    str::Builder b;
    if (len(body) > 0) {
        b.Append(body);
    }
    int port = LibraryServicePort();
    logf("ServicePost: host=%s port=%d path=%s bodyLen=%d body=%s\n", StrL("127.0.0.1"), port, Str(path), len(body),
         body);
    bool ok = HttpPost(StrL("127.0.0.1"), port, Str(path), &hdrs, &b);
    logf("ServicePost: result=%d\n", (int)ok);
    return ok;
}

static int IndexIn(Str path, const char* prefix) {
    if (!str::StartsWith(path, Str(prefix))) {
        return -1;
    }
    const char* s = path.s + strlen(prefix);
    if (*s != '[') {
        return -1;
    }
    return atoi(s + 1);
}

static bool IsTrue(Str v) {
    return str::Eq(v, StrL("true"));
}

struct LibraryParser : JsonVisitor {
    LibModel* m;

    explicit LibraryParser(LibModel* model) : m(model) {}

    bool Visit(Str path, Str value, json::Type type) override {
        if (type == json::Type::Null) {
            return true;
        }
        int bi = IndexIn(path, "/books");
        if (bi >= 0 && bi < kMaxBooks) {
            if (bi + 1 > m->nBooks) {
                m->nBooks = bi + 1;
            }
            LibBook& b = m->books[bi];
            const char* pulled = strstr(path.s, "/out_of[");
            if (pulled) {
                int oi = atoi(pulled + 8);
                if (oi < 0 || oi >= kMaxOutOf) {
                    return true;
                }
                if (oi + 1 > b.nOutOf) {
                    b.nOutOf = oi + 1;
                }
                if (str::EndsWith(path, StrL("/key"))) {
                    str::ReplaceWithCopy(&b.outOf[oi].key, value);
                } else if (str::EndsWith(path, StrL("/name"))) {
                    str::ReplaceWithCopy(&b.outOf[oi].name, value);
                }
                return true;
            }
            if (str::EndsWith(path, StrL("/id"))) {
                str::ReplaceWithCopy(&b.id, value);
            } else if (str::EndsWith(path, StrL("/title"))) {
                str::ReplaceWithCopy(&b.title, value);
            } else if (str::EndsWith(path, StrL("/title_source"))) {
                str::ReplaceWithCopy(&b.titleSource, value);
            } else if (str::EndsWith(path, StrL("/author"))) {
                str::ReplaceWithCopy(&b.author, value);
            } else if (str::EndsWith(path, StrL("/author_source"))) {
                str::ReplaceWithCopy(&b.authorSource, value);
            } else if (str::EndsWith(path, StrL("/year_source"))) {
                str::ReplaceWithCopy(&b.yearSource, value);
            } else if (str::EndsWith(path, StrL("/series"))) {
                str::ReplaceWithCopy(&b.series, value);
            } else if (str::EndsWith(path, StrL("/series_source"))) {
                str::ReplaceWithCopy(&b.seriesSource, value);
            } else if (str::EndsWith(path, StrL("/series_keys"))) {
                str::ReplaceWithCopy(&b.keys, value);
            } else if (str::EndsWith(path, StrL("/series_key"))) {
                str::ReplaceWithCopy(&b.seriesKey, value);
            } else if (str::EndsWith(path, StrL("/genre"))) {
                str::ReplaceWithCopy(&b.genre, value);
            } else if (str::EndsWith(path, StrL("/subgenre"))) {
                str::ReplaceWithCopy(&b.subgenre, value);
            } else if (str::EndsWith(path, StrL("/path"))) {
                str::ReplaceWithCopy(&b.path, value);
            } else if (str::EndsWith(path, StrL("/file"))) {
                str::ReplaceWithCopy(&b.file, value);
            } else if (str::EndsWith(path, StrL("/ext"))) {
                str::ReplaceWithCopy(&b.ext, value);
            } else if (str::EndsWith(path, StrL("/wiki"))) {
                str::ReplaceWithCopy(&b.wiki, value);
            } else if (str::EndsWith(path, StrL("/pages"))) {
                b.pages = atoi(value.s);
            } else if (str::EndsWith(path, StrL("/year"))) {
                b.year = atoi(value.s);
            } else if (str::EndsWith(path, StrL("/booknlp"))) {
                b.booknlp = IsTrue(value);
            } else if (str::EndsWith(path, StrL("/cover"))) {
                b.cover = IsTrue(value);
            } else if (str::EndsWith(path, StrL("/content_hash"))) {
                str::ReplaceWithCopy(&b.contentHash, value);
            } else if (IndexIn(path, "/books") >= 0 && str::Contains(path, "/volumes[0]")) {
                b.volume = atoi(value.s);
            }
            return true;
        }
        int si = IndexIn(path, "/series");
        if (si >= 0 && si < kMaxSeries) {
            const char* field = strchr(path.s, ']');
            if (!field || field[1] != '/' || strchr(field + 1, '[')) {
                return true;
            }
            if (si + 1 > m->nSeries) {
                m->nSeries = si + 1;
            }
            LibSeries& s = m->series[si];
            if (str::EndsWith(path, StrL("/name"))) {
                str::ReplaceWithCopy(&s.name, value);
            } else if (str::EndsWith(path, StrL("/key"))) {
                str::ReplaceWithCopy(&s.key, value);
            } else if (str::EndsWith(path, StrL("/depth"))) {
                s.depth = atoi(value.s);
            } else if (str::EndsWith(path, StrL("/author"))) {
                str::ReplaceWithCopy(&s.author, value);
            } else if (str::EndsWith(path, StrL("/parent"))) {
                str::ReplaceWithCopy(&s.parent, value);
            } else if (str::EndsWith(path, StrL("/wiki"))) {
                str::ReplaceWithCopy(&s.wiki, value);
            } else if (str::EndsWith(path, StrL("/genre"))) {
                str::ReplaceWithCopy(&s.genre, value);
            } else if (str::EndsWith(path, StrL("/subgenre"))) {
                str::ReplaceWithCopy(&s.sub, value);
            } else if (str::EndsWith(path, StrL("/head"))) {
                str::ReplaceWithCopy(&s.head, value);
            } else if (str::EndsWith(path, StrL("/subhead"))) {
                str::ReplaceWithCopy(&s.subhead, value);
            } else if (str::EndsWith(path, StrL("/kind"))) {
                str::ReplaceWithCopy(&s.kind, value);
            } else if (str::EndsWith(path, StrL("/guessed"))) {
                str::ReplaceWithCopy(&s.guessed, value);
            } else if (str::EndsWith(path, StrL("/parent_source"))) {
                str::ReplaceWithCopy(&s.parentSource, value);
            } else if (str::EndsWith(path, StrL("/books"))) {
                s.books = atoi(value.s);
            } else if (str::EndsWith(path, StrL("/booknlp"))) {
                s.booknlp = atoi(value.s);
            } else if (str::EndsWith(path, StrL("/facts"))) {
                s.facts = atoi(value.s);
            }
            return true;
        }
        if (str::Eq(path, StrL("/total"))) {
            m->total = atoi(value.s);
        } else if (str::Eq(path, StrL("/status/scope_current"))) {
            m->scopeCurrent = IsTrue(value);
        } else if (str::Eq(path, StrL("/status/resume_pending"))) {
            m->resumePending = IsTrue(value);
        } else if (str::Eq(path, StrL("/status/documents"))) {
            m->documents = atoi(value.s);
        } else if (str::Eq(path, StrL("/status/ignored"))) {
            m->ignored = atoi(value.s);
        } else if (gNativeScanning) {
            return true;
        } else if (str::Eq(path, StrL("/status/scanning"))) {
            m->scanning = IsTrue(value);
        } else if (str::Eq(path, StrL("/status/scan_done"))) {
            m->scanDone = atoi(value.s);
        } else if (str::Eq(path, StrL("/status/scan_total"))) {
            m->scanTotal = atoi(value.s);
        }
        return true;
    }
};

struct DeskParser : JsonVisitor {
    LibDesk* d;

    explicit DeskParser(LibDesk* desk) : d(desk) {}

    bool Visit(Str path, Str value, json::Type type) override {
        if (type == json::Type::Null) {
            return true;
        }
        int i = IndexIn(path, "/files");
        if (i >= 0 && i < kMaxDeskFiles) {
            if (i + 1 > d->nFiles) {
                d->nFiles = i + 1;
            }
            LibDeskFile& f = d->files[i];
            if (str::EndsWith(path, StrL("/id"))) {
                str::ReplaceWithCopy(&f.id, value);
            } else if (str::EndsWith(path, StrL("/title"))) {
                str::ReplaceWithCopy(&f.title, value);
            } else if (str::EndsWith(path, StrL("/file"))) {
                str::ReplaceWithCopy(&f.file, value);
            } else if (str::EndsWith(path, StrL("/folder"))) {
                str::ReplaceWithCopy(&f.folder, value);
            } else if (str::EndsWith(path, StrL("/path"))) {
                str::ReplaceWithCopy(&f.path, value);
            } else if (str::EndsWith(path, StrL("/ext"))) {
                str::ReplaceWithCopy(&f.ext, value);
            } else if (str::EndsWith(path, StrL("/pages"))) {
                f.pages = atoi(value.s);
            } else if (str::EndsWith(path, StrL("/size"))) {
                f.size = (i64)_atoi64(value.s);
            }
            return true;
        }
        if (str::Eq(path, StrL("/total"))) {
            d->total = atoi(value.s);
        } else if (str::Eq(path, StrL("/status/documents"))) {
            gModel.documents = atoi(value.s);
        } else if (str::Eq(path, StrL("/status/ignored"))) {
            gModel.ignored = atoi(value.s);
        }
        return true;
    }
};

struct PartitionParser : JsonVisitor {
    bool Visit(Str path, Str value, json::Type type) override {
        if (type == json::Type::Null) {
            return true;
        }
        int i = IndexIn(path, "/partitions");
        if (i < 0 || i >= kMaxPartitions) {
            return true;
        }
        if (i + 1 > gNPartitions) {
            gNPartitions = i + 1;
        }
        LibPartition& p = gPartitions[i];
        if (str::EndsWith(path, StrL("/key"))) {
            str::ReplaceWithCopy(&p.key, value);
        } else if (str::EndsWith(path, StrL("/name"))) {
            str::ReplaceWithCopy(&p.name, value);
        } else if (str::EndsWith(path, StrL("/parent"))) {
            str::ReplaceWithCopy(&p.parent, value);
        } else if (str::EndsWith(path, StrL("/books"))) {
            p.books = atoi(value.s);
        }
        return true;
    }
};

static void FreePartitions() {
    for (int i = 0; i < gNPartitions; i++) {
        str::Free(gPartitions[i].key);
        str::Free(gPartitions[i].name);
        str::Free(gPartitions[i].parent);
        gPartitions[i] = LibPartition{};
    }
    gNPartitions = 0;
}

static void RankPartitions() {
    for (int i = 0; i < gNPartitions; i++) {
        LibPartition& p = gPartitions[i];
        p.depth = 0;
        Str up = p.parent;
        for (int step = 0; step < 8 && len(up) > 0; step++) {
            bool found = false;
            for (int j = 0; j < gNPartitions; j++) {
                if (str::Eq(gPartitions[j].key, up)) {
                    p.depth++;
                    up = gPartitions[j].parent;
                    found = true;
                    break;
                }
            }
            if (!found) {
                break;
            }
        }
    }
}

static LibPartition* PartitionByKey(Str key) {
    if (len(key) == 0) {
        return nullptr;
    }
    for (int i = 0; i < gNPartitions; i++) {
        if (str::Eq(gPartitions[i].key, key)) {
            return &gPartitions[i];
        }
    }
    return nullptr;
}

struct DetailParser : JsonVisitor {
    LibDetail* d;

    explicit DetailParser(LibDetail* det) : d(det) {}

    bool Visit(Str path, Str value, json::Type type) override {
        if (type == json::Type::Null) {
            return true;
        }
        if (str::Eq(path, StrL("/title"))) {
            str::ReplaceWithCopy(&d->title, value);
        } else if (str::Eq(path, StrL("/author"))) {
            str::ReplaceWithCopy(&d->author, value);
        } else if (str::Eq(path, StrL("/series"))) {
            str::ReplaceWithCopy(&d->series, value);
        } else if (str::Eq(path, StrL("/wiki"))) {
            str::ReplaceWithCopy(&d->wiki, value);
        } else if (str::Eq(path, StrL("/path"))) {
            str::ReplaceWithCopy(&d->path, value);
        } else if (str::Eq(path, StrL("/description"))) {
            str::ReplaceWithCopy(&d->description, value);
        } else if (str::Eq(path, StrL("/year"))) {
            d->year = atoi(value.s);
        } else if (str::Eq(path, StrL("/pages"))) {
            d->pages = atoi(value.s);
        } else if (str::Eq(path, StrL("/booknlp"))) {
            d->booknlp = IsTrue(value);
        } else if (str::Eq(path, StrL("/ext"))) {
            str::ReplaceWithCopy(&d->ext, value);
        } else if (str::Eq(path, StrL("/genre"))) {
            str::ReplaceWithCopy(&d->genre, value);
        } else if (str::Eq(path, StrL("/subgenre"))) {
            str::ReplaceWithCopy(&d->sub, value);
        } else if (str::Eq(path, StrL("/meta/rating"))) {
            d->rating = atof(value.s);
        } else if (str::Eq(path, StrL("/meta/rating_count"))) {
            d->ratingCount = atoi(value.s);
        } else if (str::Eq(path, StrL("/meta/rating_source"))) {
            str::ReplaceWithCopy(&d->ratingSource, value);
        } else if (IndexIn(path, "/subjects") >= 0) {
            str::Builder b;
            if (len(d->subjects) > 0) {
                b.Append(d->subjects);
                b.Append(" \xc2\xb7 ");
            }
            b.Append(value);
            str::ReplaceWithCopy(&d->subjects, ToStr(b));
        } else {
            int pi = IndexIn(path, "/wiki_summary/people");
            if (pi >= 0 && pi < kMaxPeople) {
                if (pi + 1 > d->nPeople) {
                    d->nPeople = pi + 1;
                }
                str::ReplaceWithCopy(&d->people[pi], value);
                return true;
            }
            int li = IndexIn(path, "/wiki_summary/places");
            if (li >= 0 && li < kMaxPeople) {
                if (li + 1 > d->nPlaces) {
                    d->nPlaces = li + 1;
                }
                str::ReplaceWithCopy(&d->places[li], value);
                return true;
            }
            int ti = IndexIn(path, "/wiki_summary/topics");
            if (ti >= 0 && ti < kMaxPeople) {
                if (ti + 1 > d->nTopics) {
                    d->nTopics = ti + 1;
                }
                str::ReplaceWithCopy(&d->topics[ti], value);
                return true;
            }
        }
        return true;
    }
};

struct ScreenParser : JsonVisitor {
    LibDetail* d;

    explicit ScreenParser(LibDetail* det) : d(det) {}

    bool Visit(Str path, Str value, json::Type type) override {
        if (type == json::Type::Null) {
            return true;
        }
        int i = IndexIn(path, "/screen");
        if (i < 0 || i >= kMaxScreen) {
            return true;
        }
        if (i + 1 > d->nScreen) {
            d->nScreen = i + 1;
        }
        LibScreen& s = d->screen[i];
        if (str::EndsWith(path, StrL("/title"))) {
            str::ReplaceWithCopy(&s.title, value);
        } else if (str::EndsWith(path, StrL("/kind"))) {
            str::ReplaceWithCopy(&s.kind, value);
        } else if (str::EndsWith(path, StrL("/poster"))) {
            str::ReplaceWithCopy(&s.poster, value);
        } else if (str::EndsWith(path, StrL("/imdb_id"))) {
            str::ReplaceWithCopy(&s.imdbId, value);
        } else if (str::EndsWith(path, StrL("/via"))) {
            str::ReplaceWithCopy(&s.via, value);
        } else if (str::EndsWith(path, StrL("/year"))) {
            s.year = atoi(value.s);
        } else if (str::Contains(path, "/stars[")) {
            str::Builder b;
            if (len(s.stars) > 0) {
                b.Append(s.stars);
                b.Append(", ");
            }
            b.Append(value);
            str::ReplaceWithCopy(&s.stars, ToStr(b));
        }
        return true;
    }
};

struct FamilyParser : JsonVisitor {
    LibDetail* d;
    Str want;

    FamilyParser(LibDetail* det, Str name) : d(det), want(name) {}

    bool Visit(Str path, Str value, json::Type type) override {
        if (type == json::Type::Null) {
            return true;
        }
        if (!str::StartsWith(path, StrL("/nodes/"))) {
            return true;
        }
        const char* rest = path.s + 7;
        const char* mark = strstr(rest, "/relations/");
        if (!mark) {
            return true;
        }
        int whoLen = (int)(mark - rest);
        if (!str::EqI(Str(rest, whoLen), want)) {
            return true;
        }
        const char* after = mark + 11;
        const char* bracket = strchr(after, '[');
        if (!bracket) {
            return true;
        }
        if (!str::EndsWith(path, StrL("/name")) && !str::EndsWith(path, StrL("/mentions"))) {
            return true;
        }
        int relLen = (int)(bracket - after);
        int idx = atoi(bracket + 1);
        Str rel(after, relLen);
        int slot = -1;
        for (int i = 0; i < d->nFamily; i++) {
            if (d->family[i].idx == idx && str::Eq(d->family[i].relation, rel)) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            if (d->nFamily >= kMaxFamily) {
                return true;
            }
            slot = d->nFamily++;
            str::ReplaceWithCopy(&d->family[slot].relation, rel);
            d->family[slot].idx = idx;
        }
        if (str::EndsWith(path, StrL("/name"))) {
            str::ReplaceWithCopy(&d->family[slot].name, value);
        } else {
            d->family[slot].mentions = atoi(value.s);
        }
        return true;
    }
};

static int ChapterDepthOf(Str path) {
    int depth = 0;
    const char* s = path.s;
    const char* end = path.s + len(path);
    while (s < end) {
        const char* hit = strstr(s, "/children[");
        if (!hit || hit >= end) {
            break;
        }
        depth++;
        s = hit + 10;
    }
    return depth;
}

struct ChapterParser : JsonVisitor {
    LibDetail* d;

    explicit ChapterParser(LibDetail* det) : d(det) {}

    bool Visit(Str path, Str value, json::Type type) override {
        if (type == json::Type::Null) {
            return true;
        }
        if (!str::StartsWith(path, StrL("/chapters["))) {
            return true;
        }
        bool isTitle = str::EndsWith(path, StrL("/title"));
        bool isPage = str::EndsWith(path, StrL("/page"));
        if (!isTitle && !isPage) {
            return true;
        }
        if (isTitle) {
            if (d->nChapters >= kMaxChapters) {
                return true;
            }
            LibChapter& c = d->chapters[d->nChapters];
            c.depth = ChapterDepthOf(path);
            c.parent = -1;
            for (int i = d->nChapters - 1; i >= 0; i--) {
                if (d->chapters[i].depth < c.depth) {
                    c.parent = i;
                    d->chapters[i].kids++;
                    break;
                }
            }
            str::ReplaceWithCopy(&c.title, value);
            d->nChapters++;
        } else if (d->nChapters > 0) {
            d->chapters[d->nChapters - 1].page = atoi(value.s);
        }
        return true;
    }
};

struct KnowsParser : JsonVisitor {
    LibDetail* d;

    explicit KnowsParser(LibDetail* det) : d(det) {}

    bool Visit(Str path, Str value, json::Type type) override {
        if (type == json::Type::Null) {
            return true;
        }
        int i = IndexIn(path, "/people");
        if (i < 0 || i >= kMaxKnowers) {
            return true;
        }
        if (i + 1 > d->nKnowers) {
            d->nKnowers = i + 1;
        }
        LibKnower& k = d->knowers[i];
        if (str::EndsWith(path, StrL("/name"))) {
            str::ReplaceWithCopy(&k.name, value);
        } else if (str::EndsWith(path, StrL("/mentions"))) {
            k.mentions = atoi(value.s);
        } else if (str::Contains(path, "/evidence[0]/")) {
            if (str::EndsWith(path, StrL("/book"))) {
                str::ReplaceWithCopy(&k.book, value);
            } else if (str::EndsWith(path, StrL("/page"))) {
                k.page = atoi(value.s);
            }
        }
        return true;
    }
};

static TempStr PrettyRelationTemp(Str rel) {
    TempStr s = str::DupTemp(rel);
    for (int i = 0; i < len(s); i++) {
        if (s.s[i] == '_') {
            s.s[i] = ' ';
        }
    }
    return s;
}

struct PersonParser : JsonVisitor {
    LibDetail* d;
    int nTrait = 0;
    int nNot = 0;
    int nSpeech = 0;
    int nVoice = 0;
    int nPlace = 0;
    int nKnow = 0;
    int nKin = 0;

    PersonParser(LibDetail* det) : d(det) {}

    static void Add(Str* dst, Str value, int* n, int maxN) {
        if (*n >= maxN || len(value) == 0) {
            return;
        }
        if (*n == 0) {
            str::ReplaceWithCopy(dst, value);
        } else {
            str::ReplaceWithCopy(dst, fmt("%s \xc2\xb7 %s", *dst, value));
        }
        *n = *n + 1;
    }

    bool Visit(Str path, Str value, json::Type type) override {
        if (type == json::Type::Null) {
            return true;
        }
        if (str::StartsWith(path, StrL("/books["))) {
            d->personBooks++;
            return true;
        }
        if (str::StartsWith(path, StrL("/description/describes[0]/evidence[0]/"))) {
            if (str::EndsWith(path, StrL("/text"))) {
                str::ReplaceWithCopy(&d->personQuote, value);
            } else if (str::EndsWith(path, StrL("/book"))) {
                str::ReplaceWithCopy(&d->personQuoteBook, value);
            } else if (str::EndsWith(path, StrL("/page"))) {
                d->personQuotePage = atoi(value.s);
            }
            return true;
        }
        if (!str::EndsWith(path, StrL("/value"))) {
            return true;
        }
        if (str::StartsWith(path, StrL("/description/describes["))) {
            Add(&d->personTraits, value, &nTrait, 14);
        } else if (str::StartsWith(path, StrL("/description/describes_not["))) {
            Add(&d->personNot, value, &nNot, 8);
        } else if (str::StartsWith(path, StrL("/voice/speaks["))) {
            Add(&d->personSpeech, value, &nSpeech, 12);
        } else if (str::StartsWith(path, StrL("/voice/voice["))) {
            Add(&d->personVoice, value, &nVoice, 8);
        } else if (str::StartsWith(path, StrL("/places/"))) {
            Add(&d->personPlaces, value, &nPlace, 12);
        } else if (str::StartsWith(path, StrL("/knows/knows["))) {
            Add(&d->personKnows, value, &nKnow, 14);
        } else if (str::StartsWith(path, StrL("/family/"))) {
            const char* rel = path.s + 8;
            const char* bracket = strchr(rel, '[');
            if (bracket && atoi(bracket + 1) == 0) {
                TempStr pretty = PrettyRelationTemp(Str(rel, (int)(bracket - rel)));
                Add(&d->personKin, fmt("%s %s", Str(pretty), value), &nKin, 12);
            }
        }
        return true;
    }
};

static void FreeSeriesRow(LibSeries& s) {
    str::Free(s.key);
    str::Free(s.name);
    str::Free(s.author);
    str::Free(s.parent);
    str::Free(s.wiki);
    str::Free(s.genre);
    str::Free(s.sub);
    str::Free(s.head);
    str::Free(s.subhead);
    str::Free(s.kind);
    str::Free(s.guessed);
    str::Free(s.parentSource);
    s = LibSeries{};
}

static void FreeModel(LibModel* m) {
    for (int i = 0; i < m->nBooks; i++) {
        LibBook& b = m->books[i];
        str::Free(b.id);
        str::Free(b.title);
        str::Free(b.author);
        str::Free(b.series);
        str::Free(b.seriesParent);
        str::Free(b.seriesKey);
        str::Free(b.keys);
        str::Free(b.genre);
        str::Free(b.subgenre);
        str::Free(b.tags);
        for (int j = 0; j < kMaxOutOf; j++) {
            str::Free(b.outOf[j].key);
            str::Free(b.outOf[j].name);
        }
        str::Free(b.path);
        str::Free(b.ext);
        str::Free(b.wiki);
        b = LibBook{};
    }
    m->nBooks = 0;
    for (int i = 0; i < m->nSeries; i++) {
        FreeSeriesRow(m->series[i]);
    }
    m->nSeries = 0;
    for (int i = 0; i < m->nRoamed; i++) {
        str::Free(m->roamed[i].mark);
        m->roamed[i] = {};
    }
    m->nRoamed = 0;
}

static TempStr SeriesKeyForNameTemp(Str name) {
    str::Builder key;
    key.Append(StrL("series:"));
    const char* s = name.s;
    for (int i = 0; i < len(name) && s; i++) {
        char c = s[i];
        if (c >= 'A' && c <= 'Z') {
            c = (char)(c - 'A' + 'a');
        }
        if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
            key.AppendChar(c);
        }
    }
    return str::DupTemp(ToStr(key));
}

static int SplitKeys(Str keys, Str* out, int maxOut) {
    int n = 0;
    const char* p = keys.s;
    const char* end = p ? p + len(keys) : nullptr;
    while (p && p < end && n < maxOut) {
        while (p < end && *p == '|') {
            p++;
        }
        const char* start = p;
        while (p < end && *p != '|') {
            p++;
        }
        if (p > start) {
            out[n++] = str::DupTemp(Str(start, (int)(p - start)));
        }
    }
    return n;
}

static bool InChain(Str* chain, int n, Str key) {
    for (int i = 0; i < n; i++) {
        if (str::Eq(chain[i], key)) {
            return true;
        }
    }
    return false;
}

static int SeriesChain(Str key, Str* out, int maxOut) {
    int n = 0;
    Str cur = key;
    while (len(cur) > 0 && n < maxOut) {
        if (InChain(out, n, cur)) {
            break;
        }
        out[n++] = cur;
        LibSeries* row = RowByKey(cur);
        if (!row || len(row->parent) == 0) {
            break;
        }
        cur = row->parent;
    }
    for (int i = 0; i < n / 2; i++) {
        Str swap = out[i];
        out[i] = out[n - 1 - i];
        out[n - 1 - i] = swap;
    }
    return n;
}

static TempStr KeysForChainTemp(Str* chain, int n) {
    str::Builder keys;
    keys.Append(StrL("|"));
    for (int i = 0; i < n; i++) {
        keys.Append(chain[i]);
        keys.Append(StrL("|"));
    }
    return str::DupTemp(ToStr(keys));
}

struct LibRowPlace {
    Str key;
    Str parent;
};

static LibRowPlace gRowPlaces[kMaxSeries];
static int gnRowPlaces = 0;

static void RememberRowPlace(Str key, Str parent) {
    for (int i = 0; i < gnRowPlaces; i++) {
        if (str::Eq(gRowPlaces[i].key, key)) {
            str::ReplaceWithCopy(&gRowPlaces[i].parent, parent);
            return;
        }
    }
    if (gnRowPlaces >= kMaxSeries) {
        return;
    }
    gRowPlaces[gnRowPlaces].key = str::Dup(key);
    gRowPlaces[gnRowPlaces].parent = str::Dup(parent);
    gnRowPlaces++;
}

static LibSeries* RememberedParent(Str key) {
    for (int i = 0; i < gnRowPlaces; i++) {
        if (str::Eq(gRowPlaces[i].key, key)) {
            return RowByKey(gRowPlaces[i].parent);
        }
    }
    return nullptr;
}

static LibSeries* FormRowFor(const LibBook& b) {
    if (len(b.subgenre) == 0) {
        return nullptr;
    }
    for (int i = 0; i < gModel.nSeries; i++) {
        LibSeries& s = gModel.series[i];
        if (str::EqI(s.kind, StrL("form")) && str::EqI(s.sub, b.subgenre)) {
            return &s;
        }
    }
    return nullptr;
}

static bool IsActualSeriesRow(const LibSeries& s) {
    return str::StartsWith(s.key, StrL("series:"));
}

static LibSeries* SeriesRowByName(Str name) {
    LibSeries* found = nullptr;
    for (int i = 0; i < gModel.nSeries; i++) {
        LibSeries& s = gModel.series[i];
        if (!IsActualSeriesRow(s) || !str::EqI(s.name, name)) {
            continue;
        }
        if (found) {
            return nullptr;
        }
        found = &s;
    }
    return found;
}

static LibSeries* AddSeriesRow(Str key, Str name, LibSeries* under, LibSeries* after) {
    if (gModel.nSeries >= kMaxSeries) {
        return nullptr;
    }
    int at = gModel.nSeries;
    if (after) {
        at = (int)(after - gModel.series) + 1;
    } else if (under) {
        at = (int)(under - gModel.series) + 1;
    }
    Str parent{};
    Str genre{};
    Str sub{};
    int depth = 0;
    if (under) {
        parent = under->key;
        genre = under->genre;
        sub = under->sub;
        depth = under->depth + 1;
    }
    for (int i = gModel.nSeries; i > at; i--) {
        gModel.series[i] = gModel.series[i - 1];
    }
    gModel.nSeries++;
    LibSeries& row = gModel.series[at];
    row = LibSeries{};
    row.key = str::Dup(key);
    row.name = str::Dup(name);
    row.parent = str::Dup(parent);
    row.genre = str::Dup(genre);
    row.sub = str::Dup(sub);
    row.kind = str::Dup(StrL("series"));
    row.depth = depth;
    return &row;
}

static void RemoveSeriesRowAt(int at) {
    if (at < 0 || at >= gModel.nSeries) {
        return;
    }
    LibSeries& row = gModel.series[at];
    if (at + 1 < gModel.nSeries) {
        LibSeries& next = gModel.series[at + 1];
        if (len(row.head) > 0 && len(next.head) == 0) {
            next.head = row.head;
            row.head = {};
            if (len(next.subhead) == 0) {
                next.subhead = row.subhead;
                row.subhead = {};
            }
        }
    }
    FreeSeriesRow(row);
    for (int i = at; i + 1 < gModel.nSeries; i++) {
        gModel.series[i] = gModel.series[i + 1];
    }
    gModel.series[gModel.nSeries - 1] = LibSeries{};
    gModel.nSeries--;
}

static bool RebuildSeriesTree() {
    bool changed = false;
    Str followKey{};
    Str followName{};
    for (int i = 0; i < gModel.nBooks; i++) {
        LibBook& b = gModel.books[i];
        if (!str::EqI(b.seriesSource, StrL("user")) || len(b.series) == 0) {
            continue;
        }
        LibSeries* row = RowByKey(b.seriesKey);
        if (!row || !str::EqI(row->name, b.series)) {
            TempStr key = SeriesKeyForNameTemp(b.series);
            if (len(key) <= LenL("series:")) {
                continue;
            }
            LibSeries* basis = row;
            row = RowByKey(key);
            if (row && !IsActualSeriesRow(*row)) {
                row = nullptr;
            }
            if (!row) {
                row = SeriesRowByName(b.series);
            }
            if (!row) {
                LibSeries* under = RememberedParent(key);
                if (!under) {
                    under = basis ? RowByKey(basis->parent) : FormRowFor(b);
                }
                row = AddSeriesRow(key, b.series, under, basis);
                if (!row) {
                    continue;
                }
                changed = true;
            }
        }
        RememberRowPlace(row->key, row->parent);
        if (!str::Eq(b.seriesKey, row->key)) {
            if (len(gModel.filter) > 0 && str::Eq(gModel.filter, b.seriesKey)) {
                followKey = str::DupTemp(row->key);
                followName = str::DupTemp(row->name);
            }
            str::ReplaceWithCopy(&b.seriesKey, row->key);
            changed = true;
        }
    }
    int was[kMaxSeries];
    for (int i = 0; i < gModel.nSeries; i++) {
        LibSeries& s = gModel.series[i];
        Str chain[kSeriesTreeDepth];
        int n = SeriesChain(s.key, chain, dimofi(chain));
        int depth = n > 0 ? n - 1 : 0;
        if (s.depth != depth) {
            s.depth = depth;
            changed = true;
        }
        was[i] = s.books;
        s.books = 0;
    }
    for (int i = 0; i < gModel.nBooks; i++) {
        LibBook& b = gModel.books[i];
        if (len(b.seriesKey) > 0 && RowByKey(b.seriesKey)) {
            Str chain[kSeriesTreeDepth];
            int n = SeriesChain(b.seriesKey, chain, dimofi(chain));
            TempStr keys = KeysForChainTemp(chain, n);
            if (!str::Eq(b.keys, keys)) {
                str::ReplaceWithCopy(&b.keys, keys);
                changed = true;
            }
        }
        Str got[kSeriesTreeDepth * 2];
        int n = SplitKeys(b.keys, got, dimofi(got));
        for (int j = 0; j < n; j++) {
            if (InChain(got, j, got[j])) {
                continue;
            }
            for (int k = 0; k < gModel.nSeries; k++) {
                if (str::Eq(gModel.series[k].key, got[j])) {
                    gModel.series[k].books++;
                }
            }
        }
    }
    for (int i = 0; i < gModel.nSeries; i++) {
        if (was[i] != gModel.series[i].books) {
            changed = true;
        }
    }
    for (int i = gModel.nSeries - 1; i >= 0; i--) {
        if (gModel.series[i].books == 0) {
            RemoveSeriesRowAt(i);
            changed = true;
        }
    }
    if (len(gModel.filter) > 0 && !RowByKey(gModel.filter)) {
        if (len(followKey) > 0 && RowByKey(followKey)) {
            str::ReplaceWithCopy(&gModel.filter, followKey);
            str::ReplaceWithCopy(&gModel.filterName, followName);
        } else {
            str::FreePtr(&gModel.filter);
            str::FreePtr(&gModel.filterName);
        }
        changed = true;
    }
    return changed;
}

static void FreeDesk(LibDesk* d) {
    for (int i = 0; i < d->nFiles; i++) {
        LibDeskFile& f = d->files[i];
        str::Free(f.id);
        str::Free(f.title);
        str::Free(f.file);
        str::Free(f.folder);
        str::Free(f.path);
        str::Free(f.ext);
        f = LibDeskFile{};
    }
    d->nFiles = 0;
    d->total = 0;
    d->anchor = -1;
}

static void FreeDetail(LibDetail* d) {
    str::Free(d->id);
    str::Free(d->title);
    str::Free(d->author);
    str::Free(d->series);
    str::Free(d->wiki);
    str::Free(d->path);
    str::Free(d->description);
    str::Free(d->subjects);
    str::Free(d->person);
    str::Free(d->personTraits);
    str::Free(d->personNot);
    str::Free(d->personKin);
    str::Free(d->personSpeech);
    str::Free(d->personVoice);
    str::Free(d->personPlaces);
    str::Free(d->personKnows);
    str::Free(d->personQuote);
    str::Free(d->personQuoteBook);
    for (int i = 0; i < d->nPeople; i++) {
        str::Free(d->people[i]);
    }
    for (int i = 0; i < d->nPlaces; i++) {
        str::Free(d->places[i]);
    }
    for (int i = 0; i < d->nTopics; i++) {
        str::Free(d->topics[i]);
    }
    for (int i = 0; i < d->nFamily; i++) {
        str::Free(d->family[i].relation);
        str::Free(d->family[i].name);
    }
    for (int i = 0; i < d->nScreen; i++) {
        str::Free(d->screen[i].title);
        str::Free(d->screen[i].kind);
        str::Free(d->screen[i].poster);
        str::Free(d->screen[i].stars);
        str::Free(d->screen[i].via);
        str::Free(d->screen[i].imdbId);
    }
    for (int i = 0; i < d->nChapters; i++) {
        str::Free(d->chapters[i].title);
    }
    for (int i = 0; i < d->nKnowers; i++) {
        str::Free(d->knowers[i].name);
        str::Free(d->knowers[i].book);
    }
    str::Free(d->topic);
    str::Free(d->ext);
    str::Free(d->genre);
    str::Free(d->sub);
    str::Free(d->checksum);
    str::Free(d->mark);
    str::Free(d->ratingSource);
    LibTab keepTab = d->tab;
    *d = LibDetail{};
    d->tab = keepTab;
}

// chunk 31R: explicit load mode for LoadModelThread instead of the
// earlier vague `skipEmbeddedPass` flag. Full is the original behavior
// (load store, fetch catalogue, adopt embedded records, sync embedded
// records). CatalogueOnly is the new fast path used by hierarchy
// changes (Series -> Series, Series -> partition) that do not touch
// any book file on disk: fetch the catalogue, keep only the fields the
// service genuinely does NOT provide, and do NOT open any book file.
enum class LoadMode {
    // Load store, fetch catalogue, adopt embedded records, sync embedded
    // records. Used for the initial load, the rescan path, and any
    // caller that needs the full model including sidecar-only fields.
    Full,
    // Fetch /library from the service, merge, and skip both the adopt
    // pass (no sidecar reads) and the sync sweep (no sidecar writes).
    // Used for hierarchy changes that the service already applied:
    // a fresh catalogue response carries the new keys/seriesKey/etc.,
    // and the in-memory model is updated by the parser alone. The
    // only fields retained from the pre-rebuild model are the ones
    // that are genuinely sidecar-only (see SidecarOnlyFields in
    // LoadModelThread).
    CatalogueOnly,
};

struct LibJob {
    Str a;
    Str b;
    // c is used by the metadata-edit path to carry the bookId so the
    // targeted PersistBookMetadata() call can find the right book
    // without re-walking the model.
    Str c;
    int cleared = 0;
    LoadMode loadMode = LoadMode::Full;
};

static LibJob* NewJob(Str a, Str b = {}) {
    auto j = new LibJob();
    j->a = str::Dup(a);
    j->b = str::Dup(b);
    j->loadMode = LoadMode::Full;
    return j;
}

static LibJob* NewJob3(Str a, Str b, Str c, int cleared = 0) {
    auto j = new LibJob();
    j->a = str::Dup(a);
    j->b = str::Dup(b);
    j->c = str::Dup(c);
    j->cleared = cleared;
    j->loadMode = LoadMode::Full;
    return j;
}

// chunk 31R: hierarchy changes (Series -> Series, Series -> partition)
// post to the service and then ask LoadModelThread to refresh the
// catalogue from the service without re-reading any book file. The
// service response is authoritative for keys, seriesKey, series,
// seriesSource, genre, subgenre — those are never carried forward.
static LibJob* NewJobCatalogueOnly() {
    auto j = new LibJob();
    j->loadMode = LoadMode::CatalogueOnly;
    return j;
}

static void FreeJob(LibJob* j) {
    str::Free(j->a);
    str::Free(j->b);
    str::Free(j->c);
    delete j;
}

static Str LibrarySortOrder() {
    Str how = gGlobalPrefs ? gGlobalPrefs->audiobook.librarySort : Str{};
    if (len(how) == 0) {
        return StrL("alpha");
    }
    return how;
}

// The index the page draws from lives in SumatraLibrary.txt and the covers in
// an AppendStore beside it, so the page can be drawn before, or without, the
// service answering. The service is only what refreshes them.

static LibraryThumbs* gThumbs = nullptr;
static bool gThumbsTried = false;
static CRITICAL_SECTION gThumbsLock;
static bool gThumbsLockReady = false;

static void EnterThumbs() {
    if (!gThumbsLockReady) {
        InitializeCriticalSection(&gThumbsLock);
        gThumbsLockReady = true;
    }
    EnterCriticalSection(&gThumbsLock);
}

static void LeaveThumbs() {
    LeaveCriticalSection(&gThumbsLock);
}

static LibraryThumbs* ThumbsStore() {
    if (!gThumbsTried) {
        gThumbsTried = true;
        gThumbs = LibraryThumbsOpen(Str(GetAppDataDirTemp()));
    }
    return gThumbs;
}

static Str ThumbRead(Str bookId) {
    EnterThumbs();
    LibraryThumbs* thumbs = ThumbsStore();
    Str png = thumbs ? LibraryThumbsGet(thumbs, bookId) : Str{};
    LeaveThumbs();
    return png;
}

static void ThumbWrite(Str bookId, Str png) {
    EnterThumbs();
    LibraryThumbs* thumbs = ThumbsStore();
    if (thumbs) {
        LibraryThumbsPut(thumbs, bookId, png);
    }
    LeaveThumbs();
}

static TempStr LibraryStorePathTemp() {
    return GetPathInAppDataDirTemp(StrL(kLibraryStoreFileName));
}

static void CopyBookFromStore(LibBook& b, LibraryBook* src) {
    str::ReplaceWithCopy(&b.id, src->id);
    str::ReplaceWithCopy(&b.title, src->title);
    str::ReplaceWithCopy(&b.author, src->author);
    str::ReplaceWithCopy(&b.series, src->series);
    str::ReplaceWithCopy(&b.seriesParent, src->seriesParent);
    str::ReplaceWithCopy(&b.seriesKey, src->seriesKey);
    str::ReplaceWithCopy(&b.keys, src->keys);
    str::ReplaceWithCopy(&b.genre, src->genre);
    str::ReplaceWithCopy(&b.subgenre, src->subgenre);
    str::ReplaceWithCopy(&b.tags, src->tags);
    str::ReplaceWithCopy(&b.path, src->path);
    str::ReplaceWithCopy(&b.ext, src->ext);
    str::ReplaceWithCopy(&b.wiki, src->wiki);
    str::ReplaceWithCopy(&b.titleSource, src->titleSource);
    str::ReplaceWithCopy(&b.authorSource, src->authorSource);
    str::ReplaceWithCopy(&b.yearSource, src->yearSource);
    str::ReplaceWithCopy(&b.seriesSource, src->seriesSource);
    b.pages = src->pages;
    b.year = src->year;
    b.volume = src->volume;
    b.booknlp = src->bookNlp;
    b.cover = src->cover;
    b.nOutOf = 0;
    for (LibraryOutOf* o : *src->libraryOutOf) {
        if (b.nOutOf >= kMaxOutOf) {
            break;
        }
        str::ReplaceWithCopy(&b.outOf[b.nOutOf].key, o->key);
        str::ReplaceWithCopy(&b.outOf[b.nOutOf].name, o->name);
        b.nOutOf++;
    }
}

static void CopySeriesFromStore(LibSeries& s, LibrarySeries* src) {
    str::ReplaceWithCopy(&s.key, src->key);
    str::ReplaceWithCopy(&s.name, src->name);
    str::ReplaceWithCopy(&s.author, src->author);
    str::ReplaceWithCopy(&s.parent, src->parent);
    str::ReplaceWithCopy(&s.wiki, src->wiki);
    str::ReplaceWithCopy(&s.genre, src->genre);
    str::ReplaceWithCopy(&s.sub, src->sub);
    str::ReplaceWithCopy(&s.head, src->head);
    str::ReplaceWithCopy(&s.subhead, src->subhead);
    str::ReplaceWithCopy(&s.kind, src->kind);
    str::ReplaceWithCopy(&s.guessed, src->guessed);
    str::ReplaceWithCopy(&s.parentSource, src->parentSource);
    s.books = src->books;
    s.booknlp = src->bookNlp;
    s.facts = src->facts;
    s.depth = src->depth;
}

static LibraryRoamed* FindRoamed(LibModel* model, Str mark, bool create) {
    for (int i = 0; i < model->nRoamed; i++) {
        if (str::Eq(model->roamed[i].mark, mark)) {
            return &model->roamed[i];
        }
    }
    if (!create || model->nRoamed >= kMaxRoamed) {
        return nullptr;
    }
    LibraryRoamed* row = &model->roamed[model->nRoamed++];
    row->mark = str::Dup(mark);
    return row;
}

// In-memory mutation of a book from a sidecar record. Caller must hold the
// lib lock. This is the cheap half of the old AdoptEmbeddedRecord: the
// BookBlobRecord is read by the caller BEFORE taking the lock, so this
// function only touches in-memory data.
//
// IMPORTANT: this runs every time AdoptEmbeddedRecords is called —
// which can be repeatedly during a session, every time the user
// renames a book or the catalogue re-loads. If we just blindly
// overwrote book.title with the sidecar's title, an in-memory
// user rename (from the inline edit) would be silently reverted
// the next time the canvas is repainted. We must respect the
// *Source fields: if a field's source is "user", the user has
// spoken and the sidecar is not allowed to overwrite it.
static bool AdoptEmbeddedRecordFields(LibModel* model, LibBook& book, const BookBlobRecord& rec) {
    bool changed = false;
    if (rec.hasIdentity) {
        if (rec.identity.title && !str::Eq(book.title, Str(rec.identity.title)) &&
            !str::EqI(book.titleSource, StrL("user"))) {
            str::ReplaceWithCopy(&book.title, Str(rec.identity.title));
            changed = true;
        }
        if (rec.identity.author && !str::Eq(book.author, Str(rec.identity.author)) &&
            !str::EqI(book.authorSource, StrL("user"))) {
            str::ReplaceWithCopy(&book.author, Str(rec.identity.author));
            changed = true;
        }
        if (rec.identity.year > 0 && book.year != rec.identity.year && !str::EqI(book.yearSource, StrL("user"))) {
            book.year = rec.identity.year;
            changed = true;
        }
        if (rec.identity.pages > 0 && book.pages != rec.identity.pages) {
            book.pages = rec.identity.pages;
            changed = true;
        }
    }
    if (rec.hasShelf) {
        if (rec.shelf.series && !str::Eq(book.series, Str(rec.shelf.series)) &&
            !str::EqI(book.seriesSource, StrL("user"))) {
            str::ReplaceWithCopy(&book.series, Str(rec.shelf.series));
            changed = true;
        }
        if (rec.shelf.seriesParent && !str::Eq(book.seriesParent, Str(rec.shelf.seriesParent))) {
            str::ReplaceWithCopy(&book.seriesParent, Str(rec.shelf.seriesParent));
            changed = true;
        }
        if (rec.shelf.genre && !str::Eq(book.genre, Str(rec.shelf.genre))) {
            str::ReplaceWithCopy(&book.genre, Str(rec.shelf.genre));
            changed = true;
        }
        if (rec.shelf.subgenre && !str::Eq(book.subgenre, Str(rec.shelf.subgenre))) {
            str::ReplaceWithCopy(&book.subgenre, Str(rec.shelf.subgenre));
            changed = true;
        }
        str::Builder tags;
        for (const char* tag : rec.shelf.tags) {
            if (len(tags) > 0) {
                tags.Append(StrL(";"));
            }
            tags.Append(Str(tag));
        }
        if (len(tags) > 0 && !str::Eq(book.tags, ToStr(tags))) {
            str::ReplaceWithCopy(&book.tags, ToStr(tags));
            changed = true;
        }
        str::Builder partitions;
        for (const char* partition : rec.shelf.partitions) {
            if (len(partitions) > 0) {
                partitions.Append(StrL(";"));
            }
            partitions.Append(Str(partition));
        }
        if (len(partitions) > 0 && len(book.keys) == 0) {
            str::ReplaceWithCopy(&book.keys, ToStr(partitions));
            changed = true;
        }
        if (rec.shelf.seriesIndex >= 0 && book.volume != rec.shelf.seriesIndex) {
            book.volume = rec.shelf.seriesIndex;
            changed = true;
        }
    }
    if (rec.hasStats && rec.hasIdentity && rec.identity.fingerprint) {
        bool firstSight = FindRoamed(model, Str(rec.identity.fingerprint), false) == nullptr;
        LibraryRoamed* before = FindRoamed(model, Str(rec.identity.fingerprint), true);
        if (!before) {
            return changed;
        }
        if (firstSight) {
            FileState* here = FileHistoryFindByPath(book.path);
            if (here) {
                before->lastReadAt = here->lastReadAt;
                before->timeSpentMs = here->timeSpentMs;
                before->openCount = here->openCount;
            }
        }
        i64 addTime = 0;
        i64 addOpens = 0;
        if (!LibraryRoamedMerge(before, rec.stats.lastReadAt, rec.stats.timeSpentMs, rec.stats.openCount, &addTime,
                                &addOpens)) {
            return changed;
        }
        FileState* fs = FileHistoryFindByPath(book.path);
        if (!fs) {
            fs = NewFileState(book.path);
            FileHistoryAppend(fs);
        }
        bool newer = rec.stats.lastReadAt > fs->lastReadAt;
        fs->lastReadAt = std::max(fs->lastReadAt, rec.stats.lastReadAt);
        fs->timeSpentMs += std::max<i64>(0, addTime);
        fs->openCount += (int)std::min<i64>(INT_MAX, std::max<i64>(0, addOpens));
        int reached = (int)std::min<i64>(INT_MAX, rec.stats.pageNo);
        if (book.pages > 0 && rec.stats.percentRead > 0) {
            reached = std::max(reached, (int)(book.pages * rec.stats.percentRead / 100));
        }
        fs->maxPageReached = std::max(fs->maxPageReached, reached);
        if (newer && rec.stats.pageNo > 0) {
            fs->pageNo = (int)std::min<i64>(INT_MAX, rec.stats.pageNo);
        }
        changed = true;
    }
    return changed;
}

// Bulk variant of AdoptEmbeddedRecordFields. Used only by tests today;
// the production LoadModelThread path uses the lock-free per-book loop
// that calls AdoptEmbeddedRecordFields directly.
static bool AdoptEmbeddedRecords(LibModel* model) {
    bool changed = false;
    for (int i = 0; i < model->nBooks; i++) {
        BookBlobRecord rec;
        if (LibrarySidecarReadRecord(model->books[i].path, rec)) {
            changed = AdoptEmbeddedRecordFields(model, model->books[i], rec) || changed;
        }
    }
    return changed;
}

// keep a reference so the compiler does not strip AdoptEmbeddedRecords
static bool (*gAdoptEmbeddedRecordsKeepAlive)(LibModel*) = &AdoptEmbeddedRecords;

static void CopyBookToStore(LibraryBook* dst, const LibBook& b) {
    dst->id = str::Dup(b.id);
    dst->title = str::Dup(b.title);
    dst->author = str::Dup(b.author);
    dst->series = str::Dup(b.series);
    dst->seriesParent = str::Dup(b.seriesParent);
    dst->seriesKey = str::Dup(b.seriesKey);
    dst->keys = str::Dup(b.keys);
    dst->genre = str::Dup(b.genre);
    dst->subgenre = str::Dup(b.subgenre);
    dst->tags = str::Dup(b.tags);
    dst->path = str::Dup(b.path);
    dst->ext = str::Dup(b.ext);
    dst->wiki = str::Dup(b.wiki);
    dst->titleSource = str::Dup(b.titleSource);
    dst->authorSource = str::Dup(b.authorSource);
    dst->yearSource = str::Dup(b.yearSource);
    dst->seriesSource = str::Dup(b.seriesSource);
    dst->pages = b.pages;
    dst->year = b.year;
    dst->volume = b.volume;
    dst->bookNlp = b.booknlp;
    dst->cover = b.cover;
    dst->libraryOutOf = new Vec<LibraryOutOf*>();
    for (int i = 0; i < b.nOutOf; i++) {
        auto* o = new LibraryOutOf();
        o->key = str::Dup(b.outOf[i].key);
        o->name = str::Dup(b.outOf[i].name);
        dst->libraryOutOf->Append(o);
    }
}

static void CopySeriesToStore(LibrarySeries* dst, const LibSeries& s) {
    dst->key = str::Dup(s.key);
    dst->name = str::Dup(s.name);
    dst->author = str::Dup(s.author);
    dst->parent = str::Dup(s.parent);
    dst->wiki = str::Dup(s.wiki);
    dst->genre = str::Dup(s.genre);
    dst->sub = str::Dup(s.sub);
    dst->head = str::Dup(s.head);
    dst->subhead = str::Dup(s.subhead);
    dst->kind = str::Dup(s.kind);
    dst->guessed = str::Dup(s.guessed);
    dst->parentSource = str::Dup(s.parentSource);
    dst->books = s.books;
    dst->bookNlp = s.booknlp;
    dst->facts = s.facts;
    dst->depth = s.depth;
}

// caller holds the lib lock
static void SaveModelToStore(const LibModel* m) {
    LibraryStore* store = LibraryStoreNew();
    if (!store) {
        return;
    }
    store->scannedAtMs = UnixTimeMsNow();
    store->total = m->total;
    store->documents = m->documents;
    for (int i = 0; i < m->nBooks; i++) {
        auto* dst = new LibraryBook();
        CopyBookToStore(dst, m->books[i]);
        store->libraryBooks->Append(dst);
    }
    for (int i = 0; i < m->nSeries; i++) {
        auto* dst = new LibrarySeries();
        CopySeriesToStore(dst, m->series[i]);
        store->librarySeries->Append(dst);
    }
    for (int i = 0; i < m->nRoamed; i++) {
        auto* dst = new LibraryRoamed();
        dst->mark = str::Dup(m->roamed[i].mark);
        dst->lastReadAt = m->roamed[i].lastReadAt;
        dst->timeSpentMs = m->roamed[i].timeSpentMs;
        dst->openCount = m->roamed[i].openCount;
        store->libraryRoamed->Append(dst);
    }
    if (!LibraryStoreSave(store, LibraryStorePathTemp())) {
        logf("SaveModelToStore: could not write %s\n", LibraryStorePathTemp());
    }
    LibraryStoreFree(store);
}

static void RescanThread(LibJob* job);

static void LoadModelThread(LibJob* job) {
    ScopedMutex loadLock(&gLoadModelMutex);
    LoadMode loadMode = job ? job->loadMode : LoadMode::Full;
    bool isCatalogueOnly = (loadMode == LoadMode::CatalogueOnly);
    FreeJob(job);

    // chunk 34 regression: claim a generation number at the very start.
    // If a newer load has already started by the time we finish, we
    // will NOT write our perf snapshot (the newer load owns it).
    // AtomicIntInc returns the NEW value, so loadGeneration IS the
    // post-increment value of gLoadModelCurrentGeneration.
    int loadGeneration = AtomicIntInc(&gLoadModelCurrentGeneration);

    BookFingerprintCacheOpen(Str(GetAppDataDirTemp()));

    // chunk 31: count this load, reset per-call perf snapshot.
    AtomicIntInc(&gLoadModelThreadStarts);
    // chunk 34 regression: local counters for this load only. The old
    // gLastLoad* globals were shared between concurrent LoadModelThread
    // calls, so a slow initial Full load's adopt loop could overwrite
    // the counters of a fast catalogue-only load that ran concurrently
    // and read them back at the end. Local variables are private to
    // this call, so the snapshot we write at the end is always this
    // load's own numbers.
    u64 loadAdoptMs = 0;
    u64 loadSyncMs = 0;
    int loadReads = 0;
    int loadWrites = 0;
    int loadPdfOpen = 0;
    bool loadSkippedEmbedded = isCatalogueOnly;

    // draw what the last scan found before waiting on the service, so a cold
    // start shows the library instead of an empty page. We split the store
    // load into a no-lock file-I/O phase and a brief critical section that
    // only copies the in-memory book list into gModel. AdoptEmbeddedRecords
    // (which opens every PDF and decodes its LZMA2 blob) is run OUTSIDE the
    // lock; previously this whole sequence held gLock for 10+ seconds on a
    // 128-book library, blocking the UI thread from painting.
    bool showStored = false;
    {
        LibraryStore* store = LibraryStoreLoad(LibraryStorePathTemp());
        if (store) {
            EnterLib();
            showStored = !gModel.loaded && gModel.nBooks == 0;
            if (showStored) {
                for (LibraryBook* src : *store->libraryBooks) {
                    if (gModel.nBooks >= kMaxBooks) {
                        break;
                    }
                    CopyBookFromStore(gModel.books[gModel.nBooks], src);
                    gModel.nBooks++;
                }
                for (LibrarySeries* src : *store->librarySeries) {
                    if (gModel.nSeries >= kMaxSeries) {
                        break;
                    }
                    CopySeriesFromStore(gModel.series[gModel.nSeries], src);
                    gModel.nSeries++;
                }
                for (LibraryRoamed* src : *store->libraryRoamed) {
                    if (gModel.nRoamed >= kMaxRoamed || len(src->mark) == 0) {
                        continue;
                    }
                    LibraryRoamed& dst = gModel.roamed[gModel.nRoamed++];
                    dst.mark = str::Dup(src->mark);
                    dst.lastReadAt = src->lastReadAt;
                    dst.timeSpentMs = src->timeSpentMs;
                    dst.openCount = src->openCount;
                }
                gModel.total = store->total > 0 ? store->total : gModel.nBooks;
                gModel.documents = store->documents;
                showStored = gModel.nBooks > 0;
                RebuildSeriesTree();
            }
            LeaveLib();
            LibraryStoreFree(store);
        }
    }
    if (showStored) {
        Repaint();
    }

    LibraryEnsureService();
    str::Builder path;
    path.Append("/library?limit=4096&sort=");
    path.Append(UrlEncodeTemp(LibrarySortOrder()));
    TempStr body = ServiceGetTextTemp(ToStr(path));
    TempStr parts = ServiceGetTextTemp("/partitions");
    bool catalogueLoaded = len(body) > 0;

    // chunk 31R: CatalogueOnly mode is used by hierarchy changes
    // (Series -> Series, Series -> partition) that the service has
    // already applied. Fresh catalogue values win for every field
    // the service owns: series, seriesSource, seriesKey, keys,
    // genre, subgenre. Only the genuinely sidecar-only fields
    // (seriesParent, tags) may be carried forward; the service does
    // not provide them. We do NOT preserve keys / seriesKey /
    // series / seriesSource / genre / subgenre here because chunk 18
    // already established that fresh catalogue keys must not be
    // overwritten by stale embedded snapshots, and a hierarchy
    // operation is exactly the case where old membership would
    // be wrong.
    struct SidecarOnlyFields {
        Str path;
        Str seriesParent;
        Str tags;
    };
    Vec<SidecarOnlyFields> preserved;
    if (isCatalogueOnly && len(body) > 0) {
        EnterLib();
        for (int i = 0; i < gModel.nBooks; i++) {
            SidecarOnlyFields s;
            s.path = str::Dup(gModel.books[i].path);
            s.seriesParent = str::Dup(gModel.books[i].seriesParent);
            s.tags = str::Dup(gModel.books[i].tags);
            preserved.Append(s);
        }
        LeaveLib();
    }

    EnterLib();
    FreePartitions();
    if (len(parts) > 0) {
        PartitionParser p;
        JsonParseWithVisitor(Str(parts), &p);
        RankPartitions();
    }
    if (len(body) > 0) {
        FreeModel(&gModel);
        LibraryParser p(&gModel);
        JsonParseWithVisitor(Str(body), &p);
        // chunk 31R: restore ONLY the sidecar-only fields the service
        // does not provide (seriesParent, tags). Do NOT restore
        // keys, seriesKey, series, seriesSource, genre, subgenre —
        // those are service-owned and the parser has just set them
        // to the fresh catalogue values. Any stale entry from the
        // pre-rebuild model would be wrong specifically when the
        // caller is a hierarchy change.
        for (int i = 0; i < gModel.nBooks && preserved.len > 0; i++) {
            for (int j = 0; j < preserved.len; j++) {
                if (str::Eq(gModel.books[i].path, preserved[j].path)) {
                    str::ReplaceWithCopy(&gModel.books[i].seriesParent, preserved[j].seriesParent);
                    str::ReplaceWithCopy(&gModel.books[i].tags, preserved[j].tags);
                    break;
                }
            }
        }
        for (int i = 0; i < preserved.len; i++) {
            str::Free(preserved[i].path);
            str::Free(preserved[i].seriesParent);
            str::Free(preserved[i].tags);
        }
        preserved.Reset();
        gModel.loaded = true;
        gModel.loadFailed = false;
        str::FreePtr(&gModel.error);
        RebuildSeriesTree();
    } else if (gModel.nBooks > 0) {
        gModel.loaded = true;
        gModel.loadFailed = false;
        str::FreePtr(&gModel.error);
    } else {
        FreeModel(&gModel);
        gModel.loadFailed = true;
        str::ReplaceWithCopy(&gModel.error, StrL("the library service is not answering. "
                                                 "Use Library > Rescan library to try again."));
    }
    gModel.loading = false;
    bool sweepDevice = (!gModel.scopeCurrent || gModel.resumePending) && !gNativeScanning && !gAutoSweepStarted;
    if (sweepDevice) {
        gAutoSweepStarted = true;
    }
    LeaveLib();
    Repaint();

    // Adopt embedded records OUTSIDE the lock. This opens every PDF and
    // decodes its LZMA2 sidecar blob, which can take 5-15 seconds on a
    // 128-book library. Doing it under the lock would freeze the UI.
    // The cheap LibrarySidecarHas walk is used to skip books that don't
    // have a sidecar at all — without that guard, on a 128-book library
    // with a few hundred milliseconds of LZMA2 decode per file, this loop
    // heats the CPU to the point of throttling for over a minute.
    //
    // chunk 30: every record we read here is also retained in
    // `recordCache` so the SyncEmbeddedRecords sweep below can reuse it
    // instead of opening the same PDFs a second time. The cache lives only
    // for the rest of this LoadModelThread call.
    //
    // chunk 31R: when loadMode is CatalogueOnly, the caller is a
    // hierarchy change that has already been applied by the service.
    // We skip both the adopt pass (no book file changed) and the sync
    // sweep (no sidecar write is needed). The sidecar-only fields the
    // service does not provide (seriesParent, tags) were preserved
    // across the model rebuild above. This turns a Series -> Series
    // or Series -> partition move from "open 230 sidecars" to
    // "refresh catalogue from service, zero I/O on the books".
    bool adopted = false;
    LoadRecordCache recordCache;
    if (loadMode == LoadMode::Full) {
        u64 adoptStart = GetTickCount64();
        int readCountBeforeAdopt = 0, writeCountBeforeAdopt = 0;
        LibrarySidecarPerfCounters(&readCountBeforeAdopt, &writeCountBeforeAdopt);
        {
            int n = 0;
            {
                EnterLib();
                n = gModel.nBooks;
                LeaveLib();
            }
            for (int i = 0; i < n; i++) {
                Str bookPath;
                {
                    EnterLib();
                    if (i < gModel.nBooks) {
                        bookPath = str::Dup(gModel.books[i].path);
                    }
                    LeaveLib();
                }
                if (len(bookPath) == 0) {
                    str::Free(bookPath);
                    continue;
                }
                if (!LibrarySidecarHas(bookPath)) {
                    str::Free(bookPath);
                    continue;
                }
                BookBlobRecord rec;
                if (!LibrarySidecarReadRecord(bookPath, rec)) {
                    str::Free(bookPath);
                    continue;
                }
                // Apply changes under the lock; the in-memory mutation is cheap
                EnterLib();
                if (i < gModel.nBooks && str::Eq(gModel.books[i].path, bookPath)) {
                    if (AdoptEmbeddedRecordFields(&gModel, gModel.books[i], rec)) {
                        adopted = true;
                    }
                }
                LeaveLib();
                // Remember the record so the next sweep can skip its own read.
                // Put() clones the record into storage the cache owns, so the
                // local `rec` can be freed at the end of this iteration without
                // affecting the cached copy.
                recordCache.Put(bookPath, rec);
                str::Free(bookPath);
            }
        }
        int readCountAfterAdopt = 0, writeCountAfterAdopt = 0;
        LibrarySidecarPerfCounters(&readCountAfterAdopt, &writeCountAfterAdopt);
        loadReads = readCountAfterAdopt - readCountBeforeAdopt;
        loadAdoptMs = GetTickCount64() - adoptStart;
        int fpFull = 0, fpShape = 0, fpHits = 0, fpSeeded = 0;
        BookFingerprintPerfCounters(&fpFull, &fpShape, &fpHits, &fpSeeded, nullptr, nullptr, nullptr, nullptr);
        logf("LoadModelThread adopt: %d ms; fingerprint full=%d shape=%d cacheHits=%d seeded=%d\n", (int)loadAdoptMs,
             fpFull, fpShape, fpHits, fpSeeded);
    }
    if (adopted || catalogueLoaded) {
        EnterLib();
        bool overlaid = RebuildSeriesTree();
        SaveModelToStore(&gModel);
        LeaveLib();
        if (overlaid) {
            Repaint();
        }
    }

    if (isCatalogueOnly) {
        // chunk 31R: still re-save the model so the catalogue-only
        // changes (e.g. a Series -> partition membership change) get
        // persisted, but do not run SyncEmbeddedRecords. The sync
        // sweep writes user-metadata back to sidecar files, and no
        // sidecar needs to change on a kind/partition move.
        EnterLib();
        SaveModelToStore(&gModel);
        LeaveLib();
    } else {
        u64 syncStart = GetTickCount64();
        int writeCountBeforeSync = 0, readCountBeforeSync = 0;
        LibrarySidecarPerfCounters(&readCountBeforeSync, &writeCountBeforeSync);
        SyncEmbeddedRecords(&recordCache);
        int writeCountAfterSync = 0, readCountAfterSync = 0;
        LibrarySidecarPerfCounters(&readCountAfterSync, &writeCountAfterSync);
        loadWrites = writeCountAfterSync - writeCountBeforeSync;
        loadSyncMs = GetTickCount64() - syncStart;
        loadReads += readCountAfterSync - readCountBeforeSync;
    }
    // recordCache goes out of scope here; its StrVec pages are freed by the
    // BookBlobRecord destructors. We deliberately do NOT keep the cache
    // alive past this LoadModelThread call: a stale entry is exactly the
    // bug we are trying to prevent, and a fresh cache costs the same as
    // building one on every load.
    if (sweepDevice) {
        RunAsync(MkFunc0<LibJob>(RescanThread, NewJob({})), "libRescan");
    }
    {
        EnterLib();
        int loadBooks = gModel.nBooks;
        LeaveLib();
        // chunk 34 regression: write this load's own counters (local
        // variables) to the snapshot. The old code wrote the shared
        // gLastLoad* globals, which could be overwritten by a slow
        // adopt loop of a concurrent load. Local variables are
        // private to this call, so the snapshot is always this
        // load's own numbers.
        //
        // Only write the snapshot if THIS load is still the most
        // recent one. If a newer LoadModelThread has already started
        // (e.g. a fast catalogue-only load triggered while the
        // initial Full load is still in its slow adopt loop), the
        // newer load's snapshot is the one the test cares about, and
        // this load's snapshot would just overwrite it with stale
        // values.
        int myGeneration = loadGeneration;
        if (AtomicIntGet(&gLoadModelCurrentGeneration) == myGeneration) {
            gLastCompletedLoadAdoptMs = loadAdoptMs;
            gLastCompletedLoadSyncMs = loadSyncMs;
            gLastCompletedLoadReads = loadReads;
            gLastCompletedLoadWrites = loadWrites;
            gLastCompletedLoadPdfOpen = loadPdfOpen;
            gLastCompletedLoadBooks = loadBooks;
            gLastCompletedLoadSkippedEmbedded = loadSkippedEmbedded;
            gLastCompletedLoadGeneration = AtomicIntGet(&gLoadModelThreadStarts);
        }
    }
    // chunk 34 regression: signal that this load is fully done. The
    // test harness polls this counter to know it is safe to read the
    // snapshot above. Must be the LAST thing this function does.
    AtomicIntInc(&gLoadModelThreadCompletes);
}

static void EnsureModel() {
    if (gModel.loaded || gModel.loading || gModel.loadFailed) {
        return;
    }
    gModel.loading = true;
    RunAsync(MkFunc0<LibJob>(LoadModelThread, NewJob({})), "libModel");
}

static void LoadDeskThread(LibJob* job) {
    FreeJob(job);
    LibraryEnsureService();
    EnterLib();
    bool wantIgnored = gDesk.showIgnored;
    LeaveLib();
    str::Builder path;
    path.Append("/deskpan?limit=4096");
    if (wantIgnored) {
        path.Append("&show=ignored");
    }
    TempStr body = ServiceGetTextTemp(ToStr(path));
    EnterLib();
    FreeDesk(&gDesk);
    if (len(body) > 0) {
        DeskParser p(&gDesk);
        JsonParseWithVisitor(Str(body), &p);
        gDesk.loaded = true;
    }
    gDesk.loading = false;
    LeaveLib();
    Repaint();
}

static void EnsureDesk() {
    if (gDesk.loaded || gDesk.loading) {
        return;
    }
    gDesk.loading = true;
    RunAsync(MkFunc0<LibJob>(LoadDeskThread, NewJob({})), "libDesk");
}

static void ReloadDesk() {
    EnterLib();
    gDesk.loaded = false;
    LeaveLib();
    EnsureDesk();
}

struct KnownParser : JsonVisitor {
    Vec<LibraryKnownFile>* out;

    explicit KnownParser(Vec<LibraryKnownFile>* files) : out(files) {}

    bool Visit(Str path, Str value, json::Type type) override {
        if (type == json::Type::Null) {
            return true;
        }
        int i = IndexIn(path, "/files");
        if (i < 0) {
            return true;
        }
        while (out->len <= i) {
            LibraryKnownFile blank;
            out->Append(blank);
        }
        LibraryKnownFile& f = (*out)[i];
        if (str::EndsWith(path, StrL("/path"))) {
            f.path = str::Dup(value);
        } else if (str::EndsWith(path, StrL("/size"))) {
            f.size = (i64)_atoi64(value.s);
        } else if (str::EndsWith(path, StrL("/mtime"))) {
            f.mtime = atof(value.s);
        } else if (str::EndsWith(path, StrL("/completed"))) {
            f.completed = str::Eq(value, StrL("true"));
        } else if (str::EndsWith(path, StrL("/resume_candidate"))) {
            f.resumeCandidate = str::Eq(value, StrL("true"));
        } else if (str::EndsWith(path, StrL("/indexed"))) {
            f.indexed = str::Eq(value, StrL("true"));
        } else if (str::EndsWith(path, StrL("/placeholder"))) {
            f.placeholder = str::Eq(value, StrL("true"));
        } else if (str::EndsWith(path, StrL("/details_pending"))) {
            f.detailsPending = str::Eq(value, StrL("true"));
        } else if (str::EndsWith(path, StrL("/scan_json"))) {
            f.scanJson = str::Dup(value);
        }
        return true;
    }
};

static TempStr JsonStrTemp(Str s);

static void ReadKnownFiles(Vec<LibraryKnownFile>& out, const StrVec& roots, bool wholeDevice) {
    str::Builder rootsJson;
    rootsJson.AppendChar('[');
    for (int i = 0; i < roots.size; i++) {
        if (i > 0) {
            rootsJson.AppendChar(',');
        }
        rootsJson.Append(JsonStrTemp(roots.At(i)));
    }
    rootsJson.AppendChar(']');
    TempStr request =
        fmt("/known?scope=%d&roots=%s", wholeDevice ? kLibraryScanScope : 0, UrlEncodeTemp(ToStr(rootsJson)));
    TempStr body = ServiceGetTextTemp(request);
    if (len(body) == 0) {
        return;
    }
    KnownParser p(&out);
    JsonParseWithVisitor(Str(body), &p);
}

static void FreeKnownFiles(Vec<LibraryKnownFile>& files) {
    for (LibraryKnownFile& f : files) {
        str::Free(f.path);
        str::Free(f.scanJson);
    }
    files.Reset();
}

static void OnScanProgress(const LibraryScanProgress& progress, void*) {
    EnterLib();
    gModel.scanning = true;
    gModel.scanDone = progress.reading ? progress.done : progress.found;
    gModel.scanTotal = progress.reading ? progress.total : 0;
    gModel.scanFilesDiscovered = progress.reading ? progress.total : progress.found;
    gModel.scanFilesProcessed = progress.reading ? progress.done : 0;
    gModel.scanItemDone = progress.itemDone;
    gModel.scanItemTotal = progress.itemTotal;
    gModel.scanItemIndeterminate = progress.itemIndeterminate;
    str::ReplaceWithCopy(&gModel.scanItem, progress.item);
    str::ReplaceWithCopy(&gModel.scanItemAction, progress.itemAction);
    LeaveLib();
    Repaint();
}

struct ScanCallCtx {
    StrVec roots;
    bool wholeDevice;
};

static void OnScanSnapshot(Str bookJson, void* ctx) {
    auto* c = (ScanCallCtx*)ctx;
    if (!c) {
        return;
    }
    str::Builder body(1024);
    body.Append(StrL("{\"roots\":["));
    for (int i = 0; i < c->roots.size; i++) {
        if (i > 0) {
            body.AppendChar(',');
        }
        body.Append(JsonStrTemp(c->roots.At(i)));
    }
    body.Append(StrL("],\"scope\":"));
    body.Append(StrL(c->wholeDevice ? "2" : "0"));
    body.Append(StrL(",\"book\":"));
    body.Append(bookJson);
    body.AppendChar('}');
    if (!ServicePost("/book", Str(body.els, body.len))) {
        logf("OnScanSnapshot: /book failed for size=%d\n", (int)bookJson.len);
    }
    if (gGlobalPrefs && gGlobalPrefs->audiobook.progressiveLibraryScan) {
        LoadModelThread(NewJobCatalogueOnly());
    }
}

static void OnScanManifest(Str manifestJson, void* ctx) {
    auto* c = (ScanCallCtx*)ctx;
    if (!c) {
        return;
    }
    if (!ServicePost("/manifest", manifestJson)) {
        logf("OnScanManifest: /manifest failed for size=%d\n", (int)manifestJson.len);
    }
}

static void RunOneScan(const StrVec& roots, const Vec<LibraryKnownFile>& known, bool wholeDevice) {
    EnterLib();
    gModel.scanTraversals++;
    LeaveLib();
    ScanCallCtx ctx;
    ctx.roots = roots;
    ctx.wholeDevice = wholeDevice;
    Str body = LibraryScanToJson(roots, known, wholeDevice, OnScanProgress, nullptr, &gScanCancel, OnScanSnapshot,
                                  &ctx, OnScanManifest, &ctx);
    if (!gScanCancel) {
        ServicePost("/index", body);
    }
    str::Free(body);
    EnterLib();
    gModel.loaded = false;
    gModel.loadFailed = false;
    LeaveLib();
    EnsureModel();
}

static void RescanThread(LibJob* job) {
    FreeJob(job);

    EnterLib();
    bool busy = gNativeScanning;
    if (!busy) {
        gNativeScanning = true;
        InterlockedExchange(&gScanCancel, 0);
        gModel.scanning = true;
        gModel.scanDone = 0;
        gModel.scanTotal = 0;
        gModel.scanItemDone = 0;
        gModel.scanItemTotal = 0;
        gModel.scanItemIndeterminate = false;
        str::ReplaceWithCopy(&gModel.scanItem, {});
        str::ReplaceWithCopy(&gModel.scanItemAction, {});
        gModel.scanTraversals = 0;
        gModel.scanFilesDiscovered = 0;
        gModel.scanFilesProcessed = 0;
    }
    LeaveLib();
    if (busy) {
        return;
    }

    BookFingerprintResetCounters();

    if (!LibraryEnsureService()) {
        // The local library service (the Python audiobook.library process
        // that owns the index) is not running and we can't bring it up.
        // Without it, RunOneScan's ServicePost("/index", ...) is a no-op,
        // but LibraryScanToJson still walks the whole device filesystem
        // for every call. On a multi-drive Windows box that can keep a
        // modern CPU pegged for 30+ seconds at startup — exactly the
        // "phone heats up" symptom we're trying to fix. Skip the scan and
        // let the user trigger a rescan manually once the service is up.
        logf("RescanThread: library service not available, skipping auto-sweep\n");
        EnterLib();
        gNativeScanning = false;
        gModel.scanning = false;
        LeaveLib();
        Repaint();
        return;
    }
    Repaint();

    StrVec roots = LibraryStartingRoots();
    bool wholeDevice = !LibraryHasExplicitRoots();
    if (wholeDevice) {
        roots = LibraryWholeDeviceRoots();
    }
    Vec<LibraryKnownFile> known;
    ReadKnownFiles(known, roots, wholeDevice);
    if (roots.size > 0 && !gScanCancel) {
        RunOneScan(roots, known, wholeDevice);
    }
    FreeKnownFiles(known);

    EnterLib();
    gNativeScanning = false;
    gModel.scanning = false;
    gModel.scanItemDone = 0;
    gModel.scanItemTotal = 0;
    gModel.scanItemIndeterminate = false;
    str::ReplaceWithCopy(&gModel.scanItem, {});
    str::ReplaceWithCopy(&gModel.scanItemAction, {});
    gModel.loaded = false;
    gModel.loadFailed = false;
    LeaveLib();
    EnsureModel();
    Repaint();
}

struct ResumeParser : JsonVisitor {
    bool resumePending = false;

    bool Visit(Str path, Str value, json::Type type) override {
        if (type != json::Type::Null && str::Eq(path, StrL("/resume_pending"))) {
            resumePending = IsTrue(value);
        }
        return true;
    }
};

static bool AutoScanAlreadyOwned() {
    EnterLib();
    bool owned = gAutoSweepStarted || gNativeScanning || gModel.loading;
    LeaveLib();
    return owned;
}

static void ResumeScanThread(LibJob* job) {
    FreeJob(job);
    if (!file::Exists(LibraryStorePathTemp())) {
        return;
    }
    if (AutoScanAlreadyOwned()) {
        return;
    }
    if (!LibraryEnsureService()) {
        logf("LibraryResumeInterruptedScan: the library service is not answering\n");
        return;
    }
    TempStr body = ServiceGetTextTemp("/status");
    if (len(body) == 0) {
        return;
    }
    ResumeParser p;
    JsonParseWithVisitor(Str(body), &p);
    if (!p.resumePending) {
        return;
    }
    EnterLib();
    bool owned = gAutoSweepStarted || gNativeScanning || gModel.loading;
    if (!owned) {
        gModel.loaded = false;
        gModel.loadFailed = false;
    }
    LeaveLib();
    if (owned) {
        return;
    }
    logf("LibraryResumeInterruptedScan: an unfinished library scan is pending\n");
    EnsureModel();
}

void LibraryResumeInterruptedScan() {
    RunAsync(MkFunc0<LibJob>(ResumeScanThread, NewJob({})), "libResume");
}

void LibraryRefresh(MainWindow* win, bool rescan) {
    gNotifyHwnd = win ? win->hwndCanvas : gNotifyHwnd;
    if (rescan) {
        RunAsync(MkFunc0<LibJob>(RescanThread, NewJob({})), "libRescan");
    }
    EnterLib();
    gModel.loaded = false;
    gModel.loadFailed = false;
    LeaveLib();
    EnsureModel();
    Repaint();
}

static Str BookPathForId(Str id) {
    Str path;
    EnterLib();
    for (int i = 0; i < gModel.nBooks; i++) {
        if (str::Eq(gModel.books[i].id, id)) {
            path = str::Dup(gModel.books[i].path);
            break;
        }
    }
    LeaveLib();
    return path;
}

static bool ReadEmbeddedRecordForId(Str id, BookBlobRecord& rec) {
    Str path = BookPathForId(id);
    bool ok = len(path) > 0 && LibrarySidecarReadRecord(path, rec);
    str::Free(path);
    return ok;
}

static void SaveEmbeddedChapters(Str id) {
    BookBlobRecord rec;
    if (!ReadEmbeddedRecordForId(id, rec)) {
        return;
    }
    bool have = false;
    EnterLib();
    if (str::Eq(gDetail.id, id) && gDetail.nChapters > 0) {
        rec.chapters.Reset();
        for (int i = 0; i < gDetail.nChapters; i++) {
            BlobChapter dst{};
            dst.title = rec.strings.Append(gDetail.chapters[i].title).s;
            dst.page = gDetail.chapters[i].page;
            dst.depth = gDetail.chapters[i].depth;
            rec.chapters.Append(dst);
        }
        have = true;
    }
    LeaveLib();
    if (!have) {
        return;
    }
    Str path = BookPathForId(id);
    Str error;
    if (len(path) > 0) {
        LibrarySidecarWriteRecord(path, rec, &error);
    }
    str::Free(error);
    str::Free(path);
}

static void SaveEmbeddedScreen(Str id) {
    BookBlobRecord rec;
    if (!ReadEmbeddedRecordForId(id, rec)) {
        return;
    }
    bool have = false;
    EnterLib();
    if (str::Eq(gDetail.id, id) && gDetail.nScreen > 0) {
        rec.adaptations.Reset();
        for (int i = 0; i < gDetail.nScreen; i++) {
            LibScreen& src = gDetail.screen[i];
            if (len(src.imdbId) == 0) {
                continue;
            }
            BlobShow dst{};
            dst.title = rec.strings.Append(src.title).s;
            dst.kind = rec.strings.Append(src.kind).s;
            dst.year = src.year;
            dst.ref = rec.strings.Append(src.imdbId).s;
            rec.adaptations.Append(dst);
        }
        have = len(rec.adaptations) > 0;
    }
    LeaveLib();
    if (!have) {
        return;
    }
    Str path = BookPathForId(id);
    Str error;
    if (len(path) > 0) {
        LibrarySidecarWriteRecord(path, rec, &error);
    }
    str::Free(error);
    str::Free(path);
}

static void ApplyEmbeddedDetail(LibDetail* detail, const BookBlobRecord& rec) {
    if (rec.hasIdentity) {
        // Title / Author / Year are service-authoritative: /book supplies
        // them and any user override is layered by the service. The
        // embedded sidecar (written before the override) is NOT allowed
        // to overwrite them here.
        if (rec.identity.pages > 0) {
            detail->pages = rec.identity.pages;
        }
    }
    if (rec.hasShelf) {
        // Series is service-authoritative (same reason as Title/Author).
        if (rec.shelf.genre) {
            str::ReplaceWithCopy(&detail->genre, Str(rec.shelf.genre));
        }
        if (rec.shelf.subgenre) {
            str::ReplaceWithCopy(&detail->sub, Str(rec.shelf.subgenre));
        }
        str::Builder subjects;
        for (const char* tag : rec.shelf.tags) {
            if (len(subjects) > 0) {
                subjects.Append(StrL(", "));
            }
            subjects.Append(Str(tag));
        }
        if (len(subjects) > 0) {
            str::ReplaceWithCopy(&detail->subjects, ToStr(subjects));
        }
    }
}

static void ApplyEmbeddedScreen(LibDetail* detail, const BookBlobRecord& rec) {
    for (int i = 0; i < detail->nScreen; i++) {
        str::Free(detail->screen[i].title);
        str::Free(detail->screen[i].kind);
        str::Free(detail->screen[i].poster);
        str::Free(detail->screen[i].stars);
        str::Free(detail->screen[i].via);
        str::Free(detail->screen[i].imdbId);
        detail->screen[i] = {};
    }
    detail->nScreen = 0;
    for (const BlobShow& src : rec.adaptations) {
        if (detail->nScreen >= kMaxScreen || !src.ref) {
            continue;
        }
        LibScreen& dst = detail->screen[detail->nScreen++];
        dst.title = str::Dup(Str(src.title ? src.title : ""));
        dst.kind = str::Dup(Str(src.kind ? src.kind : "Title"));
        dst.imdbId = str::Dup(Str(src.ref));
        dst.via = str::Dup(StrL("roaming"));
        dst.year = src.year;
    }
}

static void ApplyEmbeddedChapters(LibDetail* detail, const BookBlobRecord& rec) {
    for (int i = 0; i < detail->nChapters; i++) {
        str::Free(detail->chapters[i].title);
        detail->chapters[i] = {};
    }
    detail->nChapters = 0;
    for (const BlobChapter& src : rec.chapters) {
        if (detail->nChapters >= kMaxChapters) {
            break;
        }
        int idx = detail->nChapters++;
        LibChapter& dst = detail->chapters[idx];
        dst.title = str::Dup(Str(src.title ? src.title : ""));
        dst.page = src.page;
        dst.depth = src.depth;
        dst.parent = -1;
        for (int i = idx - 1; i >= 0; i--) {
            if (detail->chapters[i].depth < dst.depth) {
                dst.parent = i;
                detail->chapters[i].kids++;
                break;
            }
        }
        dst.open = dst.depth > 0;
    }
}

static void LoadDetailThread(LibJob* job) {
    TempStr id = str::DupTemp(job->a);
    FreeJob(job);
    BookBlobRecord rec;
    bool embedded = ReadEmbeddedRecordForId(id, rec);
    // Always fetch /book — it carries the current user override for
    // Title / Author / Series / Year, which the embedded sidecar (a
    // snapshot from before the override) would otherwise overwrite.
    TempStr body = ServiceGetTextTemp(fmt("/book?id=%s", id));
    EnterLib();
    if (str::Eq(gDetail.id, Str(id))) {
        if (len(body) > 0) {
            DetailParser p(&gDetail);
            JsonParseWithVisitor(Str(body), &p);
        }
        if (embedded) {
            ApplyEmbeddedDetail(&gDetail, rec);
        }
        gDetail.loading = false;
    }
    LeaveLib();
    Repaint();
}

static void LoadScreenThread(LibJob* job) {
    TempStr id = str::DupTemp(job->a);
    FreeJob(job);
    BookBlobRecord rec;
    bool embedded = ReadEmbeddedRecordForId(id, rec) && len(rec.adaptations) > 0;
    TempStr body;
    if (!embedded) {
        body = ServiceGetTextTemp(fmt("/screen?id=%s", id));
    }
    EnterLib();
    if (str::Eq(gDetail.id, Str(id))) {
        if (len(body) > 0) {
            ScreenParser p(&gDetail);
            JsonParseWithVisitor(Str(body), &p);
        }
        if (embedded) {
            ApplyEmbeddedScreen(&gDetail, rec);
        }
        gDetail.screenLoading = false;
        gDetail.screenDone = true;
    }
    LeaveLib();
    if (!embedded && len(body) > 0) {
        SaveEmbeddedScreen(id);
    }
    Repaint();
}

static void LoadPersonThread(LibJob* job) {
    TempStr series = str::DupTemp(job->a);
    TempStr who = str::DupTemp(job->b);
    FreeJob(job);
    TempStr path = fmt("/wiki?q=character&series=%s&name=%s", UrlEncodeTemp(series), UrlEncodeTemp(who));
    TempStr body = ServiceGetTextTemp(path);
    TempStr famPath = fmt("/wiki?q=family&series=%s&name=%s", UrlEncodeTemp(series), UrlEncodeTemp(who));
    TempStr fam = ServiceGetTextTemp(famPath);
    EnterLib();
    if (str::EqI(gDetail.person, Str(who))) {
        if (len(body) > 0) {
            PersonParser p(&gDetail);
            JsonParseWithVisitor(Str(body), &p);
        }
        gDetail.personLoaded = true;
        for (int i = 0; i < gDetail.nFamily; i++) {
            str::Free(gDetail.family[i].relation);
            str::Free(gDetail.family[i].name);
            gDetail.family[i] = LibFamilyRow{};
        }
        gDetail.nFamily = 0;
        if (len(fam) > 0) {
            FamilyParser p(&gDetail, Str(who));
            JsonParseWithVisitor(Str(fam), &p);
        }
    }
    LeaveLib();
    Repaint();
}

static void LoadChaptersThread(LibJob* job) {
    TempStr id = str::DupTemp(job->a);
    FreeJob(job);
    BookBlobRecord rec;
    bool embedded = ReadEmbeddedRecordForId(id, rec) && len(rec.chapters) > 0;
    TempStr body;
    if (!embedded) {
        body = ServiceGetTextTemp(fmt("/chapters?id=%s", id));
    }
    EnterLib();
    if (str::Eq(gDetail.id, Str(id))) {
        if (len(body) > 0) {
            ChapterParser p(&gDetail);
            JsonParseWithVisitor(Str(body), &p);
        }
        if (embedded) {
            ApplyEmbeddedChapters(&gDetail, rec);
        }
        for (int i = 0; i < gDetail.nChapters; i++) {
            gDetail.chapters[i].open = gDetail.chapters[i].depth > 0;
        }
        gDetail.chaptersLoading = false;
        gDetail.chaptersDone = true;
    }
    LeaveLib();
    if (!embedded && len(body) > 0) {
        SaveEmbeddedChapters(id);
    }
    Repaint();
}

static i64 FileTimeToUnixMs(const FILETIME& ft) {
    ULARGE_INTEGER u{};
    u.LowPart = ft.dwLowDateTime;
    u.HighPart = ft.dwHighDateTime;
    if (u.QuadPart < 116444736000000000ULL) {
        return 0;
    }
    return (i64)((u.QuadPart - 116444736000000000ULL) / 10000ULL);
}

static i64 CountWords(Str text) {
    i64 n = 0;
    bool inWord = false;
    for (int i = 0; i < len(text); i++) {
        char c = text.s[i];
        bool space = (c == ' ' || c == '\n' || c == '\r' || c == '\t');
        if (space) {
            inWord = false;
        } else if (!inWord) {
            inWord = true;
            n++;
        }
    }
    return n;
}

static void LoadInfoThread(LibJob* job) {
    TempStr id = str::DupTemp(job->a);
    TempStr path = str::DupTemp(job->b);
    FreeJob(job);

    i64 created = 0;
    i64 mtime = 0;
    i64 size = 0;
    WIN32_FILE_ATTRIBUTE_DATA fi{};
    if (GetFileAttributesExW(CWStrTemp(path), GetFileExInfoStandard, &fi)) {
        created = FileTimeToUnixMs(fi.ftCreationTime);
        mtime = FileTimeToUnixMs(fi.ftLastWriteTime);
        size = ((i64)fi.nFileSizeHigh << 32) | (i64)fi.nFileSizeLow;
    }

    TempStr checksum;
    Str bytes = file::ReadFile(path);
    if (len(bytes) > 0) {
        u8 digest[16]{};
        CalcMD5Digest(bytes, digest);
        checksum = str::MemToHexTemp(Str((char*)digest, 16));
    }
    str::Free(bytes);

    TempStr mark;
    i64 words = -1;
    BookFingerprint fp;
    if (BookFingerprintOfFile(path, fp, 0)) {
        mark = str::DupTemp(fp.fingerprint);
        words = CountWords(fp.readingText);
    }
    BookFingerprintFree(fp);

    EnterLib();
    if (str::Eq(gDetail.id, Str(id))) {
        gDetail.created = created;
        gDetail.mtime = mtime;
        gDetail.size = size;
        str::ReplaceWithCopy(&gDetail.checksum, Str(checksum));
        str::ReplaceWithCopy(&gDetail.mark, Str(mark));
        gDetail.words = words;
        gDetail.infoWorking = false;
        gDetail.infoDone = true;
    }
    LeaveLib();
    Repaint();
}

static void LoadTopicThread(LibJob* job) {
    TempStr series = str::DupTemp(job->a);
    TempStr what = str::DupTemp(job->b);
    FreeJob(job);
    TempStr path = fmt("/wiki?q=knows&series=%s&topic=%s", UrlEncodeTemp(series), UrlEncodeTemp(what));
    TempStr body = ServiceGetTextTemp(path);
    EnterLib();
    if (str::Eq(gDetail.topic, Str(what))) {
        if (len(body) > 0) {
            KnowsParser p(&gDetail);
            JsonParseWithVisitor(Str(body), &p);
        }
        gDetail.topicLoaded = true;
    }
    LeaveLib();
    Repaint();
}

static void OpenDetail(Str id) {
    EnterLib();
    FreeDetail(&gDetail);
    str::ReplaceWithCopy(&gDetail.id, id);
    for (int i = 0; i < gModel.nBooks; i++) {
        if (!str::Eq(gModel.books[i].id, id)) {
            continue;
        }
        str::ReplaceWithCopy(&gDetail.path, gModel.books[i].path);
        str::ReplaceWithCopy(&gDetail.title, gModel.books[i].title);
        str::ReplaceWithCopy(&gDetail.author, gModel.books[i].author);
        str::ReplaceWithCopy(&gDetail.series, gModel.books[i].series);
        str::ReplaceWithCopy(&gDetail.genre, gModel.books[i].genre);
        str::ReplaceWithCopy(&gDetail.sub, gModel.books[i].subgenre);
        str::ReplaceWithCopy(&gDetail.subjects, gModel.books[i].tags);
        gDetail.pages = gModel.books[i].pages;
        gDetail.year = gModel.books[i].year;
        break;
    }
    gDetail.loading = true;
    gDetail.tab = LibTab::Overview;
    LeaveLib();
    gDetailOpen = true;
    RunAsync(MkFunc0<LibJob>(LoadDetailThread, NewJob(id)), "libDetail");
}

static void EnsureScreen() {
    if (gDetail.screenLoading || gDetail.screenDone || len(gDetail.id) == 0) {
        return;
    }
    gDetail.screenLoading = true;
    RunAsync(MkFunc0<LibJob>(LoadScreenThread, NewJob(gDetail.id)), "libScreen");
}

static void EnsureChapters() {
    if (gDetail.chaptersLoading || gDetail.chaptersDone || len(gDetail.id) == 0) {
        return;
    }
    gDetail.chaptersLoading = true;
    RunAsync(MkFunc0<LibJob>(LoadChaptersThread, NewJob(gDetail.id)), "libChapters");
}

static void EnsureInfo() {
    if (gDetail.infoWorking || gDetail.infoDone || len(gDetail.id) == 0 || len(gDetail.path) == 0) {
        return;
    }
    gDetail.infoWorking = true;
    RunAsync(MkFunc0<LibJob>(LoadInfoThread, NewJob(gDetail.id, gDetail.path)), "libInfo");
}

static void OpenTopic(Str what) {
    if (len(gDetail.wiki) == 0) {
        return;
    }
    EnterLib();
    for (int i = 0; i < gDetail.nKnowers; i++) {
        str::Free(gDetail.knowers[i].name);
        str::Free(gDetail.knowers[i].book);
        gDetail.knowers[i] = LibKnower{};
    }
    gDetail.nKnowers = 0;
    gDetail.topicLoaded = false;
    str::ReplaceWithCopy(&gDetail.topic, what);
    LeaveLib();
    RunAsync(MkFunc0<LibJob>(LoadTopicThread, NewJob(gDetail.wiki, what)), "libTopic");
}

static void OpenPerson(Str who) {
    if (len(gDetail.wiki) == 0) {
        return;
    }
    EnterLib();
    str::ReplaceWithCopy(&gDetail.person, who);
    str::FreePtr(&gDetail.personTraits);
    str::FreePtr(&gDetail.personNot);
    str::FreePtr(&gDetail.personKin);
    str::FreePtr(&gDetail.personSpeech);
    str::FreePtr(&gDetail.personVoice);
    str::FreePtr(&gDetail.personPlaces);
    str::FreePtr(&gDetail.personKnows);
    str::FreePtr(&gDetail.personQuote);
    str::FreePtr(&gDetail.personQuoteBook);
    gDetail.personQuotePage = 0;
    gDetail.personBooks = 0;
    gDetail.personLoaded = false;
    LeaveLib();
    RunAsync(MkFunc0<LibJob>(LoadPersonThread, NewJob(gDetail.wiki, who)), "libPerson");
}

static Str AfterPrefix(Str url, const char* prefix) {
    int n = (int)strlen(prefix);
    return Str(url.s + n, url.len - n);
}

static CoverSlot* SlotFor(Str key) {
    for (int i = 0; i < gNCovers; i++) {
        if (str::Eq(gCovers[i].key, key)) {
            return &gCovers[i];
        }
    }
    if (gNCovers >= kMaxCovers) {
        return nullptr;
    }
    CoverSlot* s = &gCovers[gNCovers++];
    str::ReplaceWithCopy(&s->key, key);
    return s;
}

struct CoverBookInfo {
    Str path;
    Str title;
    Str author;
    Str series;
    Str seriesParent;
    Str genre;
    Str subgenre;
    Str tags;
    Str partitions;
    int year = 0;
    int pages = 0;
    int seriesIndex = -1;
    // Whether the user owns each of these four. The portable record in the
    // book file is read back by any device as "the user typed this", so a
    // value we merely auto-detected must not be written into it: the next
    // clean scan would hand it back marked "user" and no Revert could ever
    // shake it off.
    bool titleIsUser = false;
    bool authorIsUser = false;
    bool seriesIsUser = false;
    bool yearIsUser = false;
};

static bool IsUserSource(Str source) {
    return str::EqI(source, StrL("user"));
}

static CoverBookInfo CoverBookInfoById(Str id) {
    CoverBookInfo res;
    EnterLib();
    for (int i = 0; i < gModel.nBooks; i++) {
        if (str::Eq(gModel.books[i].id, id)) {
            res.path = str::Dup(gModel.books[i].path);
            res.title = str::Dup(gModel.books[i].title);
            res.author = str::Dup(gModel.books[i].author);
            res.series = str::Dup(gModel.books[i].series);
            res.seriesParent = str::Dup(gModel.books[i].seriesParent);
            res.genre = str::Dup(gModel.books[i].genre);
            res.subgenre = str::Dup(gModel.books[i].subgenre);
            res.tags = str::Dup(gModel.books[i].tags);
            res.partitions = str::Dup(gModel.books[i].keys);
            res.year = gModel.books[i].year;
            res.pages = gModel.books[i].pages;
            res.seriesIndex = gModel.books[i].volume;
            res.titleIsUser = IsUserSource(gModel.books[i].titleSource);
            res.authorIsUser = IsUserSource(gModel.books[i].authorSource);
            res.seriesIsUser = IsUserSource(gModel.books[i].seriesSource);
            res.yearIsUser = IsUserSource(gModel.books[i].yearSource);
            break;
        }
    }
    LeaveLib();
    return res;
}

static Str PortableTitle(const CoverBookInfo& b) {
    return b.titleIsUser ? b.title : Str();
}
static Str PortableAuthor(const CoverBookInfo& b) {
    return b.authorIsUser ? b.author : Str();
}
static Str PortableSeries(const CoverBookInfo& b) {
    return b.seriesIsUser ? b.series : Str();
}
static int PortableYear(const CoverBookInfo& b) {
    return b.yearIsUser ? b.year : 0;
}

static void FreeCoverBookInfo(CoverBookInfo& info) {
    str::Free(info.path);
    str::Free(info.title);
    str::Free(info.author);
    str::Free(info.series);
    str::Free(info.seriesParent);
    str::Free(info.genre);
    str::Free(info.subgenre);
    str::Free(info.tags);
    str::Free(info.partitions);
    info = {};
}

static void SyncEmbeddedRecords(LoadRecordCache* recordCache) {
    int count;
    EnterLib();
    count = gModel.nBooks;
    LeaveLib();
    int pdfSidecarCtxBefore = 0, pdfSidecarOpenedBefore = 0, blobBefore = 0, coverBefore = 0;
    PdfSidecarPerfCounters(&pdfSidecarCtxBefore, &pdfSidecarOpenedBefore, &blobBefore, &coverBefore);
    int readCountBefore = 0, writeCountBefore = 0;
    LibrarySidecarPerfCounters(&readCountBefore, &writeCountBefore);
    u64 startMs = GetTickCount64();
    int adoptedHits = 0;
    int writes = 0;
    for (int i = 0; i < count; i++) {
        Str id;
        EnterLib();
        if (i < gModel.nBooks) {
            id = str::Dup(gModel.books[i].id);
        }
        LeaveLib();
        if (len(id) == 0) {
            str::Free(id);
            continue;
        }
        CoverBookInfo book = CoverBookInfoById(id);
        str::Free(id);
        FileState* fs = FileHistoryFindByPath(book.path);
        BlobStats stats;
        BlobStats* statsPtr = nullptr;
        if (fs && (fs->lastReadAt > 0 || fs->timeSpentMs > 0 || fs->openCount > 0 || fs->maxPageReached > 0)) {
            stats.lastReadAt = fs->lastReadAt;
            stats.timeSpentMs = fs->timeSpentMs;
            stats.openCount = fs->openCount;
            stats.pageNo = fs->pageNo;
            stats.percentRead = book.pages > 0 ? fs->maxPageReached * 100LL / book.pages : 0;
            statsPtr = &stats;
        }
        bool carries = len(book.series) > 0 || len(book.seriesParent) > 0 || len(book.genre) > 0 ||
                       len(book.subgenre) > 0 || len(book.tags) > 0 || len(book.partitions) > 0 || statsPtr ||
                       LibrarySidecarHas(book.path);
        if (len(book.path) > 0 && carries) {
            // chunk 30: if the adopt pass in this same LoadModelThread already
            // read this book's record, reuse it instead of opening the PDF /
            // parsing the LZMA2 blob again just to learn "nothing changed".
            // For a 231-book warm load this drops the read count of the
            // second sweep from N to 0.
            const BookBlobRecord* cached = recordCache ? recordCache->Find(book.path) : nullptr;
            if (cached) {
                adoptedHits++;
            }
            int writeCountPre = 0;
            LibrarySidecarPerfCounters(nullptr, &writeCountPre);
            LibrarySidecarWriteMetadataWithRec(book.path, cached, PortableTitle(book), PortableAuthor(book),
                                               PortableSeries(book), book.seriesParent, book.genre, book.subgenre,
                                               book.tags, book.partitions, book.seriesIndex, PortableYear(book),
                                               book.pages, statsPtr);
            int writeCountPost = 0;
            LibrarySidecarPerfCounters(nullptr, &writeCountPost);
            // If WriteMetadata decided a write was actually needed, the file
            // on disk is now newer than the cached record we just used for
            // comparison. Drop the cache entry so a subsequent sweep in the
            // same load would re-read it instead of comparing against
            // pre-write state.
            if (recordCache && cached && writeCountPost > writeCountPre) {
                recordCache->Invalidate(book.path);
                writes++;
            }
        }
        FreeCoverBookInfo(book);
    }
    u64 endMs = GetTickCount64();
    int pdfSidecarCtxAfter = 0, pdfSidecarOpenedAfter = 0, blobAfter = 0, coverAfter = 0;
    PdfSidecarPerfCounters(&pdfSidecarCtxAfter, &pdfSidecarOpenedAfter, &blobAfter, &coverAfter);
    int readCountAfter = 0, writeCountAfter = 0;
    LibrarySidecarPerfCounters(&readCountAfter, &writeCountAfter);
    logf(
        "SyncEmbeddedRecords: %d books in %llu ms; PdfSidecar ctx +%d, opened +%d, blob decoded +%d, cover decoded "
        "+%d; LibrarySidecar reads +%d, writes +%d (adopt hits %d, invalidations %d)\n",
        count, (unsigned long long)(endMs - startMs), pdfSidecarCtxAfter - pdfSidecarCtxBefore,
        pdfSidecarOpenedAfter - pdfSidecarOpenedBefore, blobAfter - blobBefore, coverAfter - coverBefore,
        readCountAfter - readCountBefore, writeCountAfter - writeCountBefore, adoptedHits, writes);
}

static Str BookPathById(Str id) {
    CoverBookInfo info = CoverBookInfoById(id);
    Str path = info.path;
    info.path = {};
    FreeCoverBookInfo(info);
    return path;
}

// Test hook: walks every book in the in-memory model the way SyncEmbeddedRecords
// does, but does it synchronously and reports a single-line perf summary so a
// test can parse it. Caller must be on the UI thread (uses gModel and friends).
TempStr RunBenchSyncOnce() {
    int count;
    EnterLib();
    count = gModel.nBooks;
    LeaveLib();
    int pdfCtxBefore = 0, openedBefore = 0, blobBefore = 0, coverBefore = 0;
    PdfSidecarPerfCounters(&pdfCtxBefore, &openedBefore, &blobBefore, &coverBefore);
    u64 startMs = GetTickCount64();
    int nWithSidecar = 0;
    int nWrote = 0;
    for (int i = 0; i < count; i++) {
        Str id;
        EnterLib();
        if (i < gModel.nBooks) {
            id = str::Dup(gModel.books[i].id);
        }
        LeaveLib();
        if (len(id) == 0) {
            str::Free(id);
            continue;
        }
        CoverBookInfo book = CoverBookInfoById(id);
        str::Free(id);
        FileState* fs = FileHistoryFindByPath(book.path);
        BlobStats stats;
        BlobStats* statsPtr = nullptr;
        if (fs && (fs->lastReadAt > 0 || fs->timeSpentMs > 0 || fs->openCount > 0 || fs->maxPageReached > 0)) {
            stats.lastReadAt = fs->lastReadAt;
            stats.timeSpentMs = fs->timeSpentMs;
            stats.openCount = fs->openCount;
            stats.pageNo = fs->pageNo;
            stats.percentRead = book.pages > 0 ? fs->maxPageReached * 100LL / book.pages : 0;
            statsPtr = &stats;
        }
        bool hasNewMetadata = len(book.series) > 0 || len(book.seriesParent) > 0 || len(book.genre) > 0 ||
                              len(book.subgenre) > 0 || len(book.tags) > 0 || len(book.partitions) > 0 || statsPtr;
        // `carries` means "there is data worth embedding". We only write
        // the sidecar when the file doesn't already have one — otherwise
        // this loop becomes "open the PDF and incrementally save it
        // again" for every book in the library on every load, which is
        // why the user's machine fans up. (LibrarySidecarHas now walks
        // the PieceInfo dict without decoding the stream, so it is
        // cheap enough to call for every book.)
        bool hasSidecar = len(book.path) > 0 && LibrarySidecarHas(book.path);
        if (hasSidecar) {
            nWithSidecar++;
        }
        if (len(book.path) > 0 && hasNewMetadata && !hasSidecar) {
            LibrarySidecarWriteMetadata(book.path, PortableTitle(book), PortableAuthor(book), PortableSeries(book),
                                        book.seriesParent, book.genre, book.subgenre, book.tags, book.partitions,
                                        book.seriesIndex, PortableYear(book), book.pages, statsPtr);
            nWrote++;
        }
        FreeCoverBookInfo(book);
    }
    u64 endMs = GetTickCount64();
    int pdfCtxAfter = 0, openedAfter = 0, blobAfter = 0, coverAfter = 0;
    PdfSidecarPerfCounters(&pdfCtxAfter, &openedAfter, &blobAfter, &coverAfter);
    return str::FormatTemp(
        "OK books=%d withSidecar=%d wrote=%d ms=%llu pdfCtx=%d pdfOpen=%d blobDecode=%d coverDecode=%d", count,
        nWithSidecar, nWrote, (unsigned long long)(endMs - startMs), pdfCtxAfter - pdfCtxBefore,
        openedAfter - openedBefore, blobAfter - blobBefore, coverAfter - coverBefore);
}

static void CoverWorker(LibJob* job) {
    FreeJob(job);
    for (;;) {
        CoverSlot* pick = nullptr;
        EnterLib();
        for (int i = 0; i < gNCovers; i++) {
            CoverSlot& s = gCovers[i];
            if (s.wanted && !s.fetching && !s.failed && len(s.bytes) == 0) {
                s.fetching = true;
                pick = &s;
                break;
            }
        }
        if (!pick) {
            gWorkers--;
            LeaveLib();
            return;
        }
        TempStr key = str::DupTemp(pick->key);
        int gen = pick->gen;
        LeaveLib();

        bool isBook = !str::StartsWith(key, StrL("http")) && !str::StartsWith(key, Str(kDeskCoverKey));
        Str img = isBook ? ThumbRead(key) : Str{};
        // `coverAlreadyCached` means the thumb cache already has a usable
        // cover for this book. When true, we must not re-open the PDF
        // (LibrarySidecarReadCover + CoverBuildForBook) just to redo
        // work that was already done on a previous launch: that was the
        // dominant cost on a 246-book warm launch — each "cached" cover
        // re-opened its PDF, ran the cover model, and overwrote the
        // thumb cache with the sidecar data, repeating the work on every
        // launch. On a warm launch the thumb cache IS the cover; only on
        // the very first launch (img empty) do we need to build one.
        bool coverAlreadyCached = len(img) > 0;
        if (isBook && !coverAlreadyCached) {
            CoverBookInfo book = CoverBookInfoById(key);
            if (len(book.path) > 0) {
                LibrarySidecarCover sidecar{};
                bool inTheBook = LibrarySidecarReadCover(book.path, &sidecar);
                bool hasLiteralCover = len(sidecar.data) > 64;
                if (hasLiteralCover) {
                    if (!CoverChoiceIsByHand(key)) {
                        CoverModelLearnFromImage(sidecar.data);
                        if (sidecar.kind == kBlobCoverPage) {
                            CoverModelLearnFromBookCrop(book.path, sidecar.pageNo, sidecar.rect);
                            CoverChoiceRememberPageCrop(key, sidecar.pageNo, sidecar.rect);
                        } else {
                            CoverChoiceRememberImage(key);
                        }
                    }
                    str::Free(img);
                    img = sidecar.data;
                    sidecar.data = {};
                    ThumbWrite(key, img);
                }
                if (!hasLiteralCover && sidecar.kind == kBlobCoverPage) {
                    if (!CoverChoiceIsByHand(key)) {
                        CoverModelLearnFromBookCrop(book.path, sidecar.pageNo, sidecar.rect);
                        CoverChoiceRememberPageCrop(key, sidecar.pageNo, sidecar.rect);
                    }
                    CoverBuildLocation location;
                    location.insideBook = true;
                    location.pageNo = sidecar.pageNo;
                    location.rect = sidecar.rect;
                    location.rotation = sidecar.rotation;
                    Str rebuilt = CoverBuildFromLocation(book.path, location);
                    if (len(rebuilt) > 64) {
                        str::Free(img);
                        img = rebuilt;
                        rebuilt = {};
                    }
                    str::Free(rebuilt);
                    if (len(img) > 64) {
                        ThumbWrite(key, img);
                    }
                }
                str::Free(sidecar.data);
                str::Free(sidecar.format);
                str::Free(sidecar.fingerprint);
                if (!inTheBook) {
                    CoverBuildLocation location;
                    Str built =
                        CoverBuildForBook(book.path, key, book.title, book.author, book.series, book.year, &location);
                    if (len(built) > 64) {
                        str::Free(img);
                        img = built;
                        built = {};
                        ThumbWrite(key, img);
                        if (location.pageNo > 0) {
                            LibrarySidecarWriteCoverSpot(book.path, location.pageNo, location.rect, location.rotation,
                                                         img, PortableTitle(book), PortableAuthor(book),
                                                         PortableSeries(book), PortableYear(book));
                        } else {
                            LibrarySidecarWriteCover(book.path, {}, img, PortableTitle(book), PortableAuthor(book),
                                                     PortableSeries(book), PortableYear(book));
                        }
                    }
                    str::Free(built);
                }
            }
            FreeCoverBookInfo(book);
        }
        if (len(img) == 0 && !isBook) {
            HttpRsp rsp;
            TempStr path;
            if (str::StartsWith(key, StrL("http"))) {
                path = fmt("/poster?url=%s&key=%s", UrlEncodeTemp(key), UrlEncodeTemp(key));
            } else if (str::StartsWith(key, Str(kDeskCoverKey))) {
                path = fmt("/cover?id=%s&desk=1", AfterPrefix(key, kDeskCoverKey));
            } else {
                path = fmt("/cover?id=%s", key);
            }
            if (ServiceGet(path, &rsp) && len(ToStr(rsp.data)) > 64) {
                img = str::Dup(ToStr(rsp.data));
                if (isBook) {
                    ThumbWrite(key, img);
                }
            }
        }

        EnterLib();
        pick->fetching = false;
        if (pick->gen != gen) {
            str::Free(img);
        } else if (len(img) > 64) {
            pick->bytes = img;
        } else {
            str::Free(img);
            pick->failed = true;
        }
        LeaveLib();
        Repaint();
    }
}

static void WakeCoverWorkers() {
    while (gWorkers < kCoverWorkers) {
        gWorkers++;
        RunAsync(MkFunc0<LibJob>(CoverWorker, NewJob({})), "libCover");
    }
}

static RenderedBitmap* CoverBitmap(Str key) {
    if (len(key) == 0) {
        return nullptr;
    }
    EnterLib();
    CoverSlot* s = SlotFor(key);
    if (!s) {
        LeaveLib();
        return nullptr;
    }
    s->wanted = true;
    bool needFetch = !s->decoded && !s->failed && len(s->bytes) == 0;
    bool canDecode = !s->decoded && len(s->bytes) > 0;
    RenderedBitmap* out = s->bmp;
    LeaveLib();

    if (needFetch) {
        WakeCoverWorkers();
        return nullptr;
    }
    if (!canDecode) {
        return out;
    }

    EnterLib();
    Str raw = s->bytes;
    Pixmap* px = PixmapFromData(raw);
    s->decoded = true;
    if (px) {
        s->bmp = RenderedBitmapFromPixmap(px);
    }
    out = s->bmp;
    LeaveLib();
    return out;
}

static void CoverForget(Str key) {
    EnterLib();
    for (int i = 0; i < gNCovers; i++) {
        CoverSlot& s = gCovers[i];
        if (!str::Eq(s.key, key)) {
            continue;
        }
        str::FreePtr(&s.bytes);
        delete s.bmp;
        s.bmp = nullptr;
        s.decoded = false;
        s.failed = false;
        s.wanted = false;
        s.gen++;
        break;
    }
    LeaveLib();
}

void LibraryCoverReplace(Str bookId, Str png) {
    if (len(bookId) == 0 || len(png) == 0) {
        return;
    }
    CoverBookInfo book = CoverBookInfoById(bookId);
    if (len(book.path) > 0 && LibrarySidecarWriteCover(book.path, {}, png, PortableTitle(book), PortableAuthor(book),
                                                       PortableSeries(book), PortableYear(book))) {
        ThumbWrite(bookId, png);
        CoverForget(bookId);
        Repaint();
    }
    FreeCoverBookInfo(book);
}

void LibraryCoverReplacePage(Str bookId, int pageNo, RectF rect, int rotation, Str png) {
    if (len(bookId) == 0 || len(png) == 0) {
        return;
    }
    CoverBookInfo book = CoverBookInfoById(bookId);
    if (len(book.path) > 0 &&
        LibrarySidecarWriteCoverSpot(book.path, pageNo, rect, rotation, png, PortableTitle(book), PortableAuthor(book),
                                     PortableSeries(book), PortableYear(book))) {
        ThumbWrite(bookId, png);
        CoverForget(bookId);
        Repaint();
    }
    FreeCoverBookInfo(book);
}

void LibraryCoverRevert(Str bookId) {
    if (len(bookId) == 0) {
        return;
    }
    Str bookPath = BookPathById(bookId);
    if (len(bookPath) > 0) {
        LibrarySidecarForgetCover(bookPath);
    }
    EnterThumbs();
    LibraryThumbs* thumbs = ThumbsStore();
    if (thumbs) {
        LibraryThumbsRemove(thumbs, bookId);
    }
    LeaveThumbs();
    CoverForget(bookId);
    str::Free(bookPath);
    Repaint();
}

static TempStr JsonStrTemp(Str s) {
    str::Builder b;
    b.Append("\"");
    for (int i = 0; i < len(s); i++) {
        char c = s.s[i];
        if (c == '"' || c == '\\') {
            b.Append(fmt("\\%c", c));
        } else if ((u8)c < 0x20) {
            b.Append(fmt("\\u%04x", (int)(u8)c));
        } else {
            char one[2] = {c, 0};
            b.Append(Str(one, 1));
        }
    }
    b.Append("\"");
    return str::DupTemp(ToStr(b));
}

static void PartitionThread(LibJob* job) {
    LibraryEnsureService();
    ServicePost(job->a.s, job->b);
    FreeJob(job);
    EnterLib();
    gModel.loading = true;
    LeaveLib();
    // chunk 31R: a hierarchy change (Series -> Series via
    // /series/parent, Series -> partition via /partition/assign) is
    // applied by the service. The next /library response already
    // carries the new keys, seriesKey, series, genre, subgenre.
    // We refresh the catalogue from the service without re-reading
    // any book file. The only fields retained from the old model
    // are the genuinely sidecar-only ones (seriesParent, tags) that
    // the service does not provide.
    LoadModelThread(NewJobCatalogueOnly());
}

static void PostPartition(const char* path, Str body) {
    RunAsync(MkFunc0<LibJob>(PartitionThread, NewJob(Str(path), body)), "libPartition");
}

// chunk 31R: regression-test observation helpers. They go through
// the EXACT production paths the UI uses — PostPartition for hierarchy
// changes and PostBookEdit for /book/edit — and a getter for the
// in-memory book state. They do NOT construct alternate service
// operations, do NOT touch a different URL than the UI does, and
// therefore exercise the same code path a real user click would.
TempStr TestBookStateTemp(Str bookId) {
    EnterLib();
    LibBook* found = nullptr;
    for (int i = 0; i < gModel.nBooks; i++) {
        if (str::Eq(gModel.books[i].id, bookId)) {
            found = &gModel.books[i];
            break;
        }
    }
    if (!found) {
        LeaveLib();
        return str::FormatTemp("ERR not_found id=%s\n", bookId);
    }
    // Snapshot the fields under the lock; copy out the Strs so the
    // formatted reply is safe once we drop the lock.
    Str id = str::Dup(found->id);
    Str title = str::Dup(found->title);
    Str series = str::Dup(found->series);
    Str seriesSource = str::Dup(found->seriesSource);
    Str seriesKey = str::Dup(found->seriesKey);
    Str seriesParent = str::Dup(found->seriesParent);
    Str keys = str::Dup(found->keys);
    Str genre = str::Dup(found->genre);
    Str subgenre = str::Dup(found->subgenre);
    Str tags = str::Dup(found->tags);
    Str path = str::Dup(found->path);
    LeaveLib();
    TempStr res = str::FormatTemp(
        "OK id=%s title=%s series=%s seriesSource=%s seriesKey=%s seriesParent=%s keys=%s genre=%s subgenre=%s tags=%s "
        "path=%s\n",
        id, title, series, seriesSource, seriesKey, seriesParent, keys, genre, subgenre, tags, path);
    str::Free(id);
    str::Free(title);
    str::Free(series);
    str::Free(seriesSource);
    str::Free(seriesKey);
    str::Free(seriesParent);
    str::Free(keys);
    str::Free(genre);
    str::Free(subgenre);
    str::Free(tags);
    str::Free(path);
    return res;
}

void TestTriggerPartition(Str url, Str body) {
    // chunk 31R: this is the same call the UI makes from
    // kMenuRowParentFirst / kMenuTakeOutOfPartition / partition
    // context-menu handlers. url is the service endpoint, body is the
    // JSON payload. The wrapper is a thin pass-through; it does not
    // construct an alternate service operation.
    PostPartition(url.s, body);
}

void TestTriggerBookEdit(Str body, Str bookId) {
    // chunk 31R: this is the same call PostUserFieldEdit and
    // RunEditBookMetadata make. /book/edit with the given body and
    // bookId. MetadataEditThread targets a single book, never falls
    // into LoadModelThread on the happy path, so the regression test
    // asserts the load count does NOT move when this fires.
    PostBookEdit("/book/edit", body, bookId, 0);
}

// Persist the current gModel state of a single book to its portable metadata
// (PDF sidecar, ZIP-archive META-INF/sumatra.book, or .sumatra fallback for
// unsupported containers) and verify the round-trip by reading the record
// back. Returns true on success, false if the book has no path, the write
// failed, or the read-back disagreed.
//
// This is the targeted persistence path for the /book/edit API. It writes
// exactly one book's metadata to disk instead of running the full
// SyncEmbeddedRecords sweep, which would re-open every PDF in the library.
static bool PersistBookMetadata(Str bookId) {
    if (len(bookId) == 0) {
        return false;
    }
    CoverBookInfo book = CoverBookInfoById(bookId);
    if (len(book.path) == 0) {
        FreeCoverBookInfo(book);
        return false;
    }
    FileState* fs = FileHistoryFindByPath(book.path);
    BlobStats stats;
    BlobStats* statsPtr = nullptr;
    if (fs && (fs->lastReadAt > 0 || fs->timeSpentMs > 0 || fs->openCount > 0 || fs->maxPageReached > 0)) {
        stats.lastReadAt = fs->lastReadAt;
        stats.timeSpentMs = fs->timeSpentMs;
        stats.openCount = fs->openCount;
        stats.pageNo = fs->pageNo;
        stats.percentRead = book.pages > 0 ? fs->maxPageReached * 100LL / book.pages : 0;
        statsPtr = &stats;
    }
    bool ok = LibrarySidecarWriteMetadata(book.path, PortableTitle(book), PortableAuthor(book), PortableSeries(book),
                                          book.seriesParent, book.genre, book.subgenre, book.tags, book.partitions,
                                          book.seriesIndex, PortableYear(book), book.pages, statsPtr);
    if (ok) {
        BookBlobRecord verify;
        if (LibrarySidecarReadRecord(book.path, verify)) {
            // round-trip succeeded: the sidecar still has a parseable record
        } else {
            logf("PersistBookMetadata: write ok but read-back failed for %s (%s)\n", bookId, book.path);
            ok = false;
        }
    } else {
        logf("PersistBookMetadata: write failed for %s (%s)\n", bookId, book.path);
    }
    FreeCoverBookInfo(book);
    return ok;
}

enum {
    kRevertedTitle = 1,
    kRevertedAuthor = 2,
    kRevertedSeries = 4,
    kRevertedYear = 8,
};

static void RefreshRevertedFields(Str bookId, int reverted) {
    if (reverted == 0 || len(bookId) == 0) {
        return;
    }
    int fileFields = reverted & (kRevertedTitle | kRevertedAuthor | kRevertedYear);
    LibraryAutoMeta meta;
    bool haveMeta = false;
    if (fileFields != 0) {
        Str path = BookPathById(bookId);
        if (len(path) == 0) {
            logf("RefreshRevertedFields: no path for %s\n", bookId);
        } else {
            haveMeta = LibraryReadAutoMeta(path, meta);
            str::Free(path);
            if (!haveMeta) {
                logf("RefreshRevertedFields: no automatic metadata for %s\n", bookId);
            }
        }
    }
    if (!haveMeta && !(reverted & kRevertedSeries)) {
        return;
    }
    if (haveMeta) {
        logf("RefreshRevertedFields: bookId=%s mask=%d title=%s/%s author=%s/%s year=%d/%s\n", bookId, reverted,
             meta.title, meta.titleSource, meta.author, meta.authorSource, meta.year, meta.yearSource);
    }
    EnterLib();
    for (int i = 0; i < gModel.nBooks; i++) {
        LibBook* b = &gModel.books[i];
        if (!str::Eq(b->id, bookId)) {
            continue;
        }
        if (haveMeta && (reverted & kRevertedTitle)) {
            str::ReplaceWithCopy(&b->title, meta.title);
            str::ReplaceWithCopy(&b->titleSource, meta.titleSource);
        }
        if (haveMeta && (reverted & kRevertedAuthor)) {
            str::ReplaceWithCopy(&b->author, meta.author);
            str::ReplaceWithCopy(&b->authorSource, meta.authorSource);
        }
        if (haveMeta && (reverted & kRevertedYear)) {
            b->year = meta.year;
            str::ReplaceWithCopy(&b->yearSource, meta.yearSource);
        }
        if (reverted & kRevertedSeries) {
            logf("RefreshRevertedFields: dropping reverted series '%s' for %s\n", b->series, bookId);
            str::ReplaceWithCopy(&b->series, StrL(""));
        }
        break;
    }
    if (str::Eq(gDetail.id, bookId)) {
        if (haveMeta && (reverted & kRevertedTitle)) {
            str::ReplaceWithCopy(&gDetail.title, meta.title);
        }
        if (haveMeta && (reverted & kRevertedAuthor)) {
            str::ReplaceWithCopy(&gDetail.author, meta.author);
        }
        if (haveMeta && (reverted & kRevertedYear)) {
            gDetail.year = meta.year;
        }
        if (reverted & kRevertedSeries) {
            str::ReplaceWithCopy(&gDetail.series, StrL(""));
        }
    }
    LeaveLib();
    LibraryAutoMetaFree(meta);
}

// Dedicated persistence path for /book/edit. Posts the override to the
// library service, immediately writes the resulting metadata to the book's
// file, then schedules the catalogue refresh so the UI reflects the new
// metadata. This is intentionally separate from PartitionThread so an edit
// on one book doesn't need to wait for the full catalogue reload + the
// adopt-embedded / sync-embedded passes that LoadModelThread does.
static void MetadataEditThread(LibJob* job) {
    Str bookId = str::Dup(job->c);
    Str path = str::Dup(job->a);
    Str body = str::Dup(job->b);
    int reverted = job->cleared;
    FreeJob(job);

    logf("MetadataEditThread: bookId=%s bodyLen=%d\n", bookId, len(body));

    LibraryEnsureService();
    bool posted = ServicePost(path.s, body);
    str::Free(path);
    str::Free(body);

    // Step 2 — never report success when POST fails. The edit transaction
    // did not make it to the service, so we must not call PersistBookMetadata,
    // SaveModelToStore, or Repaint (the last of which would re-paint the
    // library view with the user's value even though the service has no
    // record of the change — that would be "fake success"). The in-memory
    // state set by LibBookSetField in the UI thread stays as the user typed
    // it so the detail view still reflects the edit, but the disk and
    // library view are not updated. The user has to retry.
    if (!posted) {
        logf("POST RESULT: failed for bookId=%s\n", bookId);
        logf("MetadataEditThread: aborting — edit was not persisted because /book/edit failed\n");
        str::Free(bookId);
        return;
    }

    logf("POST RESULT: success for bookId=%s\n", bookId);

    // Step 3 — confirm the in-memory state is in sync. After a successful
    // edit both gModel.books[i].title and gDetail.title must hold the new
    // value. (LibBookSetField, called by the UI thread before this thread
    // was dispatched, is what keeps them in sync — this log just proves it.)
    {
        Str gModelTitle;
        Str gDetailTitle;
        {
            EnterLib();
            for (int i = 0; i < gModel.nBooks; i++) {
                if (str::Eq(gModel.books[i].id, bookId)) {
                    gModelTitle = gModel.books[i].title;
                    break;
                }
            }
            gDetailTitle = gDetail.title;
            LeaveLib();
        }
        logf("gModel title: %s\n", gModelTitle);
        logf("gDetail title: %s\n", gDetailTitle);
    }

    // Step 4 — keep the previous chunk's fix: do NOT call LoadModelThread()
    // here. A reload from the service would re-fetch /library, free
    // gModel, and overwrite the user's edit with whatever the service
    // returns. The catalogue refresh (when it does happen) uses
    // AdoptEmbeddedRecordFields' *Source=="user" guard to preserve the
    // user override.
    if (len(bookId) > 0) {
        bool ok = PersistBookMetadata(bookId);
        logf("MetadataEditThread: PersistBookMetadata=%d\n", (int)ok);
    }

    RefreshRevertedFields(bookId, reverted);

    EnterLib();
    SaveModelToStore(&gModel);
    LeaveLib();

    Repaint();

    str::Free(bookId);

    if (reverted & kRevertedSeries) {
        EnterLib();
        gModel.loading = true;
        LeaveLib();
        LoadModelThread(NewJob({}));
    }
}

// Post the user-driven edit to /book/edit. Unlike PostPartition (which is
// for /partition/* and /series/* operations and triggers a full sweep), this
// dispatcher routes through MetadataEditThread so the edit gets an immediate
// targeted sidecar write instead of waiting for the catalogue refresh to
// find the changed book among the rest of the library.
static void PostBookEdit(const char* path, Str body, Str bookId, int reverted) {
    logf("PostBookEdit: path=%s bookId=%s bodyLen=%d reverted=%d\n", StrL(path), bookId, len(body), reverted);
    RunAsync(MkFunc0<LibJob>(MetadataEditThread, NewJob3(Str(path), body, bookId, reverted)), "libBookEdit");
}

static void KindThread(LibJob* job) {
    LibraryEnsureService();
    ServicePost("/kind", job->a);
    FreeJob(job);
    EnterLib();
    gDesk.working = false;
    gDesk.loaded = false;
    gDesk.loading = true;
    gModel.loading = true;
    LeaveLib();
    LoadDeskThread(NewJob({}));
    // /kind is the "ignored" / "document" / "trash" path (kMenuRemoveFromLibrary,
    // kMenuIgnoreFile). It is NOT the production path for book -> Series
    // (that goes through PostUserFieldEdit -> /book/edit with
    // SeriesSource=user) nor for Series -> Series (that goes through
    // PostPartition -> /series/parent). We use Full mode here because
    // /kind can move a file between ignored and document; the
    // catalogue's books list itself changes and we want a full adopt
    // pass to pick up sidecar-only fields for any newly-visible book.
    LoadModelThread(NewJob({}));
}

static void PostKind(Str body) {
    EnterLib();
    gDesk.working = true;
    LeaveLib();
    RunAsync(MkFunc0<LibJob>(KindThread, NewJob(body)), "libKind");
}

static int DeskChosenCount() {
    int n = 0;
    for (int i = 0; i < gDesk.nFiles; i++) {
        if (gDesk.files[i].chosen) {
            n++;
        }
    }
    return n;
}

static void DeskToggle(int i) {
    if (i < 0 || i >= gDesk.nFiles) {
        return;
    }
    if (IsShiftPressed() && gDesk.anchor >= 0 && gDesk.anchor < gDesk.nFiles) {
        int from = gDesk.anchor;
        int to = i;
        if (from > to) {
            int swap = from;
            from = to;
            to = swap;
        }
        for (int k = from; k <= to; k++) {
            gDesk.files[k].chosen = true;
        }
        gDesk.anchor = i;
        return;
    }
    gDesk.files[i].chosen = !gDesk.files[i].chosen;
    gDesk.anchor = i;
    if (DeskChosenCount() == 0) {
        gDesk.selecting = false;
    }
}

static void DeskStartSelecting(int i) {
    if (i < 0 || i >= gDesk.nFiles) {
        return;
    }
    gDesk.selecting = true;
    gDesk.files[i].chosen = true;
    gDesk.anchor = i;
}

static void DeskStopSelecting() {
    gDesk.selecting = false;
    for (int i = 0; i < gDesk.nFiles; i++) {
        gDesk.files[i].chosen = false;
    }
    gDesk.anchor = -1;
}

struct ImportPreviewParser : JsonVisitor {
    LibraryImportData* d;

    explicit ImportPreviewParser(LibraryImportData* data) : d(data) {
    }

    static void Take(LibraryImportField& f, Str value) {
        str::ReplaceWithCopy(&f.value, value);
        str::ReplaceWithCopy(&f.original, value);
    }

    bool Visit(Str path, Str value, json::Type type) override {
        if (type == json::Type::Null) {
            return true;
        }
        if (str::Eq(path, StrL("/path"))) {
            str::ReplaceWithCopy(&d->path, value);
        } else if (str::Eq(path, StrL("/id"))) {
            str::ReplaceWithCopy(&d->bookId, value);
        } else if (str::Eq(path, StrL("/proposed_kind"))) {
            str::ReplaceWithCopy(&d->proposedKind, value);
        } else if (str::Eq(path, StrL("/already"))) {
            str::ReplaceWithCopy(&d->already, value);
        } else if (str::Eq(path, StrL("/ext"))) {
            str::ReplaceWithCopy(&d->ext, value);
        } else if (str::Eq(path, StrL("/excluded"))) {
            d->excluded = IsTrue(value);
        } else if (str::Eq(path, StrL("/has_writing"))) {
            d->hasWriting = IsTrue(value);
        } else if (str::Eq(path, StrL("/pages"))) {
            d->pages = atoi(CStrTemp(value));
        } else if (str::Eq(path, StrL("/size"))) {
            d->size = (i64)atof(CStrTemp(value));
        } else if (str::Eq(path, StrL("/scraped/meta"))) {
            d->scrapedMeta = IsTrue(value);
        } else if (str::Eq(path, StrL("/scraped/series"))) {
            d->scrapedSeries = IsTrue(value);
        } else if (str::Eq(path, StrL("/fields/title/value"))) {
            Take(d->title, value);
        } else if (str::Eq(path, StrL("/fields/title/source"))) {
            str::ReplaceWithCopy(&d->title.source, value);
        } else if (str::Eq(path, StrL("/fields/author/value"))) {
            Take(d->author, value);
        } else if (str::Eq(path, StrL("/fields/author/source"))) {
            str::ReplaceWithCopy(&d->author.source, value);
        } else if (str::Eq(path, StrL("/fields/series/value"))) {
            Take(d->series, value);
        } else if (str::Eq(path, StrL("/fields/series/source"))) {
            str::ReplaceWithCopy(&d->series.source, value);
        } else if (str::Eq(path, StrL("/fields/series_index/value"))) {
            Take(d->seriesIndex, value);
        } else if (str::Eq(path, StrL("/fields/series_index/source"))) {
            str::ReplaceWithCopy(&d->seriesIndex.source, value);
        } else if (str::Eq(path, StrL("/fields/year/value"))) {
            Take(d->year, value);
        } else if (str::Eq(path, StrL("/fields/year/source"))) {
            str::ReplaceWithCopy(&d->year.source, value);
        } else if (str::Eq(path, StrL("/fields/genre/value"))) {
            Take(d->genre, value);
        } else if (str::Eq(path, StrL("/fields/genre/source"))) {
            str::ReplaceWithCopy(&d->genre.source, value);
        } else if (str::Eq(path, StrL("/fields/subgenre/value"))) {
            Take(d->subgenre, value);
        } else if (str::Eq(path, StrL("/fields/subgenre/source"))) {
            str::ReplaceWithCopy(&d->subgenre.source, value);
        } else if (str::EndsWith(path, StrL("/key")) && str::StartsWith(path, StrL("/partitions["))) {
            d->partitionKeys.Append(value);
        } else if (str::EndsWith(path, StrL("/name")) && str::StartsWith(path, StrL("/partitions["))) {
            d->partitionNames.Append(value);
        }
        return true;
    }
};

static void AppendImportField(str::Builder& b, const char* name, const LibraryImportField& f, bool& first) {
    if (len(f.value) == 0) {
        return;
    }
    Str source = f.overridden ? StrL("user") : f.source;
    if (len(source) == 0) {
        return;
    }
    if (!first) {
        b.Append(",");
    }
    first = false;
    b.Append(fmt("%s:{\"value\":%s,\"source\":%s}", JsonStrTemp(Str(name)), JsonStrTemp(f.value),
                 JsonStrTemp(source)));
}

static void AppendImportCorrection(str::Builder& b, const char* name, const LibraryImportField& f, bool& first) {
    if (!f.overridden) {
        return;
    }
    if (!first) {
        b.Append(",");
    }
    first = false;
    b.Append(fmt("%s:{\"from\":%s,\"to\":%s}", JsonStrTemp(Str(name)), JsonStrTemp(f.original),
                 JsonStrTemp(f.value)));
}

static void ImportCommitThread(LibJob* job) {
    LibraryEnsureService();
    bool ok = ServicePost("/import/commit", job->a);
    logf("LibraryImport: commit posted=%d\n", (int)ok);
    FreeJob(job);
    EnterLib();
    gModel.loading = true;
    gModel.loaded = false;
    gDesk.loaded = false;
    LeaveLib();
    LoadDeskThread(NewJob({}));
    LoadModelThread(NewJob({}));
}

static void LibraryImportCommit(MainWindow* win, LibraryImportData* d) {
    if (!d || len(d->bookJson) == 0) {
        return;
    }
    str::Builder body;
    body.Append("{\"roots\":[");
    StrVec roots = LibraryStartingRoots();
    for (int i = 0; i < len(roots); i++) {
        if (i > 0) {
            body.Append(",");
        }
        body.Append(JsonStrTemp(roots.At(i)));
    }
    body.Append("],\"book\":");
    body.Append(d->bookJson);
    body.Append(fmt(",\"kind\":%s", JsonStrTemp(d->chosenKind)));
    body.Append(fmt(",\"proposed_kind\":%s", JsonStrTemp(d->proposedKind)));
    if (len(d->partitionKey) > 0) {
        body.Append(fmt(",\"partition\":%s", JsonStrTemp(d->partitionKey)));
    }
    body.Append(",\"fields\":{");
    bool first = true;
    AppendImportField(body, "title", d->title, first);
    AppendImportField(body, "author", d->author, first);
    AppendImportField(body, "series", d->series, first);
    AppendImportField(body, "year", d->year, first);
    body.Append("},\"corrections\":{");
    first = true;
    AppendImportCorrection(body, "title", d->title, first);
    AppendImportCorrection(body, "author", d->author, first);
    body.Append("}}");
    Str payload = body.TakeStr();
    logf("LibraryImport: committing %s as %s\n", d->path, d->chosenKind);
    RunAsync(MkFunc0<LibJob>(ImportCommitThread, NewJob(payload)), "libImportCommit");
    str::Free(payload);
}

struct ImportPreviewJob {
    MainWindow* win = nullptr;
    Str path;
    Str reply;
    Str bookJson;
};

static void ShowImportPreview(ImportPreviewJob* job) {
    if (!job) {
        return;
    }
    if (len(job->reply) == 0) {
        logf("LibraryImport: could not read the picked file\n");
    } else {
        auto* d = new LibraryImportData();
        str::ReplaceWithCopy(&d->path, job->path);
        str::ReplaceWithCopy(&d->bookJson, job->bookJson);
        str::ReplaceWithCopy(&d->proposedKind, StrL("book"));
        ImportPreviewParser parser(d);
        JsonParseWithVisitor(job->reply, &parser);
        str::ReplaceWithCopy(&d->chosenKind, StrL("book"));
        ShowLibraryImportWindow(job->win, d, LibraryImportCommit);
    }
    str::Free(job->path);
    str::Free(job->reply);
    str::Free(job->bookJson);
    delete job;
}

static void ImportPreviewThread(LibJob* job) {
    auto* out = new ImportPreviewJob();
    out->win = (len(gWindows) == 0) ? nullptr : gWindows[0];
    out->path = str::Dup(job->a);
    FreeJob(job);
    Str bookJson = LibraryScanOneFileToJson(out->path);
    if (len(bookJson) == 0) {
        uitask::Post(MkFunc0<ImportPreviewJob>(ShowImportPreview, out), "libImportPreview");
        return;
    }
    out->bookJson = bookJson;
    str::Builder body;
    body.Append("{\"roots\":[");
    StrVec roots = LibraryStartingRoots();
    for (int i = 0; i < len(roots); i++) {
        if (i > 0) {
            body.Append(",");
        }
        body.Append(JsonStrTemp(roots.At(i)));
    }
    body.Append("],\"book\":");
    body.Append(bookJson);
    body.Append("}");
    Str payload = body.TakeStr();
    LibraryEnsureService();
    HttpRsp rsp;
    TempStr url = fmt("http://127.0.0.1:%d/import/preview", LibraryServicePort());
    if (HttpPostUrl(Str(url), StrL("application/json"), Str(), payload, &rsp) && IsHttpRspOk(&rsp)) {
        out->reply = str::Dup(ToStr(rsp.data));
    }
    str::Free(payload);
    uitask::Post(MkFunc0<ImportPreviewJob>(ShowImportPreview, out), "libImportPreview");
}

void LibraryImportBook(MainWindow* win) {
    if (!win) {
        return;
    }
    TempStr picked = PickOneDocumentFileTemp(win->hwndFrame);
    if (len(picked) == 0) {
        logf("LibraryImport: the file picker was cancelled\n");
        return;
    }
    logf("LibraryImport: picked %s\n", picked);
    RunAsync(MkFunc0<LibJob>(ImportPreviewThread, NewJob(Str(picked))), "libImportPreview");
}

int LibraryIgnoreDays() {
    int days = gGlobalPrefs ? gGlobalPrefs->audiobook.libraryIgnoreDays : 30;
    if (days < 1) {
        days = 30;
    }
    if (days > 3650) {
        days = 3650;
    }
    return days;
}

static void MoveDeskChosen(const char* kind) {
    str::Builder b;
    b.Append("{\"kind\":");
    b.Append(JsonStrTemp(Str(kind)));
    b.Append(fmt(",\"days\":%d", LibraryIgnoreDays()));
    b.Append(",\"paths\":[");
    int n = 0;
    for (int i = 0; i < gDesk.nFiles; i++) {
        if (!gDesk.files[i].chosen) {
            continue;
        }
        if (n > 0) {
            b.AppendChar(',');
        }
        b.Append(JsonStrTemp(gDesk.files[i].path));
        n++;
    }
    b.Append("]}");
    if (n == 0) {
        return;
    }
    Str body = b.TakeStr();
    PostKind(body);
    str::Free(body);
    DeskStopSelecting();
}

static void MoveOneFile(Str path, const char* kind) {
    if (len(path) == 0) {
        return;
    }
    TempStr body = fmt("{\"kind\":%s,\"days\":%d,\"paths\":[%s]}", JsonStrTemp(Str(kind)), LibraryIgnoreDays(),
                       JsonStrTemp(path));
    PostKind(Str(body));
}

void LibraryRequestScanCancel() {
    InterlockedExchange(&gScanCancel, 1);
}

void LibraryRequestScanCancelAndWait() {
    InterlockedExchange(&gScanCancel, 1);
    int spinCount = 0;
    while (gNativeScanning) {
        SleepInMs(10);
        spinCount++;
        if (spinCount > 600) {
            break;
        }
    }
}

void LibraryFreeCache() {
    gScanCancel = true;
    if (gScanAnimationTimer != 0 && gNotifyHwnd) {
        KillTimer(gNotifyHwnd, gScanAnimationTimer);
        gScanAnimationTimer = 0;
    }
    CoverEditorShutdown();
    EnterLib();
    FreePartitions();
    for (int i = 0; i < gNCovers; i++) {
        delete gCovers[i].bmp;
        str::Free(gCovers[i].key);
        str::Free(gCovers[i].bytes);
        gCovers[i] = CoverSlot{};
    }
    gNCovers = 0;
    FreeModel(&gModel);
    str::Free(gModel.scanItem);
    gModel.scanItem = {};
    str::Free(gModel.scanItemAction);
    gModel.scanItemAction = {};
    FreeDetail(&gDetail);
    FreeDesk(&gDesk);
    gDesk.loaded = false;
    str::Free(gModel.filter);
    gModel.filter = {};
    gModel.loaded = false;
    gModel.loadFailed = false;
    LeaveLib();
    EnterThumbs();
    LibraryThumbsClose(gThumbs);
    gThumbs = nullptr;
    gThumbsTried = false;
    LeaveThumbs();
}

struct LibHit {
    Rect rect;
    Str target;
    Str tip;
};

struct LibLayout {
    MainWindow* win = nullptr;
    HDC hdc = nullptr;
    Rect rc;
    Rect rcRail;
    Rect rcMain;
    int contentDy = 0;
    int scrollY = 0;
};

static int gContentDy = 0;
static int gRailScrollY = 0;
static int gRailDy = 0;
static Rect gRailBand;

struct LibScrollBar {
    Rect track;
    Rect thumb;
    int contentDy = 0;
    int viewDy = 0;
    bool live = false;
};

static LibScrollBar gRailBar;
static LibScrollBar gMainBar;
static LibScrollBar* gBarDrag = nullptr;
static LibScrollBar* gBarHot = nullptr;
static int gBarGrabDy = 0;

static void AddLink(MainWindow* win, Rect r, Str target, Str tip = {}) {
    if (r.dy <= 0 || r.dx <= 0) {
        return;
    }
    win->staticLinks.Append(new StaticLink(r, target, tip));
}

static void DrawTextIn(HDC hdc, Rect r, Str text, UINT fmtFlags, COLORREF col, bool underline = false) {
    if (len(text) == 0 || r.dy <= 0) {
        return;
    }
    TempWStr ws = ToWStrTemp(text);
    RECT rc = {r.x, r.y, r.x + r.dx, r.y + r.dy};
    SetTextColor(hdc, col);
    if (underline) {
        // LOGFONT.lfUnderline = 1 makes Win32 actually paint an
        // underline (DrawText alone doesn't). We need a fresh
        // underlined font — selecting the current one with underline
        // can leak into other draws that share the HDC.
        LOGFONT lf{};
        HFONT cur = (HFONT)GetCurrentObject(hdc, OBJ_FONT);
        if (cur && GetObjectW(cur, sizeof(lf), &lf) > 0) {
            lf.lfUnderline = 1;
            HFONT underlined = CreateFontIndirectW(&lf);
            HFONT prev = (HFONT)SelectObject(hdc, underlined);
            DrawTextW(hdc, ws.s, ws.len, &rc, fmtFlags);
            SelectObject(hdc, prev);
            DeleteObject(underlined);
        } else {
            DrawTextW(hdc, ws.s, ws.len, &rc, fmtFlags);
        }
    } else {
        DrawTextW(hdc, ws.s, ws.len, &rc, fmtFlags);
    }
}

static int MeasureTextDy(HDC hdc, Rect r, Str text, UINT fmtFlags) {
    if (len(text) == 0) {
        return 0;
    }
    TempWStr ws = ToWStrTemp(text);
    RECT rc = {r.x, r.y, r.x + r.dx, r.y + r.dy};
    DrawTextW(hdc, ws.s, ws.len, &rc, fmtFlags | DT_CALCRECT);
    return rc.bottom - rc.top;
}

static void FillRound(HDC hdc, Rect r, COLORREF col, int radius) {
    AutoDeleteBrush br(CreateSolidBrush(col));
    AutoDeletePen pen(CreatePen(PS_SOLID, 1, col));
    ScopedSelectObject sb(hdc, br);
    ScopedSelectObject sp(hdc, pen);
    RoundRect(hdc, r.x, r.y, r.x + r.dx, r.y + r.dy, radius, radius);
}

static COLORREF Mix(COLORREF a, COLORREF b, int pct);

static void DrawScanBar(HDC hdc, Rect r, int done, int total, bool indeterminate) {
    COLORREF bg = ThemeMainWindowBackgroundColor();
    COLORREF text = ThemeWindowTextColor();
    FillRound(hdc, r, Mix(bg, text, 12), r.dy / 2);
    Rect fill = r;
    if (indeterminate || total <= 0) {
        fill.dx = DpiScale(24) > r.dx / 4 ? DpiScale(24) : r.dx / 4;
        int travel = r.dx - fill.dx > 1 ? r.dx - fill.dx : 1;
        fill.x += (int)((GetTickCount64() / 18) % (u64)travel);
    } else {
        fill.dx = (int)((i64)r.dx * limitValue(done, 0, total) / total);
    }
    if (fill.dx > 0) {
        FillRound(hdc, fill, ThemeWindowLinkColor(), r.dy / 2);
    }
}

static COLORREF Mix(COLORREF a, COLORREF b, int pct) {
    int r = (GetRValue(a) * (100 - pct) + GetRValue(b) * pct) / 100;
    int g = (GetGValue(a) * (100 - pct) + GetGValue(b) * pct) / 100;
    int bl = (GetBValue(a) * (100 - pct) + GetBValue(b) * pct) / 100;
    return RGB(r, g, bl);
}

static int BarWidth(HDC hdc) {
    return DpiScale(12);
}

static void LayoutBar(HDC hdc, LibScrollBar* bar, Rect track, int contentDy, int viewDy, int pos) {
    *bar = LibScrollBar{};
    if (viewDy <= 0 || track.dy <= 0 || contentDy <= viewDy) {
        return;
    }
    bar->live = true;
    bar->track = track;
    bar->contentDy = contentDy;
    bar->viewDy = viewDy;
    int least = DpiScale(30);
    int thumbDy = (int)((i64)track.dy * viewDy / contentDy);
    thumbDy = limitValue(thumbDy, least, track.dy);
    int span = track.dy - thumbDy;
    int most = contentDy - viewDy;
    int at = (most > 0) ? (int)((i64)span * limitValue(pos, 0, most) / most) : 0;
    bar->thumb = Rect(track.x, track.y + at, track.dx, thumbDy);
}

static void DrawBar(HDC hdc, const LibScrollBar& bar, bool hot) {
    if (!bar.live) {
        return;
    }
    COLORREF bg = ThemeMainWindowBackgroundColor();
    COLORREF text = ThemeWindowTextColor();
    int inset = DpiScale(3);
    int wide = bar.track.dx - 2 * inset;
    if (wide < 2) {
        wide = 2;
    }
    FillRound(hdc, Rect(bar.track.x + inset, bar.track.y, wide, bar.track.dy), Mix(bg, text, 7), wide);
    FillRound(hdc, Rect(bar.thumb.x + inset, bar.thumb.y, wide, bar.thumb.dy), Mix(bg, text, hot ? 55 : 30), wide);
}

static int PosFromBar(const LibScrollBar& bar, int y) {
    int span = bar.track.dy - bar.thumb.dy;
    if (span <= 0) {
        return 0;
    }
    int most = bar.contentDy - bar.viewDy;
    int at = limitValue(y - bar.track.y, 0, span);
    return (int)((i64)most * at / span);
}

static bool BookVisible(const LibBook& b) {
    if (len(gModel.filter) == 0) {
        return true;
    }
    if (len(b.keys) == 0) {
        return false;
    }
    TempStr want = fmt("|%s|", gModel.filter);
    return str::Contains(b.keys, want.s);
}

static TempStr SubtitleTemp(const LibBook& b) {
    str::Builder s;
    if (b.volume > 0) {
        s.Append(fmt("#%d", b.volume));
    }
    if (len(b.author) > 0) {
        if (len(ToStr(s)) > 0) {
            s.Append(" \xc2\xb7 ");
        }
        s.Append(b.author);
    }
    if (len(ToStr(s)) == 0 && b.pages > 0) {
        s.Append(fmt("%d pages", b.pages));
    }
    return str::DupTemp(ToStr(s));
}

constexpr int kCoverBoxDx = 132;
constexpr int kCoverBoxDy = 196;
constexpr int kCoverBoxCorner = 8;
constexpr int kTileCaptionDy = 44;

static Rect CoverFitInBox(Size sz, Rect box) {
    Rect fit = box;
    if (sz.dx < 1 || sz.dy < 1) {
        return fit;
    }
    double want = (double)box.dx / (double)box.dy;
    double have = (double)sz.dx / (double)sz.dy;
    if (have > want) {
        fit.dy = (int)(box.dx / have);
        fit.y = box.y + (box.dy - fit.dy);
    } else {
        fit.dx = (int)(box.dy * have);
        fit.x = box.x + (box.dx - fit.dx) / 2;
    }
    return fit;
}

static Rect DrawCoverInBox(HDC hdc, Rect box, RenderedBitmap* bmp, COLORREF fill) {
    FillRound(hdc, box, fill, kCoverBoxCorner);
    if (!bmp || !bmp->IsValid()) {
        return box;
    }
    Rect fit = CoverFitInBox(bmp->GetSize(), box);
    int saved = SaveDC(hdc);
    HRGN clip =
        CreateRoundRectRgn(box.x, box.y, box.x + box.dx + 1, box.y + box.dy + 1, kCoverBoxCorner, kCoverBoxCorner);
    ExtSelectClipRgn(hdc, clip, RGN_AND);
    bmp->Blit(hdc, fit);
    RestoreDC(hdc, saved);
    DeleteObject(clip);
    return fit;
}

static void DrawCoverTile(HDC hdc, MainWindow* win, Rect tile, const LibBook& b, HFONT fontTitle, HFONT fontSub) {
    COLORREF bg = ThemeMainWindowBackgroundColor();
    COLORREF text = ThemeWindowTextColor();
    COLORREF dim = Mix(text, bg, 45);

    int coverDy = tile.dy - DpiScale(kTileCaptionDy);
    Rect rcCover(tile.x, tile.y, tile.dx, coverDy);

    RenderedBitmap* bmp = CoverBitmap(b.id);
    Rect fit = DrawCoverInBox(hdc, rcCover, bmp, Mix(bg, text, 12));
    if (!bmp || !bmp->IsValid()) {
        Rect inner(rcCover.x + DpiScale(8), rcCover.y + rcCover.dy / 3, rcCover.dx - DpiScale(16), rcCover.dy / 3);
        SelectObject(hdc, fontSub);
        DrawTextIn(hdc, inner, b.title, DT_CENTER | DT_WORDBREAK | DT_NOPREFIX | DT_END_ELLIPSIS, dim);
    }

    if (b.booknlp) {
        int d = DpiScale(9);
        Rect dot(fit.x + fit.dx - d - DpiScale(5), fit.y + DpiScale(5), d, d);
        FillRound(hdc, dot, RGB(93, 160, 40), d);
    }

    Rect rcTitle(tile.x, tile.y + coverDy + DpiScale(6), tile.dx, DpiScale(18));
    SelectObject(hdc, fontTitle);
    DrawTextIn(hdc, rcTitle, b.title, DT_LEFT | DT_SINGLELINE | DT_END_ELLIPSIS | DT_NOPREFIX, text);

    Rect rcSub(tile.x, rcTitle.y + rcTitle.dy, tile.dx, DpiScale(16));
    SelectObject(hdc, fontSub);
    DrawTextIn(hdc, rcSub, SubtitleTemp(b), DT_LEFT | DT_SINGLELINE | DT_END_ELLIPSIS | DT_NOPREFIX, dim);

    TempStr open = fmt("%s%s", Str(kLinkOpen), b.path);
    if (len(b.path) > 0) {
        AddLink(win, rcCover, Str(open), StrL("Open where you left off"));
    }
    TempStr target = fmt("%s%s", Str(kLinkBook), b.id);
    Rect rcText(tile.x, rcTitle.y, tile.dx, rcTitle.dy + rcSub.dy);
    AddLink(win, rcText, Str(target), StrL("About this book"));
}

struct LibSortChoice {
    const char* key;
    const char* label;
    const char* tip;
};

static const LibSortChoice gSortChoices[] = {
    {"alpha", "A-Z", "Sort the series by name"},
    {"genre", "Genre", "Group the series under their genre"},
    {"most", "Most", "Biggest series first"},
    {"fewest", "Fewest", "Smallest series first"},
};

static int DrawSortRow(HDC hdc, MainWindow* win, Rect row, HFONT font) {
    COLORREF bg = ThemeMainWindowBackgroundColor();
    COLORREF text = ThemeWindowTextColor();
    COLORREF dim = Mix(text, bg, 45);
    Str now = LibrarySortOrder();

    SelectObject(hdc, font);
    Rect rcLabel(row.x, row.y, DpiScale(32), row.dy);
    DrawTextIn(hdc, rcLabel, StrL("Sort"), DT_LEFT | DT_SINGLELINE | DT_VCENTER | DT_NOPREFIX, dim);
    int x = rcLabel.x + rcLabel.dx;
    int y = row.y;
    for (const LibSortChoice& c : gSortChoices) {
        Str label(c.label);
        TempWStr ws = ToWStrTemp(label);
        SIZE sz{};
        GetTextExtentPoint32W(hdc, ws.s, ws.len, &sz);
        int dx = sz.cx + DpiScale(10);
        if (x + dx > row.x + row.dx) {
            x = rcLabel.x + rcLabel.dx;
            y += row.dy;
        }
        Rect one(x, y, dx, row.dy);
        bool active = str::Eq(now, Str(c.key));
        if (active) {
            FillRound(hdc, one, Mix(bg, text, 16), 5);
        }
        DrawTextIn(hdc, one, label, DT_CENTER | DT_SINGLELINE | DT_VCENTER | DT_NOPREFIX,
                   active ? text : ThemeWindowLinkColor());
        AddLink(win, one, fmt("%s%s", Str(kLinkSort), Str(c.key)), Str(c.tip));
        x += dx + DpiScale(2);
    }
    return y + row.dy - row.y;
}

static void DrawRail(HDC hdc, MainWindow* win, Rect rail, HFONT fontRow, HFONT fontHead) {
    COLORREF bg = ThemeMainWindowBackgroundColor();
    COLORREF text = ThemeWindowTextColor();
    COLORREF dim = Mix(text, bg, 45);
    COLORREF sel = Mix(bg, text, 14);

    HdcFillRect(hdc, rail, Mix(bg, text, 5));

    int pad = DpiScale(12);
    int y = rail.y + pad;
    int rowDy = DpiScale(26);

    SelectObject(hdc, fontHead);
    Rect rcHead(rail.x + pad, y, rail.dx - 2 * pad, DpiScale(22));
    DrawTextIn(hdc, rcHead, StrL("Library"), DT_LEFT | DT_SINGLELINE | DT_NOPREFIX, text);
    y += rcHead.dy + DpiScale(10);

    SelectObject(hdc, fontRow);
    {
        Rect row(rail.x + DpiScale(6), y, rail.dx - DpiScale(12), rowDy);
        if (len(gModel.filter) == 0 && !gDeskOpen) {
            FillRound(hdc, row, sel, 6);
        }
        Rect label(row.x + DpiScale(8), row.y, row.dx - DpiScale(16), row.dy);
        TempStr all = fmt("All books  (%d)", gModel.total);
        DrawTextIn(hdc, label, Str(all), DT_LEFT | DT_SINGLELINE | DT_VCENTER | DT_NOPREFIX | DT_END_ELLIPSIS, text);
        AddLink(win, row, Str(kLinkAllBooks));
        y += rowDy + DpiScale(4);
    }
    {
        Rect row(rail.x + DpiScale(6), y, rail.dx - DpiScale(12), rowDy);
        if (gDeskOpen) {
            FillRound(hdc, row, sel, 6);
        }
        Rect label(row.x + DpiScale(8), row.y, row.dx - DpiScale(16), row.dy);
        TempStr desk = fmt("Deskpan  (%d)", gModel.documents);
        DrawTextIn(hdc, label, Str(desk), DT_LEFT | DT_SINGLELINE | DT_VCENTER | DT_NOPREFIX | DT_END_ELLIPSIS, text);
        AddLink(win, row, Str(kLinkDeskpan), StrL("Files that are not books: manuals, invoices, forms"));
        y += rowDy + DpiScale(4);
    }

    Rect rcSort(rail.x + DpiScale(10), y, rail.dx - DpiScale(16), DpiScale(22));
    y += DrawSortRow(hdc, win, rcSort, fontRow) + DpiScale(8);

    int footDy = DpiScale(24);
    int footerRows = gModel.scanning ? 6 : 4;
    int lastY = rail.y + rail.dy - footerRows * footDy - 2 * pad;
    int headDy = DpiScale(20);
    int firstY = y;
    int bandDy = lastY - firstY;
    int stepDy = rowDy + DpiScale(2);
    int needDy = 0;
    for (int i = 0; i < gModel.nSeries; i++) {
        const LibSeries& s = gModel.series[i];
        needDy += (len(s.head) > 0 ? headDy : 0) + (len(s.subhead) > 0 ? headDy : 0) + stepDy;
    }
    int barDx = BarWidth(hdc);
    gRailDy = needDy;
    gRailBand = Rect(rail.x, firstY, rail.dx, bandDy);
    gRailScrollY = limitValue(gRailScrollY, 0, needDy > bandDy ? needDy - bandDy : 0);
    LayoutBar(hdc, &gRailBar, Rect(rail.x + rail.dx - barDx, firstY, barDx, bandDy), needDy, bandDy, gRailScrollY);
    int gutter = gRailBar.live ? barDx : 0;
    if (gRailScrollY > 0) {
        y -= gRailScrollY;
    }
    for (int i = 0; i < gModel.nSeries; i++) {
        const LibSeries& s = gModel.series[i];
        int above = (len(s.head) > 0 ? headDy : 0) + (len(s.subhead) > 0 ? headDy : 0);
        if (y + above + rowDy > lastY) {
            y += above + rowDy + DpiScale(2);
            continue;
        }
        if (y + above < firstY) {
            y += above + rowDy + DpiScale(2);
            continue;
        }
        SelectObject(hdc, fontRow);
        if (len(s.head) > 0) {
            Rect rcGenre(rail.x + DpiScale(8), y, rail.dx - DpiScale(14) - gutter, headDy);
            DrawTextIn(hdc, rcGenre, s.head, DT_LEFT | DT_SINGLELINE | DT_NOPREFIX | DT_END_ELLIPSIS, text);
            y += headDy;
        }
        if (len(s.subhead) > 0) {
            Rect rcSub(rail.x + DpiScale(14), y, rail.dx - DpiScale(20) - gutter, headDy);
            DrawTextIn(hdc, rcSub, s.subhead, DT_LEFT | DT_SINGLELINE | DT_NOPREFIX | DT_END_ELLIPSIS, dim);
            y += headDy;
        }
        Rect row(rail.x + DpiScale(6), y, rail.dx - DpiScale(12) - gutter, rowDy);
        if (str::EqI(gModel.filter, s.key)) {
            FillRound(hdc, row, sel, 6);
        }
        int indent = DpiScale(8) + s.depth * DpiScale(14);
        Rect label(row.x + indent, row.y, row.dx - indent - DpiScale(34), row.dy);
        DrawTextIn(hdc, label, s.name, DT_LEFT | DT_SINGLELINE | DT_VCENTER | DT_NOPREFIX | DT_END_ELLIPSIS, text);
        Rect count(row.x + row.dx - DpiScale(32), row.y, DpiScale(28), row.dy);
        DrawTextIn(hdc, count, fmt("%d", s.books), DT_RIGHT | DT_SINGLELINE | DT_VCENTER | DT_NOPREFIX, dim);
        TempStr target = fmt("%s%s", Str(kLinkSeries), s.key);
        TempStr tip = fmt("%s \xc2\xb7 %d books", s.name, s.books);
        if (len(s.genre) > 0) {
            tip = fmt("%s \xc2\xb7 %s", Str(tip), s.genre);
        }
        if (len(s.sub) > 0) {
            tip = fmt("%s \xe2\x80\xba %s", Str(tip), s.sub);
        }
        if (len(s.author) > 0) {
            tip = fmt("%s \xc2\xb7 %s", Str(tip), s.author);
        }
        if (len(s.wiki) > 0) {
            tip = fmt("%s \xc2\xb7 wiki: %s (%d facts)", Str(tip), s.wiki, s.facts);
        }
        if (len(s.guessed) > 0) {
            tip = fmt("%s \xc2\xb7 put here because it says \"%s\"", Str(tip), s.guessed);
        }
        AddLink(win, row, Str(target), Str(tip));
        y += rowDy + DpiScale(2);
    }
    DrawBar(hdc, gRailBar, gBarHot == &gRailBar || gBarDrag == &gRailBar);

    Rect rcRescan(rail.x + DpiScale(6), rail.y + rail.dy - footerRows * footDy - pad, rail.dx - DpiScale(12), footDy);
    SelectObject(hdc, fontRow);
    Str rescanLabel = StrL("Rescan library");
    if (gModel.scanning) {
        rescanLabel = gModel.scanTotal > 0 ? Str(fmt("Scanning %d of %d...", gModel.scanDone, gModel.scanTotal))
                                           : StrL("Scanning...");
    }
    DrawTextIn(hdc, Rect(rcRescan.x + DpiScale(8), rcRescan.y, rcRescan.dx, rcRescan.dy), rescanLabel,
               DT_LEFT | DT_SINGLELINE | DT_VCENTER | DT_NOPREFIX, ThemeWindowLinkColor());
    if (!gModel.scanning) {
        AddLink(win, rcRescan, Str(kLinkRescan), StrL("Look for new books on disk"));
    }

    int nextY = rcRescan.y + footDy;
    if (gModel.scanning) {
        EnsureScanAnimationTimer(win->hwndCanvas);
        Str item = gModel.scanItem;
        TempStr itemLabel;
        if (gModel.scanItemTotal > 0) {
            itemLabel =
                fmt("%s — %s — %d of %d", gModel.scanItemAction, item, gModel.scanItemDone, gModel.scanItemTotal);
        } else if (len(item) > 0) {
            itemLabel = fmt("%s — %s", gModel.scanItemAction, item);
        } else {
            itemLabel = str::DupTemp(gModel.scanItemAction);
        }
        Rect rcItem(rcRescan.x + DpiScale(8), nextY, rcRescan.dx - DpiScale(8), footDy);
        DrawTextIn(hdc, rcItem, itemLabel, DT_LEFT | DT_SINGLELINE | DT_VCENTER | DT_NOPREFIX | DT_END_ELLIPSIS, dim);
        int scanBarDx = rcRescan.dx - DpiScale(16);
        int barX = rcRescan.x + DpiScale(8);
        Rect overall(barX, nextY + footDy + DpiScale(2), scanBarDx, DpiScale(6));
        Rect current(barX, overall.y + overall.dy + DpiScale(4), scanBarDx, DpiScale(6));
        DrawScanBar(hdc, overall, gModel.scanDone, gModel.scanTotal, gModel.scanTotal <= 0);
        DrawScanBar(hdc, current, gModel.scanItemDone, gModel.scanItemTotal, gModel.scanItemIndeterminate);
        nextY += 2 * footDy;
    }

    Rect rcProgressive(rcRescan.x, nextY, rcRescan.dx, footDy);
    TempStr progressive = fmt("[%c] Show books while scanning",
                              gGlobalPrefs && gGlobalPrefs->audiobook.progressiveLibraryScan ? 'x' : ' ');
    DrawTextIn(hdc, Rect(rcProgressive.x + DpiScale(8), rcProgressive.y, rcProgressive.dx, rcProgressive.dy),
               progressive, DT_LEFT | DT_SINGLELINE | DT_VCENTER | DT_NOPREFIX, ThemeWindowLinkColor());
    AddLink(win, rcProgressive, Str(kLinkProgressiveScan), StrL("Show completed books before the scan finishes"));

    Rect rcImport(rcRescan.x, rcProgressive.y + footDy, rcRescan.dx, footDy);
    DrawTextIn(hdc, Rect(rcImport.x + DpiScale(8), rcImport.y, rcImport.dx, rcImport.dy),
               StrL("Manually add book to library..."), DT_LEFT | DT_SINGLELINE | DT_VCENTER | DT_NOPREFIX,
               ThemeWindowLinkColor());
    AddLink(win, rcImport, Str(kLinkImportBook), StrL("Pick one file, check what the Library found, then add it"));

    Rect rcClassic(rcRescan.x, rcImport.y + footDy, rcRescan.dx, footDy);
    DrawTextIn(hdc, Rect(rcClassic.x + DpiScale(8), rcClassic.y, rcClassic.dx, rcClassic.dy), StrL("Frequently read"),
               DT_LEFT | DT_SINGLELINE | DT_VCENTER | DT_NOPREFIX, ThemeWindowLinkColor());
    AddLink(win, rcClassic, Str(kLinkClassic), StrL("Show the classic home page"));
}

static void DrawGrid(HDC hdc, MainWindow* win, Rect main, int scrollY, HFONT fontTitle, HFONT fontSub, HFONT fontHead) {
    COLORREF bg = ThemeMainWindowBackgroundColor();
    COLORREF text = ThemeWindowTextColor();
    COLORREF dim = Mix(text, bg, 45);

    int pad = DpiScale(20);
    int tileDx = DpiScale(kCoverBoxDx);
    int tileDy = DpiScale(kCoverBoxDy + kTileCaptionDy);
    int gapX = DpiScale(20);
    int gapY = DpiScale(22);

    int avail = main.dx - 2 * pad;
    int perRow = (avail + gapX) / (tileDx + gapX);
    if (perRow < 1) {
        perRow = 1;
    }

    int headDy = DpiScale(40);
    SelectObject(hdc, fontHead);
    Rect rcHead(main.x + pad, main.y + DpiScale(12), avail, DpiScale(26));
    Str title = len(gModel.filterName) > 0 ? gModel.filterName : StrL("Everything");
    DrawTextIn(hdc, rcHead, title, DT_LEFT | DT_SINGLELINE | DT_NOPREFIX | DT_END_ELLIPSIS, text);

    int shown = 0;
    for (int i = 0; i < gModel.nBooks; i++) {
        if (BookVisible(gModel.books[i])) {
            shown++;
        }
    }
    SelectObject(hdc, fontSub);
    Rect rcCount(main.x + pad, rcHead.y + rcHead.dy, avail, DpiScale(16));
    int withWiki = 0;
    for (int i = 0; i < gModel.nBooks; i++) {
        if (BookVisible(gModel.books[i]) && gModel.books[i].booknlp) {
            withWiki++;
        }
    }
    DrawTextIn(hdc, rcCount,
               fmt("%d %s \xc2\xb7 %d read by BookNLP", shown, shown == 1 ? StrL("book") : StrL("books"), withWiki),
               DT_LEFT | DT_SINGLELINE | DT_NOPREFIX, dim);

    int top = main.y + headDy + DpiScale(22) - scrollY;
    int col = 0;
    int row = 0;
    for (int i = 0; i < gModel.nBooks; i++) {
        const LibBook& b = gModel.books[i];
        if (!BookVisible(b)) {
            continue;
        }
        int x = main.x + pad + col * (tileDx + gapX);
        int y = top + row * (tileDy + gapY);
        Rect tile(x, y, tileDx, tileDy);
        if (y + tileDy >= main.y && y <= main.y + main.dy) {
            DrawCoverTile(hdc, win, tile, b, fontTitle, fontSub);
        }
        col++;
        if (col >= perRow) {
            col = 0;
            row++;
        }
    }
    int rows = (shown + perRow - 1) / perRow;
    gContentDy = headDy + DpiScale(22) + rows * (tileDy + gapY);
}

static TempStr FileSizeTemp(i64 size) {
    if (size >= 1024 * 1024) {
        return fmt("%.1f MB", (double)size / (1024.0 * 1024.0));
    }
    if (size >= 1024) {
        return fmt("%d KB", (int)(size / 1024));
    }
    return fmt("%d bytes", (int)size);
}

static TempStr DeskSizeTemp(const LibDeskFile& f) {
    str::Builder s;
    if (f.pages > 0) {
        s.Append(fmt("%d %s", f.pages, f.pages == 1 ? StrL("page") : StrL("pages")));
    }
    if (f.size > 0) {
        if (len(ToStr(s)) > 0) {
            s.Append(" \xc2\xb7 ");
        }
        s.Append(FileSizeTemp(f.size));
    }
    return str::DupTemp(ToStr(s));
}

static void DeskFileIcon(LibDeskFile& f) {
    if (f.himl || len(f.path) == 0) {
        return;
    }
    SHFILEINFOW sfi{};
    sfi.iIcon = -1;
    uint flags = SHGFI_SYSICONINDEX | SHGFI_SMALLICON | SHGFI_USEFILEATTRIBUTES;
    WCHAR* pathW = CWStrTemp(f.path);
    f.himl = (HIMAGELIST)SHGetFileInfoW(pathW, 0, &sfi, sizeof(sfi), flags);
    f.iconIdx = sfi.iIcon;
}

static int DrawDeskActions(HDC hdc, MainWindow* win, Rect row, HFONT font, int chosen) {
    COLORREF bg = ThemeMainWindowBackgroundColor();
    COLORREF text = ThemeWindowTextColor();
    COLORREF dim = Mix(text, bg, 45);

    struct Action {
        const char* kind;
        const char* label;
        const char* tip;
    };
    Action doing[3];
    int nDoing = 0;
    if (gDesk.showIgnored) {
        doing[nDoing++] = {kKindBook, "Move selected to library", "Put these files back on the shelf"};
        doing[nDoing++] = {kKindDocument, "Put back on the desk", "Put these files back on the desk"};
    } else {
        doing[nDoing++] = {kKindBook, "Move selected to library", "Put these files on the shelf as books"};
        doing[nDoing++] = {kKindIgnored, "Ignore file", "Never show these files again"};
    }

    SelectObject(hdc, font);
    int x = row.x;
    for (int i = 0; i < nDoing; i++) {
        Str label(doing[i].label);
        TempWStr ws = ToWStrTemp(label);
        SIZE sz{};
        GetTextExtentPoint32W(hdc, ws.s, ws.len, &sz);
        Rect one(x, row.y, sz.cx + DpiScale(16), row.dy);
        FillRound(hdc, one, Mix(bg, text, chosen > 0 ? 16 : 7), 5);
        DrawTextIn(hdc, one, label, DT_CENTER | DT_SINGLELINE | DT_VCENTER | DT_NOPREFIX,
                   chosen > 0 ? ThemeWindowLinkColor() : dim);
        if (chosen > 0) {
            AddLink(win, one, fmt("%s%s", Str(kLinkDeskMove), Str(doing[i].kind)), Str(doing[i].tip));
        }
        x += one.dx + DpiScale(8);
    }
    Rect all(x, row.y, DpiScale(92), row.dy);
    Str pickLabel = chosen > 0 ? StrL("Select none") : StrL("Select all");
    DrawTextIn(hdc, all, pickLabel, DT_CENTER | DT_SINGLELINE | DT_VCENTER | DT_NOPREFIX, ThemeWindowLinkColor());
    AddLink(win, all, Str(kLinkDeskPickAll), StrL("Choose every file in this list"));
    return row.dy;
}

static TempStr DeskSubtitleTemp(const LibDeskFile& f) {
    str::Builder s;
    TempStr size = DeskSizeTemp(f);
    if (len(size) > 0) {
        s.Append(Str(size));
    }
    if (len(f.folder) > 0) {
        if (len(ToStr(s)) > 0) {
            s.Append(" \xc2\xb7 ");
        }
        s.Append(f.folder);
    }
    return str::DupTemp(ToStr(s));
}

static void DrawTickBox(HDC hdc, Rect box, bool ticked) {
    COLORREF bg = ThemeMainWindowBackgroundColor();
    COLORREF text = ThemeWindowTextColor();
    COLORREF fill = ticked ? ThemeWindowLinkColor() : bg;
    FillRound(hdc, box, fill, 4);
    if (!ticked) {
        ScopedSelectObject pen(hdc, CreatePen(PS_SOLID, 1, Mix(bg, text, 40)), true);
        ScopedSelectObject brush(hdc, GetStockBrush(NULL_BRUSH));
        RoundRect(hdc, box.x, box.y, box.x + box.dx, box.y + box.dy, 8, 8);
        return;
    }
    ScopedSelectObject pen(hdc, CreatePen(PS_SOLID, DpiScale(2), RGB(255, 255, 255)), true);
    int x0 = box.x + box.dx / 4;
    int y0 = box.y + box.dy / 2;
    int x1 = box.x + box.dx * 4 / 9;
    int y1 = box.y + box.dy * 7 / 10;
    int x2 = box.x + box.dx * 3 / 4;
    int y2 = box.y + box.dy * 3 / 10;
    POINT tick[3] = {{x0, y0}, {x1, y1}, {x2, y2}};
    Polyline(hdc, tick, 3);
}

static void DrawDeskTile(HDC hdc, MainWindow* win, Rect tile, LibDeskFile& f, int idx, HFONT fontTitle, HFONT fontSub) {
    COLORREF bg = ThemeMainWindowBackgroundColor();
    COLORREF text = ThemeWindowTextColor();
    COLORREF dim = Mix(text, bg, 45);

    int coverDy = tile.dy - DpiScale(kTileCaptionDy);
    Rect rcCover(tile.x, tile.y, tile.dx, coverDy);

    Rect art = rcCover;
    if (f.chosen) {
        int shrinkX = art.dx / 20;
        int shrinkY = art.dy / 20;
        art = Rect(art.x + shrinkX / 2, art.y + shrinkY / 2, art.dx - shrinkX, art.dy - shrinkY);
    }

    RenderedBitmap* bmp = CoverBitmap(fmt("%s%s", Str(kDeskCoverKey), f.id));
    FillRound(hdc, rcCover, Mix(bg, text, 12), kCoverBoxCorner);
    if (bmp && bmp->IsValid()) {
        DrawCoverInBox(hdc, art, bmp, Mix(bg, text, 12));
    } else {
        DeskFileIcon(f);
        int icoDx = 0;
        int icoDy = 0;
        if (f.himl && f.iconIdx >= 0) {
            ImageList_GetIconSize(f.himl, &icoDx, &icoDy);
            ImageList_Draw(f.himl, f.iconIdx, hdc, art.x + (art.dx - icoDx) / 2, art.y + art.dy / 2 - icoDy,
                           ILD_TRANSPARENT);
        }
        Str ext = f.ext;
        if (str::StartsWith(ext, StrL("."))) {
            ext = AfterPrefix(ext, ".");
        }
        SelectObject(hdc, fontSub);
        Rect label(art.x, art.y + art.dy / 2 + DpiScale(4), art.dx, DpiScale(18));
        DrawTextIn(hdc, label, str::ToUpperInPlace(str::DupTemp(ext)), DT_CENTER | DT_SINGLELINE | DT_NOPREFIX, dim);
    }

    if (gDesk.selecting) {
        int box = DpiScale(18);
        DrawTickBox(hdc, Rect(rcCover.x + DpiScale(5), rcCover.y + DpiScale(5), box, box), f.chosen);
    }

    Str name = len(f.file) > 0 ? f.file : f.title;
    Rect rcTitle(tile.x, tile.y + coverDy + DpiScale(6), tile.dx, DpiScale(18));
    SelectObject(hdc, fontTitle);
    DrawTextIn(hdc, rcTitle, name, DT_LEFT | DT_SINGLELINE | DT_END_ELLIPSIS | DT_NOPREFIX, text);

    Rect rcSub(tile.x, rcTitle.y + rcTitle.dy, tile.dx, DpiScale(16));
    SelectObject(hdc, fontSub);
    DrawTextIn(hdc, rcSub, DeskSubtitleTemp(f), DT_LEFT | DT_SINGLELINE | DT_PATH_ELLIPSIS | DT_NOPREFIX, dim);

    Str tip = gDesk.selecting ? StrL("Tick to add this file to the selection") : StrL("Open this file");
    AddLink(win, tile, fmt("%s%d", Str(kLinkDeskPick), idx), tip);
}

static void DrawDeskpan(HDC hdc, MainWindow* win, Rect main, int scrollY, HFONT fontTitle, HFONT fontSub,
                        HFONT fontHead) {
    COLORREF bg = ThemeMainWindowBackgroundColor();
    COLORREF text = ThemeWindowTextColor();
    COLORREF dim = Mix(text, bg, 45);

    EnsureDesk();

    int pad = DpiScale(20);
    int avail = main.dx - 2 * pad;

    SelectObject(hdc, fontHead);
    Rect rcHead(main.x + pad, main.y + DpiScale(12), avail, DpiScale(26));
    DrawTextIn(hdc, rcHead, gDesk.showIgnored ? StrL("Deskpan \xc2\xb7 ignored") : StrL("Deskpan"),
               DT_LEFT | DT_SINGLELINE | DT_NOPREFIX | DT_END_ELLIPSIS, text);

    SelectObject(hdc, fontSub);
    Rect rcCount(main.x + pad, rcHead.y + rcHead.dy, avail, DpiScale(16));
    int chosen = DeskChosenCount();
    Str what = gDesk.showIgnored ? StrL("ignored") : StrL("documents");
    Str line = Str(fmt("%d %s \xc2\xb7 %d chosen", gDesk.nFiles, what, chosen));
    if (!gDesk.loaded) {
        line = StrL("reading the desk...");
    } else if (gDesk.working) {
        line = StrL("moving files...");
    }
    DrawTextIn(hdc, rcCount, line, DT_LEFT | DT_SINGLELINE | DT_NOPREFIX, dim);

    Rect rcShow(main.x + pad, rcCount.y + rcCount.dy + DpiScale(8), avail, DpiScale(22));
    SelectObject(hdc, fontSub);
    {
        int x = rcShow.x;
        const char* names[] = {"Documents", "Ignored"};
        for (int i = 0; i < 2; i++) {
            Str label(names[i]);
            TempWStr ws = ToWStrTemp(label);
            SIZE sz{};
            GetTextExtentPoint32W(hdc, ws.s, ws.len, &sz);
            Rect one(x, rcShow.y, sz.cx + DpiScale(14), rcShow.dy);
            bool active = gDesk.showIgnored == (i == 1);
            if (active) {
                FillRound(hdc, one, Mix(bg, text, 16), 5);
            }
            DrawTextIn(hdc, one, label, DT_CENTER | DT_SINGLELINE | DT_VCENTER | DT_NOPREFIX,
                       active ? text : ThemeWindowLinkColor());
            AddLink(win, one, fmt("%s%d", Str(kLinkDeskShow), i), StrL("Choose which pile to show"));
            x += one.dx + DpiScale(4);
        }
    }

    int headDy = rcShow.y + rcShow.dy + DpiScale(14) - main.y;
    if (gDesk.selecting) {
        Rect rcActions(main.x + pad, rcShow.y + rcShow.dy + DpiScale(10), avail, DpiScale(24));
        int actionsDy = DrawDeskActions(hdc, win, rcActions, fontSub, chosen);
        headDy = rcActions.y + actionsDy + DpiScale(14) - main.y;
    }

    int tileDx = DpiScale(kCoverBoxDx);
    int tileDy = DpiScale(kCoverBoxDy + kTileCaptionDy);
    int gapX = DpiScale(20);
    int gapY = DpiScale(22);
    int perRow = (avail + gapX) / (tileDx + gapX);
    if (perRow < 1) {
        perRow = 1;
    }

    int top = main.y + headDy - scrollY;
    int col = 0;
    int row = 0;
    for (int i = 0; i < gDesk.nFiles; i++) {
        int x = main.x + pad + col * (tileDx + gapX);
        int y = top + row * (tileDy + gapY);
        if (y + tileDy >= main.y && y <= main.y + main.dy) {
            DrawDeskTile(hdc, win, Rect(x, y, tileDx, tileDy), gDesk.files[i], i, fontTitle, fontSub);
        }
        col++;
        if (col >= perRow) {
            col = 0;
            row++;
        }
    }
    if (gDesk.loaded && gDesk.nFiles == 0) {
        SelectObject(hdc, fontSub);
        Rect empty(main.x + pad, main.y + headDy, avail, DpiScale(40));
        Str msg = gDesk.showIgnored ? StrL("Nothing is being ignored.")
                                    : StrL("Every file the scan found looks like a book.");
        DrawTextIn(hdc, empty, msg, DT_LEFT | DT_WORDBREAK | DT_NOPREFIX, dim);
    }
    int rows = (gDesk.nFiles + perRow - 1) / perRow;
    gContentDy = headDy + rows * (tileDy + gapY) + DpiScale(20);
}

static void DrawTabs(HDC hdc, MainWindow* win, Rect r, HFONT font) {
    static const char* names[] = {"Overview", "Characters", "Family", "Places", "Who knows what", "On screen", "Info"};
    COLORREF bg = ThemeMainWindowBackgroundColor();
    COLORREF text = ThemeWindowTextColor();
    COLORREF sel = Mix(bg, text, 16);
    SelectObject(hdc, font);
    int x = r.x;
    for (int i = 0; i < kLibTabCount; i++) {
        Str label(names[i]);
        Rect probe(0, 0, 400, 40);
        int dx = 0;
        {
            TempWStr ws = ToWStrTemp(label);
            SIZE sz{};
            GetTextExtentPoint32W(hdc, ws.s, ws.len, &sz);
            dx = sz.cx + DpiScale(22);
            (void)probe;
        }
        Rect tab(x, r.y, dx, r.dy);
        bool active = (int)gDetail.tab == i;
        if (active) {
            FillRound(hdc, tab, sel, 6);
        }
        DrawTextIn(hdc, tab, label, DT_CENTER | DT_SINGLELINE | DT_VCENTER | DT_NOPREFIX,
                   active ? text : Mix(text, bg, 40));
        AddLink(win, tab, fmt("%s%d", Str(kLinkTab), i));
        x += dx + DpiScale(4);
    }
}

static void DrawChips(HDC hdc, MainWindow* win, Rect area, Str* items, int n, const char* linkPrefix, HFONT font,
                      int* usedDy) {
    COLORREF bg = ThemeMainWindowBackgroundColor();
    COLORREF text = ThemeWindowTextColor();
    COLORREF chip = Mix(bg, text, 10);
    SelectObject(hdc, font);
    int x = area.x;
    int y = area.y;
    int chipDy = DpiScale(24);
    for (int i = 0; i < n; i++) {
        if (len(items[i]) == 0) {
            continue;
        }
        TempWStr ws = ToWStrTemp(items[i]);
        SIZE sz{};
        GetTextExtentPoint32W(hdc, ws.s, ws.len, &sz);
        int dx = sz.cx + DpiScale(18);
        if (x + dx > area.x + area.dx) {
            x = area.x;
            y += chipDy + DpiScale(6);
        }
        if (y + chipDy > area.y + area.dy) {
            break;
        }
        Rect r(x, y, dx, chipDy);
        FillRound(hdc, r, chip, chipDy / 2);
        DrawTextIn(hdc, r, items[i], DT_CENTER | DT_SINGLELINE | DT_VCENTER | DT_NOPREFIX, text);
        if (linkPrefix) {
            AddLink(win, r, fmt("%s%s", Str(linkPrefix), items[i]));
        }
        x += dx + DpiScale(6);
    }
    *usedDy = (y + chipDy) - area.y;
}

static void DrawScreenRow(HDC hdc, MainWindow* win, Rect area, HFONT fontTitle, HFONT fontSub) {
    COLORREF bg = ThemeMainWindowBackgroundColor();
    COLORREF text = ThemeWindowTextColor();
    COLORREF dim = Mix(text, bg, 45);
    int posterDx = DpiScale(104);
    int posterDy = DpiScale(154);
    int gap = DpiScale(18);
    int x = area.x;
    for (int i = 0; i < gDetail.nScreen; i++) {
        const LibScreen& s = gDetail.screen[i];
        if (x + posterDx > area.x + area.dx) {
            break;
        }
        Rect rcP(x, area.y, posterDx, posterDy);
        RenderedBitmap* bmp = len(s.poster) > 0 ? CoverBitmap(s.poster) : nullptr;
        if (bmp && bmp->IsValid()) {
            int saved = SaveDC(hdc);
            HRGN clip = CreateRoundRectRgn(rcP.x, rcP.y, rcP.x + rcP.dx + 1, rcP.y + rcP.dy + 1, 8, 8);
            ExtSelectClipRgn(hdc, clip, RGN_AND);
            bmp->Blit(hdc, rcP);
            RestoreDC(hdc, saved);
            DeleteObject(clip);
        } else {
            FillRound(hdc, rcP, Mix(bg, text, 12), 8);
        }
        SelectObject(hdc, fontTitle);
        Rect rcT(x, rcP.y + posterDy + DpiScale(6), posterDx, DpiScale(17));
        DrawTextIn(hdc, rcT, s.title, DT_LEFT | DT_SINGLELINE | DT_END_ELLIPSIS | DT_NOPREFIX, text);
        SelectObject(hdc, fontSub);
        Rect rcK(x, rcT.y + rcT.dy, posterDx, DpiScale(16));
        TempStr sub = s.year > 0 ? fmt("%s \xc2\xb7 %d", s.kind, s.year) : str::DupTemp(s.kind);
        DrawTextIn(hdc, rcK, Str(sub), DT_LEFT | DT_SINGLELINE | DT_END_ELLIPSIS | DT_NOPREFIX, dim);
        Rect rcS(x, rcK.y + rcK.dy, posterDx, DpiScale(30));
        DrawTextIn(hdc, rcS, s.stars, DT_LEFT | DT_WORDBREAK | DT_END_ELLIPSIS | DT_NOPREFIX, dim);
        if (len(s.imdbId) > 0) {
            Rect hot(x, rcP.y, posterDx, posterDy + rcT.dy + rcK.dy);
            AddLink(win, hot, fmt("%s%s", Str(kLinkScreenTitle), s.imdbId), fmt("Open %s on IMDb", s.title));
        }
        x += posterDx + gap;
    }
}

static bool ChapterShown(int i) {
    int parent = gDetail.chapters[i].parent;
    while (parent >= 0) {
        if (!gDetail.chapters[parent].open) {
            return false;
        }
        parent = gDetail.chapters[parent].parent;
    }
    return true;
}

static int DrawChapters(HDC hdc, MainWindow* win, Rect body, HFONT fontSub, HFONT fontBody) {
    COLORREF bg = ThemeMainWindowBackgroundColor();
    COLORREF text = ThemeWindowTextColor();
    COLORREF dim = Mix(text, bg, 45);

    int lineDy = DpiScale(19);
    int y = body.y;
    SelectObject(hdc, fontSub);
    if (!gDetail.chaptersDone) {
        DrawTextIn(hdc, Rect(body.x, y, body.dx, lineDy), StrL("Reading the chapter list..."),
                   DT_LEFT | DT_SINGLELINE | DT_NOPREFIX, dim);
        return lineDy;
    }
    if (gDetail.nChapters == 0) {
        return 0;
    }
    DrawTextIn(hdc, Rect(body.x, y, body.dx, lineDy), fmt("%d chapters", gDetail.nChapters),
               DT_LEFT | DT_SINGLELINE | DT_NOPREFIX, dim);
    y += lineDy + DpiScale(4);

    SelectObject(hdc, fontBody);
    for (int i = 0; i < gDetail.nChapters; i++) {
        const LibChapter& c = gDetail.chapters[i];
        if (!ChapterShown(i)) {
            continue;
        }
        if (y + lineDy > body.y + body.dy) {
            break;
        }
        int indent = c.depth * DpiScale(16);
        Rect row(body.x + indent, y, body.dx - indent, lineDy);
        if (c.kids > 0) {
            Rect rcArrow(row.x, row.y, DpiScale(14), lineDy);
            DrawTextIn(hdc, rcArrow, c.open ? StrL("-") : StrL("+"), DT_LEFT | DT_SINGLELINE | DT_NOPREFIX, dim);
            AddLink(win, rcArrow, fmt("%s%d", Str(kLinkChapter), i), fmt("%d chapters inside", c.kids));
        }
        Rect rcName(row.x + DpiScale(16), row.y, row.dx - DpiScale(70), lineDy);
        DrawTextIn(hdc, rcName, c.title, DT_LEFT | DT_SINGLELINE | DT_END_ELLIPSIS | DT_NOPREFIX, text);
        if (c.page > 0) {
            AddLink(win, rcName, fmt("%s%d|%s", Str(kLinkPage), c.page, gDetail.path), StrL("Open at this page"));
            Rect rcPage(body.x + body.dx - DpiScale(50), row.y, DpiScale(46), lineDy);
            DrawTextIn(hdc, rcPage, fmt("p %d", c.page), DT_RIGHT | DT_SINGLELINE | DT_NOPREFIX, dim);
            AddLink(win, rcPage, fmt("%s%d|%s", Str(kLinkPage), c.page, gDetail.path), StrL("Open at this page"));
        }
        y += lineDy;
    }
    return y - body.y;
}

static int DrawKnows(HDC hdc, MainWindow* win, Rect body, HFONT fontTitle, HFONT fontSub, HFONT fontBody) {
    COLORREF bg = ThemeMainWindowBackgroundColor();
    COLORREF text = ThemeWindowTextColor();
    COLORREF dim = Mix(text, bg, 45);

    int usedDy = 0;
    if (len(gDetail.topic) == 0) {
        SelectObject(hdc, fontSub);
        int lineDy = DpiScale(18);
        DrawTextIn(hdc, Rect(body.x, body.y, body.dx, lineDy), StrL("Pick a subject to see who knows about it."),
                   DT_LEFT | DT_SINGLELINE | DT_NOPREFIX, dim);
        Rect rest(body.x, body.y + lineDy + DpiScale(6), body.dx, body.dy - lineDy);
        int chipsDy = 0;
        DrawChips(hdc, win, rest, gDetail.topics, gDetail.nTopics, kLinkTopic, fontSub, &chipsDy);
        return lineDy + DpiScale(6) + chipsDy;
    }

    int lineDy = DpiScale(20);
    int y = body.y;
    SelectObject(hdc, fontSub);
    Rect rcBack(body.x, y, DpiScale(130), lineDy);
    DrawTextIn(hdc, rcBack, StrL("< all subjects"), DT_LEFT | DT_SINGLELINE | DT_NOPREFIX, ThemeWindowLinkColor());
    AddLink(win, rcBack, Str(kLinkTopicList), StrL("Back to every subject"));
    y += lineDy + DpiScale(6);

    SelectObject(hdc, fontTitle);
    DrawTextIn(hdc, Rect(body.x, y, body.dx, DpiScale(22)), gDetail.topic, DT_LEFT | DT_SINGLELINE | DT_NOPREFIX, text);
    y += DpiScale(26);

    SelectObject(hdc, fontBody);
    if (!gDetail.topicLoaded) {
        DrawTextIn(hdc, Rect(body.x, y, body.dx, lineDy), StrL("Reading the wiki..."),
                   DT_LEFT | DT_SINGLELINE | DT_NOPREFIX, dim);
        return y + lineDy - body.y;
    }
    if (gDetail.nKnowers == 0) {
        DrawTextIn(hdc, Rect(body.x, y, body.dx, lineDy), StrL("Nobody in this series is recorded knowing about it."),
                   DT_LEFT | DT_SINGLELINE | DT_NOPREFIX, dim);
        return y + lineDy - body.y;
    }

    int indent = DpiScale(18);
    int nameDx = DpiScale(180);
    for (int i = 0; i < gDetail.nKnowers; i++) {
        const LibKnower& k = gDetail.knowers[i];
        if (len(k.name) == 0) {
            continue;
        }
        if (y + lineDy > body.y + body.dy) {
            break;
        }
        Rect tick(body.x, y, indent, lineDy);
        DrawTextIn(hdc, tick, StrL("\xc2\xb7"), DT_CENTER | DT_SINGLELINE | DT_NOPREFIX, dim);
        Rect rcName(body.x + indent, y, nameDx, lineDy);
        DrawTextIn(hdc, rcName, k.name, DT_LEFT | DT_SINGLELINE | DT_END_ELLIPSIS | DT_NOPREFIX, text);
        AddLink(win, rcName, fmt("%s%s", Str(kLinkPerson), k.name), fmt("Everything about %s", k.name));
        TempStr note =
            k.mentions > 0 ? fmt("%d %s", k.mentions, k.mentions == 1 ? StrL("mention") : StrL("mentions")) : TempStr{};
        if (len(k.book) > 0) {
            TempStr where = k.page > 0 ? fmt("%s, page %d", k.book, k.page) : str::DupTemp(k.book);
            note = len(note) > 0 ? fmt("%s \xc2\xb7 %s", Str(note), Str(where)) : where;
        }
        Rect rcNote(body.x + indent + nameDx, y, body.dx - indent - nameDx, lineDy);
        DrawTextIn(hdc, rcNote, Str(note), DT_LEFT | DT_SINGLELINE | DT_END_ELLIPSIS | DT_NOPREFIX, dim);
        y += lineDy;
    }
    usedDy = y - body.y;
    return usedDy;
}

static int DrawPerson(HDC hdc, Rect body, HFONT fontTitle, HFONT fontSub, HFONT fontBody) {
    COLORREF bg = ThemeMainWindowBackgroundColor();
    COLORREF text = ThemeWindowTextColor();
    COLORREF dim = Mix(text, bg, 45);

    int y = body.y;
    int lineDy = DpiScale(20);
    SelectObject(hdc, fontTitle);
    DrawTextIn(hdc, Rect(body.x, y, body.dx, DpiScale(22)), gDetail.person, DT_LEFT | DT_SINGLELINE | DT_NOPREFIX,
               text);
    y += DpiScale(26);

    SelectObject(hdc, fontBody);
    if (!gDetail.personLoaded) {
        DrawTextIn(hdc, Rect(body.x, y, body.dx, lineDy), StrL("Reading the wiki..."),
                   DT_LEFT | DT_SINGLELINE | DT_NOPREFIX, dim);
        return y + lineDy - body.y;
    }

    if (len(gDetail.personQuote) > 0) {
        TempStr quote = fmt("\"%s\"", gDetail.personQuote);
        if (len(gDetail.personQuoteBook) > 0) {
            quote = fmt("%s \xc2\xb7 %s", Str(quote), gDetail.personQuoteBook);
            if (gDetail.personQuotePage > 0) {
                quote = fmt("%s, page %d", Str(quote), gDetail.personQuotePage);
            }
        }
        int dy = MeasureTextDy(hdc, body, Str(quote), DT_LEFT | DT_WORDBREAK | DT_NOPREFIX);
        DrawTextIn(hdc, Rect(body.x, y, body.dx, dy), Str(quote), DT_LEFT | DT_WORDBREAK | DT_NOPREFIX, text);
        y += dy + DpiScale(14);
    }

    struct PersonRow {
        Str label;
        Str value;
    };
    PersonRow rows[] = {
        {StrL("Described as"), gDetail.personTraits}, {StrL("But not"), gDetail.personNot},
        {StrL("Family"), gDetail.personKin},          {StrL("Speaks"), gDetail.personSpeech},
        {StrL("Voice"), gDetail.personVoice},         {StrL("Places"), gDetail.personPlaces},
        {StrL("Knows about"), gDetail.personKnows},
    };
    int labelDx = DpiScale(116);
    int gap = DpiScale(12);
    int valueDx = body.dx - labelDx - gap;
    for (const PersonRow& row : rows) {
        if (len(row.value) == 0) {
            continue;
        }
        Rect rcValue(body.x + labelDx + gap, y, valueDx, body.dy);
        int dy = MeasureTextDy(hdc, rcValue, row.value, DT_LEFT | DT_WORDBREAK | DT_NOPREFIX);
        if (dy < lineDy) {
            dy = lineDy;
        }
        SelectObject(hdc, fontSub);
        DrawTextIn(hdc, Rect(body.x, y, labelDx, lineDy), row.label, DT_LEFT | DT_SINGLELINE | DT_NOPREFIX, dim);
        SelectObject(hdc, fontBody);
        DrawTextIn(hdc, Rect(rcValue.x, y, valueDx, dy), row.value, DT_LEFT | DT_WORDBREAK | DT_NOPREFIX, text);
        y += dy + DpiScale(8);
    }

    if (gDetail.personBooks > 0) {
        SelectObject(hdc, fontSub);
        DrawTextIn(hdc, Rect(body.x, y, body.dx, lineDy), fmt("Appears in %d books", gDetail.personBooks),
                   DT_LEFT | DT_SINGLELINE | DT_NOPREFIX, dim);
        y += lineDy;
    }
    return y - body.y;
}

static TempStr InfoDateTemp(i64 ms, Str absent) {
    if (ms <= 0) {
        return str::DupTemp(absent);
    }
    ULARGE_INTEGER u{};
    u.QuadPart = (ULONGLONG)ms * 10000ULL + 116444736000000000ULL;
    FILETIME ft{};
    ft.dwLowDateTime = u.LowPart;
    ft.dwHighDateTime = u.HighPart;
    FILETIME local{};
    SYSTEMTIME st{};
    if (!FileTimeToLocalFileTime(&ft, &local) || !FileTimeToSystemTime(&local, &st)) {
        return str::DupTemp(absent);
    }
    WCHAR dateW[128]{};
    WCHAR timeW[128]{};
    if (GetDateFormatW(LOCALE_USER_DEFAULT, DATE_LONGDATE, &st, nullptr, dateW, dimof(dateW)) < 2) {
        return str::DupTemp(absent);
    }
    if (GetTimeFormatW(LOCALE_USER_DEFAULT, TIME_NOSECONDS, &st, nullptr, timeW, dimof(timeW)) < 2) {
        return ToUtf8Temp(dateW);
    }
    return fmt("%s, %s", ToUtf8Temp(dateW), ToUtf8Temp(timeW));
}

static TempStr InfoDurationTemp(i64 ms) {
    if (ms <= 0) {
        return str::DupTemp(StrL("None yet"));
    }
    if (ms < 60 * 1000) {
        return str::DupTemp(StrL("under a minute"));
    }
    i64 minutes = ms / (60 * 1000);
    i64 hours = minutes / 60;
    if (hours >= 1) {
        return fmt("%dh %dm", (int)hours, (int)(minutes % 60));
    }
    return fmt("%dm", (int)minutes);
}

static TempStr InfoRatingTemp() {
    if (gDetail.rating <= 0) {
        return str::DupTemp(gDetail.loading ? StrL("Looking it up...") : StrL("None published"));
    }
    Str where = StrL("online");
    if (str::Eq(gDetail.ratingSource, StrL("openlibrary"))) {
        where = StrL("Open Library");
    } else if (str::Eq(gDetail.ratingSource, StrL("googlebooks"))) {
        where = StrL("Google Books");
    } else if (len(gDetail.ratingSource) > 0) {
        where = gDetail.ratingSource;
    }
    TempStr score = str::FormatFloatWithThousandSepTemp(gDetail.rating);
    if (gDetail.ratingCount > 0) {
        return fmt("%s / 5 \xc2\xb7 %s ratings on %s", score, str::FormatNumWithThousandSepTemp(gDetail.ratingCount),
                   where);
    }
    return fmt("%s / 5 \xc2\xb7 %s", score, where);
}

static TempStr InfoWorkingTemp(Str have) {
    if (len(have) > 0) {
        return str::DupTemp(have);
    }
    return str::DupTemp(gDetail.infoDone ? StrL("Unavailable") : StrL("Working it out..."));
}

static int DrawInfoRow(HDC hdc, Rect body, int y, Str label, Str value, HFONT fontSub, HFONT fontBody) {
    COLORREF bg = ThemeMainWindowBackgroundColor();
    COLORREF text = ThemeWindowTextColor();
    COLORREF dim = Mix(text, bg, 45);
    int labelDx = DpiScale(140);
    int gap = DpiScale(14);
    int rowDy = DpiScale(20);

    SelectObject(hdc, fontBody);
    UINT flags = DT_LEFT | DT_WORDBREAK | DT_NOPREFIX;
    Rect rcVal(body.x + labelDx + gap, y, body.dx - labelDx - gap, rowDy);
    int want = MeasureTextDy(hdc, rcVal, value, flags);
    if (want < rowDy) {
        want = rowDy;
    }
    rcVal.dy = want;
    DrawTextIn(hdc, rcVal, value, flags, text);

    SelectObject(hdc, fontSub);
    DrawTextIn(hdc, Rect(body.x, y, labelDx, rowDy), label, DT_LEFT | DT_SINGLELINE | DT_NOPREFIX, dim);
    return want + DpiScale(4);
}

static int DrawInfoHead(HDC hdc, Rect body, int y, Str title, HFONT fontTitle) {
    SelectObject(hdc, fontTitle);
    int dy = DpiScale(22);
    DrawTextIn(hdc, Rect(body.x, y, body.dx, dy), title, DT_LEFT | DT_SINGLELINE | DT_NOPREFIX, ThemeWindowTextColor());
    return dy + DpiScale(4);
}

static int DrawInfo(HDC hdc, Rect body, HFONT fontTitle, HFONT fontSub, HFONT fontBody) {
    EnsureInfo();
    int y = body.y;
    int sectionGap = DpiScale(14);

    y += DrawInfoHead(hdc, body, y, StrL("File"), fontTitle);
    y += DrawInfoRow(hdc, body, y, StrL("Location"), len(gDetail.path) > 0 ? gDetail.path : StrL("Unknown"), fontSub,
                     fontBody);
    y += DrawInfoRow(hdc, body, y, StrL("MD5"), Str(InfoWorkingTemp(gDetail.checksum)), fontSub, fontBody);
    y += DrawInfoRow(hdc, body, y, StrL("Fingerprint"), Str(InfoWorkingTemp(gDetail.mark)), fontSub, fontBody);
    y += DrawInfoRow(hdc, body, y, StrL("Created"), Str(InfoDateTemp(gDetail.created, StrL("Unknown"))), fontSub,
                     fontBody);
    y += DrawInfoRow(hdc, body, y, StrL("Modified"), Str(InfoDateTemp(gDetail.mtime, StrL("Unknown"))), fontSub,
                     fontBody);
    Str size = gDetail.size > 0 ? Str(str::FormatSizeShortTemp(gDetail.size)) : StrL("Unknown");
    y += DrawInfoRow(hdc, body, y, StrL("Size"), size, fontSub, fontBody);
    TempStr format = str::DupTemp(gDetail.ext);
    if (str::StartsWith(format, StrL("."))) {
        format = str::DupTemp(Str(format.s + 1, format.len - 1));
    }
    str::ToUpperInPlace(format);
    y += DrawInfoRow(hdc, body, y, StrL("Format"), len(format) > 0 ? Str(format) : StrL("Unknown"), fontSub, fontBody);

    FileState* fs = len(gDetail.path) > 0 ? FileHistoryFindByPath(gDetail.path) : nullptr;
    int reached = fs ? fs->maxPageReached : 0;
    TempStr read;
    if (gDetail.pages > 0 && reached > 0) {
        double share = reached * 100.0 / gDetail.pages;
        if (share > 100) {
            share = 100;
        }
        read = fmt("%d%% \xc2\xb7 page %d of %d", (int)(share + 0.5), reached, gDetail.pages);
    } else if (gDetail.pages > 0) {
        read = str::DupTemp(StrL("Not started"));
    } else {
        read = str::DupTemp(StrL("Unknown"));
    }

    y += sectionGap;
    y += DrawInfoHead(hdc, body, y, StrL("Reading"), fontTitle);
    y += DrawInfoRow(hdc, body, y, StrL("Last read"), Str(InfoDateTemp(fs ? fs->lastReadAt : 0, StrL("Never"))),
                     fontSub, fontBody);
    y += DrawInfoRow(hdc, body, y, StrL("Read"), Str(read), fontSub, fontBody);
    y += DrawInfoRow(hdc, body, y, StrL("Time spent"), Str(InfoDurationTemp(fs ? fs->timeSpentMs : 0)), fontSub,
                     fontBody);
    y += DrawInfoRow(hdc, body, y, StrL("Times opened"), Str(fmt("%d", fs ? fs->openCount : 0)), fontSub, fontBody);

    y += sectionGap;
    y += DrawInfoHead(hdc, body, y, StrL("The book"), fontTitle);
    Str pages = gDetail.pages > 0 ? Str(str::FormatNumWithThousandSepTemp(gDetail.pages)) : StrL("Unknown");
    y += DrawInfoRow(hdc, body, y, StrL("Pages"), pages, fontSub, fontBody);
    Str words = gDetail.words >= 0 ? Str(str::FormatNumWithThousandSepTemp(gDetail.words))
                                   : (gDetail.infoDone ? StrL("Unavailable") : StrL("Counting..."));
    y += DrawInfoRow(hdc, body, y, StrL("Words"), words, fontSub, fontBody);
    y += DrawInfoRow(hdc, body, y, StrL("Rating"), Str(InfoRatingTemp()), fontSub, fontBody);
    str::Builder genre;
    if (len(gDetail.genre) > 0) {
        genre.Append(gDetail.genre);
    }
    if (len(gDetail.sub) > 0) {
        if (len(ToStr(genre)) > 0) {
            genre.Append(" \xc2\xb7 ");
        }
        genre.Append(gDetail.sub);
    }
    Str genreStr = len(ToStr(genre)) > 0 ? ToStr(genre) : StrL("Unclassified");
    y += DrawInfoRow(hdc, body, y, StrL("Genre"), genreStr, fontSub, fontBody);
    Str series = len(gDetail.series) > 0 ? gDetail.series : StrL("Not in a series");
    y += DrawInfoRow(hdc, body, y, StrL("Series"), series, fontSub, fontBody);

    return y - body.y;
}

static void DrawDetail(HDC hdc, MainWindow* win, Rect main, int scrollY, HFONT fontTitle, HFONT fontSub, HFONT fontHead,
                       HFONT fontBody) {
    COLORREF bg = ThemeMainWindowBackgroundColor();
    COLORREF text = ThemeWindowTextColor();
    COLORREF dim = Mix(text, bg, 45);

    int pad = DpiScale(24);
    int y = main.y + DpiScale(12) - scrollY;

    SelectObject(hdc, fontSub);
    Rect rcBack(main.x + pad, y, DpiScale(190), DpiScale(20));
    DrawTextIn(hdc, rcBack, StrL("< Back to the library"), DT_LEFT | DT_SINGLELINE | DT_NOPREFIX,
               ThemeWindowLinkColor());
    AddLink(win, rcBack, Str(kLinkBack));
    y += rcBack.dy + DpiScale(14);

    int coverDx = DpiScale(168);
    int coverDy = DpiScale(168 * kCoverBoxDy / kCoverBoxDx);
    Rect rcCover(main.x + pad, y, coverDx, coverDy);
    RenderedBitmap* bmp = CoverBitmap(gDetail.id);
    DrawCoverInBox(hdc, rcCover, bmp, Mix(bg, text, 12));
    if (len(gDetail.id) > 0 && len(gDetail.path) > 0) {
        Rect rcChange(main.x + pad, y + coverDy + DpiScale(4), coverDx, DpiScale(18));
        DrawTextIn(hdc, rcChange, StrL("Change the cover"), DT_CENTER | DT_SINGLELINE | DT_NOPREFIX,
                   ThemeWindowLinkColor());
        AddLink(win, rcChange, Str(kLinkChangeCover), StrL("Pick a different picture for this book"));
        AddLink(win, rcCover, Str(kLinkChangeCover), StrL("Pick a different picture for this book"));
    }

    int infoX = main.x + pad + coverDx + DpiScale(22);
    int infoDx = main.x + main.dx - infoX - pad;
    int iy = y;

    SelectObject(hdc, fontHead);
    Rect rcTitle(infoX, iy, infoDx, DpiScale(30));
    // The hit-test rect has to be large because the 20pt fontHead's
    // character cell extends well above and below the 30px drawing
    // rect. The visible text sits near the top of the cell, not at
    // the rect's top edge. Without a generous hit rect, a user
    // clicking on the *visible* text misses the link entirely and
    // the inline rename "doesn't work" from their perspective.
    // Make it cover the full cell plus padding. Keep the EDITTEXT
    // at the drawing rect so it lines up with the original text.
    Rect rcTitleHit(infoX, iy - DpiScale(25), infoDx, DpiScale(90));
    // Stash the title rect for the inline-edit handler.
    gDetailTitleRect = rcTitleHit;
    // The title is inline-editable like a Windows file rename: click
    // it and the text turns into an EDITTEXT in place; Enter saves,
    // Escape or focus-loss cancels. We render it as plain text
    // (NOT a link colour + underline) because that visual screams
    // "I am a hyperlink to a separate page" and Windows file rename
    // is the opposite — it looks like a label until you click it.
    // The click handler still works; the inline-rename machinery is
    // the only place that uses it.
    DrawTextIn(hdc, rcTitle, gDetail.title, DT_LEFT | DT_SINGLELINE | DT_END_ELLIPSIS | DT_NOPREFIX, text);
    if (len(gDetail.id) > 0) {
        TempStr titleTip = BuildTitleTip();
        // Use the wider hit rect (rcTitleHit) so clicks on the
        // visible text actually register, not just clicks in the
        // 30px drawing rect which the text overflows.
        AddLink(win, rcTitleHit, fmt("%s%s", Str(kLinkEditTitle), gDetail.id), titleTip);
        AddLink(win, Rect(rcTitleHit.x, rcTitleHit.y + rcTitleHit.dy, rcTitleHit.dx, 0),
                fmt("%s%s", Str(kLinkEditTitle), gDetail.id), titleTip);
    }
    iy += rcTitle.dy + DpiScale(2);

    SelectObject(hdc, fontSub);
    str::Builder meta;
    if (len(gDetail.author) > 0) {
        meta.Append(gDetail.author);
    }
    if (gDetail.year > 0) {
        if (len(ToStr(meta)) > 0) {
            meta.Append(" \xc2\xb7 ");
        }
        meta.Append(fmt("%d", gDetail.year));
    }
    if (len(gDetail.series) > 0) {
        if (len(ToStr(meta)) > 0) {
            meta.Append(" \xc2\xb7 ");
        }
        meta.Append(gDetail.series);
    }
    if (gDetail.pages > 0) {
        if (len(ToStr(meta)) > 0) {
            meta.Append(" \xc2\xb7 ");
        }
        meta.Append(fmt("%d pages", gDetail.pages));
    }
    Rect rcMeta(infoX, iy, infoDx, DpiScale(18));
    DrawTextIn(hdc, rcMeta, ToStr(meta), DT_LEFT | DT_SINGLELINE | DT_END_ELLIPSIS | DT_NOPREFIX, dim);
    iy += rcMeta.dy + DpiScale(10);

    SelectObject(hdc, fontSub);
    Rect rcRead(infoX, iy, DpiScale(92), DpiScale(26));
    FillRound(hdc, rcRead, Mix(bg, text, 16), 6);
    DrawTextIn(hdc, rcRead, StrL("Read"), DT_CENTER | DT_SINGLELINE | DT_VCENTER | DT_NOPREFIX, text);
    if (len(gDetail.path) > 0) {
        AddLink(win, rcRead, fmt("%s%s", Str(kLinkRead), gDetail.path), gDetail.path);
    }
    iy += rcRead.dy + DpiScale(12);

    SelectObject(hdc, fontBody);
    Str blurb = gDetail.description;
    if (len(blurb) == 0 && gDetail.loading) {
        blurb = StrL("Looking this book up...");
    }
    int maxDescDy = coverDy - (iy - y);
    Rect rcDesc(infoX, iy, infoDx, maxDescDy);
    if (len(blurb) > 0) {
        UINT flags = DT_LEFT | DT_WORDBREAK | DT_NOPREFIX;
        int want = MeasureTextDy(hdc, rcDesc, blurb, flags);
        rcDesc.dy = want < maxDescDy ? want : maxDescDy;
        DrawTextIn(hdc, rcDesc, blurb, flags, text);
    }

    y += coverDy + DpiScale(38);

    Rect rcTabs(main.x + pad, y, main.dx - 2 * pad, DpiScale(28));
    DrawTabs(hdc, win, rcTabs, fontSub);
    y += rcTabs.dy + DpiScale(16);

    Rect body(main.x + pad, y, main.dx - 2 * pad, main.y + main.dy - y);
    int usedDy = 0;
    switch (gDetail.tab) {
        case LibTab::Overview: {
            SelectObject(hdc, fontBody);
            if (len(gDetail.subjects) > 0) {
                int dy = MeasureTextDy(hdc, body, gDetail.subjects, DT_LEFT | DT_WORDBREAK | DT_NOPREFIX);
                DrawTextIn(hdc, Rect(body.x, body.y, body.dx, dy), gDetail.subjects,
                           DT_LEFT | DT_WORDBREAK | DT_NOPREFIX, dim);
                usedDy = dy + DpiScale(12);
            }
            if (len(gDetail.wiki) == 0) {
                Rect r(body.x, body.y + usedDy, body.dx, DpiScale(40));
                DrawTextIn(hdc, r,
                           StrL("No wiki yet for this book. Read it aloud with the BookNLP analyser to build one."),
                           DT_LEFT | DT_WORDBREAK | DT_NOPREFIX, dim);
                usedDy += DpiScale(40);
            } else {
                int chipsDy = 0;
                Rect r(body.x, body.y + usedDy, body.dx, body.dy - usedDy);
                DrawChips(hdc, win, r, gDetail.people, gDetail.nPeople < 18 ? gDetail.nPeople : 18, kLinkPerson,
                          fontSub, &chipsDy);
                usedDy += chipsDy;
            }
            EnsureChapters();
            usedDy += DpiScale(14);
            usedDy +=
                DrawChapters(hdc, win, Rect(body.x, body.y + usedDy, body.dx, body.dy - usedDy), fontSub, fontBody);
            break;
        }
        case LibTab::People: {
            if (len(gDetail.person) > 0) {
                usedDy = DrawPerson(hdc, body, fontTitle, fontSub, fontBody);
            } else {
                DrawChips(hdc, win, body, gDetail.people, gDetail.nPeople, kLinkPerson, fontSub, &usedDy);
            }
            break;
        }
        case LibTab::Family: {
            SelectObject(hdc, fontBody);
            if (len(gDetail.person) == 0) {
                DrawTextIn(hdc, body, StrL("Pick a character on the Characters tab to see their family."),
                           DT_LEFT | DT_WORDBREAK | DT_NOPREFIX, dim);
                usedDy = DpiScale(24);
                break;
            }
            int ry = body.y;
            int rowDy = DpiScale(22);
            SelectObject(hdc, fontTitle);
            DrawTextIn(hdc, Rect(body.x, ry, body.dx, rowDy), gDetail.person, DT_LEFT | DT_SINGLELINE | DT_NOPREFIX,
                       text);
            ry += rowDy + DpiScale(6);
            SelectObject(hdc, fontBody);
            for (int i = 0; i < gDetail.nFamily; i++) {
                const LibFamilyRow& f = gDetail.family[i];
                if (len(f.name) == 0) {
                    continue;
                }
                if (ry + rowDy > body.y + body.dy) {
                    break;
                }
                Rect rcRel(body.x, ry, DpiScale(120), rowDy);
                DrawTextIn(hdc, rcRel, Str(PrettyRelationTemp(f.relation)), DT_LEFT | DT_SINGLELINE | DT_NOPREFIX, dim);
                Rect rcName(body.x + DpiScale(126), ry, body.dx - DpiScale(126), rowDy);
                DrawTextIn(hdc, rcName, f.name, DT_LEFT | DT_SINGLELINE | DT_END_ELLIPSIS | DT_NOPREFIX, text);
                AddLink(win, rcName, fmt("%s%s", Str(kLinkPerson), f.name));
                ry += rowDy;
            }
            usedDy = ry - body.y;
            break;
        }
        case LibTab::Places:
            DrawChips(hdc, win, body, gDetail.places, gDetail.nPlaces, nullptr, fontSub, &usedDy);
            break;
        case LibTab::Knows:
            usedDy = DrawKnows(hdc, win, body, fontTitle, fontSub, fontBody);
            break;
        case LibTab::Screen: {
            EnsureScreen();
            if (gDetail.nScreen == 0) {
                SelectObject(hdc, fontBody);
                Str msg = gDetail.screenLoading ? StrL("Looking for films and TV...")
                                                : StrL("No film or TV adaptation found for this book.");
                DrawTextIn(hdc, body, msg, DT_LEFT | DT_WORDBREAK | DT_NOPREFIX, dim);
                usedDy = DpiScale(24);
            } else {
                DrawScreenRow(hdc, win, body, fontTitle, fontSub);
                usedDy = DpiScale(230);
            }
            break;
        }
        case LibTab::Info:
            usedDy = DrawInfo(hdc, body, fontTitle, fontSub, fontBody);
            break;
    }
    gContentDy = (y - main.y + scrollY) + usedDy + DpiScale(40);
}

void DrawLibraryPage(MainWindow* win, HDC hdc) {
    gNotifyHwnd = win->hwndCanvas;
    DeleteVecMembers(win->staticLinks);
    EnsureModel();

    Rect rc = HwndClientRect(win->hwndCanvas);
    COLORREF bg = ThemeMainWindowBackgroundColor();
    COLORREF text = ThemeWindowTextColor();
    HdcFillRect(hdc, rc, bg);
    SetBkMode(hdc, TRANSPARENT);

    if (CoverEditorActive()) {
        CoverEditorDraw(win, hdc, rc);
        return;
    }

    HFONT fontHead = HdcCreateSimpleFont(hdc, "MS Shell Dlg", 20)->GetHFont();
    HFONT fontTitle = HdcCreateSimpleFont(hdc, "MS Shell Dlg", 13)->GetHFont();
    HFONT fontSub = HdcCreateSimpleFont(hdc, "MS Shell Dlg", 12)->GetHFont();
    HFONT fontBody = HdcCreateSimpleFont(hdc, "MS Shell Dlg", 13)->GetHFont();

    int railDx = DpiScale(210);
    if (railDx > rc.dx / 3) {
        railDx = rc.dx / 3;
    }
    Rect rail(rc.x, rc.y, railDx, rc.dy);
    Rect main(rc.x + railDx, rc.y, rc.dx - railDx, rc.dy);

    EnterLib();
    if (!gModel.loaded) {
        SelectObject(hdc, fontBody);
        Str msg = len(gModel.error) > 0 ? gModel.error : StrL("Opening the library...");
        Rect r(rc.x + DpiScale(40), rc.y + rc.dy / 2 - DpiScale(20), rc.dx - DpiScale(80), DpiScale(60));
        DrawTextIn(hdc, r, msg, DT_CENTER | DT_WORDBREAK | DT_NOPREFIX, Mix(text, bg, 40));
        LeaveLib();
        SelectObject(hdc, GetStockObject(SYSTEM_FONT));
        return;
    }

    DrawRail(hdc, win, rail, fontSub, fontHead);

    int barDx = BarWidth(hdc);
    Rect body(main.x, main.y, main.dx - barDx, main.dy);
    int saved = SaveDC(hdc);
    HRGN clip = CreateRectRgn(body.x, body.y, body.x + body.dx, body.y + body.dy);
    SelectClipRgn(hdc, clip);
    DeleteObject(clip);

    if (gDetailOpen) {
        DrawDetail(hdc, win, body, win->homePageScrollY, fontTitle, fontSub, fontHead, fontBody);
    } else if (gDeskOpen) {
        DrawDeskpan(hdc, win, body, win->homePageScrollY, fontTitle, fontSub, fontHead);
    } else {
        DrawGrid(hdc, win, body, win->homePageScrollY, fontTitle, fontSub, fontHead);
    }
    RestoreDC(hdc, saved);
    LeaveLib();

    int mostY = gContentDy - main.dy;
    if (mostY < 0) {
        mostY = 0;
    }
    if (win->homePageScrollY > mostY) {
        win->homePageScrollY = mostY;
    }
    LayoutBar(hdc, &gMainBar, Rect(main.x + main.dx - barDx, main.y, barDx, main.dy), gContentDy, main.dy,
              win->homePageScrollY);
    DrawBar(hdc, gMainBar, gBarHot == &gMainBar || gBarDrag == &gMainBar);

    SelectObject(hdc, GetStockObject(SYSTEM_FONT));
}

static int VisibleDy(MainWindow* win) {
    Rect rc = HwndClientRect(win->hwndCanvas);
    return rc.dy;
}

static void ScrollTo(MainWindow* win, int y) {
    int maxY = gContentDy - VisibleDy(win);
    if (maxY < 0) {
        maxY = 0;
    }
    if (y > maxY) {
        y = maxY;
    }
    if (y < 0) {
        y = 0;
    }
    if (y != win->homePageScrollY) {
        win->homePageScrollY = y;
        InvalidateRect(win->hwndCanvas, nullptr, FALSE);
    }
}

static void RailScrollTo(MainWindow* win, int y) {
    int most = gRailDy - gRailBand.dy;
    if (most < 0) {
        most = 0;
    }
    y = limitValue(y, 0, most);
    if (y != gRailScrollY) {
        gRailScrollY = y;
        InvalidateRect(win->hwndCanvas, nullptr, FALSE);
    }
}

static LibScrollBar* BarAt(int x, int y) {
    Point pt(x, y);
    if (gMainBar.live && gMainBar.track.Contains(pt)) {
        return &gMainBar;
    }
    if (gRailBar.live && gRailBar.track.Contains(pt)) {
        return &gRailBar;
    }
    return nullptr;
}

static void BarScrollTo(MainWindow* win, LibScrollBar* bar, int pos) {
    if (bar == &gRailBar) {
        RailScrollTo(win, pos);
    } else {
        ScrollTo(win, pos);
    }
}

bool LibraryOnLeftButtonDown(MainWindow* win, int x, int y) {
    if (CoverEditorActive()) {
        return CoverEditorOnLeftButtonDown(win, x, y);
    }
    // An active inline edit (e.g. the user clicked the title to
    // rename) must commit when the user clicks anywhere else. The
    // canvas doesn't take focus on its own — and without an explicit
    // SetFocus, the EDITTEXT never receives WM_KILLFOCUS, so the
    // commit never fires. Force a commit here, then re-dispatch
    // the click so the user's actual click target is still hit.
    if (gInlineEdit.hwnd != nullptr) {
        CommitInlineEdit(true);
    }
    LibScrollBar* bar = BarAt(x, y);
    if (!bar) {
        return false;
    }
    if (bar->thumb.Contains(Point(x, y))) {
        gBarGrabDy = y - bar->thumb.y;
    } else {
        gBarGrabDy = bar->thumb.dy / 2;
        BarScrollTo(win, bar, PosFromBar(*bar, y - gBarGrabDy));
    }
    gBarDrag = bar;
    gBarHot = bar;
    SetCapture(win->hwndCanvas);
    InvalidateRect(win->hwndCanvas, nullptr, FALSE);
    return true;
}

bool LibraryOnMouseMove(MainWindow* win, int x, int y) {
    if (CoverEditorActive()) {
        return CoverEditorOnMouseMove(win, x, y);
    }
    if (gBarDrag) {
        BarScrollTo(win, gBarDrag, PosFromBar(*gBarDrag, y - gBarGrabDy));
        return true;
    }
    LibScrollBar* hot = BarAt(x, y);
    if (hot != gBarHot) {
        gBarHot = hot;
        InvalidateRect(win->hwndCanvas, nullptr, FALSE);
    }
    // Show a hand cursor when hovering over a static link (book row,
    // back-to-library arrow, edit-metadata title, change-cover link,
    // etc.). Win32 only delivers WM_SETCURSOR on the canvas via
    // Canvas.cpp, but the library home page is drawn on the frame
    // window, not the canvas — so we have to drive the cursor here.
    TempStr linkUrl = GetStaticLinkAtTemp(win->staticLinks, x, y, nullptr);
    if (len(linkUrl) > 0) {
        SetCursorCached(IDC_HAND);
    }
    return hot != nullptr;
}

bool LibraryOnLeftButtonUp(MainWindow* win) {
    if (CoverEditorActive()) {
        return CoverEditorOnLeftButtonUp(win);
    }
    if (!gBarDrag) {
        return false;
    }
    gBarDrag = nullptr;
    ReleaseCapture();
    InvalidateRect(win->hwndCanvas, nullptr, FALSE);
    return true;
}

void LibraryOnCaptureLost(MainWindow* win) {
    if (CoverEditorActive()) {
        CoverEditorOnCaptureLost();
        return;
    }
    if (!gBarDrag) {
        return;
    }
    gBarDrag = nullptr;
    InvalidateRect(win->hwndCanvas, nullptr, FALSE);
}

static bool CursorOnRail(MainWindow* win, int screenX, int screenY) {
    if (gRailBand.dy <= 0 || gRailDy <= gRailBand.dy) {
        return false;
    }
    POINT pt{screenX, screenY};
    ScreenToClient(win->hwndCanvas, &pt);
    return gRailBand.Contains(Point(pt.x, pt.y));
}

void LibraryOnMouseWheel(MainWindow* win, int delta, int screenX, int screenY) {
    if (CoverEditorActive()) {
        return;
    }
    int step = DpiScale(90);
    if (CursorOnRail(win, screenX, screenY)) {
        RailScrollTo(win, gRailScrollY + (delta > 0 ? -step : step));
        return;
    }
    ScrollTo(win, win->homePageScrollY + (delta > 0 ? -step : step));
}

void LibraryOnVScroll(MainWindow* win, WPARAM wp) {
    int line = DpiScale(90);
    int page = VisibleDy(win) - line;
    int y = win->homePageScrollY;
    switch (LOWORD(wp)) {
        case SB_LINEUP:
            y -= line;
            break;
        case SB_LINEDOWN:
            y += line;
            break;
        case SB_PAGEUP:
            y -= page;
            break;
        case SB_PAGEDOWN:
            y += page;
            break;
        case SB_TOP:
            y = 0;
            break;
        case SB_BOTTOM:
            y = INT_MAX;
            break;
        case SB_THUMBTRACK:
        case SB_THUMBPOSITION:
            y = (int)(short)HIWORD(wp);
            break;
    }
    ScrollTo(win, y);
}

struct LibOpenJob {
    MainWindow* win = nullptr;
    int page = 0;
    bool audiobook = false;
};

static void OnLibraryBookLoaded(LibOpenJob* job, bool ok) {
    AutoDelete del(job);
    MainWindow* win = job->win;
    if (!ok || !IsMainWindowValid(win) || win->isBeingClosed || !win->ctrl) {
        return;
    }
    if (job->page > 0 && win->ctrl->ValidPageNo(job->page)) {
        win->ctrl->GoToPage(job->page, false);
        UpdateToolbarPageText(win, win->ctrl->PageCount());
    }
    if (job->audiobook) {
        HwndSendCommand(win->hwndFrame, CmdReadAloudFromTopPage);
    }
}

static void LibraryOpenBook(MainWindow* win, Str path, int pageNo, bool audiobook = false) {
    if (len(path) == 0) {
        return;
    }
    if (pageNo == 0) {
        FileState* fs = FileHistoryFindByPath(path);
        if (fs && fs->pageNo > 1) {
            pageNo = fs->pageNo;
        }
    }
    auto job = new LibOpenJob;
    job->win = win;
    job->page = pageNo;
    job->audiobook = audiobook;
    LoadArgs args(path, win);
    args.activateExisting = true;
    args.activateExistingInWindow = true;
    args.onFinished = MkFunc1<LibOpenJob, bool>(OnLibraryBookLoaded, job);
    StartLoadDocument(&args);
}

bool LibraryOnLinkClicked(MainWindow* win, Str url) {
    if (!str::StartsWith(url, Str(kLinkLibraryPrefix))) {
        return false;
    }
    if (CoverEditorOnLink(win, url)) {
        return true;
    }
    if (str::Eq(url, Str(kLinkChangeCover))) {
        EnterLib();
        Str id = str::Dup(gDetail.id);
        Str path = str::Dup(gDetail.path);
        Str title = str::Dup(gDetail.title);
        LeaveLib();
        CoverEditorOpen(win, id, path, title);
        str::Free(id);
        str::Free(path);
        str::Free(title);
        return true;
    }
    if (str::StartsWith(url, Str(kLinkAllBooks))) {
        EnterLib();
        str::FreePtr(&gModel.filter);
        str::FreePtr(&gModel.filterName);
        LeaveLib();
        gDetailOpen = false;
        gDeskOpen = false;
        win->homePageScrollY = 0;
        InvalidateRect(win->hwndCanvas, nullptr, FALSE);
        return true;
    }
    if (str::StartsWith(url, Str(kLinkEditTitle))) {
        // Click on the title in the detail view opens an inline edit
        // (like F2 rename in Windows Explorer) — NOT a modal dialog.
        // The dialog is still reachable via the right-click "Edit
        // metadata" menu for users who want the full multi-field
        // experience.
        LibBook* book = BookById(AfterPrefix(url, kLinkEditTitle));
        if (book != nullptr) {
            StartInlineEdit(win, book, InlineField::Title, gDetailTitleRect);
        }
        return true;
    }
    if (str::StartsWith(url, Str(kLinkEditMetadata))) {
        // Right-click "Edit metadata" menu item — opens the full
        // per-book edit dialog with current values + per-field source
        // attribution. The dialog has a Revert button per field and
        // a "Clear all overrides" button, which the inline edit
        // can't show.
        LibBook* book = BookById(AfterPrefix(url, kLinkEditMetadata));
        if (book != nullptr) {
            EnterLib();
            gDetailOpen = false;
            LeaveLib();
            RunEditBookMetadata(win, book);
        }
        return true;
    }
    if (str::StartsWith(url, Str(kLinkDeskpan))) {
        gDetailOpen = false;
        gDeskOpen = true;
        win->homePageScrollY = 0;
        EnterLib();
        DeskStopSelecting();
        LeaveLib();
        EnsureDesk();
        InvalidateRect(win->hwndCanvas, nullptr, FALSE);
        return true;
    }
    if (str::StartsWith(url, Str(kLinkDeskShow))) {
        bool ignored = atoi(AfterPrefix(url, kLinkDeskShow).s) == 1;
        EnterLib();
        bool changed = gDesk.showIgnored != ignored;
        gDesk.showIgnored = ignored;
        DeskStopSelecting();
        LeaveLib();
        if (changed) {
            win->homePageScrollY = 0;
            ReloadDesk();
        }
        InvalidateRect(win->hwndCanvas, nullptr, FALSE);
        return true;
    }
    if (str::StartsWith(url, Str(kLinkDeskPickAll))) {
        EnterLib();
        if (DeskChosenCount() >= gDesk.nFiles) {
            DeskStopSelecting();
        } else {
            for (int i = 0; i < gDesk.nFiles; i++) {
                gDesk.files[i].chosen = true;
            }
            gDesk.selecting = true;
            gDesk.anchor = -1;
        }
        LeaveLib();
        InvalidateRect(win->hwndCanvas, nullptr, FALSE);
        return true;
    }
    if (str::StartsWith(url, Str(kLinkDeskPick))) {
        int i = atoi(AfterPrefix(url, kLinkDeskPick).s);
        EnterLib();
        bool selecting = gDesk.selecting;
        TempStr path = str::DupTemp(i >= 0 && i < gDesk.nFiles ? gDesk.files[i].path : Str{});
        if (selecting) {
            DeskToggle(i);
        }
        LeaveLib();
        if (!selecting && len(path) > 0) {
            LibraryOpenBook(win, path, 0);
            return true;
        }
        InvalidateRect(win->hwndCanvas, nullptr, FALSE);
        return true;
    }
    if (str::StartsWith(url, Str(kLinkDeskMove))) {
        Str kind = AfterPrefix(url, kLinkDeskMove);
        EnterLib();
        TempStr want = str::DupTemp(kind);
        MoveDeskChosen(want.s);
        LeaveLib();
        InvalidateRect(win->hwndCanvas, nullptr, FALSE);
        return true;
    }
    if (str::StartsWith(url, Str(kLinkSort))) {
        Str how = AfterPrefix(url, kLinkSort);
        if (gGlobalPrefs && !str::Eq(LibrarySortOrder(), how)) {
            str::ReplaceWithCopy(&gGlobalPrefs->audiobook.librarySort, how);
            SaveSettings();
            gRailScrollY = 0;
            EnterLib();
            gModel.loaded = false;
            gModel.loadFailed = false;
            LeaveLib();
            EnsureModel();
        }
        InvalidateRect(win->hwndCanvas, nullptr, FALSE);
        return true;
    }
    if (str::StartsWith(url, Str(kLinkSeries))) {
        Str key = AfterPrefix(url, kLinkSeries);
        EnterLib();
        str::ReplaceWithCopy(&gModel.filter, key);
        str::FreePtr(&gModel.filterName);
        for (int i = 0; i < gModel.nSeries; i++) {
            if (str::EqI(gModel.series[i].key, key)) {
                str::ReplaceWithCopy(&gModel.filterName, gModel.series[i].name);
                break;
            }
        }
        LeaveLib();
        gDetailOpen = false;
        gDeskOpen = false;
        win->homePageScrollY = 0;
        InvalidateRect(win->hwndCanvas, nullptr, FALSE);
        return true;
    }
    if (str::StartsWith(url, Str(kLinkBook))) {
        if (!gDetailOpen) {
            win->libScrollYBeforeDetail = win->homePageScrollY;
            win->libScrollYSaved = true;
        }
        OpenDetail(AfterPrefix(url, kLinkBook));
        win->homePageScrollY = 0;
        InvalidateRect(win->hwndCanvas, nullptr, FALSE);
        return true;
    }
    if (str::StartsWith(url, Str(kLinkBack))) {
        gDetailOpen = false;
        if (win->libScrollYSaved) {
            win->homePageScrollY = win->libScrollYBeforeDetail;
            win->libScrollYSaved = false;
        } else {
            win->homePageScrollY = 0;
        }
        InvalidateRect(win->hwndCanvas, nullptr, FALSE);
        return true;
    }
    if (str::StartsWith(url, Str(kLinkRead))) {
        Str path = AfterPrefix(url, kLinkRead);
        LoadArgs args(path, win);
        args.activateExisting = true;
        args.activateExistingInWindow = true;
        StartLoadDocument(&args);
        return true;
    }
    if (str::StartsWith(url, Str(kLinkOpen))) {
        LibraryOpenBook(win, AfterPrefix(url, kLinkOpen), 0);
        return true;
    }
    if (str::StartsWith(url, Str(kLinkPage))) {
        Str rest = AfterPrefix(url, kLinkPage);
        const char* bar = strchr(rest.s, '|');
        if (bar) {
            int pageNo = atoi(rest.s);
            Str path(bar + 1, (int)(rest.s + rest.len - bar - 1));
            LibraryOpenBook(win, path, pageNo);
        }
        return true;
    }
    if (str::StartsWith(url, Str(kLinkChapter))) {
        int i = atoi(AfterPrefix(url, kLinkChapter).s);
        EnterLib();
        if (i >= 0 && i < gDetail.nChapters) {
            gDetail.chapters[i].open = !gDetail.chapters[i].open;
        }
        LeaveLib();
        InvalidateRect(win->hwndCanvas, nullptr, FALSE);
        return true;
    }
    if (str::StartsWith(url, Str(kLinkTopicList))) {
        EnterLib();
        str::FreePtr(&gDetail.topic);
        LeaveLib();
        win->homePageScrollY = 0;
        InvalidateRect(win->hwndCanvas, nullptr, FALSE);
        return true;
    }
    if (str::StartsWith(url, Str(kLinkTopic))) {
        OpenTopic(AfterPrefix(url, kLinkTopic));
        gDetail.tab = LibTab::Knows;
        win->homePageScrollY = 0;
        InvalidateRect(win->hwndCanvas, nullptr, FALSE);
        return true;
    }
    if (str::StartsWith(url, Str(kLinkScreenTitle))) {
        Str id = AfterPrefix(url, kLinkScreenTitle);
        SumatraLaunchBrowser(fmt("https://www.imdb.com/title/%s/", id));
        return true;
    }
    if (str::StartsWith(url, Str(kLinkTab))) {
        Str which = AfterPrefix(url, kLinkTab);
        gDetail.tab = (LibTab)atoi(which.s);
        win->homePageScrollY = 0;
        InvalidateRect(win->hwndCanvas, nullptr, FALSE);
        return true;
    }
    if (str::StartsWith(url, Str(kLinkPerson))) {
        OpenPerson(AfterPrefix(url, kLinkPerson));
        gDetail.tab = LibTab::People;
        win->homePageScrollY = 0;
        InvalidateRect(win->hwndCanvas, nullptr, FALSE);
        return true;
    }
    if (str::StartsWith(url, Str(kLinkRescan))) {
        LibraryRefresh(win, true);
        return true;
    }
    if (str::StartsWith(url, Str(kLinkImportBook))) {
        LibraryImportBook(win);
        return true;
    }
    if (str::StartsWith(url, Str(kLinkProgressiveScan))) {
        if (gGlobalPrefs) {
            gGlobalPrefs->audiobook.progressiveLibraryScan = !gGlobalPrefs->audiobook.progressiveLibraryScan;
            SaveSettings();
            InvalidateRect(win->hwndCanvas, nullptr, FALSE);
        }
        return true;
    }
    if (str::StartsWith(url, Str(kLinkClassic))) {
        SetLibraryHomeEnabled(false);
        SaveSettings();
        win->homePageScrollY = 0;
        win->RedrawAll(true);
        return true;
    }
    return true;
}

static TempStr BookPathAtTemp(MainWindow* win, int x, int y) {
    TempStr url = GetStaticLinkAtTemp(win->staticLinks, x, y, nullptr);
    if (len(url) == 0) {
        return {};
    }
    if (str::StartsWith(url, Str(kLinkOpen))) {
        return str::DupTemp(AfterPrefix(url, kLinkOpen));
    }
    if (str::StartsWith(url, Str(kLinkBook))) {
        Str id = AfterPrefix(url, kLinkBook);
        for (int i = 0; i < gModel.nBooks; i++) {
            if (str::Eq(gModel.books[i].id, id)) {
                return str::DupTemp(gModel.books[i].path);
            }
        }
    }
    return {};
}

static TempStr RowKeyAtTemp(MainWindow* win, int x, int y) {
    TempStr url = GetStaticLinkAtTemp(win->staticLinks, x, y, nullptr);
    if (len(url) == 0) {
        return {};
    }
    if (str::StartsWith(url, Str(kLinkSeries))) {
        return str::DupTemp(AfterPrefix(url, kLinkSeries));
    }
    Str id;
    if (str::StartsWith(url, Str(kLinkBook))) {
        id = AfterPrefix(url, kLinkBook);
    } else if (str::StartsWith(url, Str(kLinkOpen))) {
        Str path = AfterPrefix(url, kLinkOpen);
        for (int i = 0; i < gModel.nBooks; i++) {
            if (str::Eq(gModel.books[i].path, path)) {
                id = gModel.books[i].id;
                break;
            }
        }
    }
    if (len(id) == 0) {
        return {};
    }
    for (int i = 0; i < gModel.nBooks; i++) {
        if (!str::Eq(gModel.books[i].id, id)) {
            continue;
        }
        Str keys = gModel.books[i].keys;
        if (len(keys) < 3) {
            return {};
        }
        const char* end = keys.s + len(keys) - 1;
        const char* start = end - 1;
        while (start > keys.s && *start != '|') {
            start--;
        }
        return str::DupTemp(Str(start + 1, (int)(end - start - 1)));
    }
    return {};
}

static LibBook* BookByPath(Str path) {
    for (int i = 0; i < gModel.nBooks; i++) {
        if (str::Eq(gModel.books[i].path, path)) {
            return &gModel.books[i];
        }
    }
    return nullptr;
}

static LibBook* BookById(Str id) {
    for (int i = 0; i < gModel.nBooks; i++) {
        if (str::Eq(gModel.books[i].id, id)) {
            return &gModel.books[i];
        }
    }
    return nullptr;
}

static LibSeries* RowByKey(Str key) {
    for (int i = 0; i < gModel.nSeries; i++) {
        if (str::Eq(gModel.series[i].key, key)) {
            return &gModel.series[i];
        }
    }
    return nullptr;
}

static bool CanLeaveSeries(const LibBook* b) {
    if (!b || len(b->seriesKey) == 0) {
        return false;
    }
    LibSeries* row = RowByKey(b->seriesKey);
    if (!row || len(row->kind) == 0) {
        return false;
    }
    return !str::Eq(row->kind, StrL("loose")) && !str::Eq(row->kind, StrL("usershelf")) &&
           !str::Eq(row->kind, StrL("partition"));
}

static bool CanHostBooks(const LibSeries& row) {
    return !str::Eq(row.kind, StrL("partition")) && !str::Eq(row.kind, StrL("loose"));
}

struct LibRowParentPick {
    Str key;
};

static LibRowParentPick gRowParentPicks[kMaxSeries];
static int gnRowParentPicks = 0;

static void FreeRowParentPicks() {
    for (int i = 0; i < gnRowParentPicks; i++) {
        str::Free(gRowParentPicks[i].key);
    }
    gnRowParentPicks = 0;
}

static bool RowIsUnder(Str key, Str above) {
    if (str::Eq(key, above)) {
        return false;
    }
    Str chain[16];
    int n = SeriesChain(key, chain, 16);
    return InChain(chain, n, above);
}

static HMENU PartitionDestMenu(LibSeries* row, LibPartition* home) {
    HMENU into = CreatePopupMenu();
    for (int i = 0; i < gNPartitions; i++) {
        LibPartition& p = gPartitions[i];
        if (row && str::Eq(row->key, p.key)) {
            continue;
        }
        str::Builder label;
        for (int step = 0; step < p.depth; step++) {
            label.Append("    ");
        }
        label.Append(fmt("%s  (%d)", p.name, p.books));
        uint flags = MF_STRING;
        if (home && str::Eq(home->key, p.key)) {
            flags |= MF_CHECKED;
        }
        AppendMenuW(into, flags, kMenuPartitionFirst + i, ToWStrTemp(ToStr(label)).s);
    }
    if (gNPartitions > 0) {
        AppendMenuW(into, MF_SEPARATOR, 0, nullptr);
    }
    AppendMenuW(into, MF_STRING, kMenuNewPartition, ToWStrTemp(StrL("New partition...")).s);
    return into;
}

static HMENU SeriesDestMenu(LibSeries* row) {
    FreeRowParentPicks();
    if (!row) {
        return nullptr;
    }
    HMENU into = CreatePopupMenu();
    for (int i = 0; i < gModel.nSeries && gnRowParentPicks < kMaxSeries; i++) {
        LibSeries& cand = gModel.series[i];
        if (len(cand.key) == 0 || len(cand.name) == 0 || !CanHostBooks(cand)) {
            continue;
        }
        if (str::Eq(cand.key, row->key) || RowIsUnder(cand.key, row->key)) {
            continue;
        }
        str::Builder label;
        for (int step = 0; step < cand.depth; step++) {
            label.Append("    ");
        }
        label.Append(fmt("%s  (%d)", cand.name, cand.books));
        uint flags = MF_STRING;
        if (str::Eq(row->parent, cand.key)) {
            flags |= MF_CHECKED;
        }
        AppendMenuW(into, flags, kMenuRowParentFirst + gnRowParentPicks, ToWStrTemp(ToStr(label)).s);
        gRowParentPicks[gnRowParentPicks].key = str::Dup(cand.key);
        gnRowParentPicks++;
    }
    if (gnRowParentPicks == 0) {
        DestroyMenu(into);
        return nullptr;
    }
    return into;
}

static void AddPartitionMenu(HMENU popup, Str rowKey) {
    LibSeries* row = RowByKey(rowKey);
    LibPartition* home = row ? PartitionByKey(row->parent) : nullptr;
    HMENU move = CreatePopupMenu();
    AppendMenuW(move, MF_POPUP, (UINT_PTR)PartitionDestMenu(row, home), ToWStrTemp(StrL("Partition")).s);
    HMENU series = SeriesDestMenu(row);
    if (series) {
        AppendMenuW(move, MF_POPUP, (UINT_PTR)series, ToWStrTemp(StrL("Series")).s);
    }
    if (row && !home && str::EqI(row->parentSource, StrL("user"))) {
        AppendMenuW(move, MF_SEPARATOR, 0, nullptr);
        AppendMenuW(move, MF_STRING, kMenuRestoreAutoParent, ToWStrTemp(StrL("Restore automatic parent")).s);
    }
    AppendMenuW(popup, MF_POPUP, (UINT_PTR)move, ToWStrTemp(StrL("Move to")).s);
    if (home) {
        AppendMenuW(popup, MF_STRING, kMenuTakeOutOfPartition, ToWStrTemp(fmt("Take out of %s", home->name)).s);
    }
    if (row && str::Eq(row->kind, StrL("partition"))) {
        AppendMenuW(popup, MF_SEPARATOR, 0, nullptr);
        AppendMenuW(popup, MF_STRING, kMenuRenamePartition, ToWStrTemp(StrL("Rename partition...")).s);
        AppendMenuW(popup, MF_STRING, kMenuDeletePartition, ToWStrTemp(StrL("Delete partition")).s);
    }
}

static void RunPartitionCommand(MainWindow* win, int cmd, Str rowKey) {
    LibSeries* row = RowByKey(rowKey);
    bool isPartition = row && str::Eq(row->kind, StrL("partition"));
    if (cmd == kMenuNewPartition) {
        Str name{};
        if (Dialog_PartitionName(win->hwndFrame, StrL("New partition"), StrL("&Name this partition:"), name)) {
            TempStr body = fmt("{\"name\":%s,\"row\":%s}", JsonStrTemp(name),
                               isPartition ? StrL("null") : Str(JsonStrTemp(rowKey)));
            PostPartition("/partition/new", Str(body));
        }
        str::Free(name);
        return;
    }
    if (cmd == kMenuTakeOutOfPartition && row) {
        TempStr body = fmt("{\"key\":\"\",\"row\":%s,\"out_of\":%s}", JsonStrTemp(rowKey), JsonStrTemp(row->parent));
        PostPartition("/partition/assign", Str(body));
        return;
    }
    if (cmd == kMenuRenamePartition && isPartition) {
        Str name = str::Dup(row->name);
        if (Dialog_PartitionName(win->hwndFrame, StrL("Rename partition"), StrL("&Name this partition:"), name)) {
            TempStr body = fmt("{\"key\":%s,\"name\":%s}", JsonStrTemp(rowKey), JsonStrTemp(name));
            PostPartition("/partition/rename", Str(body));
        }
        str::Free(name);
        return;
    }
    if (cmd == kMenuDeletePartition && isPartition) {
        TempStr body = fmt("{\"key\":%s}", JsonStrTemp(rowKey));
        PostPartition("/partition/delete", Str(body));
        return;
    }
    int pick = cmd - kMenuPartitionFirst;
    if (pick < 0 || pick >= gNPartitions) {
        return;
    }
    Str key = gPartitions[pick].key;
    if (isPartition) {
        TempStr body = fmt("{\"key\":%s,\"parent\":%s}", JsonStrTemp(rowKey), JsonStrTemp(key));
        PostPartition("/partition/nest", Str(body));
        return;
    }
    TempStr body = fmt("{\"key\":%s,\"row\":%s}", JsonStrTemp(key), JsonStrTemp(rowKey));
    PostPartition("/partition/assign", Str(body));
}

static LibSeries* FindSeriesByKey(Str key) {
    for (int i = 0; i < gModel.nSeries; i++) {
        if (str::Eq(gModel.series[i].key, key)) {
            return &gModel.series[i];
        }
    }
    return nullptr;
}

// ===== Inline field edit (Windows file rename) =====
//
// Click on the title (or author/series/year, once they're clickable) in
// the detail view and the text turns into an EDITTEXT in place — exactly
// like F2 rename in Windows Explorer. Enter saves, Escape cancels,
// focus-loss commits (matching Explorer, which commits on focus loss
// and treats Escape as cancel). One edit at a time; starting a new one
// commits the previous one first.
//
// (struct InlineEdit and the gInlineEdit global are defined near
// the top of the file so the early click handler can see them.)

static Str LibBookGetField(LibBook* b, InlineField f) {
    switch (f) {
        case InlineField::Title:
            return b->title;
        case InlineField::Author:
            return b->author;
        case InlineField::Series:
            return b->series;
        case InlineField::Year:
            return b->year > 0 ? fmt("%d", b->year) : Str();
    }
    return {};
}

static void LibBookSetField(LibBook* b, InlineField f, Str v, Str exactKey = {}) {
    switch (f) {
        case InlineField::Title:
            str::ReplaceWithCopy(&b->title, v);
            str::ReplaceWithCopy(&b->titleSource, StrL("user"));
            str::ReplaceWithCopy(&gDetail.title, v);
            break;
        case InlineField::Author:
            str::ReplaceWithCopy(&b->author, v);
            str::ReplaceWithCopy(&b->authorSource, StrL("user"));
            str::ReplaceWithCopy(&gDetail.author, v);
            break;
        case InlineField::Year:
            b->year = atoi(v.s ? v.s : "0");
            str::ReplaceWithCopy(&b->yearSource, StrL("user"));
            gDetail.year = b->year;
            break;
        case InlineField::Series:
            // The value the user typed is what the portable writer embeds,
            // marked as theirs. The native model owns where the book now
            // sits, so the tree is rebuilt around the new name before any
            // catalogue refresh happens.
            str::ReplaceWithCopy(&b->series, v);
            str::ReplaceWithCopy(&b->seriesSource, StrL("user"));
            str::ReplaceWithCopy(&gDetail.series, v);
            EnterLib();
            if (len(exactKey) > 0 && RowByKey(exactKey)) {
                str::ReplaceWithCopy(&b->seriesKey, exactKey);
            }
            RebuildSeriesTree();
            LeaveLib();
            break;
    }
}

// Revert / Clear all overrides. The value the user is looking at stays
// put; what goes away is the "user" authority over it, so the next
// generated or online value is free to replace it again. Without this
// the store and the model keep saying "user" and the revert is nothing
// but a repainted label.
static void LibBookClearFieldSource(LibBook* b, InlineField f) {
    switch (f) {
        case InlineField::Title:
            str::ReplaceWithCopy(&b->titleSource, StrL(""));
            break;
        case InlineField::Author:
            str::ReplaceWithCopy(&b->authorSource, StrL(""));
            break;
        case InlineField::Year:
            str::ReplaceWithCopy(&b->yearSource, StrL(""));
            break;
        case InlineField::Series:
            str::ReplaceWithCopy(&b->seriesSource, StrL(""));
            break;
    }
}

static const char* InlineFieldName(InlineField f) {
    switch (f) {
        case InlineField::Title:
            return "title";
        case InlineField::Author:
            return "author";
        case InlineField::Year:
            return "year";
        case InlineField::Series:
            return "series";
    }
    return "title";
}

static void PostUserFieldEdit(LibBook* book, InlineField field, Str value, bool restoreAuto, Str exactKey = {}) {
    if (!book) {
        return;
    }
    int reverted = 0;
    if (restoreAuto) {
        LibBookClearFieldSource(book, field);
        switch (field) {
            case InlineField::Title:
                reverted = kRevertedTitle;
                break;
            case InlineField::Author:
                reverted = kRevertedAuthor;
                break;
            case InlineField::Year:
                reverted = kRevertedYear;
                break;
            case InlineField::Series:
                reverted = kRevertedSeries;
                break;
        }
    } else {
        LibBookSetField(book, field, value, exactKey);
    }
    TempStr body;
    if (restoreAuto) {
        body = fmt("{\"id\":%s,\"fields\":{\"%s\":{\"value\":\"\",\"source\":\"\"}}}", JsonStrTemp(book->id),
                   Str(InlineFieldName(field)));
    } else if (len(exactKey) > 0) {
        body = fmt("{\"id\":%s,\"fields\":{\"%s\":{\"value\":%s,\"source\":\"user\",\"key\":%s}}}",
                   JsonStrTemp(book->id), Str(InlineFieldName(field)), JsonStrTemp(value), JsonStrTemp(exactKey));
    } else {
        body = fmt("{\"id\":%s,\"fields\":{\"%s\":{\"value\":%s,\"source\":\"user\"}}}", JsonStrTemp(book->id),
                   Str(InlineFieldName(field)), JsonStrTemp(value));
    }
    PostBookEdit("/book/edit", Str(body), book->id, reverted);
}

void TestTriggerUserFieldEdit(Str bookId, Str field, Str value, Str exactKey) {
    LibBook* book = BookById(bookId);
    if (!book) {
        return;
    }
    InlineField which = InlineField::Title;
    if (str::EqI(field, StrL("author"))) {
        which = InlineField::Author;
    } else if (str::EqI(field, StrL("year"))) {
        which = InlineField::Year;
    } else if (str::EqI(field, StrL("series"))) {
        which = InlineField::Series;
    }
    PostUserFieldEdit(book, which, value, false, exactKey);
}

int CurrentLibScrollY() {
    if (len(gWindows) == 0) {
        return 0;
    }
    return gWindows[0]->homePageScrollY;
}

void TestLibScrollToY(int y) {
    if (len(gWindows) == 0) {
        return;
    }
    MainWindow* win = gWindows[0];
    ScrollTo(win, y);
}

void TestLibForceScrollY(int y) {
    if (len(gWindows) == 0) {
        return;
    }
    MainWindow* win = gWindows[0];
    win->homePageScrollY = y;
    InvalidateRect(win->hwndCanvas, nullptr, FALSE);
}

void TestLibOpenBookById(Str bookId) {
    if (len(gWindows) == 0) {
        return;
    }
    MainWindow* win = gWindows[0];
    Str url = str::Join(kLinkBook, bookId);
    LibraryOnLinkClicked(win, url);
}

void TestLibClickBack() {
    if (len(gWindows) == 0) {
        return;
    }
    MainWindow* win = gWindows[0];
    LibraryOnLinkClicked(win, Str(kLinkBack));
}

void TestLibRescan() {
    LibraryRefresh(nullptr, true);
}

TempStr TestLibScanStatus() {
    EnterLib();
    int scanning = gModel.scanning ? 1 : 0;
    int done = gModel.scanDone;
    int total = gModel.scanTotal;
    int scanning_ = gNativeScanning ? 1 : 0;
    int ocr_sweep = gSweepActive ? 1 : 0;
    int traversals = gModel.scanTraversals;
    int discovered = gModel.scanFilesDiscovered;
    int processed = gModel.scanFilesProcessed;
    int itemDone = gModel.scanItemDone;
    int itemTotal = gModel.scanItemTotal;
    int itemIndeterminate = gModel.scanItemIndeterminate ? 1 : 0;
    int itemAction = 0;
    if (str::Eq(gModel.scanItemAction, StrL("Finding books"))) {
        itemAction = 1;
    } else if (str::Eq(gModel.scanItemAction, StrL("Opening file"))) {
        itemAction = 2;
    } else if (str::Eq(gModel.scanItemAction, StrL("Processing EPUB"))) {
        itemAction = 3;
    } else if (str::Eq(gModel.scanItemAction, StrL("Processing pages"))) {
        itemAction = 4;
    } else if (str::Eq(gModel.scanItemAction, StrL("Publishing item"))) {
        itemAction = 5;
    }
    int visible = gModel.nBooks;
    int progressive = gGlobalPrefs && gGlobalPrefs->audiobook.progressiveLibraryScan ? 1 : 0;
    LeaveLib();
    int full = 0;
    int shape = 0;
    int ocr = 0;
    BookFingerprintPerfCounters(&full, &shape, nullptr, nullptr, &ocr, nullptr, nullptr, nullptr);
    TempStr scan = str::FormatTemp(
        "scanning=%d native=%d done=%d total=%d itemDone=%d itemTotal=%d itemIndeterminate=%d itemAction=%d "
        "traversals=%d "
        "discovered=%d processed=%d "
        "visible=%d progressive=%d sweep=%d",
        scanning, scanning_, done, total, itemDone, itemTotal, itemIndeterminate, itemAction, traversals, discovered,
        processed, visible, progressive, ocr_sweep);
    return str::FormatTemp("%s full=%d shape=%d ocr=%d\n", scan, full, shape, ocr);
}

void TestLibToggleProgressive() {
    if (len(gWindows) == 0) {
        return;
    }
    LibraryOnLinkClicked(gWindows[0], Str(kLinkProgressiveScan));
}

void TestLibClickAllBooks() {
    if (len(gWindows) == 0) {
        return;
    }
    MainWindow* win = gWindows[0];
    LibraryOnLinkClicked(win, Str(kLinkAllBooks));
}

void TestLibClickSeries(Str seriesKey) {
    if (len(gWindows) == 0) {
        return;
    }
    MainWindow* win = gWindows[0];
    Str url = str::Join(kLinkSeries, seriesKey);
    LibraryOnLinkClicked(win, url);
}

static void CommitInlineEdit(bool commit) {
    if (!gInlineEdit.hwnd) {
        return;
    }
    HWND h = gInlineEdit.hwnd;
    LibBook* book = gInlineEdit.book;
    MainWindow* win = gInlineEdit.win;
    InlineField field = gInlineEdit.field;
    Str original = gInlineEdit.original;
    gInlineEdit.committing = true;

    if (commit && book != nullptr) {
        TempStr newVal = HwndGetTextTemp(h);
        if (!str::Eq(newVal, original)) {
            PostUserFieldEdit(book, field, Str(newVal), false);
        }
    }

    // Tear down the edit control and its state.
    SetWindowLongPtrW(h, GWLP_WNDPROC, (LONG_PTR)gInlineEdit.prevWndProc);
    DestroyWindow(h);
    if (gInlineEdit.font) {
        DeleteObject(gInlineEdit.font);
    }
    str::Free(gInlineEdit.original);
    HWND hwndFrame = (win != nullptr) ? win->hwndFrame : nullptr;
    gInlineEdit = InlineEdit{};

    // Force a redraw of the whole frame so the title repaints and
    // the EDITTEXT is gone.
    if (hwndFrame) {
        InvalidateRect(hwndFrame, nullptr, FALSE);
    }
}

static LRESULT CALLBACK InlineEditWndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    switch (msg) {
        case WM_GETDLGCODE:
            return DLGC_WANTALLKEYS;
        case WM_KEYDOWN:
            if (wp == VK_ESCAPE) {
                CommitInlineEdit(false);
                return 0;
            }
            if (wp == VK_RETURN) {
                CommitInlineEdit(true);
                return 0;
            }
            break;
        case WM_KILLFOCUS:
            // Explorer commits on focus loss. If the user clicked
            // somewhere else without pressing Enter, save the value
            // (no-op if unchanged).
            CommitInlineEdit(true);
            return 0;
    }
    return CallWindowProcW(gInlineEdit.prevWndProc, hwnd, msg, wp, lp);
}

static void StartInlineEdit(MainWindow* win, LibBook* book, InlineField field, Rect fieldRect) {
    if (!win || !book) {
        return;
    }
    // If an edit is already running, commit it first (Explorer-style
    // auto-save on focus loss; safer than cancelling in case the
    // user was mid-typing).
    if (gInlineEdit.hwnd != nullptr) {
        CommitInlineEdit(true);
    }

    Str current = LibBookGetField(book, field);
    gInlineEdit.book = book;
    gInlineEdit.win = win;
    gInlineEdit.field = field;
    gInlineEdit.original = str::Dup(current);

    HMODULE hmod = GetModuleHandleW(nullptr);
    DWORD style = WS_CHILD | WS_VISIBLE | WS_BORDER | ES_AUTOHSCROLL;
    // Parent the edit to the canvas (same surface that draws the
    // static text), and use the canvas-local rect directly — no
    // ClientToScreen round-trip needed. The field rect was captured
    // in DrawDetail in the same coordinate space the canvas paints in.
    HWND hEdit = CreateWindowExW(0, WC_EDITW, L"", style, fieldRect.x, fieldRect.y, fieldRect.dx, fieldRect.dy,
                                 win->hwndCanvas, (HMENU)1, hmod, nullptr);
    if (!hEdit) {
        return;
    }
    gInlineEdit.hwnd = hEdit;

    // Match the static-text font we replaced (fontHead) so the
    // in-place edit doesn't shift the layout.
    LOGFONT lf{};
    lf.lfHeight = -DpiScale(18);
    lf.lfWeight = FW_BOLD;
    wcscpy(lf.lfFaceName, L"Segoe UI");
    gInlineEdit.font = CreateFontIndirectW(&lf);
    SetWindowFont(hEdit, gInlineEdit.font, TRUE);

    HwndSetText(hEdit, current);
    SendMessageW(hEdit, EM_SETMARGINS, EC_LEFTMARGIN | EC_RIGHTMARGIN, MAKELPARAM(2, 2));

    gInlineEdit.prevWndProc = (WNDPROC)SetWindowLongPtrW(hEdit, GWLP_WNDPROC, (LONG_PTR)InlineEditWndProc);

    SetFocus(hEdit);
    SendMessageW(hEdit, EM_SETSEL, 0, -1);
    logf("StartInlineEdit: hEdit=%p bookId=%s field=%d current='%s'\n", (void*)hEdit, book->id, (int)field, current);
}

// Fill a BookMetadataField from a string, plus the "user"/"auto" source
// marker. The original is left empty; the dialog will set it.
static void MetaFieldFromStr(BookMetadataField& f, Str value, const char* source) {
    str::ReplaceWithCopy(&f.value, value);
    str::ReplaceWithCopy(&f.source, Str(source ? Str(source) : StrL("")));
    f.overridden = (source && str::EqI(Str(source), StrL("user")));
}

// Build a human-readable tooltip for the book detail title that
// shows where each field came from. e.g.
//   "Click to edit metadata"
//   "Source: filename (01 The Hitchhiker's Guide...).pdf"
//   "Source: pdf-meta"
static TempStr BuildTitleTip() {
    LibBook* b = BookById(gDetail.id);
    if (b == nullptr) {
        return str::DupTemp(StrL("Click to edit metadata"));
    }
    str::Builder tip;
    tip.Append("Click to edit metadata\n");
    if (len(b->file) > 0) {
        tip.Append(fmt("File: %s\n", Str(b->file)));
    }
    if (len(b->titleSource) > 0) {
        tip.Append(fmt("Title source: %s\n", Str(b->titleSource)));
    }
    if (len(b->authorSource) > 0 && len(b->author) > 0) {
        tip.Append(fmt("Author source: %s\n", Str(b->authorSource)));
    }
    if (len(b->yearSource) > 0 && b->year > 0) {
        tip.Append(fmt("Year source: %s\n", Str(b->yearSource)));
    }
    return tip.TakeStr();
}

static void RunEditBookMetadata(MainWindow* win, LibBook* book) {
    BookMetadataEdit edit{};
    edit.bookId = str::Dup(book->id);
    edit.filePath = str::Dup(book->path);
    // Use the per-field source attribution returned by the server so
    // the dialog can show "filename" / "pdf-meta" / "user" / "wikipedia"
    // / etc. next to each value. Without this, the user has no idea
    // where the auto-detected title came from.
    MetaFieldFromStr(edit.title, book->title, book->titleSource.s);
    MetaFieldFromStr(edit.author, book->author, book->authorSource.s);
    MetaFieldFromStr(edit.series, book->series, book->seriesSource.s);
    // year comes back as an int; convert via fmt for the dialog
    if (book->year > 0) {
        TempStr ys = fmt("%d", book->year);
        MetaFieldFromStr(edit.year, Str(ys), book->yearSource.s);
    } else {
        MetaFieldFromStr(edit.year, Str(), book->yearSource.s);
    }
    if (!Dialog_BookMetadata(win->hwndFrame, edit)) {
        // user cancelled — nothing to do
        FreeBookMetadataEdit(edit);
        return;
    }
    // Build the JSON body for /book/edit
    str::Builder body;
    body.Append(fmt("{\"id\":%s,", JsonStrTemp(book->id)));
    body.Append("\"fields\":{");
    bool first = true;
    if (edit.title.overridden) {
        if (!first) body.Append(",");
        first = false;
        body.Append(fmt("\"title\":{\"value\":%s,\"source\":%s}", JsonStrTemp(edit.title.value),
                        JsonStrTemp(edit.title.source)));
    } else if (edit.title.cleared) {
        if (!first) body.Append(",");
        first = false;
        body.Append("\"title\":{\"value\":\"\",\"source\":\"\"}");
    }
    if (edit.author.overridden) {
        if (!first) body.Append(",");
        first = false;
        body.Append(fmt("\"author\":{\"value\":%s,\"source\":%s}", JsonStrTemp(edit.author.value),
                        JsonStrTemp(edit.author.source)));
    } else if (edit.author.cleared) {
        if (!first) body.Append(",");
        first = false;
        body.Append("\"author\":{\"value\":\"\",\"source\":\"\"}");
    }
    if (edit.series.overridden) {
        if (!first) body.Append(",");
        first = false;
        body.Append(fmt("\"series\":{\"value\":%s,\"source\":%s}", JsonStrTemp(edit.series.value),
                        JsonStrTemp(edit.series.source)));
    } else if (edit.series.cleared) {
        if (!first) body.Append(",");
        first = false;
        body.Append("\"series\":{\"value\":\"\",\"source\":\"\"}");
    }
    if (edit.year.overridden) {
        if (!first) body.Append(",");
        first = false;
        body.Append(
            fmt("\"year\":{\"value\":%s,\"source\":%s}", JsonStrTemp(edit.year.value), JsonStrTemp(edit.year.source)));
    } else if (edit.year.cleared) {
        if (!first) body.Append(",");
        first = false;
        body.Append("\"year\":{\"value\":\"\",\"source\":\"\"}");
    }
    body.Append("}}");
    Str bodyStr = body.TakeStr();

    // Update the in-memory model BEFORE posting so the targeted
    // PersistBookMetadata() call (which reads from gModel via
    // CoverBookInfoById) writes the user's new values, not the old ones.
    // The source is also marked "user" so AdoptEmbeddedRecordFields'
    // *Source=="user" guard prevents the next sync from reverting it.
    if (edit.title.overridden) {
        LibBookSetField(book, InlineField::Title, edit.title.value);
    } else if (edit.title.cleared) {
        LibBookClearFieldSource(book, InlineField::Title);
    }
    if (edit.author.overridden) {
        LibBookSetField(book, InlineField::Author, edit.author.value);
    } else if (edit.author.cleared) {
        LibBookClearFieldSource(book, InlineField::Author);
    }
    if (edit.year.overridden) {
        LibBookSetField(book, InlineField::Year, edit.year.value);
    } else if (edit.year.cleared) {
        LibBookClearFieldSource(book, InlineField::Year);
    }
    if (edit.series.overridden) {
        LibBookSetField(book, InlineField::Series, edit.series.value);
    } else if (edit.series.cleared) {
        LibBookClearFieldSource(book, InlineField::Series);
    }

    int reverted = 0;
    if (edit.title.cleared) {
        reverted |= kRevertedTitle;
    }
    if (edit.author.cleared) {
        reverted |= kRevertedAuthor;
    }
    if (edit.series.cleared) {
        reverted |= kRevertedSeries;
    }
    if (edit.year.cleared) {
        reverted |= kRevertedYear;
    }

    PostBookEdit("/book/edit", bodyStr, book->id, reverted);
    FreeBookMetadataEdit(edit);
}

static void RunRenameSeries(MainWindow* win, Str rowKey) {
    LibSeries* row = FindSeriesByKey(rowKey);
    if (!row) {
        return;
    }
    Str currentName = str::Dup(row->name);
    Str newName = str::Dup(currentName);
    if (!Dialog_RenameSeries(win->hwndFrame, currentName, newName)) {
        str::Free(currentName);
        str::Free(newName);
        return;
    }
    if (str::Eq(newName, currentName)) {
        // no change
        str::Free(currentName);
        str::Free(newName);
        return;
    }
    TempStr body = fmt("{\"row\":%s,\"name\":%s}", JsonStrTemp(rowKey), JsonStrTemp(newName));
    PostPartition("/series/rename", body);
    str::Free(currentName);
    str::Free(newName);
}

static int DeskRowAt(MainWindow* win, int x, int y) {
    TempStr url = GetStaticLinkAtTemp(win->staticLinks, x, y, nullptr);
    if (len(url) == 0) {
        return -1;
    }
    if (str::StartsWith(url, Str(kLinkDeskPick))) {
        return atoi(AfterPrefix(url, kLinkDeskPick).s);
    }
    return -1;
}

static bool DeskRightClick(MainWindow* win, int x, int y) {
    EnterLib();
    int row = DeskRowAt(win, x, y);
    if (row < 0 || row >= gDesk.nFiles) {
        LeaveLib();
        return false;
    }
    bool selecting = gDesk.selecting;
    int chosen = DeskChosenCount();
    TempStr path = str::DupTemp(gDesk.files[row].path);
    bool ignoredView = gDesk.showIgnored;
    LeaveLib();

    HMENU popup = CreatePopupMenu();
    if (!selecting) {
        AppendMenuW(popup, MF_STRING, kMenuOpenDocument, ToWStrTemp(StrL("Open")).s);
        AppendMenuW(popup, MF_STRING, kMenuSelectFiles, ToWStrTemp(StrL("Select")).s);
        AppendMenuW(popup, MF_SEPARATOR, 0, nullptr);
    }
    Str move = chosen > 1 ? Str(fmt("Move %d to library", chosen)) : StrL("Move to library");
    AppendMenuW(popup, MF_STRING, kMenuMoveToLibrary, ToWStrTemp(move).s);
    if (ignoredView) {
        AppendMenuW(popup, MF_STRING, kMenuRemoveFromLibrary, ToWStrTemp(StrL("Put back on the desk")).s);
    } else {
        Str hide = chosen > 1 ? Str(fmt("Ignore %d files", chosen)) : StrL("Ignore file");
        AppendMenuW(popup, MF_STRING, kMenuIgnoreFile, ToWStrTemp(hide).s);
    }
    POINT pt = {x, y};
    MapWindowPoints(win->hwndCanvas, HWND_DESKTOP, &pt, 1);
    int cmd = TrackPopupMenu(popup, TPM_RETURNCMD | TPM_RIGHTBUTTON, pt.x, pt.y, 0, win->hwndFrame, nullptr);
    DestroyMenu(popup);

    const char* kind = nullptr;
    if (cmd == kMenuMoveToLibrary) {
        kind = kKindBook;
    } else if (cmd == kMenuRemoveFromLibrary) {
        kind = kKindDocument;
    } else if (cmd == kMenuIgnoreFile) {
        kind = kKindIgnored;
    }
    if (cmd == kMenuOpenDocument) {
        LibraryOpenBook(win, path, 0);
        return true;
    }
    if (cmd == kMenuSelectFiles) {
        EnterLib();
        DeskStartSelecting(row);
        LeaveLib();
    } else if (kind && selecting) {
        EnterLib();
        MoveDeskChosen(kind);
        LeaveLib();
    } else if (kind) {
        MoveOneFile(path, kind);
    }
    InvalidateRect(win->hwndCanvas, nullptr, FALSE);
    return true;
}

struct LibSeriesPick {
    Str key;
    Str name;
};

static LibSeriesPick gSeriesPicks[kMaxSeries];
static int gnSeriesPicks = 0;

static void FreeSeriesPicks() {
    for (int i = 0; i < gnSeriesPicks; i++) {
        str::Free(gSeriesPicks[i].key);
        str::Free(gSeriesPicks[i].name);
    }
    gnSeriesPicks = 0;
}

static void AddSeriesMenu(HMENU popup, LibBook* book) {
    FreeSeriesPicks();
    if (!book) {
        return;
    }
    HMENU move = CreatePopupMenu();
    for (int i = 0; i < gModel.nSeries && gnSeriesPicks < kMaxSeries; i++) {
        LibSeries& row = gModel.series[i];
        if (len(row.name) == 0 || !CanHostBooks(row)) {
            continue;
        }
        str::Builder label;
        for (int step = 0; step < row.depth; step++) {
            label.Append("    ");
        }
        label.Append(fmt("%s  (%d)", row.name, row.books));
        uint flags = MF_STRING;
        if (str::Eq(book->seriesKey, row.key)) {
            flags |= MF_CHECKED;
        }
        AppendMenuW(move, flags, kMenuSeriesFirst + gnSeriesPicks, ToWStrTemp(ToStr(label)).s);
        gSeriesPicks[gnSeriesPicks].key = str::Dup(row.key);
        gSeriesPicks[gnSeriesPicks].name = str::Dup(row.name);
        gnSeriesPicks++;
    }
    if (gnSeriesPicks == 0) {
        DestroyMenu(move);
        return;
    }
    if (str::EqI(book->seriesSource, StrL("user"))) {
        AppendMenuW(move, MF_SEPARATOR, 0, nullptr);
        AppendMenuW(move, MF_STRING, kMenuRestoreAutoSeries, ToWStrTemp(StrL("Restore automatic series")).s);
    }
    AppendMenuW(popup, MF_POPUP, (UINT_PTR)move, ToWStrTemp(StrL("Move to series")).s);
}

bool LibraryOnRightClick(MainWindow* win, int x, int y) {
    if (!LibraryHomeEnabled()) {
        return false;
    }
    if (gDeskOpen && !gDetailOpen) {
        return DeskRightClick(win, x, y);
    }
    TempStr path = BookPathAtTemp(win, x, y);
    TempStr rowKey = RowKeyAtTemp(win, x, y);
    if (len(path) == 0 && len(rowKey) == 0) {
        return false;
    }
    LibBook* book = len(path) > 0 ? BookByPath(path) : nullptr;
    HMENU popup = CreatePopupMenu();
    if (len(path) > 0) {
        AppendMenuW(popup, MF_STRING, kMenuOpenResume, ToWStrTemp(_TRA("Open book from last page read")).s);
        AppendMenuW(popup, MF_STRING, kMenuOpenStart, ToWStrTemp(_TRA("Open book from beginning")).s);
        AppendMenuW(popup, MF_STRING, kMenuPlayAudiobook, ToWStrTemp(_TRA("Play as Audio Book")).s);
        AppendMenuW(popup, MF_SEPARATOR, 0, nullptr);
        if (CanLeaveSeries(book)) {
            Str out = Str(fmt("Take out of %s", book->series));
            AppendMenuW(popup, MF_STRING, kMenuLeaveSeries, ToWStrTemp(out).s);
        }
        for (int i = 0; book && i < book->nOutOf; i++) {
            if (len(book->outOf[i].key) == 0) {
                continue;
            }
            Str back = Str(fmt("Put back into %s", book->outOf[i].name));
            AppendMenuW(popup, MF_STRING, kMenuRejoinFirst + i, ToWStrTemp(back).s);
        }
        AddSeriesMenu(popup, book);
        AppendMenuW(popup, MF_STRING, kMenuEditBookMetadata, ToWStrTemp(_TRA("Edit metadata...")).s);
        AppendMenuW(popup, MF_STRING, kMenuRemoveFromLibrary, ToWStrTemp(StrL("Remove from library")).s);
        AppendMenuW(popup, MF_STRING, kMenuIgnoreFile, ToWStrTemp(StrL("Ignore file")).s);
    }
    if (len(rowKey) > 0) {
        if (len(path) > 0) {
            AppendMenuW(popup, MF_SEPARATOR, 0, nullptr);
        }
        AppendMenuW(popup, MF_STRING, kMenuRenameSeries, ToWStrTemp(_TRA("Rename series...")).s);
        AddPartitionMenu(popup, rowKey);
    }
    POINT pt = {x, y};
    MapWindowPoints(win->hwndCanvas, HWND_DESKTOP, &pt, 1);
    int cmd = TrackPopupMenu(popup, TPM_RETURNCMD | TPM_RIGHTBUTTON, pt.x, pt.y, 0, win->hwndFrame, nullptr);
    DestroyMenu(popup);

    if (cmd == kMenuOpenResume) {
        LibraryOpenBook(win, path, 0);
    } else if (cmd == kMenuOpenStart) {
        LibraryOpenBook(win, path, 1);
    } else if (cmd == kMenuPlayAudiobook) {
        LibraryOpenBook(win, path, 0, true);
    } else if (cmd == kMenuRemoveFromLibrary) {
        MoveOneFile(path, kKindIgnored);
    } else if (cmd == kMenuIgnoreFile) {
        MoveOneFile(path, kKindIgnored);
    } else if (cmd == kMenuLeaveSeries && CanLeaveSeries(book)) {
        TempStr body = fmt("{\"books\":[%s],\"row\":%s,\"name\":%s}", JsonStrTemp(book->id),
                           JsonStrTemp(book->seriesKey), JsonStrTemp(book->series));
        PostPartition("/series/pull", Str(body));
    } else if (book && cmd >= kMenuRejoinFirst && cmd < kMenuRejoinFirst + book->nOutOf) {
        Str was = book->outOf[cmd - kMenuRejoinFirst].key;
        TempStr body = fmt("{\"books\":[%s],\"row\":%s}", JsonStrTemp(book->id), JsonStrTemp(was));
        PostPartition("/series/restore", Str(body));
    } else if (cmd == kMenuEditBookMetadata && book) {
        RunEditBookMetadata(win, book);
    } else if (cmd == kMenuRestoreAutoSeries && book) {
        PostUserFieldEdit(book, InlineField::Series, Str(), true);
    } else if (book && cmd >= kMenuSeriesFirst && cmd < kMenuSeriesFirst + gnSeriesPicks) {
        Str pick = gSeriesPicks[cmd - kMenuSeriesFirst].name;
        Str pickKey = gSeriesPicks[cmd - kMenuSeriesFirst].key;
        if (!str::Eq(book->seriesKey, pickKey) || !str::Eq(book->series, pick) ||
            !str::EqI(book->seriesSource, StrL("user"))) {
            PostUserFieldEdit(book, InlineField::Series, pick, false, pickKey);
            InvalidateRect(win->hwndCanvas, nullptr, FALSE);
        }
    } else if (cmd == kMenuRestoreAutoParent && len(rowKey) > 0) {
        TempStr body = fmt("{\"row\":%s,\"parent\":\"\"}", JsonStrTemp(rowKey));
        PostPartition("/series/parent", Str(body));
    } else if (len(rowKey) > 0 && cmd >= kMenuRowParentFirst && cmd < kMenuRowParentFirst + gnRowParentPicks) {
        Str dest = gRowParentPicks[cmd - kMenuRowParentFirst].key;
        TempStr body = fmt("{\"row\":%s,\"parent\":%s}", JsonStrTemp(rowKey), JsonStrTemp(dest));
        PostPartition("/series/parent", Str(body));
    } else if (cmd == kMenuRenameSeries && len(rowKey) > 0) {
        RunRenameSeries(win, rowKey);
    } else if (cmd > 0 && len(rowKey) > 0) {
        RunPartitionCommand(win, cmd, rowKey);
    }
    FreeSeriesPicks();
    FreeRowParentPicks();
    return true;
}
