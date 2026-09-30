package com.openminis.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Durable failed-cleanup ledger; records names only, never payloads. */
class SessionDeletionRetryStore private constructor(private val file: File) {
    @Synchronized
    fun load(sessionId: String): Set<String> = runCatching {
        if (!file.isFile) return@runCatching emptySet()
        val rows = JSONObject(file.readText(StandardCharsets.UTF_8)).optJSONObject("sessions") ?: return@runCatching emptySet()
        val array = rows.optJSONArray(sessionId) ?: return@runCatching emptySet()
        buildSet { for (i in 0 until array.length()) add(array.optString(i)) }
    }.getOrDefault(emptySet())

    @Synchronized
    fun save(sessionId: String, steps: Set<String>): Boolean = update(sessionId, steps.takeIf { it.isNotEmpty() })

    /**
     * Every session id that still owes at least one step.
     *
     * A retry has to be *reachable*: once the chat rows of a subtree are gone,
     * rebuilding the delete set from the database can no longer produce those
     * ids, so a failed artifact step would be recorded and then never revisited.
     * Callers union this into the delete set so the ledger is the authority on
     * what is still owed, not the surviving rows.
     */
    @Synchronized
    fun ids(): Set<String> = runCatching {
        if (!file.isFile) return@runCatching emptySet()
        val rows = JSONObject(file.readText(StandardCharsets.UTF_8)).optJSONObject("sessions")
            ?: return@runCatching emptySet()
        buildSet {
            rows.keys().forEach { key ->
                val array = rows.optJSONArray(key)
                if (array != null && array.length() > 0) add(key)
            }
        }
    }.getOrDefault(emptySet())

    private fun update(sessionId: String, steps: Set<String>?): Boolean = runCatching {
        file.parentFile?.mkdirs()
        val root = if (file.isFile) JSONObject(file.readText(StandardCharsets.UTF_8)) else JSONObject()
        val sessions = root.optJSONObject("sessions") ?: JSONObject().also { root.put("sessions", it) }
        if (steps == null) sessions.remove(sessionId) else sessions.put(sessionId, JSONArray(steps.toList().sorted()))
        val tmp = File(file.parentFile, "${file.name}.tmp-${System.nanoTime()}")
        tmp.writeText(root.toString(), StandardCharsets.UTF_8)
        try { Files.move(tmp.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING) }
        finally { if (tmp.exists()) tmp.delete() }
        true
    }.getOrDefault(false)

    companion object {
        fun open(context: Context): SessionDeletionRetryStore =
            SessionDeletionRetryStore(File(context.filesDir, "runtime/session-deletion-retries.json"))

        internal fun openForTest(file: File): SessionDeletionRetryStore = SessionDeletionRetryStore(file)
    }
}
