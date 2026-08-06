package com.sumatrapdf.reader

// Shared menu types. The toolbar uses DisplayMode/ZoomLevel to set the
// checked state of its checkable buttons, the tab bar uses them to
// show the right state in the hamburger menu, and the hamburger menu
// uses them all (DisplayMode, ZoomLevel, MenuSection, MenuAction) to
// render the cascading Win32 menu bar from `src/Menu.cpp::menuDefMenubar`.

// Win32 DisplayMode + Continuous, see src/DisplayMode.h and
// src/SumatraPDF.cpp (ChangeZoomLevel / SwitchToDisplayMode).
enum class DisplayMode { SinglePage, Facing, BookView }
enum class ZoomLevel { FitPage, FitWidth, FitHeight, FitContent, Custom }

// Sections of the Win32 menu bar, in `src/Menu.cpp::menuDefMenubar` order.
// `MenuSection` is the identity of a section, used as the key when
// the cascading popup renders the right column. The string for the
// section header is read from string resources.
enum class MenuSection {
    File, View, GoTo, Zoom, Selection, ReadAloud, Favorites, Settings, Help, Debug
}

sealed class MenuAction {
    // File — src/Menu.cpp::menuDefFile
    object NewWindow : MenuAction()
    object Open : MenuAction()
    object ShowRecent : MenuAction()
    object Close : MenuAction()
    object ShowInFolder : MenuAction()
    object OpenNext : MenuAction()
    object OpenPrev : MenuAction()
    object SaveAs : MenuAction()
    object ShareDocument : MenuAction()
    object SaveAnnotations : MenuAction()
    object Rename : MenuAction()
    object Delete : MenuAction()
    object Print : MenuAction()
    object Properties : MenuAction()
    object Exit : MenuAction()

    // View — src/Menu.cpp::menuDefView
    object CommandPalette : MenuAction()
    object CommandPaletteTOC : MenuAction()
    data class SetDisplayMode(val mode: DisplayMode) : MenuAction()
    object ToggleContinuous : MenuAction()
    object ToggleMangaMode : MenuAction()
    object RotateLeft : MenuAction()
    object RotateRight : MenuAction()
    object Presentation : MenuAction()
    object Fullscreen : MenuAction()
    object ShowBookmarks : MenuAction()
    object ShowMenu : MenuAction()
    object ShowToolbar : MenuAction()

    // Go To — src/Menu.cpp::menuDefGoTo
    object NextPage : MenuAction()
    object PrevPage : MenuAction()
    object FirstPage : MenuAction()
    object LastPage : MenuAction()
    object GoToPage : MenuAction()
    object NavigateBack : MenuAction()
    object NavigateForward : MenuAction()
    object FindFirst : MenuAction()

    // Zoom — src/Menu.cpp::menuDefZoom
    object FitPage : MenuAction()
    object ActualSize : MenuAction()
    object FitWidth : MenuAction()
    object FitHeight : MenuAction()
    object FitByOrientation : MenuAction()
    object FitContent : MenuAction()
    object ShrinkToFit : MenuAction()
    object CustomZoom : MenuAction()
    data class ZoomPercent(val percent: Int) : MenuAction()

    // Selection — src/Menu.cpp::menuDefMainSelection
    object CopySelection : MenuAction()
    object TranslateGoogle : MenuAction()
    object TranslateDeepL : MenuAction()
    object TranslateGrokBuild : MenuAction()
    object TranslateClaudeCode : MenuAction()
    object TranslateOpenAICodex : MenuAction()
    object SearchGoogle : MenuAction()
    object SearchBing : MenuAction()
    object SearchWikipedia : MenuAction()
    object SearchGoogleScholar : MenuAction()
    object SelectAll : MenuAction()

    // Read Aloud — src/Menu.cpp::menuDefReadAloud is a placeholder holding
    // only "Start Reading From Top"; the real menu is built by
    // SumatraPDF.cpp::RebuildReadAloudMenu, which adds Read Current Page,
    // Read From Cursor, Continue Reading, Read Selection, Pause, Stop, the
    // CmdTtsVoice* list and the CmdTtsSpeed* list. Read From Cursor, Read
    // Selection and the speed list have no equivalent here yet.
    object StartReadingTop : MenuAction()
    object PauseReading : MenuAction()
    object ResumeReading : MenuAction()
    object StopReading : MenuAction()
    object ChangeVoice : MenuAction()

    // Favorites — src/Menu.cpp::menuDefFavorites
    object AddBookmark : MenuAction()
    object RemoveBookmark : MenuAction()
    object ListBookmarks : MenuAction()
    object ShowFavoritesInTab : MenuAction()
    object SaveTabGroup : MenuAction()
    object RestoreTabGroup : MenuAction()

