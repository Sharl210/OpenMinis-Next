package com.openminis.app.feature.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeStopNotificationTest {
    @Test
    fun `normal completion strips diagnostic fields`() {
        val metadata = RuntimeSystemMessageMetadata.from(
            RuntimeStopReport(
                nodeId = "child",
                statusCode = 200,
                errorResponse = "ignored",
                responseHeaders = mapOf("x-debug" to "ignored"),
                debugInfo = "ignored",
                lastSentBody = "secret".repeat(100),
                completedNormally = true,
            ),
        )
        val json = metadata.toJson()
        assertEquals("child_completed", metadata.kind)
        assertFalse(json.contains("errorResponse"))
        assertFalse(json.contains("responseHeaders"))
        assertFalse(json.contains("debugInfo"))
        assertFalse(json.contains("lastSentBodyTail"))
        assertFalse(json.contains("statusCode"))
    }

    @Test
    fun `abnormal report includes error fields and only last 200 chars`() {
        val body = "0123456789".repeat(30)
        val metadata = RuntimeSystemMessageMetadata.from(
            RuntimeStopReport(
                nodeId = "child",
                statusCode = 503,
                errorResponse = "gateway failed",
                responseHeaders = mapOf("x-request-id" to "r1"),
                debugInfo = "timeout",
                lastSentBody = body,
            ),
        )
        assertEquals(200, metadata.lastSentBodyTail?.length)
        assertEquals(body.takeLast(200), metadata.lastSentBodyTail)
        val json = metadata.toJson()
        assertTrue(json.contains("503"))
        assertTrue(json.contains("gateway failed"))
        assertTrue(json.contains("x-request-id"))
        assertTrue(json.contains("timeout"))
    }

    @Test
    fun `parent receives one notification and root only records audit`() {
        val tree = RuntimeSessionTree(RuntimeTreeConfig(), clock = { 100L })
        tree.createRoot("root", RuntimeModelSnapshot("p", "root"))
        tree.start("root")
        tree.createChild("root", "child", RuntimeModelSnapshot("p", "child")).getOrThrow()
        tree.start("child")
        assertTrue(tree.complete("child"))
        val first = tree.claimNextNotification("root")
        assertEquals("child_completed", first?.taskIntent?.let { org.json.JSONObject(it).getString("kind") })
        assertEquals(null, tree.claimNextNotification("root"))

        val root = RuntimeSessionTree(RuntimeTreeConfig(), clock = { 200L })
        root.createRoot("only-root", RuntimeModelSnapshot("p", "root"))
        root.start("only-root")
        assertTrue(root.complete("only-root"))
        assertTrue(root.events().any { it.kind == "child_stop_notification_unroutable" })
    }

    @Test
    fun `parent completion waits for active child and notifies once after child settles`() {
        val tree = RuntimeSessionTree(clock = { 300L })
        tree.createRoot("root", RuntimeModelSnapshot("p", "root")); tree.start("root")
        tree.createChild("root", "child", RuntimeModelSnapshot("p", "child")).getOrThrow(); tree.start("child")
        assertTrue(tree.complete("root")); assertEquals(null, tree.claimNextNotification("root"))
        assertTrue(tree.complete("child"))
        assertEquals("child_completed", tree.claimNextNotification("root")?.taskIntent?.let { org.json.JSONObject(it).getString("kind") })
        assertEquals(null, tree.claimNextNotification("root"))
    }

    @Test
    fun `grandchild delays parent and grandparent notifications`() {
        val tree = RuntimeSessionTree(clock = { 400L })
        tree.createRoot("root", RuntimeModelSnapshot("p", "root")); tree.start("root")
        tree.createChild("root", "parent", RuntimeModelSnapshot("p", "parent")).getOrThrow(); tree.start("parent")
        tree.createChild("parent", "grandchild", RuntimeModelSnapshot("p", "grandchild")).getOrThrow(); tree.start("grandchild")
        assertTrue(tree.complete("root")); assertTrue(tree.complete("parent")); assertEquals(null, tree.claimNextNotification("root"))
        assertTrue(tree.complete("grandchild"))
        assertEquals("child_completed", tree.claimNextNotification("parent")?.taskIntent?.let { org.json.JSONObject(it).getString("kind") })
        assertEquals("child_completed", tree.claimNextNotification("root")?.taskIntent?.let { org.json.JSONObject(it).getString("kind") })
    }

    @Test
    fun `waiting children and stop requested are not terminal notifications`() {
        val tree = RuntimeSessionTree(clock = { 500L })
        tree.createRoot("root", RuntimeModelSnapshot("p", "root")); tree.start("root")
        tree.createChild("root", "child", RuntimeModelSnapshot("p", "child")).getOrThrow(); tree.start("child")
        assertTrue(tree.waitForChildren("root")); assertEquals(null, tree.claimNextNotification("root"))
        assertTrue(tree.complete("child"))
        assertEquals("child_completed", tree.claimNextNotification("root")?.taskIntent?.let { org.json.JSONObject(it).getString("kind") })
    }

    @Test
    fun `aborted parent report is delayed and duplicate completion is idempotent`() {
        val tree = RuntimeSessionTree(clock = { 600L })
        tree.createRoot("root", RuntimeModelSnapshot("p", "root")); tree.start("root")
        tree.createChild("root", "child", RuntimeModelSnapshot("p", "child")).getOrThrow(); tree.start("child")
        assertTrue(tree.abort("root", abnormal = true, reason = "parent failed")); assertEquals(null, tree.claimNextNotification("root"))
        assertTrue(tree.abort("child", abnormal = false))
        // [T-android-stop-request-kind] `abnormal = false` IS the deliberate-stop
        // verdict (ABORTED, not ABNORMAL_INTERRUPTION) — it is what `abort` is called
        // with on the requested-stop and subtree-purge paths. The kind therefore says
        // "on request" rather than "abnormal", which is the point of the distinction:
        // the two cases used to share one kind because a deliberate stop and a crash
        // both report `completedNormally = false`.
        assertEquals("child_stopped_by_request", tree.claimNextNotification("root")?.taskIntent?.let { org.json.JSONObject(it).getString("kind") })
        assertEquals(false, tree.complete("root")); assertEquals(null, tree.claimNextNotification("root"))
    }
}
