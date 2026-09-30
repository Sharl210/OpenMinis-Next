package com.openminis.app.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The judge, judged. Every rule in [KotlinSourceText] is exercised against **hand
 * written samples** — one that must pass and one that must fail — before any
 * source-shape test is allowed to rely on it.
 *
 * That ordering is the whole point: a shape assertion built on an unverified
 * matcher reports "0 problems" for the same reason a broken smoke detector reports
 * "no fire". Each case below names the failure it exists to prevent, and the two
 * marked MEASURED are the ones that were actually observed on a fake source tree
 * during the audit that produced this object.
 */
class KotlinSourceTextTest {

    // ── the two masks ────────────────────────────────────────────────────────

    @Test
    fun `code drops comment bodies and literal bodies but keeps offsets`() {
        val src = """
            fun f() {
                // if (guard) return   <-- MEASURED: this satisfied a shape regex
                val s = "if (guard) return"
                if (guard) return
            }
        """.trimIndent()

        val masked = KotlinSourceText.code(src)
        assertEquals("the mask must preserve length", src.length, masked.length)
        assertEquals("the mask must preserve newlines", src.count { it == '\n' }, masked.count { it == '\n' })
        assertEquals("only the real branch may survive", 1, Regex("if \\(guard\\) return").findAll(masked).count())
        assertTrue("the mask must still show the real branch", masked.contains("if (guard) return"))
        assertFalse("the literal body must not survive", masked.contains("\"if (guard) return\""))
    }

    @Test
    fun `noComments keeps literals, because some names exist only as strings`() {
        val src = """
            // "delete_subtree" mentioned in prose
            val name = "delete_subtree"
        """.trimIndent()

        val masked = KotlinSourceText.noComments(src)
        assertEquals("the literal must survive this mask", 1, Regex("\"delete_subtree\"").findAll(masked).count())
        assertFalse("the comment must not", masked.contains("mentioned in prose"))
        assertEquals(src.length, masked.length)
    }

    @Test
    fun `a pathological sample with nested literals stays balanced`() {
        // Every construct here exists to break a naive scanner: an escaped quote, an
        // embedded apostrophe, a brace in a char literal, a triple-quoted block.
        // Built line by line rather than as one raw string, because a raw string
        // cannot contain the very construct it is meant to exercise.
        val src = listOf(
            "val a = \"he said \\\"hi\\\"\"",
            "val b = \"it's fine\"",
            "val c = '\\''",
            "val d = '{'",
            "val e = \"\"\"raw { }\"\"\"",
            "fun f() { }",
        ).joinToString("\n")

        val masked = KotlinSourceText.code(src)
        assertEquals(src.length, masked.length)
        assertEquals("braces in literals must not survive", 1, masked.count { it == '{' })
        assertEquals(1, masked.count { it == '}' })
        assertNotNull("the real declaration must still be findable", KotlinSourceText.bracedBlock(masked, "fun f("))
    }

    // ── reachability scanning ────────────────────────────────────────────────

    @Test
    fun `references catches the forms that escaped narrower scanners`() {
        val src = """
            import com.openminis.app.x.RuntimeSessionCoordinator.purgeSubtrees as zap
            fun purgeSubtrees(a: Int) { }
            fun caller(c: Any) {
                // c.purgeSubtrees(a)   <-- MEASURED: this satisfied a shape test
                val f = c::purgeSubtrees
                zap(c, 1)
                c.purgeSubtrees(1, "why")
            }
        """.trimIndent()

        val refs = KotlinSourceText.references(src, "purgeSubtrees")
        val lines = refs.map { it.line }
        assertFalse("the declaration is not a reference", lines.contains(2))
        assertFalse("a mention inside a comment is not reachability", lines.contains(4))
        assertTrue("an import alias must be caught (it is the only place the name appears)", lines.contains(1))
        assertTrue("a function reference must be caught", lines.contains(5))
        assertTrue("a nested call must be caught", lines.contains(7))
        // The exact set, not "at least these" and not a rewrite of the same
        // expression on both sides: line 6 calls the *alias*, so the name does not
        // appear there and must not be reported, and a scanner that over-reports is
        // as wrong as one that misses a form (it would flag renames that are fine).
        assertEquals("references are exactly the executable mentions", listOf(1, 5, 7), lines)
        assertEquals("the reported text is the identifier itself", "purgeSubtrees", refs.first().text)
    }

    @Test
    fun `references is not fooled by a name that only appears in a literal`() {
        val src = """
            fun note() = "purgeSubtrees( would be a breach"
        """.trimIndent()

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

    // ── argument counting ────────────────────────────────────────────────────

    @Test
    fun `arguments counts arguments, not commas`() {
        assertEquals(1, KotlinSourceText.arguments("f(minOf(a, b))", "f").size)
        assertEquals(2, KotlinSourceText.arguments("f(a,\n        b,)", "f").size)
        assertEquals(1, KotlinSourceText.arguments("f(\"a, b\")", "f").size)
        assertEquals(
            2,
            KotlinSourceText.arguments("f(maxOf(1, minOf(2, g(a, b))), h(i, j))", "f").size,
        )
    }

    @Test
    fun `arguments finds the call even when a string in it contains a paren`() {
        assertEquals(
            2,
            KotlinSourceText.arguments("""f(")", g(x))""", "f").size,
        )
    }

    // ── blocks and parameters ────────────────────────────────────────────────

    @Test
    fun `bracedBlock stops at the matching brace even when a literal holds one`() {
        val src = """
            fun outer() {
                when (x) {
                    '{' -> depth++
                    '}' -> depth--
                }
            }
            fun after() { }
        """.trimIndent()

        val block = KotlinSourceText.bracedBlock(src, "fun outer(")
        assertNotNull(block)
        assertTrue("the whole body must be inside the block", block!!.contains("'}' -> depth--"))
        assertFalse("a sibling declaration must not be", block.contains("fun after("))
        assertNull("a missing marker must yield null, not an exception", KotlinSourceText.bracedBlock(src, "fun nope("))
    }

    @Test
    fun `parameterList and parametersOfType find the nullable snapshot parameter`() {
        val src = """
            private fun build(
                request: RuntimeDelegationRequest,
                capabilities: AgentCapabilitySnapshot?,
                childSessionId: String,
            ): String = buildString { }
        """.trimIndent()

        val params = KotlinSourceText.parameterList(src, "build")
        assertNotNull(params)
        assertEquals(3, KotlinSourceText.splitTopLevel(params!!).size)
        assertEquals(
            listOf("capabilities"),
            KotlinSourceText.parametersOfType(params, "AgentCapabilitySnapshot?"),
        )
        assertNull("an absent function must yield null", KotlinSourceText.parameterList(src, "nope"))
    }

    @Test
    fun `lineOf agrees with the mask`() {
        val src = "a\nb\nc"
        assertEquals(1, KotlinSourceText.lineOf(src, 0))
        assertEquals(2, KotlinSourceText.lineOf(src, 2))
        assertEquals(3, KotlinSourceText.lineOf(src, 4))
    }
}
