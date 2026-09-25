package com.openminis.app.feature.runtime

/**
 * Runtime knobs that are independent from Android UI, storage, or networking.
 *
 * [maxAttempts] is the automatic retry budget, not the total number of
 * executions: `-1` means always/unlimited retries, `0` disables automatic
 * retries, and a positive value permits that many retries.
 */
data class AgentRuntimeConfig(
    val maxAttempts: Int = -1,
    val initialRetryDelayMillis: Long = 1_000L,
    val maxRetryDelayMillis: Long = 30_000L,
    val backoffMultiplier: Double = 1.2,
    val jitterRatio: Double = 0.2,
    val retryNetworkErrors: Boolean = true,
    val retryableStatusCodes: Set<Int> = DEFAULT_RETRYABLE_STATUS_CODES,
    val retryKeywords: Set<String> = DEFAULT_RETRY_KEYWORDS,
    val stopRetryKeywords: Set<String> = DEFAULT_STOP_RETRY_KEYWORDS,
) {
    init {
        require(maxAttempts >= -1) {
            "maxAttempts must be -1 (always), 0 (disabled), or a positive retry count"
        }
        require(initialRetryDelayMillis >= 0) { "initialRetryDelayMillis must be non-negative" }
        require(maxRetryDelayMillis >= initialRetryDelayMillis) {
            "maxRetryDelayMillis must be >= initialRetryDelayMillis"
        }
        require(backoffMultiplier >= 1.0 && backoffMultiplier.isFinite()) {
            "backoffMultiplier must be finite and >= 1.0"
        }
        require(jitterRatio in 0.0..1.0 && jitterRatio.isFinite()) {
            "jitterRatio must be finite and between 0.0 and 1.0"
        }
        require(retryableStatusCodes.all { it in 100..599 }) {
            "retryableStatusCodes must contain valid HTTP status codes"
        }
        require(retryKeywords.none { it.isBlank() }) { "retryKeywords must not contain blanks" }
        require(stopRetryKeywords.none { it.isBlank() }) {
            "stopRetryKeywords must not contain blanks"
        }
    }

    companion object {
        val DEFAULT_RETRYABLE_STATUS_CODES: Set<Int> =
            setOf(408, 425, 429, 500, 502, 503, 504)
        val DEFAULT_RETRY_KEYWORDS: Set<String> = setOf(
            "timeout",
            "timed out",
            "temporarily unavailable",
            "temporary failure",
            "connection reset",
            "connection refused",
            "connection aborted",
            "network error",
            "dns",
            "rate limit",
            "rate_limit",
            "too many requests",
            "overloaded",
            "try again",
        )
        val DEFAULT_STOP_RETRY_KEYWORDS: Set<String> = setOf(
            "do not retry",
            "don't retry",
            "non-retryable",
            "permanent error",
            "invalid api key",
            "invalid_api_key",
            "authentication failed",
            "unauthorized",
            "forbidden",
            "permission denied",
            "content policy",
            "safety policy",
            "insufficient quota",
            "quota exceeded",
        )
    }
}

enum class RuntimePhase {
    IDLE,
    STARTING,
    RUNNING,
    WAITING_RETRY,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

/** A failure is data so callers can decide retryability without exceptions. */
data class RuntimeFailure(
    val code: String,
    val message: String,
    /** Legacy caller hint; policy also evaluates status/network/keywords. */
    val retryable: Boolean,
    val statusCode: Int? = null,
    val networkError: Boolean = false,
) {
    init {
        require(code.isNotBlank()) { "failure code must not be blank" }
        require(message.isNotBlank()) { "failure message must not be blank" }
        require(statusCode == null || statusCode in 100..599) {
            "statusCode must be a valid HTTP status code"
        }
    }
}

sealed interface RuntimeCommand {
    val atMillis: Long

    data class Start(
        val taskId: String,
        override val atMillis: Long,
    ) : RuntimeCommand

    data class WorkerStarted(
        override val atMillis: Long,
    ) : RuntimeCommand

    data class Completed(
        val output: String,
        override val atMillis: Long,
    ) : RuntimeCommand

    data class Failed(
        val failure: RuntimeFailure,
        override val atMillis: Long,
    ) : RuntimeCommand

    data class RetryDue(
        override val atMillis: Long,
    ) : RuntimeCommand

    data class Cancel(
        val reason: String? = null,
        override val atMillis: Long,
    ) : RuntimeCommand

