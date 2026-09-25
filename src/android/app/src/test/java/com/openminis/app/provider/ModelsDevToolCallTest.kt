package com.openminis.app.provider

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelOverrides
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Regression coverage for models.dev tool_call tri-state and user precedence. */
class ModelsDevToolCallTest {
    private fun parseModel(json: String): ModelsDevApi.ModelDevEntry {
        val method = ModelsDevApi::class.java.getDeclaredMethod(
            "parseModelDevEntry",
            String::class.java,
            JSONObject::class.java,
        ).apply { isAccessible = true }
        return method.invoke(ModelsDevApi, "test-model", JSONObject(json)) as ModelsDevApi.ModelDevEntry
    }

    @Test
    fun `tool_call preserves true false and unknown`() {
        assertEquals(true, parseModel("{\"tool_call\":true}").toolCall)
        assertEquals(false, parseModel("{\"tool_call\":false}").toolCall)
        assertEquals(null, parseModel("{}").toolCall)
        assertEquals(null, parseModel("{\"tool_call\":null}").toolCall)
    }

    @Test
    fun `user override wins and null restores catalog value`() {
        val catalogModel = LLMModel(
            id = "test-model",
            displayName = "Test",
            provider = "Test",
            supportsTools = false,
        )

        val forcedOn = ModelEntry(
            providerInstanceId = "provider",
            baseModel = catalogModel,
            overrides = ModelOverrides(supportsTools = true),
        )
        assertEquals(true, forcedOn.model.supportsTools)

        val forcedOff = forcedOn.copy(overrides = ModelOverrides(supportsTools = false))
        assertEquals(false, forcedOff.model.supportsTools)

        val inherited = forcedOn.copy(overrides = ModelOverrides(supportsTools = null))
        assertEquals(false, inherited.model.supportsTools)
    }
}
