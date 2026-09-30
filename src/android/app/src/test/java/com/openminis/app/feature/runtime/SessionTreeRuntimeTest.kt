package com.openminis.app.feature.runtime

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTreeRuntimeTest {
    private val model = RuntimeModelSnapshot(provider = "test", model = "model")

    @Test
    fun `supports two independent roots and their children`() {
        val tree = RuntimeSessionTree(clock = { 1_000L })
        val rootA = tree.createRoot("root-a", model)
        val rootB = tree.createRoot("root-b", model)
        val childA = tree.createChild("root-a", "child-a", model).getOrThrow()
        val childB = tree.createChild("root-b", "child-b", model).getOrThrow()

        tree.start(rootA.id)
        tree.start(rootB.id)
        tree.start(childA.id)
        tree.start(childB.id)

        val aggregate = tree.aggregate()
        assertEquals(setOf("root-a", "root-b"), aggregate.rootIds)
        assertEquals("root-a", aggregate.rootId)
        assertTrue(aggregate.mainRunning)
        assertEquals(2, aggregate.activeChildCount)
        assertTrue(aggregate.activeTree)
        assertEquals(listOf("child-a"), tree.descendants("root-a").map { it.id })
        assertEquals(listOf("child-b"), tree.descendants("root-b").map { it.id })
    }

    @Test
    fun `json restore retains roots children aggregate and recursive export`() {
        val source = RuntimeSessionTree(clock = { 2_000L })
        source.createRoot("root-a", model)
        source.createRoot("root-b", model)
        source.createChild("root-a", "child-a", model).getOrThrow()
        source.createChild("root-b", "child-b", model).getOrThrow()
        source.start("root-a")
        source.start("child-a")
        source.start("child-b")
        source.addSubscription("child-a", "root-a", setOf(RuntimeEdgePermission.NOTIFY))
        source.appendTranscript("root-a", "user", "inspect the child", createdAtMillis = 2_001L)
        source.appendTranscript("child-a", "assistant", "child transcript body", metadata = "tool-result", createdAtMillis = 2_002L)

        val restored = RuntimeSessionTree(clock = { 3_000L })
        assertTrue(restored.restoreJson(source.toJson()))
        assertEquals(setOf("root-a", "root-b"), restored.aggregate().rootIds)
        assertEquals(listOf("child-a"), restored.descendants("root-a").map { it.id })
        assertEquals(listOf("child-b"), restored.descendants("root-b").map { it.id })
        assertEquals(1, restored.topology().subscriptions.size)
        assertEquals("child transcript body", restored.transcript("child-a").single().content)
        assertTrue(restored.events().any { it.kind == "transcript_appended" })

        val text = String(restored.export("text").bytes, StandardCharsets.UTF_8)
        assertTrue(text.contains("root-a"))
        assertTrue(text.contains("root-b"))
        val zip = ZipInputStream(ByteArrayInputStream(restored.exportZip("json").bytes))
        val entries = linkedMapOf<String, String>()
        zip.use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                entries[entry.name] = input.readBytes().toString(StandardCharsets.UTF_8)
            }
        }
        assertTrue(entries.keys.contains("session-tree.json"))
        assertTrue(entries.keys.contains("root-a/node.json"))
        assertTrue(entries.keys.contains("root-a/children/child-a/node.json"))
        val childJson = JSONObject(entries.getValue("root-a/children/child-a/node.json"))
        assertEquals("child transcript body", childJson.getJSONArray("transcript").getJSONObject(0).getString("content"))
        assertTrue(childJson.getJSONArray("events").length() > 0)
        assertTrue(childJson.getJSONArray("inbox").length() >= 0)
    }

    @Test
    fun `legacy single root json remains readable`() {
        val source = RuntimeSessionTree(clock = { 4_000L })
        source.createRoot("legacy-root", model)
        val json = JSONObject(source.toJson()).apply { remove("rootIds") }.toString()

        val restored = RuntimeSessionTree()
        assertTrue(restored.restoreJson(json))
        assertEquals(setOf("legacy-root"), restored.aggregate().rootIds)
        assertNotNull(restored.node("legacy-root"))
    }
    @Test
    fun `send attaches authoritative sender and receiver snapshots with config revision`() {
        val tree = RuntimeSessionTree(clock = { 5_000L })
        val root = tree.createRoot("root", model, task = "supervise")
        val child = tree.createChild(root.id, "child", model, task = "delegated task").getOrThrow()
        tree.start(root.id); tree.start(child.id)
        val first = tree.send(root.id, child.id, "inspect", RuntimeDelivery.QUEUE, taskIntent = "inspect tree")
        assertTrue(first.accepted)
        val firstJson = JSONObject(tree.toJson())
        val firstEnvelope = firstJson.getJSONArray("inbox").getJSONObject(0)
        assertEquals("root", firstEnvelope.getJSONObject("senderCapabilitySnapshot").getString("nodeId"))
        assertEquals("child", firstEnvelope.getJSONObject("capabilitySnapshot").getString("nodeId"))
        assertEquals("inspect tree", firstEnvelope.getString("taskIntent"))
        val revision = tree.configurationRevision()
        assertTrue(tree.updateConfig(maxDepth = 3, maxParallelSubagents = 7, leaseMillis = 99_000L))
        assertTrue(tree.configurationRevision() > revision)
        val second = tree.send(root.id, child.id, "inspect again", RuntimeDelivery.QUEUE)
        assertTrue(second.accepted)
        val inbox = JSONObject(tree.toJson()).getJSONArray("inbox")
        assertEquals(tree.configurationRevision(), inbox.getJSONObject(1)
            .getJSONObject("capabilitySnapshot").getLong("configRevision"))
        assertEquals(3, inbox.getJSONObject(1).getJSONObject("capabilitySnapshot").getInt("maxDepth"))
    }
    @Test
    fun `config update with mode change and no root is atomic`() {
        val tree = RuntimeSessionTree()
        val before = tree.config.copy()
        val revision = tree.configurationRevision()

        assertFalse(tree.updateConfig(maxDepth = 7, maxParallelSubagents = 9, leaseMillis = 99_000L, mode = DelegationMode.TEAM))
        assertEquals(before, tree.config)
        assertEquals(revision, tree.configurationRevision())
    }
    @Test
    fun `lowering runtime limits preserves existing nodes and events`() {
        val tree = RuntimeSessionTree(clock = { 7_000L })
        val root = tree.createRoot("root", model)
        val child = tree.createChild(root.id, "child", model).getOrThrow()
        tree.start(root.id)
        tree.start(child.id)
        val nodesBefore = tree.topology().nodes.map { it.id }.toSet()
        val eventCountBefore = JSONObject(tree.toJson()).getJSONArray("events").length()

        assertTrue(tree.updateConfig(maxDepth = 0, maxParallelSubagents = 1))
        assertEquals(nodesBefore, tree.topology().nodes.map { it.id }.toSet())
        assertEquals(eventCountBefore, JSONObject(tree.toJson()).getJSONArray("events").length())
        assertNotNull(tree.node("child"))
    }

    @Test
    fun `max parallel limit is enforced when creating active children`() {
        val tree = RuntimeSessionTree(RuntimeTreeConfig(maxDepth = 2, maxParallelSubagents = 1))
        val root = tree.createRoot("root", model)
        assertTrue(tree.createChild(root.id, "child-1", model).isSuccess)
        assertTrue(tree.start("child-1"))
        assertFalse(tree.createChild(root.id, "child-2", model).isSuccess)
    }
    @Test
    fun `restore config with missing fields preserves constructor defaults`() {
        val tree = RuntimeSessionTree(RuntimeTreeConfig(maxDepth = 1, maxParallelSubagents = 7, leaseMillis = 45_000L))
        val json = JSONObject(tree.toJson())
        json.put("config", JSONObject().put("mode", DelegationMode.TEAM.name))

        assertTrue(tree.restoreJson(json.toString()))
        assertEquals(1, tree.config.maxDepth)
        assertEquals(7, tree.config.maxParallelSubagents)
        assertEquals(45_000L, tree.config.leaseMillis)
        assertEquals(DelegationMode.TEAM, tree.config.mode)
    }

    @Test
    fun `restore rejects invalid persisted config bounds`() {
        val tree = RuntimeSessionTree()
        val json = JSONObject(tree.toJson())
        json.getJSONObject("config").put("maxParallelSubagents", 0)

        assertFalse(tree.restoreJson(json.toString()))
    }
}

