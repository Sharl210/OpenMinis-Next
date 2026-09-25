package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTopologyTest {
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
        assertEquals("238K", layout.nodes.first().tokenLabel)
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
}
