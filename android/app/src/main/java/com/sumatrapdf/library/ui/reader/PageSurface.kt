package com.sumatrapdf.library.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.sumatrapdf.library.data.PageLayout
import com.sumatrapdf.library.data.ZoomFit
import com.sumatrapdf.library.engine.DocumentEngine
import com.sumatrapdf.library.ui.theme.SumTypography
import kotlin.math.abs

enum class TapKind { Single, Double, Long }

// PageSurface renders one or two pages of a DocumentEngine, with pinch
// zoom, double-tap to fit, drag to pan, and (in single mode) horizontal
// swipe to change page. The engine does the real MuPDF work; this
// composable just asks for a bitmap at the right pixel width and lays it
// out, the same way the Win32 reader asks the engine for a page bitmap
// and blits it onto the window.
@Composable
fun PageSurface(
    engine: DocumentEngine?,
    pageIndex: Int,
    pageCount: Int,
    layout: PageLayout,
    zoom: ZoomFit,
    night: Boolean,
    onTap: (TapKind) -> Unit,
    onPageChange: (Int) -> Unit,
    onSwipeOut: (forward: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = MaterialTheme.colorScheme.surface
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(bg)
            .clipToBounds(),
    ) {
        val viewportWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val viewportHeightPx = with(LocalDensity.current) { maxHeight.toPx() }
        when (layout) {
            PageLayout.Single -> SinglePage(
                engine, pageIndex, pageCount, zoom, night,
                viewportWidthPx, viewportHeightPx,
                onTap, onPageChange, onSwipeOut,
            )
            PageLayout.Facing -> FacingPages(
                engine, pageIndex, pageCount, zoom, night,
                viewportWidthPx, viewportHeightPx,
                onTap, onPageChange,
            )
            PageLayout.Book -> BookPages(
                engine, pageIndex, pageCount, zoom, night,
                viewportWidthPx, viewportHeightPx,
                onTap, onPageChange,
            )
        }
    }
}

