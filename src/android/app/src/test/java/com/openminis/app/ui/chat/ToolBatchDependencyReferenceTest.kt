package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * [T-tool-call-depends-on-reference] Guards the hop that made the blocking
 * option actually usable, not merely visible.
 *
 * `depends_on` used to be documented as "the tool call id you assigned to that
 * earlier call". That is only true on Anthropic and OpenAI Chat Completions,
 * where the id the model wrote is the id that comes back. On the other two the
 * client invents it:
 *
 *  - Gemini: `GeminiProvider.kt:175` — `val toolId = "gemini_${System.nanoTime()}"`
 *  - OpenAI Responses: `OpenAIProvider.kt:3325` — `"$callId|$fcId"`
 *
 * The model cannot predict either value, so an id-only option was unusable there
 * — and worse than absent, because the model would set `depends_on`, believe the
 * ordering was guaranteed, and get `Unknown dependency tool call id` instead.
 * A batch POSITION is always constructible: the model knows the order in which
 * it emitted the calls, and every backend feeds this scheduler in that order.
 */
class ToolBatchDependencyReferenceTest {

    @Test
    fun `a call waits for the nth call of the batch by position, and unrelated calls still overlap`() = runBlocking {
        val timeline = mutableListOf<String>()
        val independentInFlight = AtomicInteger()
        val bothIndependentStarted = CompletableDeferred<Unit>()
        val outcomes = withTimeout(5_000) {
            ToolCallBatchScheduler.execute(
                listOf(
                    ToolBatchItem("gemini_881", value = "a"),
                    ToolBatchItem("gemini_882", dependsOn = setOf("1"), value = "b"),
                    ToolBatchItem("gemini_883", value = "c"),
                ),
            ) { value ->
                timeline += "start:$value"
                if (value == "b") {
                    // The whole point of the feature: b must not begin before a ends.
                    assertTrue("positional prerequisite must have finished: $timeline", "end:a" in timeline)
                } else {
                    // Both non-dependent calls must be in flight together: a batch that
                    // serialized them could never get past this barrier.
                    if (independentInFlight.incrementAndGet() == 2) bothIndependentStarted.complete(Unit)
                    bothIndependentStarted.await()
                }
                timeline += "end:$value"
                ToolBatchExecution(success = true, value = value)
            }
        }

        assertEquals(listOf("gemini_881", "gemini_882", "gemini_883"), outcomes.map { it.item.id })
        assertEquals(
            listOf(ToolBatchStatus.SUCCESS, ToolBatchStatus.SUCCESS, ToolBatchStatus.SUCCESS),
            outcomes.map { it.status },
        )
        assertTrue("unrelated call c must not wait behind b: $timeline", timeline.indexOf("start:b") > timeline.indexOf("end:c"))
        assertTrue(timeline.indexOf("start:b") > timeline.indexOf("end:a"))
    }

    /**
     * The basis of a position is "the nth call as the client handed the batch to
     * the scheduler" — the model's emission order. It is NOT sorted-id order and
     * NOT whatever numbering a provider happened to use. The batch below is
     * deliberately adversarial: its ids sort in the opposite order to their
     * positions.
     */
    @Test
    fun `a position means batch order, not a sorted or canonicalised order`() = runBlocking {
        val outcomes = ToolCallBatchScheduler.execute(
            listOf(
                ToolBatchItem("zzz_1st", value = "pos1"),
                ToolBatchItem("aaa_2nd", value = "pos2"),
                ToolBatchItem("2", dependsOn = setOf("1"), value = "pos3"),
            ),
        ) { value ->
            ToolBatchExecution(success = value != "pos1", value = value, error = "boom")
        }

        // "1" is not an id here, so it is read as position 1 -> the FIRST element,
        // which failed. Under a sorted-id reading the first call would be "2"
        // (itself), giving a cyclic FAILED instead of a BLOCKED dependent — the
        // two assertions below tell those apart.
        assertEquals(ToolBatchStatus.FAILED, outcomes[0].status)
        assertEquals(ToolBatchStatus.BLOCKED, outcomes[2].status)
        assertTrue(
            "block must name the call that failed: ${outcomes[2].error}",
            outcomes[2].error?.contains("zzz_1st") == true,
        )
    }

    /**
     * When a token is BOTH an existing call id and an in-range position that
     * points somewhere else, the id wins — otherwise a provider whose id looks
     * like a number would silently repoint the dependency.
     */
    @Test
    fun `an exact id wins over the same token read as a position`() = runBlocking {
        val timeline = mutableListOf<String>()
        val outcomes = ToolCallBatchScheduler.execute(
            listOf(
                ToolBatchItem("x", value = "unrelated"),
                ToolBatchItem("call_a", dependsOn = setOf("2"), value = "depends"),
                ToolBatchItem("2", value = "id-two"),
            ),
        ) { value ->
            timeline += "start:$value"
            if (value == "depends") {
                assertTrue(
                    "must wait for the call whose id is `2`, not for itself: $timeline",
                    "end:id-two" in timeline,
                )
            }
            timeline += "end:$value"
            ToolBatchExecution(success = true, value = value)
        }

        assertEquals(ToolBatchStatus.SUCCESS, outcomes[1].status)
        assertEquals(ToolBatchStatus.SUCCESS, outcomes[2].status)
        assertTrue(timeline.indexOf("end:id-two") < timeline.indexOf("start:depends"))
    }

