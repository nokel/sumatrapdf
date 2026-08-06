package com.sumatrapdf.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup

// Long-press context menu framework. A port of the 11 `MenuDef`
// definitions in `src/Menu.cpp` that fire on right-click (long-press
// on touch). The Win32 side has:
//
//   menuDefContext                      (14 items, main)
//   menuDefDocumentOperations           (12 items, PDF tools submenu)
//   menuDefContextStart                 (4 items,  start-page tile long-press)
//   menuDefTabGroups                    (2 items,  tab groups submenu)
//   menuDefContextImage                 (5 items,  image long-press)
//   menuDefSelection                    (10 items, text selection long-press)
//   menuDefZoomShort                    (8 items,  zoom quick-set)
//   menuDefCreateAnnotFromSelection     (5 items,  annot from sel submenu)
//   menuDefCreateAnnotUnderCursor       (12 items, annot under cursor submenu)
//   menuDefContextReadAloud             (— items, dynamic — built by code)
//   menuDefDocumentAIChat               (— items, dynamic)
//
// We model the framework with three types:
//   * `ContextMenuItem` is one row in a menu (label, action, checked,
//     shortcut, optional submenu). The same row can be a leaf that
//     dispatches an action or a parent that opens a sub-menu.
//   * `ContextMenuDefinition` is the static structure of a menu —
//     a list of `ContextMenuItem`s with an `id` (which port of the
//     Win32 menu it is).
//   * `ContextMenuPopup` is the Compose popup that renders a menu
//     at a given offset and dispatches the picked action to a
//     `(MenuAction) -> Unit` callback.
//
// Items whose action is a no-op in this port (annotations, AI chat,
// the image ops) still appear in the menu with their real label, so
// the user sees the same surface as on Windows — and the menu
// dispatches a `MenuAction` that `handleMenu` will surface as
// "Not implemented in this build". That is the toggle rule applied
// at the row level: the user can see the missing piece is missing.

// One row in a context menu. `action` is the MenuAction to dispatch
// when the row is tapped; `submenu` overrides `action` when present
// (a parent row opens its submenu, never dispatches a MenuAction).
// `checked` makes the row a checkable toggle (like Win32's
// CmdToggleMenuBar).
data class ContextMenuItem(
    val label: String,
    val action: MenuAction? = null,
    val submenu: ContextMenuDefinition? = null,
    val checked: Boolean = false,
    val shortcut: String? = null,
    val enabled: Boolean = true,
)

// A static menu definition. The list is rendered top-to-bottom;
// `null` entries are separators (mirrors Win32's `kMenuSeparator`).
data class ContextMenuDefinition(
    val id: ContextMenuId,
    val rows: List<ContextMenuItem?>,   // null = separator
)

// Identifies which port of the Win32 menu this is. The id is the
// only thing the rest of the app needs to know to open the right
// menu on long-press.
enum class ContextMenuId {
    Document,            // long-press on document body
    DocumentOperations,  // submenu of Document
    StartPage,           // long-press on a tile in the start page
    TabGroups,           // submenu of Favorites ▸ Tab Groups
    Image,               // long-press on an image
    Selection,           // long-press on selected text
    ZoomShort,           // quick-set zoom (not yet a submenu of any)
    CreateAnnotFromSelection, // submenu of Selection
    CreateAnnotUnderCursor,   // submenu of Document
    ReadAloud,           // long-press while read-aloud is on
    AIChat,              // long-press to invoke an AI chat CLI
}

object ContextMenus {

    // The set of menus the port knows about, indexed by id. The
    // first call to `byId(id)` builds them; subsequent calls
    // return the cached definitions. (The cache is not a perf
    // concern; the real reason to keep them in one place is so the
    // "which Win32 menu did this come from?" question has a single
    // answer for every row.)
    private val cache: MutableMap<ContextMenuId, ContextMenuDefinition> = mutableMapOf()

    fun byId(id: ContextMenuId): ContextMenuDefinition {
        return cache.getOrPut(id) { build(id) }
    }

