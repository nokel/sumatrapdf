/* Copyright 2026 the SumatraPDF project authors (see AUTHORS file).
   License: Simplified BSD (see COPYING.BSD) */

#include "base/Base.h"
#include "base/Crypto.h"
#include "base/Dict.h"
#include "base/File.h"

extern "C" {
#include <mupdf/pdf.h>
}

#include "BookBlob.h"
#include "PdfSidecar.h"
#include "BookFingerprint.h"

static const int kDigitRanges[] = {
    0x0030,  0x0039,  0x0660,  0x0669,  0x06F0,  0x06F9,  0x07C0,  0x07C9,  0x0966,  0x096F,  0x09E6,  0x09EF,  0x0A66,
    0x0A6F,  0x0AE6,  0x0AEF,  0x0B66,  0x0B6F,  0x0BE6,  0x0BEF,  0x0C66,  0x0C6F,  0x0CE6,  0x0CEF,  0x0D66,  0x0D6F,
    0x0DE6,  0x0DEF,  0x0E50,  0x0E59,  0x0ED0,  0x0ED9,  0x0F20,  0x0F29,  0x1040,  0x1049,  0x1090,  0x1099,  0x17E0,
    0x17E9,  0x1810,  0x1819,  0x1946,  0x194F,  0x19D0,  0x19D9,  0x1A80,  0x1A89,  0x1A90,  0x1A99,  0x1B50,  0x1B59,
    0x1BB0,  0x1BB9,  0x1C40,  0x1C49,  0x1C50,  0x1C59,  0xA620,  0xA629,  0xA8D0,  0xA8D9,  0xA900,  0xA909,  0xA9D0,
    0xA9D9,  0xA9F0,  0xA9F9,  0xAA50,  0xAA59,  0xABF0,  0xABF9,  0xFF10,  0xFF19,  0x104A0, 0x104A9, 0x10D30, 0x10D39,
    0x11066, 0x1106F, 0x110F0, 0x110F9, 0x11136, 0x1113F, 0x111D0, 0x111D9, 0x112F0, 0x112F9, 0x11450, 0x11459, 0x114D0,
    0x114D9, 0x11650, 0x11659, 0x116C0, 0x116C9, 0x11730, 0x11739, 0x118E0, 0x118E9, 0x11950, 0x11959, 0x11C50, 0x11C59,
    0x11D50, 0x11D59, 0x11DA0, 0x11DA9, 0x11F50, 0x11F59, 0x16A60, 0x16A69, 0x16AC0, 0x16AC9, 0x16B50, 0x16B59, 0x1D7CE,
    0x1D7FF, 0x1E140, 0x1E149, 0x1E2F0, 0x1E2F9, 0x1E4F0, 0x1E4F9, 0x1E950, 0x1E959, 0x1FBF0, 0x1FBF9,
};

typedef int(WINAPI* Sig_NormalizeString)(int, const WCHAR*, int, WCHAR*, int);
static Sig_NormalizeString gNormalizeString = nullptr;
static bool gNormalizeLoaded = false;

static Sig_NormalizeString GetNormalizeString() {
    if (gNormalizeLoaded) {
        return gNormalizeString;
    }
    gNormalizeLoaded = true;
    HMODULE h = GetModuleHandleW(L"kernel32.dll");
    if (h) {
        gNormalizeString = (Sig_NormalizeString)GetProcAddress(h, "NormalizeString");
    }
    if (!gNormalizeString) {
        h = LoadLibraryW(L"Normaliz.dll");
        if (h) {
            gNormalizeString = (Sig_NormalizeString)GetProcAddress(h, "NormalizeString");
        }
    }
    return gNormalizeString;
}

static bool IsPySpace(int c) {
    switch (c) {
        case 0x20:
        case 0x85:
        case 0xA0:
        case 0x1680:
        case 0x2028:
        case 0x2029:
        case 0x202F:
        case 0x205F:
        case 0x3000:
            return true;
    }
    if (c >= 0x09 && c <= 0x0D) {
        return true;
    }
    if (c >= 0x1C && c <= 0x1F) {
        return true;
    }
    return c >= 0x2000 && c <= 0x200A;
}

