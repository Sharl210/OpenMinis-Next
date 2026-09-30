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

    @Test
    fun `an arrival at the end resumes following`() {
        val machine = ScrollFollowStateMachine()
        machine.dispatch(ScrollFollowEvent.UserDragStopped(atBottom = false))

        val transition = machine.dispatch(ScrollFollowEvent.AtBottomReached)

        assertEquals(ScrollFollowState.FOLLOWING, transition.currentState)
        assertEquals(ScrollFollowState.FOLLOWING, machine.state)
        assertTrue(transition.shouldFollow)
    }

    /**
     * 需求「只要触过一次底，它就又继续跟随更新」的判据是**位置**，不是手势停止那一刻的
     * 采样：手指离开屏幕之后 fling 才把视图送到末尾（用户拿它当中途反悔后停住），
     * 此时若只有手势路径能恢复，跟随就永远回不来。
     */
    @Test
    fun `one arrival at the end re-arms follow for every later growth`() {
        val machine = ScrollFollowStateMachine()
        machine.dispatch(ScrollFollowEvent.UserDragStopped(atBottom = false))

        // 还没到达末尾：内容继续增长也必须停住（需求 b）。
        val growthWhileAway = machine.dispatch(ScrollFollowEvent.ContentChanged)
        assertEquals(ScrollFollowState.PAUSED_BY_USER, growthWhileAway.currentState)
        assertFalse(growthWhileAway.shouldFollow)

        // 位置到达末尾：跟随回来（需求 c）。
        machine.dispatch(ScrollFollowEvent.AtBottomReached)

        // 之后每次增长都要重新锚到末尾（需求 a）。
        val firstGrowth = machine.dispatch(ScrollFollowEvent.ContentChanged)
        val secondGrowth = machine.dispatch(ScrollFollowEvent.LayoutChanged)
        assertEquals(ScrollFollowState.FOLLOWING, firstGrowth.currentState)
        assertTrue(firstGrowth.shouldFollow)
        assertTrue(secondGrowth.shouldFollow)
    }

    @Test
    fun `an arrival at the end never arms a pause`() {
        val machine = ScrollFollowStateMachine()

        val transition = machine.dispatch(ScrollFollowEvent.AtBottomReached)

        assertEquals(ScrollFollowState.FOLLOWING, transition.currentState)
        assertFalse(transition.stateChanged)
        assertTrue(transition.shouldFollow)
    }
}
