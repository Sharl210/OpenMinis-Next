package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextPolicyUserHeadroomTest {
    @Test
    fun `zero keeps model default compact headroom`() {
        assertEquals(
            ContextPolicy.forContextWindow(128_000).compactThreshold,
            ContextPolicy.forContextWindow(128_000, 0).compactThreshold,
        )
    }

    @Test
    fun `positive setting overrides compact headroom in thousands`() {
        assertEquals(118_000, ContextPolicy.forContextWindow(128_000, 10_000).compactThreshold)
    }

    @Test
    fun `small windows keep compact disabled`() {
        assertEquals(0, ContextPolicy.forContextWindow(48_000, 10_000).compactThreshold)
    }

    @Test
    fun `headroom is clamped to safe quarter window`() {
        val policy = ContextPolicy.forContextWindow(128_000, 150_000)
        assertTrue(policy.compactThreshold >= 96_000)
    }

    // --- [T-android-compact-headroom-wording] direction of the setting ---
    //
    // The settings UI used to call this an absolute "阈值" ("compaction fires AT
    // this token count"), which tells the reader that raising it delays
    // compaction. It is a reserve, so raising it does the opposite. These pin
    // the real direction so the wording cannot drift away from the behaviour
    // again without a failing test.

    @Test
    fun `a larger setting compacts EARLIER because it is a reserve`() {
        val smallReserve = ContextPolicy.forContextWindow(128_000, 10_000).compactThreshold
        val largeReserve = ContextPolicy.forContextWindow(128_000, 30_000).compactThreshold
        assertTrue(
            "a bigger reserve must lower the trigger point, not raise it: " +
                "10K -> $smallReserve, 30K -> $largeReserve",
            largeReserve < smallReserve,
        )
    }

    @Test
    fun `the default setting behaves as a reserve rather than an absolute threshold`() {
        // Default is 150 (thousands) = 150_000, which is larger than a 128K
        // window. Read as an absolute threshold that would mean "never
        // compact"; read as a reserve it clamps to a quarter window and still
        // compacts. The reserve reading is the working one.
        val atDefault = ContextPolicy.forContextWindow(128_000, 150 * 1_000)
        assertTrue("must still compact", atDefault.compactThreshold in 1 until 128_000)
        assertEquals(128_000 - 32_000, atDefault.compactThreshold)
    }

    @Test
    fun `a setting larger than the whole window never disables compaction`() {
        // Guards the clamp: an over-large reserve must degrade, not invert into
        // "no compaction at all".
        val policy = ContextPolicy.forContextWindow(64_000, 999_000)
        assertTrue("compaction must stay enabled", policy.compactThreshold > 0)
        assertTrue(policy.compactThreshold < 64_000)
    }
}
