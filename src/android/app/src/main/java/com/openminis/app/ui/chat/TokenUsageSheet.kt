package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.i18n.uppercaseForDisplay
import java.util.Locale

/**
 * Session Token Usage bottom sheet — mirrors iOS `TokenUsageSheet` and uses
 * the standardized chat sheet shell so its header/dismiss behavior matches
 * every other "⋯" menu sheet.
 *
 * Sections (top to bottom):
 *   - Context: Context Used / Context Window / Max Output
 *   - Thinking (only when the model supports reasoning): On/Off / Level / Supported
 *   - Tokens (Session Total): Total (input incl. cache + output) / Input (incl. cache) / Output
 *   - Runtime: how long the conversation actually ran (see [activeDurationMillis],
 *     which is deliberately NOT "now minus creation time")
 *   - Cache (Session Total): Cache Read / Cache Write
 *   - Agent Loop: Total Loops
 *
 * Token counts are rendered by AgentTopology's [formatAgentTokens] — the same
 * K/M/B formatter the topology cards use — rather than a second local
 * implementation, so one concept cannot drift into two shapes.
 *
 * Data loads asynchronously via [ChatViewModel.loadSessionTokenStats] when the
 * sheet appears; we intentionally don't hold a live subscription — token
 * counters change per API call, not per keystroke, so pull-on-open is enough.
 */
@Composable
fun TokenUsageSheet(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
) {
    var stats by remember { mutableStateOf<ChatViewModel.SessionTokenStats?>(null) }
    val contextWindow = remember { viewModel.currentModelContextWindow }
    val maxOutput = remember { viewModel.currentModelMaxOutputTokens }
    val thinking = remember { viewModel.thinkingInfo() }

    LaunchedEffect(Unit) {
        stats = viewModel.loadSessionTokenStats()
    }

    StandardChatSheet(
        title = stringResource(R.string.token_usage_sheet_title),
        onDismiss = onDismiss,
        // T148: iOS uses .presentationDetents([.medium]) for the same sheet
        // (AIChatView.swift:508). Match that proportion on Android so the
        // half-screen feel is consistent — the token-usage view holds maybe
        // a screenful of stat rows max and looked overgrown at 90%.
        heightFraction = 0.5f,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            val s = stats
            val onText = stringResource(R.string.common_on)
            val offText = stringResource(R.string.common_off)
            val yesText = stringResource(R.string.common_yes)
            val noText = stringResource(R.string.common_no)
            val runtimeStrings = RuntimeFormatStrings(
                hoursMinutes = stringResource(R.string.token_usage_runtime_hours_minutes),
                minutesSeconds = stringResource(R.string.token_usage_runtime_minutes_seconds),
                minutes = stringResource(R.string.token_usage_runtime_minutes),
                lessThanMinute = stringResource(R.string.token_usage_runtime_under_minute),
            )
            StatSection(title = stringResource(R.string.token_usage_section_context)) {
                StatRow(stringResource(R.string.token_usage_context_used), displayTokens((s?.context ?: 0).toLong()))
                contextWindow?.let { StatRow(stringResource(R.string.token_usage_context_window), displayTokens(it.toLong())) }
                maxOutput?.let { StatRow(stringResource(R.string.token_usage_max_output), displayTokens(it.toLong())) }
            }

            thinking?.let { t ->
                StatSection(title = stringResource(R.string.token_usage_section_thinking)) {
                    StatRow(stringResource(R.string.token_usage_thinking_label), if (t.enabled) onText else offText)
                    if (t.enabled) StatRow(stringResource(R.string.token_usage_thinking_level), t.level)
                    StatRow(stringResource(R.string.token_usage_thinking_supported), if (t.supported) yesText else noText)
                }
            }

            StatSection(title = stringResource(R.string.token_usage_section_tokens)) {
                val inputTotal = (s?.input ?: 0L) + (s?.cacheRead ?: 0L) + (s?.cacheWrite ?: 0L)
                val outputTotal = s?.output ?: 0L
                StatRow(
                    stringResource(R.string.token_usage_total_tokens),
                    displayTokens(sessionTotalTokens(inputTotal, outputTotal)),
                )
                StatRow(stringResource(R.string.token_usage_input_with_cache), displayTokens(inputTotal))
                StatRow(stringResource(R.string.token_usage_output), displayTokens(outputTotal))
            }

            StatSection(title = stringResource(R.string.token_usage_section_runtime)) {
                // Distinguish "not known yet / not readable" from "really zero".
                // Rendering a missing number as "不到 1 分" asserts something we
                // did not measure — and it contradicted the token rows right
                // above it, which stay populated while the runtime is not.
                StatRow(
                    stringResource(R.string.token_usage_runtime_active),
                    s?.activeMillis?.let { formatActiveDuration(it, runtimeStrings) }
                        ?: stringResource(R.string.token_usage_runtime_unavailable),
                )
            }

            StatSection(title = stringResource(R.string.token_usage_section_cache)) {
                StatRow(stringResource(R.string.token_usage_cache_read), displayTokens(s?.cacheRead ?: 0L))
                StatRow(stringResource(R.string.token_usage_cache_write), displayTokens(s?.cacheWrite ?: 0L))
                StatRow(
                    stringResource(R.string.token_usage_cache_hit_rate),
                    s?.cacheHitRatePercent?.let { String.format("%.1f%%", it) }
                        ?: stringResource(R.string.token_usage_cache_hit_rate_unavailable),
                )
            }

            StatSection(title = stringResource(R.string.token_usage_section_agent_loop)) {
                StatRow(stringResource(R.string.token_usage_total_loops), (s?.loopCount ?: 0).toString())
            }
        }
    }
}

