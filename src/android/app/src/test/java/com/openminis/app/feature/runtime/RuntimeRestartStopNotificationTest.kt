package com.openminis.app.feature.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-child-restart] A node can now stop MORE THAN ONCE, and the stop
 * notification has to keep up.
 *
 * Before restart existed a node's stop was a once-in-a-lifetime event, so
 * `SessionTreeRuntime` could safely remember "this node was already reported
 * stopped" in `stopNotificationSentForNodeIds` and swallow every later attempt
 * (`notifyParentOnStop` starts with `if (!stopNotificationSentForNodeIds.add(nodeId)) return`).
 * Its only reset lived in `removeNodes`, i.e. the node ceasing to exist.
 *
 * Restart breaks that assumption: interplay, two stops become possible for the
 * same node, and a parent that is told about the first one and never about the
 * second will believe the child is still alive — while the child is terminal and
 * nothing will ever move it again. The requirement is explicit that a stopping
 * child notifies its direct parent (request.md:248) and that "stopped" means the
 * child and its whole subtree stopped (request.md:250), so a second stop needs a
 * second notification.
 *
 * The dedup itself is CORRECT and must survive: a node that stops once is
 * reported once. Only the "across a restart" dimension needs resetting. Both
 * halves are asserted below, because fixing the first by deleting the dedup
 * would be an equally wrong outcome.
 */
class RuntimeRestartStopNotificationTest {

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
        val dir = Files.createTempDirectory("runtime-restart-stop-notify").toFile()
        return coordinator(store(dir))
    }

    private fun RuntimeSessionCoordinator.store(): RuntimeTreeStore {
        val field = RuntimeSessionCoordinator::class.java.getDeclaredField("store")
        field.isAccessible = true
        return field.get(this) as RuntimeTreeStore
    }

    private fun RuntimeSessionCoordinator.tree(): RuntimeSessionTree = store().snapshot()

    private fun RuntimeSessionCoordinator.restartPersisted(nodeId: String, reason: String = ""): Boolean {
        var result = false
        store().update { result = restart(nodeId, reason) }
        return result
    }

    /** Root + one running child, stopped the way a crash leaves it. */
    private fun coordinatorWithInterruptedChild(): RuntimeSessionCoordinator {
        val coordinator = newCoordinator()
        assertTrue(coordinator.startRoot("root") != null)
        assertTrue(coordinator.startChild("root", "child"))
        coordinator.store().update { abort("child", abnormal = true, reason = "lease expired") }
        return coordinator
    }

    /** How many stop notifications the parent has actually been sent. */
    private fun RuntimeSessionCoordinator.stopNotificationsToParent(): Int =
        tree().events().count { it.kind == "child_stop_notification_enqueued" }

    /**
     * The core regression: one stop, one notification; a restart, then a second
     * stop, must produce a second notification — otherwise the parent is left
     * believing a child it was told had stopped is still running.
     */
    @Test
    fun `a child that stops after being restarted notifies its parent a second time`() {
        val coordinator = coordinatorWithInterruptedChild()
        assertEquals("the first stop must be reported once", 1, coordinator.stopNotificationsToParent())

        assertTrue(coordinator.restartPersisted("child", reason = "crash recovery"))
        assertTrue(coordinator.tree().abort("child", abnormal = true, reason = "interrupted again"))

        assertEquals(
            "the parent was told about the first stop; without a second notification it never " +
                "learns the restarted child stopped again and will believe it is still alive",
            2,
            coordinator.stopNotificationsToParent(),
        )
    }

    /**
     * The dedup has to stay: a node that stops ONCE is reported ONCE. Deleting
     * the guard to make the test above pass would spam the parent on every
     * `maybeNotifyWhenSettled` walk instead.
     */
    @Test
    fun `a single stop is still reported exactly once`() {
        val coordinator = coordinatorWithInterruptedChild()

        // Settle the chain several more times; a node already reported must not
        // be reported again.
        repeat(3) { coordinator.tree().settleForTest("child") }

        assertEquals("one stop means one notification, not four", 1, coordinator.stopNotificationsToParent())
    }

    @Test
    fun `a second stop without a restart in between is still deduplicated`() {
        val coordinator = coordinatorWithInterruptedChild()

        // abort() refuses an already-terminal node, so this cannot even land a
        // second state change; the notification count must not move either.
        assertTrue(!coordinator.tree().abort("child", abnormal = true, reason = "second abort"))
        assertEquals(1, coordinator.stopNotificationsToParent())
    }

    /**
     * The stale-report half of the same defect.
     *
     * `maybeNotifyWhenSettled` only removes `pendingStopReportByNodeId` on the
     * branch that actually notifies, and it returns early while any descendant is
     * still active. So a node that stops while a child of its own is still running
     * leaves its report QUEUED and undelivered — that is correct, the subtree has
     * not settled yet.
     *
     * But if that node is then restarted, the queued report describes a run that
     * is over: delivering it later would tell the parent "this child stopped" when
     * the child is running again. A restart has to drop it.
     *
     * The active grandchild is what makes this state reachable; without it the
     * report would already have been consumed by the settle walk and the assertion
     * would hold vacuously.
     */
    @Test
    fun `a restart clears a stop report left queued by the previous run`() {
        val coordinator = newCoordinator()
        assertTrue(coordinator.startRoot("root") != null)
        assertTrue(coordinator.startChild("root", "child"))
        assertTrue(coordinator.startChild("child", "grandchild"))
        // The child stops while its own child is still running, so the settle walk
        // holds the report back rather than delivering it.
        coordinator.store().update { abort("child", abnormal = true, reason = "lease expired") }

        assertNotNull(
            "the interrupted run's stop report is still queued — the subtree has not settled",
            coordinator.tree().pendingStopReportForTest("child"),
        )

        assertTrue(coordinator.restartPersisted("child", reason = "crash recovery"))

        assertNull(
            "the previous run's stop report must not survive into the next run, or a later " +
                "settle would report the child as stopped while it is running again",
            coordinator.tree().pendingStopReportForTest("child"),
        )
    }

    @Test
    fun `the restarted child still notifies with the report of its own stop`() {
        val coordinator = coordinatorWithInterruptedChild()
        assertTrue(coordinator.restartPersisted("child", reason = "crash recovery"))

        assertTrue(coordinator.tree().abort("child", abnormal = true, reason = "second interruption"))

        val reports = coordinator.tree().events()
            .filter { it.kind == "child_stop_notification_enqueued" }
        assertEquals(2, reports.size)
        // The second notification must describe the SECOND stop, not resurrect
        // the first run's "lease expired".
        assertTrue(
            "the second notification must carry the new stop's reason",
            reports.last().payload.contains("second interruption"),
        )
    }

    /**
     * The third piece of the same bookkeeping: an abandoned stop REQUEST.
     *
     * `stopDescendant` parks the operation id in `pendingStopOperationByNodeId`
     * and leaves the node STOP_REQUESTED — which is terminal. Restarting that node
     * abandons the request, but the id stays parked, and the next `abort` consumes
     * it to stamp `stateAfter` onto that OLD receipt. A brand-new stop then gets
     * filed under someone else's operation id, and whoever polls receipt `op-1`
     * sees it report an outcome that belongs to a different run.
     */
    @Test
    fun `a restart does not let a later stop be filed under an abandoned stop request`() {
        val coordinator = newCoordinator()
        assertTrue(coordinator.startRoot("root") != null)
        assertTrue(coordinator.startChild("root", "child"))
        val request = coordinator.stopDescendant(
            actorSessionId = "root",
            targetSessionId = "child",
            reason = "asked to stop",
            operationId = "op-1",
            idempotencyKey = "op-1",
        )
        assertTrue("the stop request must be accepted", request.accepted)
        assertEquals(
            RuntimeNodeStatus.STOP_REQUESTED,
            coordinator.tree().node("child")?.status,
        )

        assertTrue(coordinator.restartPersisted("child", reason = "restart instead of stopping"))
        coordinator.store().update { abort("child", abnormal = true, reason = "lease expired") }

        assertEquals(
            "receipt op-1 belongs to the abandoned stop request; a later, unrelated abort must " +
                "not overwrite its outcome as if that request had produced it",
            RuntimeNodeStatus.STOP_REQUESTED,
            coordinator.tree().stopReceipt("op-1")?.stateAfter,
        )
    }
}

