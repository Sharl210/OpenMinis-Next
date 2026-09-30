package com.openminis.app.data

import com.openminis.app.data.repository.SessionSubtreeDeletionPlan
import com.openminis.app.data.repository.sessionSubtreeDeletionPlan
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The retry ledger is the thing a recursive delete is most likely to break: the
 * chat and runtime-tree steps are batched over the whole subtree while the
 * artifact steps are per session, and a batch failure has to be recorded against
 * *every* session in that batch or the retry silently cleans nothing.
 */
class SessionSubtreeDeletionPipelineTest {

    private fun retryStore(): SessionDeletionRetryStore {
        val dir = Files.createTempDirectory("subtree-delete-ledger").toFile()
        return SessionDeletionRetryStore.openForTest(File(dir, "retries.json"))
    }

    private fun plan(vararg ids: String) =
        SessionSubtreeDeletionPlan(ids.toList(), ids.toList(), ids.toList())

    /** A batch step that never fails, for runs whose batch outcome is not the point. */

    private class Recorder {
        val batches = mutableListOf<Pair<String, List<String>>>()
        val perSession = mutableListOf<Pair<String, String>>()
    }

    private fun pipeline(
        store: SessionDeletionRetryStore,
        recorder: Recorder,
        failBatch: Set<String> = emptySet(),
        failPerSession: Set<Pair<String, String>> = emptySet(),
        cancelAt: Pair<String, String>? = null,
    ) = SessionSubtreeDeletionPipeline(
        retryStore = store,
        batchSteps = listOf("runtime_tree", "chat").map { name ->
            SessionSubtreeDeletionPipeline.BatchStep(name) { subtree ->
                recorder.batches += name to subtree.sessionIds
                if (name in failBatch) error("injected $name failure")
            }
        },
        perSessionSteps = listOf("media", "goal").map { name ->
            SessionSubtreeDeletionPipeline.PerSessionStep(name) { sessionId ->
                recorder.perSession += name to sessionId
                if (name to sessionId in failPerSession) error("injected $name failure")
                // Cancellation is not a step failure: it unwinds the whole run the
                // way leaving the screen does, without recording a verdict for the
                // steps that were never reached.
                if (name to sessionId == cancelAt) throw CancellationException("cancelled")
            }
        },
    )

    @Test
    fun `a clean delete runs every step once per session and leaves no ledger row`() = runBlocking {
        val store = retryStore()
        val recorder = Recorder()

        val outcome = pipeline(store, recorder).run(plan("A", "B", "C"))

        assertTrue(outcome.success)
        assertEquals(listOf("runtime_tree", "chat"), recorder.batches.map { it.first })
        assertEquals(listOf("A", "B", "C"), recorder.batches.first().second)
        assertEquals(6, recorder.perSession.size)
        assertTrue(store.load("A").isEmpty())
        assertTrue(store.load("B").isEmpty())
        assertTrue(store.load("C").isEmpty())
    }

    @Test
    fun `a failed batch step is recorded against every session in the subtree`() = runBlocking {
        val store = retryStore()
        val recorder = Recorder()

        val outcome = pipeline(store, recorder, failBatch = setOf("chat")).run(plan("A", "B", "C"))

        assertFalse(outcome.success)
        assertEquals(setOf("chat"), store.load("A"))
        assertEquals(setOf("chat"), store.load("B"))
        assertEquals(setOf("chat"), store.load("C"))
    }

    @Test
    fun `a retry runs only the steps that failed`() = runBlocking {
        val store = retryStore()
        val first = Recorder()
        pipeline(store, first, failPerSession = setOf("media" to "B")).run(plan("A", "B"))

        assertEquals(setOf("media"), store.load("B"))

        // The retry plan is rebuilt from the chat table, and A's row is gone —
        // only B is still named by the ledger. (The synthetic batch steps here do
        // not delete rows, but the point under test is precisely that the ledger,
        // not the plan, decides who is still worked on.)
        val retry = Recorder()
        val outcome = pipeline(store, retry).run(plan())

        assertTrue(outcome.sessionIds.contains("B"))
        assertTrue(outcome.success)
        // Only B's media step is re-attempted: the batch steps and every step
        // that already succeeded stay untouched.
        assertEquals(listOf("media" to "B"), retry.perSession)
        assertEquals(emptyList<Pair<String, List<String>>>(), retry.batches)
        assertTrue(store.load("B").isEmpty())
    }

    @Test
    fun `a retry of a failed batch step re-runs only that batch step`() = runBlocking {
        val store = retryStore()
        val first = Recorder()
        pipeline(store, first, failBatch = setOf("runtime_tree")).run(plan("A"))

        assertEquals(setOf("runtime_tree"), store.load("A"))

        val retry = Recorder()
        pipeline(store, retry).run(plan("A"))

        assertEquals(listOf("runtime_tree"), retry.batches.map { it.first })
        // The artifact steps already ran successfully, so the retry owes none.
        assertEquals(emptyList<Pair<String, String>>(), retry.perSession)
        assertTrue(store.load("A").isEmpty())
    }

    @Test
    fun `a failure recorded for one session does not leak onto its sibling`() = runBlocking {
        val store = retryStore()
        val recorder = Recorder()

        pipeline(store, recorder, failPerSession = setOf("goal" to "A")).run(plan("A", "B"))

        assertEquals(setOf("goal"), store.load("A"))
        assertTrue(store.load("B").isEmpty())
    }

