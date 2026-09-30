package com.openminis.app.ui.chat

import com.openminis.app.data.repository.canonicalRuntimeSessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-topology-run-root-keys] Runtime node ids stop being conversation ids
 * after a session re-runs.
 *
 * `RuntimeSessionCoordinator.createRoot` roots every run after the first at
 * `"<sessionId>#run-<n>"`, while chat messages and token rows stay under the plain
 * conversation id. Three lookups in the topology route were keyed by node id, so on
 * every run after the first they all missed — cheap to hit, since "first run
 * completed, then the user sends another message" is the ordinary path:
 *
 *  - the node body fell back to `currentSummary`, i.e. the LATEST assistant reply —
 *    the exact behaviour request.md:37 replaced with the first message;
 *  - `aggregateAgentTopologyTokens` filters `row.sessionId in sessionIds`, so the
 *    conversation's rows were discarded and the root node rendered **0 tokens**;
 *  - session titles came back null.
 *
 * Found by an independent audit of my own first-message change, which had fixed the
 * first run only. The fix re-keys per-session data onto node ids
 * ([remapRuntimeNodeKeys]) using the existing `canonicalRuntimeSessionId` helper
 * rather than a second copy of the `#run-` rule.
 *
 * COVERAGE LIMIT, stated plainly: the JVM can pin the re-keying helper and the graph
 * builder. It cannot reach the Compose route that fetches and wires the maps, so
 * "the route really calls this" rests on the call site reading
 * `canonicalRuntimeSessionId`/`remapRuntimeNodeKeys` — not on an assertion here.
 */
class AgentTopologyRunRootKeysTest {

    // ---- the re-keying helper ---------------------------------------------------

    @Test
    fun `a repeated run's data is re-keyed onto the run-root node id`() {
        // The core case: one conversation, re-run, root node carries the suffix.
        val remapped = remapRuntimeNodeKeys(
            nodeIds = setOf("session#run-1"),
            valuesBySessionId = mapOf("session" to "第一条消息"),
        )
        assertEquals(mapOf("session#run-1" to "第一条消息"), remapped)
    }

    @Test
    fun `a first-run root id passes through unchanged`() {
        val remapped = remapRuntimeNodeKeys(
            nodeIds = setOf("session"),
            valuesBySessionId = mapOf("session" to "hello"),
        )
        assertEquals(mapOf("session" to "hello"), remapped)
    }

    @Test
    fun `delegated children keep their own ids because they are real sessions`() {
        // Children are created with createSession, so their node id IS their
        // conversation id; the folding must not disturb them.
        val remapped = remapRuntimeNodeKeys(
            nodeIds = setOf("child-1", "child-2"),
            valuesBySessionId = mapOf("child-1" to "a", "child-2" to "b"),
        )
        assertEquals(mapOf("child-1" to "a", "child-2" to "b"), remapped)
    }

    @Test
    fun `a conversation and its re-run root both receive the same data`() {
        // Both nodes stand for the same conversation, so both must show its first
        // message (and, for tokens, its total) rather than one of them going blank.
        val remapped = remapRuntimeNodeKeys(
            nodeIds = setOf("session", "session#run-1"),
            valuesBySessionId = mapOf("session" to "第一条消息"),
        )
        assertEquals("第一条消息", remapped["session"])
        assertEquals("第一条消息", remapped["session#run-1"])
    }

    @Test
    fun `a node with no data is absent rather than mapped to a blank value`() {
        // "unknown" must stay distinguishable from "empty": the graph builder falls
        // back to note/summary for an absent key, but would render a blank body for
        // an empty string.
        val remapped = remapRuntimeNodeKeys(
            nodeIds = setOf("session", "session#run-1"),
            valuesBySessionId = emptyMap<String, String>(),
        )
        assertTrue(remapped.isEmpty())
        assertNull(remapped["session#run-1"])
    }

    @Test
    fun `the helper does not invent nodes that were not asked for`() {
        val remapped = remapRuntimeNodeKeys(
            nodeIds = setOf("session#run-1"),
            valuesBySessionId = mapOf("session" to "x", "unrelated" to "y"),
        )
        assertEquals(setOf("session#run-1"), remapped.keys)
    }

