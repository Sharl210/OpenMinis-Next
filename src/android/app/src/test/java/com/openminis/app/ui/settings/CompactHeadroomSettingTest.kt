package com.openminis.app.ui.settings

import com.openminis.app.data.ContextPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [R5] The user's compact-reserve setting must actually move the compaction
 * trigger.
 *
 * ## The defect this file pins
 *
 * `ChatViewModel` builds its `ContextPolicy` from the stored setting with the
 * expression
 *
 * ```kotlin
 * AgentBehaviorSettingsPrefs(context).load()
 *     .compactThresholdTokens.takeIf { it > 0 }?.times(1_000)
 * ```
 *
 * at two sites (`checkContextBeforeSend`, `inLoopContextCheck`). Measured before
 * this file existed: the string `compactThresholdTokens` appeared **zero** times
 * under `src/test` and `src/androidTest`. Deleting `.times(1_000)`, or reversing
 * `takeIf { it > 0 }` into `takeIf { it <= 0 }`, left the entire suite green —
 * the setting the user moves in Settings → Agent behaviour was connected to the
 * compaction trigger by two lines nobody was watching.
 *
 * ## What is executed
 *
 * The extraction `compactHeadroomTokensFromSetting` is the same expression, moved
 * where a JVM test can run it; both production sites now call it instead of
 * repeating the expression. `ChatViewModel` itself is not constructible on the
 * JVM (it needs an Android `Context`), which is why the mapping was extracted
 * rather than invoked.
 *
 * ## Why the assertions are two-sided
 *
 * `assertEquals(150_000, …)` alone would be satisfied by a `* 1_000` applied to
 * the wrong input. The tests therefore also drive the value into the real
 * [ContextPolicy] and assert the *classification* the agent loop acts on —
 * `NEEDS_COMPACT` at 100 000 tokens, `OK` at 90 000 — which is what "the setting
 * does something" means end to end. A third test pins the direction (a larger
 * reserve triggers compaction EARLIER), which is the property the settings copy
 * and the clamp both depend on.
 */
class CompactHeadroomSettingTest {

    @Test
    fun `the stored setting is thousands of tokens`() {
        assertEquals(150_000, compactHeadroomTokensFromSetting(150))
        assertEquals(10_000, compactHeadroomTokensFromSetting(10))
        assertEquals(1_000, compactHeadroomTokensFromSetting(1))
        assertEquals(
            "the shipped default must survive the mapping",
            150_000,
            compactHeadroomTokensFromSetting(AgentBehaviorSettingsPrefs.DEFAULT_COMPACT_THRESHOLD_TOKENS),
        )
        assertEquals(
            "the slider's maximum must survive the mapping",
            150_000,
            compactHeadroomTokensFromSetting(AgentBehaviorSettingsPrefs.MAX_COMPACT_THRESHOLD_TOKENS),
        )
    }

    @Test
    fun `zero means no user reserve rather than a reserve of zero tokens`() {
        // The settings screen renders 0 as `agent_behavior_off`. Read as a
        // number, 0 would be a reserve smaller than the 4 000 floor and would be
        // silently clamped UP into an active reserve — i.e. "off" would compact
        // more eagerly than any non-zero setting. It has to be null.
        assertNull(compactHeadroomTokensFromSetting(0))
        assertNull(compactHeadroomTokensFromSetting(-1))
    }

    @Test
    fun `the default setting compacts a real 128K conversation before the model runs out`() {
        val policy = ContextPolicy.forContextWindow(
            128_000,
            compactHeadroomTokensFromSetting(AgentBehaviorSettingsPrefs.DEFAULT_COMPACT_THRESHOLD_TOKENS),
        )
        assertEquals(ContextPolicy.CheckResult.NEEDS_COMPACT, policy.check(100_000, 128_000))
        assertEquals(ContextPolicy.CheckResult.OK, policy.check(90_000, 128_000))
    }

    @Test
    fun `reading the setting as raw tokens would be a different policy`() {
        // The guard against a silently-dropped `× 1_000`: without it the reserve
        // falls to the 4 000 floor, the trigger moves from 96 000 to 124 000, and
        // a 100 000-token turn that should have been compacted is sent as-is.
        val scaled = ContextPolicy.forContextWindow(128_000, compactHeadroomTokensFromSetting(150))
        val unscaled = ContextPolicy.forContextWindow(128_000, 150)
        assertEquals(128_000 - 32_000, scaled.compactThreshold)
        assertEquals(128_000 - 4_000, unscaled.compactThreshold)
        assertNotEquals(scaled.compactThreshold, unscaled.compactThreshold)
        assertEquals(ContextPolicy.CheckResult.NEEDS_COMPACT, scaled.check(100_000, 128_000))
        assertEquals(ContextPolicy.CheckResult.OK, unscaled.check(100_000, 128_000))
    }

    @Test
    fun `the reserve grows monotonically and still never disables compaction`() {
        val thresholds = listOf(1, 10, 50, 150).map { setting ->
            ContextPolicy.forContextWindow(128_000, compactHeadroomTokensFromSetting(setting)).compactThreshold
        }
        // A larger reserve ⇒ an EARLIER trigger, with no inversion and no zero
        // (0 would mean "compaction disabled", the opposite of "reserve used up").
        thresholds.zipWithNext { smaller, larger ->
            org.junit.Assert.assertTrue(
                "reserve growth must lower the trigger monotonically: $thresholds",
                larger <= smaller,
            )
            org.junit.Assert.assertTrue("compaction must stay enabled: $thresholds", larger > 0)
        }
    }
}
