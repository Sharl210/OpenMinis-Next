package com.openminis.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // --- [T-browser-idle-eviction-conditions] sleep conditions ---
    //
    // The pool's evictor decides inline against a live WebView, so no test ever
    // triggered it. These pin the rules that were missing: a tab the user is
    // reading, and a tab with a download in flight, must never be destroyed by
    // the idle timer. Both bugs were user-visible: the page vanished under the
    // reader and never came back, and a blob download died leaving no record.

    @Test fun `sleeps only an idle tab nobody is watching and nothing is transferring`() {
        assertTrue(
            "a genuinely idle, unseen tab with no transfer is the ONLY tab that may sleep",
            BrowserIdleSleepStateMachine.shouldSleep(
                inUse = false, idleMs = 120_000, timeoutMs = 120_000,
                isUserViewing = false, anyDownloadInFlight = false,
            ),
        )
    }

    @Test fun `never sleeps the tab the user is looking at`() {
        // The requirement: a page sleeps only when the model isn't executing
        // AND "我们也没有打开去看". Being on screen is therefore a hard veto,
        // regardless of how long it has been since any recorded activity.
        assertFalse(
            BrowserIdleSleepStateMachine.shouldSleep(
                inUse = false, idleMs = Long.MAX_VALUE / 2, timeoutMs = 120_000,
                isUserViewing = true, anyDownloadInFlight = false,
            ),
        )
    }

    @Test fun `never sleeps any tab while a download is in flight`() {
        // blob:/data: downloads are fetched through the page's WebView, so
        // evicting mid-transfer loses the file AND leaves no download record.
        assertFalse(
            BrowserIdleSleepStateMachine.shouldSleep(
                inUse = false, idleMs = Long.MAX_VALUE / 2, timeoutMs = 120_000,
                isUserViewing = false, anyDownloadInFlight = true,
            ),
        )
    }

    @Test fun `never sleeps a tab an agent action holds`() {
        assertFalse(
            BrowserIdleSleepStateMachine.shouldSleep(
                inUse = true, idleMs = 999_999, timeoutMs = 120_000,
                isUserViewing = false, anyDownloadInFlight = false,
            ),
        )
    }

    @Test fun `a user action on a page counts as activity`() {
        // Typing a URL / back / forward / reload are operations on the page, so
        // a freshly-touched tab must not be immediately eligible.
        assertFalse(
            BrowserIdleSleepStateMachine.shouldSleep(
                inUse = false, idleMs = 0, timeoutMs = 120_000,
                isUserViewing = false, anyDownloadInFlight = false,
            ),
        )
        assertTrue(
            BrowserIdleSleepStateMachine.shouldSleep(
                inUse = false, idleMs = 120_000, timeoutMs = 120_000,
                isUserViewing = false, anyDownloadInFlight = false,
            ),
        )
    }

    // --- [T-browser-sleeping-tabs-visible] slept pages stay addressable ---

    @Test fun `a slept page keeps its page identity and url`() {
        // Sleep must not be a silent close. The record has to keep the PAGE id
        // so the page can be listed and restored; the runtime tab id it used to
        // be filed under is never reused inside a process, which is exactly why
        // slept pages became unreachable forever.
        val slept = BrowserTabRecord("page-abc-123", "Docs", "https://docs.example.com")
        assertEquals("page-abc-123", slept.pageId)
        assertEquals("Docs", slept.title)
        assertEquals("https://docs.example.com", slept.url)
    }

    @Test fun `sleeping list keys off page id so a restore is unambiguous`() {
        // restoreSleepingTab/forgetSleepingTab look records up by pageId, so a
        // page that sleeps twice must collapse to one entry, newest winning.
        val kept = mutableListOf<BrowserTabRecord>()
        repeat(3) { kept.add(BrowserTabRecord("page-$it", "t$it", "https://e/$it")) }
        kept.add(BrowserTabRecord("page-1", "newer", "https://e/new"))
        val deduped = kept.filterNot { it.pageId == "page-1" } + kept.last()
        assertEquals("a re-slept page must not appear twice", 3, deduped.size)
        assertEquals("newer", deduped.first { it.pageId == "page-1" }.title)
    }

    @Test fun `agent-facing line reports a slept page and how to restore it`() {
        val line = formatSleepingTabLine(
            BrowserTabRecord("abcdef01-2345", "Inbox", "https://mail.example.com"),
        )
        assertTrue("page id missing: $line", line.contains("abcdef01"))
        assertTrue("title missing: $line", line.contains("Inbox"))
        assertTrue("url missing: $line", line.contains("https://mail.example.com"))
        // Without this hint the entry is a dead end: the agent can see the page
        // existed but not that it can be brought back.
        assertTrue("must not look like a dead end: $line", line.contains("restore_tab"))
        assertFalse("a slept page is not an open tab: $line", line.contains("Tab "))
    }

    @Test fun `slept page with no title or url still renders a usable line`() {
        val line = formatSleepingTabLine(BrowserTabRecord("deadbeef", "", ""))
        assertTrue("blank title must be labelled: $line", line.contains("(untitled)"))
        assertTrue("blank url must be labelled: $line", line.contains("about:blank"))
    }
}

