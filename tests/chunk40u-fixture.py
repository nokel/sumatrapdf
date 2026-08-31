"""Build deterministic OCR fixture books A/B/C/D in an isolated temp dir.

Usage: python chunk40u-fixture.py <output-dir>

Fixture A: digital text PDF (multi-page, has substantive text)
Fixture B: image-only PDF whose pages are images of the SAME text as A
Fixture C: image-only PDF with DIFFERENT text (unrelated)
Fixture D: image-only PDF with NO readable text (blank + rectangles)
"""
import os
import sys
import ctypes
import struct
import zlib
import tempfile
import shutil

CHATTERBOX = os.environ.get(
    "CHATTERBOX",
    r"C:\Users\Nokel\Documents\AI_crap\chatterbox-AI\Chatterbox-TTS-Extended-main",
)
sys.path.insert(0, CHATTERBOX)

import fitz  # PyMuPDF


def _gdi_text_to_gray(width, height, text):
    user32 = ctypes.windll.user32
    gdi32 = ctypes.windll.gdi32

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

    logfont = LOGFONTW()
    logfont.lfHeight = -24
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
    bmp = gdi32.CreateCompatibleBitmap(screen, width, height)
    old_bmp = gdi32.SelectObject(mem, bmp)
    old_font = gdi32.SelectObject(mem, font)

    class RECT(ctypes.Structure):
        _fields_ = [("left", ctypes.c_long), ("top", ctypes.c_long),
                    ("right", ctypes.c_long), ("bottom", ctypes.c_long)]
    r = RECT(0, 0, width, height)
    r_addr = ctypes.addressof(r)

    user32.FillRect.restype = ctypes.c_int
    user32.FillRect.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p]
    user32.FillRect(mem, r_addr, 0)
    gdi32.SetBkMode(mem, 1)
    gdi32.SetTextColor(mem, 0x000000)
    user32.DrawTextA.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int, ctypes.c_void_p, ctypes.c_uint]
    user32.DrawTextA(mem, text.encode("ascii"), -1, r_addr, 0 | 4)

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
    bi.biWidth = width
    bi.biHeight = -height
    bi.biPlanes = 1
    bi.biBitCount = 32
    bi.biCompression = 0
    row_bytes = width * 4
    pixels = (ctypes.c_ubyte * (row_bytes * height))()
    gdi32.GetDIBits(mem, bmp, 0, height, ctypes.byref(pixels), ctypes.byref(bi), 0)

    gdi32.SelectObject(mem, old_bmp)
    gdi32.SelectObject(mem, old_font)
    gdi32.DeleteObject(font)
    gdi32.DeleteObject(bmp)
    gdi32.DeleteDC(mem)
    user32.ReleaseDC(None, screen)

    gray = bytearray(width * height)
    for y in range(height):
        for x in range(width):
            r = pixels[y * row_bytes + x * 4 + 2]
            g = pixels[y * row_bytes + x * 4 + 1]
            b = pixels[y * row_bytes + x * 4 + 0]
            gray[y * width + x] = (r * 299 + g * 587 + b * 114) // 1000
    return bytes(gray)


def _gray_to_png(gray, width, height):
    def chunk(tag, data):
        crc = zlib.crc32(tag + data) & 0xffffffff
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", crc)
    sig = b"\x89PNG\r\n\x1a\n"
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 0, 0, 0, 0)
    rows = b""
    for y in range(height):
        row = bytes([0]) + bytes(gray[y * width:(y + 1) * width])
        rows += row
    idat = zlib.compress(rows)
    return sig + chunk(b"IHDR", ihdr) + chunk(b"IDAT", idat) + chunk(b"IEND", b"")


def make_digital_pdf(path, paragraphs, page_w=1200, page_h=1600):
    doc = fitz.open()
    for paragraph in paragraphs:
        page = doc.new_page(width=page_w, height=page_h)
        for i, line in enumerate(paragraph):
            page.insert_text((72, 200 + i * 30), line, fontsize=14)
    doc.set_metadata({"title": "Fixture A", "author": "Test Author"})
    doc.save(path)
    doc.close()


def make_image_only_pdf(path, paragraphs, page_w=1200, page_h=1600, no_text=False):
    doc = fitz.open()
    for paragraph in paragraphs:
        page = doc.new_page(width=page_w, height=page_h)
        if no_text:
            for li, _ in enumerate(paragraph):
                if li == 0:
                    line_text = " "
                else:
                    line_text = " "
                y0 = 100 + li * 100
                y1 = y0 + 90
                gray = _gdi_text_to_gray(page_w, 90, line_text)
                png_bytes = _gray_to_png(gray, page_w, 90)
                tmp_png = tempfile.NamedTemporaryFile(suffix=".png", delete=False)
                tmp_png.write(png_bytes)
                tmp_png.close()
                page.insert_image(fitz.Rect(0, y0, page_w, y1), filename=tmp_png.name, keep_proportion=False)
                os.unlink(tmp_png.name)
        else:
            for li, line in enumerate(paragraph):
                y0 = 100 + li * 100
                y1 = y0 + 90
                gray = _gdi_text_to_gray(page_w, 90, line)
                png_bytes = _gray_to_png(gray, page_w, 90)
                tmp_png = tempfile.NamedTemporaryFile(suffix=".png", delete=False)
                tmp_png.write(png_bytes)
                tmp_png.close()
                page.insert_image(fitz.Rect(0, y0, page_w, y1), filename=tmp_png.name, keep_proportion=False)
                os.unlink(tmp_png.name)
    title = "Fixture"
    if "B" in path:
        title = "Fixture B"
    elif "C" in path:
        title = "Fixture C"
    elif "D" in path:
        title = "Fixture D"
    doc.set_metadata({"title": title, "author": "Test Author"})
    doc.save(path)
    doc.close()


