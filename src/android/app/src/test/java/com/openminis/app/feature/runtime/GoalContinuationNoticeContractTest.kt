package com.openminis.app.feature.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-goal-auto-continuation] Contract between the goal auto-continuation
 * notice strings and the call sites that format them.
 *
 * Both notices carry positional placeholders (`%1$d/%2$d`). Two failure modes are
 * invisible in review but fatal or misleading at runtime, and neither is caught
 * by the locale parity test (which only checks that a *translation* keeps the
 * placeholders of the default string — it says nothing about whether the Kotlin
 * caller supplies them):
 *
 *  1. a string declares more placeholders than a call site passes → `IllegalFormat`
 *     / `MissingFormatArgumentException` thrown while building the card, i.e. the
 *     user sees no notice at all exactly when the mode gives up;
 *  2. fewer, or extra args → the numbers silently render as the wrong quantity
 *     ("5/1" instead of "1/5") in the one message whose entire job is to explain
 *     how many attempts were spent.
 *
 * So the arity is asserted against the call sites here, and the *meaning* of the
 * two numbers (used / budget) is asserted arithmetically in
 * [GoalAutoContinuationPolicyTest] — a swapped pair of ints is not detectable from
 * text, but it is detectable from the counts.
 */
class GoalContinuationNoticeContractTest {

