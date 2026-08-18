package com.sumatrapdf.library.ui.library

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sumatrapdf.library.Book
import com.sumatrapdf.library.Covers
import com.sumatrapdf.library.Library
import com.sumatrapdf.library.Partitions
import com.sumatrapdf.library.Row
import com.sumatrapdf.library.SortOrder
import com.sumatrapdf.library.engine.DocumentSpec
import com.sumatrapdf.library.ui.reader.TabsState
import com.sumatrapdf.library.ui.theme.SumTypography
import kotlinx.coroutines.delay

// LibraryScreen renders the bookshelf surface that lives behind the
// bottom-nav "Library" tab. The actual scanner is still the existing
// Library singleton (Kotlin object) — that has the volumes/series/
// partition logic this fork is built around. Here we just glue it
// to Compose and re-paint it in the new Win32-style theme.
@Composable
fun LibraryScreen(
    tabs: TabsState,
    onOpen: (DocumentSpec) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var needsStorage by remember { mutableStateOf(checkNeedsStorage(context)) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        // Initialize the existing scanner; it lives in this package and
        // already owns storage permissions + the partitition / series
        // logic this fork added. We just observe it.
        Library.attach(context)
        if (!needsStorage) Library.start(context)
        Library.onChanged = {
            // Re-tick the state; a re-read of Library.books is enough
            // to force recomposition since visibleBooks() returns
            // from a snapshot of the books list.
            refreshKey++
        }
    }

    // Poll the scanner every second while it's running, so the cover
    // wall fills in as covers get rendered.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            if (Library.scanning) refreshKey++
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (Library.onChanged != null) {
                // Keep the callback alive across navigation; the
                // singleton outlives this composable.
            }
        }
    }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LibraryTopBar(
            onRescan = {
                if (needsStorage) {
                    askForStorage(context)
                } else {
                    if (Library.scanning) Library.stopScan() else Library.rescan(context)
                }
            },
            onGrantAccess = { askForStorage(context) },
        )
        SortRow()
        HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)

        if (needsStorage) {
            StorageNeededView(onGrantAccess = { askForStorage(context) })
        } else {
            // Touch refreshKey so the Composable re-evaluates when the
            // scanner reports a change.
            @Suppress("UNUSED_EXPRESSION") refreshKey
            val books = remember(refreshKey) { Library.visibleBooks() }
            val rows = remember(refreshKey) { Library.rows }
            if (books.isEmpty() && !Library.scanning) {
                EmptyLibraryView()
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 116.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(books, key = { it.id }) { book ->
                        BookTile(book = book, onOpen = { onOpen(DocumentSpec.fromFile(book.path)) })
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryTopBar(onRescan: () -> Unit, onGrantAccess: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "SumatraPDF",
                style = SumTypography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            if (Library.scanning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                if (Library.scanTotal > 0) {
                    Text(
                        text = "${Library.scanDone}/${Library.scanTotal}",
                        style = SumTypography.bodySmall,
                    )
                } else {
                    Text(text = "Scanning…", style = SumTypography.bodySmall)
                }
                Spacer(Modifier.width(8.dp))
            }
            IconButton(onClick = onRescan) {
                Icon(Icons.Filled.Refresh, contentDescription = "Rescan")
            }
        }
    }
}

@Composable
private fun SortRow() {
    val current = Library.sortOrder
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Sort",
            style = SumTypography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SortOrder.entries.forEach { order ->
            AssistChip(
                onClick = { Library.setSortOrder(order) },
                label = { Text(order.label) },
                colors = if (order == current)
                    AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) else AssistChipDefaults.assistChipColors(),
            )
        }
    }
}

@Composable
private fun BookTile(book: Book, onOpen: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            val cover = Covers.cached(book.id)
            if (cover != null) {
                Image(
                    bitmap = cover.asImageBitmap(),
                    contentDescription = book.title,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    book.title,
                    textAlign = TextAlign.Center,
                    style = SumTypography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(8.dp),
                )
            }
            IconButton(
                onClick = onOpen,
                modifier = Modifier.align(Alignment.BottomEnd).size(36.dp),
            ) {
                Icon(Icons.Filled.MenuBook, contentDescription = "Open",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = book.title,
            style = SumTypography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val sub = buildString {
            if (book.volumes.isNotEmpty()) append("#" + book.volumes.joinToString(", "))
            book.author?.let { if (isNotEmpty()) append(" · "); append(it) }
            if (isEmpty() && book.pages > 0) append("${book.pages}p")
        }
        if (sub.isNotEmpty()) {
            Text(
                text = sub,
                style = SumTypography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EmptyLibraryView() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.Storage,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "No books found",
            style = SumTypography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Put PDF, EPUB, XPS, FB2, CBZ, MOBI or AZW3 files on the phone. SumatraPDF looks for them in Documents, Download, and any folder named books, ebooks, library, calibre library, manga or comics at the root of each storage volume.",
            style = SumTypography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Tap the refresh button after copying files in.",
            style = SumTypography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StorageNeededView(onGrantAccess: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.Storage,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "All files access",
            style = SumTypography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "The library walks your storage the same way the Windows scanner walks fixed drives. The scoped document picker cannot enumerate a book collection, so we need the legacy permission.",
            style = SumTypography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        AssistChip(
            onClick = onGrantAccess,
            label = { Text("Grant access") },
            leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
        )
    }
}

private fun checkNeedsStorage(context: android.content.Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        return !Environment.isExternalStorageManager()
    }
    return androidx.core.content.ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_EXTERNAL_STORAGE,
    ) != PackageManager.PERMISSION_GRANTED
}

private fun askForStorage(context: android.content.Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + context.packageName),
            )
            context.startActivity(intent)
        } catch (_: Exception) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    } else {
        // The Compose tree does not own the runtime permission flow;
        // delegate to the activity, which will see the request callback.
        if (context is android.app.Activity) {
            context.requestPermissions(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1,
            )
        }
    }
}
