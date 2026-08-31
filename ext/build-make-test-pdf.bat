@echo off
setlocal
call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat" >nul
cd /d C:\Users\Nokel\Documents\AI_crap\chatterbox-AI\sumatrapdf\ext
cl /nologo /EHsc /std:c++14 /MT /I"C:/Users/Nokel/Documents/AI_crap/chatterbox-AI/sumatrapdf/ext/mupdf/include" /I"C:/Users/Nokel/Documents/AI_crap/chatterbox-AI/sumatrapdf/out/dbg64/obj-s/mupdf" make-test-pdf.cpp /link /LIBPATH:"C:/Users/Nokel/Documents/AI_crap/chatterbox-AI/sumatrapdf/out/dbg64/obj" /LIBPATH:"C:/Users/Nokel/Documents/AI_crap/chatterbox-AI/sumatrapdf/out/dbg64/obj-s" mupdf.lib cmark-gfm.lib brotli.lib freetype.lib harfbuzz.lib libjpeg-turbo.lib a-openjpeg.lib a-jbig2dec.lib lcms2.lib zlib.lib libwebp.lib libarchive.lib a-gumbo.lib a-extract.lib a-mujs.lib ole32.lib windowscodecs.lib user32.lib gdi32.lib /out:make-test-pdf.exe
if errorlevel 1 exit /b 1
exit /b 0
