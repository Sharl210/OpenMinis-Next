package com.openminis.app.feature.runtime

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.tools.AgentTools
import org.json.JSONObject

/**
 * [T-android-child-agent-completion] The delegated child's turn shape and the
 * bounded tool loop that turns a child's streamed turns into one outcome.
 *
 * This file is deliberately free of Android framework and chat-UI types so the
 * protocol (birth injection, per-message injection, "did the child declare a
 * natural end?") can be unit-tested as plain JVM logic. [RuntimeChildRunner]
 * owns the streaming, persistence and runtime-tree side effects and calls in
 * here.
 */

/** One tool call the child's model asked for inside a single assistant turn. */
data class ChildToolCall(
    val id: String,
    val name: String,
    val args: JSONObject,
)

/** Everything one streamed child turn produced. */
data class ChildTurnResult(
    val text: String,
    val toolCalls: List<ChildToolCall> = emptyList(),
    val usage: LLMUsage? = null,
    val stopReason: String? = null,
)

/** What executing one child tool call produced, as fed back to the model. */
data class ChildToolOutcome(
    val output: String,
    val isError: Boolean = false,
)

/** Why the child loop stopped. */
enum class ChildEndReason {
    /** The model called the completion tool — a natural end. */
    COMPLETION_TOOL,

    /** The model ended its turn with no tool calls and never declared completion. */
    NO_TOOL_CALL,

    /** The turn budget ran out while the child was still calling tools. */
    TURN_BUDGET_EXHAUSTED,
}

/** The child loop's terminal state. */
data class ChildLoopOutcome(
    val turns: Int,
    val text: String,
    val usage: LLMUsage?,
    val endReason: ChildEndReason,
    val completionTitle: String? = null,
    val completionSummary: String? = null,
) {
    /** Only [ChildEndReason.COMPLETION_TOOL] counts as a natural end. */
    val completedNaturally: Boolean get() = endReason == ChildEndReason.COMPLETION_TOOL
}

/**
 * The completion contract shared by the prompt the child is born with, the
 * reminder re-injected on every parent message, and the runtime's natural-end
 * decision. Kept in one object so the prompt and the decision can never drift.
 */
object ChildCompletionProtocol {

    /** Tool the child must call to declare a natural end. */
    const val TOOL_NAME: String = AgentTools.SUBAGENT_COMPLETE_TOOL_NAME

    /**
     * Names that would count as a spawn affordance for a child. Mirrors the human
     * composer commands in `RuntimeDelegationParser`; kept here as data so
     * [childHasDelegationTool] stays a pure function of the tool list.
     *
     * Declared before [childHasDelegationTool] on purpose: an object initialises
     * its properties in declaration order, so a forward reference here would read
     * a not-yet-initialised set.
     */
    private val DELEGATION_TOOL_NAMES: Set<String> =
        setOf("subagent", "delegate", "team", "spawn_subagent")

    /**
     * Whether a delegated child is actually given a tool that could spawn
     * another agent.
     *
     * Derived from the real tool surface rather than hardcoded, because the two
     * facts this block must keep apart are "the tree would allow this node more
     * children" ([AgentCapabilitySnapshot.selfCanDelegate]) and "this model can
     * act on that allowance". Today they disagree: the child's list is
     * [AgentTools.makeChildAgentTools] and it contains no spawn tool — delegation
     * in this app is a human-typed composer command. If a spawn tool is ever
     * added to that list, this flips to true on its own and the prompt starts
     * issuing real instructions instead of describing a capacity nobody can use.
     */
    val childHasDelegationTool: Boolean = AgentTools.makeChildAgentTools().any { definition ->
        definition.name in DELEGATION_TOOL_NAMES
    }

    /**
     * Reminder appended to every PARENT-originated message. Team-mode peer
     * messages carry no reminder: peers are siblings, not the child's
     * principal, and the requirement keeps peer traffic from re-teaching the
     * protocol. The birth instruction itself stays in the system prompt, so it
     * is present on every model call regardless of who sent the message.
     */
    const val MESSAGE_REMINDER: String =
        "[child-completion-protocol] When this work is done, finish by calling the `" +
            AgentTools.SUBAGENT_COMPLETE_TOOL_NAME +
            "` tool with tool_title and summary. A reply that ends without that call is " +
            "recorded as an abnormal termination."

