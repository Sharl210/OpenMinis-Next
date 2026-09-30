package com.openminis.app.shared

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The judge, judged. Every rule in [KotlinSourceText] is exercised against **hand
 * written samples** — one that must pass and one that must fail — before any
 * source-shape test is allowed to rely on it.
 *
 * That ordering is the whole point: a shape assertion built on an unverified
 * matcher reports "0 problems" for the same reason a broken smoke detector reports
 * "no fire".
 *
 * The cases marked **MEASURED** are the ones that were actually observed on a fake
 * source tree. The ones marked **REVIEW** came from an adversarial review that
 * attacked the first version of the tool with Kotlin constructs rather than with the
 * shapes it had been written for; each of them was green-or-wrong before the fix.
 */
class KotlinSourceTextTest {

    private fun lines(vararg parts: String): String = parts.joinToString("\n")

    /** Offsets taken from a mask are used to slice the original text, so the masks
     *  must not move anything. Checked on every sample below via [assertSameShape]. */
    private fun assertSameShape(src: String) {
        assertEquals("code() must preserve length", src.length, KotlinSourceText.code(src).length)
        assertEquals(
            "code() must preserve newlines",
            src.count { it == '\n' },
            KotlinSourceText.code(src).count { it == '\n' },
        )
        assertEquals(
            "noComments() must preserve length",
            src.length,
            KotlinSourceText.noComments(src).length,
        )
    }

    // ── the two masks ────────────────────────────────────────────────────────

    @Test
    fun `code drops comment bodies and literal bodies but keeps offsets`() {
        val src = lines(
            "fun f() {",
            "    // if (guard) return   <-- MEASURED: this satisfied a shape regex",
            "    val s = \"if (guard) return\"",
            "    if (guard) return",
            "}",
        )

        val masked = KotlinSourceText.code(src)
        assertSameShape(src)
        assertEquals("only the real branch may survive", 1, Regex("if \\(guard\\) return").findAll(masked).count())
        assertFalse("the literal body must not survive", masked.contains("\"if (guard) return\""))
    }

    @Test
    fun `noComments keeps literals, because some names exist only as strings`() {
        val src = lines(
            "// \"delete_subtree\" mentioned in prose",
            "val name = \"delete_subtree\"",
        )

        val masked = KotlinSourceText.noComments(src)
        assertSameShape(src)
        assertEquals("the literal must survive this mask", 1, Regex("\"delete_subtree\"").findAll(masked).count())
        assertFalse("the comment must not", masked.contains("mentioned in prose"))
    }

    // ── REVIEW: string templates are code, not opaque text ───────────────────

    @Test
    fun `a template's nested string does not leak into the mask`() {
        // REVIEW (F1). Before the fix the inner literal survived as *code*: a shape
        // test asking "does this file mention A_BRANCH" would have been satisfied by
        // prose living inside a string inside a template.
        //
        // The branch markers are distinctive on purpose: an earlier version of this
        // test asserted `!masked.contains("A")` and failed on `Boolean` — the test's
        // expectation was wrong, not the tool.
        val src = lines(
            "fun f(c: Boolean) {",
            "    val s = \"x\${if (c) \"A_BRANCH\" else \"B_BRANCH\"}y\"",
            "}",
        )

        val masked = KotlinSourceText.code(src)
        assertSameShape(src)
        assertFalse("a nested literal's body must not leak", masked.contains("A_BRANCH"))
        assertFalse("nor the other branch", masked.contains("B_BRANCH"))
        assertTrue("but the template's own structure is harmless to keep", masked.contains("val s ="))
    }

    @Test
    fun `a call inside a template is reachable`() {
        // REVIEW (F1/F8). Before the fix the whole tail of the line was eaten as
        // string text, so a real call reported ZERO references — the "must call X"
        // assertion would have been red for the wrong reason, and the "must not call
        // X" one would have been green while X was called.
        val src = lines(
            "fun f(n: Int) {",
            "    log(\"count: \${n.toString()}\")",
            "    log(\"\${items.joinToString(\", \")}\")",
            "}",
        )

        assertSameShape(src)
        assertEquals(1, KotlinSourceText.references(src, "toString").size)
        assertEquals(1, KotlinSourceText.references(src, "joinToString").size)
        // The plain text of the literal is still a literal: gone from the code mask,
        // present in the literal-quoting one. (An earlier version of this assertion
        // asked `code()` to contain it — which is the opposite of what `code` does.)
        assertFalse("literal text is not code", KotlinSourceText.code(src).contains("count:"))
        assertTrue("literal text is a literal", KotlinSourceText.noComments(src).contains("count:"))
    }

