@echo off
setlocal
call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat" >nul
cd /d C:\Users\Nokel\Documents\AI_crap\chatterbox-AI\sumatrapdf\ext
cl /nologo /EHsc /std:c++14 /MT /I"C:/Users/Nokel/Documents/AI_crap/chatterbox-AI/sumatrapdf/ext/build/ocr-install/include" ocr-smoke.cpp /link /LIBPATH:"C:/Users/Nokel/Documents/AI_crap/chatterbox-AI/sumatrapdf/ext/build/ocr-install/lib" tesseract53.lib leptonica-1.84.0.lib user32.lib gdi32.lib /out:ocr-smoke.exe
if errorlevel 1 exit /b 1
ocr-smoke.exe
exit /b %errorlevel%
