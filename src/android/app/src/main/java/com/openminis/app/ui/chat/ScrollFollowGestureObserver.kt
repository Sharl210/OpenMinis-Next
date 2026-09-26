package com.openminis.app.ui.chat

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

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