    private fun source(relative: String): String {
        val candidates = listOf(
            File(relative),
            File("app/$relative"),
            File("upstream/src/android/app/$relative"),
            File("../$relative"),
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("$relative not found; run from the module or repo root")
    }

    private val stringsXml: String by lazy { source("src/main/res/values/strings.xml") }

    private val chatViewModel: String by lazy { source("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt") }

    /** The body of one `<string name="...">…</string>`. */
    private fun stringBody(key: String): String {
        val match = Regex("<string\\s[^>]*\\bname=\"$key\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .find(stringsXml)
        assertNotNull("values/strings.xml must declare $key", match)
        return match!!.groupValues[1]
    }

    /** `%1$d`-style placeholders in the order they appear in the text. */
    private fun placeholders(body: String): List<Int> =
        Regex("%(\\d+)\\$[sdf]").findAll(body).map { it.groupValues[1].toInt() }.toList()

    /**
     * Replaces comment bodies and string/char literal bodies with spaces, keeping
     * offsets and newlines. Used by the argument splitter and the parenthesis
     * scanner below: a `,` or `)` inside a literal is data, not syntax.
     */
    private fun stripLiterals(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            when {
                c == '/' && next == '/' -> {
                    while (i < text.length && text[i] != '\n') {
                        out.append(' ')
                        i++
                    }
                }
                c == '/' && next == '*' -> {
                    out.append("  ")
                    i += 2
                    while (i < text.length && !(text[i] == '*' && text.getOrNull(i + 1) == '/')) {
                        out.append(if (text[i] == '\n') '\n' else ' ')
                        i++
                    }
                    if (i < text.length) {
                        out.append("  ")
                        i += 2
                    }
                }
                c == '"' && text.startsWith("\"\"\"", i) -> {
                    out.append("   ")
                    i += 3
                    while (i < text.length && !text.startsWith("\"\"\"", i)) {
                        out.append(if (text[i] == '\n') '\n' else ' ')
                        i++
                    }
                    if (i < text.length) {
                        out.append("   ")
                        i += 3
                    }
                }
                c == '"' || c == '\'' -> {
                    val quote = c
                    out.append(' ')
                    i++
                    while (i < text.length && text[i] != quote && text[i] != '\n') {
                        if (text[i] == '\\') {
                            out.append(' ')
                            i++
                        }
                        out.append(' ')
                        i++
                    }
                    if (i < text.length && text[i] == quote) {
                        out.append(' ')
                        i++
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /**
     * Every `getString(R.string.<key>, <args…>)` call, as the literal argument list
     * after the key. Scanned from each `getString(` backwards-of-the-key rather
     * than from the key forwards: the key sits *inside* the call, so searching
     * forward from it lands on the next unrelated `(` (that mistake produced three
     * false failures on the first run of this test — the scanner, not the code, was
     * wrong).
     *
     * The parenthesis scan runs on [stripLiterals]' output, so a `)` inside a
     * string argument cannot close the call early.
     */
    private fun callSites(key: String): List<String> {
        val needle = "R.string.$key"
        val code = stripLiterals(chatViewModel)
        val out = mutableListOf<String>()
        var index = code.indexOf("getString(")
        while (index >= 0) {
            val open = index + "getString".length
            var depth = 0
            var cursor = open
            while (cursor < code.length) {
                when (code[cursor]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) break
                    }
                }
                cursor++
            }
            assertTrue(
                "could not find the end of the getString( call starting at offset $index — the " +
                    "scanner, not the code, is wrong",
                cursor < code.length,
            )
            // The *original* text, so the caller sees real argument expressions.
            val body = chatViewModel.substring(open + 1, cursor)
            val at = body.indexOf(needle)
            if (at >= 0) {
                out += body.substring(at + needle.length).trimStart(',', ' ', '\n')
            }
            index = code.indexOf("getString(", index + 1)
        }
        assertTrue("no getString call formats $key", out.isNotEmpty())
        return out
    }

    /** Splits an argument list on commas that are not nested in brackets or literals. */
    private fun splitArguments(args: String): List<String> {
        val code = stripLiterals(args)
        val out = mutableListOf<String>()
        var depth = 0
        var start = 0
        for (i in code.indices) {
            when (code[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) {
                    out += args.substring(start, i)
                    start = i + 1
                }
            }
        }
        out += args.substring(start)
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * How many arguments the call site actually passes.
     *
     * This used to be `args.split(',').count { … }`, which counts the commas of a
     * *nested* expression too: `getString(R.string.x, minOf(a, b))` was counted as
     * 2 with one placeholder, so the assertion could not see the arity error it
     * exists for. Kotlin's trailing comma is not an argument; nor is the empty
     * string between two commas that a stray blank line can leave behind.
     */
    private fun argCount(args: String): Int = splitArguments(args).size

    @Test
    fun `the in-flight notice takes exactly its two placeholders`() {
        val placeholders = placeholders(stringBody("chat_goal_auto_continuing"))
        assertEquals("chat_goal_auto_continuing must read as %1\$d/%2\$d", listOf(1, 2), placeholders)

        val sites = callSites("chat_goal_auto_continuing")
        assertEquals("chat_goal_auto_continuing must have exactly one call site", 1, sites.size)
        assertEquals(
            "the call site must pass one argument per placeholder, in the declared order " +
                "(used+1, budget): ${sites[0]}",
            placeholders.size,
            argCount(sites[0]),
        )
        assertTrue("the first argument must be the attempt number", sites[0].contains("decision.used + 1"))
        assertTrue("the second argument must be the ceiling", sites[0].contains("decision.budget"))
    }

    @Test
    fun `the hand-over notice takes exactly its two placeholders on every call site`() {
        val placeholders = placeholders(stringBody("chat_goal_auto_continuation_stopped"))
        assertEquals("chat_goal_auto_continuation_stopped must read as %1\$d/%2\$d", listOf(1, 2), placeholders)

        val sites = callSites("chat_goal_auto_continuation_stopped")
        assertEquals(
            "two call sites build this card (the init-time notice and the run-end hand-over)",
            2,
            sites.size,
        )
        for (site in sites) {
            assertEquals(
                "every call site must pass one argument per placeholder: $site",
                placeholders.size,
                argCount(site),
            )
            assertTrue("the first argument must be the spent count: $site", site.contains("decision.used"))
            assertTrue("the second argument must be the ceiling: $site", site.contains("decision.budget"))
            assertTrue(
                "the pair must not be swapped (both are Ints, so only the source shows the order): $site",
                site.indexOf("decision.used") < site.indexOf("decision.budget"),
            )
        }
    }

    @Test
    fun `the scanner counts arguments, not commas`() {
        // Seven of the eight assertions in this file are `argCount(...) ==
        // placeholders.size`. A counter that splits on every comma makes them all
        // unsound in the same direction: a nested call is counted as several
        // arguments, so a call site that passes *one* argument where the string
        // wants two — a MissingFormatArgumentException, i.e. no notice at the one
        // moment the mode gives up — is counted as two and stays green.
        assertEquals("a nested call is one argument", 1, argCount("minOf(decision.used, decision.budget)"))
        assertEquals("two plain arguments", 2, argCount("decision.used + 1,\n                decision.budget,"))
        assertEquals("trailing comma is not an argument", 2, argCount("a, b,"))
        assertEquals("a comma inside a string is data", 1, argCount("\"a, b\""))
        assertEquals("a comma inside a nested string is data", 1, argCount("format(\"a,b\", x)"))
        assertEquals("no arguments at all", 0, argCount(""))
        assertEquals("no arguments at all, with whitespace", 0, argCount("   \n  "))
        assertEquals(
            "three nesting levels",
            2,
            argCount("maxOf(1, minOf(2, f(a, b))), g(h(i, j), k)"),
        )
    }

    @Test
    fun `every call site passes exactly one argument per placeholder`() {
        for (key in listOf("chat_goal_auto_continuing", "chat_goal_auto_continuation_stopped")) {
            val placeholders = placeholders(stringBody(key))
            val sites = callSites(key)
            assertTrue("$key must have at least one call site", sites.isNotEmpty())
            for (site in sites) {
                assertEquals(
                    "$key declares ${placeholders.size} placeholder(s) but this call site does not " +
                        "pass exactly that many arguments — an under-supplied format string throws " +
                        "MissingFormatArgumentException while the card is being built, and an " +
                        "over-supplied one throws too: $site",
                    placeholders.size,
                    argCount(site),
                )
                assertFalse(
                    "$key must be formatted BY getString, not pre-joined with '+', or the " +
                        "placeholder count above says nothing about the runtime format call: $site",
                    Regex("\"\\s*\\+").containsMatchIn(site),
                )
            }
        }
    }

    @Test
    fun `the plain interruption notice declares no placeholders`() {
        // It is formatted with no arguments; a placeholder added to it later would
        // throw at the moment the notice is shown.
        assertEquals(
            "chat_goal_interrupted must stay argument-free",
            emptyList<Int>(),
            placeholders(stringBody("chat_goal_interrupted")),
        )
        for (site in callSites("chat_goal_interrupted")) {
            assertEquals("no arguments may be passed for a placeholder-free string: $site", 0, argCount(site))
        }
    }
}
