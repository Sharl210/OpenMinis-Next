package com.openminis.app.browser

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Device-level proof for the browser tab ceiling.
 *
 * [T-browser-capacity-20] The product requirement is "max creatable tabs = 20".
 * The constant said 10 and a green JVM test asserted 10, so the requirement was
 * unimplemented while every local signal said otherwise. This test creates tabs
 * for real on a device: a JVM assertion can only re-read the constant, whereas
 * this one proves the pool actually carries 20 live WebView-backed tabs and
 * refuses the 21st instead of crashing or silently recycling a tab.
 */
@RunWith(AndroidJUnit4::class)
class BrowserTabPoolCapacityInstrumentedTest {
    private lateinit var context: Context
    private lateinit var pool: BrowserTabPool

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        pool = BrowserTabPool(context)
        deleteSession(SESSION)
        pool.setSession(SESSION)
    }

    @After
    fun tearDown() {
        runCatching { deleteSession(SESSION) }
    }

    @Test
    fun createsTwentyTabsAndRefusesTheTwentyFirst() = runBlocking {
        val created = mutableListOf<Int>()
        repeat(BrowserTabPool.MAX_TABS) { index ->
            val tab = pool.newTabFromUI()
            assertNotNull("tab #${index + 1} of ${BrowserTabPool.MAX_TABS} must be creatable", tab)
            created += tab!!.id
        }
        assertEquals(BrowserTabPool.MAX_TABS, pool.tabs.value.size)
        assertEquals(
            "every created tab must hold a distinct id",
            BrowserTabPool.MAX_TABS,
            created.toSet().size,
        )

        // The ceiling must REFUSE, not recycle: an implementation that silently
        // evicted a live tab to make room would still return a non-null tab here.
        assertNull("the ${BrowserTabPool.MAX_TABS + 1}st tab must be refused", pool.newTabFromUI())
        assertEquals(
            "a refused creation must not disturb the existing tabs",
            BrowserTabPool.MAX_TABS,
            pool.tabs.value.size,
        )
    }

    @Test
    fun ceilingIsConfigurableConstantNotAnIncidentalCount() {
        // Pins the product number itself so a future edit cannot quietly lower
        // the ceiling again without a failing device test.
        assertEquals("product requirement: max 20 browser tabs", 20, BrowserTabPool.MAX_TABS)
        assertTrue(BrowserTabPool.canCreateTab(BrowserTabPool.MAX_TABS - 1))
        assertTrue(BrowserTabPool.canCreateTab(BrowserTabPool.MAX_TABS - 10))
    }

    @Test
    fun tabsAtCeilingStillCarryStablePageIds() = runBlocking {
        // Page ids are what the requirement asks the user to quote to the model
        // ("标题前显示稳定页 ID"), so they must stay unique and non-empty at the
        // raised ceiling too — not only for the first few tabs.
        repeat(BrowserTabPool.MAX_TABS) { pool.newTabFromUI() }
        val pageIds = pool.tabs.value.map { it.pageId }
        assertEquals(BrowserTabPool.MAX_TABS, pageIds.size)
        assertTrue("page ids must be non-empty", pageIds.none { it.isEmpty() })
        assertEquals("page ids must be unique", pageIds.size, pageIds.toSet().size)
    }

    private fun deleteSession(session: String) {
        File(context.filesDir, "minis-sessions/$session").deleteRecursively()
        File(context.filesDir, "browser_tabs/$session.downloads.json").delete()
    }

    private companion object {
        const val SESSION = "capacity-20"
    }
}
