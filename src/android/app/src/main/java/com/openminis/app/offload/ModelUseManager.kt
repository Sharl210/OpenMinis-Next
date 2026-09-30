package com.openminis.app.offload

import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderInstance
import org.json.JSONArray
import org.json.JSONObject

/**
 * Provides model listing and search capabilities for the model_use tool.
 * Only surfaces the Agent-Loop-visible subset (mirrors iOS
 * ProviderConfigStore.resolvedAgentLoopEntries). Models not exposed in
 * Settings > Model Groups > "Available Models in Agent Loop" are hidden.
 *
 * Output shape mirrors iOS `ModelUseOffloadBridge.entryDict` so the agent
 * sees the same fields on both platforms — most importantly `modalities`,
 * which it needs to pick an image-generation / TTS model.
 */
object ModelUseManager {

    private const val NO_MODELS_HINT =
        "No models available. Go to Settings > Model Groups > Available Models in Agent Loop to expose models to the agent."

    /**
     * List the agent-loop-visible models, optionally filtered by a free-text
     * query that matches against model id, display name, or provider.
     */
    fun listModels(
        entries: List<ModelEntry>,
        instances: Map<String, ProviderInstance> = emptyMap(),
        filter: String? = null,
    ): String {
        val filtered = if (filter.isNullOrBlank()) entries else {
            val q = filter.lowercase()
            entries.filter {
                it.model.id.lowercase().contains(q) ||
                    it.model.displayName.lowercase().contains(q) ||
                    it.model.provider.lowercase().contains(q)
            }
        }

        if (filtered.isEmpty()) {
            return if (filter != null) "No models matching '$filter'."
            else NO_MODELS_HINT
        }

        val result = JSONArray()
        for (entry in filtered) {
            result.put(entryDict(entry, instances[entry.providerInstanceId]))
        }
        return result.toString(2)
    }

    /**
     * Emit the per-entry JSON. Field names match iOS exactly:
     * entry_id, model_id, display_name, provider, instance_label,
     * provider_type, modalities, context_window.
     *
     * [T-android-subagent-model-guidance] Plus the three things the requirement
     * says the MAIN agent must be told before it picks a sub-agent model:
     *
     * > "subagent 可以让主代理自己选择从提供商模型列表中自己勾选模型，然后每个模型还可以
     * >  备注，就是给主模型介绍什么时候用本模型作为子代理，用户可以自定义编辑介绍；默认
     * >  不填就是直接告诉主代理模型名和相对应的能力（思考、工具调用能力、输入输出模态能力
     * >  等模型详情），**即使用户编辑了备注，这些模型能力详情也要携带告知主代理**，只不过
     * >  优先参考用户备注建议。" (request.md:1)
     *
     * Before this, `subagent_note` was in the output nowhere: the note was stored
     * (`ProviderConfig.subAgentModelNotes`), editable in Settings, and then read by
     * nothing at all — `ProviderRepository.subAgentModelNote()` had zero callers
     * and the two model-use handlers contained the string "note" zero times. So the
     * main agent could never see the guidance the user wrote specifically for it.
     *
     * `supports_tools` / `supports_reasoning` are deliberately emitted even when a
     * note exists, because the requirement makes the capabilities unconditional and
     * the note merely advisory. Each is omitted when the catalog does not
     * affirmatively declare it, matching [com.openminis.app.data.model.LLMModel]'s
     * "null means unknown, not false" convention — the agent must be able to tell
     * "no tools" apart from "we don't know".
     *
     * @param subagentNote the user's own guidance for using this model as a
     *   sub-agent, verbatim. Null/blank means the user wrote nothing, and the
     *   caller falls back to name + capabilities.
     */
    fun entryDict(
        entry: ModelEntry,
        instance: ProviderInstance?,
        subagentNote: String? = null,
    ): JSONObject {
        // Enrich at read time — entries persisted before the modalities field
        // existed won't have inputModalities/outputModalities populated, so
        // look them up from the bundled models.dev registry on the fly.
        val enrichedModel = com.openminis.app.provider.ModelsDevApi.enrichModel(entry.model)
        val modalities = JSONArray()
        val inputs = enrichedModel.inputModalities.orEmpty()
        val outputs = enrichedModel.outputModalities.orEmpty()
        // Fallback when neither models.dev nor pattern inference populated them —
        // every LLM supports text in/out.
        val inputSet = if (inputs.isEmpty() && outputs.isEmpty()) listOf("text") else inputs
        val outputSet = if (inputs.isEmpty() && outputs.isEmpty()) listOf("text") else outputs
        if ("text" in inputSet) modalities.put("text_input")
        if ("text" in outputSet) modalities.put("text_output")
        if ("image" in inputSet) modalities.put("image_input")
        if ("image" in outputSet) modalities.put("image_output")
        if ("audio" in inputSet) modalities.put("audio_input")
        if ("audio" in outputSet) modalities.put("audio_output")
        if ("video" in inputSet) modalities.put("video_input")
        if ("video" in outputSet) modalities.put("video_output")
        if ("pdf" in inputSet) modalities.put("pdf_input")

        return JSONObject().apply {
            put("entry_id", entry.id)
            put("model_id", entry.model.id)
            put("display_name", entry.model.displayName)
            put("provider", entry.model.provider)
            put("instance_label", instance?.label ?: "unknown")
            put("provider_type", instance?.providerType?.displayName ?: "unknown")
            put("modalities", modalities)
            enrichedModel.contextWindow?.let { put("context_window", it) }
            // [T-android-subagent-model-guidance] Capability details are emitted
            // UNCONDITIONALLY (the requirement makes them mandatory even when the
            // user wrote a note), and only when the catalog affirmatively declares
            // them — a missing key reads as "unknown", never as a negative.
            enrichedModel.supportsTools?.let { put("supports_tools", it) }
            enrichedModel.supportsReasoning?.let { put("supports_reasoning", it) }
            enrichedModel.reasoningEffortValues?.takeIf { it.isNotEmpty() }
                ?.let { put("reasoning_effort_values", JSONArray(it)) }
            // The user's note, verbatim, and last so it reads as the closing
            // recommendation. `subagent_note_source` tells the agent whether it is
            // looking at user guidance or at the name+capabilities fallback the
            // requirement describes for the unwritten case — without it the two
            // are indistinguishable and "prefer the note" cannot be acted on.
            subagentNote?.trim()?.takeIf { it.isNotEmpty() }?.let {
                put("subagent_note", it)
                put("subagent_note_source", "user")
            } ?: put("subagent_note_source", "capabilities")
        }
    }
}