    /**
     * An invalid dependency must fail the call, never degrade into "no
     * dependency": silently dropping a requested ordering would turn it into an
     * unobservable race instead of an observable error.
     */
    @Test
    fun `an unresolvable reference fails the call instead of being ignored`() = runBlocking {
        val executed = mutableListOf<String>()
        val outcomes = ToolCallBatchScheduler.execute(
            listOf(
                ToolBatchItem("a", value = "a"),
                ToolBatchItem("too-high", dependsOn = setOf("99"), value = "too-high"),
                ToolBatchItem("zero", dependsOn = setOf("0"), value = "zero"),
                ToolBatchItem("negative", dependsOn = setOf("-1"), value = "negative"),
                ToolBatchItem("not-a-number", dependsOn = setOf("zzz"), value = "not-a-number"),
                ToolBatchItem("mixed", dependsOn = setOf("1", "zzz"), value = "mixed"),
                ToolBatchItem("free", value = "free"),
            ),
        ) { value ->
            executed += value
            ToolBatchExecution(success = true, value = value)
        }

        assertEquals("only the reachable calls run", listOf("a", "free"), executed)
        assertEquals(ToolBatchStatus.SUCCESS, outcomes[0].status)
        for (index in 1..5) {
            assertEquals("outcomes[$index] must be FAILED, not silently parallel", ToolBatchStatus.FAILED, outcomes[index].status)
        }
        assertEquals(ToolBatchStatus.SUCCESS, outcomes[6].status)
        // The error must quote the reference the model wrote, verbatim.
        assertTrue(outcomes[1].error?.contains("99") == true)
        assertTrue(outcomes[4].error?.contains("zzz") == true)
        assertTrue(outcomes[5].error?.contains("zzz") == true)
    }

    @Test
    fun `the last position of a batch is usable while the one past it is not`() = runBlocking {
        val timeline = mutableListOf<String>()
        val reachable = ToolCallBatchScheduler.execute(
            listOf(
                ToolBatchItem("x", dependsOn = setOf("3"), value = "x"),
                ToolBatchItem("y", value = "y"),
                ToolBatchItem("z", value = "z"),
            ),
        ) { value ->
            timeline += "start:$value"
            timeline += "end:$value"
            ToolBatchExecution(success = true, value = value)
        }
        assertEquals(
            listOf(ToolBatchStatus.SUCCESS, ToolBatchStatus.SUCCESS, ToolBatchStatus.SUCCESS),
            reachable.map { it.status },
        )
        assertTrue("x must wait for the batch's last call: $timeline", timeline.indexOf("start:x") > timeline.indexOf("end:z"))

        val executed = mutableListOf<String>()
        val past = ToolCallBatchScheduler.execute(
            listOf(
                ToolBatchItem("x", dependsOn = setOf("4"), value = "x"),
                ToolBatchItem("y", value = "y"),
                ToolBatchItem("z", value = "z"),
            ),
        ) { value ->
            executed += value
            ToolBatchExecution(success = true, value = value)
        }
        assertEquals(listOf("y", "z"), executed)
        assertEquals(ToolBatchStatus.FAILED, past[0].status)
    }

    @Test
    fun `a call that depends on its own position is rejected rather than deadlocked`() = runBlocking {
        val executed = mutableListOf<String>()
        val outcomes = ToolCallBatchScheduler.execute(
            listOf(
                ToolBatchItem("1", dependsOn = setOf("1"), value = "self-by-id-1"),
                ToolBatchItem("middle", dependsOn = setOf("2"), value = "self-by-position-2"),
                ToolBatchItem("tail", value = "tail"),
            ),
        ) { value ->
            executed += value
            ToolBatchExecution(success = true, value = value)
        }

        assertEquals(listOf("tail"), executed)
        assertEquals(ToolBatchStatus.FAILED, outcomes[0].status)
        assertEquals(ToolBatchStatus.FAILED, outcomes[1].status)
        assertEquals(ToolBatchStatus.SUCCESS, outcomes[2].status)
    }

    @Test
    fun `a positional dependency cycle is detected like an id one`() = runBlocking {
        val executed = mutableListOf<String>()
        val outcomes = ToolCallBatchScheduler.execute(
            listOf(
                ToolBatchItem("gemini_1", dependsOn = setOf("2"), value = "a"),
                ToolBatchItem("gemini_2", dependsOn = setOf("1"), value = "b"),
                ToolBatchItem("gemini_3", value = "free"),
            ),
        ) { value ->
            executed += value
            ToolBatchExecution(success = true, value = value)
        }

        assertEquals(listOf("free"), executed)
        assertEquals(ToolBatchStatus.FAILED, outcomes[0].status)
        assertEquals(ToolBatchStatus.FAILED, outcomes[1].status)
        assertEquals(ToolBatchStatus.SUCCESS, outcomes[2].status)
    }

