package com.openminis.app.backup

import com.openminis.app.data.NextDataRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NextBackupStagedRestoreTest {
    @Test
    fun `export and staged restore round trip payload`() {
        val root = Files.createTempDirectory("next-backup-test").toFile()
        try {
            val source = mapOf("db/state.json" to "next-state".toByteArray(), "runtime/tree.json" to "{}".toByteArray())
            val packageFile = NextBackupExporter().export(root, "backup-1", source)
            val restored = NextBackupImporter().restore(root, packageFile)
            assertTrue(restored.toString(), restored is NextBackupRestoreResult.Success)
            val directory = (restored as NextBackupRestoreResult.Success).dataDirectory
            assertEquals(directory, NextBackupImporter().currentRestoredDataDirectory(root))
            assertEquals("next-state", File(directory, "db/state.json").readText())
            assertEquals("{}", File(directory, "runtime/tree.json").readText())
            assertFalse("legacy db untouched", NextDataRoot.databaseFile(root).exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `bad integrity package preserves current pointer and previous version`() {
        val root = Files.createTempDirectory("next-backup-bad-hash").toFile()
        try {
            val exporter = NextBackupExporter()
            val importer = NextBackupImporter()
            val goodPackage = exporter.export(root, "valid", mapOf("state.json" to "good".toByteArray()))
            val first = importer.restore(root, goodPackage) as NextBackupRestoreResult.Success
            val oldPointer = NextDataRoot.restoredCurrentPointerFile(root).readText()
            rewritePayload(goodPackage, "state.json", "evil".toByteArray())
            val result = importer.restore(root, goodPackage)
            assertTrue(result is NextBackupRestoreResult.Failure)
            assertEquals(oldPointer, NextDataRoot.restoredCurrentPointerFile(root).readText())
            assertEquals("good", File(first.dataDirectory, "state.json").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `corrupt and traversing current pointers are rejected without changing versions`() {
        val root = Files.createTempDirectory("next-backup-pointer").toFile()
        try {
            val importer = NextBackupImporter()
            val packageFile = NextBackupExporter().export(root, "valid", mapOf("state.json" to "good".toByteArray()))
            val success = importer.restore(root, packageFile) as NextBackupRestoreResult.Success
            val pointer = NextDataRoot.restoredCurrentPointerFile(root)
            val versionCount = NextDataRoot.restoredVersionsDirectory(root).listFiles()?.size
            listOf("../outside", "missing-version", "", "version/child").forEach { badValue ->
                pointer.writeText(badValue)
                assertEquals(null, importer.currentRestoredDataDirectory(root))
                assertTrue(success.dataDirectory.isDirectory)
                assertEquals(versionCount, NextDataRoot.restoredVersionsDirectory(root).listFiles()?.size)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `successive restores switch pointer while retaining old version`() {
        val root = Files.createTempDirectory("next-backup-versions").toFile()
        try {
            val exporter = NextBackupExporter()
            val importer = NextBackupImporter()
            val firstPackage = exporter.export(root, "first", mapOf("state.json" to "first".toByteArray()))
            val first = importer.restore(root, firstPackage) as NextBackupRestoreResult.Success
            val secondPackage = exporter.export(root, "second", mapOf("state.json" to "second".toByteArray()))
            val second = importer.restore(root, secondPackage) as NextBackupRestoreResult.Success
            assertEquals(second.dataDirectory, importer.currentRestoredDataDirectory(root))
            assertEquals("second", File(second.dataDirectory, "state.json").readText())
            assertEquals("first", File(first.dataDirectory, "state.json").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `legacy namespace and traversal path are rejected`() {
        val root = Files.createTempDirectory("next-backup-invalid").toFile()
        try {
            val file = File(root, "legacy.zip")
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write("{\"format\":\"minisbak/1\",\"version\":1,\"backupId\":\"x\",\"payloadSha256\":{},\"references\":[]}".toByteArray())
                zip.closeEntry()
            }
            assertTrue(NextBackupImporter().restore(root, file) is NextBackupRestoreResult.Failure)
            assertTrue(NextBackupIntegrity.runCatchingSafePath("../outside") == false)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun rewritePayload(file: File, path: String, replacement: ByteArray) {
        val entries = linkedMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(file.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = if (entry.name == path) replacement else zip.readBytes()
                if (entry.name == path) zip.readBytes()
                zip.closeEntry()
            }
        }
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
    }
}

private fun NextBackupIntegrity.runCatchingSafePath(path: String): Boolean =
    runCatching { safeRelativePath(path) }.isSuccess
