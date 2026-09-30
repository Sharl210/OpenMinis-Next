package com.openminis.app.data.model

import com.openminis.app.provider.ModelsDevApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [R8-recent-model-defaults] Regression coverage for the built-in default
 * parameter template in `LLMModel` and for its precedence chain:
 *
 *   provider payload / user override  >  models.dev catalog  >  static template
 *                                     >  id heuristic  >  generic 128K floor
 *
 * Values asserted here are the ones published by the sources named in
 * `LLMModel`'s template comments (models.dev `https://models.dev/api.json` read
 * 2026-09-30, plus each vendor's own model page). When a source moves, this file
 * is where the change should surface — deliberately, and in one place.
 */
class RecentModelDefaultsTest {

    // ── Reflection plumbing ────────────────────────────────────────────────
    //
    // `cachedRegistry` / `cacheTimestamp` are private state on the ModelsDevApi
    // object. Unit tests run without an android.content.Context, so the normal
    // three-tier load (memory → disk → bundled asset) can only ever reach the
    // memory tier; installing a registry there is what lets us exercise the real
    // `enrichModels` precedence path instead of re-implementing it. Same
    // reflection style as ModelsDevToolCallTest.

    private val registryField = ModelsDevApi::class.java
        .getDeclaredField("cachedRegistry").apply { isAccessible = true }
    private val timestampField = ModelsDevApi::class.java
        .getDeclaredField("cacheTimestamp").apply { isAccessible = true }

    private fun parseRegistry(json: String): Map<String, ModelsDevApi.ProviderEntry> {
        val method = ModelsDevApi::class.java
            .getDeclaredMethod("parseRegistry", String::class.java)
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return method.invoke(ModelsDevApi, json) as Map<String, ModelsDevApi.ProviderEntry>
    }

    private fun installRegistry(json: String) {
        registryField.set(ModelsDevApi, parseRegistry(json))
        timestampField.setLong(ModelsDevApi, System.currentTimeMillis())
    }

    /** Never leave the fake catalog visible to another test class. */
    @After
    fun clearInjectedRegistry() {
        registryField.set(ModelsDevApi, null)
        timestampField.setLong(ModelsDevApi, 0L)
    }

    /** A model exactly as a custom relay's /v1/models response would build it. */
    private fun fromRelay(id: String) = LLMModel(id = id, displayName = id, provider = "Custom")

    // ── 1. The reported symptom: DeepSeek stuck at the retired 128K ─────────

    @Test
    fun `deepseek template carries the published 1M window not 128K`() {
        val pro = LLMModel.staticDefaultFor("deepseek-v4-pro")
        assertNotNull("deepseek-v4-pro must have a template entry", pro)
        pro!!
        // https://api-docs.deepseek.com/quick_start/pricing — "CONTEXT LENGTH 1M"
        assertEquals(1_000_000, pro.contextWindow)
        // …"MAX OUTPUT MAXIMUM: 384K" (384 × 1024, as models.dev reports it)
        assertEquals(393_216, pro.maxOutputTokens)
        assertEquals(true, pro.supportsReasoning)
        assertEquals(true, pro.supportsTools)
        assertEquals(listOf("text"), pro.inputModalities)

        // A relay-built instance has no values of its own, so it lands on the
        // template. This is the assertion the old `deepseek → 128_000` branch
        // failed: it answered 128_000 for every DeepSeek id, including ones it
        // had no data about.
        assertEquals(1_000_000, fromRelay("deepseek-v4-pro").contextWindowTokens)
    }

