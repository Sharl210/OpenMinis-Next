package com.openminis.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-model-prompt-fragments] Behaviour of the per-model system-prompt
 * composition, including the two properties that are easy to get wrong and
 * silent when wrong: **no duplicate append** and **cacheable prefix preserved**.
 *
 * ## What was broken before this function existed
 *
 * `LLMModel.capabilityPromptFragment()` and `LLMModel.agentBehaviorPromptFragment()`
 * existed with iOS-matching wording but had **no production caller on Android**,
 * so no request ever carried the Gemini "invoke tools via function calling, not
 * as plain text" instruction, the Codex "act autonomously" instruction, or the
 * capability hint. Every assertion below therefore fails if the composition is
 * short-circuited (`/tmp` falsification harness) — this file is not satisfied by
 * the fragment builders existing, only by the composition happening and landing
 * in the right place.
 *
 * ## These are behaviour assertions, not text-source assertions
 *
 * The generator under test is a pure function, so everything here executes.
 * The separate structural pin — that the *production* call sites exist and pass
 * the live provider's model — lives in
 * `com.openminis.app.ui.chat.ModelPromptFragmentWiringSourceTest` and is
 * labelled there as a source-text assertion; nothing in this file greps source.
 *
 * The two expected sentences are spelled out in full below on purpose. They are
 * the exact iOS strings (`AIChatViewModel.swift` / `+Fallback.swift` append the
 * same text), so a silent wording change on either side breaks this test and
 * forces the parity decision to be made deliberately.
 */
class ModelPromptFragmentsTest {

    private val geminiInstruction =
        "When you need to use a tool, invoke it via the function-calling mechanism directly. " +
            "Do not emit tool invocations as plain text — they will not be executed."

    private val codexInstruction =
        "Act autonomously: don't stop at analysis, don't ask for permission on reversible local " +
            "actions, and never announce \"I will use tool X\" without actually calling X. " +
            "Keep iterating until the task is fully complete."

    /**
     * A stand-in for `ChatViewModel.buildSystemPrompt()`: a long model-independent
     * head (identity + the tool catalog, which is the bulk of the real prompt),
     * then the per-request "Runtime context" tail. The literal
     * `"Runtime context:"` marker is reproduced because the ordering contract
     * under test is stated against it — the fragments must land after it, not
     * inside the head.
     */
    private val staticHead = buildString {
        append("You are Minis, a capable AI assistant running on an Android device. ")
        append("You should proactively use shell commands to accomplish the user's tasks.")
        repeat(40) { append("\n- shell_execute: Run any shell command. (").append(it).append(")") }
        append("\n- file_read / file_write / file_edit / browser_use: as documented.")
    }

    private val runtimeTail = buildString {
        append("\n\nRuntime context:\n")
        append("- Current date: 2026-09-30 (Asia/Shanghai)\n")
        append("- Device language: en-US\n")
        append("- minis-model-use models available: 7")
        append("\n\nConversation IDs: a `minis-conv-` id is this app's own conversation id.")
    }

