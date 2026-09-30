package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-child-restart] Schema guards for `restart_descendant`.
 *
 * The tool schema is documentation for the model, NOT an authorization boundary:
 * the model can put any field it likes in the argument JSON, so the actor is
 * always the session id the application bound, never something read out of the
 * arguments. The way that stays true is that no identity field is offered in the
 * first place — the same discipline `supervise_descendants` and `delete_subtree`
 * already follow. A declared `actor_session_id` would not create a
 * vulnerability by itself (the executor ignores it), but it would advertise a
 * capability the tool does not have and invite the model to believe it can act
 * as someone else.
 */
class AgentToolsRestartDescendantTest {

    private fun definition() = AgentTools.makeAgentTools(restartAvailable = true)
        .firstOrNull { it.name == AgentTools.RESTART_DESCENDANT_TOOL_NAME }

    /**
     * The tool must be ABSENT when nothing can execute it.
     *
     * Re-arming the runtime node without a launch path restarts nothing, so
     * exposing the tool would offer a stub — and a stub costs the model a whole
     * turn discovering that. `goal_complete` (`goalActive`) and the memory tools
     * (`memoryEnabled`) are gated the same way, and the reasoning is stated in
     * `makeChildAgentTools`'s KDoc: "a stub is worse than absence".
     */
    @Test
    fun `restart tool is absent while no launch path exists`() {
        val defaultNames = AgentTools.makeAgentTools().map { it.name }

        assertFalse(
            "with no way to re-run the child, offering the tool only burns a turn",
            defaultNames.contains(AgentTools.RESTART_DESCENDANT_TOOL_NAME),
        )
        // ...and its absence must not disturb the tools around it.
        assertTrue(defaultNames.contains("supervise_descendants"))
        assertTrue(defaultNames.contains("stop_descendant"))
    }

    @Test
    fun `restart tool is registered on the main agent surface when a launch path exists`() {
        val tool = definition()
        assertNotNull("the main agent must be able to restart an interrupted descendant", tool)
        assertEquals(
            AgentToolExecutor.RESTART_DESCENDANT,
            AgentTools.RESTART_DESCENDANT_TOOL_NAME,
        )
    }

    @Test
    fun `restart tool declares a target but never an actor identity`() {
        val tool = requireNotNull(definition())

        assertTrue(tool.required.contains("tool_title"))
        assertTrue("the model must be able to name WHICH descendant to restart", tool.required.contains("child_session_id"))
        assertTrue(tool.parameters.containsKey("child_session_id"))

        listOf(
            "actor_session_id",
            "actor",
            "initiator_session_id",
            "initiator",
            "executor_session_id",
            "executor",
            "session_id",
        ).forEach { forbidden ->
            assertFalse(
                "restart_descendant must not offer '$forbidden': the acting session is bound by " +
                    "the application and can never be supplied by the caller",
                tool.parameters.containsKey(forbidden),
            )
        }
    }

    @Test
    fun `restart tool is main-agent-only and never handed to a delegated child`() {
        val childNames = AgentTools.makeChildAgentTools().map { it.name }

        assertFalse(
            "a delegated child has no subtree of its own to recover, and re-arming siblings " +
                "would let delegated work widen its own authority",
            childNames.contains(AgentTools.RESTART_DESCENDANT_TOOL_NAME),
        )
        assertTrue(
            "it is deliberately absent from the portable executor set, which IS handed to children",
            !AgentToolExecutor.PORTABLE_TOOL_NAMES.contains(AgentTools.RESTART_DESCENDANT_TOOL_NAME),
        )
        assertTrue(
            "the executor still owns the name, so ChatViewModel's shared dispatch reaches it",
            AgentToolExecutor.MAIN_AGENT_ONLY_TOOL_NAMES.contains(AgentTools.RESTART_DESCENDANT_TOOL_NAME),
        )
    }
}
