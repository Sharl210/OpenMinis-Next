package com.openminis.app.feature.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSessionCoordinatorDeleteSubtreeTest {
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

    @Test
    fun `deleteSubtree maps active runtime ids and preserves sibling`() {
        val dir = Files.createTempDirectory("runtime-coordinator-delete").toFile()
        val coordinator = coordinator(store(dir))
        val rootRuntimeId = coordinator.startRoot("root")
        assertTrue(rootRuntimeId != null)
        assertTrue(coordinator.startChild("root", "child"))
        assertTrue(coordinator.startChild("root", "sibling"))
        coordinator.finishChild("child")
        coordinator.finishChild("sibling")

        val receipt = coordinator.deleteSubtree(
            initiatorSessionId = "root",
            executorSessionId = "root",
            targetSessionId = "child",
            rootId = rootRuntimeId,
            operationId = "op-1",
            idempotencyKey = "key-1",
        )

        assertEquals(DeleteSubtreeResult.DELETED, receipt.result)
        assertEquals(rootRuntimeId, receipt.initiatorNodeId)
        assertEquals(rootRuntimeId, receipt.executorNodeId)
        assertEquals("child", receipt.targetNodeId)
        assertEquals(rootRuntimeId, receipt.oldParentId)
        assertTrue(receipt.affectedNodeIds.contains("child"))
        val ids = coordinatorSnapshot(coordinator).topology().nodes.map { it.id }
        assertTrue(ids.contains("sibling"))
        assertTrue(!ids.contains("child"))

        val repeated = coordinator.deleteSubtree("root", "root", "child", rootRuntimeId, "op-2", "key-1")
        assertEquals(receipt, repeated)
    }

    @Test
    fun `deleteSubtree returns persistence failure receipt`() {
        val dir = Files.createTempDirectory("runtime-coordinator-delete-failure").toFile()
        val seed = coordinator(store(dir))
        val rootRuntimeId = seed.startRoot("root")
        assertTrue(rootRuntimeId != null)
        assertTrue(seed.startChild("root", "child"))
        seed.finishChild("child")
        val coordinator = coordinator(store(dir, failing = true))
        val receipt = coordinator.deleteSubtree("root", "root", "child", rootRuntimeId, "op-fail", "key-fail")
        assertEquals(DeleteSubtreeResult.REJECTED, receipt.result)
        assertEquals("runtime tree persistence failed", receipt.reason)
        assertEquals("op-fail", receipt.operationId)
        assertEquals("key-fail", receipt.idempotencyKey)
    }

    private fun coordinatorSnapshot(coordinator: RuntimeSessionCoordinator): RuntimeSessionTree {
        val field = RuntimeSessionCoordinator::class.java.getDeclaredField("store")
        field.isAccessible = true
        return (field.get(coordinator) as RuntimeTreeStore).snapshot()
    }
}
