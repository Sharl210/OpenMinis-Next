package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-topology-global-order-base] The "全局序号" shown on a node card must be
 * **1-based**, because the requirement defines it as a Chinese ordinal:
 *
 * > "然后每个节点不是还要包含它的创建时间和创建的一个序号吗？这个序号就是全局的索引号，
 * >  就是它的**第几个被创建的**…"  (`request.md:37`)
 * >
 * > "全局序号: 3这种"  (same line — the label's own example)
 *
 * "第几个" starts at 1, and "序号" in Chinese is a serial number. The parser used to
 * take the node's position in the runtime `nodes` array, which is a 0-based index —
 * so the root, i.e. the **first** node created, rendered as "全局序号: 0".
 *
 * WHY THE BASE MATTERS AND WHY THIS FILE EXISTS. Nothing else in the codebase reads
 * `globalOrder`: `topologySiblingIndexes` only sorts by it (a constant offset cannot
 * reorder), and it is not exported. So the ONLY observable consequence of the base is
 * this one user-visible number — which means no other test could ever catch a
 * regression here. Before this file, no test pinned the mapping at all.
 *
 * The tests below deliberately go through `agentTopologyGraphFromRuntimeJson` (the real
 * parse of a real runtime JSON payload) rather than constructing `AgentTopologyNode`
 * directly. Asserting that a hand-built node with `globalOrder = 1` renders "1" would be
 * circular: it would pass no matter what the parser does.
 */
class AgentTopologyGlobalOrderBaseTest {

    private val zh = TopologyCardStrings(
        mainAgent = "主代理",
        subAgentTemplate = "%1\$s级子代理",
        levelNumerals = listOf("一", "二", "三", "四", "五", "六", "七", "八", "九", "十"),
        createdKnown = "创建于 %1\$s",
        createdUnknown = "创建时间未知",
        order = "全局序号: %1\$d",
    )

    private fun graph(runtimeJson: String, sessionId: String = "session") =
        agentTopologyGraphFromRuntimeJson(
            runtimeJson = runtimeJson,
            sessionId = sessionId,
            sessionTitle = "Conversation",
            currentSummary = "最新一条回复",
            currentTokens = 0L,
            tokenTotalsBySession = emptyMap(),
            firstMessagesBySession = emptyMap(),
        )

    /** The requirement's own example label, so failures read like the requirement. */
    private fun label(node: AgentTopologyNode) = topologyOrderLabel(node.globalOrder, zh)

    // ─── the base itself ────────────────────────────────────────────────

    @Test
    fun `the first node created is number 1, not 0`() {
        val g = graph(
            """
            {"nodes":[
              {"id":"session","parentId":null,"rootId":"session","depth":0,"status":"RUNNING","createdAtMillis":10,"model":{"model":"main"}}
            ]}
            """.trimIndent(),
        )
        val root = g.nodes.single { it.id == "session" }
        assertEquals(
            "the requirement calls this \"第几个被创建的\"; the first one is 1",
            1,
            root.globalOrder,
        )
        assertEquals("全局序号: 1", label(root))
    }

    @Test
    fun `global order runs 1-based across the whole tree in creation order`() {
        // Root first, then two siblings — the runtime serialises `nodes` in creation
        // order, so the array positions are 0,1,2 and must read 1,2,3.
        val g = graph(
            """
            {"nodes":[
              {"id":"session","parentId":null,"rootId":"session","depth":0,"status":"RUNNING","createdAtMillis":10,"model":{"model":"main"}},
              {"id":"child-a","parentId":"session","rootId":"session","depth":1,"status":"RUNNING","createdAtMillis":20,"model":{"model":"child"}},
              {"id":"child-b","parentId":"session","rootId":"session","depth":1,"status":"RUNNING","createdAtMillis":30,"model":{"model":"child"}}
            ]}
            """.trimIndent(),
        )
        assertEquals(1, g.nodes.single { it.id == "session" }.globalOrder)
        assertEquals(2, g.nodes.single { it.id == "child-a" }.globalOrder)
        assertEquals(3, g.nodes.single { it.id == "child-b" }.globalOrder)

        // And the rendered labels agree with the requirement's format.
        assertEquals("全局序号: 1", label(g.nodes.single { it.id == "session" }))
        assertEquals("全局序号: 3", label(g.nodes.single { it.id == "child-b" }))
    }

    @Test
    fun `no node ever carries the 0-based number`() {
        // A blanket guard: whatever the tree looks like, "全局序号: 0" must not appear.
        // This is the assertion that fails loudly if someone reverts to `order = index`.
        val g = graph(
            """
            {"nodes":[
              {"id":"session","parentId":null,"rootId":"session","depth":0,"status":"RUNNING","createdAtMillis":10,"model":{"model":"main"}},
              {"id":"c1","parentId":"session","rootId":"session","depth":1,"status":"RUNNING","createdAtMillis":20,"model":{"model":"child"}},
              {"id":"c2","parentId":"session","rootId":"session","depth":1,"status":"RUNNING","createdAtMillis":30,"model":{"model":"child"}}
            ]}
            """.trimIndent(),
        )
        assertTrue("the graph should not be empty", g.nodes.isNotEmpty())
        g.nodes.forEach { node ->
            assertFalse(
                "node ${node.id} rendered \"全局序号: 0\" — the base slipped back to 0",
                label(node) == "全局序号: 0",
            )
            assertTrue(
                "node ${node.id} has globalOrder=${node.globalOrder}; it must be >= 1",
                node.globalOrder >= 1,
            )
        }
    }

    // ─── the synthetic fallback must use the same convention ────────────

    @Test
    fun `the synthetic node for missing runtime data is also number 1`() {
        // When there is no runtime JSON yet the graph is a single stand-in node for the
        // session. It has to follow the same convention, otherwise the very first thing
        // a user sees would be "全局序号: 0".
        val g = graph("""{"nodes":[]}""")
        val node = g.nodes.single()
        assertEquals(1, node.globalOrder)
        assertEquals("全局序号: 1", label(node))
    }

    // ─── sorting must not be disturbed by the base ──────────────────────

    @Test
    fun `sibling numbering still follows creation order after the base change`() {
        // `topologySiblingIndexes` sorts by `globalOrder`. The base fix adds a constant
        // to every value, so the sibling sequence must be unchanged — pin that, because
        // "changed the display number" is exactly the kind of edit that quietly reorders
        // something else.
        val g = graph(
            """
            {"nodes":[
              {"id":"session","parentId":null,"rootId":"session","depth":0,"status":"RUNNING","createdAtMillis":10,"model":{"model":"main"}},
              {"id":"first","parentId":"session","rootId":"session","depth":1,"status":"RUNNING","createdAtMillis":20,"model":{"model":"child"}},
              {"id":"second","parentId":"session","rootId":"session","depth":1,"status":"RUNNING","createdAtMillis":30,"model":{"model":"child"}}
            ]}
            """.trimIndent(),
        )
        val graphNodes = g.nodes
        val indexes = topologySiblingIndexes(graphNodes)
        assertEquals("creation order decides -1", 1, indexes["first"])
        assertEquals("creation order decides -2", 2, indexes["second"])
        assertEquals(
            "一级子代理-1",
            topologyLevelLabel(
                depth = graphNodes.single { it.id == "first" }.depth,
                siblingIndex = indexes["first"] ?: 0,
                strings = zh,
            ),
        )
    }
}
