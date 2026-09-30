package com.openminis.app.crash

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the two crash-report defects this store was extracted to fix:
 *
 *   - **evidence loss**: the old second-resolution filename + `writeText`
 *     truncate meant two crashes in one second shared a path and the second
 *     wiped the first;
 *   - **unbounded growth**: no cap on how many reports were kept, and the drop
 *     (once there is one) had to stay visible instead of becoming silent.
 *
 * Uses a real throwaway directory, so it needs no Context and no device.
 */
class CrashReportStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 2026-01-02 03:04:05.006 in epoch millis; only the formatting matters, not the zone. */
    private val t0 = 1_767_322_445_006L

    private fun dir(): File = tmp.newFolder()

    private fun body(tag: String) = "=== Minis Java/Kotlin Crash ===\ncrash $tag\n"

    private fun crashFiles(dir: File): List<File> =
        dir.listFiles { f: File -> f.name.startsWith("crash-") && f.name.endsWith(".log") }
            ?.sortedBy { it.name } ?: emptyList()

    /** Seed [n] distinct, non-overlapping, already-name-sorted old reports. */
    private fun seedOldReports(dir: File, n: Int) {
        for (i in 0 until n) {
            val name = "crash-2025-01-01_%02d-%02d-%02d-000.log".format(i / 3600, (i / 60) % 60, i % 60)
            File(dir, name).writeText(body("OLD-$i"))
        }
    }

    // ─── bug 2: same-second evidence loss ────────────────────────────────

    @Test
    fun `two crashes in the same millisecond both leave a report`() {
        val dir = dir()

        val first = CrashReportStore.writeReport(dir, body("FIRST"), t0)
        val second = CrashReportStore.writeReport(dir, body("SECOND"), t0)

        assertFalse(
            "the two reports must not share a path — sharing it is what let writeText destroy the first",
            first.file.absolutePath == second.file.absolutePath,
        )
        val files = crashFiles(dir)
        assertEquals("both crashes must be on disk, got ${files.map { it.name }}", 2, files.size)
        val text = files.joinToString("\n<<<>>>\n") { it.readText() }
        assertTrue("the FIRST crash's evidence is gone: $text", text.contains("crash FIRST"))
        assertTrue("the SECOND crash's evidence is gone: $text", text.contains("crash SECOND"))
    }

    @Test
    fun `two crashes in the same second but different millis also both survive`() {
        val dir = dir()

        CrashReportStore.writeReport(dir, body("EARLY"), t0)
        CrashReportStore.writeReport(dir, body("LATE"), t0 + 900L)

        val text = crashFiles(dir).joinToString("\n<<<>>>\n") { it.readText() }
        assertTrue(text.contains("crash EARLY"))
        assertTrue(text.contains("crash LATE"))
    }

    @Test
    fun `the report name still matches what the Logs screen filters on`() {
        val dir = dir()
        val out = CrashReportStore.writeReport(dir, body("X"), t0)
        val later = CrashReportStore.writeReport(dir, body("Y"), t0 + 60_000L)

        assertTrue(
            "LogManagementScreen/AppLogger filter on prefix 'crash-' and extension 'log', got ${out.file.name}",
            out.file.name.startsWith("crash-") && out.file.name.endsWith(".log"),
        )
        // Name-sorted newest-first is how the Logs screen orders rows, so the
        // stamp must stay chronological when compared as a string.
        assertTrue(
            "later report must sort after the earlier one by name: ${out.file.name} vs ${later.file.name}",
            later.file.name > out.file.name,
        )
    }

    // ─── bug 1: the cap, and the dropped count staying visible ───────────

    @Test
    fun `the report count is capped and the drop is announced inside the surviving report`() {
        val dir = dir()
        seedOldReports(dir, CrashReportStore.MAX_REPORTS)
        val oldest = dir.resolve("crash-2025-01-01_00-00-00-000.log")
        assertTrue("precondition: the oldest seeded report must exist", oldest.exists())

        val out = CrashReportStore.writeReport(dir, body("NEWEST"), t0)

        assertEquals("exactly one report had to go to make room", 1, out.dropped)
        assertEquals(CrashReportStore.MAX_REPORTS, crashFiles(dir).size)
        assertFalse(
            "the cap evicted the wrong end — the oldest report is still there",
            oldest.exists(),
        )
        val text = out.file.readText()
        assertTrue(
            "the newest report survived but its body was damaged: $text",
            text.contains("crash NEWEST"),
        )
        assertTrue(
            "how many reports were dropped must be readable by the user, got: $text",
            text.contains("1 oldest crash report(s) were deleted"),
        )
        assertTrue(
            "the notice must name the limit it enforced, got: $text",
            text.contains("within the ${CrashReportStore.MAX_REPORTS}-report limit"),
        )
    }

    @Test
    fun `reporting again after a drop keeps enforcing the cap and keeps announcing it`() {
        val dir = dir()
        for (i in 0 until CrashReportStore.MAX_REPORTS + 40) {
            CrashReportStore.writeReport(dir, body("BURST-$i"), t0 + i.toLong())
        }

        assertEquals(CrashReportStore.MAX_REPORTS, crashFiles(dir).size)
        val newest = crashFiles(dir).last()
        val text = newest.readText()
        assertTrue("newest report body damaged: $text", text.contains("crash BURST-"))
        // With a full directory each new report evicts exactly one.
        assertTrue(
            "the steady-state drop must still be announced, got: $text",
            text.contains("1 oldest crash report(s) were deleted"),
        )
    }

    @Test
    fun `under the cap nothing is dropped and the body is untouched`() {
        val dir = dir()

        val out = CrashReportStore.writeReport(dir, body("ONLY"), t0)

        assertEquals(0, out.dropped)
        assertEquals(body("ONLY"), out.file.readText())
        assertFalse("a retention notice appeared with nothing dropped", out.file.readText().contains("Retention"))
    }

    // ─── it must not eat the daily logs sharing the directory ────────────

    @Test
    fun `daily logs and native crash reports are never touched by the cap`() {
        val dir = dir()
        val daily = File(dir, "minis-2025-01-01.log").also { it.writeText("daily\n") }
        val native = File(dir, "native-crash-2025-01-01_00-00-00.log").also { it.writeText("native\n") }
        seedOldReports(dir, CrashReportStore.MAX_REPORTS + 5)
        assertTrue(
            "precondition: the dir must be over the cap so eviction really runs",
            crashFiles(dir).size > CrashReportStore.MAX_REPORTS,
        )

        CrashReportStore.writeReport(dir, body("NEWEST"), t0)

        assertTrue("the daily log was deleted by the crash cap", daily.exists())
        assertTrue("a native crash report was deleted by the java crash cap", native.exists())
    }
}
