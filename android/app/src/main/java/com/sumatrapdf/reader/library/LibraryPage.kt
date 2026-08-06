package com.sumatrapdf.reader.library

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.Dialog
import com.sumatrapdf.reader.Settings
import com.sumatrapdf.reader.textFieldKeyHandler
import kotlin.math.sqrt

// The Compose half of src/LibraryPage.cpp. That file is a renderer over
// data the Python service returns; here the same layout is drawn over
// the data layer in this package. Sizes follow the Win32 ones: 132x240
// tiles, a 260 rail, a 168x250 cover on the detail page.

private val RAIL_WIDTH = 260.dp
private val TILE_WIDTH = 132.dp
private val TILE_COVER_RATIO = 132f / 196f
private val TILE_GAP = 20.dp
private val WALL_PADDING = 20.dp
private val WIKI_DOT = Color(0xFF5DA028)
private const val PINCH_STEP = 1.35f

// What the library wall looks like: how many books fit across a row
// (pinched by the user) and how big their titles are (a setting).
class LibraryLook(
    val columns: Int,
    val titleScale: Float,
    val onColumns: (Int) -> Unit,
)

fun scaledText(style: androidx.compose.ui.text.TextStyle, scale: Float): androidx.compose.ui.text.TextStyle =
    style.copy(
        fontSize = if (style.fontSize.isSpecified) style.fontSize * scale else style.fontSize,
        lineHeight = if (style.lineHeight.isSpecified) style.lineHeight * scale else style.lineHeight,
    )

private suspend fun PointerInputScope.detectPinch(onZoom: (Float) -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var pinching = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                val zoom = event.calculateZoom()
                if (zoom != 1f) {
                    pinching = true
                    onZoom(zoom)
                }
                if (pinching) event.changes.forEach { it.consume() }
            }
            if (event.changes.none { it.pressed }) break
        }
    }
}

private val SORT_CHOICES = listOf(
    SORT_ALPHA to "A-Z",
    SORT_MOST to "Most",
    SORT_FEWEST to "Fewest",
)

@Composable
private fun coverOf(book: Book): Bitmap? =
    produceState<Bitmap?>(CoverCache.cached(book.id), book.id, CoverCache.revision) {
        value = CoverCache.cached(book.id) ?: CoverCache.cover(book)
    }.value

@Composable
private fun posterOf(url: String?): Bitmap? =
    produceState<Bitmap?>(initialValue = null, url) {
        if (url != null) value = CoverCache.poster(url)
    }.value

