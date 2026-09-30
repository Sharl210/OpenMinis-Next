package com.openminis.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class LLMModel(
    val id: String,
    val displayName: String,
    val provider: String,
    val contextWindow: Int? = null,
    val maxOutputTokens: Int? = null,
    val supportsReasoning: Boolean? = null,
    val interleavedReasoningField: String? = null,
    // [T-reasoning-effort-data-driven] Effort tiers this model accepts, from the
    // models.dev `reasoning_options` entry of type `effort` (e.g. ["high","max"]
    // for zhipuai glm-5.2). Mirrors iOS LLMModel.reasoningEffortValues.
    //
    // Presence (non-null, non-empty) means "controlled by reasoning_effort" and
    // replaces the old hardcoded deepseek/glm/kimi/minimax skip list; the
    // contents are the ALLOWED tiers, which the request builder clamps onto
    // (the catalog's sets vary: ["low","medium","high"], ["high","max"], …).
    val reasoningEffortValues: List<String>? = null,
    // [OpenMinis#163] The catalog affirmatively declares NO effort tiers for
    // this model — it reasons, but takes no `reasoning_effort` parameter.
    // Mirrors iOS LLMModel.declaresNoEffortTiers.
    //
    // Distinct from `reasoningEffortValues == null`, which also covers "the
    // catalog has never heard of this model". Only the affirmative case may
    // suppress the field; the unknown case stays permissive so third-party
    // relays keep working.
    //
    // Nullable (not a plain Boolean) so decoding a model persisted before this
    // field existed yields null — "unknown", the pre-existing behaviour —
    // rather than a synthesized `false` that would read as a real answer.
    val declaresNoEffortTiers: Boolean? = null,
    // Input/output modalities from models.dev (e.g. "text", "image", "audio", "video", "pdf").
    // Mirrors iOS ModelModality flags. When null, treat as text-in/text-out only.
    val inputModalities: List<String>? = null,
    val outputModalities: List<String>? = null,
    // models.dev `tool_call` is an explicit nullable declaration. Keep null as
    // "unknown" so older/provider payloads remain permissive instead of being
    // mistaken for a negative capability.
    val supportsTools: Boolean? = null,
) {
    companion object {
        // Anthropic — mirrors iOS LLMTypes.swift allAnthropic.
        // [T-android-claude-opus48-thinking-toggle] (Sow Sow 38845/38850) Every
        // Claude 4.x model supports extended thinking, so hard-stamp
        // supportsReasoning = true (same as the OpenAI gpt-5.x catalog). Without
        // it the built-in fallback list — used when the /v1/models fetch fails
        // on a direct-Anthropic instance — lands with supportsReasoning=null and
        // ChatViewModel's `== true` gate hides the Deep Thinking toggle (the
        // reported Opus 4.8 bug). The dynamic /v1/models path stamps it the same
        // way via AnthropicProvider.supportsThinking.
        // [T-anthropic-fable5-catalog-android] Claude Fable 5 (2026-06-09,
        // first GA Mythos-class model; API id has no dated variant). Context
        // window / max output deliberately unset — 1M ctx is third-party
        // reported, not confirmed on Anthropic's model page; models.dev /
        // dynamic lookup fills them in once catalogued, same as the sibling
        // entries. 5-series adaptive thinking + no-temperature handling
        // comes from parseClaudeVersion (243dadf3). Claude Mythos 5 has no
        // public API id (Project Glasswing) and is intentionally absent.
        // [T-anthropic-context-window] Explicit context/output caps per
        // Anthropic's catalog (mirrors iOS): modern Opus/Sonnet 4.x & 5 and
        // Fable 5 are 1M context; Haiku 4.5 is 200K. Output: 128K (Opus/Fable),
        // 64K (Sonnet/Haiku). Set explicitly so the values don't depend on the
        // id heuristic; models.dev enrich can still override at runtime.
        //
        // [R8-recent-model-defaults] Each entry also carries modalities and
        // tool-call support. Before this they were null, and null is read as
        // "no opinion" by some consumers and as "lacks the capability" by the
        // modality flags (`LLMModel.hasImageInput` → native image routing /
        // Vision Group candidacy / the `read_image` tool), so the built-in
        // fallback list left every Claude looking text-only to the vision
        // plumbing. Every current Claude is text+image+pdf in / text out with
        // tools, per https://models.dev/api.json (provider `anthropic`,
        // 2026-09-30) and the Claude models overview
        // (https://docs.anthropic.com/en/docs/about-claude/models/overview:
        // "All current models support text and image input, text output,
        // multilingual capabilities, vision, and tool use").
        val anthropicVision = listOf("text", "image", "pdf")
        val textOnlyOutput = listOf("text")
        val claudeFable5 = LLMModel("claude-fable-5", "Claude Fable 5", "Anthropic", contextWindow = 1_000_000, maxOutputTokens = 128_000, supportsReasoning = true, inputModalities = anthropicVision, outputModalities = textOnlyOutput, supportsTools = true)
        // [R8-recent-model-defaults] Claude Fable 5.1 / Opus 5.5 / Sonnet 5.5 are
        // the CURRENT GA lineup, per the Claude models overview comparison table
        // (Claude API IDs `claude-fable-5-1`, `claude-opus-5-5`,
        // `claude-sonnet-5-5`; context 1M / max output 128K) and models.dev
        // (`claude-fable-5-1` released 2026-09-01, `claude-opus-5-5`
        // 2026-09-22, `claude-sonnet-5-5` 2026-09-28). The list stopped at the
        // 4.x/5 generation, so an Anthropic instance whose /v1/models fetch
        // fails had no entry for any of them.
        val claudeFable51 = LLMModel("claude-fable-5-1", "Claude Fable 5.1", "Anthropic", contextWindow = 1_000_000, maxOutputTokens = 128_000, supportsReasoning = true, inputModalities = anthropicVision, outputModalities = textOnlyOutput, supportsTools = true)
        val claudeOpus55 = LLMModel("claude-opus-5-5", "Claude Opus 5.5", "Anthropic", contextWindow = 1_000_000, maxOutputTokens = 128_000, supportsReasoning = true, inputModalities = anthropicVision, outputModalities = textOnlyOutput, supportsTools = true)
        val claudeSonnet55 = LLMModel("claude-sonnet-5-5", "Claude Sonnet 5.5", "Anthropic", contextWindow = 1_000_000, maxOutputTokens = 128_000, supportsReasoning = true, inputModalities = anthropicVision, outputModalities = textOnlyOutput, supportsTools = true)
        val claudeOpus48 = LLMModel("claude-opus-4-8", "Claude Opus 4.8", "Anthropic", contextWindow = 1_000_000, maxOutputTokens = 128_000, supportsReasoning = true, inputModalities = anthropicVision, outputModalities = textOnlyOutput, supportsTools = true)
        val claudeOpus46 = LLMModel("claude-opus-4-6", "Claude Opus 4.6", "Anthropic", contextWindow = 1_000_000, maxOutputTokens = 128_000, supportsReasoning = true, inputModalities = anthropicVision, outputModalities = textOnlyOutput, supportsTools = true)
        // [T-anthropic-sonnet5-catalog-android] Claude Sonnet 5 — same 5-series
        // adaptive-thinking + no-temperature handling (parseClaudeVersion) and
        // identical modalities/capabilities as the Sonnet 4.6 entry below.
        //
        // [R8-recent-model-defaults] maxOutputTokens corrected 64_000 → 128_000:
        // both the Claude models overview table and models.dev report 128K for
        // Sonnet, not 64K. Only Haiku is 64K.
        val claudeSonnet5 = LLMModel("claude-sonnet-5", "Claude Sonnet 5", "Anthropic", contextWindow = 1_000_000, maxOutputTokens = 128_000, supportsReasoning = true, inputModalities = anthropicVision, outputModalities = textOnlyOutput, supportsTools = true)
        val claudeSonnet46 = LLMModel("claude-sonnet-4-6", "Claude Sonnet 4.6", "Anthropic", contextWindow = 1_000_000, maxOutputTokens = 128_000, supportsReasoning = true, inputModalities = anthropicVision, outputModalities = textOnlyOutput, supportsTools = true)
        // Haiku 4.5 keeps 200K context / 64K output (overview table + models.dev)
        // and is the only current Claude that does not reach 1M.
        val claudeHaiku45 = LLMModel("claude-haiku-4-5", "Claude Haiku 4.5", "Anthropic", contextWindow = 200_000, maxOutputTokens = 64_000, supportsReasoning = true, inputModalities = anthropicVision, outputModalities = textOnlyOutput, supportsTools = true)

        val allAnthropic = listOf(claudeFable51, claudeOpus55, claudeSonnet55, claudeFable5, claudeOpus48, claudeOpus46, claudeSonnet5, claudeSonnet46, claudeHaiku45)

        // Gemini
        //
        // [R8-recent-model-defaults] Filled in from https://models.dev/api.json
        // (provider `google`, read 2026-09-30). Every one of these carries
        // context 1_048_576 (1M), output 65_536, reasoning true, tool_call true,
        // text out, and the full text/image/video/audio/pdf input set. The
        // entries used to be bare ids, which meant the built-in fallback list
        // left supportsReasoning null (the Deep Thinking pill gates on `== true`
        // and stayed disabled) and inputModalities null — which the modality
        // flags read as "no vision", dropping Gemini out of native image routing
        // and the Vision Group candidate list.
        //
        // gemini-3.1-pro-preview is added for iOS parity — iOS `allGemini`
        // already lists it (Providers/LLMTypes.swift) and it is the current Pro
        // entry in the catalog (released 2026-02-19).
        val geminiFullInput = listOf("text", "image", "video", "audio", "pdf")
        val gemini31Pro = LLMModel("gemini-3.1-pro-preview", "Gemini 3.1 Pro (Preview)", "Google", contextWindow = 1_048_576, maxOutputTokens = 65_536, supportsReasoning = true, inputModalities = geminiFullInput, outputModalities = textOnlyOutput, supportsTools = true)
        val gemini3Pro = LLMModel("gemini-3-pro-preview", "Gemini 3 Pro (Preview)", "Google", contextWindow = 1_048_576, maxOutputTokens = 65_536, supportsReasoning = true, inputModalities = geminiFullInput, outputModalities = textOnlyOutput, supportsTools = true)
        val gemini3Flash = LLMModel("gemini-3-flash-preview", "Gemini 3 Flash (Preview)", "Google", contextWindow = 1_048_576, maxOutputTokens = 65_536, supportsReasoning = true, inputModalities = geminiFullInput, outputModalities = textOnlyOutput, supportsTools = true)
        val gemini25Pro = LLMModel("gemini-2.5-pro", "Gemini 2.5 Pro", "Google", contextWindow = 1_048_576, maxOutputTokens = 65_536, supportsReasoning = true, inputModalities = geminiFullInput, outputModalities = textOnlyOutput, supportsTools = true)
        val gemini25Flash = LLMModel("gemini-2.5-flash", "Gemini 2.5 Flash", "Google", contextWindow = 1_048_576, maxOutputTokens = 65_536, supportsReasoning = true, inputModalities = geminiFullInput, outputModalities = textOnlyOutput, supportsTools = true)
        val gemini25FlashLite = LLMModel("gemini-2.5-flash-lite", "Gemini 2.5 Flash Lite", "Google", contextWindow = 1_048_576, maxOutputTokens = 65_536, supportsReasoning = true, inputModalities = geminiFullInput, outputModalities = textOnlyOutput, supportsTools = true)

        // [R8-recent-model-defaults] gemini-3.8-flash (released 2026-09-02) is the
        // current Flash and was absent from this list, which stops at
        // gemini-3.1-pro / 3-pro / 3-flash-preview. Every value is the vendor's
        // own published number, read from
        // https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash
        // (2026-09-30): "Input token limit 1,048,576", "Output token limit
        // 65,536", "Thinking Supported (low, medium, high)", "Function calling
        // Supported" — identical to what models.dev's `google` row carries for
        // this id. The `gemini` heuristic further down answers 1_000_000 for ANY
        // gemini id, so without this entry a cold catalog under-reported the
        // window by 48_576 and left output/reasoning/modalities unknown (the
        // Thinking pill gates on `== true`, and a null modality list reads as
        // "no vision" at hasImageInput / Vision Group routing).
        val gemini38Flash = LLMModel("gemini-3.8-flash", "Gemini 3.8 Flash", "Google", contextWindow = 1_048_576, maxOutputTokens = 65_536, supportsReasoning = true, inputModalities = geminiFullInput, outputModalities = textOnlyOutput, supportsTools = true)

        val allGemini = listOf(gemini31Pro, gemini38Flash, gemini3Pro, gemini3Flash, gemini25Pro, gemini25Flash, gemini25FlashLite)

        // OpenAI — GPT-5.x and o-series ALWAYS support reasoning
        // (`reasoning_effort` field is required on Codex-OAuth and respected
        // by /v1/responses for these models). Without the explicit
        // `supportsReasoning = true` here the catalog falls back to `null`,
        // ChatViewModel.currentModelSupportsReasoning resolves to `false`,
        // and the Thinking pill in the composer is disabled — the user
        // can't pick high/medium/low even though the provider plumbing
        // honours it. Mirrors iOS LLMTypes.swift defaults plus the
        // OpenAIAgentProvider `supportsReasoning ?? true` GPT-5.x
        // assumption (T119).
        //
        // [R8-recent-model-defaults] Context/output/modalities filled in from
        // https://models.dev/api.json (provider `openai`, read 2026-09-30).
        // The id heuristic below returned 400_000 for every `gpt-5*` id, which
        // is the GPT-5.0 window: the whole GPT-5.4-and-later line is 1_050_000
        // (OpenAI's model pages say "Context window 1.05M" / "Max output 128K" —
        // https://platform.openai.com/docs/models), so the group context slider
        // and ContextPolicy underestimated those models by 2.6x. The exact-id
        // entries below now win over the heuristic.
        //
        // gpt-4o / gpt-4o-mini declare
        // `reasoning: false` in the catalog — stamping that explicitly keeps the
        // template identical to what a catalog hit already produced (Thinking
        // Level OFF instead of "no opinion").
        val openaiVision = listOf("text", "image", "pdf")
        val openaiImageOnly = listOf("text", "image")
        val gpt55 = LLMModel("gpt-5.5", "GPT-5.5", "OpenAI", contextWindow = 1_050_000, maxOutputTokens = 128_000, supportsReasoning = true, inputModalities = openaiVision, outputModalities = textOnlyOutput, supportsTools = true)
        val gpt53Codex = LLMModel("gpt-5.3-codex", "GPT-5.3 Codex", "OpenAI", contextWindow = 400_000, maxOutputTokens = 128_000, supportsReasoning = true, inputModalities = openaiVision, outputModalities = textOnlyOutput, supportsTools = true)
        val gpt52Codex = LLMModel("gpt-5.2-codex", "GPT-5.2 Codex", "OpenAI", contextWindow = 400_000, maxOutputTokens = 128_000, supportsReasoning = true, inputModalities = openaiVision, outputModalities = textOnlyOutput, supportsTools = true)
        // gpt-5.1-codex-max: bundled catalog has 5 rows for it under relay
        // providers, all agreeing (context 400_000, output 128_000, reasoning
        // true, tool_call true, input text+image), so it is filled rather than
        // left to the `gpt-5` heuristic.
        val gpt51CodexMax = LLMModel("gpt-5.1-codex-max", "GPT-5.1 Codex Max", "OpenAI", contextWindow = 400_000, maxOutputTokens = 128_000, supportsReasoning = true, inputModalities = openaiImageOnly, outputModalities = textOnlyOutput, supportsTools = true)
        val gpt52 = LLMModel("gpt-5.2", "GPT-5.2", "OpenAI", contextWindow = 400_000, maxOutputTokens = 128_000, supportsReasoning = true, inputModalities = openaiImageOnly, outputModalities = textOnlyOutput, supportsTools = true)
        val gpt4o = LLMModel("gpt-4o", "GPT-4o", "OpenAI", contextWindow = 128_000, maxOutputTokens = 16_384, supportsReasoning = false, inputModalities = openaiVision, outputModalities = textOnlyOutput, supportsTools = true)
        val gpt4oMini = LLMModel("gpt-4o-mini", "GPT-4o Mini", "OpenAI", contextWindow = 128_000, maxOutputTokens = 16_384, supportsReasoning = false, inputModalities = openaiVision, outputModalities = textOnlyOutput, supportsTools = true)
        val o3 = LLMModel("o3", "o3", "OpenAI", contextWindow = 200_000, maxOutputTokens = 100_000, supportsReasoning = true, inputModalities = openaiVision, outputModalities = textOnlyOutput, supportsTools = true)
        val o4Mini = LLMModel("o4-mini", "o4 Mini", "OpenAI", contextWindow = 200_000, maxOutputTokens = 100_000, supportsReasoning = true, inputModalities = openaiImageOnly, outputModalities = textOnlyOutput, supportsTools = true)
        // codex-mini-latest: absent from models.dev — the `codex` heuristic
        // below (200_000) still covers the window.
        val codexMini = LLMModel("codex-mini-latest", "Codex Mini", "OpenAI", supportsReasoning = true)

        val allOpenAI = listOf(gpt55, gpt53Codex, gpt52Codex, gpt51CodexMax, gpt52, gpt4o, gpt4oMini, o3, o4Mini, codexMini)

        // OpenRouter (matching iOS built-in set)
        //
        // [R8-recent-model-defaults] Filled from https://models.dev/api.json
        // (provider `openrouter`, read 2026-09-30). Two of these were being
        // guessed wrong by the id heuristic and the guess is now gone:
        //   • `anthropic/claude-sonnet-4` — the `claude` branch returned 1_000_000
        //     because the id contains "claude", but OpenRouter serves Sonnet 4 at
        //     200_000. A 5x over-estimate: ContextPolicy's compactThreshold sat
        //     far above the real window, so the request blew up before the app
        //     would ever compact.
        //   • `meta-llama/llama-4-maverick` — fell through to the 128_000 default
        //     against a real 1_048_576 window.
        val orClaudeSonnet4 = LLMModel("anthropic/claude-sonnet-4", "Claude Sonnet 4", "OpenRouter", contextWindow = 200_000, maxOutputTokens = 64_000, supportsReasoning = true, inputModalities = anthropicVision, outputModalities = textOnlyOutput, supportsTools = true)
        // models.dev's openrouter mirror lists 65_535 for this id while the
        // provider-of-record `google/gemini-2.5-flash` entry lists 65_536 — a
        // transcription artifact in the mirror. Use the provider-of-record value.
        val orGemini25Flash = LLMModel("google/gemini-2.5-flash", "Gemini 2.5 Flash", "OpenRouter", contextWindow = 1_048_576, maxOutputTokens = 65_536, supportsReasoning = true, inputModalities = geminiFullInput, outputModalities = textOnlyOutput, supportsTools = true)
        val orGpt4o = LLMModel("openai/gpt-4o", "GPT-4o", "OpenRouter", contextWindow = 128_000, maxOutputTokens = 16_384, supportsReasoning = false, inputModalities = openaiVision, outputModalities = textOnlyOutput, supportsTools = true)
        val orLlamaMaverick = LLMModel("meta-llama/llama-4-maverick", "Llama 4 Maverick", "OpenRouter", contextWindow = 1_048_576, maxOutputTokens = 16_384, supportsReasoning = false, inputModalities = openaiImageOnly, outputModalities = textOnlyOutput, supportsTools = true)

        val allOpenRouter = listOf(orClaudeSonnet4, orGemini25Flash, orGpt4o, orLlamaMaverick)

        // xAI (Grok) — OAuth-only path uses these as the built-in catalog.
        // Source of truth = XAIModelsAPI; this list is what surfaces in the
        // Add Provider → Models step before any models-cache call.
        //
        // Catalog ordering = default-pick order. grok-4.3 stays the
        // flagship on top. T-xai-models-refresh dropped grok-3-* slugs
        // (xAI server-side now redirects those to grok-4.3, so showing
        // them in the picker is just noise) and added the multi-agent
        // / build / fast / code-fast variants surfaced by xAI docs and
        // OpenClaw's catalog (port iOS db973552).
        // Official xAI catalog (docs.x.ai/docs/models) - synced from CLIProxyAPI models.json
        // [T-provider-dynamic-catalog-reconcile] grok-4.6 added for GH#265.
        // Note this list is now a SEED/FALLBACK, not the whole story: since
        // that fix refreshModels does a real GET /v1/models against api.x.ai,
        // so a model released after this build still shows up. Keeping the
        // list current only improves the pre-network first paint.
        //
        // [R8-recent-model-defaults] Capability values filled from
        // https://models.dev/api.json (provider `xai`, read 2026-09-30), which
        // agrees with xAI's own model page
        // (https://docs.x.ai/docs/models — "grok-4.7 ... Context 500k tokens").
        // The `grok` heuristic below floors every non-2/3 Grok at 256_000, which
        // under-reported grok-4.3/4.20 (1_000_000) and grok-4.5/4.6 (500_000).
        // Two flags were also wrong in the seed list and are now explicit:
        //   • grok-4.20-0309-non-reasoning — reasoning false (was null, i.e.
        //     "no opinion", so the Thinking pill's state was guesswork);
        //   • grok-4.20-multi-agent-0309 — tool_call false in the catalog.
        // grok-4.7 (released 2026-09-21) is added on top: xAI's docs call it
        // "Our flagship model", so the seed list was a release behind.
        val grokVision = listOf("text", "image", "pdf")
        val grok47 = LLMModel("grok-4.7", "Grok 4.7", "xAI", contextWindow = 500_000, maxOutputTokens = 500_000, supportsReasoning = true, inputModalities = grokVision, outputModalities = textOnlyOutput, supportsTools = true)
        val grok46 = LLMModel("grok-4.6", "Grok 4.6", "xAI", contextWindow = 500_000, maxOutputTokens = 500_000, supportsReasoning = true, inputModalities = grokVision, outputModalities = textOnlyOutput, supportsTools = true)
        val grok45 = LLMModel("grok-4.5", "Grok 4.5", "xAI", contextWindow = 500_000, maxOutputTokens = 500_000, supportsReasoning = true, inputModalities = grokVision, outputModalities = textOnlyOutput, supportsTools = true)
        val grok43 = LLMModel("grok-4.3", "Grok 4.3", "xAI", contextWindow = 1_000_000, maxOutputTokens = 30_000, supportsReasoning = true, inputModalities = grokVision, outputModalities = textOnlyOutput, supportsTools = true)
        val grok420Reasoning = LLMModel("grok-4.20-0309-reasoning", "Grok 4.20 Reasoning", "xAI", contextWindow = 1_000_000, maxOutputTokens = 30_000, supportsReasoning = true, inputModalities = grokVision, outputModalities = textOnlyOutput, supportsTools = true)
        val grok420NonReasoning = LLMModel("grok-4.20-0309-non-reasoning", "Grok 4.20", "xAI", contextWindow = 1_000_000, maxOutputTokens = 30_000, supportsReasoning = false, inputModalities = grokVision, outputModalities = textOnlyOutput, supportsTools = true)
        val grok420MultiAgent = LLMModel("grok-4.20-multi-agent-0309", "Grok 4.20 Multi-Agent", "xAI", contextWindow = 1_000_000, maxOutputTokens = 30_000, supportsReasoning = true, inputModalities = grokVision, outputModalities = textOnlyOutput, supportsTools = false)
        val grokBuild01 = LLMModel("grok-build-0.1", "Grok Build 0.1", "xAI", contextWindow = 256_000, maxOutputTokens = 256_000, supportsReasoning = true, inputModalities = grokVision, outputModalities = textOnlyOutput, supportsTools = true)
        // The ids below have NO `xai` row in either catalog, so they are resolved
        // through `enrichModels`' scan-ALL-providers step (relay mirrors carry
        // several of them). Filling only the values those mirrors agree on:
        //   • grok-4-fast-non-reasoning — 4/4 rows at context 2_000_000 (and xAI
        //     documents the fast line as 2M), which is 7.8x above the 256_000
        //     `grok` heuristic floor. That gap is the exact "compacts every 20-30
        //     tool calls" failure the floor's own comment describes, so this id
        //     was still paying it before the template existed. reasoning false is
        //     4/4 and matches the id. Output is left unset: the rows disagree
        //     (2_000_000 / 2_000_000 / 30_000 / 16_384).
        //   • grok-3-mini (1 row), grok-code-fast-1 (4 rows) — context matches the
        //     heuristic floor exactly (131_072 / 256_000), so there is nothing to
        //     add; their `reasoning` rows contradict the id's known capability
        //     (3 of 4 say false for a reasoning code model), so that flag stays
        //     as-is rather than being flipped on thin evidence.
        //   • grok-3-mini-fast, grok-composer-2.5-fast, grok-4-fast — 0 rows
        //     anywhere. Left unset on purpose; guessing a window for an id
        //     nothing publishes is exactly the failure this change fixes.
        val grok4FastNonReasoning = LLMModel("grok-4-fast-non-reasoning", "Grok 4 Fast (Non-Reasoning)", "xAI", contextWindow = 2_000_000, supportsReasoning = false, inputModalities = openaiImageOnly, outputModalities = textOnlyOutput, supportsTools = true)
        val grok3Mini = LLMModel("grok-3-mini", "Grok 3 Mini", "xAI", supportsReasoning = true)
        val grok3MiniFast = LLMModel("grok-3-mini-fast", "Grok 3 Mini Fast", "xAI", supportsReasoning = true)
        val grokComposer25Fast = LLMModel("grok-composer-2.5-fast", "Grok Composer 2.5 Fast", "xAI")
        // High-frequency fast / code variants surfaced by OpenClaw's catalog.
        val grok4Fast = LLMModel("grok-4-fast", "Grok 4 Fast", "xAI", supportsReasoning = true)
        val grokCodeFast1 = LLMModel("grok-code-fast-1", "Grok Code Fast 1", "xAI", supportsReasoning = true)

        val allXAI = listOf(
            grok47,
            grok46,
            grok45,
            grok43,
            grok420Reasoning,
            grok420NonReasoning,
            grok420MultiAgent,
            grokBuild01,
            grok3Mini,
            grok3MiniFast,
            grokComposer25Fast,
            grok4Fast,
            grok4FastNonReasoning,
            grokCodeFast1,
        )

        // [T-kimi-oauth] Kimi Code (Coding Plan) built-in fallback — deliberately
        // minimal and non-speculative (iOS parity): only confirmed current-gen
        // models. kimi-k3 verified present as the first entry of a live
        // GET /coding/v1/models fetch (2026-07-23). The real catalog replaces
        // this via refreshModels after login — the upstream lineup shifts
        // (K2 → K3 → …), so we never hand-author a "complete" list.
        //
        // [R8-recent-model-defaults] Kimi K3 filled from
        // https://models.dev/api.json (provider `moonshotai`, read 2026-09-30):
        // context 1_048_576, reasoning true, tool_call true, text/image/video in,
        // text out. The bundled copy of that same catalog agrees on the context
        // and disagrees on the output (131_072 bundled vs 1_048_576 live); the
        // SHIPPED value is used so a catalog hit and a template fill cannot
        // disagree. The difference is inert either way — ChatViewModel clamps
        // max_tokens to GLOBAL_MAX_TOKENS_CEILING (128_000) — so this picks
        // consistency over the larger number. Moonshot's own docs do not publish
        // a window for this id in a form this app can cite, so models.dev is the
        // only source and both fields are that single source's numbers.
        //
        // kimi-k2 stays bare on purpose: the `moonshotai` provider has no
        // `kimi-k2` row in either catalog (only k2.6 / k2.7-code / the dated k2
        // previews), and the four relay rows that do carry the id DISAGREE on
        // the window (128_000 ×2, 262_144, 256_000). Picking one of three
        // candidate values off relay mirrors would be a guess, so the generic
        // 128K floor still applies. Guessing is the bug this change fixes.
        val kimiVision = listOf("text", "image", "video")
        val kimiK3 = LLMModel("kimi-k3", "Kimi K3", "Kimi", contextWindow = 1_048_576, maxOutputTokens = 131_072, supportsReasoning = true, inputModalities = kimiVision, outputModalities = textOnlyOutput, supportsTools = true)
        // [R8-recent-model-defaults] `kimi-k2` keeps BOTH numeric fields unset —
        // the four relay rows that carry the id disagree on the window
        // (128_000 ×2, 262_144, 256_000) and on the output
        // (64_000, 128_000, 262_144, 16_384), and the `moonshotai` provider has
        // no row for it at all, so any number here would be a pick, not a value.
        //
        // The four rows DO agree unanimously on everything that is not a number,
        // and those answers are recorded here instead of being left null:
        //   • reasoning false — 4/4. Not a contradiction of the id the way the
        //     grok-3-mini rows were: K2's thinking variant ships as its own id
        //     (`kimi-k2-thinking`), so the base id being non-thinking is the
        //     expected shape. `false` and `null` behave identically at the
        //     `== true` Thinking gate, so this records a known answer rather
        //     than changing behaviour.
        //   • tool_call true — 4/4.
        //   • text in / text out — 4/4. This is the conservative direction: it
        //     claims no vision, so nothing new can be sent natively.
        val kimiK2 = LLMModel("kimi-k2", "Kimi K2", "Kimi", supportsReasoning = false, inputModalities = listOf("text"), outputModalities = textOnlyOutput, supportsTools = true)

        val allKimi = listOf(kimiK3, kimiK2)

        // ─────────────────────────────────────────────────────────────────────
        // [R8-recent-model-defaults] Default parameter template for recently
        // released models that the provider-level lists above cannot carry:
        // DeepSeek, GLM (Z.ai), Qwen (Alibaba) and MiniMax have no
        // `ProviderType` on Android — users reach them as custom OpenAI- or
        // Anthropic-compatible endpoints — so before this block their ONLY
        // source of capability data was a models.dev catalog hit. With the
        // catalog unavailable (or the id not yet catalogued) they fell through
        // to the id heuristics in `contextWindowTokens`, and the only DeepSeek
        // branch there said 128_000 — the context length of the retired V3/R1
        // generation. That is the "DeepSeek still stuck at 128K" report.
        //
        // Values below are transcribed from https://models.dev/api.json
        // (read 2026-09-30) and cross-checked against each vendor's own docs:
        //   • DeepSeek — https://api-docs.deepseek.com/quick_start/pricing
        //     ("CONTEXT LENGTH 1M", "MAX OUTPUT MAXIMUM: 384K", thinking mode,
        //     "Tool Calls ✓"; Vision ✓ only for deepseek-flash)
        //   • GLM — https://docs.z.ai/guides/llm/glm-5 (GLM-5 "Context Length
        //     200K / Maximum Output Tokens 128K") and /guides/llm/glm-5.3
        //   • Qwen — https://www.alibabacloud.com/help/en/model-studio/models
        //   • MiniMax — https://platform.minimax.io/docs ("MiniMax-M3
        //     1,000,000 ... 1M context window")
        //
        // 393_216 is 384 × 1024, i.e. DeepSeek's "384K" expressed the way
        // models.dev expresses it. The bundled catalog still says 384_000 for
        // the same ids; a catalog hit keeps winning (see applyDevData), so the
        // template value only shows while the catalog is cold.
        //
        // The GPT-5.6 trio rides here too because `allOpenAI` deliberately
        // excludes it (Codex-OAuth-only, see OpenAIModelsApi.fetchModelsOAuth):
        // without an entry here every gpt-5.6 id fell to the `gpt-5` heuristic's
        // 400_000 against a real 1_050_000 window.
        // ─────────────────────────────────────────────────────────────────────

        // DeepSeek. Official docs list exactly two models — `deepseek-flash`
        // (DeepSeek-V4.1-Flash) and `deepseek-v4-pro` (DeepSeek-V4-Pro-0813) —
        // both 1M context / 384K max output / thinking mode / tool calls. The
        // legacy names `deepseek-v4-flash` and `deepseek-v4-flash-vision-exp`
        // are "still accepted, but the corresponding models have been retired,
        // their requests are served by the DeepSeek-V4.1-Flash model", so they
        // carry the Flash capabilities rather than any 128K-era window.
        //
        // Image input is declared ONLY where the evidence agrees:
        //   • `deepseek-flash` — docs say "Vision ✓" for the Flash tier
        //     (`deepseek-v4-pro` is "Not supported"), and the live models.dev
        //     entry lists modalities.input ["text","image"].
        //   • `deepseek-v4-flash-vision-exp` — the id itself is the vision
        //     experiment, and both bundled rows for it declare text+image.
        //   • `deepseek-v4-flash` — deliberately TEXT-ONLY. All 23 bundled
        //     catalog rows for this id declare input ["text"], and the live
        //     entry for it is `status: deprecated` (mapped to the V4.1-Flash
        //     canonical id). Declaring image here would make the app send
        //     screenshots natively to endpoints whose catalog entry says they
        //     cannot take them — a 400, versus the harmless degradations of the
        //     opposite error (vision routed through the Vision Group instead).
        //     The asymmetry decides it: when sources disagree, do not claim the
        //     capability.
        val deepseekFlash = LLMModel("deepseek-flash", "DeepSeek V4.1 Flash", "DeepSeek", contextWindow = 1_000_000, maxOutputTokens = 393_216, supportsReasoning = true, inputModalities = openaiImageOnly, outputModalities = textOnlyOutput, supportsTools = true)
        val deepseekV4Pro = LLMModel("deepseek-v4-pro", "DeepSeek V4 Pro", "DeepSeek", contextWindow = 1_000_000, maxOutputTokens = 393_216, supportsReasoning = true, inputModalities = listOf("text"), outputModalities = textOnlyOutput, supportsTools = true)
        val deepseekV4Flash = LLMModel("deepseek-v4-flash", "DeepSeek V4 Flash", "DeepSeek", contextWindow = 1_000_000, maxOutputTokens = 393_216, supportsReasoning = true, inputModalities = listOf("text"), outputModalities = textOnlyOutput, supportsTools = true)
        val deepseekV4FlashVisionExp = LLMModel("deepseek-v4-flash-vision-exp", "DeepSeek V4 Flash Vision Exp", "DeepSeek", contextWindow = 1_000_000, maxOutputTokens = 393_216, supportsReasoning = true, inputModalities = openaiImageOnly, outputModalities = textOnlyOutput, supportsTools = true)

        // The two legacy names DeepSeek still serves — `deepseek-chat` (V3
        // non-thinking) and `deepseek-reasoner` (the R-series reasoner). These
        // are the half of the DeepSeek fix that was missing: the family floor
        // further down answers CONTEXT for an otherwise-unknown deepseek id,
        // but nothing answered `supportsReasoning` / `maxOutputTokens` /
        // `inputModalities`. An unregistered id fell through with all three
        // `null`, and `null` reads as "thinking off" at
        // `ChatViewModel.currentModelSupportsReasoning` (a `== true` gate) and
        // as the 16_384 provider fallback for output.
        //
        // Both ids are in the bundled catalog (models.dev provider `deepseek`,
        // read 2026-09-30), so the values below are transcribed, not inferred:
        //   • `deepseek-chat`     — 1M ctx / reasoning FALSE / tools ✓ / text
        //   • `deepseek-reasoner` — 1M ctx / reasoning TRUE  / tools ✓ / text
        //
        // `supportsReasoning = false` on `deepseek-chat` is copied verbatim: the
        // catalog positively declares `reasoning: false`. That is a different
        // statement from `null` ("never looked") even though both close the
        // `== true` gate — it records a known answer instead of an unknown one.
        //
        // `maxOutputTokens` keeps the family's 393_216 for the catalog's 384_000
        // (the 384 × 1024 note above) so one family carries one number, and a
        // catalog hit still wins whenever the catalog is warm.
        val deepseekChat = LLMModel("deepseek-chat", "DeepSeek Chat", "DeepSeek", contextWindow = 1_000_000, maxOutputTokens = 393_216, supportsReasoning = false, inputModalities = listOf("text"), outputModalities = textOnlyOutput, supportsTools = true)
        val deepseekReasoner = LLMModel("deepseek-reasoner", "DeepSeek Reasoner", "DeepSeek", contextWindow = 1_000_000, maxOutputTokens = 393_216, supportsReasoning = true, inputModalities = listOf("text"), outputModalities = textOnlyOutput, supportsTools = true)

        val allDeepSeek = listOf(deepseekFlash, deepseekV4Pro, deepseekV4Flash, deepseekV4FlashVisionExp, deepseekChat, deepseekReasoner)

        // GLM (Z.ai / Zhipu). GLM-5.x split into a 1M-context flagship line
        // (5.2 / 5.3) and the 200K 5.0 / 4.x line — which is why the family has
        // no single "GLM floor" and why these ids are enumerated instead.
        val glm53 = LLMModel("glm-5.3", "GLM-5.3", "Z.ai", contextWindow = 1_000_000, maxOutputTokens = 131_072, supportsReasoning = true, inputModalities = listOf("text"), outputModalities = textOnlyOutput, supportsTools = true)
        val glm53Flash = LLMModel("glm-5.3-flash", "GLM-5.3 Flash", "Z.ai", contextWindow = 1_000_000, maxOutputTokens = 131_072, supportsReasoning = true, inputModalities = listOf("text", "image", "video", "pdf"), outputModalities = textOnlyOutput, supportsTools = true)
        val glm52 = LLMModel("glm-5.2", "GLM-5.2", "Z.ai", contextWindow = 1_000_000, maxOutputTokens = 131_072, supportsReasoning = true, inputModalities = listOf("text"), outputModalities = textOnlyOutput, supportsTools = true)
        val glm47 = LLMModel("glm-4.7", "GLM-4.7", "Z.ai", contextWindow = 204_800, maxOutputTokens = 131_072, supportsReasoning = true, inputModalities = listOf("text"), outputModalities = textOnlyOutput, supportsTools = true)
        val glm46 = LLMModel("glm-4.6", "GLM-4.6", "Z.ai", contextWindow = 204_800, maxOutputTokens = 131_072, supportsReasoning = true, inputModalities = listOf("text"), outputModalities = textOnlyOutput, supportsTools = true)

        val allGlm = listOf(glm53, glm53Flash, glm52, glm47, glm46)

        // Qwen (Alibaba Model Studio). The 3.6/3.7/3.8 generation is 1M across
        // the max / plus / flash tiers; `qwen3.8-omni-flash` is the multimodal
        // one (audio in, still text out).
        val qwen38Max = LLMModel("qwen3.8-max", "Qwen3.8 Max", "Alibaba", contextWindow = 1_000_000, maxOutputTokens = 131_072, supportsReasoning = true, inputModalities = listOf("text", "image", "video", "pdf"), outputModalities = textOnlyOutput, supportsTools = true)
        val qwen37Max = LLMModel("qwen3.7-max", "Qwen3.7 Max", "Alibaba", contextWindow = 1_000_000, maxOutputTokens = 131_072, supportsReasoning = true, inputModalities = listOf("text"), outputModalities = textOnlyOutput, supportsTools = true)
        val qwen37Plus = LLMModel("qwen3.7-plus", "Qwen3.7 Plus", "Alibaba", contextWindow = 1_000_000, maxOutputTokens = 131_072, supportsReasoning = true, inputModalities = listOf("text", "image", "video"), outputModalities = textOnlyOutput, supportsTools = true)
        val qwen36Plus = LLMModel("qwen3.6-plus", "Qwen3.6 Plus", "Alibaba", contextWindow = 1_000_000, maxOutputTokens = 65_536, supportsReasoning = true, inputModalities = listOf("text", "image", "video"), outputModalities = textOnlyOutput, supportsTools = true)
        val qwen38OmniFlash = LLMModel("qwen3.8-omni-flash", "Qwen3.8 Omni Flash", "Alibaba", contextWindow = 1_000_000, maxOutputTokens = 131_072, supportsReasoning = true, inputModalities = listOf("text", "image", "audio", "video"), outputModalities = textOnlyOutput, supportsTools = true)

        // [R8-recent-model-defaults] Qwen3.8 Max Prime (2026-09-23) — the
        // accelerated "prime" SKU of the 3.8 Max generation. Alibaba has no
        // `ProviderType` on Android either, so this is a custom-relay family.
        //
        // Honest sourcing, because it is weaker than the entries above: Alibaba
        // publishes NO dedicated spec page for this SKU (模型总览 lists only
        // qwen3.8-max / qwen3.7-plus / qwen3.8-flash / qwen3.8-omni-flash; the
        // prime doc URL 404s). What IS on a vendor page is the token tier —
        // https://www.alibabacloud.com/help/en/model-studio/model-pricing lists
        // `qwen3.8-max-prime` as a Model ID and prices it in the "0<Token≤1M"
        // tier, i.e. a 1M-token window, and the base model's own spec page gives
        // Context 1_000_000 / Max Output 131_072 for the same generation.
        // models.dev carries no `alibaba` row for it either — only the
        // namespaced `qwen/qwen3.8-max-prime`, where three catalogs agree
        // exactly (nano-gpt, kilo, openrouter: context 1_000_000, output
        // 131_072, reasoning true, tool_call true, text+image+video in).
        // So: window is vendor-anchored, output comes from those three agreeing
        // catalogs (inert anyway — GLOBAL_MAX_TOKENS_CEILING clamps to 128_000),
        // and modalities are the intersection of the three (the base model's
        // page adds pdf; the prime rows do not, and pdf is the claim that would
        // send a document natively, so it is not declared here).
        //
        // Both spellings are listed: the vendor's own id (what a relay pointed
        // at Model Studio echoes) and OpenRouter's namespaced form (what a relay
        // mirroring OpenRouter's catalog echoes). `staticDefaultFor` is a plain
        // id→model string map with no namespace handling, so each spelling has
        // to be an entry of its own to be found at all.
        val qwen38MaxPrime = LLMModel("qwen3.8-max-prime", "Qwen3.8 Max Prime", "Alibaba", contextWindow = 1_000_000, maxOutputTokens = 131_072, supportsReasoning = true, inputModalities = listOf("text", "image", "video"), outputModalities = textOnlyOutput, supportsTools = true)
        val orQwen38MaxPrime = LLMModel("qwen/qwen3.8-max-prime", "Qwen3.8 Max Prime", "OpenRouter", contextWindow = 1_000_000, maxOutputTokens = 131_072, supportsReasoning = true, inputModalities = listOf("text", "image", "video"), outputModalities = textOnlyOutput, supportsTools = true)

        val allQwen = listOf(qwen38Max, qwen37Max, qwen37Plus, qwen36Plus, qwen38OmniFlash, qwen38MaxPrime, orQwen38MaxPrime)

        // MiniMax (minimax.io). M3 is the current multimodal flagship; the M2.x
        // line is text-only at 204_800 / 131_072.
        val minimaxM3 = LLMModel("MiniMax-M3", "MiniMax M3", "MiniMax", contextWindow = 1_000_000, maxOutputTokens = 512_000, supportsReasoning = true, inputModalities = listOf("text", "image", "video"), outputModalities = textOnlyOutput, supportsTools = true)
        val minimaxM27 = LLMModel("MiniMax-M2.7", "MiniMax M2.7", "MiniMax", contextWindow = 204_800, maxOutputTokens = 131_072, supportsReasoning = true, inputModalities = listOf("text"), outputModalities = textOnlyOutput, supportsTools = true)

        // [R8-recent-model-defaults] MiniMax-M3.1-Flash-Preview (2026-09-27) is
        // the newest MiniMax id and the reason this family list needed
        // revisiting. MiniMax has no `ProviderType` on Android (users reach it
        // as a custom OpenAI-/Anthropic-compatible endpoint), so before this
        // entry the id had exactly one source of capability data — a models.dev
        // hit — and with the catalog cold it fell through to the generic 128_000
        // floor (this file's heuristic deliberately has NO MiniMax branch)
        // against a real 1_000_000 window: an 8x underestimate, i.e. compaction
        // at roughly an eighth of the conversation.
        //
        // Values and sources:
        //   • context 1_000_000 — MiniMax's own docs, "MiniMax-M3.1-Flash-Preview
        //     1,000,000 ... 1M context window"
        //     (https://platform.minimax.io/docs/guides/text-generation), which
        //     also states the id is served only through M Plan / MiniMax Code.
        //     models.dev agrees, but ONLY under MiniMax's own plan endpoints —
        //     providers `minimax-coding-plan` (api https://api.minimax.io/…)
        //     and `minimax-cn-coding-plan` (api https://api.minimax.cn/…),
        //     two rows both at 1_000_000 / 512_000 — and not yet under the base
        //     `minimax` row.
        //   • output 512_000 — the two vendor plan rows above. MiniMax's docs do
        //     not publish a max-output number for this id, so this is the
        //     vendor's own catalog entry rather than a relay mirror; it also
        //     keeps one number for the M3 line. Inert either way: ChatViewModel
        //     clamps every request to GLOBAL_MAX_TOKENS_CEILING (128_000).
        //   • reasoning true / tool_call true / text+image+video in — both rows.
        val minimaxM31FlashPreview = LLMModel("MiniMax-M3.1-Flash-Preview", "MiniMax M3.1 Flash Preview", "MiniMax", contextWindow = 1_000_000, maxOutputTokens = 512_000, supportsReasoning = true, inputModalities = listOf("text", "image", "video"), outputModalities = textOnlyOutput, supportsTools = true)

        val allMiniMax = listOf(minimaxM3, minimaxM27, minimaxM31FlashPreview)

        // GPT-5.6 family — Codex-OAuth-only ids (see OpenAIModelsApi), listed
        // here for their capability values, NOT added to `allOpenAI`.
        val gpt56Sol = LLMModel("gpt-5.6-sol", "GPT-5.6 Sol", "OpenAI", contextWindow = 1_050_000, maxOutputTokens = 128_000, supportsReasoning = true, inputModalities = openaiVision, outputModalities = textOnlyOutput, supportsTools = true)
        val gpt56Terra = LLMModel("gpt-5.6-terra", "GPT-5.6 Terra", "OpenAI", contextWindow = 1_050_000, maxOutputTokens = 128_000, supportsReasoning = true, inputModalities = openaiVision, outputModalities = textOnlyOutput, supportsTools = true)
        val gpt56Luna = LLMModel("gpt-5.6-luna", "GPT-5.6 Luna", "OpenAI", contextWindow = 1_050_000, maxOutputTokens = 128_000, supportsReasoning = true, inputModalities = openaiVision, outputModalities = textOnlyOutput, supportsTools = true)

        val allCodexOAuthDefaults = listOf(gpt56Sol, gpt56Terra, gpt56Luna)

        // ── GPT-6 family ─────────────────────────────────────────────────────
        // [R8-recent-model-defaults] Same shape of gap as the GPT-5.6 trio right
        // above, one release later: none of these four ids is in `allOpenAI`, and
        // OpenAIModelsApi.fetchModelsOAuth() still stops at gpt-5.6-*, so with
        // the catalog cold `gpt-6-sol` matched no heuristic branch at all
        // (the ladder checks gpt-3.5 / gpt-4o / gpt-5 / gpt-4 / o3 / codex) and
        // landed on the generic 128_000 floor against a real 1_050_000 window —
        // an 8.2x underestimate that makes ContextPolicy compact far too early.
        // Output, reasoning and modalities were `null` on the same path.
        //
        // Every value below is OpenAI's own published number, read from
        // https://platform.openai.com/docs/models/<id> (2026-09-30):
        //   • "1,050,000 context window" and "128,000 max output tokens" on
        //     gpt-6-sol, gpt-6.1-sol, gpt-6-luna and gpt-6-astra (all four agree);
        //   • reasoning — true for all four. Two of them cannot be turned OFF at
        //     all ("The none and minimal reasoning efforts are not supported" on
        //     gpt-6.1-sol and gpt-6-astra); that distinction lives in the effort
        //     tiers the catalog carries, not in `supportsReasoning`.
        // models.dev's `openai` row agrees on all four ids (1_050_000 / 128_000 /
        // reasoning true / tool_call true).
        //
        // `openaiVision` (text+image+pdf) is the family constant the GPT-5.x and
        // GPT-5.6 entries above already use, and models.dev's `openai` rows list
        // exactly those three. Recorded for the record rather than hidden: the
        // platform doc pages summarize "Input Text, Image" without pdf, so pdf is
        // a catalog-level claim shared with the rest of this family. It was not
        // narrowed here because it is the same claim the sibling entries make,
        // and a per-entry divergence inside one family is its own bug.
        val gpt61Sol = LLMModel("gpt-6.1-sol", "GPT-6.1 Sol", "OpenAI", contextWindow = 1_050_000, maxOutputTokens = 128_000, supportsReasoning = true, inputModalities = openaiVision, outputModalities = textOnlyOutput, supportsTools = true)
        val gpt6Sol = LLMModel("gpt-6-sol", "GPT-6 Sol", "OpenAI", contextWindow = 1_050_000, maxOutputTokens = 128_000, supportsReasoning = true, inputModalities = openaiVision, outputModalities = textOnlyOutput, supportsTools = true)
        val gpt6Luna = LLMModel("gpt-6-luna", "GPT-6 Luna", "OpenAI", contextWindow = 1_050_000, maxOutputTokens = 128_000, supportsReasoning = true, inputModalities = openaiVision, outputModalities = textOnlyOutput, supportsTools = true)
        val gpt6Astra = LLMModel("gpt-6-astra", "GPT-6 Astra", "OpenAI", contextWindow = 1_050_000, maxOutputTokens = 128_000, supportsReasoning = true, inputModalities = openaiVision, outputModalities = textOnlyOutput, supportsTools = true)

        val allGpt6Defaults = listOf(gpt61Sol, gpt6Sol, gpt6Luna, gpt6Astra)

        // ── Xiaomi MiMo ──────────────────────────────────────────────────────
        // [R8-recent-model-defaults] Xiaomi has no `ProviderType` on Android, so
        // MiMo ids arrive over a custom relay. `mimo-v2.6-pro` (2026-09-22) is
        // the current Pro: Xiaomi's own model page publishes "Context Window 1M
        // tokens" / "Max Output 128K tokens" / input "Text, Image, Video, Audio"
        // / "Deep Thinking" / "Tool Call"
        // (https://mimo.mi.com/models/en-US/mimo-v2.6-pro), and models.dev's
        // `xiaomi` provider row (api https://api.xiaomimimo.com/v1 — the
        // vendor's own endpoint) states 1_048_576 / 131_072 for it.
        //
        // 1_048_576, not 1_050_000: OpenRouter's model card rounds this id up to
        // `context_length: 1050000` while its OWN `top_provider.context_length`
        // in the same record says 1_048_576, and six other provider rows
        // (nano-gpt, kilo, zenmux, vercel, crossmodel, llmgateway) plus Xiaomi's
        // own row say 1_048_576. Only OpenAI's own pages document 1_050_000, and
        // only for OpenAI's ids.
        //
        // Both spellings are listed on purpose: the vendor's id and OpenRouter's
        // namespaced form are two distinct strings that each reach a real user,
        // and `staticDefaultFor` is a plain id→model map with no namespace
        // stripping. Without the namespaced entry a relay echoing OpenRouter's
        // catalog handed this model the 128_000 generic floor — the same 8.2x
        // underestimate.
        val mimoModalities = listOf("text", "image", "audio", "video")
        val mimoV26Pro = LLMModel("mimo-v2.6-pro", "MiMo V2.6 Pro", "Xiaomi", contextWindow = 1_048_576, maxOutputTokens = 131_072, supportsReasoning = true, inputModalities = mimoModalities, outputModalities = textOnlyOutput, supportsTools = true)
        val orMimoV26Pro = LLMModel("xiaomi/mimo-v2.6-pro", "MiMo V2.6 Pro", "OpenRouter", contextWindow = 1_048_576, maxOutputTokens = 131_072, supportsReasoning = true, inputModalities = mimoModalities, outputModalities = textOnlyOutput, supportsTools = true)

        val allXiaomiDefaults = listOf(mimoV26Pro, orMimoV26Pro)

        // ── Meta Muse Spark ──────────────────────────────────────────────────
        // [R8-recent-model-defaults] Meta likewise has no `ProviderType` here.
        // `muse-spark-1.3` (2026-09-02) is the current one; Meta's own page
        // publishes the 1M window and models.dev's `meta` provider row (api
        // https://api.meta.ai/v1) gives 1_048_576 / 131_072 with reasoning and
        // tool_call true. The four mirror rows that carry the bare id agree with
        // the vendor row on all of those.
        //
        // Two values are deliberately narrowed rather than copied whole:
        //   • output 131_072 is the VENDOR row's number. The namespaced
        //     `meta/muse-spark-1.3` rows disagree (943_718 ×3 — exactly 0.9 ×
        //     1_048_576, a derived value — and 1_048_576 ×2), and Meta's page
        //     publishes no output number at all. This is the same
        //     "provider-of-record beats mirror" call this file already makes for
        //     `google/gemini-2.5-flash`.
        //   • input drops the audio modality that the vendor row lists: Meta's
        //     page (the other independent source) lists text/image/video/pdf and
        //     not audio, and an over-declared modality is the claim that makes
        //     the app send something natively to an endpoint that rejects it.
        //     The two sources agree on text+image+video, so that is what ships.
        val museSpark13 = LLMModel("muse-spark-1.3", "Muse Spark 1.3", "Meta", contextWindow = 1_048_576, maxOutputTokens = 131_072, supportsReasoning = true, inputModalities = listOf("text", "image", "video"), outputModalities = textOnlyOutput, supportsTools = true)

        val allMetaDefaults = listOf(museSpark13)

        /** Every entry of the default parameter template, in one place. */
        val recentModelDefaults = allDeepSeek + allGlm + allQwen + allMiniMax + allCodexOAuthDefaults +
            allGpt6Defaults + allXiaomiDefaults + allMetaDefaults

        val allModels = allAnthropic + allGemini + allOpenAI + allOpenRouter + allXAI + allKimi + recentModelDefaults

        /**
         * [R8-recent-model-defaults] Exact-id lookup into the default parameter
         * template. Returns null when no template entry exists — never a partial
         * or guessed model.
         *
         * This is deliberately id-keyed rather than provider-keyed: the template
         * exists for models that arrive from a custom relay (provider string
         * "Custom"), or from a persisted ModelEntry created before the entry
         * gained its values, and neither carries a provider name the rest of the
         * code could match on. Case-insensitive because provider catalogs do not
         * agree on casing (`MiniMax-M3` vs an endpoint echoing `minimax-m3`).
         */
        private val staticDefaultsById: Map<String, LLMModel> by lazy {
            allModels.associateBy { it.id.lowercase() }
        }

        fun staticDefaultFor(id: String): LLMModel? = staticDefaultsById[id.lowercase()]

        /**
         * Last-resort output ceiling, used when the model, the catalog and this
         * file's template all decline to answer. This is the historical
         * provider-level constant (`LLMProvider.defaultMaxOutputTokens`), moved
         * here so the number exists once — that property now reads this value.
         */
        const val DEFAULT_MAX_OUTPUT_TOKENS = 16_384

        /**
         * [G3-max-output-family-floor] Family-level floor for a DeepSeek id that
         * is in neither the catalog nor the per-id template above — a future
         * release, or a relay alias.
         *
         * The CONTEXT half of this floor used to sit inline in
         * `contextWindowTokens` (`if (lid.contains("deepseek")) return
         * 1_000_000`) and answered nothing else. A relay serving an id neither
         * source had shipped yet therefore got the right window and, one call
         * later, the provider-level 16_384 output ceiling — 24× below the
         * 393_216 every published DeepSeek id carries. Long answers were cut
         * mid-sentence with no error the model could explain to the user.
         *
         * Values — models.dev `deepseek`, all 5 rows in both the bundled
         * snapshot (`app/src/main/assets/models-dev-api.json`, read 2026-09-30)
         * and the live https://models.dev/api.json: context 1_000_000, output
         * 384_000, reasoning true. DeepSeek's own page
         * (https://api-docs.deepseek.com/quick_start/pricing) agrees
         * ("CONTEXT LENGTH 1M", "MAX OUTPUT MAXIMUM: 384K"). 384_000 is written
         * as 393_216 to match the per-id template rows above (384 × 1024, the
         * way models.dev itself expresses it) so one family carries one number.
         *
         * DeepSeek is deliberately the ONLY family with an output floor. It is
         * the only family whose current entries agree on one output ceiling;
         * every other family spans more than an order of magnitude across its
         * own rows — GLM 16_384–131_072, Qwen 512–384_000, OpenAI 0–272_000,
         * xAI 0–500_000, Moonshot 16_384–262_144 (same snapshot, counted per
         * provider) — so a family number there would be a fresh guess for
         * exactly the ids that matter. And the cost is ASYMMETRIC: guessing
         * output too high makes a provider reject the request outright (HTTP
         * 400), where guessing too low merely truncates. Those families keep
         * the generic [DEFAULT_MAX_OUTPUT_TOKENS] on purpose.
         *
         * Residual risk, stated rather than hidden: a relay re-labelling an old
         * 128K / 4_096-output V3 build with a `deepseek-*` id is now
         * over-estimated on output as well as on context. The blast radius is
         * bounded by the global request ceiling (`GLOBAL_MAX_TOKENS_CEILING` =
         * 128_000 in `ChatViewModel.dynamicMaxTokens`), so the worst case is 8×
         * the old fallback rather than 24×. Three escape hatches outrank it: a
         * models.dev catalog hit, this file's per-id template, and the user's
         * per-model `maxOutputTokens` override.
         */
        private val deepseekFamilyFloor = LLMModel(
            id = "deepseek-family-floor",
            displayName = "DeepSeek family floor",
            provider = "DeepSeek",
            contextWindow = 1_000_000,
            maxOutputTokens = 393_216,
            supportsReasoning = true,
        )

        /**
         * Family-level floors, consulted only after the catalog and the per-id
         * template above have both declined to answer for this id. A `null`
         * return — the normal case — means "no family claims this id" and the
         * caller keeps its own generic fallback.
         */
        internal fun familyFloorFor(id: String): LLMModel? =
            if (id.lowercase().contains("deepseek")) deepseekFamilyFloor else null

        /**
         * Heuristic display-name formatter for API model ids.
         * Mirrors iOS `modelDisplayName(from:)`: splits on `/` and `-`, preserves a
         * small set of uppercase acronyms, applies brand-name capitalization for
         * well-known vendors (OpenAI, DeepSeek, etc.), and title-cases the rest.
         */
        fun modelDisplayName(fromId: String): String {
            if (fromId.isBlank()) return fromId
            val upperTokens = setOf(
                "gpt", "glm", "oss", "ai", "xl", "vl", "llm", "moe", "api",
                "hd", "sd", "rp", "sft", "rl", "dpo", "gguf", "fp16", "bf16", "int4", "int8",
            )
            val brandRewrites = mapOf(
                "openai" to "OpenAI",
                "deepseek" to "DeepSeek",
                "chatgpt" to "ChatGPT",
                "llama" to "Llama",
                "gemma" to "Gemma",
                "phi" to "Phi",
                "mistral" to "Mistral",
                "mixtral" to "Mixtral",
                "qwen" to "Qwen",
                "yi" to "Yi",
            )
            return fromId.split('/').joinToString(" / ") { segment ->
                segment.split('-').joinToString("-") { token ->
                    val lower = token.lowercase()
                    when {
                        brandRewrites.containsKey(lower) -> brandRewrites[lower]!!
                        upperTokens.contains(lower) -> lower.uppercase()
                        token.isEmpty() -> token
                        else -> token.replaceFirstChar { it.titlecase() }
                    }
                }
            }
        }
    }

    /**
     * [T-newchat-default-model-fallback-android] True when this model can
     * produce a TEXT reply — the only kind a fresh chat should default to.
     * Per the field's documented convention (outputModalities null ⇒ "text
     * out only"), a null/empty list counts as text. A non-empty list must
     * contain "text" (normalized) to qualify — this excludes pure
     * image/audio/video generators (e.g. an image-only model whose
     * outputModalities is ["image"]). Mirrors iOS #636 isTextOutput.
     */
    val isTextOutput: Boolean
        get() {
            val out = outputModalities.normalizeModalities() ?: return true
            return "text" in out
        }

    /**
     * Effective context window in tokens. When `contextWindow` is set (from
     * models.dev enrichment or the built-in catalog) use it; otherwise fall
     * back to a model-id heuristic. Mirrors iOS LLMModel.contextWindowTokens
     * (T-anthropic-context-window): the old "Claude → 200K" default wrongly
     * capped Sonnet 4.6 / Sonnet 5 / Opus 4.x / Fable 5, whose real window is
     * 1M — only Haiku and the legacy 2.x/3.x line are 200K.
     */
    val contextWindowTokens: Int
        get() {
            contextWindow?.let { if (it > 0) return it }
            // [R8-recent-model-defaults] Before any id heuristic: an exact-id hit
            // in the built-in default parameter template. This is what lets the
            // template fix BOTH directions of the old guessing:
            //   • under-estimate — `gpt-5.5` / `deepseek-v4-pro` / `kimi-k3` were
            //     handed a stale family number (400K / 128K / 128K) instead of
            //     their real 1M+ window, so ContextPolicy compacted far too early;
            //   • over-estimate — `anthropic/claude-sonnet-4` matched the
            //     `claude` branch and claimed 1_000_000 against OpenRouter's real
            //     200_000, so the request overflowed before compaction could run.
            // The models.dev catalog still outranks the template: it arrives as a
            // non-null `contextWindow` on the line above.
            staticDefaultFor(id)?.contextWindow?.let { if (it > 0) return it }
            val lid = id.lowercase()
            // Anthropic Claude — modern Opus/Sonnet 4.x & 5 and Fable/Mythos 5
            // ship 1M; Haiku and legacy 2.x/3.x are 200K.
            if (lid.contains("claude")) {
                if (lid.contains("haiku")) return 200_000
                if (lid.contains("claude-2") || lid.contains("claude-3")) return 200_000
                return 1_000_000
            }
            // Google Gemini — modern Gemini advertises 1M+; only 1.0 was 32K.
            if (lid.contains("gemini")) {
                if (lid.contains("1.0")) return 32_000
                return 1_000_000
            }
            // OpenAI family
            if (lid.contains("gpt-3.5")) return 16_000
            if (lid.contains("gpt-4o") || lid.contains("gpt-4-turbo")) return 128_000
            if (lid.contains("gpt-5")) return 400_000
            if (lid.contains("gpt-4")) return 8_000
            if (lid.contains("o3") || lid.contains("o4")) return 200_000
            if (lid.contains("codex")) return 200_000
            // DeepSeek. [R8-recent-model-defaults] This branch used to read
            // `if (lid.contains("deepseek")) return 128_000`, which was wrong
            // twice over:
            //   1. 128_000 is the window of the RETIRED V3/R1 generation.
            //      DeepSeek's own model page lists exactly two current models —
            //      `deepseek-flash` and `deepseek-v4-pro` — both at
            //      "CONTEXT LENGTH 1M", and states that the legacy names
            //      `deepseek-v4-flash` / `deepseek-v4-flash-vision-exp` are
            //      served by the DeepSeek-V4.1-Flash model
            //      (https://api-docs.deepseek.com/quick_start/pricing).
            //      models.dev agrees for all four ids (context 1_000_000), and so
            //      does this app's own bundled catalog (1_000_000 for all five
            //      ids it carries, including `deepseek-chat`/`deepseek-reasoner`).
            //      Not one available source claimed 128K any more.
            //   2. It was also a literal no-op: the function's final `return
            //      128_000` below produces the identical value, so the branch
            //      changed no behaviour while reading like an authoritative
            //      "DeepSeek is 128K". It is gone; this replacement states the
            //      family floor the sources actually support.
            //
            // Kept in the same spirit as the Grok branch below (a conservative
            // per-family floor, not a per-model guess): this only runs for a
            // DeepSeek id that is in neither the catalog nor the template above,
            // e.g. a future release or a relay alias. Every id either source can
            // speak to has already returned by now.
            //
            // Residual risk, stated rather than hidden: a relay re-labelling an
            // old 128K V3/R1 build with a `deepseek-*` id would now be
            // over-estimated. Two escape hatches already outrank this — a
            // catalog hit, and the user's per-model `contextWindow` override.
            //
            // [G3-max-output-family-floor] The floor moved into
            // `familyFloorFor` and now answers output and reasoning too, not
            // just context, so the same over-estimate also reaches the
            // `max_tokens` the request carries. Same three escape hatches, now
            // including the user's per-model `maxOutputTokens` override; see
            // that helper's KDoc for why DeepSeek is the only family that gets
            // an output floor and what bounds the damage.
            familyFloorFor(id)?.contextWindow?.let { if (it > 0) return it }
            // xAI Grok. [T-android-grok-context-underestimate] Without this
            // branch a Grok id missing from the models.dev catalog fell through
            // to the 128K default, and ContextPolicy turned that into
            // compactThreshold = 128K - 20K = 108K — so a model with a 256K-2M
            // window auto-compacted every ~20-30 tool calls. iOS field report
            // 2026-08-13: `grok-4.6` (still absent from the bundled catalog,
            // verified) compacted 6 times in 47 minutes.
            //
            // Grok 2/3 are the only 131K generation; Grok 4 and later are 256K
            // at minimum and the fast / 4.20 lines advertise 2M. 256K is the
            // conservative floor for an unknown Grok 4+. A handful of
            // relay-hosted grok-4 entries do declare 128K-200K, but every one
            // of them IS in the catalog, so the explicit `contextWindow` check
            // above wins and this heuristic never runs for them — it only ever
            // sees ids models.dev has not shipped yet, which is the whole
            // failure mode. Port of iOS d63e9b9c9.
            if (lid.contains("grok")) {
                if (lid.contains("grok-2") || lid.contains("grok-3")) return 131_072
                return 256_000
            }
            // GLM / Qwen / MiniMax deliberately have NO family branch here. Their
            // current generations span 128K-1M (GLM-4.6 204_800 vs GLM-5.3
            // 1_000_000; qwen-max 32_768 vs qwen3.8-max 1_000_000), so any single
            // family number would be a fresh guess for exactly the ids that
            // matter. Their known ids are enumerated in the template above; an
            // unrecognized id gets the generic floor below.
            // Default: assume a modern long-context model rather than 64K so the
            // group context-limit slider doesn't collapse to a single stop.
            return 128_000
        }

    /**
     * [G3-max-output-family-floor] The model-level half of the max-output
     * ceiling resolution: this model's own value → this file's per-id template
     * → its family floor. `null` means "no source in this file knows", and the
     * caller keeps its own provider-level fallback — this property deliberately
     * cannot see the provider, because a provider-level ceiling (e.g.
     * Anthropic's 64_000) is a fact about the ENDPOINT and only the provider
     * knows it.
     *
     * Same precedence shape as [contextWindowTokens], and for the same reason:
     * the template and the family floor are stored values, not getters, so a
     * directly-constructed model (a relay's /v1/models result before
     * `ModelsDevApi.enrichModels` has run, a persisted entry, a test fixture)
     * has no other way to reach them. Without this chain the only answer for
     * such a model was the caller's generic 16_384.
     *
     * Ordering note — this is why the template is consulted HERE and not only
     * in `enrichModels`: `enrichModels` fills the FIELDS, which is right for a
     * model that goes through it, but the fields are what get reset by any
     * `copy()` that does not carry them forward. Resolving the id again at
     * read time cannot be lost that way.
     */
    val resolvedMaxOutputTokens: Int?
        get() {
            maxOutputTokens?.let { if (it > 0) return it }
            staticDefaultFor(id)?.maxOutputTokens?.let { if (it > 0) return it }
            familyFloorFor(id)?.maxOutputTokens?.let { if (it > 0) return it }
            return null
        }

    /**
     * Capability hint appended to the system prompt so the model knows exactly
     * what it can natively consume vs what it must route through shell tools.
     * Returns `null` for fully-multimodal models (no hint needed). Matches the
     * iOS `capabilityPromptFragment` wording so Android/iOS chats are identical
     * when routed through the same model.
     */
    fun capabilityPromptFragment(): String? {
        // [T-android-vision-native-check-misses-image_input] normalizeModalities,
        // not a bare lowercase: OpenAI / OpenRouter spell these "image_input" /
        // "audio_input", models.dev spells them bare. Lowercasing alone left the
        // suffix intact, so a vision model from an OpenAI-shaped catalog was told
        // in its own system prompt that it could NOT see images.
        val inputs = inputModalities.normalizeModalities() ?: emptyList()
        val hasImage = "image" in inputs
        val hasPdf = "pdf" in inputs
        val hasAudio = "audio" in inputs
        val hasVideo = "video" in inputs
        if (hasImage && hasPdf && hasAudio && hasVideo) return null

        val natives = buildList {
            if (hasImage) add("images")
            if (hasPdf) add("PDFs")
            if (hasAudio) add("audio")
            if (hasVideo) add("video")
        }
        val missing = buildList {
            if (!hasImage) add("images")
            if (!hasPdf) add("PDFs")
            if (!hasAudio) add("audio")
            if (!hasVideo) add("video")
        }

        val sb = StringBuilder()
        if (natives.isNotEmpty()) {
            sb.append("You can natively process ").append(natives.joinToString(", ")).append(". ")
        }
        if (missing.isNotEmpty()) {
            sb.append("You cannot natively process ").append(missing.joinToString(", "))
            sb.append(" — for those formats, call shell_execute with ffmpeg or similar tools to extract text/metadata first.")
        }
        return sb.toString().trim().ifEmpty { null }
    }

    /**
     * Per-model-family agent-loop behavior hint. Gemini needs a reminder to
     * actually invoke tools via function calling; OpenAI Codex family needs a
     * push toward autonomous persistence ("don't stop at analysis").
     */
    fun agentBehaviorPromptFragment(): String? {
        val idLower = id.lowercase()
        val providerLower = provider.lowercase()

        if (providerLower == "google" || idLower.contains("gemini")) {
            return "When you need to use a tool, invoke it via the function-calling mechanism directly. Do not emit tool invocations as plain text — they will not be executed."
        }

        val isCodex = idLower.contains("codex") ||
            Regex("gpt-5(?:\\.\\d+)?-codex").containsMatchIn(idLower)
        if (isCodex) {
            return "Act autonomously: don't stop at analysis, don't ask for permission on reversible local actions, and never announce \"I will use tool X\" without actually calling X. Keep iterating until the task is fully complete."
        }
        return null
    }
}

/**
 * Normalize a modality string to its bare form. Provider APIs vary —
 * OpenAI / OpenRouter return "image_input" / "text_output" with _input/_output
 * suffixes; models.dev returns bare "image" / "text". Internally we always
 * use the bare form ("image", "pdf", "audio", "video", "text") so toggles,
 * `"image" in modalities` checks, and capability fragments work uniformly.
 */
fun String.normalizeModalityName(): String =
    // [T-android-modality-normalize-case-order] Lowercase FIRST, then strip.
    // The reverse order silently failed on any non-lowercase spelling:
    // removeSuffix("_input") matches literally, so "IMAGE_INPUT" kept its
    // suffix and normalized to "image_input", which then compared unequal to
    // "image" everywhere — hasImageInput / hasAudioInput / hasAudioOutput and
    // the Vision Group member filter all read such a model as lacking the
    // modality it actually declares.
    lowercase().removeSuffix("_input").removeSuffix("_output")

fun List<String>?.normalizeModalities(): List<String>? =
    this?.map { it.normalizeModalityName() }?.distinct()?.takeIf { it.isNotEmpty() }
