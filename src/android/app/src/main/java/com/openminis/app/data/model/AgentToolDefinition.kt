package com.openminis.app.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Provider-agnostic tool definition. Each tool registers with this structure,
 * and providers convert it to their native format (Anthropic input_schema,
 * Gemini function_declarations, OpenAI function calling).
 */
data class AgentToolDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, AgentToolParam>,
    val required: List<String> = emptyList(),
    val propertyOrdering: List<String>? = null,
) {
    /**
     * The `properties` map every provider converter shares.
     *
     * Tool-owned parameters first, then the batch-scheduling metadata that is
     * identical on every tool ([SCHEDULING_PARAMS]). It lives here, once, so
     * the three converters (and any later one) can never advertise different
     * parameter sets — a rule duplicated per provider is a rule that drifts.
     *
     * `required` is deliberately untouched: scheduling metadata is optional.
     */
    private fun schemaProperties(gemini: Boolean): JSONObject {
        val render = if (gemini) AgentToolParam::toGeminiJson else AgentToolParam::toJson
        val props = JSONObject()
        for ((key, param) in parameters) {
            props.put(key, render(param))
        }
        for ((key, param) in SCHEDULING_PARAMS) {
            // A tool that ever declares its own parameter under this name keeps it.
            if (!props.has(key)) props.put(key, render(param))
        }
        return props
    }

    /**
     * Gemini's `propertyOrdering` mirror of [schemaProperties]' keys: the
     * scheduling parameters go last.
     *
     * The Gemini API documents `Schema.propertyOrdering` only as "the order of
     * the properties … used to determine the order of the properties in the
     * response" and says nothing about it having to be complete, so a property
     * left out of the list is (per the docs) still described. We append anyway:
     * it costs one array entry, it keeps ordering and `properties` consistent
     * for a schema the model reads, and it removes any dependence on a
     * documented-as-optional hint behaving permissively.
     */
    private fun geminiPropertyOrdering(): JSONArray? {
        val ordering = propertyOrdering ?: return null
        val complete = if (DEPENDS_ON_PARAM in ordering) ordering else ordering + DEPENDS_ON_PARAM
        return JSONArray(complete)
    }

    /** Anthropic format: {name, description, input_schema: {type:object, properties, required}} */
    fun toAnthropicJson(): JSONObject {
        val schema = JSONObject().apply {
            put("type", "object")
            put("properties", schemaProperties(gemini = false))
            if (required.isNotEmpty()) put("required", JSONArray(required))
        }
        return JSONObject().apply {
            put("name", name)
            put("description", description)
            put("input_schema", schema)
        }
    }

    /** Gemini format: {name, description, parameters: {type:OBJECT, properties, required}} */
    fun toGeminiJson(): JSONObject {
        val params = JSONObject().apply {
            put("type", "OBJECT")
            put("properties", schemaProperties(gemini = true))
            if (required.isNotEmpty()) put("required", JSONArray(required))
            geminiPropertyOrdering()?.let { put("propertyOrdering", it) }
        }
        return JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", params)
        }
    }

    /** OpenAI format: {type:function, function: {name, description, parameters: {type:object, ...}}} */
    fun toOpenAIJson(): JSONObject {
        val params = JSONObject().apply {
            put("type", "object")
            put("properties", schemaProperties(gemini = false))
            if (required.isNotEmpty()) put("required", JSONArray(required))
        }
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("description", description)
                put("parameters", params)
            })
        }
    }

    companion object {
        /**
         * Name of the optional batch-scheduling parameter advertised on EVERY
         * tool. The parser also tolerates the camelCase `dependsOn` spelling,
         * but only this one is put in front of the model.
         */
        const val DEPENDS_ON_PARAM = "depends_on"

        /**
         * Parameters that belong to tool-call *scheduling*, not to any single
         * tool's business. `depends_on` names other calls in the SAME batch
         * that must succeed first; omitting it keeps the default, which is
         * parallel execution.
         *
         * A call can be named two ways, because not every backend gives the
         * model ids it chose itself: Gemini and the Responses API synthesize
         * them client-side, so there the model can point at a sibling only by
         * its position in the batch. The description therefore offers the
         * position first (it always works) and mentions ids second.
         *
         * Declaring it here (instead of in each of the ~20 tool definitions)
         * is what keeps "one definition, every tool, all three providers"
         * true by construction, and matches the project's existing rule that
         * a general-purpose optional argument must be visible to the model.
         */
        internal val SCHEDULING_PARAMS: Map<String, AgentToolParam> = mapOf(
            DEPENDS_ON_PARAM to AgentToolParam(
                type = "string",
                description = "Optional. Tool-call scheduling metadata: names the other tool call(s) in " +
                    "THIS SAME batch that must complete successfully before this one runs. Name each " +
                    "one either by its position in this batch — \"1\" for the first call you emitted, " +
                    "\"2\" for the second, and so on — or, when your provider shows you call ids, by " +
                    "that call's id. The position always works; prefer it when you cannot see the " +
                    "ids. Give an array to wait for several calls. Use it only when this call " +
                    "genuinely needs something an earlier call produces (e.g. one call fetches a " +
                    "value, the next call consumes it). Leave it out and this call runs in parallel " +
                    "with the rest of the batch, which is the default.",
            ),
        )
    }
}

data class AgentToolParam(
    val type: String,
    val description: String,
    val enumValues: List<String>? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type)
        put("description", description)
        if (enumValues != null) put("enum", JSONArray(enumValues))
    }

    fun toGeminiJson(): JSONObject = JSONObject().apply {
        put("type", type.uppercase())
        put("description", description)
        if (enumValues != null) put("enum", JSONArray(enumValues))
    }
}
