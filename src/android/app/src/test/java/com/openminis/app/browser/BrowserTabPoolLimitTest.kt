package com.openminis.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserTabPoolLimitTest {
    @Test
    fun `browser limit is twenty tabs and rejects the twenty-first`() {
        // Raised 10 → 20 to match the product requirement. This test used to
        // assert 10 — which is exactly how the requirement stayed unimplemented
        // while a green suite said the limit was fine.
        assertEquals(20, BrowserTabPool.MAX_TABS)
        assertTrue(BrowserTabPool.canCreateTab(0))
        assertTrue(BrowserTabPool.canCreateTab(19))
        assertFalse(BrowserTabPool.canCreateTab(20))
        assertFalse(BrowserTabPool.canCreateTab(21))
    }

    @Test
    fun `limit matches the documented product ceiling`() {
        // Pins the number itself, not only the boundary behaviour, so a future
        // silent edit cannot reintroduce a smaller ceiling.
        assertEquals("product requirement: max 20 browser tabs", 20, BrowserTabPool.MAX_TABS)
    }
}
