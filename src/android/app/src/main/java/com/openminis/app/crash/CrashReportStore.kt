package com.openminis.app.crash

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * File naming + retention for the Java/Kotlin crash reports that
 * [CrashFileSender] drops into `filesDir/logs/`.
 *
 * Deliberately free of `Context` / ACRA so the two things that were wrong about
 * the old inline writer can be exercised by a JVM unit test; [CrashFileSender]
 * needs both of those and can't be.
 *
 * ## What was wrong
 *
 * 1. **Same-second overwrite.** The name was `crash-yyyy-MM-dd_HH-mm-ss.log` and
 *    the body went out through `File.writeText`, which truncates. Two crashes
 *    inside one wall-clock second therefore landed on the SAME path and the
 *    second one destroyed the first — the evidence for the earlier crash was
 *    gone, with nothing anywhere recording that it had happened.
 * 2. **No count limit.** One file per crash, kept forever by this writer. (An
 *    age prune does exist elsewhere — `AppLogger.pruneOldLogs()` sweeps the
 *    whole `logs/` dir at 15 days — but "how many" was unbounded, so a crash
 *    loop could put an unbounded number of stack-trace + logcat dumps on disk
 *    inside that window.)
 *
 * ## Why millis AND a unique-name loop
 *
 * Millis removes the collision for any realistic crash rate; the `-N` suffix
 * loop makes it impossible rather than improbable, and it is what turns the
 * write from "truncate whatever is there" into "never destroy an existing
 * report". Reports ARE evidence, so the writer must not be able to lose one.
 *
 * Note the difference from `ui/chat/LargeContentGuard`, where a millisecond
 * filename means a cache entry is *never reused* and therefore grows. Reuse is
 * the wrong goal here: two crashes are two distinct events and merging them
 * into one file is exactly bug 1. Unbounded growth is instead answered by
 * [MAX_REPORTS], which is why the (correct-for-a-cache) "stable name" trick
 * would have been the wrong fix on this side.
 *
 * ## Why the drop is announced
 *
 * Deleting evidence silently would be a worse bug than keeping too much. When
 * [MAX_REPORTS] forces an eviction, the count is appended to the report that
 * survives, so "N older reports were dropped" is readable in the Logs screen
 * instead of being invisible, and [CrashFileSender] also logs it. Same shape as
 * `deliveryReceipts`, which stores its `dropped` count in the JSON it keeps.
 */
internal object CrashReportStore {

    /** Matches what the Logs screen filters on (`prefix = "crash-"`). */
    const val FILE_PREFIX = "crash-"

    /**
     * How many Java/Kotlin crash reports `filesDir/logs/` keeps.
     *
     * 100 is not a fresh number: it is the Logs screen's own display cap
     * (`AppLogger.listLogFileMetas(prefix = "crash-", limit = 100)` then
     * `take(100)` in LogManagementScreen), so everything evicted here is
     * already unreachable through the UI. Reports older than the newest 100
     * were evidence nobody could get to; the newest 100 include every crash the
     * crash-share / safe-mode detectors look at, since they filter by mtime
     * inside a recent window.
     */
    const val MAX_REPORTS = 100

    // Millis so two crashes in the same second get two files. Kept as a
    // `yyyy-MM-dd_...` prefix like the daily `minis-<date>.log` files so the
    // Logs screen's name-descending sort still reads newest-first.
    private val STAMP_FMT = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US)

    /** Timestamp token used in both the filename and the report's `Time:` line. */
    fun stamp(nowMillis: Long): String = STAMP_FMT.format(Date(nowMillis))

    /** @param dropped how many older reports had to be deleted to make room. */
    data class WriteOutcome(val file: File, val dropped: Int)

    /**
     * Write [body] as a new crash report in [dir], evicting the oldest reports
     * down to [MAX_REPORTS] and recording the eviction count inside the report
     * that survives.
     */
    fun writeReport(dir: File, body: String, nowMillis: Long = System.currentTimeMillis()): WriteOutcome {
        dir.mkdirs()
        // Evict FIRST, reserving one slot for the report we are about to write,
        // so the count we can announce below is already known.
        val dropped = evictOldest(dir, MAX_REPORTS - 1)
        val out = unusedTarget(dir, stamp(nowMillis))
        out.writeText(if (dropped > 0) body + retentionNotice(dropped) else body)
        return WriteOutcome(out, dropped)
    }

    private fun retentionNotice(dropped: Int): String = buildString {
        appendLine()
        appendLine("--- Retention ---")
        appendLine("$dropped oldest crash report(s) were deleted to stay within the $MAX_REPORTS-report limit.")
        appendLine("Raise CrashReportStore.MAX_REPORTS to keep more, at the cost of disk.")
    }

    /** Existing Java/Kotlin crash reports, oldest name first (names sort chronologically). */
    private fun existingReports(dir: File): List<File> =
        dir.listFiles { f ->
            f.isFile && f.name.startsWith(FILE_PREFIX) && f.name.endsWith(".log")
        }?.sortedBy { it.name } ?: emptyList()

    /** Delete the oldest reports until at most [keep] remain. Returns how many went. */
    private fun evictOldest(dir: File, keep: Int): Int {
        val files = existingReports(dir)
        if (files.size <= keep) return 0
        var dropped = 0
        for (file in files.take(files.size - keep)) {
            if (file.delete()) dropped++
        }
        return dropped
    }

    /**
     * `crash-<stamp>.log`, or `crash-<stamp>-1.log` … when that path is taken.
     * Never hands back a path that already holds a report, so `writeText` can
     * never truncate one.
     */
    private fun unusedTarget(dir: File, stamp: String): File {
        val first = File(dir, "$FILE_PREFIX$stamp.log")
        if (!first.exists()) return first
        var n = 1
        while (true) {
            val candidate = File(dir, "$FILE_PREFIX$stamp-$n.log")
            if (!candidate.exists()) return candidate
            n++
        }
    }
}
