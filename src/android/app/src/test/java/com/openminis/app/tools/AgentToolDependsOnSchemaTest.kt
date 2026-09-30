package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.ui.chat.ToolDependencyParseResult
import com.openminis.app.ui.chat.parseToolCallDependencies
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-tool-call-depends-on-schema] The blocking ("wait for the earlier call")
 * option is executed by `ToolCallBatchScheduler`, but a mechanism the model
 * cannot see is a mechanism the model never uses: no tool schema used to
 * declare `depends_on`, so the model had no way to know the option existed and
 * every batch ran fully parallel.
 *
 * These guards cover the three provider wire formats, every tool the agent can
 * actually be handed, and the fact that the parameter is OPTIONAL (a polluted
 * `required` list would make every tool call fail preflight).
 */
class AgentToolDependsOnSchemaTest {

    private val dependsOn = "depends_on"

    private val providers = listOf("anthropic", "gemini", "openai")

    /** The provider-specific `properties` object, addressed by its REAL path. */
    private fun properties(definition: AgentToolDefinition, provider: String): JSONObject = when (provider) {
        "anthropic" -> definition.toAnthropicJson().getJSONObject("input_schema").getJSONObject("properties")
        "gemini" -> definition.toGeminiJson().getJSONObject("parameters").getJSONObject("properties")
        "openai" -> definition.toOpenAIJson().getJSONObject("function").getJSONObject("parameters")
            .getJSONObject("properties")
        else -> throw IllegalArgumentException(provider)
    }

    /** The provider-specific schema object that owns `required`. */
    private fun schemaObject(definition: AgentToolDefinition, provider: String): JSONObject = when (provider) {
        "anthropic" -> definition.toAnthropicJson().getJSONObject("input_schema")
        "gemini" -> definition.toGeminiJson().getJSONObject("parameters")
        "openai" -> definition.toOpenAIJson().getJSONObject("function").getJSONObject("parameters")
        else -> throw IllegalArgumentException(provider)
    }

    private fun requiredNames(definition: AgentToolDefinition, provider: String): List<String> {
        val array = schemaObject(definition, provider).optJSONArray("required") ?: return emptyList()
        return (0 until array.length()).map { array.optString(it, "") }
    }

    /**
     * Every definition reachable from a public entry point: the maximal
     * [AgentTools.makeAgentTools] gate set, the delegated-child surface, and the
     * standalone public factories (so a future call site that builds a list
     * without going through `AgentTools` is still covered).
     */
    private fun allToolDefinitions(): List<AgentToolDefinition> = buildList {
        addAll(
            AgentTools.makeAgentTools(
                supportsImageInput = true,
                visionGroupConfigured = true,
                memoryEnabled = true,
                goalActive = true,
                restartAvailable = true,
            ),
        )
        addAll(AgentTools.makeChildAgentTools())
        add(FileReadTool.definition())
        add(FileWriteTool.definition())
        add(FileEditTool.definition())
        add(ReadImageTool.definition())
        add(BrowserDevToolsTools.definition())
    }.distinctBy { it.name }

    private fun sample() = AgentTools.makeAgentTools().first { it.name == "shell_execute" }

    @Test
    fun `all three provider schemas advertise depends_on with a usable description`() {
        val definition = sample()
        for (provider in providers) {
            val props = properties(definition, provider)
            assertTrue("$provider schema must advertise `$dependsOn`", props.has(dependsOn))

            val param = props.getJSONObject(dependsOn)
            assertEquals(
                "$provider: declared as a string so a single tool-call id fits the schema",
                "string",
                param.getString("type").lowercase(),
            )

            val description = param.optString("description", "")
            assertTrue("$provider: description must not be empty", description.isNotBlank())
            assertTrue(
                "$provider: description must state what the value is (ids of other tool calls)",
                description.contains("tool call", ignoreCase = true),
            )
            assertTrue(
                "$provider: description must scope the ids to the same batch",
                description.contains("same batch", ignoreCase = true),
            )
            assertTrue(
                "$provider: description must state that omitting it means parallel execution",
                description.contains("parallel", ignoreCase = true),
            )
        }
    }

    @Test
    fun `depends_on is never added to any provider's required list`() {
        val definitions = allToolDefinitions()
        for (definition in definitions) {
            for (provider in providers) {
                // Non-vacuous: the parameter has to be there at all before its
                // absence from `required` means anything.
                assertTrue(
                    "${definition.name}/$provider: `$dependsOn` must be advertised",
                    properties(definition, provider).has(dependsOn),
                )
                assertFalse(
                    "${definition.name}/$provider: `$dependsOn` is optional and must stay out of required",
                    requiredNames(definition, provider).contains(dependsOn),
                )
            }
        }
    }

    @Test
    fun `every tool AgentTools exposes advertises depends_on on every provider`() {
        val definitions = allToolDefinitions()
        val failures = mutableListOf<String>()
        for (definition in definitions) {
            for (provider in providers) {
                if (!properties(definition, provider).has(dependsOn)) failures += "${definition.name}/$provider"
            }
        }
        assertTrue("tools whose schema is missing `$dependsOn`: $failures", failures.isEmpty())
        println(
            "[depends_on coverage] ${definitions.size} distinct definitions x ${providers.size} providers: " +
                definitions.map { it.name }.sorted().joinToString(", "),
        )
    }

    @Test
    fun `gemini keeps its propertyOrdering complete and puts the scheduling key last`() {
        val ordered = allToolDefinitions().filter { it.propertyOrdering != null }
        assertTrue("no tool declares propertyOrdering anymore — this guard went blind", ordered.isNotEmpty())

        for (definition in ordered) {
            val params = definition.toGeminiJson().getJSONObject("parameters")
            val ordering = params.getJSONArray("propertyOrdering")
            val names = (0 until ordering.length()).map { ordering.getString(it) }

            assertEquals("${definition.name}: scheduling key must be ordered last", dependsOn, names.last())
            assertEquals("${definition.name}: ordering must not repeat a name", names.size, names.toSet().size)

            val propertyNames = params.getJSONObject("properties").keys().asSequence().toSet()
            val orderedButUndeclared = names.filter { it !in propertyNames }
            assertTrue(
                "${definition.name}: propertyOrdering lists names that are not properties: $orderedButUndeclared",
                orderedButUndeclared.isEmpty(),
            )
        }
    }

    @Test
    fun `the advertised name is the one the batch scheduler parses`() {
        // The literal is deliberate: if the advertised key is ever renamed, this
        // guard must go red instead of silently following the rename and leaving
        // the scheduler unable to read what the model was told to send.
        val advertised = "depends_on"
        assertTrue("schema must advertise `$advertised`", properties(sample(), "anthropic").has(advertised))
        assertTrue(
            "schema must advertise `$advertised` to Gemini too",
            properties(sample(), "gemini").has(advertised),
        )

        assertEquals(
            ToolDependencyParseResult.Valid(setOf("call-a")),
            parseToolCallDependencies(JSONObject().put(advertised, "call-a")),
        )
        assertEquals(
            ToolDependencyParseResult.Valid(setOf("call-a", "call-b")),
            parseToolCallDependencies(JSONObject().put(advertised, JSONArray(listOf("call-a", "call-b")))),
        )
    }
}
