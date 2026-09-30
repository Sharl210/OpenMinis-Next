package com.openminis.app.feature.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-conversation-id-query] The conversation ID's ONE definition, as
 * request.md:136 requires it: 「他的ID前面都要有一个特定的前缀」 and the prefixed
 * form is the SAME id the conversation is named by (「这个对话ID和每一个子代理的那种
 * 哈希ID，它实际上是同一个ID」).
 */
class ConversationIdProtocolTest {

    private val rawSession = "6f1c0a5e-3b9d-4a7c-8e21-0d5f9a4b7c33"

    @Test
    fun `prefixing then stripping returns the original id`() {
        val prefixed = ConversationIdProtocol.prefixedConversationId(rawSession)
        assertEquals("minis-conv-$rawSession", prefixed)
        assertEquals(rawSession, ConversationIdProtocol.strip(prefixed))
    }

    @Test
    fun `a bare id is accepted without a prefix and normalises to the same value`() {
        assertEquals(
            ConversationIdProtocol.prefixedConversationId(rawSession),
            ConversationIdProtocol.prefixedConversationId(ConversationIdProtocol.strip(rawSession)),
        )
        assertEquals(rawSession, ConversationIdProtocol.strip(rawSession))
    }

    @Test
    fun `prefixing is idempotent so an exit cannot double the prefix`() {
        val once = ConversationIdProtocol.prefixedConversationId(rawSession)
        assertEquals(once, ConversationIdProtocol.prefixedConversationId(once))
        assertFalse(ConversationIdProtocol.prefixedConversationId(once).contains("minis-conv-minis-conv"))
    }

    /**
     * The root re-run generation is a runtime counter, NOT part of the
     * conversation's identity: `RuntimeSessionCoordinator.startRoot` mints
     * `"$sessionId#run-N"` when a terminal root is started again, and one
     * conversation run twice is still one conversation. Without the normalisation
     * a model handed two spellings of the same conversation would read them as two.
     */
    @Test
    fun `a root run generation collapses to the conversation id`() {
        assertEquals(rawSession, ConversationIdProtocol.conversationIdOf("$rawSession#run-3"))
        assertEquals(
            "minis-conv-$rawSession",
            ConversationIdProtocol.prefixedConversationId("$rawSession#run-3"),
        )
        // ...and the generation still strips out of an already-prefixed spelling,
        // which is what a tool argument can look like.
        assertEquals(rawSession, ConversationIdProtocol.strip("minis-conv-$rawSession#run-17"))
    }

    /**
     * The prefix must be unmistakeable against a bare UUID, because recognising
     * "this is one of ours" is the entire job it was asked to do.
     */
    @Test
    fun `the prefix cannot be confused with a bare uuid`() {
        assertTrue(ConversationIdProtocol.isPrefixed("minis-conv-$rawSession"))
        assertFalse(ConversationIdProtocol.isPrefixed(rawSession))
        // A UUID's alphabet is [0-9a-f-]: it cannot contain the letters that make
        // up the prefix, so no bare UUID can ever look prefixed.
        assertFalse(Regex("^[0-9a-fA-F-]+$").matches(ConversationIdProtocol.PREFIX + "x"))
    }

    @Test
    fun `blank ids stay blank instead of becoming a prefixed empty id`() {
        assertEquals("", ConversationIdProtocol.prefixedConversationId(""))
        assertEquals("", ConversationIdProtocol.prefixedConversationId("   "))
        assertEquals("", ConversationIdProtocol.strip("minis-conv-"))
    }

    @Test
    fun `message ids round-trip under their own prefix`() {
        val rawMessage = "b7d2f0aa-91c4-4e35-9a10-77c2be8d0f51"
        val prefixed = ConversationIdProtocol.prefixedMessageId(rawMessage)
        assertEquals("minis-msg-$rawMessage", prefixed)
        assertEquals(rawMessage, ConversationIdProtocol.stripMessageId(prefixed))
        assertEquals(prefixed, ConversationIdProtocol.prefixedMessageId(prefixed))
        // The two id kinds must not be mistakable for each other: a result carries
        // both at once.
        assertFalse(prefixed.startsWith(ConversationIdProtocol.PREFIX))
        assertTrue(ConversationIdProtocol.prefixedConversationId(rawMessage).startsWith(ConversationIdProtocol.PREFIX))
    }

