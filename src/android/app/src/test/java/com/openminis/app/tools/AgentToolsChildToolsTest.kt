package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-child-agent-completion] Structural guards on the DELEGATED CHILD
 * tool surface: which tools a child is born with, and that the main agent is
 * never handed the child-only completion tool.
 */
class AgentToolsChildToolsTest {

    @Test
    fun `child surface is the completion tool plus the portable executor tools`() {
        val names = AgentTools.makeChildAgentTools().map { it.name }

        assertEquals(
            "child surface must be exactly {completion} ∪ {portable executor tools}",
            setOf(AgentTools.SUBAGENT_COMPLETE_TOOL_NAME) + AgentToolExecutor.PORTABLE_TOOL_NAMES,
            names.toSet(),
        )
        // No duplicates: a repeated definition would be sent twice on the wire.
        assertEquals(names.size, names.toSet().size)
        assertEquals(
            "the completion tool must be first so the model reads it as the priority affordance",
            AgentTools.SUBAGENT_COMPLETE_TOOL_NAME,
            names.first(),
        )
    }

    @Test
    fun `completion tool requires a title and a summary`() {
        val definition = AgentTools.makeChildAgentTools()
            .firstOrNull { it.name == AgentTools.SUBAGENT_COMPLETE_TOOL_NAME }

        requireNotNull(definition)
        assertEquals(listOf("tool_title", "summary"), definition.required)
        assertTrue(definition.description.contains("FINISHED"))
    }

    @Test
    fun `child surface excludes tools that need chat-ui scope or the root goal`() {
        val names = AgentTools.makeChildAgentTools().map { it.name }.toSet()

        // Each of these is executable ONLY through ChatViewModel's UI-owned
        // state (tool blocks, browser pool, offload shell bridge, session bind
        // mounts, memory repository, root-goal runtime) — offering one to a
        // child would be offering a stub.
        listOf(
            "shell_execute",
            "browser_use",
            "browser_devtools",
            "file_read",
            "file_write",
            "file_edit",
            "read_image",
            "memory_write",
            "memory_get",
            "stop_descendant",
            "communication_query",
            "communication_detail",
            "delete_subtree",
            "goal_complete",
        ).forEach { name ->
            assertFalse("child surface must not contain '$name'", names.contains(name))
        }
    }

    @Test
    fun `main agent never receives the child completion tool`() {
        listOf(false, true).forEach { image ->
            listOf(false, true).forEach { vision ->
                listOf(false, true).forEach { memory ->
                    listOf(false, true).forEach { goal ->
                        val names = AgentTools.makeAgentTools(
                            supportsImageInput = image,
                            visionGroupConfigured = vision,
                            memoryEnabled = memory,
                            goalActive = goal,
                        ).map { it.name }
                        assertFalse(
                            "main agent tool list must never contain " +
                                "${AgentTools.SUBAGENT_COMPLETE_TOOL_NAME} " +
                                "(image=$image vision=$vision memory=$memory goal=$goal)",
                            names.contains(AgentTools.SUBAGENT_COMPLETE_TOOL_NAME),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `goal complete is main-agent-only and gated on an active root goal`() {
        assertFalse(AgentTools.makeAgentTools(goalActive = false).any { it.name == "goal_complete" })
        assertTrue(AgentTools.makeAgentTools(goalActive = true).any { it.name == "goal_complete" })
        assertFalse(AgentTools.makeChildAgentTools().any { it.name == "goal_complete" })
    }
}
