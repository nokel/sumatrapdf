package com.sumatrapdf.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

// The find toolbar that lives just below the main toolbar when active.
// Mirrors the Win32 SumatraPDF Find toolbar: a query field, a hit
// counter ("3 / 47"), up/down navigation between hits, and a close
// button. The query is not searched until the user presses Enter or
// clicks the up/down arrows.
@Composable
fun SearchBar(
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    onSubmit: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onClose: () -> Unit,
    currentHit: Int,
    totalHits: Int,
    visible: Boolean,
    isSearching: Boolean = false,
    searchProgress: Float? = null,
    anyTextFieldFocused: MutableState<Boolean>,
) {
    if (!visible) return
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 2.dp,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text("Find in document") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .textFieldKeyHandler(
                            value = query,
                            onValueChange = onQueryChange,
                            anyTextFieldFocused = anyTextFieldFocused,
                            onSubmit = onSubmit,
                            onClose = onClose,
                        ),
                )
                if (isSearching) {
                    // The bar shows a percentage while a search is
                    // running. The progress is the page loop's
                    // current page / pageCount (0..1). The Win32
                    // Find window does the same — a status line
                    // in the corner of the dialog.
                    Text(
                        text = if (searchProgress != null) {
                            "${(searchProgress * 100).toInt()}%"
                        } else {
                            "Searching…"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box(modifier = Modifier.width(20.dp).height(20.dp)) {
                        CircularProgressIndicator(
                            modifier = Modifier.fillMaxSize(),
                            strokeWidth = 2.dp,
                        )
                    }
                } else if (totalHits > 0) {
                    Text(
                        text = "$currentHit / $totalHits",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (query.text.isNotEmpty()) {
                    Text(
                        text = "No matches",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onPrev, enabled = totalHits > 0 && !isSearching) {
                    Icon(
                        Icons.Outlined.KeyboardArrowUp,
                        contentDescription = "Previous hit",
                    )
                }
                IconButton(onClick = onNext, enabled = totalHits > 0 && !isSearching) {
                    Icon(
                        Icons.Outlined.KeyboardArrowDown,
                        contentDescription = "Next hit",
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close find")
                }
            }
            // A thin progress strip under the row so the user
            // sees the search sweep across the document, not just
            // a spinner. Matches the Win32 Find window's status
            // bar.
            if (isSearching && searchProgress != null) {
                LinearProgressIndicator(
                    progress = { searchProgress },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                )
            } else {
                // Reserve the same vertical space when not
                // searching so the toolbar does not jump.
                Spacer(modifier = Modifier.fillMaxWidth().height(2.dp))
            }
        }
    }
}

