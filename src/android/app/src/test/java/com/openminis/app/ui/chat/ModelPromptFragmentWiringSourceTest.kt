package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-model-prompt-fragments] **Source-text assertions** (not behaviour):
 * pin that the per-model prompt fragments have a *production caller*, and that
 * they are composed exactly once, from a fragment-free base, with the model of
 * the provider actually being called.
 *
 * ## Why this file is source-text and not behavioural
 *
 * This is the exact defect class it exists to catch: before this change,
 * `capabilityPromptFragment()` / `agentBehaviorPromptFragment()` were correct,
 * well-documented, iOS-parity functions with **zero production callers** — a
 * state no behavioural unit test could observe, because every unit of behaviour
 * was fine and simply never invoked. The composition helper itself IS tested
 * behaviourally in `data/model/ModelPromptFragmentsTest`; what cannot be reached
 * from a JVM unit test is the call site: `runAgentLoop` is a private method on a
 * `ViewModel` whose construction needs `EncryptedSharedPreferences` and a real
 * provider loop (see `RuntimeChildRunnerMessagingSourceTest`'s note on the same
 * limitation).
 *
 * Every assertion below reads production source text.
 *
 * ## Wrapping-tolerance
 *
 * These two files are edited by several agents at once and Kotlin call sites get
 * re-wrapped (the child `streamChildTurn` call was reformatted to one-argument-
 * per-line within minutes of this change landing, which broke the first version
 * of this test for a reason that had nothing to do with the wiring). So the
 * assertions are written against **bounded windows** around an anchor, not
 * against whole lines: a formatter may move the bytes, and the test still means
 * "this call site composes the fragments".
 */
class ModelPromptFragmentWiringSourceTest {

    private fun source(relative: String): String = sequenceOf(
        File(relative),
        File("app/$relative"),
    ).first { it.isFile }.readText()

    private val chatViewModel by lazy {
        source("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt")
    }
    private val childRunner by lazy {
        source("src/main/java/com/openminis/app/feature/runtime/RuntimeChildRunner.kt")
    }

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

    // ─── The main agent loop ─────────────────────────────────────────────

    @Test
    fun `the main agent loop composes the fragments from the live provider's model`() {
        val at = chatViewModel.indexOf("systemPromptWithModelFragments(")
        assertTrue(
            "the request site must compose the per-model fragments — without this the two " +
                "fragment functions have no production caller again, which is the bug this " +
                "wiring test exists for",
            at >= 0,
        )

        val site = chatViewModel.substring(at).take(160)
        assertTrue(
            "must compose from the fragment-free `systemPrompt` base (composing from an " +
                "already-composed value would stack a second copy of the fragment): $site",
            site.contains("systemPromptWithModelFragments(systemPrompt,"),
        )
        assertTrue(
            "must use the model of the provider actually being called, so a fallback that " +
                "swaps currentProvider is picked up: $site",
            site.contains("currentProvider.model"),
        )
        // The main agent loop HAS shell_execute, so it must keep the capability
        // hint: taking the default is the assertion. (The delegated-child path is
        // the one that opts out — see the child test below.)
        assertTrue(
            "the main agent loop must not opt out of the capability hint, whose " +
                "shell_execute instruction its tool set actually satisfies: $site",
            !site.contains("includeCapability"),
        )
    }

    @Test
    fun `the composed prompt is what reaches the provider, not the raw base`() {
        val composeAt = chatViewModel.indexOf("systemPromptWithModelFragments(")
        assertTrue("request site must exist", composeAt >= 0)

        val callAt = chatViewModel.indexOf("currentProvider.streamMessage(", composeAt)
        assertTrue(
            "the composition must sit above the provider call it feeds",
            callAt > composeAt,
        )

        // Window rather than line: the call is already multi-line and may be
        // re-wrapped again. `requestSystemPrompt` is produced in exactly one
        // place, so finding it inside this call's argument window is the
        // assertion that the composed value — not the raw base — is passed.
        val callBlock = chatViewModel.substring(callAt).take(600)
        assertTrue(
            "the composed value must be the system prompt argument: $callBlock",
            callBlock.contains("requestSystemPrompt"),
        )
    }

