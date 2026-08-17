/* Copyright 2026 the SumatraPDF project authors (see AUTHORS file).
   License: GPLv3 */

#include "base/Base.h"

#if defined(DEBUG)

#include "TipText.h"
#include "Commands.h"
#include "base/File.h"
#include "BookBlob.h"
#include "BookFingerprint.h"
#include "PdfSidecar.h"

// must be last to over-write assert()
#include "base/UtAssert.h"

static void ParseTipExpectWordsLinks(Str input, int expWords, int expLinks) {
    ParsedTip tip;
    ParseTip(tip, input);
    utassert(len(tip.words) == expWords);
    utassert(len(tip.links) == expLinks);
}

static void ParseTipExpectPlainContains(Str input, Str needle) {
    ParsedTip tip;
    ParseTip(tip, input);
    TempStr plain = TipPlainTextTemp(tip);
    utassert(plain && str::Contains(plain, needle));
}

static void ParseTipExpectLinkCmd(Str input, Str expCmd) {
    ParsedTip tip;
    ParseTip(tip, input);
    utassert(len(tip.links) == 1);
    utassert(str::Eq(tip.links[0].cmd, expCmd));
}

static void ParseTip_UnitTests() {
    // issue #5752: brackets in filenames must not hang
    ParseTipExpectPlainContains("Loading Apocalypse Bringer Mynoghra_01 [CIW].pdf ...", "[CIW]");

    // empty link text must not create a zero-word link (DrawTipWords crash)
    ParseTipExpectWordsLinks("[](CmdFoo)", 1, 0);
    ParseTipExpectPlainContains("[](CmdFoo)", "[](CmdFoo)");

    // URLs may contain balanced parentheses
    ParseTipExpectLinkCmd("[text](https://example.com/foo(bar))", "https://example.com/foo(bar)");
    ParseTipExpectWordsLinks("[text](https://example.com/foo(bar))", 1, 1);

    // Help/ link followed by trailing punctuation: the resolved URL must stop at
    // the link's ')' and not pull in the following ")." (the link cmd is a
    // non-NUL-terminated view into the tip line)
    ParseTipExpectLinkCmd("You can [extract text from PDF file](Help/Tool-x-extract-text-from-pdf).",
                          "https://www.sumatrapdfreader.org/docs/Tool-x-extract-text-from-pdf");

    // nested brackets in link text
    ParseTipExpectWordsLinks("[foo [bar]](CmdFoo)", 2, 1);
    ParseTipExpectPlainContains("[foo [bar]](CmdFoo)", "foo");
    ParseTipExpectPlainContains("[foo [bar]](CmdFoo)", "[bar]");

    // (Key/...) only expands for real commands
    ParseTipExpectPlainContains("file (Key/foo).pdf", "(Key/foo).pdf");
    ParseTipExpectPlainContains("(Key/CmdCommandPalette)", "Ctrl");

    // whitespace: tab and newline break words
    ParseTipExpectWordsLinks("line1\nline2", 2, 0);
    ParseTipExpectWordsLinks("tab\there", 2, 0);

    // ordinary tips still work
    ParseTipExpectWordsLinks("before [valid](CmdFoo)", 2, 1);
    ParseTipExpectWordsLinks("[valid](CmdFoo) after", 2, 1);
}

static bool BytesEq(const Vec<u8>& a, Str b) {
    if (a.len != b.len) {
        return false;
    }
    return memcmp(a.LendData(), b.s, (size_t)b.len) == 0;
}