    private fun build(id: ContextMenuId): ContextMenuDefinition = when (id) {
        ContextMenuId.Document -> documentContextMenu()
        ContextMenuId.DocumentOperations -> documentOperationsMenu()
        ContextMenuId.StartPage -> startPageContextMenu()
        ContextMenuId.TabGroups -> tabGroupsMenu()
        ContextMenuId.Image -> imageContextMenu()
        ContextMenuId.Selection -> selectionContextMenu()
        ContextMenuId.ZoomShort -> zoomShortMenu()
        ContextMenuId.CreateAnnotFromSelection -> createAnnotFromSelectionMenu()
        ContextMenuId.CreateAnnotUnderCursor -> createAnnotUnderCursorMenu()
        ContextMenuId.ReadAloud -> readAloudContextMenu()
        ContextMenuId.AIChat -> aiChatMenu()
    }

    // ---- menuDefContext (14 main items, the long-press on document body) ----
    private fun documentContextMenu(): ContextMenuDefinition = ContextMenuDefinition(
        ContextMenuId.Document,
        listOf(
            ContextMenuItem("Copy Selection", action = MenuAction.CopySelection, shortcut = "Ctrl+Ins"),
            ContextMenuItem("Selection", submenu = byId(ContextMenuId.Selection)),
            ContextMenuItem("Copy Link Address", action = MenuAction.CopyLinkTarget),
            ContextMenuItem("Copy Comment", action = MenuAction.CopyComment),
            ContextMenuItem("Save Attachment", action = MenuAction.SaveAttachment),
            ContextMenuItem("Image", submenu = byId(ContextMenuId.Image)),
            ContextMenuItem("Add to favorites", action = MenuAction.FavoriteAdd),
            ContextMenuItem("Remove from favorites", action = MenuAction.FavoriteDel),
            ContextMenuItem("Show Favorites", action = MenuAction.FavoriteToggle),
            ContextMenuItem("Show Bookmarks", action = MenuAction.ShowBookmarks, shortcut = "F12"),
            ContextMenuItem("Show Toolbar", action = MenuAction.ShowToolbar, shortcut = "F8"),
            null, // separator
            ContextMenuItem("AI chat with document using", submenu = byId(ContextMenuId.AIChat)),
            ContextMenuItem("Document", submenu = byId(ContextMenuId.DocumentOperations)),
            ContextMenuItem("Read Aloud", submenu = byId(ContextMenuId.ReadAloud)),
            ContextMenuItem("Edit Annotations", action = MenuAction.EditAnnotations),
            ContextMenuItem("Create Annotation From Selection", submenu = byId(ContextMenuId.CreateAnnotFromSelection)),
            ContextMenuItem("Create Annotation Under Cursor", submenu = byId(ContextMenuId.CreateAnnotUnderCursor)),
            ContextMenuItem("Delete Annotation", action = MenuAction.DeleteAnnotation),
            ContextMenuItem("Save Annotations to existing PDF", action = MenuAction.SaveAnnotations, shortcut = "Ctrl+Shift+S"),
            ContextMenuItem("Show Errors", action = MenuAction.ShowErrors),
            ContextMenuItem("Exit Fullscreen", action = MenuAction.Fullscreen, shortcut = "F11"),
        ),
    )

    // ---- menuDefDocumentOperations (12 PDF tools) ----
    private fun documentOperationsMenu(): ContextMenuDefinition = ContextMenuDefinition(
        ContextMenuId.DocumentOperations,
        listOf(
            ContextMenuItem("Properties", action = MenuAction.Properties, shortcut = "Ctrl+D"),
            ContextMenuItem("Show PDF Info", action = MenuAction.ShowPdfInfo),
            ContextMenuItem("Show Document Table Of Contents", action = MenuAction.DocumentShowOutline),
            ContextMenuItem("Extract Pages From PDF", action = MenuAction.PdfExtractPages),
            ContextMenuItem("Delete Pages From PDF", action = MenuAction.PdfDeletePages),
            ContextMenuItem("Extract Text From Document", action = MenuAction.DocumentExtractText),
            ContextMenuItem("Compress PDF", action = MenuAction.PdfCompress),
            ContextMenuItem("Decompress PDF", action = MenuAction.PdfDecompress),
            ContextMenuItem("Encrypt PDF", action = MenuAction.PdfEncrypt),
            ContextMenuItem("Decrypt PDF", action = MenuAction.PdfDecrypt),
            ContextMenuItem("Bake PDF", action = MenuAction.PdfBake),
            ContextMenuItem("Show in folder", action = MenuAction.ShowInFolder),
        ),
    )

