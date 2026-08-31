/* Copyright 2026 the SumatraPDF project authors (see AUTHORS file).
   License: GPLv3 */

#include "base/Base.h"

#if defined(DEBUG)

#include "base/Archive.h"
#include "gui/UIModels.h"
#include "gui/Layout.h"
#include "gui/PlatformFont.h"
#include "gui/Gfx.h"
#include "gui/VirtCtrl.h"
#include "Commands.h"
#include "base/Crypto.h"
#include "base/File.h"
#include "base/GuessFileType.h"
#include "BookBlob.h"
#include "BookFingerprint.h"
#include "CoverOnline.h"
#include "CoverVision.h"
#include "LibrarySidecar.h"
#include "EbookBase.h"
#include "EbookDoc.h"
#include "PalmDbReader.h"
#include "MobiDoc.h"
#include "PdfSidecar.h"
#include "CoverSpotVectors.h"

#if defined(DEBUG)
void TextSelection_UnitTests();
void Layout_UnitTests();
void VirtCtrl_UnitTests();
bool TableOfContents_UnitTestSnapshotNamedDest();
bool MarkdownModel_UnitTestBrowserNavigationUrl();
bool MarkdownToc_UnitTestHtmlLinks();
bool MarkdownToc_UnitTestHtmlHeadings();
bool MarkdownToc_UnitTestMermaid();
bool EbookDoc_UnitTestNormalizeURL();
bool ExternalViewers_UnitTestPDFXChangePaths();
bool Canvas_UnitTestScrollLineAmount();
bool EngineMupdf_UnitTestEbookLineSpacingCss();
bool EngineMupdf_UnitTestEbookFontFamilyCss();
bool EngineMupdf_UnitTestEbookMarginCss();
bool EngineMupdf_UnitTestMergeEBookUI();
#endif

// must be last to over-write assert()
#include "base/UtAssert.h"

static void ParseTipExpectWordsLinks(Str input, int expWords, int expLinks) {
    VirtRichText* tip = ParseTip(input);
    utassert(TipWordCount(tip) == expWords);
    utassert(TipLinkCount(tip) == expLinks);
    delete tip;
}

static void ParseTipExpectPlainContains(Str input, Str needle) {
    VirtRichText* tip = ParseTip(input);
    TempStr plain = tip->PlainTextTemp();
    utassert(plain && str::Contains(plain, needle));
    delete tip;
}

