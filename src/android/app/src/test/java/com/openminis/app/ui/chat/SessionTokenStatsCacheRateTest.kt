package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionTokenStatsCacheRateTest {
    @Test
    fun `cache hit rate uses uncached input plus cache reads`() {
        val stats = ChatViewModel.SessionTokenStats(
            input = 600,
            output = 0,
            cacheRead = 400,
            cacheWrite = 200,
            context = 0,
            loopCount = 0,
        )
        assertEquals(40.0, stats.cacheHitRatePercent!!, 0.0001)
    }

    @Test
    fun `cache hit rate is unavailable without input denominator`() {
        val stats = ChatViewModel.SessionTokenStats(0, 0, 0, 500, 0, 0)
        assertNull(stats.cacheHitRatePercent)
    }

    @Test
    fun `cache hit rate is zero when no cache was read`() {
        val stats = ChatViewModel.SessionTokenStats(500, 0, 0, 100, 0, 0)
        assertEquals(0.0, stats.cacheHitRatePercent!!, 0.0001)
    }
}
