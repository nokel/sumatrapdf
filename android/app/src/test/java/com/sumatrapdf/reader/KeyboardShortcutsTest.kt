package com.sumatrapdf.reader

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Tests for the keyboard-shortcut layer. The reference for the binding
// table is `src/Accelerators.cpp` (728 LoC, 82 unique commands bound
// to keys). We assert two things here:
//
//   * `parseShortcut` is a faithful round-trip for the Win32
//     "Ctrl+N" / "Shift+F3" / "F11" / "Ctrl+Shift+Right" string form
//     used in src/Accelerators.cpp's `parseShortcutString`. The
//     round-trip is the way the user re-binds a command on Windows.
//
//   * `keyCodeToAction` returns the right `MenuAction` for the
//     canonical bindings. The MetaState is the raw
//     `KeyEvent.getMetaState()` value, so the test uses
//     `KeyEvent.META_*_ON` directly.
//
// The Android-KeyEvent half of the table is hand-mapped from
// `src/Accelerators.cpp` (which uses Win32 VK_* codes). Where a Win32
// code has no Android equivalent (e.g. VK_APPS), the binding is
// dropped from the table; the test asserts that explicitly with
// the comment "platform:android".
class KeyboardShortcutsTest {

    // -------- parseShortcut --------

    @Test
    fun parsesSingleLetterWithoutModifier() {
        val (kc, m) = KeyboardShortcuts.parseShortcut("K")!!
        assertEquals(KeyEvent.KEYCODE_K, kc)
        assertEquals(0, m)
    }

    @Test
    fun parsesControlLetter() {
        val (kc, m) = KeyboardShortcuts.parseShortcut("Ctrl+N")!!
        assertEquals(KeyEvent.KEYCODE_N, kc)
        assertEquals(KeyEvent.META_CTRL_ON, m)
    }

    @Test
    fun parsesControlShiftLetter() {
        val (kc, m) = KeyboardShortcuts.parseShortcut("Ctrl+Shift+S")!!
        assertEquals(KeyEvent.KEYCODE_S, kc)
        assertEquals(KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON, m)
    }

    @Test
    fun parsesFunctionKey() {
        val (kc, m) = KeyboardShortcuts.parseShortcut("F11")!!
        assertEquals(KeyEvent.KEYCODE_F11, kc)
        assertEquals(0, m)
    }

    @Test
    fun parsesShiftFunctionKey() {
        val (kc, m) = KeyboardShortcuts.parseShortcut("Shift+F3")!!
        assertEquals(KeyEvent.KEYCODE_F3, kc)
        assertEquals(KeyEvent.META_SHIFT_ON, m)
    }

    @Test
    fun parsesCtrlShiftArrow() {
        val (kc, m) = KeyboardShortcuts.parseShortcut("Ctrl+Shift+Right")!!
        assertEquals(KeyEvent.KEYCODE_DPAD_RIGHT, kc)
        assertEquals(KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON, m)
    }

    @Test
    fun parsesAltArrow() {
        val (kc, m) = KeyboardShortcuts.parseShortcut("Alt+Left")!!
        assertEquals(KeyEvent.KEYCODE_DPAD_LEFT, kc)
        assertEquals(KeyEvent.META_ALT_ON, m)
    }

    @Test
    fun parsesHomeAndEnd() {
        val (kcH, _) = KeyboardShortcuts.parseShortcut("Home")!!
        assertEquals(KeyEvent.KEYCODE_HOME, kcH)
        val (kcE, _) = KeyboardShortcuts.parseShortcut("End")!!
        // Android deprecated KEYCODE_END in API 26; the modern value
        // is KEYCODE_MOVE_END.
        assertEquals(KeyEvent.KEYCODE_MOVE_END, kcE)
    }

    @Test
    fun parsesNumpad() {
        val (kc, m) = KeyboardShortcuts.parseShortcut("Ctrl+Numpad0")!!
        assertEquals(KeyEvent.KEYCODE_NUMPAD_0, kc)
        assertEquals(KeyEvent.META_CTRL_ON, m)
    }

    @Test
    fun parsesPunctuation() {
        val (kc, _) = KeyboardShortcuts.parseShortcut("[")!!
        assertEquals(KeyEvent.KEYCODE_LEFT_BRACKET, kc)
    }

    @Test
    fun rejectsGarbage() {
        assertNull(KeyboardShortcuts.parseShortcut("NotAKey"))
        // "Ctrl+NotAKey" is also garbage; the second token is parsed
        // as a key name and the lookup returns null.
        assertNull(KeyboardShortcuts.parseShortcut("Ctrl+NotAKey"))
    }

