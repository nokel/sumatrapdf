/* Copyright 2026 the SumatraPDF project authors (see AUTHORS file).
   License: Simplified BSD (see COPYING.BSD) */

#include "base/Base.h"
#include "base/Pixmap.h"
#include "base/GuessFileType.h"

#include "WebpReader.h"
#include "WebpWriter.h"

#include "base/UtAssert.h"

static void Fill(Pixmap* px, u8 r, u8 g, u8 b, u8 a) {
    for (int y = 0; y < px->height; y++) {
        u8* row = px->data + (size_t)y * (size_t)px->stride;
        for (int x = 0; x < px->width; x++) {
            u8* p = row + (size_t)x * 4;
            p[0] = b;
            p[1] = g;
            p[2] = r;
            p[3] = a;
        }
    }
}

static void EncodesRecognisableWebp() {
    Pixmap* px = AllocPixmap(64, 48);
    utassert(px != nullptr);
    Fill(px, 200, 40, 40, 255);

    Str data = webp::EncodeFromPixmap(px);
    utassert(len(data) > 0);
    utassert(GuessFileTypeFromData(data) == FileType::Webp);

    str::Free(data);
    FreePixmap(px);
}

static void QualityChangesSize() {
    Pixmap* px = AllocPixmap(96, 96);
    utassert(px != nullptr);
    for (int y = 0; y < px->height; y++) {
        u8* row = px->data + (size_t)y * (size_t)px->stride;
        for (int x = 0; x < px->width; x++) {
            u8* p = row + (size_t)x * 4;
            p[0] = (u8)(x * 3);
            p[1] = (u8)(y * 5);
            p[2] = (u8)((x ^ y) * 7);
            p[3] = 255;
        }
    }
    Str low = webp::EncodeFromPixmap(px, 5);
    Str high = webp::EncodeFromPixmap(px, 100);
    utassert(len(low) > 0);
    utassert(len(high) > 0);
    utassert(len(low) < len(high));
    str::Free(low);
    str::Free(high);
    FreePixmap(px);
}

static void OpaquePixmapKeepsItsColour() {
    Pixmap* px = AllocPixmap(32, 32);
    utassert(px != nullptr);
    Fill(px, 10, 220, 30, 0);
    px->hasAlpha = false;

    Str data = webp::EncodeFromPixmap(px, 100);
    utassert(len(data) > 0);

    Pixmap* back = webp::PixmapFromData(data);
    utassert(back != nullptr);
    utassert(back->width == 32 && back->height == 32);
    u8* p = back->data;
    utassert(p[0] > 200 || p[1] > 200 || p[2] > 200);

    FreePixmap(back);
    str::Free(data);
    FreePixmap(px);
}

static void PremultipliedAlphaIsUndone() {
    Pixmap* px = AllocPixmap(16, 16);
    utassert(px != nullptr);
    Fill(px, 100, 50, 25, 128);
    px->hasAlpha = true;
    px->premultiplied = true;

    Str data = webp::EncodeFromPixmap(px, 100);
    utassert(len(data) > 0);

    Pixmap* back = webp::PixmapFromData(data);
    utassert(back != nullptr);
    u8* p = back->data;
    utassert(p[2] > 150);
    utassert(p[3] > 100 && p[3] < 160);

    FreePixmap(back);
    str::Free(data);
    FreePixmap(px);
}

static void RejectsWhatItCannotEncode() {
    utassert(len(webp::EncodeFromPixmap(nullptr)) == 0);

    Pixmap* px = AllocPixmap(8, 8);
    utassert(px != nullptr);
    px->format = PixmapFormat::Native;
    utassert(len(webp::EncodeFromPixmap(px)) == 0);
    px->format = PixmapFormat::BGRA8;

    int savedWidth = px->width;
    px->width = 0;
    utassert(len(webp::EncodeFromPixmap(px)) == 0);
    px->width = savedWidth;

    Str ok = webp::EncodeFromPixmap(px, -50);
    utassert(len(ok) > 0);
    str::Free(ok);

    FreePixmap(px);
}

void WebpWriterTest() {
    utassert(webp::CanEncode());
    EncodesRecognisableWebp();
    QualityChangesSize();
    OpaquePixmapKeepsItsColour();
    PremultipliedAlphaIsUndone();
    RejectsWhatItCannotEncode();
}
