package com.openminis.app.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.browser.BrowserHistoryStore
import com.openminis.app.ui.components.MinisTextButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Browser history and bookmark manager. History and bookmarks intentionally use
 * separate list branches: their data classes have the same display fields but
 * keeping their types distinct prevents a mixed Compose list from erasing the
 * stable model type (and makes delete/toggle actions unambiguous).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserHistorySheet(
    historyStore: BrowserHistoryStore,
    onNavigate: (String) -> Unit,
    onDismiss: () -> Unit,
    initialBookmarks: Boolean = false,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by remember { mutableStateOf("") }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(initialBookmarks) }
    var refreshKey by remember { mutableStateOf(0) }

    val historyGroups = remember(searchQuery, refreshKey) {
        if (searchQuery.isBlank()) historyStore.groupedByDay().toList()
        else listOf("Results" to historyStore.search(searchQuery))
    }
    val bookmarks: List<BrowserHistoryStore.Bookmark> = remember(searchQuery, refreshKey) {
        historyStore.searchBookmarks(searchQuery)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (showBookmarks) stringResource(R.string.browser_bookmarks_title)
                    else stringResource(R.string.browser_history_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                MinisTextButton(onClick = {
                    showBookmarks = !showBookmarks
                    searchQuery = ""
                }) {
                    Text(
                        if (showBookmarks) stringResource(R.string.browser_history_action)
                        else stringResource(R.string.browser_bookmarks_title),
                    )
                }
                if (!showBookmarks) {
                    MinisTextButton(
                        onClick = { showClearConfirm = true },
                        enabled = historyStore.getEntries().isNotEmpty(),
                    ) {
                        Text(
                            stringResource(R.string.browser_history_clear),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                MinisTextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.browser_history_done))
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        if (showBookmarks) stringResource(R.string.browser_bookmarks_search)
                        else stringResource(R.string.browser_history_search),
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                textStyle = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.size(8.dp))

            if (showBookmarks) {
                if (bookmarks.isEmpty()) {
                    EmptyBrowserLibrary(
                        text = stringResource(R.string.browser_bookmarks_empty),
                    )
                } else {
                    LazyColumn(Modifier.weight(1f)) {
                        items(bookmarks, key = { it.id }) { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onNavigate(entry.url) }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(
                                    Icons.Default.Language,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        entry.title.ifEmpty { entry.domain },
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        entry.url,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        historyStore.removeBookmark(entry.id)
                                        refreshKey++
                                    },
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = stringResource(R.string.browser_bookmark_delete),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                Text(
                                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(entry.timestamp)),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                )
                            }
                            HorizontalDivider(modifier = Modifier.padding(start = 46.dp))
                        }
                    }
                }
            } else if (historyGroups.flatMap { it.second }.isEmpty()) {
                EmptyBrowserLibrary(
                    text = stringResource(R.string.browser_history_empty_title),
                )
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    historyGroups.forEach { (label, rows) ->
                        if (rows.isEmpty()) return@forEach
                        item(key = "header_$label") {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                            )
                        }
                        items(rows, key = { it.id }) { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onNavigate(entry.url) }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(
                                    Icons.Default.Language,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        entry.title.ifEmpty { entry.domain },
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        entry.url,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        historyStore.deleteHistory(entry.id)
                                        refreshKey++
                                    },
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = stringResource(R.string.common_delete),
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                                Text(
                                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(entry.timestamp)),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                )
                            }
                            HorizontalDivider(modifier = Modifier.padding(start = 46.dp))
                        }
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.browser_history_clear_dialog_title)) },
            text = { Text(stringResource(R.string.browser_history_clear_dialog_message)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    historyStore.clear()
                    refreshKey++
                    showClearConfirm = false
                }) {
                    Text(
                        stringResource(R.string.browser_history_clear),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun EmptyBrowserLibrary(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 160.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