    @Test
    fun `a real call after a template on the same line survives`() {
        val src = "fun f() { val s = \"a\${b(\"c\")}d\"; purgeSubtrees(1) }"
        assertSameShape(src)
        assertEquals(1, KotlinSourceText.references(src, "purgeSubtrees").size)
    }

    // ── comments ─────────────────────────────────────────────────────────────

    @Test
    fun `block comments nest, as Kotlin says they do`() {
        // REVIEW (F6). Kotlin's block comments nest (verified with the compiler).
        // Stopping at the first `*/` leaked the tail back in as code.
        val src = lines(
            "fun f() {",
            "    /* outer /* inner */ purgeSubtrees(1) */",
            "    val a = 1",
            "}",
        )

        assertSameShape(src)
        assertEquals("a nested comment is all comment", 0, KotlinSourceText.references(src, "purgeSubtrees").size)
        assertTrue("the real statement survives", KotlinSourceText.code(src).contains("val a = 1"))
    }

    // ── literals that used to break the scanner ──────────────────────────────

    @Test
    fun `a pathological sample with nested literals stays balanced`() {
        val src = lines(
            "val a = \"he said \\\"hi\\\"\"",
            "val b = \"it's fine\"",
            "val c = '\\''",
            "val d = '{'",
            "val e = \"\"\"raw { }\"\"\"",
            "fun f() { }",
        )

        val masked = KotlinSourceText.code(src)
        assertSameShape(src)
        assertEquals("braces in literals must not survive", 1, masked.count { it == '{' })
        assertEquals(1, masked.count { it == '}' })
        assertNotNull("the real declaration must still be findable", KotlinSourceText.bracedBlock(masked, "fun f("))
    }

    @Test
    fun `an apostrophe inside a backtick identifier does not swallow the line`() {
        // REVIEW (F7). `fun `the user's values`()` is legal Kotlin (verified with the
        // compiler); reading the apostrophe as a char literal ate the rest of the line
        // and left the braces unbalanced.
        val src = lines(
            "class T {",
            "    fun `the user's values`() {",
            "        purgeSubtrees(1)",
            "    }",
            "}",
        )

        val masked = KotlinSourceText.code(src)
        assertSameShape(src)
        assertEquals("braces must stay balanced", masked.count { it == '{' }, masked.count { it == '}' })
        assertEquals(1, KotlinSourceText.references(src, "purgeSubtrees").size)
    }

    @Test
    fun `a raw string containing quotes and a template is erased`() {
        val src = lines(
            "val q = \"\"\"he said \"hi\" and \"\" twice\"\"\"",
            "val t = \"\"\"x \${if (c) \"A\" else \"B\"} y\"\"\"",
            "fun after() { purgeSubtrees(1) }",
        )

        assertSameShape(src)
        assertFalse("a nested literal inside a raw string's template must not leak", KotlinSourceText.code(src).contains("A"))
        assertEquals("the following declaration is still code", 1, KotlinSourceText.references(src, "purgeSubtrees").size)
    }

    // ── reachability scanning ────────────────────────────────────────────────

