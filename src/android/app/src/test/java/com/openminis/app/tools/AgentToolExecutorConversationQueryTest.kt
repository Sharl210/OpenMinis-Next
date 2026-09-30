package com.openminis.app.tools

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files.createTempDirectory
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-conversation-id-query] `conversation_query` at the executor, over a
 * real [ConversationTranscriptSource] stand-in for the live chat store.
 *
 * Requirement (request.md:136): every conversation must be able to be read by
 * another one that shares no ancestry with it, given only its ID — and the read
 * must be progressive rather than one unbounded dump.
 *
 * These assert on the tool's actual JSON output. What they pin:
 *
 *  1. a read by `minis-conv-` id returns that conversation's messages;
 *  2. a read reached by a cursor returns the NEXT page, and the last page says
 *     so by reporting a null cursor;
 *  3. `query` searches instead of paging;
 *  4. a runtime ROUTE address (`openminis-conv:<sha256>`) is refused with
 *     guidance, instead of silently querying a non-existent session and
 *     reporting "that conversation is empty";
 *  5. no source configured ⇒ an explicit failure, never an empty transcript.
 *
 * Boundary, stated rather than implied: the `ChatRepository` binding itself
 * (`ChatRepositoryConversationSource`) is not covered here — it is a thin
 * delegation to `loadMessagePage` / `searchMessages`, which have their own tests
 * and a live CLI consumer. What is covered is every decision the tool makes.
 */
class AgentToolExecutorConversationQueryTest {