    /**
     * True when [delivery] came from the parent (and therefore gets a reminder).
     *
     * [capabilities] is the child's own capability snapshot, re-read for every
     * message: it is appended to the reminder so a top-down message always
     * carries who the receiver is. `null` means the runtime could not resolve
     * the node and is rendered as an explicit "unknown" — never as "cannot
     * delegate", because those two states are not interchangeable.
     *
     * Team-peer traffic still returns null, capabilities or not: peers are
     * siblings, not the child's principal.
     *
     * [capabilities] has no default on purpose: a new call site that forgets to
     * wire the snapshot would otherwise render a silent "unknown" block forever
     * while every test stayed green. Forcing the argument turns that into a
     * compile error, and passing an explicit `null` is then a visible decision.
     */
    fun reminderFor(
        delivery: RuntimeDelivery,
        capabilities: AgentCapabilitySnapshot?,
    ): String? = if (delivery == RuntimeDelivery.TEAM_PEER) {
        null
    } else {
        MESSAGE_REMINDER + "\n\n" + capabilityBlock(capabilities)
    }

    /** True when [toolName] is the completion tool. */
    fun isCompletionCall(toolName: String): Boolean = toolName == TOOL_NAME

    /**
     * Birth injection: the block appended to the child's system prompt on every
     * model call. Never appended to the main agent's prompt — the main agent
     * has no delegated sibling to end.
     *
     * [capabilities] has no default for the same reason as [reminderFor]: wiring
     * this to nothing must fail to compile rather than degrade silently.
     */
    fun systemInstruction(capabilities: AgentCapabilitySnapshot?): String = buildString {
        append("## Delegated child completion protocol\n")
        append("You are a delegated child agent, not the main agent. You have exactly one way to ")
        append("declare that this run ended normally: call the `")
        append(AgentTools.SUBAGENT_COMPLETE_TOOL_NAME)
        append("` tool. Call it once, as your last action, after the delegated request is ")
        append("actually satisfied and you have the result the parent needs. A reply that ends ")
        append("without that call is recorded as an abnormal termination, and the parent is told ")
        append("to decide whether to restart you. Never report completion in prose only.\n")
        append("You may call these tools: ")
        append(AgentTools.makeChildAgentTools().joinToString(", ") { it.name })
        append(".\n")
        append("\n")
        append(capabilityBlock(capabilities))
    }

    /**
     * [T-android-child-agent-completion] The delegation-capability metadata that
     * every top-down message carries, written for the child model rather than
     * for the runtime: can this node delegate further, can its own subagents
     * delegate, how many parallel slots exist and are in use, and — the point of
     * the whole block — whether the reader is a final executor or a manager.
     * Without it a child can only learn its role by spending a tool call on
     * `supervise_descendants`, and messages that arrive before that call leave
     * it guessing whether to work or to plan a parallel split.
     *
     * Two different things are deliberately kept apart, because conflating them
     * made an earlier version of this block argue with itself:
     *
     *  - **The layer's capacity** ([AgentCapabilitySnapshot.selfCanDelegate],
     *    [AgentCapabilitySnapshot.descendantCanDelegate]) is a property of the
     *    runtime tree. It gates whether `createChild` would be accepted, and the
     *    requirement asks for it by name.
     *  - **The tool a model could call** is a separate question, and today the
     *    answer is a flat no: [AgentTools.makeChildAgentTools] offers no spawn
     *    tool, and neither does the main agent's list — delegation in this app is
     *    a human-typed composer command. So a block that read "your layer can
     *    delegate, plan a parallel split" was ordering a call the child cannot
     *    make.
     *
     * The wording therefore reports the capacity as a fact while stating plainly
     * that the child cannot act on it, and the tool line is derived from
     * [childHasDelegationTool] so it starts telling the truth automatically if a
     * spawn tool is ever added.
     *
     * Single producer for both injections (birth and per-message), so the two
     * can never drift. A null snapshot yields an explicit unknown block: it must
     * neither crash nor claim the child cannot delegate.
     */
    fun capabilityBlock(capabilities: AgentCapabilitySnapshot?): String {
        if (capabilities == null) {
            return buildString {
                append("### Your delegation capability\n")
                append("- Unknown: the runtime could not resolve a capability snapshot for this node, ")
                append("so whether you may delegate further is NOT established. Do not assume you can ")
                append("spawn subagents and do not assume you cannot. If splitting the work matters ")
                append("here, say so in your result instead of guessing.\n")
            }
        }
        val slots = capabilities.currentActiveChildren.toString() + "/" +
            capabilities.maxParallelSubagents
        return buildString {
            append("### Your delegation capability\n")
            append("- Node `").append(capabilities.nodeId).append("`, depth ")
            append(capabilities.depth).append(" of ").append(capabilities.maxDepth)
            append(" (").append(capabilities.effectiveDepth)
            append(" level(s) of delegation left below you), mode ")
            append(capabilities.delegationMode.name).append(".\n")
            append("- Delegation capacity of your layer: ")
            if (capabilities.selfCanDelegate) {
                append("ALLOWED — ").append(slots).append(" subagent slot(s) in use.\n")
            } else {
                append("NONE — ")
                append(
                    if (capabilities.effectiveDepth <= 0) {
                        "you are already at the maximum delegation depth.\n"
                    } else {
                        "all " + capabilities.maxParallelSubagents +
                            " subagent slot(s) are in use (" + slots + ").\n"
                    },
                )
            }
            append("- Can your own subagents delegate? ")
            append(
                if (capabilities.descendantCanDelegate) {
                    "YES — they would be managers themselves.\n"
                } else {
                    "NO — any subagent below you is a final executor.\n"
                },
            )
            append("- Delegation tool available to you: ")
            append(
                if (childHasDelegationTool) {
                    "YES.\n"
                } else {
                    "NONE — the capacity above is only the tree's allowance for this node, not " +
                        "something you can act on: you have no tool that spawns a subagent.\n"
                },
            )
            append("- Your role: ")
            append(roleConclusion(capabilities))
            append("\n")
        }
    }