    @Test
    fun `whole deepseek lineup is 1M with the flash tiers seeing images`() {
        // Official docs list exactly deepseek-flash + deepseek-v4-pro and state
        // that deepseek-v4-flash / deepseek-v4-flash-vision-exp are retired
        // names served by DeepSeek-V4.1-Flash — so all four share the window.
        for (id in listOf(
            "deepseek-flash",
            "deepseek-v4-pro",
            "deepseek-v4-flash",
            "deepseek-v4-flash-vision-exp",
        )) {
            val m = LLMModel.staticDefaultFor(id)
            assertNotNull("missing template entry for $id", m)
            assertEquals("context for $id", 1_000_000, m!!.contextWindowTokens)
            assertEquals("output for $id", 393_216, m.maxOutputTokens)
        }

        // "Vision ✓" is declared only where sources agree: `deepseek-flash`
        // (docs) and the `-vision-exp` id, NOT plain `deepseek-v4-flash` (all
        // 23 bundled rows for it say input ["text"]).
        // Read through the production enrichment path: modalities are template
        // FIELDS, so they reach a relay-built instance via enrichModels(), not
        // through a getter (only contextWindowTokens resolves lazily).
        assertEquals(
            listOf("text", "image"),
            ModelsDevApi.enrichModels(listOf(fromRelay("deepseek-flash"))).single().inputModalities,
        )
        assertEquals(
            listOf("text", "image"),
            ModelsDevApi.enrichModels(listOf(fromRelay("deepseek-v4-flash-vision-exp"))).single().inputModalities,
        )
        assertEquals(
            "deepseek-v4-flash is text-only in every bundled catalog row",
            listOf("text"),
            ModelsDevApi.enrichModels(listOf(fromRelay("deepseek-v4-flash"))).single().inputModalities,
        )
        assertEquals(
            listOf("text"),
            ModelsDevApi.enrichModels(listOf(fromRelay("deepseek-v4-pro"))).single().inputModalities,
        )
        assertEquals(true, LLMModel.staticDefaultFor("deepseek-v4-pro")!!.supportsTools)
    }

    @Test
    fun `the pre-V4 deepseek ids answer the capability half, not just the window`() {
        // The half of the DeepSeek fix that shipped late, and the reason this
        // test exists: the CONTEXT half landed first (the family floor plus 1M
        // windows), which made the family look repaired, while
        // `supportsReasoning` / `maxOutputTokens` / `inputModalities` had no
        // family-level answer at all — only the per-id template, which carried
        // four V4 ids and neither `deepseek-chat` nor `deepseek-reasoner`.
        //
        // User-visible consequence, with the models.dev catalog cold: a relay
        // serving `deepseek-reasoner` (the catalog declares `reasoning: true`)
        // got `supportsReasoning == null`, which the `== true` gate in
        // `ChatViewModel.currentModelSupportsReasoning` reads as "thinking off",
        // and `maxOutputTokens == null`, which drops to the provider-level
        // 16_384 fallback in `LLMProvider`.
        val chat = ModelsDevApi.enrichModels(listOf(fromRelay("deepseek-chat"))).single()
        assertEquals(1_000_000, chat.contextWindowTokens)
        assertEquals(393_216, chat.maxOutputTokens)
        assertEquals(false, chat.supportsReasoning)
        assertEquals(listOf("text"), chat.inputModalities)
        assertEquals(true, chat.supportsTools)

        val reasoner = ModelsDevApi.enrichModels(listOf(fromRelay("deepseek-reasoner"))).single()
        assertEquals(1_000_000, reasoner.contextWindowTokens)
        assertEquals(393_216, reasoner.maxOutputTokens)
        assertEquals(true, reasoner.supportsReasoning)
        assertEquals(listOf("text"), reasoner.inputModalities)
        assertEquals(true, reasoner.supportsTools)

        // The lookup is case-insensitive, so a relay's casing must not decide
        // whether the capability fields get answered at all.
        assertEquals(true, ModelsDevApi.enrichModels(listOf(fromRelay("DeepSeek-Reasoner"))).single().supportsReasoning)
    }

