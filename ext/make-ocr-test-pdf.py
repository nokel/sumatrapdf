"""Render text into a multi-page image-only PDF for OCR smoke testing.

Usage: python make-ocr-test-pdf.py <output.pdf> [text-line-1] [text-line-2] [text-line-3]
"""
import sys
import os
import struct
import zlib

def render_text_to_png(W, H, text, out_path):
    """Render text via ctypes + GDI to a PNG. Pure Python with no PIL."""
    import ctypes
    from ctypes import wintypes

    user32 = ctypes.windll.user32
    gdi32 = ctypes.windll.gdi32
    user32.GetDC.restype = ctypes.c_void_p
    user32.GetDC.argtypes = [ctypes.c_void_p]
    user32.ReleaseDC.argtypes = [ctypes.c_void_p, ctypes.c_void_p]
    gdi32.CreateCompatibleDC.argtypes = [ctypes.c_void_p]
    gdi32.CreateCompatibleDC.restype = ctypes.c_void_p
    gdi32.CreateCompatibleBitmap.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_int]
    gdi32.CreateCompatibleBitmap.restype = ctypes.c_void_p
    gdi32.SelectObject.argtypes = [ctypes.c_void_p, ctypes.c_void_p]
    gdi32.SelectObject.restype = ctypes.c_void_p
    gdi32.DeleteObject.argtypes = [ctypes.c_void_p]
    gdi32.DeleteDC.argtypes = [ctypes.c_void_p]

    LF_FACESIZE = 32
    class LOGFONTW(ctypes.Structure):
        _fields_ = [
            ("lfHeight", ctypes.c_long),
            ("lfWidth", ctypes.c_long),
            ("lfEscapement", ctypes.c_long),
            ("lfOrientation", ctypes.c_long),
            ("lfWeight", ctypes.c_long),
            ("lfItalic", ctypes.c_byte),
            ("lfUnderline", ctypes.c_byte),
            ("lfStrikeOut", ctypes.c_byte),
            ("lfCharSet", ctypes.c_byte),
            ("lfOutPrecision", ctypes.c_byte),
            ("lfClipPrecision", ctypes.c_byte),
            ("lfQuality", ctypes.c_byte),
            ("lfPitchAndFamily", ctypes.c_byte),
            ("lfFaceName", ctypes.c_wchar * LF_FACESIZE),
        ]

    FW_BOLD = 700
    DEFAULT_CHARSET = 1
    OUT_OUTLINE_PRECIS = 8
    CLIP_DEFAULT_PRECIS = 0
    CLEARTYPE_QUALITY = 5
    FF_SWISS = 32
    DT_CENTER = 1
    DT_VCENTER = 4
    DT_SINGLELINE = 32

    logfont = LOGFONTW()
    logfont.lfHeight = -72
    logfont.lfWeight = FW_BOLD
    logfont.lfCharSet = DEFAULT_CHARSET
    logfont.lfOutPrecision = OUT_OUTLINE_PRECIS
    logfont.lfClipPrecision = CLIP_DEFAULT_PRECIS
    logfont.lfQuality = CLEARTYPE_QUALITY
    logfont.lfPitchAndFamily = FF_SWISS
    logfont.lfFaceName = "Arial"

    font = gdi32.CreateFontIndirectW(ctypes.byref(logfont))
    screen_dc = user32.GetDC(None)
    mem_dc = gdi32.CreateCompatibleDC(screen_dc)
    bmp = gdi32.CreateCompatibleBitmap(screen_dc, W, H)
    old_bmp = gdi32.SelectObject(mem_dc, bmp)
    old_font = gdi32.SelectObject(mem_dc, font)

    class RECT(ctypes.Structure):
        _fields_ = [("left", ctypes.c_long), ("top", ctypes.c_long),
                    ("right", ctypes.c_long), ("bottom", ctypes.c_long)]
    rect = RECT(0, 0, W, H)
    rect_addr = ctypes.addressof(rect)

    WHITE_BRUSH = 0
    user32.FillRect.restype = ctypes.c_int
    user32.FillRect.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p]
    user32.FillRect(mem_dc, rect_addr, WHITE_BRUSH)
    gdi32.SetBkMode.argtypes = [ctypes.c_void_p, ctypes.c_int]
    gdi32.SetBkMode(mem_dc, 1)
    gdi32.SetTextColor.argtypes = [ctypes.c_void_p, ctypes.c_int]
    gdi32.SetTextColor(mem_dc, 0x000000)
    user32.DrawTextA.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int, ctypes.c_void_p, ctypes.c_uint]
    user32.DrawTextA(mem_dc, text.encode("ascii"), -1, rect_addr,
                     DT_CENTER | DT_VCENTER | DT_SINGLELINE)

    class BITMAPINFOHEADER(ctypes.Structure):
        _fields_ = [
            ("biSize", ctypes.c_uint32), ("biWidth", ctypes.c_int32),
            ("biHeight", ctypes.c_int32), ("biPlanes", ctypes.c_uint16),
            ("biBitCount", ctypes.c_uint16), ("biCompression", ctypes.c_uint32),
            ("biSizeImage", ctypes.c_uint32), ("biXPelsPerMeter", ctypes.c_int32),
            ("biYPelsPerMeter", ctypes.c_int32), ("biClrUsed", ctypes.c_uint32),
            ("biClrImportant", ctypes.c_uint32),
        ]
    bi = BITMAPINFOHEADER()
    bi.biSize = ctypes.sizeof(BITMAPINFOHEADER)
    bi.biWidth = W
    bi.biHeight = -H
    bi.biPlanes = 1
    bi.biBitCount = 32
    bi.biCompression = 0
    row_bytes = W * 4
    pixels = (ctypes.c_ubyte * (row_bytes * H))()
    DIB_RGB_COLORS = 0
    gdi32.GetDIBits.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_uint32,
                                 ctypes.c_uint32, ctypes.c_void_p,
                                 ctypes.c_void_p, ctypes.c_uint32]
    gdi32.GetDIBits(mem_dc, bmp, 0, H, ctypes.byref(pixels), ctypes.byref(bi), DIB_RGB_COLORS)

    gdi32.SelectObject(mem_dc, old_bmp)
    gdi32.SelectObject(mem_dc, old_font)
    gdi32.DeleteObject(font)
    gdi32.DeleteObject(bmp)
    gdi32.DeleteDC(mem_dc)
    user32.ReleaseDC(None, screen_dc)

    gray = bytearray(W * H)
    for y in range(H):
        for x in range(W):
            r = pixels[y * row_bytes + x * 4 + 2]
            g = pixels[y * row_bytes + x * 4 + 1]
            b = pixels[y * row_bytes + x * 4 + 0]
            gray[y * W + x] = (r * 299 + g * 587 + b * 114) // 1000
    with open(out_path, "wb") as f:
        f.write(bytes(gray))

