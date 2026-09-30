package com.openminis.app.feature.runtime

import kotlin.math.roundToLong

/**
 * Pure retry policy. [jitterSource] returns a value in [-1, 1], allowing tests
 * to pin the delay while production can supply a random value. A null source
 * preserves deterministic backoff without jitter.
 */
class AgentRetryPolicy(
    private val config: AgentRuntimeConfig = AgentRuntimeConfig(),
    private val jitterSource: (() -> Double)? = null,
) {

    fun isRetryable(failure: RuntimeFailure): Boolean {
        val text = "${failure.code} ${failure.message}".lowercase()
        if (config.stopRetryKeywords.any { text.contains(it.lowercase()) }) return false
        if (failure.statusCode != null && failure.statusCode in config.retryableStatusCodes) return true
        if (failure.networkError && config.retryNetworkErrors) return true
        if (config.retryKeywords.any { text.contains(it.lowercase()) }) return true
        return failure.retryable
    }

    fun decide(attempt: Int, failure: RuntimeFailure): RetryDecision {
        require(attempt > 0) { "attempt must be positive" }

        if (config.maxAttempts == 0) {
            return RetryDecision(
                shouldRetry = false,
                nextAttempt = null,
                delayMillis = 0L,
                reason = RetryDecisionReason.RETRIES_DISABLED,
            )
        }

        // -1 is the explicit always/unlimited mode: it retries every failure
        // except an explicit stop-retry signal. Cancellation is handled by the
        // caller and never reaches this policy.
        if (isExplicitStopRetry(failure)) {
            return RetryDecision(
                shouldRetry = false,
                nextAttempt = null,
                delayMillis = 0L,
                reason = RetryDecisionReason.NON_RETRYABLE_FAILURE,
            )
        }
        if (config.maxAttempts != -1 && !isRetryable(failure)) {
            return RetryDecision(
                shouldRetry = false,
                nextAttempt = null,
                delayMillis = 0L,
                reason = RetryDecisionReason.NON_RETRYABLE_FAILURE,
            )
        }

        // Positive maxAttempts is a retry budget. The first failed execution
        // is attempt 1, so a budget of N permits retries for attempts 1..N.
        if (config.maxAttempts > 0 && attempt > config.maxAttempts) {
            return RetryDecision(
                shouldRetry = false,
                nextAttempt = null,
                delayMillis = 0L,
                reason = RetryDecisionReason.ATTEMPTS_EXHAUSTED,
            )
        }

        val exponent = attempt - 1
        val baseDelay = config.initialRetryDelayMillis.toDouble() *
            config.backoffMultiplier.pow(exponent)
        val boundedBase = baseDelay.coerceIn(0.0, config.maxRetryDelayMillis.toDouble())
        val jittered = jitterSource?.invoke()?.coerceIn(-1.0, 1.0)?.let { sample ->
            boundedBase * (1.0 + sample * config.jitterRatio)
        } ?: boundedBase
        val delay = jittered.roundToLong().coerceIn(0L, config.maxRetryDelayMillis)
        return RetryDecision(
            shouldRetry = true,
            nextAttempt = attempt + 1,
            delayMillis = delay,
            reason = RetryDecisionReason.RETRY_SCHEDULED,
        )
    }

    private fun isExplicitStopRetry(failure: RuntimeFailure): Boolean {
        val text = "${failure.code} ${failure.message}".lowercase()
        return config.stopRetryKeywords.any { text.contains(it.lowercase()) }
    }
    private fun Double.pow(exponent: Int): Double {
        var result = 1.0
        repeat(exponent) { result *= this }
        return result
    }
}

/**
 * The retry LOOP that every user-governed model call site runs its request
 * through, so "how many times do we retry" has exactly one answer: the user's
 * automatic-retry setting, carried by [policy].
 *
 * [T-android-retry-governs-title-and-compaction] Before this existed, only the
 * agent main loop went through [AgentRetryPolicy]; session-title generation
 * used a hardcoded budget of 3 and context compaction used its own recursion
 * depth, so moving the retry slider in Settings changed nothing for either of
 * them. Callers now build the policy/runner from the single settings mapping
 * (`agentRetryPolicyFromSettings` / `agentRetryRunnerFromSettings`) and let
 * this loop own the decision.
 *
 * [maxAttempts] mirrors the policy's own value (-1 always, 0 disabled, N>0
 * retries) so a call site can log or reason about the budget without reaching
 * back into the policy. [sleep] is injectable so tests do not wait out the
 * backoff, and [onRetry] lets a call site surface progress.
 *
 * The loop is deliberately NOT responsible for degrading the REQUEST: it only
 * re-issues the same one. A failure whose remedy is a smaller request must be
 * handed up by [run]'s `failureOf` returning null (see compaction's halving
 * layer) — repeating an identical oversized request can never succeed, and
 * under `-1` (always) it would spin on it until the caller's own deadline.
 */
class AgentRetryRunner(
    private val policy: AgentRetryPolicy,
    val maxAttempts: Int,
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val onRetry: (attempt: Int, delayMillis: Long, failure: RuntimeFailure) -> Unit =
        { _, _, _ -> },
) {

    /** Whether the policy considers [failure] worth repeating exactly as-is. */
    fun isRetryable(failure: RuntimeFailure): Boolean = policy.isRetryable(failure)

    /**
     * Runs [block], re-attempting per [policy]. [failureOf] maps a thrown error
     * to the [RuntimeFailure] the policy judges, or returns null to declare
     * "this loop must not handle this one" and rethrow it immediately (the seam
     * a caller uses to hand a failure to a different recovery layer).
     * Cancellation is never retried and always propagates.
     */
    suspend fun <T> run(failureOf: (Throwable) -> RuntimeFailure?, block: suspend () -> T): T {
        var attempt = 1
        while (true) {
            try {
                return block()
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                val failure = failureOf(error) ?: throw error
                val decision = policy.decide(attempt, failure)
                if (!decision.shouldRetry) throw error
                onRetry(attempt, decision.delayMillis, failure)
                if (decision.delayMillis > 0L) sleep(decision.delayMillis)
                attempt = decision.nextAttempt ?: (attempt + 1)
            }
        }
    }
}
