package com.openminis.app.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolsDeleteSubtreeTest {
    @Test
    fun `delete subtree schema is registered without actor forgery fields`() {
        val definition = AgentTools.makeAgentTools().firstOrNull { it.name == "delete_subtree" }
        assertNotNull(definition)
        val tool = definition!!
        assertTrue(tool.required.contains("tool_title"))
        assertTrue(tool.required.contains("target_session_id"))
        assertTrue(tool.parameters.containsKey("target_session_id"))
        assertFalse(tool.parameters.containsKey("initiator_session_id"))
        assertFalse(tool.parameters.containsKey("executor_session_id"))
        assertFalse(tool.parameters.containsKey("initiator"))
        assertFalse(tool.parameters.containsKey("executor"))
    }
}
