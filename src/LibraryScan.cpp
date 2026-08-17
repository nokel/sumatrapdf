/* Copyright 2026 the SumatraPDF project authors (see AUTHORS file).
   License: Simplified BSD (see COPYING.BSD) */

#include "base/Base.h"
#include "base/ScopedWin.h"
#include "base/File.h"
#include "base/Win.h"
#include "base/GuessFileType.h"

#include "wingui/UIModels.h"

#include "Settings.h"
#include "GlobalPrefs.h"
#include "DocProperties.h"
#include "DocController.h"
#include "EngineBase.h"
#include "EngineAll.h"
#include "LibraryScan.h"

constexpr int kScanDepth = 5;
constexpr int kScanBudget = 40000;
constexpr int kPageSamples = 6;
constexpr float kArtShare = 0.5f;
constexpr int kSampleChars = 3000;
constexpr int kProgressEvery = 25;
constexpr int kFrontPages = 6;
constexpr int kFrontChars = 2400;
constexpr int kMarkerCap = 3;
constexpr int kBookScore = 2;

static const WCHAR* kBookExts[] = {L".pdf", L".epub", L".mobi", L".azw3", L".fb2", L".cbz", L".xps"};

static const WCHAR* kEbookContainerExts[] = {L".epub", L".mobi", L".azw3", L".fb2", L".cbz"};

static const WCHAR* kSkipDirNames[] = {
    L"windows",     L"program files", L"program files (x86)",
    L"programdata", L"$recycle.bin",  L"system volume information",
    L"appdata",     L"node_modules",  L"__pycache__",
    L"recovery",    L"perflogs",      L"temp",
    L"tmp",         L"site-packages", L"venv",
    L"cache",       L"steamapps",
};

static const char* kLibraryDirNames[] = {
    "ebooks",     "ebook",     "books",     "book",       "library",     "calibre library",
    "audiobooks", "novels",    "manga",     "comics",     "reading",     "epub",
    "kindle",     "animorphs", "discworld", "hitchhiker", "hitchhikers", "rick and morty",
};

// folders that hold a project's own paperwork rather than someone's reading
static const char* kWorkDirNames[] = {
    "ext",        "tests",    "test",         "doc",    "docs",   "vendor",      "third_party",  "vcpkg",
    "samples",    "sample",   "node_modules", "src",    "build",  "buildtrees",  "download",     "downloads",
    "music",      "songs",    "tracks",       "lyrics", "liner",  "system",      "framework",    "frameworks",
    "kernel",     "drivers",  "driver",       "logs",   "log",    "tmp",         "temp",         "cache",
    "backup",     "backups",  "old",          "app",    "apps",   "application", "applications", "installer",
    "installers", "receipts", "invoices",     "bills",  "orders", "statements",
};

static const char* kBookWords[] = {
    "isbn",
    "library of congress",
    "first published",
    "first edition",
    "printed in the united",
    "cataloguing in publication",
    "publishing",
    "publishers",
    "chapter one",
    "chapter 1",
    "prologue",
    "epilogue",
    "to be continued",
    "illustrated by",
    "translated by",
    "cover art",
};

static const char* kDocWords[] = {
    "invoice",
    "amount due",
    "purchase order",
    "work order",
    "bill to",
    "ship to",
    "subtotal",
    "tax invoice",
    "order number",
    "tracking number",
    "return label",
    "shipping label",
    "curriculum vitae",
    "resume",
    "consent form",
    "date of birth",
    "policy number",
    "claim number",
    "account number",
    "statement period",
    "receipt",
    "datasheet",
    "data sheet",
    "absolute maximum",
    "electrical characteristics",
    "ordering information",
    "part number",
    "privacy policy",
    "user manual",
    "owner s manual",
    "service manual",
    "instruction manual",
    "operating instructions",
    "operating manual",
    "this manual",
    "installation guide",
    "quick start",
    "safety instructions",
    "precautions",
    "specification",
    "internet draft",
    "microsoft powerpoint",
    "this document was generated",
    "signature",
    "synopsis",
    "see also",
    "abstract",
    "et al",
    "yours sincerely",
    "booklet",
    "liner notes",
    "lyrics",
    "tracklist",
    "track list",
    "song list",
    "album",
    "deluxe edition",
    "remastered",
    "motherboard",
    "bios",
};

static bool SkipDirName(const WCHAR* name) {
    if (name[0] == L'.') {
        return true;
    }
    for (const WCHAR* s : kSkipDirNames) {
        if (_wcsicmp(name, s) == 0) {
            return true;
        }
    }
    return false;
}

