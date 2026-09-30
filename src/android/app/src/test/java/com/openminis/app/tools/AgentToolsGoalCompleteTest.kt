package com.openminis.app.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolsGoalCompleteTest {
    @Test
    fun `goal complete is exposed only while a root goal is active`() {
        assertFalse(AgentTools.makeAgentTools(goalActive = false).any { it.name == "goal_complete" })
        val definition = AgentTools.makeAgentTools(goalActive = true).firstOrNull { it.name == "goal_complete" }
        assertNotNull(definition)
        assertTrue(definition!!.required.containsAll(listOf("tool_title", "summary")))
    }

    @Test
    fun `communication detail requires an explicit record id`() {
        val definition = AgentTools.makeAgentTools().firstOrNull { it.name == "communication_detail" }
        assertNotNull(definition)
        assertTrue(definition!!.required == listOf("tool_title", "record_id"))
        assertFalse(definition.parameters.containsKey("payload"))
        assertFalse(definition.parameters.containsKey("transcript"))
    }

    @Test
    fun `supervision tool is always exposed with current-session scope`() {
        val definition = AgentTools.makeAgentTools().firstOrNull { it.name == "supervise_descendants" }
        assertNotNull(definition)
        assertTrue(definition!!.required == listOf("tool_title"))
        assertFalse(definition.parameters.containsKey("actor_session_id"))
    }
}
