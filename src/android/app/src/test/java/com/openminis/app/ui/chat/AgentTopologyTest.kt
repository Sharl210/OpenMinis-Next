package com.openminis.app.ui.chat

import com.openminis.app.data.db.SessionTokenUsageRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTopologyTest {
    @Test
    fun `runtime graph assigns token totals to child nodes`() {
        val totals = aggregateAgentTopologyTokens(
            setOf("session", "child"),
            listOf(
                SessionTokenUsageRow("session", "{\"inputTokens\":10,\"outputTokens\":5}"),
                SessionTokenUsageRow("child", "{\"inputTokens\":20,\"outputTokens\":7}"),
            ),
        )
        val graph = agentTopologyGraphFromRuntimeJson(
            runtimeJson = """{"nodes":[
                {"id":"session","parentId":null,"rootId":"session","depth":0},
                {"id":"child","parentId":"session","rootId":"session","depth":1}
            ]}""",
            sessionId = "session",
            sessionTitle = "Current",
            currentSummary = "",
            currentTokens = totals["session"] ?: 0L,
            tokenTotalsBySession = totals,
        )
        assertEquals(27L, graph.nodes.single { it.id == "child" }.tokens)
    }

    @Test
    fun `layout keeps nodes separate and formats metadata`() {
        val layout = layoutAgentTopology(
            nodes = listOf(
                AgentTopologyNode("root", "Main", "root summary", tokens = 238_400L),
                AgentTopologyNode("child", "Child", "child summary", parentId = "root", tokens = 1_200L),
            ),
            relations = listOf(AgentTopologyRelation("root", "child")),
        )
        assertEquals(2, layout.nodes.size)
        // [T-agent-topology-token-unit-bug] The requirement's example is
        // "238.4K" (request.md:39 "再加个 238.4K/M/B"), not "238K": the decimals
        // are part of the specified shape. This assertion previously pinned the
        // wrong value AND, because both samples were under 100 (unit value),
        // never touched the code path that formatted whole numbers — which is
        // where 200_000 was displayed as "2K".
        assertEquals("238.4K", layout.nodes.first().tokenLabel)
        assertEquals("1.2K", layout.nodes.last().tokenLabel)
        assertTrue(layout.nodes[0].rect.bottom <= layout.nodes[1].rect.top)
        assertTrue(layout.edges.single().points.size >= 2)
    }

    @Test
    fun `duplicate ordered edges collapse while reverse edges remain`() {
        val relations = deduplicateAgentTopologyRelations(
            listOf(
                AgentTopologyRelation("a", "b", "first"),
                AgentTopologyRelation("a", "b", "duplicate"),
                AgentTopologyRelation("b", "a", "reverse"),
            ),
        )
        assertEquals(2, relations.size)
        assertEquals("first", relations.first().label)
        assertEquals("b", relations.last().fromId)
    }

    @Test
    fun `route avoids non endpoint rectangle`() {
        val rects = mapOf(
            "a" to AgentTopologyRect(0f, 0f, 40f, 40f),
            "blocker" to AgentTopologyRect(40f, -10f, 40f, 60f),
            "b" to AgentTopologyRect(100f, 0f, 40f, 40f),
        )
        val edge = routeAgentTopologyEdges(
            listOf(AgentTopologyRelation("a", "b")),
            rects,
            clearance = 2f,
        ).single()
        assertTrue(edge.collisionFree)
        assertTrue(agentTopologyPathIsClear(edge.points, "a", "b", rects, clearance = 2f))
    }

    @Test
    fun `runtime json graph keeps current root and child relation`() {
        val graph = agentTopologyGraphFromRuntimeJson(
            runtimeJson = """
                {"nodes":[
                  {"id":"session","parentId":null,"rootId":"session","depth":0,"status":"RUNNING","createdAtMillis":10,"model":{"model":"main","note":"root"}},
                  {"id":"child","parentId":"session","rootId":"session","depth":1,"status":"SUCCEEDED","createdAtMillis":20,"model":{"model":"worker","note":"child note"}},
                  {"id":"other","parentId":null,"rootId":"other","depth":0,"status":"INACTIVE","createdAtMillis":30,"model":{"model":"other"}}
                ]}
            """.trimIndent(),
            sessionId = "session",
            sessionTitle = "Current session",
            currentSummary = "latest answer",
            currentTokens = 42,
        )
        assertEquals("session", graph.currentNodeId)
        assertEquals(setOf("session", "child"), graph.nodes.map { it.id }.toSet())
        assertEquals(listOf(AgentTopologyRelation("session", "child", "parent")), graph.relations)
        assertEquals("latest answer", graph.nodes.first { it.id == "session" }.summary)
    }

    @Test
    fun `the node body is the conversation's first message, not the latest reply`() {
        // [T-android-topology-first-message] Requirement (request.md:37): "就是这个节点
        // 所对应的对话内容的第一条消息" — 每一个节点.
        //
        // This is the POSITIVE assertion for a behaviour I changed. Without it only
        // the fallback branch below would be covered, so the graph could have quietly
        // kept showing the latest reply (or the model id) forever and every test would
        // still have passed.
        val graph = agentTopologyGraphFromRuntimeJson(
            runtimeJson = """
                {"nodes":[
                  {"id":"session","parentId":null,"rootId":"session","depth":0,"status":"RUNNING","createdAtMillis":10,"model":{"model":"main","note":"root"}},
                  {"id":"child","parentId":"session","rootId":"session","depth":1,"status":"SUCCEEDED","createdAtMillis":20,"model":{"model":"worker","note":"child note"}}
                ]}
            """.trimIndent(),
            sessionId = "session",
            sessionTitle = "Current session",
            currentSummary = "latest answer",
            firstMessagesBySession = mapOf(
                "session" to "修一下登录页的报错",
                "child" to "帮我看看这个崩溃栈",
            ),
        )
        assertEquals("修一下登录页的报错", graph.nodes.first { it.id == "session" }.summary)
        assertEquals("帮我看看这个崩溃栈", graph.nodes.first { it.id == "child" }.summary)
    }

    @Test
    fun `a session with no stored message yet keeps showing live context`() {
        // The first turn can still be streaming, so no message row exists. Falling
        // back to the live summary keeps the node informative instead of blanking it.
        val graph = agentTopologyGraphFromRuntimeJson(
            runtimeJson = """
                {"nodes":[
                  {"id":"session","parentId":null,"rootId":"session","depth":0,"status":"RUNNING","createdAtMillis":10,"model":{"model":"main","note":"root"}}
                ]}
            """.trimIndent(),
            sessionId = "session",
            sessionTitle = "Current session",
            currentSummary = "latest answer",
            firstMessagesBySession = emptyMap(),
        )
        assertEquals("latest answer", graph.nodes.first { it.id == "session" }.summary)
    }

    @Test
    fun `an empty first message is treated as missing rather than blanking the node`() {
        // A stored message can carry only media or tool parts; the extractor returns
        // "" for those, and that must not win over the note/summary fallbacks.
        val graph = agentTopologyGraphFromRuntimeJson(
            // NOTE: the child really must have a parent here. My first draft used
            // `parentId: null`, which makes the node ITSELF the root, so it took the
            // root branch and the test failed for a reason unrelated to what it claims
            // to check — a test that lies about its subject.
            runtimeJson = """
                {"nodes":[
                  {"id":"session","parentId":null,"rootId":"session","depth":0,"status":"RUNNING","createdAtMillis":10,"model":{"model":"main"}},
                  {"id":"child","parentId":"session","rootId":"session","depth":1,"status":"RUNNING","createdAtMillis":20,"model":{"model":"worker","note":"child note"}}
                ]}
            """.trimIndent(),
            sessionId = "session",
            sessionTitle = "Current session",
            currentSummary = "irrelevant for a non-root node",
            firstMessagesBySession = mapOf("child" to "   "),
        )
        assertEquals("child note", graph.nodes.first { it.id == "child" }.summary)
    }

    @Test
    fun `runtime graph falls back to current session when tree has no root`() {
        val graph = agentTopologyGraphFromRuntimeJson(
            runtimeJson = "{}",
            sessionId = "session",
            sessionTitle = "Current session",
            currentSummary = "latest answer",
            currentTokens = 42,
        )
        assertEquals(listOf("session"), graph.nodes.map { it.id })
        assertTrue(graph.relations.isEmpty())
        assertEquals(42L, graph.nodes.single().tokens)
    }

    @Test
    fun `safe export preserves dpi while bounding bitmap`() {
        val size = AgentTopologyPrefs.safeExportSize(
            contentWidthPx = 100_000,
            contentHeightPx = 80_000,
            maxPixels = 1_000_000,
            maxDimensionPx = 2_000,
            dpi = 600,
        )
        assertEquals(600, size.dpi)
        assertTrue(size.width <= 2_000)
        assertTrue(size.height <= 2_000)
        assertTrue(size.width.toLong() * size.height <= 1_000_000L)
    }

    // --- [T-agent-topology-token-unit-bug] token unit formatting ---
    //
    // These exist because the previous suite only sampled 238_400 and 1_200 —
    // both safe — while the bug lived in whole-number unit values. A formatted
    // quantity that can understate itself 100× needs the whole number line
    // covered, not two happy-path samples.

    @Test
    fun `whole-number magnitudes keep their magnitude`() {
        // The regression: `%.0f` produced "200" and an unconditional trimEnd('0')
        // turned it into "2" → 200_000 shown as "2K".
        assertEquals("100K", formatAgentTokens(100_000L))
        assertEquals("120K", formatAgentTokens(120_000L))
        assertEquals("150K", formatAgentTokens(150_000L))
        assertEquals("200K", formatAgentTokens(200_000L))
        assertEquals("500K", formatAgentTokens(500_000L))
        assertEquals("100M", formatAgentTokens(100_000_000L))
        assertEquals("200M", formatAgentTokens(200_000_000L))
    }

    @Test
    fun `unit values below one hundred keep one decimal`() {
        assertEquals("1.2K", formatAgentTokens(1_200L))
        assertEquals("99.9K", formatAgentTokens(99_900L))
        assertEquals("1.5M", formatAgentTokens(1_500_000L))
    }

    @Test
    fun `labels never round up into the next unit`() {
        // 999_999 must not read as "1000.0K" (or, worse after trimming, "1K").
        assertEquals("1M", formatAgentTokens(999_999L))
        assertEquals("999.9K", formatAgentTokens(999_949L))
        assertEquals("1B", formatAgentTokens(999_999_999L))
    }

    @Test
    fun `sub-thousand counts stay exact`() {
        assertEquals("0", formatAgentTokens(0L))
        assertEquals("999", formatAgentTokens(999L))
    }

    @Test
    fun `label magnitude never decreases as the count grows`() {
        // The invariant a lost-zero bug breaks: parse the label back to a number
        // and require it to be non-decreasing. (Comparing strings would not do:
        // "999.9K" < "1M" lexically.)
        val counts = listOf(
            0L, 1L, 999L, 1_000L, 10_000L, 99_900L, 100_000L, 120_000L, 200_000L,
            999_949L, 999_999L, 1_000_000L, 100_000_000L, 999_999_999L,
        )
        val parsed = counts.map { parseCompactTokens(formatAgentTokens(it)) }
        for (i in 1 until parsed.size) {
            assertTrue(
                "label for ${counts[i]} (${formatAgentTokens(counts[i])}) is smaller than " +
                    "for ${counts[i - 1]} (${formatAgentTokens(counts[i - 1])})",
                parsed[i] >= parsed[i - 1],
            )
        }
    }

    /** Parses "238.4K" / "1M" / "999" back into a token count for comparison. */
    private fun parseCompactTokens(label: String): Double {
        val multiplier = when (label.last()) {
            'K' -> 1_000.0
            'M' -> 1_000_000.0
            'B' -> 1_000_000_000.0
            else -> return label.toDouble()
        }
        return label.dropLast(1).toDouble() * multiplier
    }
}