    /**
     * The role verdict the requirement asks for: a child must know whether it is
     * the last layer that actually does the work or an intermediate manager that
     * is expected to split it.
     *
     * "Cannot delegate right now" has two different causes with two different
     * futures, so they get two different sentences. Being out of depth is
     * permanent for this run; being out of parallel slots is temporary. Collapsing
     * both into one verdict made the block argue with itself — a slot-blocked node
     * read "FINAL EXECUTOR" directly above "your own subagents can delegate? YES",
     * which is exactly the role confusion the requirement is trying to remove.
     *
     * Every branch also respects [childHasDelegationTool]: a verdict may not order
     * a split the child has no means to perform. While the tool is absent, a
     * managing layer is told to report the split rather than to start it.
     */
    private fun roleConclusion(capabilities: AgentCapabilitySnapshot): String = when {
        !capabilities.selfCanDelegate && capabilities.effectiveDepth <= 0 ->
            "FINAL EXECUTOR. You are at the deepest layer this tree allows, so you cannot hand " +
                "this work down: solve the delegated request yourself and return the result."
        !capabilities.selfCanDelegate ->
            "EXECUTOR FOR NOW — your layer MAY hold further children, but no slot is free this " +
                "moment (" + capabilities.currentActiveChildren + " of " +
                capabilities.maxParallelSubagents + " in use), so you are the one who must do " +
                "this work. Start on it rather than waiting for a slot. If the request genuinely " +
                "needs splitting, say so in your result and let the parent decide, instead of " +
                "blocking on capacity."
        !capabilities.descendantCanDelegate ->
            "MANAGING LAYER OVER FINAL EXECUTORS. Your layer may hold further children, and those " +
                "children would be final executors, so a split would have to hand each of them one " +
                "self-contained piece that a single executor can finish. " +
                splitInstruction()
        else ->
            "INTERMEDIATE MANAGER. Your layer sits in the middle of the tree and may hold further " +
                "children, and those children could manage children of their own, so a multi-level " +
                "parallel split is structurally available. " + splitInstruction()
    }

    /**
     * What a managing layer should DO about a split, which depends on whether it
     * can act. While no spawn tool exists the honest instruction is to report the
     * split rather than to start one — and the tool line above has already
     * explained why, so this only says what to do about it.
     */
    private fun splitInstruction(): String = if (childHasDelegationTool) {
        "You may split the request and delegate, and you still own the result you return."
    } else {
        "Since you cannot start that split, do the part you can and state plainly in your result " +
            "what should be split off and why — the parent or the user decides whether to start " +
            "it. You still own the result you return."
    }

    /** The completion summary the parent sees; falls back when the model omits it. */
    fun completionSummary(args: JSONObject?): String =
        args?.optString("summary")?.trim().orEmpty().ifBlank { "Delegated child reported completion." }

    /** The completion title for the tool card; falls back when the model omits it. */
    fun completionTitle(args: JSONObject?): String =
        args?.optString("tool_title")?.trim().orEmpty().ifBlank { "Delegated task complete" }
}

/**
 * Bounded tool loop for one delegated child run.
 *
 * Termination is the whole point: the loop stops on the completion tool (a
 * natural end), on a turn with no tool calls (not a natural end) or when the
 * turn budget runs out (not a natural end). The caller decides what the runtime
 * records; this class only reports which of the three happened.
 *
 * @param maxTurns hard ceiling on model round-trips, so a child that keeps
 *   calling tools can never spin forever.
 * @param executeTool executes one tool call and returns what the model should
 *   read back. Unknown/unavailable tools must come back as an error outcome
 *   rather than a throw, so the child can recover inside its budget.
 */
