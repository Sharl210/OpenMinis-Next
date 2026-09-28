package com.openminis.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BrowserDownloadMetadataStoreTest {
    @Test fun `known completed size survives restart and missing files fail`() {
        val root = Files.createTempDirectory("browser-download").toFile()
        try {
            val workspace = File(root, "workspace").apply { mkdirs() }
            val metadata = File(root, "downloads.json")
            val target = File(workspace, "file.bin").apply { writeBytes(ByteArray(4)) }
            val record = BrowserDownloadMetadataStore.Record(7, target.name, "file.bin", 4, 4,
                "DOWNLOADING", null, 123, false, "https://example.org/file", null, null)
            assertTrue(BrowserDownloadMetadataStore.save(metadata, workspace, listOf(record)))
            val restored = BrowserDownloadMetadataStore.load(metadata, workspace).single()
            assertEquals("COMPLETED", restored.state)
            assertEquals(record.sourceUrl, restored.sourceUrl)
            target.delete()
            assertEquals("FAILED", BrowserDownloadMetadataStore.load(metadata, workspace).single().state)
        } finally { root.deleteRecursively() }
    }

    @Test fun `unknown total and partial files never become completed`() {
        val root = Files.createTempDirectory("browser-download-partial").toFile()
        try {
            val workspace = File(root, "workspace").apply { mkdirs() }
            File(workspace, "partial.bin").writeBytes(ByteArray(3))
            val metadata = File(root, "downloads.json")
            val base = BrowserDownloadMetadataStore.Record(1, "partial.bin", "partial.bin", 3, 8,
                "DOWNLOADING", null, 99, false)
            assertTrue(BrowserDownloadMetadataStore.save(metadata, workspace, listOf(base)))
            assertEquals("FAILED", BrowserDownloadMetadataStore.load(metadata, workspace).single().state)
            assertTrue(BrowserDownloadMetadataStore.save(metadata, workspace, listOf(base.copy(totalBytes = 0))))
            assertEquals("FAILED", BrowserDownloadMetadataStore.load(metadata, workspace).single().state)
        } finally { root.deleteRecursively() }
    }

    @Test fun `bad metadata and escaping destination are ignored`() {
        val root = Files.createTempDirectory("browser-download-bad").toFile()
        try {
            val workspace = File(root, "workspace").apply { mkdirs() }
            val metadata = File(root, "downloads.json").apply { writeText("not-json") }
            assertTrue(BrowserDownloadMetadataStore.load(metadata, workspace).isEmpty())
            val escape = BrowserDownloadMetadataStore.Record(1, "secret", "../secret", 0, 1,
                "DOWNLOADING", null, 99, false)
            assertFalse(BrowserDownloadMetadataStore.save(metadata, workspace, listOf(escape)))
            assertTrue(BrowserDownloadMetadataStore.load(metadata, workspace).isEmpty())
        } finally { root.deleteRecursively() }
    }


    @Test fun `atomic move failure preserves old bytes and cleans temp`() {
        val root = Files.createTempDirectory("browser-download-atomic").toFile()
        try {
            val workspace = File(root, "workspace").apply { mkdirs() }
            val metadata = File(root, "downloads.json")
            val target = File(workspace, "stable.bin").apply { writeBytes(ByteArray(1)) }
            val old = BrowserDownloadMetadataStore.Record(1, target.name, target.name, 1, 1,
                "COMPLETED", null, 1, true)
            assertTrue(BrowserDownloadMetadataStore.save(metadata, workspace, listOf(old)))
            val before = metadata.readBytes()
            BrowserDownloadMetadataStore.atomicMoveForTest = { _, _ -> error("injected move failure") }
            try {
                assertFalse(BrowserDownloadMetadataStore.save(metadata, workspace, listOf(old.copy(bytesDone = 2))))
            } finally {
                BrowserDownloadMetadataStore.atomicMoveForTest = null
            }
            assertEquals(before.toList(), metadata.readBytes().toList())
            assertFalse(File(metadata.parentFile, metadata.name + ".tmp").exists())
        } finally { root.deleteRecursively() }
    }
}
