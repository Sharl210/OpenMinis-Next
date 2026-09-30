package com.openminis.app.feature.runtime

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.tools.AgentTools
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-child-agent-completion] Guards for the delegated-child completion
 * protocol: the birth injection, the per-message re-injection, and the rule
 * that ONLY a completion-tool call counts as a natural end.
 */
class ChildAgentLoopTest {

    private fun turn(
        text: String = "",
        calls: List<ChildToolCall> = emptyList(),
        usage: LLMUsage? = null,
    ) = ChildTurnResult(text = text, toolCalls = calls, usage = usage)

    private fun call(name: String, args: JSONObject = JSONObject(), id: String = "c1") =
        ChildToolCall(id = id, name = name, args = args)

    // ─── 出生注入 ────────────────────────────────────────────────────────

    @Test
    fun `child tool list contains the completion tool`() {
        val names = AgentTools.makeChildAgentTools().map { it.name }

        assertTrue(
            "child tool list must contain ${ChildCompletionProtocol.TOOL_NAME}, was $names",
            names.contains(ChildCompletionProtocol.TOOL_NAME),
        )
        // First, so the model reads it as the highest-priority affordance.
        assertEquals(ChildCompletionProtocol.TOOL_NAME, names.first())
    }

    @Test
    fun `birth instruction names the completion tool and advertises the real child tools`() {
        val instruction = ChildCompletionProtocol.systemInstruction(null)
        val advertised = AgentTools.makeChildAgentTools().map { it.name }

        assertTrue(instruction.contains("delegated child"))
        assertTrue(
            "birth instruction must name ${ChildCompletionProtocol.TOOL_NAME}",
            instruction.contains(ChildCompletionProtocol.TOOL_NAME),
        )
        // The advertised list must be the list actually handed to the provider.
        // If a definition is dropped from makeChildAgentTools, this goes red —
        // a prompt that advertises a tool the child does not have is a lie.
        assertTrue(
            "birth instruction must advertise exactly $advertised",
            instruction.contains(advertised.joinToString(", ")),
        )
    }

    @Test
    fun `birth instruction only names a completion tool the child actually has`() {
        val hasCompletionTool = AgentTools.makeChildAgentTools()
            .any { it.name == ChildCompletionProtocol.TOOL_NAME }
        val instruction = ChildCompletionProtocol.systemInstruction(null)

        assertEquals(
            "the birth instruction must mention the completion tool if and only if the child " +
                "is actually given it — a prompt that orders a call the child cannot make is a lie",
            hasCompletionTool,
            instruction.contains("`${ChildCompletionProtocol.TOOL_NAME}`"),
        )
    }

    @Test
    fun `every advertised child tool is executable by the shared executor or the protocol`() {
        // Structural invariant: the child is never offered a tool that has no
        // implementation behind it. Two legitimate owners — the shared
        // AgentToolExecutor, and the completion protocol itself.
        AgentTools.makeChildAgentTools().forEach { definition ->
            val owned = definition.name == ChildCompletionProtocol.TOOL_NAME ||
                com.openminis.app.tools.AgentToolExecutor.PORTABLE_TOOL_NAMES.contains(definition.name)
            assertTrue(
                "child tool '${definition.name}' has no executor",
                owned,
            )
        }
    }

    @Test
    fun `parent messages re-inject the protocol and team peer messages do not`() {
        assertNotNull(ChildCompletionProtocol.reminderFor(RuntimeDelivery.QUEUE, null))
        assertNotNull(ChildCompletionProtocol.reminderFor(RuntimeDelivery.STEER, null))
        assertNotNull(ChildCompletionProtocol.reminderFor(RuntimeDelivery.NOTIFY, null))
        assertNull(ChildCompletionProtocol.reminderFor(RuntimeDelivery.TEAM_PEER, null))

        assertTrue(ChildCompletionProtocol.reminderFor(RuntimeDelivery.QUEUE, null)!!.contains(
            ChildCompletionProtocol.TOOL_NAME,
        ))
    }

    // ─── 自然结束判定 ────────────────────────────────────────────────────

    @Test
    fun `calling the completion tool marks a natural end`() = runBlocking {
        val args = JSONObject().put("tool_title", "Done").put("summary", "Delegated work finished")
        var executorCalls = 0
        val loop = ChildAgentLoop(executeTool = { executorCalls++ ; ChildToolOutcome("unused") })

        val outcome = loop.run(seed = emptyList()) {
            turn(
                text = "I inspected the tree.",
                calls = listOf(call(ChildCompletionProtocol.TOOL_NAME, args)),
                usage = LLMUsage(inputTokens = 10, outputTokens = 5, latestContextTokens = 15),
            )
        }

        assertTrue(outcome.completedNaturally)
        assertEquals(ChildEndReason.COMPLETION_TOOL, outcome.endReason)
        assertEquals("Delegated work finished", outcome.completionSummary)
        assertEquals("Done", outcome.completionTitle)
        assertEquals(1, outcome.turns)
        assertEquals(10, outcome.usage?.inputTokens)
        // The completion call is a protocol signal, not a side effect: the tool
        // executor must not be asked to run it.
        assertEquals(0, executorCalls)
    }

