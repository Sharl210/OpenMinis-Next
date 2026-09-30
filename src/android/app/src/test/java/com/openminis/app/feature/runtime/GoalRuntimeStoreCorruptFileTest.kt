package com.openminis.app.feature.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-goal-corrupt-restore] A goal snapshot that cannot be restored must be
 * reported, because its failure is otherwise indistinguishable from success.
 *
 * `load()` used to be `runCatching { … }.getOrDefault(GoalRuntimeSnapshot.idle())`.
 * That is not the same kind of silence as `save()` returning `false`:
 *
 *  - a failed **save** tells its caller, which gets `false` and can retry;
 *  - a failed **load** returns [GoalRuntimeSnapshot.idle] — a value a session with no
 *    goal at all also returns. Nobody downstream can tell "the file is corrupt" from
 *    "this session never had a goal".
 *
 * The observable consequence is that a goal which was being auto-continued simply
 * stops, and from the outside that is indistinguishable from a goal that had already
 * finished. A missing file is a normal first run and stays silent; only a file that
 * exists and cannot be read back is reported.
 *
 * The store's own contract is unchanged — a corrupt file still yields `idle()`, because
 * refusing to start would be worse. What changed is that the discard is now visible.
 */
class GoalRuntimeStoreCorruptFileTest {

    private class Reports {
        val messages = mutableListOf<String>()
        fun record(detail: String) {
            messages.add(detail)
        }
    }

    private fun tempDir(): File = Files.createTempDirectory("goal-store-corrupt").toFile()

    @Test
    fun `a missing file is a first run and is not reported as corruption`() {
        val dir = tempDir()
        try {
            val reports = Reports()
            val store = GoalRuntimeStore.openForTest(File(dir, "absent.json"), reports::record)
            assertEquals(GoalRuntimeSnapshot.idle(), store.load())
            assertEquals("a first run must not be reported", emptyList<String>(), reports.messages)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a healthy snapshot round-trips without reporting anything`() {
        val dir = tempDir()
        try {
            val file = File(dir, "goal.json")
            val expected = GoalRuntimeSnapshot(GoalStatus.ACTIVE, "ship it", updatedAtMillis = 42)
            assertTrue(
                "fixture: saving has to succeed",
                GoalRuntimeStore.openForTest(file).save(expected),
            )

            val reports = Reports()
            val store = GoalRuntimeStore.openForTest(file, reports::record)
            assertEquals("a healthy load must stay silent", emptyList<String>(), reports.messages)
            assertEquals(expected, store.load())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a corrupt file is reported exactly once per load and still yields idle`() {
        val dir = tempDir()
        try {
            val file = File(dir, "goal.json")
            file.writeText("{ this is not json")

            val reports = Reports()
            val store = GoalRuntimeStore.openForTest(file, reports::record)

            // Contract preserved: the caller still gets an idle snapshot rather than a
            // crash. That is deliberate — refusing to start would be worse.
            assertEquals(GoalRuntimeSnapshot.idle(), store.load())
            assertEquals("exactly one report", 1, reports.messages.size)
            assertTrue(
                "the report has to name the file, not just say 'failed': ${reports.messages}",
                reports.messages.single().contains("goal"),
            )

            // A second load reports again: this is a per-read event, not a one-shot flag.
            store.load()
            assertEquals("each load reports", 2, reports.messages.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the corruption report names the right failure, not a read failure`() {
        val dir = tempDir()
        try {
            val file = File(dir, "goal.json")
            // Valid JSON, wrong shape: this must be reported as an invalid *snapshot*,
            // which points somewhere different from "the file could not be read".
            file.writeText("not a json object at all")

            val reports = Reports()
            GoalRuntimeStore.openForTest(file, reports::record).load()
            assertEquals(1, reports.messages.size)
            assertTrue(
                "a parse failure must not be reported as a read failure: ${reports.messages}",
                !reports.messages.single().contains("could not be read"),
            )
        } finally {
            dir.deleteRecursively()
        }
    }
}
