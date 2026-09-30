package com.openminis.app.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-web-search-settings] Guards the search tool's ceiling semantics.
 *
 * The requirement asks the user to configure how many results the search tool
 * may return and how long it may take ("设置一次我们的这个调用的这个搜索工具,它能
 * 返回最大多少条内容,然后超时时间是多少"). That makes the configured values a
 * CEILING, not a default: the model may ask for less, never more.
 *
 * This matters because the previous behaviour silently capped a model that asked
 * for more — the model requested 30 and got 10 with nothing in the reply saying so,
 * and the number it actually got depended on a setting the model cannot see.
 *
 * The logic lived inline in the tool executor, so no test could reach it and the
 * ceiling was never asserted — only read. It is now a pure function, which is why
 * these assertions can exist at all.
 *
 * NOTE on what these tests do NOT cover: they cannot prove the settings SCREEN is
 * wired (that needs UI-level verification). They do prove the value the screen
 * writes actually governs the tool, which is the half that silently broke.
 */
class EffectiveSearchOptionsTest {

    private val userResults = 10
    private val userTimeoutMs = 20_000L

    @Test
    fun `an absent request uses the user's configured values`() {
        val options = effectiveSearchOptions(
            requestedMaxResults = null,
            requestedTimeoutMs = null,
            userMaxResults = userResults,
            userTimeoutMs = userTimeoutMs,
        )
        assertEquals(10, options.maxResults)
        assertEquals(20_000L, options.timeoutMs)
    }

    @Test
    fun `the user's setting is a ceiling the model cannot exceed`() {
        // The load-bearing assertion: raising the request must NOT raise the
        // result count beyond what the user allowed.
        val greedy = effectiveSearchOptions(
            requestedMaxResults = 30,
            requestedTimeoutMs = 120_000L,
            userMaxResults = userResults,
            userTimeoutMs = userTimeoutMs,
        )
        assertEquals("the model must not exceed the user's ceiling", 10, greedy.maxResults)
        assertEquals(20_000L, greedy.timeoutMs)
    }

    @Test
    fun `the model may ask for LESS than the ceiling`() {
        // The ceiling is not a floor: a narrow search is legitimate and must be
        // honoured, otherwise the model cannot reduce noise on purpose.
        val narrow = effectiveSearchOptions(
            requestedMaxResults = 3,
            requestedTimeoutMs = 5_000L,
            userMaxResults = userResults,
            userTimeoutMs = userTimeoutMs,
        )
        assertEquals(3, narrow.maxResults)
        assertEquals(5_000L, narrow.timeoutMs)
    }

    @Test
    fun `raising the user's setting actually raises what the model can get`() {
        // Proves the setting is live rather than decorative: this is the
        // difference the settings entry is supposed to make.
        val low = effectiveSearchOptions(30, null, userMaxResults = 5, userTimeoutMs = userTimeoutMs)
        val high = effectiveSearchOptions(30, null, userMaxResults = 25, userTimeoutMs = userTimeoutMs)
        assertEquals(5, low.maxResults)
        assertEquals(25, high.maxResults)
        assertTrue(high.maxResults > low.maxResults)
    }

    @Test
    fun `the service bounds still apply on top of the user's choice`() {
        // SearchOptions.normalized clamps to 1..50 and 1s..120s. A user setting
        // outside that range must degrade to the service bound rather than
        // reaching the adapter as an impossible request.
        val absurd = effectiveSearchOptions(null, null, userMaxResults = 500, userTimeoutMs = 999_999L)
        assertEquals("clamped to the service maximum", 50, absurd.maxResults)
        assertEquals(120_000L, absurd.timeoutMs)

        val zero = effectiveSearchOptions(null, null, userMaxResults = 0, userTimeoutMs = 0L)
        assertEquals("still asks for at least one result", 1, zero.maxResults)
        assertEquals(1_000L, zero.timeoutMs)
    }

    @Test
    fun `a zero request does not cancel the user's setting`() {
        // `optInt` with a default cannot distinguish "absent" from "0", and 0 is
        // not a meaningful request. Treating it as a ceiling of zero would make
        // the tool return nothing, so it must fall back to the service minimum
        // rather than silently disabling search.
        val options = effectiveSearchOptions(0, 0L, userResults, userTimeoutMs)
        assertEquals(1, options.maxResults)
        assertEquals(1_000L, options.timeoutMs)
    }

    @Test
    fun `a negative request cannot invert the ceiling`() {
        val options = effectiveSearchOptions(-5, -1L, userResults, userTimeoutMs)
        assertTrue("must stay a usable request", options.maxResults >= 1)
        assertTrue(options.timeoutMs >= 1_000L)
    }
}
