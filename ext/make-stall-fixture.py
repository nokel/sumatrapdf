"""Build a large fixture that reproduces the 125/381 stall."""
import os
import sys
import shutil
import fitz


def make_digital_pdf(path, pages_text, page_w=850, page_h=1100):
    doc = fitz.open()
    for body in pages_text:
        page = doc.new_page(width=page_w, height=page_h)
        for i, ln in enumerate(body):
            page.insert_text((72, 110 + i * 30), ln, fontsize=14)
    doc.save(path)
    doc.close()


def make_image_only_pdf(path, page_text, page_w=850, page_h=1100):
    """Render text into a PNG via GDI and embed as a single image-only page."""
    import ctypes
    import struct
    import zlib
    import tempfile

    user32 = ctypes.windll.user32
    gdi32 = ctypes.windll.gdi32

    class LOGFONTW(ctypes.Structure):
        _fields_ = [
            ("lfHeight", ctypes.c_long), ("lfWidth", ctypes.c_long),
            ("lfEscapement", ctypes.c_long), ("lfOrientation", ctypes.c_long),
            ("lfWeight", ctypes.c_long), ("lfItalic", ctypes.c_byte),
            ("lfUnderline", ctypes.c_byte), ("lfStrikeOut", ctypes.c_byte),
            ("lfCharSet", ctypes.c_byte), ("lfOutPrecision", ctypes.c_byte),
            ("lfClipPrecision", ctypes.c_byte), ("lfQuality", ctypes.c_byte),
            ("lfPitchAndFamily", ctypes.c_byte), ("lfFaceName", ctypes.c_wchar * 32),
        ]

    class RECT(ctypes.Structure):
        _fields_ = [("left", ctypes.c_long), ("top", ctypes.c_long),
                    ("right", ctypes.c_long), ("bottom", ctypes.c_long)]

    class BITMAPINFOHEADER(ctypes.Structure):
        _fields_ = [
            ("biSize", ctypes.c_uint32), ("biWidth", ctypes.c_int32),
            ("biHeight", ctypes.c_int32), ("biPlanes", ctypes.c_uint16),
            ("biBitCount", ctypes.c_uint16), ("biCompression", ctypes.c_uint32),
            ("biSizeImage", ctypes.c_uint32), ("biXPelsPerMeter", ctypes.c_int32),
            ("biYPelsPerMeter", ctypes.c_int32), ("biClrUsed", ctypes.c_uint32),
            ("biClrImportant", ctypes.c_uint32),
        ]

    def chunk(tag, data):
        crc = zlib.crc32(tag + data) & 0xffffffff
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", crc)

    text = page_text
    if isinstance(text, (list, tuple)):
        text = " ".join(text)
    text = str(text)

    line_h = 200
    logfont = LOGFONTW()
    logfont.lfHeight = -32
    logfont.lfWeight = 400
    logfont.lfCharSet = 1
    logfont.lfOutPrecision = 8
    logfont.lfClipPrecision = 0
    logfont.lfQuality = 5
    logfont.lfPitchAndFamily = 32
    logfont.lfFaceName = "Times New Roman"
    font = gdi32.CreateFontIndirectW(ctypes.byref(logfont))
    screen = user32.GetDC(None)
    mem = gdi32.CreateCompatibleDC(screen)
    bmp = gdi32.CreateCompatibleBitmap(screen, page_w, line_h)
    old_bmp = gdi32.SelectObject(mem, bmp)
    old_font = gdi32.SelectObject(mem, font)
    r = RECT(0, 0, page_w, line_h)
    r_addr = ctypes.addressof(r)
    user32.FillRect.restype = ctypes.c_int
    user32.FillRect.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p]
    user32.FillRect(mem, r_addr, 0)
    gdi32.SetBkMode(mem, 1)
    gdi32.SetTextColor(mem, 0x000000)
    user32.DrawTextA.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int, ctypes.c_void_p, ctypes.c_uint]
    user32.DrawTextA(mem, text.encode("ascii"), -1, r_addr, 0 | 4)
    bi = BITMAPINFOHEADER()
    bi.biSize = ctypes.sizeof(BITMAPINFOHEADER)
    bi.biWidth = page_w
    bi.biHeight = -line_h
    bi.biPlanes = 1
    bi.biBitCount = 32
    bi.biCompression = 0
    row_bytes = page_w * 4
    pixels = (ctypes.c_ubyte * (row_bytes * line_h))()
    gdi32.GetDIBits(mem, bmp, 0, line_h, ctypes.byref(pixels), ctypes.byref(bi), 0)
    gdi32.SelectObject(mem, old_bmp)
    gdi32.SelectObject(mem, old_font)
    gdi32.DeleteObject(font)
    gdi32.DeleteObject(bmp)
    gdi32.DeleteDC(mem)
    user32.ReleaseDC(None, screen)

    gray = bytearray(page_w * line_h)
    for y in range(line_h):
        for x in range(page_w):
            r = pixels[y * row_bytes + x * 4 + 2]
            g = pixels[y * row_bytes + x * 4 + 1]
            b = pixels[y * row_bytes + x * 4 + 0]
            gray[y * page_w + x] = (r * 299 + g * 587 + b * 114) // 1000
    ihdr = struct.pack(">IIBBBBB", page_w, line_h, 8, 0, 0, 0, 0)
    png_rows = b""
    for y in range(line_h):
        png_rows += bytes([0]) + bytes(gray[y * page_w:(y + 1) * page_w])
    png_bytes = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(png_rows))
        + chunk(b"IEND", b"")
    )

    doc = fitz.open()
    page = doc.new_page(width=page_w, height=page_h)
    tmp = tempfile.NamedTemporaryFile(suffix=".png", delete=False)
    tmp.write(png_bytes)
    tmp.close()
    page.insert_image(fitz.Rect(0, 80, page_w, 80 + line_h), filename=tmp.name, keep_proportion=False)
    os.unlink(tmp.name)
    doc.save(path)
    doc.close()


