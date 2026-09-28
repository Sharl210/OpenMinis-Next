package com.openminis.app.browser

/** Pure browser lifecycle state used by the pool and JVM tests. */
data class BrowserTabRecord(
    val pageId: String,
    val title: String = "",
    val url: String = "",
)

enum class BrowserIdleState { ACTIVE, SLEEPING }

object BrowserIdleSleepStateMachine {
    fun state(inUse: Boolean, idleMs: Long, timeoutMs: Long): BrowserIdleState =
        if (inUse || idleMs < timeoutMs) BrowserIdleState.ACTIVE else BrowserIdleState.SLEEPING
}


/** Pure persistence ledger used when live WebViews are evicted and recreated. */
object BrowserTabPersistence {
    fun rememberEvicted(
        records: MutableMap<Int, BrowserTabRecord>,
        runtimeId: Int,
        record: BrowserTabRecord,
    ) {
        records[runtimeId] = record
    }

    fun mergeLive(
        records: MutableMap<Int, BrowserTabRecord>,
        runtimeId: Int,
        live: BrowserTabRecord,
    ): BrowserTabRecord {
        val previous = records[runtimeId]
        val merged = BrowserTabRecord(
            pageId = live.pageId.ifBlank { previous?.pageId.orEmpty() },
            title = live.title.ifBlank { previous?.title.orEmpty() },
            url = live.url.ifBlank { previous?.url.orEmpty() },
        )
        records[runtimeId] = merged
        return merged
    }
}

/** Validates minis:// session-scoped local paths without touching device-wide files. */
object MinisLocalPathPolicy {
    fun resolve(host: String, path: String): Result<String> = runCatching {
        require(host == "workspace" || host == "app") { "Unsupported minis host" }
        require(path.startsWith('/')) { "Path must be absolute within minis scope" }
        val decoded = java.net.URLDecoder.decode(path, Charsets.UTF_8.name())
        val segments = decoded.split('/')
        require(segments.none { it == ".." || it == "." }) { "Path traversal is not allowed" }
        require('\u0000' !in decoded) { "NUL is not allowed" }
        "minis://$host$decoded"
    }
}
