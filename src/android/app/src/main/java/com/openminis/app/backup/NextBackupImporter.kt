package com.openminis.app.backup

import com.openminis.app.data.NextDataRoot
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID
import java.util.zip.ZipInputStream

/** Stages, validates, then swaps a Next payload directory; legacy backups are never accepted. */
class NextBackupImporter(
    private val maxEntries: Int = 4096,
    private val maxTotalBytes: Long = 1L shl 30,
) {
    fun currentRestoredDataDirectory(filesDir: File): File? =
        runCatching {
            val root = NextDataRoot.root(filesDir).canonicalFile
            val versions = NextDataRoot.restoredVersionsDirectory(filesDir).canonicalFile
            val pointer = NextDataRoot.restoredCurrentPointerFile(filesDir)
            if (!pointer.isFile) return null
            val versionId = pointer.readText(Charsets.UTF_8)
            require(versionId.isNotBlank() && versionId == versionId.trim()) { "Invalid restore pointer" }
            require(versionId == File(versionId).name && versionId != "." && versionId != "..") {
                "Invalid restore pointer"
            }
            val target = File(versions, versionId).canonicalFile
            require(target.isDirectory && target.parentFile == versions && target.path.startsWith(versions.path + File.separator)) {
                "Restore pointer target is missing or outside Next root"
            }
            require(versions.path.startsWith(root.path + File.separator)) { "Restore versions directory is outside Next root" }
            target
        }.getOrNull()

    fun restore(filesDir: File, packageFile: File): NextBackupRestoreResult {
        val candidateId = "version-${System.currentTimeMillis()}-${UUID.randomUUID()}"
        var candidate: File? = null
        var stage: File? = null
        var pointerTmp: File? = null
        try {
            val nextRoot = NextDataRoot.root(filesDir).apply { mkdirs() }.canonicalFile
            val versionsRoot = NextDataRoot.restoredVersionsDirectory(filesDir).apply { mkdirs() }.canonicalFile
            require(versionsRoot.path.startsWith(nextRoot.path + File.separator)) { "Restore versions directory is outside Next root" }
            stage = NextDataRoot.backupStagingDirectory(filesDir, "restore-${System.nanoTime()}").apply {
                require(mkdirs() || isDirectory) { "Could not create restore staging directory" }
            }
            val stageDir = checkNotNull(stage)
            val extracted = File(stageDir, "payload").apply { require(mkdirs() || isDirectory) }
            val candidateDir = NextDataRoot.restoredVersionDirectory(filesDir, candidateId).also { candidate = it }
            val pointer = NextDataRoot.restoredCurrentPointerFile(filesDir)
            val pointerTmpFile = File(nextRoot, ".${NextDataRoot.RESTORE_CURRENT_POINTER_NAME}-$candidateId.tmp").also { pointerTmp = it }
            val entries = linkedMapOf<String, ByteArray>()
            var manifestBytes: ByteArray? = null
            var totalBytes = 0L
            ZipInputStream(FileInputStream(packageFile)).use { zip ->
                var entryCount = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory) { "Directory entries are not allowed" }
                    entryCount++
                    // [T-android-next-backup-entry-budget] The `+ 1` is the
                    // manifest, and it is deliberate — NOT an off-by-one.
                    //
                    // `entryCount` counts every zip entry including
                    // `manifest.json`, and the manifest is mandatory (see the
                    // "Next package has no manifest.json" throw below). So this
                    // permits at most `maxEntries` PAYLOAD files plus one
                    // manifest.
                    //
                    // This was undocumented, and two independent readers concluded
                    // it let one extra entry through. It does not — but the cost of
                    // that ambiguity is real: "fixing" it to `entryCount <=
                    // maxEntries` would silently REJECT a legitimate backup that
                    // carries exactly `maxEntries` payload files.
                    // NextBackupEntryBudgetTest pins both directions.
                    //
                    // A package with no manifest still passes this count check and
                    // is then rejected by the manifest requirement, so the count is
                    // never the only guard.
                    require(entryCount <= maxEntries + 1) { "Package has too many entries" }
                    val path = if (entry.name == "manifest.json") "manifest.json" else NextBackupIntegrity.safeRelativePath(entry.name)
                    val bytes = zip.readBytesBounded((maxTotalBytes - totalBytes).coerceAtLeast(0))
                    totalBytes += bytes.size
                    require(totalBytes <= maxTotalBytes) { "Package exceeds size limit" }
                    if (path == "manifest.json") {
                        require(manifestBytes == null) { "Duplicate manifest" }
                        manifestBytes = bytes
                    } else {
                        require(entries.put(path, bytes) == null) { "Duplicate payload path" }
                    }
                    zip.closeEntry()
                }
            }
            val rawManifest = manifestBytes?.toString(Charsets.UTF_8)
                ?: throw BackupException("Next package has no manifest.json")
            val manifest = NextBackupExporter.json.decodeFromString<NextBackupManifest>(rawManifest)
            NextBackupFormat.requireIdentity(manifest.format)
            require(manifest.version == NextBackupFormat.VERSION) { "Unsupported Next backup version" }
            require(manifest.backupId.isNotBlank()) { "Backup ID is empty" }
            require(manifest.payloadSha256.keys == entries.keys) { "Manifest payload listing does not match package" }
            entries.forEach { (path, bytes) ->
                require(NextBackupIntegrity.safeRelativePath(path) == path)
                val expected = manifest.payloadSha256[path] ?: error("Missing payload hash")
                require(expected.matches(Regex("[0-9a-f]{64}"))) { "Invalid SHA-256 value" }
                require(NextBackupIntegrity.sha256(bytes) == expected) { "Payload integrity check failed: $path" }
                val target = NextBackupIntegrity.resolveUnder(extracted, path)
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
            }
            val refs = manifest.references.map(NextBackupIntegrity::safeRelativePath)
            require(refs.distinct().size == refs.size && refs.all { it in entries }) { "Manifest contains invalid payload references" }

            require(!candidateDir.exists()) { "Restore version already exists" }
            require(extracted.renameTo(candidateDir)) { "Could not publish candidate restore version" }
            val pointerBytes = candidateId.toByteArray(Charsets.UTF_8)
            FileOutputStream(pointerTmpFile).use { output ->
                output.write(pointerBytes)
                output.fd.sync()
            }
            try {
                Files.move(pointerTmpFile.toPath(), pointer.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (unsupported: java.nio.file.AtomicMoveNotSupportedException) {
                throw IllegalStateException("Atomic current-pointer replacement is not supported", unsupported)
            }
            stageDir.deleteRecursively()
            return NextBackupRestoreResult.Success(candidateDir, manifest.backupId)
        } catch (t: Throwable) {
            stage?.deleteRecursively()
            pointerTmp?.delete()
            val candidateDir = candidate
            if (candidateDir != null && candidateDir.exists() && currentRestoredDataDirectory(filesDir)?.canonicalFile != candidateDir.canonicalFile) {
                candidateDir.deleteRecursively()
            }
            return NextBackupRestoreResult.Failure(t.message ?: "Next restore failed")
        }
    }

    private fun ZipInputStream.readBytesBounded(remaining: Long): ByteArray {
        require(remaining >= 0)
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var count = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            count += read
            require(count <= remaining) { "Package exceeds size limit" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}

sealed interface NextBackupRestoreResult {
    data class Success(val dataDirectory: File, val backupId: String) : NextBackupRestoreResult
    data class Failure(val reason: String) : NextBackupRestoreResult
}
