package com.openminis.app.data.repository

import com.openminis.app.feature.runtime.RuntimeModelSnapshot
import com.openminis.app.feature.runtime.RuntimeNodeStatus
import com.openminis.app.feature.runtime.RuntimeSessionNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The half of the recursive-delete defect that can be pinned without Room:
 * *which* ids a user-initiated conversation delete has to take with it.
 *
 * The chat database cannot answer this — `ChatSessionEntity` has no parent
 * column — so the set comes out of the runtime tree, and getting it wrong is
 * exactly the reported bug (deleting the conversation left its sub-agent
 * conversations behind as orphans).
 */
class SessionSubtreeDeletionPlanTest {

    private fun node(
        id: String,
        parentId: String? = null,
        birthChain: List<String> = emptyList(),
        depth: Int = 0,
        status: RuntimeNodeStatus = RuntimeNodeStatus.SUCCEEDED,
    ) = RuntimeSessionNode(
        id = id,
        parentId = parentId,
        rootId = birthChain.firstOrNull() ?: id,
        depth = depth,
        model = RuntimeModelSnapshot(provider = "p", model = "m"),
        status = status,
        createdAtMillis = 0L,
        updatedAtMillis = 0L,
        birthChain = birthChain,
    )

    /** A → B → C, plus an unrelated root R → S. */
    private val threeLevels = listOf(
        node("A", birthChain = listOf("A")),
        node("B", parentId = "A", birthChain = listOf("A", "B"), depth = 1),
        node("C", parentId = "B", birthChain = listOf("A", "B", "C"), depth = 2),
        node("R", birthChain = listOf("R")),
        node("S", parentId = "R", birthChain = listOf("R", "S"), depth = 1),
    )

    @Test
    fun `deleting a conversation takes its whole subtree of sub-agent conversations`() {
        val plan = sessionSubtreeDeletionPlan(threeLevels, listOf("A"))

        assertEquals(listOf("A", "B", "C"), plan.sessionIds)
        assertEquals(listOf("A", "B", "C"), plan.runtimeNodeIds)
    }

    @Test
    fun `deleting a sub-agent conversation keeps its ancestors`() {
        val plan = sessionSubtreeDeletionPlan(threeLevels, listOf("B"))

        assertEquals(listOf("B", "C"), plan.sessionIds)
        assertEquals(listOf("B", "C"), plan.runtimeNodeIds)
    }

    @Test
    fun `a sibling tree under another root survives`() {
        val plan = sessionSubtreeDeletionPlan(threeLevels, listOf("A"))

        assertFalse("unrelated root leaked into the delete set", "R" in plan.sessionIds)
        assertFalse("unrelated child leaked into the delete set", "S" in plan.sessionIds)
        assertFalse("R" in plan.runtimeNodeIds)
        assertFalse("S" in plan.runtimeNodeIds)
    }

    @Test
    fun `a repeated run root is folded onto its conversation`() {
        // A conversation that ran twice gets `A#run-2` as its root node; the
        // children hang off that node, not off the plain id.
        val nodes = listOf(
            node("A#run-2", birthChain = listOf("A#run-2")),
            node("B", parentId = "A#run-2", birthChain = listOf("A#run-2", "B"), depth = 1),
        )

        val plan = sessionSubtreeDeletionPlan(nodes, listOf("A"))

        assertEquals(listOf("A", "B"), plan.sessionIds)
        assertEquals(listOf("A#run-2", "B"), plan.runtimeNodeIds)
    }

    @Test
    fun `a descendant is found through its birth chain when the parent node is missing`() {
        // The parentId walk in descendantsForChatSession stops at the first link
        // whose parent node is absent from the snapshot; the immutable birth
        // chain captured at birth does not. Dropping this branch would leave an
        // orphan conversation behind — the reported bug.
        val nodes = listOf(
            node("A", birthChain = listOf("A")),
            node("C", parentId = "B", birthChain = listOf("A", "B", "C"), depth = 2),
        )

        val plan = sessionSubtreeDeletionPlan(nodes, listOf("A"))

        assertTrue("birth-chain descendant missing: ${plan.sessionIds}", "C" in plan.sessionIds)
        assertTrue("C" in plan.runtimeNodeIds)
    }

    @Test
    fun `selecting a conversation and one of its own descendants deletes each id once`() {
        val plan = sessionSubtreeDeletionPlan(threeLevels, listOf("A", "B"))

        assertEquals(listOf("A", "B", "C"), plan.sessionIds)
        assertEquals(listOf("A", "B", "C"), plan.runtimeNodeIds)
    }

