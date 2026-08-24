/* Copyright 2026 the SumatraPDF project authors (see AUTHORS file).
   License: Simplified BSD (see COPYING.BSD) */

struct Pixmap;

namespace webp {

constexpr int kWebpDefaultQuality = 90;

bool CanEncode();
Str EncodeFromPixmap(const Pixmap* px, int quality = kWebpDefaultQuality);

}
