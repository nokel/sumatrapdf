package com.sumatrapdf.library.ui.favorites

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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.PushPin
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
import com.sumatrapdf.library.data.FavoritesStore
import com.sumatrapdf.library.engine.DocumentSpec
import com.sumatrapdf.library.ui.reader.TabsState
import com.sumatrapdf.library.ui.theme.SumTypography
import java.io.File

// FavoritesScreen is the bottom-nav Favorites tab. It shows every pinned
// page in one list, regardless of which document it came from. Tapping
// opens that document in a new tab (or focuses it if already open) and
// jumps to the saved page.
@Composable
fun FavoritesScreen(tabs: TabsState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { FavoritesStore.get(context) }
    var refreshKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { /* keep refreshKey reactive */ }
    val favorites = remember(refreshKey) { store.list() }

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
                    imageVector = Icons.Outlined.PushPin,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text("Favorites", style = SumTypography.titleLarge, modifier = Modifier.weight(1f))
                if (favorites.isNotEmpty()) {
                    Text(
                        text = "${favorites.size}",
                        style = SumTypography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
        if (favorites.isEmpty()) {
            EmptyFavorites()
        } else {
            LazyColumn(
                contentPadding = PaddingValues(vertical = 8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(favorites, key = { it.id }) { fav ->
                    FavoriteRow(
                        title = fav.label,
                        docName = fav.name,
                        page = fav.page,
                        onClick = {
                            openAt(tabs, fav.origin, fav.name, fav.page)
                        },
                        onRemove = {
                            store.remove(fav.id)
                            refreshKey++
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FavoriteRow(
    title: String,
    docName: String,
    page: Int,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
                text = (page + 1).toString(),
                style = SumTypography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = SumTypography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = docName,
                style = SumTypography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "Remove favorite",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
}

@Composable
private fun EmptyFavorites() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.Bookmark,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text("No favorites yet", style = SumTypography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Open a document and pin the current page to add a favorite. Favorites show up here regardless of which document they came from.",
            style = SumTypography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// Re-open a document from its origin (file path or content URI). If the
// file path no longer exists, we delete the favorite — silent failure
// is the wrong answer here, the user thinks they have a pinned page
// and they don't.
private fun openAt(tabs: TabsState, origin: String, name: String, page: Int) {
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
