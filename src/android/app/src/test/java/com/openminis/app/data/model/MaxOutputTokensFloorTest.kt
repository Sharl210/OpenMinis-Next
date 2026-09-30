package com.openminis.app.data.model

import com.openminis.app.provider.LLMProvider
import kotlinx.coroutines.flow.Flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [G3-max-output-family-floor] The max-output half of the capability template.
 *
 * What this file is for: before this change a model whose id was in neither the
 * models.dev catalog nor the per-id template got the provider-level 16_384 for
 * output, while its own siblings got 393_216 — a 24× cliff a user sees as
 * long code/answers cut mid-sentence, with nothing in the UI explaining why.
 * The CONTEXT half of the family floor (the `deepseek` branch in
 * `contextWindowTokens`) had been fixed separately, which is what made the
 * family look repaired while this half stayed broken.
 *
 * These are behavioural assertions on the real production entry points —
 * `LLMModel.resolvedMaxOutputTokens` and the `LLMProvider.effectiveMaxOutputTokens`
 * that actually feeds `dynamicMaxTokens` — not source-text checks. The sibling
 * regression file for the template itself is
 * [RecentModelDefaultsTest]; this one owns the fallback *chain* around it.
 */
class MaxOutputTokensFloorTest {

    /** Exactly what a custom relay's /v1/models response would build. */
    private fun fromRelay(id: String) = LLMModel(id = id, displayName = id, provider = "Custom")

    /** The generic third-party OpenAI-compatible endpoint: no published ceiling. */
    private open class FakeOpenAICompatibleProvider(override var model: LLMModel) : LLMProvider {
        override val name: String = "fake-openai-compatible"
        override suspend fun sendMessageClamped(
            messages: List<LLMMessage>,
            systemPrompt: String?,
            maxTokens: Int,
            temperature: Double?,
            imageParts: List<LLMMessage.ImagePart>,
            tools: List<AgentToolDefinition>,
            thinkingLevel: ThinkingLevel,
        ): LLMResponse = LLMResponse(text = "ok", stopReason = "stop", usage = null)

        override fun streamMessageClamped(
            messages: List<LLMMessage>,
            systemPrompt: String?,
            maxTokens: Int,
            temperature: Double?,
            imageParts: List<LLMMessage.ImagePart>,
            tools: List<AgentToolDefinition>,
            thinkingLevel: ThinkingLevel,
        ): Flow<LLMStreamChunk> = throw UnsupportedOperationException("not exercised")
    }

    /** Mirrors AnthropicProvider, which publishes a real ceiling for its own API. */
    private class FakeAnthropicCompatibleProvider(override var model: LLMModel) :
        FakeOpenAICompatibleProvider(model) {
        override val name: String = "fake-anthropic-compatible"
        override val defaultMaxOutputTokens: Int get() = 64_000
    }

    private fun effective(id: String): Int {
        val m = fromRelay(id)
        return FakeOpenAICompatibleProvider(m).effectiveMaxOutputTokens(m)
    }

    // ── 1. An unregistered id of a family with a documented floor ───────────

    @Test
    fun `unregistered deepseek id gets the family output ceiling, not 16_384`() {
        val m = fromRelay("deepseek-v9-unreleased")

        // The context half (pre-existing) must still answer.
        assertEquals(1_000_000, m.contextWindowTokens)
        // The output half (this change). 393_216 = the 384K every published
        // DeepSeek row carries, expressed as models.dev expresses it (384×1024)
        // so one family carries one number.
        assertEquals(393_216, m.resolvedMaxOutputTokens)
        assertEquals(393_216, effective("deepseek-v9-unreleased"))
    }

    @Test
    fun `unregistered deepseek id matches its registered siblings`() {
        // The user-visible symptom was a CLIFF between two ids of the same
        // family, so the assertion that matters is equality, not a number.
        assertEquals(effective("deepseek-chat"), effective("deepseek-v9-unreleased"))
        assertEquals(effective("deepseek-v4-pro"), effective("deepseek-v9-unreleased"))
        assertEquals(393_216, effective("deepseek-v9-unreleased"))
    }