def make_png(W, H, gray_data):
    """Create a PNG file from grayscale raw bytes."""
    def chunk(tag, data):
        crc = zlib.crc32(tag + data) & 0xffffffff
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", crc)

    sig = b"\x89PNG\r\n\x1a\n"
    ihdr = struct.pack(">IIBBBBB", W, H, 8, 0, 0, 0, 0)
    rows = b""
    for y in range(H):
        row = bytes([0])
        row += bytes(gray_data[y * W:(y + 1) * W])
        rows += row
    idat = zlib.compress(rows)
    return sig + chunk(b"IHDR", ihdr) + chunk(b"IDAT", idat) + chunk(b"IEND", b"")

def make_image_only_pdf(out_path, lines, page_w=1600, page_h=2000):
    import fitz
    doc = fitz.open()
    line_h = page_h // len(lines)
    for line in lines:
        page = doc.new_page(width=page_w, height=page_h)
        import tempfile
        with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as f:
            tmp_png = f.name
        render_text_to_png(page_w, line_h, line, tmp_png.replace(".png", ".gray"))
        with open(tmp_png.replace(".png", ".gray"), "rb") as f:
            gray = f.read()
        png_bytes = make_png(page_w, line_h, gray)
        os.unlink(tmp_png.replace(".png", ".gray"))
        with open(tmp_png, "wb") as f:
            f.write(png_bytes)
        rect = fitz.Rect(0, 0, page_w, page_h)
        page.insert_image(rect, filename=tmp_png, keep_proportion=False)
        os.unlink(tmp_png)
    doc.save(out_path)
    doc.close()
    print(f"Wrote {out_path}")

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("usage: python make-ocr-test-pdf.py <output.pdf> [line1] [line2] [line3]", file=sys.stderr)
        sys.exit(2)
    out = sys.argv[1]
    lines = sys.argv[2:] if len(sys.argv) > 2 else [
        "OCR SMOKE TEST",
        "THE QUICK BROWN FOX",
        "JUMPS OVER THE LAZY DOG",
    ]
    make_image_only_pdf(out, lines)
