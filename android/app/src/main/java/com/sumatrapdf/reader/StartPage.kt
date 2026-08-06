package com.sumatrapdf.reader

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Reorder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Port of src/HomePage.cpp::LayoutHomePage / DrawHomePage — the page
// SumatraPDF shows when no document is loaded. Top to bottom:
//
//   [grid][list]  Recently Opened   [icon] Open a document...   SumatraPDF 1.0
//   ------------------------------------------------------------------
//   [ search filter                                                  ]
//   thumbnail grid, or one row per file in list view
//
// The Win32 page also carries a tips/promotions band along the bottom
// (sumatraTips in HomePage.cpp). Every tip points at a Windows-only
// affordance — keyboard shortcuts, the scrollbar, the command palette —
// so it is deliberately not ported.
//
// The header title toggles between "Recently Opened" and "Frequently
// Read". On Windows that choice lives in advanced settings
// (GlobalPrefs::homePageSortByFrequentlyRead), which the port has no
// editor for yet, so the title itself is the control.

private val kSumatraLetterColors = listOf(
    Color(0xFFC44032), Color(0xFFE36B23), Color(0xFF5DA028), Color(0xFF4584BE),
    Color(0xFF7073CF), Color(0xFF7073CF), Color(0xFF4584BE), Color(0xFF5DA028),
    Color(0xFFE36B23), Color(0xFFC44032),
)

@Composable
fun StartPage(
    entries: List<FileHistoryEntry>,
    listView: Boolean,
    sortByFrequentlyRead: Boolean,
    filter: String,
    onFilterChange: (String) -> Unit,
    onSetListView: (Boolean) -> Unit,
    onToggleSort: () -> Unit,
    onOpenDocument: () -> Unit,
    onOpenEntry: (FileHistoryEntry) -> Unit,
    onPin: (FileHistoryEntry) -> Unit,
    onForget: (FileHistoryEntry) -> Unit,
    onShowInFolder: (FileHistoryEntry) -> Unit,
    onShowLibrary: () -> Unit = {},
    titleScale: Float = 1f,
    anyTextFieldFocused: androidx.compose.runtime.MutableState<Boolean>,
) {
    val words = remember(filter) { splitFilterToWords(filter) }
    val shown = remember(entries, words) {
        if (words.isEmpty()) entries else entries.filter { filterMatches(it.displayName, words) }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        StartPageHeader(
            listView = listView,
            sortByFrequentlyRead = sortByFrequentlyRead,
            onSetListView = onSetListView,
            onToggleSort = onToggleSort,
            onShowLibrary = onShowLibrary,
            onOpenDocument = onOpenDocument,
        )
        FilterField(
            filter = filter,
            onFilterChange = onFilterChange,
            modifier = Modifier.padding(top = 10.dp, bottom = 10.dp),
            anyTextFieldFocused = anyTextFieldFocused,
        )
        if (shown.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Text(
                    text = if (entries.isEmpty()) {
                        "No documents yet. Open one and it will show up here."
                    } else {
                        "No document matches “$filter”."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 40.dp),
                )
            }
            return@Column
        }
        if (listView) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(shown, key = { it.path }) { entry ->
                    HomeListRow(
                        entry = entry,
                        titleScale = titleScale,
                        onOpen = { onOpenEntry(entry) },
                        onPin = { onPin(entry) },
                        onForget = { onForget(entry) },
                        onShowInFolder = { onShowInFolder(entry) },
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(shown, key = { it.path }) { entry ->
                    HomeThumbnail(
                        entry = entry,
                        titleScale = titleScale,
                        onOpen = { onOpenEntry(entry) },
                        onPin = { onPin(entry) },
                        onForget = { onForget(entry) },
                        onShowInFolder = { onShowInFolder(entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun StartPageHeader(
    listView: Boolean,
    sortByFrequentlyRead: Boolean,
    onSetListView: (Boolean) -> Unit,
    onToggleSort: () -> Unit,
    onOpenDocument: () -> Unit,
    onShowLibrary: () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ViewModeIcon(
                icon = Icons.Outlined.GridView,
                label = "Show as thumbnails",
                selected = !listView,
                onClick = { onSetListView(false) },
            )
            Spacer(Modifier.width(4.dp))
            ViewModeIcon(
                icon = Icons.Outlined.Reorder,
                label = "Show as list",
                selected = listView,
                onClick = { onSetListView(true) },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (sortByFrequentlyRead) "Frequently Read" else "Recently Opened",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.clickable(onClick = onToggleSort),
            )
            Spacer(Modifier.width(1.dp).weight(1f))
            SumatraWordmark()
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(top = 2.dp)
                .clickable(onClick = onOpenDocument),
        ) {
            Icon(
                imageVector = Icons.Outlined.FileOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "Open a document...",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
            )
        }
        Text(
            text = "Library",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.padding(top = 2.dp).clickable(onClick = onShowLibrary),
        )
        Spacer(
            Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

@Composable
private fun SumatraWordmark() {
    val name = "SumatraPDF"
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = buildAnnotatedString {
                name.forEachIndexed { i, ch ->
                    withStyle(SpanStyle(color = kSumatraLetterColors[i % kSumatraLetterColors.size])) {
                        append(ch)
                    }
                }
            },
            fontSize = 15.sp,
            fontWeight = FontWeight.Black,
            maxLines = 1,
        )
        Text(
            text = " ${BuildConfig.VERSION_NAME}",
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 1.dp),
        )
    }
}

@Composable
private fun ViewModeIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (selected) MaterialTheme.colorScheme.primary
               else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                else Color.Transparent,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun FilterField(
    filter: String,
    onFilterChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    anyTextFieldFocused: androidx.compose.runtime.MutableState<Boolean>,
) {
    var fieldValue by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("")) }
    androidx.compose.runtime.LaunchedEffect(filter) {
        if (fieldValue.text != filter) {
            fieldValue = androidx.compose.ui.text.input.TextFieldValue(filter, androidx.compose.ui.text.TextRange(filter.length))
        }
    }
    val update: (androidx.compose.ui.text.input.TextFieldValue) -> Unit = { v ->
        fieldValue = v
        onFilterChange(v.text)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(3.dp))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (filter.isEmpty()) {
                Text(
                    text = "Filter by name",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BasicTextField(
                value = fieldValue,
                onValueChange = update,
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .textFieldKeyHandler(
                        value = fieldValue,
                        onValueChange = update,
                        anyTextFieldFocused = anyTextFieldFocused,
                    ),
            )
        }
        if (filter.isNotEmpty()) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "Clear filter",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(16.dp)
                    .clickable { onFilterChange("") },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeThumbnail(
    entry: FileHistoryEntry,
    titleScale: Float,
    onOpen: () -> Unit,
    onPin: () -> Unit,
    onForget: () -> Unit,
    onShowInFolder: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(kThumbnailDx.toFloat() / kThumbnailDy.toFloat())
                .clip(RoundedCornerShape(2.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp))
                .combinedClickable(onClick = onOpen, onLongClick = { menuOpen = true }),
        ) {
            ThumbnailImage(entry)
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphButton(
                    icon = Icons.Outlined.PushPin,
                    label = if (entry.isPinned) "Unpin" else "Pin",
                    active = entry.isPinned,
                    onClick = onPin,
                )
                Spacer(Modifier.width(2.dp))
                GlyphButton(
                    icon = Icons.Outlined.Close,
                    label = "Remove from Frequently Read",
                    active = false,
                    onClick = onForget,
                )
            }
            EntryMenu(
                entry = entry,
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                onOpen = onOpen,
                onPin = onPin,
                onForget = onForget,
                onShowInFolder = onShowInFolder,
            )
        }
        Text(
            text = entry.displayName,
            fontSize = 12.sp * titleScale,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeListRow(
    entry: FileHistoryEntry,
    titleScale: Float,
    onOpen: () -> Unit,
    onPin: () -> Unit,
    onForget: () -> Unit,
    onShowInFolder: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .combinedClickable(onClick = onOpen, onLongClick = { menuOpen = true })
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(38.dp)
                    .height(50.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)),
            ) {
                ThumbnailImage(entry)
            }
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.displayName,
                    fontSize = 14.sp * titleScale,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = parentDir(entry.path).ifEmpty { entry.uri ?: "" },
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = fileSizeLabel(entry.path),
                fontSize = 11.sp,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            GlyphButton(
                icon = Icons.Outlined.Close,
                label = "Remove from Frequently Read",
                active = false,
                onClick = onForget,
            )
            Spacer(Modifier.width(4.dp))
            GlyphButton(
                icon = Icons.Outlined.PushPin,
                label = if (entry.isPinned) "Unpin" else "Pin",
                active = entry.isPinned,
                onClick = onPin,
            )
        }
        Spacer(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        EntryMenu(
            entry = entry,
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            onOpen = onOpen,
            onPin = onPin,
            onForget = onForget,
            onShowInFolder = onShowInFolder,
        )
    }
}

// src/Menu.cpp::menuDefContextStart
@Composable
private fun EntryMenu(
    entry: FileHistoryEntry,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onPin: () -> Unit,
    onForget: () -> Unit,
    onShowInFolder: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Open Document") },
            onClick = { onDismiss(); onOpen() },
        )
        DropdownMenuItem(
            text = { Text("Show in folder") },
            onClick = { onDismiss(); onShowInFolder() },
        )
        DropdownMenuItem(
            text = { Text(if (entry.isPinned) "✓  Pin Document" else "Pin Document") },
            onClick = { onDismiss(); onPin() },
        )
        DropdownMenuItem(
            text = { Text("Remove From History") },
            onClick = { onDismiss(); onForget() },
        )
    }
}