static bool IsBookName(const WCHAR* name) {
    const WCHAR* dot = wcsrchr(name, L'.');
    if (!dot) {
        return false;
    }
    for (const WCHAR* e : kBookExts) {
        if (_wcsicmp(dot, e) == 0) {
            return true;
        }
    }
    return false;
}

static bool IsDotDir(const WCHAR* name) {
    return name[0] == L'.' && (name[1] == 0 || (name[1] == L'.' && name[2] == 0));
}

static double FileTimeToUnix(const FILETIME& ft) {
    ULARGE_INTEGER u;
    u.LowPart = ft.dwLowDateTime;
    u.HighPart = ft.dwHighDateTime;
    return ((double)u.QuadPart - 116444736000000000.0) / 10000000.0;
}

static i64 FileSizeOf(const WIN32_FIND_DATAW& fd) {
    ULARGE_INTEGER u;
    u.LowPart = fd.nFileSizeLow;
    u.HighPart = fd.nFileSizeHigh;
    return (i64)u.QuadPart;
}

static bool IsCloudPlaceholderAttrs(DWORD attrs) {
    DWORD bits = FILE_ATTRIBUTE_OFFLINE | FILE_ATTRIBUTE_RECALL_ON_OPEN | FILE_ATTRIBUTE_RECALL_ON_DATA_ACCESS;
    return (attrs & bits) != 0;
}

// one growable wide path for a whole walk: no per-directory allocation and no
// MAX_PATH limit (roots on a drive letter get the \\?\ prefix)
struct PathW {
    Vec<WCHAR> chars;
    int prefix = 0;

    void SetRoot(WStr root) {
        chars.SetSize(0);
        prefix = 0;
        bool driveAbs = root.len >= 3 && root.s[1] == L':' && (root.s[2] == L'\\' || root.s[2] == L'/');
        if (driveAbs) {
            chars.Append(L"\\\\?\\", 4);
            prefix = 4;
        }
        int n = root.len;
        while (n > 3 && (root.s[n - 1] == L'\\' || root.s[n - 1] == L'/')) {
            n--;
        }
        chars.Append(root.s, n);
    }

    int Mark() const { return chars.len; }

    void Rewind(int mark) { chars.SetSize(mark); }

    void PushName(const WCHAR* name, int nameLen) {
        if (chars.len > 0 && chars[chars.len - 1] != L'\\') {
            chars.Append(L'\\');
        }
        chars.Append(name, nameLen);
    }

    WCHAR* W() const { return chars.LendData(); }

    WStr Visible() const { return WStr(chars.LendData() + prefix, chars.len - prefix); }
};

static Str PathUtf8(const PathW& p) {
    return str::Dup(ToUtf8Temp(p.Visible()));
}

struct FindPattern {
    PathW* p;
    int mark;

    explicit FindPattern(PathW* path) {
        p = path;
        mark = p->Mark();
        p->PushName(L"*", 1);
    }
    ~FindPattern() { p->Rewind(mark); }
};

static Str RealPath(Str path) {
    WStr wide = ToWStrTemp(path);
    HANDLE h = CreateFileW(wide.s, 0, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr, OPEN_EXISTING,
                           FILE_FLAG_BACKUP_SEMANTICS, nullptr);
    if (h == INVALID_HANDLE_VALUE) {
        return str::Dup(path);
    }
    WCHAR buf[1024];
    DWORD n = GetFinalPathNameByHandleW(h, buf, dimof(buf) - 1, FILE_NAME_NORMALIZED | VOLUME_NAME_DOS);
    CloseHandle(h);
    if (n == 0 || n >= dimof(buf) - 1) {
        return str::Dup(path);
    }
    buf[n] = 0;
    WCHAR* out = buf;
    int cch = (int)n;
    if (cch > 6 && buf[0] == L'\\' && buf[1] == L'\\' && buf[2] == L'?' && buf[3] == L'\\' && buf[5] == L':') {
        out = buf + 4;
        cch -= 4;
    }
    while (cch > 3 && out[cch - 1] == L'\\') {
        out[--cch] = 0;
    }
    return str::Dup(ToUtf8Temp(WStr(out, cch)));
}

static bool IsDirPath(Str path) {
    DWORD attrs = GetFileAttributesW(ToWStrTemp(path).s);
    if (attrs == INVALID_FILE_ATTRIBUTES) {
        return false;
    }
    return (attrs & FILE_ATTRIBUTE_DIRECTORY) != 0;
}

static bool SameOrUnder(Str path, Str parent) {
    if (path.len < parent.len) {
        return false;
    }
    if (!str::StartsWithI(path, parent)) {
        return false;
    }
    if (path.len == parent.len) {
        return true;
    }
    if (parent.len > 0 && path::IsSep(parent.s[parent.len - 1])) {
        return true;
    }
    return path::IsSep(path.s[parent.len]);
}