    @Test
    fun `only a genuine numeric run marker is folded`() {
        // canonicalRuntimeSessionId deliberately strips the suffix ONLY when it really
        // is the runtime tree's numeric run marker, so an ordinary id that happens to
        // contain the text is left alone. The topology lookup's correctness leans on
        // this, so it is pinned here too.
        //
        // My first draft of this test asserted "abc#run-7" came back unchanged — wrong,
        // and it contradicted the helper's own KDoc: a numeric suffix IS folded, for
        // any prefix. Only a NON-numeric suffix survives.
        assertEquals("abc", canonicalRuntimeSessionId("abc#run-7"))
        assertEquals("session", canonicalRuntimeSessionId("session#run-2"))
        assertEquals("session#run-x", canonicalRuntimeSessionId("session#run-x"))
        assertEquals("session#run-", canonicalRuntimeSessionId("session#run-"))
        assertEquals("session", canonicalRuntimeSessionId("session"))
        // A leading marker is not a suffix, so nothing is stripped.
        assertEquals("#run-5", canonicalRuntimeSessionId("#run-5"))
    }

    // ---- the graph builder, given node-keyed data -------------------------------

    private fun graph(
        runtimeJson: String,
        firstMessages: Map<String, String> = emptyMap(),
        tokens: Map<String, Long> = emptyMap(),
        currentTokens: Long = 0L,
    ) = agentTopologyGraphFromRuntimeJson(
        runtimeJson = runtimeJson,
        sessionId = "session",
        sessionTitle = "Conversation",
        currentSummary = "最新一条回复",
        currentTokens = currentTokens,
        tokenTotalsBySession = tokens,
        firstMessagesBySession = firstMessages,
    )

    @Test
    fun `a re-run root shows the first message instead of the latest reply`() {
        // THE regression: before the fix the node id `session#run-1` missed the
        // first-message map, so the body fell back to `currentSummary` (the latest
        // reply) — the very thing the requirement replaced.
        val g = graph(
            runtimeJson = """
                {"nodes":[
                  {"id":"session#run-1","parentId":null,"rootId":"session#run-1","depth":0,"status":"RUNNING","createdAtMillis":10,"model":{"model":"main"}}
                ]}
            """.trimIndent(),
            firstMessages = mapOf("session#run-1" to "修一下登录页的报错"),
        )
        assertEquals("修一下登录页的报错", g.nodes.first { it.id == "session#run-1" }.summary)
        assertFalse(
            "the latest reply must not win over the first message",
            g.nodes.first { it.id == "session#run-1" }.summary == "最新一条回复",
        )
    }

    @Test
    fun `a re-run root reports its real token total instead of zero`() {
        // Same miss, token side: the root previously rendered 0 because the
        // conversation's rows were filtered out by the node-id key set.
        val g = graph(
            runtimeJson = """
                {"nodes":[
                  {"id":"session#run-1","parentId":null,"rootId":"session#run-1","depth":0,"status":"RUNNING","createdAtMillis":10,"model":{"model":"main"}}
                ]}
            """.trimIndent(),
            tokens = mapOf("session#run-1" to 4_321L),
            currentTokens = 4_321L,
        )
        assertEquals(4_321L, g.nodes.first { it.id == "session#run-1" }.tokens)
    }

    @Test
    fun `without first-message data the live summary still fills the body`() {
        // The fallback stays available for a session whose first turn has not been
        // persisted yet — the fix must not turn that into a blank node.
        val g = graph(
            runtimeJson = """
                {"nodes":[
                  {"id":"session#run-1","parentId":null,"rootId":"session#run-1","depth":0,"status":"RUNNING","createdAtMillis":10,"model":{"model":"main","note":"root note"}}
                ]}
            """.trimIndent(),
        )
        assertEquals("最新一条回复", g.nodes.first { it.id == "session#run-1" }.summary)
    }
}