@Composable
private fun GlyphButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(
                if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (active) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(13.dp),
        )
    }
}

@Composable
private fun ThumbnailImage(entry: FileHistoryEntry) {
    val bitmap = rememberThumbnail(entry.path)
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = entry.displayName,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Outlined.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                val ext = entry.displayName.substringAfterLast('.', "").uppercase()
                if (ext.isNotEmpty() && ext.length <= 5) {
                    Text(
                        text = ext,
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberThumbnail(path: String): Bitmap? {
    val context = LocalContext.current
    var bitmap by remember(path) { mutableStateOf(Thumbnails.cached(path)) }
    LaunchedEffect(path) {
        if (bitmap == null) {
            bitmap = withContext(Dispatchers.IO) { Thumbnails.get(context, path) }
        }
    }
    return bitmap
}

private fun fileSizeLabel(path: String): String {
    val n = try { java.io.File(path).length() } catch (_: Throwable) { 0L }
    if (n <= 0L) return ""
    return formatFileSize(n)
}

internal fun formatFileSize(n: Long): String = when {
    n >= 1L shl 30 -> String.format("%.1f GB", n / (1L shl 30).toDouble())
    n >= 1L shl 20 -> String.format("%.1f MB", n / (1L shl 20).toDouble())
    n >= 1L shl 10 -> String.format("%.0f KB", n / (1L shl 10).toDouble())
    else -> "$n B"
}