def make_long_image_only(path, page_text, page_count=500, page_w=850, page_h=1100):
    """Render the same text into many image-only pages — the stall reproducer."""
    import ctypes
    import struct
    import zlib
    import tempfile

    user32 = ctypes.windll.user32
    gdi32 = ctypes.windll.gdi32

    class LOGFONTW(ctypes.Structure):
        _fields_ = [
            ("lfHeight", ctypes.c_long), ("lfWidth", ctypes.c_long),
            ("lfEscapement", ctypes.c_long), ("lfOrientation", ctypes.c_long),
            ("lfWeight", ctypes.c_long), ("lfItalic", ctypes.c_byte),
            ("lfUnderline", ctypes.c_byte), ("lfStrikeOut", ctypes.c_byte),
            ("lfCharSet", ctypes.c_byte), ("lfOutPrecision", ctypes.c_byte),
            ("lfClipPrecision", ctypes.c_byte), ("lfQuality", ctypes.c_byte),
            ("lfPitchAndFamily", ctypes.c_byte), ("lfFaceName", ctypes.c_wchar * 32),
        ]

    class RECT(ctypes.Structure):
        _fields_ = [("left", ctypes.c_long), ("top", ctypes.c_long),
                    ("right", ctypes.c_long), ("bottom", ctypes.c_long)]

    class BITMAPINFOHEADER(ctypes.Structure):
        _fields_ = [
            ("biSize", ctypes.c_uint32), ("biWidth", ctypes.c_int32),
            ("biHeight", ctypes.c_int32), ("biPlanes", ctypes.c_uint16),
            ("biBitCount", ctypes.c_uint16), ("biCompression", ctypes.c_uint32),
            ("biSizeImage", ctypes.c_uint32), ("biXPelsPerMeter", ctypes.c_int32),
            ("biYPelsPerMeter", ctypes.c_int32), ("biClrUsed", ctypes.c_uint32),
            ("biClrImportant", ctypes.c_uint32),
        ]

    def chunk(tag, data):
        crc = zlib.crc32(tag + data) & 0xffffffff
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", crc)

    text = page_text
    if isinstance(text, (list, tuple)):
        text = " ".join(text)
    text = str(text)

    line_h = 200
    logfont = LOGFONTW()
    logfont.lfHeight = -32
    logfont.lfWeight = 400
    logfont.lfCharSet = 1
    logfont.lfOutPrecision = 8
    logfont.lfClipPrecision = 0
    logfont.lfQuality = 5
    logfont.lfPitchAndFamily = 32
    logfont.lfFaceName = "Times New Roman"
    font = gdi32.CreateFontIndirectW(ctypes.byref(logfont))
    screen = user32.GetDC(None)
    mem = gdi32.CreateCompatibleDC(screen)
    bmp = gdi32.CreateCompatibleBitmap(screen, page_w, line_h)
    old_bmp = gdi32.SelectObject(mem, bmp)
    old_font = gdi32.SelectObject(mem, font)
    r = RECT(0, 0, page_w, line_h)
    r_addr = ctypes.addressof(r)
    user32.FillRect.restype = ctypes.c_int
    user32.FillRect.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p]
    user32.FillRect(mem, r_addr, 0)
    gdi32.SetBkMode(mem, 1)
    gdi32.SetTextColor(mem, 0x000000)
    user32.DrawTextA.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int, ctypes.c_void_p, ctypes.c_uint]
    user32.DrawTextA(mem, text.encode("ascii"), -1, r_addr, 0 | 4)
    bi = BITMAPINFOHEADER()
    bi.biSize = ctypes.sizeof(BITMAPINFOHEADER)
    bi.biWidth = page_w
    bi.biHeight = -line_h
    bi.biPlanes = 1
    bi.biBitCount = 32
    bi.biCompression = 0
    row_bytes = page_w * 4
    pixels = (ctypes.c_ubyte * (row_bytes * line_h))()
    gdi32.GetDIBits(mem, bmp, 0, line_h, ctypes.byref(pixels), ctypes.byref(bi), 0)
    gdi32.SelectObject(mem, old_bmp)
    gdi32.SelectObject(mem, old_font)
    gdi32.DeleteObject(font)
    gdi32.DeleteObject(bmp)
    gdi32.DeleteDC(mem)
    user32.ReleaseDC(None, screen)

    gray = bytearray(page_w * line_h)
    for y in range(line_h):
        for x in range(page_w):
            r = pixels[y * row_bytes + x * 4 + 2]
            g = pixels[y * row_bytes + x * 4 + 1]
            b = pixels[y * row_bytes + x * 4 + 0]
            gray[y * page_w + x] = (r * 299 + g * 587 + b * 114) // 1000
    ihdr = struct.pack(">IIBBBBB", page_w, line_h, 8, 0, 0, 0, 0)
    png_rows = b""
    for y in range(line_h):
        png_rows += bytes([0]) + bytes(gray[y * page_w:(y + 1) * page_w])
    png_bytes = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(png_rows))
        + chunk(b"IEND", b"")
    )

    doc = fitz.open()
    for p in range(page_count):
        page = doc.new_page(width=page_w, height=page_h)
        tmp = tempfile.NamedTemporaryFile(suffix=".png", delete=False)
        tmp.write(png_bytes)
        tmp.close()
        page.insert_image(fitz.Rect(0, 80, page_w, 80 + line_h), filename=tmp.name, keep_proportion=False)
        os.unlink(tmp.name)
    doc.save(path)
    doc.close()