    // ---- menuDefContextStart (4 start-page tile items) ----
    private fun startPageContextMenu(): ContextMenuDefinition = ContextMenuDefinition(
        ContextMenuId.StartPage,
        listOf(
            ContextMenuItem("Open Document", action = MenuAction.OpenSelectedDocument),
            ContextMenuItem("Show in folder", action = MenuAction.ShowInFolder),
            ContextMenuItem("Pin Document", action = MenuAction.PinSelectedDocument),
            null, // separator
            ContextMenuItem("Remove From History", action = MenuAction.ForgetSelectedDocument),
        ),
    )

    // ---- menuDefTabGroups (2 items) ----
    private fun tabGroupsMenu(): ContextMenuDefinition = ContextMenuDefinition(
        ContextMenuId.TabGroups,
        listOf(
            ContextMenuItem("Save Tab Group", action = MenuAction.SaveTabGroup),
            ContextMenuItem("Restore Tab Group", action = MenuAction.RestoreTabGroup),
        ),
    )

    // ---- menuDefContextImage (5 image long-press items) ----
    private fun imageContextMenu(): ContextMenuDefinition = ContextMenuDefinition(
        ContextMenuId.Image,
        listOf(
            ContextMenuItem("Copy Image", action = MenuAction.CopyImage),
            ContextMenuItem("Crop Image", action = MenuAction.CropImage),
            ContextMenuItem("Resize Image", action = MenuAction.ResizeImage),
            ContextMenuItem("Save Image", action = MenuAction.SaveImage),
            ContextMenuItem("Paste Image", action = MenuAction.PasteImageFromClipboard),
        ),
    )

    // ---- menuDefSelection (10 text selection items) ----
    private fun selectionContextMenu(): ContextMenuDefinition = ContextMenuDefinition(
        ContextMenuId.Selection,
        listOf(
            ContextMenuItem("Copy", action = MenuAction.CopySelection, shortcut = "Ctrl+C"),
            ContextMenuItem("Translate with Google", action = MenuAction.TranslateSelectionGoogle),
            ContextMenuItem("Translate with DeepL", action = MenuAction.TranslateSelectionDeepL),
            ContextMenuItem("Translate with Grok", action = MenuAction.TranslateSelectionGrokBuild),
            ContextMenuItem("Translate with Claude", action = MenuAction.TranslateSelectionClaudeCode),
            ContextMenuItem("Translate with Codex", action = MenuAction.TranslateSelectionOpenAICodex),
            ContextMenuItem("Search with Google", action = MenuAction.SearchSelectionGoogle),
            ContextMenuItem("Search with Bing", action = MenuAction.SearchSelectionBing),
            ContextMenuItem("Search with Wikipedia", action = MenuAction.SearchSelectionWikipedia),
            ContextMenuItem("Search with Google Scholar", action = MenuAction.SearchSelectionGoogleScholar),
        ),
    )

    // ---- menuDefZoomShort (8 quick-zoom items) ----
    private fun zoomShortMenu(): ContextMenuDefinition = ContextMenuDefinition(
        ContextMenuId.ZoomShort,
        listOf(
            ContextMenuItem("6400%", action = MenuAction.ZoomPercent(6400)),
            ContextMenuItem("3200%", action = MenuAction.ZoomPercent(3200)),
            ContextMenuItem("1600%", action = MenuAction.ZoomPercent(1600)),
            ContextMenuItem("800%", action = MenuAction.ZoomPercent(800)),
            ContextMenuItem("400%", action = MenuAction.ZoomPercent(400)),
            ContextMenuItem("200%", action = MenuAction.ZoomPercent(200)),
            ContextMenuItem("100%", action = MenuAction.ZoomPercent(100)),
            ContextMenuItem("50%", action = MenuAction.ZoomPercent(50)),
        ),
    )

