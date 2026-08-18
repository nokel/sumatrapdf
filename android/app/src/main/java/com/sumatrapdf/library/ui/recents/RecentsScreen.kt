package com.sumatrapdf.library.ui.recents

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sumatrapdf.library.data.RecentsStore
import com.sumatrapdf.library.engine.DocumentSpec
import com.sumatrapdf.library.ui.reader.TabsState
import com.sumatrapdf.library.ui.theme.SumTypography
import java.io.File

// RecentsScreen is the bottom-nav Recents tab. It mirrors the Recent
// documents surface in the Win32 File menu: a list of recently opened
// files, ordered by most-recently opened first. Tapping a row opens the
// document in a new tab (or focuses an existing tab with the same
// origin) and jumps to the saved page.
@Composable
fun RecentsScreen(tabs: TabsState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { RecentsStore.get(context) }
    var refreshKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { /* keep recomposition reactive */ }
    val list = remember(refreshKey) { store.list() }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.History,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text("Recent documents", style = SumTypography.titleLarge, modifier = Modifier.weight(1f))
                if (list.isNotEmpty()) {
                    IconButton(onClick = {
                        store.clear()
                        refreshKey++
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Clear list")
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
        if (list.isEmpty()) {
            EmptyRecents()
        } else {
            LazyColumn(
                contentPadding = PaddingValues(vertical = 8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(list, key = { it.origin }) { rec ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { open(tabs, store, rec.origin, rec.page) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = (rec.page + 1).toString(),
                                style = SumTypography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = rec.name.ifBlank { rec.origin.substringAfterLast('/') },
                                style = SumTypography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = if (rec.pages > 0) "page ${rec.page + 1} of ${rec.pages}" else rec.origin,
                                style = SumTypography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
                }
            }
        }
    }
}

@Composable
private fun EmptyRecents() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.History,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text("No recent documents", style = SumTypography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Documents opened from the library or from the system file picker will show up here.",
            style = SumTypography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun open(tabs: TabsState, store: RecentsStore, origin: String, page: Int) {
    if (origin.startsWith("content://")) {
        val uri = android.net.Uri.parse(origin)
        val spec = DocumentSpec.fromUri(tabs.context, uri) ?: return
        tabs.open(spec)
        tabs.goTo(spec.id, page)
    } else {
        val file = File(origin)
        if (!file.exists()) return
        val spec = DocumentSpec.fromFile(origin)
        tabs.open(spec)
        tabs.goTo(spec.id, page)
    }
}
