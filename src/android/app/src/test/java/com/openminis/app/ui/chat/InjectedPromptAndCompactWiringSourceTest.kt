package com.openminis.app.ui.chat

import com.openminis.app.shared.KotlinSourceText
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Source-text assertions (not behaviour).** Read this before trusting them.
 *
 * ## What this file is
 *
 * `[R7]` and `[R5]` are two behaviours whose *logic* is covered by real
 * behavioural tests, but whose **call sites** live inside classes no JVM unit test
 * can construct:
 *
 *  - `ChatViewModel` needs an Android `Context` and a full provider stack;
 *  - `RuntimeChildRunner.execute` needs a provider stack (see
 *    `RuntimeChildRunnerMessagingSourceTest`'s class comment).
 *
 * A behavioural test of an extracted function is green whether or not anybody
 * calls it, so deleting the two production lines that motivated this work — the
 * `appendToHistory` call in `RuntimeChildRunner` and in `ChatViewModel` — would
 * leave every behavioural test in this suite green. That is exactly the audit
 * finding: the audit measured `appendToHistory` at **zero** occurrences under
 * `src/test` and `src/androidTest`. The only tool left for "is the call site still
 * there" is the source text, so this file reads it.
 *
 * ## What it can and cannot do
 *
 *  * **can** catch the deletion of a call site; a call moved out of the window
 *    that consumes its decision; a call whose history argument changed to a list
 *    the provider never sees; and a re-inlined expression that bypasses the
 *    tested mapping;
 *  * **cannot** catch a call site whose *logic* is wrong in a way that keeps the
 *    call in the window — swapped-order arguments of the same name, a call moved
 *    behind a branch that never runs, a wrong value inside the mapping. Those are
 *    the behavioural tests' job (`RoundInjectionHistoryTest`,
 *    `CompactHeadroomSettingTest`), and claiming otherwise here would be exactly
 *    the "假防" this work exists to remove.
 *
 * It is written in the same spirit as [ScrollFollowResumeWiringSourceTest]: match
 * against `KotlinSourceText.code`, which blanks comments and string literals, so a
 * commented-out call cannot satisfy the assertion; and assert over a **bounded
 * window around an anchor** rather than a whole line, so a formatter may move the
 * bytes without the assertion losing its meaning.
 *
 * ## Why a window, and not just `contains("appendToHistory(")`
 *
 * A bare `contains` is also satisfied by a call that no longer consumes the
 * injection decision — left behind while `beforeModelCall` is what got deleted, or
 * moved to a neighbouring function. Anchoring on the decision and requiring the
 * append inside its window checks "the prompt this round computed is the prompt
 * this round appends", which is the property that fails when the two lines are
 * removed.
 */
class InjectedPromptAndCompactWiringSourceTest {

    private fun source(relative: String): String = sequenceOf(
        File(relative),
        File("app/$relative"),
    ).first { it.isFile }.readText()

