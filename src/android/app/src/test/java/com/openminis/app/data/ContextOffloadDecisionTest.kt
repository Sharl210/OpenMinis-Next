package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The offload decision itself: `ContextPolicy.shouldOffload`.
 *
 * ## Why this file exists
 *
 * This predicate had NO test, while the decision it expresses was live — as an
 * inline copy in `ChatViewModel.offloadContextIfNeeded`:
 *
 * ```
 * if (!force && policy.offloadThreshold == 0) return
 * ...
 * if (!force && effectiveTokens < policy.offloadThreshold) return
 * ```
 *
 * That pair of guards is `!force && !policy.shouldOffload(effectiveTokens)`
 * written out by hand, so the shared predicate and the call site could disagree
 * with nothing going red: `shouldOffload` is not referenced anywhere else in the
 * repo, and a test that asserts the shared function says nothing about the code
 * that actually runs. These cases pin the predicate (and its tier boundaries),
 * which is what a future replacement of that copy has to keep.
 *
 * Not asserted here, and deliberately: `effectiveTokens` (the caller prefers its
 * own last-context-token measurement and only estimates when it has none) and
 * `force` (post-compact paths offload regardless of headroom). Both are call-site
 * policy; what is pinned is the threshold comparison they wrap.
 */
class ContextOffloadDecisionTest {

    // ─── the tier boundaries ─────────────────────────────────────────────

    @Test
    fun `the trigger sits exactly on the tier threshold`() {
        // 128K+ tier: threshold = window - 40_000.
        val policy = ContextPolicy.forContextWindow(128_000)
        assertEquals(88_000, policy.offloadThreshold)
        assertFalse("one token below the line is not enough", policy.shouldOffload(87_999))
        assertTrue("the line itself triggers", policy.shouldOffload(88_000))
        assertTrue("past the line keeps triggering", policy.shouldOffload(200_000))
    }

    @Test
    fun `the 64K-128K tier uses its own headroom`() {
        val policy = ContextPolicy.forContextWindow(100_000)
        assertEquals(80_000, policy.offloadThreshold)
        assertFalse(policy.shouldOffload(79_999))
        assertTrue(policy.shouldOffload(80_000))
    }

    @Test
    fun `the 32K-64K tier offloads but never compacts`() {
        val policy = ContextPolicy.forContextWindow(48_000)
        assertEquals(38_000, policy.offloadThreshold)
        assertEquals("offload-only tier", 0, policy.compactThreshold)
        assertFalse(policy.shouldOffload(37_999))
        assertTrue(policy.shouldOffload(38_000))
    }

    // ─── what "offload disabled" means ───────────────────────────────────

    @Test
    fun `the small-window tier never offloads anything`() {
        val policy = ContextPolicy.forContextWindow(16_000)
        assertEquals("0 disables offload", 0, policy.offloadThreshold)
        assertFalse(policy.shouldOffload(0))
        assertFalse(policy.shouldOffload(15_000))
        // The tier's only escape hatch is the caller's force flag; a threshold of
        // 0 must not behave like "everything is over the line".
        assertFalse(policy.shouldOffload(Int.MAX_VALUE))
    }

    @Test
    fun `a hand-built policy with a zero threshold never offloads`() {
        val policy = ContextPolicy(
            offloadThreshold = 0,
            offloadTarget = 0,
            compactThreshold = 0,
            exhaustedOnly = true,
            manualCompactAllowed = false,
        )
        assertFalse(policy.shouldOffload(Int.MAX_VALUE))
    }

    @Test
    fun `crossing the threshold is monotone in the token estimate`() {
        // A higher estimate must never flip the decision back off: the caller
        // recomputes `effectiveTokens` between turns, and a threshold that
        // flickered would offload one turn and not the next.
        for (window in listOf(32_000, 48_000, 100_000, 128_000, 200_000)) {
            val policy = ContextPolicy.forContextWindow(window)
            var seenTriggered = false
            for (tokens in 0..window step 500) {
                val now = policy.shouldOffload(tokens)
                if (seenTriggered) {
                    assertTrue("window=$window tokens=$tokens went back below the line", now)
                }
                seenTriggered = seenTriggered || now
            }
        }
    }
}
