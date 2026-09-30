package com.openminis.app.backup

import com.openminis.app.data.NextDataRoot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * [T-android-next-backup-entry-budget] Pins what the entry budget ACTUALLY means.
 *
 * `NextBackupImporter` guards the package with
 * `require(entryCount <= maxEntries + 1)`, and `entryCount` counts every zip
 * entry including `manifest.json` (which is mandatory). So the budget is
 * `maxEntries` PAYLOAD files plus one manifest.
 *
 * Two independent readers of that line concluded it let an extra file through.
 * It does not — but the ambiguity is the hazard: the "obvious fix" of dropping
 * the `+ 1` would silently reject a legitimate backup carrying exactly
 * `maxEntries` payload files. These tests pin both directions so neither the
 * current form nor that "fix" can be adopted by accident.
 *
 * There were no tests for this budget at all before.
 */
class NextBackupEntryBudgetTest {

    private val maxEntries = 3

    private var seedCounter = 0

    /** Builds a well-formed package (real exporter) then rewrites it with extra entries. */
    private fun rewriteWithPayloadCount(root: File, payloadCount: Int, includeManifest: Boolean = true): File {
        // Use the real exporter for a valid package, then rebuild the zip with the
        // requested number of payload members. Going through the exporter keeps the
        // manifest format honest instead of hand-rolling JSON the importer would
        // reject for unrelated reasons.
        //
        // The backupId must be unique per call: the exporter refuses to overwrite
        // an existing backup ("Next backup already exists"), so a fixed id makes
        // the second call in one test fail for an unrelated reason.
        val genuine = NextBackupExporter().export(
            root,
            "seed-${seedCounter++}",
            mapOf("seed.txt" to "s".toByteArray()),
        )
        val seedManifest = readManifestBytes(genuine)

        val out = File(root, "package-$payloadCount-${includeManifest}.zip")
        ZipOutputStream(out.outputStream()).use { zip ->
            if (includeManifest) {
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(seedManifest ?: error("seed package had no manifest"))
                zip.closeEntry()
            }
            repeat(payloadCount) { index ->
                zip.putNextEntry(ZipEntry("payload/file-$index.txt"))
                zip.write("x".toByteArray())
                zip.closeEntry()
            }
        }
        return out
    }

    private fun readManifestBytes(zipFile: File): ByteArray? {
        java.util.zip.ZipInputStream(zipFile.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return null
                if (entry.name == "manifest.json") return zip.readBytes()
                zip.closeEntry()
            }
        }
    }

    /**
     * `restore` reports failure by RETURNING [NextBackupRestoreResult.Failure],
     * never by throwing — it catches `Throwable` internally. Reading it through
     * `runCatching{}.exceptionOrNull()` therefore sees success for every failed
     * restore, which is what my first draft of this test did.
     */
    private fun restoreFailureReason(root: File, packageFile: File): String? =
        (NextBackupImporter(maxEntries = maxEntries).restore(root, packageFile)
            as? NextBackupRestoreResult.Failure)?.reason

    private fun restoreFailsWithTooManyEntries(root: File, packageFile: File): Boolean =
        restoreFailureReason(root, packageFile)?.contains("too many entries") == true

    @Test
    fun `the budget allows exactly maxEntries payload files plus the manifest`() {
        val root = Files.createTempDirectory("next-budget-ok").toFile()
        try {
            // maxEntries payload + 1 manifest = maxEntries + 1 total entries. This
            // MUST be accepted: it is the documented shape of a full package.
            val packageFile = rewriteWithPayloadCount(root, payloadCount = maxEntries)
            assertFalse(
                "a package with exactly the allowed payload count must not be " +
                    "rejected as 'too many entries'",
                restoreFailsWithTooManyEntries(root, packageFile),
            )
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `one payload file past the budget is rejected`() {
        val root = Files.createTempDirectory("next-budget-over").toFile()
        try {
            val packageFile = rewriteWithPayloadCount(root, payloadCount = maxEntries + 1)
            assertTrue(
                "one file past the budget must trip the guard",
                restoreFailsWithTooManyEntries(root, packageFile),
            )
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `the manifest does not consume a payload slot`() {
        // The whole point of the `+ 1`. If the manifest were charged against the
        // budget, a full package would be rejected one file early — which is the
        // regression that dropping `+ 1` would reintroduce.
        val root = Files.createTempDirectory("next-budget-manifest").toFile()
        try {
            val atLimit = rewriteWithPayloadCount(root, payloadCount = maxEntries)
            val overLimit = rewriteWithPayloadCount(root, payloadCount = maxEntries + 1)
            assertFalse(restoreFailsWithTooManyEntries(root, atLimit))
            assertTrue(restoreFailsWithTooManyEntries(root, overLimit))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `a package with no manifest is rejected for the missing manifest`() {
        // The count check alone must not be the guard: without a manifest the
        // budget could in principle be spent entirely on payload, and the package
        // still has to fail — for the RIGHT reason, so the error is diagnosable.
        val root = Files.createTempDirectory("next-budget-nomanifest").toFile()
        try {
            val packageFile = rewriteWithPayloadCount(root, payloadCount = 1, includeManifest = false)
            val reason = restoreFailureReason(root, packageFile)
            assertTrue("expected a rejection", reason != null)
            assertTrue(
                "must name the missing manifest rather than reporting a size problem: $reason",
                reason.orEmpty().contains("manifest"),
            )
            assertFalse("legacy db must stay untouched", NextDataRoot.databaseFile(root).exists())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `a rejected oversized package leaves no restored version behind`() {
        val root = Files.createTempDirectory("next-budget-cleanup").toFile()
        try {
            val packageFile = rewriteWithPayloadCount(root, payloadCount = maxEntries + 5)
            restoreFailsWithTooManyEntries(root, packageFile)
            // A rejected import must not publish anything: no pointer, so the
            // app keeps whatever it had.
            assertFalse(
                "a failed restore must not leave a current pointer",
                NextDataRoot.restoredCurrentPointerFile(root).exists(),
            )
        } finally { root.deleteRecursively() }
    }
}
