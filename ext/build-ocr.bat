@echo off
setlocal
set INSTALL_PREFIX=C:/Users/Nokel/Documents/AI_crap/chatterbox-AI/sumatrapdf/ext/build/ocr-install
set LEPTONICA_DIR=%INSTALL_PREFIX%/lib/cmake/leptonica

echo === Building Leptonica Debug ===
cmake -S a-leptonica -B build/leptonica-x64-debug -G "Visual Studio 17 2022" -A x64 ^
  -DCMAKE_INSTALL_PREFIX=%INSTALL_PREFIX% ^
  -DBUILD_PROG=OFF -DBUILD_SHARED_LIBS=OFF ^
  -DENABLE_ZLIB=OFF -DENABLE_PNG=OFF -DENABLE_GIF=OFF ^
  -DENABLE_JPEG=OFF -DENABLE_TIFF=OFF -DENABLE_WEBP=OFF -DENABLE_OPENJPEG=OFF ^
  -DCMAKE_POLICY_VERSION_MINIMUM=3.5 -DSW_BUILD=OFF ^
  -DCMAKE_C_FLAGS_RELEASE="/MT /O2" ^
  -DCMAKE_C_FLAGS_DEBUG="/MTd /Od"
if errorlevel 1 exit /b 1
cmake --build build/leptonica-x64-debug --config Debug --target install
if errorlevel 1 exit /b 1
echo === Building Leptonica Release ===
cmake -S a-leptonica -B build/leptonica-x64-release -G "Visual Studio 17 2022" -A x64 ^
  -DCMAKE_INSTALL_PREFIX=%INSTALL_PREFIX% ^
  -DBUILD_PROG=OFF -DBUILD_SHARED_LIBS=OFF ^
  -DENABLE_ZLIB=OFF -DENABLE_PNG=OFF -DENABLE_GIF=OFF ^
  -DENABLE_JPEG=OFF -DENABLE_TIFF=OFF -DENABLE_WEBP=OFF -DENABLE_OPENJPEG=OFF ^
  -DCMAKE_POLICY_VERSION_MINIMUM=3.5 -DSW_BUILD=OFF ^
  -DCMAKE_C_FLAGS_RELEASE="/MT /O2" ^
  -DCMAKE_C_FLAGS_DEBUG="/MTd /Od"
if errorlevel 1 exit /b 1
cmake --build build/leptonica-x64-release --config Release --target install
if errorlevel 1 exit /b 1

echo === Building Tesseract Debug ===
cmake -S a-tesseract -B build/tesseract-x64-debug -G "Visual Studio 17 2022" -A x64 ^
  -DCMAKE_INSTALL_PREFIX=%INSTALL_PREFIX% ^
  -DBUILD_PROG=OFF -DBUILD_SHARED_LIBS=OFF ^
  -DBUILD_TRAINING_TOOLS=OFF -DBUILD_TESTS=OFF ^
  -DSW_BUILD=OFF ^
  -DCMAKE_POLICY_VERSION_MINIMUM=3.5 ^
  -DLeptonica_DIR=%LEPTONICA_DIR% ^
  -DCMAKE_CXX_FLAGS_RELEASE="/MT /O2" ^
  -DCMAKE_CXX_FLAGS_DEBUG="/MTd /Od" ^
  -DCMAKE_C_FLAGS_RELEASE="/MT /O2" ^
  -DCMAKE_C_FLAGS_DEBUG="/MTd /Od"
if errorlevel 1 exit /b 1
cmake --build build/tesseract-x64-debug --config Debug --target install
if errorlevel 1 exit /b 1
echo === Building Tesseract Release ===
cmake -S a-tesseract -B build/tesseract-x64-release -G "Visual Studio 17 2022" -A x64 ^
  -DCMAKE_INSTALL_PREFIX=%INSTALL_PREFIX% ^
  -DBUILD_PROG=OFF -DBUILD_SHARED_LIBS=OFF ^
  -DBUILD_TRAINING_TOOLS=OFF -DBUILD_TESTS=OFF ^
  -DSW_BUILD=OFF ^
  -DCMAKE_POLICY_VERSION_MINIMUM=3.5 ^
  -DLeptonica_DIR=%LEPTONICA_DIR% ^
  -DCMAKE_CXX_FLAGS_RELEASE="/MT /O2" ^
  -DCMAKE_CXX_FLAGS_DEBUG="/MTd /Od" ^
  -DCMAKE_C_FLAGS_RELEASE="/MT /O2" ^
  -DCMAKE_C_FLAGS_DEBUG="/MTd /Od"
if errorlevel 1 exit /b 1
cmake --build build/tesseract-x64-release --config Release --target install
exit /b %errorlevel%
