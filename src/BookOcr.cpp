/* Copyright 2026 the SumatraPDF project authors (see AUTHORS file).
   License: Simplified BSD (see COPYING.BSD) */

#include "base/Base.h"
#include "base/File.h"

extern "C" {
#include <mupdf/fitz.h>
}

#include "BookOcr.h"
#include "BookFingerprint.h"

static TempStr GetTessdataSubdirInExeDir() {
    TempStr dir = GetSelfExeDirTemp();
    return path::JoinTemp(dir, "tessdata");
}

static TempStr GetTessdataSubdirInRepoShare() {
    TempStr dir = GetSelfExeDirTemp();
    TempStr up = path::JoinTemp(dir, "..");
    up = path::NormalizeTemp(up);
    return path::JoinTemp(up, "share", "tessdata");
}

static TempStr GetTessdataSubdirInRepoExt() {
    TempStr dir = GetSelfExeDirTemp();
    TempStr cur = str::DupTemp(dir);
    for (int i = 0; i < 6; i++) {
        TempStr tryPath = path::JoinTemp(cur, Str("ext/build/ocr-install/share/tessdata"));
        if (file::Exists(tryPath)) {
            return tryPath;
        }
        TempStr parent = path::GetDirTemp(cur);
        if (!parent.s || str::Eq(parent, cur)) {
            break;
        }
        cur = parent;
    }
    return nullptr;
}

TempStr BookOcrTessdataPath() {
    const char* env = getenv("TESSDATA_PREFIX");
    if (env && *env) {
        return file::Exists(path::JoinTemp(env, "eng.traineddata")) ? str::DupTemp(env) : nullptr;
    }

    TempStr inExe = GetTessdataSubdirInExeDir();
    if (file::Exists(path::JoinTemp(inExe, "eng.traineddata"))) {
        return inExe;
    }

    TempStr inShare = GetTessdataSubdirInRepoShare();
    if (file::Exists(path::JoinTemp(inShare, "eng.traineddata"))) {
        return inShare;
    }

    TempStr inExt = GetTessdataSubdirInRepoExt();
    return inExt && file::Exists(path::JoinTemp(inExt, "eng.traineddata")) ? inExt : nullptr;
}

static i64 gMaxBookMs = 0;

void BookOcrSetMaxBookMs(i64 ms) {
    gMaxBookMs = ms;
}

i64 BookOcrGetMaxBookMs() {
    return gMaxBookMs;
}

static void AppendChar(str::Builder& b, int c) {
    if (c < 0) return;
    if (c < 0x80) {
        b.AppendChar((char)c);
        return;
    }
    if (c < 0x800) {
        b.AppendChar((char)(0xC0 | (c >> 6)));
        b.AppendChar((char)(0x80 | (c & 0x3F)));
        return;
    }
    if (c < 0x10000) {
        b.AppendChar((char)(0xE0 | (c >> 12)));
        b.AppendChar((char)(0x80 | ((c >> 6) & 0x3F)));
        b.AppendChar((char)(0x80 | (c & 0x3F)));
        return;
    }
    b.AppendChar((char)(0xF0 | (c >> 18)));
    b.AppendChar((char)(0x80 | ((c >> 12) & 0x3F)));
    b.AppendChar((char)(0x80 | ((c >> 6) & 0x3F)));
    b.AppendChar((char)(0x80 | (c & 0x3F)));
}

static Str StextPageToText(fz_stext_page* stext) {
    str::Builder b;
    if (!stext) return b.TakeStr();
    int lastLine = -1;
    for (fz_stext_block* block = stext->first_block; block; block = block->next) {
        if (block->type != FZ_STEXT_BLOCK_TEXT) continue;
        for (fz_stext_line* line = block->u.t.first_line; line; line = line->next) {
            if (lastLine >= 0) {
                b.AppendChar('\n');
            }
            for (fz_stext_char* ch = line->first_char; ch; ch = ch->next) {
                if (ch->c == 0) continue;
                if (ch->c >= 32 && ch->c <= 0x7E) {
                    b.AppendChar((char)ch->c);
                } else if (ch->c == 0xA0) {
                    b.AppendChar(' ');
                } else if (ch->c >= 0xA1) {
                    AppendChar(b, ch->c);
                }
            }
            lastLine = 0;
        }
    }
    return b.TakeStr();
}

