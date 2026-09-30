package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationMapScreenTest {
    @Test
    fun `filter matches text beyond truncated preview`() {
        val message = ChatMessage(
            id = "m1",
            role = "user",
            content = "prefix " + "x".repeat(220) + " unique-tail-marker",
        )

        val result = filterConversationMapItems(listOf(message), "unique-tail-marker")

        assertEquals(listOf("m1"), result.map { it.id })
    }

    @Test
    fun `row lookup uses actual reversed flat item order and stable message ids`() {
        val messages = listOf(
            ChatMessage("older", "user", "older text"),
            ChatMessage("newer", "assistant", "newer text"),
        )
        val chronological = listOf(
            FlatChatItem.UserBubble(messages[0]),
            FlatChatItem.AssistantHeader("newer"),
            FlatChatItem.AssistantLegacyContent("newer", "newer text", false),
        )
        val displayed = chronological.asReversed()

        assertEquals(0, conversationRowIndexForMessage(displayed, "newer"))
        assertEquals(2, conversationRowIndexForMessage(displayed, "older"))
        assertEquals(-1, conversationRowIndexForMessage(displayed, "missing"))
    }
    @Test
    fun `filter preserves message order`() {
        val messages = listOf(
            ChatMessage("m1", "user", "first"),
            ChatMessage("m2", "assistant", "second match"),
            ChatMessage("m3", "user", "third match"),
        )

        assertEquals(
            listOf("m2", "m3"),
            filterConversationMapItems(messages, "match").map { it.id },
        )
    }
}