@Composable
private fun StatSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title.uppercaseForDisplay(),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        content()
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = value,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    )
}

// Token counts are rendered with AgentTopology's [formatAgentTokens] (K/M/B,
// one decimal, rounding-aware unit promotion) so the sheet and the agent
// topology cards cannot disagree about the same number. The local `%.1fM/%.1fK`
// copy that used to live here was removed for that reason — the repo has been
// bitten before by the same concept redefined per screen.
//
// Two behaviour changes came with that reuse, both deliberate:
//  - the shared formatter is Locale-independent (`Locale.US`), so a device in a
//    comma-decimal locale no longer renders "128,0K";
//  - it drops a trailing ".0" ("128.0K" → "128K"), which is the shape the
//    topology cards already had.

/**
 * Display-safe wrapper for [formatAgentTokens], which `require`s a non-negative
 * count and would therefore throw *inside composition* — taking the whole sheet
 * down — if a negative ever reached it.
 *
 * Model config cannot supply one today (every write path guards `> 0`:
 * ModelEntryDetailScreen, ProviderMutationMethods, ModelsCollection). Token
 * usage CAN: those numbers are summed from `token_usage` JSON read straight out
 * of the database (`ChatDao.tokenUsages`), and rows may arrive from a backup
 * import or another platform without passing our validation. Degrading a
 * corrupt number to 0 is strictly better than a crash on a stats panel.
 */
private fun displayTokens(value: Long): String = formatAgentTokens(if (value < 0L) 0L else value)

/**
 * The session total the panel prints: every input token the provider billed —
 * fresh input plus cache read plus cache write, i.e. the same number shown as
 * `Input (incl. cache)` — plus output.
 *
 * Defined as "the two rows printed below it, added up" rather than as a second,
 * independent summation: a total that can drift from the rows a user can add up
 * by hand would be worse than showing no total at all.
 *
 * Relationship to the agent topology's per-node number, which the requirement
 * points at: that one aggregates `inputTokens + outputTokens` only
 * ([formatAgentTokens] from AgentTopology, via `aggregateAgentTopologyTokens`),
 * so for the same session it is SMALLER by the cache portion. The difference is
 * intentional and visible rather than silent: this panel prints
 * `Input (incl. cache)` directly under the total, and cache read/write ARE input
 * the conversation consumed. Do not "align" this row to the topology by
 * dropping the cache — that would make the total disagree with the rows above
 * it, which reads as a bug.
 */
internal fun sessionTotalTokens(inputWithCache: Long, output: Long): Long = inputWithCache + output

