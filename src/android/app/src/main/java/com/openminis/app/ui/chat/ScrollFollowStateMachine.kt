package com.openminis.app.ui.chat

/**
 * Whether long chat content should stay anchored to its end.
 *
 * This helper deliberately has no Compose or Android dependency. A UI layer can
 * feed it scroll/content events and use [ScrollFollowTransition.shouldFollow]
 * to decide whether to issue a scroll-to-bottom request.
 */
enum class ScrollFollowState {
    FOLLOWING,
    PAUSED_BY_USER,
}

/** Explicit actions that intentionally return a conversation to the bottom. */
enum class ScrollFollowSource {
    FAB,
    SEND,
    RESUME,
    RETRY,
}

/** Events observed by [ScrollFollowStateMachine]. */
sealed interface ScrollFollowEvent {
    /** Establish the initial follow policy for a newly opened conversation. */
    data object Initial : ScrollFollowEvent

    /** New messages/content were appended or streaming content grew. */
    data object ContentChanged : ScrollFollowEvent

    /** A layout pass changed the measured content/viewport geometry. */
    data object LayoutChanged : ScrollFollowEvent

    /** The user's drag ended; only a real bottom position resumes following. */
    data class UserDragStopped(val atBottom: Boolean) : ScrollFollowEvent

    /** The user's drag was cancelled before it established a new position. */
    data object UserDragCancelled : ScrollFollowEvent

    /** A user intentionally navigated away from the live bottom (top/turn jump). */
    data object ExplicitPause : ScrollFollowEvent

    /** A user-visible action explicitly requests following again. */
    data class ExplicitFollow(val source: ScrollFollowSource) : ScrollFollowEvent

    /** The collapsed conversation was reopened and should re-anchor at the end. */
    data object CollapseReopen : ScrollFollowEvent
}

/**
 * Immutable result of one event dispatch.
 *
 * [shouldFollow] is intentionally event-sensitive: content/layout changes while
 * paused must not trigger a scroll, while an explicit follow action may request
 * one even when the state was already [ScrollFollowState.FOLLOWING].
 */
data class ScrollFollowTransition(
    val event: ScrollFollowEvent,
    val previousState: ScrollFollowState,
    val currentState: ScrollFollowState,
    val shouldFollow: Boolean,
) {
    val stateChanged: Boolean
        get() = previousState != currentState
}

/**
 * Small, instance-scoped state machine for long-content chat scrolling.
 *
 * There is no process-global state: each conversation/screen owns its own
 * instance, so pausing one conversation cannot pause another one.
 */
class ScrollFollowStateMachine(
    initialState: ScrollFollowState = ScrollFollowState.FOLLOWING,
) {
    var state: ScrollFollowState = initialState
        private set

    fun dispatch(event: ScrollFollowEvent): ScrollFollowTransition {
        val previous = state
        state = nextState(event, previous)
        return ScrollFollowTransition(
            event = event,
            previousState = previous,
            currentState = state,
            shouldFollow = shouldFollow(event, state),
        )
    }

    private fun nextState(
        event: ScrollFollowEvent,
        current: ScrollFollowState,
    ): ScrollFollowState = when (event) {
        ScrollFollowEvent.Initial,
        is ScrollFollowEvent.ExplicitFollow,
        ScrollFollowEvent.CollapseReopen,
        -> ScrollFollowState.FOLLOWING

        ScrollFollowEvent.ExplicitPause -> ScrollFollowState.PAUSED_BY_USER

        ScrollFollowEvent.ContentChanged,
        ScrollFollowEvent.LayoutChanged,
        ScrollFollowEvent.UserDragCancelled,
        -> current

        is ScrollFollowEvent.UserDragStopped ->
            if (event.atBottom) ScrollFollowState.FOLLOWING
            else ScrollFollowState.PAUSED_BY_USER
    }

    private fun shouldFollow(
        event: ScrollFollowEvent,
        resultingState: ScrollFollowState,
    ): Boolean = when (event) {
        ScrollFollowEvent.Initial,
        ScrollFollowEvent.ContentChanged,
        ScrollFollowEvent.LayoutChanged,
        -> resultingState == ScrollFollowState.FOLLOWING

        is ScrollFollowEvent.UserDragStopped ->
            event.atBottom && resultingState == ScrollFollowState.FOLLOWING

        ScrollFollowEvent.UserDragCancelled -> false
        ScrollFollowEvent.ExplicitPause -> false
        is ScrollFollowEvent.ExplicitFollow,
        ScrollFollowEvent.CollapseReopen,
        -> true
    }
}