    private fun indexes(haystack: String, needle: String): List<Int> {
        val found = mutableListOf<Int>()
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return found
            found += at
            from = at + needle.length
        }
    }

    /** `span` characters of [text] starting at [from], bounded by the end of the file. */
    private fun window(text: String, from: Int, span: Int) =
        text.substring(from, minOf(text.length, from + span))

    // ── [R7] the injected prompt must be appended to the history ─────────────

    private fun assertInjectionReachesHistory(relative: String, historyName: String) {
        val code = KotlinSourceText.code(source(relative))
        val lets = indexes(code, "injection.prompt?.let {")
        assertTrue(
            "$relative: expected the per-round injection decision to be consumed by a " +
                "`injection.prompt?.let {` — the anchor itself may have been deleted",
            lets.isNotEmpty(),
        )
        for (at in lets) {
            val decisionWindow = window(code, at, 900)
            assertTrue(
                "$relative: the decision this round computed must be appended to the " +
                    "history, otherwise `injection.prompt` is read by nobody and the agent " +
                    "never receives the instruction. Window: ${decisionWindow.take(300)}",
                decisionWindow.contains("appendToHistory("),
            )
            // …and it must be appended to the list the request is built from, not to
            // some other list that never reaches a provider.
            val appendAt = decisionWindow.indexOf("appendToHistory(")
            val arguments = window(decisionWindow, appendAt, 120)
            assertTrue(
                "$relative: the injection must be appended to `$historyName` — the list the " +
                    "request is built from. Window: ${arguments.take(120)}",
                arguments.contains(historyName),
            )

            // ── The guard this file was missing ─────────────────────────────────
            //
            // The first version of this test only asked "does the window contain an
            // append call". Measured: wrapping the call in `if (prompt.isEmpty())` —
            // i.e. a guard under which it can never append — kept this file GREEN
            // (mutation `R7d`, tests=10 failures=0). A source-shape assertion that
            // cannot see a dead branch is the same class of defect as a behavioural
            // test that cannot fail.
            //
            // So: the append must be the FIRST thing the `let` block does, with
            // nothing but a receiver expression in front of it. No `if`, `when`,
            // `takeIf`, boolean operator or loop may sit between the decision and the
            // call — which is what a call "left behind inside a branch that never
            // runs" looks like.
            val blockStart = decisionWindow.indexOf('{', decisionWindow.indexOf("injection.prompt?.let"))
            assertTrue("$relative: could not find the body of injection.prompt?.let", blockStart >= 0)
            val between = decisionWindow.substring(blockStart + 1, appendAt)
            val guards = listOf("if ", "if(", "when ", "when(", "else", "&&", "||", "takeIf", "for ", "while ", "return")
            val found = guards.filter { Regex("(?<![\\p{L}\\p{N}_])" + Regex.escape(it.trim()) + "(?![\\p{L}\\p{N}_])").containsMatchIn(between) }
            assertTrue(
                "$relative: the append must be the first statement the decision's `let` " +
                    "performs — found ${found} between the decision and the call, which is " +
                    "how a call ends up in a branch that never runs. Between: " +
                    between.take(200),
                found.isEmpty(),
            )
        }
    }

    @Test
    fun `the child agent appends the injected prompt to its request messages`() {
        assertInjectionReachesHistory(
            "src/main/java/com/openminis/app/feature/runtime/RuntimeChildRunner.kt",
            "responseMessages",
        )
    }

    @Test
    fun `the main agent appends the injected prompt to its agent history`() {
        assertInjectionReachesHistory(
            "src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt",
            "agentHistory",
        )
    }

    // ── [R5] the compact reserve must be scaled through the tested mapping ────

    @Test
    fun `both compact checks scale the stored setting through the tested mapping`() {
        val relative = "src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt"
        val code = KotlinSourceText.code(source(relative))

        // Two `compactHeadroomTokens = …` arguments, one per context check
        // (`checkContextBeforeSend`, `inLoopContextCheck`). Losing one means the
        // in-loop exit path stops honouring the user's setting.
        val sites = indexes(code, "compactHeadroomTokens =")
        assertEquals(
            "$relative: expected exactly two compactHeadroomTokens call sites " +
                "(checkContextBeforeSend and inLoopContextCheck)",
            2,
            sites.size,
        )

        for (at in sites) {
            val siteWindow = window(code, at, 260)
            assertTrue(
                "$relative: the reserve must be produced by the tested mapping " +
                    "`compactHeadroomTokensFromSetting(…)`, not by a fresh inline " +
                    "expression — an inlined `takeIf { it > 0 }?.times(1_000)` here would be " +
                    "behaviour no test executes. Window: ${siteWindow.take(200)}",
                siteWindow.contains("compactHeadroomTokensFromSetting("),
            )
        }

        // …and the mapping's result must still be handed to a real ContextPolicy, so
        // the value is not merely computed and dropped.
        val policies = indexes(code, "ContextPolicy.forContextWindow(")
        val withReserve = policies.count { at ->
            window(code, at, 400).contains("compactHeadroomTokens")
        }
        assertEquals(
            "$relative: the reserve must reach ContextPolicy.forContextWindow at both checks",
            2,
            withReserve,
        )
    }
}