/**
 * Read access to the stop report a node has queued but not yet delivered.
 *
 * `pendingStopReportByNodeId` is private to [RuntimeSessionTree]; the test needs
 * to observe "a restart dropped the stale report", and asserting on the public
 * event log cannot distinguish "no report pending" from "a report pending that
 * would be delivered later". Reflection keeps the production surface unchanged.
 */
private fun RuntimeSessionTree.pendingStopReportForTest(nodeId: String): RuntimeStopReport? {
    val field = RuntimeSessionTree::class.java.getDeclaredField("pendingStopReportByNodeId")
    field.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val map = field.get(this) as Map<String, RuntimeStopReport>
    return map[nodeId]
}

/**
 * Run the settle walk for [nodeId] again.
 *
 * `maybeNotifyWhenSettled` is private: it is driven from `complete()` / `abort()`
 * and has no public entry point. The dedup test needs to walk it a second time on
 * an ALREADY-settled node, which no public call can produce (both `complete` and
 * `abort` refuse a terminal node), so the walk is invoked directly rather than
 * reached through a contrived state change.
 */
private fun RuntimeSessionTree.settleForTest(nodeId: String) {
    val method = RuntimeSessionTree::class.java
        .getDeclaredMethod("maybeNotifyWhenSettled", String::class.java)
    method.isAccessible = true
    method.invoke(this, nodeId)
}
