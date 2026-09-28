package com.openminis.app.feature.runtime

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
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
}