@Composable
private fun SinglePage(
    engine: DocumentEngine?,
    pageIndex: Int,
    pageCount: Int,
    zoom: ZoomFit,
    night: Boolean,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    onTap: (TapKind) -> Unit,
    onPageChange: (Int) -> Unit,
    onSwipeOut: (forward: Boolean) -> Unit,
) {
    val shape = remember { mutableStateOf<Pair<Float, Float>?>(null) }
    val targetWidth = remember(viewportWidthPx) { viewportWidthPx.toInt() }
    LaunchedEffect(engine, pageIndex, targetWidth) {
        if (engine == null || targetWidth <= 0) { shape.value = null; return@LaunchedEffect }
        engine.pageShape(pageIndex) { shape.value = it }
    }
    val targetRatio = shape.value?.let { (w, h) -> if (w > 0f) h / w else 1.4f } ?: 1.4f

    val bitmapState = remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(engine, pageIndex, targetWidth, night) {
        if (engine == null) { bitmapState.value = null; return@LaunchedEffect }
        bitmapState.value = null
        engine.render(pageIndex, targetWidth, night) { made ->
            if (made != null) bitmapState.value = made
        }
    }

    val userScale = remember { mutableFloatStateOf(1f) }
    val offsetX = remember { mutableFloatStateOf(0f) }
    val offsetY = remember { mutableFloatStateOf(0f) }
    val fitScale = remember(viewportWidthPx, viewportHeightPx, targetRatio, zoom) {
        computeFitScale(zoom, viewportWidthPx, viewportHeightPx, targetRatio)
    }
    LaunchedEffect(fitScale, pageIndex, zoom) {
        userScale.floatValue = 1f
        offsetX.floatValue = 0f
        offsetY.floatValue = 0f
    }
    val totalScale = fitScale * userScale.floatValue

    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(engine, pageIndex) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    if (gestureZoom != 1f) {
                        userScale.floatValue = (userScale.floatValue * gestureZoom).coerceIn(0.5f, 6f)
                    }
                    if (userScale.floatValue > 1.001f) {
                        offsetX.floatValue += pan.x
                        offsetY.floatValue += pan.y
                    } else {
                        offsetX.floatValue = 0f
                        offsetY.floatValue = 0f
                    }
                }
            }
            .pointerInput(engine, pageIndex, pageCount, userScale.floatValue) {
                detectSwipeGesture(
                    threshold = SWIPE_THRESHOLD_PX,
                    onSwipeLeft = {
                        if (pageIndex < pageCount - 1) onPageChange(pageIndex + 1)
                        else onSwipeOut(true)
                    },
                    onSwipeRight = {
                        if (pageIndex > 0) onPageChange(pageIndex - 1)
                        else onSwipeOut(false)
                    },
                )
            }
            .pointerInput(engine, pageIndex) {
                detectTapGestures(
                    onTap = { onTap(TapKind.Single) },
                    onDoubleTap = { onTap(TapKind.Double) },
                    onLongPress = { onTap(TapKind.Long) },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmapState.value
        if (bmp != null) {
            val w = bmp.width.toFloat() * totalScale
            val h = bmp.height.toFloat() * totalScale
            val maxX = (w - size.width).coerceAtLeast(0f) / 2f
            val maxY = (h - size.height).coerceAtLeast(0f) / 2f
            val clampedX = if (maxX > 0f) offsetX.floatValue.coerceIn(-maxX, maxX) else 0f
            val clampedY = if (maxY > 0f) offsetY.floatValue.coerceIn(-maxY, maxY) else 0f
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "page ${pageIndex + 1}",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .graphicsLayer {
                        translationX = clampedX
                        translationY = clampedY
                        scaleX = totalScale
                        scaleY = totalScale
                    }
                    .size(
                        width = with(LocalDensity.current) { w.toDp() },
                        height = with(LocalDensity.current) { h.toDp() },
                    ),
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Rendering page ${pageIndex + 1}",
                    style = SumTypography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FacingPages(
    engine: DocumentEngine?,
    pageIndex: Int,
    pageCount: Int,
    zoom: ZoomFit,
    night: Boolean,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    onTap: (TapKind) -> Unit,
    onPageChange: (Int) -> Unit,
) {
    val left = if (pageIndex == 0) -1 else pageIndex - 1
    val right = if (pageIndex == 0) 0 else pageIndex
    val targetWidth = ((viewportWidthPx - 16f) / 2f).toInt()
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp)
            .pointerInput(engine, pageIndex, pageCount) {
                detectSwipeGesture(
                    threshold = SWIPE_THRESHOLD_PX,
                    onSwipeLeft = { if (pageIndex < pageCount - 1) onPageChange(pageIndex + 1) },
                    onSwipeRight = { if (pageIndex > 0) onPageChange(pageIndex - 1) },
                )
            }
            .pointerInput(engine, pageIndex) {
                detectTapGestures(
                    onTap = { onTap(TapKind.Single) },
                    onDoubleTap = { onTap(TapKind.Double) },
                    onLongPress = { onTap(TapKind.Long) },
                )
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (left >= 0) {
            SinglePageBitmap(engine, left, targetWidth, night, Modifier.weight(1f).fillMaxHeight())
        } else {
            Box(Modifier.weight(1f).fillMaxHeight())
        }
        SinglePageBitmap(engine, right, targetWidth, night, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun BookPages(
    engine: DocumentEngine?,
    pageIndex: Int,
    pageCount: Int,
    zoom: ZoomFit,
    night: Boolean,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    onTap: (TapKind) -> Unit,
    onPageChange: (Int) -> Unit,
) {
    val showingCover = pageIndex == 0
    val right = pageIndex
    val left = if (showingCover) -1 else pageIndex - 1
    val targetWidth = ((viewportWidthPx - 16f) / 2f).toInt()
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp)
            .pointerInput(engine, pageIndex, pageCount) {
                detectSwipeGesture(
                    threshold = SWIPE_THRESHOLD_PX,
                    onSwipeLeft = { if (pageIndex < pageCount - 1) onPageChange(pageIndex + 1) },
                    onSwipeRight = { if (pageIndex > 0) onPageChange(pageIndex - 1) },
                )
            }
            .pointerInput(engine, pageIndex) {
                detectTapGestures(
                    onTap = { onTap(TapKind.Single) },
                    onDoubleTap = { onTap(TapKind.Double) },
                    onLongPress = { onTap(TapKind.Long) },
                )
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (left >= 0) {
            SinglePageBitmap(engine, left, targetWidth, night, Modifier.weight(1f).fillMaxHeight())
        } else {
            BookCover(engine?.spec?.name ?: "", Modifier.weight(1f).fillMaxHeight())
        }
        SinglePageBitmap(engine, right, targetWidth, night, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun SinglePageBitmap(
    engine: DocumentEngine?,
    pageIndex: Int,
    targetWidth: Int,
    night: Boolean,
    modifier: Modifier = Modifier,
) {
    val bitmapState = remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(engine, pageIndex, targetWidth, night) {
        if (engine == null) { bitmapState.value = null; return@LaunchedEffect }
        bitmapState.value = null
        engine.render(pageIndex, targetWidth, night) { bitmapState.value = it }
    }
    val bmp = bitmapState.value
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "page ${pageIndex + 1}",
            contentScale = ContentScale.Fit,
            modifier = modifier,
        )
    } else {
        Box(modifier = modifier.background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center) {
            Text(
                "${pageIndex + 1}",
                style = SumTypography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BookCover(name: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.substringBeforeLast('.', name),
            textAlign = TextAlign.Center,
            style = SumTypography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun computeFitScale(
    zoom: ZoomFit,
    viewportWidth: Float,
    viewportHeight: Float,
    targetRatio: Float,
): Float {
    return when (zoom) {
        ZoomFit.Actual -> 1f
        ZoomFit.FitPage -> {
            val viewportRatio = viewportHeight / viewportWidth.coerceAtLeast(1f)
            if (targetRatio > viewportRatio) viewportHeight / (viewportWidth * targetRatio)
            else 1f
        }
        ZoomFit.FitWidth -> 1f
    }
}

// A swipe is a single drag past a pixel threshold in one direction.
// We track the total horizontal movement between the first finger down
// and the lift, then fire the left/right callback if the absolute
// distance passed the threshold. Compose's built-in
// detectHorizontalDragGestures fires per frame, so we use a manual
// awaitEachGesture loop instead.
private suspend fun PointerInputScope.detectSwipeGesture(
    threshold: Float,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var totalDx = 0f
        var totalDy = 0f
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                val absX = abs(totalDx)
                val absY = abs(totalDy)
                if (absX > threshold && absX > absY * 1.4f) {
                    if (totalDx < 0) onSwipeLeft() else onSwipeRight()
                }
                break
            }
            totalDx += change.positionChange().x
            totalDy += change.positionChange().y
        }
    }
}

private val SWIPE_THRESHOLD_PX = 80f
