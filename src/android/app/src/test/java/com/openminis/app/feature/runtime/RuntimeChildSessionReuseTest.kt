package com.openminis.app.feature.runtime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-child-restart] What a RESTART does to the child's session, and what
 * it is allowed to destroy.
 *
 * `RuntimeChildRunner` used to be able to do exactly one thing with a session:
 * mint a new one and, when the runtime tree refused the child, delete it. A
 * restart needs the opposite on both counts — "reuse this child's context" means
 * running the SAME session id again (a second session would leave the
 * interrupted child's transcript orphaned and its runtime node pointing at rows
 * nobody reads), and the cleanup that is correct for a session this run created
 * is data loss for a session it was asked to reuse.
 *
 * ## Why these are unit tests of two named functions rather than of `execute`
 *
 * `execute` needs a live provider stack (`ProviderRepository` config, a
 * credential, a provider instance), and this module has no Robolectric, so a JVM
 * test cannot get past model resolution to observe which session the run picked.
 * The decision therefore lives in two small, context-free functions that
 * production calls — `resolveChildSessionId` and `beginChildRun` — and those are
 * what is exercised here, with the real production functions, not a restatement
 * of them. Deleting the guard (making the delete unconditional again) or making
 * the reuse branch create a session turns these red; see the reverse-proof note
 * in the task report.
 */
class RuntimeChildSessionReuseTest {

    // ─── which session does this run use ────────────────────────────────

    @Test
    fun `a restart reuses the child's own session and never creates a second one`() = runBlocking {
        var created = 0

        val plan = RuntimeChildRunner.resolveChildSessionId("child-session") {
            created++
            "a-brand-new-session"
        }

        assertEquals("the interrupted child's own session", "child-session", plan.sessionId)
        assertEquals("a restart must not mint a second child session", 0, created)
        assertFalse(
            "a session this run did not create is not its to delete",
            plan.createdByThisRun,
        )
    }

    @Test
    fun `an ordinary delegation still creates exactly one session`() = runBlocking {
        var created = 0

        val plan = RuntimeChildRunner.resolveChildSessionId(null) {
            created++
            "fresh-session"
        }

        assertEquals("fresh-session", plan.sessionId)
        assertEquals(1, created)
        assertTrue(plan.createdByThisRun)
    }

    @Test
    fun `a blank existing id is treated as no existing session`() = runBlocking {
        listOf("", "   ", "\n").forEach { blank ->
            var created = 0
            val plan = RuntimeChildRunner.resolveChildSessionId(blank) {
                created++
                "fresh-$created"
            }

            assertEquals("a blank id cannot name a child to reuse", "fresh-1", plan.sessionId)
            assertEquals(1, created)
            assertTrue(plan.createdByThisRun)
        }
    }

    @Test
    fun `surrounding whitespace does not turn a reusable id into a new session`() = runBlocking {
        var created = 0

        val plan = RuntimeChildRunner.resolveChildSessionId("  child-session  ") {
            created++
            "a-brand-new-session"
        }

        assertEquals("child-session", plan.sessionId)
        assertEquals(0, created)
    }

    // ─── what may be undone when the tree refuses the child ─────────────

    @Test
    fun `a refused run deletes the session it created itself`() = runBlocking {
        var deleted = 0

        val registered = RuntimeChildRunner.beginChildRun(
            sessionCreatedByThisRun = true,
            begin = { false },
            deleteSession = { deleted++ },
        )

        assertFalse(registered)
        assertEquals("cleanup of a session this run created is expected", 1, deleted)
    }

    @Test
    fun `a refused restart never deletes the child's own transcript`() = runBlocking {
        var deleted = 0

        val registered = RuntimeChildRunner.beginChildRun(
            sessionCreatedByThisRun = false,
            begin = { false },
            deleteSession = { deleted++ },
        )

        assertFalse(registered)
        assertEquals(
            "a refused launch must leave the interrupted child's history alone — deleting it " +
                "would answer a refusal with data loss",
            0,
            deleted,
        )
    }

    @Test
    fun `a registered run deletes nothing`() = runBlocking {
        listOf(true, false).forEach { createdByThisRun ->
            var deleted = 0

            val registered = RuntimeChildRunner.beginChildRun(
                sessionCreatedByThisRun = createdByThisRun,
                begin = { true },
                deleteSession = { deleted++ },
            )

            assertTrue(registered)
            assertEquals(0, deleted)
        }
    }
}