    // ---- menuDefCreateAnnotFromSelection (5 items) ----
    private fun createAnnotFromSelectionMenu(): ContextMenuDefinition = ContextMenuDefinition(
        ContextMenuId.CreateAnnotFromSelection,
        listOf(
            ContextMenuItem("Highlight", action = MenuAction.ShowLinks),  // CmdCreateAnnotHighlight
            ContextMenuItem("Underline", action = MenuAction.ShowLinks),  // CmdCreateAnnotUnderline
            ContextMenuItem("Strike Out", action = MenuAction.ShowLinks), // CmdCreateAnnotStrikeOut
            ContextMenuItem("Squiggly", action = MenuAction.ShowLinks),   // CmdCreateAnnotSquiggly
            ContextMenuItem("Redact", action = MenuAction.ShowLinks),     // CmdCreateAnnotRedact
        ),
    )

    // ---- menuDefCreateAnnotUnderCursor (12 items) ----
    private fun createAnnotUnderCursorMenu(): ContextMenuDefinition = ContextMenuDefinition(
        ContextMenuId.CreateAnnotUnderCursor,
        listOf(
            ContextMenuItem("Text", action = MenuAction.ShowLinks),       // CmdCreateAnnotText
            ContextMenuItem("Link", action = MenuAction.ShowLinks),       // CmdCreateAnnotLink
            ContextMenuItem("Free Text", action = MenuAction.ShowLinks),  // CmdCreateAnnotFreeText
            ContextMenuItem("Line", action = MenuAction.ShowLinks),       // CmdCreateAnnotLine
            ContextMenuItem("Square", action = MenuAction.ShowLinks),     // CmdCreateAnnotSquare
            ContextMenuItem("Circle", action = MenuAction.ShowLinks),     // CmdCreateAnnotCircle
            ContextMenuItem("Polygon", action = MenuAction.ShowLinks),    // CmdCreateAnnotPolygon
            ContextMenuItem("Poly Line", action = MenuAction.ShowLinks),  // CmdCreateAnnotPolyLine
            ContextMenuItem("Stamp", action = MenuAction.ShowLinks),      // CmdCreateAnnotStamp
            ContextMenuItem("Caret", action = MenuAction.ShowLinks),      // CmdCreateAnnotCaret
            ContextMenuItem("Ink", action = MenuAction.ShowLinks),        // CmdCreateAnnotInk
            ContextMenuItem("File Attachment", action = MenuAction.ShowLinks), // CmdCreateAnnotFileAttachment
        ),
    )

    // ---- menuDefContextReadAloud (built by code, dynamic) ----
    // Win32 builds this from the current TTS state; for the
    // port the static version covers the common commands.
    private fun readAloudContextMenu(): ContextMenuDefinition = ContextMenuDefinition(
        ContextMenuId.ReadAloud,
        listOf(
            ContextMenuItem("Start Reading From Top", action = MenuAction.StartReadingTop),
            ContextMenuItem("Pause", action = MenuAction.PauseReading),
            ContextMenuItem("Resume", action = MenuAction.ResumeReading),
            ContextMenuItem("Stop", action = MenuAction.StopReading),
        ),
    )

    // ---- menuDefDocumentAIChat (built by code, dynamic) ----
    // Win32 lists the available AI chat CLIs (Codex, Claude, Grok);
    // the port stubs them out — the user sees the menu but the
    // items dispatch to "not implemented".
    private fun aiChatMenu(): ContextMenuDefinition = ContextMenuDefinition(
        ContextMenuId.AIChat,
        listOf(
            ContextMenuItem("Codex", action = MenuAction.TranslateOpenAICodex, enabled = false),
            ContextMenuItem("Claude", action = MenuAction.TranslateClaudeCode, enabled = false),
            ContextMenuItem("Grok", action = MenuAction.TranslateGrokBuild, enabled = false),
        ),
    )
}

