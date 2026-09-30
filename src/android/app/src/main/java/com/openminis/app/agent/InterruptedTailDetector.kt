package com.openminis.app.agent

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.MessagePartsCodec

/**
 * Which "the agent loop stopped early" shape a session's tail matches.
 *
 * Extracted from `ChatViewModel.loadSession` so the rule can be tested
 * directly: it decides whether the user gets a Resume affordance at all, and
 * a wrong answer is invisible in code review — either the banner never
 * appears (the GH#262/#263 report) or it appears over a turn that is simply
 * still waiting.
 *
 * ────────────────────────────────────────────────────────────────────────────
 * [T-android-interrupted-tail-drift] THIS OBJECT IS CURRENTLY DORMANT, AND THE
 * RULE IT HOLDS EXISTS IN TWO OTHER PLACES.
 *
 * As of this writing `classify()` has **zero callers in `main/`** — the live
 * rule is still the inline `when` block in `ChatViewModel.loadSession`
 * (search for `Case D`), and the log line there re-derives the shape label from
 * a **third** copy of the same predicates. So the same rule has been written
 * three times, and the copies had already drifted apart:
 *
 *   1. The inline version excludes app-authored reminders from the
 *      "unanswered human turn" case (an app-written `<system-reminder>` row must
 *      not light the Resume banner). **This object did not** — a tail written by
 *      `resume()`'s re-entry or the delegated-child abnormal-end note would have
 *      been reported as a reply-less human turn. Fixed here.
 *   2. For an EMPTY user turn the two disagree by design: the inline version
 *      treats it as unanswered (hence recoverable), while this object returns
 *      [InterruptedTailShape.NONE] — see the reasoning below the `when`. This
 *      object's answer is the deliberate one; if the object is ever wired in,
 *      that difference is a real behaviour change (a strictly smaller set of
 *      tails offers Resume) and must be called out in the change, not smuggled.
 *
 * WHY IT IS LEFT IN PLACE RATHER THAN DELETED. Wiring it in is the right end
 * state — it collapses three copies into one and gives the live rule the 11
 * tests that today guard a dormant object. That edit touches
 * `ChatViewModel.loadSession` and was deliberately not made as part of a
 * bug fix. Until it happens: **if you change the Case D logic in
 * `ChatViewModel`, change it here too**, or the next person to wire this in will
 * silently revert your fix.
 * ────────────────────────────────────────────────────────────────────────────
 */
enum class InterruptedTailShape {
    /** Tools completed, but the follow-up model call never fired. */
    TOOL_RESULT_TAIL,

    /** The model asked for tools that never executed. */
    ASSISTANT_TOOL_USE,

    /** The synthetic "user stopped the previous response" reminder was
     *  committed, but `resume()` never re-entered the loop. */
    CONTINUE_REMINDER,

    /**
     * A plain-text user turn with NO reply after it at all (GH#262/#263).
     *
     * `send()` persists the user row BEFORE the reply lands, and
     * `persistAssistantTurn()` drops an assistant row that has no parts — so a
     * process death in between (or a first-turn network failure, where
     * `setInlineError` has no assistant row to attach to) leaves a tail that
     * looks finished but never got an answer.
     */
    UNANSWERED_USER_TURN,

    /** Not interrupted. */
    NONE,
}

object InterruptedTailDetector {

    /** Marker text of the synthetic continue reminder (see `resume()`). */
    const val CONTINUE_REMINDER_MARKER = "The user stopped the previous response"

    /**
     * Classify [lastEntry] — the final entry of `agentHistory`.
     *
     * Callers MUST additionally gate on "nothing is currently streaming"
     * (`!isStreaming && !SessionActivityTracker.isActive(sid)`). This function
     * deliberately knows nothing about liveness: it answers "what shape is
     * this tail", not "is it safe to offer Resume", and conflating the two is
     * how a still-waiting turn would get a Resume banner.
     */
    fun classify(lastEntry: LLMMessage?): InterruptedTailShape {
        if (lastEntry == null) return InterruptedTailShape.NONE
        return when (lastEntry.role) {
            LLMMessage.Role.USER -> {
                val parts = lastEntry.contentParts
                val allToolResults = parts.isNotEmpty() &&
                    parts.all { it is AgentContentPart.ToolResult }
                val isContinueReminder = parts.size == 1 &&
                    (parts.first() as? AgentContentPart.Text)?.text
                        ?.contains(CONTINUE_REMINDER_MARKER) == true
                // [T-android-interrupted-tail-drift] An app-authored
                // `<system-reminder>` row is not an unanswered HUMAN turn, so it
                // must not be offered as one. These rows are written by the app on
                // the API's `user` role (`resume()`'s re-entry, and the
                // delegated-child abnormal-end note), and `MessagePartsCodec`
                // already treats this exact prefix as "no human put content here"
                // (`hasHumanTurnContent`). Without this check a cold start would
                // show a paused/Resume affordance over the app's own note.
                //
                // Kept SEPARATE from `isContinueReminder` on purpose, and checked
                // AFTER it: `resume()`'s own reminder is also app-authored but its
                // turn really was cut off, so it must KEEP reporting an
                // interruption. Folding the two together would silently drop that
                // recovery path — the ordering here is load-bearing.
                val isAppReminder = parts.size == 1 &&
                    (parts.first() as? AgentContentPart.Text)?.text
                        ?.trimStart()
                        ?.startsWith(MessagePartsCodec.SYSTEM_REMINDER_PREFIX) == true
                when {
                    // Order matters: the first two describe a turn that was
                    // mid-flight; the rest describe one that never started.
                    allToolResults -> InterruptedTailShape.TOOL_RESULT_TAIL
                    isContinueReminder -> InterruptedTailShape.CONTINUE_REMINDER
                    isAppReminder -> InterruptedTailShape.NONE
                    // An EMPTY user turn is not a recoverable shape — there is
                    // nothing to answer, and re-sending it would post a
                    // content-less message the API rejects.
                    parts.isEmpty() -> InterruptedTailShape.NONE
                    else -> InterruptedTailShape.UNANSWERED_USER_TURN
                }
            }
            LLMMessage.Role.ASSISTANT ->
                if (lastEntry.contentParts.any { it is AgentContentPart.ToolUse }) {
                    InterruptedTailShape.ASSISTANT_TOOL_USE
                } else {
                    // A plain assistant reply IS the completed turn.
                    InterruptedTailShape.NONE
                }
            else -> InterruptedTailShape.NONE
        }
    }

    /** True when the tail is any recoverable shape. */
    fun isInterrupted(lastEntry: LLMMessage?): Boolean =
        classify(lastEntry) != InterruptedTailShape.NONE
}
