package com.openminis.app.feature.runtime

/**
 * [T-android-goal-auto-continuation] Pure policy for `/goal`'s unattended
 * auto-advance.
 *
 * Requirement (`plans/ULW-2026-09-25-01/request.md:31`): `/goal` means "do not
 * stop until the goal is met". The goal is exited only when the model calls
 * [ForkGoalRuntime.NORMAL_END_TOOL_NAME]; if the run stops without that call, the
 * goal has to continue **itself**, because the reason the mode exists is that
 * "the human cannot sit here watching it" (same paragraph).
 *
 * [ForkGoalRuntime.onStopped] already turns an abnormal stop of an ACTIVE goal
 * into a saved continuation prompt. What decides *whether this agent may spend
 * that prompt automatically* is this file, and it is deliberately one pure
 * function so it can be tested on the JVM without a provider, a coroutine or a
 * clock: the caller passes the snapshot it read back from disk and gets a
 * verdict.
 *
 * Three rules, in order:
 *
 *  1. **Only the real root primary agent** ([ForkGoalNode.isRootPrimary]). The
 *     requirement is explicit and states the reason: if a child sub-agent could
 *     own a goal too, "it would recurse forever". A child's lifecycle is the job
 *     of its parent — so a child never advances a goal automatically. The
 *     verdict itself is not re-derived here; it is the runtime tree's existing
 *     answer, see [goalNodeForChatSession].
 *  2. **A pending, undelivered continuation prompt.** That is exactly the state
 *     [ForkGoalRuntime.onStopped] leaves behind, and `continuationDelivered`
 *     ([ForkGoalRuntime.pollContinuationPrompt]'s one-shot flag) is what stops
 *     the automatic path and a real user send from injecting the same prompt
 *     twice.
 *  3. **A finite budget** ([MAX_AUTO_CONTINUATIONS]). "Never stop until done" is
 *     only safe unattended if it is bounded; without a ceiling a goal that
 *     cannot make progress (revoked key, dead network, a model that keeps
 *     failing) becomes an unattended loop with an unbounded bill. When the
 *     budget is spent the policy reports [GoalAutoContinuationReason
 *     .BUDGET_EXHAUSTED]; the caller stops advancing and surfaces a visible
 *     "your decision is needed" card, while the pending prompt stays on disk so
 *     the human can still continue the goal by hand.
 */
enum class GoalAutoContinuationReason {
    /** The goal stopped without `goal_complete` and this agent may continue it. */
    CONTINUE,

    /** A child sub-agent session: never advance a goal on its own. */
    NOT_ROOT_PRIMARY,

    /** No goal, or a goal that is not sitting on an undelivered prompt. */
    NO_PENDING_CONTINUATION,

    /** The pending prompt was already consumed — by the auto path or a real send. */
    ALREADY_DELIVERED,

    /** [GoalAutoContinuationPolicy.MAX_AUTO_CONTINUATIONS] reached: hand over to the user. */
    BUDGET_EXHAUSTED,
}

/** Verdict of [GoalAutoContinuationPolicy.decide], with the numbers behind it. */
data class GoalAutoContinuationDecision(
    val shouldContinue: Boolean,
    val reason: GoalAutoContinuationReason,
    /** Auto-continuations this goal has already spent. */
    val used: Int,
    /** The budget [used] is measured against. */
    val budget: Int,
) {
    val isExhausted: Boolean
        get() = reason == GoalAutoContinuationReason.BUDGET_EXHAUSTED
}

object GoalAutoContinuationPolicy {

    /**
     * How many times one goal may continue itself with nobody watching.
     *
     * This is the single source of the ceiling: the trigger site reads this
     * constant instead of carrying its own copy, because the repo's recurring
     * defect is one rule written out several times and then drifting.
     *
     * Why 5:
     *  - It is an order of magnitude above the module's automatic *retry* budget
     *    (3 attempts, `ChatViewModel.AUTO_RETRY_DELAYS_SEC`) because an
     *    auto-continuation is not the same request tried again — it is a fresh
     *    agent turn that is expected to make progress, and one continuation is
     *    often all a stopped goal needs.
     *  - It is small enough that a goal which can never progress exhausts in a
     *    handful of turns (seconds, when the failure is a bad key or no network)
     *    and then hands the decision to the user, instead of burning tokens for
     *    hours unattended.
     *  - It is deliberately a plain constant and not a per-session setting: the
     *    point of the mode is that it survives the human walking away, so its
     *    ceiling has to hold without anyone having configured it first.
     */
    const val MAX_AUTO_CONTINUATIONS: Int = 5

