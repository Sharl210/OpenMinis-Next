package com.openminis.app.feature.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-goal-auto-continuation] Behaviour of `/goal`'s unattended
 * auto-advance (`plans/ULW-2026-09-25-01/request.md:31`).
 *
 * The decision is deliberately a pure function over values, so everything the
 * requirement asks for — the ceiling, "root primary only", and one-shot
 * consumption — is provable here on the plain JVM with no provider, no
 * coroutine and no timer. No test in this file sleeps, schedules or talks to a
 * network: "how many times may this continue" is a number in, a verdict out.
 */
class GoalAutoContinuationPolicyTest {

    private val rootNode = ForkGoalNode(nodeId = "root", sessionName = "Main")

    private val childNode = ForkGoalNode(
        nodeId = "root-2",
        sessionName = "Main-2",
        parentNodeId = "root",
        rootNodeId = "root",
    )

    private fun activeRuntime(objective: String = "finish the migration"): ForkGoalRuntime =
        ForkGoalRuntime(rootNode).apply { startGoal(GoalRequest(objective), nowMillis = 1L) }

    private fun stoppedRuntime(objective: String = "finish the migration"): ForkGoalRuntime =
        activeRuntime(objective).apply { onStopped(GoalStopReason.ABNORMAL, nowMillis = 2L) }

    // ── B: the ceiling ────────────────────────────────────────────────────

    /**
     * Replays exactly what `ChatViewModel.maybeAutoContinueGoal` does per stop:
     * decide → spend one unit → consume the saved prompt (one-shot) → re-activate
     * → the continuation run ends without `goal_complete`, so the goal stops
     * again. The loop is the production loop minus Android, so the numbers below
     * are the numbers the app will actually spend.
     */
    private class LoopResult(
        val continuationNumbers: List<Int>,
        val injectedPrompts: List<String>,
        val finalDecision: GoalAutoContinuationDecision,
    )

    private fun replayStopLoop(runtime: ForkGoalRuntime): LoopResult {
        val numbers = mutableListOf<Int>()
        val prompts = mutableListOf<String>()
        var decision = GoalAutoContinuationPolicy.decide(rootNode, runtime.snapshot)
        // The replay itself is capped one step past the ceiling, so a regression
        // that removes the ceiling fails an assertion instead of hanging the test
        // run: a bounded loop is what makes "it stopped" observable.
        var guard = 0
        while (decision.shouldContinue && guard <= GoalAutoContinuationPolicy.MAX_AUTO_CONTINUATIONS) {
            guard++
            runtime.noteAutoContinuation(nowMillis = 10L + decision.used)
            val prompt = runtime.pollContinuationPrompt(nowMillis = 20L + decision.used)
            assertNotNull("the decided continuation must have a prompt to inject", prompt)
            prompts += prompt!!
            runtime.resume(nowMillis = 30L + decision.used)
            numbers += decision.used + 1
            // The continuation ran and ended without the exit tool: same stop.
            runtime.onStopped(GoalStopReason.ABNORMAL, nowMillis = 40L + decision.used)
            decision = GoalAutoContinuationPolicy.decide(rootNode, runtime.snapshot)
        }
        return LoopResult(numbers, prompts, decision)
    }