    @Test
    fun `no template DeepSeek id leaves reasoning, output or modalities unknown`() {
        // The guard for "the other half went missing again". The failure mode is
        // not a wrong number, it is an ABSENT answer: an id that the template
        // claims to carry but that still reports `null` for the capability
        // fields. `null` is not neutral here — it closes the
        // `supportsReasoning == true` gate (Deep Thinking forced OFF for a model
        // that does reason) and hands output to the provider's 16_384 fallback.
        //
        // The two DeepSeek fixes were shipped as two halves, and the first half
        // passing is exactly what hid the second: every existing DeepSeek
        // assertion went through `contextWindowTokens`, which the family floor
        // answers for ANY deepseek id. So this asserts the fields the floor
        // cannot answer.
        //
        // Two shapes of the same regression, and the loop below only catches the
        // second one on its own:
        //   • "the id was never added to the template at all" — what actually
        //     happened to `deepseek-chat` / `deepseek-reasoner`. The family floor
        //     answers their CONTEXT anyway, so a missing entry looks like a
        //     working one; the pin below is what makes their absence fail.
        //   • "the id is listed but its capability fields are still null" — what
        //     a future entry will regress into; the `allDeepSeek` loop catches it
        //     for every id the family carries, including ones added later.
        val mustBeCarried = listOf(
            "deepseek-flash",                  // vendor docs: DeepSeek-V4.1-Flash
            "deepseek-chat",                   // bundled catalog (models.dev `deepseek`)
            "deepseek-reasoner",               // bundled catalog
            "deepseek-v4-pro",
            "deepseek-v4-flash",
            "deepseek-v4-flash-vision-exp",
        )
        val carried = LLMModel.allDeepSeek.map { it.id.lowercase() }.toSet()
        for (id in mustBeCarried) {
            assertTrue("$id is missing from allDeepSeek (carried: $carried)", id in carried)
        }

        assertTrue("allDeepSeek is empty — this guard would pass vacuously", LLMModel.allDeepSeek.isNotEmpty())
        for (m in LLMModel.allDeepSeek) {
            val t = LLMModel.staticDefaultFor(m.id)
            assertNotNull("allDeepSeek id ${m.id} is not resolvable in the template", t)
            assertNotNull("supportsReasoning for ${m.id} must be declared, not left unknown", t!!.supportsReasoning)
            assertNotNull("maxOutputTokens for ${m.id} must be declared, not left unknown", t.maxOutputTokens)
            assertNotNull("inputModalities for ${m.id} must be declared, not left unknown", t.inputModalities)
            assertNotNull("outputModalities for ${m.id} must be declared, not left unknown", t.outputModalities)
        }
    }

    // ── 1b. Second batch: the same gap, one release later ───────────────────

    @Test
    fun `no template id in the default parameter set leaves any field unknown`() {
        // The generalisation of the DeepSeek guard above, for exactly the reason
        // that guard exists: the second half of a fix goes missing quietly when
        // the first half is what the assertions look at. There it was "context
        // answered by the family floor, capabilities absent"; here it is "the id
        // was added for its window and the capability fields left null".
        //
        // Scope = `recentModelDefaults`, i.e. the families that exist ONLY for
        // custom relays (DeepSeek / GLM / Qwen / MiniMax / Codex-OAuth / GPT-6 /
        // Xiaomi / Meta). Those are supposed to be COMPLETE entries: when a field
        // has no source, the reason is to leave the entry out, not to ship half of
        // it. That is what makes a non-null assertion honest here rather than
        // aspirational.
        //
        // The DeepSeek-specific guard above stays: it also pins the ID SET
        // (`mustBeCarried`), which this loop cannot see, so it catches an id
        // dropped from `allDeepSeek`; this one catches an entry that arrived
        // half-filled anywhere in the template, including families added later.
        assertTrue(
            "recentModelDefaults is empty — this guard would pass vacuously",
            LLMModel.recentModelDefaults.isNotEmpty(),
        )
        for (m in LLMModel.recentModelDefaults) {
            val t = LLMModel.staticDefaultFor(m.id)
            assertNotNull("template id ${m.id} is not resolvable", t)
            t!!
            assertNotNull("contextWindow for ${m.id} must be declared, not left unknown", t.contextWindow)
            assertNotNull("maxOutputTokens for ${m.id} must be declared, not left unknown", t.maxOutputTokens)
            assertNotNull("supportsReasoning for ${m.id} must be declared, not left unknown", t.supportsReasoning)
            assertNotNull("supportsTools for ${m.id} must be declared, not left unknown", t.supportsTools)
            assertTrue("inputModalities for ${m.id} must be declared and non-empty", !t.inputModalities.isNullOrEmpty())
            assertTrue("outputModalities for ${m.id} must be declared and non-empty", !t.outputModalities.isNullOrEmpty())
        }
    }

