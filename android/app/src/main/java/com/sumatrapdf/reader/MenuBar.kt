package com.sumatrapdf.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource

// The Win32 SumatraPDF menu bar. On Windows it's a row of text
// labels at the top of the window — File, View, Go To, Zoom,
// Selection, Read Aloud, Favorites, Settings, Help. Tapping a label
// opens a submenu (a `HMENU` in Win32, a `DropdownMenu` here).
//
// We render it as a horizontal row of `TextButton`s in a `Surface`
// styled like a real menu bar. Each button owns one `DropdownMenu`;
// tapping outside any open menu dismisses it (Compose's DropdownMenu
// does this automatically via `onDismissRequest`).
//
// We only render the categories Win32 actually shows: File, View,
// Go To, Zoom, Favorites, Settings, Help. Selection and Read Aloud
// are skipped from the menu bar in our touch UI — selection is
// touch-driven (long-press to select, copy), and Read Aloud is
// surfaced in the toolbar as a button (with a future voice / speed
// panel). This matches the spirit of Win32 (the menu bar is
// optional) without forcing a non-touch-friendly submenu.
@Composable
fun MenuBar(
    enabled: Boolean,
    displayMode: DisplayMode,
    continuous: Boolean,
    zoom: ZoomLevel,
    onAction: (MenuAction) -> Unit,
) {
    var openMenu by remember { mutableStateOf<String?>(null) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .padding(horizontal = 2.dp),
        ) {
            MenuBarButton(
                label = stringResource(R.string.menu_file),
                isOpen = openMenu == "File",
                enabled = true,
                onOpen = { openMenu = "File" },
                onDismiss = { openMenu = null },
            ) { close ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cmd_new_window)) },
                    onClick = { close(); onAction(MenuAction.NewWindow) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cmd_open)) },
                    onClick = { close(); onAction(MenuAction.Open) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.recent_files)) },
                    onClick = { close(); onAction(MenuAction.ShowRecent) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cmd_close)) },
                    onClick = { close(); onAction(MenuAction.Close) },
                    enabled = enabled,
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cmd_show_in_folder)) },
                    onClick = { close(); onAction(MenuAction.ShowInFolder) },
                    enabled = enabled,
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cmd_open_next)) },
                    onClick = { close(); onAction(MenuAction.OpenNext) },
                    enabled = enabled,
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cmd_open_prev)) },
                    onClick = { close(); onAction(MenuAction.OpenPrev) },
                    enabled = enabled,
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cmd_save_as)) },
                    onClick = { close(); onAction(MenuAction.SaveAs) },
                    enabled = enabled,
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cmd_save_annotations)) },
                    onClick = { close(); onAction(MenuAction.SaveAnnotations) },
                    enabled = enabled,
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cmd_rename)) },
                    onClick = { close(); onAction(MenuAction.Rename) },
                    enabled = enabled,
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cmd_delete)) },
                    onClick = { close(); onAction(MenuAction.Delete) },
                    enabled = enabled,
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cmd_print)) },
                    onClick = { close(); onAction(MenuAction.Print) },
                    enabled = enabled,
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cmd_properties)) },
                    onClick = { close(); onAction(MenuAction.Properties) },
                    enabled = enabled,
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cmd_exit)) },
                    onClick = { close(); onAction(MenuAction.Exit) },
                )
            }

            MenuBarButton(
                label = stringResource(R.string.menu_view),
                isOpen = openMenu == "View",
                enabled = enabled,
                onOpen = { openMenu = "View" },
                onDismiss = { openMenu = null },
            ) { close ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cmd_command_palette)) },
                    onClick = { close(); onAction(MenuAction.CommandPalette) },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { CheckedText(stringResource(R.string.cmd_single_page), displayMode == DisplayMode.SinglePage) },
                    onClick = { close(); onAction(MenuAction.SetDisplayMode(DisplayMode.SinglePage)) },
                )
                DropdownMenuItem(
                    text = { CheckedText(stringResource(R.string.cmd_facing), displayMode == DisplayMode.Facing) },
                    onClick = { close(); onAction(MenuAction.SetDisplayMode(DisplayMode.Facing)) },
                )
                DropdownMenuItem(
                    text = { CheckedText(stringResource(R.string.cmd_book_view), displayMode == DisplayMode.BookView) },
                    onClick = { close(); onAction(MenuAction.SetDisplayMode(DisplayMode.BookView)) },
                )
                DropdownMenuItem(
                    text = { CheckedText(stringResource(R.string.cmd_show_pages_continuously), continuous) },
                    onClick = { close(); onAction(MenuAction.ToggleContinuous) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cmd_manga_mode)) },
                    onClick = { close(); onAction(MenuAction.ToggleMangaMode) },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cmd_rotate_left)) },
                    onClick = { close(); onAction(MenuAction.RotateLeft) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cmd_rotate_right)) },
                    onClick = { close(); onAction(MenuAction.RotateRight) },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cmd_presentation)) },
                    onClick = { close(); onAction(MenuAction.Presentation) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cmd_fullscreen)) },
                    onClick = { close(); onAction(MenuAction.Fullscreen) },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cmd_show_bookmarks)) },
                    onClick = { close(); onAction(MenuAction.ShowBookmarks) },
                )
            }

            MenuBarButton(
                label = stringResource(R.string.menu_goto),
                isOpen = openMenu == "GoTo",
                enabled = enabled,
                onOpen = { openMenu = "GoTo" },
                onDismiss = { openMenu = null },
            ) { close ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cmd_next_page)) },
                    onClick = { close(); onAction(MenuAction.NextPage) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cmd_prev_page)) },
                    onClick = { close(); onAction(MenuAction.PrevPage) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cmd_first_page)) },
                    onClick = { close(); onAction(MenuAction.FirstPage) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cmd_last_page)) },
                    onClick = { close(); onAction(MenuAction.LastPage) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cmd_go_to_page)) },
                    onClick = { close(); onAction(MenuAction.GoToPage) },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cmd_back)) },
                    onClick = { close(); onAction(MenuAction.NavigateBack) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cmd_forward)) },
                    onClick = { close(); onAction(MenuAction.NavigateForward) },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.find)) },
                    onClick = { close(); onAction(MenuAction.FindFirst) },
                )
            }

            MenuBarButton(
                label = stringResource(R.string.menu_zoom),
                isOpen = openMenu == "Zoom",
                enabled = enabled,
                onOpen = { openMenu = "Zoom" },
                onDismiss = { openMenu = null },
            ) { close ->
                DropdownMenuItem(
                    text = { CheckedText(stringResource(R.string.cmd_fit_page), zoom == ZoomLevel.FitPage) },
                    onClick = { close(); onAction(MenuAction.FitPage) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cmd_actual_size)) },
                    onClick = { close(); onAction(MenuAction.ActualSize) },
                )
                DropdownMenuItem(
                    text = { CheckedText(stringResource(R.string.cmd_fit_width), zoom == ZoomLevel.FitWidth) },
                    onClick = { close(); onAction(MenuAction.FitWidth) },
                )
                DropdownMenuItem(
                    text = { CheckedText(stringResource(R.string.cmd_fit_height), zoom == ZoomLevel.FitHeight) },
                    onClick = { close(); onAction(MenuAction.FitHeight) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cmd_fit_by_orientation)) },
                    onClick = { close(); onAction(MenuAction.FitByOrientation) },
                )
                DropdownMenuItem(
                    text = { CheckedText(stringResource(R.string.cmd_fit_content), zoom == ZoomLevel.FitContent) },
                    onClick = { close(); onAction(MenuAction.FitContent) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cmd_shrink_to_fit)) },
                    onClick = { close(); onAction(MenuAction.ShrinkToFit) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cmd_custom_zoom)) },
                    onClick = { close(); onAction(MenuAction.CustomZoom) },
                )
            }

            MenuBarButton(
                label = stringResource(R.string.menu_favorites),
                isOpen = openMenu == "Favorites",
                enabled = enabled,
                onOpen = { openMenu = "Favorites" },
                onDismiss = { openMenu = null },
            ) { close ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cmd_add_bookmark)) },
                    onClick = { close(); onAction(MenuAction.AddBookmark) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cmd_show_favorites)) },
                    onClick = { close(); onAction(MenuAction.ListBookmarks) },
                )
            }

            MenuBarButton(
                label = stringResource(R.string.menu_settings),
                isOpen = openMenu == "Settings",
                enabled = true,
                onOpen = { openMenu = "Settings" },
                onDismiss = { openMenu = null },
            ) { close ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cmd_change_language)) },
                    onClick = { close(); onAction(MenuAction.ChangeLanguage) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cmd_options)) },
                    onClick = { close(); onAction(MenuAction.Options) },
                    enabled = enabled,
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cmd_advanced_settings)) },
                    onClick = { close(); onAction(MenuAction.AdvancedSettings) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cmd_advanced_options)) },
                    onClick = { close(); onAction(MenuAction.AdvancedOptions) },
                )
            }

            MenuBarButton(
                label = stringResource(R.string.menu_help),
                isOpen = openMenu == "Help",
                enabled = true,
                onOpen = { openMenu = "Help" },
                onDismiss = { openMenu = null },
            ) { close ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cmd_manual)) },
                    onClick = { close(); onAction(MenuAction.Manual) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cmd_keyboard_shortcuts)) },
                    onClick = { close(); onAction(MenuAction.Manual) }, // same as Manual for now
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cmd_manual_on_website)) },
                    onClick = { close(); onAction(MenuAction.Manual) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cmd_visit_website)) },
                    onClick = { close(); onAction(MenuAction.VisitWebsite) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cmd_check_update)) },
                    onClick = { close(); onAction(MenuAction.CheckUpdate) },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cmd_about)) },
                    onClick = { close(); onAction(MenuAction.About) },
                )
            }
        }
    }
}

// A single top-level menu button. Looks like a regular text label;
// when its submenu is open, the label is highlighted with the
// primary color (matches the Win32 menu bar's "active" look).
@Composable
private fun MenuBarButton(
    label: String,
    isOpen: Boolean,
    enabled: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable (closeMenu: () -> Unit) -> Unit,
) {
    Box {
        TextButton(
            onClick = onOpen,
            enabled = enabled,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            modifier = Modifier.height(28.dp),
        ) {
            Text(
                text = label,
                color = if (isOpen) MaterialTheme.colorScheme.primary
                        else if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                fontWeight = if (isOpen) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 13.sp,
            )
        }
        DropdownMenu(
            expanded = isOpen,
            onDismissRequest = onDismiss,
        ) {
            content(onDismiss)
        }
    }
}

@Composable
private fun CheckedText(text: String, checked: Boolean) {
    Row {
        if (checked) {
            Text("✓ ", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
        } else {
            Text("   ", fontSize = 13.sp)
        }
        Text(text, fontSize = 13.sp)
    }
}