    @Test
    fun `a reply with no tool call is not a natural end`() = runBlocking {
        val loop = ChildAgentLoop(executeTool = { ChildToolOutcome("unused") })

        val outcome = loop.run(seed = emptyList()) { turn(text = "Here is the answer.") }

        assertFalse(outcome.completedNaturally)
        assertEquals(ChildEndReason.NO_TOOL_CALL, outcome.endReason)
        assertEquals("Here is the answer.", outcome.text)
        assertNull(outcome.completionSummary)
    }

    @Test
    fun `running out of turns while still calling tools is not a natural end`() = runBlocking {
        val loop = ChildAgentLoop(maxTurns = 3, executeTool = { ChildToolOutcome("ok") })

        val outcome = loop.run(seed = emptyList()) {
            turn(text = "still working", calls = listOf(call("web_search")))
        }

        assertFalse(outcome.completedNaturally)
        assertEquals(ChildEndReason.TURN_BUDGET_EXHAUSTED, outcome.endReason)
        assertEquals(3, outcome.turns)
    }

    @Test
    fun `completion wins over sibling tool calls in the same turn`() = runBlocking {
        var executorCalls = 0
        val loop = ChildAgentLoop(executeTool = { executorCalls++; ChildToolOutcome("ok") })

        val outcome = loop.run(seed = emptyList()) {
            turn(
                calls = listOf(
                    call("web_search", id = "a"),
                    call(ChildCompletionProtocol.TOOL_NAME, id = "b"),
                ),
            )
        }

        assertTrue(outcome.completedNaturally)
        assertEquals(0, executorCalls)
    }

    // ─── 工具循环 ────────────────────────────────────────────────────────

    @Test
    fun `tool results are fed back and the child continues until it completes`() = runBlocking {
        val seenToolResults = mutableListOf<String>()
        val executed = mutableListOf<String>()
        val loop = ChildAgentLoop(
            executeTool = { call ->
                executed.add(call.name)
                ChildToolOutcome("result-of-${call.name}")
            },
        )

        val outcome = loop.run(seed = listOf(LLMMessage(role = LLMMessage.Role.USER, content = "do it"))) { messages ->
            messages.lastOrNull()
                ?.contentParts
                ?.filterIsInstance<AgentContentPart.ToolResult>()
                ?.forEach { seenToolResults.add(it.content) }

            if (executed.isEmpty()) {
                turn(text = "searching", calls = listOf(call("web_search", JSONObject().put("query", "x"))))
            } else {
                turn(calls = listOf(call(ChildCompletionProtocol.TOOL_NAME)))
            }
        }

        assertEquals(listOf("web_search"), executed)
        assertEquals(listOf("result-of-web_search"), seenToolResults)
        assertTrue(outcome.completedNaturally)
        assertEquals(2, outcome.turns)
        assertEquals("searching", outcome.text)
    }

    @Test
    fun `an unavailable tool comes back as an error that still names the real tool list`() =
        runBlocking {
            var fedBack: String? = null
            var isError = false
            val loop = ChildAgentLoop(
                executeTool = { ChildToolOutcome(ChildAgentLoop.unavailableToolMessage(it.name), true) },
            )

            loop.run(seed = emptyList()) { messages ->
                messages.lastOrNull()
                    ?.contentParts
                    ?.filterIsInstance<AgentContentPart.ToolResult>()
                    ?.forEach { fedBack = it.content; isError = it.isError }

                if (fedBack == null) {
                    turn(calls = listOf(call("shell_execute")))
                } else {
                    turn(calls = listOf(call(ChildCompletionProtocol.TOOL_NAME)))
                }
            }

            assertTrue(isError)
            assertNotNull(fedBack)
            assertTrue(fedBack!!.contains("shell_execute"))
            assertTrue(fedBack!!.contains(ChildCompletionProtocol.TOOL_NAME))
        }

    @Test
    fun `only the completion end reason is a natural end`() {
        fun outcome(reason: ChildEndReason) = ChildLoopOutcome(
            turns = 1,
            text = "",
            usage = null,
            endReason = reason,
        )

        assertTrue(outcome(ChildEndReason.COMPLETION_TOOL).completedNaturally)
        assertFalse(outcome(ChildEndReason.NO_TOOL_CALL).completedNaturally)
        assertFalse(outcome(ChildEndReason.TURN_BUDGET_EXHAUSTED).completedNaturally)
    }

    @Test
    fun `runtime result derives the natural-end flag from the end reason`() {
        val base = RuntimeModelSnapshot(provider = "p", model = "m")
        val attribution = com.openminis.app.data.model.ModelAttributionSnapshot(
            modelId = "m",
            displayName = "M",
            providerTypeRaw = "p",
            providerInstanceId = "i",
        )
        fun result(reason: ChildEndReason) = RuntimeChildExecutionResult(
            childSessionId = "child",
            model = base,
            modelSnapshot = attribution,
            output = "out",
            endReason = reason,
        )

        assertTrue(result(ChildEndReason.COMPLETION_TOOL).completedNaturally)
        assertFalse(result(ChildEndReason.NO_TOOL_CALL).completedNaturally)
        assertFalse(result(ChildEndReason.TURN_BUDGET_EXHAUSTED).completedNaturally)
    }
}
