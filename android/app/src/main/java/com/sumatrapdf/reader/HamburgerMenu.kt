package com.sumatrapdf.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Menu
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup

// The hamburger ≡ button and the cascading menu it opens.
//
// Direct port of the Win32 menu bar from `src/Menu.cpp::menuDefMenubar`:
//
//   File | View | Go To | Zoom | Selection | Read Aloud | Favorites |
//   Settings | Help | Debug
//
// The Win32 model is: a top-level menu bar, each item has a submenu,
// and clicking a submenu item with a submenu pops ANOTHER menu to the
// right, aligned to the y of the clicked item. The whole thing is a
// chain of popups. Here we have two popups only (sections, and one
// submenu) because no submenu has a submenu of its own.
//
//   Popup #1 (section list): anchored to the hamburger, offset
//   (0, buttonHeight) so it appears just below the hamburger.
//
//   Popup #2 (submenu): anchored to the same hamburger, offset
//   (sectionListWidth, buttonHeight + sectionIndex * rowHeight). The
//   submenu's top edge is aligned with the y position of the tapped
//   section, exactly like a Win32 cascading menu.
//
// Both popups stay open simultaneously; tapping outside either closes
// both.
@Composable
fun HamburgerMenu(
    displayMode: DisplayMode,
    continuous: Boolean,
    zoom: ZoomLevel,
    onMenuAction: (MenuAction) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var openSection by remember { mutableStateOf<MenuSection?>(null) }

    // Dimensions measured from the rendered menu, in pixels:
    var buttonHeightPx by remember { mutableStateOf(0) }
    var sectionListWidthPx by remember { mutableStateOf(0) }
    var sectionRowHeightPx by remember { mutableStateOf(0) }

    val density = LocalDensity.current

    androidx.activity.compose.BackHandler(enabled = open) {
        if (openSection != null) openSection = null else open = false
    }

    Box {
        IconButton(
            onClick = {
                open = !open
                if (!open) openSection = null
            },
            modifier = Modifier.onSizeChanged { buttonHeightPx = it.height },
            enabled = true,
        ) {
            Icon(
                imageVector = Icons.Outlined.Menu,
                contentDescription = stringResource(R.string.menu),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp),
            )
        }

        // === Popup #1: section list ===
        // Drops down from the hamburger. Each section is one row;
        // tapping a section sets openSection, which makes popup #2
        // appear.
        if (open) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, buttonHeightPx),
                onDismissRequest = {
                    open = false
                    openSection = null
                },
            ) {
                MenuSurface {
                    Column(
                        modifier = Modifier
                            .widthIn(min = 180.dp, max = 240.dp)
                            .heightIn(max = 480.dp)
                            .verticalScroll(rememberScrollState())
                            .onSizeChanged { sectionListWidthPx = it.width },
                    ) {
                        MenuSection.values().forEachIndexed { index, section ->
                            SectionRow(
                                section = section,
                                isHighlighted = openSection == section,
                                onMeasureRowHeight = if (index == 0) {
                                    { h -> sectionRowHeightPx = h }
                                } else null,
                                onTap = { openSection = section },
                            )
                        }
                    }
                }
            }
        }

        // === Popup #2: submenu ===
        // Appears to the right of the section list, with its top
        // edge aligned to the tapped section. The y offset is:
        //   buttonHeight (skip the hamburger) +
        //   sectionIndex * rowHeight (skip the rows above the tapped one)
        // We only render once we have measured the row height
        // (otherwise the first tap on File would put the submenu at
        // y=0, which is wrong).
        if (openSection != null && sectionRowHeightPx > 0 && sectionListWidthPx > 0) {
            val section = openSection!!
            val yOffset = buttonHeightPx + section.ordinal * sectionRowHeightPx
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(
                    x = sectionListWidthPx,
                    y = yOffset,
                ),
                onDismissRequest = { openSection = null },
            ) {
                MenuSurface {
                    Column(
                        modifier = Modifier.widthIn(max = 260.dp),
                    ) {
                        SectionItems(
                            section = section,
                            displayMode = displayMode,
                            continuous = continuous,
                            zoom = zoom,
                            onPick = { action ->
                                open = false
                                openSection = null
                                onMenuAction(action)
                            },
                        )
                    }
                }
            }
        }
    }
}

