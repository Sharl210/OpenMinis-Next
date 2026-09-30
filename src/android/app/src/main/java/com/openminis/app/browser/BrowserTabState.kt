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

    /**
     * The complete decision to sleep a tab, as a pure function.
     *
     * [T-browser-idle-eviction-conditions] This exists so the eviction rules can
     * be tested at all. The evictor used to decide inline inside a method that
     * needs an Android `Context` and a live `WebView`, so **no test ever
     * exercised it** — which is exactly how a tab the user was reading, and a
     * user-visible download in flight, both got destroyed by an idle timer
     * without anyone noticing. One pure predicate, one place to test.
     *
     * Sleep is permitted only when nothing is happening to the page. The
     * requirement lists the conditions explicitly: the model is not executing
     * anything, the user has not opened it to look, and no script is running
     * against it.
     *
     * @param isUserViewing the tab the user currently has on screen
     * @param anyDownloadInFlight a download anywhere in the pool is still
     *        transferring; blob:/data: downloads are fetched through the page's
     *        WebView, so evicting during one silently loses the transfer and
     *        leaves no record at all
     */
    fun shouldSleep(
        inUse: Boolean,
        idleMs: Long,
        timeoutMs: Long,
        isUserViewing: Boolean,
        anyDownloadInFlight: Boolean,
    ): Boolean {
        if (isUserViewing) return false
        if (anyDownloadInFlight) return false
        return state(inUse = inUse, idleMs = idleMs, timeoutMs = timeoutMs) == BrowserIdleState.SLEEPING
    }
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