static bool IsPyDigit(int c) {
    int lo = 0;
    int hi = (int)dimof(kDigitRanges) / 2 - 1;
    while (lo <= hi) {
        int mid = (lo + hi) / 2;
        if (c < kDigitRanges[mid * 2]) {
            hi = mid - 1;
        } else if (c > kDigitRanges[mid * 2 + 1]) {
            lo = mid + 1;
        } else {
            return true;
        }
    }
    return false;
}

static int NextRune(Str s, int at, int& cp) {
    const u8* p = (const u8*)s.s + at;
    int avail = s.len - at;
    u8 c = p[0];
    if (c < 0x80) {
        cp = c;
        return 1;
    }
    if ((c & 0xE0) == 0xC0 && avail >= 2) {
        cp = ((c & 0x1F) << 6) | (p[1] & 0x3F);
        return 2;
    }
    if ((c & 0xF0) == 0xE0 && avail >= 3) {
        cp = ((c & 0x0F) << 12) | ((p[1] & 0x3F) << 6) | (p[2] & 0x3F);
        return 3;
    }
    if ((c & 0xF8) == 0xF0 && avail >= 4) {
        cp = ((c & 0x07) << 18) | ((p[1] & 0x3F) << 12) | ((p[2] & 0x3F) << 6) | (p[3] & 0x3F);
        return 4;
    }
    cp = c;
    return 1;
}

static void AppendRune(str::Builder& b, int cp) {
    if (cp < 0) {
        return;
    }
    if (cp < 0x80) {
        b.AppendChar((char)cp);
        return;
    }
    if (cp < 0x800) {
        b.AppendChar((char)(0xC0 | (cp >> 6)));
        b.AppendChar((char)(0x80 | (cp & 0x3F)));
        return;
    }
    if (cp < 0x10000) {
        b.AppendChar((char)(0xE0 | (cp >> 12)));
        b.AppendChar((char)(0x80 | ((cp >> 6) & 0x3F)));
        b.AppendChar((char)(0x80 | (cp & 0x3F)));
        return;
    }
    if (cp > 0x10FFFF) {
        return;
    }
    b.AppendChar((char)(0xF0 | (cp >> 18)));
    b.AppendChar((char)(0x80 | ((cp >> 12) & 0x3F)));
    b.AppendChar((char)(0x80 | ((cp >> 6) & 0x3F)));
    b.AppendChar((char)(0x80 | (cp & 0x3F)));
}

static Str TrimPySpace(Str s) {
    int start = 0;
    while (start < s.len) {
        int cp = 0;
        int n = NextRune(s, start, cp);
        if (!IsPySpace(cp)) {
            break;
        }
        start += n;
    }
    int end = s.len;
    while (end > start) {
        int back = end - 1;
        while (back > start && ((u8)s.s[back] & 0xC0) == 0x80) {
            back--;
        }
        int cp = 0;
        NextRune(s, back, cp);
        if (!IsPySpace(cp)) {
            break;
        }
        end = back;
    }
    return Str(s.s + start, end - start);
}

static int CountRunes(Str s) {
    int n = 0;
    int at = 0;
    while (at < s.len) {
        int cp = 0;
        at += NextRune(s, at, cp);
        n++;
    }
    return n;
}

static void SqueezePySpace(Str s, str::Builder& out) {
    int at = 0;
    while (at < s.len) {
        int cp = 0;
        int n = NextRune(s, at, cp);
        if (IsPySpace(cp)) {
            out.AppendChar(' ');
            while (at < s.len) {
                int next = 0;
                int m = NextRune(s, at, next);
                if (!IsPySpace(next)) {
                    break;
                }
                at += m;
            }
            continue;
        }
        out.Append(Str(s.s + at, n));
        at += n;
    }
}

static void DropPySpaceWide(WStr s, wstr::Builder& out) {
    for (int i = 0; i < s.len; i++) {
        if (IsPySpace((int)s.s[i])) {
            continue;
        }
        out.AppendChar(s.s[i]);
    }
}

static void SubDigitRuns(Str s, str::Builder& out) {
    int at = 0;
    while (at < s.len) {
        int cp = 0;
        int n = NextRune(s, at, cp);
        if (IsPyDigit(cp)) {
            out.AppendChar('#');
            while (at < s.len) {
                int next = 0;
                int m = NextRune(s, at, next);
                if (!IsPyDigit(next)) {
                    break;
                }
                at += m;
            }
            continue;
        }
        out.Append(Str(s.s + at, n));
        at += n;
    }
}

