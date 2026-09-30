package com.openminis.app.tools

import com.openminis.app.feature.runtime.RuntimeCommunicationCursor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-conversation-id-query] The 截流 ("throttle") half of request.md:136,
 * asserted on the JSON the tool's pure layer produces:
 *
 *   「如果一次性把所有的对话内容都塞回给这个工具调用里面那么模型可能会一下把上下文
 *     搞炸掉，所以你需要做一个截流…你要让它进行一个**渐进式的披露**，就是模型自己
 *     去一次一次去搜索」
 *
 * A bounded page plus a `next_cursor` for everything left over is the executable
 * form of "read a bit at a time"; the failure this pins against is a result that
 * quietly reports "that's all" while holding back messages.
 */
class ConversationQueryPolicyTest {

    private fun message(id: String, text: String) =
        ConversationMessage(messageId = id, role = "user", createdAtMillis = 1L, text = text)

    @Test
    fun `a page larger than the budget is clipped and trimmed to fit`() {
        val messages = listOf(
            message("a", "x".repeat(100)),
            message("b", "y".repeat(100)),
            message("c", "z".repeat(100)),
        )

        val kept = ConversationQueryPolicy.fitToBudget(messages, budgetChars = 250)

        assertEquals(2, kept.size)
        assertTrue(kept.sumOf { it.text.length } <= 250)
    }

    /**
     * A single message bigger than the whole budget still comes back, clipped.
     * Rejecting the page outright (the way the metadata directory does for
     * records) would dead-end exactly the caller this feature exists for: a model
     * asking to read one long message would get an error instead of a bounded
     * part of it, and would have to guess a smaller page size to make progress.
     */
    @Test
    fun `a single oversized message is truncated rather than rejected`() {
        val kept = ConversationQueryPolicy.fitToBudget(
            listOf(message("big", "q".repeat(5_000))),
            budgetChars = 500,
        )

        assertEquals(1, kept.size)
        assertEquals(500, kept[0].text.length)
        assertTrue("the clip must be declared, not silent", kept[0].truncated)
    }

    @Test
    fun `a cursor round-trips through encode and decode`() {
        val decoded = ConversationQueryPolicy.decodeCursor(ConversationQueryPolicy.encodeCursor(40))
        assertEquals(40, decoded.getOrThrow())
    }

    @Test
    fun `no cursor means start at zero and a malformed one is a failure`() {
        assertEquals(0, ConversationQueryPolicy.decodeCursor(null).getOrThrow())
        assertTrue(ConversationQueryPolicy.decodeCursor(RuntimeCommunicationCursor("garbage")).isFailure)
        assertTrue(ConversationQueryPolicy.decodeCursor(RuntimeCommunicationCursor("m-1")).isFailure)
    }

    // ─── Where the cursor is allowed to point ────────────────────────────
    //
    // The cursor names a position in the UNDERLYING scan. Twice now it has been
    // computed instead from what was DELIVERED — `offset + kept.size` — and the two
    // are not the same number whenever a window is shorter than the rows it read
    // (the parse drops blank rows; the budget drops the tail). The first version of
    // that mistake made search return the same page forever; the second made a
    // paging walk step backwards over rows it had already sent.

    private fun slice(
        items: List<ConversationMessage>,
        resumeOffsets: List<Int>,
        resumeAfter: Int,
        moreMayRemain: Boolean,
    ) = ConversationSlice(items, resumeOffsets, resumeAfter, moreMayRemain)

    @Test
    fun `a scan that reached the end with nothing trimmed reports no more`() {
        assertNull(
            ConversationQueryPolicy.nextCursor(
                slice(listOf(message("a", "x")), listOf(7), resumeAfter = 8, moreMayRemain = false),
                deliveredCount = 1,
                offset = 7,
            ),
        )
    }

    @Test
    fun `a window the budget trimmed reports more even at the end of the scan`() {
        val cursor = ConversationQueryPolicy.nextCursor(
            slice(
                listOf(message("a", "x"), message("b", "y")),
                listOf(5, 9),
                resumeAfter = 12,
                moreMayRemain = false,
            ),
            deliveredCount = 1,
            offset = 5,
        )

        assertEquals(
            "the first UNDELIVERED item sits at 9; offset + delivered would say 6 and re-send",
            "m9",
            cursor?.token,
        )
    }

    @Test
    fun `a window that yielded nothing but did examine rows still advances`() {
        assertEquals(
            "m3",
            ConversationQueryPolicy.nextCursor(
                slice(emptyList(), emptyList(), resumeAfter = 3, moreMayRemain = true),
                deliveredCount = 0,
                offset = 0,
            )?.token,
        )
    }

