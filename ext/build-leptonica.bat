@echo off
setlocal
cd /d C:\Users\Nokel\Documents\AI_crap\chatterbox-AI\sumatrapdf\ext
if exist build\leptonica-x64 rmdir /s /q build\leptonica-x64
cmake -S a-leptonica -B build\leptonica-x64 -G "Visual Studio 17 2022" -A x64 ^
  -DCMAKE_INSTALL_PREFIX=C:/Users/Nokel/Documents/AI_crap/chatterbox-AI/sumatrapdf/ext/build/ocr-install ^
  -DBUILD_PROG=OFF ^
  -DBUILD_SHARED_LIBS=OFF ^
  -DENABLE_ZLIB=OFF ^
  -DENABLE_PNG=OFF ^
  -DENABLE_GIF=OFF ^
  -DENABLE_JPEG=OFF ^
  -DENABLE_TIFF=OFF ^
  -DENABLE_WEBP=OFF ^
  -DENABLE_OPENJPEG=OFF ^
  -DCMAKE_POLICY_VERSION_MINIMUM=3.5 ^
  -DSW_BUILD=OFF
if errorlevel 1 exit /b 1
cmake --build build\leptonica-x64 --config Release --target install
exit /b %errorlevel%