class ChildAgentLoop(
    private val maxTurns: Int = DEFAULT_MAX_TURNS,
    private val executeTool: suspend (ChildToolCall) -> ChildToolOutcome,
) {
    suspend fun run(
        seed: List<LLMMessage>,
        /**
         * [T-android-agent-messaging] Runs immediately before EVERY model call,
         * with the loop's live message list, so a caller can append what arrived
         * DURING the run.
         *
         * Why the loop and not the caller: only the loop knows when a round is
         * about to start. Before this hook existed the child runner drained its
         * runtime mailbox once, before the loop, which made a mid-run delivery
         * unreadable — a message sent while the child was working stayed in its
         * inbox until the run ended, and a single-round child never read it at
         * all. Appending through this hook is what turns "steer" into an
         * instruction the current run can actually act on.
         *
         * Optional and defaulted so existing callers (and the tests that drive
         * this loop directly) keep their exact behaviour: with no hook, the loop
         * behaves as it did before the parameter existed.
         */
        beforeTurn: (suspend (MutableList<LLMMessage>) -> Unit)? = null,
        callModel: suspend (List<LLMMessage>) -> ChildTurnResult,
    ): ChildLoopOutcome {
        require(maxTurns >= 1) { "maxTurns must be at least 1" }
        val messages = seed.toMutableList()
        val collectedText = StringBuilder()
        var lastUsage: LLMUsage? = null

        for (turn in 1..maxTurns) {
            beforeTurn?.invoke(messages)
            val result = callModel(messages)
            lastUsage = result.usage ?: lastUsage
            val turnText = result.text.trim()
            if (turnText.isNotEmpty()) {
                if (collectedText.isNotEmpty()) collectedText.append("\n\n")
                collectedText.append(turnText)
            }

            val completion = result.toolCalls.firstOrNull {
                ChildCompletionProtocol.isCompletionCall(it.name)
            }
            if (completion != null) {
                return ChildLoopOutcome(
                    turns = turn,
                    text = collectedText.toString(),
                    usage = lastUsage,
                    endReason = ChildEndReason.COMPLETION_TOOL,
                    completionTitle = ChildCompletionProtocol.completionTitle(completion.args),
                    completionSummary = ChildCompletionProtocol.completionSummary(completion.args),
                )
            }

            if (result.toolCalls.isEmpty()) {
                return ChildLoopOutcome(
                    turns = turn,
                    text = collectedText.toString(),
                    usage = lastUsage,
                    endReason = ChildEndReason.NO_TOOL_CALL,
                )
            }

            messages.add(
                LLMMessage(
                    role = LLMMessage.Role.ASSISTANT,
                    content = result.text,
                    contentParts = buildList {
                        if (result.text.isNotEmpty()) add(AgentContentPart.Text(result.text))
                        result.toolCalls.forEach { call ->
                            add(AgentContentPart.ToolUse(call.id, call.name, call.args))
                        }
                    },
                ),
            )
            val resultParts = mutableListOf<AgentContentPart>()
            result.toolCalls.forEach { call ->
                val outcome = executeTool(call)
                resultParts.add(
                    AgentContentPart.ToolResult(
                        id = call.id,
                        name = call.name,
                        content = outcome.output,
                        isError = outcome.isError,
                    ),
                )
            }
            messages.add(
                LLMMessage(
                    role = LLMMessage.Role.USER,
                    content = "",
                    contentParts = resultParts,
                ),
            )
        }

        return ChildLoopOutcome(
            turns = maxTurns,
            text = collectedText.toString(),
            usage = lastUsage,
            endReason = ChildEndReason.TURN_BUDGET_EXHAUSTED,
        )
    }

    companion object {
        /**
         * Model round-trips allowed for one delegated child run. Large enough
         * for a research child (search, fetch, search again, then complete),
         * small enough that a looping child cannot burn an unbounded budget.
         */
        const val DEFAULT_MAX_TURNS: Int = 8

        /**
         * Honest answer for a tool the child is not given. Handing back the real
         * list (instead of a bare "unknown tool") is what lets the model finish
         * its remaining work with what it has, or say plainly what it could not
         * do — either way it still has to call the completion tool.
         */
        fun unavailableToolMessage(toolName: String): String = buildString {
            append("Tool '").append(toolName).append("' is not available to a delegated child agent.")
            append(" Tools you can call: ")
            append(AgentTools.makeChildAgentTools().joinToString(", ") { it.name })
            append(". Continue with those, or state plainly in your final answer what you could not do.")
        }
    }
}