static void BookBlobGolden(Str dir, Str name, Str outDir) {
    TempStr payloadPath = str::FormatTemp("%s\\%s.payload", dir, name);
    TempStr blobPath = str::FormatTemp("%s\\%s.blob", dir, name);
    Str payload = file::ReadFile(payloadPath);
    Str blob = file::ReadFile(blobPath);
    utassert(payload.s && payload.len > 0);
    utassert(blob.s && blob.len > 0);

    BookBlobRecord fromPayload;
    utassert(BookBlobUnpayload((const u8*)payload.s, payload.len, fromPayload));
    Vec<u8> rePayload;
    utassert(BookBlobPayload(fromPayload, rePayload));
    utassert(BytesEq(rePayload, payload));

    BookBlobRecord fromBlob;
    utassert(BookBlobDecode((const u8*)blob.s, blob.len, fromBlob));
    Vec<u8> blobPayload;
    utassert(BookBlobPayload(fromBlob, blobPayload));
    utassert(BytesEq(blobPayload, payload));

    Vec<u8> ourBlob;
    utassert(BookBlobEncode(fromBlob, ourBlob));
    BookBlobRecord roundTrip;
    utassert(BookBlobDecode(ourBlob.LendData(), ourBlob.len, roundTrip));
    Vec<u8> roundTripPayload;
    utassert(BookBlobPayload(roundTrip, roundTripPayload));
    utassert(BytesEq(roundTripPayload, payload));

    Vec<u8> squeezed;
    utassert(BookBlobCompress((const u8*)payload.s, payload.len, squeezed));
    Vec<u8> expanded;
    utassert(BookBlobDecompress(squeezed.LendData(), squeezed.len, payload.len, expanded));
    utassert(BytesEq(expanded, payload));

    if (outDir.s) {
        TempStr outPath = str::FormatTemp("%s\\%s.win32.blob", outDir, name);
        utassert(file::WriteFile(outPath, Str((const char*)ourBlob.LendData(), ourBlob.len)));
    }

    str::Free(payload);
    str::Free(blob);
}

static void BookBlob_UnitTests() {
    char dirBuf[1024];
    DWORD n = GetEnvironmentVariableA("SUMATRA_BOOKBLOB_GOLDEN", dirBuf, (DWORD)dimof(dirBuf));
    if (n == 0 || n >= dimof(dirBuf)) {
        return;
    }
    char outBuf[1024];
    DWORD nOut = GetEnvironmentVariableA("SUMATRA_BOOKBLOB_OUT", outBuf, (DWORD)dimof(outBuf));
    Str outDir;
    if (nOut > 0 && nOut < dimof(outBuf)) {
        outDir = Str(outBuf);
    }
    const char* names[] = {"minimal", "edge", "image", "book0", "book1", "book2"};
    for (const char* name : names) {
        BookBlobGolden(dirBuf, name, outDir);
    }
}

static TempStr EnvVarTemp(const char* name) {
    char buf[2048];
    DWORD n = GetEnvironmentVariableA(name, buf, (DWORD)dimof(buf));
    if (n == 0 || n >= dimof(buf)) {
        return nullptr;
    }
    return str::DupTemp(Str(buf, (int)n));
}

static void AppendHex(str::Builder& b, const u8* data, int n) {
    static const char* hex = "0123456789abcdef";
    for (int i = 0; i < n; i++) {
        b.AppendChar(hex[data[i] >> 4]);
        b.AppendChar(hex[data[i] & 0xF]);
    }
}

static void BookFingerprint_UnitTests() {
    TempStr listPath = EnvVarTemp("SUMATRA_FP_LIST");
    TempStr outPath = EnvVarTemp("SUMATRA_FP_OUT");
    if (!listPath || !outPath) {
        return;
    }
    Str listData = file::ReadFile(listPath);
    utassert(listData.s && listData.len > 0);
    StrVec paths;
    Split(&paths, listData, "\n", true);

    str::Builder out;
    for (Str path : paths) {
        Str line = path;
        while (line.len > 0 && (line.s[line.len - 1] == '\r' || line.s[line.len - 1] == ' ')) {
            line.len--;
        }
        if (line.len == 0) {
            continue;
        }
        BookFingerprint fp;
        bool ok = BookFingerprintOfFile(line, fp, 1);
        if (!ok) {
            out.Append("FAIL\t\t\t\t\t\t\t");
            out.Append(line);
            out.AppendChar('\n');
            continue;
        }
        out.Append(fp.fingerprint);
        out.AppendChar('\t');
        out.Append(str::FormatTemp("%d\t%d\t%d\t%d\t", fp.pages, fp.images, fp.imageOnly ? 1 : 0, (int)fp.textLength));
        AppendHex(out, fp.textMd5, 16);
        out.AppendChar('\t');
        for (int i = 0; i < fp.pageHashes.len; i++) {
            if (i > 0) {
                out.AppendChar(',');
            }
            u64 v = fp.pageHashes[i];
            u8 be[8];
            for (int k = 0; k < 8; k++) {
                be[k] = (u8)(v >> (56 - k * 8));
            }
            AppendHex(out, be, 8);
        }
        out.AppendChar('\t');
        out.Append(line);
        out.AppendChar('\n');
        BookFingerprintFree(fp);
    }
    utassert(file::WriteFile(outPath, Str(out.els, (int)out.len)));
    str::Free(listData);
}

