package com.openminis.app.feature.runtime

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeLifecycleReconcilerTest {
    private val model = RuntimeModelSnapshot(provider = "test", model = "model")

    @Test
    fun `reports active roots and children and reconciles expired leases`() {
        val file = Files.createTempFile("runtime-tree", ".json").toFile()
        val tree = RuntimeSessionTree(clock = { 1_000L }, config = RuntimeTreeConfig(leaseMillis = 10L))
        tree.createRoot("root-live", model)
        tree.createChild("root-live", "child-live", model).getOrThrow()
        tree.createRoot("root-stale", model)
        tree.createChild("root-stale", "child-stale", model).getOrThrow()
        tree.start("root-live")
        tree.start("child-live")
        tree.start("root-stale")
        tree.start("child-stale")
        tree.heartbeat("root-live", nowMillis = 1_100L)
        tree.heartbeat("child-live", nowMillis = 1_100L)
        file.writeText(tree.toJson(), StandardCharsets.UTF_8)

        val restored = RuntimeSessionTree(clock = { 1_000L })
        var persistCalls = 0
        val result = RuntimeLifecycleReconciler(restored, file) { persistCalls++; true }
            .reconcile(nowMillis = 1_011L)

        assertEquals(RuntimeLifecycleRecoveryState.RECONCILED, result.state)
        assertEquals(listOf("root-live"), result.activeRootIds)
        assertEquals(listOf("child-live"), result.activeChildIds)
        assertEquals(setOf("root-stale", "child-stale"), result.staleNodeIds.toSet())
        assertEquals(RuntimeNodeStatus.ABNORMAL_INTERRUPTION, restored.node("root-stale")?.status)
        assertEquals(RuntimeNodeStatus.ABNORMAL_INTERRUPTION, restored.node("child-stale")?.status)
        assertEquals(1, persistCalls)
    }

    @Test
    fun `stop requested lease expires abnormally while explicit aborted stays terminal`() {
        val file = Files.createTempFile("runtime-tree-stop", ".json").toFile()
        val tree = RuntimeSessionTree(clock = { 2_000L }, config = RuntimeTreeConfig(leaseMillis = 10L))
        val root = tree.createRoot("root", model)
        val liveChild = tree.createChild(root.id, "stop-target", model).getOrThrow()
        val abortedChild = tree.createChild(root.id, "aborted-target", model).getOrThrow()
        tree.start(root.id)
        tree.start(liveChild.id)
        tree.start(abortedChild.id)
        tree.stopDescendant(RuntimeStopRequest(actorNodeId = root.id, targetNodeId = liveChild.id, reason = "test"))
        tree.abort(abortedChild.id, abnormal = false, reason = "explicit abort")
        file.writeText(tree.toJson(), StandardCharsets.UTF_8)

        val restored = RuntimeSessionTree(clock = { 2_000L })
        val result = RuntimeLifecycleReconciler(restored, file) { true }.reconcile(2_011L)
        assertEquals(RuntimeNodeStatus.ABNORMAL_INTERRUPTION, restored.node(liveChild.id)?.status)
        assertEquals(RuntimeNodeStatus.ABORTED, restored.node(abortedChild.id)?.status)
        assertTrue(result.staleNodeIds.contains(liveChild.id))
        assertFalse(result.staleNodeIds.contains(abortedChild.id))
    }

    @Test
    fun `corrupt durable file is not restored persisted or allowed to replace memory baseline`() {
        val file = Files.createTempFile("runtime-tree-corrupt", ".json").toFile()
        val corrupt = "{not valid runtime json"
        file.writeText(corrupt, StandardCharsets.UTF_8)
        val baseline = RuntimeSessionTree(clock = { 3_000L })
        baseline.createRoot("baseline-root", model)
        var persistCalls = 0

        val result = RuntimeLifecycleReconciler(baseline, file) { persistCalls++; true }
            .reconcile(3_000L)

        assertEquals(RuntimeLifecycleRecoveryState.CORRUPT_BASELINE_PRESERVED, result.state)
        assertEquals(listOf("baseline-root"), baseline.topology().nodes.map { it.id })
        assertEquals(corrupt, file.readText(StandardCharsets.UTF_8))
        assertEquals(0, persistCalls)
    }
}