    @Test
    fun `auto-continuation stops at the ceiling and reports it`() {
        val runtime = stoppedRuntime()
        val result = replayStopLoop(runtime)

        assertEquals(
            "each stop must spend exactly one unit, in order, and stop at the constant",
            (1..GoalAutoContinuationPolicy.MAX_AUTO_CONTINUATIONS).toList(),
            result.continuationNumbers,
        )
        assertEquals(result.continuationNumbers.size, result.injectedPrompts.size)
        assertTrue(
            "the prompt must still be the goal's own continuation prompt",
            result.injectedPrompts.all { it.contains("finish the migration") },
        )

        assertFalse("exhaustion must not continue again", result.finalDecision.shouldContinue)
        assertEquals(GoalAutoContinuationReason.BUDGET_EXHAUSTED, result.finalDecision.reason)
        assertEquals(GoalAutoContinuationPolicy.MAX_AUTO_CONTINUATIONS, result.finalDecision.used)
        assertEquals(GoalAutoContinuationPolicy.MAX_AUTO_CONTINUATIONS, result.finalDecision.budget)
        assertEquals(
            GoalAutoContinuationPolicy.MAX_AUTO_CONTINUATIONS,
            runtime.snapshot.autoContinuationCount,
        )
        assertTrue(
            "the spent goal stays in the visible pending-continuation state",
            runtime.snapshot.status == GoalStatus.NEEDS_CONTINUATION,
        )
        assertFalse(
            "the pending prompt must be left undelivered so the human can still use it",
            runtime.snapshot.continuationDelivered,
        )
        assertNotNull(
            "…and a real send after the stop can therefore still continue the goal by hand",
            runtime.pollContinuationPrompt(nowMillis = 999L),
        )
    }

    @Test
    fun `the numbers in the hand-over card are the real counts, not placeholders-of-placeholders`() {
        // The card says "stopped after <used>/<budget> attempts". Both numbers have
        // to mean what they say: `used` must equal the number of auto-continuations
        // that were actually performed, and `budget` the ceiling that stopped it.
        // A swapped pair would still format fine and read plausibly, which is why
        // this is asserted as arithmetic instead of as a string.
        val runtime = stoppedRuntime()
        val performed = replayStopLoop(runtime)

        assertNotNull("the loop must have run, or this assertion proves nothing", performed.continuationNumbers)
        val handOver = performed.finalDecision
        assertEquals(
            "used must be the count of continuations actually performed",
            performed.continuationNumbers.size,
            handOver.used,
        )
        assertEquals(
            "budget must be the ceiling that produced the hand-over",
            GoalAutoContinuationPolicy.MAX_AUTO_CONTINUATIONS,
            handOver.budget,
        )
        assertEquals(
            "…and the persisted counter must agree with both",
            handOver.used,
            runtime.snapshot.autoContinuationCount,
        )

        // The in-flight card is "attempt k/budget" where k = used + 1, so the first
        // automatic continuation reads 1/5 — not 0/5 and not 5/5.
        val fresh = stoppedRuntime()
        val first = GoalAutoContinuationPolicy.decide(rootNode, fresh.snapshot)
        assertTrue(first.shouldContinue)
        assertEquals("the first continuation must read 1, not 0", 1, first.used + 1)
        assertEquals(GoalAutoContinuationPolicy.MAX_AUTO_CONTINUATIONS, first.budget)
    }

    @Test
    fun `exhaustion leaves the goal unfinished and still resumable by hand`() {
        // This is the promise the hand-over card makes to the reader: the goal was
        // not silently finished or dropped, and the user's next message continues
        // it. Asserted on the state machine rather than on the wording, because the
        // wording must follow the behaviour — never the other way round.
        val runtime = stoppedRuntime()
        replayStopLoop(runtime)
        val exhausted = runtime.snapshot

        assertEquals(GoalAutoContinuationReason.BUDGET_EXHAUSTED, GoalAutoContinuationPolicy.decide(rootNode, exhausted).reason)
        assertEquals(
            "the goal must NOT have been marked completed by the hand-over",
            GoalStatus.NEEDS_CONTINUATION,
            exhausted.status,
        )
        assertTrue("…nor dropped back to idle", exhausted.status != GoalStatus.IDLE)
        assertEquals(
            "the objective (the goal's progress anchor) must survive",
            "finish the migration",
            exhausted.objective,
        )
        assertNotNull("the saved continuation prompt must survive", exhausted.continuationPrompt)
        assertFalse(
            "and must still be deliverable, or the user's next message could not continue the goal",
            exhausted.continuationDelivered,
        )

        // Exactly what the user-send path does: consume the pending prompt, then
        // re-activate. After the hand-over this must still work.
        val byHand = runtime.pollContinuationPrompt(nowMillis = 1_000L)
        assertNotNull("a hand-driven continuation after the hand-over must find the prompt", byHand)
        assertTrue(byHand!!.contains("finish the migration"))
        assertTrue("…and must re-activate the goal", runtime.resume(nowMillis = 1_001L).accepted)
        assertEquals(GoalStatus.ACTIVE, runtime.snapshot.status)
        assertFalse(
            "the automatic path must stay stopped even after the goal is running again",
            GoalAutoContinuationPolicy.decide(rootNode, runtime.snapshot).shouldContinue,
        )
    }

