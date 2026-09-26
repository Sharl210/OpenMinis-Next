package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollFollowStateMachineTest {
    @Test
    fun `initial starts following and requests an end anchor`() {
        val machine = ScrollFollowStateMachine()

        val transition = machine.dispatch(ScrollFollowEvent.Initial)

        assertEquals(ScrollFollowState.FOLLOWING, machine.state)
        assertEquals(ScrollFollowState.FOLLOWING, transition.currentState)
        assertTrue(transition.shouldFollow)
    }

    @Test
    fun `content and layout changes keep following when user has not left bottom`() {
        val machine = ScrollFollowStateMachine()

        val content = machine.dispatch(ScrollFollowEvent.ContentChanged)
        val layout = machine.dispatch(ScrollFollowEvent.LayoutChanged)

        assertEquals(ScrollFollowState.FOLLOWING, content.currentState)
        assertTrue(content.shouldFollow)
        assertEquals(ScrollFollowState.FOLLOWING, layout.currentState)
        assertTrue(layout.shouldFollow)
    }

    @Test
    fun `content and layout changes do not steal the scroll after user pauses`() {
        val machine = ScrollFollowStateMachine()
        machine.dispatch(ScrollFollowEvent.UserDragStopped(atBottom = false))

        val content = machine.dispatch(ScrollFollowEvent.ContentChanged)
        val layout = machine.dispatch(ScrollFollowEvent.LayoutChanged)

        assertEquals(ScrollFollowState.PAUSED_BY_USER, content.currentState)
        assertFalse(content.shouldFollow)
        assertEquals(ScrollFollowState.PAUSED_BY_USER, layout.currentState)
        assertFalse(layout.shouldFollow)
    }

    @Test
    fun `dragging away from bottom pauses and dragging back to bottom resumes`() {
        val machine = ScrollFollowStateMachine()

        val away = machine.dispatch(ScrollFollowEvent.UserDragStopped(atBottom = false))
        val back = machine.dispatch(ScrollFollowEvent.UserDragStopped(atBottom = true))

        assertEquals(ScrollFollowState.PAUSED_BY_USER, away.currentState)
        assertFalse(away.shouldFollow)
        assertEquals(ScrollFollowState.FOLLOWING, back.currentState)
        assertTrue(back.shouldFollow)
    }

    @Test
    fun `cancelled drag does not change state or request a follow`() {
        val following = ScrollFollowStateMachine()
        val followingCancel = following.dispatch(ScrollFollowEvent.UserDragCancelled)

        val paused = ScrollFollowStateMachine()
        paused.dispatch(ScrollFollowEvent.UserDragStopped(atBottom = false))
        val pausedCancel = paused.dispatch(ScrollFollowEvent.UserDragCancelled)

        assertEquals(ScrollFollowState.FOLLOWING, followingCancel.currentState)
        assertFalse(followingCancel.stateChanged)
        assertFalse(followingCancel.shouldFollow)
        assertEquals(ScrollFollowState.PAUSED_BY_USER, pausedCancel.currentState)
        assertFalse(pausedCancel.stateChanged)
        assertFalse(pausedCancel.shouldFollow)
    }

    @Test
    fun `explicit follow sources resume a paused conversation`() {
        ScrollFollowSource.entries.forEach { source ->
            val machine = ScrollFollowStateMachine()
            machine.dispatch(ScrollFollowEvent.UserDragStopped(atBottom = false))

            val transition = machine.dispatch(ScrollFollowEvent.ExplicitFollow(source))

            assertEquals(source, (transition.event as ScrollFollowEvent.ExplicitFollow).source)
            assertEquals(ScrollFollowState.FOLLOWING, transition.currentState)
            assertTrue(transition.shouldFollow)
        }
    }

    @Test
    fun `explicit history navigation pauses follow until an explicit return`() {
        val machine = ScrollFollowStateMachine()

        val pause = machine.dispatch(ScrollFollowEvent.ExplicitPause)
        val growth = machine.dispatch(ScrollFollowEvent.ContentChanged)
        val resume = machine.dispatch(ScrollFollowEvent.ExplicitFollow(ScrollFollowSource.FAB))

        assertEquals(ScrollFollowState.PAUSED_BY_USER, pause.currentState)
        assertFalse(pause.shouldFollow)
        assertEquals(ScrollFollowState.PAUSED_BY_USER, growth.currentState)
        assertFalse(growth.shouldFollow)
        assertEquals(ScrollFollowState.FOLLOWING, resume.currentState)
        assertTrue(resume.shouldFollow)
    }
    @Test
    fun `collapse and reopen returns to the end`() {
        val machine = ScrollFollowStateMachine()
        machine.dispatch(ScrollFollowEvent.UserDragStopped(atBottom = false))

        val transition = machine.dispatch(ScrollFollowEvent.CollapseReopen)

        assertEquals(ScrollFollowState.FOLLOWING, transition.currentState)
        assertTrue(transition.shouldFollow)
    }

    @Test
    fun `instances do not share paused state`() {
        val paused = ScrollFollowStateMachine()
        val following = ScrollFollowStateMachine()
        paused.dispatch(ScrollFollowEvent.UserDragStopped(atBottom = false))

        following.dispatch(ScrollFollowEvent.ContentChanged)

        assertEquals(ScrollFollowState.PAUSED_BY_USER, paused.state)
        assertEquals(ScrollFollowState.FOLLOWING, following.state)
    }
}
