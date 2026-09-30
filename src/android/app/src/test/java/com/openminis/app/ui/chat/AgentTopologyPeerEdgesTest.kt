package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-topology-peer-edges] The diagram must show the non-tree links.
 *
 * The requirement describes Team mode as P2P nodes — "团队模式的话，他们就是 P2P 的
 * 节点… 从和从之间可以 P2P 也就是可以拥有更多的一些拓扑关系和连接关系和交流"
 * (request.md:33) — so the peer links are part of what the topology view exists
 * to show.
 *
 * The runtime has always recorded them (`RuntimeEdgeKind.TEAM_PEER`, plus
 * subscriptions, both exposed in the snapshot as `topologyEdges`/`subscriptions`),
 * but this side only ever drew parent/child lines: `AgentTopologyRelation` was
 * built from `parentId` alone and the edge arrays were never read. The data
 * existed and was invisible.
 *
 * These tests cover the mapping and the wiring, including the case that would
 * double-stroke the tree if the handling were naive.
 */
class AgentTopologyPeerEdgesTest {

    private fun runtimeJson(nodes: String, edges: String = "[]", subscriptions: String = "[]") =
        """{"nodes":[$nodes],"topologyEdges":$edges,"subscriptions":$subscriptions}"""

    private val nodes = """
        {"id":"root","rootId":"root","depth":0,"status":"RUNNING","createdAtMillis":1000},
        {"id":"child-a","parentId":"root","rootId":"root","depth":1,"status":"RUNNING","createdAtMillis":2000},
        {"id":"child-b","parentId":"root","rootId":"root","depth":1,"status":"RUNNING","createdAtMillis":3000}
    """.trimIndent()

    private fun graph(edges: String = "[]", subscriptions: String = "[]"): AgentTopologyGraph =
        agentTopologyGraphFromRuntimeJson(
            runtimeJson = runtimeJson(nodes, edges, subscriptions),
            sessionId = "root",
            sessionTitle = "Root",
            currentSummary = "",
        )

    // ---- parsing -----------------------------------------------------------------

    @Test
    fun `a team peer edge becomes a peer relation`() {
        val links = parseRuntimeTopologyLinks(
            runtimeJson(nodes, """[{"fromNodeId":"child-a","toNodeId":"child-b","kind":"TEAM_PEER"}]"""),
        )
        assertEquals(1, links.size)
        assertEquals("child-a", links.single().fromId)
        assertEquals("child-b", links.single().toId)
        assertEquals(AgentTopologyEdgeKind.PEER, links.single().kind)
    }

    @Test
    fun `a revoked edge is not drawn`() {
        // revokedAtMillis is how the runtime retires an edge; drawing a revoked
        // link would show a permission the user already withdrew.
        val links = parseRuntimeTopologyLinks(
            runtimeJson(
                nodes,
                """[{"fromNodeId":"child-a","toNodeId":"child-b","kind":"TEAM_PEER","revokedAtMillis":555}]""",
            ),
        )
        assertTrue("revoked edges must be dropped: $links", links.isEmpty())
    }

    @Test
    fun `a parent-child runtime edge is mapped to parent and not surfaced as a peer`() {
        // It duplicates the line already derived from parentId; treating it as a
        // separate relation would stroke the delegation line twice.
        val links = parseRuntimeTopologyLinks(
            runtimeJson(nodes, """[{"fromNodeId":"root","toNodeId":"child-a","kind":"PARENT_CHILD"}]"""),
        )
        assertEquals(AgentTopologyEdgeKind.PARENT, links.single().kind)
        val drawn = graph("""[{"fromNodeId":"root","toNodeId":"child-a","kind":"PARENT_CHILD"}]""")
        val delegation = drawn.relations.filter { it.fromId == "root" && it.toId == "child-a" }
        assertEquals("delegation must be drawn exactly once: $delegation", 1, delegation.size)
    }

    @Test
    fun `a subscription is drawn from publisher to subscriber`() {
        // The arrow follows the notification, which is the direction that means
        // something to a reader.
        val links = parseRuntimeTopologyLinks(
            runtimeJson(nodes, subscriptions = """[{"publisherNodeId":"root","subscriberNodeId":"child-a"}]"""),
        )
        assertEquals(AgentTopologyEdgeKind.SUBSCRIPTION, links.single().kind)
        assertEquals("root", links.single().fromId)
        assertEquals("child-a", links.single().toId)
    }

    @Test
    fun `a self link is ignored rather than drawing a loop on a node`() {
        val links = parseRuntimeTopologyLinks(
            runtimeJson(nodes, """[{"fromNodeId":"child-a","toNodeId":"child-a","kind":"TEAM_PEER"}]"""),
        )
        assertTrue(links.isEmpty())
    }

