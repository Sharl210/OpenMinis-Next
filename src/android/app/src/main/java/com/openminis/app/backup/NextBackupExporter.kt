package com.openminis.app.backup

import com.openminis.app.data.NextDataRoot
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Next-only zip manifest. Hashes cover the exact stored payload bytes. */
@Serializable
data class NextBackupManifest(
    val format: String = NextBackupFormat.CURRENT,
    val version: Int = NextBackupFormat.VERSION,
    val backupId: String,
    val payloadSha256: Map<String, String>,
    val references: List<String>,
)

internal object NextBackupIntegrity {
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    fun safeRelativePath(value: String): String {
        require(value.isNotBlank() && value.length <= 1024) { "Invalid package path" }
        require(!value.startsWith('/') && '\\' !in value && '\u0000' !in value) { "Absolute or invalid package path" }
        val segments = value.split('/')
        require(segments.none { it.isBlank() || it == "." || it == ".." }) { "Unsafe package path" }
        require(segments.firstOrNull() != "manifest.json") { "manifest.json is reserved" }
        return value
    }

    fun resolveUnder(root: File, relative: String): File {
        val safe = safeRelativePath(relative)
        val canonicalRoot = root.canonicalFile
        val target = File(canonicalRoot, safe).canonicalFile
        require(target.toPath().startsWith(canonicalRoot.toPath())) { "Package path escapes staging directory" }
        return target
    }
}

/** Writes a Next-only package; it never calls the legacy exporter. */
class NextBackupExporter {
    fun export(filesDir: File, backupId: String, payload: Map<String, ByteArray>, references: List<String> = payload.keys.sorted()): File {
        require(backupId.isNotBlank())
        require(payload.isNotEmpty())
        val normalized = payload.mapKeys { (path, _) -> NextBackupIntegrity.safeRelativePath(path) }
        require(normalized.size == payload.size) { "Duplicate payload path" }
        val refs = references.map(NextBackupIntegrity::safeRelativePath).distinct()
        require(refs.all { it in normalized }) { "Manifest reference is missing from payload" }
        val manifest = NextBackupManifest(
            backupId = backupId,
            payloadSha256 = normalized.mapValues { (_, bytes) -> NextBackupIntegrity.sha256(bytes) },
            references = refs,
        )
        val directory = NextDataRoot.backupDirectory(filesDir).apply { mkdirs() }
        val target = File(directory, "$backupId.${NextBackupFormat.FILE_EXTENSION}")
        val temp = File(directory, ".$backupId.${NextBackupFormat.FILE_EXTENSION}.tmp")
        try {
            ZipOutputStream(FileOutputStream(temp)).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(json.encodeToString(manifest).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                normalized.toSortedMap().forEach { (path, bytes) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            require(!target.exists()) { "Next backup already exists: $backupId" }
            if (!temp.renameTo(target)) error("Could not atomically publish Next backup")
            return target
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }
    }

    companion object {
        internal val json: Json = NextBackupFormat.json
    }
}
