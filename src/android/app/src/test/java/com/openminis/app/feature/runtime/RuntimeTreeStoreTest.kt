package com.openminis.app.feature.runtime

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeTreeStoreTest {
    @Test
    fun `replace failure preserves old bytes and recoverable tree`() {
        val dir = Files.createTempDirectory("runtime-tree-store").toFile()
        val file = File(dir, "session-tree.json")
        val initial = RuntimeTreeStore.openForTest(file) { source, target ->
            Files.move(source.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        assertTrue(initial.update { createRoot("root", RuntimeModelSnapshot("p", "m")) })
        val oldBytes = file.readBytes()

        val failing = RuntimeTreeStore.openForTest(file) { _, _ ->
            error("injected replace failure")
        }
        assertFalse(failing.update { createRoot("new-root", RuntimeModelSnapshot("p", "m")) })
        assertArrayEquals(oldBytes, file.readBytes())
        assertTrue(failing.snapshot().topology().nodes.any { it.id == "root" })
        assertTrue(failing.snapshot().topology().nodes.none { it.id == "new-root" })

        val recovered = RuntimeTreeStore.openForTest(file) { source, target ->
            Files.move(source.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        assertTrue(recovered.snapshot().topology().nodes.any { it.id == "root" })
        assertTrue(recovered.snapshot().topology().nodes.none { it.id == "new-root" })
    }

    @Test
    fun `successful replace updates snapshot`() {
        val dir = Files.createTempDirectory("runtime-tree-store-success").toFile()
        val file = File(dir, "session-tree.json")
        val store = RuntimeTreeStore.openForTest(file) { source, target ->
            Files.move(source.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        assertTrue(store.update { createRoot("root", RuntimeModelSnapshot("p", "m")) })
        val oldBytes = file.readBytes()
        assertTrue(store.update { createRoot("second", RuntimeModelSnapshot("p", "m")) })
        assertFalse(oldBytes.contentEquals(file.readBytes()))
        val recovered = RuntimeTreeStore.openForTest(file) { source, target ->
            Files.move(source.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        assertTrue(recovered.snapshot().topology().nodes.any { it.id == "second" })
    }

    @Test
    fun `runtime config and revision survive store reopen`() {
        val dir = Files.createTempDirectory("runtime-tree-config").toFile()
        val file = File(dir, "session-tree.json")
        val replace: (File, File) -> Unit = { source, target ->
            Files.move(source.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        val store = RuntimeTreeStore.openForTest(file, replaceFile = replace)
        assertTrue(store.update {
            createRoot("root", RuntimeModelSnapshot("p", "m"))
            updateConfig(maxDepth = 1, maxParallelSubagents = 9, leaseMillis = 90_000L)
        })
        val expectedRevision = store.snapshot().configurationRevision()

        val reopened = RuntimeTreeStore.openForTest(file, replaceFile = replace).snapshot()
        assertEquals(1, reopened.config.maxDepth)
        assertEquals(9, reopened.config.maxParallelSubagents)
        assertEquals(90_000L, reopened.config.leaseMillis)
        assertEquals(expectedRevision, reopened.configurationRevision())
    }
}
