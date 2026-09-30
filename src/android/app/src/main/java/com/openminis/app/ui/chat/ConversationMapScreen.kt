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
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

/** A compact, searchable row in the conversation map. */
data class ConversationMapItem(
    val id: String,
    val role: String,
    val preview: String,
    val messageNumber: Int,
)

/**
 * One map row plus the pre-lowercased haystack it can be found by.
 *
 * The haystack is built ONCE per message-list change (the composable memoises
 * [buildConversationMapIndex] on `messages`), not once per keystroke: re-scanning
 * and re-collapsing every message's whitespace on each typed character is what
 * made the search box crawl on sessions holding a few very long messages.
 */
internal data class ConversationMapEntry(
    val item: ConversationMapItem,
    val searchText: String,
)

private val CONVERSATION_MAP_WHITESPACE = Regex("\\s+")

/** Whitespace-collapsed form shared by the row preview and the searchable text. */
internal fun conversationMapCollapse(text: String): String =
    CONVERSATION_MAP_WHITESPACE.replace(text, " ").trim()

/**
 * Everything a user can reasonably expect to find by typing.
 *
 * `content` alone is not enough: an image-only turn has a BLANK content string
 * (its text lives in `attachmentNames` — see `ChatViewModel.retryFromMessage`),
 * tool output lives on the `tool_use` block's `content`, and reasoning lives on
 * the `thinking` block's `content`. Matching only `content` therefore made
 * attachment names, tool names/titles/arguments/output and thinking text
 * unfindable.
 *
 * Both the raw and the whitespace-collapsed form are included so a query that
 * spans a line break ("hello world" matching "hello\nworld") keeps matching.
 */
internal fun conversationMapSearchText(message: ChatMessage): String = buildString {
    append(message.content)
    for (name in message.attachmentNames) {
        append('\n')
        append(name)
    }
    for (block in message.toolBlocks) {
        append('\n')
        append(block.toolName)
        append('\n')
        append(block.toolTitle)
        append('\n')
        append(block.toolArgs)
        append('\n')
        append(block.content)
    }
    append('\n')
    append(message.role)
    append('\n')
    append(message.error.orEmpty())
}

/**
 * Text a row falls back to when the message body itself is blank. Without the
 * fallback an attachment-only turn (blank `content` by design) and a tool-only
 * assistant turn both rendered as "(empty message)" with nothing to recognise.
 */
private fun conversationMapPreviewSource(message: ChatMessage): String {
    if (message.content.isNotBlank()) return message.content
    val attachments = message.attachmentNames.filter { it.isNotBlank() }.joinToString(" ")
    if (attachments.isNotBlank()) return attachments
    message.toolBlocks.firstOrNull { it.content.isNotBlank() }?.let { return it.content }
    message.toolBlocks.firstOrNull { it.toolTitle.isNotBlank() }?.let { return it.toolTitle }
    return message.toolBlocks.firstOrNull { it.toolName.isNotBlank() }?.toolName.orEmpty()
}

internal fun conversationMapPreview(message: ChatMessage, maxChars: Int = 180): String {
    val compact = conversationMapCollapse(conversationMapPreviewSource(message))
    return if (compact.length <= maxChars) compact else compact.take(maxChars - 1) + "…"
}

/** One searchable entry per message, in conversation order. */
internal fun buildConversationMapIndex(messages: List<ChatMessage>): List<ConversationMapEntry> =
    messages.mapIndexed { index, message ->
        val raw = conversationMapSearchText(message)
        ConversationMapEntry(
            item = ConversationMapItem(
                id = message.id,
                role = message.role,
                preview = conversationMapPreview(message),
                messageNumber = index + 1,
            ),
            // Raw + collapsed: the collapsed copy is what lets a query with a
            // space match a message that stores the same words across a newline.
            searchText = (raw + "\n" + conversationMapCollapse(raw)).lowercase(),
        )
    }