static void ParseTipExpectLinkCmd(Str input, Str expCmd) {
    VirtRichText* tip = ParseTip(input);
    utassert(TipLinkCount(tip) == 1);
    utassert(str::Eq(tip->links.next->cmd, expCmd));
    delete tip;
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

    // (Kbd/...) draws as a key-cap word; nests with (Key/...)
    {
        VirtRichText* tip = ParseTip("(Kbd/Cmd+Shift)");
        utassert(TipWordCount(tip) == 1);
        utassert(tip->words.next->isKbd);
        utassert(str::Eq(tip->words.next->text, StrL("Cmd+Shift")));
        utassert(tip->HasRichContent());
        delete tip;
    }
    {
        VirtRichText* tip = ParseTip("(Kbd/(Key/CmdCommandPalette)): go");
        utassert(TipWordCount(tip) >= 2);
        TipWord* w0 = tip->words.next;
        TipWord* w1 = w0->next;
        utassert(w0->isKbd);
        // expanded shortcut contains Ctrl (default binding)
        utassert(str::Contains(w0->text, StrL("Ctrl")));
        // ':' abuts the key-cap with no space
        utassert(w1->noSpaceBefore);
        utassert(str::Eq(w1->text, StrL(":")));
        delete tip;
    }

    // whitespace: tab and newline break words
    ParseTipExpectWordsLinks("line1\nline2", 2, 0);
    ParseTipExpectWordsLinks("tab\there", 2, 0);

    // ordinary tips still work
    ParseTipExpectWordsLinks("before [valid](CmdFoo)", 2, 1);
    ParseTipExpectWordsLinks("[valid](CmdFoo) after", 2, 1);

    // GHSA-2wv2-qm2f-vmxh: a file name can contain the markup, so text from
    // outside the app must never become a link. AddPlainText / AddPlainLink are
    // how such text gets in
    {
        Str evil = StrL("a[b](CmdExec calc.exe)c");
        VirtRichText* tip = new VirtRichText();
        tip->AddPlainText(evil);
        utassert(TipLinkCount(tip) == 0);
        utassert(str::Contains(tip->PlainTextTemp(), StrL("(CmdExec")));
        delete tip;

        // the same text as a link: exactly one link, and to our command
        tip = new VirtRichText();
        tip->AddPlainLink(evil, StrL("CmdOpenNextFileInFolder"));
        utassert(TipLinkCount(tip) == 1);
        utassert(str::Eq(tip->links.next->cmd, StrL("CmdOpenNextFileInFolder")));
        delete tip;

        // mixing our markup with outside text keeps them apart
        tip = new VirtRichText();
        ParseTipInto(tip, StrL("open"));
        tip->AddPlainLink(evil, StrL("CmdOpenNextFileInFolder"));
        ParseTipInto(tip, StrL("[browse](CmdNavigateFilesInFolder)"));
        utassert(TipLinkCount(tip) == 2);
        utassert(str::Eq(tip->links.next->cmd, StrL("CmdOpenNextFileInFolder")));
        utassert(str::Eq(tip->links.next->next->cmd, StrL("CmdNavigateFilesInFolder")));
        delete tip;
    }
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

static void MobiCover_UnitTests() {
    TempStr listPath = EnvVarTemp("SUMATRA_MOBI_LIST");
    TempStr outPath = EnvVarTemp("SUMATRA_MOBI_OUT");
    if (!listPath || !outPath) {
        return;
    }
    Str listData = file::ReadFile(listPath);
    utassert(listData.s && listData.len > 0);
    StrVec paths;
    Split(&paths, listData, "\n", true);
    str::Builder out;
    for (Str path : paths) {
        while (path.len > 0 && (path.s[path.len - 1] == '\r' || path.s[path.len - 1] == ' ')) {
            path.len--;
        }
        if (path.len == 0) {
            continue;
        }
        MobiDoc* doc = MobiDoc::CreateFromFile(path);
        Str cover = doc ? doc->GetCoverImage() : Str{};
        out.Append(str::FormatTemp("%d\t", cover.len));
        if (cover.len > 0) {
            u8 md5[16];
            CalcMD5Digest(cover, md5);
            AppendHex(out, md5, 16);
        }
        out.AppendChar('\t');
        out.Append(path::GetBaseNameTemp(path));
        out.AppendChar('\n');
        delete doc;
    }
    utassert(file::WriteFile(outPath, Str(out.els, (int)out.len)));
    str::Free(listData);
}

static Str NativeCoverForFile(Str filePath) {
    FileType kind = GuessFileType(filePath, true);
    Str cover;
    if (EpubDoc::IsSupportedFileType(kind)) {
        EpubDoc* doc = EpubDoc::CreateFromFile(filePath);
        if (doc) {
            cover = str::Dup(doc->GetCoverImage());
            delete doc;
        }
    } else if (Fb2Doc::IsSupportedFileType(kind)) {
        Fb2Doc* doc = Fb2Doc::CreateFromFile(filePath);
        if (doc) {
            cover = str::Dup(doc->GetCoverImage());
            delete doc;
        }
    } else if (MobiDoc::IsSupportedFileType(kind)) {
        MobiDoc* doc = MobiDoc::CreateFromFile(filePath);
        if (doc) {
            cover = str::Dup(doc->GetCoverImage());
            delete doc;
        }
    }
    return cover;
}

static void NativeCover_UnitTests() {
    TempStr listPath = EnvVarTemp("SUMATRA_NATIVE_COVER_LIST");
    TempStr outPath = EnvVarTemp("SUMATRA_NATIVE_COVER_OUT");
    if (!listPath || !outPath) {
        return;
    }
    Str listData = file::ReadFile(listPath);
    utassert(listData.s && listData.len > 0);
    StrVec paths;
    Split(&paths, listData, "\n", true);
    str::Builder out;
    for (Str path : paths) {
        while (path.len > 0 && (path.s[path.len - 1] == '\r' || path.s[path.len - 1] == ' ')) {
            path.len--;
        }
        if (path.len == 0) {
            continue;
        }
        Str cover = NativeCoverForFile(path);
        out.Append(str::FormatTemp("%d\t", cover.len));
        if (cover.len > 0) {
            u8 md5[16];
            CalcMD5Digest(cover, md5);
            AppendHex(out, md5, 16);
        }
        out.AppendChar('\t');
        out.Append(path::GetBaseNameTemp(path));
        out.AppendChar('\n');
        str::Free(cover);
    }
    utassert(file::WriteFile(outPath, Str(out.els, (int)out.len)));
    str::Free(listData);
}

static void BuiltCover_UnitTests() {
    TempStr listPath = EnvVarTemp("SUMATRA_BUILT_COVER_LIST");
    TempStr outPath = EnvVarTemp("SUMATRA_BUILT_COVER_OUT");
    if (!listPath || !outPath) {
        return;
    }
    Str listData = file::ReadFile(listPath);
    utassert(listData.s && listData.len > 0);
    StrVec paths;
    Split(&paths, listData, "\n", true);
    str::Builder out;
    for (Str path : paths) {
        while (path.len > 0 && (path.s[path.len - 1] == '\r' || path.s[path.len - 1] == ' ')) {
            path.len--;
        }
        if (path.len == 0) {
            continue;
        }
        Str cover = CoverBuildForBook(path, path::GetBaseNameTemp(path), {}, {}, {}, 0, nullptr);
        out.Append(str::FormatTemp("%d\t", cover.len));
        if (cover.len > 0) {
            u8 md5[16];
            CalcMD5Digest(cover, md5);
            AppendHex(out, md5, 16);
        }
        out.AppendChar('\t');
        out.Append(path::GetBaseNameTemp(path));
        out.AppendChar('\n');
        str::Free(cover);
    }
    utassert(file::WriteFile(outPath, Str(out.els, (int)out.len)));
    str::Free(listData);
}

static void AutomaticImage_UnitTests() {
    TempStr listPath = EnvVarTemp("SUMATRA_AUTO_IMAGE_LIST");
    TempStr outPath = EnvVarTemp("SUMATRA_AUTO_IMAGE_OUT");
    if (!listPath || !outPath) {
        return;
    }
    Str listData = file::ReadFile(listPath);
    utassert(listData.s && listData.len > 0);
    StrVec paths;
    Split(&paths, listData, "\n", true);
    str::Builder out;
    for (Str path : paths) {
        while (path.len > 0 && (path.s[path.len - 1] == '\r' || path.s[path.len - 1] == ' ')) {
            path.len--;
        }
        if (path.len == 0) {
            continue;
        }
        Str image = CoverImageResourceForBook(path);
        out.Append(str::FormatTemp("%d\t", image.len));
        if (image.len > 0) {
            u8 md5[16];
            CalcMD5Digest(image, md5);
            AppendHex(out, md5, 16);
        }
        out.AppendChar('\t');
        out.Append(path::GetBaseNameTemp(path));
        out.AppendChar('\n');
        str::Free(image);
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

static void PdfSidecarCover_UnitTests() {
    TempStr srcPath = EnvVarTemp("SUMATRA_SIDECAR_SRC");
    TempStr dstPath = EnvVarTemp("SUMATRA_SIDECAR_COVER_DST");
    if (!srcPath || !dstPath) {
        return;
    }
    Str original = file::ReadFile(srcPath);
    utassert(original.s && original.len > 0);
    utassert(file::Copy(dstPath, srcPath, false));

    utassert(!PdfSidecarHasCover(dstPath));
    const u8 kFakeWebp[] = {'R', 'I', 'F', 'F', 0x24, 0,   0,   0,   'W', 'E', 'B', 'P',
                            'V', 'P', '8', ' ', 'c',  'o', 'v', 'e', 'r', 0,   1,   2};
    int coverLen = (int)dimof(kFakeWebp);
    Str coverErr;
    utassert(PdfSidecarWriteCover(dstPath, StrL("webp"), kFakeWebp, coverLen, &coverErr));
    str::Free(coverErr);
    utassert(PdfSidecarHasCover(dstPath));

    Str coverFormat;
    Vec<u8> coverBack;
    utassert(PdfSidecarReadCover(dstPath, &coverFormat, coverBack));
    utassert(str::Eq(coverFormat, StrL("webp")));
    utassert(coverBack.len == coverLen);
    utassert(memcmp(coverBack.LendData(), kFakeWebp, (size_t)coverLen) == 0);
    str::Free(coverFormat);

    Str withCover = file::ReadFile(dstPath);
    utassert(withCover.s && withCover.len > original.len + coverLen);
    utassert(memcmp(withCover.s, original.s, (size_t)original.len) == 0);

    utassert(PdfSidecarRemoveCover(dstPath, &coverErr));
    str::Free(coverErr);
    utassert(!PdfSidecarHasCover(dstPath));

    str::Free(original);
    str::Free(withCover);
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

static void CoverOnline_UnitTests() {
    Str json = StrL(
        "{\"docs\":[{\"cover_i\":10,\"first_publish_year\":1990,\"publish_year\":[1990,1995],\"edition_count\":40,"
        "\"readinglog_count\":900},{\"cover_i\":20,\"first_publish_year\":1989,\"publish_year\":[1997],\"edition_"
        "count\":8,\"readinglog_count\":30},{\"cover_i\":30,\"first_publish_year\":1997,\"edition_count\":3,"
        "\"readinglog_count\":5},{\"first_publish_year\":1997,\"readinglog_count\":2000}]}");
    Vec<OnlineCoverHit> hits;
    CoverOnlineParseHits(json, 1997, hits);
    utassert(len(hits) == 4);
    utassert(hits[0].coverId == 10);
    utassert(!hits[0].yearMatches);
    utassert(hits[1].yearMatches);
    utassert(hits[2].yearMatches);
    utassert(CoverOnlinePickHit(hits, 1997) == 1);
    utassert(CoverOnlinePickHit(hits, 0) == 0);
    utassert(str::Contains(CoverOnlineSearchUrlTemp(StrL("The Exposed"), StrL("K. A. Applegate"), StrL("Animorphs")),
                           StrL("title=The%20Exposed")));
    utassert(str::Contains(CoverOnlineSearchUrlTemp(StrL("The Exposed"), StrL("K. A. Applegate"), StrL("Animorphs")),
                           StrL("author=K.%20A.%20Applegate")));
    utassert(str::Contains(CoverOnlineSearchUrlTemp(StrL("The Exposed"), {}, StrL("Animorphs")), StrL("q=Animorphs")));
    utassert(
        str::Eq(CoverOnlineImageUrlTemp(123), StrL("https://covers.openlibrary.org/b/id/123-L.jpg?default=false")));
}

static void CoverSpot_UnitTests() {
    for (const CoverSpotGood& c : gCoverSpotGood) {
        BlobCover got;
        bool ok = BookCoverSpotDecode(Str(c.text), &got);
        if (!ok) {
            logf("CoverSpot: '%s' was refused\n", Str(c.text));
        }
        utassert(ok);
        utassert(got.kind == kBlobCoverPage);
        utassert(got.page == c.page);
        utassert(got.x0 == c.x0);
        utassert(got.x1 == c.x1);
        utassert(got.y0 == c.y0);
        utassert(got.y1 == c.y1);
        utassert(got.rotation == c.rotation);

        TempStr back = BookCoverSpotEncode(got);
        if (!str::Eq(back, Str(c.text))) {
            logf("CoverSpot: '%s' encoded back as '%s'\n", Str(c.text), back);
        }
        utassert(str::Eq(back, Str(c.text)));

        BookBlobRecord rec;
        rec.hasCover = true;
        rec.cover = got;
        Vec<u8> blob;
        utassert(BookBlobEncode(rec, blob));
        BookBlobRecord read;
        utassert(BookBlobDecode(blob.LendData(), blob.len, read));
        utassert(read.hasCover);
        utassert(str::Eq(BookCoverSpotEncode(read.cover), Str(c.text)));
    }

    for (const char* bad : gCoverSpotBad) {
        BlobCover got;
        bool ok = BookCoverSpotDecode(Str(bad), &got);
        if (ok) {
            logf("CoverSpot: '%s' should not have decoded\n", Str(bad));
        }
        utassert(!ok);
    }

    BlobCover none;
    utassert(!BookCoverSpotEncode(none).s);
    BlobCover image;
    image.kind = kBlobCoverImage;
    utassert(!BookCoverSpotEncode(image).s);

    BlobCover flat;
    flat.kind = kBlobCoverPage;
    flat.page = 3;
    flat.x0 = 1;
    flat.y0 = 2;
    flat.x1 = 4;
    flat.y1 = 5;
    BookBlobRecord flatRec;
    flatRec.hasCover = true;
    flatRec.cover = flat;
    Vec<u8> flatBlob;
    utassert(BookBlobEncode(flatRec, flatBlob));
    BlobCover turned = flat;
    turned.rotation = 90;
    BookBlobRecord turnedRec;
    turnedRec.hasCover = true;
    turnedRec.cover = turned;
    Vec<u8> turnedBlob;
    utassert(BookBlobEncode(turnedRec, turnedBlob));
    utassert(turnedBlob.len > flatBlob.len);
    BookBlobRecord flatBack;
    utassert(BookBlobDecode(flatBlob.LendData(), flatBlob.len, flatBack));
    utassert(flatBack.cover.rotation == 0);
}

static BookBlobRecord* MakeSaneRecord(BookBlobRecord& rec) {
    rec.hasIdentity = true;
    rec.identity.fingerprint = rec.strings.Append(StrL("fp2:0683cd1f")).s;
    rec.identity.title = rec.strings.Append(StrL("Exploring Raspberry Pi")).s;
    return &rec;
}

static void FakeArt(Vec<u8>& out, const char* magic, int n) {
    if (str::Eq(Str(magic), StrL("webp"))) {
        out.Append((const u8*)"RIFF\x00\x00\x00\x00WEBPVP8 ", 16);
    } else if (str::Eq(Str(magic), StrL("png"))) {
        out.Append((const u8*)"\x89PNG\r\n\x1a\n", 8);
    } else {
        out.Append((const u8*)"\xff\xd8\xff\xe0", 4);
    }
    while (out.len < n) {
        out.Append((u8)0x20);
    }
}

static void BookRecordCheck_UnitTests() {
    {
        BookBlobRecord rec;
        utassert(BookRecordWhyInvalid(rec).s);
    }
    {
        BookBlobRecord rec;
        MakeSaneRecord(rec);
        TempStr why = BookRecordWhyInvalid(rec);
        if (why.s) {
            logf("BookRecordCheck: a sane record was refused: %s\n", why);
        }
        utassert(!why.s);
    }
    {
        BookBlobRecord rec;
        MakeSaneRecord(rec);
        rec.hasCover = true;
        rec.cover.kind = kBlobCoverPage;
        rec.cover.page = 0;
        rec.cover.x1 = 10;
        rec.cover.y1 = 10;
        utassert(BookRecordWhyInvalid(rec).s);
    }
    {
        BookBlobRecord rec;
        MakeSaneRecord(rec);
        rec.hasCover = true;
        rec.cover.kind = kBlobCoverPage;
        rec.cover.page = 1;
        utassert(BookRecordWhyInvalid(rec).s);
    }
    {
        BookBlobRecord rec;
        MakeSaneRecord(rec);
        rec.hasCover = true;
        rec.cover.kind = kBlobCoverPage;
        rec.cover.page = 1;
        rec.cover.x1 = 10;
        rec.cover.y1 = 10;
        rec.cover.rotation = 45;
        utassert(BookRecordWhyInvalid(rec).s);
        rec.cover.rotation = 270;
        utassert(!BookRecordWhyInvalid(rec).s);
    }
    {
        BookBlobRecord rec;
        MakeSaneRecord(rec);
        rec.hasCover = true;
        rec.cover.kind = kBlobCoverPage;
        rec.cover.page = 1;
        rec.cover.x1 = 10;
        rec.cover.y1 = 20;
        rec.cover.format = rec.strings.Append(StrL("webp")).s;
        rec.identity.title = rec.strings.Append(StrL("The Exposed")).s;
        rec.identity.author = rec.strings.Append(StrL("K. A. Applegate")).s;
        rec.identity.year = 1998;
        rec.hasShelf = true;
        rec.shelf.series = rec.strings.Append(StrL("Animorphs")).s;
        FakeArt(rec.cover.data, "webp", 2048);
        utassert(!BookRecordWhyInvalid(rec).s);
        Vec<u8> blob;
        utassert(BookBlobEncode(rec, blob));
        BookBlobRecord back;
        utassert(BookBlobDecode(blob.LendData(), blob.len, back));
        utassert(back.cover.kind == kBlobCoverPage);
        utassert(back.cover.page == 1);
        utassert(back.cover.data.len == 2048);
        utassert(str::Eq(Str(back.cover.format), StrL("webp")));
        utassert(str::Eq(Str(back.identity.title), StrL("The Exposed")));
        utassert(str::Eq(Str(back.identity.author), StrL("K. A. Applegate")));
        utassert(back.identity.year == 1998);
        utassert(str::Eq(Str(back.shelf.series), StrL("Animorphs")));
    }
    {
        BookBlobRecord rec;
        MakeSaneRecord(rec);
        rec.hasCover = true;
        rec.cover.kind = kBlobCoverImage;
        rec.cover.format = rec.strings.Append(StrL("webp")).s;
        FakeArt(rec.cover.data, "webp", 64);
        utassert(BookRecordWhyInvalid(rec).s);
    }
    {
        BookBlobRecord rec;
        MakeSaneRecord(rec);
        rec.hasCover = true;
        rec.cover.kind = kBlobCoverImage;
        rec.cover.format = rec.strings.Append(StrL("png")).s;
        FakeArt(rec.cover.data, "webp", 2048);
        utassert(BookRecordWhyInvalid(rec).s);
    }
    {
        BookBlobRecord rec;
        MakeSaneRecord(rec);
        rec.hasCover = true;
        rec.cover.kind = kBlobCoverImage;
        rec.cover.format = rec.strings.Append(StrL("webp")).s;
        FakeArt(rec.cover.data, "webp", 2048);
        TempStr why = BookRecordWhyInvalid(rec);
        if (why.s) {
            logf("BookRecordCheck: good art was refused: %s\n", why);
        }
        utassert(!why.s);
    }
    {
        BookBlobRecord rec;
        MakeSaneRecord(rec);
        rec.hasCover = true;
        rec.cover.kind = kBlobCoverImage;
        rec.cover.format = rec.strings.Append(StrL("webp")).s;
        for (int i = 0; i < 2048; i++) {
            rec.cover.data.Append((u8)'x');
        }
        utassert(BookRecordWhyInvalid(rec).s);
    }
    {
        BookBlobRecord rec;
        MakeSaneRecord(rec);
        rec.identity.author = rec.strings.Append(Str("\x80\x41", 2)).s;
        utassert(BookRecordWhyInvalid(rec).s);
    }
    {
        BookBlobRecord rec;
        MakeSaneRecord(rec);
        rec.identity.author = rec.strings.Append(Str("\xed\xa0\x80", 3)).s;
        utassert(BookRecordWhyInvalid(rec).s);
    }
    {
        BookBlobRecord rec;
        MakeSaneRecord(rec);
        rec.identity.author = rec.strings.Append(Str("\xc0\xaf", 2)).s;
        utassert(BookRecordWhyInvalid(rec).s);
    }
    {
        BookBlobRecord rec;
        MakeSaneRecord(rec);
        rec.identity.author =
            rec.strings
                .Append(
                    StrL("Derek Molloy \xe2\x80\x94 \xd0\x90\xd0\xbd\xd0\xb4\xd1\x80\xd0\xb5\xd0\xb9 \xf0\x9f\x93\x9a"))
                .s;
        TempStr why = BookRecordWhyInvalid(rec);
        if (why.s) {
            logf("BookRecordCheck: good unicode was refused: %s\n", why);
        }
        utassert(!why.s);
    }

    utassert(str::Eq(Str(BookCoverFormatOfBytes((const u8*)"RIFF\x00\x00\x00\x00WEBPVP8 ", 16)), StrL("webp")));
    utassert(str::Eq(Str(BookCoverFormatOfBytes((const u8*)"\x89PNG\r\n\x1a\n", 8)), StrL("png")));
    utassert(str::Eq(Str(BookCoverFormatOfBytes((const u8*)"\xff\xd8\xff\xe0", 4)), StrL("jpeg")));
    utassert(!BookCoverFormatOfBytes((const u8*)"RIFF\x00\x00\x00\x00WAVE", 12));
    utassert(!BookCoverFormatOfBytes((const u8*)"RIFF", 4));
    utassert(!BookCoverFormatOfBytes(nullptr, 0));
}

static void LibrarySidecar_UnitTests() {
    Str src = StrL("tests/issue-5846.epub");
    TempStr tempBase = GetTempFilePathTemp(StrL("sumatra-record-"));
    Str dst = str::Dup(str::FormatTemp("%s.epub", tempBase));
    file::Delete(tempBase);
    file::Delete(dst);
    utassert(file::Copy(dst, src, false));

    BookFingerprint fp;
    utassert(BookFingerprintOfFile(dst, fp, 0));
    BookBlobRecord rec;
    rec.hasIdentity = true;
    rec.identity.fingerprint = rec.strings.Append(fp.fingerprint).s;
    for (int i = 0; i < 16; i++) {
        rec.identity.textMd5.Append(fp.textMd5[i]);
    }
    rec.identity.textLength = fp.textLength;
    rec.identity.pages = fp.pages;
    rec.identity.title = rec.strings.Append(StrL("Cross-platform record")).s;
    rec.hasShelf = true;
    rec.shelf.series = rec.strings.Append(StrL("Old series")).s;
    BlobChapter chapter{};
    chapter.title = rec.strings.Append(StrL("Kept chapter")).s;
    chapter.page = 7;
    rec.chapters.Append(chapter);
    BlobFact fact{};
    fact.subject = rec.strings.Append(StrL("Kept subject")).s;
    fact.predicate = rec.strings.Append(StrL("is")).s;
    fact.object = rec.strings.Append(StrL("kept")).s;
    fact.confidence = 0.75;
    rec.lore.Append(fact);
    BlobShow show{};
    show.title = rec.strings.Append(StrL("Kept adaptation")).s;
    show.kind = rec.strings.Append(StrL("Film")).s;
    show.year = 2001;
    rec.adaptations.Append(show);
    BookFingerprintFree(fp);

    Str err;
    utassert(LibrarySidecarWriteRecord(dst, rec, &err));
    str::Free(err);
    TempStr sidecar = str::FormatTemp("%s.sumatra", dst);
    utassert(!file::Exists(sidecar));
    BookBlobRecord back;
    utassert(LibrarySidecarReadRecord(dst, back));
    utassert(back.identity.title && str::Eq(Str(back.identity.title), StrL("Cross-platform record")));

    BlobStats stats{};
    stats.lastReadAt = 123456789;
    stats.timeSpentMs = 987654;
    stats.openCount = 12;
    stats.pageNo = 4;
    stats.percentRead = 60;
    utassert(LibrarySidecarWriteMetadata(dst, StrL("Moved title"), StrL("Moved author"), StrL("Moved series"),
                                         StrL("Moved parent"), StrL("Fantasy"), StrL("Portal"), StrL("one;two"),
                                         StrL("a;b"), 3, 1998, 42, &stats));
    BookBlobRecord moved;
    utassert(LibrarySidecarReadRecord(dst, moved));
    utassert(str::Eq(Str(moved.identity.title), StrL("Moved title")));
    utassert(str::Eq(Str(moved.identity.author), StrL("Moved author")));
    utassert(moved.identity.year == 1998);
    utassert(moved.identity.pages == 42);
    utassert(str::Eq(Str(moved.shelf.series), StrL("Moved series")));
    utassert(str::Eq(Str(moved.shelf.seriesParent), StrL("Moved parent")));
    utassert(str::Eq(Str(moved.shelf.genre), StrL("Fantasy")));
    utassert(str::Eq(Str(moved.shelf.subgenre), StrL("Portal")));
    utassert(moved.shelf.seriesIndex == 3);
    utassert(moved.shelf.partitions.len == 2 && str::Eq(Str(moved.shelf.partitions[0]), StrL("a")) &&
             str::Eq(Str(moved.shelf.partitions[1]), StrL("b")));
    utassert(moved.shelf.tags.len == 2 && str::Eq(Str(moved.shelf.tags[0]), StrL("one")) &&
             str::Eq(Str(moved.shelf.tags[1]), StrL("two")));
    utassert(moved.hasStats && moved.stats.timeSpentMs == stats.timeSpentMs);
    utassert(moved.chapters.len == 1 && str::Eq(Str(moved.chapters[0].title), StrL("Kept chapter")));
    utassert(moved.lore.len == 1 && str::Eq(Str(moved.lore[0].subject), StrL("Kept subject")));
    utassert(moved.adaptations.len == 1 && str::Eq(Str(moved.adaptations[0].title), StrL("Kept adaptation")));
    Str unchanged = file::ReadFile(dst);
    utassert(LibrarySidecarWriteMetadata(dst, StrL("Moved title"), StrL("Moved author"), StrL("Moved series"),
                                         StrL("Moved parent"), StrL("Fantasy"), StrL("Portal"), StrL("one;two"),
                                         StrL("a;b"), 3, 1998, 42, &stats));
    Str after = file::ReadFile(dst);
    utassert(str::Eq(unchanged, after));
    str::Free(after);

    // chunk 30: the WithRec overload must produce the same on-disk result
    // as the no-cache overload, and must NOT re-read the file when the
    // caller already has a fresh record. The counter check is the proof
    // that the read was actually skipped: we capture the read count, call
    // WriteMetadataWithRec with a record the caller "already has" (the
    // one we just read above), and assert the counter did not move.
    BookBlobRecord preCached;
    utassert(LibrarySidecarReadRecord(dst, preCached));
    int readsBefore = 0, writesBefore = 0;
    LibrarySidecarPerfCounters(&readsBefore, &writesBefore);
    utassert(LibrarySidecarWriteMetadataWithRec(dst, &preCached, StrL("Moved title"), StrL("Moved author"),
                                                StrL("Moved series"), StrL("Moved parent"), StrL("Fantasy"),
                                                StrL("Portal"), StrL("one;two"), StrL("a;b"), 3, 1998, 42, &stats));
    int readsAfter = 0, writesAfter = 0;
    LibrarySidecarPerfCounters(&readsAfter, &writesAfter);
    utassert(readsAfter == readsBefore);
    utassert(writesAfter == writesBefore);
    // Round-trip the no-cache path against the same data to confirm both
    // overloads produce the same on-disk record.
    Str after2 = file::ReadFile(dst);
    utassert(str::Eq(unchanged, after2));
    str::Free(after2);

    // Now change a field through the WithRec overload and confirm the
    // change is written exactly once (no double-write, no skipped write).
    utassert(LibrarySidecarWriteMetadataWithRec(dst, &preCached, StrL("Final title"), StrL("Moved author"),
                                                StrL("Moved series"), StrL("Moved parent"), StrL("Fantasy"),
                                                StrL("Portal"), StrL("one;two"), StrL("a;b"), 3, 1998, 42, &stats));
    BookBlobRecord finalRec;
    utassert(LibrarySidecarReadRecord(dst, finalRec));
    utassert(str::Eq(Str(finalRec.identity.title), StrL("Final title")));

    str::Free(unchanged);
    file::Delete(sidecar);
    file::Delete(dst);
    str::Free(dst);
}

static void ChurnHeap() {
    for (int i = 0; i < 120; i++) {
        StrVec v;
        for (int j = 0; j < 48; j++) {
            v.Append(StrL("filler text that reuses whatever StrVec pages were just freed"));
        }
    }
}

static void SeedLifetimeRecord(Str bookPath) {
    BookFingerprint fp;
    utassert(BookFingerprintOfFile(bookPath, fp, 0));
    BookBlobRecord seed;
    seed.hasIdentity = true;
    seed.identity.fingerprint = seed.strings.Append(fp.fingerprint).s;
    for (int i = 0; i < 16; i++) {
        seed.identity.textMd5.Append(fp.textMd5[i]);
    }
    seed.identity.textLength = fp.textLength;
    seed.identity.pages = fp.pages;
    BookFingerprintFree(fp);
    seed.identity.title = seed.strings.Append(StrL("Lifetime title")).s;
    seed.identity.author = seed.strings.Append(StrL("Lifetime author")).s;
    seed.identity.year = 1997;
    seed.hasShelf = true;
    seed.shelf.series = seed.strings.Append(StrL("Lifetime series")).s;
    seed.shelf.seriesParent = seed.strings.Append(StrL("Lifetime parent")).s;
    seed.shelf.genre = seed.strings.Append(StrL("Lifetime genre")).s;
    seed.shelf.subgenre = seed.strings.Append(StrL("Lifetime subgenre")).s;
    seed.shelf.seriesIndex = 3;
    seed.shelf.tags.Append(seed.strings.Append(StrL("one")).s);
    seed.shelf.tags.Append(seed.strings.Append(StrL("two")).s);
    seed.shelf.partitions.Append(seed.strings.Append(StrL("a")).s);
    seed.shelf.partitions.Append(seed.strings.Append(StrL("b")).s);
    seed.hasCover = true;
    seed.cover.kind = kBlobCoverImage;
    seed.cover.format = seed.strings.Append(StrL("webp")).s;
    FakeArt(seed.cover.data, "webp", 4096);
    BlobChapter chapter{};
    chapter.title = seed.strings.Append(StrL("Lifetime chapter")).s;
    chapter.page = 5;
    seed.chapters.Append(chapter);
    utassert(!BookRecordForeignString(seed));
    Str err;
    utassert(LibrarySidecarWriteRecord(bookPath, seed, &err));
    str::Free(err);
}

static void ReadThenDropSource(Str bookPath, BookBlobRecord& out) {
    BookBlobRecord* onDisk = new BookBlobRecord();
    utassert(LibrarySidecarReadRecord(bookPath, *onDisk));
    utassert(!BookRecordForeignString(*onDisk));
    BookBlobRecordClone(*onDisk, out);
    delete onDisk;
    ChurnHeap();
}

static void BookBlobLifetime_UnitTests() {
    Str src = StrL("tests/issue-5846.epub");
    TempStr tempBase = GetTempFilePathTemp(StrL("sumatra-lifetime-"));
    Str dst = str::Dup(str::FormatTemp("%s.epub", tempBase));
    file::Delete(tempBase);
    file::Delete(dst);
    utassert(file::Copy(dst, src, false));
    SeedLifetimeRecord(dst);

    BookBlobRecord clone;
    ReadThenDropSource(dst, clone);
    Str which;
    const char* foreign = BookRecordForeignString(clone, &which);
    if (foreign) {
        logf("BookBlobLifetime: %s does not point into the clone's own strings\n", which);
    }
    utassert(!foreign);
    utassert(str::Eq(Str(clone.identity.title), StrL("Lifetime title")));
    utassert(str::Eq(Str(clone.identity.author), StrL("Lifetime author")));
    utassert(clone.identity.year == 1997);
    utassert(str::Eq(Str(clone.shelf.series), StrL("Lifetime series")));
    utassert(str::Eq(Str(clone.shelf.seriesParent), StrL("Lifetime parent")));
    utassert(str::Eq(Str(clone.shelf.genre), StrL("Lifetime genre")));
    utassert(str::Eq(Str(clone.shelf.subgenre), StrL("Lifetime subgenre")));
    utassert(clone.shelf.seriesIndex == 3);
    utassert(clone.shelf.tags.len == 2 && str::Eq(Str(clone.shelf.tags[0]), StrL("one")) &&
             str::Eq(Str(clone.shelf.tags[1]), StrL("two")));
    utassert(clone.shelf.partitions.len == 2 && str::Eq(Str(clone.shelf.partitions[0]), StrL("a")) &&
             str::Eq(Str(clone.shelf.partitions[1]), StrL("b")));
    utassert(clone.chapters.len == 1 && str::Eq(Str(clone.chapters[0].title), StrL("Lifetime chapter")));
    utassert(clone.hasCover && clone.cover.data.len == 4096);
    utassert(str::Eq(Str(clone.cover.format), StrL("webp")));
    utassert(clone.hasIdentity && clone.identity.fingerprint && *clone.identity.fingerprint);
    TempStr why = BookRecordWhyInvalid(clone);
    if (why.s) {
        logf("BookBlobLifetime: a cloned record was refused: %s\n", why);
    }
    utassert(!why.s);

    for (int round = 0; round < 40; round++) {
        BookBlobRecord cached;
        ReadThenDropSource(dst, cached);
        utassert(!BookRecordForeignString(cached));
        utassert(cached.hasCover && str::Eq(Str(cached.cover.format), StrL("webp")));
        TempStr bad = BookRecordWhyInvalid(cached);
        if (bad.s) {
            logf("BookBlobLifetime: round %d refused a cached record: %s\n", round, bad);
        }
        utassert(!bad.s);
        int readsPre = 0, writesPre = 0;
        LibrarySidecarPerfCounters(&readsPre, &writesPre);
        utassert(LibrarySidecarWriteMetadataWithRec(dst, &cached, StrL("Lifetime title"), StrL("Lifetime author"),
                                                    StrL("Lifetime series"), StrL("Lifetime parent"),
                                                    StrL("Lifetime genre"), StrL("Lifetime subgenre"), StrL("one;two"),
                                                    StrL("a;b"), 3, 1997, cached.identity.pages, nullptr));
        int readsPost = 0, writesPost = 0;
        LibrarySidecarPerfCounters(&readsPost, &writesPost);
        utassert(readsPost == readsPre);
        utassert(writesPost == writesPre);
    }

    BookBlobRecord cachedForWrite;
    ReadThenDropSource(dst, cachedForWrite);
    int readsPre = 0, writesPre = 0;
    LibrarySidecarPerfCounters(&readsPre, &writesPre);
    utassert(LibrarySidecarWriteMetadataWithRec(dst, &cachedForWrite, StrL("Changed title"), StrL("Lifetime author"),
                                                StrL("Lifetime series"), StrL("Lifetime parent"),
                                                StrL("Lifetime genre"), StrL("Lifetime subgenre"), StrL("one;two"),
                                                StrL("a;b"), 3, 1997, cachedForWrite.identity.pages, nullptr));
    int readsPost = 0, writesPost = 0;
    LibrarySidecarPerfCounters(&readsPost, &writesPost);
    utassert(readsPost == readsPre);
    utassert(writesPost == writesPre + 1);
    ChurnHeap();
    BookBlobRecord back;
    utassert(LibrarySidecarReadRecord(dst, back));
    utassert(!BookRecordForeignString(back));
    utassert(str::Eq(Str(back.identity.title), StrL("Changed title")));
    utassert(str::Eq(Str(back.identity.author), StrL("Lifetime author")));
    utassert(back.hasCover && back.cover.data.len == 4096);
    utassert(str::Eq(Str(back.cover.format), StrL("webp")));
    utassert(back.chapters.len == 1 && str::Eq(Str(back.chapters[0].title), StrL("Lifetime chapter")));
    utassert(back.shelf.tags.len == 2 && back.shelf.partitions.len == 2);

    file::Delete(dst);
    str::Free(dst);
}

static void RoamingBook_UnitTests() {
    TempStr path = EnvVarTemp("SUMATRA_ROAMING_BOOK");
    if (!path) {
        return;
    }
    BookBlobRecord rec;
    utassert(LibrarySidecarReadRecord(path, rec));
    utassert(rec.hasIdentity);
    utassert(rec.identity.fingerprint);
    utassert(str::Eq(Str(rec.identity.title), StrL("The Exposed")));
    utassert(str::Eq(Str(rec.identity.author), StrL("Katherine Applegate")));
    utassert(rec.identity.source);
    utassert(rec.identity.pages > 0);
    utassert(rec.identity.year == 1999);
    utassert(rec.hasShelf);
    utassert(str::Eq(Str(rec.shelf.series), StrL("Animorphs")));
    utassert(rec.hasCover);
    utassert(str::Eq(Str(rec.cover.format), StrL("webp")));
    utassert(rec.cover.data.len >= kBookCoverMinBytes);
    utassert(str::Eq(Str(BookCoverFormatOfBytes(rec.cover.data.LendData(), rec.cover.data.len)), StrL("webp")));
}

static void SidecarDump_UnitTests() {
    TempStr path = EnvVarTemp("SUMATRA_SIDECAR_DUMP");
    TempStr outPath = EnvVarTemp("SUMATRA_SIDECAR_DUMP_OUT");
    if (!path || !outPath) {
        return;
    }
    BookBlobRecord rec;
    utassert(LibrarySidecarReadRecord(path, rec));
    str::Builder out;
    out.Append("title\t");
    if (rec.hasIdentity && rec.identity.title) {
        out.Append(Str(rec.identity.title));
    }
    out.Append("\nauthor\t");
    if (rec.hasIdentity && rec.identity.author) {
        out.Append(Str(rec.identity.author));
    }
    out.Append("\nseries\t");
    if (rec.hasShelf && rec.shelf.series) {
        out.Append(Str(rec.shelf.series));
    }
    out.Append(str::FormatTemp("\nyear\t%d\n", rec.hasIdentity ? rec.identity.year : 0));
    utassert(file::WriteFile(outPath, Str(out.els, (int)out.len)));
}

static void InteropBook_UnitTests() {
    TempStr path = EnvVarTemp("SUMATRA_INTEROP_BOOK");
    if (!path) {
        return;
    }
    BookBlobRecord rec;
    utassert(LibrarySidecarReadRecord(path, rec));
    utassert(rec.hasIdentity);
    utassert(rec.identity.fingerprint);
    utassert(str::Eq(Str(rec.identity.title), StrL("Transferred Title")));
    utassert(str::Eq(Str(rec.identity.author), StrL("Transferred Author")));
    utassert(rec.identity.pages == 777);
    utassert(rec.identity.year == 1998);
    utassert(rec.hasShelf);
    utassert(str::Eq(Str(rec.shelf.genre), StrL("Fantasy")));
    utassert(str::Eq(Str(rec.shelf.subgenre), StrL("Epic")));
    utassert(str::Eq(Str(rec.shelf.series), StrL("The Roaming Cycle")));
    utassert(str::Eq(Str(rec.shelf.seriesParent), StrL("The Roaming Library")));
    utassert(rec.shelf.seriesIndex == 2);
    utassert(rec.shelf.partitions.len == 2);
    utassert(str::Eq(Str(rec.shelf.partitions[0]), StrL("partition:a")));
    utassert(str::Eq(Str(rec.shelf.partitions[1]), StrL("partition:b")));
    utassert(rec.shelf.tags.len == 2);
    utassert(str::Eq(Str(rec.shelf.tags[0]), StrL("one")));
    utassert(str::Eq(Str(rec.shelf.tags[1]), StrL("two")));
    utassert(rec.hasCover);
    utassert(rec.cover.kind == kBlobCoverImage);
    utassert(rec.cover.data.len >= kBookCoverMinBytes);
    utassert(BookCoverFormatOfBytes(rec.cover.data.LendData(), rec.cover.data.len));
    utassert(rec.hasCast);
    utassert(str::Eq(Str(rec.cast.narratorVoice), StrL("Narrator")));
    utassert(rec.cast.people.len == 1);
    utassert(str::Eq(Str(rec.cast.people[0].name), StrL("Marra Venn")));
    utassert(rec.cast.people[0].lines == 42);
    utassert(str::Eq(Str(rec.cast.people[0].voice), StrL("low burr")));
    utassert(rec.cast.people[0].aliasCount == 1);
    utassert(str::Eq(Str(rec.cast.aliases[rec.cast.people[0].aliasAt]), StrL("Marra")));
    utassert(rec.speakers.len == 1);
    utassert(rec.speakers[0].start == 10 && rec.speakers[0].end == 20);
    utassert(rec.speakers[0].mentionStart == 11 && rec.speakers[0].mentionEnd == 12);
    utassert(rec.speakers[0].character == 0);
    utassert(rec.entities.len == 1);
    utassert(rec.entities[0].start == 30 && rec.entities[0].end == 40 && rec.entities[0].coref == 0);
    utassert(str::Eq(Str(rec.entities[0].prop), StrL("proper")));
    utassert(str::Eq(Str(rec.entities[0].cat), StrL("PER")));
    utassert(rec.lore.len == 2);
    utassert(str::Eq(Str(rec.lore[0].subject), StrL("Marra Venn")));
    utassert(str::Eq(Str(rec.lore[0].predicate), StrL("voice")));
    utassert(str::Eq(Str(rec.lore[0].object), StrL("low burr")));
    utassert(rec.lore[0].confidence == 0.82 && rec.lore[0].count == 4 && !rec.lore[0].inferred);
    utassert(rec.lore[0].evidenceCount == 1);
    const BlobEvidence& evidence = rec.evidence[rec.lore[0].evidenceAt];
    utassert(evidence.offset == 1234 && evidence.page == 31 && evidence.para == 2 && !evidence.note);
    utassert(rec.lore[1].evidenceCount == 1);
    const BlobEvidence& derived = rec.evidence[rec.lore[1].evidenceAt];
    utassert(derived.offset < 0 && str::Eq(Str(derived.note), StrL("Derived relationship evidence")));
    utassert(rec.chapters.len == 3);
    utassert(str::Eq(Str(rec.chapters[0].title), StrL("Chapter One")) && rec.chapters[0].depth == 0);
    utassert(str::Eq(Str(rec.chapters[1].title), StrL("A Turn")) && rec.chapters[1].depth == 1);
    utassert(str::Eq(Str(rec.chapters[2].title), StrL("Chapter Two")) && rec.chapters[2].page == 7);
    utassert(rec.adaptations.len == 1);
    utassert(str::Eq(Str(rec.adaptations[0].title), StrL("The Roaming Cycle")));
    utassert(str::Eq(Str(rec.adaptations[0].kind), StrL("TV Series")));
    utassert(rec.adaptations[0].year == 1999);
    utassert(str::Eq(Str(rec.adaptations[0].ref), StrL("tt0000001")));
    utassert(rec.hasStats);
    utassert(rec.stats.lastReadAt == 1700000000000LL);
    utassert(rec.stats.timeSpentMs == 90000 && rec.stats.openCount == 3 && rec.stats.pageNo == 12);
    utassert(rec.stats.scrollX == 4 && rec.stats.scrollY == 5 && rec.stats.percentRead == 16 && rec.stats.unit == 1);

    TempStr output = EnvVarTemp("SUMATRA_INTEROP_OUTPUT");
    if (!output) {
        return;
    }
    file::Delete(output);
    utassert(file::Copy(output, path, false));
    rec.identity.title = rec.strings.Append(StrL("Windows Title")).s;
    rec.identity.author = rec.strings.Append(StrL("Windows Author")).s;
    rec.identity.pages = 888;
    rec.identity.year = 2005;
    rec.shelf.genre = rec.strings.Append(StrL("Science Fiction")).s;
    rec.shelf.subgenre = rec.strings.Append(StrL("Space Opera")).s;
    rec.shelf.series = rec.strings.Append(StrL("Windows Cycle")).s;
    rec.shelf.seriesParent = rec.strings.Append(StrL("Windows Library")).s;
    rec.shelf.seriesIndex = 6;
    rec.shelf.partitions.Clear();
    rec.shelf.partitions.Append(rec.strings.Append(StrL("partition:w1")).s);
    rec.shelf.partitions.Append(rec.strings.Append(StrL("partition:w2")).s);
    rec.shelf.tags.Clear();
    rec.shelf.tags.Append(rec.strings.Append(StrL("windows-one")).s);
    rec.shelf.tags.Append(rec.strings.Append(StrL("windows-two")).s);
    rec.cast.narratorVoice = rec.strings.Append(StrL("Windows Narrator")).s;
    rec.cast.people[0].name = rec.strings.Append(StrL("Dana Holt")).s;
    rec.cast.people[0].lines = 84;
    rec.cast.people[0].voice = rec.strings.Append(StrL("clear tenor")).s;
    rec.cast.aliases[rec.cast.people[0].aliasAt] = rec.strings.Append(StrL("Dana")).s;
    rec.speakers[0] = {21, 31, 22, 23, 0};
    rec.entities[0] = {41, 51, 0, rec.strings.Append(StrL("name")).s, rec.strings.Append(StrL("PERSON")).s};
    rec.lore[0].subject = rec.strings.Append(StrL("Dana Holt")).s;
    rec.lore[0].predicate = rec.strings.Append(StrL("voice")).s;
    rec.lore[0].object = rec.strings.Append(StrL("clear tenor")).s;
    rec.lore[0].confidence = 0.91;
    rec.lore[0].count = 8;
    rec.evidence[rec.lore[0].evidenceAt].offset = 2345;
    rec.evidence[rec.lore[0].evidenceAt].page = 44;
    rec.evidence[rec.lore[0].evidenceAt].para = 3;
    rec.evidence[rec.lore[1].evidenceAt].note = rec.strings.Append(StrL("Windows derived evidence")).s;
    rec.chapters[0].title = rec.strings.Append(StrL("Windows Chapter")).s;
    rec.chapters[0].page = 2;
    rec.adaptations[0].title = rec.strings.Append(StrL("Windows Cycle")).s;
    rec.adaptations[0].kind = rec.strings.Append(StrL("Film")).s;
    rec.adaptations[0].year = 2006;
    rec.adaptations[0].ref = rec.strings.Append(StrL("tt9999999")).s;
    rec.stats = {1800000000000LL, 180000, 7, 22, 8, 9, 25, 2};
    Str err;
    utassert(LibrarySidecarWriteRecord(output, rec, &err));
    str::Free(err);
}

int RunAppUnitTests() {
    ParseTip_UnitTests();
    BookBlob_UnitTests();
    CoverSpot_UnitTests();
    BookRecordCheck_UnitTests();
    LibrarySidecar_UnitTests();
    BookBlobLifetime_UnitTests();
    RoamingBook_UnitTests();
    SidecarDump_UnitTests();
    InteropBook_UnitTests();
    BookFingerprint_UnitTests();
    MobiCover_UnitTests();
    NativeCover_UnitTests();
    BuiltCover_UnitTests();
    AutomaticImage_UnitTests();
    PdfSidecar_UnitTests();
    PdfSidecarCover_UnitTests();
    PdfSidecarForeign_UnitTests();
    CoverOnline_UnitTests();
#if defined(DEBUG)
    TextSelection_UnitTests();
    Layout_UnitTests();
#if OS_WIN
    LayoutWin_UnitTests();
#endif
    VirtCtrl_UnitTests();
    utassert(TableOfContents_UnitTestSnapshotNamedDest());
    utassert(MarkdownModel_UnitTestBrowserNavigationUrl());
    utassert(MarkdownToc_UnitTestHtmlLinks());
    utassert(MarkdownToc_UnitTestHtmlHeadings());
    utassert(MarkdownToc_UnitTestMermaid());
    utassert(EbookDoc_UnitTestNormalizeURL());
    utassert(ExternalViewers_UnitTestPDFXChangePaths());
    utassert(Canvas_UnitTestScrollLineAmount());
    utassert(EngineMupdf_UnitTestEbookLineSpacingCss());
    utassert(EngineMupdf_UnitTestEbookFontFamilyCss());
    utassert(EngineMupdf_UnitTestEbookMarginCss());
    utassert(EngineMupdf_UnitTestMergeEBookUI());
#endif
    return utassert_print_results();
}

#endif
