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
        /**
         * Row indices the store drops while parsing — a `parts_json` that extracts
         * to blank text, or a LIKE hit on tool-call metadata that yields no
         * snippet. They still occupy a slot in the window, which is exactly what
         * makes "how many items came back" a different number from "how far the
         * scan got".
         */
        private val hidden: Set<Int> = emptySet(),
    ) : ConversationTranscriptSource {
        var lastPageOffset = -1
        var lastPageLimit = -1
        var lastSearchOffset = -1
        var lastSearchKeyword: String? = null

        override suspend fun count(sessionId: String): Int = rows.size

        // This fake models the store's WINDOW semantics, not just its items: it
        // reports which window row each item came from, how many rows were
        // examined, and whether the scan filled the window. A fake that returned a
        // bare list would let the tool compute a cursor from `items.size` and still
        // pass — which is exactly the bug these tests exist to catch, so the resume
        // bookkeeping is part of the contract being faked, not test scaffolding.
        override suspend fun page(
            sessionId: String,
            offset: Int,
            limit: Int,
            maxChars: Int,
        ): ConversationSlice {
            lastPageOffset = offset
            lastPageLimit = limit
            val window = rows.drop(offset).take(limit)
            val items = mutableListOf<ConversationMessage>()
            val indices = mutableListOf<Int>()
            window.forEachIndexed { i, row ->
                if (offset + i in hidden) return@forEachIndexed
                items += row.copy(
                    text = row.text.take(maxChars),
                    truncated = row.text.length > maxChars,
                )
                indices += i
            }
            return ConversationSlice(
                items = items,
                resumeOffsets = indices.map { offset + it },
                resumeAfter = offset + window.size,
                moreMayRemain = window.size >= limit,
            )
        }

        override suspend fun search(
            sessionId: String,
            keyword: String,
            offset: Int,
            limit: Int,
        ): ConversationSlice {
            lastSearchKeyword = keyword
            lastSearchOffset = offset
            // The whole remainder is one window here (a fake has no reason to
            // over-fetch), so "more may remain" is exactly "stopped before the end
            // of everything it could see".
            val scanned = rows.drop(offset)
            val hits = mutableListOf<ConversationMessage>()
            val indices = mutableListOf<Int>()
            var consumed = scanned.size
            for ((i, r) in scanned.withIndex()) {
                if (offset + i in hidden) continue
                if (!r.text.contains(keyword, ignoreCase = true)) continue
                hits += r.copy(text = r.text.take(80), truncated = true)
                indices += i
                if (hits.size >= limit) {
                    consumed = i + 1
                    break
                }
            }
            return ConversationSlice(
                items = hits,
                resumeOffsets = indices.map { offset + it },
                resumeAfter = offset + consumed,
                moreMayRemain = consumed < scanned.size,
            )
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

    /**
     * The R43 defect, on the branch that had no offset to pass.
     *
     * Scroll back through `query` until the cursor runs out, and read every
     * `message_id` that comes back. The old search branch called the store with no
     * offset at all and then built the next cursor from `offset + kept.size` — a
     * number that grew while the query it described stayed at the top of the result
     * set. So each page repeated the previous one and the cursor never became null:
     * a caller following it reads the same rows forever and never reaches the hits
     * past the first page.
     *
     * The assertion is the whole walk, so it fails on a repeat, on a gap, and on a
     * walk that does not end.
     */
    @Test
    fun `search paging walks every hit exactly once and then stops`() = runBlocking {
        val executor = executor(FakeSource((0 until 5).map(::message)))
        val seen = mutableListOf<String>()

        var cursor: String? = null
        var rounds = 0
        while (true) {
            rounds++
            assertTrue("paging must terminate; round $rounds already means it does not", rounds <= 6)
            var args = "{\"tool_title\":\"Read\",\"conversation_id\":\"minis-conv-$raw\"," +
                "\"query\":\"line\",\"limit\":2"
            if (cursor != null) args += ",\"cursor\":\"$cursor\""
            args += "}"

            val json = run(executor, args)
            assertTrue(json.getBoolean("ok"))
            val messages = json.getJSONArray("messages")
            for (i in 0 until messages.length()) {
                seen += messages.getJSONObject(i).getString("message_id")
            }
            if (json.isNull("next_cursor")) break
            cursor = json.getString("next_cursor")
        }

        assertEquals(
            "every hit exactly once, in row order",
            (0 until 5).map { "minis-msg-message-$it" },
            seen,
        )
    }

    /**
     * A search page the budget shortened still has rows behind it.
     *
     * The old predicate was `kept.size == limit`, so trimming a page made the tool
     * answer "there is nothing more" — the exact claim `resultJson`'s contract
     * forbids while messages are being held back. Nothing about a short page implies
     * an empty result set; only the scan position does.
     */
    @Test
    fun `a search page the budget trimmed must not claim there is nothing left`() = runBlocking {
        val long = "needle " + "x".repeat(600)
        val source = FakeSource((0 until 3).map { message(it).copy(text = long) })

        val json = run(
            executor(source),
            "{\"tool_title\":\"Search\",\"conversation_id\":\"minis-conv-$raw\"," +
                "\"query\":\"needle\",\"limit\":20,\"result_budget_chars\":40}",
        )

        assertEquals(1, json.getInt("returned"))
        assertFalse(
            "the budget dropped two hits, so a cursor must say more remains",
            json.isNull("next_cursor"),
        )
    }

    /**
     * A window whose rows are all dropped during the parse returns nothing — but the
     * scan moved, and the next cursor has to reflect that.
     *
     * Deriving it from the delivered count yields `offset + 0`, i.e. the same token
     * the caller just used: every subsequent call asks the identical question and
     * gets the identical empty page, forever. The window examined three rows here,
     * so the honest resume point is three rows in.
     */
    @Test
    fun `an all-hidden window advances the cursor instead of looping on it`() = runBlocking {
        val executor = executor(FakeSource((0 until 6).map(::message), hidden = setOf(0, 1, 2)))

        val page1 = run(
            executor,
            """{"tool_title":"Read","conversation_id":"minis-conv-$raw","limit":3}""",
        )
        assertEquals(0, page1.getInt("returned"))
        assertEquals(
            "three rows were examined, so the next window starts after them — not at the same place",
            "m3",
            page1.getString("next_cursor"),
        )

        val page2 = run(
            executor,
            """{"tool_title":"Read","conversation_id":"minis-conv-$raw","limit":3,"cursor":"m3"}""",
        )
        assertEquals(3, page2.getInt("returned"))
        assertTrue("the second window is the end of the conversation", page2.isNull("next_cursor"))
    }

    /**
     * The same walk, with rows dropped in the MIDDLE of windows rather than in one
     * leading block.
     *
     * Here the two index spaces diverge by one row instead of by a whole page, which
     * is what makes the mistake survivable-looking: `offset + delivered` still lands
     * on a real row, just the wrong one, so the walk keeps making progress while
     * quietly re-sending rows it already sent. Budgeted to one item per page on
     * purpose, so every page is trimmed and every resume point is exercised.
     */
    @Test
    fun `paging with hidden rows repeats nothing, skips nothing and terminates`() = runBlocking {
        val executor = executor(FakeSource((0 until 6).map(::message), hidden = setOf(1, 4)))
        val seen = mutableListOf<String>()

        var cursor: String? = null
        var rounds = 0
        while (true) {
            rounds++
            assertTrue("paging must terminate; round $rounds", rounds <= 8)
            var args = "{\"tool_title\":\"Read\",\"conversation_id\":\"minis-conv-$raw\"," +
                "\"limit\":3,\"result_budget_chars\":6"
            if (cursor != null) args += ",\"cursor\":\"$cursor\""
            args += "}"

            val json = run(executor, args)
            val messages = json.getJSONArray("messages")
            for (i in 0 until messages.length()) {
                seen += messages.getJSONObject(i).getString("message_id").removePrefix("minis-msg-")
            }
            if (json.isNull("next_cursor")) break
            cursor = json.getString("next_cursor")
        }

        assertEquals(
            "rows 1 and 4 are the ones the store drops; the rest must arrive once each, in order",
            listOf("message-0", "message-2", "message-3", "message-5"),
            seen,
        )
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