    @Test
    fun `the second batch reaches a relay-built instance through the template`() {
        // The user-visible path rather than the table: the relay reports bare ids,
        // the catalog is cold, and `enrichModels` is the only thing between those
        // ids and the generic 128_000 floor. Before this batch every entry below
        // got exactly that floor (or, for gemini, the heuristic's 1_000_000) plus
        // null output/reasoning/modalities.
        val expected = mapOf(
            // platform.openai.com/docs/models/<id> + models.dev `openai`
            "gpt-6.1-sol" to 1_050_000,
            "gpt-6-sol" to 1_050_000,
            "gpt-6-luna" to 1_050_000,
            "gpt-6-astra" to 1_050_000,
            // ai.google.dev/gemini-api/docs/models/gemini-3.8-flash
            "gemini-3.8-flash" to 1_048_576,
            // platform.minimax.io/docs/guides/text-generation + its plan-endpoint rows
            "MiniMax-M3.1-Flash-Preview" to 1_000_000,
            // mimo.mi.com/models/en-US/mimo-v2.6-pro (+ both id spellings)
            "mimo-v2.6-pro" to 1_048_576,
            "xiaomi/mimo-v2.6-pro" to 1_048_576,
            // models.dev provider `meta` (api https://api.meta.ai/v1)
            "muse-spark-1.3" to 1_048_576,
            // Alibaba pricing page (Model ID in the "0<Token≤1M" tier) + the
            // three catalogs carrying the namespaced id
            "qwen3.8-max-prime" to 1_000_000,
            "qwen/qwen3.8-max-prime" to 1_000_000,
        )
        for ((id, window) in expected) {
            val enriched = ModelsDevApi.enrichModels(listOf(fromRelay(id))).single()
            assertEquals("contextWindowTokens for $id", window, enriched.contextWindowTokens)
            assertTrue("$id must not fall back to the generic 128K floor", window > 128_000)
            assertNotNull("maxOutputTokens for $id must be filled by the template", enriched.maxOutputTokens)
            assertEquals("supportsReasoning for $id", true, enriched.supportsReasoning)
            assertEquals("supportsTools for $id", true, enriched.supportsTools)
            assertEquals("sees images: $id", true, enriched.inputModalities?.contains("image") == true)
            assertEquals(listOf("text"), enriched.outputModalities)
        }
    }

    @Test
    fun `kimi-k2 declares the capabilities its rows agree on and no window`() {
        // The other half of the same investigation: `kimi-k2` was a bare entry
        // with no fields at all. The four relay rows that carry the id disagree on
        // both numbers (128_000×2 / 262_144 / 256_000 and 64_000 / 128_000 /
        // 262_144 / 16_384) and the `moonshotai` provider has no row for it, so
        // the numbers stay unset — but all four agree on the capability half, and
        // that half is now recorded. `false` and `null` both close the
        // `== true` Thinking gate, so the behaviour change is the modality and
        // tool answers, in the conservative (no-vision) direction.
        val k2 = ModelsDevApi.enrichModels(listOf(fromRelay("kimi-k2"))).single()
        assertEquals(false, k2.supportsReasoning)
        assertEquals(true, k2.supportsTools)
        assertEquals(listOf("text"), k2.inputModalities)
        assertEquals(listOf("text"), k2.outputModalities)
        assertNull("no source agrees on an output cap for kimi-k2", k2.maxOutputTokens)
        // Context still answers with the conservative floor rather than a guess.
        assertEquals(128_000, k2.contextWindowTokens)
    }

    // ── 2. Production chain: template fills what the catalog cannot ─────────

    @Test
    fun `enrichModels applies the template when the catalog has no entry`() {
        // No registry injected: loadRegistry() cannot reach disk or the bundled
        // asset without a Context, so enrichModels() runs with "no catalog" —
        // the case the template exists for.
        val enriched = ModelsDevApi.enrichModels(listOf(fromRelay("deepseek-v4-pro"))).single()

        assertEquals(1_000_000, enriched.contextWindowTokens)
        assertEquals(393_216, enriched.maxOutputTokens)
        assertEquals(true, enriched.supportsReasoning)
        assertEquals(true, enriched.supportsTools)
        assertEquals(listOf("text"), enriched.inputModalities)
        // End to end: the value that reaches ContextPolicy is the real window.
        assertEquals(1_000_000, enriched.contextWindowTokens)
    }