// Common surface wrapper for both popups: white background, drop
// shadow, thin border, rounded corners. Matches the Win32 menu
// appearance.
@Composable
private fun MenuSurface(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
    ) {
        content()
    }
}

// One row in the section list. The first row's height is reported
// back via `onMeasureRowHeight` so popup #2 can compute the y offset
// for each section.
@Composable
private fun SectionRow(
    section: MenuSection,
    isHighlighted: Boolean,
    onTap: () -> Unit,
    onMeasureRowHeight: ((Int) -> Unit)?,
) {
    val bg = if (isHighlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
             else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .let {
                if (onMeasureRowHeight != null) {
                    it.onSizeChanged { size -> onMeasureRowHeight(size.height) }
                } else {
                    it
                }
            }
            .clickable { onTap() }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = sectionLabel(section),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        Text(
            "›",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun sectionLabel(section: MenuSection): String = when (section) {
    MenuSection.File -> stringResource(R.string.menu_file)
    MenuSection.View -> stringResource(R.string.menu_view)
    MenuSection.GoTo -> stringResource(R.string.menu_goto)
    MenuSection.Zoom -> stringResource(R.string.menu_zoom)
    MenuSection.Selection -> stringResource(R.string.menu_selection)
    MenuSection.ReadAloud -> stringResource(R.string.menu_read_aloud)
    MenuSection.Favorites -> stringResource(R.string.menu_favorites)
    MenuSection.Settings -> stringResource(R.string.menu_settings)
    MenuSection.Help -> stringResource(R.string.menu_help)
    MenuSection.Debug -> stringResource(R.string.menu_debug)
}

// Renders the items for a section. Mirrors the contents of the
// corresponding `menuDef*` in src/Menu.cpp.
@Composable
private fun SectionItems(
    section: MenuSection,
    displayMode: DisplayMode,
    continuous: Boolean,
    zoom: ZoomLevel,
    onPick: (MenuAction) -> Unit,
) {
    when (section) {
        MenuSection.File -> FileItems(onPick)
        MenuSection.View -> ViewItems(displayMode, continuous, onPick)
        MenuSection.GoTo -> GoToItems(onPick)
        MenuSection.Zoom -> ZoomItems(zoom, onPick)
        MenuSection.Selection -> SelectionItems(onPick)
        MenuSection.ReadAloud -> ReadAloudItems(onPick)
        MenuSection.Favorites -> FavoritesItems(onPick)
        MenuSection.Settings -> SettingsItems(onPick)
        MenuSection.Help -> HelpItems(onPick)
        MenuSection.Debug -> DebugItems(onPick)
    }
}

@Composable
private fun LeafRow(
    text: String,
    action: MenuAction,
    checked: Boolean = false,
    shortcut: String? = null,
    onPick: (MenuAction) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPick(action) }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (checked) {
            Text(
                "✓",
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(16.dp),
            )
        } else {
            Spacer(Modifier.width(16.dp))
        }
        // Item text takes weight(1f) so the shortcut is pushed to
        // the right edge of the popup, making all shortcuts line up
        // in a column (like Win32's right-aligned shortcuts). The
        // submenu's max widthIn keeps the whole popup from getting
        // too wide and overlapping the section list.
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (shortcut != null) {
            Spacer(Modifier.width(16.dp))
            Text(
                shortcut,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun Sep() = HorizontalDivider(
    color = MaterialTheme.colorScheme.outlineVariant,
    thickness = 0.5.dp,
)

// File — src/Menu.cpp::menuDefFile
@Composable
private fun FileItems(onPick: (MenuAction) -> Unit) {
    LeafRow(stringResource(R.string.cmd_new_window), MenuAction.NewWindow, shortcut = "Ctrl + N", onPick = onPick)
    LeafRow(stringResource(R.string.cmd_open), MenuAction.Open, shortcut = "Ctrl + O", onPick = onPick)
    LeafRow(stringResource(R.string.cmd_recent_files), MenuAction.ShowRecent, onPick = onPick)
    LeafRow(stringResource(R.string.cmd_close), MenuAction.Close, shortcut = "Ctrl + W", onPick = onPick)
    Sep()
    LeafRow(stringResource(R.string.cmd_show_in_folder), MenuAction.ShowInFolder, onPick = onPick)
    LeafRow(stringResource(R.string.cmd_open_next), MenuAction.OpenNext, shortcut = "Ctrl + Shift + Right", onPick = onPick)
    LeafRow(stringResource(R.string.cmd_open_prev), MenuAction.OpenPrev, shortcut = "Ctrl + Shift + Left", onPick = onPick)
    Sep()
    LeafRow(stringResource(R.string.cmd_save_as), MenuAction.SaveAs, shortcut = "Ctrl + S", onPick = onPick)
    LeafRow(stringResource(R.string.cmd_share), MenuAction.ShareDocument, onPick = onPick)
    LeafRow(stringResource(R.string.cmd_save_annotations), MenuAction.SaveAnnotations, shortcut = "Ctrl + Shift + S", onPick = onPick)
    LeafRow(stringResource(R.string.cmd_rename), MenuAction.Rename, shortcut = "F2", onPick = onPick)
    LeafRow(stringResource(R.string.cmd_delete), MenuAction.Delete, onPick = onPick)
    Sep()
    LeafRow(stringResource(R.string.cmd_print), MenuAction.Print, shortcut = "Ctrl + P", onPick = onPick)
    Sep()
    LeafRow(stringResource(R.string.cmd_properties), MenuAction.Properties, shortcut = "Ctrl + D", onPick = onPick)
    Sep()
    LeafRow(stringResource(R.string.cmd_exit), MenuAction.Exit, shortcut = "Ctrl + Q", onPick = onPick)
}

// View — src/Menu.cpp::menuDefView
@Composable
private fun ViewItems(displayMode: DisplayMode, continuous: Boolean, onPick: (MenuAction) -> Unit) {
    LeafRow(stringResource(R.string.cmd_command_palette), MenuAction.CommandPalette, onPick = onPick)
    Sep()
    LeafRow(
        stringResource(R.string.cmd_single_page),
        MenuAction.SetDisplayMode(DisplayMode.SinglePage),
        checked = displayMode == DisplayMode.SinglePage,
        onPick = onPick,
    )
    LeafRow(
        stringResource(R.string.cmd_facing),
        MenuAction.SetDisplayMode(DisplayMode.Facing),
        checked = displayMode == DisplayMode.Facing,
        onPick = onPick,
    )
    LeafRow(
        stringResource(R.string.cmd_book_view),
        MenuAction.SetDisplayMode(DisplayMode.BookView),
        checked = displayMode == DisplayMode.BookView,
        onPick = onPick,
    )
    LeafRow(
        stringResource(R.string.cmd_show_pages_continuously),
        MenuAction.ToggleContinuous,
        checked = continuous,
        onPick = onPick,
    )
    LeafRow(stringResource(R.string.cmd_manga_mode), MenuAction.ToggleMangaMode, onPick = onPick)
    Sep()
    LeafRow(stringResource(R.string.cmd_rotate_left), MenuAction.RotateLeft, onPick = onPick)
    LeafRow(stringResource(R.string.cmd_rotate_right), MenuAction.RotateRight, onPick = onPick)
    Sep()
    LeafRow(stringResource(R.string.cmd_presentation), MenuAction.Presentation, onPick = onPick)
    LeafRow(stringResource(R.string.cmd_fullscreen), MenuAction.Fullscreen, onPick = onPick)
    Sep()
    LeafRow(stringResource(R.string.cmd_show_bookmarks), MenuAction.ShowBookmarks, onPick = onPick)
    LeafRow(stringResource(R.string.cmd_show_menu), MenuAction.ShowMenu, onPick = onPick)
    LeafRow(stringResource(R.string.cmd_show_toolbar), MenuAction.ShowToolbar, onPick = onPick)
}

// Go To — src/Menu.cpp::menuDefGoTo
@Composable
private fun GoToItems(onPick: (MenuAction) -> Unit) {
    LeafRow(stringResource(R.string.cmd_next_page), MenuAction.NextPage, onPick = onPick)
    LeafRow(stringResource(R.string.cmd_prev_page), MenuAction.PrevPage, onPick = onPick)
    LeafRow(stringResource(R.string.cmd_first_page), MenuAction.FirstPage, onPick = onPick)
    LeafRow(stringResource(R.string.cmd_last_page), MenuAction.LastPage, onPick = onPick)
    LeafRow(stringResource(R.string.cmd_go_to_page), MenuAction.GoToPage, onPick = onPick)
    Sep()
    LeafRow(stringResource(R.string.cmd_back), MenuAction.NavigateBack, onPick = onPick)
    LeafRow(stringResource(R.string.cmd_forward), MenuAction.NavigateForward, onPick = onPick)
    Sep()
    LeafRow(stringResource(R.string.find), MenuAction.FindFirst, onPick = onPick)
}

// Zoom — src/Menu.cpp::menuDefZoom
@Composable
private fun ZoomItems(zoom: ZoomLevel, onPick: (MenuAction) -> Unit) {
    LeafRow(
        stringResource(R.string.cmd_fit_page),
        MenuAction.FitPage,
        checked = zoom == ZoomLevel.FitPage,
        onPick = onPick,
    )
    LeafRow(stringResource(R.string.cmd_actual_size), MenuAction.ActualSize, onPick = onPick)
    LeafRow(
        stringResource(R.string.cmd_fit_width),
        MenuAction.FitWidth,
        checked = zoom == ZoomLevel.FitWidth,
        onPick = onPick,
    )
    LeafRow(
        stringResource(R.string.cmd_fit_height),
        MenuAction.FitHeight,
        checked = zoom == ZoomLevel.FitHeight,
        onPick = onPick,
    )
    LeafRow(stringResource(R.string.cmd_fit_by_orientation), MenuAction.FitByOrientation, onPick = onPick)
    LeafRow(
        stringResource(R.string.cmd_fit_content),
        MenuAction.FitContent,
        checked = zoom == ZoomLevel.FitContent,
        onPick = onPick,
    )
    LeafRow(stringResource(R.string.cmd_shrink_to_fit), MenuAction.ShrinkToFit, onPick = onPick)
    Sep()
    LeafRow(stringResource(R.string.cmd_custom_zoom), MenuAction.CustomZoom, onPick = onPick)
}

// Selection — src/Menu.cpp::menuDefMainSelection
@Composable
private fun SelectionItems(onPick: (MenuAction) -> Unit) {
    LeafRow(stringResource(R.string.cmd_copy_selection), MenuAction.CopySelection, shortcut = "Ctrl + C", onPick = onPick)
    Sep()
    LeafRow(stringResource(R.string.cmd_translate_google), MenuAction.TranslateGoogle, onPick = onPick)
    LeafRow(stringResource(R.string.cmd_translate_deepl), MenuAction.TranslateDeepL, onPick = onPick)
    LeafRow(stringResource(R.string.cmd_translate_grok), MenuAction.TranslateGrokBuild, onPick = onPick)
    LeafRow(stringResource(R.string.cmd_translate_claude), MenuAction.TranslateClaudeCode, onPick = onPick)
    LeafRow(stringResource(R.string.cmd_translate_codex), MenuAction.TranslateOpenAICodex, onPick = onPick)
    Sep()
    LeafRow(stringResource(R.string.cmd_search_google), MenuAction.SearchGoogle, onPick = onPick)
    LeafRow(stringResource(R.string.cmd_search_bing), MenuAction.SearchBing, onPick = onPick)
    LeafRow(stringResource(R.string.cmd_search_wikipedia), MenuAction.SearchWikipedia, onPick = onPick)
    LeafRow(stringResource(R.string.cmd_search_google_scholar), MenuAction.SearchGoogleScholar, onPick = onPick)
    Sep()
    LeafRow(stringResource(R.string.cmd_select_all), MenuAction.SelectAll, shortcut = "Ctrl + A", onPick = onPick)
}

// Read Aloud — src/Menu.cpp::menuDefReadAloud
@Composable
private fun ReadAloudItems(onPick: (MenuAction) -> Unit) {
    LeafRow(stringResource(R.string.cmd_start_reading_top), MenuAction.StartReadingTop, onPick = onPick)
    LeafRow(stringResource(R.string.cmd_pause_reading), MenuAction.PauseReading, onPick = onPick)
    LeafRow(stringResource(R.string.cmd_resume_reading), MenuAction.ResumeReading, onPick = onPick)
    LeafRow(stringResource(R.string.cmd_stop_reading), MenuAction.StopReading, onPick = onPick)
    Sep()
    LeafRow(stringResource(R.string.cmd_change_voice), MenuAction.ChangeVoice, onPick = onPick)
}

// Favorites — src/Menu.cpp::menuDefFavorites
@Composable
private fun FavoritesItems(onPick: (MenuAction) -> Unit) {
    LeafRow(stringResource(R.string.cmd_add_bookmark), MenuAction.AddBookmark, onPick = onPick)
    LeafRow(stringResource(R.string.cmd_remove_bookmark), MenuAction.RemoveBookmark, onPick = onPick)
    LeafRow(stringResource(R.string.cmd_show_favorites), MenuAction.ListBookmarks, onPick = onPick)
    LeafRow(stringResource(R.string.cmd_show_favorites_in_tab), MenuAction.ShowFavoritesInTab, onPick = onPick)
    Sep()
    LeafRow(stringResource(R.string.cmd_save_tab_group), MenuAction.SaveTabGroup, onPick = onPick)
    LeafRow(stringResource(R.string.cmd_restore_tab_group), MenuAction.RestoreTabGroup, onPick = onPick)
}

// Settings — src/Menu.cpp::menuDefSettings
@Composable
private fun SettingsItems(onPick: (MenuAction) -> Unit) {
    LeafRow(stringResource(R.string.cmd_change_language), MenuAction.ChangeLanguage, onPick = onPick)
    Sep()
    LeafRow(stringResource(R.string.cmd_options), MenuAction.Options, onPick = onPick)
    LeafRow(stringResource(R.string.cmd_advanced_settings), MenuAction.AdvancedSettings, onPick = onPick)
    LeafRow(stringResource(R.string.cmd_advanced_options), MenuAction.AdvancedOptions, onPick = onPick)
    Sep()
    LeafRow(stringResource(R.string.cmd_theme), MenuAction.Theme, onPick = onPick)
}

// Help — src/Menu.cpp::menuDefHelp
@Composable
private fun HelpItems(onPick: (MenuAction) -> Unit) {
    LeafRow(stringResource(R.string.cmd_manual), MenuAction.Manual, onPick = onPick)
    LeafRow(stringResource(R.string.cmd_keyboard_shortcuts), MenuAction.KeyboardShortcuts, onPick = onPick)
    LeafRow(stringResource(R.string.cmd_manual_on_website), MenuAction.ManualOnWebsite, onPick = onPick)
    LeafRow(stringResource(R.string.cmd_visit_website), MenuAction.VisitWebsite, onPick = onPick)
    LeafRow(stringResource(R.string.cmd_check_update), MenuAction.CheckUpdate, onPick = onPick)
    Sep()
    LeafRow(stringResource(R.string.cmd_toggle_render_info), MenuAction.ToggleRenderInfo, onPick = onPick)
    LeafRow(stringResource(R.string.cmd_toggle_cache_info), MenuAction.ToggleCacheInfo, onPick = onPick)
    Sep()
    LeafRow(stringResource(R.string.cmd_about), MenuAction.About, onPick = onPick)
}

// Debug — src/Menu.cpp::menuDefDebug
@Composable
private fun DebugItems(onPick: (MenuAction) -> Unit) {
    LeafRow(stringResource(R.string.cmd_show_links), MenuAction.ShowLinks, onPick = onPick)
    LeafRow(stringResource(R.string.cmd_download_symbols), MenuAction.DownloadSymbols, onPick = onPick)
    LeafRow(stringResource(R.string.cmd_test_app), MenuAction.TestApp, onPick = onPick)
    LeafRow(stringResource(R.string.cmd_show_notification), MenuAction.ShowNotification, onPick = onPick)
}
