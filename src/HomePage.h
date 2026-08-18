/* Copyright 2022 the SumatraPDF project authors (see AUTHORS file).
   License: GPLv3 */

/* styling for About/Properties windows */

struct MainWindow;
struct Gfx;
struct StaticLink;

constexpr const char* kLeftTextFont = "Arial";
constexpr int kLeftTextFontSize = 14;
constexpr const char* kRightTextFont = "Arial Black";
constexpr int kRightTextFontSize = 14;

void ShowAboutWindow(MainWindow*);

void DrawAboutPage(MainWindow* win, Gfx* gfx);

bool HomePageIsListView();
void SetHomePageListView(bool listView);

TempStr GetStaticLinkAtTemp(Vec<StaticLink*>& linkInfo, int x, int y, StaticLink** info);

constexpr const char* kLinkOpenFile = "<File,Open>";
constexpr const char* kLinkShowList = "<View,ShowList>";
constexpr const char* kLinkHideList = "<View,HideList>";
constexpr const char* kLinkNextTip = "<NextTip>";
constexpr const char* kLinkHomeLibrary = "<HomePage,Library>";
constexpr const char* kLinkHomeListView = "<HomePage,ListView>";
constexpr const char* kLinkHomeThumbnailView = "<HomePage,ThumbnailView>";
constexpr const char* kLinkHomeRemoveFilePrefix = "<HomePage,RemoveFile>";
constexpr const char* kLinkHomePinFilePrefix = "<HomePage,PinFile>";

void SetPromoString(Str s);
void FreeHomePageTips();
void HomePageInvalidateLayoutCache();

void DrawHomePage(MainWindow* win, Gfx* gfx);
void PickAnotherRandomPromotion();
void HomePageOnVScroll(MainWindow* win, WPARAM wp);
void HomePageOnMouseWheel(MainWindow* win, int delta);
void HomePageFocusSearch(MainWindow* win);
void HomePageUpdateSearchColors(MainWindow* win);
void HomePageDestroySearch(MainWindow* win);
void HomePageDestroyChrome(MainWindow* win);
bool HomePageOnCanvasMessage(MainWindow* win, UINT msg, WPARAM wp, LPARAM lp, LRESULT& res);

void HomePageMoveSelection(MainWindow* win, int dCol, int dRow);
Str HomePageSelectedFilePathTemp(MainWindow* win);
void HomePageSelectFirst(MainWindow* win);
void HomePageOnWindowActivate(MainWindow* win, bool active);
bool HomePageOnHover(MainWindow* win, int x, int y);
Str HomePageFilePathAtTemp(MainWindow* win, int x, int y);

void HomePageClearActiveEntry(MainWindow* win);

TempStr HomeListRowsResultTemp(int* exitCodeOut);
TempStr HomeSelectionResultTemp(int* exitCodeOut);