    @Test
    fun `enrichModels leaves a model nobody knows alone`() {
        val unknown = fromRelay("relay-house-model-9000")
        val enriched = ModelsDevApi.enrichModels(listOf(unknown)).single()

        assertNull(LLMModel.staticDefaultFor("relay-house-model-9000"))
        assertEquals(unknown, enriched)
        // Conservative floor retained — an unknown model must NOT be upgraded to
        // "modern long context" just because some hot models now are.
        assertEquals(128_000, enriched.contextWindowTokens)
    }

    // ── 3. Precedence: catalog beats template, override beats both ──────────

    @Test
    fun `catalog hit outranks the static template`() {
        // Deliberately different numbers from the template (1_000_000 / 393_216)
        // so a template win cannot masquerade as a catalog win.
        installRegistry(
            """
            {
              "deepseek": {
                "id": "deepseek",
                "name": "DeepSeek",
                "api": "https://api.deepseek.com",
                "models": {
                  "deepseek-v4-pro": {
                    "id": "deepseek-v4-pro",
                    "name": "DeepSeek V4 Pro",
                    "limit": {"context": 999999, "output": 11111},
                    "reasoning": true,
                    "tool_call": true,
                    "modalities": {"input": ["text", "image"], "output": ["text"]}
                  }
                }
              }
            }
            """.trimIndent()
        )

        // provider "Custom" is absent from providerKeyMap, so this exercises the
        // same fallback scan a third-party gateway hits.
        val enriched = ModelsDevApi.enrichModels(listOf(fromRelay("deepseek-v4-pro"))).single()

        assertEquals(999_999, enriched.contextWindow)
        assertEquals(999_999, enriched.contextWindowTokens)
        assertEquals(11_111, enriched.maxOutputTokens)
        assertEquals(listOf("text", "image"), enriched.inputModalities)
    }

    @Test
    fun `user override outranks the catalog and the template`() {
        val templateBacked = LLMModel.staticDefaultFor("deepseek-v4-pro")!!
        val entry = ModelEntry(
            providerInstanceId = "provider",
            baseModel = templateBacked,
            overrides = ModelOverrides(contextWindow = 555_555, maxOutputTokens = 22_222, supportsTools = false),
        )

        assertEquals(555_555, entry.model.contextWindowTokens)
        assertEquals(22_222, entry.model.maxOutputTokens)
        assertEquals(false, entry.model.supportsTools)

        // …and null overrides restore the template value rather than clearing it.
        val inherited = entry.copy(overrides = ModelOverrides())
        assertEquals(1_000_000, inherited.model.contextWindowTokens)
        assertEquals(393_216, inherited.model.maxOutputTokens)
    }

    @Test
    fun `template never overwrites a value the provider already supplied`() {
        val relaySaidSo = LLMModel(
            id = "deepseek-v4-pro",
            displayName = "DeepSeek V4 Pro",
            provider = "Custom",
            contextWindow = 64_000,
            maxOutputTokens = 4_096,
            inputModalities = listOf("text"),
        )

        val enriched = ModelsDevApi.enrichModels(listOf(relaySaidSo)).single()

        assertEquals(64_000, enriched.contextWindowTokens)
        assertEquals(4_096, enriched.maxOutputTokens)
        // The fields the relay left unset are the ones the template fills.
        assertEquals(true, enriched.supportsReasoning)
        assertEquals(true, enriched.supportsTools)
    }

    // ── 4. The new DeepSeek family floor ────────────────────────────────────

