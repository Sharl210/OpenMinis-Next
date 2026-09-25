package com.openminis.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** A compact, searchable row in the conversation map. */
data class ConversationMapItem(
    val id: String,
    val role: String,
    val preview: String,
    val messageNumber: Int,
)

/** Pure filtering helper kept separate so the map behavior can be tested without Compose. */
internal fun filterConversationMapItems(
    messages: List<ChatMessage>,
    query: String,
): List<ConversationMapItem> {
    val normalizedQuery = query.trim().lowercase()
    return messages.mapIndexed { index, message ->
        ConversationMapItem(
            id = message.id,
            role = message.role,
            preview = conversationMapPreview(message.content),
            messageNumber = index + 1,
        )
    }.filter { item ->
        normalizedQuery.isEmpty() ||
            item.preview.lowercase().contains(normalizedQuery) ||
            item.role.lowercase().contains(normalizedQuery) ||
            item.messageNumber.toString() == normalizedQuery
    }
}

internal fun conversationMapPreview(content: String, maxChars: Int = 180): String {
    val compact = content.replace(Regex("\\s+"), " ").trim()
    return if (compact.length <= maxChars) compact else compact.take(maxChars - 1) + "…"
}

/**
 * Conversation map presented from the chat overflow menu. It owns only local
 * search/collapse state; selecting a row is delegated to [onMessageClick] so
 * ChatScreen can use its existing LazyListState and stable item keys.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationMapScreen(
    messages: List<ChatMessage>,
    onDismiss: () -> Unit,
    onMessageClick: (messageId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var collapsedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val entries = remember(messages, query) { filterConversationMapItems(messages, query) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Conversation map",
                    style = MaterialTheme.typography.titleLarge,
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close conversation map")
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text("Search messages") },
                label = { Text("Search") },
            )
            if (entries.isEmpty()) {
                Text(
                    text = if (messages.isEmpty()) "No messages" else "No matching messages",
                    modifier = Modifier.padding(vertical = 28.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(entries, key = { it.id }) { item ->
                        val collapsed = item.id in collapsedIds
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics {
                                    contentDescription = "Jump to message ${item.messageNumber}"
                                },
                            shape = MaterialTheme.shapes.medium,
                            tonalElevation = 1.dp,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(
                                        role = Role.Button,
                                        onClick = { onMessageClick(item.id) },
                                    )
                                    .padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${item.messageNumber}. ${item.role.replaceFirstChar { it.uppercase() }}",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    if (!collapsed) {
                                        Text(
                                            text = item.preview.ifEmpty { "(empty message)" },
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        collapsedIds = if (collapsed) {
                                            collapsedIds - item.id
                                        } else {
                                            collapsedIds + item.id
                                        }
                                    },
                                ) {
                                    Icon(
                                        imageVector = if (collapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                                        contentDescription = if (collapsed) "Expand message" else "Collapse message",
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
