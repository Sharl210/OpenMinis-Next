package com.openminis.app.data.model

/**
 * Compose the system prompt actually sent to [model] by appending its
 * per-model-family fragments to a fragment-free [base] prompt.
 *
 * ## [T-android-model-prompt-fragments] Why this exists
 *
 * [LLMModel.capabilityPromptFragment] and [LLMModel.agentBehaviorPromptFragment]
 * were ported from iOS with matching wording (the KDoc on the former promises
 * "Android/iOS chats are identical when routed through the same model") — but
 * until this function existed **nothing on Android called either one**. A grep
 * for `agentBehaviorPromptFragment` across `app/src` matched only its own
 * definition line, and `buildSystemPrompt()` contained no equivalent
 * instruction. So two iOS behaviours were missing on Android:
 *
 * - the Gemini-family hint ("invoke tools via the function-calling mechanism
 *   directly; do not emit tool invocations as plain text — they will not be
 *   executed"), without which a model that narrates a tool call produces text
 *   no component will ever execute; and
 * - the Codex-family hint ("act autonomously: don't stop at analysis …"),
 * - plus the capability hint, which is the only place a text-only model is told
 *   it must route images/PDFs/audio/video through `shell_execute`.
 *
 * iOS appends both fragments at *every* prompt-assembly point: on the first
 * request of a turn (`AIChatViewModel.swift:4786-4793`) and again whenever a
 * fallback switches the active model (`AIChatViewModel+Fallback.swift:214-221`,
 * `:267-274`, `:349-356`, and `AIChatViewModel.swift:5266-5272`). Android wires
 * the equivalent with this one function so both paths cannot drift apart.
 *
 * ## Composition contract (the part callers must get right)
 *
 * [base] must be **fragment-free** — the output of a builder that does not
 * itself append these fragments (on Android: `ChatViewModel.buildSystemPrompt()`
 * and `RuntimeChildRunner.buildSystemPrompt()`). Always compose from that base;
 * never feed this function its own output. Idempotence is therefore structural
 * rather than a `contains` check: recomposing for a new model *replaces* the
 * previous model's fragments instead of stacking them, which is exactly what
 * keeps a fallback switch from accumulating two models' hints.
 *
 * ## Ordering (prompt-cache safety)
 *
 * The fragments go at the very END of the prompt — after the caller's own
 * cache-stable head *and* after its per-request "Runtime context" tail. Android
 * keeps that head byte-stable because OpenAI / DeepSeek prompt caching is
 * prefix-based, and the head is model-independent, so composing at the tail
 * means a model switch leaves every earlier byte as a prefix hit and only the
 * fragment tail changes. (Inserting the fragments mid-prompt would invalidate
 * the tail's prefix on every switch.) The append order — capability, then
 * behaviour — and the `"\n\n"` separator match iOS exactly.
 *
 * @param base the fragment-free prompt. `null` returns `null`: a null base means
 *   "send no system message", and materialising a fragments-only message in that
 *   case would be a behaviour change beyond this port.
 * @param model the model of the provider that will actually receive the request.
 *   `null` returns [base] unchanged.
 * @param includeCapability whether [LLMModel.capabilityPromptFragment] may be
 *   appended. Defaults to `true`; pass `false` **only** for a caller whose model
 *   has no `shell_execute` tool — see below. The behaviour fragment is always
 *   eligible and has no such switch, because it constrains how the model invokes
 *   whatever tools it *does* have.
 *
 * ## Why the capability fragment needs a switch: it names a specific tool
 *
 * Its second sentence is not a capability statement but an **instruction**:
 * "You cannot natively process … — for those formats, call `shell_execute` with
 * ffmpeg or similar tools to extract text/metadata first." That instruction is
 * only honest where `shell_execute` is actually offered. A delegated child agent
 * is not: `AgentTools.makeChildAgentTools()` exposes exactly `subagent_complete`,
 * `supervise_descendants`, `message_peer`, `conversation_query`, `web_search` and
 * `web_fetch` (the `shell_execute` definition is added only by `makeAgentTools()`),
 * and a child that calls it anyway gets `ChildAgentLoop`'s "Tool 'shell_execute'
 * is not available to a delegated child agent". Appending the fragment there
 * would therefore spend tokens teaching the model a false escape hatch, which is
 * the same shape the tool layer already warns about for stubs ("worse than
 * absence, because the model would burn turns calling something that cannot
 * work"). A child also cannot receive images/PDFs/audio at all — its seed message
 * is plain text — so the fragment describes a situation that cannot arise.
 * `RuntimeChildRunner` consequently passes `includeCapability = false` and keeps
 * the behaviour fragment, which does apply to its real tools.
 *
 * @return [base] unchanged when it contributes no eligible fragment.
 */
fun systemPromptWithModelFragments(
    base: String?,
    model: LLMModel?,
    includeCapability: Boolean = true,
): String? {
    if (base == null) return null
    if (model == null) return base
    val capability = if (includeCapability) model.capabilityPromptFragment() else null
    val behavior = model.agentBehaviorPromptFragment()
    if (capability == null && behavior == null) return base
    return buildString(base.length + 512) {
        append(base)
        if (capability != null) append("\n\n").append(capability)
        if (behavior != null) append("\n\n").append(behavior)
    }
}
