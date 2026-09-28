package com.openminis.app.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class BrowserElementSelectionPayloadTest {
    @Test
    fun `payload round trips locator metadata and builds chat snippet`() {
        val original = BrowserElementSelectionPayload(
            pageId = "page-uuid",
            runtimeTabId = 7,
            url = "https://example.com/account",
            title = "Account",
            domPath = "html:nth-of-type(1) > body:nth-of-type(1) > button:nth-of-type(2)",
            stableSelector = "button[data-testid=\"save\"]",
            attributes = mapOf("data-testid" to "save", "aria-label" to "Save"),
            visibleText = "Save changes",
        )

        val restored = BrowserElementSelectionPayload.fromJson(original.toJson().toString())
        assertNotNull(restored)
        assertEquals(original, restored)
        assertEquals(true, original.toPromptSnippet().contains("pageId"))
        assertEquals(true, original.toPromptSnippet().contains("stableSelector"))
    }
}
