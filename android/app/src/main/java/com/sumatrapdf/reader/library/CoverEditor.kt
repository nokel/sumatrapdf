package com.sumatrapdf.reader.library

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val HANDLE_REACH = 64f
private const val MIN_BOX = 24f

private class CropBox(var x0: Float, var y0: Float, var x1: Float, var y1: Float) {
    val left get() = min(x0, x1)
    val top get() = min(y0, y1)
    val right get() = max(x0, x1)
    val bottom get() = max(y0, y1)
    val width get() = right - left
    val height get() = bottom - top
}

private enum class DragKind { None, New, Move, TopLeft, TopRight, BottomLeft, BottomRight }

@Composable
fun CoverEditor(model: LibraryModel, book: Book, onClose: () -> Unit) {
    var pickingPage by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        if (pickingPage) {
            PageCropper(model, book, onCancel = { pickingPage = false }, onDone = onClose)
        } else {
            CoverSourceChooser(
                model = model,
                book = book,
                onPickPage = { pickingPage = true },
                onClose = onClose,
            )
        }
    }
}

@Composable
private fun CoverSourceChooser(
    model: LibraryModel,
    book: Book,
    onPickPage: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                val bitmap = try {
                    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                } catch (_: Throwable) {
                    null
                }
                if (bitmap == null) {
                    false
                } else {
                    val saved = setCoverFromBitmap(book, bitmap) != null
                    bitmap.recycle()
                    if (saved) CoverChoices.rememberFile(book.id, uri.toString())
                    saved
                }
            }
            busy = false
            if (ok) {
                model.coverChanged(book)
                onClose()
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(colors.background).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            book.title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Where should the cover come from?",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = { picker.launch("image/*") }, enabled = !busy) {
            Text("Choose an image file")
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onPickPage, enabled = !busy) {
            Text("Take it from a page")
        }
        if (CoverChoices.isChosenByHand(book.id)) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    CoverChoices.forget(book.id)
                    scope.launch {
                        withContext(Dispatchers.IO) { buildCover(book, force = true) }
                        model.coverChanged(book)
                        onClose()
                    }
                },
                enabled = !busy,
            ) {
                Text("Back to the automatic cover")
            }
        }
        Spacer(Modifier.height(1.dp).weight(1f))
        if (busy) CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onClose, enabled = !busy) { Text("Cancel") }
    }
}

@Composable
private fun PageCropper(
    model: LibraryModel,
    book: Book,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var pageIndex by remember { mutableStateOf(0) }
    var rendered by remember { mutableStateOf<RenderedPage?>(null) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var box by remember { mutableStateOf<CropBox?>(null) }
    var area by remember { mutableStateOf(IntSize.Zero) }
    var dragging by remember { mutableStateOf(DragKind.None) }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Previous",
                style = MaterialTheme.typography.labelLarge,
                color = if (pageIndex > 0) colors.primary else colors.onSurfaceVariant,
                modifier = Modifier.clickable(enabled = pageIndex > 0 && !saving) {
                    pageIndex--
                    box = null
                },
            )
            Spacer(Modifier.width(16.dp))
            val total = rendered?.pages ?: 0
            Text(
                if (total > 0) "Page ${pageIndex + 1} of $total" else "Loading...",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onBackground,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Next",
                style = MaterialTheme.typography.labelLarge,
                color = if (total == 0 || pageIndex + 1 < total) colors.primary else colors.onSurfaceVariant,
                modifier = Modifier.clickable(enabled = (total == 0 || pageIndex + 1 < total) && !saving) {
                    pageIndex++
                    box = null
                },
            )
        }
        Text(
            "Drag across the part of the page you want, then drag the corners to adjust it.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        BoxWithConstraints(
            Modifier.fillMaxWidth().weight(1f).padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            val wantW = with(density) { maxWidth.roundToPx() }
            val wantH = with(density) { maxHeight.roundToPx() }
            LaunchedEffect(pageIndex, wantW, wantH) {
                if (wantW <= 0 || wantH <= 0) return@LaunchedEffect
                loading = true
                val shown = withContext(Dispatchers.IO) {
                    renderBookPage(book.path, pageIndex, wantW, wantH)
                }
                rendered = shown
                if (shown != null) pageIndex = shown.index
                loading = false
            }

            val shown = rendered
            if (loading && shown == null) {
                CircularProgressIndicator()
            } else if (shown != null) {
                Box(
                    Modifier
                        .width(with(density) { shown.bitmap.width.toDp() })
                        .height(with(density) { shown.bitmap.height.toDp() })
                        .onSizeChanged { area = it }
                        .pointerInput(shown) {
                            detectDragGestures(
                                onDragStart = { at ->
                                    val have = box
                                    dragging = kindFor(have, at)
                                    if (dragging == DragKind.New) {
                                        box = CropBox(at.x, at.y, at.x, at.y)
                                    }
                                },
                                onDrag = { change, delta ->
                                    change.consume()
                                    val have = box ?: return@detectDragGestures
                                    val w = area.width.toFloat()
                                    val h = area.height.toFloat()
                                    when (dragging) {
                                        DragKind.New -> {
                                            have.x1 = (have.x1 + delta.x).coerceIn(0f, w)
                                            have.y1 = (have.y1 + delta.y).coerceIn(0f, h)
                                        }
                                        DragKind.Move -> {
                                            val dx = delta.x.coerceIn(-have.left, w - have.right)
                                            val dy = delta.y.coerceIn(-have.top, h - have.bottom)
                                            have.x0 += dx
                                            have.x1 += dx
                                            have.y0 += dy
                                            have.y1 += dy
                                        }
                                        DragKind.TopLeft -> {
                                            have.x0 = (have.x0 + delta.x).coerceIn(0f, have.x1 - MIN_BOX)
                                            have.y0 = (have.y0 + delta.y).coerceIn(0f, have.y1 - MIN_BOX)
                                        }
                                        DragKind.TopRight -> {
                                            have.x1 = (have.x1 + delta.x).coerceIn(have.x0 + MIN_BOX, w)
                                            have.y0 = (have.y0 + delta.y).coerceIn(0f, have.y1 - MIN_BOX)
                                        }
                                        DragKind.BottomLeft -> {
                                            have.x0 = (have.x0 + delta.x).coerceIn(0f, have.x1 - MIN_BOX)
                                            have.y1 = (have.y1 + delta.y).coerceIn(have.y0 + MIN_BOX, h)
                                        }
                                        DragKind.BottomRight -> {
                                            have.x1 = (have.x1 + delta.x).coerceIn(have.x0 + MIN_BOX, w)
                                            have.y1 = (have.y1 + delta.y).coerceIn(have.y0 + MIN_BOX, h)
                                        }
                                        DragKind.None -> {}
                                    }
                                    box = CropBox(have.x0, have.y0, have.x1, have.y1)
                                },
                                onDragEnd = {
                                    val have = box
                                    dragging = DragKind.None
                                    if (have != null && (have.width < MIN_BOX || have.height < MIN_BOX)) {
                                        box = null
                                    } else if (have != null) {
                                        box = CropBox(have.left, have.top, have.right, have.bottom)
                                    }
                                },
                                onDragCancel = { dragging = DragKind.None },
                            )
                        },
                ) {
                    Image(
                        shown.bitmap.asImageBitmap(),
                        contentDescription = book.title,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize(),
                    )
                    val have = box
                    if (have != null) {
                        Canvas(Modifier.fillMaxSize()) {
                            drawRect(
                                color = Color(0x99000000),
                                size = Size(size.width, have.top),
                            )
                            drawRect(
                                color = Color(0x99000000),
                                topLeft = Offset(0f, have.bottom),
                                size = Size(size.width, size.height - have.bottom),
                            )
                            drawRect(
                                color = Color(0x99000000),
                                topLeft = Offset(0f, have.top),
                                size = Size(have.left, have.height),
                            )
                            drawRect(
                                color = Color(0x99000000),
                                topLeft = Offset(have.right, have.top),
                                size = Size(size.width - have.right, have.height),
                            )
                            drawRect(
                                color = Color.White,
                                topLeft = Offset(have.left, have.top),
                                size = Size(have.width, have.height),
                                style = Stroke(width = 3f),
                            )
                            for (corner in corners(have)) {
                                drawCircle(Color.White, radius = 14f, center = corner)
                            }
                        }
                    }
                }
            } else {
                Text(
                    "That page could not be drawn.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onCancel, enabled = !saving) { Text("Cancel") }
            Spacer(Modifier.width(20.dp))
            Button(
                enabled = box != null && rendered != null && !saving,
                onClick = {
                    val have = box ?: return@Button
                    val shown = rendered ?: return@Button
                    val rect = pageRectOfCrop(shown, have.left, have.top, have.right, have.bottom)
                    saving = true
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            val made = coverFromFile(book.path, shown.index, rect)
                            val data = made.data
                            if (data == null) {
                                false
                            } else {
                                val saved = writeCover(book.id, data) != null
                                if (saved) {
                                    CoverChoices.rememberPageCrop(book.id, shown.index, rect)
                                    learnCoverCrop(book.path, shown.index, rect)
                                }
                                saved
                            }
                        }
                        saving = false
                        if (ok) {
                            model.coverChanged(book)
                            model.relearnCovers()
                            onDone()
                        }
                    }
                },
            ) {
                Text(if (saving) "Saving..." else "Accept")
            }
        }
    }
}

private fun corners(b: CropBox): List<Offset> = listOf(
    Offset(b.left, b.top),
    Offset(b.right, b.top),
    Offset(b.left, b.bottom),
    Offset(b.right, b.bottom),
)

private fun near(a: Offset, b: Offset): Boolean =
    abs(a.x - b.x) <= HANDLE_REACH && abs(a.y - b.y) <= HANDLE_REACH

private fun kindFor(box: CropBox?, at: Offset): DragKind {
    if (box == null) return DragKind.New
    val list = corners(box)
    if (near(at, list[0])) return DragKind.TopLeft
    if (near(at, list[1])) return DragKind.TopRight
    if (near(at, list[2])) return DragKind.BottomLeft
    if (near(at, list[3])) return DragKind.BottomRight
    val inside = at.x in box.left..box.right && at.y in box.top..box.bottom
    return if (inside) DragKind.Move else DragKind.New
}