    @Test
    fun `only the exit tool can complete a goal, and the hand-over never calls it`() {
        // `goal_complete` is the requirement's normal-exit signal; the auto path
        // must not reach COMPLETED by itself, or "auto-advance gave up" would look
        // like "the goal was achieved".
        val runtime = stoppedRuntime()
        replayStopLoop(runtime)
        assertEquals(GoalStatus.NEEDS_CONTINUATION, runtime.snapshot.status)
        // Not ACTIVE either: while the hand-over stands, the direct exit tool is
        // refused (unreachable in practice — a tool call needs a live run, and a
        // run is only started after the prompt is consumed and the goal resumed).
        assertFalse("the hand-over must not leave the goal running", runtime.snapshot.status == GoalStatus.ACTIVE)
        assertFalse(runtime.onToolCall(ForkGoalRuntime.NORMAL_END_TOOL_NAME, nowMillis = 2_000L).accepted)

        // After the user's own continuation the exit tool works again, unchanged.
        assertNotNull(runtime.pollContinuationPrompt(nowMillis = 2_001L))
        runtime.resume(nowMillis = 2_002L)
        assertTrue(runtime.onToolCall(ForkGoalRuntime.NORMAL_END_TOOL_NAME, nowMillis = 2_003L).accepted)
        assertEquals(GoalStatus.COMPLETED, runtime.snapshot.status)
    }

    @Test
    fun `a goal with no stop is not advanced at all`() {
        val decision = GoalAutoContinuationPolicy.decide(rootNode, activeRuntime().snapshot)
        assertFalse(decision.shouldContinue)
        assertEquals(GoalAutoContinuationReason.NO_PENDING_CONTINUATION, decision.reason)
    }

    @Test
    fun `one unit below the ceiling still continues`() {
        val runtime = stoppedRuntime()
        runtime.noteAutoContinuation()
        val decision = GoalAutoContinuationPolicy.decide(rootNode, runtime.snapshot)
        assertTrue(decision.shouldContinue)
        assertEquals(GoalAutoContinuationReason.CONTINUE, decision.reason)
        assertEquals(1, decision.used)
        assertEquals(GoalAutoContinuationPolicy.MAX_AUTO_CONTINUATIONS, decision.budget)
    }

    @Test
    fun `spending the budget is refused when the goal has no pending continuation`() {
        val runtime = activeRuntime()
        assertFalse(runtime.noteAutoContinuation().accepted)
        assertEquals(0, runtime.snapshot.autoContinuationCount)
    }

    // ── A: root primary only ──────────────────────────────────────────────

    @Test
    fun `a child sub-agent session never advances a goal automatically`() {
        // The child cannot even start a goal (ForkGoalRuntime refuses it, see
        // ForkGoalRuntimeTest); the policy must independently refuse to advance
        // one, because a pending prompt on disk is the only input it gets.
        val pending = GoalRuntimeSnapshot(
            status = GoalStatus.NEEDS_CONTINUATION,
            objective = "nested goal",
            continuationPrompt = "Continue the goal below…",
            continuationDelivered = false,
            continuationCount = 1,
        )

        val decision = GoalAutoContinuationPolicy.decide(childNode, pending)
        assertFalse("a child sub-agent must never auto-continue", decision.shouldContinue)
        assertEquals(GoalAutoContinuationReason.NOT_ROOT_PRIMARY, decision.reason)

        // And the refusal does not depend on the budget being spent.
        val fresh = GoalAutoContinuationPolicy.decide(childNode, pending.copy(autoContinuationCount = 0))
        assertEquals(GoalAutoContinuationReason.NOT_ROOT_PRIMARY, fresh.reason)
    }

