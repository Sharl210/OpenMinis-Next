package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueuedPromptLifecycleUiTest {
    @Test
    fun `withdrawable verdict is derived from the ledger at every transition`() {
        val ledger = ledger()

        assertTrue(QueuedPromptUiPolicy.isWithdrawable(ledger, "queue"))

        // The model took it: the bubble must stop offering 撤回 / 撤回并编辑 here,
        // because the ledger will refuse the tap from this instant on.
        assertTrue(ledger.claim("queue"))
        assertFalse(QueuedPromptUiPolicy.isWithdrawable(ledger, "queue"))
        assertFalse(QueuedPromptUiPolicy.canWithdraw(ledger.state("queue")!!))

        // A failed execution rolls the claim back, so the affordances return.
        assertTrue(ledger.rollbackClaim("queue"))
        assertTrue(QueuedPromptUiPolicy.isWithdrawable(ledger, "queue"))

        assertTrue(ledger.claim("queue"))
        assertTrue(ledger.consume("queue"))
        assertFalse(QueuedPromptUiPolicy.isWithdrawable(ledger, "queue"))
    }

    @Test
    fun `withdrawable verdict is false for a missing prompt id`() {
        val ledger = ledger()

        assertFalse(QueuedPromptUiPolicy.isWithdrawable(ledger, null))
        assertFalse(QueuedPromptUiPolicy.isWithdrawable(ledger, "never-registered"))
    }

    @Test
    fun `a refused withdraw maps to the visible ALREADY_STARTED notice`() {
        assertEquals(QueuedWithdrawOutcome.WITHDRAWN, QueuedPromptUiPolicy.withdrawOutcome(accepted = true))
        assertEquals(
            QueuedWithdrawOutcome.ALREADY_STARTED,
            QueuedPromptUiPolicy.withdrawOutcome(accepted = false),
        )
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
    fun `bubble actions remain available for sent messages and hidden for queued or streaming`() {
        assertTrue(QueuedPromptUiPolicy.canActOnSentMessage(isQueued = false, isStreaming = false))
        assertFalse(QueuedPromptUiPolicy.canActOnSentMessage(isQueued = true, isStreaming = false))
        assertFalse(QueuedPromptUiPolicy.canActOnSentMessage(isQueued = false, isStreaming = true))
        assertFalse(QueuedPromptUiPolicy.canActOnSentMessage(isQueued = true, isStreaming = true))
    }

    private fun ledger(): QueuedPromptLedger = QueuedPromptLedger().apply {
        register("queue", "message-queue", QueuedPromptDelivery.QUEUE)
        register("steer", "message-steer", QueuedPromptDelivery.STEER)
    }
}
