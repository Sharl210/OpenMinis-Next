package com.openminis.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserTabPoolLimitTest {
    @Test
    fun `browser limit is ten tabs and rejects the eleventh`() {
        assertEquals(10, BrowserTabPool.MAX_TABS)
        assertTrue(BrowserTabPool.canCreateTab(0))
        assertTrue(BrowserTabPool.canCreateTab(9))
        assertFalse(BrowserTabPool.canCreateTab(10))
        assertFalse(BrowserTabPool.canCreateTab(11))
    }
}