static void LineKey(Str line, str::Builder& scratch, str::Builder& out) {
    scratch.Reset();
    out.Reset();
    SqueezePySpace(line, scratch);
    Str squeezed = TrimPySpace(Str(scratch.els, (int)scratch.len));
    SubDigitRuns(squeezed, out);
}

static bool KeyIsAllHashes(Str key) {
    for (int i = 0; i < key.len; i++) {
        if (key.s[i] != '#' && key.s[i] != ' ') {
            return false;
        }
    }
    return true;
}

static void SplitInkLines(Str text, Vec<Str>& out) {
    out.Reset();
    int start = 0;
    for (int i = 0; i <= text.len; i++) {
        if (i < text.len && text.s[i] != '\n') {
            continue;
        }
        Str line(text.s + start, i - start);
        if (TrimPySpace(line).len > 0) {
            out.Append(line);
        }
        start = i + 1;
    }
}

StrVec BookRunningLines(const StrVec& pages) {
    StrVec res;
    int nPages = pages.size;
    if (nPages < kBookRunningLineMinPages) {
        return res;
    }
    dict::MapStrToInt seen(1024);
    Vec<Str> lines;
    str::Builder scratch;
    str::Builder key;
    for (int i = 0; i < nPages; i++) {
        Str text = pages.At(i);
        SplitInkLines(text, lines);
        if (lines.len == 0) {
            continue;
        }
        Str edges[2] = {lines[0], lines[lines.len - 1]};
        for (Str line : edges) {
            LineKey(line, scratch, key);
            Str k(key.els, (int)key.len);
            if (k.len == 0) {
                continue;
            }
            int cur = 0;
            if (!seen.Insert(k, 1, &cur, nullptr)) {
                seen.Remove(k, nullptr);
                seen.Insert(k, cur + 1, nullptr, nullptr);
            }
        }
    }
    int limit = (int)((double)nPages * kBookRunningLineShare);
    if (limit < 2) {
        limit = 2;
    }
    for (int i = 0; i < nPages; i++) {
        Str text = pages.At(i);
        SplitInkLines(text, lines);
        if (lines.len == 0) {
            continue;
        }
        Str edges[2] = {lines[0], lines[lines.len - 1]};
        for (Str line : edges) {
            LineKey(line, scratch, key);
            Str k(key.els, (int)key.len);
            if (k.len == 0) {
                continue;
            }
            int count = 0;
            if (!seen.Get(k, &count) || count < limit) {
                continue;
            }
            if (res.Find(k) < 0) {
                res.Append(k);
            }
        }
    }
    return res;
}

Str BookReadingText(const StrVec& pages) {
    str::Builder b;
    int n = pages.size;
    for (int i = 0; i < n; i++) {
        if (i > 0) {
            b.AppendChar('\n');
        }
        b.Append(pages.At(i));
    }
    str::Builder res;
    Str raw(b.els, (int)b.len);
    for (int i = 0; i < raw.len; i++) {
        char c = raw.s[i];
        if (c == '\r') {
            if (i + 1 < raw.len && raw.s[i + 1] == '\n') {
                i++;
            }
            res.AppendChar('\n');
            continue;
        }
        res.AppendChar(c);
    }
    return res.TakeStr();
}