    @Test
    fun roundTripsEveryBindingInTheTable() {
        // For every (keyCode, modifiers) in the table, serialise back
        // to a string with shortcutString() and re-parse it. The
        // (keyCode, modifiers) must come back equal. This is the
        // "the table is internally consistent" check.
        for (b in KeyboardShortcuts.BINDINGS) {
            val s = KeyboardShortcuts.shortcutString(b.keyCode, b.modifiers)
            val parsed = KeyboardShortcuts.parseShortcut(s)
            assertNotNull("could not parse round-tripped $s", parsed)
            assertEquals(b.keyCode, parsed!!.first)
            assertEquals(b.modifiers, parsed.second)
        }
    }

    // -------- keyCodeToAction --------

    @Test
    fun resolvesCtrlF4ToClose() {
        assertEquals(
            MenuAction.Close,
            KeyboardShortcuts.keyCodeToAction(KeyEvent.KEYCODE_F4, KeyEvent.META_CTRL_ON),
        )
    }

    @Test
    fun resolvesF3ToFindNext() {
        assertEquals(
            MenuAction.FindNext,
            KeyboardShortcuts.keyCodeToAction(KeyEvent.KEYCODE_F3, 0),
        )
    }

    @Test
    fun resolvesCtrlFToFindFirst() {
        // Ctrl+F is the universal "open find" shortcut and matches Win32
        // SumatraPDF. Regression: was unbound before this row was added —
        // adb-driven testing had to fall back to the menu, which made the
        // search submit path on the find toolbar hard to verify.
        assertEquals(
            MenuAction.FindFirst,
            KeyboardShortcuts.keyCodeToAction(KeyEvent.KEYCODE_F, KeyEvent.META_CTRL_ON),
        )
        // Bare F (no modifier) must NOT open the toolbar, only Ctrl+F —
        // otherwise a physical keyboard's "F" key would pop a dialog.
        assertNull(
            KeyboardShortcuts.keyCodeToAction(KeyEvent.KEYCODE_F, 0),
        )
    }

    @Test
    fun resolvesShiftF3ToFindPrev() {
        assertEquals(
            MenuAction.FindPrev,
            KeyboardShortcuts.keyCodeToAction(KeyEvent.KEYCODE_F3, KeyEvent.META_SHIFT_ON),
        )
    }

    @Test
    fun resolvesSpaceToScrollDownPage() {
        assertEquals(
            MenuAction.ScrollDownPage,
            KeyboardShortcuts.keyCodeToAction(KeyEvent.KEYCODE_SPACE, 0),
        )
    }

    @Test
    fun resolvesShiftSpaceToScrollUpPage() {
        assertEquals(
            MenuAction.ScrollUpPage,
            KeyboardShortcuts.keyCodeToAction(KeyEvent.KEYCODE_SPACE, KeyEvent.META_SHIFT_ON),
        )
    }

    @Test
    fun resolvesVimHJKL() {
        assertEquals(MenuAction.ScrollLeft, KeyboardShortcuts.keyCodeToAction(KeyEvent.KEYCODE_H, 0))
        assertEquals(MenuAction.ScrollDown, KeyboardShortcuts.keyCodeToAction(KeyEvent.KEYCODE_J, 0))
        assertEquals(MenuAction.ScrollUp, KeyboardShortcuts.keyCodeToAction(KeyEvent.KEYCODE_K, 0))
        assertEquals(MenuAction.ScrollRight, KeyboardShortcuts.keyCodeToAction(KeyEvent.KEYCODE_L, 0))
    }

    @Test
    fun resolvesAltLeftToNavigateBack() {
        assertEquals(
            MenuAction.NavigateBack,
            KeyboardShortcuts.keyCodeToAction(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.META_ALT_ON),
        )
    }

    @Test
    fun resolvesAltRightToNavigateForward() {
        assertEquals(
            MenuAction.NavigateForward,
            KeyboardShortcuts.keyCodeToAction(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.META_ALT_ON),
        )
    }

    @Test
    fun resolvesCtrlNumpad0ToFitPage() {
        assertEquals(
            MenuAction.FitPage,
            KeyboardShortcuts.keyCodeToAction(
                KeyEvent.KEYCODE_NUMPAD_0,
                KeyEvent.META_CTRL_ON,
            ),
        )
    }

    @Test
    fun resolvesCtrlNumpad7ToFacingView() {
        assertEquals(
            MenuAction.FacingView,
            KeyboardShortcuts.keyCodeToAction(
                KeyEvent.KEYCODE_NUMPAD_7,
                KeyEvent.META_CTRL_ON,
            ),
        )
    }

