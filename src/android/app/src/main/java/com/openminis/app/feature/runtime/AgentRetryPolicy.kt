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

        // -1 is the explicit always/unlimited mode: it intentionally ignores
        // retryability hints and stop keywords, matching the caller's request
        // to keep trying until success or cancellation.
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

    private fun Double.pow(exponent: Int): Double {
        var result = 1.0
        repeat(exponent) { result *= this }
        return result
    }
}