    @Test
    fun `a failed positional prerequisite blocks only its dependent branch`() = runBlocking {
        val executed = mutableListOf<String>()
        val outcomes = ToolCallBatchScheduler.execute(
            listOf(
                ToolBatchItem("a", value = "bad"),
                ToolBatchItem("b", dependsOn = setOf("1"), value = "dependent"),
                ToolBatchItem("c", value = "free"),
            ),
        ) { value ->
            executed += value
            ToolBatchExecution(success = value != "bad", value = value, error = "failed")
        }
        assertEquals(listOf("bad", "free"), executed)
        assertEquals(ToolBatchStatus.FAILED, outcomes[0].status)
        assertEquals(ToolBatchStatus.BLOCKED, outcomes[1].status)
        assertEquals(ToolBatchStatus.SUCCESS, outcomes[2].status)
        assertFalse("dependent must not have run", executed.contains("dependent"))
    }

    @Test
    fun `an empty batch and a fully independent batch are unaffected`() = runBlocking {
        assertTrue(
            ToolCallBatchScheduler.execute(emptyList<ToolBatchItem<String>>()) {
                ToolBatchExecution(success = true, value = it)
            }.isEmpty(),
        )

        val outcomes = ToolCallBatchScheduler.execute(
            listOf(ToolBatchItem("a", value = "a"), ToolBatchItem("b", value = "b")),
        ) { ToolBatchExecution(success = true, value = it) }
        assertEquals(listOf(ToolBatchStatus.SUCCESS, ToolBatchStatus.SUCCESS), outcomes.map { it.status })
    }
}

/**
 * [T-tool-call-depends-on-reference] The other half of the same hop: what the
 * model is TOLD. Asserted on the generated schema fields (the strings the
 * provider converters actually emit), which is the data the model receives.
 *
 * `AgentToolDependsOnSchemaTest` already covers "the parameter is advertised on
 * every tool, and stays out of `required`". These guards cover the part that
 * made the advertisement true on every backend: the description must offer a
 * reference the model can construct without being shown any call id.
 */
class ToolBatchDependencySchemaTest {

    private val dependsOn = "depends_on"

    private fun shellExecute(): AgentToolDefinition = AgentToolDefinition(
        name = "shell_execute",
        description = "Execute a terminal command in the sandbox.",
        parameters = linkedMapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does."),
            "command" to AgentToolParam("string", "The shell command to execute."),
            "working_dir" to AgentToolParam("string", "Optional working directory."),
            "timeout" to AgentToolParam("integer", "Optional timeout in seconds."),
        ),
        required = listOf("tool_title", "command"),
        propertyOrdering = listOf("tool_title", "command", "working_dir", "timeout"),
    )

    private fun properties(definition: AgentToolDefinition, provider: String): JSONObject = when (provider) {
        "anthropic" -> definition.toAnthropicJson().getJSONObject("input_schema").getJSONObject("properties")
        "gemini" -> definition.toGeminiJson().getJSONObject("parameters").getJSONObject("properties")
        "openai" -> definition.toOpenAIJson().getJSONObject("function").getJSONObject("parameters")
            .getJSONObject("properties")
        else -> throw IllegalArgumentException(provider)
    }

    @Test
    fun `the description offers a reference the model can always construct and names the default`() {
        val description = properties(shellExecute(), "anthropic").getJSONObject(dependsOn).getString("description")

        assertTrue(
            "must offer the batch position, which works even where the client invents the call ids: $description",
            Regex("\"1\"\\s+for the first").containsMatchIn(description),
        )
        assertTrue("must name the alternative form: $description", description.contains("call id", ignoreCase = true))
        assertTrue("must state the default explicitly: $description", description.contains("parallel", ignoreCase = true))
        assertTrue("must scope the reference to this batch: $description", description.contains("SAME batch", ignoreCase = true))
    }

    @Test
    fun `the advertised key is the key the scheduler resolves, and the tool itself never declares it`() {
        val advertised = properties(shellExecute(), "anthropic").keys().asSequence()
            .firstOrNull { it.contains("depend") }
            ?: throw AssertionError("no dependency key advertised at all")

        assertEquals(dependsOn, advertised)
        assertEquals(
            ToolDependencyParseResult.Valid(setOf("1")),
            parseToolCallDependencies(JSONObject().put(advertised, "1")),
        )
        // Injected once by the shared schema builder, for every tool: no tool
        // declares it in its own parameter map and none lists it as required.
        assertFalse(shellExecute().parameters.containsKey(dependsOn))
        assertFalse(shellExecute().required.contains(dependsOn))
    }
}
