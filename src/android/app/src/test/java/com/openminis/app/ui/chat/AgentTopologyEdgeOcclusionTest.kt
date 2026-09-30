package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-topology-edge-occlusion] The requirement is absolute — *"所有的线不能够被节点
 * 遮挡住 … 就要绕过节点"* (request.md:35) — and until now nothing tested it.
 *
 * ## What was untested
 *
 * `routeAgentTopologyEdges` reports a `collisionFree` flag per edge. The existing topology
 * test asserted `assertTrue(edge.collisionFree)` once, for one three-rectangle fixture, and
 * the file contains **no `assertFalse` at all**. So the branch the absolute requirement
 * cannot honour — the last-resort straight line, reachable when no channel width works —
 * had no test in either direction: nothing proved the flag could be false, and nothing
 * pinned that it stays true for layouts the app actually produces.
 *
 * ## What this file adds
 *
 * 1. **A sweep over well-formed layouts** (node sizes, relations varied, edge clearance
 *    varied): every routed edge must be `collisionFree`, and every routed polyline must
 *    also pass `agentTopologyPathIsClear`. What the sweep is for is the case where the
 *    router stops routing around obstacles in a layout the app actually builds — with the
 *    routing deleted it fires on the four-sibling layout. **It is not a proof of the flag
 *    itself**: with `agentTopologyPathIsClear` stubbed to `true`, both assertions here go
 *    trivially green, because the fixture's edges are collision-free to begin with. That
 *    job belongs to the last two tests, which drive both answers.
 * 2. **The fence itself, driven false for the first time.** A blocker rectangle that
 *    encloses both endpoints blocks every candidate, including the successively widened
 *    outer channels, which is the case the source comments call malformed input. The
 *    result must be the honest straight line with `collisionFree = false`.
 *
 * Together these say: the layout the app builds never trips the last resort, and the last
 * resort still reports honestly when it is tripped. Neither claim was checkable before.
 *
 * Both mutations that were run to check this file are on the record in the plan, including
 * the one the sweep did **not** catch.
 */
class AgentTopologyEdgeOcclusionTest {

    private fun node(id: String, width: Float = 120f, height: Float = 60f, parent: String? = null) =
        AgentTopologyNode(
            id = id,
            title = id,
            parentId = parent,
            width = width,
            height = height,
            depth = if (parent == null) 0 else 1,
            status = "RUNNING",
        )

    private fun rect(x: Float, y: Float, w: Float, h: Float) = AgentTopologyRect(x, y, w, h)

    // ---- 1. the app's own layouts never trip the last resort -----------------------

    @Test
    fun `every edge of a well-formed layout is collision free and its geometry agrees`() {
        val layouts = listOf(
            // A single child: the simplest cross-level edge.
            listOf(node("root") to listOf(node("child", parent = "root"))),
            // A chain, so a relation has to span three levels.
            listOf(
                node("a") to listOf(node("b", parent = "a")),
                node("b") to listOf(node("c", parent = "b")),
            ),
            // Wide siblings, which push the level's total width out.
            listOf(node("root") to (1..4).map { node("c$it", parent = "root") }),
            // Very different node sizes on the same level.
            listOf(
                node("root", width = 40f, height = 200f) to listOf(
                    node("skinny", width = 30f, height = 20f, parent = "root"),
                    node("wide", width = 400f, height = 30f, parent = "root"),
                ),
            ),
            // A cross-level edge between cousins, which has to leave the tree and re-enter.
            listOf(
                node("root") to listOf(
                    node("x", parent = "root"),
                    node("y", parent = "root"),
                ),
            ),
        )

        for (clearance in listOf(0f, 8f, 24f)) {
            for ((index, layout) in layouts.withIndex()) {
                // The pair encoding below names a node twice when it is one level's
                // child and the next level's parent (the chain case). `layoutAgentTopology`
                // requires unique ids, and the first mention is the one that carries the
                // parent link we mean, so keep that one.
                val nodes = layout.flatMap { (parent, children) -> listOf(parent) + children }
                    .distinctBy { it.id }
                val parentOf = nodes.associate { it.id to it.parentId }
                // Parent-to-child plus every pair of siblings, so relations cross the
                // layout in both directions and skip levels.
                val relations = buildList {
                    for (n in nodes) {
                        val p = parentOf[n.id]
                        if (p != null) add(AgentTopologyRelation(p, n.id))
                    }
                    val siblings = nodes.filter { it.parentId != null && it.parentId == nodes.first().id }
                    siblingPairs(siblings).forEach { (a, b) -> add(AgentTopologyRelation(a, b)) }
                }

                val result = layoutAgentTopology(
                    nodes = nodes,
                    relations = relations,
                    options = AgentTopologyLayoutOptions(edgeClearance = clearance),
                )
                val rects = result.nodes.associate { it.node.id to it.rect }

                val where = "layout #$index at clearance $clearance"
                assertTrue("$where: fixture has to produce edges", result.edges.isNotEmpty())
                for (edge in result.edges) {
                    assertTrue(
                        "$where: ${edge.relation.fromId}->${edge.relation.toId} was routed " +
                            "through a node, which the requirement forbids unconditionally",
                        edge.collisionFree,
                    )
                    // Re-derived from the geometry, not read off the flag above.
                    assertTrue(
                        "$where: ${edge.relation.fromId}->${edge.relation.toId} polygon " +
                            "intersects a node even though the flag says otherwise",
                        agentTopologyPathIsClear(
                            edge.points,
                            edge.relation.fromId,
                            edge.relation.toId,
                            rects,
                            clearance,
                        ),
                    )
                }
            }
        }
    }

