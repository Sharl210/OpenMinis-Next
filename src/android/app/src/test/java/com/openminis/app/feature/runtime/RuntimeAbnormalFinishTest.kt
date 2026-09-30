package com.openminis.app.feature.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-child-agent-completion] A delegated child that was asked to record
 * a failure and never declared a normal end has to land in
 * [RuntimeNodeStatus.ABNORMAL_INTERRUPTION], not in [RuntimeNodeStatus.FAILED].
 *
 * FAILED means "the task failed"; ABNORMAL_INTERRUPTION means "this run stopped
 * without declaring an end" — which is what a child whose model call returned
 * 200 but never called the completion tool actually did. Routing both through
 * `complete(failed = true)` left the dedicated terminal state reachable only by
 * lease expiry, so the state existed and was never used for its own case.
 */
class RuntimeAbnormalFinishTest {

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
        val dir = Files.createTempDirectory("runtime-abnormal-finish").toFile()
        return coordinator(store(dir))
    }

    private fun RuntimeSessionCoordinator.tree(): RuntimeSessionTree {
        val field = RuntimeSessionCoordinator::class.java.getDeclaredField("store")
        field.isAccessible = true
        return (field.get(this) as RuntimeTreeStore).snapshot()
    }

    /** Seeds a root plus one running child and returns the coordinator. */
    private fun runningChild(): RuntimeSessionCoordinator {
        val coordinator = newCoordinator()
        assertTrue(coordinator.startRoot("root") != null)
        assertTrue(coordinator.startChild("root", "child"))
        return coordinator
    }

    // ─── 终态分流 ────────────────────────────────────────────────────────

    @Test
    fun `a child that never declared completion lands in ABNORMAL_INTERRUPTION`() {
        val coordinator = runningChild()

        coordinator.finishChild(
            "child",
            failed = true,
            report = RuntimeStopReport(
                nodeId = "child",
                debugInfo = "Delegated child ended without calling 'subagent_complete' (end_reason=NO_TOOL_CALL)",
                completedNormally = false,
            ),
        )

        val node = coordinator.tree().node("child")
        assertEquals(
            "an interrupted child is not a failed task",
            RuntimeNodeStatus.ABNORMAL_INTERRUPTION,
            node?.status,
        )
        // `abnormal` is the flag complete() never sets — the second, independent
        // observable that this did not go through the success/failure path.
        assertEquals(true, node?.abnormal)
    }

    @Test
    fun `a natural end still completes successfully`() {
        val coordinator = runningChild()

        coordinator.finishChild("child", failed = false)

        val node = coordinator.tree().node("child")
        assertEquals(RuntimeNodeStatus.SUCCEEDED, node?.status)
        assertEquals(false, node?.abnormal)
    }

    @Test
    fun `a failure report that claims a normal end is still recorded as FAILED`() {
        // Contradictory input: the caller says "failed" but its own report says
        // the child ended normally. The explicit failure verdict wins; only an
        // undeclared end is an interruption.
        val coordinator = runningChild()

        coordinator.finishChild(
            "child",
            failed = true,
            report = RuntimeStopReport(nodeId = "child", completedNormally = true),
        )

        assertEquals(RuntimeNodeStatus.FAILED, coordinator.tree().node("child")?.status)
        assertEquals(false, coordinator.tree().node("child")?.abnormal)
    }

    @Test
    fun `a failure with no report at all is treated as an undeclared end`() {
        // No report means nobody claimed the child ended normally, so the
        // honest terminal state is the interruption one, not a task failure.
        val coordinator = runningChild()

        coordinator.finishChild("child", failed = true)

        assertEquals(RuntimeNodeStatus.ABNORMAL_INTERRUPTION, coordinator.tree().node("child")?.status)
    }

    // ─── 父代理通知 ──────────────────────────────────────────────────────

    @Test
    fun `the abnormal stop notifies the parent and carries the end reason`() {
        val coordinator = runningChild()

        coordinator.finishChild(
            "child",
            failed = true,
            report = RuntimeStopReport(
                nodeId = "child",
                debugInfo = "Delegated child ended without calling 'subagent_complete' (end_reason=NO_TOOL_CALL)",
                lastSentBody = "please research the tree",
                completedNormally = false,
            ),
        )

        val notification = coordinator.tree().claimNextNotification("root")
        assertTrue("the parent must be told: ${notification?.payload}", notification != null)
        val metadata = JSONObject(notification!!.taskIntent)
        assertEquals("child_abnormal_stop", metadata.getString("kind"))
        assertEquals("child", metadata.getString("childNodeId"))
        assertEquals(
            true,
            metadata.getString("debugInfo").contains("end_reason=NO_TOOL_CALL"),
        )
    }

    @Test
    fun `the abnormal notification still carries the child's diagnostics`() {
        // Vehicle change, not information loss: the runtime's abort() takes only a
        // reason string, so the coordinator folds status code and error body into
        // that line instead of passing a structured stop report. The parent still
        // reads every field, in debugInfo.
        val coordinator = runningChild()

        coordinator.finishChild(
            "child",
            failed = true,
            report = RuntimeStopReport(
                nodeId = "child",
                statusCode = 503,
                errorResponse = "upstream unavailable",
                debugInfo = "provider error",
                completedNormally = false,
            ),
        )

        val metadata = JSONObject(coordinator.tree().claimNextNotification("root")!!.taskIntent)
        val debugInfo = metadata.getString("debugInfo")
        assertTrue("status code must survive: $debugInfo", debugInfo.contains("status=503"))
        assertTrue("error body must survive: $debugInfo", debugInfo.contains("upstream unavailable"))
        assertTrue("root cause must survive: $debugInfo", debugInfo.contains("provider error"))
        assertEquals("child_abnormal_stop", metadata.getString("kind"))
    }

    @Test
    fun `an oversized error body is truncated on its way into the reason`() {
        // The reason is persisted with the tree and copied into every parent
        // notification, so a megabyte error body must not ride along.
        val coordinator = runningChild()

        coordinator.finishChild(
            "child",
            failed = true,
            report = RuntimeStopReport(
                nodeId = "child",
                errorResponse = "x".repeat(50_000),
                completedNormally = false,
            ),
        )

        val debugInfo = JSONObject(coordinator.tree().claimNextNotification("root")!!.taskIntent)
            .getString("debugInfo")
        // Pinned to the real cap (ABNORMAL_REASON_MAX_CHARS = 600), not a looser
        // bound: a bound of 700 would only catch the cap being deleted outright
        // and would wave through any drift from 600 upwards.
        assertTrue("reason must be capped at 600, was ${debugInfo.length}", debugInfo.length <= 600)
        assertTrue(debugInfo.contains("delegated child stopped abnormally"))
    }

    @Test
    fun `the abnormal notification carries the response headers as a structured field`() {
        // `responseHeaders` is the one diagnostic no reason line can carry: the
        // reason is a human sentence capped at 600 chars, while headers are a set
        // of name/value pairs. Before this was pinned, the provider collected them,
        // the child runner put them in the stop report and `abort()` dropped them
        // on the floor — so `request.md:248`'s 响应头 requirement held nowhere.
        val coordinator = runningChild()

        coordinator.finishChild(
            "child",
            failed = true,
            report = RuntimeStopReport(
                nodeId = "child",
                statusCode = 503,
                errorResponse = "upstream unavailable",
                responseHeaders = mapOf("x-request-id" to "r1", "retry-after" to "30"),
                debugInfo = "provider error",
                completedNormally = false,
            ),
        )

        val metadata = JSONObject(coordinator.tree().claimNextNotification("root")!!.taskIntent)
        assertEquals("child_abnormal_stop", metadata.getString("kind"))
        val headers = metadata.optJSONObject("responseHeaders")
        assertTrue("the headers must travel as their own field, got: $metadata", headers != null)
        assertEquals("r1", headers!!.optString("x-request-id"))
        assertEquals("30", headers.optString("retry-after"))
        // The structured field must not have been flattened into the reason line.
        assertTrue(
            "the error body must still be readable in the reason: ${metadata.optString("debugInfo")}",
            metadata.getString("debugInfo").contains("upstream unavailable"),
        )
    }

    @Test
    fun `a normal completion still notifies the parent as completed`() {
        val coordinator = runningChild()

        coordinator.finishChild("child", failed = false)

        val metadata = JSONObject(coordinator.tree().claimNextNotification("root")!!.taskIntent)
        assertEquals("child_completed", metadata.getString("kind"))
        assertFalse(metadata.has("debugInfo"))
    }

    // ─── 接线与边界 ──────────────────────────────────────────────────────

    @Test
    fun `finishing an unknown child is a no-op rather than a crash`() {
        val coordinator = newCoordinator()

        coordinator.finishChild("never-started", failed = true, report = RuntimeStopReport(nodeId = "never-started", completedNormally = false))

        assertNull(coordinator.tree().node("never-started"))
    }

    @Test
    fun `the abnormal reason is preserved on the node event trail`() {
        val coordinator = runningChild()

        coordinator.finishChild(
            "child",
            failed = true,
            report = RuntimeStopReport(
                nodeId = "child",
                debugInfo = "end_reason=TURN_BUDGET_EXHAUSTED",
                completedNormally = false,
            ),
        )

        val events = coordinator.tree().events()
            .filter { it.nodeId == "child" }
            .map { it.kind }
        assertTrue("expected an abnormal_interruption event, got $events", events.contains("abnormal_interruption"))
        assertFalse("must not be recorded as a task failure", events.contains("failed"))
    }
}
