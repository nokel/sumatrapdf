#!/usr/bin/env python3
"""Create a minimal one-page PDF with both 'Brotli' and 'brotli' so we
can verify case-insensitive search works after the SearchFlags fix.

Hand-rolled because we have no reportlab/fpdf available. The output is
text-only and intentionally trivial; the goal is to exercise the search
code path, not to be a beautiful document.
"""
import sys

def make_pdf(path: str) -> None:
    # A single page with two paragraphs.
    content = (
        b"BT /F1 18 Tf 50 750 Td (Brotli compression reference) Tj ET\n"
        b"BT /F1 12 Tf 50 700 Td (This page tests case-insensitive search.) Tj ET\n"
        b"BT /F1 12 Tf 50 680 Td (The brotli compressor is a generic-purpose) Tj ET\n"
        b"BT /F1 12 Tf 50 660 Td (lossless compression algorithm that combines) Tj ET\n"
        b"BT /F1 12 Tf 50 640 Td (a modern variant of the LZ77 algorithm, Huffman) Tj ET\n"
        b"BT /F1 12 Tf 50 620 Td (coding and 2nd order context modelling.) Tj ET\n"
        b"BT /F1 12 Tf 50 600 Td (BROTLI is open-sourced under the MIT License.) Tj ET\n"
        b"BT /F1 12 Tf 50 580 Td (Search lower-case 'brotli' should find all 3 forms.) Tj ET\n"
    )
    stream_len = len(content)
    objects = []

    # 1: Catalog
    objects.append(b"1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
    # 2: Pages
    objects.append(b"2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
    # 3: Page
    objects.append(b"3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
                  b"/Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>\nendobj\n")
    # 4: Content stream
    objects.append(b"4 0 obj\n<< /Length " + str(stream_len).encode() + b" >>\nstream\n" +
                  content + b"endstream\nendobj\n")
    # 5: Font
    objects.append(b"5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n")

    out = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets = [0]  # 1-indexed; offsets[i] = byte offset of object i
    for obj in objects:
        offsets.append(len(out))
        out += obj

    # xref: one entry per object (incl. object 0 which is the free list)
    xref_offset = len(out)
    n = len(objects) + 1
    out += b"xref\n0 " + str(n).encode() + b"\n"
    out += b"0000000000 65535 f \n"
    for off in offsets[1:]:
        out += f"{off:010d} 00000 n \n".encode()
    out += b"trailer\n<< /Size " + str(n).encode() + b" /Root 1 0 R >>\n"
    out += b"startxref\n" + str(xref_offset).encode() + b"\n%%EOF\n"

    with open(path, "wb") as f:
        f.write(out)
    print(f"wrote {path}: {len(out)} bytes, {n-1} objects")


if __name__ == "__main__":
    make_pdf(sys.argv[1] if len(sys.argv) > 1 else "test-search.pdf")
