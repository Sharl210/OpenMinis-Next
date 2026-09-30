package com.openminis.app.feature.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-child-restart] A descendant that stopped abnormally has to be
 * RESTARTABLE, and every layer that reports on that restart has to be honest
 * about whether it happened.
 *
 * The requirement (request.md:9) asks for two things: the parent can see which
 * descendants were interrupted (already built, see `RuntimeAbnormalFinishTest`),
 * and the parent can bring one back up. Before this the second half did not
 * exist — `updateStatus` refuses every terminal node outright, so nothing in the
 * runtime could move a node out of ABNORMAL_INTERRUPTION, and the only branch
 * that even tried (`RuntimeSessionCoordinator.startChild`'s reuse path) called
 * `start()` and `heartbeat()`, discarded both answers, and returned `true`
 * anyway. A caller was therefore told "the child is registered" while its node
 * stayed terminal and a *terminal* node was published as the child's active
 * runtime.
 *
 * These tests are written against observable state, not against the shape of the
 * implementation:
 *
 *  - every positive assertion about the tree is made TWICE — once on the live
 *    in-memory tree and once after re-reading the JSON the coordinator actually
 *    wrote to disk. A restart that only exists in memory is not a restart: the
 *    whole point is to survive the process death that caused the interruption.
 *  - the return value of `startChild` is asserted against the node's real status
 *    in the same breath, so "returns true" and "is running" can never disagree
 *    without a test going red.
 */
class RuntimeChildRestartTest {

    // ─── fixtures ────────────────────────────────────────────────────────

    private fun coordinator(store: RuntimeTreeStore): RuntimeSessionCoordinator {
        val constructor = RuntimeSessionCoordinator::class.java.getDeclaredConstructor(RuntimeTreeStore::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(store)
    }

    private fun store(dir: File): RuntimeTreeStore =
        RuntimeTreeStore.openForTest(File(dir, "session-tree.json")) { source, target ->
            Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }

    private fun newCoordinator(): RuntimeSessionCoordinator {
        val dir = Files.createTempDirectory("runtime-child-restart").toFile()
        return coordinator(store(dir))
    }

    private fun RuntimeSessionCoordinator.store(): RuntimeTreeStore {
        val field = RuntimeSessionCoordinator::class.java.getDeclaredField("store")
        field.isAccessible = true
        return field.get(this) as RuntimeTreeStore
    }

    /**
     * Read-only view of the LIVE in-memory tree.
     *
     * Note for anyone extending this file: mutating through this object changes
     * memory only — nothing reaches disk until something calls
     * [RuntimeTreeStore.update]. That is why every mutation below goes through
     * [restartPersisted] / [abortPersisted] rather than calling the tree
     * directly, and why the persistence tests re-read the file instead of the
     * tree. A test that mutated the tree here and then asserted on disk would be
     * asserting that a write which never happened did happen.
     */
    private fun RuntimeSessionCoordinator.tree(): RuntimeSessionTree = store().snapshot()

    /**
     * Restart [nodeId] the way production does: inside `store.update { }`, which
     * is what makes the transition durable.
     */
    private fun RuntimeSessionCoordinator.restartPersisted(nodeId: String, reason: String = ""): Boolean {
        var result = false
        store().update { result = restart(nodeId, reason) }
        return result
    }

    /**
     * Abort [nodeId] through the durable path, using the exact call shape
     * `SessionTreeRuntime.reconcileLeases` uses for a lapsed lease
     * (`abort(id, abnormal = true, reason = "lease expired")`) — so the fixture is
     * the crash-recovery state, not a hand-built approximation of it.
     */
    private fun RuntimeSessionCoordinator.abortPersisted(nodeId: String, reason: String = "lease expired") {
        store().update { abort(nodeId, abnormal = true, reason = reason) }
    }

    /**
     * The `nodes` array as it exists ON DISK.
     *
     * Read back through a fresh [RuntimeTreeStore] over the same file rather than
     * from the coordinator's in-memory tree, because an assertion that only ever
     * sees the object it just mutated cannot detect a write that never happened.
     */
    private fun RuntimeSessionCoordinator.persistedNode(nodeId: String): JSONObject? =
        persistedTreeJson().getJSONArray("nodes").let { nodes ->
            (0 until nodes.length())
                .map { nodes.getJSONObject(it) }
                .firstOrNull { it.optString("id") == nodeId }
        }

    /** The runtime tree exactly as a later process would load it. */
    private fun RuntimeSessionCoordinator.persistedTreeJson(): JSONObject {
        val fileField = RuntimeTreeStore::class.java.getDeclaredField("file")
        fileField.isAccessible = true
        val path = fileField.get(store()) as File
        assertTrue("the runtime tree must have been written to ${path.absolutePath}", path.isFile)
        val reopened = RuntimeTreeStore.openForTest(path) { source, target ->
            Files.copy(source.toPath(), target.toPath(), REPLACE_EXISTING)
        }
        return JSONObject(reopened.snapshot().toJson())
    }

    /** Root + one child, with the child stopped the way a crash leaves it. */
    private fun coordinatorWithInterruptedChild(): RuntimeSessionCoordinator {
        val coordinator = newCoordinator()
        assertTrue(coordinator.startRoot("root") != null)
        assertTrue(coordinator.startChild("root", "child"))
        // Exactly the path crash recovery takes: the lease reaper marks an
        // un-heartbeated node ABNORMAL_INTERRUPTION (SessionTreeRuntime
        // .reconcileLeases -> abort(abnormal = true, reason = "lease expired")).
        coordinator.abortPersisted("child")
        assertEquals(
            RuntimeNodeStatus.ABNORMAL_INTERRUPTION,
            coordinator.tree().node("child")?.status,
        )
        return coordinator
    }

    // ─── 1. restart revives a terminal node ──────────────────────────────

    @Test
    fun `an interrupted node is revived to RUNNING with a fresh lease and a cleared abnormal flag`() {
        val coordinator = coordinatorWithInterruptedChild()
        val before = requireNotNull(coordinator.tree().node("child"))

        assertTrue(coordinator.restartPersisted("child", reason = "crash recovery"))

        val after = requireNotNull(coordinator.tree().node("child"))
        assertEquals(RuntimeNodeStatus.RUNNING, after.status)
        assertFalse("a running node is not an interrupted node", after.abnormal)
        assertNotNull("a running node must hold a lease or the reaper kills it again", after.leaseUntilMillis)
        assertTrue(
            "the lease must be renewed, not inherited from the interrupted run",
            after.leaseUntilMillis!! > before.updatedAtMillis,
        )
        assertTrue(after.updatedAtMillis >= before.updatedAtMillis)
    }

    @Test
    fun `the revived state is actually persisted, not only held in memory`() {
        val coordinator = coordinatorWithInterruptedChild()

        assertTrue(coordinator.restartPersisted("child", reason = "crash recovery"))

        // Re-read the medium: a restart that does not survive the process is not
        // a crash-recovery restart.
        val persisted = requireNotNull(coordinator.persistedNode("child"))
        assertEquals(RuntimeNodeStatus.RUNNING.name, persisted.optString("status"))
        assertFalse(persisted.optBoolean("abnormal"))
        assertTrue(persisted.optLong("leaseUntilMillis") > 0L)
    }

    @Test
    fun `restarting a node that is still running is refused and changes nothing`() {
        val coordinator = newCoordinator()
        assertTrue(coordinator.startRoot("root") != null)
        assertTrue(coordinator.startChild("root", "child"))
        val before = requireNotNull(coordinator.tree().node("child"))
        assertEquals(RuntimeNodeStatus.RUNNING, before.status)

        assertFalse(
            "live work must not be restarted behind the back of whoever is driving it",
            coordinator.restartPersisted("child"),
        )

        assertEquals(before, coordinator.tree().node("child"))
    }

    @Test
    fun `restarting an unknown node is refused`() {
        val coordinator = newCoordinator()
        assertTrue(coordinator.startRoot("root") != null)

        assertFalse(coordinator.restartPersisted("no-such-node"))
        assertFalse(coordinator.tree().canRestart("no-such-node"))
    }

    @Test
    fun `canRestart answers only for terminal nodes`() {
        val coordinator = coordinatorWithInterruptedChild()

        assertTrue("an interrupted node is restartable", coordinator.tree().canRestart("child"))
        assertFalse("the still-running root is not", coordinator.tree().canRestart("root"))
    }

    // ─── 2. the interruption evidence survives the restart ───────────────

    @Test
    fun `restarting does not erase the interruption record`() {
        val coordinator = coordinatorWithInterruptedChild()

        assertTrue(coordinator.restartPersisted("child", reason = "crash recovery"))

        val events = coordinator.tree().events().filter { it.nodeId == "child" }
        val interruption = events.filter { it.kind == "abnormal_interruption" }
        assertTrue(
            "'this node was interrupted' must stay answerable after the restart — the parent's " +
                "decision to restart was based on it",
            interruption.isNotEmpty(),
        )
        assertTrue(interruption.any { it.payload.contains("lease expired") })

        val restarted = events.filter { it.kind == "restarted" }
        assertEquals("the restart is recorded exactly once", 1, restarted.size)
        assertTrue(
            "the restart must say which state it came back from",
            restarted.single().payload.contains(RuntimeNodeStatus.ABNORMAL_INTERRUPTION.name),
        )
        assertTrue(restarted.single().payload.contains("crash recovery"))
    }

    @Test
    fun `the interruption record survives on disk too`() {
        val coordinator = coordinatorWithInterruptedChild()

        assertTrue(coordinator.restartPersisted("child", reason = "crash recovery"))

        // The event log is the durable half of the evidence; the in-memory list
        // being right proves nothing about what a later process will see.
        val events = coordinator.persistedTreeJson().getJSONArray("events")
        val kinds = (0 until events.length())
            .map { events.getJSONObject(it) }
            .filter { it.optString("nodeId") == "child" }
            .map { it.optString("kind") }

        assertTrue("persisted events must still contain the interruption", kinds.contains("abnormal_interruption"))
        assertTrue("persisted events must contain the restart", kinds.contains("restarted"))
    }

    // ─── 3. restartDescendant authorization ──────────────────────────────

    @Test
    fun `restartDescendant revives an interrupted child of the acting session`() {
        val coordinator = coordinatorWithInterruptedChild()

        assertTrue(coordinator.restartDescendant("root", "child"))

        assertEquals(RuntimeNodeStatus.RUNNING, coordinator.tree().node("child")?.status)
        assertFalse(coordinator.tree().node("child")!!.abnormal)
        assertEquals(
            "a restart the parent can rely on must survive the process",
            RuntimeNodeStatus.RUNNING.name,
            requireNotNull(coordinator.persistedNode("child")).optString("status"),
        )
    }

    @Test
    fun `restartDescendant refuses a node that is not a descendant of the actor`() {
        val coordinator = newCoordinator()
        assertTrue(coordinator.startRoot("root") != null)
        // Two siblings: both descendants of `root`, neither one the other's
        // descendant. A sibling restart would let one branch of the tree widen
        // its own authority over another.
        assertTrue(coordinator.startChild("root", "siblingA"))
        assertTrue(coordinator.startChild("root", "siblingB"))
        coordinator.abortPersisted("siblingB")

        assertFalse(
            "a sibling is not a descendant",
            coordinator.restartDescendant("siblingA", "siblingB"),
        )
        assertEquals(
            "a refused restart must not touch the target",
            RuntimeNodeStatus.ABNORMAL_INTERRUPTION,
            coordinator.tree().node("siblingB")?.status,
        )
    }

    @Test
    fun `restartDescendant reaches a grandchild, not only a direct child`() {
        val coordinator = newCoordinator()
        assertTrue(coordinator.startRoot("root") != null)
        assertTrue(coordinator.startChild("root", "child"))
        // Depth 2 is the default, so root -> child -> grandchild is legal.
        assertTrue(coordinator.startChild("child", "grandchild"))
        coordinator.abortPersisted("grandchild")

        // A root recovers its whole subtree, not just the layer beneath it: the
        // ancestry check has to walk, not look only at direct children.
        assertTrue(coordinator.restartDescendant("root", "grandchild"))

        assertEquals(RuntimeNodeStatus.RUNNING, coordinator.tree().node("grandchild")?.status)
        assertFalse(coordinator.tree().node("grandchild")!!.abnormal)
    }

    @Test
    fun `restartDescendant refuses an unrelated node that shares no ancestry`() {
        val coordinator = newCoordinator()
        assertTrue(coordinator.startRoot("root") != null)
        assertTrue(coordinator.startChild("root", "child"))
        assertTrue(coordinator.startChild("child", "grandchild"))
        assertTrue(coordinator.startChild("root", "otherChild"))
        coordinator.abortPersisted("otherChild")

        // `otherChild` shares the root with `grandchild` but is not underneath
        // it: sharing a root is not the same as being a descendant.
        assertFalse(coordinator.restartDescendant("grandchild", "otherChild"))
        assertEquals(RuntimeNodeStatus.ABNORMAL_INTERRUPTION, coordinator.tree().node("otherChild")?.status)
    }

    @Test
    fun `restartDescendant refuses an actor restarting itself`() {
        val coordinator = newCoordinator()
        assertTrue(coordinator.startRoot("root") != null)

        assertFalse(coordinator.restartDescendant("root", "root"))
        assertEquals(RuntimeNodeStatus.RUNNING, coordinator.tree().node("root")?.status)
    }

    @Test
    fun `restartDescendant refuses a node in another runtime tree`() {
        val coordinator = newCoordinator()
        assertTrue(coordinator.startRoot("rootA") != null)
        assertTrue(coordinator.startChild("rootA", "childA"))
        assertTrue(coordinator.startRoot("rootB") != null)
        assertTrue(coordinator.startChild("rootB", "childB"))
        coordinator.abortPersisted("childB")

        assertFalse(
            "cross-root control is not authorized",
            coordinator.restartDescendant("rootA", "childB"),
        )
        assertEquals(RuntimeNodeStatus.ABNORMAL_INTERRUPTION, coordinator.tree().node("childB")?.status)
    }

    @Test
    fun `restartDescendant refuses an unknown or blank target`() {
        val coordinator = coordinatorWithInterruptedChild()

        assertFalse(coordinator.restartDescendant("root", "no-such-node"))
        assertFalse(coordinator.restartDescendant("root", "   "))
        assertFalse(coordinator.restartDescendant("", "child"))
    }

    @Test
    fun `restartDescendant refuses a descendant that is still running`() {
        val coordinator = newCoordinator()
        assertTrue(coordinator.startRoot("root") != null)
        assertTrue(coordinator.startChild("root", "child"))
        val before = requireNotNull(coordinator.tree().node("child"))

        assertFalse(coordinator.restartDescendant("root", "child"))

        assertEquals(before, coordinator.tree().node("child"))
    }

    // ─── 4. startChild stops lying about terminal children ───────────────

    /**
     * The core regression net.
     *
     * `startChild` used to return `persisted && childRuntimeId != null`, which is
     * true whenever the tree was written — regardless of whether the child was
     * actually started. On the reuse path for a terminal node, `start()` returned
     * false and `heartbeat()` returned false, both answers were discarded, and
     * the method still returned `true`. Reverting the fix (ignoring the
     * `start`/`restart` verdict again) turns the first assertion below red, and
     * the status assertions independent of it stay red too.
     */
    @Test
    fun `startChild never reports success while leaving the child terminal`() {
        val coordinator = coordinatorWithInterruptedChild()

        // The same child session id as the interrupted node: this is the "run
        // this child again on its existing context" path.
        val reported = coordinator.startChild("root", "child")

        val status = coordinator.tree().node("child")?.status
        assertEquals(
            "startChild returned $reported but the node is $status — the return value must " +
                "describe the node's real state, never the fact that the file was written",
            reported,
            status == RuntimeNodeStatus.RUNNING,
        )
        assertTrue("the interrupted child must come back up", reported)
        assertEquals(RuntimeNodeStatus.RUNNING, status)
        assertFalse(coordinator.tree().node("child")!!.abnormal)
    }

    @Test
    fun `startChild leaves the restarted child live and persisted`() {
        val coordinator = coordinatorWithInterruptedChild()

        assertTrue(coordinator.startChild("root", "child"))

        assertEquals(
            "startChild reported success but left the child terminal",
            RuntimeNodeStatus.RUNNING,
            coordinator.tree().node("child")?.status,
        )
        assertEquals(
            "startChild reported success but the child is not live",
            RuntimeNodeStatus.RUNNING.name,
            requireNotNull(coordinator.persistedNode("child")).optString("status"),
        )
        assertFalse(requireNotNull(coordinator.persistedNode("child")).optBoolean("abnormal"))
    }

    @Test
    fun `startChild still refuses when the parent has no runtime node`() {
        val coordinator = newCoordinator()

        assertFalse(coordinator.startChild("nobody", "child"))
        assertEquals(null, coordinator.tree().node("child"))
    }

    @Test
    fun `startChild on a fresh session still creates and starts the child`() {
        val coordinator = newCoordinator()
        assertTrue(coordinator.startRoot("root") != null)

        assertTrue(coordinator.startChild("root", "child"))

        val node = requireNotNull(coordinator.tree().node("child"))
        assertEquals(RuntimeNodeStatus.RUNNING, node.status)
        assertEquals("root", node.parentId)
        assertFalse(node.abnormal)
    }

    /**
     * A restart is not allowed to forge a *different* node: the child keeps its
     * identity, its parent and its birth position in the tree. Reusing an
     * existing session has to mean "same node, run again", not "new node that
     * looks like the old one".
     */
    @Test
    fun `the restarted child keeps its identity and parentage`() {
        val coordinator = coordinatorWithInterruptedChild()
        val before = requireNotNull(coordinator.tree().node("child"))

        assertTrue(coordinator.startChild("root", "child"))

        val after = requireNotNull(coordinator.tree().node("child"))
        assertEquals(before.id, after.id)
        assertEquals(before.parentId, after.parentId)
        assertEquals(before.rootId, after.rootId)
        assertEquals(before.depth, after.depth)
        assertEquals(before.birthChain, after.birthChain)
        assertNotEquals(before.status, after.status)
    }

    @Test
    fun `restartDescendant records who asked and why`() {
        val coordinator = coordinatorWithInterruptedChild()

        assertTrue(coordinator.restartDescendant("root", "child", reason = "recover after crash"))

        val event = coordinator.tree().events()
            .single { it.nodeId == "child" && it.kind == "restarted" }
        // The audit has to answer both questions a later reader will have: who
        // ordered this, and why. `restart()` alone cannot supply the actor, and
        // the actor alone cannot distinguish a crash recovery from a retry.
        assertTrue("the audit must name the requesting session", event.payload.contains("root"))
        assertTrue("the audit must carry the caller's reason", event.payload.contains("recover after crash"))
    }

    @Test
    fun `restartDescendant still records the actor when the caller gives no reason`() {
        val coordinator = coordinatorWithInterruptedChild()

        assertTrue(coordinator.restartDescendant("root", "child"))

        val event = coordinator.tree().events()
            .single { it.nodeId == "child" && it.kind == "restarted" }
        assertTrue(event.payload.contains("root"))
        assertFalse("no reason was given, so none may be invented", event.payload.contains("null"))
    }

    // ─── 5. a restart that cannot finish must not leave a lie behind ─────

    /**
     * The restart is a two-part operation: re-arm the node, then re-run the
     * agent. This covers the second part failing after the first succeeded —
     * without the rollback the tree would report a live child that nothing
     * drives, the parent would stop seeing it as interrupted, and it would wait
     * for a result that can never arrive.
     */
    @Test
    fun `a restart whose child never started is rolled back to abnormal`() {
        val coordinator = coordinatorWithInterruptedChild()
        assertTrue(coordinator.restartDescendant("root", "child"))
        assertEquals(RuntimeNodeStatus.RUNNING, coordinator.tree().node("child")?.status)

        assertTrue(
            coordinator.failRestartedChild(
                parentSessionId = "root",
                childSessionId = "child",
                reason = "restart did not start the child",
            ),
        )

        val node = requireNotNull(coordinator.tree().node("child"))
        assertEquals(
            "'nothing is running this child' has to look like 'nothing is running this child'",
            RuntimeNodeStatus.ABNORMAL_INTERRUPTION,
            node.status,
        )
        assertTrue(node.abnormal)
        assertEquals(
            RuntimeNodeStatus.ABNORMAL_INTERRUPTION.name,
            requireNotNull(coordinator.persistedNode("child")).optString("status"),
        )
    }

    @Test
    fun `the rollback leaves the child restartable again`() {
        val coordinator = coordinatorWithInterruptedChild()
        assertTrue(coordinator.restartDescendant("root", "child"))
        assertTrue(coordinator.failRestartedChild("root", "child", reason = "launch failed"))

        // A failed restart must leave the parent where it started — able to try
        // again — not in a state that merely looks better.
        assertTrue(coordinator.tree().canRestart("child"))
        assertTrue(coordinator.restartDescendant("root", "child"))
        assertEquals(RuntimeNodeStatus.RUNNING, coordinator.tree().node("child")?.status)
    }

    @Test
    fun `the rollback never touches a node that already reached a terminal state`() {
        val coordinator = coordinatorWithInterruptedChild()

        // `child` is ABNORMAL_INTERRUPTION, not RUNNING: the rollback's premise
        // ("a restart re-armed this node") does not hold, so it must refuse and
        // leave the node exactly as it is.
        assertFalse(coordinator.failRestartedChild("root", "child", reason = "no restart in flight"))
        assertEquals(
            RuntimeNodeStatus.ABNORMAL_INTERRUPTION,
            coordinator.tree().node("child")?.status,
        )
    }

    @Test
    fun `the rollback refuses a node outside the actor's own tree`() {
        val coordinator = newCoordinator()
        assertTrue(coordinator.startRoot("rootA") != null)
        assertTrue(coordinator.startChild("rootA", "childA"))
        assertTrue(coordinator.startRoot("rootB") != null)
        assertTrue(coordinator.startChild("rootB", "childB"))

        assertFalse(coordinator.failRestartedChild("rootA", "childB", reason = "cross-root rollback"))
        assertEquals(
            "a cross-root rollback must not be able to mark another tree's live child abnormal",
            RuntimeNodeStatus.RUNNING,
            coordinator.tree().node("childB")?.status,
        )
    }
}
