package com.openminis.app.feature.runtime

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
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

        val restored = RuntimeSessionTree(clock = { 3_000L })
        assertTrue(restored.restoreJson(source.toJson()))
        assertEquals(setOf("root-a", "root-b"), restored.aggregate().rootIds)
        assertEquals(listOf("child-a"), restored.descendants("root-a").map { it.id })
        assertEquals(listOf("child-b"), restored.descendants("root-b").map { it.id })

        val text = String(restored.export("text").bytes, StandardCharsets.UTF_8)
        assertTrue(text.contains("root-a"))
        assertTrue(text.contains("root-b"))

        val zip = ZipInputStream(ByteArrayInputStream(restored.exportZip("json").bytes))
        val entries = buildSet {
            while (true) {
                val entry = zip.nextEntry ?: break
                add(entry.name)
            }
        }
        assertTrue(entries.contains("session-tree.json"))
        assertTrue(entries.contains("children/root-a/root-a/node.json"))
        assertTrue(entries.contains("children/root-a/child-a/node.json"))
        assertTrue(entries.contains("children/root-b/root-b/node.json"))
        assertTrue(entries.contains("children/root-b/child-b/node.json"))
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
}
