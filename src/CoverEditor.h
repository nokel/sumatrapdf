/* Copyright 2026 the SumatraPDF project authors (see AUTHORS file).
   License: Simplified BSD (see COPYING.BSD) */

struct MainWindow;

bool CoverEditorActive();
void CoverEditorOpen(MainWindow* win, Str bookId, Str bookPath, Str title);
void CoverEditorShutdown();

void CoverEditorDraw(MainWindow* win, HDC hdc, Rect rc);
bool CoverEditorOnLink(MainWindow* win, Str url);
bool CoverEditorOnLeftButtonDown(MainWindow* win, int x, int y);
bool CoverEditorOnMouseMove(MainWindow* win, int x, int y);
bool CoverEditorOnLeftButtonUp(MainWindow* win);
void CoverEditorOnCaptureLost();
