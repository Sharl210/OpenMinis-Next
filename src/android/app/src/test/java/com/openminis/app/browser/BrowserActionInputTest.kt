package com.openminis.app.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserActionInputTest {
    @Test
    fun `parses bookmark action aliases and library identifiers`() {
        val input = BrowserActionInput.parse(
            """{"action":" OPEN_BOOKMARK ","bookmark_id":"saved-1","query":"Docs","title":"Page"}""",
        )
        assertEquals(BrowserAction.OPEN_BOOKMARK, input?.action)
        assertEquals("saved-1", input?.itemId)
        assertEquals("Docs", input?.query)
        assertEquals("Page", input?.title)
    }

    @Test
    fun `unknown actions are rejected without manufacturing an action`() {
        assertEquals(null, BrowserActionInput.parse("""{"action":"open-any-url"}"""))
    }
}
