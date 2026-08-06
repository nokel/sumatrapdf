/* Copyright 2026 the SumatraPDF project authors (see AUTHORS file).
   License: GPLv3 */

// Plain C bridge between the Android app (Kotlin/Compose) and the document
// engine. Mirrors src/mac/SumatraMacEngine.h so a future implementation can
// be swapped (Sumatra engine vs. mupdf-direct) without touching the UI layer.
//
// Today the Android build calls mupdf directly via the JNI bindings shipped
// under mupdf/platform/java, exposed to Kotlin as com.artifex.mupdf.fitz.*.
// This header documents the bridge contract the UI relies on; the Kotlin
// side implements it as a thin wrapper over those bindings.

#pragma once

#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

struct AndroidRenderedPage {
    int width;
    int height;
    int stride;
    bool premultiplied;
    unsigned char* data;
};

struct AndroidLayoutParams {
    bool continuous;
    int startPage;
    int viewX;
    int viewY;
    int viewWidth;
    int viewHeight;
    double zoomVirtual;
    double backingScale;
    int rotation;
};

struct AndroidLayoutPage {
    int pageNo;
    int x;
    int y;
    int width;
    int height;
    int screenX;
    int screenY;
    int screenWidth;
    int screenHeight;
    double visibleRatio;
    double renderZoom;
    bool shown;
};

struct AndroidDocumentLayout {
    int pageCount;
    int currentPage;
    int canvasWidth;
    int canvasHeight;
    AndroidLayoutPage* pages;
};

// Opens a document. Returns an opaque handle, or nullptr on failure; on failure
// *errorOut (if non-null) is set to a malloc'd message the caller must free().
void* AndroidOpenDocument(const char* path, char** errorOut);

int AndroidPageCount(void* document);

bool AndroidPageSize(void* document, int pageNo, double* widthOut, double* heightOut);

double AndroidFileDPI(void* document);

bool AndroidRenderPage(void* document, int pageNo, float zoom, int rotation,
                      AndroidRenderedPage* page);

bool AndroidLayoutDocument(void* document, const AndroidLayoutParams* params,
                          AndroidDocumentLayout* layout);

void AndroidFreeDocumentLayout(AndroidDocumentLayout* layout);

void AndroidFreeRenderedPage(AndroidRenderedPage* page);
void AndroidCloseDocument(void* document);
void AndroidShutdown();

#ifdef __cplusplus
}
#endif
