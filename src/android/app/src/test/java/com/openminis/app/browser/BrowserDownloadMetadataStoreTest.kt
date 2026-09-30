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

    // --- [T-browser-download-attribution] the 标签页 field ---
    //
    // These two fields (and their persistence) existed from the start, but every
    // existing record in this suite passes `null, null` for them — so nothing
    // ever verified they could survive a restart, and nothing noticed that the
    // pool never populated them. The requirement lists 标签页 among the fields
    // the download centre must record.

    @Test fun `tab and page attribution round-trips through persistence`() {
        val root = Files.createTempDirectory("browser-download-attribution").toFile()
        try {
            val workspace = File(root, "workspace").apply { mkdirs() }
            val metadata = File(root, "downloads.json")
            val target = File(workspace, "report.pdf").apply { writeBytes(ByteArray(9)) }
            val record = BrowserDownloadMetadataStore.Record(
                id = 3,
                filename = "report.pdf",
                destinationRelativePath = "report.pdf",
                bytesDone = 9,
                totalBytes = 9,
                state = "DOWNLOADING",
                failureReason = null,
                startedAt = 1_000,
                seen = false,
                sourceUrl = "https://example.org/report.pdf",
                tabId = 4,
                pageId = "0f9c1e2a-1111-2222-3333-444455556666",
            )
            assertTrue(BrowserDownloadMetadataStore.save(metadata, workspace, listOf(record)))
            val restored = BrowserDownloadMetadataStore.load(metadata, workspace).single()
            assertEquals(4, restored.tabId)
            assertEquals("0f9c1e2a-1111-2222-3333-444455556666", restored.pageId)
            assertEquals("https://example.org/report.pdf", restored.sourceUrl)
        } finally { root.deleteRecursively() }
    }

    @Test fun `a download with no recorded tab stays explicit rather than losing the field`() {
        val root = Files.createTempDirectory("browser-download-unattributed").toFile()
        try {
            val workspace = File(root, "workspace").apply { mkdirs() }
            val metadata = File(root, "downloads.json")
            File(workspace, "x.bin").writeBytes(ByteArray(1))
            val record = BrowserDownloadMetadataStore.Record(1, "x.bin", "x.bin", 1, 1,
                "DOWNLOADING", null, 5, false)
            assertTrue(BrowserDownloadMetadataStore.save(metadata, workspace, listOf(record)))
            val restored = BrowserDownloadMetadataStore.load(metadata, workspace).single()
            assertEquals(null, restored.tabId)
            assertEquals(null, restored.pageId)
        } finally { root.deleteRecursively() }
    }

    @Test fun `browser download directory is session workspace child and isolated`() {
        val root = Files.createTempDirectory("browser-download-dir").toFile()
        try {
            val workspaceA = File(root, "session-a/workspace")
            val workspaceB = File(root, "session-b/workspace")
            val downloadsA = browserDownloadDir(workspaceA)
            val downloadsB = browserDownloadDir(workspaceB)
            assertEquals(File(workspaceA, "downloads").canonicalFile, downloadsA?.canonicalFile)
            assertEquals(File(workspaceB, "downloads").canonicalFile, downloadsB?.canonicalFile)
            assertTrue(downloadsA!!.isDirectory)
            assertTrue(downloadsB!!.isDirectory)
            assertFalse(downloadsA.canonicalFile == downloadsB.canonicalFile)
            assertEquals(null, browserDownloadDir(null))
        } finally { root.deleteRecursively() }
    }

    @Test fun `downloads relative path round trips and remains inside workspace`() {
        val root = Files.createTempDirectory("browser-download-roundtrip").toFile()
        try {
            val workspace = File(root, "workspace").apply { mkdirs() }
            val downloads = browserDownloadDir(workspace)!!
            val target = File(downloads, "report.pdf").apply { writeBytes(ByteArray(2)) }
            val metadata = File(root, "downloads.json")
            val record = BrowserDownloadMetadataStore.Record(
                11, target.name, "downloads/report.pdf", 2, 2,
                "COMPLETED", null, 1, false,
            )
            assertTrue(BrowserDownloadMetadataStore.save(metadata, workspace, listOf(record)))
            val restored = BrowserDownloadMetadataStore.load(metadata, workspace).single()
            assertEquals("downloads/report.pdf", restored.destinationRelativePath)
            assertEquals("report.pdf", File(workspace, restored.destinationRelativePath).name)
            assertFalse(BrowserDownloadMetadataStore.save(
                metadata,
                workspace,
                listOf(record.copy(destinationRelativePath = "downloads/../../outside")),
            ))
        } finally { root.deleteRecursively() }
    }


    @Test fun `agent-facing line carries every field the requirement names`() {
        val line = formatDownloadEntryLine(
            BrowserTabPool.DownloadEntry(
                id = 7,
                filename = "invoice.pdf",
                destination = File("/data/app/workspace/invoice.pdf"),
                bytesDone = 12_345,
                totalBytes = 12_345,
                state = BrowserTabPool.DownloadState.COMPLETED,
                startedAt = 100,
                sessionId = "s1",
                sourceUrl = "https://shop.example/order.pdf",
                tabId = 2,
                pageId = "abcdef01-2345-6789-abcd-ef0123456789",
            ),
        )
        assertTrue("size missing: $line", line.contains("12345 bytes"))
        assertTrue("state missing: $line", line.contains("COMPLETED"))
        assertTrue("source missing: $line", line.contains("https://shop.example/order.pdf"))
        assertTrue("tab missing: $line", line.contains("tab 2"))
        assertTrue("page missing: $line", line.contains("abcdef01"))
        assertTrue("path missing: $line", line.contains("invoice.pdf"))
    }

    @Test fun `unattributed downloads say so instead of silently dropping the field`() {
        val line = formatDownloadEntryLine(
            BrowserTabPool.DownloadEntry(
                id = 8,
                filename = "blob.bin",
                destination = null,
                bytesDone = 0,
                totalBytes = 0,
                state = BrowserTabPool.DownloadState.FAILED,
                failureReason = "Cancelled",
                startedAt = 100,
                sessionId = "s1",
            ),
        )
        assertTrue("must state tab is unknown: $line", line.contains("tab unknown"))
        assertTrue("must state page is unknown: $line", line.contains("page unknown"))
        assertTrue("failure reason must be visible: $line", line.contains("error: Cancelled"))
    }
}