    @Test
    fun `a batch failure survives a later per-session success`() = runBlocking {
        // The ledger is written once per session at the end of the run; a batch
        // failure recorded in the first phase must not be wiped by the artifact
        // steps succeeding afterwards — that was the exact way the old
        // per-session `save(failed.keys)` could lose an outstanding step.
        val store = retryStore()
        val recorder = Recorder()

        pipeline(store, recorder, failBatch = setOf("chat")).run(plan("A"))

        assertEquals(setOf("chat"), store.load("A"))
    }

    @Test
    fun `unknown step names left by an older build are dropped rather than replayed`() = runBlocking {
        // A ledger row naming a step this build no longer has must not make the
        // pipeline believe it is on a retry — otherwise a failed upgrade could
        // pin a session at "nothing left to do" forever. Unknown names are
        // dropped, the run counts as a first attempt, and each current step runs
        // exactly once.
        val store = retryStore()
        store.save("A", setOf("legacy_step"))
        val recorder = Recorder()

        val outcome = pipeline(store, recorder).run(plan("A"))

        assertTrue(outcome.success)
        assertEquals(listOf("media" to "A", "goal" to "A"), recorder.perSession)
        assertTrue(store.load("A").isEmpty())
    }

    @Test
    fun `a session that owes everything is recorded as owing everything before any step runs`() = runBlocking {
        // An interruption in the middle of the artifact phase must leave an
        // accurate debt. Writing the ledger only after a step finished made "was
        // never started" indistinguishable from "already cleaned", so the retry
        // skipped the artifacts entirely.
        val store = retryStore()
        val recorder = Recorder()

        val failure = runCatching {
            pipeline(store, recorder, cancelAt = "goal" to "B").run(plan("A", "B"))
        }

        assertTrue("the run must surface the cancellation", failure.exceptionOrNull() is CancellationException)
        // A finished every artifact step, so nothing is owed for it.
        assertTrue(store.load("A").isEmpty())
        // B's media succeeded; its goal was cancelled and never ran — both states
        // stay visible instead of collapsing into "done".
        assertEquals(setOf("goal"), store.load("B"))
    }

    @Test
    fun `a retry reaches an id whose chat row is already gone`() = runBlocking {
        // The batched `chat` step removes the rows, so the next plan built from
        // the database cannot contain this id any more. The ledger has to be what
        // carries it back into the run, otherwise its media/goal/... would never
        // be retried and its ledger row would sit there forever.
        val store = retryStore()
        pipeline(store, Recorder(), failPerSession = setOf("media" to "B")).run(plan("A", "B"))
        assertEquals(setOf("media"), store.load("B"))

        // The second attempt's plan no longer names B at all — its row is gone.
        val retry = Recorder()
        val outcome = pipeline(store, retry).run(plan())

        assertTrue("B's debt was not carried into the retry", "B" in outcome.sessionIds)
        assertTrue(outcome.success)
        assertEquals(listOf("media" to "B"), retry.perSession)
        assertTrue(store.load("B").isEmpty())
    }

    @Test
    fun `a carried-over id is worked on even when the plan no longer names it`() = runBlocking {
        val store = retryStore()
        store.save("gone", setOf("media"))
        val recorder = Recorder()

        // The plan is empty because the row is gone; without the ledger union the
        // run would do nothing and still report success, leaving the media behind.
        val outcome = pipeline(store, recorder).run(plan())

        assertEquals(listOf("gone"), outcome.sessionIds)
        assertEquals(listOf("media" to "gone"), recorder.perSession)
        assertTrue(outcome.success)
        assertTrue(store.load("gone").isEmpty())
    }

    @Test
    fun `a batch step is handed every id it is then cleared for`() = runBlocking {
        // A committed batch step is cleared for every id in the run (one
        // transaction covered the whole subtree), and the `chat` step deletes
        // exactly the ids in the plan it is handed. Those two sets have to be the
        // same one: an id the step never received gets marked done with its rows
        // still in the database.
        val store = retryStore()
        pipeline(store, Recorder(), failBatch = setOf("chat")).run(plan("A", "B"))
        assertEquals(setOf("chat"), store.load("B"))

        // The retry's rebuilt plan can only name A — B's row is gone from the
        // database — but B is still owed the step, so B has to reach it.
        val retry = Recorder()
        val outcome = pipeline(store, retry).run(plan("A"))

        assertEquals(
            "the batch step was handed ${retry.batches} while the run clears it for A and B",
            listOf("A", "B"),
            retry.batches.first { it.first == "chat" }.second,
        )
        assertTrue(outcome.success)
        assertTrue(store.load("B").isEmpty())
    }

    @Test
    fun `a batch failure never gates another session's artifact steps`() = runBlocking {
        // The retry decision is per session id, not per run: a `chat` failure for
        // one id must not turn another id's artifact cleanup into a no-op.
        val store = retryStore()
        store.save("A", setOf("chat"))
        val recorder = Recorder()

        val outcome = pipeline(store, recorder, failBatch = setOf("chat")).run(plan("A", "B"))

        assertTrue(!outcome.success)
        assertEquals(setOf("chat"), store.load("A"))
        // B was fresh and owed everything, so it keeps owing the chat step that
        // never committed — while its artifacts still ran exactly once, because a
        // batch failure for one id must not gate another id's steps.
        assertEquals(setOf("chat"), store.load("B"))
        assertEquals(listOf("media" to "B", "goal" to "B"), recorder.perSession.filter { it.second == "B" })
        // A's artifacts were already cleaned in the earlier attempt, so nothing
        // is re-run for it.
        assertEquals(emptyList<Pair<String, String>>(), recorder.perSession.filter { it.second == "A" })
    }
}
