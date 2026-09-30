package com.openminis.app.ui.chat

import com.openminis.app.data.model.MessagePartsCodec
import com.openminis.app.data.model.MessageProvenance
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-r37-system-row] R37 splits the transcript into three app-layer
 * classes — human / assistant / SYSTEM — and the wire protocol only has two
 * roles. The app therefore synthesises some rows on the USER side (the
 * `<system-reminder>` re-entries written by `resume()` and by the
 * delegated-child abnormal-end report), and those must NOT read back to the
 * person as their own bubble: they belong to the middle class, drawn as a
 * stand-alone neutral row with the injected text behind a tap.
 *
 * These tests pin the dispatch at the row builder, which is where the choice is
 * actually made. The data path is deliberately untouched and is pinned too: the
 * row is still a `user` role row, still carries its injected text verbatim, and
 * still counts as NO human turn — the retry / delete / rewind anchors and the
 * four-button up/down navigation both key off that.
 */
class SystemRowDispatchTest {

    private val reminder =
        "<system-reminder>The user stopped the previous response but now wants to continue. " +
            "Pick up exactly where you left off.</system-reminder>"

    private fun textPart(value: String) = """{"type":"text","value":${JSONObject.quote(value)}}"""

    private fun userMessage(
        id: String,
        content: String,
        provenance: MessageProvenance,
        injected: String? = null,
    ) = ChatMessage(
        id = id,
        role = "user",
        content = content,
        provenance = provenance,
        injectedSystemText = injected,
    )

    private fun infoBlock(id: String = "sysinfo_1", content: String = "12 messages compacted") =
        AssistantBlock(
            id = id,
            kind = "info",
            content = content,
            toolName = "compact",
            toolArgs = "the summary payload",
        )

    // ── the human class must be untouched ────────────────────────────────

    @Test
    fun `a manually sent turn still renders as a user bubble`() {
        val rows = buildFlatChatItems(
            listOf(userMessage("u1", "hello", MessageProvenance.MANUAL_USER)),
        )
        assertEquals(1, rows.size)
        assertTrue(
            "a human turn must stay a bubble — this is the regression the fix must not cause",
            rows.single() is FlatChatItem.UserBubble,
        )
    }

    @Test
    fun `a legacy row with no provenance marker stays a user bubble`() {
        // Rows written before provenance existed carry UNKNOWN and ARE the
        // human's own text, so the classification must be an allow-list of
        // system provenances, never "anything that is not MANUAL_USER".
        val rows = buildFlatChatItems(
            listOf(userMessage("u-legacy", "typed before the marker existed", MessageProvenance.UNKNOWN)),
        )
        assertTrue(rows.single() is FlatChatItem.UserBubble)
    }

    // ── the system class: neutral row, never a bubble ────────────────────

    @Test
    fun `a tool injection row renders as a neutral system row instead of a user bubble`() {
        val rows = buildFlatChatItems(
            listOf(userMessage("sys1", "", MessageProvenance.TOOL_INJECTION, injected = reminder)),
        )
        val row = rows.single()
        assertTrue("injected row must be the neutral system row", row is FlatChatItem.SystemRow)
        assertFalse("injected row must never come back as the human's bubble", row is FlatChatItem.UserBubble)
        assertEquals(reminder, (row as FlatChatItem.SystemRow).injectedText)
        assertTrue("a payload-bearing system row must be openable", row.canExpand)
    }

    @Test
    fun `an injected row gets no assistant header and no assistant attribution`() {
        val rows = buildFlatChatItems(
            listOf(
                ChatMessage(id = "a0", role = "assistant", content = "earlier reply"),
                userMessage("sys1", "", MessageProvenance.TOOL_INJECTION, injected = reminder),
            ),
        )
        assertTrue(rows.any { it is FlatChatItem.SystemRow })
        assertTrue(
            "a system row stands on its own — it is not a new speaker turn",
            rows.none { it is FlatChatItem.AssistantHeader && it.messageId == "sys1" },
        )
    }

    @Test
    fun `a system card provenance on a user row is neutral too`() {
        val rows = buildFlatChatItems(
            listOf(userMessage("sys2", "", MessageProvenance.SYSTEM_CARD, injected = reminder)),
        )
        assertTrue(rows.single() is FlatChatItem.SystemRow)
    }

    @Test
    fun `a system row with no payload is still neutral, it just cannot be opened`() {
        val rows = buildFlatChatItems(
            listOf(userMessage("sys3", "", MessageProvenance.TOOL_INJECTION)),
        )
        val row = rows.single()
        assertTrue(row is FlatChatItem.SystemRow)
        assertEquals("", (row as FlatChatItem.SystemRow).injectedText)
        assertFalse("nothing to show ⇒ no tap-through", row.canExpand)
    }

    // ── the existing system path (role = "system") must not be disturbed ──

    @Test
    fun `a persisted system card row still goes down the existing system path`() {
        val rows = buildFlatChatItems(
            listOf(
                ChatMessage(
                    id = "s1",
                    role = "system",
                    content = "",
                    toolBlocks = listOf(infoBlock()),
                    provenance = MessageProvenance.SYSTEM_CARD,
                ),
            ),
        )
        val row = rows.single()
        assertTrue("role=system keeps rendering as an info divider", row is FlatChatItem.AssistantInfo)
        assertEquals("info", row.contentType)
        assertTrue(rows.none { it is FlatChatItem.SystemRow })
    }