OcrResult BookOcrRun(fz_context* ctx, fz_document* doc, const char* tessdataPath) {
    OcrResult res;
    if (!ctx || !doc) return res;
    if (!tessdataPath || !file::Exists(path::JoinTemp(tessdataPath, "eng.traineddata"))) {
        return res;
    }
    res.tessdataFound = true;
    res.ocrAttempted = true;

    int nPages = 0;
    fz_try(ctx) {
        nPages = fz_count_pages(ctx, doc);
    }
    fz_catch(ctx) {
        fz_report_error(ctx);
        nPages = 0;
    }
    res.totalPages = nPages;
    if (nPages <= 0) {
        return res;
    }

    i64 maxMs = gMaxBookMs;
    u64 tStart = maxMs > 0 ? GetTickCount64() : 0;

    str::Builder collected;
    bool anyText = false;
    for (int pn = 0; pn < nPages; pn++) {
        if (maxMs > 0) {
            u64 now = GetTickCount64();
            if (now - tStart > (u64)maxMs) {
                res.cancelled = true;
                for (int rest = pn; rest < nPages; rest++) {
                    res.pageTexts.Append(str::Dup(Str()));
                    res.pagesSkipped++;
                }
                break;
            }
        }
        fz_page* page = nullptr;
        fz_try(ctx) {
            page = fz_load_page(ctx, doc, pn);
        }
        fz_catch(ctx) {
            fz_report_error(ctx);
            page = nullptr;
        }
        if (!page) {
            res.pageTexts.Append(str::Dup(Str()));
            res.pagesSkipped++;
            continue;
        }

        fz_matrix ctm = fz_identity;
        fz_rect bounds = fz_bound_page(ctx, page);

        fz_stext_page* stext = fz_new_stext_page(ctx, bounds);
        fz_stext_options opts{};
        fz_parse_stext_options(ctx, &opts, "preserve-ligatures,preserve-whitespace,use-cid-for-unknown-unicode");
        fz_device* stext_dev = fz_new_stext_device(ctx, stext, &opts);
        fz_device* ocr_dev = nullptr;
        fz_try(ctx) {
            ocr_dev = fz_new_ocr_device(ctx, stext_dev, ctm, bounds, 0, "eng", tessdataPath, nullptr, nullptr);
        }
        fz_catch(ctx) {
            fz_report_error(ctx);
            ocr_dev = nullptr;
        }
        if (!ocr_dev) {
            fz_close_device(ctx, stext_dev);
            fz_drop_device(ctx, stext_dev);
            fz_drop_stext_page(ctx, stext);
            fz_drop_page(ctx, page);
            res.pageTexts.Append(str::Dup(Str()));
            res.pagesSkipped++;
            continue;
        }

        fz_try(ctx) {
            fz_run_page(ctx, page, ocr_dev, ctm, nullptr);
            fz_close_device(ctx, ocr_dev);
        }
        fz_catch(ctx) {
            fz_report_error(ctx);
        }
        fz_drop_device(ctx, ocr_dev);
        fz_close_device(ctx, stext_dev);
        fz_drop_device(ctx, stext_dev);

        Str pageText = StextPageToText(stext);
        fz_drop_stext_page(ctx, stext);
        fz_drop_page(ctx, page);

        Str trimmed = pageText;
        int start = 0;
        while (start < trimmed.len) {
            if (trimmed.s[start] == ' ' || trimmed.s[start] == '\n' || trimmed.s[start] == '\r' || trimmed.s[start] == '\t') {
                start++;
            } else {
                break;
            }
        }
        int end = trimmed.len;
        while (end > start) {
            char c = trimmed.s[end - 1];
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                end--;
            } else {
                break;
            }
        }
        Str clean(trimmed.s + start, end - start);
        int tokenCount = 0;
        bool inWord = false;
        for (int i = 0; i < clean.len; i++) {
            char c = clean.s[i];
            bool alphaNum = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (alphaNum && !inWord) {
                tokenCount++;
                inWord = true;
            } else if (!alphaNum) {
                inWord = false;
            }
        }
        if (tokenCount > 0) {
            res.pagesOcred++;
            if (collected.len > 0) {
                collected.AppendChar('\n');
            }
            collected.Append(clean);
            anyText = true;
            res.pageTexts.Append(str::Dup(clean));
        } else {
            res.pagesSkipped++;
            res.pageTexts.Append(str::Dup(Str()));
        }
        Str boundedIdentity = BookIdentityText(res.pageTexts, nullptr, &res.recognizedTokens);
        str::Free(boundedIdentity);
        if (res.recognizedTokens >= kBookIdentityTokenLimit) {
            for (int rest = pn + 1; rest < nPages; rest++) {
                res.pageTexts.Append(str::Dup(Str()));
                res.pagesSkipped++;
            }
            break;
        }
    }

    if (anyText && res.recognizedTokens >= kBookOcrMinTokensForIdentity) {
        res.ocrSucceeded = true;
        res.collectedText = collected.TakeStr();
    }
    return res;
}