static void PdfSidecar_UnitTests() {
    TempStr srcPath = EnvVarTemp("SUMATRA_SIDECAR_SRC");
    TempStr dstPath = EnvVarTemp("SUMATRA_SIDECAR_DST");
    TempStr blobPath = EnvVarTemp("SUMATRA_SIDECAR_BLOB");
    if (!srcPath || !dstPath || !blobPath) {
        return;
    }
    Str original = file::ReadFile(srcPath);
    Str blob = file::ReadFile(blobPath);
    utassert(original.s && original.len > 0);
    utassert(blob.s && blob.len > 0);
    utassert(file::Copy(dstPath, srcPath, false));

    BookFingerprint before;
    utassert(BookFingerprintOfFile(dstPath, before, 1));
    utassert(!PdfSidecarHasBlob(dstPath));
    utassert(!PdfSidecarReadFingerprint(dstPath));

    Vec<PdfInfoField> info;
    info.Append({"Title", "SidecarProbe"});
    info.Append({kInfoSeries, "SidecarSeries"});
    Str err;
    bool wrote = PdfSidecarWriteBlob(dstPath, (const u8*)blob.s, blob.len, before.fingerprint, &info, &err);
    utassert(wrote);

    Str saved = file::ReadFile(dstPath);
    utassert(saved.s && saved.len > original.len);
    utassert(memcmp(saved.s, original.s, (size_t)original.len) == 0);

    utassert(PdfSidecarHasBlob(dstPath));
    Vec<u8> readBack;
    utassert(PdfSidecarReadBlob(dstPath, readBack));
    utassert(BytesEq(readBack, blob));

    BookBlobRecord fromFile;
    utassert(PdfSidecarReadRecord(dstPath, fromFile));
    BookBlobRecord fromBlob;
    utassert(BookBlobDecode((const u8*)blob.s, blob.len, fromBlob));
    Vec<u8> payloadA;
    Vec<u8> payloadB;
    utassert(BookBlobPayload(fromFile, payloadA));
    utassert(BookBlobPayload(fromBlob, payloadB));
    utassert(payloadA.len == payloadB.len);
    utassert(memcmp(payloadA.LendData(), payloadB.LendData(), (size_t)payloadA.len) == 0);

    TempStr storedFp = PdfSidecarReadFingerprint(dstPath);
    utassert(storedFp && str::Eq(storedFp, before.fingerprint));

    BookFingerprint after;
    utassert(BookFingerprintOfFile(dstPath, after, 1));
    utassert(str::Eq(after.fingerprint, before.fingerprint));
    utassert(after.pages == before.pages);
    utassert(after.textLength == before.textLength);
    utassert(memcmp(after.textMd5, before.textMd5, 16) == 0);
    utassert(after.pageHashes.len == before.pageHashes.len);
    for (int i = 0; i < after.pageHashes.len; i++) {
        utassert(after.pageHashes[i] == before.pageHashes[i]);
    }

    BookFingerprintFree(before);
    BookFingerprintFree(after);
    str::Free(original);
    str::Free(saved);
    str::Free(blob);
}

static void PdfSidecarForeign_UnitTests() {
    TempStr foreignList = EnvVarTemp("SUMATRA_SIDECAR_FOREIGN");
    TempStr blobPath = EnvVarTemp("SUMATRA_SIDECAR_BLOB");
    if (!foreignList || !blobPath) {
        return;
    }
    Str blob = file::ReadFile(blobPath);
    utassert(blob.s && blob.len > 0);
    StrVec files;
    Split(&files, foreignList, ";", true);
    for (Str path : files) {
        if (path.len == 0) {
            continue;
        }
        utassert(PdfSidecarHasBlob(path));
        Vec<u8> got;
        utassert(PdfSidecarReadBlob(path, got));
        utassert(BytesEq(got, blob));

        BookBlobRecord rec;
        utassert(PdfSidecarReadRecord(path, rec));
        TempStr storedFp = PdfSidecarReadFingerprint(path);
        utassert(storedFp && str::StartsWith(storedFp, kBookFingerprintVersion));

        BookFingerprint fp;
        utassert(BookFingerprintOfFile(path, fp, 0));
        utassert(str::Eq(fp.fingerprint, storedFp));
        BookFingerprintFree(fp);
    }
    str::Free(blob);
}

int RunAppUnitTests() {
    ParseTip_UnitTests();
    BookBlob_UnitTests();
    BookFingerprint_UnitTests();
    PdfSidecar_UnitTests();
    PdfSidecarForeign_UnitTests();
    return utassert_print_results();
}

#endif