    /**
     * [T-android-conversation-id-query] The app has TWO prefixed id forms and
     * they are not two conversations. A runtime route address
     * (`openminis-conv:<sha256>`) is one-way, so it can never be resolved back to
     * a session id — if a read tool accepted it, the store would find nothing and
     * report an empty conversation, which reads as "that conversation has no
     * messages". The forms must therefore be distinguishable by an exact test,
     * not by a substring one: `openminis-conv:` itself contains `minis-conv`.
     */
    @Test
    fun `a runtime route address is recognised and is not a conversation id`() {
        val address = RuntimeConversationAddress.fromStableSessionId(rawSession).value
        assertTrue(address.startsWith(ConversationIdProtocol.RUNTIME_ADDRESS_PREFIX))

        assertTrue(
            "an exact leading-token test must catch the address form",
            ConversationIdProtocol.isRuntimeAddress(address),
        )
        // ...and must NOT claim a conversation id or a bare uuid is an address.
        assertFalse(ConversationIdProtocol.isRuntimeAddress(rawSession))
        assertFalse(ConversationIdProtocol.isRuntimeAddress(ConversationIdProtocol.prefixedConversationId(rawSession)))

        // The substring trap this test exists to prevent: the address form
        // contains `minis-conv`, so a `contains`-based check would conflate them.
        assertTrue(address.contains("minis-conv"))
        assertFalse(
            "a substring test would wrongly call the address prefixed-with-PREFIX",
            ConversationIdProtocol.isPrefixed(address),
        )
    }

    /**
     * The two forms genuinely denote the same conversation, which is why a model
     * must be told they differ in KIND rather than assume they name two things.
     */
    @Test
    fun `both forms derive from the same conversation`() {
        val address = RuntimeConversationAddress.fromStableSessionId(rawSession)
        // Same input ⇒ same address, so the two forms can be correlated at all.
        assertEquals(address, RuntimeConversationAddress.fromStableSessionId(rawSession))
        assertTrue(
            ConversationIdProtocol.prefixedConversationId(rawSession)
                .endsWith(rawSession),
        )
    }

    /**
     * The birth injection has to carry the three facts the requirement asks a
     * model to be told, or having the prefix in the data does nothing.
     */
    @Test
    fun `the injected instruction explains the prefix and the read it enables`() {
        val text = ConversationIdProtocol.systemInstruction(rawSession)

        assertTrue("must name the prefix", text.contains(ConversationIdProtocol.PREFIX))
        assertTrue(
            "must say this is the app's own conversation id",
            text.contains("this app"),
        )
        assertTrue(
            "must name the tool that reads a conversation by that id",
            text.contains("conversation_query"),
        )
        assertTrue(
            "must say the read works without ancestry — the requirement's whole point",
            text.contains("no ancestry") || text.contains("share no ancestry"),
        )
        assertTrue(
            "must say the read is progressive, not one big dump",
            text.lowercase().contains("progressive"),
        )
        assertTrue(
            "must hand the model its own id in prefixed form",
            text.contains(ConversationIdProtocol.prefixedConversationId(rawSession)),
        )
        // The model meets BOTH forms, so the instruction has to name both — and
        // say the route address cannot be read with, or it will try.
        assertTrue(
            "must name the runtime route address form",
            text.contains(ConversationIdProtocol.RUNTIME_ADDRESS_PREFIX),
        )
        assertTrue(
            "must say the route address is one-way / not readable",
            text.contains("one-way"),
        )
        assertTrue(
            "must name the message prefix too, since query results carry it",
            text.contains(ConversationIdProtocol.MESSAGE_PREFIX),
        )
    }
}