@Composable
fun LibraryPage(
    model: LibraryModel,
    look: LibraryLook,
    onOpen: (String) -> Unit,
    onOpenAt: (String, Int) -> Unit,
    onClassicHome: () -> Unit,
    onEditCover: (Book) -> Unit,
    railOpen: Boolean,
    onRailOpen: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    anyTextFieldFocused: androidx.compose.runtime.MutableState<Boolean> = androidx.compose.runtime.mutableStateOf(false),
) {
    LaunchedEffect(Unit) { model.load() }

    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= 640.dp
        Row(Modifier.fillMaxSize()) {
            if (wide) {
                LibraryRail(model, onClassicHome, Modifier.width(RAIL_WIDTH).fillMaxHeight(), anyTextFieldFocused = anyTextFieldFocused)
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val detail = model.detail
                if (detail != null) {
                    BookDetailPage(model, detail, onOpen, onOpenAt, onEditCover)
                } else if (model.deskpanOpen) {
                    DeskpanPage(model, wide, { onRailOpen(true) }, onOpen)
                } else {
                    LibraryGrid(model, look, wide, { onRailOpen(true) }, onOpen, anyTextFieldFocused)
                }
            }
        }
        if (!wide && railOpen) {
            Box(
                Modifier.fillMaxSize()
                    .background(Color(0x99000000))
                    .clickable { onRailOpen(false) },
            )
            LibraryRail(
                model,
                onClassicHome,
                Modifier.width(RAIL_WIDTH).fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surface),
                onPicked = { onRailOpen(false) },
                anyTextFieldFocused = anyTextFieldFocused,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryRail(
    model: LibraryModel,
    onClassicHome: () -> Unit,
    modifier: Modifier = Modifier,
    onPicked: () -> Unit = {},
    anyTextFieldFocused: androidx.compose.runtime.MutableState<Boolean> = androidx.compose.runtime.mutableStateOf(false),
) {
    val colors = MaterialTheme.colorScheme
    var menuFor by remember { mutableStateOf<SeriesRow?>(null) }
    var renaming by remember { mutableStateOf<SeriesRow?>(null) }
    var creatingFor by remember { mutableStateOf<SeriesRow?>(null) }
    var creatingOpen by remember { mutableStateOf(false) }
    val ordered = remember(model.rows, model.sort) { sortSeries(model.rows, model.sort) }
    val visibleRows = if (model.deskpanOpen) emptyList() else ordered.filter { model.rowCount(it) > 0 }

    Column(modifier.background(colors.surfaceVariant)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Sort", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            for ((key, label) in SORT_CHOICES) {
                val active = model.sort == key
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) colors.onSurface else colors.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (active) colors.outlineVariant else Color.Transparent)
                        .clickable { model.sort = key }
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
                Spacer(Modifier.width(2.dp))
            }
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item {
                RailRow(
                    name = "Everything",
                    count = model.bookCount,
                    depth = 0,
                    selected = !model.deskpanOpen && model.filterKey == null,
                    onClick = {
                        model.deskpanOpen = false
                        model.selectRow(null); onPicked()
                    },
                    onLongClick = {},
                )
            }
            if (model.documentCount > 0 || model.ignoredCount > 0) {
                item {
                    RailRow(
                        name = "Deskpan",
                        count = model.documentCount,
                        depth = 0,
                        selected = model.deskpanOpen,
                        onClick = {
                            model.toggleDeskpan()
                            onPicked()
                        },
                        onLongClick = {},
                    )
                }
            }
            items(visibleRows, key = { it.key }) { row ->
                if (row.head != null) {
                    Text(
                        row.head!!,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurface,
                        modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 2.dp),
                    )
                }
                if (row.subhead != null) {
                    Text(
                        row.subhead!!,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = 14.dp, bottom = 2.dp),
                    )
                }
                RailRow(
                    name = row.name,
                    count = model.rowCount(row),
                    depth = row.depth,
                    selected = !model.deskpanOpen && model.filterKey == row.key,
                    guessed = row.guessed,
                    onClick = { model.deskpanOpen = false; model.selectRow(row); onPicked() },
                    onLongClick = { menuFor = row },
                )
                if (menuFor === row) {
                    RailMenu(
                        model = model,
                        row = row,
                        onDismiss = { menuFor = null },
                        onRename = { renaming = row; menuFor = null },
                        onNewPartition = { creatingFor = row; creatingOpen = true; menuFor = null },
                    )
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
            val label = when {
                model.scanning && model.scanTotal > 0 -> "Scanning ${model.scanDone} of ${model.scanTotal}..."
                model.scanning -> "Scanning..."
                else -> "Rescan library"
            }
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = colors.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !model.scanning) { model.rescan(null) }
                    .padding(vertical = 5.dp),
            )
            model.sweeping?.let {
                Text(
                    if (it == "covers") "Making covers..." else "Looking books up...",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Text(
                "Frequently read",
                style = MaterialTheme.typography.labelMedium,
                color = colors.primary,
                modifier = Modifier.fillMaxWidth().clickable { onClassicHome() }.padding(vertical = 5.dp),
            )
        }
    }

    if (creatingOpen) {
        NameDialog("New partition", "", anyTextFieldFocused) { name ->
            creatingOpen = false
            if (name != null) model.newPartition(name, creatingFor)
            creatingFor = null
        }
    }
    renaming?.let { row ->
        NameDialog("Rename partition", row.name, anyTextFieldFocused) { name ->
            if (name != null) model.renamePartitionRow(row, name)
            renaming = null
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RailRow(
    name: String,
    count: Int,
    depth: Int,
    selected: Boolean,
    guessed: String? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) colors.outlineVariant else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = (8 + depth * 14).dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (guessed != null) {
                Text(
                    "put here because it says \"$guessed\"",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun RailMenu(
    model: LibraryModel,
    row: SeriesRow,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onNewPartition: () -> Unit,
) {
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("New partition from this") }, onClick = onNewPartition)
        if (row.parent != null) {
            DropdownMenuItem(
                text = { Text("Take out of the partition") },
                onClick = { model.takeOutOfPartition(row); onDismiss() },
            )
        }
        for (p in model.partitions) {
            if (p.key == row.key) continue
            DropdownMenuItem(
                text = { Text("Move to ${p.name}") },
                onClick = { model.moveToPartition(row, p.key); onDismiss() },
            )
        }
        if (row.kind == "partition") {
            DropdownMenuItem(text = { Text("Rename partition") }, onClick = onRename)
            DropdownMenuItem(
                text = { Text("Delete partition") },
                onClick = { model.deletePartitionRow(row); onDismiss() },
            )
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    anyTextFieldFocused: androidx.compose.runtime.MutableState<Boolean>,
    done: (String?) -> Unit,
) {
    var fieldValue by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val submit: () -> Unit = { done(fieldValue.text.trim().ifEmpty { null }) }
    AlertDialog(
        onDismissRequest = { done(null) },
        title = { Text(title) },
        text = {
            BasicTextField(
                value = fieldValue,
                onValueChange = { fieldValue = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                    .padding(8.dp)
                    .focusRequester(focusRequester)
                    .textFieldKeyHandler(
                        value = fieldValue,
                        onValueChange = { fieldValue = it },
                        anyTextFieldFocused = anyTextFieldFocused,
                        onSubmit = submit,
                        onClose = { done(null) },
                    ),
            )
        },
        confirmButton = { TextButton(onClick = submit) { Text("OK") } },
        dismissButton = { TextButton(onClick = { done(null) }) { Text("Cancel") } },
    )
}

@Composable
private fun DeskpanPage(
    model: LibraryModel,
    wide: Boolean,
    onShowRail: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val files = model.deskFiles()
    val chosen = model.deskChosen

    Column(Modifier.fillMaxSize().background(colors.background)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!wide) {
                Text(
                    "Shelves",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.primary,
                    modifier = Modifier.clickable { onShowRail() }.padding(end = 12.dp),
                )
            }
            Text(
                if (model.deskShowIgnored) "Deskpan · ignored" else "Deskpan",
                style = MaterialTheme.typography.titleLarge,
                color = colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        val what = if (model.deskShowIgnored) "ignored" else "documents"
        Text(
            when {
                model.deskWorking -> "moving files..."
                model.loading -> "reading the desk..."
                else -> "${files.size} $what · ${chosen.size} chosen"
            },
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 6.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for ((i, label) in listOf("Documents", "Ignored").withIndex()) {
                val active = model.deskShowIgnored == (i == 1)
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (active) colors.onSurface else colors.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (active) colors.outlineVariant else Color.Transparent)
                        .clickable { model.deskShow(i == 1) }
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
        }
        DeskActions(model, files, chosen.size)
        if (files.isEmpty()) {
            Text(
                if (model.deskShowIgnored) {
                    "Nothing is being ignored."
                } else {
                    "Every file the scan found looks like a book."
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(files, key = { _, f -> f.id }) { at, f ->
                DeskRow(
                    file = f,
                    chosen = f.path in chosen,
                    onTick = { model.deskTick(files, at) },
                    onPick = { model.deskPick(files, at, false) },
                    onRun = { model.deskPick(files, at, true) },
                    onOpen = { onOpen(f.path) },
                )
            }
        }
    }
}

@Composable
private fun DeskActions(model: LibraryModel, files: List<Book>, chosen: Int) {
    val colors = MaterialTheme.colorScheme
    val doing = if (model.deskShowIgnored) {
        listOf(KIND_BOOK to "Move selected to library", KIND_DOCUMENT to "Remove from library")
    } else {
        listOf(KIND_BOOK to "Move selected to library", KIND_IGNORED to "Ignore file")
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((kind, label) in doing) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (chosen > 0) colors.primary else colors.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (chosen > 0) colors.outlineVariant else colors.surface)
                    .clickable(enabled = chosen > 0) { model.moveDeskChosen(kind) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            if (chosen > 0) "Select none" else "Select all",
            style = MaterialTheme.typography.labelMedium,
            color = colors.primary,
            maxLines = 1,
            modifier = Modifier
                .clickable { model.deskPickAll(files) }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeskRow(
    file: Book,
    chosen: Boolean,
    onTick: () -> Unit,
    onPick: () -> Unit,
    onRun: () -> Unit,
    onOpen: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val lineColor = colors.outlineVariant
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 20.dp)
            .background(if (chosen) colors.surfaceVariant else Color.Transparent, RoundedCornerShape(5.dp))
            .height(58.dp)
            .drawBehind {
                drawLine(
                    lineColor,
                    Offset(0f, size.height - 1f),
                    Offset(size.width, size.height - 1f),
                    strokeWidth = 1f,
                )
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.fillMaxHeight().clickable { onTick() }.padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(14.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (chosen) colors.primary else colors.outlineVariant),
            )
        }
        Box(
            Modifier.width(38.dp).height(50.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(colors.outlineVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                file.ext.trimStart('.').uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Row(
            Modifier.weight(1f).fillMaxHeight()
                .combinedClickable(onClick = onPick, onLongClick = onRun)
                .padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                file.file.ifBlank { file.title },
                style = MaterialTheme.typography.titleSmall,
                color = colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(2f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                deskWhere(file.folder),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                deskSize(file),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onBackground,
                maxLines = 1,
            )
        }
        Text(
            "Open",
            style = MaterialTheme.typography.labelMedium,
            color = colors.primary,
            modifier = Modifier.clickable { onOpen() }.padding(horizontal = 10.dp, vertical = 18.dp),
        )
    }
}

private val PRIMARY_STORAGE = Regex("^/storage/emulated/\\d+/?")

private fun deskWhere(folder: String): String =
    PRIMARY_STORAGE.replace(folder, "").ifBlank { folder }

private fun deskSize(f: Book): String {
    val parts = ArrayList<String>(2)
    if (f.pages > 0) parts.add("${f.pages} ${if (f.pages == 1) "page" else "pages"}")
    if (f.size > 0) parts.add(fileSizeText(f.size))
    return parts.joinToString(" · ")
}

private fun fileSizeText(size: Long): String {
    val kb = size / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> String.format("%.2f GB", gb)
        mb >= 1 -> String.format("%.2f MB", mb)
        kb >= 1 -> String.format("%.2f KB", kb)
        else -> "$size B"
    }
}

@Composable
private fun LibraryGrid(
    model: LibraryModel,
    look: LibraryLook,
    wide: Boolean,
    onShowRail: () -> Unit,
    onOpen: (String) -> Unit,
    anyTextFieldFocused: androidx.compose.runtime.MutableState<Boolean>,
) {
    val colors = MaterialTheme.colorScheme
    val shown = model.visibleBooks()
    val withWiki = shown.count { it.booknlp }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!wide) {
                Text(
                    "Shelves",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.primary,
                    modifier = Modifier.clickable { onShowRail() }.padding(end = 12.dp),
                )
            }
            Text(
                model.filterName ?: "Everything",
                style = MaterialTheme.typography.titleLarge,
                color = colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            "${shown.size} ${if (shown.size == 1) "book" else "books"} · $withWiki read by BookNLP",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 6.dp),
        )
        var searchField by remember { mutableStateOf(TextFieldValue(model.search, TextRange(model.search.length))) }
        androidx.compose.runtime.LaunchedEffect(model.search) {
            if (searchField.text != model.search) {
                searchField = TextFieldValue(model.search, TextRange(model.search.length))
            }
        }
        val updateSearch: (TextFieldValue) -> Unit = { v ->
            searchField = v
            model.search = v.text
        }
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        BasicTextField(
            value = searchField,
            onValueChange = updateSearch,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth()
                        .background(colors.surface, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    if (model.search.isEmpty()) {
                        Text(
                            "Search title, author or series",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    inner()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp)
                .focusRequester(focusRequester)
                .textFieldKeyHandler(
                    value = searchField,
                    onValueChange = updateSearch,
                    anyTextFieldFocused = anyTextFieldFocused,
                ),
        )
        if (model.scanning) {
            LinearProgressIndicator(
                progress = {
                    if (model.scanTotal > 0) model.scanDone / model.scanTotal.toFloat() else 0f
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        model.error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = colors.error,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        if (shown.isEmpty() && !model.scanning) {
            Text(
                "No books found. Rescan the library, or add a folder that holds them.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        }
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            val room = maxWidth - WALL_PADDING * 2
            val fits = ((room + TILE_GAP) / (TILE_WIDTH + TILE_GAP)).toInt()
                .coerceIn(Settings.MIN_LIBRARY_COLUMNS, Settings.MAX_LIBRARY_COLUMNS)
            val columns = (if (look.columns > 0) look.columns else fits)
                .coerceIn(Settings.MIN_LIBRARY_COLUMNS, Settings.MAX_LIBRARY_COLUMNS)
            val cell = (room - TILE_GAP * (columns - 1)) / columns
            val scale = (sqrt(cell / TILE_WIDTH) * look.titleScale).coerceIn(0.6f, 2.5f)
            var pinch by remember { mutableStateOf(1f) }

            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                contentPadding = PaddingValues(WALL_PADDING),
                horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
                verticalArrangement = Arrangement.spacedBy(22.dp),
                modifier = Modifier.fillMaxSize().pointerInput(columns) {
                    detectPinch { zoom ->
                        pinch *= zoom
                        if (pinch >= PINCH_STEP) {
                            pinch = 1f
                            if (columns > Settings.MIN_LIBRARY_COLUMNS) look.onColumns(columns - 1)
                        } else if (pinch <= 1f / PINCH_STEP) {
                            pinch = 1f
                            if (columns < Settings.MAX_LIBRARY_COLUMNS) look.onColumns(columns + 1)
                        }
                    }
                },
            ) {
                items(shown, key = { it.id }) { book ->
                    CoverTile(
                        book,
                        scale,
                        onOpen = { onOpen(book.path) },
                        onAbout = { model.openBook(book) },
                        onKind = { kind -> model.moveOneFile(book.path, kind) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CoverTile(
    book: Book,
    scale: Float,
    onOpen: () -> Unit,
    onAbout: () -> Unit,
    onKind: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val bitmap = coverOf(book)
    var menuOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth()
                .aspectRatio(TILE_COVER_RATIO)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.outlineVariant)
                .combinedClickable(onClick = onOpen, onLongClick = { menuOpen = true }),
            contentAlignment = Alignment.Center,
        ) {
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Remove from library") },
                    onClick = { menuOpen = false; onKind(KIND_DOCUMENT) },
                )
                DropdownMenuItem(
                    text = { Text("Ignore file") },
                    onClick = { menuOpen = false; onKind(KIND_IGNORED) },
                )
            }
            if (bitmap != null) {
                val squat = bitmap.width.toFloat() / bitmap.height > TILE_COVER_RATIO
                Image(
                    bitmap.asImageBitmap(),
                    contentDescription = book.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                        .align(if (squat) Alignment.BottomCenter else Alignment.Center),
                )
            } else {
                Text(
                    book.title,
                    style = scaledText(MaterialTheme.typography.bodySmall, scale),
                    color = colors.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(8.dp),
                )
            }
            if (book.booknlp) {
                Box(
                    Modifier.align(Alignment.TopEnd)
                        .padding(5.dp)
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(WIKI_DOT),
                )
            }
        }
        Column(Modifier.fillMaxWidth().clickable { onAbout() }) {
            Text(
                book.title,
                style = scaledText(MaterialTheme.typography.titleSmall, scale),
                color = colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitleOf(book),
                style = scaledText(MaterialTheme.typography.bodySmall, scale),
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// LibraryPage.cpp::SubtitleTemp
fun subtitleOf(book: Book): String {
    val parts = mutableListOf<String>()
    book.volumes.firstOrNull()?.let { parts.add("#$it") }
    val author = book.author?.takeIf { it.isNotBlank() && it != "null" }
    author?.let { parts.add(it) }
    if (parts.isEmpty() && book.pages > 0) parts.add("${book.pages} pages")
    return parts.joinToString(" · ")
}

@Composable
private fun BookDetailPage(
    model: LibraryModel,
    detail: BookDetail,
    onOpen: (String) -> Unit,
    onOpenAt: (String, Int) -> Unit,
    onEditCover: (Book) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val book = detail.book
    val scroll = rememberScrollState()

    Column(
        Modifier.fillMaxSize().background(colors.background).verticalScroll(scroll).padding(24.dp),
    ) {
        Text(
            "< Back to the library",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.primary,
            modifier = Modifier.clickable { model.closeDetail() },
        )
        Spacer(Modifier.height(14.dp))

        Row(Modifier.fillMaxWidth()) {
            val bitmap = coverOf(book)
            Column {
                Box(
                    Modifier.width(168.dp).height(250.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.outlineVariant)
                        .clickable { onEditCover(book) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap.asImageBitmap(),
                            contentDescription = book.title,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text(
                            "No cover",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    "Change the cover",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.primary,
                    modifier = Modifier.width(168.dp)
                        .clickable { onEditCover(book) }
                        .padding(top = 6.dp),
                )
            }
            Spacer(Modifier.width(22.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    detailMetaLine(detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Read",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurface,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.outlineVariant)
                        .clickable { onOpen(book.path) }
                        .padding(horizontal = 24.dp, vertical = 6.dp),
                )
                Spacer(Modifier.height(12.dp))
                val blurb = detail.meta?.description
                    ?: if (detail.metaLoading) "Looking this book up..." else null
                if (blurb != null) {
                    Text(blurb, style = MaterialTheme.typography.bodyMedium, color = colors.onBackground)
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            for (tab in LibraryTab.entries) {
                val active = detail.tab == tab
                Text(
                    tabLabel(tab),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (active) colors.onSurface else colors.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (active) colors.outlineVariant else Color.Transparent)
                        .clickable { detail.tab = tab }
                        .padding(horizontal = 11.dp, vertical = 5.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
        }
        Spacer(Modifier.height(16.dp))

        when (detail.tab) {
            LibraryTab.Overview -> OverviewTab(model, detail, onOpenAt)
            LibraryTab.Characters -> CharactersTab(model, detail)
            LibraryTab.Family -> FamilyTab(model, detail)
            LibraryTab.Places -> ChipFlow(detail.summary?.places ?: emptyList()) {}
            LibraryTab.Knows -> KnowsTab(model, detail)
            LibraryTab.Screen -> ScreenTab(model, detail)
        }
        Spacer(Modifier.height(40.dp))
    }
}

private fun tabLabel(tab: LibraryTab): String = when (tab) {
    LibraryTab.Overview -> "Overview"
    LibraryTab.Characters -> "Characters"
    LibraryTab.Family -> "Family"
    LibraryTab.Places -> "Places"
    LibraryTab.Knows -> "Who knows what"
    LibraryTab.Screen -> "On screen"
}

private fun detailMetaLine(detail: BookDetail): String {
    val book = detail.book
    val parts = mutableListOf<String>()
    (book.author ?: detail.meta?.author)?.let { parts.add(it) }
    (book.year ?: detail.meta?.year)?.let { parts.add(it.toString()) }
    book.series?.let { parts.add(it) }
    if (book.pages > 0) parts.add("${book.pages} pages")
    return parts.joinToString(" · ")
}

@Composable
private fun OverviewTab(model: LibraryModel, detail: BookDetail, onOpenAt: (String, Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    LaunchedEffect(detail.book.id) { model.ensureChapters(detail) }

    val subjects = detail.meta?.subjects ?: emptyList()
    if (subjects.isNotEmpty()) {
        Text(
            subjects.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
    }
    val people = detail.summary?.people
    if (people.isNullOrEmpty()) {
        Text(
            "No wiki yet for this book. Read it aloud with the BookNLP analyser to build one.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
    } else {
        ChipFlow(people.take(18)) { model.openPerson(detail, it) }
    }
    Spacer(Modifier.height(14.dp))
    ChapterTree(detail, onOpenAt)
}

@Composable
private fun ChapterTree(detail: BookDetail, onOpenAt: (String, Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val list = detail.chapters
    if (detail.chaptersLoading) {
        Text("Reading the chapters...", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        return
    }
    if (list == null || list.chapters.isEmpty()) {
        Text(
            "No chapter list in this file.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        return
    }
    Text(
        "${list.count} chapters (from the ${list.source})",
        style = MaterialTheme.typography.labelMedium,
        color = colors.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    Column(Modifier.fillMaxWidth()) {
        ChapterRows(detail, list.chapters, 0, onOpenAt)
    }
}

@Composable
private fun ChapterRows(
    detail: BookDetail,
    rows: List<ChapterRow>,
    depth: Int,
    onOpenAt: (String, Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    for (row in rows) {
        val id = "$depth:${row.page}:${row.title}"
        val open = id in detail.openChapters
        Row(
            Modifier.fillMaxWidth()
                .clickable { onOpenAt(detail.book.path, row.page) }
                .padding(start = (depth * 14).dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (row.children.isNotEmpty()) {
                Text(
                    if (open) "▾" else "▸",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier
                        .clickable {
                            detail.openChapters = if (open) detail.openChapters - id else detail.openChapters + id
                        }
                        .padding(end = 6.dp),
                )
            } else {
                Spacer(Modifier.width(16.dp))
            }
            Text(
                row.title,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                row.page.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        if (open) ChapterRows(detail, row.children, depth + 1, onOpenAt)
    }
}

@Composable
private fun CharactersTab(model: LibraryModel, detail: BookDetail) {
    val colors = MaterialTheme.colorScheme
    val person = detail.person
    if (person == null) {
        val people = detail.summary?.people ?: emptyList()
        if (people.isEmpty()) {
            Text("No wiki for this book yet.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        } else {
            ChipFlow(people) { model.openPerson(detail, it) }
        }
        return
    }
    Text(
        "< All characters",
        style = MaterialTheme.typography.bodyMedium,
        color = colors.primary,
        modifier = Modifier.clickable { model.clearPerson(detail) },
    )
    Spacer(Modifier.height(8.dp))
    Text(person.name, style = MaterialTheme.typography.titleLarge, color = colors.onBackground)
    Spacer(Modifier.height(6.dp))
    WikiGroup("Described as", person.description["describes"])
    WikiGroup("Not", person.description["describes_not"])
    WikiGroup("Voice", person.voice["voice"])
    WikiGroup("Speaks", person.voice["speaks"])
    WikiGroup("Seen at", person.places["seen_at"])
    WikiGroup("Knows about", person.knows["knows"])
    for ((relation, values) in person.family) {
        WikiGroup(prettyRelation(relation), values)
    }
    if (person.books.isNotEmpty()) {
        Text(
            "In: ${person.books.joinToString(", ")}",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun WikiGroup(title: String, values: List<WikiValue>?) {
    if (values.isNullOrEmpty()) return
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        Text(
            values.joinToString(", ") { it.value },
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onBackground,
            modifier = Modifier.weight(1f),
        )
    }
}

fun prettyRelation(relation: String): String = relation.replace('_', ' ')

@Composable
private fun FamilyTab(model: LibraryModel, detail: BookDetail) {
    val colors = MaterialTheme.colorScheme
    val person = detail.person
    if (person == null) {
        Text(
            "Pick a character on the Characters tab to see their family.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        return
    }
    Text(person.name, style = MaterialTheme.typography.titleMedium, color = colors.onBackground)
    Spacer(Modifier.height(6.dp))
    if (person.family.isEmpty()) {
        Text(
            "Nothing recorded.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        return
    }
    for ((relation, values) in person.family) {
        for (v in values) {
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(
                    prettyRelation(relation),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.width(120.dp),
                )
                Text(
                    v.value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.primary,
                    modifier = Modifier.weight(1f).clickable { model.openPerson(detail, v.value) },
                )
            }
        }
    }
}

@Composable
private fun KnowsTab(model: LibraryModel, detail: BookDetail) {
    val colors = MaterialTheme.colorScheme
    val topics = detail.summary?.topics ?: emptyList()
    if (topics.isEmpty()) {
        Text(
            "No wiki for this book yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        return
    }
    ChipFlow(topics) { model.openTopic(detail, it) }
    val topic = detail.topic ?: return
    Spacer(Modifier.height(12.dp))
    Text(topic, style = MaterialTheme.typography.titleMedium, color = colors.onBackground)
    Spacer(Modifier.height(4.dp))
    if (detail.knowers.isEmpty()) {
        Text("Nobody recorded.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
    }
    for (k in detail.knowers) {
        Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    k.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.primary,
                    modifier = Modifier.weight(1f).clickable { model.openPerson(detail, k.name) },
                )
                Text(
                    "${k.mentions}×",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            k.evidence?.let { e ->
                Text(
                    e.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ScreenTab(model: LibraryModel, detail: BookDetail) {
    val colors = MaterialTheme.colorScheme
    LaunchedEffect(detail.book.id) { model.ensureScreen(detail) }
    val rows = detail.screen
    if (rows == null) {
        Text(
            if (detail.screenLoading) "Looking for films and TV..." else "",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        return
    }
    if (rows.isEmpty()) {
        Text(
            "No film or TV adaptation found for this book.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        return
    }
    Row(Modifier.fillMaxWidth()) {
        for (row in rows.take(4)) {
            Column(Modifier.width(140.dp).padding(end = 14.dp)) {
                val poster = posterOf(row.poster)
                Box(
                    Modifier.fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.outlineVariant),
                ) {
                    if (poster != null) {
                        Image(
                            poster.asImageBitmap(),
                            contentDescription = row.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Text(
                    row.title ?: row.imdbId,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(row.kind, row.year?.toString()).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipFlow(items: List<String>, onClick: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (item in items) {
            Text(
                item,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurface,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surfaceVariant)
                    .clickable { onClick(item) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}
