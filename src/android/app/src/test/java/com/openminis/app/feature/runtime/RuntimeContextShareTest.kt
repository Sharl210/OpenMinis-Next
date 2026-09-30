package com.openminis.app.feature.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R55/R56 counterpart for the runtime side: a dispatcher's `--share=` becomes a
 * REFERENCE, and nothing about the shared messages travels with it.
 *
 * The requirement behind this file, verbatim from
 * `plans/ULW-2026-09-25-01/request.md:138`:
 *
 *   「派遣方，可以主动选择分享我们当前绘画的哪些上下文，或者说选择直接主动全选…
 *     附加过去的，并不是以文字消息附加过去的……接收方可以用这个对话ID向我们运
 *     行时中心去查询，然后查询对应指定的一些索引号……就不会一下子把上下文给撑爆
 *     如果直接纯文本硬塞的话」
 *
 * Every test that mentions [CANARY] is load-bearing: the canary stands in for the
 * sender's message text, and the assertions are that it appears NOWHERE in the
 * resolved attachment, in its `toString`, or in the capsule the receiver reads.
 * Inlining the transcript is the failure mode these pin, and it fails them.
 */
class RuntimeContextShareTest {

    private val conversation = RuntimeConversationAddress.fromStableSessionId("conv-parent")

    @Test
    fun `share all references every message without copying any of them`() {
        val transcript = listOf("first $CANARY", "second", "third")

        val attachment = RuntimeContextAttachments.resolve(RuntimeContextShareRequest.All, conversation, transcript)

        assertNotNull("sharing all of a non-empty conversation must produce a reference", attachment)
        assertEquals(RuntimeContextSelection.All(3), attachment!!.selection)
        assertEquals(listOf(0, 1, 2), attachment.indices)
        assertEquals(3, attachment.size)
        assertTrue("references are never payload", attachment.referentialOnly)
        assertEquals(conversation, attachment.conversationId)
    }

    @Test
    fun `a selected share is normalised to the messages the conversation actually has`() {
        val transcript = listOf("a", "b", "c")

        val attachment = RuntimeContextAttachments.resolve(
            RuntimeContextShareRequest.Selected(listOf(2, 0, 2)),
            conversation,
            transcript,
        )

        assertEquals(RuntimeContextSelection.Indices(listOf(0, 2)), attachment!!.selection)
        assertEquals(2, attachment.size)
    }

    @Test
    fun `indices the conversation no longer has are dropped rather than invented`() {
        val transcript = listOf("a", "b")

        assertNull(
            "a selection that refers to nothing must not become an attachment",
            RuntimeContextAttachments.resolve(
                RuntimeContextShareRequest.Selected(listOf(5, 9)),
                conversation,
                transcript,
            ),
        )
        assertEquals(
            "in-range indices survive an out-of-range sibling",
            RuntimeContextSelection.Indices(listOf(1)),
            RuntimeContextAttachments.resolve(
                RuntimeContextShareRequest.Selected(listOf(1, 9)),
                conversation,
                transcript,
            )!!.selection,
        )
    }

    @Test
    fun `nothing is attached when there is nothing to share`() {
        assertNull(RuntimeContextAttachments.resolve(null, conversation, listOf("a")))
        assertNull(
            "an empty conversation has no messages to reference",
            RuntimeContextAttachments.resolve(RuntimeContextShareRequest.All, conversation, emptyList()),
        )
    }

    @Test
    fun `the attachment carries the conversation locator, never the messages`() {
        val attachment = RuntimeContextAttachments.resolve(
            RuntimeContextShareRequest.All,
            conversation,
            listOf("user said $CANARY", "assistant replied with $CANARY too"),
        )!!

        // The locator the receiver queries by is present…
        assertTrue(
            "the receiver needs the conversation id to query: $attachment",
            attachment.toString().contains(conversation.value),
        )
        // …and the text is not, anywhere in the object. A new text-carrying
        // field would show up in `toString` and fail here even if `capsule`
        // were left alone.
        assertFalse(
            "the shared messages must never be copied into the attachment: $attachment",
            attachment.toString().contains(CANARY),
        )
        assertFalse(
            "the capsule is what the receiver reads, so it must not carry text either",
            RuntimeContextAttachments.capsule(attachment).contains(CANARY),
        )
    }

    @Test
    fun `the capsule states the locator and the indices`() {
        val attachment = RuntimeContextAttachments.resolve(
            RuntimeContextShareRequest.Selected(listOf(4, 7)),
            conversation,
            List(10) { "message $it" },
        )!!

        val capsule = RuntimeContextAttachments.capsule(attachment)

        assertTrue("capsule must name the conversation: $capsule", capsule.contains(conversation.value))
        assertTrue("capsule must name the indices the receiver queries: $capsule", capsule.contains("4,7"))
        assertTrue("capsule must state how many messages are referenced: $capsule", capsule.contains("2 message(s) referenced"))
    }

    @Test
    fun `a long selection collapses to a count so the summary cap cannot truncate the locator`() {
        val attachment = RuntimeContextAttachments.resolve(
            RuntimeContextShareRequest.All,
            conversation,
            List(5_000) { "m$it" },
        )!!

        val capsule = RuntimeContextAttachments.capsule(attachment)

        assertTrue("a 5000-message share must not list 5000 indices", capsule.contains("<all 5000 messages>"))
        assertTrue(
            "the capsule must fit the record's summary cap; length=${capsule.length}",
            capsule.length <= RuntimeCommunicationMetadata.MAX_SUMMARY_CHARS,
        )
    }

    @Test
    fun `an index selection must be well formed to exist at all`() {
        // Constructed directly (not through resolve), so these pin the type's
        // own invariants rather than resolve's normalisation.
        runCatching { RuntimeContextSelection.Indices(emptyList()) }
            .onSuccess { error("an empty selection is not a reference") }
        runCatching { RuntimeContextSelection.Indices(listOf(-1)) }
            .onSuccess { error("negative indices are not message positions") }
        runCatching { RuntimeContextSelection.Indices(listOf(2, 1)) }
            .onSuccess { error("indices must be ascending so a record decodes predictably") }
        runCatching { RuntimeContextSelection.Indices(listOf(1, 1)) }
            .onSuccess { error("duplicate indices would double-count in the receiver's budget") }
    }

    private companion object {
        /** Stands in for the sender's message text. Must never travel. */
        const val CANARY = "CANARY_TRANSCRIPT_BODY_9f3a"
    }
}