    data class Reset(
        override val atMillis: Long,
    ) : RuntimeCommand
}

data class RuntimeSnapshot(
    val taskId: String?,
    val phase: RuntimePhase,
    val attempt: Int,
    val maxAttempts: Int,
    val retryAtMillis: Long?,
    val lastFailure: RuntimeFailure?,
    val output: String?,
    val cancelReason: String?,
    val startedAtMillis: Long?,
    val updatedAtMillis: Long,
) {
    val isActive: Boolean
        get() = phase == RuntimePhase.STARTING ||
            phase == RuntimePhase.RUNNING ||
            phase == RuntimePhase.WAITING_RETRY

    val isTerminal: Boolean
        get() = phase == RuntimePhase.SUCCEEDED ||
            phase == RuntimePhase.FAILED ||
            phase == RuntimePhase.CANCELLED

    companion object {
        fun idle(config: AgentRuntimeConfig): RuntimeSnapshot = RuntimeSnapshot(
            taskId = null,
            phase = RuntimePhase.IDLE,
            attempt = 0,
            maxAttempts = config.maxAttempts,
            retryAtMillis = null,
            lastFailure = null,
            output = null,
            cancelReason = null,
            startedAtMillis = null,
            updatedAtMillis = 0L,
        )
    }
}

data class RuntimeTransition(
    val previous: RuntimeSnapshot,
    val current: RuntimeSnapshot,
    val accepted: Boolean,
    val rejectionReason: String? = null,
)

enum class RetryDecisionReason {
    RETRY_SCHEDULED,
    RETRIES_DISABLED,
    NON_RETRYABLE_FAILURE,
    ATTEMPTS_EXHAUSTED,
}

data class RetryDecision(
    val shouldRetry: Boolean,
    val nextAttempt: Int?,
    val delayMillis: Long,
    val reason: RetryDecisionReason,
)

/**
 * Synchronous runtime state machine. It owns no Android resources and receives
 * timestamps from its caller, which keeps it deterministic and easy to embed in
 * a coroutine, service, or test without coupling this feature to any UI layer.
 */
class AgentRuntimeStateMachine(
    private val config: AgentRuntimeConfig = AgentRuntimeConfig(),
    private val retryPolicy: AgentRetryPolicy = AgentRetryPolicy(config),
) {
    var snapshot: RuntimeSnapshot = RuntimeSnapshot.idle(config)
        private set

    fun dispatch(command: RuntimeCommand): RuntimeTransition {
        val previous = snapshot
        val transition = when (command) {
            is RuntimeCommand.Reset -> accept(RuntimeSnapshot.idle(config).copy(updatedAtMillis = command.atMillis))
            is RuntimeCommand.Start -> start(command)
            is RuntimeCommand.WorkerStarted -> workerStarted(command)
            is RuntimeCommand.Completed -> completed(command)
            is RuntimeCommand.Failed -> failed(command)
            is RuntimeCommand.RetryDue -> retryDue(command)
            is RuntimeCommand.Cancel -> cancel(command)
        }
        return if (transition.accepted) {
            snapshot = transition.current
            transition.copy(previous = previous)
        } else {
            transition.copy(previous = previous, current = previous)
        }
    }

    private fun start(command: RuntimeCommand.Start): RuntimeTransition {
        if (command.taskId.isBlank()) return reject("taskId must not be blank")
        if (snapshot.isActive) return reject("cannot start while a task is active")
        return accept(
            RuntimeSnapshot(
                taskId = command.taskId,
                phase = RuntimePhase.STARTING,
                attempt = 1,
                maxAttempts = config.maxAttempts,
                retryAtMillis = null,
                lastFailure = null,
                output = null,
                cancelReason = null,
                startedAtMillis = command.atMillis,
                updatedAtMillis = command.atMillis,
            ),
        )
    }

    private fun workerStarted(command: RuntimeCommand.WorkerStarted): RuntimeTransition {
        if (snapshot.phase != RuntimePhase.STARTING) {
            return reject("worker can only start from STARTING")
        }
        return accept(snapshot.copy(phase = RuntimePhase.RUNNING, updatedAtMillis = command.atMillis))
    }

    private fun completed(command: RuntimeCommand.Completed): RuntimeTransition {
        if (snapshot.phase != RuntimePhase.RUNNING) {
            return reject("task can only complete from RUNNING")
        }
        return accept(
            snapshot.copy(
                phase = RuntimePhase.SUCCEEDED,
                retryAtMillis = null,
                lastFailure = null,
                output = command.output,
                updatedAtMillis = command.atMillis,
            ),
        )
    }

    private fun failed(command: RuntimeCommand.Failed): RuntimeTransition {
        if (snapshot.phase != RuntimePhase.RUNNING) {
            return reject("task can only fail from RUNNING")
        }
        val decision = retryPolicy.decide(snapshot.attempt, command.failure)
        if (!decision.shouldRetry) {
            return accept(
                snapshot.copy(
                    phase = RuntimePhase.FAILED,
                    retryAtMillis = null,
                    lastFailure = command.failure,
                    updatedAtMillis = command.atMillis,
                ),
            )
        }
        return accept(
            snapshot.copy(
                phase = RuntimePhase.WAITING_RETRY,
                attempt = decision.nextAttempt ?: snapshot.attempt,
                retryAtMillis = safeAdd(command.atMillis, decision.delayMillis),
                lastFailure = command.failure,
                updatedAtMillis = command.atMillis,
            ),
        )
    }

    private fun retryDue(command: RuntimeCommand.RetryDue): RuntimeTransition {
        if (snapshot.phase != RuntimePhase.WAITING_RETRY) {
            return reject("retry is only available from WAITING_RETRY")
        }
        val retryAt = snapshot.retryAtMillis ?: return reject("retry deadline is missing")
        if (command.atMillis < retryAt) return reject("retry deadline has not been reached")
        return accept(
            snapshot.copy(
                phase = RuntimePhase.RUNNING,
                retryAtMillis = null,
                updatedAtMillis = command.atMillis,
            ),
        )
    }

    private fun cancel(command: RuntimeCommand.Cancel): RuntimeTransition {
        if (!snapshot.isActive) return reject("only an active task can be cancelled")
        return accept(
            snapshot.copy(
                phase = RuntimePhase.CANCELLED,
                retryAtMillis = null,
                cancelReason = command.reason,
                updatedAtMillis = command.atMillis,
            ),
        )
    }

    private fun accept(current: RuntimeSnapshot): RuntimeTransition =
        RuntimeTransition(snapshot, current, accepted = true)

    private fun reject(reason: String): RuntimeTransition =
        RuntimeTransition(snapshot, snapshot, accepted = false, rejectionReason = reason)

    private fun safeAdd(base: Long, delta: Long): Long =
        if (delta <= 0L || base > Long.MAX_VALUE - delta) Long.MAX_VALUE else base + delta
}
