@echo off
setlocal
cd /d C:\Users\Nokel\Documents\AI_crap\chatterbox-AI\sumatrapdf\ext
if exist build\tesseract-x64 rmdir /s /q build\tesseract-x64
cmake -S a-tesseract -B build\tesseract-x64 -G "Visual Studio 17 2022" -A x64 ^
  -DCMAKE_INSTALL_PREFIX=C:/Users/Nokel/Documents/AI_crap/chatterbox-AI/sumatrapdf/ext/build/ocr-install ^
  -DBUILD_PROG=OFF ^
  -DBUILD_SHARED_LIBS=OFF ^
  -DBUILD_TRAINING_TOOLS=OFF ^
  -DBUILD_TESTS=OFF ^
  -DSW_BUILD=OFF ^
  -DCMAKE_POLICY_VERSION_MINIMUM=3.5 ^
  -DLeptonica_DIR=C:/Users/Nokel/Documents/AI_crap/chatterbox-AI/sumatrapdf/ext/build/ocr-install/lib/cmake/leptonica
if errorlevel 1 exit /b 1
cmake --build build\tesseract-x64 --config Release --target install
exit /b %errorlevel%
