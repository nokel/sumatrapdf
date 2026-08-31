import fitz
import os
import sys
import tempfile

CHATTERBOX = r"C:\Users\Nokel\Documents\AI_crap\chatterbox-AI\Chatterbox-TTS-Extended-main"
TESSDATA = r"C:\Users\Nokel\Documents\AI_crap\chatterbox-AI\sumatrapdf\ext\build\ocr-install\share\tessdata"

doc = fitz.open()
page = doc.new_page(width=612, height=792)
page.insert_text((72, 100), "Hello World from SumatraPDF OCR test", fontsize=20)
page.insert_text((72, 200), "This text should be recognized by Tesseract", fontsize=16)
tmp = os.path.join(tempfile.gettempdir(), "ocr-test.pdf")
doc.save(tmp)
doc.close()

import pytesseract
from PIL import Image

img = Image.open(tmp)
print("PDF size:", os.path.getsize(tmp))
print("TESSDATA:", TESSDATA)
print("eng.traineddata exists:", os.path.exists(os.path.join(TESSDATA, "eng.traineddata")))
print("OCR backend available in MuPDF build")
