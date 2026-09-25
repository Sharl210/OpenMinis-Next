package com.openminis.app.feature.runtime

/**
 * Pure command and lifecycle contract for the `/fork` and `/goal` features.
 *
 * This file intentionally has no Android, database, or provider dependency. The
 * chat/runtime integration can translate these decisions into session-tree and
 * agent-loop operations without making those layers own the policy.
 */

data class ForkGoalNode(
    val nodeId: String,
    val sessionName: String,
    val parentNodeId: String? = null,
    val rootNodeId: String = nodeId,
) {
    init {
        require(nodeId.isNotBlank()) { "nodeId must not be blank" }
        require(sessionName.isNotBlank()) { "sessionName must not be blank" }
        require(rootNodeId.isNotBlank()) { "rootNodeId must not be blank" }
    }

    /** Only the real root/main node may own a goal. */
    val isRootPrimary: Boolean
        get() = parentNodeId == null && nodeId == rootNodeId
}

sealed interface ForkGoalCommand {
    data class Fork(val requestedName: String? = null) : ForkGoalCommand
    data class Goal(val objective: String) : ForkGoalCommand
}

/** User-facing slash-command parser. It accepts both ASCII and full-width slash. */
object ForkGoalCommandParser {
    private const val FORK = "/fork"
    private const val GOAL = "/goal"

    fun isCommand(input: String): Boolean = parse(input) != null

    fun parse(input: String): ForkGoalCommand? {
        val normalized = normalize(input)
        return when {
            startsCommand(normalized, FORK) -> {
                val argument = normalized.substring(FORK.length).trim()
                ForkGoalCommand.Fork(argument.ifBlank { null })
            }
            startsCommand(normalized, GOAL) -> {
                val objective = normalized.substring(GOAL.length).trim()
                objective.takeIf { it.isNotBlank() }?.let { ForkGoalCommand.Goal(it) }
            }
            else -> null
        }
    }

    private fun normalize(input: String): String = input.trim().replaceFirst('／', '/')

    private fun startsCommand(input: String, command: String): Boolean =
        input.equals(command, ignoreCase = true) ||
            input.startsWith("$command ", ignoreCase = true) ||
            input.startsWith("$command\t", ignoreCase = true)
}

/** Default child naming contract: parent name-2, parent name-3, and so on. */
object ForkNameAllocator {
    fun nextName(parentSessionName: String, existingSiblingNames: Iterable<String>): String {
        val parent = parentSessionName.trim().ifBlank { "session" }
        val existing = existingSiblingNames
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toSet()
        var suffix = 2
        while ("$parent-$suffix" in existing) suffix++
        return "$parent-$suffix"
    }
}

data class ForkDecision(
    val accepted: Boolean,
    val childSessionName: String? = null,
    val reason: String? = null,
)

data class GoalRequest(val objective: String)

enum class GoalStatus {
    IDLE,
    ACTIVE,
    NEEDS_CONTINUATION,
    COMPLETED,
}

enum class GoalStopReason {
    ABNORMAL,
    LEASE_EXPIRED,
    STOPPED,
}

data class GoalRuntimeSnapshot(
    val status: GoalStatus = GoalStatus.IDLE,
    val objective: String? = null,
    val continuationPrompt: String? = null,
    val continuationDelivered: Boolean = false,
    val continuationCount: Int = 0,
    val updatedAtMillis: Long = 0L,
) {
    companion object {
        fun idle(): GoalRuntimeSnapshot = GoalRuntimeSnapshot()
    }
}

data class GoalTransition(
    val previous: GoalRuntimeSnapshot,
    val current: GoalRuntimeSnapshot,
    val accepted: Boolean,
    val reason: String? = null,
)

/**
 * Pure fork/goal policy and state machine for one runtime node.
 *
 * A node may fork regardless of whether it is root or child. Goal ownership is
 * deliberately stricter: only [ForkGoalNode.isRootPrimary] can enter goal mode,
 * preventing recursive child-owned goal loops.
 */
