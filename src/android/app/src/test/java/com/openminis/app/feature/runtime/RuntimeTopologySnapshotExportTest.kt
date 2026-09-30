package com.openminis.app.feature.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeTopologySnapshotExportTest {
    @Test
    fun `repeated root run is treated as same chat session for descendants`() {
        val root = RuntimeSessionNode("chat-1#run-2", null, "chat-1#run-2", 0, RuntimeModelSnapshot("p", "m"), createdAtMillis = 1, updatedAtMillis = 1)
        val child = RuntimeSessionNode("child-1", root.id, root.rootId, 1, RuntimeModelSnapshot("p", "m"), createdAtMillis = 1, updatedAtMillis = 1)
        val topology = RuntimeTopologySnapshot(listOf(root, child), emptyList(), emptyList())
        assertEquals(listOf(child), topology.descendantsForChatSession("chat-1"))
    }

    @Test
    fun `unrelated runtime root is not exported for chat session`() {
        val other = RuntimeSessionNode("other#run-1", null, "other#run-1", 0, RuntimeModelSnapshot("p", "m"), createdAtMillis = 1, updatedAtMillis = 1)
        val topology = RuntimeTopologySnapshot(listOf(other), emptyList(), emptyList())
        assertEquals(emptyList<RuntimeSessionNode>(), topology.descendantsForChatSession("chat-1"))
    }
}
