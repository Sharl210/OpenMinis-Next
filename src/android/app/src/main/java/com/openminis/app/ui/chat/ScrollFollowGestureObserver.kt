package com.openminis.app.ui.chat

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * How close to a scroller's own end counts as "parked at the end". One value for
 * every long-content scroller (thinking panel, code block): the judgement that
 * arms the pause and the judgement that clears it must not drift apart.
 */
internal const val FOLLOW_AT_END_TOLERANCE_PX = 4

/** True when [scrollState] is parked at its own end, within the shared tolerance. */
internal fun ScrollState.isParkedAtEnd(): Boolean =
    maxValue - value <= FOLLOW_AT_END_TOLERANCE_PX

/**
 * Observes a vertical gesture without consuming it, so the owning ScrollState
 * remains responsible for the actual movement. Layout/recomposition changes do
 * not call either callback; only a gesture with measurable movement does.
 */
internal fun Modifier.observeVerticalDrag(
    key: Any,
    atBottom: () -> Boolean,
    onStopped: (atBottom: Boolean) -> Unit,
): Modifier = pointerInput(key) {
    awaitEachGesture {
        val down = awaitFirstDown(
            requireUnconsumed = false,
            pass = PointerEventPass.Initial,
        )
        var moved = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                if (moved) onStopped(atBottom())
                break
            }
            val delta: Offset = change.positionChange()
            if (abs(delta.y) > 1f) moved = true
        }
    }
}

/**
 * Installs the *resume* half of the long-content follow contract: as soon as the
 * scroller is parked at its end and nothing is scrolling any more, the owner is
 * told so and can put its [ScrollFollowStateMachine] back into
 * [ScrollFollowState.FOLLOWING].
 *
 * Why the resume cannot ride on the gesture that caused it: [observeVerticalDrag]
 * reports at finger-up, and `DragInteraction.Stop` is emitted before the fling
 * even starts (Compose `DragGestureNode.processDragStop` emits Stop, then calls
 * `onDragStopped`, which performs the fling). A flick to the end therefore
 * lands there *after* the only sample the gesture path takes, so a gesture-only
 * resume leaves the view paused at the end forever — the reported "panel keeps
 * growing but stays where it was" behaviour. Judging the position instead makes
 * the resume independent of how the view got to the end: drag, fling or
 * programmatic scroll all end with the same observable state, "parked at the
 * end, not scrolling".
 *
 * Detection goes through `snapshotFlow` on purpose: it re-evaluates when the
 * position settles, not on every frame of the movement, and
 * `distinctUntilChanged` makes the callback fire on the false→true edge only, so
 * the owner sees one "parked" event per arrival rather than one per frame.
 *
 * @param key re-installs the observer when the scroller identity changes
 *   (session switch, new block) — mirrors [observeVerticalDrag]'s key.
 * @param atEnd the same predicate the pause side uses, so the two halves agree.
 * @param scrollInProgress suppresses the callback while a drag, fling or
 *   programmatic scroll is still moving the view; a finger still on the screen
 *   has not finished expressing intent yet.
 * @param onParkedAtEnd callback that re-arms following; it must not itself start
 *   a scroll when the view is already parked (a scroll would flip
 *   [scrollInProgress] back on and re-trigger the callback).
 */
@Composable
internal fun ObserveFollowResume(
    key: Any,
    atEnd: () -> Boolean,
    scrollInProgress: () -> Boolean,
    onParkedAtEnd: () -> Unit,
) {
    LaunchedEffect(key) {
        snapshotFlow { atEnd() && !scrollInProgress() }
            .distinctUntilChanged()
            .collect { parked ->
                if (parked) onParkedAtEnd()
            }
    }
}