    @Test
    fun `references catches the forms that escaped narrower scanners`() {
        val src = lines(
            "import com.openminis.app.x.RuntimeSessionCoordinator.purgeSubtrees as zap",
            "fun purgeSubtrees(a: Int) { }",
            "fun caller(c: Any) {",
            "    // c.purgeSubtrees(a)   <-- MEASURED: this satisfied a shape test",
            "    val f = c::purgeSubtrees",
            "    zap(c, 1)",
            "    c.purgeSubtrees(1, \"why\")",
            "}",
        )

        val lines = KotlinSourceText.references(src, "purgeSubtrees").map { it.line }
        assertFalse("the declaration is not a reference", lines.contains(2))
        assertFalse("a mention inside a comment is not reachability", lines.contains(4))
        assertTrue("an import alias must be caught (it is the only place the name appears)", lines.contains(1))
        assertTrue("a function reference must be caught", lines.contains(5))
        assertTrue("a nested call must be caught", lines.contains(7))
        // The exact set, not "at least these". Line 6 calls the *alias*, so the name
        // does not appear there and must not be reported: a scanner that over-reports
        // is as wrong as one that misses a form, because it turns renames red.
        assertEquals("references are exactly the executable mentions", listOf(1, 5, 7), lines)
        assertEquals("the reported text is the identifier itself", "purgeSubtrees", KotlinSourceText.references(src, "purgeSubtrees").first().text)
    }

    @Test
    fun `references is not fooled by a name that only appears in a literal`() {
        val src = "fun note() = \"purgeSubtrees( would be a breach\""

        assertTrue(
            "a name inside a string literal is not a reference — this is the reverse probe for the " +
                "mask above: if this returned a reference, the reachability test would be doing " +
                "exactly what the old `contains` form did",
            KotlinSourceText.references(src, "purgeSubtrees").isEmpty(),
        )
        assertTrue(
            "but the literal-quoting mask must still find it",
            KotlinSourceText.noComments(src).contains("\"purgeSubtrees( would be a breach\""),
        )
    }

    @Test
    fun `references matches a non-ascii identifier whole`() {
        // REVIEW (F10): an ASCII `\b` stopped at the first accented letter, so a real
        // identifier never matched.
        assertEquals(1, KotlinSourceText.references("val café = 1", "café").size)
        assertTrue(KotlinSourceText.references("val café = 1", "caf").isEmpty())
    }

    @Test
    fun `references refuses an empty name instead of matching everything`() {
        assertThrows(IllegalArgumentException::class.java) {
            KotlinSourceText.references("val a = 1", "")
        }
    }

    // ── argument counting ────────────────────────────────────────────────────

    @Test
    fun `arguments counts arguments, not commas`() {
        assertEquals(1, KotlinSourceText.arguments("f(minOf(a, b))", "f").size)
        assertEquals(2, KotlinSourceText.arguments("f(a,\n        b,)", "f").size)
        assertEquals(1, KotlinSourceText.arguments("f(\"a, b\")", "f").size)
        assertEquals(2, KotlinSourceText.arguments("f(maxOf(1, minOf(2, g(a, b))), h(i, j))", "f").size)
    }

    @Test
    fun `arguments finds the call even when a string in it contains a paren`() {
        assertEquals(2, KotlinSourceText.arguments("""f(")", g(x))""", "f").size)
    }

    @Test
    fun `arguments requires a whole identifier, so a marker cannot match a longer name`() {
        // REVIEW (F9): `"f"` used to match the `f` in `if`/`printf`, returning the
        // wrong call's argument list with no signal at all.
        assertEquals(listOf("1"), KotlinSourceText.arguments("if (a) f(1)", "f"))
        assertThrows(AssertionError::class.java) {
            KotlinSourceText.arguments("printf(\"%d\", x)", "f")
        }
        assertThrows(IllegalArgumentException::class.java) {
            KotlinSourceText.arguments("f(minOf(a, b))", "f(")
        }
    }

    // ── splitting ────────────────────────────────────────────────────────────

    @Test
    fun `splitTopLevel does not split a generic type`() {
        // REVIEW (F5): ignoring angle brackets made `Map<String, List<Int>>` look like
        // three parameters, which made parametersOfType answer `[]` for exactly the
        // type it had been asked about.
        assertEquals(2, KotlinSourceText.splitTopLevel("m: Map<String, List<Int>>, b: Int").size)
        assertEquals(2, KotlinSourceText.splitTopLevel("a as Map<String, Int>, b").size)
        assertEquals(2, KotlinSourceText.splitTopLevel("Foo<A, B>, c").size)
    }

    @Test
    fun `splitTopLevel does not treat a spaced less-than as a generic`() {
        assertEquals(2, KotlinSourceText.splitTopLevel("a < b, c").size)
        assertEquals(2, KotlinSourceText.splitTopLevel("(Int, String) -> Unit, b").size)
        assertEquals(2, KotlinSourceText.splitTopLevel("\"a, b\", c").size)
    }