internal fun filterConversationMapIndex(
    entries: List<ConversationMapEntry>,
    query: String,
): List<ConversationMapItem> {
    val normalizedQuery = query.trim().lowercase()
    if (normalizedQuery.isEmpty()) return entries.map { it.item }
    return entries
        .filter { entry ->
            entry.searchText.contains(normalizedQuery) ||
                entry.item.messageNumber.toString() == normalizedQuery
        }
        .map { it.item }
}

/** Pure filtering helper kept separate so the map behavior can be tested without Compose. */
internal fun filterConversationMapItems(
    messages: List<ChatMessage>,
    query: String,
): List<ConversationMapItem> = filterConversationMapIndex(buildConversationMapIndex(messages), query)

/**
 * What a jump from the map must do before the target's row can exist.
 *
 * Resolving this is a pure function of the message list and its window, which is
 * what makes the deleted-target case decidable without Compose or a ViewModel:
 * the answer is [TargetGone], not "wait for a row that will never be built".
 */
internal sealed interface ConversationMapJumpPlan {
    /** The target is still listed: grow the window by [pagesToLoad] pages, then find its row. */
    data class Ready(
        val targetIndex: Int,
        val requiredCap: Int,
        val pagesToLoad: Int,
    ) : ConversationMapJumpPlan

    /** No message carries that id any more — it was deleted while the map was open. */
    data object TargetGone : ConversationMapJumpPlan
}

internal fun planConversationMapJump(
    messages: List<ChatMessage>,
    targetId: String,
    visibleCap: Int,
    capStep: Int,
): ConversationMapJumpPlan {
    val targetIndex = messages.indexOfFirst { it.id == targetId }
    if (targetIndex < 0) return ConversationMapJumpPlan.TargetGone
    // The list renders a tail window of `visibleCap` messages, so the window has
    // to cover the target: requiredCap >= messages.size - targetIndex.
    val requiredCap = messages.size - targetIndex
    val missing = (requiredCap - visibleCap).coerceAtLeast(0)
    val pages = if (capStep <= 0) 0 else (missing + capStep - 1) / capStep
    return ConversationMapJumpPlan.Ready(targetIndex, requiredCap, pages)
}

/**
 * Deadline for the jump's row lookup. Bounded on purpose: the row list is
 * republished asynchronously and keeps changing while a turn streams, so an
 * unbounded wait for "some row matches" can strand the jump coroutine forever —
 * the user tapped a row and nothing at all happened.
 */
internal const val CONVERSATION_MAP_LOCATE_TIMEOUT_MS: Long = 8000L

/**
 * Row index of [targetId] in the displayed (reverse-chronological) flat rows, or
 * -1 when no matching row appears within [timeoutMs].
 */
internal suspend fun conversationMapRowIndexWithin(
    rows: Flow<List<FlatChatItem>>,
    targetId: String,
    timeoutMs: Long = CONVERSATION_MAP_LOCATE_TIMEOUT_MS,
): Int {
    if (targetId.isEmpty() || timeoutMs <= 0L) return -1
    return withTimeoutOrNull(timeoutMs) {
        rows.map { conversationRowIndexForMessage(it, targetId) }.first { it >= 0 }
    } ?: -1
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
    // Index is memoised on the message list; the query only re-filters it. See
    // ConversationMapEntry for why the split matters.
    val index = remember(messages) { buildConversationMapIndex(messages) }
    val entries = remember(index, query) { filterConversationMapIndex(index, query) }

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
                    text = stringResource(R.string.conversation_map_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.conversation_map_close))
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.conversation_map_search_messages)) },
                label = { Text(stringResource(R.string.conversation_map_search)) },
            )
            if (entries.isEmpty()) {
                Text(
                    text = stringResource(
                        if (messages.isEmpty()) R.string.conversation_map_no_messages
                        else R.string.conversation_map_no_matches,
                    ),
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
                        val jumpDescription = stringResource(R.string.conversation_map_jump_to_message, item.messageNumber)
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics {
                                    contentDescription = jumpDescription
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
                                            text = item.preview.ifEmpty { stringResource(R.string.conversation_map_empty_message) },
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
                                        contentDescription = stringResource(
                                            if (collapsed) R.string.conversation_map_expand_message
                                            else R.string.conversation_map_collapse_message,
                                        ),
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