    @Test
    fun `an unknown kind falls back to parent instead of crashing`() {
        val links = parseRuntimeTopologyLinks(
            runtimeJson(nodes, """[{"fromNodeId":"child-a","toNodeId":"child-b","kind":"SOMETHING_NEW"}]"""),
        )
        assertEquals(AgentTopologyEdgeKind.PARENT, links.single().kind)
    }

    @Test
    fun `malformed or absent json yields no links instead of throwing`() {
        // A diagram must still render when the runtime snapshot is partial.
        assertTrue(parseRuntimeTopologyLinks("").isEmpty())
        assertTrue(parseRuntimeTopologyLinks("{}").isEmpty())
        assertTrue(parseRuntimeTopologyLinks("not json at all").isEmpty())
        assertTrue(
            parseRuntimeTopologyLinks("""{"topologyEdges":"not-an-array","subscriptions":7}""").isEmpty(),
        )
    }

    // ---- wiring into the graph ---------------------------------------------------

    @Test
    fun `the graph carries a peer relation between two sub-agents`() {
        val drawn = graph("""[{"fromNodeId":"child-a","toNodeId":"child-b","kind":"TEAM_PEER"}]""")
        val peer = drawn.relations.singleOrNull { it.kind == AgentTopologyEdgeKind.PEER }
        assertTrue("expected a peer relation, got ${drawn.relations}", peer != null)
        assertEquals("child-a", peer?.fromId)
        assertEquals("child-b", peer?.toId)
    }

    @Test
    fun `a link to a node outside the tree is dropped`() {
        // Endpoints that are not on screen cannot be drawn; keeping the relation
        // would make the layout look for a node that is not there.
        val drawn = graph("""[{"fromNodeId":"child-a","toNodeId":"somewhere-else","kind":"TEAM_PEER"}]""")
        assertFalse(
            "no relation may reference an off-screen node: ${drawn.relations}",
            drawn.relations.any { it.toId == "somewhere-else" || it.fromId == "somewhere-else" },
        )
    }

    @Test
    fun `a snapshot without edge arrays still yields the delegation tree`() {
        // Regression guard: reading the new arrays must not break the old shape,
        // where the snapshot had no topologyEdges key at all.
        val drawn = agentTopologyGraphFromRuntimeJson(
            runtimeJson = """{"nodes":[$nodes]}""",
            sessionId = "root",
            sessionTitle = "Root",
            currentSummary = "",
        )
        assertEquals(3, drawn.nodes.size)
        assertEquals(2, drawn.relations.size)
        assertTrue(drawn.relations.all { it.kind == AgentTopologyEdgeKind.PARENT })
    }

    @Test
    fun `peer relations survive the layout so a renderer can style them`() {
        // The renderers style by `edge.relation.kind`, so the kind must reach the
        // routed edge — not be lost in layout.
        val drawn = graph("""[{"fromNodeId":"child-a","toNodeId":"child-b","kind":"TEAM_PEER"}]""")
        val layout = layoutAgentTopology(drawn.nodes, drawn.relations)
        val routed = layout.edges.singleOrNull { it.relation.kind == AgentTopologyEdgeKind.PEER }
        assertTrue("peer edge must be routed: ${layout.edges.map { it.relation.kind }}", routed != null)
        assertTrue("routed peer edge needs points", (routed?.points?.size ?: 0) >= 2)
    }

    // ---- style -------------------------------------------------------------------

    @Test
    fun `each edge kind is visually distinguishable`() {
        // Three kinds sharing one appearance would leave the P2P structure as
        // unreadable as not drawing it. Delegation is solid; the link kinds are
        // dashed so they cannot be mistaken for the tree.
        val parent = topologyEdgeStyle(AgentTopologyEdgeKind.PARENT)
        val peer = topologyEdgeStyle(AgentTopologyEdgeKind.PEER)
        val subscription = topologyEdgeStyle(AgentTopologyEdgeKind.SUBSCRIPTION)

        assertNull("delegation is the solid backbone", parent.dashIntervals)
        assertTrue(peer.dashIntervals != null && peer.dashIntervals.isNotEmpty())
        assertTrue(subscription.dashIntervals != null && subscription.dashIntervals.isNotEmpty())
        assertEquals(
            "colours must differ between kinds",
            3,
            setOf(parent.argb, peer.argb, subscription.argb).size,
        )
    }

    @Test
    fun `every kind has a style so no edge can be drawn unstyled`() {
        AgentTopologyEdgeKind.entries.forEach { kind ->
            val style = topologyEdgeStyle(kind)
            assertTrue("$kind needs a positive stroke width", style.strokeWidth > 0f)
            assertTrue("$kind needs dash intervals longer than zero", style.dashIntervals?.all { it > 0f } != false)
        }
    }
}
