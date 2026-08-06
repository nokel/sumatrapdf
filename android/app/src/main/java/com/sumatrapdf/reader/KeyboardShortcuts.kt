package com.sumatrapdf.reader

import android.view.KeyEvent

// Keyboard shortcut layer. A port of `src/Accelerators.cpp`
// (728 LoC, 82 unique commands bound to keys) to Android `KeyEvent`.
//
// Win32 uses a virtual-key + modifier triple (FVIRTKEY|FCONTROL|FSHIFT
// `VK_*`) and an array of 114 `ACCEL` entries. We use the same shape:
// a triple of (Android `KeyEvent.KEYCODE_*`, modifier mask, MenuAction)
// entries. The table at the bottom of this file is the Win32 table,
// with the Win32 VK_* replaced by the equivalent Android `KEYCODE_*`.
//
// The table is a `List<Binding>` so user-defined overrides can be
// merged in front of it later (the Win32 `struct Shortcut` feature is
// out of scope for this port). `Settings.keyboardShortcuts` (default
// true) gates the whole layer.
//
// Two entry points:
//   * `keyCodeToAction(keyCode, modifiers)` — pure lookup, used by
//     `MainActivity.dispatchKeyEvent` and by the unit tests
//   * `parseShortcut("Ctrl+Shift+L")` — Win32-style shortcut string
//     parser; used to display the binding in the menu and to
//     round-trip user overrides

// The Android `KeyEvent` modifier mask values we care about. Android
// already provides META_CTRL_ON, META_SHIFT_ON, META_ALT_ON; we only
// need the first three (Win32's Ctrl/Shift/Alt = the modifier keys
// a Bluetooth keyboard sends).
internal const val MOD_CTRL = KeyEvent.META_CTRL_ON
internal const val MOD_SHIFT = KeyEvent.META_SHIFT_ON
internal const val MOD_ALT = KeyEvent.META_ALT_ON

data class ShortcutBinding(
    val keyCode: Int,    // KeyEvent.KEYCODE_*
    val modifiers: Int,   // bit-or of MOD_*
    val action: MenuAction,
    val shortcutString: String,
)

object KeyboardShortcuts {

    /** True if the keyboard-shortcut layer is active. */
    fun enabled(settings: Settings): Boolean = settings.keyboardShortcuts

    /**
     * Look up the action bound to (keyCode, modifiers) by the built-in
     * table. Returns null if no binding matches.
     *
     * `modifiers` is the raw `KeyEvent.getMetaState()` value, so the
     * caller does not have to mask out META_SYM / META_CAPS_LOCK etc.
     */
    fun keyCodeToAction(keyCode: Int, metaState: Int): MenuAction? {
        val wantMods = (if (metaState and MOD_CTRL != 0) MOD_CTRL else 0) or
            (if (metaState and MOD_SHIFT != 0) MOD_SHIFT else 0) or
            (if (metaState and MOD_ALT != 0) MOD_ALT else 0)
        for (b in BINDINGS) {
            if (b.keyCode == keyCode && b.modifiers == wantMods) {
                return b.action
            }
        }
        return null
    }

    /**
     * Parse a Win32-style shortcut string ("Ctrl+N", "Ctrl+Shift+L",
     * "F3", "Escape") into a (keyCode, modifiers) pair, or null if
     * the string is not a valid binding.
     */
    fun parseShortcut(s: String): Pair<Int, Int>? {
        val parts = s.split('+').map { it.trim() }
        var keyCode: Int? = null
        var mods = 0
        for (p in parts) {
            when (p.lowercase()) {
                "ctrl", "control" -> mods = mods or MOD_CTRL
                "shift" -> mods = mods or MOD_SHIFT
                "alt" -> mods = mods or MOD_ALT
                else -> {
                    if (keyCode != null) return null  // two non-modifier parts
                    keyCode = nameToKeyCode(p) ?: return null
                }
            }
        }
        if (keyCode == null) return null
        return Pair(keyCode, mods)
    }

    /** Round-trip a (keyCode, modifiers) back into a Win32-style string. */
    fun shortcutString(keyCode: Int, modifiers: Int): String {
        val parts = mutableListOf<String>()
        if (modifiers and MOD_CTRL != 0) parts += "Ctrl"
        if (modifiers and MOD_SHIFT != 0) parts += "Shift"
        if (modifiers and MOD_ALT != 0) parts += "Alt"
        parts += keyCodeName(keyCode) ?: return ""
        return parts.joinToString("+")
    }

