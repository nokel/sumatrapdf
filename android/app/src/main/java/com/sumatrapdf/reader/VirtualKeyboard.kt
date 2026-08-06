package com.sumatrapdf.reader

import android.app.Activity
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val kRow1 = listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P")
private val kRow2 = listOf("A", "S", "D", "F", "G", "H", "J", "K", "L")
private val kRow3 = listOf("Z", "X", "C", "V", "B", "N", "M")

private fun letterToKeyCode(ch: Char): Int = when (ch) {
    in 'A'..'Z' -> KeyEvent.KEYCODE_A + (ch - 'A')
    in 'a'..'z' -> KeyEvent.KEYCODE_A + (ch - 'a')
    in '0'..'9' -> KeyEvent.KEYCODE_0 + (ch - '0')
    else -> KeyEvent.KEYCODE_UNKNOWN
}

private fun labelToAction(label: String): Pair<Int, Int>? = when (label) {
    "Space" -> KeyEvent.KEYCODE_SPACE to 0
    "Enter" -> KeyEvent.KEYCODE_ENTER to 0
    "Backspace" -> KeyEvent.KEYCODE_DEL to 0
    "Esc" -> KeyEvent.KEYCODE_ESCAPE to 0
    "Tab" -> KeyEvent.KEYCODE_TAB to 0
    "Shift" -> null
    else -> {
        if (label.length == 1) letterToKeyCode(label[0]) to 0 else null
    }
}

private fun keyToCode(label: String, shift: Boolean): Pair<Int, Int>? {
    if (label == "Space" || label == "Enter" || label == "Backspace" || label == "Esc" || label == "Tab") {
        return labelToAction(label)
    }
    val base = letterToKeyCode(label[0])
    if (base == KeyEvent.KEYCODE_UNKNOWN) return null
    val meta = if (shift && label[0] in 'A'..'Z') KeyEvent.META_SHIFT_ON else 0
    return base to meta
}

@Composable
fun VirtualKeyboard(
    onCharacter: (String) -> Unit = {},
) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity

    fun tap(label: String, shift: Boolean = false) {
        if (activity == null) return
        val (code, meta) = keyToCode(label, shift) ?: return
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta)
        val up = KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta)
        activity.dispatchKeyEvent(down)
        activity.dispatchKeyEvent(up)
        onCharacter(label)
    }

    fun vkButton(label: String, width: Int = 36, height: Int = 44, shift: Boolean = false): @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .size(width.dp, height.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                .clickable { tap(label, shift) },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (shift && label.length == 1) label.uppercase() else label,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            kRow1.forEach { vkButton(it)() }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
        ) {
            kRow2.forEach { vkButton(it)() }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            vkButton("Shift", width = 48)()
            kRow3.forEach { vkButton(it)() }
            vkButton("Backspace", width = 60)()
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            vkButton("Tab", width = 48)()
            vkButton("Space", width = 180)()
            vkButton("Enter", width = 60)()
            vkButton("Esc", width = 48)()
        }
    }
}