Str BookIdentityText(const StrVec& pages) {
    StrVec drop = BookRunningLines(pages);
    dict::MapStrToInt dropped(1024);
    for (int i = 0; i < drop.size; i++) {
        dropped.Insert(drop.At(i), 1, nullptr, nullptr);
    }

    str::Builder joined;
    Vec<Str> lines;
    str::Builder scratch;
    str::Builder key;
    bool first = true;
    int nPages = pages.size;
    for (int i = 0; i < nPages; i++) {
        SplitInkLines(pages.At(i), lines);
        if (lines.len == 0) {
            continue;
        }
        for (int at = 0; at < lines.len; at++) {
            bool edge = at == 0 || at == lines.len - 1;
            if (edge) {
                LineKey(lines[at], scratch, key);
                Str k(key.els, (int)key.len);
                int found = 0;
                if (KeyIsAllHashes(k) || dropped.Get(k, &found)) {
                    continue;
                }
            }
            if (!first) {
                joined.AppendChar('\n');
            }
            first = false;
            joined.Append(lines[at]);
        }
    }

    WStr wide = ToWStr(Str(joined.els, (int)joined.len));
    Sig_NormalizeString normalize = GetNormalizeString();
    WStr formed = wide;
    WCHAR* owned = nullptr;
    if (normalize && wide.len > 0) {
        int want = normalize(5 /* NormalizationKC */, wide.s, wide.len, nullptr, 0);
        for (int tries = 0; tries < 4 && want > 0; tries++) {
            owned = AllocArray<WCHAR>((size_t)want + 1);
            int got = normalize(5, wide.s, wide.len, owned, want);
            if (got > 0) {
                formed = WStr(owned, got);
                break;
            }
            free(owned);
            owned = nullptr;
            if (GetLastError() != ERROR_INSUFFICIENT_BUFFER) {
                break;
            }
            want = -got;
        }
    }

    wstr::Builder tight;
    DropPySpaceWide(formed, tight);
    Str res = ToUtf8(WStr(tight.els, (int)tight.len));
    free(owned);
    wstr::Free(wide);
    return res;
}

bool BookIsImageOnly(const StrVec& pages) {
    int n = pages.size;
    if (n == 0) {
        return true;
    }
    i64 total = 0;
    for (int i = 0; i < n; i++) {
        total += CountRunes(TrimPySpace(pages.At(i)));
    }
    return total < (i64)kBookImageOnlyCharsPerPage * n;
}

int BookPageHashDistance(u64 a, u64 b) {
    u64 v = a ^ b;
    int n = 0;
    while (v) {
        v &= v - 1;
        n++;
    }
    return n;
}

bool BookPageHashesLookAlike(const Vec<u64>& a, const Vec<u64>& b, int perPage) {
    if (a.len == 0 || b.len == 0 || a.len != b.len) {
        return false;
    }
    for (int i = 0; i < a.len; i++) {
        if (BookPageHashDistance(a[i], b[i]) > perPage) {
            return false;
        }
    }
    return true;
}

static void HexDigest(const u8 digest[16], char out[33]) {
    static const char* hex = "0123456789abcdef";
    for (int i = 0; i < 16; i++) {
        out[i * 2] = hex[digest[i] >> 4];
        out[i * 2 + 1] = hex[digest[i] & 0xF];
    }
    out[32] = 0;
}

static Str PageTextOf(fz_context* ctx, fz_page* page) {
    fz_stext_options opts{};
    fz_stext_page* stext = nullptr;
    str::Builder b;
    fz_var(stext);
    fz_try(ctx) {
        fz_parse_stext_options(ctx, &opts, kBookStextTextOptions);
        stext = fz_new_stext_page_from_page(ctx, page, &opts);
    }
    fz_catch(ctx) {
        fz_report_error(ctx);
        stext = nullptr;
    }
    if (!stext) {
        return str::Dup(Str(""));
    }
    for (fz_stext_block* block = stext->first_block; block; block = block->next) {
        if (block->type != FZ_STEXT_BLOCK_TEXT) {
            continue;
        }
        for (fz_stext_line* line = block->u.t.first_line; line; line = line->next) {
            int last = 0;
            for (fz_stext_char* ch = line->first_char; ch; ch = ch->next) {
                AppendRune(b, ch->c);
                last = ch->c;
            }
            if (last != '\n' && last > 0) {
                b.AppendChar('\n');
            }
        }
    }
    fz_drop_stext_page(ctx, stext);
    return b.TakeStr();
}

static void PageTexts(fz_context* ctx, fz_document* doc, int nPages, StrVec& out) {
    for (int i = 0; i < nPages; i++) {
        fz_page* page = nullptr;
        fz_var(page);
        fz_try(ctx) {
            page = fz_load_page(ctx, doc, i);
        }
        fz_catch(ctx) {
            fz_report_error(ctx);
            page = nullptr;
        }
        if (!page) {
            out.Append("");
            continue;
        }
        Str text = PageTextOf(ctx, page);
        out.Append(text);
        str::Free(text);
        fz_drop_page(ctx, page);
    }
}

static int CompareMarks(const void* a, const void* b) {
    return memcmp(a, b, 32);
}

