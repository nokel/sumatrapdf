@echo off
setlocal
call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat" >nul
cd /d C:\Users\Nokel\Documents\AI_crap\chatterbox-AI\sumatrapdf\ext
cl /nologo /EHsc /std:c++14 /MT /I"C:/Users/Nokel/Documents/AI_crap/chatterbox-AI/sumatrapdf/ext/mupdf/include" /I"C:/Users/Nokel/Documents/AI_crap/chatterbox-AI/sumatrapdf/out/dbg64/obj-s/mupdf" /I"C:/Users/Nokel/Documents/AI_crap/chatterbox-AI/sumatrapdf/ext/build/ocr-install/include" mupdf-ocr-smoke.cpp /link /LIBPATH:"C:/Users/Nokel/Documents/AI_crap/chatterbox-AI/sumatrapdf/out/dbg64/obj" /LIBPATH:"C:/Users/Nokel/Documents/AI_crap/chatterbox-AI/sumatrapdf/out/dbg64/obj-s" /LIBPATH:"C:/Users/Nokel/Documents/AI_crap/chatterbox-AI/sumatrapdf/ext/build/ocr-install/lib" mupdf.lib tesseract53.lib leptonica-1.84.0.lib cmark-gfm.lib brotli.lib freetype.lib harfbuzz.lib libjpeg-turbo.lib a-openjpeg.lib a-jbig2dec.lib lcms2.lib zlib.lib libwebp.lib libarchive.lib a-gumbo.lib a-extract.lib a-mujs.lib ole32.lib windowscodecs.lib /out:mupdf-ocr-smoke.exe
if errorlevel 1 exit /b 1
mupdf-ocr-smoke.exe %*
exit /b %errorlevel%
