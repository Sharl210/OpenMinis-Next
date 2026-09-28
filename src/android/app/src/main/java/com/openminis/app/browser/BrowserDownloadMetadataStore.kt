package com.openminis.app.browser

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** Session-scoped, bounded download history persisted beside browser tab state. */
internal object BrowserDownloadMetadataStore {
    internal const val SCHEMA_VERSION = 1
    internal const val MAX_ENTRIES = 100
    internal var atomicMoveForTest: ((java.nio.file.Path, java.nio.file.Path) -> Unit)? = null


    data class Record(
        val id: Long,
        val filename: String,
        val destinationRelativePath: String,
        val bytesDone: Long,
        val totalBytes: Long,
        val state: String,
        val failureReason: String?,
        val startedAt: Long,
        val seen: Boolean,
        val sourceUrl: String? = null,
        val tabId: Int? = null,
        val pageId: String? = null,
    )

    fun load(file: File, workspace: File): List<Record> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val root = JSONObject(file.readText())
            require(root.optInt("schemaVersion", -1) == SCHEMA_VERSION)
            val array = root.optJSONArray("downloads") ?: return emptyList()
            val records = mutableListOf<Record>()
            for (i in 0 until minOf(array.length(), MAX_ENTRIES)) {
                val row = array.optJSONObject(i) ?: continue
                val path = row.optString("destination", "")
                val resolved = resolveDestination(workspace, path) ?: continue
                val state = row.optString("state", "")
                if (state !in setOf("DOWNLOADING", "COMPLETED", "FAILED")) continue
                val expectedBytes = row.optLong("bytesDone", 0L).coerceAtLeast(0L)
                val totalBytes = row.optLong("totalBytes", 0L).coerceAtLeast(0L)
                val exists = resolved.isFile
                val actualBytes = if (exists) resolved.length() else 0L
                val reconciledState = when {
                    !exists -> "FAILED"
                    state == "DOWNLOADING" && totalBytes > 0L && actualBytes >= totalBytes -> "COMPLETED"
                    state == "DOWNLOADING" -> "FAILED"
                    state == "COMPLETED" && totalBytes > 0L && actualBytes < totalBytes -> "FAILED"
                    else -> state
                }
                val reason = when {
                    reconciledState == "FAILED" && !exists -> "File missing after restart"
                    reconciledState == "FAILED" && state == "DOWNLOADING" -> "Incomplete download after restart"
                    reconciledState == "FAILED" && state == "COMPLETED" -> "Downloaded file is shorter than expected"
                    else -> row.optString("failureReason").takeIf { it.isNotBlank() && it != "null" }
                }
                records += Record(
                    id = row.optLong("id", 0L),
                    filename = row.optString("filename", resolved.name).take(512),
                    destinationRelativePath = path,
                    bytesDone = if (reconciledState == "COMPLETED") actualBytes else expectedBytes,
                    totalBytes = totalBytes,
                    state = reconciledState,
                    failureReason = reason,
                    startedAt = row.optLong("startedAt", 0L).coerceAtLeast(0L),
                    seen = row.optBoolean("seen", false),
                    sourceUrl = row.optString("sourceUrl").takeIf { it.isNotBlank() && it != "null" },
                    tabId = if (row.has("tabId") && !row.isNull("tabId")) row.optInt("tabId") else null,
                    pageId = row.optString("pageId").takeIf { it.isNotBlank() && it != "null" },
                )
            }
            records
        }.getOrDefault(emptyList())
    }

    fun save(file: File, workspace: File, records: List<Record>): Boolean {
        return runCatching {
            file.parentFile?.mkdirs()
            val array = JSONArray()
            records.take(MAX_ENTRIES).forEach { record ->
                val safePath = resolveDestination(workspace, record.destinationRelativePath)
                    ?: error("download path escapes workspace")
                val row = JSONObject()
                    .put("id", record.id)
                    .put("filename", record.filename.take(512))
                    .put("destination", relativePath(workspace, safePath))
                    .put("bytesDone", record.bytesDone.coerceAtLeast(0L))
                    .put("totalBytes", record.totalBytes.coerceAtLeast(0L))
                    .put("state", record.state)
                    .put("failureReason", record.failureReason ?: JSONObject.NULL)
                    .put("startedAt", record.startedAt.coerceAtLeast(0L))
                    .put("seen", record.seen)
                    .put("sourceUrl", record.sourceUrl ?: JSONObject.NULL)
                    .put("tabId", record.tabId ?: JSONObject.NULL)
                    .put("pageId", record.pageId ?: JSONObject.NULL)
                array.put(row)
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            FileOutputStream(tmp).use { output ->
                output.write(JSONObject().put("schemaVersion", SCHEMA_VERSION).put("downloads", array).toString().toByteArray())
                output.flush()
                output.fd.sync()
            }
            try {
                atomicMoveForTest?.invoke(tmp.toPath(), file.toPath()) ?: java.nio.file.Files.move(
                    tmp.toPath(), file.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: Exception) {
                // Preserve the previous complete metadata file if atomic replacement is unsupported.
                tmp.delete()
                return false
            }
            true
        }.getOrDefault(false)
    }

    fun relativePath(workspace: File, destination: File): String {
        val root = workspace.canonicalFile
        val child = destination.canonicalFile
        require(child.toPath().startsWith(root.toPath())) { "destination outside workspace" }
        return root.toPath().relativize(child.toPath()).toString().replace(File.separatorChar, '/')
    }

    private fun resolveDestination(workspace: File, relative: String): File? = runCatching {
        if (relative.isBlank() || relative.startsWith('/') || relative.contains('\\')) return null
        val root = workspace.canonicalFile
        val child = File(root, relative).canonicalFile
        if (child == root || !child.toPath().startsWith(root.toPath())) null else child
    }.getOrNull()
}
