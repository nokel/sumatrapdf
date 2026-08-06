package com.sumatrapdf.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.RotateLeft
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Toc
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

// Mirrors the Win32 SumatraPDF toolbar order in `src/Toolbar.cpp::gToolbarButtons`:
//
//   Open | Print | [page-info text box] | Prev | Next | (sep) | Back | Forward |
//   (sep) | Read Aloud | (sep) | Fit Width + Continuous | Fit Single Page |
//   Rotate Left | Rotate Right | Zoom Out | Zoom In | (sep) | Find
//
// The hamburger ≡ button is NOT in this row — it lives at the LEFT of
// the tab bar (see TabsRow.kt) because that's where the Win32 version
// puts the menu bar. See `src/WindowTabs.cpp` and the user-supplied
// screenshot.
//
// The two layout buttons are CHECKABLE — the same icons that appear
// "pressed" in the Win32 toolbar when CmdZoomFitWidthAndContinuous /
// CmdZoomFitPageAndSinglePage match the current state (see
// `SetToolbarButtonCheckedState` in src/Toolbar.cpp). On touch the
// highlight is a tonal background tint.
//
// The page-info text box is tappable and opens the "Go to page…"
// dialog, like in Windows.
@Composable
fun SumatraToolbar(
    page: Int,
    pageCount: Int,
    enabled: Boolean,
    // Display state, so the layout buttons can show their checked
    // state the same way the Win32 toolbar does.
    displayMode: DisplayMode,
    continuous: Boolean,
    zoom: ZoomLevel,
    onOpen: () -> Unit,
    onPrint: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateForward: () -> Unit,
    onReadAloud: () -> Unit,
    onFitWidthContinuous: () -> Unit,
    onFitSinglePage: () -> Unit,
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    onZoomOut: () -> Unit,
    onZoomIn: () -> Unit,
    onFind: () -> Unit,
    onPageStatus: () -> Unit,
    readAloudActive: Boolean = false,
    // Win32's zoom button pops a quick-zoom list (6400% / 3200% /
    // 1600% / 800% / 400% / 200% / 100% / 50%) on right-click. On
    // touch that gesture is a long-press. (PORTING-STATUS §1.4.)
    onZoomLongPress: (() -> Unit)? = null,
) {
    // Win32: a button is "checked" when its (mode, zoom) preset matches
    // the current (mode, zoom) state. See ChangeZoomLevel in
    // src/SumatraPDF.cpp.
    val fitWidthContinuousActive =
        displayMode == DisplayMode.SinglePage && continuous && zoom == ZoomLevel.FitWidth
    val fitSinglePageActive =
        displayMode == DisplayMode.SinglePage && !continuous && zoom == ZoomLevel.FitPage

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolbarButton(
                icon = Icons.Outlined.FileOpen,
                description = stringResource(R.string.open_file),
                onClick = onOpen,
                enabled = true,
            )
            ToolbarButton(
                icon = Icons.Outlined.Print,
                description = stringResource(R.string.print),
                onClick = onPrint,
                enabled = enabled,
            )
            PageStatusItem(page, pageCount, enabled, onPageStatus)
            ToolbarButton(
                icon = Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                description = stringResource(R.string.previous_page),
                onClick = onPrev,
                enabled = enabled,
            )
            ToolbarButton(
                icon = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                description = stringResource(R.string.next_page),
                onClick = onNext,
                enabled = enabled,
            )
            ToolbarDivider()
            ToolbarButton(
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                description = stringResource(R.string.navigate_back),
                onClick = onNavigateBack,
                enabled = enabled,
            )
            ToolbarButton(
                icon = Icons.AutoMirrored.Outlined.ArrowForward,
                description = stringResource(R.string.navigate_forward),
                onClick = onNavigateForward,
                enabled = enabled,
            )
            ToolbarDivider()
            ToolbarCheckableButton(
                icon = Icons.Outlined.VolumeUp,
                description = stringResource(R.string.read_aloud),
                onClick = onReadAloud,
                enabled = enabled,
                checked = readAloudActive,
            )
            ToolbarDivider()
            ToolbarCheckableButton(
                icon = Icons.Outlined.ViewAgenda,
                description = stringResource(R.string.fit_width_continuous),
                onClick = onFitWidthContinuous,
                enabled = enabled,
                checked = fitWidthContinuousActive,
            )
            ToolbarCheckableButton(
                icon = Icons.Outlined.CropSquare,
                description = stringResource(R.string.fit_single_page),
                onClick = onFitSinglePage,
                enabled = enabled,
                checked = fitSinglePageActive,
            )
            ToolbarButton(
                icon = Icons.Outlined.RotateLeft,
                description = stringResource(R.string.rotate_left),
                onClick = onRotateLeft,
                enabled = enabled,
            )
            ToolbarButton(
                icon = Icons.Outlined.RotateRight,
                description = stringResource(R.string.rotate_right),
                onClick = onRotateRight,
                enabled = enabled,
            )
            ToolbarButton(
                icon = Icons.Outlined.ZoomOut,
                description = stringResource(R.string.zoom_out),
                onClick = onZoomOut,
                enabled = enabled,
                onLongClick = onZoomLongPress,
            )
            ToolbarButton(
                icon = Icons.Outlined.ZoomIn,
                description = stringResource(R.string.zoom_in),
                onClick = onZoomIn,
                enabled = enabled,
                onLongClick = onZoomLongPress,
            )
            ToolbarDivider()
            ToolbarButton(
                icon = Icons.Outlined.Search,
                description = stringResource(R.string.find),
                onClick = onFind,
                enabled = enabled,
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ToolbarButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean,
    onLongClick: (() -> Unit)? = null,
) {
    // The regular `IconButton` doesn't expose a long-press
    // callback. When the caller wants one, swap in
    // `combinedClickable` so a long-press can also dispatch.
    // When there is no long-press handler, fall back to the
    // standard `IconButton` (which is what every other toolbar
    // button uses). Mirrors the right-click-on-zoom-button
    // behavior in Win32 SumatraPDF's toolbar (`menuDefZoomShort`).
    if (onLongClick != null) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                    enabled = enabled,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                modifier = Modifier.size(20.dp),
                tint = if (enabled)
                    MaterialTheme.colorScheme.onSurfaceVariant
                else
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
        }
    } else {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                modifier = Modifier.size(20.dp),
                tint = if (enabled)
                    MaterialTheme.colorScheme.onSurfaceVariant
                else
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
        }
    }
}

@Composable
private fun ToolbarCheckableButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean,
    checked: Boolean,
) {
    val bg = if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
             else Color.Transparent
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                modifier = Modifier.size(20.dp),
                tint = if (enabled)
                    if (checked) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                else
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
        }
    }
}

@Composable
private fun ToolbarDivider() {
    Box(
        modifier = Modifier
            .height(20.dp)
            .width(1.dp)
            .background(MaterialTheme.colorScheme.outline),
    )
    Spacer(Modifier.width(2.dp))
}

@Composable
private fun PageStatusItem(
    page: Int,
    pageCount: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    // The page-info text box in the Win32 SumatraPDF toolbar is the small
    // label that shows "3 / 847" right after the Print button; tapping it
    // opens the "Go to page…" dialog. We render it as a flat, thin-bordered
    // label inside a clickable area so the visual matches.
    val bg = if (enabled) MaterialTheme.colorScheme.surface
             else MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = Modifier
            .padding(horizontal = 6.dp)
            .height(24.dp)
            .widthIn(min = 40.dp)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (pageCount > 0) "$page / $pageCount" else "",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
