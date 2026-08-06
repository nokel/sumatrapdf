package com.sumatrapdf.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Mirrors the Win32 SumatraPDF tab bar (see `src/WindowTabs.cpp` and
// `MainWindow::Tabs`).
//
// Layout, top to bottom:
//
//   [≡] [tab1.x] [tab2.x] [tab3.x]   <-- this row
//   <Win32 toolbar>                  <-- SumatraToolbar
//   <page content>
//
// The hamburger ≡ on the LEFT of the tab bar is the Win32 menu bar's
// stand-in (see src/Menu.cpp::menuDefMenubar). Tapping it opens a
// cascading popup with File / View / Go To / Zoom / Selection / Read
// Aloud / Favorites / Settings / Help / Debug, with each section's
// items popping out to the right. This is how a new document is
// opened: hamburger ▸ File ▸ Open… or hamburger ▸ File ▸ Recent
// Files. There is intentionally no "+" button on the tab bar — the
// hamburger is the only way to add a tab.
//
// Each tab's × closes that document. Tapping the body of a tab makes
// it active. The bar scrolls horizontally when the tabs overflow.
@Composable
fun TabsRow(
    tabs: List<Tab>,
    activeIndex: Int,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onMenuAction: (MenuAction) -> Unit,
    displayMode: DisplayMode,
    continuous: Boolean,
    zoom: ZoomLevel,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Hamburger ≡ on the very left of the tab bar.
            HamburgerMenu(
                displayMode = displayMode,
                continuous = continuous,
                zoom = zoom,
                onMenuAction = onMenuAction,
            )
            Spacer(Modifier.width(4.dp))
            tabs.forEachIndexed { i, tab ->
                TabItem(
                    title = tab.title,
                    isActive = i == activeIndex,
                    isHome = tab.isHome,
                    onSelect = { onSelect(i) },
                    onClose = { onClose(i) },
                )
            }
        }
    }
}

@Composable
private fun TabItem(
    title: String,
    isActive: Boolean,
    isHome: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    val bg = if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
             else Color.Transparent
    val border = if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                 else MaterialTheme.colorScheme.outlineVariant
    val titleColor = if (isActive) MaterialTheme.colorScheme.primary
                     else MaterialTheme.colorScheme.onSurfaceVariant
    val closeColor = if (isActive) MaterialTheme.colorScheme.primary
                     else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .padding(horizontal = 2.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable(onClick = onSelect)
            .padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isHome) {
                Icon(
                    imageVector = Icons.Outlined.Home,
                    contentDescription = null,
                    tint = titleColor,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = title,
                color = titleColor,
                fontSize = 13.sp,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(if (isHome) 44.dp else 140.dp),
            )
            Spacer(Modifier.width(2.dp))
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(20.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Close tab",
                    tint = closeColor,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

// The per-tab UI state. In the Win32 app this mirrors the fields of
// `FileState` in src/Settings.h that live on the per-tab WindowTab —
// page, scroll, zoom, rotation, display mode, continuous flag.
data class Tab(
    val title: String,
    val path: String,
    val docHandle: Int,
    // The Home tab (src/Tabs.cpp: WindowTab::Type::About, inserted at
    // index 0 with the first document tab unless GlobalPrefs::noHomeTab).
    // It has no document — docHandle is 0 — and shows the start page.
    val isHome: Boolean = false,
    val page: Int = 0,
    val displayMode: DisplayMode = DisplayMode.SinglePage,
    val continuous: Boolean = false,
    val zoom: ZoomLevel = ZoomLevel.FitWidth,
    val customZoom: Float = 1f,
    val rotation: Int = 0,
    val showToc: Boolean = false,
)
