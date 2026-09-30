package com.openminis.app.tools

import com.openminis.app.feature.runtime.RuntimeCommunicationCursor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
