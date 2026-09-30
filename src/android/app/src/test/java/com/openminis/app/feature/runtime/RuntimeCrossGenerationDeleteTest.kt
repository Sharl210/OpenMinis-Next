package com.openminis.app.feature.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Deletion is narrower than delivery — pinned where it can actually fail.
 *
 * `request.md:111` needs a generation root to reach every member of its own
 * conversation, so sending is conversation-scoped. `request.md:9` never asked
 * for cross-generation deletion: it asks to VIEW interrupted descendants and to
 * RESTART them. Reusing the conversation-scope predicate for deletion widened
 * authority — a re-run root `A#run-1` could delete a subtree hanging off the
 * crashed root `A`, a target it has no ancestry over.
 *
 * Why this file exists at all: that widening was found, then its only guard was
 * judged "an outdated text assertion", and the guard was replaced by a
 * structure regex that accepts BOTH spellings — at which point nothing in the
 * suite failed any more. Measured, on the widened predicate:
 *
 *   RuntimeSubtreeDeletionTest          OK (4 tests)
 *   SessionSubtreeDeletionWiringSourceTest  OK (4 tests)
 *
 * Both stayed green because their scenarios are refused under *either* rule:
 * self-delete is caught by the `executor.id == target.id` half, and a sibling
 * is neither's ancestor, so the conversation-scope rule refuses it too. The
 * one shape that separates the two rules — same conversation, neither an
 * ancestor of the other — had no test at all.
 *
 * This file is that test, and it is written so a rename cannot silence it: it
 * asserts the OUTCOME and the REASON, never the spelling of a predicate.
 */
class RuntimeCrossGenerationDeleteTest {

    private fun tree(): RuntimeSessionTree {
        val t = RuntimeSessionTree(RuntimeTreeConfig(maxDepth = 4), clock = { 10L })
        // Two roots of the SAME conversation: `canonicalRuntimeSessionId` folds
        // the `#run-N` marker, so `A` and `A#run-1` are one conversation while
        // being two independent roots (neither is the other's ancestor).
        t.createRoot("A", RuntimeModelSnapshot("p", "A"))
        t.start("A")
        t.createRoot("A#run-1", RuntimeModelSnapshot("p", "A#run-1"))
        t.start("A#run-1")
        return t
    }

    @Test
    fun `a generation root may not delete a subtree in another generation of the same conversation`() {
        val t = tree()
        // `child` hangs off the OLD generation root; the executor is the NEW one.
        t.createChild("A", "child", RuntimeModelSnapshot("p", "child"))
        t.complete("child")
        assertEquals(RuntimeNodeStatus.SUCCEEDED, t.node("child")?.status)

        val receipt = t.deleteSubtree(DeleteSubtreeRequest("A#run-1", "A#run-1", "child"))

        assertEquals(
            "a target this root has no ancestry over must not be deletable, even in its own conversation: " +
                receipt.reason,
            DeleteSubtreeResult.REJECTED,
            receipt.result,
        )
        assertEquals(
            "the refusal must name the ancestry rule, not the conversation rule — they DO share a conversation",
            "executor must be a strict ancestor of target",
            receipt.reason,
        )
        assertNotNull("the refused target must still be there", t.node("child"))
    }

    @Test
    fun `the generation that owns the subtree still deletes it`() {
        val t = tree()
        t.createChild("A", "child", RuntimeModelSnapshot("p", "child"))
        t.createChild("child", "grandchild", RuntimeModelSnapshot("p", "grandchild"))
        t.complete("grandchild")
        t.complete("child")

        val receipt = t.deleteSubtree(DeleteSubtreeRequest("A", "A", "child"))

        assertEquals("own subtree: ${receipt.reason}", DeleteSubtreeResult.DELETED, receipt.result)
        assertEquals(listOf("child", "grandchild"), receipt.affectedNodeIds)
        assertNull("the subtree is really gone", t.node("child"))
    }

    /**
     * The same widening, mirrored on the OTHER destructive gate, so the two
     * cannot be "fixed" into each other by accident. A deliberate stop is
     * conversation-scoped on purpose (`request.md:9` needs the re-run root to
     * be able to stop zombies left by a crash), so this asserts the ASYMMETRY
     * rather than one side of it: stop reaches across, delete does not.
     */
    @Test
    fun `stopping across generations is allowed while deleting across them is not`() {
        val t = tree()
        t.createChild("A", "zombie", RuntimeModelSnapshot("p", "zombie"))
        t.start("zombie")

        val stop = t.stopDescendant(
            RuntimeStopRequest(
                actorNodeId = "A#run-1",
                targetNodeId = "zombie",
                reason = "crash cleanup",
                operationId = "stop-cross-gen",
            ),
        )
        assertEquals(
            "a re-run root must be able to stop what a crash left behind: ${stop.reason}",
            true,
            stop.accepted,
        )

        val del = t.deleteSubtree(DeleteSubtreeRequest("A#run-1", "A#run-1", "zombie"))
        assertEquals(
            "but destruction stays narrow: ${del.reason}",
            DeleteSubtreeResult.REJECTED,
            del.result,
        )
        assertNotNull("refused delete must leave the node alone", t.node("zombie"))
    }
}
