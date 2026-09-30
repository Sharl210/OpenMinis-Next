package com.openminis.app.feature.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner-authorized subtree purge, the runtime-tree half of a user-initiated
 * conversation delete.
 *
 * This deliberately does **not** go through [RuntimeSessionCoordinator.deleteSubtree]:
 * that path requires the executor to be a strict ancestor of the target inside
 * the same root, which is how an agent is kept from deleting itself or a
 * stranger (R44 line 254). The human owner is not a node in this tree, so the
 * user's delete needs its own authorization model — and must not weaken the
 * agent-facing one, which the regression tests at the bottom pin.
 */
class RuntimeSessionSubtreePurgeTest {

    private fun coordinator(store: RuntimeTreeStore): RuntimeSessionCoordinator {
        val constructor = RuntimeSessionCoordinator::class.java.getDeclaredConstructor(RuntimeTreeStore::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(store)
    }

    private fun store(dir: File, failing: Boolean = false): RuntimeTreeStore =
        RuntimeTreeStore.openForTest(File(dir, "session-tree.json")) { source, target ->
            if (failing) error("injected replace failure")
            Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }

    private fun newCoordinator(failing: Boolean = false): RuntimeSessionCoordinator {
        val dir = Files.createTempDirectory("runtime-purge").toFile()
        return coordinator(store(dir, failing))
    }

    private fun ids(coordinator: RuntimeSessionCoordinator): List<String> =
        coordinator.topologySnapshot().nodes.map { it.id }

    /** A (root) → B → C, plus an unrelated root R → S, all finished. */
    private fun threeLevels(): RuntimeSessionCoordinator {
        val coordinator = newCoordinator()
        assertTrue(coordinator.startRoot("A") != null)
        assertTrue(coordinator.startChild("A", "B"))
        assertTrue(coordinator.startChild("B", "C"))
        assertTrue(coordinator.startRoot("R") != null)
        assertTrue(coordinator.startChild("R", "S"))
        coordinator.finishChild("C")
        coordinator.finishChild("B")
        coordinator.finishChild("S")
        return coordinator
    }

    @Test
    fun `a user delete purges the conversation and every sub-agent node below it`() {
        val coordinator = threeLevels()

        val receipt = requireNotNull(coordinator.purgeSubtrees(listOf("A"), PURGE_REASON)) {
            "purge reported a persistence failure"
        }

        assertEquals(listOf("A", "B", "C"), receipt.removedNodeIds)
        assertTrue(receipt.persisted)
        val remaining = ids(coordinator)
        assertEquals(listOf("R", "S"), remaining)
    }

    @Test
    fun `a sibling tree under another root survives`() {
        val coordinator = threeLevels()

        coordinator.purgeSubtrees(listOf("B"))

        assertEquals(listOf("A", "R", "S"), ids(coordinator))
    }

    @Test
    fun `a conversation whose sub-agent is still running is aborted, not refused`() {
        // The user asked for this conversation to be gone. Refusing would make
        // "delete" a lie; leaving the node behind would leave the tree pointing
        // at a session that no longer exists. The run that is still in flight is
        // stamped ABORTED so the tree records "this run did not finish".
        val coordinator = newCoordinator()
        coordinator.startRoot("A")
        coordinator.startChild("A", "B")
        val liveStatus = coordinator.topologySnapshot().nodes.first { it.id == "B" }.status
        assertTrue("precondition: B must still be live, was $liveStatus", liveStatus in LIVE_STATUSES)
        assertEquals(listOf("A", "B"), ids(coordinator))

        val receipt = requireNotNull(coordinator.purgeSubtrees(listOf("A"), PURGE_REASON))

        assertTrue("live node must be reported as aborted: ${receipt.abortedNodeIds}", "B" in receipt.abortedNodeIds)
        assertTrue("A" in receipt.abortedNodeIds)
        assertEquals(emptyList<String>(), ids(coordinator))
    }

    @Test
    fun `purging an already-purged subtree is an idempotent no-op`() {
        val coordinator = threeLevels()
        coordinator.purgeSubtrees(listOf("A"))
        assertEquals(listOf("R", "S"), ids(coordinator))

        val again = requireNotNull(coordinator.purgeSubtrees(listOf("A")))

        assertEquals(emptyList<String>(), again.removedNodeIds)
        assertEquals(emptyList<String>(), again.abortedNodeIds)
    }

    @Test
    fun `a tree that cannot be persisted rolls back and reports the purge as still owed`() {
        // The store restores the previous tree when the write fails, so a null
        // receipt means nothing was removed — the caller must still owe the
        // purge, and must not report it as done.
        val dir = Files.createTempDirectory("runtime-purge-failing").toFile()
        val seed = coordinator(store(dir))
        seed.startRoot("A")
        seed.startChild("A", "B")
        seed.finishChild("B")

        val failing = coordinator(store(dir, failing = true))
        val receipt = failing.purgeSubtrees(listOf("A"))

        assertEquals(null, receipt)
        assertEquals(listOf("A", "B"), ids(failing))
    }

    // ─── R44 line 254 regression: an agent still cannot delete itself ────────

    @Test
    fun `an agent issuing a delete for itself is still rejected`() {
        val coordinator = threeLevels()

        val receipt = coordinator.deleteSubtree(
            initiatorSessionId = "B",
            executorSessionId = "B",
            targetSessionId = "B",
        )

        assertEquals(DeleteSubtreeResult.REJECTED, receipt.result)
        assertEquals("executor must be a strict ancestor of target", receipt.reason)
        assertEquals(listOf("A", "B", "C", "R", "S"), ids(coordinator))
    }

    @Test
    fun `an agent still cannot delete the conversation it runs in`() {
        // A root has no ancestor at all, which is exactly why the user-facing
        // purge could not be expressed through the agent-authorized path.
        val coordinator = threeLevels()

        val receipt = coordinator.deleteSubtree(
            initiatorSessionId = "A",
            executorSessionId = "A",
            targetSessionId = "A",
        )

        assertEquals(DeleteSubtreeResult.REJECTED, receipt.result)
        assertEquals(listOf("A", "B", "C", "R", "S"), ids(coordinator))
    }

    @Test
    fun `an agent still cannot delete an unrelated conversation in another tree`() {
        val coordinator = threeLevels()

        val receipt = coordinator.deleteSubtree(
            initiatorSessionId = "A",
            executorSessionId = "A",
            targetSessionId = "S",
        )

        assertEquals(DeleteSubtreeResult.REJECTED, receipt.result)
        assertTrue("cross-root delete must not remove the target", "S" in ids(coordinator))
    }

    // ─── a purge must not leave the session→node memo pointing at a dead node ──

    @Test
    fun `a purged conversation is not remembered as having a live runtime`() {
        // The coordinator memoizes sessionId → runtime node id. Left behind by a
        // purge, `startRoot` short-circuits on `existingActive != null` and
        // heartbeats a node that no longer exists — `heartbeat` returns false and
        // changes nothing — then hands the caller a runtime id that is absent from
        // the tree. Everything that later addresses the session through that id
        // (stop, restart, supervision) is then aimed at nothing. The observable
        // symptom is exactly this: a runtime id the tree does not contain.
        val coordinator = newCoordinator()
        val original = requireNotNull(coordinator.startRoot("A"))
        requireNotNull(coordinator.purgeSubtrees(listOf("A"), PURGE_REASON)) { "purge failed" }
        assertEquals("precondition: the tree is empty after the purge", emptyList<String>(), ids(coordinator))

        val restarted = requireNotNull(coordinator.startRoot("A"))

        assertTrue(
            "startRoot handed back a runtime id absent from the tree: $restarted (original was $original)",
            restarted in ids(coordinator),
        )
    }

    private companion object {
        const val PURGE_REASON = "conversation deleted from the session list"

        /** The statuses a node can hold while its run is still in flight. */
        val LIVE_STATUSES = setOf(
            RuntimeNodeStatus.STARTING,
            RuntimeNodeStatus.RUNNING,
            RuntimeNodeStatus.WAITING_CHILDREN,
        )
    }
}