    @Test
    fun resolvesCtrlTabToNextTabSmart() {
        assertEquals(
            MenuAction.NextTabSmart,
            KeyboardShortcuts.keyCodeToAction(
                KeyEvent.KEYCODE_TAB,
                KeyEvent.META_CTRL_ON,
            ),
        )
    }

    @Test
    fun resolvesCtrlPlusToZoomIn() {
        assertEquals(
            MenuAction.ZoomIn,
            KeyboardShortcuts.keyCodeToAction(
                KeyEvent.KEYCODE_PLUS,
                KeyEvent.META_CTRL_ON,
            ),
        )
    }

    @Test
    fun resolvesCtrlMinusToZoomOut() {
        assertEquals(
            MenuAction.ZoomOut,
            KeyboardShortcuts.keyCodeToAction(
                KeyEvent.KEYCODE_MINUS,
                KeyEvent.META_CTRL_ON,
            ),
        )
    }

    @Test
    fun resolvesCtrlShiftPlusToRotateRight() {
        assertEquals(
            MenuAction.RotateRight,
            KeyboardShortcuts.keyCodeToAction(
                KeyEvent.KEYCODE_PLUS,
                KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON,
            ),
        )
    }

    @Test
    fun resolvesF5ToPresentation() {
        assertEquals(
            MenuAction.Presentation,
            KeyboardShortcuts.keyCodeToAction(KeyEvent.KEYCODE_F5, 0),
        )
    }

    @Test
    fun resolvesF11ToFullscreen() {
        assertEquals(
            MenuAction.Fullscreen,
            KeyboardShortcuts.keyCodeToAction(KeyEvent.KEYCODE_F11, 0),
        )
    }

    @Test
    fun resolvesShiftF11ToPresentation() {
        assertEquals(
            MenuAction.Presentation,
            KeyboardShortcuts.keyCodeToAction(
                KeyEvent.KEYCODE_F11,
                KeyEvent.META_SHIFT_ON,
            ),
        )
    }

    @Test
    fun resolvesF12ToShowBookmarks() {
        assertEquals(
            MenuAction.ShowBookmarks,
            KeyboardShortcuts.keyCodeToAction(KeyEvent.KEYCODE_F12, 0),
        )
    }

    @Test
    fun resolvesShiftF12ToCommandPaletteTOC() {
        assertEquals(
            MenuAction.CommandPaletteTOC,
            KeyboardShortcuts.keyCodeToAction(
                KeyEvent.KEYCODE_F12,
                KeyEvent.META_SHIFT_ON,
            ),
        )
    }

    @Test
    fun ignoresUnboundKeys() {
        // KEYCODE_F7 is not bound to anything in the Win32 table.
        assertNull(KeyboardShortcuts.keyCodeToAction(KeyEvent.KEYCODE_F7, 0))
    }

    @Test
    fun ignoresWrongModifier() {
        // Ctrl+Shift+F3 is "find prev in selection" — NOT plain find next.
        assertEquals(
            MenuAction.FindPrevSel,
            KeyboardShortcuts.keyCodeToAction(
                KeyEvent.KEYCODE_F3,
                KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON,
            ),
        )
    }

    // -------- binding-table shape --------

    @Test
    fun tableHasTheCanonicalKeyActions() {
        // The Win32 table has 82 unique commands. The Android port
        // drops the ones whose commands are not ported to Android
        // (annotations, screenshot, AI chat backends, printer
        // discovery, etc.) and the ones whose Win32 VK_* has no
        // Android equivalent (VK_APPS). The result is around 54
        // unique actions and around 80 binding rows. We assert
        // presence, not exact count, because future ports can add
        // more without this test needing to be updated.
        val actions = KeyboardShortcuts.BINDINGS.map { it.action }.toSet()
        // The most-used actions a Windows user tries in the first
        // ten seconds. Each MUST be present in the table.
        //
        // Note: CmdOpenFile (MenuAction.Open) is in the menu but
        // not in the Win32 binding table — opening files uses the
        // menu / toolbar, not a hotkey — so it is intentionally
        // not in this list.
        val must = listOf(
            MenuAction.Close,
            MenuAction.NavigateBack,
            MenuAction.NavigateForward,
            MenuAction.FindNext,
            MenuAction.FindPrev,
            MenuAction.FirstPage,
            MenuAction.LastPage,
            MenuAction.RotateLeft,
            MenuAction.RotateRight,
            MenuAction.FitPage,
            MenuAction.FitWidth,
            MenuAction.FitContent,
            MenuAction.ActualSize,
            MenuAction.ZoomIn,
            MenuAction.ZoomOut,
            MenuAction.SinglePageView,
            MenuAction.FacingView,
            MenuAction.BookView,
            MenuAction.NextTabSmart,
            MenuAction.PrevTabSmart,
            MenuAction.Presentation,
            MenuAction.Fullscreen,
            MenuAction.Manual,
            MenuAction.ShowBookmarks,
        )
        for (m in must) {
            assertTrue("expected $m in the binding table", m in actions)
        }
    }

