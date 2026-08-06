package com.sumatrapdf.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// Win32 SumatraPDF status bar: a thin grey strip at the bottom with the
// current page count on the right. On Win32 it also shows rotation/zoom
// state, but those are usually inferred from the toolbar so we keep it
// minimal here.
@Composable
fun SumatraStatusBar(
    page: Int,
    pageCount: Int,
    rotation: Int,
    zoomLabel: String,
    onSeekToPage: (Int) -> Unit = {},
    showSlider: Boolean = true,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val maxIndex = (pageCount - 1).coerceAtLeast(0)
    val shownPage = if (dragging) dragValue.roundToInt() + 1 else page

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 0.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (showSlider && pageCount > 1) {
                Slider(
                    value = if (dragging) dragValue else (page - 1).coerceIn(0, maxIndex).toFloat(),
                    onValueChange = {
                        dragging = true
                        dragValue = it
                    },
                    onValueChangeFinished = {
                        dragging = false
                        onSeekToPage(dragValue.roundToInt().coerceIn(0, maxIndex))
                    },
                    valueRange = 0f..maxIndex.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .padding(horizontal = 12.dp),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (rotation != 0) {
                        Text(
                            text = "↻ $rotation°",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    }
                    Text(
                        text = "$zoomLabel   |   $shownPage / $pageCount",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
