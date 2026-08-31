"""Build pathological fixtures for chunk 40W.

These fixtures verify that the OCR migration in the brand pipeline does not
hold the rescan hostage, that timeout protection works, and that corrupt
books do not crash the brand loop.

Usage: py -3.12 make-pathological-fixtures.py <output-dir>
"""
import os
import sys
import shutil

import reportlab.pdfgen.canvas as canvas_module
from reportlab.lib.pagesizes import letter
from reportlab.pdfgen import canvas as canvas_module_canvas
from PIL import Image, ImageDraw, ImageFont


def make_digital_pdf(path, pages_text, page_w=850, page_h=1100):
    c = canvas_module_canvas.Canvas(path, pagesize=(page_w, page_h))
    for i, body in enumerate(pages_text):
        c.drawString(72, page_h - 100, f"Page {i + 1}")
        for j, line in enumerate(body):
            c.drawString(72, page_h - 140 - j * 30, line)
        c.showPage()
    c.save()


def make_image_only_pdf(path, page_text_lines, page_w=850, page_h=1100):
    """Render text into PNG via PIL, embed as single image per page."""
    pages = []
    for lines in page_text_lines:
        img = Image.new("L", (page_w, page_h), 255)
        draw = ImageDraw.Draw(img)
        try:
            font = ImageFont.truetype("arial.ttf", 28)
        except OSError:
            font = ImageFont.load_default()
        y = 80
        for line in lines:
            draw.text((72, y), line, fill=0, font=font)
            y += 50
        pages.append(img)

    pages[0].save(
        path, "PDF", resolution=72.0, save_all=True, append_images=pages[1:]
    )


def make_long_image_only(path, page_lines, page_count=500, page_w=850, page_h=1100):
    """Render the same text into many image-only pages - the 500-page fixture."""
    pages = []
    for _ in range(page_count):
        img = Image.new("L", (page_w, page_h), 255)
        draw = ImageDraw.Draw(img)
        try:
            font = ImageFont.truetype("arial.ttf", 28)
        except OSError:
            font = ImageFont.load_default()
        y = 80
        for line in page_lines:
            draw.text((72, y), line, fill=0, font=font)
            y += 50
        pages.append(img)
    pages[0].save(
        path, "PDF", resolution=72.0, save_all=True, append_images=pages[1:]
    )


def make_sparse_image_only(path, page_count=20, page_w=850, page_h=1100):
    """Few words per page - exercises low-token OCR path."""
    pages = []
    for i in range(page_count):
        img = Image.new("L", (page_w, page_h), 255)
        draw = ImageDraw.Draw(img)
        try:
            font = ImageFont.truetype("arial.ttf", 24)
        except OSError:
            font = ImageFont.load_default()
        draw.text((72, 100), f"Page {i + 1}", fill=0, font=font)
        draw.text((72, 200), "Hello world", fill=0, font=font)
        pages.append(img)
    pages[0].save(
        path, "PDF", resolution=72.0, save_all=True, append_images=pages[1:]
    )


def make_blank_pdf(path, pages=500, page_w=850, page_h=1100):
    c = canvas_module_canvas.Canvas(path, pagesize=(page_w, page_h))
    for _ in range(pages):
        c.setFillColorRGB(0.97, 0.97, 0.97)
        c.rect(50, 50, page_w - 100, page_h - 100, fill=1, stroke=0)
        c.showPage()
    c.save()


def make_corrupt_pdf(path):
    """A file that looks like a PDF but has invalid xref."""
    with open(path, "wb") as f:
        f.write(b"%PDF-1.4\n")
        f.write(b"1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        f.write(b"2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
        f.write(b"3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>\nendobj\n")
        f.write(b"xref\n0 4\n0000000000 65535 f \n0000000009 00000 n \n0000000058 00000 n \n0000000115 00000 n \n")
        f.write(b"trailer\n<< /Size 4 /Root 1 0 R >>\nstartxref\n190\n%%EOF\n")
        f.write(b"\x00\x00\x00\x00garbage-data-here-makes-it-corrupt\n")


def make_digital_corpus(out_dir, n):
    pages_text = []
    for i in range(n):
        para = (
            f"Book {i + 1} Chapter 1 introduction. "
            f"Many sentences follow explaining the test subject. "
            f"Chapter 2 continues with deeper exploration of themes. "
            f"Chapter 3 concludes with thoughtful final observations."
        )
        line_groups = [para[j:j + 70] for j in range(0, len(para), 70)]
        pages_text.append(line_groups)
        make_digital_pdf(os.path.join(out_dir, f"book_{i + 1:04d}.pdf"), [line_groups])


def main():
    if len(sys.argv) < 2:
        print("usage: make-pathological-fixtures.py <output-dir>", file=sys.stderr)
        sys.exit(2)
    out_dir = sys.argv[1]
    if os.path.exists(out_dir):
        shutil.rmtree(out_dir)
    os.makedirs(out_dir, exist_ok=True)

    digital_dir = os.path.join(out_dir, "DigitalBooks")
    image_dir = os.path.join(out_dir, "ImageBooks")
    big_dir = os.path.join(out_dir, "BigImageOnly")
    os.makedirs(digital_dir, exist_ok=True)
    os.makedirs(image_dir, exist_ok=True)
    os.makedirs(big_dir, exist_ok=True)

    print(f"Building 20 digital books...")
    make_digital_corpus(digital_dir, 20)

    print("Building 4 normal image-only books (multi-line, 5 pages each)...")
    paragraph = [
        "Chapter 1 The Adventure Begins.",
        "A short fixture for OCR testing.",
        "The quick brown fox jumps over the lazy dog.",
        "Many trees stood tall in the quiet morning.",
        "Behind the bookshelf lay a secret room.",
    ]
    for i in range(4):
        make_image_only_pdf(
            os.path.join(image_dir, f"image_{i + 1:04d}.pdf"), [paragraph] * 5
        )

    print("Building 500-page image-only book (the real stall repro)...")
    make_long_image_only(
        os.path.join(big_dir, "long_500page.pdf"),
        paragraph,
        page_count=500,
    )

    print("Building sparse-text image-only book...")
    make_sparse_image_only(
        os.path.join(image_dir, "sparse_text.pdf"),
        page_count=20,
    )

    print("Building blank 500-page digital PDF...")
    make_blank_pdf(os.path.join(big_dir, "blank_500page.pdf"), pages=500)

    print("Building corrupt PDF...")
    make_corrupt_pdf(os.path.join(out_dir, "corrupt.pdf"))

    total = sum(
        len(os.listdir(d))
        for d in (digital_dir, image_dir, big_dir, out_dir)
    )
    print(f"Done. Total files: {total}")


if __name__ == "__main__":
    main()