    @Test
    fun `a cursor is never emitted that cannot move the scan forward`() {
        val a = message("a", "x")
        val movable = listOf(
            slice(listOf(a), listOf(4), resumeAfter = 12, moreMayRemain = true),
            slice(listOf(a, a), listOf(4, 6), resumeAfter = 12, moreMayRemain = true),
        )
        for (case in movable) {
            val cursor = ConversationQueryPolicy.nextCursor(case, case.items.size, offset = 4)
            assertTrue("a window with rows left must hand back a cursor", cursor != null)
            assertTrue(
                "${cursor?.token} must land past offset 4 or the caller loops on this page",
                ConversationQueryPolicy.decodeCursor(cursor!!).getOrThrow() > 4,
            )
        }

        // The pathological shape: the source says it is still standing exactly
        // where it began. Following that cursor asks the same question forever, so
        // the only safe answer is to stop and let the caller conclude it is done.
        assertNull(
            ConversationQueryPolicy.nextCursor(
                slice(listOf(a), listOf(4), resumeAfter = 4, moreMayRemain = true),
                deliveredCount = 1,
                offset = 4,
            ),
        )
    }

    /**
     * Page mode can be exact, because `totalRows` and `resumeAfter` are both raw
     * row counts there. Without the bound, a filled window cannot tell "more rows
     * follow" from "that was the last one", and a session whose size divides evenly
     * by the page size costs one extra round trip that returns nothing.
     */
    @Test
    fun `a window that filled but sat exactly on the last row is the end`() {
        val a = message("a", "x")
        val filled = slice(listOf(a), listOf(9), resumeAfter = 10, moreMayRemain = true)

        assertNull(
            ConversationQueryPolicy.nextCursor(filled, deliveredCount = 1, offset = 9, totalRows = 10),
        )
        assertEquals(
            "with a row still beyond the window, the same slice does continue",
            "m10",
            ConversationQueryPolicy.nextCursor(filled, 1, 9, totalRows = 11)?.token,
        )
    }

    /**
     * The result must carry the prefixed id back, because that echo is the
     * evidence that the ID the model read elsewhere is the one this tool accepts.
     */
    @Test
    fun `the result echoes the conversation id in prefixed form`() {
        val raw = "6f1c0a5e-3b9d-4a7c-8e21-0d5f9a4b7c33"
        val json = ConversationQueryPolicy.resultJson(
            conversationIdRaw = raw,
            messages = listOf(message("m1", "hello")),
            totalMatching = 9,
            nextCursor = RuntimeCommunicationCursor("m1"),
            budgetChars = 8_000,
            mode = "page",
        )

        assertTrue(json.getBoolean("ok"))
        assertEquals("minis-conv-$raw", json.getString("conversation_id"))
        assertEquals(9, json.getInt("total_matching"))
        assertEquals(1, json.getInt("returned"))
        assertEquals(5, json.getInt("used_chars"))
        assertEquals("m1", json.getString("next_cursor"))

        val first = json.getJSONArray("messages").getJSONObject(0)
        assertEquals("minis-msg-m1", first.getString("message_id"))
        assertEquals("hello", first.getString("text"))
    }

    /**
     * "Nothing left" must be distinguishable from "cursor not supplied" — a
     * missing cursor reported as `null` is how the model learns to stop.
     */
    @Test
    fun `an exhausted read reports a null cursor so the model knows to stop`() {
        val json = ConversationQueryPolicy.resultJson(
            conversationIdRaw = "abc",
            messages = listOf(message("m1", "only one")),
            totalMatching = 1,
            nextCursor = null,
            budgetChars = 1_000,
            mode = "page",
        )
        // `isNull`, not `assertNull(get(...))`: org.json represents a JSON null with
        // its own NULL sentinel object, so `get` never returns a Java null and
        // assertNull here would be asserting a falsehood about the implementation.
        assertTrue(json.isNull("next_cursor"))
        assertEquals(0, json.getInt("total_matching") - json.getInt("returned"))
    }

    @Test
    fun `an empty conversation returns no messages and no invented cursor`() {
        val json = ConversationQueryPolicy.resultJson(
            conversationIdRaw = "abc",
            messages = emptyList(),
            totalMatching = 0,
            nextCursor = null,
            budgetChars = 1_000,
            mode = "page",
        )
        assertTrue(json.getBoolean("ok"))
        assertEquals(0, json.getJSONArray("messages").length())
        assertFalse(json.has("error"))
    }
}