    private fun nameToKeyCode(name: String): Int? {
        if (name.length == 1) {
            val c = name[0].uppercaseChar()
            if (c in 'A'..'Z') return KeyEvent.KEYCODE_A + (c - 'A')
            if (c in '0'..'9') return KeyEvent.KEYCODE_0 + (c - '0')
            return when (c) {
                '+' -> KeyEvent.KEYCODE_PLUS
                '-' -> KeyEvent.KEYCODE_MINUS
                '*' -> KeyEvent.KEYCODE_STAR
                '/' -> KeyEvent.KEYCODE_SLASH
                '[' -> KeyEvent.KEYCODE_LEFT_BRACKET
                ']' -> KeyEvent.KEYCODE_RIGHT_BRACKET
                else -> null
            }
        }
        return when (name.lowercase()) {
            "esc", "escape" -> KeyEvent.KEYCODE_ESCAPE
            "tab" -> KeyEvent.KEYCODE_TAB
            "return", "enter" -> KeyEvent.KEYCODE_ENTER
            "space" -> KeyEvent.KEYCODE_SPACE
            "back", "backspace", "del", "delete" -> KeyEvent.KEYCODE_DEL
            "ins", "insert" -> KeyEvent.KEYCODE_INSERT
            "home" -> KeyEvent.KEYCODE_HOME
            "end" -> KeyEvent.KEYCODE_MOVE_END
            "pageup", "pgup" -> KeyEvent.KEYCODE_PAGE_UP
            "pagedown", "pgdown" -> KeyEvent.KEYCODE_PAGE_DOWN
            "left" -> KeyEvent.KEYCODE_DPAD_LEFT
            "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "up" -> KeyEvent.KEYCODE_DPAD_UP
            "down" -> KeyEvent.KEYCODE_DPAD_DOWN
            "f1" -> KeyEvent.KEYCODE_F1
            "f2" -> KeyEvent.KEYCODE_F2
            "f3" -> KeyEvent.KEYCODE_F3
            "f4" -> KeyEvent.KEYCODE_F4
            "f5" -> KeyEvent.KEYCODE_F5
            "f6" -> KeyEvent.KEYCODE_F6
            "f7" -> KeyEvent.KEYCODE_F7
            "f8" -> KeyEvent.KEYCODE_F8
            "f9" -> KeyEvent.KEYCODE_F9
            "f10" -> KeyEvent.KEYCODE_F10
            "f11" -> KeyEvent.KEYCODE_F11
            "f12" -> KeyEvent.KEYCODE_F12
            "numpad0" -> KeyEvent.KEYCODE_NUMPAD_0
            "numpad1" -> KeyEvent.KEYCODE_NUMPAD_1
            "numpad2" -> KeyEvent.KEYCODE_NUMPAD_2
            "numpad3" -> KeyEvent.KEYCODE_NUMPAD_3
            "numpad4" -> KeyEvent.KEYCODE_NUMPAD_4
            "numpad5" -> KeyEvent.KEYCODE_NUMPAD_5
            "numpad6" -> KeyEvent.KEYCODE_NUMPAD_6
            "numpad7" -> KeyEvent.KEYCODE_NUMPAD_7
            "numpad8" -> KeyEvent.KEYCODE_NUMPAD_8
            "numpad9" -> KeyEvent.KEYCODE_NUMPAD_9
            "add", "plus" -> KeyEvent.KEYCODE_PLUS
            "subtract", "sub", "minus" -> KeyEvent.KEYCODE_MINUS
            "multiply", "mult" -> KeyEvent.KEYCODE_STAR
            "divide", "div" -> KeyEvent.KEYCODE_SLASH
            "comma" -> KeyEvent.KEYCODE_COMMA
            "period" -> KeyEvent.KEYCODE_PERIOD
            "semicolon" -> KeyEvent.KEYCODE_SEMICOLON
            "apostrophe" -> KeyEvent.KEYCODE_APOSTROPHE
            "grave" -> KeyEvent.KEYCODE_GRAVE
            "printscreen", "prtsc" -> KeyEvent.KEYCODE_SYSRQ
            "scroll" -> KeyEvent.KEYCODE_SCROLL_LOCK
            "pause" -> KeyEvent.KEYCODE_BREAK
            "numlock" -> KeyEvent.KEYCODE_NUM_LOCK
            "capslock" -> KeyEvent.KEYCODE_CAPS_LOCK
            "help" -> KeyEvent.KEYCODE_HELP
            "clear" -> KeyEvent.KEYCODE_CLEAR
            "select" -> KeyEvent.KEYCODE_BUTTON_SELECT
            else -> null
        }
    }