    @Test
    fun `unpublished deepseek id keeps the family floor`() {
        // Neither the catalog nor the template knows this id. Every DeepSeek id
        // any source publishes is 1M, so the family floor is 1M; the retired
        // 128K is not what an unpublished DeepSeek id should inherit.
        assertEquals(1_000_000, fromRelay("deepseek-v5-something").contextWindowTokens)
        // Legacy-looking ids too: DeepSeek no longer documents deepseek-chat /
        // deepseek-reasoner, and this app's own bundled catalog lists both at 1M.
        assertEquals(1_000_000, fromRelay("deepseek-chat").contextWindowTokens)
        assertEquals(1_000_000, fromRelay("DeepSeek-Reasoner").contextWindowTokens)
    }

    @Test
    fun `families without a defensible floor still fall back conservatively`() {
        // GLM / Qwen / MiniMax span 128K–1M across one family, so they
        // deliberately have no family branch: an unrecognized id keeps the
        // generic floor instead of inheriting the flagship's window.
        assertEquals(128_000, fromRelay("glm-6-unknown").contextWindowTokens)
        assertEquals(128_000, fromRelay("qwen9-max").contextWindowTokens)
        assertEquals(128_000, fromRelay("MiniMax-M9").contextWindowTokens)
    }

    // ── 5. The template itself: representative values per family ────────────