    @Test
    fun everyBindingHasAKeyCode() {
        for (b in KeyboardShortcuts.BINDINGS) {
            assertTrue("binding $b has keyCode=0", b.keyCode != 0)
        }
    }
}

// SearchFlags — the typed wrapper for mupdf's search-options
// bitmask. The bit positions match the mupdf C API; the Kotlin
// side is a data class so callers do not have to remember them.
class SearchFlagsTest {

    @Test
    fun defaultFlagsHaveIgnoreCaseOnly() {
        // mupdf 1.28.0's Java binding's `search(String, int)` is
        // case-insensitive by default. The port's default is
        // therefore the same — case-insensitive, no other
        // modifiers — to match the Win32 default.
        val flags = SearchFlags.DEFAULT
        assertTrue(flags.ignoreCase)
        assertTrue(!flags.ignoreDiacritics)
        assertTrue(!flags.regex)
        assertTrue(!flags.keepLines)
    }

    @Test
    fun maskCombinesAllSetFlags() {
        val mask = SearchFlags(ignoreCase = true, ignoreDiacritics = true).toMask()
        assertEquals(SearchFlags.FLAG_IGNORE_CASE or SearchFlags.FLAG_IGNORE_DIACRITICS, mask)
    }

    @Test
    fun maskForEmptyFlagsIsZero() {
        val mask = SearchFlags(
            ignoreCase = false,
            ignoreDiacritics = false,
            regex = false,
            keepLines = false,
        ).toMask()
        assertEquals(0, mask)
    }

    @Test
    fun regexFlagIsolated() {
        val mask = SearchFlags(ignoreCase = false, regex = true).toMask()
        assertEquals(SearchFlags.FLAG_REGEXP, mask)
    }

    // Regression test for the "No matches" search bug observed on
    // the device (Z Fold 4 / Android 13) where typing "casual" or
    // "brotli" in the find bar returned zero hits even when the
    // word was clearly in the document text.
    //
    // Root cause: the Kotlin code passed a hard-coded `64` as the
    // second argument to `Page.search(needle, int)`. The 1.28.0
    // Java binding's int parameter is the `fz_search_options`
    // bitmask, NOT maxHits (the binding has no maxHits), and `64`
    // = 0b01000000 is bit 6, an unknown flag that does NOT set
    // IGNORE_CASE. The search then fell into the `FZ_SEARCH_EXACT`
    // branch (case-sensitive) and never matched title-case words
    // like "Brotli". The fix is to default `style` to
    // `FLAG_IGNORE_CASE = 1`.
    @Test
    fun hardCodedStyleOf64MissesTitleCase() {
        // The 64 that was hard-coded in DocumentEngine.searchPage
        // before the fix.
        val buggyStyle = 64
        // 64 = 0x40 = bit 6. None of the defined fz_search_options
        // are bit 6 (the highest defined is bit 5 = KEEP_HYPHENS =
        // 32), so the value is an unknown flag and the search
        // falls into the FZ_SEARCH_EXACT (case-sensitive) branch.
        assertEquals(0, buggyStyle and SearchFlags.FLAG_IGNORE_CASE)
        assertEquals(0, buggyStyle and SearchFlags.FLAG_IGNORE_DIACRITICS)
        assertEquals(0, buggyStyle and SearchFlags.FLAG_REGEXP)
        assertEquals(0, buggyStyle and SearchFlags.FLAG_KEEP_LINES)
        assertEquals(0, buggyStyle and SearchFlags.FLAG_KEEP_PARAGRAPHS)
        assertEquals(0, buggyStyle and SearchFlags.FLAG_KEEP_HYPHENS)
        // The default IGNORE_CASE-only mask IS the IGNORE_CASE bit.
        assertEquals(1, SearchFlags.FLAG_IGNORE_CASE)
        // The `style` parameter mupdf 1.28.0 wants. Caller must
        // pass this, not a maxHits-shaped number.
        assertEquals(1, SearchFlags.DEFAULT.toMask())
    }
}