    private fun siblingPairs(ids: List<AgentTopologyNode>): List<Pair<String, String>> =
        if (ids.size < 2) emptyList()
        else listOf(ids.first().id to ids.last().id) + siblingPairs(ids.dropLast(1))

    @Test
    fun `a blocker between two nodes is routed around rather than through`() {
        // The one case the old test covered, kept here so the sweep above is not the only
        // thing standing between this requirement and a regression.
        val rects = mapOf(
            "a" to rect(0f, 0f, 40f, 40f),
            "blocker" to rect(40f, -10f, 40f, 60f),
            "b" to rect(100f, 0f, 40f, 40f),
        )
        val edge = routeAgentTopologyEdges(
            listOf(AgentTopologyRelation("a", "b")),
            rects,
            clearance = 2f,
        ).single()

        assertTrue(edge.collisionFree)
        assertTrue(agentTopologyPathIsClear(edge.points, "a", "b", rects, clearance = 2f))
        assertTrue(
            "a route that avoids a blocker has to bend; a straight line would mean the " +
                "router ignored it, points=${edge.points}",
            edge.points.size > 2,
        )
    }

    // ---- 2. the last resort, driven for the first time ----------------------------

    @Test
    fun `a blocker that encloses both endpoints is reported honestly, not hidden`() {
        // Every candidate the router can build starts at `start` and ends at `end`, and
        // this blocker contains both — so the straight line, the visibility grid and all
        // four widened outer channels are blocked. The source comment names this case
        // ("malformed or fully enclosing rectangles") and used to leave it invisible: the
        // outcome was a straight line through the blocker with the flag quietly saying so,
        // and nothing ever read the flag.
        val rects = mapOf(
            "a" to rect(0f, 0f, 10f, 10f),
            "b" to rect(60f, 0f, 10f, 10f),
            "enclosing" to rect(-1000f, -1000f, 3000f, 3000f),
        )
        val edge = routeAgentTopologyEdges(
            listOf(AgentTopologyRelation("a", "b")),
            rects,
            clearance = 2f,
        ).single()

        assertFalse(
            "the flag has to be false here: every route is blocked, and reporting a " +
                "collision-free path would be the dishonest answer this flag exists to avoid",
            edge.collisionFree,
        )
        assertEquals(
            "the last resort is the straight line, and the geometry has to say so",
            listOf(rect(0f, 0f, 10f, 10f).let { AgentTopologyPoint(it.center.x, it.center.y) },
                   rect(60f, 0f, 10f, 10f).let { AgentTopologyPoint(it.center.x, it.center.y) }),
            edge.points,
        )
        assertFalse(
            "…and the polygon really does cross the blocker, so false is the accurate answer",
            agentTopologyPathIsClear(edge.points, "a", "b", rects, clearance = 2f),
        )
    }

    // ---- 3. the flag is computed, not defaulted ------------------------------------

    @Test
    fun `the flag distinguishes a clear route from a blocked one`() {
        // If `collisionFree` were ever hardwired, this pair would be the pair that shows
        // it: same endpoints, same clearance, one blocked and one not.
        val endpoints = mapOf("a" to rect(0f, 0f, 10f, 10f), "b" to rect(60f, 0f, 10f, 10f))
        val clear = routeAgentTopologyEdges(
            listOf(AgentTopologyRelation("a", "b")),
            endpoints,
            clearance = 2f,
        ).single()
        val blocked = routeAgentTopologyEdges(
            listOf(AgentTopologyRelation("a", "b")),
            endpoints + ("enclosing" to rect(-1000f, -1000f, 3000f, 3000f)),
            clearance = 2f,
        ).single()

        assertEquals(
            "the only difference between these two inputs is the blocker, so the flag has " +
                "to be the only difference between the outputs",
            !clear.collisionFree,
            blocked.collisionFree,
        )
        assertTrue(clear.collisionFree)
        assertFalse(blocked.collisionFree)
    }
}
