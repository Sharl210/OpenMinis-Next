package com.openminis.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-copy-conversation-id] Copying an ID must put THAT ID on the
 * clipboard and must tell the user what happened.
 *
 * request.md:136 asks for the copy entries; the visible-feedback half is this
 * project's own repeated defect (the plain "Copy" on a user message bubble wrote
 * the clipboard and said nothing, so success and silent failure were
 * indistinguishable). These assertions are on the value that actually reaches
 * the clipboard writer and on the message chosen to be shown — the two things a
 * user experiences — not on source text.
 */
class ClipboardFeedbackTest {

    /** Records exactly what production would have handed to the clipboard. */
    private class RecordingWriter(private val succeed: Boolean = true) : ClipboardWriter {
        var lastLabel: String? = null
        var lastValue: String? = null
        var calls = 0

        override fun write(label: String, value: String): Boolean {
            calls++
            lastLabel = label
            lastValue = value
            return succeed
        }
    }

    @Test
    fun `the copied value is exactly the id that was passed in`() {
        val writer = RecordingWriter()
        val id = "minis-conv-6f1c0a5e-3b9d-4a7c-8e21-0d5f9a4b7c33"

        val outcome = performClipboardCopy(
            writer = writer,
            label = "conversation-id",
            value = id,
            successText = "Copied: $id",
            failureText = "nope",
        )

        assertTrue(outcome.copied)
        assertEquals("the clipboard must receive the id verbatim", id, writer.lastValue)
        assertEquals("conversation-id", writer.lastLabel)
        assertEquals(1, writer.calls)
    }

    /**
     * The feedback half. A copy that succeeded must say so, and it must say it
     * with the success text — not silently, which is what the old code did.
     */
    @Test
    fun `a successful copy reports the success text`() {
        val writer = RecordingWriter(succeed = true)
        val outcome = performClipboardCopy(
            writer = writer,
            label = "conversation-id",
            value = "minis-conv-x",
            successText = "Copied: minis-conv-x",
            failureText = "failed",
        )
        assertTrue(outcome.copied)
        assertEquals("Copied: minis-conv-x", outcome.message)
    }

    /**
     * And a copy that did NOT happen must never be reported as if it had. The
     * clipboard service can be absent and `setPrimaryClip` can throw; both land
     * here as `false`.
     */
    @Test
    fun `a failed copy reports the failure text instead of success`() {
        val writer = RecordingWriter(succeed = false)
        val outcome = performClipboardCopy(
            writer = writer,
            label = "message-id",
            value = "minis-msg-x",
            successText = "Copied: minis-msg-x",
            failureText = "Couldn't copy to the clipboard",
        )

        assertFalse(outcome.copied)
        assertEquals("Couldn't copy to the clipboard", outcome.message)
        assertNotEquals("Copied: minis-msg-x", outcome.message)
    }
}