    // ── blocks and parameters ────────────────────────────────────────────────

    @Test
    fun `bracedBlock prefers the declaration over an earlier call site`() {
        // Found by probing the tool against the real ChatViewModel: the first
        // `resumeRun(` in that file is a *call* from the trigger that schedules it,
        // 14,000 lines above the declaration. Searching for the bare marker found the
        // call, saw no body after the argument list, and answered `null` for a
        // function with a perfectly good body.
        val src = lines(
            "class C {",
            "    fun caller() {",
            "        resumeRun(phase = \"x\")",
            "    }",
            "    private fun resumeRun(phase: String) {",
            "        if (phase.isEmpty()) return",
            "    }",
            "}",
        )

        val block = KotlinSourceText.bracedBlock(src, "resumeRun(")
        assertNotNull("a bare name must resolve to its declaration, not to its call site", block)
        assertTrue("the declaration is inside: $block", block!!.contains("private fun resumeRun("))
        assertTrue("and so is its body: $block", block.contains("if (phase.isEmpty()) return"))
    }

    @Test
    fun `bracedBlock stops at the matching brace even when a literal holds one`() {
        val src = lines(
            "fun outer() {",
            "    when (x) {",
            "        '{' -> depth++",
            "        '}' -> depth--",
            "    }",
            "}",
            "fun after() { }",
        )

        val block = KotlinSourceText.bracedBlock(src, "fun outer(")
        assertNotNull(block)
        assertTrue("the whole body must be inside the block", block!!.contains("'}' -> depth--"))
        assertFalse("a sibling declaration must not be", block.contains("fun after("))
        assertNull("a missing marker must yield null, not an exception", KotlinSourceText.bracedBlock(src, "fun nope("))
    }

    @Test
    fun `bracedBlock does not swallow the next function from an expression body`() {
        // REVIEW (F2). On the real ChatViewModel.kt the old form returned the body of
        // the *other* `sendMessage` overload — 19,939 characters — so "must call X
        // inside sendMessage" could be satisfied by a different function entirely.
        val src = lines(
            "class C {",
            "    fun f() = 42",
            "    fun after() { purgeSubtrees(1) }",
            "}",
        )

        val block = KotlinSourceText.bracedBlock(src, "fun f()")
        assertNotNull(block)
        assertTrue("the expression body is included: $block", block!!.contains("= 42"))
        assertFalse("the next declaration is not: $block", block.contains("purgeSubtrees"))
    }

    @Test
    fun `bracedBlock keeps a delegating overload out of the overload it delegates to`() {
        val src = lines(
            "class C {",
            "    fun sendMessage(text: String) = sendMessage(text, skipContextCheck = false)",
            "    private fun sendMessage(text: String, skipContextCheck: Boolean) {",
            "        work()",
            "    }",
            "}",
        )

        val oneArg = KotlinSourceText.bracedBlock(src, "fun sendMessage(text: String)")!!
        assertTrue("the delegate call belongs to the one-arg overload", oneArg.contains("skipContextCheck = false"))
        assertFalse("but the implementation does not", oneArg.contains("work()"))

        val twoArg = KotlinSourceText.bracedBlock(src, "sendMessage(text: String, skipContextCheck: Boolean)")!!
        assertTrue("the full signature pins the block-bodied overload", twoArg.contains("work()"))
    }

    @Test
    fun `bracedBlock starts after the parameter list, not before it`() {
        // REVIEW (F3): a lambda in a parameter default used to truncate the block
        // before the real body began.
        val src = lines(
            "fun f(cb: () -> Unit = { }) {",
            "    work()",
            "}",
        )

        val block = KotlinSourceText.bracedBlock(src, "fun f(")
        assertNotNull(block)
        assertTrue("the body must be inside the block: $block", block!!.contains("work()"))
    }

    @Test
    fun `bracedBlock follows an expression body past its own lambdas`() {
        val src = lines(
            "fun f() = run { step1() }.also { step2() }",
            "fun after() { purgeSubtrees(1) }",
        )

        val block = KotlinSourceText.bracedBlock(src, "fun f()")!!
        assertTrue("step2 is inside the expression: $block", block.contains("step2()"))
        assertFalse("the sibling is not: $block", block.contains("purgeSubtrees"))
    }