    // ── 2. Families with no defensible number must NOT be widened ───────────

    @Test
    fun `families with no agreed output ceiling keep the conservative fallback`() {
        // GLM 16_384–131_072, Qwen 512–384_000, OpenAI 0–272_000, xAI
        // 0–500_000, Moonshot 16_384–262_144 across their own rows (bundled
        // models.dev snapshot) — a family number for any of them would be a
        // fresh guess, and guessing output too HIGH is the expensive direction
        // (a provider 400, not a truncation). So they get nothing.
        // The conservative number itself is pinned in one place.
        assertEquals(16_384, LLMModel.DEFAULT_MAX_OUTPUT_TOKENS)

        for (id in listOf("relay-house-model-9000", "glm-6-unknown", "qwen9-max", "MiniMax-M9")) {
            assertNull("$id must get no family floor", fromRelay(id).resolvedMaxOutputTokens)
            assertEquals("effective output for $id", 16_384, effective(id))
        }
    }

    // ── 3. Every escape hatch still outranks the floor ──────────────────────

    @Test
    fun `user override outranks the family floor`() {
        val overridden = LLMModel(
            id = "deepseek-v9-unreleased",
            displayName = "override",
            provider = "Custom",
            maxOutputTokens = 8_000,
        )
        assertEquals(8_000, overridden.resolvedMaxOutputTokens)
        assertEquals(8_000, FakeOpenAICompatibleProvider(overridden).effectiveMaxOutputTokens(overridden))
    }

    @Test
    fun `a value the endpoint itself reported outranks the family floor`() {
        val relaySaidSo = LLMModel(
            id = "deepseek-v9-unreleased",
            displayName = "relay-said",
            provider = "Custom",
            maxOutputTokens = 4_096,
        )
        assertEquals(
            4_096,
            FakeOpenAICompatibleProvider(relaySaidSo).effectiveMaxOutputTokens(relaySaidSo),
        )
    }

    @Test
    fun `the per-id template still outranks the family floor`() {
        // `deepseek-chat` is in the template; the floor is only reached for ids
        // the template declines. Both currently agree on 393_216, so this pins
        // reachability rather than a different number: the template hit must be
        // what answers, which is why a template id that is NOT in the family
        // list is asserted separately below.
        assertEquals(393_216, fromRelay("deepseek-chat").resolvedMaxOutputTokens)
        // A template entry that deliberately leaves output unset must STAY
        // unset — the floor must not resurrect a value its own sources
        // disagreed on (4 bundled relay rows disagree 2M/2M/30_000/16_384).
        assertNull(fromRelay("grok-4-fast-non-reasoning").resolvedMaxOutputTokens)
        assertEquals(16_384, effective("grok-4-fast-non-reasoning"))
    }

    @Test
    fun `a provider that publishes its own ceiling keeps it when the model has none`() {
        val unknown = fromRelay("relay-house-model-9000")
        val anthropicShaped = FakeAnthropicCompatibleProvider(unknown)
        assertEquals(64_000, anthropicShaped.effectiveMaxOutputTokens(unknown))
    }

    // ── 4. The two halves are pinned together ───────────────────────────────

    @Test
    fun `the model-level chain and the provider consumer must agree`() {
        // This is the anti-"someone fixes only one of them" pin. The chain in
        // LLMModel and the line in LLMProvider that actually feeds
        // dynamicMaxTokens are two halves of one behaviour: reverting either
        // alone puts the unregistered DeepSeek id back on 16_384 while the
        // other half still reports the family value.
        val m = fromRelay("deepseek-v9-unreleased")
        val provider = FakeOpenAICompatibleProvider(m)
        assertEquals(393_216, m.resolvedMaxOutputTokens)
        assertEquals(m.resolvedMaxOutputTokens, provider.effectiveMaxOutputTokens(m))
        assertEquals(393_216, provider.effectiveMaxOutputTokens(m))
    }
}