    // Settings — src/Menu.cpp::menuDefSettings
    object ChangeLanguage : MenuAction()
    object Options : MenuAction()
    object AdvancedSettings : MenuAction()
    object AdvancedOptions : MenuAction()
    object Theme : MenuAction()

    // Help — src/Menu.cpp::menuDefHelp
    object Manual : MenuAction()
    object KeyboardShortcuts : MenuAction()
    object ManualOnWebsite : MenuAction()
    object VisitWebsite : MenuAction()
    object CheckUpdate : MenuAction()
    object ToggleRenderInfo : MenuAction()
    object ToggleCacheInfo : MenuAction()
    object About : MenuAction()

    // Debug — src/Menu.cpp::menuDefDebug
    object ShowLinks : MenuAction()
    object DownloadSymbols : MenuAction()
    object TestApp : MenuAction()
    object ShowNotification : MenuAction()

    // Commands added for the keyboard-shortcut layer
    // (src/Accelerators.cpp, 82 bound commands). Most mirror an existing
    // Win32 menu item; the ones below were either not in the menu bar
    // (because they are pure key bindings, like the Vim hjkl or the
    // scroll-half-page) or were dropped from the menu bar but kept as
    // commands. Each new command has a Win32 equivalent with the
    // same name in src/Commands.h.
    object BookView : MenuAction()            // CmdBookView
    object FacingView : MenuAction()          // CmdFacingView
    object SinglePageView : MenuAction()      // CmdSinglePageView
    object FindNext : MenuAction()            // CmdFindNext
    object FindPrev : MenuAction()            // CmdFindPrev
    object FindNextSel : MenuAction()         // CmdFindNextSel
    object FindPrevSel : MenuAction()         // CmdFindPrevSel
    object InvertColors : MenuAction()        // CmdInvertColors
    object ZoomIn : MenuAction()              // CmdZoomIn
    object ZoomOut : MenuAction()             // CmdZoomOut
    object MoveTabLeft : MenuAction()         // CmdMoveTabLeft
    object MoveTabRight : MenuAction()        // CmdMoveTabRight
    object NextTab : MenuAction()             // CmdNextTab
    object PrevTab : MenuAction()             // CmdPrevTab
    object NextTabSmart : MenuAction()        // CmdNextTabSmart
    object PrevTabSmart : MenuAction()        // CmdPrevTabSmart
    object Screenshot : MenuAction()          // CmdScreenshot
    object ScrollUp : MenuAction()            // CmdScrollUp
    object ScrollDown : MenuAction()          // CmdScrollDown
    object ScrollLeft : MenuAction()          // CmdScrollLeft
    object ScrollRight : MenuAction()         // CmdScrollRight
    object ScrollUpHalfPage : MenuAction()    // CmdScrollUpHalfPage
    object ScrollDownHalfPage : MenuAction()  // CmdScrollDownHalfPage
    object ScrollUpPage : MenuAction()        // CmdScrollUpPage
    object ScrollDownPage : MenuAction()      // CmdScrollDownPage
    object ScrollLeftPage : MenuAction()      // CmdScrollLeftPage
    object ScrollRightPage : MenuAction()     // CmdScrollRightPage
    object PasteClipboardImage : MenuAction() // CmdPasteClipboardImage
    object ReopenLastClosedFile : MenuAction() // CmdReopenLastClosedFile
    object ReloadDocument : MenuAction()      // CmdReloadDocument
    object TogglePageInfo : MenuAction()      // CmdTogglePageInfo
    object ToggleCursorPosition : MenuAction() // CmdToggleCursorPosition
    object ToggleZoom : MenuAction()          // CmdToggleZoom
    object PresentationBlack : MenuAction()   // CmdPresentationBlackBackground
    object PresentationWhite : MenuAction()   // CmdPresentationWhiteBackground
    object CreateShortcutToFile : MenuAction() // CmdCreateShortcutToFile
    object DuplicateInNewWindow : MenuAction() // CmdDuplicateInNewWindow
    object CommandPaletteOnlyTabs : MenuAction() // CmdCommandPaletteOnlyTabs
    object MoveFrameFocus : MenuAction()      // CmdMoveFrameFocus