    // ── the assistant class must not be disturbed ────────────────────────

    @Test
    fun `assistant text still classifies as assistant content`() {
        val rows = buildFlatChatItems(
            listOf(
                ChatMessage(
                    id = "a1",
                    role = "assistant",
                    content = "answer",
                    toolBlocks = listOf(AssistantBlock(id = "text_a1_0", kind = "text", content = "answer")),
                ),
            ),
        )
        assertTrue(
            "assistant prose must still reach the assistant render path",
            rows.any { it is FlatChatItem.AssistantMarkdownBlock },
        )
        assertTrue(rows.none { it is FlatChatItem.SystemRow })
    }

    // ── expandability is a pure predicate on the payload ─────────────────

    @Test
    fun `expandability follows the payload, not the provenance`() {
        assertTrue(FlatChatItem.SystemRow("m1", reminder).canExpand)
        assertFalse(FlatChatItem.SystemRow("m2", "").canExpand)
        assertFalse("whitespace-only payload is nothing to show", FlatChatItem.SystemRow("m3", "   \n").canExpand)
    }

    @Test
    fun `a system row keys off its message so LazyColumn can anchor it`() {
        assertEquals("sysrow:m1", FlatChatItem.SystemRow("m1", reminder).key)
        assertEquals("sysrow", FlatChatItem.SystemRow("m1", reminder).contentType)
        val rows = buildFlatChatItems(
            listOf(
                userMessage("u1", "hi", MessageProvenance.MANUAL_USER),
                userMessage("sys1", "", MessageProvenance.TOOL_INJECTION, injected = reminder),
                ChatMessage(id = "a1", role = "assistant", content = "answer"),
            ),
        )
        assertEquals("duplicate keys crash LazyColumn", rows.size, rows.map { it.key }.toSet().size)
    }

    // ── the injected row must not become a human turn ────────────────────

    @Test
    fun `an injected row is not a human turn and not a navigation anchor`() {
        val injected = userMessage("sys1", "", MessageProvenance.TOOL_INJECTION, injected = reminder)
        assertFalse("the four-button up/down walk must skip it", injected.isManualHumanUser())
        assertFalse(injected.rendersAsUserBubble())
        assertFalse(
            "retry / delete / rewind count human turns off `content`; the injected text must not leak in",
            injected.countsAsHumanTurn(),
        )
    }

    @Test
    fun `two bubbles separated by an injected row do not get the back-to-back gap`() {
        // The extra gap exists because two adjacent BUBBLES have no assistant
        // header between them. A neutral system row in between already separates
        // them, so the gap would read as a stray double margin.
        val bubbles = buildFlatChatItems(
            listOf(
                userMessage("u1", "one", MessageProvenance.MANUAL_USER),
                userMessage("sys1", "", MessageProvenance.TOOL_INJECTION, injected = reminder),
                userMessage("u2", "two", MessageProvenance.MANUAL_USER),
            ),
        ).filterIsInstance<FlatChatItem.UserBubble>()
        assertEquals(listOf(false, false), bubbles.map { it.precededByUser })
    }

    @Test
    fun `two adjacent bubbles still get the back-to-back gap`() {
        val bubbles = buildFlatChatItems(
            listOf(
                userMessage("u1", "one", MessageProvenance.MANUAL_USER),
                userMessage("u2", "two", MessageProvenance.MANUAL_USER),
            ),
        ).filterIsInstance<FlatChatItem.UserBubble>()
        assertEquals(listOf(false, true), bubbles.map { it.precededByUser })
    }

    // ── the DB → UI extraction, on the real persisted shape ──────────────

    @Test
    fun `the injected text is read back verbatim from the persisted row`() {
        // Exactly what appendMessage stores for an injected row.
        val persisted = MessagePartsCodec.withProvenance(
            """[${textPart(reminder)}]""",
            MessageProvenance.TOOL_INJECTION,
        )
        assertEquals(reminder, verbatimTextPartsOf(persisted))
        assertEquals(MessageProvenance.TOOL_INJECTION, MessagePartsCodec.provenanceOf(persisted))
        assertFalse(
            "the reminder row is not a human turn on the DB side either — the two sides must agree",
            MessagePartsCodec.hasHumanTurnContent(persisted),
        )
    }

    @Test
    fun `a tool result only row has nothing to display`() {
        // Such a row is still dropped by the transcript filter — this fix must
        // not resurrect it (the tool output already renders inside the tool
        // pill it belongs to).
        val toolResultOnly =
            """[{"type":"toolResult","value":{"toolUseId":"t1","name":"sh","output":"x","success":true}}]"""
        assertNull(verbatimTextPartsOf(toolResultOnly))
    }

    @Test
    fun `malformed parts answer null instead of throwing`() {
        assertNull(verbatimTextPartsOf("not json"))
        assertNull(verbatimTextPartsOf(""))
    }

    @Test
    fun `a multi-part injected row keeps every text part in order`() {
        val second = "<system-reminder>Second nudge.</system-reminder>"
        val persisted = """[${textPart(reminder)},${textPart(second)}]"""
        assertEquals("$reminder\n$second", verbatimTextPartsOf(persisted))
    }
}
