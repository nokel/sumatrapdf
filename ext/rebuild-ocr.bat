@echo off
setlocal
set INSTALL_PREFIX=C:/Users/Nokel/Documents/AI_crap/chatterbox-AI/sumatrapdf/ext/build/ocr-install
set LEPTONICA_DIR=%INSTALL_PREFIX%/lib/cmake/leptonica
set TOOLCHAIN=C:/Users/Nokel/Documents/AI_crap/chatterbox-AI/sumatrapdf/ext/mt-toolchain.cmake

if exist build\leptonica-x64-release rmdir /s /q build\leptonica-x64-release
if exist build\tesseract-x64-release rmdir /s /q build\tesseract-x64-release

echo === Building Leptonica Release with /MT ===
cmake -S a-leptonica -B build/leptonica-x64-release -G "Visual Studio 17 2022" -A x64 ^
  -DCMAKE_INSTALL_PREFIX=%INSTALL_PREFIX% ^
  -DBUILD_PROG=OFF -DBUILD_SHARED_LIBS=OFF ^
  -DENABLE_ZLIB=OFF -DENABLE_PNG=OFF -DENABLE_GIF=OFF ^
  -DENABLE_JPEG=OFF -DENABLE_TIFF=OFF -DENABLE_WEBP=OFF -DENABLE_OPENJPEG=OFF ^
  -DCMAKE_POLICY_VERSION_MINIMUM=3.5 -DSW_BUILD=OFF
if errorlevel 1 exit /b 1
powershell -Command "Get-ChildItem -Path build\leptonica-x64-release -Recurse -Filter *.vcxproj | ForEach-Object { (Get-Content $_.FullName -Raw) -replace 'MultiThreadedDLL', 'MultiThreaded' -replace 'MultiThreadedDebugDLL', 'MultiThreadedDebug' | Set-Content $_.FullName }"
cmake --build build\leptonica-x64-release --config Release --target install
if errorlevel 1 exit /b 1

echo === Building Tesseract Release with /MT ===
cmake -S a-tesseract -B build/tesseract-x64-release -G "Visual Studio 17 2022" -A x64 ^
  -DCMAKE_INSTALL_PREFIX=%INSTALL_PREFIX% ^
  -DBUILD_PROG=OFF -DBUILD_SHARED_LIBS=OFF ^
  -DBUILD_TRAINING_TOOLS=OFF -DBUILD_TESTS=OFF ^
  -DSW_BUILD=OFF ^
  -DCMAKE_POLICY_VERSION_MINIMUM=3.5 ^
  -DLeptonica_DIR=%LEPTONICA_DIR%
if errorlevel 1 exit /b 1
powershell -Command "Get-ChildItem -Path build\tesseract-x64-release -Recurse -Filter *.vcxproj | ForEach-Object { (Get-Content $_.FullName -Raw) -replace 'MultiThreadedDLL', 'MultiThreaded' -replace 'MultiThreadedDebugDLL', 'MultiThreadedDebug' | Set-Content $_.FullName }"
cmake --build build/tesseract-x64-release --config Release --target install
exit /b %errorlevel%