    // Context-menu items. The Win32 long-press menu (src/Menu.cpp
    // has 11 definitions, ~70 commands) is ported in
    // ContextMenu.kt; each row here is a Cmd* that the menu
    // dispatches to the same handleMenu() the hamburger uses.
    object OpenSelectedDocument : MenuAction() // CmdOpenSelectedDocument
    object PinSelectedDocument : MenuAction()  // CmdPinSelectedDocument
    object ForgetSelectedDocument : MenuAction() // CmdForgetSelectedDocument
    object CopyLinkTarget : MenuAction()       // CmdCopyLinkTarget
    object CopyComment : MenuAction()          // CmdCopyComment
    object SaveAttachment : MenuAction()       // CmdSaveAttachment
    object ShowErrors : MenuAction()           // CmdShowErrors
    object EditAnnotations : MenuAction()      // CmdEditAnnotations
    object DeleteAnnotation : MenuAction()     // CmdDeleteAnnotation
    object CopyImage : MenuAction()            // CmdCopyImage
    object CropImage : MenuAction()            // CmdCropImage
    object ResizeImage : MenuAction()          // CmdResizeImage
    object SaveImage : MenuAction()            // CmdSaveImage
    object ShowPdfInfo : MenuAction()          // CmdPdShowInfo
    object DocumentShowOutline : MenuAction()  // CmdDocumentShowOutline
    object DocumentExtractText : MenuAction()   // CmdDocumentExtractText
    object PdfExtractPages : MenuAction()      // CmdPdfExtractPages
    object PdfDeletePages : MenuAction()       // CmdPdfDeletePages
    object PdfCompress : MenuAction()          // CmdPdfCompress
    object PdfDecompress : MenuAction()        // CmdPdfDecompress
    object PdfEncrypt : MenuAction()           // CmdPdfEncrypt
    object PdfDecrypt : MenuAction()           // CmdPdfDecrypt
    object PdfBake : MenuAction()              // CmdPdfBake
    object PasteImageFromClipboard : MenuAction() // CmdPasteClipboardImage (context)
    object FavoriteAdd : MenuAction()          // CmdFavoriteAdd
    object FavoriteDel : MenuAction()          // CmdFavoriteDel
    object FavoriteToggle : MenuAction()       // CmdFavoriteToggle
    object FavoriteShowInTab : MenuAction()    // CmdFavoriteShowInTab
    object TranslateSelectionGoogle : MenuAction() // CmdTranslateSelectionWithGoogle
    object TranslateSelectionDeepL : MenuAction()  // CmdTranslateSelectionWithDeepL
    object TranslateSelectionGrokBuild : MenuAction() // CmdTranslateSelectionWithGrokBuild
    object TranslateSelectionClaudeCode : MenuAction() // CmdTranslateSelectionWithClaudeCode
    object TranslateSelectionOpenAICodex : MenuAction() // CmdTranslateSelectionWithOpenAICodex
    object SearchSelectionGoogle : MenuAction() // CmdSearchSelectionWithGoogle
    object SearchSelectionBing : MenuAction()   // CmdSearchSelectionWithBing
    object SearchSelectionWikipedia : MenuAction() // CmdSearchSelectionWithWikipedia
    object SearchSelectionGoogleScholar : MenuAction() // CmdSearchSelectionWithGoogleScholar

    // Tab commands (src/Tabs.cpp + src/CommandPalette.cpp). Most
    // are simple list operations on the existing `tabs` state;
    // the rest (SetTabColor, TabGroupSave/Restore) are partial
    // because the underlying mechanism (color tags, named
    // groups) isn't part of the port yet.
    object CloseOtherTabs : MenuAction()        // CmdCloseOtherTabs
    object CloseTabsToTheRight : MenuAction()  // CmdCloseTabsToTheRight
    object CloseTabsToTheLeft : MenuAction()   // CmdCloseTabsToTheLeft
    object CloseAllTabs : MenuAction()         // CmdCloseAllTabs
    object DuplicateInNewTab : MenuAction()    // CmdDuplicateInNewTab
    object SetTabColor : MenuAction()          // CmdSetTabColor (no real handler)

    // ToC commands (src/TableOfContents.cpp).
    object ExpandAll : MenuAction()             // CmdExpandAll
    object CollapseAll : MenuAction()           // CmdCollapseAll
    object ExpandToCurrentPage : MenuAction()   // CmdExpandToCurrentPage
    object TocExpandToLevel1 : MenuAction()     // CmdTocExpandToLevel1 (no real handler)
    object TocExpandToLevel2 : MenuAction()     // CmdTocExpandToLevel2 (no real handler)
    object TocExpandToLevel3 : MenuAction()     // CmdTocExpandToLevel3 (no real handler)
    object TocCollapseSameLevel : MenuAction()  // CmdTocCollapseSameLevel (no real handler)

    // Favorite / bookmark navigation commands (src/Favorites.cpp).
    // The Android port already has the Add / Remove / Show
    // variants under different names; the navigation commands
    // jump the page to the next / previous bookmark in the
    // active document, and the sort toggle reorders the
    // Bookmarks sidebar by name vs. by page.
    object GoToNextFavorite : MenuAction()       // CmdGoToNextFavorite
    object GoToPrevFavorite : MenuAction()       // CmdGoToPrevFavorite
    object ToggleFavoritesSort : MenuAction()    // CmdToggleFavoritesSort
    object CommandPaletteFavorites : MenuAction() // CmdCommandPaletteFavorites
}
