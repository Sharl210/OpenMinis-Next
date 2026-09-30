package com.openminis.app.feature.runtime

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Durable, session-scoped Goal state; independent from the chat schema. */
class GoalRuntimeStore private constructor(private val file: File) {
    fun load(): GoalRuntimeSnapshot = runCatching {
        if (!file.isFile) return@runCatching GoalRuntimeSnapshot.idle()
        val j = JSONObject(file.readText(StandardCharsets.UTF_8))
        GoalRuntimeSnapshot(
            status = runCatching { GoalStatus.valueOf(j.optString("status")) }.getOrDefault(GoalStatus.IDLE),
            objective = j.optString("objective").takeIf { it.isNotBlank() },
            continuationPrompt = j.optString("continuationPrompt").takeIf { it.isNotBlank() },
            continuationDelivered = j.optBoolean("continuationDelivered"),
            continuationCount = j.optInt("continuationCount"),
            // [T-android-goal-auto-continuation] Absent in snapshots written
            // before the field existed → 0, i.e. a full budget. That is the safe
            // direction: the ceiling still applies, it just has not been used.
            autoContinuationCount = j.optInt("autoContinuationCount"),
            updatedAtMillis = j.optLong("updatedAtMillis"),
        )
    }.getOrDefault(GoalRuntimeSnapshot.idle())

    fun save(snapshot: GoalRuntimeSnapshot): Boolean = runCatching {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp-${System.nanoTime()}")
        tmp.writeText(JSONObject().apply {
            put("status", snapshot.status.name)
            put("objective", snapshot.objective ?: "")
            put("continuationPrompt", snapshot.continuationPrompt ?: "")
            put("continuationDelivered", snapshot.continuationDelivered)
            put("continuationCount", snapshot.continuationCount)
            put("autoContinuationCount", snapshot.autoContinuationCount)
            put("updatedAtMillis", snapshot.updatedAtMillis)
        }.toString(), StandardCharsets.UTF_8)
        try { Files.move(tmp.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING) }
        finally { if (tmp.exists()) tmp.delete() }
        true
    }.getOrDefault(false)

    fun delete(): Boolean = runCatching { !file.exists() || file.delete() }.getOrDefault(false)

    companion object {
        fun open(context: Context, sessionId: String): GoalRuntimeStore {
            val safe = sessionId.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("").take(160)
            return GoalRuntimeStore(File(context.filesDir, "runtime/goals/$safe.json"))
        }

        internal fun openForTest(file: File): GoalRuntimeStore = GoalRuntimeStore(file)
    }
}