class ForkGoalRuntime(
    val node: ForkGoalNode,
    initialSnapshot: GoalRuntimeSnapshot = GoalRuntimeSnapshot.idle(),
) {
    var snapshot: GoalRuntimeSnapshot = initialSnapshot
        private set

    /** Any node can request a child; persistence and actual child creation remain caller-owned. */
    fun fork(
        command: ForkGoalCommand.Fork = ForkGoalCommand.Fork(),
        existingSiblingNames: Iterable<String> = emptyList(),
    ): ForkDecision {
        val requested = command.requestedName?.trim().orEmpty()
        val childName = requested.ifBlank {
            ForkNameAllocator.nextName(node.sessionName, existingSiblingNames)
        }
        return if (childName.isBlank()) {
            ForkDecision(false, reason = "child session name must not be blank")
        } else {
            ForkDecision(true, childSessionName = childName)
        }
    }

    fun startGoal(request: GoalRequest, nowMillis: Long = 0L): GoalTransition {
        val previous = snapshot
        if (!node.isRootPrimary) return reject(previous, "only the root primary agent may start a goal")
        val objective = request.objective.trim()
        if (objective.isBlank()) return reject(previous, "goal objective must not be blank")
        if (snapshot.status != GoalStatus.IDLE) return reject(previous, "goal is already started or completed")
        val current = GoalRuntimeSnapshot(
            status = GoalStatus.ACTIVE,
            objective = objective,
            updatedAtMillis = nowMillis,
        )
        snapshot = current
        return GoalTransition(previous, current, accepted = true)
    }

    /**
     * A normal-end tool call is the explicit completion signal. Other tool calls
     * are observed but do not end goal mode.
     */
    fun onToolCall(toolName: String, nowMillis: Long = 0L): GoalTransition {
        val previous = snapshot
        if (snapshot.status != GoalStatus.ACTIVE) return reject(previous, "goal is not active")
        if (toolName != NORMAL_END_TOOL_NAME) {
            return GoalTransition(previous, previous, accepted = true)
        }
        val current = snapshot.copy(
            status = GoalStatus.COMPLETED,
            continuationPrompt = null,
            continuationDelivered = false,
            updatedAtMillis = nowMillis,
        )
        snapshot = current
        return GoalTransition(previous, current, accepted = true)
    }

    /**
     * An abnormal, stopped, or lease-expired active run receives one continuation
     * prompt. Repeated stop notifications before resume are idempotent.
     */
    fun onStopped(reason: GoalStopReason, nowMillis: Long = 0L): GoalTransition {
        val previous = snapshot
        if (snapshot.status != GoalStatus.ACTIVE) return reject(previous, "goal is not active")
        val prompt = continuationPrompt(snapshot.objective.orEmpty(), reason)
        val current = snapshot.copy(
            status = GoalStatus.NEEDS_CONTINUATION,
            continuationPrompt = prompt,
            continuationDelivered = false,
            continuationCount = snapshot.continuationCount + 1,
            updatedAtMillis = nowMillis,
        )
        snapshot = current
        return GoalTransition(previous, current, accepted = true)
    }

    /** Returns the pending prompt once; repeated calls do not duplicate injection. */
    fun pollContinuationPrompt(nowMillis: Long = snapshot.updatedAtMillis): String? {
        if (snapshot.status != GoalStatus.NEEDS_CONTINUATION || snapshot.continuationDelivered) return null
        val prompt = snapshot.continuationPrompt ?: return null
        snapshot = snapshot.copy(continuationDelivered = true, updatedAtMillis = nowMillis)
        return prompt
    }

    /** Starts the next run after the caller injected the pending continuation prompt. */
    fun resume(nowMillis: Long = 0L): GoalTransition {
        val previous = snapshot
        if (snapshot.status != GoalStatus.NEEDS_CONTINUATION) return reject(previous, "goal has no pending continuation")
        val current = snapshot.copy(
            status = GoalStatus.ACTIVE,
            continuationPrompt = null,
            continuationDelivered = false,
            updatedAtMillis = nowMillis,
        )
        snapshot = current
        return GoalTransition(previous, current, accepted = true)
    }

    private fun reject(previous: GoalRuntimeSnapshot, reason: String): GoalTransition =
        GoalTransition(previous, previous, accepted = false, reason = reason)

    companion object {
        /** Built-in tool name exposed to the root goal loop. */
        const val NORMAL_END_TOOL_NAME = "goal_complete"

        fun continuationPrompt(objective: String, reason: GoalStopReason): String =
            "Continue the goal below until it is complete. The previous run stopped " +
                "without the normal completion tool (reason: ${reason.name.lowercase()}). " +
                "Preserve the existing progress and continue from the next required step.\n\n" +
                "Goal: ${objective.trim()}"
    }
}
