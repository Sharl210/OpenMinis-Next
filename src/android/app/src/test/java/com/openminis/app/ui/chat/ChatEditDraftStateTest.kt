package com.openminis.app.ui.chat

import android.net.TestUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatEditDraftStateTest {
    private fun attachment(name: String): InputAttachment = InputAttachment(
        fileName = name,
        uri = TestUri("content://test/$name"),
        mimeType = "text/plain",
        kind = InputAttachment.Kind.DOCUMENT,
    )

    @Test
    fun `enter edit saves ordinary draft and swaps text and attachments`() {
        val ordinaryAttachment = attachment("ordinary.txt")
        val editAttachment = attachment("edit.txt")
        val state = ChatEditDraftState(
            ChatDraftSnapshot("ordinary", listOf(ordinaryAttachment)),
        )

        val entered = state.beginEditing(
            "message-1",
            ChatDraftSnapshot("message one", listOf(editAttachment)),
        )

        assertTrue(state.isEditing)
        assertEquals("message-1", state.editingMessageId)
        assertEquals("message one", entered.text)
        assertEquals(listOf(editAttachment), state.currentDraft().attachments)
        assertEquals("ordinary", state.ordinaryDraft().text)
        assertEquals(listOf(ordinaryAttachment), state.ordinaryDraft().attachments)
    }

    @Test
    fun `switching target replaces only singleton edit draft`() {
        val ordinary = ChatDraftSnapshot("ordinary", listOf(attachment("ordinary")))
        val first = ChatDraftSnapshot("first", listOf(attachment("first")))
        val second = ChatDraftSnapshot("second", listOf(attachment("second")))
        val state = ChatEditDraftState(ordinary)

        state.beginEditing("message-1", first)
        state.updateCurrentDraft("first changed", listOf(attachment("first-changed")))
        state.switchEditingTarget("message-2", second)

        assertEquals("message-2", state.editingMessageId)
        assertEquals(second, state.currentDraft())
        assertEquals(ordinary, state.ordinaryDraft())
    }

    @Test
    fun `exit restores ordinary draft without commit`() {
        val ordinary = ChatDraftSnapshot("ordinary", listOf(attachment("ordinary")))
        val state = ChatEditDraftState(ordinary)
        state.beginEditing("message-1", ChatDraftSnapshot("edited"))
        state.updateCurrentDraft("changed but not committed", emptyList())

        val restored = state.exitEditing()

        assertEquals(ordinary, restored)
        assertFalse(state.isEditing)
        assertNull(state.editingMessageId)
        assertEquals(ordinary, state.currentDraft())
    }

    @Test
    fun `commit is update in place and restores ordinary draft`() {
        val ordinary = ChatDraftSnapshot("ordinary", listOf(attachment("ordinary")))
        val state = ChatEditDraftState(ordinary)
        state.beginEditing("message-1", ChatDraftSnapshot("replacement"))
        state.updateCurrentDraft("replacement changed", emptyList())

        val result = state.commitEditing()

        assertEquals("message-1", result.messageId)
        assertEquals("replacement changed", result.replacement.text)
        assertEquals(EditCommitMode.UPDATE_IN_PLACE, result.mode)
        assertFalse(state.isEditing)
        assertEquals(ordinary, state.currentDraft())
    }

    @Test
    fun `withdraw edit accepts only enqueued prompt`() {
        val enqueued = QueuedPromptState("message-1", QueuedPromptDelivery.QUEUE)
        val withdrawn = enqueued.withdrawForEdit()

        assertTrue(withdrawn.accepted)
        assertEquals(QueuedPromptLifecycle.WITHDRAWN_EDIT, withdrawn.state.lifecycle)
        assertEquals(enqueued.messageId, withdrawn.state.messageId)

        val claimed = enqueued.claim()
        val claimedResult = claimed.withdrawForEdit()
        assertFalse(claimedResult.accepted)
        assertEquals(QueuedPromptLifecycle.CLAIMED, claimedResult.state.lifecycle)

        val consumedResult = claimed.consume().withdrawForEdit()
        assertFalse(consumedResult.accepted)
        assertEquals(QueuedPromptLifecycle.CONSUMED, consumedResult.state.lifecycle)
    }

    @Test
    fun `steer has same withdrawal rule`() {
        val prompt = QueuedPromptState("message-2", QueuedPromptDelivery.STEER)
        assertTrue(prompt.withdrawForEdit().accepted)
        assertFalse(prompt.claim().withdrawForEdit().accepted)
    }
}
