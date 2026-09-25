package com.openminis.app.feature.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeStateMachineTest {
    @Test
    fun `default retry configuration matches runtime contract`() {
        val config = AgentRuntimeConfig()
        assertEquals(-1, config.maxAttempts)
        assertEquals(1_000L, config.initialRetryDelayMillis)
        assertEquals(1.2, config.backoffMultiplier, 0.0)
        assertEquals(30_000L, config.maxRetryDelayMillis)
        assertEquals(0.2, config.jitterRatio, 0.0)
    }

    @Test
    fun `negative one always retries ordinary non retryable failure`() {
        val policy = AgentRetryPolicy(AgentRuntimeConfig(maxAttempts = -1, jitterRatio = 0.0))
        val decision = policy.decide(
            attempt = 100,
            failure = RuntimeFailure("provider", "unexpected response", retryable = false),
        )
        assertTrue(decision.shouldRetry)
        assertEquals(101, decision.nextAttempt)
        assertEquals(RetryDecisionReason.RETRY_SCHEDULED, decision.reason)
    }

    @Test
    fun `unlimited mode still stops on explicit stop error`() {
        val policy = AgentRetryPolicy(AgentRuntimeConfig(maxAttempts = -1, jitterRatio = 0.0))
        val decision = policy.decide(
            attempt = 100,
            failure = RuntimeFailure("auth", "invalid API key; do not retry", retryable = false),
        )
        assertFalse(decision.shouldRetry)
        assertEquals(RetryDecisionReason.NON_RETRYABLE_FAILURE, decision.reason)
    }
    @Test
    fun `zero disables automatic retries`() {
        val policy = AgentRetryPolicy(AgentRuntimeConfig(maxAttempts = 0))
        val decision = policy.decide(
            attempt = 1,
            failure = RuntimeFailure("timeout", "temporary failure", retryable = true),
        )
        assertFalse(decision.shouldRetry)
        assertEquals(RetryDecisionReason.RETRIES_DISABLED, decision.reason)
    }

    @Test
    fun `positive max attempts is a retry budget`() {
        val policy = AgentRetryPolicy(
            AgentRuntimeConfig(maxAttempts = 2, initialRetryDelayMillis = 100L, jitterRatio = 0.0),
        )
        val failure = RuntimeFailure("timeout", "timed out", retryable = false)
        assertTrue(policy.decide(attempt = 1, failure).shouldRetry)
        assertTrue(policy.decide(attempt = 2, failure).shouldRetry)
        val exhausted = policy.decide(attempt = 3, failure)
        assertFalse(exhausted.shouldRetry)
        assertEquals(RetryDecisionReason.ATTEMPTS_EXHAUSTED, exhausted.reason)
    }

    @Test
    fun `retryable failure schedules bounded retry and resumes`() {
        val machine = AgentRuntimeStateMachine(
            AgentRuntimeConfig(
                maxAttempts = 3,
                initialRetryDelayMillis = 100L,
                maxRetryDelayMillis = 150L,
                jitterRatio = 0.0,
            ),
        )

        assertTrue(machine.dispatch(RuntimeCommand.Start("task-1", 1_000L)).accepted)
        assertEquals(RuntimePhase.STARTING, machine.snapshot.phase)
        assertTrue(machine.dispatch(RuntimeCommand.WorkerStarted(1_001L)).accepted)

        val failed = machine.dispatch(
            RuntimeCommand.Failed(
                RuntimeFailure("timeout", "request timed out", retryable = true),
                1_010L,
            ),
        )
        assertTrue(failed.accepted)
        assertEquals(RuntimePhase.WAITING_RETRY, machine.snapshot.phase)
        assertEquals(2, machine.snapshot.attempt)
        assertEquals(1_110L, machine.snapshot.retryAtMillis)

        assertTrue(machine.dispatch(RuntimeCommand.RetryDue(1_110L)).accepted)
        assertEquals(RuntimePhase.RUNNING, machine.snapshot.phase)
        assertTrue(machine.dispatch(RuntimeCommand.Completed("done", 1_120L)).accepted)
        assertEquals(RuntimePhase.SUCCEEDED, machine.snapshot.phase)
        assertEquals("done", machine.snapshot.output)
    }

    @Test
    fun `retryability accepts network status and retry keywords`() {
        val policy = AgentRetryPolicy(AgentRuntimeConfig(jitterRatio = 0.0))
        assertTrue(policy.isRetryable(RuntimeFailure("io", "socket reset", false, networkError = true)))
        assertTrue(policy.isRetryable(RuntimeFailure("http", "server busy", false, statusCode = 503)))
        assertTrue(policy.isRetryable(RuntimeFailure("provider", "temporary failure", false)))
        assertTrue(policy.isRetryable(RuntimeFailure("provider", "rate limit reached", false)))
    }

    @Test
    fun `stop keywords override retryable hints`() {
        val policy = AgentRetryPolicy()
        assertFalse(policy.isRetryable(RuntimeFailure("auth", "invalid API key; please retry", true)))
        assertFalse(policy.isRetryable(RuntimeFailure("policy", "do not retry this request", true)))
        assertFalse(policy.isRetryable(RuntimeFailure("http", "forbidden", true, statusCode = 403)))
    }

    @Test
    fun `injected jitter stays within plus or minus twenty percent`() {
        val config = AgentRuntimeConfig(jitterRatio = 0.2)
        val failure = RuntimeFailure("network", "connection reset", false, networkError = true)
        val low = AgentRetryPolicy(config) { -1.0 }.decide(1, failure)
        val high = AgentRetryPolicy(config) { 1.0 }.decide(1, failure)
        assertEquals(800L, low.delayMillis)
        assertEquals(1_200L, high.delayMillis)
    }

    @Test
    fun `non retryable failure is terminal`() {
        val machine = AgentRuntimeStateMachine(AgentRuntimeConfig(maxAttempts = 0))
        machine.dispatch(RuntimeCommand.Start("task-2", 2_000L))
        machine.dispatch(RuntimeCommand.WorkerStarted(2_001L))

        val transition = machine.dispatch(
            RuntimeCommand.Failed(
                RuntimeFailure("invalid", "bad input", retryable = false),
                2_002L,
            ),
        )

        assertTrue(transition.accepted)
        assertEquals(RuntimePhase.FAILED, machine.snapshot.phase)
        assertEquals(1, machine.snapshot.attempt)
    }
}
