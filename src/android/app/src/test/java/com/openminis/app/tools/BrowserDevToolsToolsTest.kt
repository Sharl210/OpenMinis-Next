package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDevToolsToolsTest {
    private val scope = BrowserDevToolsTools.Scope("session-a", 7, "page-7-v2")

    @Test
    fun `definition is AI-only and exposes bounded diagnostics`() {
        val definition = BrowserDevToolsTools.definition()
        assertEquals("browser_devtools", definition.name)
        assertTrue(definition.description.contains("AI-only"))
        assertTrue(definition.parameters["action"]!!.enumValues!!.contains("accessibility"))
        assertTrue(definition.parameters["action"]!!.enumValues!!.contains("script_debug"))
    }

    @Test
    fun `scope rejects cross session tab and page`() {
        val request = BrowserDevToolsTools.Request(
            BrowserDevToolsTools.Action.DOM, "session-a", 7, "page-7-v2",
        )
        assertEquals(BrowserDevToolsTools.Status.OK, BrowserDevToolsTools.authorize(request, scope).status)
        assertEquals(
            BrowserDevToolsTools.Status.DENIED,
            BrowserDevToolsTools.authorize(request.copy(sessionId = "session-b"), scope).status,
        )
        assertEquals(
            BrowserDevToolsTools.Status.DENIED,
            BrowserDevToolsTools.authorize(request.copy(tabId = 8), scope).status,
        )
        assertEquals(
            BrowserDevToolsTools.Status.DENIED,
            BrowserDevToolsTools.authorize(request.copy(pageId = "old-page"), scope).status,
        )
    }

    @Test
    fun `script timeout is clamped and missing script is invalid`() {
        val request = BrowserDevToolsTools.Request(
            BrowserDevToolsTools.Action.SCRIPT_DEBUG, "session-a", 7, "page-7-v2",
            script = "return 1", timeoutMs = 999_999,
        ).normalized()
        assertEquals(60_000L, request.timeoutMs)
        assertEquals(BrowserDevToolsTools.Status.OK, BrowserDevToolsTools.authorize(request, scope).status)
        val missing = request.copy(script = null)
        assertEquals(BrowserDevToolsTools.Status.INVALID, BrowserDevToolsTools.authorize(missing, scope).status)
    }

    @Test
    fun `unsupported diagnostics remain structured and scoped`() {
        val result = BrowserDevToolsTools.Result(
            BrowserDevToolsTools.Status.UNSUPPORTED,
            "UNSUPPORTED: network",
            scope,
        )
        val json = org.json.JSONObject(result.toJson())
        assertEquals("unsupported", json.getString("status"))
        assertEquals("session-a", json.getString("session_id"))
        assertEquals(7, json.getInt("tab_id"))
        assertEquals("page-7-v2", json.getString("page_id"))
    }
}