    @Test
    fun `the fragments are composed in exactly one place in ChatViewModel`() {
        // Two compositions would mean one of them feeds the other — i.e. a
        // double append. One is the whole point: idempotence here is
        // "recompose from the base", not a `contains` guard.
        assertEquals(
            "exactly one composition site expected in ChatViewModel.kt",
            1,
            countOccurrences(chatViewModel, "systemPromptWithModelFragments("),
        )
    }

    @Test
    fun `buildSystemPrompt does not compose fragments itself`() {
        // `buildSystemPrompt()` owns the cache-stable head plus the per-request
        // "Runtime context" tail; the fragments are appended by the request site
        // AFTER all of it. If the builder ever appended them too, the request
        // site's recomposition would duplicate them.
        val builderAt = chatViewModel.indexOf("private fun buildSystemPrompt(): String?")
        assertTrue("the builder must still exist", builderAt >= 0)
        val builderBody = chatViewModel.substring(builderAt, builderAt + 20_000)
        assertTrue(
            "buildSystemPrompt must NOT append the fragments — the request site does that, " +
                "after the builder's Runtime context tail",
            !builderBody.contains("systemPromptWithModelFragments("),
        )
    }

    // ─── The delegated-child path ───────────────────────────────────────

    @Test
    fun `the delegated child loop composes the fragments from its own builder`() {
        // Window-based, not whole-line: this call was reformatted to
        // one-argument-per-line while the change was in flight, so a
        // `contains("<exact line>")` assertion would fail on the formatter.
        val childAt = childRunner.indexOf("val childSystemPrompt = systemPromptWithModelFragments(")
        assertTrue(
            "the child agent loop calls tools too, so a Gemini child that narrates a tool call " +
                "as text has no consumer for it either — the behaviour fragment must reach it",
            childAt >= 0,
        )

        val childSite = childRunner.substring(childAt).take(220)
        assertTrue(
            "the child must compose from its own fragment-free base and its own resolved entry: $childSite",
            childSite.contains("systemPrompt,") && childSite.contains("entry.model,"),
        )
        // The capability hint's only executable instruction is "call
        // `shell_execute` with ffmpeg …", and `makeChildAgentTools()` does not
        // provide `shell_execute`. Handing it to a child teaches a false escape
        // hatch and burns a turn on a call that returns "not available".
        assertTrue(
            "the child must NOT receive the capability hint, whose only executable " +
                "instruction names a tool the child surface does not offer: $childSite",
            childSite.contains("includeCapability = false"),
        )
        assertEquals(
            "the child's composition must carry the switch exactly once, and it must be `false` " +
                "(counted over the call site, not the file: this file's explanatory comment " +
                "names the argument too)",
            1,
            countOccurrences(childSite, "includeCapability"),
        )

        // The capability-injection test anchors on this exact line, so it must
        // stay byte-identical — the composition is a separate val below it.
        assertTrue(
            "the buildSystemPrompt call site must keep its shape (ChildCapabilityInjectionTest anchors on it)",
            childRunner.contains("val systemPrompt = buildSystemPrompt("),
        )

        val callAt = childRunner.indexOf("streamChildTurn(")
        assertTrue("streamChildTurn must be called", callAt >= 0)
        // Wide window on purpose: this call is one-argument-per-line and carries
        // interleaved explanatory comments (~900 chars before the prompt
        // argument at the time of writing). A tight window made the test fail on
        // a comment that had nothing to do with the wiring.
        val callBlock = childRunner.substring(callAt).take(2_500)
        assertTrue(
            "the child's model call must be handed the composed prompt: $callBlock",
            callBlock.contains("childSystemPrompt"),
        )
    }

    @Test
    fun `the child prompt is composed in exactly one place`() {
        assertEquals(
            "exactly one composition site expected in RuntimeChildRunner.kt",
            1,
            countOccurrences(childRunner, "systemPromptWithModelFragments("),
        )
    }
}
