package com.openminis.app.backup

import com.openminis.app.data.NextDataRoot
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class NextDataBoundaryTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = File.createTempFile("openminis-next-boundary", "").apply {
            delete()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun writeManifest(format: String): File = File(root, "manifest.json").apply {
        writeText("{\"format\":\"$format\",\"backup_id\":\"next-test\"}")
    }

    @Test
    fun `legacy minisbak namespace is rejected by Next reader`() {
        writeManifest("minisbak/1")

        assertBackupRejected { NextBackupPackageReader(root).readManifest() }
    }

    @Test
    fun `unknown namespace is rejected by Next reader`() {
        writeManifest("other-backup/1")

        assertBackupRejected { NextBackupPackageReader(root).readManifest() }
    }

    @Test
    fun `current Next identity is accepted`() {
        writeManifest(NextBackupFormat.CURRENT)

        val manifest = NextBackupPackageReader(root).readManifest()

        assertEquals(NextBackupFormat.CURRENT, manifest.format)
        assertEquals("next-test", manifest.backupId)
    }

    @Test
    fun `Next metadata rejects legacy numeric epoch timestamp`() {
        assertBackupRejected {
            NextBackupFormat.decodeEnvVarMeta(
                """{"id":"env-1","key":"TOKEN","createdAt":1700000000000}""",
            )
        }
    }

    @Test
    fun `Next metadata accepts strict ISO timestamp`() {
        val metadata = NextBackupFormat.decodeEnvVarMeta(
            """{"id":"env-1","key":"TOKEN","createdAt":"2023-11-14T22:13:20Z"}""",
        )

        assertEquals(1_700_000_000_000L, metadata.createdAt)
    }

    @Test
    fun `Next paths are stable and remain separate from legacy data`() {
        val filesDir = File(root, "files")

        assertEquals(
            File(filesDir, "openminis-next").path,
            NextDataRoot.root(filesDir).path,
        )
        assertEquals(
            File(filesDir, "openminis-next/openminis-next.db").path,
            NextDataRoot.databaseFile(filesDir).path,
        )
        assertEquals(
            File(filesDir, "openminis-next/runtime/session-tree.json").path,
            NextDataRoot.runtimeTreeFile(filesDir).path,
        )
        assertEquals(
            File(filesDir, "openminis-next/backups").path,
            NextBackupStorage.packagesDirectory(filesDir).path,
        )
        assertEquals(
            File(filesDir, "openminis-next/backup-staging/backup-1").path,
            NextBackupStorage.stagingDirectory(filesDir, "backup-1").path,
        )
        assertEquals(
            File(filesDir, "openminis-next/backup-history/records.json").path,
            NextBackupStorage.historyFile(filesDir).path,
        )
        assertTrue(NextDataRoot.DATABASE_NAME != "minis.db")
    }

    @Test
    fun `Next staging path rejects traversal`() {
        assertIllegalArgument { NextDataRoot.backupStagingDirectory(root, "../escape") }
        assertIllegalArgument { NextDataRoot.backupStagingDirectory(root, "nested/id") }
    }

    private fun assertBackupRejected(block: () -> Unit) {
        try {
            block()
            fail("expected BackupException")
        } catch (_: BackupException) {
            // expected
        }
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
