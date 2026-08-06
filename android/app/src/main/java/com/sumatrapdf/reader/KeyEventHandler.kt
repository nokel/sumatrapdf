package com.sumatrapdf.reader

import android.view.KeyEvent
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

fun Modifier.textFieldKeyHandler(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    anyTextFieldFocused: MutableState<Boolean>,
    onSubmit: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
): Modifier = this
    .onFocusChanged { fs ->
        anyTextFieldFocused.value = fs.isFocused
    }
    .onPreviewKeyEvent { event ->
        if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
        if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ESCAPE && onClose != null) {
            onClose()
            return@onPreviewKeyEvent true
        }
        if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER && onSubmit != null) {
            onSubmit()
            return@onPreviewKeyEvent true
        }
        if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DEL) {
            if (value.text.isEmpty()) return@onPreviewKeyEvent false
            val sel = value.selection
            val newText = if (sel.collapsed && sel.start > 0) {
                value.text.removeRange(sel.start - 1, sel.start)
            } else if (!sel.collapsed) {
                value.text.removeRange(sel.min, sel.max)
            } else {
                return@onPreviewKeyEvent false
            }
            val newCursor = if (sel.collapsed) sel.start - 1 else sel.min
            onValueChange(TextFieldValue(newText, TextRange(newCursor)))
            return@onPreviewKeyEvent true
        }
        val ch = charForKeyEvent(event.nativeKeyEvent) ?: return@onPreviewKeyEvent false
        val sel = value.selection
        val chStr = ch.toString()
        val (newText, newCursor) = if (sel.collapsed) {
            val pos = sel.start
            value.text.substring(0, pos) + chStr + value.text.substring(pos) to (pos + chStr.length)
        } else {
            value.text.substring(0, sel.min) + chStr + value.text.substring(sel.max) to (sel.min + chStr.length)
        }
        onValueChange(TextFieldValue(newText, TextRange(newCursor)))
        true
    }

fun charForKeyEvent(event: KeyEvent): Char? {
    val keyCode = event.keyCode
    val metaState = event.metaState
    val unicode = event.unicodeChar
    if (unicode > 0) {
        val c = unicode.toChar()
        if (!c.isISOControl()) return c
    }
    return keyCodeToChar(keyCode, metaState)
}

fun keyCodeToChar(keyCode: Int, metaState: Int): Char? {
    val shift = (metaState and KeyEvent.META_SHIFT_ON) != 0
    val alt = (metaState and KeyEvent.META_ALT_ON) != 0
    val ctrl = (metaState and KeyEvent.META_CTRL_ON) != 0
    if (ctrl) return null
    return when (keyCode) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> {
            val upper = 'A' + (keyCode - KeyEvent.KEYCODE_A)
            if (shift || alt && !shift) upper else upper.lowercaseChar()
        }
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
            if (!shift) '0' + (keyCode - KeyEvent.KEYCODE_0)
            else when (keyCode) {
                KeyEvent.KEYCODE_1 -> '!'
                KeyEvent.KEYCODE_2 -> '@'
                KeyEvent.KEYCODE_3 -> '#'
                KeyEvent.KEYCODE_4 -> '$'
                KeyEvent.KEYCODE_5 -> '%'
                KeyEvent.KEYCODE_6 -> '^'
                KeyEvent.KEYCODE_7 -> '&'
                KeyEvent.KEYCODE_8 -> '*'
                KeyEvent.KEYCODE_9 -> '('
                else -> ')'
            }
        }
        KeyEvent.KEYCODE_SPACE -> ' '
        KeyEvent.KEYCODE_TAB -> '\t'
        KeyEvent.KEYCODE_ENTER -> '\n'
        KeyEvent.KEYCODE_PERIOD -> if (shift) '>' else '.'
        KeyEvent.KEYCODE_COMMA -> if (shift) '<' else ','
        KeyEvent.KEYCODE_MINUS -> if (shift) '_' else '-'
        KeyEvent.KEYCODE_EQUALS -> if (shift) '+' else '='
        KeyEvent.KEYCODE_SLASH -> if (shift) '?' else '/'
        KeyEvent.KEYCODE_SEMICOLON -> if (shift) ':' else ';'
        KeyEvent.KEYCODE_APOSTROPHE -> if (shift) '"' else '\''
        KeyEvent.KEYCODE_LEFT_BRACKET -> if (shift) '{' else '['
        KeyEvent.KEYCODE_RIGHT_BRACKET -> if (shift) '}' else ']'
        KeyEvent.KEYCODE_BACKSLASH -> if (shift) '|' else '\\'
        KeyEvent.KEYCODE_GRAVE -> if (shift) '~' else '`'
        KeyEvent.KEYCODE_NUMPAD_0 -> '0'
        KeyEvent.KEYCODE_NUMPAD_1 -> '1'
        KeyEvent.KEYCODE_NUMPAD_2 -> '2'
        KeyEvent.KEYCODE_NUMPAD_3 -> '3'
        KeyEvent.KEYCODE_NUMPAD_4 -> '4'
        KeyEvent.KEYCODE_NUMPAD_5 -> '5'
        KeyEvent.KEYCODE_NUMPAD_6 -> '6'
        KeyEvent.KEYCODE_NUMPAD_7 -> '7'
        KeyEvent.KEYCODE_NUMPAD_8 -> '8'
        KeyEvent.KEYCODE_NUMPAD_9 -> '9'
        KeyEvent.KEYCODE_NUMPAD_DOT -> '.'
        KeyEvent.KEYCODE_NUMPAD_COMMA -> ','
        KeyEvent.KEYCODE_NUMPAD_ENTER -> '\n'
        KeyEvent.KEYCODE_PLUS -> '+'
        KeyEvent.KEYCODE_MINUS -> '-'
        KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> '*'
        KeyEvent.KEYCODE_NUMPAD_DIVIDE -> '/'
        KeyEvent.KEYCODE_NUMPAD_EQUALS -> '='
        KeyEvent.KEYCODE_NUMPAD_LEFT_PAREN -> '('
        KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN -> ')'
        else -> null
    }
}