    /** Convenience overload over the two objects the caller already has. */
    fun decide(node: ForkGoalNode, snapshot: GoalRuntimeSnapshot): GoalAutoContinuationDecision =
        decide(
            isRootPrimary = node.isRootPrimary,
            status = snapshot.status,
            pendingPrompt = snapshot.continuationPrompt,
            delivered = snapshot.continuationDelivered,
            used = snapshot.autoContinuationCount,
        )

    /**
     * The decision itself, over plain values, so tests can drive every branch
     * without building a runtime or a clock.
     */
    fun decide(
        isRootPrimary: Boolean,
        status: GoalStatus,
        pendingPrompt: String?,
        delivered: Boolean,
        used: Int,
        budget: Int = MAX_AUTO_CONTINUATIONS,
    ): GoalAutoContinuationDecision {
        fun verdict(reason: GoalAutoContinuationReason) = GoalAutoContinuationDecision(
            shouldContinue = reason == GoalAutoContinuationReason.CONTINUE,
            reason = reason,
            used = used,
            budget = budget,
        )
        if (!isRootPrimary) return verdict(GoalAutoContinuationReason.NOT_ROOT_PRIMARY)
        if (status != GoalStatus.NEEDS_CONTINUATION) {
            return verdict(GoalAutoContinuationReason.NO_PENDING_CONTINUATION)
        }
        if (delivered) return verdict(GoalAutoContinuationReason.ALREADY_DELIVERED)
        if (pendingPrompt.isNullOrBlank()) {
            return verdict(GoalAutoContinuationReason.NO_PENDING_CONTINUATION)
        }
        // Checked last: an exhausted goal must report BUDGET_EXHAUSTED rather
        // than "nothing to do", because that verdict is what produces the
        // user-visible hand-over card.
        if (used >= budget) return verdict(GoalAutoContinuationReason.BUDGET_EXHAUSTED)
        return verdict(GoalAutoContinuationReason.CONTINUE)
    }
}

/**
 * The goal node of a chat session, derived from the runtime tree's **existing**
 * parent link instead of a second notion of "who is a sub-agent".
 *
 * `ChatViewModel` used to build `ForkGoalNode(sessionId, sessionId)` for every
 * session, which makes [ForkGoalNode.isRootPrimary] true unconditionally — so a
 * sub-agent's own chat window could start a goal, the exact recursive setup the
 * requirement forbids. The parent link is already recorded by the very same call
 * that creates a child session
 * (`SessionActivityTracker.beginDelegatedChild` → `RuntimeSessionCoordinator
 * .startChild`, using the child session id as the node id), and the skill/MCP
 * inheritance chain already reads it through the same lookup
 * (`com.openminis.app.data.repository.SessionTreeParentChain`). This function is
 * that chain's parent lookup projected onto [ForkGoalNode]: no parent ⇒ root
 * primary; a parent ⇒ a child, whose root is found by walking up.
 *
 * An unknown session (no node in the tree at all) reads as "no parent" and is
 * therefore treated as a root — the same safe fallback the inheritance chain
 * uses, and the only reading that keeps a plain top-level conversation working
 * when the runtime tree has never recorded it.
 */
fun goalNodeForChatSession(sessionId: String, parentOf: (String) -> String?): ForkGoalNode {
    val parentId = parentOf(sessionId)?.takeIf { it.isNotBlank() } ?: return ForkGoalNode(sessionId, sessionId)
    // Walk to the real root so the node carries honest ancestry, with a visited
    // set instead of a depth constant: a corrupted tree that points a node at
    // its own descendant (or at itself) must not spin here.
    var rootId = parentId
    val visited = mutableSetOf(sessionId, parentId)
    var cursor = parentOf(parentId)?.takeIf { it.isNotBlank() }
    while (cursor != null && visited.add(cursor)) {
        rootId = cursor
        cursor = parentOf(cursor)?.takeIf { it.isNotBlank() }
    }
    return ForkGoalNode(
        nodeId = sessionId,
        sessionName = sessionId,
        parentNodeId = parentId,
        rootNodeId = rootId,
    )
}
