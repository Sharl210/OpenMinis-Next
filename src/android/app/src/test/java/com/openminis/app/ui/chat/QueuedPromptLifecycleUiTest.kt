package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueuedPromptLifecycleUiTest {
    @Test
    fun `queue and steer delivery are projected unchanged`() {
        val queue = QueuedPrompt("q", "queued", delivery = QueuedPromptDelivery.QUEUE)
        val steer = QueuedPrompt("s", "steered", delivery = QueuedPromptDelivery.STEER)

        assertEquals(QueuedPromptDelivery.QUEUE, QueuedPromptUiPolicy.deliveryForMessage(queue))
        assertEquals(QueuedPromptDelivery.STEER, QueuedPromptUiPolicy.deliveryForMessage(steer))
    }

    @Test
    fun `direct withdrawal only accepts enqueued and never restores a draft`() {
        val ledger = ledger()
        val ordinary = ChatDraftSnapshot("unsent draft")
        val editState = ChatEditDraftState(ordinary)

        val enqueued = ledger.state("queue")!!
        assertTrue(QueuedPromptUiPolicy.canWithdraw(enqueued))
        val directDiscard = ledger.withdrawForEdit("queue")!!
        assertTrue(directDiscard.accepted)
        assertEquals(QueuedPromptLifecycle.WITHDRAWN_EDIT, ledger.state("queue")!!.lifecycle)
        assertEquals(ordinary, editState.currentDraft())
        assertFalse(QueuedPromptUiPolicy.canWithdraw(ledger.state("queue")!!))

        assertTrue(ledger.claim("steer"))
        assertFalse(QueuedPromptUiPolicy.canWithdraw(ledger.state("steer")!!))
        assertFalse(ledger.withdrawForEdit("steer")!!.accepted)
        assertTrue(ledger.consume("steer"))
        assertFalse(QueuedPromptUiPolicy.canWithdraw(ledger.state("steer")!!))
        assertFalse(ledger.withdrawForEdit("steer")!!.accepted)
    }

    @Test
    fun `withdraw for edit restores queued text and attachments as edit draft`() {
        val ledger = ledger()
        val editState = ChatEditDraftState(ChatDraftSnapshot("ordinary draft"))
        val withdrawn = ledger.withdrawForEdit("queue")!!
        assertTrue(withdrawn.accepted)

        val target = ChatDraftSnapshot("original queued text")
        assertEquals(target, editState.beginEditing(withdrawn.state.messageId, target))
        assertEquals(target, editState.currentDraft())
        assertEquals(ChatDraftSnapshot("ordinary draft"), editState.exitEditing())
        assertTrue(ledger.restoreEdited("queue"))
        assertEquals(QueuedPromptLifecycle.ENQUEUED, ledger.state("queue")!!.lifecycle)
    }

    @Test
    fun `retry remains available for sent messages and hidden for queued or streaming`() {
        assertTrue(QueuedPromptUiPolicy.canRetry(isQueued = false, isStreaming = false))
        assertFalse(QueuedPromptUiPolicy.canRetry(isQueued = true, isStreaming = false))
        assertFalse(QueuedPromptUiPolicy.canRetry(isQueued = false, isStreaming = true))
        assertFalse(QueuedPromptUiPolicy.canRetry(isQueued = true, isStreaming = true))
    }

    private fun ledger(): QueuedPromptLedger = QueuedPromptLedger().apply {
        register("queue", "message-queue", QueuedPromptDelivery.QUEUE)
        register("steer", "message-steer", QueuedPromptDelivery.STEER)
    }
}