    @Test
    fun `template matches the published values for the recent lineups`() {
        // id → (context, output, reasoning, tools, has image input)
        val expected = mapOf(
            // models.dev `deepseek` + https://api-docs.deepseek.com/quick_start/pricing
            "deepseek-flash" to Row(1_000_000, 393_216, true, true, true),
            // The pre-V4 names. Both are in the bundled catalog with the same
            // window as the V4 line; `deepseek-chat` is the only DeepSeek id
            // whose `reasoning` is FALSE (V3 non-thinking), so it is also the
            // one row here that pins a capability to "declared absent" rather
            // than "declared present". Output keeps the family's 393_216 (the
            // catalog's 384K) so one family carries one number.
            "deepseek-chat" to Row(1_000_000, 393_216, false, true, false),
            "deepseek-reasoner" to Row(1_000_000, 393_216, true, true, false),
            // models.dev `zai`; GLM-5 line: 200K@5.0/4.x, 1M@5.2+
            "glm-5.3" to Row(1_000_000, 131_072, true, true, false),
            "glm-5.3-flash" to Row(1_000_000, 131_072, true, true, true),
            "glm-4.6" to Row(204_800, 131_072, true, true, false),
            // models.dev `alibaba`
            "qwen3.8-max" to Row(1_000_000, 131_072, true, true, true),
            "qwen3.7-plus" to Row(1_000_000, 131_072, true, true, true),
            "qwen3.8-omni-flash" to Row(1_000_000, 131_072, true, true, true),
            // models.dev `minimax`
            "MiniMax-M3" to Row(1_000_000, 512_000, true, true, true),
            "MiniMax-M2.7" to Row(204_800, 131_072, true, true, false),
            // models.dev `moonshotai`
            "kimi-k3" to Row(1_048_576, 131_072, true, true, true),
            // models.dev `anthropic` (current GA line)
            "claude-opus-5-5" to Row(1_000_000, 128_000, true, true, true),
            "claude-sonnet-5-5" to Row(1_000_000, 128_000, true, true, true),
            "claude-haiku-4-5" to Row(200_000, 64_000, true, true, true),
            // models.dev `google`
            "gemini-3.1-pro-preview" to Row(1_048_576, 65_536, true, true, true),
            // models.dev `openai` — 1.05M, i.e. the `gpt-5` heuristic's 400K is stale
            "gpt-5.5" to Row(1_050_000, 128_000, true, true, true),
            "gpt-5.6-sol" to Row(1_050_000, 128_000, true, true, true),
            "gpt-4o" to Row(128_000, 16_384, false, true, true),
            // models.dev `xai`
            "grok-4.7" to Row(500_000, 500_000, true, true, true),
            "grok-4.3" to Row(1_000_000, 30_000, true, true, true),
            "grok-4.20-0309-non-reasoning" to Row(1_000_000, 30_000, false, true, true),
            "grok-4.20-multi-agent-0309" to Row(1_000_000, 30_000, true, false, true),
            // 4/4 relay rows agree on context 2_000_000; output deliberately
            // unset (they disagree: 2M / 2M / 30_000 / 16_384).
            "grok-4-fast-non-reasoning" to Row(2_000_000, null, false, true, true),
            // gpt-5.1-codex-max: 5/5 relay rows agree (bundled catalog).
            "gpt-5.1-codex-max" to Row(400_000, 128_000, true, true, true),
            // models.dev `openrouter`
            "anthropic/claude-sonnet-4" to Row(200_000, 64_000, true, true, true),
            "meta-llama/llama-4-maverick" to Row(1_048_576, 16_384, false, true, true),

            // ── Second batch of the same fix ────────────────────────────────
            // [R8-recent-model-defaults] The ids below were still missing after
            // the first pass, and every one of them had the identical failure
            // shape: absent from the id heuristic's ladder, so it landed on the
            // generic 128_000 floor against a real 1M+ window, with output,
            // reasoning and modalities left null.
            //
            // GPT-6 family — https://platform.openai.com/docs/models/<id>
            // ("1,050,000 context window", "128,000 max output tokens"), all four
            // ids agreeing, and models.dev's `openai` row agreeing with them.
            "gpt-6.1-sol" to Row(1_050_000, 128_000, true, true, true),
            "gpt-6-sol" to Row(1_050_000, 128_000, true, true, true),
            "gpt-6-luna" to Row(1_050_000, 128_000, true, true, true),
            "gpt-6-astra" to Row(1_050_000, 128_000, true, true, true),
            // Google's own spec page: "Input token limit 1,048,576" / "Output
            // token limit 65,536" / thinking + function calling supported.
            "gemini-3.8-flash" to Row(1_048_576, 65_536, true, true, true),
            // MiniMax's own docs (1M window) + its own plan-endpoint catalog
            // rows (output 512_000, the M3 line's number).
            "MiniMax-M3.1-Flash-Preview" to Row(1_000_000, 512_000, true, true, true),
            // Xiaomi's model page + its own catalog row: 1_048_576 / 131_072.
            // OpenRouter's rounded 1_050_000 is rejected — its own
            // `top_provider.context_length` in the same record says 1_048_576.
            // Both the vendor id and OpenRouter's namespaced id are entries.
            "mimo-v2.6-pro" to Row(1_048_576, 131_072, true, true, true),
            "xiaomi/mimo-v2.6-pro" to Row(1_048_576, 131_072, true, true, true),
            // Meta's own catalog row (131_072 output; the namespaced mirror rows
            // disagree at 943_718 and 1_048_576, so the provider of record wins).
            "muse-spark-1.3" to Row(1_048_576, 131_072, true, true, true),
            // Qwen3.8 Max Prime: window anchored to Alibaba's pricing page (the
            // "0<Token≤1M" tier lists this exact Model ID); output and modalities
            // from the three agreeing catalogs that carry the namespaced id.
            "qwen3.8-max-prime" to Row(1_000_000, 131_072, true, true, true),
            "qwen/qwen3.8-max-prime" to Row(1_000_000, 131_072, true, true, true),
        )

        for ((id, row) in expected) {
            val m = LLMModel.staticDefaultFor(id)
            assertNotNull("no template entry for $id", m)
            assertEquals("contextWindow for $id", row.context, m!!.contextWindow)
            if (row.output == null) {
                assertNull("maxOutputTokens for $id must stay unset", m.maxOutputTokens)
            } else {
                assertEquals("maxOutputTokens for $id", row.output, m.maxOutputTokens)
            }
            assertEquals("supportsReasoning for $id", row.reasoning, m.supportsReasoning)
            assertEquals("supportsTools for $id", row.tools, m.supportsTools)
            assertEquals(
                "image input for $id",
                row.hasImage,
                m.inputModalities?.contains("image") == true,
            )
            assertEquals("contextWindowTokens for $id", row.context, m.contextWindowTokens)
        }
    }

    /** `output == null` means "deliberately left unset" (sources disagree). */
    private data class Row(
        val context: Int,
        val output: Int?,
        val reasoning: Boolean,
        val tools: Boolean,
        val hasImage: Boolean,
    )

