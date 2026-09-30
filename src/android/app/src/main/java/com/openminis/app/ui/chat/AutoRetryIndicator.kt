package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.theme.ChatColors

/**
 * [T-android-auto-retry-progress] What the chat shows while the automatic
 * retry mechanism is WAITING OUT its backoff.
 *
 * Field report (request.md): the auto-retry mechanism must be visible in the
 * UI *while it runs*, not only once it has given up. Before this row, the only
 * retry feedback on screen was the message-level `InlineErrorBanner`, whose
 * text was a hardcoded English `"<reason> — retrying (2/5)…"`, which meant:
 *
 *  - a Chinese session still read English;
 *  - nothing on screen moved while the app sat in the backoff wait;
 *  - the two StateFlows the ViewModel already published for that wait
 *    (`autoRetryAttempt`, `autoRetryCountdown`) had no reader anywhere.
 *
 * The banner is driven by `(attempt, maxAttempts, secondsRemaining)` and only
 * renders while a wait is genuinely in progress, so it appears exactly when
 * the user has nothing else to look at, and disappears the moment the next
 * attempt starts producing a stream.
 */
data class AutoRetryUiState(
    /** 1-based ordinal of the retry whose backoff is being waited out. */
    val attempt: Int,
    /** Retry budget in force; `-1` is the app's "unlimited" setting. */
    val maxAttempts: Int,
    /** Whole seconds left in the wait; `0` means the wait is over. */
    val secondsRemaining: Int,
) {
    /**
     * Only a positive budget makes "2 of 5" meaningful. `-1` (unlimited) and
     * `0` (retries disabled, in which case no wait is ever scheduled) must
     * never render "attempt 2 of 0".
     */
    val showsAttemptCap: Boolean get() = maxAttempts >= 1

    companion object {
        /**
         * Non-null only while a retry wait is live: an attempt must have been
         * scheduled AND the backoff clock must still be running. The ViewModel
         * publishes the attempt a beat before the first countdown tick, and
         * that same value can be left behind if a turn is cancelled while the
         * failure is still being handled — neither should paint this row.
         */
        fun of(attempt: Int, maxAttempts: Int, secondsRemaining: Int): AutoRetryUiState? =
            if (attempt >= 1 && secondsRemaining >= 1) {
                AutoRetryUiState(attempt, maxAttempts, secondsRemaining)
            } else {
                null
            }
    }
}

/**
 * One-line status row for an in-flight automatic retry: a live spinner plus
 * the localized "retrying in N s (attempt 2 of 5)" line, rendered where the
 * compaction row and the resume banner already live (just above the composer,
 * so it is on screen without scrolling).
 */
@Composable
internal fun AutoRetryIndicator(state: AutoRetryUiState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = ChatColors.tertiaryText,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (state.showsAttemptCap) {
                stringResource(
                    R.string.chat_auto_retry_progress,
                    state.secondsRemaining,
                    state.attempt,
                    state.maxAttempts,
                )
            } else {
                stringResource(
                    R.string.chat_auto_retry_progress_unlimited,
                    state.secondsRemaining,
                    state.attempt,
                )
            },
            fontSize = 14.sp,
            color = ChatColors.tertiaryText,
            modifier = Modifier.weight(1f),
        )
    }
}
