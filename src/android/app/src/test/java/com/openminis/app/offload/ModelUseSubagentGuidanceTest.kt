package com.openminis.app.offload

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-subagent-model-guidance] Guards what the main agent is told about the
 * models it may pick as sub-agents.
 *
 * Requirement (request.md:1, verbatim):
 *
 * > "subagent 可以让主代理自己选择从提供商模型列表中自己勾选模型，然后每个模型还可以
 * >  备注，就是给主模型介绍什么时候用本模型作为子代理，用户可以自定义编辑介绍；默认不填
 * >  就是直接告诉主代理模型名和相对应的能力（思考、工具调用能力、输入输出模态能力等模型
 * >  详情），即使用户编辑了备注，这些模型能力详情也要携带告知主代理，只不过优先参考用户
 * >  备注建议。"
 *
 * Before this, `subagent_note` appeared in NO output surface: the note was stored
 * and editable in Settings, `ProviderRepository.subAgentModelNote()` had **zero
 * callers**, and both model-use handlers contained the string "note" zero times.
 * The main agent therefore never saw guidance the user had written for it — and the
 * capability fields the requirement calls mandatory (thinking, tool calling) were
 * missing from the listing entirely.
 *
 * Two halves are pinned here, because the requirement has two halves:
 *  1. the note is carried when present;
 *  2. the capability details are carried **anyway** — "即使用户编辑了备注，这些模型能力
 *     详情也要携带" — with the note merely taking precedence as advice.
 */
class ModelUseSubagentGuidanceTest {

    // `ModelEntry.id` is the entry's `uuid`, not the provider instance id — my first
    // draft passed "inst-1" as providerInstanceId and asserted entry_id == "entry-1",
    // which could never hold.
    private fun entry(
        uuid: String = "entry-1",
        modelId: String = "gpt-5.6-terra",
        displayName: String = "GPT-5.6 Terra",
        supportsTools: Boolean? = null,
        supportsReasoning: Boolean? = null,
        reasoningEffortValues: List<String>? = null,
        contextWindow: Int? = null,
    ) = ModelEntry(
        providerInstanceId = "inst-1",
        uuid = uuid,
        baseModel = LLMModel(
            id = modelId,
            displayName = displayName,
            provider = "openai",
            contextWindow = contextWindow,
            supportsReasoning = supportsReasoning,
            reasoningEffortValues = reasoningEffortValues,
            supportsTools = supportsTools,
        ),
    )

    private fun dict(
        entry: ModelEntry,
        note: String? = null,
    ): JSONObject = ModelUseManager.entryDict(entry, instance = null, subagentNote = note)

    // ---- the note reaches the agent ---------------------------------------------

    @Test
    fun `the user's sub-agent note is carried verbatim`() {
        // THE defect this file exists for: the note used to reach no output at all.
        val note = "用这个做代码审计，它慢但智商高"
        val json = dict(entry(), note)
        assertEquals(note, json.optString("subagent_note"))
    }

    @Test
    fun `a blank note is treated as absent rather than emitted as empty text`() {
        // The requirement's fallback case ("默认不填就是直接告诉主代理模型名和相对应的能力")
        // must not render as an empty guidance string, which reads as "the user said
        // nothing useful" instead of "the user said nothing".
        listOf(null, "", "   ", "\n\t").forEach { blank ->
            val json = dict(entry(), blank)
            assertFalse(
                "a blank note must not produce a subagent_note key: $blank",
                json.has("subagent_note"),
            )
        }
    }

    @Test
    fun `the source field distinguishes user guidance from the capability fallback`() {
        // Without this the agent cannot act on "优先参考用户备注建议": an absent note
        // and a note that merely happens to be empty look identical.
        assertEquals("user", dict(entry(), "prefer me for audits").optString("subagent_note_source"))
        assertEquals("capabilities", dict(entry(), null).optString("subagent_note_source"))
        assertEquals("capabilities", dict(entry(), "   ").optString("subagent_note_source"))
    }

    @Test
    fun `the note does not displace the model's own identity fields`() {
        val json = dict(entry(), "some guidance")
        assertEquals("entry-1", json.optString("entry_id"))
        assertEquals("gpt-5.6-terra", json.optString("model_id"))
        assertEquals("GPT-5.6 Terra", json.optString("display_name"))
        assertEquals("openai", json.optString("provider"))
    }

    // ---- capability details are MANDATORY, note or no note ----------------------