static void RollMarks(const Vec<u8>& marks, Str header, char out[33]) {
    str::Builder b;
    b.Append(header);
    b.Append(Str((char*)marks.LendData(), marks.len));
    u8 digest[16];
    CalcMD5Digest(Str(b.els, (int)b.len), digest);
    HexDigest(digest, out);
}

static void ShapeDigest(fz_context* ctx, fz_document* doc, int nPages, char out[33], int& imageCount) {
    imageCount = 0;
    pdf_document* pdf = pdf_specifics(ctx, doc);
    Vec<u8> images;
    Vec<u8> others;
    if (!pdf) {
        RollMarks(images, "raw:0:", out);
        return;
    }
    Vec<int> skip;
    PdfSidecarBlobXrefs(ctx, pdf, skip);

    int xrefLen = 0;
    fz_try(ctx) {
        xrefLen = pdf_xref_len(ctx, pdf);
    }
    fz_catch(ctx) {
        fz_report_error(ctx);
        xrefLen = 0;
    }
    for (int num = 1; num < xrefLen; num++) {
        if (skip.Find(num) >= 0) {
            continue;
        }
        pdf_obj* ref = nullptr;
        fz_buffer* buf = nullptr;
        bool isImage = false;
        fz_var(ref);
        fz_var(buf);
        fz_var(isImage);
        fz_try(ctx) {
            ref = pdf_new_indirect(ctx, pdf, num, 0);
            if (pdf_is_stream(ctx, ref)) {
                buf = pdf_load_raw_stream(ctx, ref);
                const char* subtype = pdf_to_name(ctx, pdf_dict_get(ctx, ref, PDF_NAME(Subtype)));
                isImage = subtype && str::Eq(subtype, "Image");
            }
        }
        fz_catch(ctx) {
            fz_ignore_error(ctx);
        }
        if (buf) {
            u8* data = nullptr;
            size_t n = fz_buffer_storage(ctx, buf, &data);
            if (n > 0) {
                u8 digest[16];
                CalcMD5Digest(Str((char*)data, (int)n), digest);
                char mark[33];
                HexDigest(digest, mark);
                Vec<u8>& bucket = isImage ? images : others;
                bucket.Append((u8*)mark, 32);
            }
            fz_drop_buffer(ctx, buf);
        }
        pdf_drop_obj(ctx, ref);
    }

    if (images.len > 0) {
        qsort(images.LendData(), (size_t)images.len / 32, 32, CompareMarks);
        RollMarks(images, "img:", out);
        imageCount = images.len / 32;
        return;
    }
    qsort(others.LendData(), (size_t)others.len / 32, 32, CompareMarks);
    TempStr header = str::FormatTemp("raw:%d:", nPages);
    RollMarks(others, header, out);
}

static u64 PageHash(fz_context* ctx, fz_page* page) {
    const int side = kBookPageHashSide;
    fz_rect box{};
    fz_var(box);
    fz_try(ctx) {
        box = fz_bound_page(ctx, page);
    }
    fz_catch(ctx) {
        fz_ignore_error(ctx);
        return 0;
    }
    float wide = box.x1 - box.x0;
    float tall = box.y1 - box.y0;
    if (wide <= 0 || tall <= 0) {
        return 0;
    }
    fz_matrix m = fz_make_matrix((side + 1) * kBookPageHashScale / wide, 0, 0, side * kBookPageHashScale / tall, 0, 0);
    fz_pixmap* pix = nullptr;
    fz_var(pix);
    fz_try(ctx) {
        pix = fz_new_pixmap_from_page_contents(ctx, page, m, fz_device_gray(ctx), 0);
    }
    fz_catch(ctx) {
        fz_ignore_error(ctx);
        pix = nullptr;
    }
    if (!pix) {
        return 0;
    }
    int width = pix->w;
    int height = pix->h;
    u64 bits = 0;
    if (width >= 1 && height >= 1 && pix->samples) {
        int cols = side + 1;
        int rows = side;
        Vec<i64> totals;
        Vec<i64> counts;
        for (int row = 0; row < rows; row++) {
            int y0 = (row * height) / rows;
            int y1 = ((row + 1) * height) / rows;
            if (y1 < y0 + 1) {
                y1 = y0 + 1;
            }
            for (int col = 0; col < cols; col++) {
                int x0 = (col * width) / cols;
                int x1 = ((col + 1) * width) / cols;
                if (x1 < x0 + 1) {
                    x1 = x0 + 1;
                }
                i64 total = 0;
                for (int y = y0; y < y1; y++) {
                    const u8* rowData = pix->samples + (size_t)y * (size_t)pix->stride;
                    for (int x = x0; x < x1; x++) {
                        total += rowData[x * pix->n];
                    }
                }
                totals.Append(total);
                counts.Append((i64)(y1 - y0) * (i64)(x1 - x0));
            }
        }
        int bit = 0;
        for (int row = 0; row < rows; row++) {
            int base = row * cols;
            for (int col = 0; col < side; col++) {
                int left = base + col;
                int right = left + 1;
                if (totals[left] * counts[right] > totals[right] * counts[left]) {
                    bits |= (u64)1 << bit;
                }
                bit++;
            }
        }
    }
    fz_drop_pixmap(ctx, pix);
    return bits;
}

