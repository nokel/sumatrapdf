package com.sumatrapdf.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// The ToC / Bookmarks / Outline sidebar that lives on the left edge of
// the document in the Win32 SumatraPDF. Two tabs (Contents, Bookmarks)
// are shown; for now we only populate Contents, the Bookmarks tab is
// wired into the same Settings used for the Bookmarks menu.
//
// The Contents tree is collapsible: every OutlineNode with children
// has a chevron that flips the node's `id` in / out of `expandedIds`.
// The state is a `Set<Long>` of expanded node ids; `expandAll` adds
// every parent id, `collapseAll` empties the set, and
// `expandToCurrentPage` walks the tree to find the first ancestor
// chain of the current page and expands each one.
//
// The `expandAll` / `collapseAll` / `expandToCurrent` triggers are
// counters (not booleans) so the same action can fire twice in a
// row — each bump is a state change that the LaunchedEffect
// inside picks up.
@Composable
fun ToCSidebar(
    outline: List<OutlineNode>,
    bookmarks: List<Bookmark>,
    currentPage: Int,
    currentPath: String,
    pageCount: Int,
    onJumpToPage: (Int) -> Unit,
    onClose: () -> Unit,
    onRemoveBookmark: (Bookmark) -> Unit,
    expandAllTrigger: Int = 0,
    collapseAllTrigger: Int = 0,
    expandToCurrentTrigger: Int = 0,
    sortBookmarksByName: Boolean = false,
    onToggleBookmarkSort: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(SidebarTab.Contents) }
    // 280dp is two thirds of a narrow phone, which leaves the page an
    // unreadable strip, so the sidebar never takes more than 42% of the
    // row it shares with the page.
    BoxWithConstraints(modifier = modifier.fillMaxHeight()) {
    val sidebarWidth = minOf(280.dp, maxWidth * 0.42f)
    // Expand/collapse state for the Contents tree. The ids are
    // assigned by DocumentEngine.getOutline (per-document, so
    // different documents never collide). The state is held
    // here, not in Settings, because the user's collapse
    // pattern is per-session and a different document has a
    // different tree.
    var expandedIds by remember(outline) { mutableStateOf(setOf<Long>()) }
    // React to triggers from the toolbar / keyboard shortcut /
    // context menu by mutating expandedIds. Each trigger is a
    // counter that bumps when the user fires the action; the
    // LaunchedEffect with the trigger as the key re-fires each
    // time, so the action can be triggered multiple times in a
    // row (expand-all, collapse-all, expand-to-current).
    androidx.compose.runtime.LaunchedEffect(expandAllTrigger) {
        if (expandAllTrigger > 0) expandedIds = collectAllParentIds(outline)
    }
    androidx.compose.runtime.LaunchedEffect(collapseAllTrigger) {
        if (collapseAllTrigger > 0) expandedIds = emptySet()
    }
    androidx.compose.runtime.LaunchedEffect(expandToCurrentTrigger, currentPage) {
        if (expandToCurrentTrigger > 0) {
            expandedIds = expandedIds + ancestorIdsOfFirstAtPage(outline, currentPage)
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 0.dp,
        modifier = Modifier
            .fillMaxHeight()
            .width(sidebarWidth),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TabButton(
                    text = "Contents",
                    selected = tab == SidebarTab.Contents,
                    onClick = { tab = SidebarTab.Contents },
                )
                Spacer(Modifier.width(4.dp))
                TabButton(
                    text = "Bookmarks",
                    selected = tab == SidebarTab.Bookmarks,
                    onClick = { tab = SidebarTab.Bookmarks },
                )
                if (tab == SidebarTab.Contents && outline.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = {
                            // Win32 CmdExpandAll: every parent
                            // node is open. We collect the ids
                            // with a tree walk.
                            expandedIds = collectAllParentIds(outline)
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Outlined.KeyboardArrowDown,
                            contentDescription = "Expand all",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(
                        onClick = { expandedIds = emptySet() },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Outlined.KeyboardArrowRight,
                            contentDescription = "Collapse all",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                if (tab == SidebarTab.Bookmarks) {
                    // The sort toggle is a single button: tap
                    // it to flip between "by page" and "by
                    // name". The label changes to match the
                    // current mode.
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = onToggleBookmarkSort,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Text(
                            text = if (sortBookmarksByName) "A→Z" else "#",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close sidebar")
                }
            }
            HorizontalDivider()
            when (tab) {
                SidebarTab.Contents -> ContentsTab(
                    outline = outline,
                    currentPage = currentPage,
                    pageCount = pageCount,
                    expandedIds = expandedIds,
                    onToggle = { id ->
                        expandedIds = if (id in expandedIds) {
                            expandedIds - id
                        } else {
                            expandedIds + id
                        }
                    },
                    onExpandToCurrent = {
                        // Win32 CmdExpandToCurrentPage: walk
                        // the tree, find every node whose page
                        // is >= the current page, and add its
                        // ancestor chain to the expanded set.
                        expandedIds = expandedIds + ancestorIdsOfFirstAtPage(outline, currentPage)
                    },
                    onJumpToPage = onJumpToPage,
                )
                SidebarTab.Bookmarks -> BookmarksTab(
                    bookmarks = bookmarks.filter { it.path == currentPath },
                    currentPage = currentPage,
                    sortByName = sortBookmarksByName,
                    onJumpToPage = onJumpToPage,
                    onRemoveBookmark = onRemoveBookmark,
                )
            }
        }
    }
    }
}

// Walk the outline tree and return every node id that has at
// least one child. Used by "Expand all" to know which rows are
// collapsable.
private fun collectAllParentIds(outline: List<OutlineNode>): Set<Long> {
    val out = mutableSetOf<Long>()
    fun walk(n: OutlineNode) {
        if (n.children.isNotEmpty()) {
            out.add(n.id)
            n.children.forEach { walk(it) }
        }
    }
    outline.forEach { walk(it) }
    return out
}

// Walk the tree and return the ancestor chain of the first
// node whose page is >= the target. If no such node exists,
// return an empty set (the user is past the last ToC entry).
private fun ancestorIdsOfFirstAtPage(outline: List<OutlineNode>, target: Int): Set<Long> {
    val chain = mutableListOf<Long>()
    fun walk(n: OutlineNode): Boolean {
        if (n.page >= target) return true
        for (c in n.children) {
            chain.add(n.id)
            if (walk(c)) return true
            chain.removeAt(chain.lastIndex)
        }
        return false
    }
    for (root in outline) {
        chain.clear()
        if (walk(root)) return chain.toSet()
    }
    return emptySet()
}

private enum class SidebarTab { Contents, Bookmarks }

@Composable
private fun TabButton(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.surface
             else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onSurface
             else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = fg,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun ContentsTab(
    outline: List<OutlineNode>,
    currentPage: Int,
    pageCount: Int,
    expandedIds: Set<Long>,
    onToggle: (Long) -> Unit,
    onExpandToCurrent: () -> Unit,
    onJumpToPage: (Int) -> Unit,
) {
    if (outline.isEmpty()) {
        EmptyHint("This document has no table of contents.")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Text(
                "Page ${currentPage + 1} of $pageCount",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            HorizontalDivider()
        }
        // Flatten the tree, but only emit a node if all of its
        // ancestors are expanded. The "all expanded" default
        // (expandedIds empty) used to render every node; the
        // new default is "every parent collapsed" which still
        // shows the top level only. Users tap a chevron to
        // expand.
        for (root in outline) {
            flattenOutline(root, 0, expandedIds, currentPage).forEach { (depth, node) ->
                item(key = "toc-${node.id}") {
                    OutlineRow(
                        node = node,
                        depth = depth,
                        currentPage = currentPage,
                        isExpanded = node.id in expandedIds,
                        onToggle = { onToggle(node.id) },
                        onClick = { onJumpToPage(node.page) },
                    )
                }
            }
        }
    }
}

// Walk the tree, emitting only nodes whose ancestor chain is
// fully in `expandedIds`. The top level is always emitted
// regardless of state.
private fun flattenOutline(
    node: OutlineNode,
    depth: Int,
    expandedIds: Set<Long>,
    currentPage: Int,
): List<Pair<Int, OutlineNode>> {
    val out = mutableListOf<Pair<Int, OutlineNode>>()
    out.add(depth to node)
    if (node.id in expandedIds) {
        node.children.forEach { c ->
            out.addAll(flattenOutline(c, depth + 1, expandedIds, currentPage))
        }
    }
    return out
}

@Composable
private fun OutlineRow(
    node: OutlineNode,
    depth: Int,
    currentPage: Int,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
) {
    val isCurrent = node.page == currentPage
    val color = if (isCurrent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface
    val hasChildren = node.children.isNotEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (isCurrent) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .padding(start = (8 + depth * 12).dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasChildren) {
            // The chevron is its own click target so tapping
            // it does NOT also fire the row's onClick (the
            // row's onClick is "jump to this page"). This is
            // how the Win32 tree-view works: the + / - box
            // toggles, the rest of the row navigates.
            IconButton(
                onClick = onToggle,
                modifier = Modifier.size(20.dp),
            ) {
                Icon(
                    imageVector = if (isExpanded) Icons.Outlined.KeyboardArrowDown
                                  else Icons.Outlined.KeyboardArrowRight,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = color,
                    modifier = Modifier.size(16.dp),
                )
            }
        } else {
            // A leaf row reserves the same horizontal space
            // for alignment with parent rows. The MenuBook
            // icon used to live here on Win32; the Android
            // port uses a fixed spacer so the leaf title
            // aligns with its siblings' titles.
            Spacer(Modifier.width(20.dp))
        }
        Text(
            text = node.title,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${node.page + 1}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BookmarksTab(
    bookmarks: List<Bookmark>,
    currentPage: Int,
    sortByName: Boolean,
    onJumpToPage: (Int) -> Unit,
    onRemoveBookmark: (Bookmark) -> Unit,
) {
    if (bookmarks.isEmpty()) {
        EmptyHint("No bookmarks in this document.\nUse Bookmarks ▸ Add bookmark to create one.")
        return
    }
    // Win32's CmdToggleFavoritesSort flips between "by page"
    // (the default, and the order the user added them in) and
    // "by name" (alphabetical by display name). The same list
    // is shown in both modes; only the order changes.
    val ordered = if (sortByName) {
        bookmarks.sortedBy { it.displayName.lowercase() }
    } else {
        bookmarks.sortedBy { it.page }
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items = ordered, key = { "${it.path}::${it.page}" }) { b ->
            val isCurrent = b.page == currentPage
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onJumpToPage(b.page) }
                    .background(
                        if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                        else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = b.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text(
                        text = "Page ${b.page + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { onRemoveBookmark(b) }) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "Remove bookmark",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopStart) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
