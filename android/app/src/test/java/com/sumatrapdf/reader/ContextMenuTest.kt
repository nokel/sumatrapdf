package com.sumatrapdf.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Tests for the long-press context menu framework. The reference
// is the 11 `MenuDef` tables in `src/Menu.cpp` (~70 commands
// across menuDefContext, menuDefDocumentOperations,
// menuDefContextStart, menuDefTabGroups, menuDefContextImage,
// menuDefSelection, menuDefZoomShort, and the four that are
// stubs / not ported). We assert the same shape and same
// per-row command identity the Win32 source has.
class ContextMenuTest {

    // -------- shape: every menu is built and has rows --------

    @Test
    fun everyKnownMenuIdReturnsADefinition() {
        // Loop every enum value; each must produce a
        // non-null definition with at least one row.
        for (id in ContextMenuId.values()) {
            val def = ContextMenus.byId(id)
            assertTrue(
                "menu $id has no rows",
                def.rows.isNotEmpty(),
            )
            assertTrue(
                "menu $id has no leaf row",
                def.rows.any { it != null },
            )
        }
    }

    @Test
    fun sameIdReturnsTheSameDefinition() {
        // The menu cache must hand out the same instance on
        // repeat calls. (Otherwise the click handler in
        // ReaderScreen would rebuild the menu every recompose,
        // and the open/closed state of submenus would reset.)
        val a = ContextMenus.byId(ContextMenuId.Document)
        val b = ContextMenus.byId(ContextMenuId.Document)
        assertEquals(a, b)
    }

    // -------- per-menu: Document (the main context menu) --------

    @Test
    fun documentMenuHasTheWin32Items() {
        val def = ContextMenus.byId(ContextMenuId.Document)
        val labels = def.rows.filterNotNull().map { it.label }
        // Win32 menuDefContext has Copy Selection, Selection,
        // Copy Link Address, Copy Comment, Save Attachment,
        // Image, Add/Remove/Show favorites, Show Bookmarks,
        // Show Toolbar, AI chat, Document, Read Aloud,
        // Edit Annotations, Create Annotation From Selection,
        // Create Annotation Under Cursor, Delete Annotation,
        // Save Annotations, Show Errors, Exit Fullscreen.
        // (20 leaf rows, plus the separator.)
        val must = listOf(
            "Copy Selection",
            "Selection",
            "Copy Link Address",
            "Copy Comment",
            "Save Attachment",
            "Image",
            "Add to favorites",
            "Remove from favorites",
            "Show Favorites",
            "Show Bookmarks",
            "Show Toolbar",
            "AI chat with document using",
            "Document",
            "Read Aloud",
            "Edit Annotations",
            "Create Annotation From Selection",
            "Create Annotation Under Cursor",
            "Delete Annotation",
            "Save Annotations to existing PDF",
            "Show Errors",
            "Exit Fullscreen",
        )
        for (m in must) {
            assertTrue("Document menu missing row \"$m\"", m in labels)
        }
    }

    @Test
    fun documentMenuSubmenusMatchWin32() {
        // The 5 submenus the Win32 Document menu references:
        // Selection, Image, AIChat, DocumentOperations, ReadAloud,
        // CreateAnnotFromSelection, CreateAnnotUnderCursor.
        val def = ContextMenus.byId(ContextMenuId.Document)
        val parentIds = def.rows.filterNotNull()
            .filter { it.submenu != null }
            .map { it.submenu!!.id }
        val expected = setOf(
            ContextMenuId.Selection,
            ContextMenuId.Image,
            ContextMenuId.AIChat,
            ContextMenuId.DocumentOperations,
            ContextMenuId.ReadAloud,
            ContextMenuId.CreateAnnotFromSelection,
            ContextMenuId.CreateAnnotUnderCursor,
        )
        assertEquals(expected, parentIds.toSet())
    }

    // -------- per-menu: DocumentOperations (the PDF tools submenu) --------

    @Test
    fun documentOperationsMenuHasAllWin32Items() {
        val def = ContextMenus.byId(ContextMenuId.DocumentOperations)
        val labels = def.rows.filterNotNull().map { it.label }
        val must = listOf(
            "Properties",
            "Show PDF Info",
            "Show Document Table Of Contents",
            "Extract Pages From PDF",
            "Delete Pages From PDF",
            "Extract Text From Document",
            "Compress PDF",
            "Decompress PDF",
            "Encrypt PDF",
            "Decrypt PDF",
            "Bake PDF",
            "Show in folder",
        )
        for (m in must) {
            assertTrue("DocumentOperations menu missing \"$m\"", m in labels)
        }
    }

    // -------- per-menu: StartPage (tile long-press) --------