    // ── 6. Template hygiene ────────────────────────────────────────────────

    @Test
    fun `template ids are unique across every built-in list`() {
        val ids = LLMModel.allModels.map { it.id.lowercase() }
        val duplicates = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue("duplicate template ids: $duplicates", duplicates.isEmpty())
        assertEquals(ids.size, LLMModel.allModels.size)

        // Every hot-model entry must actually be reachable through the lookup —
        // a list that the index forgot is the "second definition" failure mode.
        for (m in LLMModel.recentModelDefaults) {
            assertEquals(
                "recentModelDefaults entry ${m.id} is not resolvable by id",
                m,
                LLMModel.staticDefaultFor(m.id),
            )
        }
    }

    @Test
    fun `lookup is case-insensitive and never invents an entry`() {
        // Registered under its display-cased id, found from any casing.
        assertEquals("claude-opus-5-5", LLMModel.staticDefaultFor("CLAUDE-OPUS-5-5")?.id)
        assertEquals("MiniMax-M3", LLMModel.staticDefaultFor("minimax-m3")?.id)
        assertEquals("MiniMax-M3", LLMModel.staticDefaultFor("MiniMax-M3")?.id)
        assertNull(LLMModel.staticDefaultFor(""))
        assertNull(LLMModel.staticDefaultFor("no-such-model-anywhere"))
    }

    @Test
    fun `template modality lists are already normalised`() {
        // normalizeModalities() strips "_input"/"_output"; a template spelling
        // like "image_input" would compare unequal everywhere downstream.
        for (m in LLMModel.allModels) {
            for (modality in (m.inputModalities ?: emptyList()) + (m.outputModalities ?: emptyList())) {
                assertEquals(
                    "modality ${m.id}/$modality must be bare",
                    modality.normalizeModalityName(),
                    modality,
                )
            }
        }
    }

    @Test
    fun `template carries an explicit capability instead of leaving it unknown`() {
        // [T-android-model-prompt-fragments] This NOTE used to read
        // "`capabilityPromptFragment()` has NO production caller on Android
        // (only iOS wires it up)". That is no longer true: it is now composed
        // into the system prompt by `systemPromptWithModelFragments()` at the
        // ChatViewModel request site (and in RuntimeChildRunner for delegated
        // children). So the assertion below documents user-visible prompt text,
        // not merely template data. The wiring is pinned by
        // `ui/chat/ModelPromptFragmentWiringSourceTest` and the composed wording
        // by `ModelPromptFragmentsTest`. The other consumer of these fields
        // remains the modality flag `hasImageInput` (native image routing /
        // Vision Group candidacy / `read_image` exposure) — keep them in step.
        val claude = LLMModel.staticDefaultFor("claude-opus-5-5")!!
        val fragment = claude.capabilityPromptFragment() ?: ""
        assertTrue("must claim image support: $fragment", fragment.contains("images"))
        assertFalse("must not deny image support: $fragment", fragment.contains("cannot natively process images"))

        // DeepSeek V4 Pro really is text-only, so it KEEPS the limitation —
        // proving the fix is data, not "everything is multimodal now".
        val deepseekPro = LLMModel.staticDefaultFor("deepseek-v4-pro")!!
        val proFragment = deepseekPro.capabilityPromptFragment() ?: ""
        assertTrue(proFragment.contains("cannot natively process images"))

        // The field the real consumer reads, for the same two models.
        assertEquals(true, claude.inputModalities?.contains("image"))
        assertEquals(false, deepseekPro.inputModalities?.contains("image"))
    }

    @Test
    fun `catalog parse of a synthetic entry still drives enrichment`() {
        // Guards the reflection plumbing above: if parseRegistry's contract
        // changes, this fails loudly instead of silently making every other
        // catalog test meaningless.
        val parsed = parseRegistry(
            """{"p":{"id":"p","name":"P","api":"https://p.example","models":{"m":{"id":"m","limit":{"context":123}}}}}"""
        )
        assertEquals(1, parsed.size)
        assertEquals(123, parsed["p"]!!.models["m"]!!.contextWindow)
    }
}