/**
 * One persisted transcript row, reduced to the two facts the runtime
 * aggregation needs.
 *
 * A dedicated type instead of `MessageEntity` on purpose: the rule below is a
 * pure function of "when was this row written" and "does it mark the end of an
 * assistant turn", so keeping it free of the Room/DB type lets the rule be
 * asserted in a plain JVM test.
 */
internal data class ActiveTimeRow(
    /** `messages.created_at`, epoch millis — the moment the row was persisted. */
    val createdAtMillis: Long,
    /** `messages.role` verbatim; [isTurnEndRow] decides what it means. */
    val role: String,
)

/**
 * The role that marks the end of a turn, as the single source of truth.
 *
 * An assistant row is only persisted after that turn's model call AND all of its
 * tools finished (`persistAssistantTurn`; tool results land as `role = "user"`,
 * so they never fake a turn end). Every `appendMessage("assistant", …)` call
 * site was checked and none writes mid-turn.
 *
 * This used to be an inline `row.role == "assistant"` at the call site, which
 * meant the ONE decision the whole feature rests on was untestable from a JVM
 * test — an adversarial audit showed that flipping it to `"user"` (or to a
 * constant) kept every test green while making the panel report the idle time
 * BETWEEN turns, i.e. the exact opposite of the requirement. Naming it makes
 * that decision assertable.
 */
internal const val TURN_END_ROLE: String = "assistant"

/** Whether a persisted row marks the completion of a turn. */
internal fun isTurnEndRow(role: String): Boolean = role == TURN_END_ROLE

/**
 * Active runtime: how long this conversation actually spent RUNNING — not how
 * long ago it was created.
 *
 * Definition — the union of the per-turn spans
 * `[previousRow.createdAt, turnEndRow.createdAt]`, summed:
 *
 *  - An assistant row only reaches the DB once that iteration's model call AND
 *    its tool calls have finished (`ChatViewModel.persistAssistantTurn`, or the
 *    runtime child runner), so its `createdAt` is the instant the work ended.
 *  - The row immediately before it — the human message that started the turn, or
 *    the tool-result row that started the next iteration — was written when that
 *    work began.
 *  - Each span is therefore one iteration of real work. Everything the app did
 *    NOT run (time the user spent typing, backgrounded app, phone off) falls in
 *    the gaps BETWEEN spans and is not counted. A conversation created three days
 *    ago but used for two minutes reports two minutes.
 *
 * Union rather than a plain sum: rows are adjacent by `sort_order`, so spans do
 * not normally overlap — but a clock adjustment can make an earlier span end
 * after a later one starts, and merging overlapping spans stops such a case from
 * being billed twice.
 *
 * Two honest gaps, neither of them hidden:
 *  - The turn currently running is not in the DB yet, so it is excluded until it
 *    finishes. Counting it live would need an in-memory turn-start timestamp that
 *    does not exist today.
 *  - `messages.updated_at` is deliberately NOT used as the turn-end marker. It is
 *    written only by stream-interrupt counting, rerun trimming and in-place user
 *    message rewrites (see ChatDao), never at a normal turn end — so on a healthy
 *    row it is NULL, and where it is set it carries an interrupt/rewrite instant,
 *    which is the wrong moment for this purpose.
 *
 * @param rows rows of one session ordered by `sort_order` ascending.
 */
/**
 * [T-android-token-runtime-process-clamp] Wall-clock instant the CURRENT app
 * process started.
 *
 * Subtracting elapsed-realtime-since-process-start from the wall clock is exact
 * and needs no Application hook, so it cannot be forgotten or mis-ordered. The
 * app's minSdk is 26, well above the API 24 that provides
 * [android.os.Process.getStartElapsedRealtime].
 *
 * The formula is absolute rather than relative to first use, so evaluating it
 * lazily (on first panel open) is still correct.
 */
internal fun currentProcessStartWallClockMillis(): Long =
    System.currentTimeMillis() -
        (android.os.SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime())

