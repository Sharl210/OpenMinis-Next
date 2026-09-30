package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Long-press selection boundaries for table cells vs prose.
 *
 * A table cell selects in FULL; prose keeps the sentence-level expansion. The
 * distinction matters because most cells are punctuation-free ("Alice Smith"),
 * so the sentence scan happens to grab the whole cell and hides the bug — until
 * a cell contains a comma or a period ("1,200", "v1.2 beta"), where the scan
 * stops mid-cell and the user gets a fragment of the thing they pressed.
 *
 * ## Why this file calls production instead of re-implementing it
 *
 * It used to define two private helpers — `atomicBounds` and `sentenceBounds` —
 * described in their own comments as "Mirrors SelectionController…" and "Mirrors
 * SelectionController.wordBoundsAt". Every assertion here was made against those
 * *copies*, so the file exercised **none** of the production code it names, and
 * the copies had silently drifted from it: the prose copy omitted production's
 * degenerate-range fallback, so for blank input it returned an inverted range
 * (`2 to 1`) and threw `StringIndexOutOfBoundsException` the moment it was used
 * to take a substring. Nobody could notice, because no assertion compared the
 * two. That is the failure mode this rewrite removes.
 *
 * Both rules are now reachable from a plain JVM test:
 *  - `atomicUnitBounds` was extracted out of `beginSelectionWord`'s inline
 *    atomic branch (the branch itself still needs a registered [TextShard]).
 *  - `wordBoundsAt` was widened from `private` to `internal`; it takes only a
 *    String and an Int.
 *
 * The last test pins the exact behaviour the old copy got wrong, so a
 * reintroduced copy fails here rather than shipping.
 */
class AtomicCellSelectionTest {

    private val controller = SelectionController()

    private fun atomicBounds(text: String): Pair<Int, Int> =
        controller.atomicUnitBounds(text) ?: (0 to 0)

    /** The real sentence-expansion rule, executed rather than mirrored. */
    private fun sentenceBounds(text: String, offset: Int): Pair<Int, Int> =
        controller.wordBoundsAt(text, offset)

    private fun select(text: String, bounds: Pair<Int, Int>) =
        text.substring(bounds.first, bounds.second)

    // ── atomic units (table cells) ───────────────────────────────────────

    @Test
    fun `a cell containing punctuation still selects in full`() {
        // This is the case the sentence scan gets wrong.
        val cell = "1,200"
        assertEquals("1,200", select(cell, atomicBounds(cell)))
        assertEquals(
            "sentence expansion stops at the comma — the cell path must not",
            "1,", select(cell, sentenceBounds(cell, 0)),
        )
    }

    @Test
    fun `a cell with a version string selects in full`() {
        val cell = "v1.2 beta"
        assertEquals("v1.2 beta", select(cell, atomicBounds(cell)))
    }

    @Test
    fun `a punctuation-free cell selects in full either way`() {
        val cell = "Alice Smith"
        assertEquals("Alice Smith", select(cell, atomicBounds(cell)))
        assertEquals("Alice Smith", select(cell, sentenceBounds(cell, 3)))
    }

    @Test
    fun `a CJK cell selects in full`() {
        val cell = "张三，项目经理"
        assertEquals("张三，项目经理", select(cell, atomicBounds(cell)))
    }

    /** Surrounding whitespace is trimmed so the highlight hugs the content. */
    @Test
    fun `padding around cell text is not selected`() {
        val cell = "  spaced value  "
        assertEquals("spaced value", select(cell, atomicBounds(cell)))
    }

    @Test
    fun `an empty or blank cell yields an empty range`() {
        assertEquals(0 to 0, atomicBounds(""))
        assertEquals(0 to 0, atomicBounds("   "))
    }

    /**
     * A blank cell must be distinguishable from a cell that merely starts at 0,
     * which is why the production function returns `null` rather than `0 to 0`:
     * the caller uses that to decide between "select the cell" and "leave a
     * caret". The old copy collapsed both into `0 to 0` and could not express it.
     */
    @Test
    fun `a blank cell reports no atomic range at all`() {
        assertNull(controller.atomicUnitBounds(""))
        assertNull(controller.atomicUnitBounds("   "))
        assertNull(controller.atomicUnitBounds("\n\t"))
        assertEquals(0 to 3, controller.atomicUnitBounds("abc "))
    }

    /** The press offset is irrelevant for a cell — the whole cell is the unit. */
    @Test
    fun `selection is independent of where inside the cell the press landed`() {
        val cell = "alpha, beta, gamma"
        val expected = select(cell, atomicBounds(cell))
        for (offset in cell.indices) {
            assertEquals("offset=$offset", expected, select(cell, atomicBounds(cell)))
        }
        assertEquals("alpha, beta, gamma", expected)
    }

    // ── prose (sentence-level expansion) ─────────────────────────────────

    /** Prose must NOT become atomic — the sentence behaviour is still wanted. */
    @Test
    fun `prose keeps sentence-level expansion`() {
        val prose = "Hello world. Second sentence here."
        assertEquals("Hello world.", select(prose, sentenceBounds(prose, 2)))
    }

    /**
     * Regression pin for the drift described in the class comment.
     *
     * The deleted copy of `wordBoundsAt` returned `2 to 1` for this input — an
     * inverted range — because it lacked production's `hi <= lo` fallback.
     * Asserting the *production* range here means any future copy that omits the
     * fallback fails on this test instead of silently disagreeing with shipped
     * behaviour. A `lo > hi` result is checked separately from the expected pair
     * so the failure message says which property broke.
     */
    @Test
    fun `blank input never yields an inverted range`() {
        for (text in listOf("", " ", "   ", "\n", "\n\n", "\t \t")) {
            for (offset in 0..text.length + 1) {
                val (lo, hi) = sentenceBounds(text, offset)
                assertTrueMsg(
                    "blank text=${text.replace("\n", "\\n")} offset=$offset gave ($lo, $hi)",
                    lo <= hi,
                )
            }
        }
        // And a non-blank sample, so this cannot pass by returning (0, 0) always.
        // The stop character is part of the selection, so this is "A。" not "A".
        assertEquals("A。", select("A。B", sentenceBounds("A。B", 0)))
    }

    private fun assertTrueMsg(message: String, condition: Boolean) {
        if (!condition) throw AssertionError(message)
    }
}