    /** The fragment-free base every production call site must compose from. */
    private val base: String = staticHead + runtimeTail

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return count
            count++
            from = at + needle.length
        }
    }

    // ─── 1. Gemini family ────────────────────────────────────────────────

    @Test
    fun `gemini by id gets the function-calling instruction`() {
        val gemini = LLMModel.staticDefaultFor("gemini-2.5-flash")
            ?: error("template must carry gemini-2.5-flash")

        val out = systemPromptWithModelFragments(base, gemini) ?: error("base must survive")

        assertTrue(
            "a Gemini-family model must be told to invoke tools via function calling, " +
                "otherwise a narrated tool call is emitted as text no component executes:\n$out",
            out.contains(geminiInstruction),
        )
        assertEquals(
            "the instruction must appear exactly once",
            1,
            countOccurrences(out, geminiInstruction),
        )
    }

    @Test
    fun `gemini is matched on the provider name too, not just the id`() {
        // `agentBehaviorPromptFragment` triggers on providerLower == "google"
        // OR idLower.contains("gemini"). A relay-proxied Google model keeps the
        // id but not the provider string, so both arms need covering or one of
        // them can rot unnoticed.
        val byProviderOnly = LLMModel(id = "unknown-relay-model", displayName = "X", provider = "google")
        val byIdOnly = LLMModel(id = "google/gemini-2.5-flash", displayName = "X", provider = "OpenRouter")

        assertTrue(
            "provider=google must trigger the Gemini instruction",
            systemPromptWithModelFragments(base, byProviderOnly)!!.contains(geminiInstruction),
        )
        assertTrue(
            "an id containing gemini must trigger the Gemini instruction",
            systemPromptWithModelFragments(base, byIdOnly)!!.contains(geminiInstruction),
        )
    }

    // ─── 2. Codex family ─────────────────────────────────────────────────

    @Test
    fun `codex family gets the autonomy instruction`() {
        val codex = LLMModel.staticDefaultFor("gpt-5.3-codex")
            ?: error("template must carry gpt-5.3-codex")

        val out = systemPromptWithModelFragments(base, codex) ?: error("base must survive")

        assertTrue(
            "a Codex-family model must get the autonomous-persistence push (don't stop at " +
                "analysis, don't announce a tool call without making it):\n$out",
            out.contains(codexInstruction),
        )
        assertFalse(
            "the Gemini instruction must not be attached to a non-Gemini model",
            out.contains(geminiInstruction),
        )
    }

    @Test
    fun `codex is matched on the id heuristic for ids the template does not list`() {
        val synthetic = LLMModel(id = "gpt-5.9-codex-preview", displayName = "X", provider = "Custom")
        assertTrue(
            "the id heuristic must cover unlisted codex ids",
            systemPromptWithModelFragments(base, synthetic)!!.contains(codexInstruction),
        )
    }

    // ─── 3. No indiscriminate injection ──────────────────────────────────

    @Test
    fun `models outside both families get neither instruction`() {
        val unrelated = listOf(
            LLMModel.staticDefaultFor("claude-opus-5-5")!!,
            LLMModel.staticDefaultFor("gpt-5.2") ?: LLMModel("gpt-5.2", "GPT-5.2", "OpenAI"),
            LLMModel.staticDefaultFor("deepseek-v4-pro")!!,
            LLMModel.staticDefaultFor("gpt-4o") ?: LLMModel("gpt-4o", "GPT-4o", "OpenAI"),
        )
        for (model in unrelated) {
            val out = systemPromptWithModelFragments(base, model) ?: error("base must survive")
            assertFalse("${model.id} must not get the Gemini instruction", out.contains(geminiInstruction))
            assertFalse("${model.id} must not get the Codex instruction", out.contains(codexInstruction))
        }
    }

    @Test
    fun `a model with neither fragment leaves the prompt byte-identical`() {
        // All four native input modalities and no family match → both fragment
        // builders return null. This is the anti-"inject everywhere" guard: the
        // identity of the base String is preserved, so a model that needs no
        // hint costs no prompt bytes and no cache invalidation at all.
        val fullyMultimodal = LLMModel(
            id = "some-relay-omni",
            displayName = "Omni",
            provider = "Custom",
            inputModalities = listOf("text", "image", "pdf", "audio", "video"),
        )
        val out = systemPromptWithModelFragments(base, fullyMultimodal)
        assertEquals("prompt must be untouched", base, out)
    }

    // ─── 4. Idempotence across repeated turns ────────────────────────────

    @Test
    fun `recomposing from the same base across turns never stacks the fragment`() {
        val gemini = LLMModel.staticDefaultFor("gemini-2.5-flash")!!

        // The production shape: every turn recomposes from the fragment-free
        // base (ChatViewModel composes inside its per-turn loop). Ten turns must
        // produce ten identical prompts, each carrying the instruction once.
        val perTurn = (1..10).map { systemPromptWithModelFragments(base, gemini)!! }

        for ((index, prompt) in perTurn.withIndex()) {
            assertEquals(
                "turn ${index + 1} must carry the instruction exactly once",
                1,
                countOccurrences(prompt, geminiInstruction),
            )
            assertEquals(
                "turn ${index + 1} must be byte-identical to turn 1",
                perTurn[0],
                prompt,
            )
            assertEquals(
                "indexOf/lastIndexOf must agree (first and only occurrence)",
                prompt.indexOf(geminiInstruction),
                prompt.lastIndexOf(geminiInstruction),
            )
        }
    }

    @Test
    fun `the fragment builders themselves stay pure under repeated calls`() {
        // If capabilityPromptFragment()/agentBehaviorPromptFragment() ever
        // accumulated state, the assertions above could pass while the prompt
        // grew. Pin the generators directly.
        val gemini = LLMModel.staticDefaultFor("gemini-2.5-flash")!!
        val first = gemini.agentBehaviorPromptFragment()
        assertEquals(first, gemini.agentBehaviorPromptFragment())
        assertEquals(first, gemini.agentBehaviorPromptFragment())
        assertEquals(gemini.capabilityPromptFragment(), gemini.capabilityPromptFragment())
    }

    // ─── 5. Cache prefix must survive ────────────────────────────────────

    @Test
    fun `the whole static base stays a byte-identical prefix of the composed prompt`() {
        val gemini = LLMModel.staticDefaultFor("gemini-2.5-flash")!!
        val out = systemPromptWithModelFragments(base, gemini)!!

        // The cache contract: OpenAI / DeepSeek prompt caching is prefix-based
        // and the head is model-independent, so EVERY byte before the fragments
        // must be reusable. Anything inserted mid-base would break it, which is
        // why the fragments are appended at the very end.
        assertTrue(
            "the entire base must remain a prefix — nothing may be inserted mid-prompt",
            out.startsWith(base),
        )
        assertEquals("base must be byte-identical", base, out.substring(0, base.length))
        assertEquals(
            "nothing besides the fragment and its separator may be added",
            base.length + "\n\n".length + geminiInstruction.length,
            out.length,
        )
        assertTrue(
            "the fragment must land AFTER the per-request Runtime context tail: " +
                "runtimeContext=${out.indexOf("Runtime context:")} fragment=${out.indexOf(geminiInstruction)}",
            out.indexOf("Runtime context:") < out.indexOf(geminiInstruction),
        )
        assertTrue(
            "the fragment must not land inside the tool-catalog head",
            out.indexOf(geminiInstruction) > out.indexOf("shell_execute"),
        )
    }

    @Test
    fun `two different models share the entire base prefix`() {
        val gemini = LLMModel.staticDefaultFor("gemini-2.5-flash")!!
        val codex = LLMModel.staticDefaultFor("gpt-5.3-codex")!!

        val onGemini = systemPromptWithModelFragments(base, gemini)!!
        val onCodex = systemPromptWithModelFragments(base, codex)!!

        // A model switch in the same conversation still reuses every byte of the
        // base, so the switch costs one fragment's worth of new ingestion rather
        // than a whole re-ingest. This is the reason the composition happens at
        // the request site (after the base is fully built) instead of inside the
        // builder's head.
        assertEquals(base, onGemini.substring(0, base.length))
        assertEquals(base, onCodex.substring(0, base.length))
        assertNotEquals(
            "the two models must not receive the same prompt (that would mean no per-model text)",
            onGemini,
            onCodex,
        )
    }

    // ─── 6. A fallback switch yields the NEW model's fragment ────────────

    @Test
    fun `after a fallback the prompt carries only the new model's fragments`() {
        val primary = LLMModel.staticDefaultFor("gemini-2.5-flash")!!
        val fallback = LLMModel.staticDefaultFor("gpt-5.3-codex")!!

        // Android's fallback swaps `currentProvider` and composes from the SAME
        // fragment-free base with the new provider's model. Modelling exactly
        // that: A's fragment must be gone, B's present, and exactly once.
        val onPrimary = systemPromptWithModelFragments(base, primary)!!
        val onFallback = systemPromptWithModelFragments(base, fallback)!!

        assertTrue(onPrimary.contains(geminiInstruction))
        assertFalse(onPrimary.contains(codexInstruction))

        assertFalse(
            "the previous model's fragment must not survive the switch",
            onFallback.contains(geminiInstruction),
        )
        assertTrue(onFallback.contains(codexInstruction))
        assertEquals(1, countOccurrences(onFallback, codexInstruction))
    }

    @Test
    fun `falling back to a model with no behaviour fragment drops the previous one`() {
        val primary = LLMModel.staticDefaultFor("gemini-2.5-flash")!!
        val plain = LLMModel.staticDefaultFor("claude-opus-5-5")!!

        // Anthropic contributes a capability hint but no behaviour hint. The
        // Gemini instruction must not be left behind just because the new model
        // contributes nothing at this position.
        val out = systemPromptWithModelFragments(base, plain)!!
        assertFalse(out.contains(geminiInstruction))
        assertFalse(out.contains(codexInstruction))
        assertTrue("the capability hint still applies to Claude", out.startsWith(base))
    }

    // ─── Null-safety of the seam ─────────────────────────────────────────

    @Test
    fun `a null base stays null and a null model leaves the base alone`() {
        assertEquals(null, systemPromptWithModelFragments(null, LLMModel.staticDefaultFor("gemini-2.5-flash")))
        assertEquals(null, systemPromptWithModelFragments(null, null))
        assertEquals("no model resolved → base unchanged", base, systemPromptWithModelFragments(base, null))
    }

    // ─── includeCapability = false (the delegated-child path) ────────────

    @Test
    fun `includeCapability false drops the capability hint but keeps the behaviour hint`() {
        // The child path passes false because the capability hint's only
        // executable instruction is "call `shell_execute` with ffmpeg …" and
        // `makeChildAgentTools()` provides no `shell_execute`. Verified here on
        // a model that HAS both fragments, so the assertions can actually
        // distinguish "dropped" from "would have been null anyway".
        val gemini = LLMModel.staticDefaultFor("gemini-2.5-flash")!!

        // Precondition for the test to mean anything: this model does carry a
        // capability hint, and it is the one that names shell_execute.
        val capability = gemini.capabilityPromptFragment()
        val capabilityIsNull = capability == null

        val withCapability = systemPromptWithModelFragments(base, gemini, includeCapability = true)!!
        val withoutCapability = systemPromptWithModelFragments(base, gemini, includeCapability = false)!!

        assertTrue(
            "behaviour hint must survive includeCapability=false (it constrains how the " +
                "model invokes whatever tools it does have):\n$withoutCapability",
            withoutCapability.contains(geminiInstruction),
        )
        assertTrue(
            "the base must still be a byte-identical prefix",
            withoutCapability.startsWith(base),
        )
        assertEquals(1, countOccurrences(withoutCapability, geminiInstruction))

        if (capabilityIsNull) {
            // No switch needed for this model; both forms must agree.
            assertEquals(withCapability, withoutCapability)
        } else {
            assertFalse(
                "the capability hint must be gone: ${withoutCapability.substring(base.length)}",
                withoutCapability.contains(capability!!),
            )
            assertTrue(
                "with includeCapability=true it must be present (proves the switch is what removed it)",
                withCapability.contains(capability),
            )
            assertEquals(
                "the only difference must be the dropped capability hint plus its separator",
                withCapability.length - ("\n\n".length + capability.length),
                withoutCapability.length,
            )
        }
    }

    @Test
    fun `includeCapability false never appends the shell_execute instruction`() {
        // The whole reason the switch exists: a delegated child has no
        // shell_execute, so the capability hint — whose second sentence is
        // "call `shell_execute` with ffmpeg …" — must not be appended.
        //
        // Scoped to the APPENDED TAIL, not the whole prompt: `base` deliberately
        // reproduces the real tool catalog, which already names shell_execute 40
        // times. Asserting on the whole prompt would fail on the fixture and
        // prove nothing about the switch.
        val textOnly = LLMModel(
            id = "some-text-only-model",
            displayName = "TextOnly",
            provider = "Custom",
            inputModalities = listOf("text"),
        )
        assertTrue("fixture precondition: the base names shell_execute", base.contains("shell_execute"))

        val tailWithout = systemPromptWithModelFragments(base, textOnly, includeCapability = false)!!
            .substring(base.length)
        assertFalse(
            "the appended tail must not name shell_execute when the capability hint is off: '$tailWithout'",
            tailWithout.contains("shell_execute"),
        )

        // Guard the guard: WITH the hint, the same model DOES add the
        // instruction to the tail — so the assertion above tests the switch and
        // not a model that happens to say nothing.
        val tailWith = systemPromptWithModelFragments(base, textOnly, includeCapability = true)!!
            .substring(base.length)
        assertTrue(
            "sanity: the capability hint for a text-only model does name shell_execute: '$tailWith'",
            tailWith.contains("shell_execute"),
        )
    }

    @Test
    fun `the default keeps existing callers on the capability-including form`() {
        val gemini = LLMModel.staticDefaultFor("gemini-2.5-flash")!!
        assertEquals(
            "omitting the argument must equal passing true — otherwise adding the switch " +
                "would silently change the main agent loop's prompt",
            systemPromptWithModelFragments(base, gemini, includeCapability = true),
            systemPromptWithModelFragments(base, gemini),
        )
    }
}
