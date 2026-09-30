package com.openminis.app.feature.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-goal-auto-continuation] The spent auto-continuation budget has to
 * be durable, or "bounded" is only true inside one process: a kill and restart
 * would hand the goal a fresh ceiling and the unattended loop would have no
 * ceiling at all over any realistic time span.
 *
 * These tests read the value back out of the file the app actually writes
 * (`GoalRuntimeStore`), which is also the only honest way to check it: an
 * in-memory assertion would pass even if `save` dropped the field.
 */
class GoalAutoContinuationPersistenceTest {

    @Test
    fun `the spent budget survives save and reload`() {
        val dir = createTempDir(prefix = "goal-auto-continuation-")
        val file = File(dir, "goal.json")
        val store = GoalRuntimeStore.openForTest(file)

        val runtime = ForkGoalRuntime(ForkGoalNode("root", "Main"))
        runtime.startGoal(GoalRequest("ship the release"), nowMillis = 1L)
        runtime.onStopped(GoalStopReason.ABNORMAL, nowMillis = 2L)
        repeat(3) { runtime.noteAutoContinuation(nowMillis = 3L + it) }
        assertTrue(store.save(runtime.snapshot))

        val reloaded = store.load()
        assertEquals(3, reloaded.autoContinuationCount)

        // A restart must continue counting from where the file left off, not from
        // zero: the budget the policy sees after a process death is the spent one.
        val restarted = ForkGoalRuntime(ForkGoalNode("root", "Main"), reloaded)
        val decision = GoalAutoContinuationPolicy.decide(restarted.node, restarted.snapshot)
        assertEquals(3, decision.used)
        assertEquals(GoalAutoContinuationPolicy.MAX_AUTO_CONTINUATIONS, decision.budget)
        assertTrue("3 of 5 spent still leaves room", decision.shouldContinue)

        dir.deleteRecursively()
    }

    @Test
    fun `an exhausted goal is still resumable after a reload`() {
        // The hand-over card tells the reader the goal is unfinished and its saved
        // progress is intact. That promise is about the *persisted* state, so it is
        // checked by writing the exhausted snapshot to a real file, reading it back,
        // and then doing what the user's next message does.
        val dir = createTempDir(prefix = "goal-auto-continuation-exhausted-")
        val file = File(dir, "goal.json")
        val store = GoalRuntimeStore.openForTest(file)

        val runtime = ForkGoalRuntime(ForkGoalNode("root", "Main"))
        runtime.startGoal(GoalRequest("finish the migration"), nowMillis = 1L)
        repeat(GoalAutoContinuationPolicy.MAX_AUTO_CONTINUATIONS) {
            runtime.onStopped(GoalStopReason.ABNORMAL, nowMillis = 2L + it)
            runtime.noteAutoContinuation(nowMillis = 3L + it)
            assertTrue(runtime.pollContinuationPrompt(nowMillis = 4L + it) != null)
            runtime.resume(nowMillis = 5L + it)
        }
        // Last stop of the replay: the one the budget refuses to continue.
        runtime.onStopped(GoalStopReason.ABNORMAL, nowMillis = 90L)
        assertTrue(store.save(runtime.snapshot))
        val persisted = store.load()

        val restarted = ForkGoalRuntime(ForkGoalNode("root", "Main"), persisted)
        val decision = GoalAutoContinuationPolicy.decide(restarted.node, restarted.snapshot)
        assertEquals(GoalAutoContinuationReason.BUDGET_EXHAUSTED, decision.reason)
        assertFalse("the restart must not resume the unattended loop", decision.shouldContinue)
        assertEquals(GoalAutoContinuationPolicy.MAX_AUTO_CONTINUATIONS, decision.used)
        assertEquals(GoalAutoContinuationPolicy.MAX_AUTO_CONTINUATIONS, decision.budget)

        // The promise the card makes, verified from the file:
        assertEquals(GoalStatus.NEEDS_CONTINUATION, restarted.snapshot.status)
        assertEquals("finish the migration", restarted.snapshot.objective)
        val prompt = restarted.snapshot.continuationPrompt
        assertTrue("the saved prompt must survive the reload", !prompt.isNullOrBlank())
        assertFalse(restarted.snapshot.continuationDelivered)

        // …and the user's next message still continues the goal by hand.
        val byHand = restarted.pollContinuationPrompt(nowMillis = 100L)
        assertTrue(byHand != null && byHand.contains("finish the migration"))
        assertTrue(restarted.resume(nowMillis = 101L).accepted)
        assertEquals(GoalStatus.ACTIVE, restarted.snapshot.status)

        dir.deleteRecursively()
    }

    @Test
    fun `a snapshot written before the field existed still gets a full budget`() {
        val dir = createTempDir(prefix = "goal-auto-continuation-legacy-")
        val file = File(dir, "goal.json")
        // Exactly the shape the previous version wrote: no autoContinuationCount.
        file.writeText(
            """{"status":"NEEDS_CONTINUATION","objective":"old goal",""" +
                """"continuationPrompt":"Continue the goal below…",""" +
                """"continuationDelivered":false,"continuationCount":1,"updatedAtMillis":5}""",
        )
        val store = GoalRuntimeStore.openForTest(file)

        val snapshot = store.load()
        assertEquals("an unknown count must read as 'nothing spent yet'", 0, snapshot.autoContinuationCount)
        assertEquals(GoalStatus.NEEDS_CONTINUATION, snapshot.status)
        assertTrue(GoalAutoContinuationPolicy.decide(ForkGoalNode("root", "Main"), snapshot).shouldContinue)

        dir.deleteRecursively()
    }
}