    private class TestContext(private val directory: File) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = directory
    }

    /** A conversation held in memory, paged and searched the way the store is. */
    private class FakeSource(
        private val rows: List<ConversationMessage>,
        private val label: String = "conv",
    ) : ConversationTranscriptSource {
        var lastPageOffset = -1
        var lastPageLimit = -1
        var lastSearchKeyword: String? = null

        override suspend fun count(sessionId: String): Int = rows.size

        override suspend fun page(
            sessionId: String,
            offset: Int,
            limit: Int,
            maxChars: Int,
        ): List<ConversationMessage> {
            lastPageOffset = offset
            lastPageLimit = limit
            return rows.drop(offset).take(limit).map {
                it.copy(text = it.text.take(maxChars), truncated = it.text.length > maxChars)
            }
        }

        override suspend fun search(
            sessionId: String,
            keyword: String,
            limit: Int,
        ): List<ConversationMessage> {
            lastSearchKeyword = keyword
            return rows.filter { it.text.contains(keyword, ignoreCase = true) }
                .take(limit)
                .map { it.copy(text = it.text.take(80), truncated = true) }
        }

        fun unusedLabel(): String = label
    }

    private fun message(index: Int): ConversationMessage = ConversationMessage(
        messageId = "message-$index",
        role = if (index % 2 == 0) "user" else "assistant",
        createdAtMillis = 1_000L + index,
        text = "line $index",
    )

    private val raw = "6f1c0a5e-3b9d-4a7c-8e21-0d5f9a4b7c33"

    private fun executor(source: ConversationTranscriptSource?): AgentToolExecutor {
        val dir = createTempDirectory("conversation-query").toFile()
        return AgentToolExecutor(TestContext(dir), conversationSource = source)
    }

    private suspend fun run(
        executor: AgentToolExecutor,
        args: String,
    ): JSONObject {
        val result = executor.execute(AgentToolExecutor.CONVERSATION_QUERY, args, "actor")
        assertNotNull("the executor owns ${AgentToolExecutor.CONVERSATION_QUERY}", result)
        return JSONObject(requireNotNull(result).output)
    }

    @Test
    fun `a conversation is readable by its prefixed id`() = runBlocking {
        val source = FakeSource((0 until 5).map(::message))
        val json = run(
            executor(source),
            """{"tool_title":"Read conversation","conversation_id":"minis-conv-$raw","limit":2}""",
        )

        assertTrue(json.getBoolean("ok"))
        assertEquals("minis-conv-$raw", json.getString("conversation_id"))
        assertEquals(5, json.getInt("total_matching"))
        assertEquals(2, json.getInt("returned"))
        assertEquals(0, source.lastPageOffset)
        assertEquals(2, source.lastPageLimit)
        assertEquals("line 0", json.getJSONArray("messages").getJSONObject(0).getString("text"))
        assertEquals(
            "minis-msg-message-0",
            json.getJSONArray("messages").getJSONObject(0).getString("message_id"),
        )
    }

    @Test
    fun `a bare id is accepted too`() = runBlocking {
        val json = run(
            executor(FakeSource(listOf(message(0)))),
            """{"tool_title":"Read","conversation_id":"$raw"}""",
        )
        assertTrue(json.getBoolean("ok"))
        assertEquals("minis-conv-$raw", json.getString("conversation_id"))
    }

    /**
     * Progressive disclosure: the cursor from one call must fetch the NEXT page,
     * and the final page must say so rather than implying more is coming.
     */
    @Test
    fun `paging advances through the cursor and ends with a null cursor`() = runBlocking {
        val source = FakeSource((0 until 4).map(::message))
        val executor = executor(source)

        val page1 = run(
            executor,
            """{"tool_title":"Read","conversation_id":"minis-conv-$raw","limit":2}""",
        )
        assertEquals(2, page1.getInt("returned"))
        val cursor = page1.getString("next_cursor")
        assertNotNull(cursor)

        val page2 = run(
            executor,
            """{"tool_title":"Read","conversation_id":"minis-conv-$raw","limit":2,"cursor":"$cursor"}""",
        )
        assertEquals("the second page must resume where the first stopped", 2, source.lastPageOffset)
        assertEquals("line 2", page2.getJSONArray("messages").getJSONObject(0).getString("text"))
        // Four rows, two pages of two: this page exhausts the conversation. The key is
        // always serialised (with a JSON null value when there is nothing left), so the
        // claim to check is the VALUE, not the presence of the key.
        assertTrue("an exhausted read must not promise another page", page2.isNull("next_cursor"))
    }

    @Test
    fun `query searches instead of paging`() = runBlocking {
        val source = FakeSource(
            listOf(
                ConversationMessage("a", "user", 1L, "the build broke"),
                ConversationMessage("b", "assistant", 2L, "unrelated"),
                ConversationMessage("c", "user", 3L, "build again"),
            ),
        )
        val json = run(
            executor(source),
            """{"tool_title":"Search","conversation_id":"minis-conv-$raw","query":"build"}""",
        )

        assertEquals("build", source.lastSearchKeyword)
        assertEquals("search", json.getString("mode"))
        assertEquals(2, json.getInt("returned"))
    }

    /**
     * The refusal this tool exists to get right. `openminis-conv:<sha256>` is a
     * one-way routing address; querying the store with it finds no session, so
     * the honest failure mode is an error naming the right id — NOT an empty
     * "ok" result, which a model would read as "that conversation has no
     * messages".
     */
    @Test
    fun `a runtime route address is refused with guidance rather than read as empty`() = runBlocking {
        val address = com.openminis.app.feature.runtime.RuntimeConversationAddress
            .fromStableSessionId(raw).value
        val json = run(
            executor(FakeSource(emptyList())),
            """{"tool_title":"Read","conversation_id":"$address"}""",
        )

        assertFalse(json.getBoolean("ok"))
        assertEquals("ADDRESS_NOT_QUERYABLE", json.getJSONObject("error").getString("code"))
        val message = json.getJSONObject("error").getString("message")
        assertTrue("must name the id form to use instead: $message", message.contains("minis-conv-"))
        assertTrue("must say why: $message", message.contains("one-way") || message.contains("routing address"))
        assertFalse("must not report success with no messages", json.has("messages"))
    }

    @Test
    fun `a missing conversation id is an argument error`() = runBlocking {
        val json = run(executor(FakeSource(emptyList())), """{"tool_title":"Read"}""")
        assertFalse(json.getBoolean("ok"))
        assertEquals("INVALID_ARGUMENTS", json.getJSONObject("error").getString("code"))
    }

    @Test
    fun `an out-of-range page size or budget is rejected by name`() = runBlocking {
        val huge = run(
            executor(FakeSource(emptyList())),
            """{"tool_title":"Read","conversation_id":"minis-conv-$raw","limit":100000}""",
        )
        assertEquals("PAGE_LIMIT_EXCEEDED", huge.getJSONObject("error").getString("code"))

        val cheap = run(
            executor(FakeSource(emptyList())),
            """{"tool_title":"Read","conversation_id":"minis-conv-$raw","result_budget_chars":1000000}""",
        )
        assertEquals("RESULT_BUDGET_EXCEEDED", cheap.getJSONObject("error").getString("code"))
    }

    @Test
    fun `a malformed cursor is rejected instead of silently restarting`() = runBlocking {
        val json = run(
            executor(FakeSource(listOf(message(0)))),
            """{"tool_title":"Read","conversation_id":"minis-conv-$raw","cursor":"not-a-cursor"}""",
        )
        assertFalse(json.getBoolean("ok"))
        assertEquals("INVALID_CURSOR", json.getJSONObject("error").getString("code"))
    }

    /**
     * A missing read port must be a named failure. An empty transcript here would
     * be indistinguishable from a real empty conversation — the one answer this
     * tool must never invent.
     */
    @Test
    fun `no source configured fails explicitly instead of reporting an empty conversation`() = runBlocking {
        val json = run(
            executor(null),
            """{"tool_title":"Read","conversation_id":"minis-conv-$raw"}""",
        )
        assertFalse(json.getBoolean("ok"))
        assertEquals("UNAVAILABLE", json.getJSONObject("error").getString("code"))
        assertFalse(json.has("messages"))
    }
}