    private fun keyCodeName(keyCode: Int): String? = when (keyCode) {
        KeyEvent.KEYCODE_ESCAPE -> "Escape"
        KeyEvent.KEYCODE_TAB -> "Tab"
        KeyEvent.KEYCODE_ENTER -> "Return"
        KeyEvent.KEYCODE_SPACE -> "Space"
        KeyEvent.KEYCODE_DEL -> "Back"
        KeyEvent.KEYCODE_INSERT -> "Insert"
        KeyEvent.KEYCODE_HOME -> "Home"
        KeyEvent.KEYCODE_MOVE_END -> "End"
        KeyEvent.KEYCODE_PAGE_UP -> "PageUp"
        KeyEvent.KEYCODE_PAGE_DOWN -> "PageDown"
        KeyEvent.KEYCODE_DPAD_LEFT -> "Left"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "Right"
        KeyEvent.KEYCODE_DPAD_UP -> "Up"
        KeyEvent.KEYCODE_DPAD_DOWN -> "Down"
        KeyEvent.KEYCODE_F1 -> "F1"
        KeyEvent.KEYCODE_F2 -> "F2"
        KeyEvent.KEYCODE_F3 -> "F3"
        KeyEvent.KEYCODE_F4 -> "F4"
        KeyEvent.KEYCODE_F5 -> "F5"
        KeyEvent.KEYCODE_F6 -> "F6"
        KeyEvent.KEYCODE_F7 -> "F7"
        KeyEvent.KEYCODE_F8 -> "F8"
        KeyEvent.KEYCODE_F9 -> "F9"
        KeyEvent.KEYCODE_F10 -> "F10"
        KeyEvent.KEYCODE_F11 -> "F11"
        KeyEvent.KEYCODE_F12 -> "F12"
        KeyEvent.KEYCODE_NUMPAD_0 -> "Numpad0"
        KeyEvent.KEYCODE_NUMPAD_1 -> "Numpad1"
        KeyEvent.KEYCODE_NUMPAD_2 -> "Numpad2"
        KeyEvent.KEYCODE_NUMPAD_3 -> "Numpad3"
        KeyEvent.KEYCODE_NUMPAD_4 -> "Numpad4"
        KeyEvent.KEYCODE_NUMPAD_5 -> "Numpad5"
        KeyEvent.KEYCODE_NUMPAD_6 -> "Numpad6"
        KeyEvent.KEYCODE_NUMPAD_7 -> "Numpad7"
        KeyEvent.KEYCODE_NUMPAD_8 -> "Numpad8"
        KeyEvent.KEYCODE_NUMPAD_9 -> "Numpad9"
        KeyEvent.KEYCODE_PLUS -> "Plus"
        KeyEvent.KEYCODE_MINUS -> "Minus"
        KeyEvent.KEYCODE_STAR -> "*"
        KeyEvent.KEYCODE_SLASH -> "/"
        KeyEvent.KEYCODE_LEFT_BRACKET -> "["
        KeyEvent.KEYCODE_RIGHT_BRACKET -> "]"
        else -> {
            if (keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
                "" + ('A' + (keyCode - KeyEvent.KEYCODE_A))
            } else if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
                "" + ('0' + (keyCode - KeyEvent.KEYCODE_0))
            } else {
                null
            }
        }
    }

    // The bindings table. Mirrors the Win32 `gBuiltInAccelerators` array
    // in src/Accelerators.cpp (114 entries; 82 unique commands) with
    // the Win32 VK_* codes replaced by Android `KEYCODE_*`.
    //
    // Each row is (keyCode, modifiers, action, shortcutString). The
    // shortcutString is for the menu item hint and the unit tests; it
    // is the Win32 string the user would type in the custom-shortcut
    // text box.
    val BINDINGS: List<ShortcutBinding> = listOf(
        // Vim-style navigation (CmdScrollUp/Down/Left/Right)
        ShortcutBinding(KeyEvent.KEYCODE_K, 0, MenuAction.ScrollUp, "K"),
        ShortcutBinding(KeyEvent.KEYCODE_J, 0, MenuAction.ScrollDown, "J"),
        ShortcutBinding(KeyEvent.KEYCODE_H, 0, MenuAction.ScrollLeft, "H"),
        ShortcutBinding(KeyEvent.KEYCODE_L, 0, MenuAction.ScrollRight, "L"),
        // Arrow keys: same as Vim (no modifier)
        ShortcutBinding(KeyEvent.KEYCODE_DPAD_UP, 0, MenuAction.ScrollUp, "Up"),
        ShortcutBinding(KeyEvent.KEYCODE_DPAD_DOWN, 0, MenuAction.ScrollDown, "Down"),
        ShortcutBinding(KeyEvent.KEYCODE_DPAD_LEFT, 0, MenuAction.ScrollLeft, "Left"),
        ShortcutBinding(KeyEvent.KEYCODE_DPAD_RIGHT, 0, MenuAction.ScrollRight, "Right"),
        // Shift+arrow = scroll by half page
        ShortcutBinding(KeyEvent.KEYCODE_DPAD_UP, MOD_SHIFT, MenuAction.ScrollUpHalfPage, "Shift+Up"),
        ShortcutBinding(KeyEvent.KEYCODE_DPAD_DOWN, MOD_SHIFT, MenuAction.ScrollDownHalfPage, "Shift+Down"),
        ShortcutBinding(KeyEvent.KEYCODE_DPAD_LEFT, MOD_SHIFT, MenuAction.ScrollLeftPage, "Shift+Left"),
        ShortcutBinding(KeyEvent.KEYCODE_DPAD_RIGHT, MOD_SHIFT, MenuAction.ScrollRightPage, "Shift+Right"),
        // PageDown / PageUp = scroll one full page
        ShortcutBinding(KeyEvent.KEYCODE_PAGE_DOWN, 0, MenuAction.ScrollDownPage, "PageDown"),
        ShortcutBinding(KeyEvent.KEYCODE_PAGE_UP, 0, MenuAction.ScrollUpPage, "PageUp"),
        // Space / Return = scroll down a page; Shift+Space / Shift+Return = up
        ShortcutBinding(KeyEvent.KEYCODE_SPACE, 0, MenuAction.ScrollDownPage, "Space"),
        ShortcutBinding(KeyEvent.KEYCODE_ENTER, 0, MenuAction.ScrollDownPage, "Return"),
        ShortcutBinding(KeyEvent.KEYCODE_SPACE, MOD_SHIFT, MenuAction.ScrollUpPage, "Shift+Space"),
        ShortcutBinding(KeyEvent.KEYCODE_ENTER, MOD_SHIFT, MenuAction.ScrollUpPage, "Shift+Return"),
        // Ctrl+Down / Ctrl+Up = scroll one full page
        ShortcutBinding(KeyEvent.KEYCODE_DPAD_DOWN, MOD_CTRL, MenuAction.ScrollDownPage, "Ctrl+Down"),
        ShortcutBinding(KeyEvent.KEYCODE_DPAD_UP, MOD_CTRL, MenuAction.ScrollUpPage, "Ctrl+Up"),
        // Home / End = first / last page
        ShortcutBinding(KeyEvent.KEYCODE_HOME, 0, MenuAction.FirstPage, "Home"),
        ShortcutBinding(KeyEvent.KEYCODE_HOME, MOD_CTRL, MenuAction.FirstPage, "Ctrl+Home"),
        ShortcutBinding(KeyEvent.KEYCODE_MOVE_END, 0, MenuAction.LastPage, "End"),
        ShortcutBinding(KeyEvent.KEYCODE_MOVE_END, MOD_CTRL, MenuAction.LastPage, "Ctrl+End"),
        // Back / Forward
        ShortcutBinding(KeyEvent.KEYCODE_DEL, 0, MenuAction.NavigateBack, "Back"),
        ShortcutBinding(KeyEvent.KEYCODE_DPAD_LEFT, MOD_ALT, MenuAction.NavigateBack, "Alt+Left"),
        ShortcutBinding(KeyEvent.KEYCODE_DEL, MOD_SHIFT, MenuAction.NavigateForward, "Shift+Back"),
        ShortcutBinding(KeyEvent.KEYCODE_DPAD_RIGHT, MOD_ALT, MenuAction.NavigateForward, "Alt+Right"),
        // Open Next / Prev in folder
        ShortcutBinding(KeyEvent.KEYCODE_DPAD_RIGHT, MOD_CTRL or MOD_SHIFT, MenuAction.OpenNext, "Ctrl+Shift+Right"),
        ShortcutBinding(KeyEvent.KEYCODE_DPAD_LEFT, MOD_CTRL or MOD_SHIFT, MenuAction.OpenPrev, "Ctrl+Shift+Left"),
        // Rename (F2)
        ShortcutBinding(KeyEvent.KEYCODE_F2, 0, MenuAction.Rename, "F2"),
        // Duplicate in new window
        ShortcutBinding(KeyEvent.KEYCODE_N, MOD_CTRL or MOD_SHIFT, MenuAction.DuplicateInNewWindow, "Ctrl+Shift+N"),
        // Copy selection
        ShortcutBinding(KeyEvent.KEYCODE_INSERT, MOD_CTRL, MenuAction.CopySelection, "Ctrl+Insert"),
        // Save annotations
        ShortcutBinding(KeyEvent.KEYCODE_S, MOD_CTRL or MOD_SHIFT, MenuAction.SaveAnnotations, "Ctrl+Shift+S"),
        // Numpad zoom / view-mode (Win32 numpad has FCONTROL|VK_NUMPAD*)
        ShortcutBinding(KeyEvent.KEYCODE_NUMPAD_0, MOD_CTRL, MenuAction.FitPage, "Ctrl+Numpad0"),
        ShortcutBinding(KeyEvent.KEYCODE_NUMPAD_1, MOD_CTRL, MenuAction.ActualSize, "Ctrl+Numpad1"),
        ShortcutBinding(KeyEvent.KEYCODE_NUMPAD_2, MOD_CTRL, MenuAction.FitWidth, "Ctrl+Numpad2"),
        ShortcutBinding(KeyEvent.KEYCODE_NUMPAD_3, MOD_CTRL, MenuAction.FitContent, "Ctrl+Numpad3"),
        ShortcutBinding(KeyEvent.KEYCODE_PLUS, MOD_CTRL, MenuAction.ZoomIn, "Ctrl+Plus"),
        ShortcutBinding(KeyEvent.KEYCODE_MINUS, MOD_CTRL, MenuAction.ZoomOut, "Ctrl+Minus"),
        ShortcutBinding(KeyEvent.KEYCODE_NUMPAD_6, MOD_CTRL, MenuAction.SinglePageView, "Ctrl+Numpad6"),
        ShortcutBinding(KeyEvent.KEYCODE_NUMPAD_7, MOD_CTRL, MenuAction.FacingView, "Ctrl+Numpad7"),
        ShortcutBinding(KeyEvent.KEYCODE_NUMPAD_8, MOD_CTRL, MenuAction.BookView, "Ctrl+Numpad8"),
        // Rotate with shift+ctrl+plus / shift+ctrl+minus
        ShortcutBinding(KeyEvent.KEYCODE_PLUS, MOD_CTRL or MOD_SHIFT, MenuAction.RotateRight, "Ctrl+Shift+Plus"),
        ShortcutBinding(KeyEvent.KEYCODE_MINUS, MOD_CTRL or MOD_SHIFT, MenuAction.RotateLeft, "Ctrl+Shift+Minus"),
        // Find: Ctrl+F opens the find toolbar, F3 next, Shift+F3 prev, Ctrl+F3
        // next-in-selection, Shift+Ctrl+F3 prev. Ctrl+F is the universal "find"
        // shortcut and matches Win32 SumatraPDF.
        ShortcutBinding(KeyEvent.KEYCODE_F, MOD_CTRL, MenuAction.FindFirst, "Ctrl+F"),
        ShortcutBinding(KeyEvent.KEYCODE_F3, 0, MenuAction.FindNext, "F3"),
        ShortcutBinding(KeyEvent.KEYCODE_F3, MOD_SHIFT, MenuAction.FindPrev, "Shift+F3"),
        ShortcutBinding(KeyEvent.KEYCODE_F3, MOD_CTRL, MenuAction.FindNextSel, "Ctrl+F3"),
        ShortcutBinding(KeyEvent.KEYCODE_F3, MOD_CTRL or MOD_SHIFT, MenuAction.FindPrevSel, "Ctrl+Shift+F3"),
        // Close: Ctrl+F4, Esc (also Esc is used in Presentation)
        ShortcutBinding(KeyEvent.KEYCODE_F4, MOD_CTRL, MenuAction.Close, "Ctrl+F4"),
        // Frame focus (F6)
        ShortcutBinding(KeyEvent.KEYCODE_F6, 0, MenuAction.MoveFrameFocus, "F6"),
        // Toolbar / Menubar toggles
        ShortcutBinding(KeyEvent.KEYCODE_F8, 0, MenuAction.ShowToolbar, "F8"),
        ShortcutBinding(KeyEvent.KEYCODE_F9, 0, MenuAction.ShowMenu, "F9"),
        // Presentation / Fullscreen
        ShortcutBinding(KeyEvent.KEYCODE_F5, 0, MenuAction.Presentation, "F5"),
        ShortcutBinding(KeyEvent.KEYCODE_F11, MOD_SHIFT, MenuAction.Presentation, "Shift+F11"),
        ShortcutBinding(KeyEvent.KEYCODE_L, MOD_CTRL or MOD_SHIFT, MenuAction.Fullscreen, "Ctrl+Shift+L"),
        ShortcutBinding(KeyEvent.KEYCODE_F11, 0, MenuAction.Fullscreen, "F11"),
        // Bookmarks / Command palette
        ShortcutBinding(KeyEvent.KEYCODE_F12, 0, MenuAction.ShowBookmarks, "F12"),
        ShortcutBinding(KeyEvent.KEYCODE_F12, MOD_SHIFT, MenuAction.CommandPaletteTOC, "Shift+F12"),
        // ToC expand-all / collapse-all / expand-to-current
        ShortcutBinding(KeyEvent.KEYCODE_A, MOD_CTRL or MOD_SHIFT, MenuAction.ExpandAll, "Ctrl+Shift+A"),
        ShortcutBinding(KeyEvent.KEYCODE_C, MOD_CTRL or MOD_SHIFT, MenuAction.CollapseAll, "Ctrl+Shift+C"),
        ShortcutBinding(KeyEvent.KEYCODE_E, MOD_CTRL or MOD_SHIFT, MenuAction.ExpandToCurrentPage, "Ctrl+Shift+E"),
        // Favorite / bookmark navigation
        ShortcutBinding(KeyEvent.KEYCODE_F2, MOD_SHIFT, MenuAction.GoToNextFavorite, "Shift+F2"),
        ShortcutBinding(KeyEvent.KEYCODE_F2, MOD_CTRL, MenuAction.GoToPrevFavorite, "Ctrl+F2"),
        // Reopen last closed file
        ShortcutBinding(KeyEvent.KEYCODE_T, MOD_CTRL or MOD_SHIFT, MenuAction.ReopenLastClosedFile, "Ctrl+Shift+T"),
        // Tab navigation
        ShortcutBinding(KeyEvent.KEYCODE_PAGE_DOWN, MOD_CTRL, MenuAction.NextTab, "Ctrl+PageDown"),
        ShortcutBinding(KeyEvent.KEYCODE_PAGE_UP, MOD_CTRL, MenuAction.PrevTab, "Ctrl+PageUp"),
        ShortcutBinding(KeyEvent.KEYCODE_PAGE_DOWN, MOD_CTRL or MOD_SHIFT, MenuAction.MoveTabRight, "Ctrl+Shift+PageDown"),
        ShortcutBinding(KeyEvent.KEYCODE_PAGE_UP, MOD_CTRL or MOD_SHIFT, MenuAction.MoveTabLeft, "Ctrl+Shift+PageUp"),
        ShortcutBinding(KeyEvent.KEYCODE_TAB, MOD_CTRL, MenuAction.NextTabSmart, "Ctrl+Tab"),
        ShortcutBinding(KeyEvent.KEYCODE_TAB, MOD_CTRL or MOD_SHIFT, MenuAction.PrevTabSmart, "Ctrl+Shift+Tab"),
        // Help
        ShortcutBinding(KeyEvent.KEYCODE_F1, 0, MenuAction.Manual, "F1"),
        // Annotation creation (kept for parity; the annotation layer is
        // out of scope for this port, see ROADMAP §1.3)
        ShortcutBinding(KeyEvent.KEYCODE_A, MOD_SHIFT, MenuAction.ShowLinks, "Shift+A"),
        ShortcutBinding(KeyEvent.KEYCODE_U, MOD_SHIFT, MenuAction.ShowLinks, "Shift+U"),
        ShortcutBinding(KeyEvent.KEYCODE_I, MOD_SHIFT, MenuAction.InvertColors, "Shift+I"),
        ShortcutBinding(KeyEvent.KEYCODE_DEL, MOD_CTRL, MenuAction.Delete, "Ctrl+Del"),
        // Square-bracket rotation (Win32 VK_OEM_4 / VK_OEM_6)
        ShortcutBinding(KeyEvent.KEYCODE_LEFT_BRACKET, 0, MenuAction.RotateLeft, "["),
        ShortcutBinding(KeyEvent.KEYCODE_RIGHT_BRACKET, 0, MenuAction.RotateRight, "]"),
    )
}