    @Test
    fun `the root primary is the only node the policy accepts`() {
        assertTrue(rootNode.isRootPrimary)
        assertFalse(childNode.isRootPrimary)
    }

    // ── C: one pending prompt, one injection ─────────────────────────────

    @Test
    fun `the automatic path and a real user send cannot share one pending prompt`() {
        // Order 1: the auto path got there first.
        val autoFirst = stoppedRuntime()
        val prompt = autoFirst.pollContinuationPrompt(nowMillis = 3L)
        assertNotNull("the auto path injected the prompt", prompt)
        assertNull(
            "the one-shot flag is what makes it one-shot, and it takes effect before the goal is " +
                "re-activated: a second consumer polling in the same window must get nothing",
            autoFirst.pollContinuationPrompt(nowMillis = 4L),
        )
        assertTrue(autoFirst.resume(nowMillis = 5L).accepted)
        assertNull(
            "a real user send afterwards must find nothing to inject",
            autoFirst.pollContinuationPrompt(nowMillis = 6L),
        )
        assertEquals(
            "and the goal must not be waiting for a continuation any more",
            GoalAutoContinuationReason.NO_PENDING_CONTINUATION,
            GoalAutoContinuationPolicy.decide(rootNode, autoFirst.snapshot).reason,
        )

        // Order 2: the real user send got there first.
        val userFirst = stoppedRuntime()
        assertNotNull(userFirst.pollContinuationPrompt(nowMillis = 3L))
        assertTrue(userFirst.resume(nowMillis = 4L).accepted)
        val decision = GoalAutoContinuationPolicy.decide(rootNode, userFirst.snapshot)
        assertFalse(decision.shouldContinue)
        assertEquals(
            "the user's send re-activated the goal, so there is nothing left for the auto path",
            GoalStatus.ACTIVE,
            userFirst.snapshot.status,
        )
    }

    @Test
    fun `a delivered prompt is reported as already delivered, not as nowhere-to-go`() {
        val runtime = stoppedRuntime()
        assertNotNull(runtime.pollContinuationPrompt(nowMillis = 3L))
        // The prompt was handed out but the goal was not re-activated yet: this is
        // the state a crash between "injected" and "resumed" would leave behind.
        val decision = GoalAutoContinuationPolicy.decide(rootNode, runtime.snapshot)
        assertFalse(decision.shouldContinue)
        assertEquals(GoalAutoContinuationReason.ALREADY_DELIVERED, decision.reason)
    }

    // ── the node the caller derives ──────────────────────────────────────

    @Test
    fun `a session with no runtime parent is treated as the root primary`() {
        val node = goalNodeForChatSession("session-a") { null }
        assertTrue(node.isRootPrimary)
        assertNull(node.parentNodeId)
    }

    @Test
    fun `a linked session is a child with honest ancestry`() {
        // root ← mid ← leaf
        val parents = mapOf("mid" to "root", "leaf" to "mid")
        val node = goalNodeForChatSession("leaf") { parents[it] }
        assertFalse("a linked session must never be root primary", node.isRootPrimary)
        assertEquals("mid", node.parentNodeId)
        assertEquals("root", node.rootNodeId)
        assertEquals("leaf", node.nodeId)
    }

    @Test
    fun `a corrupted parent cycle terminates and still reads as a child`() {
        // a → b → a: the walk must not spin, and the result must stay non-root.
        val parents = mapOf("a" to "b", "b" to "a")
        val node = goalNodeForChatSession("a") { parents[it] }
        assertFalse(node.isRootPrimary)
        assertEquals("b", node.parentNodeId)
    }
}
