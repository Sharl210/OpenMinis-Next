package com.openminis.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserTabStateTest {
    @Test fun `idle lifecycle preserves active work and sleeps only at threshold`() {
        assertEquals(BrowserIdleState.ACTIVE, BrowserIdleSleepStateMachine.state(true, 500_000, 120_000))
        assertEquals(BrowserIdleState.ACTIVE, BrowserIdleSleepStateMachine.state(false, 119_999, 120_000))
        assertEquals(BrowserIdleState.SLEEPING, BrowserIdleSleepStateMachine.state(false, 120_000, 120_000))
    }

    @Test fun `stable page identities are independent from runtime tab handles`() {
        val first = BrowserTabRecord(pageId = "page-a", title = "Account", url = "https://example.com")
        val reopened = first.copy()
        val newPage = first.copy(pageId = "page-b")
        assertEquals(first.pageId, reopened.pageId)
        assertNotEquals(first.pageId, newPage.pageId)
        assertEquals("Account", reopened.title)
    }

    @Test fun `evicted page identity and title survive live merge with blank webview fields`() {
        val records = mutableMapOf<Int, BrowserTabRecord>()
        BrowserTabPersistence.rememberEvicted(records, 4, BrowserTabRecord("stable-4", "Account", "https://example.com"))
        val merged = BrowserTabPersistence.mergeLive(records, 4, BrowserTabRecord("stable-4", "", ""))
        assertEquals("stable-4", merged.pageId)
        assertEquals("Account", merged.title)
        assertEquals("https://example.com", merged.url)
    }

    @Test fun `local path policy rejects traversal and allows workspace`() {
        assertTrue(MinisLocalPathPolicy.resolve("workspace", "/../secrets.txt").isFailure)
        assertTrue(MinisLocalPathPolicy.resolve("../other-session", "/index.html").isFailure)
        assertTrue(MinisLocalPathPolicy.resolve("workspace", "/index.html").isSuccess)
    }
}
