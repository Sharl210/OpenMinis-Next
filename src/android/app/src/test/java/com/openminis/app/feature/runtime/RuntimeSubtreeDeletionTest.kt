package com.openminis.app.feature.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSubtreeDeletionTest {
    private fun tree(): RuntimeSessionTree {
        val t = RuntimeSessionTree(RuntimeTreeConfig(maxDepth = 4), clock = { 10L })
        t.createRoot("root", RuntimeModelSnapshot("p", "root"))
        t.start("root")
        return t
    }

    @Test
    fun `birth chain is captured and recursive deletion preserves siblings`() {
        val t = tree()
        val a = t.createChild("root", "a", RuntimeModelSnapshot("p", "a")).getOrThrow()
        val c = t.createChild("a", "c", RuntimeModelSnapshot("p", "c")).getOrThrow()
        t.createChild("root", "sibling", RuntimeModelSnapshot("p", "s"))
        t.complete("c")
        t.complete("a")
        val receipt = t.deleteSubtree(DeleteSubtreeRequest("root", "root", "a"))
        assertEquals(DeleteSubtreeResult.DELETED, receipt.result)
        assertEquals(listOf("root", "a", "c"), c.birthChain)
        assertTrue(t.topology().nodes.none { it.id == "a" || it.id == "c" })
        assertTrue(t.topology().nodes.any { it.id == "sibling" })
        assertEquals("child_subtree_deleted", org.json.JSONObject(t.claimNextNotification("root")!!.taskIntent).getString("kind"))
        assertNotNull(t.deleteSubtreeReceipt(receipt.operationId))
    }

    @Test
    fun `self delete and unrelated actor are rejected`() {
        val t = tree()
        t.createChild("root", "a", RuntimeModelSnapshot("p", "a"))
        t.createChild("root", "other", RuntimeModelSnapshot("p", "other"))
        assertEquals(DeleteSubtreeResult.REJECTED, t.deleteSubtree(DeleteSubtreeRequest("a", "a", "a")).result)
        assertEquals(DeleteSubtreeResult.REJECTED, t.deleteSubtree(DeleteSubtreeRequest("other", "other", "a")).result)
    }

    @Test
    fun `busy subtree is rejected without partial deletion`() {
        val t = tree()
        t.createChild("root", "a", RuntimeModelSnapshot("p", "a"))
        t.start("a")
        val receipt = t.deleteSubtree(DeleteSubtreeRequest("root", "root", "a"))
        assertEquals(DeleteSubtreeResult.BUSY, receipt.result)
        assertTrue(t.topology().nodes.any { it.id == "a" })
    }

    @Test
    fun `idempotency returns same receipt and executor result is separate`() {
        val t = tree()
        t.createChild("root", "a", RuntimeModelSnapshot("p", "a"))
        t.complete("a")
        val request = DeleteSubtreeRequest("root", "root", "a", idempotencyKey = "delete-a")
        val first = t.deleteSubtree(request)
        val second = t.deleteSubtree(request.copy(operationId = "another"))
        assertEquals(first, second)
        assertFalse(t.topology().nodes.any { it.id == "a" })
        val result = t.claimNextNotification("root")
        assertNotNull(result)
        assertEquals("child_subtree_deleted", org.json.JSONObject(result!!.taskIntent).getString("kind"))
        val executorResult = t.claimNextNotification("root")
        assertNotNull(executorResult)
        assertEquals("subtree_delete_result", org.json.JSONObject(executorResult!!.taskIntent).getString("kind"))
    }
}