static void TakeRoot(StrVec& out, Str path) {
    if (path.len == 0) {
        return;
    }
    Str real = RealPath(path);
    if (IsDirPath(real) && out.FindI(real) < 0) {
        out.Append(real);
    }
    str::Free(real);
}

static StrVec Outermost(const StrVec& roots) {
    StrVec out;
    for (int i = 0; i < roots.size; i++) {
        Str p = roots.At(i);
        bool nested = false;
        for (int j = 0; j < roots.size; j++) {
            if (i == j) {
                continue;
            }
            Str q = roots.At(j);
            if (q.len < p.len && SameOrUnder(p, q)) {
                nested = true;
                break;
            }
        }
        if (!nested) {
            out.Append(p);
        }
    }
    return out;
}

static bool FolderHasBooks(PathW& p) {
    FindPattern pat(&p);
    WIN32_FIND_DATAW fd;
    HANDLE h = FindFirstFileW(p.W(), &fd);
    if (h == INVALID_HANDLE_VALUE) {
        return false;
    }
    bool found = false;
    do {
        if ((fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) != 0) {
            continue;
        }
        if (IsBookName(fd.cFileName)) {
            found = true;
            break;
        }
    } while (FindNextFileW(h, &fd));
    FindClose(h);
    return found;
}

static void ListSubdirs(PathW& p, Vec<WCHAR>& names, Vec<int>& starts) {
    FindPattern pat(&p);
    WIN32_FIND_DATAW fd;
    HANDLE h = FindFirstFileW(p.W(), &fd);
    if (h == INVALID_HANDLE_VALUE) {
        return;
    }
    do {
        if ((fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) == 0) {
            continue;
        }
        if (IsDotDir(fd.cFileName) || SkipDirName(fd.cFileName)) {
            continue;
        }
        if ((fd.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0) {
            continue;
        }
        starts.Append(names.len);
        int n = (int)wcslen(fd.cFileName);
        names.Append(fd.cFileName, n);
        names.Append(L'\0');
    } while (FindNextFileW(h, &fd));
    FindClose(h);
}

static void HuntLibraries(PathW& p, int level, int* budget, StrVec& out) {
    if (*budget <= 0) {
        return;
    }
    *budget = *budget - 1;
    if (FolderHasBooks(p)) {
        Str u = PathUtf8(p);
        TakeRoot(out, u);
        str::Free(u);
        return;
    }
    if (level >= kScanDepth) {
        return;
    }
    Vec<WCHAR> names;
    Vec<int> starts;
    ListSubdirs(p, names, starts);
    for (int i = 0; i < starts.len; i++) {
        if (*budget <= 0) {
            return;
        }
        const WCHAR* name = names.LendData() + starts[i];
        int mark = p.Mark();
        p.PushName(name, (int)wcslen(name));
        HuntLibraries(p, level + 1, budget, out);
        p.Rewind(mark);
    }
}

StrVec LibraryStartingRoots() {
    StrVec found;

    if (gGlobalPrefs) {
        Str configured = gGlobalPrefs->audiobook.libraryRoots;
        if (configured.len > 0) {
            StrVec parts;
            Split(&parts, configured, StrL(";"), true);
            for (Str one : parts) {
                TakeRoot(found, one);
            }
        }
    }

    StrVec bases;
    TempStr docs = GetSpecialFolderTemp(CSIDL_MYDOCUMENTS);
    TempStr desktop = GetSpecialFolderTemp(CSIDL_DESKTOPDIRECTORY);
    TempStr home = GetSpecialFolderTemp(CSIDL_PROFILE);
    if (docs.len > 0) {
        bases.Append(docs);
    }
    if (home.len > 0) {
        bases.Append(path::JoinTemp(home, StrL("Downloads")));
    }
    if (desktop.len > 0) {
        bases.Append(desktop);
    }
    if (home.len > 0) {
        bases.Append(home);
    }

    for (Str base : bases) {
        if (!IsDirPath(base)) {
            continue;
        }
        for (const char* name : kLibraryDirNames) {
            TakeRoot(found, path::JoinTemp(base, Str(name)));
        }
    }

    int budget = kScanBudget;
    for (Str base : bases) {
        if (!IsDirPath(base)) {
            continue;
        }
        PathW p;
        p.SetRoot(ToWStrTemp(base));
        HuntLibraries(p, 0, &budget, found);
    }
    return Outermost(found);
}

StrVec LibraryWholeDeviceRoots() {
    StrVec all = LibraryStartingRoots();
    DWORD mask = GetLogicalDrives();
    for (int i = 0; i < 26; i++) {
        if ((mask & (1u << i)) == 0) {
            continue;
        }
        WCHAR wroot[4] = {(WCHAR)(L'A' + i), L':', L'\\', 0};
        if (GetDriveTypeW(wroot) != DRIVE_FIXED) {
            continue;
        }
        char root[4] = {(char)('A' + i), ':', '\\', 0};
        Str p(root, 3);
        if (all.FindI(p) < 0) {
            all.Append(p);
        }
    }
    return Outermost(all);
}

struct FoundBook {
    Str path;
    i64 size;
    double mtime;
    bool placeholder;
};

struct Walk {
    Vec<FoundBook>* out;
    const volatile bool* cancel;
    LibraryScanNotifyCb cb;
    void* ctx;
    LibraryScanProgress* progress;
};

static void WalkDir(Walk& w, PathW& p) {
    if (w.cancel && *w.cancel) {
        return;
    }

    Vec<WCHAR> names;
    Vec<int> starts;

    {
        FindPattern pat(&p);
        WIN32_FIND_DATAW fd;
        HANDLE h = FindFirstFileW(p.W(), &fd);
        if (h == INVALID_HANDLE_VALUE) {
            return;
        }
        do {
            if (IsDotDir(fd.cFileName)) {
                continue;
            }
            if ((fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) != 0) {
                if (SkipDirName(fd.cFileName) || (fd.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0) {
                    continue;
                }
                starts.Append(names.len);
                int n = (int)wcslen(fd.cFileName);
                names.Append(fd.cFileName, n);
                names.Append(L'\0');
                continue;
            }
            if (!IsBookName(fd.cFileName)) {
                continue;
            }
            FoundBook b;
            p.Rewind(pat.mark);
            p.PushName(fd.cFileName, (int)wcslen(fd.cFileName));
            b.path = PathUtf8(p);
            p.Rewind(pat.mark);
            b.size = FileSizeOf(fd);
            b.mtime = FileTimeToUnix(fd.ftLastWriteTime);
            b.placeholder = IsCloudPlaceholderAttrs(fd.dwFileAttributes);
            w.out->Append(b);
            w.progress->found = w.out->len;
            if (w.cb && (w.progress->found % kProgressEvery) == 0) {
                Str where = PathUtf8(p);
                w.progress->where = where;
                w.cb(*w.progress, w.ctx);
                w.progress->where = {};
                str::Free(where);
            }
        } while (FindNextFileW(h, &fd));
        FindClose(h);
    }

    for (int i = 0; i < starts.len; i++) {
        if (w.cancel && *w.cancel) {
            return;
        }
        const WCHAR* name = names.LendData() + starts[i];
        int mark = p.Mark();
        p.PushName(name, (int)wcslen(name));
        WalkDir(w, p);
        p.Rewind(mark);
    }
}

static u64 HashPathI(Str s) {
    u64 h = 1469598103934665603ull;
    for (int i = 0; i < s.len; i++) {
        char c = s.s[i];
        if (c >= 'A' && c <= 'Z') {
            c = (char)(c - 'A' + 'a');
        }
        if (c == '/') {
            c = '\\';
        }
        h ^= (u64)(u8)c;
        h *= 1099511628211ull;
    }
    return h;
}

struct KnownEntry {
    u64 hash;
    i64 size;
    double mtime;
};

static int CmpKnown(const void* a, const void* b) {
    u64 ha = ((const KnownEntry*)a)->hash;
    u64 hb = ((const KnownEntry*)b)->hash;
    if (ha < hb) {
        return -1;
    }
    return ha > hb ? 1 : 0;
}

static bool IsUnchanged(const Vec<KnownEntry>& sorted, Str path, i64 size, double mtime) {
    if (sorted.len == 0) {
        return false;
    }
    u64 want = HashPathI(path);
    int lo = 0;
    int hi = sorted.len - 1;
    while (lo <= hi) {
        int mid = lo + (hi - lo) / 2;
        u64 got = sorted[mid].hash;
        if (got == want) {
            double dt = sorted[mid].mtime - mtime;
            if (dt < 0) {
                dt = -dt;
            }
            return sorted[mid].size == size && dt < 1.0;
        }
        if (got < want) {
            lo = mid + 1;
        } else {
            hi = mid - 1;
        }
    }
    return false;
}

static int Utf8SafeLen(Str s, int want) {
    if (want >= s.len) {
        return s.len;
    }
    int n = want;
    while (n > 0 && ((u8)s.s[n] & 0xc0) == 0x80) {
        n--;
    }
    return n;
}

static int Utf8SeqLen(u8 lead) {
    if (lead < 0x80) {
        return 1;
    }
    if ((lead & 0xe0) == 0xc0) {
        return 2;
    }
    if ((lead & 0xf0) == 0xe0) {
        return 3;
    }
    if ((lead & 0xf8) == 0xf0) {
        return 4;
    }
    return 0;
}

static int Utf8SeqAt(Str text, int i) {
    int n = Utf8SeqLen((u8)text.s[i]);
    if (n == 0 || i + n > text.len) {
        return 0;
    }
    for (int k = 1; k < n; k++) {
        if (((u8)text.s[i + k] & 0xc0) != 0x80) {
            return 0;
        }
    }
    return n;
}

static void AppendSquashed(str::Builder& b, Str text, int budget) {
    bool pendingSpace = false;
    int wrote = 0;
    int i = 0;
    while (i < text.len && wrote < budget) {
        int n = Utf8SeqAt(text, i);
        if (n == 0) {
            i++;
            continue;
        }
        char c = text.s[i];
        if (n == 1 && (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\f' || c == '\v')) {
            pendingSpace = wrote > 0;
            i++;
            continue;
        }
        if (wrote + n > budget) {
            break;
        }
        if (pendingSpace) {
            b.AppendChar(' ');
            wrote++;
            pendingSpace = false;
            if (wrote + n > budget) {
                break;
            }
        }
        b.Append(Str(text.s + i, n));
        wrote += n;
        i += n;
    }
}

static u32 Utf8CodePointAt(Str text, int i, int* seqLen) {
    int n = Utf8SeqAt(text, i);
    *seqLen = n;
    if (n == 0) {
        return 0;
    }
    u8 lead = (u8)text.s[i];
    u32 cp;
    if (n == 1) {
        return lead;
    }
    if (n == 2) {
        cp = lead & 0x1fu;
    } else if (n == 3) {
        cp = lead & 0x0fu;
    } else {
        cp = lead & 0x07u;
    }
    for (int k = 1; k < n; k++) {
        cp = (cp << 6) | ((u8)text.s[i + k] & 0x3fu);
    }
    return cp;
}

// what a typesetter joined, a word match has to see apart again: "speciﬁcation"
// is one glyph in a lot of PDFs and never matches "specification" without this
static const char* LigatureLetters(u32 cp) {
    switch (cp) {
        case 0xfb00:
            return "ff";
        case 0xfb01:
            return "fi";
        case 0xfb02:
            return "fl";
        case 0xfb03:
            return "ffi";
        case 0xfb04:
            return "ffl";
        case 0xfb05:
        case 0xfb06:
            return "st";
        case 0x0152:
        case 0x0153:
            return "oe";
        case 0x00c6:
        case 0x00e6:
            return "ae";
        default:
            return nullptr;
    }
}

// lowercase words separated by single spaces, with a space at each end, so that
// " isbn " only matches the whole word
static Str NormalizedWords(Str text) {
    str::Builder b(text.len + 2);
    b.AppendChar(' ');
    bool space = true;
    int i = 0;
    while (i < text.len) {
        int n = 0;
        u32 cp = Utf8CodePointAt(text, i, &n);
        if (n == 0) {
            i++;
            continue;
        }
        i += n;
        const char* letters = LigatureLetters(cp);
        if (!letters && cp >= 0x80) {
            cp = ' ';
        }
        char one[2] = {(char)cp, 0};
        const char* run = letters ? letters : one;
        for (const char* p = run; *p; p++) {
            char c = *p;
            if (c >= 'A' && c <= 'Z') {
                c = (char)(c - 'A' + 'a');
            }
            bool keep = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (keep) {
                b.AppendChar(c);
                space = false;
            } else if (!space) {
                b.AppendChar(' ');
                space = true;
            }
        }
    }
    if (!space) {
        b.AppendChar(' ');
    }
    return b.TakeStr();
}

static Str PaddedWord(char* buf, int cap, const char* word) {
    int n = (int)strlen(word);
    if (n + 3 > cap) {
        return {};
    }
    buf[0] = ' ';
    memcpy(buf + 1, word, (size_t)n);
    buf[n + 1] = ' ';
    buf[n + 2] = 0;
    return Str(buf, n + 2);
}

static bool HasWord(Str words, const char* word) {
    char buf[64];
    Str padded = PaddedWord(buf, dimof(buf), word);
    return padded.len > 0 && str::Contains(words, padded);
}

static int CountWord(Str words, const char* word) {
    char buf[64];
    Str padded = PaddedWord(buf, dimof(buf), word);
    if (padded.len == 0) {
        return 0;
    }
    int found = 0;
    Str rest = words;
    for (;;) {
        int at = str::IndexOf(rest, padded);
        if (at < 0) {
            return found;
        }
        found++;
        int step = at + padded.len - 1;
        rest = Str(rest.s + step, rest.len - step);
    }
}

static int MarkerHits(Str words, const char** markers, int nMarkers, int cap) {
    int hits = 0;
    for (int i = 0; i < nMarkers && hits < cap; i++) {
        if (HasWord(words, markers[i])) {
            hits++;
        }
    }
    return hits;
}

// prose that tells a story: someone said something, people are he and she, and
// speech sits in quotation marks. Paperwork does none of it.
static int NarrativeMarks(Str sampleWords, Str sampleRaw) {
    int marks = 0;
    if (CountWord(sampleWords, "said") >= 1) {
        marks++;
    }
    if (CountWord(sampleWords, "he") + CountWord(sampleWords, "she") >= 3) {
        marks++;
    }
    int quotes = 0;
    int i = 0;
    while (i < sampleRaw.len) {
        int n = 0;
        u32 cp = Utf8CodePointAt(sampleRaw, i, &n);
        if (n == 0) {
            i++;
            continue;
        }
        i += n;
        if (cp == '"' || cp == 0x201c || cp == 0x201d || cp == 0x00ab || cp == 0x00bb || cp == 0x300c || cp == 0x300d) {
            quotes++;
        }
    }
    if (quotes >= 6) {
        marks++;
    }
    return marks;
}

static bool PathHasDirName(Str path, const char** names, int nNames) {
    int start = 0;
    int lastSep = -1;
    for (int i = 0; i < path.len; i++) {
        if (path.s[i] == '\\' || path.s[i] == '/') {
            lastSep = i;
        }
    }
    if (lastSep < 0) {
        return false;
    }
    for (int i = 0; i <= lastSep; i++) {
        char c = path.s[i];
        if (c != '\\' && c != '/') {
            continue;
        }
        Str seg(path.s + start, i - start);
        start = i + 1;
        if (seg.len == 0) {
            continue;
        }
        for (int k = 0; k < nNames; k++) {
            if (str::EqI(seg, Str(names[k]))) {
                return true;
            }
        }
    }
    return false;
}

static bool IsEbookContainer(Str path) {
    WStr wide = ToWStrTemp(path);
    const WCHAR* dot = wcsrchr(wide.s, L'.');
    if (!dot) {
        return false;
    }
    for (const WCHAR* e : kEbookContainerExts) {
        if (_wcsicmp(dot, e) == 0) {
            return true;
        }
    }
    return false;
}

struct DocLook {
    int pages = 0;
    int ink = 0;
    float art = -1.0f;
    int toc = 0;
    bool book = true;
    Str title;
    Str author;
    Str sample;
    Str front;
};

// A book is something you read cover to cover; everything else the scan turns up
// is paperwork -- a manual, an invoice, a form, a spec -- and belongs on the
// desk, not the shelf. No single signal decides it, so they vote.
static bool LooksLikeBook(Str path, const DocLook& look) {
    int score = 0;

    bool container = IsEbookContainer(path);
    bool shelved = PathHasDirName(path, kLibraryDirNames, (int)dimof(kLibraryDirNames));
    if (container) {
        score += 3;
    }

    int pages = look.pages;
    if (pages <= 2) {
        score -= 8;
    } else if (pages <= 6) {
        score -= 5;
    } else if (pages <= 12) {
        score -= 3;
    } else if (pages <= 20) {
        score -= 1;
    } else if (pages <= 40) {
        score += 1;
    } else if (pages <= 80) {
        score += 2;
    } else if (pages <= 150) {
        score += 3;
    } else {
        score += 4;
    }

    if (look.art >= kArtShare && pages >= 12 && (container || shelved)) {
        score += 4;
    }

    if (shelved) {
        score += 3;
    }
    if (PathHasDirName(path, kWorkDirNames, (int)dimof(kWorkDirNames))) {
        score -= 3;
    }

    Str name = path;
    int lastSep = str::LastIndexOfChar(path, '\\');
    if (lastSep >= 0) {
        name = Str(path.s + lastSep + 1, path.len - lastSep - 1);
    }
    str::Builder all(look.front.len + look.sample.len + look.title.len + look.author.len + name.len + 8);
    all.Append(look.front);
    all.AppendChar(' ');
    all.Append(look.sample);
    all.AppendChar(' ');
    all.Append(look.title);
    all.AppendChar(' ');
    all.Append(look.author);
    all.AppendChar(' ');
    all.Append(name);
    Str joined = all.TakeStr();
    Str words = NormalizedWords(joined);
    Str sampleWords = NormalizedWords(look.sample);

    score += 2 * MarkerHits(words, kBookWords, (int)dimof(kBookWords), kMarkerCap);
    score -= 3 * MarkerHits(words, kDocWords, (int)dimof(kDocWords), kMarkerCap);
    score += 2 * NarrativeMarks(sampleWords, look.sample);

    if (look.toc >= 5) {
        score += 1;
    }

    str::Free(joined);
    str::Free(words);
    str::Free(sampleWords);
    return score >= kBookScore;
}

static void ReadPageLook(EngineBase* engine, DocLook& look) {
    int nPages = engine->PageCount();
    if (nPages <= 0) {
        return;
    }
    int picks[kPageSamples];
    int nPicks = 0;
    double step = (double)(nPages > 1 ? nPages - 1 : 1) / (double)(kPageSamples - 1);
    for (int i = 0; i < kPageSamples; i++) {
        int pg = (int)(i * step + 0.5);
        if (pg > nPages - 1) {
            pg = nPages - 1;
        }
        bool dup = false;
        for (int j = 0; j < nPicks; j++) {
            if (picks[j] == pg) {
                dup = true;
                break;
            }
        }
        if (!dup) {
            picks[nPicks++] = pg;
        }
    }

    int inks[kPageSamples];
    int nInks = 0;
    int artPages = 0;
    int perPage = kSampleChars / nPicks;
    str::Builder sample;

    for (int i = 0; i < nPicks; i++) {
        int pageNo = picks[i] + 1;
        PageText pt = engine->ExtractPageText(pageNo);
        inks[nInks++] = pt.len;
        AppendSquashed(sample, Str(pt.text.s, pt.len), perPage);
        sample.AppendChar(' ');
        FreePageText(&pt);

        RectF box = engine->PageMediabox(pageNo);
        float area = box.dx * box.dy;
        if (area < 0) {
            area = -area;
        }
        if (area <= 0) {
            area = 1.0f;
        }
        float biggest = 0;
        engine->BenchLoadPage(pageNo);
        Vec<IPageElement*> els = engine->GetElements(pageNo);
        for (IPageElement* el : els) {
            if (!el->Is(kindPageElementImage)) {
                continue;
            }
            RectF r = el->GetRect();
            float covered = r.dx * r.dy;
            if (covered < 0) {
                covered = -covered;
            }
            float share = covered / area;
            if (share > biggest) {
                biggest = share;
            }
        }
        if (biggest >= kArtShare) {
            artPages++;
        }
    }

    for (int i = 0; i < nInks; i++) {
        for (int j = i + 1; j < nInks; j++) {
            if (inks[j] < inks[i]) {
                int t = inks[i];
                inks[i] = inks[j];
                inks[j] = t;
            }
        }
    }
    look.ink = nInks > 0 ? inks[nInks / 2] : 0;
    look.art = nInks > 0 ? (float)artPages / (float)nInks : -1.0f;

    Str full = ToStr(sample);
    int cut = Utf8SafeLen(full, kSampleChars);
    while (cut > 0 && full.s[cut - 1] == ' ') {
        cut--;
    }
    look.sample = str::Dup(Str(full.s, cut));

    // the title page, the copyright page and whatever follows them: where a book
    // says who published it and paperwork says what form it is
    str::Builder front;
    int nFront = nPages < kFrontPages ? nPages : kFrontPages;
    int frontPerPage = kFrontChars / kFrontPages;
    for (int i = 0; i < nFront; i++) {
        PageText pt = engine->ExtractPageText(i + 1);
        AppendSquashed(front, Str(pt.text.s, pt.len), frontPerPage);
        front.AppendChar(' ');
        FreePageText(&pt);
    }
    look.front = front.TakeStr();
}

static int CountTocItems(TocItem* item, int depth) {
    int n = 0;
    while (item && n < 1000) {
        n++;
        if (depth < 4) {
            n += CountTocItems(item->child, depth + 1);
        }
        item = item->next;
    }
    return n;
}

static void ReadDocLook(Str path, DocLook& look) {
    logf("library scan: reading '%s'\n", path);
    EngineBase* engine = CreateEngineFromFile(path, nullptr, false);
    if (!engine) {
        return;
    }
    look.pages = engine->PageCount();
    TempStr title = engine->GetPropertyTemp(DocProp::Title);
    TempStr author = engine->GetPropertyTemp(DocProp::Author);
    if (title.len > 0) {
        look.title = str::Dup(title);
    }
    if (author.len > 0) {
        look.author = str::Dup(author);
    }
    TocTree* toc = engine->GetToc();
    if (toc) {
        look.toc = CountTocItems(toc->root, 0);
    }
    ReadPageLook(engine, look);
    SafeEngineRelease(&engine);
    look.book = LooksLikeBook(path, look);
}

static void JsonStr(str::Builder& b, Str s) {
    b.AppendChar('"');
    for (int i = 0; i < s.len; i++) {
        char c = s.s[i];
        if ((u8)c >= 0x80) {
            int n = Utf8SeqAt(s, i);
            if (n == 0) {
                continue;
            }
            b.Append(Str(s.s + i, n));
            i += n - 1;
            continue;
        }
        if (c == '"') {
            b.Append(StrL("\\\""));
        } else if (c == '\\') {
            b.Append(StrL("\\\\"));
        } else if (c == '\n') {
            b.Append(StrL("\\n"));
        } else if (c == '\r') {
            b.Append(StrL("\\r"));
        } else if (c == '\t') {
            b.Append(StrL("\\t"));
        } else if ((u8)c < 0x20) {
            char tmp[8];
            _snprintf_s(tmp, dimof(tmp), _TRUNCATE, "\\u%04x", (int)(u8)c);
            b.Append(Str(tmp));
        } else {
            b.AppendChar(c);
        }
    }
    b.AppendChar('"');
}

static void JsonNum(str::Builder& b, double v, int decimals) {
    char tmp[64];
    _snprintf_s(tmp, dimof(tmp), _TRUNCATE, "%.*f", decimals, v);
    b.Append(Str(tmp));
}

Str LibraryScanToJson(const StrVec& roots, const Vec<LibraryKnownFile>& known, bool wholeDevice, LibraryScanNotifyCb cb,
                      void* ctx, const volatile bool* cancel) {
    Vec<KnownEntry> sorted;
    for (const LibraryKnownFile& k : known) {
        KnownEntry e;
        e.hash = HashPathI(k.path);
        e.size = k.size;
        e.mtime = k.mtime;
        sorted.Append(e);
    }
    if (sorted.len > 1) {
        qsort(sorted.LendData(), (size_t)sorted.len, sizeof(KnownEntry), CmpKnown);
    }

    Vec<FoundBook> files;
    LibraryScanProgress progress;

    Walk w;
    w.out = &files;
    w.cancel = cancel;
    w.cb = cb;
    w.ctx = ctx;
    w.progress = &progress;

    for (Str root : roots) {
        if (cancel && *cancel) {
            break;
        }
        PathW p;
        p.SetRoot(ToWStrTemp(root));
        WalkDir(w, p);
    }

    progress.reading = true;
    progress.total = files.len;
    if (cb) {
        cb(progress, ctx);
    }

    str::Builder b(1 << 16);
    b.Append(StrL("{\"scope\":"));
    JsonNum(b, wholeDevice ? kLibraryScanScope : 0, 0);
    b.Append(StrL(",\"roots\":["));
    for (int i = 0; i < roots.size; i++) {
        if (i > 0) {
            b.AppendChar(',');
        }
        JsonStr(b, roots.At(i));
    }
    b.Append(StrL("],\"files\":["));

    bool first = true;
    for (int i = 0; i < files.len; i++) {
        if (cancel && *cancel) {
            break;
        }
        FoundBook& f = files[i];
        if (!first) {
            b.AppendChar(',');
        }
        first = false;
        b.Append(StrL("{\"path\":"));
        JsonStr(b, f.path);
        b.Append(StrL(",\"size\":"));
        JsonNum(b, (double)f.size, 0);
        b.Append(StrL(",\"mtime\":"));
        JsonNum(b, f.mtime, 3);
        if (f.placeholder) {
            b.Append(StrL(",\"placeholder\":true"));
        }

        bool reuse = f.placeholder || IsUnchanged(sorted, f.path, f.size, f.mtime);
        if (!reuse) {
            DocLook look;
            ReadDocLook(f.path, look);
            b.Append(StrL(",\"pages\":"));
            JsonNum(b, look.pages, 0);
            b.Append(StrL(",\"ink\":"));
            JsonNum(b, look.ink, 0);
            if (look.art >= 0) {
                b.Append(StrL(",\"art\":"));
                JsonNum(b, look.art, 2);
            }
            if (look.title.len > 0) {
                b.Append(StrL(",\"title\":"));
                JsonStr(b, look.title);
            }
            if (look.author.len > 0) {
                b.Append(StrL(",\"author\":"));
                JsonStr(b, look.author);
            }
            b.Append(StrL(",\"toc\":"));
            JsonNum(b, look.toc, 0);
            b.Append(StrL(",\"kind\":"));
            JsonStr(b, look.book ? StrL("book") : StrL("document"));
            b.Append(StrL(",\"sample\":"));
            JsonStr(b, look.sample);
            str::Free(look.title);
            str::Free(look.author);
            str::Free(look.sample);
            str::Free(look.front);
        }
        b.AppendChar('}');

        progress.done = i + 1;
        if (cb && ((i + 1) % kProgressEvery == 0 || i + 1 == files.len)) {
            progress.where = f.path;
            cb(progress, ctx);
            progress.where = {};
        }
    }
    b.Append(StrL("]}"));

    for (FoundBook& f : files) {
        str::Free(f.path);
    }
    return b.TakeStr();
}