    @Test
    fun `tool-calling capability is reported even when the user wrote a note`() {
        // "即使用户编辑了备注，这些模型能力详情也要携带告知主代理"
        val json = dict(entry(supportsTools = true), "use for audits")
        assertTrue("supports_tools must survive a note", json.has("supports_tools"))
        assertTrue(json.getBoolean("supports_tools"))
    }

    @Test
    fun `thinking capability and its effort tiers are reported`() {
        val json = dict(
            entry(supportsReasoning = true, reasoningEffortValues = listOf("low", "high")),
            "use for audits",
        )
        assertTrue(json.getBoolean("supports_reasoning"))
        val tiers = json.getJSONArray("reasoning_effort_values")
        assertEquals(2, tiers.length())
        assertEquals("low", tiers.getString(0))
        assertEquals("high", tiers.getString(1))
    }

    @Test
    fun `input and output modalities are still reported`() {
        val json = dict(entry(), "note")
        val modalities = json.getJSONArray("modalities")
        assertTrue("text_input must be present", modalities.toString().contains("text_input"))
    }

    @Test
    fun `an undeclared capability is omitted rather than reported as false`() {
        // LLMModel's convention is "null means unknown, not false" (see the KDoc on
        // `supportsTools` / `declaresNoEffortTiers`). Emitting `false` here would
        // tell the agent a model cannot call tools when the catalog simply has never
        // heard of it — a fabricated negative that would rule out a usable model.
        //
        // [R8-recent-model-defaults] The fixture must be an id NO capability
        // source knows. It used to default to `gpt-5.6-terra`, which was fine only
        // while nothing had data on it; that id now has a built-in default
        // template entry (LLMModel.recentModelDefaults), so enrichModel()
        // legitimately resolves tools + reasoning and the keys appear. The
        // contract under test is about SILENCE, so the model must stay silent — a
        // made-up relay id is the honest fixture. The companion test below pins
        // the template-known direction explicitly, so both halves are covered
        // rather than only the convenient one.
        val json = dict(entry(modelId = "relay-house-model-9000", supportsTools = null, supportsReasoning = null), "note")
        assertFalse("unknown tool support must not become false", json.has("supports_tools"))
        assertFalse("unknown reasoning must not become false", json.has("supports_reasoning"))
    }

    @Test
    fun `a template-known id reports the capability the template declares`() {
        // The other half of the contract: once a built-in default template entry
        // exists, "unknown" is no longer the truth — the capability is published
        // and the listing should carry it so the agent can plan around it.
        // gpt-5.6-terra: models.dev `openai` lists context 1_050_000, output
        // 128_000, reasoning true, tool_call true (read 2026-09-30), and all 16
        // catalog rows for this id agree.
        val json = dict(entry(modelId = "gpt-5.6-terra", supportsTools = null, supportsReasoning = null), "note")
        assertTrue("declared tool support must be reported", json.getBoolean("supports_tools"))
        assertTrue("declared reasoning must be reported", json.getBoolean("supports_reasoning"))

        // An explicit `false` on the entry still wins over the template — the
        // template only fills silence, it never overrides the caller's answer.
        val off = dict(entry(modelId = "gpt-5.6-terra", supportsTools = false), "note")
        assertFalse(off.getBoolean("supports_tools"))
    }

    @Test
    fun `an affirmatively false capability IS reported, because it is real information`() {
        // The mirror of the case above: "we know it cannot do this" is exactly what
        // the agent needs in order to not pick the model.
        val json = dict(entry(supportsTools = false), "note")
        assertTrue(json.has("supports_tools"))
        assertFalse(json.getBoolean("supports_tools"))
    }

    @Test
    fun `an empty effort-tier list is omitted rather than emitted as an empty array`() {
        // An empty array would read as "this model accepts no effort tiers", which is
        // the affirmative claim `declaresNoEffortTiers` exists to make separately.
        val json = dict(entry(supportsReasoning = true, reasoningEffortValues = emptyList()), "note")
        assertFalse(json.has("reasoning_effort_values"))
    }

    @Test
    fun `capability fields are present with no note at all, matching the fallback rule`() {
        // "默认不填就是直接告诉主代理模型名和相对应的能力"
        val json = dict(
            entry(supportsTools = true, supportsReasoning = true, contextWindow = 400_000),
        )
        assertTrue(json.getBoolean("supports_tools"))
        assertTrue(json.getBoolean("supports_reasoning"))
        assertEquals(400_000, json.getInt("context_window"))
        assertEquals("capabilities", json.optString("subagent_note_source"))
        assertFalse(json.has("subagent_note"))
    }
}