def main():
    if len(sys.argv) < 2:
        print("usage: chunk40u-fixture.py <output-dir>", file=sys.stderr)
        sys.exit(2)
    out_dir = sys.argv[1]
    if os.path.exists(out_dir):
        shutil.rmtree(out_dir)
    os.makedirs(out_dir, exist_ok=True)

    pages_a = [
        [
            "Chapter 1 The Adventure Begins.",
            "A short fixture for OCR testing.",
            "The quick brown fox jumps over the lazy dog.",
            "Many trees stood tall in the quiet morning.",
            "Behind the bookshelf lay a secret room.",
            "The letter said meet me at the bridge.",
            "The end of the first chapter of our short story.",
        ],
        [
            "Chapter 2 The Forest Path.",
            "The travellers walked through the forest in silence.",
            "Birds sang from the highest branches of the ancient oaks.",
            "A river wound its way through the mossy stones.",
            "They followed the path deeper into the wilderness.",
            "Twilight fell upon the glade as the sun went down.",
        ],
        [
            "Chapter 3 The Hidden Door.",
            "Behind the bookshelf a secret passage was revealed.",
            "Dust covered the stone steps leading down into the darkness.",
            "The air was cool and still in the underground chamber.",
            "Each step echoed softly in the empty corridor.",
        ],
        [
            "Chapter 4 The Final Letter.",
            "The letter arrived on a cold autumn morning.",
            "Its contents were brief and its message was unmistakable.",
            "Meet me at the bridge at midnight said the writer.",
            "The envelope bore no name and no return address.",
        ],
        [
            "Chapter 5 The Storm.",
            "Rain hammered the roof of the small cottage.",
            "The wind howled through the trees outside the window.",
            "Inside the fire crackled and the cat purred on the hearth.",
            "The night was long and full of sound and fury.",
        ],
        [
            "Chapter 6 The Journey.",
            "By morning the storm had passed completely.",
            "The road ahead was muddy but passable for travel.",
            "The travellers set out at first light with their packs.",
            "The birds were singing once again in the trees.",
        ],
        [
            "Chapter 7 The Village.",
            "They reached the village by noon and found the market.",
            "Villagers bought and sold their wares in the square.",
            "Children played in the streets while adults bargained.",
            "The smell of fresh bread filled the air everywhere.",
        ],
        [
            "Chapter 8 The Bridge.",
            "At the appointed hour they met at the bridge.",
            "The old friend stood waiting in the evening light.",
            "The two embraced after all these many years apart.",
            "Their adventure was finally at an end today.",
        ],
    ]
    pages_c = [
        ["Section A Marine biology.",
         "Ocean currents and their effects on climate.",
         "Many species depend on these global patterns."],
        ["Section B Economic theory.",
         "Small island nations in modern world trade.",
         "Their unique challenges and opportunities."],
        ["Section C Quantum mechanics.",
         "Without complicated mathematics explained simply.",
         "Concepts for the general audience to understand."],
        ["Section D History of bread.",
         "Baking across ancient civilizations and societies.",
         "How it shaped our culture and daily life."],
        ["Section E Rare earth minerals.",
         "Their industrial applications in modern electronics.",
         "Used in batteries and many other critical devices."],
        ["Section F Computer graphics.",
         "Rendering techniques explained for beginners.",
         "From basic polygons to complex scenes."],
        ["Section G Human language.",
         "Evolution through the ages and how words change.",
         "The science of historical linguistics today."],
        ["Section H Music theory.",
         "Fundamentals including scales chords and harmony.",
         "Building blocks for any aspiring musician."],
    ]

    fixture_a = os.path.join(out_dir, "fixture_A_digital.pdf")
    fixture_b = os.path.join(out_dir, "fixture_B_image_only.pdf")
    fixture_c = os.path.join(out_dir, "fixture_C_unrelated.pdf")
    fixture_d = os.path.join(out_dir, "fixture_D_no_text.pdf")

    make_digital_pdf(fixture_a, pages_a)
    make_image_only_pdf(fixture_b, pages_a)
    make_image_only_pdf(fixture_c, pages_c)
    make_image_only_pdf(fixture_d, pages_a, no_text=True)

    print("WROTE", fixture_a)
    print("WROTE", fixture_b)
    print("WROTE", fixture_c)
    print("WROTE", fixture_d)


if __name__ == "__main__":
    main()
