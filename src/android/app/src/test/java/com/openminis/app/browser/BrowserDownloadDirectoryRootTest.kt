package com.openminis.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [T-android-browser-download-dir] Guards the metadata ROOT for browser
 * downloads.
 *
 * Moving downloads into `workspace/downloads/` silently invalidated an
 * assumption in `registerDownload`, which derived the metadata root from
 * `dest.parentFile`. While downloads sat directly in the workspace root that
 * happened to equal the root; afterwards it became the `downloads/` subdirectory
 * itself. Consequences, none of which raised an error:
 *
 *  - `relativePath` dropped the prefix — `report.pdf` instead of
 *    `downloads/report.pdf`;
 *  - the periodic flush still used the workspace root, so ONE download got TWO
 *    different stored paths depending on which write landed last;
 *  - the loader resolves against the workspace ROOT, so a record stored in the
 *    prefix-less form pointed after a restart at `workspace/report.pdf`, which
 *    does not exist. The download would have looked lost.
 *
 * These tests pin the contract that makes all three impossible: the relative
 * path is always computed against the workspace root, so it always carries the
 * `downloads/` segment and always resolves back to the real file.
 */
class BrowserDownloadDirectoryRootTest {

    private fun ws(root: File, session: String) =
        File(root, session).apply { mkdirs() }

    private fun record(
        id: Long = 1L,
        path: String,
        filename: String = "report.pdf",
    ) = BrowserDownloadMetadataStore.Record(
        id = id,
        filename = filename,
        destinationRelativePath = path,
        bytesDone = 10L,
        totalBytes = 10L,
        state = "COMPLETED",
        failureReason = null,
        startedAt = 1_000L,
        seen = false,
    )

    @Test
    fun `a download under the downloads subdirectory keeps its prefix`() {
        val root = Files.createTempDirectory("dl-root-prefix").toFile()
        try {
            val workspace = ws(root, "session-a")
            val downloads = browserDownloadDir(workspace)!!
            val file = File(downloads, "report.pdf")

            // Computed against the workspace ROOT (the only correct root).
            val relative = BrowserDownloadMetadataStore.relativePath(workspace, file)

            assertEquals(
                "the segment that makes the file findable after a restart",
                "downloads/report.pdf",
                relative,
            )
            assertTrue(
                "deriving the root from dest.parentFile is what broke this, so a " +
                    "prefix-less path must never be produced",
                relative.contains("downloads/"),
            )
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `the prefix-less path that the old parentFile root produced would not resolve`() {
        val root = Files.createTempDirectory("dl-root-wrong").toFile()
        try {
            val workspace = ws(root, "session-a")
            val file = File(browserDownloadDir(workspace)!!, "report.pdf")
            file.writeText("x")

            // Reproduce the broken form: relative to the downloads dir itself.
            val broken = BrowserDownloadMetadataStore.relativePath(browserDownloadDir(workspace)!!, file)
            assertEquals("report.pdf", broken)

            // The loader resolves against the workspace root, so the record would
            // point at a file that is not there — the download "disappears".
            val resolved = File(workspace, broken)
            assertEquals(
                "this is the phantom path the bug produced",
                File(workspace, "report.pdf").absolutePath,
                resolved.absolutePath,
            )
            assertTrue(
                "and it is NOT where the file actually lives, which is the whole bug",
                !resolved.exists(),
            )
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `a download round-trips through save and load as the same file`() {
        val root = Files.createTempDirectory("dl-root-roundtrip").toFile()
        try {
            val workspace = ws(root, "session-a")
            val downloads = browserDownloadDir(workspace)!!
            val file = File(downloads, "report.pdf")
            file.writeText("payload")
            val metadata = File(root, "session-a.downloads.json")

            val written = BrowserDownloadMetadataStore.save(
                metadata,
                workspace,
                listOf(record(path = BrowserDownloadMetadataStore.relativePath(workspace, file))),
            )
            assertTrue("save must succeed", written)

            val restored = BrowserDownloadMetadataStore.load(metadata, workspace)
            assertEquals(1, restored.size)
            assertEquals("downloads/report.pdf", restored.single().destinationRelativePath)

            // The point of the exercise: the stored path must find the real file.
            val resolved = File(workspace, restored.single().destinationRelativePath)
            assertTrue("the restored entry must resolve to the real download", resolved.exists())
            assertEquals(file.canonicalPath, resolved.canonicalPath)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `two sessions keep independent download trees`() {
        val root = Files.createTempDirectory("dl-root-sessions").toFile()
        try {
            val a = ws(root, "session-a")
            val b = ws(root, "session-b")
            val fileA = File(browserDownloadDir(a)!!, "report.pdf")
            val fileB = File(browserDownloadDir(b)!!, "report.pdf")

            assertEquals(
                "downloads/report.pdf",
                BrowserDownloadMetadataStore.relativePath(a, fileA),
            )
            assertEquals(
                "downloads/report.pdf",
                BrowserDownloadMetadataStore.relativePath(b, fileB),
            )
            // Same relative name, different absolute files: the root is what
            // separates them, which is why the root must be the session root and
            // not a shared directory.
            assertTrue(fileA.absolutePath != fileB.absolutePath)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `a legacy download that sat in the workspace root still resolves`() {
        val root = Files.createTempDirectory("dl-root-legacy").toFile()
        try {
            val workspace = ws(root, "session-a")
            val legacyFile = File(workspace, "old.pdf")
            legacyFile.writeText("x")
            val metadata = File(root, "session-a.downloads.json")

            // Records written before the change carry a root-level path.
            BrowserDownloadMetadataStore.save(metadata, workspace, listOf(record(path = "old.pdf")))

            val restored = BrowserDownloadMetadataStore.load(metadata, workspace)
            assertEquals("old.pdf", restored.single().destinationRelativePath)
            assertTrue(
                "existing users' downloads must keep working",
                File(workspace, restored.single().destinationRelativePath).exists(),
            )
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `a traversal attempt is still rejected from inside the downloads directory`() {
        val root = Files.createTempDirectory("dl-root-escape").toFile()
        try {
            val workspace = ws(root, "session-a")
            val metadata = File(root, "session-a.downloads.json")

            // The guard is unchanged by the new layout, and must still hold for
            // paths that start with the new prefix.
            BrowserDownloadMetadataStore.save(
                metadata,
                workspace,
                listOf(record(path = "downloads/../../escape.pdf")),
            )

            val restored = BrowserDownloadMetadataStore.load(metadata, workspace)
            val escaped = restored.firstOrNull()?.let { record ->
                File(workspace, record.destinationRelativePath).canonicalFile
            }
            assertTrue(
                "an escaping path must never resolve outside the workspace: $escaped",
                escaped == null || escaped.toPath().startsWith(workspace.canonicalFile.toPath()),
            )
            assertNotNull("save must not crash on a hostile path", restored)
        } finally { root.deleteRecursively() }
    }
}