    @Test
    fun `parameterList and parametersOfType find the nullable snapshot parameter`() {
        val src = lines(
            "private fun build(",
            "    request: RuntimeDelegationRequest,",
            "    capabilities: AgentCapabilitySnapshot?,",
            "    childSessionId: String,",
            "): String = buildString { }",
        )

        val params = KotlinSourceText.parameterList(src, "build")
        assertNotNull(params)
        assertEquals(3, KotlinSourceText.splitTopLevel(params!!).size)
        assertEquals(listOf("capabilities"), KotlinSourceText.parametersOfType(params, "AgentCapabilitySnapshot?"))
        assertNull("an absent function must yield null", KotlinSourceText.parameterList(src, "nope"))
    }

    @Test
    fun `parameterList sees generics and extension receivers`() {
        // REVIEW (F4). Both forms returned null — and when a plain same-named
        // declaration followed, the *other* function's parameters came back instead,
        // which is worse than a miss because nothing signals it.
        val generic = "internal fun <T> runChildAttempt(prompt: String) { }"
        assertEquals(
            listOf("prompt"),
            KotlinSourceText.parametersOfType(KotlinSourceText.parameterList(generic, "runChildAttempt")!!, "String"),
        )

        val receiver = "private fun JsonObject.unwrapNested(key: String): JsonObject {"
        assertEquals(
            listOf("key"),
            KotlinSourceText.parametersOfType(KotlinSourceText.parameterList(receiver, "unwrapNested")!!, "String"),
        )

        val genericReceiver = "fun <T> Flow<T>.chunk(size: Int) { }"
        assertEquals(
            listOf("size"),
            KotlinSourceText.parametersOfType(KotlinSourceText.parameterList(genericReceiver, "chunk")!!, "Int"),
        )

        val overloads = lines("fun <T> f(generic: T) { }", "fun f(plain: String) { }")
        assertEquals(
            "the first declaration wins, not the first one the old regex happened to like",
            listOf("generic"),
            KotlinSourceText.parametersOfType(KotlinSourceText.parameterList(overloads, "f")!!, "T"),
        )
    }

    @Test
    fun `parametersOfType skips modifiers and annotations`() {
        // REVIEW (F12).
        val pl = "vararg items: String, suspend block: () -> Unit, @Named(\"a,b\") x: Int, val name: String"
        assertSameShape(pl)
        assertEquals(listOf("items", "name"), KotlinSourceText.parametersOfType(pl, "String"))
    }

    @Test
    fun `parametersOfType finds a generic type that contains a comma`() {
        assertEquals(
            listOf("m"),
            KotlinSourceText.parametersOfType("m: Map<String, List<Int>>, b: Int", "Map<String, List<Int>>"),
        )
    }

    @Test
    fun `lineOf agrees with the mask`() {
        val src = "a\nb\nc"
        assertEquals(1, KotlinSourceText.lineOf(src, 0))
        assertEquals(2, KotlinSourceText.lineOf(src, 2))
        assertEquals(3, KotlinSourceText.lineOf(src, 4))
    }

    // ── the invariant the callers actually depend on ─────────────────────────

    @Test
    fun `every production source keeps its shape under both masks`() {
        // Offsets are found in a mask and sliced from the original, so a mask that
        // moves a single character silently reports the wrong line. Checked over the
        // whole tree rather than on samples, because a lexer bug is exactly the kind
        // of thing that hides between the samples someone thought to write.
        val root = File("src/main/java")
        if (!root.isDirectory) return // not running from the module directory

        val sources = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("expected to find production sources under $root", sources.size > 50)

        val broken = sources.filter { file ->
            val text = file.readText()
            val c = KotlinSourceText.code(text)
            val n = KotlinSourceText.noComments(text)
            c.length != text.length ||
                c.count { it == '\n' } != text.count { it == '\n' } ||
                n.length != text.length ||
                n.count { it == '\n' } != text.count { it == '\n' }
        }
        assertEquals("masks must preserve length and line count", emptyList<File>(), broken)
    }
}