/**
 * Total time this conversation actually ran, in millis.
 *
 * A turn's work is the gap between the row that started it and the assistant row
 * that ended it — the assistant row is only persisted once that turn's model call
 * AND all of its tools have finished, so the user typing, the app being
 * backgrounded and the phone being off all fall BETWEEN spans and are naturally
 * excluded (the requirement is explicit: "实际在跑的时间，不是说这个对话创建了多久
 * 的时间").
 *
 * [processStartMillis] guards the one case where that inference breaks. When the
 * process is killed mid-turn, the history keeps a hole: nothing is persisted at
 * the moment of death, so the first assistant row written after the app comes
 * back is still adjacent to the row from BEFORE the shutdown. The gap is then the
 * whole downtime — a session resumed the next morning reported "23 小时 0 分"
 * instead of the few seconds it had run.
 *
 * The fix uses a fact about the data rather than a threshold guess: if a span's
 * END row was written at or after this process started, then that row was written
 * by THIS process, so the work it represents cannot have begun before this
 * process did. Clamping the span start to [processStartMillis] therefore removes
 * exactly the downtime and keeps the real work. No magic maximum, so a genuinely
 * long single turn is never truncated.
 *
 * Deliberately NOT clamped when the end row predates this process: that span
 * belongs to an earlier process, and clamping it to the current process start
 * would erase real work instead of downtime. That residual case (downtime
 * spanning two PAST processes, e.g. resume then kill again before reopening) is a
 * known gap, not a silent wrong answer — see the test that pins this boundary.
 *
 * @param processStartMillis pass null when the process start is unknown; the
 *   measurement then degrades to the unclamped sum rather than inventing a clamp.
 */
internal fun activeDurationMillis(rows: List<ActiveTimeRow>, processStartMillis: Long? = null): Long {
    if (rows.size < 2) return 0L
    val starts = ArrayList<Long>()
    val ends = ArrayList<Long>()
    var previousCreatedAt = rows.first().createdAtMillis
    for (index in 1 until rows.size) {
        val row = rows[index]
        if (isTurnEndRow(row.role) && row.createdAtMillis > previousCreatedAt) {
            // Only clamp against an end row this process actually wrote.
            val clampedStart = processStartMillis
                ?.takeIf { row.createdAtMillis >= it }
                ?.coerceAtLeast(previousCreatedAt)
                ?: previousCreatedAt
            if (row.createdAtMillis > clampedStart) {
                starts.add(clampedStart)
                ends.add(row.createdAtMillis)
            }
        }
        previousCreatedAt = row.createdAtMillis
    }
    if (starts.isEmpty()) return 0L
    val order = starts.indices.sortedWith(compareBy({ starts[it] }, { ends[it] }))
    var total = 0L
    var mergedStart = starts[order.first()]
    var mergedEnd = ends[order.first()]
    for (position in order.drop(1)) {
        val start = starts[position]
        val end = ends[position]
        if (start <= mergedEnd) {
            if (end > mergedEnd) mergedEnd = end
        } else {
            total += mergedEnd - mergedStart
            mergedStart = start
            mergedEnd = end
        }
    }
    return total + (mergedEnd - mergedStart)
}

/**
 * Localized templates for [formatActiveDuration].
 *
 * They are passed in rather than read from `stringResource` inside the formatter
 * so the formatting rule stays a pure JVM-testable function — the same shape
 * AgentTopology uses for `TopologyCardStrings`.
 */
internal data class RuntimeFormatStrings(
    val hoursMinutes: String,
    val minutesSeconds: String,
    val minutes: String,
    val lessThanMinute: String,
)

/**
 * Human-readable active runtime — `2 小时 13 分`, `43 分 12 秒`, `43 分`,
 * `不到 1 分` (English comes from the same slots; nothing here is hardcoded).
 *
 * Seconds are omitted when they are zero and the whole first minute reads as
 * "less than a minute", matching the requirement's examples. Long durations keep
 * the hour unit (`26 小时 3 分`) instead of inventing a day unit the panel does
 * not otherwise use.
 */
internal fun formatActiveDuration(millis: Long, strings: RuntimeFormatStrings): String {
    val totalSeconds = if (millis < 0L) 0L else millis / 1000L
    if (totalSeconds < 60L) return strings.lessThanMinute
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0L -> String.format(Locale.US, strings.hoursMinutes, hours, minutes)
        seconds > 0L -> String.format(Locale.US, strings.minutesSeconds, minutes, seconds)
        else -> String.format(Locale.US, strings.minutes, minutes)
    }
}
