package com.openminis.app.feature.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForkGoalRuntimeTest {
    @Test
    fun `parser accepts fork and goal with full width slash`() {
        assertTrue(ForkGoalCommandParser.isCommand("/fork"))
        assertTrue(ForkGoalCommandParser.isCommand("／goal finish the release"))
        assertFalse(ForkGoalCommandParser.isCommand("/forked child"))
        assertFalse(ForkGoalCommandParser.isCommand("/goal"))

        assertEquals(
            ForkGoalCommand.Fork(null),
            ForkGoalCommandParser.parse(" /fork "),
        )
        assertEquals(
            ForkGoalCommand.Goal("finish the release"),
            ForkGoalCommandParser.parse("／goal finish the release"),
        )
        assertNull(ForkGoalCommandParser.parse("/goal   "))
    }

    @Test
    fun `any node can fork and default names increment from two`() {
        val child = ForkGoalNode(
            nodeId = "child-1",
            sessionName = "Main-2",
            parentNodeId = "root",
            rootNodeId = "root",
        )
        val runtime = ForkGoalRuntime(child)

        val decision = runtime.fork(
            existingSiblingNames = listOf("Main-2-2", "Main-2-3", "unrelated"),
        )
        assertTrue(decision.accepted)
        assertEquals("Main-2-4", decision.childSessionName)

        val explicit = runtime.fork(ForkGoalCommand.Fork("review"))
        assertTrue(explicit.accepted)
        assertEquals("review", explicit.childSessionName)
    }

    @Test
    fun `only root primary agent can start a goal`() {
        val root = ForkGoalRuntime(ForkGoalNode("root", "Main"))
        val child = ForkGoalRuntime(
            ForkGoalNode(
                nodeId = "child",
                sessionName = "Main-2",
                parentNodeId = "root",
                rootNodeId = "root",
            ),
        )

        assertTrue(root.startGoal(GoalRequest("ship it"), nowMillis = 10L).accepted)
        val denied = child.startGoal(GoalRequest("nested goal"), nowMillis = 10L)
        assertFalse(denied.accepted)
        assertEquals(GoalStatus.IDLE, child.snapshot.status)
    }

    @Test
    fun `normal end tool completes goal and ignores ordinary tools`() {
        val runtime = ForkGoalRuntime(ForkGoalNode("root", "Main"))
        runtime.startGoal(GoalRequest("ship it"), nowMillis = 1L)

        val ordinary = runtime.onToolCall("shell_execute", nowMillis = 2L)
        assertTrue(ordinary.accepted)
        assertEquals(GoalStatus.ACTIVE, runtime.snapshot.status)

        val completed = runtime.onToolCall(ForkGoalRuntime.NORMAL_END_TOOL_NAME, nowMillis = 3L)
        assertTrue(completed.accepted)
        assertEquals(GoalStatus.COMPLETED, runtime.snapshot.status)
        assertNull(runtime.snapshot.continuationPrompt)
        assertNull(runtime.pollContinuationPrompt())
    }

    @Test
    fun `abnormal stop creates one continuation prompt and resume reactivates goal`() {
        val runtime = ForkGoalRuntime(ForkGoalNode("root", "Main"))
        runtime.startGoal(GoalRequest("finish the migration"), nowMillis = 1L)

        val stopped = runtime.onStopped(GoalStopReason.LEASE_EXPIRED, nowMillis = 2L)
        assertTrue(stopped.accepted)
        assertEquals(GoalStatus.NEEDS_CONTINUATION, runtime.snapshot.status)
        assertEquals(1, runtime.snapshot.continuationCount)

        val prompt = runtime.pollContinuationPrompt(nowMillis = 3L)
        assertNotNull(prompt)
        assertTrue(prompt!!.contains("finish the migration"))
        assertTrue(prompt.contains("lease_expired"))
        assertNull(runtime.pollContinuationPrompt(nowMillis = 4L))

        val duplicateStop = runtime.onStopped(GoalStopReason.ABNORMAL, nowMillis = 5L)
        assertFalse(duplicateStop.accepted)
        assertEquals(1, runtime.snapshot.continuationCount)

        assertTrue(runtime.resume(nowMillis = 6L).accepted)
        assertEquals(GoalStatus.ACTIVE, runtime.snapshot.status)
    }
}