    @Test
    fun `selection order and duplicate targets do not produce duplicate ids`() {
        val plan = sessionSubtreeDeletionPlan(threeLevels, listOf("R", "A", "R", " A ", ""))

        assertEquals(listOf("R", "S", "A", "B", "C"), plan.sessionIds)
        assertEquals(listOf("R", "S", "A", "B", "C"), plan.runtimeNodeIds)
    }

    @Test
    fun `a conversation with no runtime node is still deletable on its own`() {
        // Every session the runtime tree has never seen (created before the tree
        // existed, or after a tree reset) must still be deletable.
        val plan = sessionSubtreeDeletionPlan(threeLevels, listOf("Z"))

        assertEquals(listOf("Z"), plan.sessionIds)
        assertEquals(emptyList<String>(), plan.runtimeNodeIds)
        assertEquals(listOf("Z"), plan.rootSessionIds)
    }

    @Test
    fun `ids that have no chat row are filtered out of the delete set`() {
        // `deleteSessionSubtree` refuses a batch containing an unknown id, and the
        // runtime tree also holds nodes for sessions the database never had.
        val nodes = threeLevels + node("ghost", parentId = "A", birthChain = listOf("A", "ghost"), depth = 1)

        val plan = sessionSubtreeDeletionPlan(nodes, listOf("A"), sessionExists = { it != "ghost" })

        assertEquals(listOf("A", "B", "C"), plan.sessionIds)
        assertTrue("the stale runtime node still has to go", "ghost" in plan.runtimeNodeIds)
    }

    @Test
    fun `empty input yields an empty plan`() {
        val plan = sessionSubtreeDeletionPlan(threeLevels, listOf("", "  "))

        assertTrue(plan.isEmpty)
        assertEquals(emptyList<String>(), plan.rootSessionIds)
    }

    @Test
    fun `deleting an ancestor still reaches a subtree whose sub-agent was re-run`() {
        // A conversation that is opened again gets a brand-new root
        // (`B#run-2`) with parentId = null and a birth chain holding only
        // itself. Its children therefore have no parent chain back to the
        // ancestor: B#run-2 -> null, and `C.parentId == "B#run-2"`.
        //
        // Walking from A alone loses C entirely, so deleting A would delete B's
        // *old* node (still attached to A) and leave B's live subtree behind as
        // orphans — precisely the state R44 forbids. The walk is a fixed point
        // over session identities, so expanding B picks up B#run-2 and, through
        // it, C.
        val nodes = listOf(
            node("A", birthChain = listOf("A")),
            node("B", parentId = "A", birthChain = listOf("A", "B"), depth = 1),
            node("B#run-2", birthChain = listOf("B#run-2"), depth = 0),
            node("C", parentId = "B#run-2", birthChain = listOf("B#run-2", "C"), depth = 1),
            node("R", birthChain = listOf("R")),
        )

        val plan = sessionSubtreeDeletionPlan(nodes, listOf("A"))

        assertEquals(listOf("A", "B", "C"), plan.sessionIds)
        assertEquals(listOf("A", "B", "B#run-2", "C"), plan.runtimeNodeIds)
        assertFalse("the unrelated root leaked in", "R" in plan.sessionIds)
    }

    @Test
    fun `a re-run that happens several levels down does not truncate the subtree`() {
        // Same failure one level deeper: A -> B -> C, where *C* was re-run and
        // then produced D. Everything below the re-run has to come along.
        val nodes = listOf(
            node("A", birthChain = listOf("A")),
            node("B", parentId = "A", birthChain = listOf("A", "B"), depth = 1),
            node("C", parentId = "B", birthChain = listOf("A", "B", "C"), depth = 2),
            node("C#run-2", birthChain = listOf("C#run-2"), depth = 0),
            node("D", parentId = "C#run-2", birthChain = listOf("C#run-2", "D"), depth = 1),
        )

        val plan = sessionSubtreeDeletionPlan(nodes, listOf("A"))

        assertEquals(listOf("A", "B", "C", "D"), plan.sessionIds)
        assertEquals(listOf("A", "B", "C", "C#run-2", "D"), plan.runtimeNodeIds)
    }

    @Test
    fun `a node whose suffix only looks like a run marker is not folded onto the session`() {
        // `#run-abc` is not the runtime's run marker (the generation is always
        // numeric), so it is a different node id — folding it onto `A` would put
        // an unrelated session into the delete set.
        val nodes = listOf(
            node("A", birthChain = listOf("A")),
            node("A#run-abc", birthChain = listOf("A#run-abc")),
        )

        val plan = sessionSubtreeDeletionPlan(nodes, listOf("A"))

        assertEquals(listOf("A"), plan.sessionIds)
        assertEquals(listOf("A"), plan.runtimeNodeIds)
    }
}
