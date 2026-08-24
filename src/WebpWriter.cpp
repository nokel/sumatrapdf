/* Copyright 2026 the SumatraPDF project authors (see AUTHORS file).
   License: Simplified BSD (see COPYING.BSD) */

#include "base/Base.h"
#include "base/Pixmap.h"

#include "WebpWriter.h"

#ifndef NO_LIBWEBP

#include <webp/encode.h>

namespace webp {

bool CanEncode() {
    return true;
}

static u8 Unpremultiply(u8 v, u8 a) {
    if (a == 0) {
        return 0;
    }
    if (a == 255) {
        return v;
    }
    return (u8)std::min(255, (v * 255 + a / 2) / a);
}

static u8* PackForEncode(const Pixmap* px, bool withAlpha) {
    int bpp = withAlpha ? 4 : 3;
    u8* out = AllocArray<u8>((size_t)px->width * (size_t)px->height * (size_t)bpp);
    if (!out) {
        return nullptr;
    }
    bool rgba = px->format == PixmapFormat::RGBA8;
    int srcBpp = px->format == PixmapFormat::BGR8 ? 3 : 4;
    bool undo = withAlpha && px->premultiplied;
    for (int y = 0; y < px->height; y++) {
        const u8* src = px->data + (size_t)y * (size_t)px->stride;
        u8* dst = out + (size_t)y * (size_t)px->width * (size_t)bpp;
        for (int x = 0; x < px->width; x++) {
            const u8* s = src + (size_t)x * (size_t)srcBpp;
            u8* d = dst + (size_t)x * (size_t)bpp;
            u8 b = rgba ? s[2] : s[0];
            u8 g = s[1];
            u8 r = rgba ? s[0] : s[2];
            u8 a = srcBpp == 4 ? s[3] : 255;
            if (undo) {
                b = Unpremultiply(b, a);
                g = Unpremultiply(g, a);
                r = Unpremultiply(r, a);
            }
            d[0] = b;
            d[1] = g;
            d[2] = r;
            if (withAlpha) {
                d[3] = a;
            }
        }
    }
    return out;
}

Str EncodeFromPixmap(const Pixmap* px, int quality) {
    if (!px || !px->data || px->width <= 0 || px->height <= 0) {
        return {};
    }
    if (px->width > WEBP_MAX_DIMENSION || px->height > WEBP_MAX_DIMENSION) {
        return {};
    }
    if (px->format == PixmapFormat::Native) {
        return {};
    }
    float q = (float)limitValue(quality, 1, 100);
    bool withAlpha = px->hasAlpha && px->format != PixmapFormat::BGR8;

    u8* packed = PackForEncode(px, withAlpha);
    if (!packed) {
        return {};
    }
    int stride = px->width * (withAlpha ? 4 : 3);
    u8* enc = nullptr;
    size_t n = withAlpha ? WebPEncodeBGRA(packed, px->width, px->height, stride, q, &enc)
                         : WebPEncodeBGR(packed, px->width, px->height, stride, q, &enc);
    free(packed);
    if (!enc) {
        return {};
    }
    Str res;
    if (n > 0) {
        res = str::Dup(Str((const char*)enc, (int)n));
    }
    WebPFree(enc);
    return res;
}

}

#else

namespace webp {
bool CanEncode() {
    return false;
}
Str EncodeFromPixmap(const Pixmap*, int) {
    return {};
}
}

#endif