def make_blank_pdf(path, pages=500, page_w=850, page_h=1100):
    doc = fitz.open()
    for _ in range(pages):
        page = doc.new_page(width=page_w, height=page_h)
        page.draw_rect(fitz.Rect(50, 50, page_w - 50, page_h - 50), color=(0.9, 0.9, 0.9), fill=(0.95, 0.95, 0.95), width=0.5)
    doc.save(path)
    doc.close()


def main():
    if len(sys.argv) < 2:
        print("usage: make-stall-fixture.py <output-dir> [digital=180] [image=30] [long_pages=0]", file=sys.stderr)
        sys.exit(2)
    out_dir = sys.argv[1]
    if os.path.exists(out_dir):
        shutil.rmtree(out_dir)
    os.makedirs(out_dir, exist_ok=True)

    digital_count = int(sys.argv[2]) if len(sys.argv) > 2 else 180
    image_count = int(sys.argv[3]) if len(sys.argv) > 3 else 30
    long_pages = int(sys.argv[4]) if len(sys.argv) > 4 else 0

    books_dir = os.path.join(out_dir, "Books")
    image_dir = os.path.join(out_dir, "ImageBooks")
    big_dir = os.path.join(out_dir, "BigBook")
    os.makedirs(books_dir, exist_ok=True)
    os.makedirs(image_dir, exist_ok=True)
    os.makedirs(big_dir, exist_ok=True)

    print(f"Building {digital_count} digital books...")
    for i in range(digital_count):
        para_text = (
            f"Book number {i + 1} in the test library. "
            f"This book contains substantive text content that should be extractable. "
            f"Chapter 1 begins with a simple introduction to the subject matter. "
            f"Chapter 2 continues with deeper exploration of the themes. "
            f"Chapter 3 concludes with thoughtful observations and final remarks."
        )
        line_groups = [para_text[j:j + 60] for j in range(0, len(para_text), 60)]
        path = os.path.join(books_dir, f"book_{i + 1:04d}.pdf")
        make_digital_pdf(path, [line_groups])

    print(f"Building {image_count} image-only books...")
    paragraph_a = (
        "Chapter 1 The Adventure Begins. "
        "A short fixture for OCR testing. "
        "The quick brown fox jumps over the lazy dog. "
        "Many trees stood tall in the quiet morning. "
        "Behind the bookshelf lay a secret room. "
        "The letter said meet me at the bridge."
    )
    for i in range(image_count):
        path = os.path.join(image_dir, f"image_{i + 1:04d}.pdf")
        make_image_only_pdf(path, paragraph_a)

    if long_pages > 0:
        big_path = os.path.join(big_dir, f"big_{long_pages}page.pdf")
        print(f"Building long {long_pages}-page image-only book at {big_path}...")
        make_long_image_only(big_path, paragraph_a, long_pages)

    total = digital_count + image_count + (1 if long_pages > 0 else 0)
    print(f"Done. Total files: {total}")


if __name__ == "__main__":
    main()