// The Compose popup that renders a context menu at a given anchor.
// Win32's right-click pops the menu at the cursor position;
// Compose's `Popup` does the same with an `offset`. The popup
// supports a single-level submenu (rendered to the right of the
// parent row, vertically aligned with the parent) — the Win32
// menu bar cascades any depth, but on touch a one-level
// submenu is the right shape: deeper nesting is a tap-back-and-
// tap-forward loop, which is what Android users expect.
@Composable
fun ContextMenuPopup(
    definition: ContextMenuDefinition,
    anchor: Offset,
    onPick: (MenuAction) -> Unit,
    onDismiss: () -> Unit,
) {
    var openSubmenu by remember { mutableStateOf<Pair<Int, ContextMenuDefinition>?>(null) }
    var submenuAnchor by remember { mutableStateOf(Offset.Zero) }
    var parentRowWidth by remember { mutableStateOf(0) }

    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(anchor.x.toInt(), anchor.y.toInt()),
        onDismissRequest = onDismiss,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            shadowElevation = 8.dp,
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.widthIn(min = 220.dp, max = 360.dp),
        ) {
            Column {
                definition.rows.forEachIndexed { index, item ->
                    if (item == null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                drawLine(
                                    color = Color.Gray.copy(alpha = 0.4f),
                                    start = Offset(0f, 0f),
                                    end = Offset(size.width, 0f),
                                    strokeWidth = 1f,
                                )
                            }
                        }
                    } else {
                        ContextMenuRow(
                            item = item,
                            onTap = {
                                if (item.submenu != null) {
                                    openSubmenu = Pair(index, item.submenu)
                                    submenuAnchor = Offset(
                                        x = anchor.x + parentRowWidth,
                                        y = anchor.y,
                                    )
                                } else if (item.action != null) {
                                    onPick(item.action)
                                    onDismiss()
                                }
                            },
                            onRowWidthChange = { w -> parentRowWidth = w },
                        )
                    }
                }
            }
        }
    }

    // The open submenu (if any) is rendered as a SECOND popup
    // positioned to the right of the parent. Same Win32-style
    // cascading trick the hamburger uses (see HamburgerMenu.kt):
    // two Popups, the second anchored to the first's right
    // edge. The vertical alignment of the submenu's first row
    // is the same as the parent's first row, so the menu opens
    // at the same y as the row that triggered it.
    openSubmenu?.let { (_, sub) ->
        Popup(
            alignment = Alignment.TopStart,
            offset = IntOffset(submenuAnchor.x.toInt(), submenuAnchor.y.toInt()),
            onDismissRequest = { openSubmenu = null },
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                shadowElevation = 8.dp,
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.widthIn(min = 220.dp, max = 360.dp),
            ) {
                Column {
                    sub.rows.forEach { subItem ->
                        if (subItem == null) {
                            Canvas(modifier = Modifier.fillMaxWidth().height(1.dp)) {
                                drawLine(
                                    color = Color.Gray.copy(alpha = 0.4f),
                                    start = Offset(0f, 0f),
                                    end = Offset(size.width, 0f),
                                    strokeWidth = 1f,
                                )
                            }
                        } else {
                            ContextMenuRow(
                                item = subItem,
                                onTap = {
                                    if (subItem.action != null && subItem.submenu == null) {
                                        onPick(subItem.action)
                                        onDismiss()
                                    }
                                },
                                onRowWidthChange = { },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextMenuRow(
    item: ContextMenuItem,
    onTap: () -> Unit,
    onRowWidthChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = item.enabled) { onTap() }
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .onSizeChanged { onRowWidthChange(it.width) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.checked) {
            Text(
                "✓",
                modifier = Modifier.width(16.dp),
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Spacer(modifier = Modifier.width(16.dp))
        }
        Text(
            item.label,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (item.enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            },
        )
        if (item.shortcut != null) {
            Text(
                item.shortcut,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
            )
        } else if (item.submenu != null) {
            // The "▶" arrow means the row opens a submenu, not
            // dispatches an action.
            Text(
                "▶",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}
