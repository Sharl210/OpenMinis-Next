package com.openminis.app.ui.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.ui.graphics.vector.ImageVector
import com.openminis.app.R

/**
 * How one queued message must look, as data.
 *
 * [icon] is an `ImageVector` rather than a colour or a flag so the STEER/QUEUE
 * difference survives a colour-blind reader and a greyscale screenshot — see
 * [QueuedPromptUiPolicy.badgeStyle] for why all three fields move together.
 */
internal data class QueuedPromptBadgeStyle(
    /** Label text ("STEER" / "QUEUE"), resolved by the caller via `stringResource`. */
    val labelRes: Int,
    /** Glyph shown beside the label. */
    val icon: ImageVector,
    /** `true` ⇒ dashed hairline; `false` ⇒ solid hairline. */
    val borderDashed: Boolean,
)

/**
 * Pure UI projections for queued prompts; keeps delivery/lifecycle semantics testable on the JVM.
 *
 * These are the ONLY copies of the rules. A call site projects through them rather
 * than re-deriving "is it still queued" / "is a turn in flight" locally — a second
 * copy is how the bubble ended up offering 撤回并编辑 for a prompt the ledger had
 * already claimed.
 */
internal object QueuedPromptUiPolicy {
    /**
     * True when the bubble's own long-press actions (Retry / Edit / Delete from
     * here) apply: a message the model already has, with no turn in flight.
     *
     * A queued message is excluded on purpose — its content is not final yet, so
     * Retry (which truncates history) and Delete-from-here are not offered. Its
     * own withdraw affordances are gated by [isWithdrawable] instead.
     */
    fun canActOnSentMessage(isQueued: Boolean, isStreaming: Boolean): Boolean =
        !isQueued && !isStreaming

    /**
     * The bubble's withdraw affordances (撤回并编辑 / 撤回) exist exactly while the
     * prompt is still ENQUEUED — i.e. before the model has read it. A claimed or
     * consumed prompt is already in the model's hands.
     */
    fun canWithdraw(state: QueuedPromptState): Boolean =
        state.lifecycle == QueuedPromptLifecycle.ENQUEUED

    /**
     * [canWithdraw] resolved against the live ledger for one bubble.
     *
     * This is what keeps "the button is visible" and "the ledger will accept it"
     * from drifting apart: the bubble flag is this value, not a hand-set boolean.
     */
    fun isWithdrawable(ledger: QueuedPromptLedger, promptId: String?): Boolean {
        if (promptId == null) return false
        val state = ledger.state(promptId) ?: return false
        return canWithdraw(state)
    }

    /**
     * What a withdraw / discard tap turned into. A refused attempt must surface
     * [QueuedWithdrawOutcome.ALREADY_STARTED] — the ledger only refuses once the
     * model has claimed the prompt, so silence is the one wrong answer.
     */
    fun withdrawOutcome(accepted: Boolean): QueuedWithdrawOutcome =
        if (accepted) QueuedWithdrawOutcome.WITHDRAWN else QueuedWithdrawOutcome.ALREADY_STARTED

    /**
     * [T-android-queued-delivery-visual-split] The ONE definition of how a
     * queued/steered message is told apart at a glance.
     *
     * Requirement (request.md:172, verbatim): 「以插队模式发送和排队模式发送的，他们两个
     * 也要有差别，就是他们两个的**显示的样式也要有差别**，就是**一眼能让我们分辨出来**，
     * 哪一个是插队，就是他插队要有对应的一个STEER的标识，然后排队要有QUEUE的标识，
     * 然后你设计的一个让他们尽量不要去遮挡整个消息的一个显示，你可以设计在边框上或者
     * 说整个边线上，做上这种带字母的logo」.
     *
     * Before this, both deliveries rendered the SAME row: identical position
     * (above the bubble), identical `labelSmall` type, identical
     * `secondaryTextColor`, no glyph, and a border whose dash pattern was keyed
     * on `isQueued` — i.e. identical for STEER and QUEUE. Only the two words
     * differed, so "显示的样式也要有差别" was not met.
     *
     * Three INDEPENDENT discriminators move together, on purpose:
     *
     *  1. **[labelRes]** — the "带字母的logo" the requirement names (STEER/QUEUE).
     *  2. **[icon]** — `Bolt` (cut in) vs `Schedule` (waiting in line).
     *  3. **[borderDashed]** — solid (already in force) vs dashed (still waiting).
     *
     * NOT colour. Colour is the cheapest-looking option and the only one that
     * fails outright for a red-green colour-blind user; every discriminator here
     * survives greyscale, so the split is readable in a screenshot, a screen
     * reader, and a monochrome theme.
     *
     * The border is where the requirement asked for this (「尽量...不要去遮挡整个消息的
     * 显示」), which is why the third discriminator is the hairline's own shape
     * rather than anything inside the bubble's text area.
     *
     * `null` (a row written before `queuedDelivery` existed) maps to QUEUE, the
     * same fallback the label already used — a persisted row must render as
     * *something* determinate rather than as a third, unstyled state.
     */
    fun badgeStyle(delivery: QueuedPromptDelivery?): QueuedPromptBadgeStyle = when (delivery) {
        QueuedPromptDelivery.STEER -> QueuedPromptBadgeStyle(
            labelRes = R.string.chat_queue_steer_badge,
            icon = Icons.Filled.Bolt,
            borderDashed = false,
        )
        QueuedPromptDelivery.QUEUE, null -> QueuedPromptBadgeStyle(
            labelRes = R.string.chat_queue_queue_badge,
            icon = Icons.Filled.Schedule,
            borderDashed = true,
        )
    }
}

/** Result of a queued 撤回 / 撤回并编辑 tap, as the UI must render it. */
internal enum class QueuedWithdrawOutcome {
    /** The prompt was still unclaimed; the withdraw went through. */
    WITHDRAWN,

    /** The model had already claimed the prompt; nothing changed and the user must be told. */
    ALREADY_STARTED,
}