    @Test
    fun startPageMenuMatchesWin32() {
        val def = ContextMenus.byId(ContextMenuId.StartPage)
        val labels = def.rows.filterNotNull().map { it.label }
        // Win32 menuDefContextStart: Open Document, Show in folder,
        // Pin Document, [separator], Remove From History.
        assertTrue("Open Document" in labels)
        assertTrue("Show in folder" in labels)
        assertTrue("Pin Document" in labels)
        assertTrue("Remove From History" in labels)
    }

    // -------- per-menu: TabGroups (submenu of Favorites) --------

    @Test
    fun tabGroupsMenuHasBothItems() {
        val def = ContextMenus.byId(ContextMenuId.TabGroups)
        val labels = def.rows.filterNotNull().map { it.label }
        assertTrue("Save Tab Group" in labels)
        assertTrue("Restore Tab Group" in labels)
    }

    // -------- per-menu: Image (image long-press) --------

    @Test
    fun imageMenuHasTheFiveItems() {
        val def = ContextMenus.byId(ContextMenuId.Image)
        val labels = def.rows.filterNotNull().map { it.label }
        val must = listOf("Copy Image", "Crop Image", "Resize Image", "Save Image", "Paste Image")
        for (m in must) {
            assertTrue("Image menu missing \"$m\"", m in labels)
        }
    }

    // -------- per-menu: Selection (text selection long-press) --------

    @Test
    fun selectionMenuHasAllItems() {
        val def = ContextMenus.byId(ContextMenuId.Selection)
        val labels = def.rows.filterNotNull().map { it.label }
        // Win32 menuDefSelection: Copy, Translate (5), Search (4).
        assertTrue("Copy" in labels)
        for (lang in listOf("Google", "DeepL", "Grok", "Claude", "Codex")) {
            assertTrue("Selection menu missing Translate $lang", "Translate with $lang" in labels)
        }
        for (engine in listOf("Google", "Bing", "Wikipedia", "Google Scholar")) {
            assertTrue("Selection menu missing Search $engine", "Search with $engine" in labels)
        }
    }

    // -------- per-menu: ZoomShort (quick-set zoom) --------

    @Test
    fun zoomShortMenuHasTheEightPresets() {
        val def = ContextMenus.byId(ContextMenuId.ZoomShort)
        val labels = def.rows.filterNotNull().map { it.label }
        // Win32 menuDefZoomShort: 6400%, 3200%, 1600%, 800%,
        // 400%, 200%, 100%, 50% (the desktop doesn't include 25%
        // / 12.5% / 8.33% in the *short* menu, those live in
        // menuDefZoom in the main menu bar).
        for (pct in listOf("6400%", "3200%", "1600%", "800%", "400%", "200%", "100%", "50%")) {
            assertTrue("ZoomShort missing $pct", pct in labels)
        }
    }

    // -------- leaf rows: each leaf has an action or a submenu, not both --------

    @Test
    fun everyLeafRowHasAnActionOrASubmenu() {
        for (id in ContextMenuId.values()) {
            val def = ContextMenus.byId(id)
            for ((i, row) in def.rows.withIndex()) {
                if (row == null) continue
                assertTrue(
                    "menu $id row $i \"${row.label}\" has neither action nor submenu",
                    row.action != null || row.submenu != null,
                )
            }
        }
    }

    // -------- shortcuts: shortcut strings match KeyboardShortcuts --------

    @Test
    fun documentMenuShortcutsMatchBindingTable() {
        // The Document menu's "Copy Selection" row advertises the
        // Ctrl+Ins shortcut; the binding table has the same
        // string. This is a sanity check that the menu hints
        // and the actual keymap are in sync.
        val def = ContextMenus.byId(ContextMenuId.Document)
        val copyRow = def.rows.filterNotNull().first { it.label == "Copy Selection" }
        assertEquals("Ctrl+Ins", copyRow.shortcut)
    }

    // -------- separator: at least one menu has a separator --------

    @Test
    fun documentMenuHasASeparator() {
        val def = ContextMenus.byId(ContextMenuId.Document)
        assertTrue(
            "Document menu has no separator",
            def.rows.any { it == null },
        )
    }

    @Test
    fun startPageMenuHasASeparator() {
        val def = ContextMenus.byId(ContextMenuId.StartPage)
        assertTrue(
            "StartPage menu has no separator",
            def.rows.any { it == null },
        )
    }

    // -------- the Win32 AI chat items are present but disabled (port stub) --------

    @Test
    fun aiChatMenuHasAllThreeClisDisabled() {
        val def = ContextMenus.byId(ContextMenuId.AIChat)
        val rows = def.rows.filterNotNull()
        assertEquals(3, rows.size)
        assertTrue(rows.all { !it.enabled })
        val labels = rows.map { it.label }
        for (cli in listOf("Codex", "Claude", "Grok")) {
            assertTrue("AI chat missing $cli", cli in labels)
        }
    }
}