static void PageHashes(fz_context* ctx, fz_document* doc, int nPages, Vec<u64>& out) {
    for (int i = 0; i < nPages; i++) {
        fz_page* page = nullptr;
        fz_var(page);
        fz_try(ctx) {
            page = fz_load_page(ctx, doc, i);
        }
        fz_catch(ctx) {
            fz_ignore_error(ctx);
            page = nullptr;
        }
        if (!page) {
            out.Append(0);
            continue;
        }
        out.Append(PageHash(ctx, page));
        fz_drop_page(ctx, page);
    }
}

void BookFingerprintFree(BookFingerprint& fp) {
    str::Free(fp.fingerprint);
    str::Free(fp.readingText);
    fp.fingerprint = {};
    fp.readingText = {};
    fp.pageHashes.Reset();
}

bool BookFingerprintOfFile(Str path, BookFingerprint& out, int wantPageHashes) {
    if (!file::Exists(path)) {
        return false;
    }
    fz_context* ctx = fz_new_context(nullptr, nullptr, FZ_STORE_UNLIMITED);
    if (!ctx) {
        return false;
    }
    fz_register_document_handlers(ctx);
    fz_document* doc = nullptr;
    fz_var(doc);
    fz_try(ctx) {
        doc = fz_open_document(ctx, CStrTemp(path));
    }
    fz_catch(ctx) {
        fz_report_error(ctx);
        doc = nullptr;
    }
    if (!doc) {
        fz_drop_context(ctx);
        return false;
    }
    if (fz_needs_password(ctx, doc)) {
        fz_drop_document(ctx, doc);
        fz_drop_context(ctx);
        return false;
    }

    int nPages = 0;
    fz_try(ctx) {
        nPages = fz_count_pages(ctx, doc);
    }
    fz_catch(ctx) {
        fz_report_error(ctx);
        nPages = 0;
    }

    StrVec pages;
    PageTexts(ctx, doc, nPages, pages);

    Str reading = BookReadingText(pages);
    Str identity = BookIdentityText(pages);

    char shape[33];
    int imageCount = 0;
    ShapeDigest(ctx, doc, nPages, shape, imageCount);

    u8 identityDigest[16];
    CalcMD5Digest(identity, identityDigest);
    char identityHex[33];
    HexDigest(identityDigest, identityHex);

    out.fingerprint = str::Dup(str::FormatTemp("%s:%s:%s", Str(kBookFingerprintVersion), Str(identityHex), Str(shape)));
    CalcMD5Digest(reading, out.textMd5);
    out.textLength = reading.len;
    out.readingText = reading;
    out.pages = nPages;
    out.images = imageCount;
    out.imageOnly = BookIsImageOnly(pages);
    out.pageHashes.Reset();
    bool wanted = wantPageHashes < 0 ? out.imageOnly : wantPageHashes != 0;
    if (wanted) {
        PageHashes(ctx, doc, nPages, out.pageHashes);
    }

    str::Free(identity);
    fz_drop_document(ctx, doc);
    fz_drop_context(ctx);
    return true;
}

TempStr BookFingerprintOfPath(Str path) {
    BookFingerprint fp;
    if (!BookFingerprintOfFile(path, fp, 0)) {
        return {};
    }
    TempStr res = str::DupTemp(fp.fingerprint);
    BookFingerprintFree(fp);
    return res;
}
