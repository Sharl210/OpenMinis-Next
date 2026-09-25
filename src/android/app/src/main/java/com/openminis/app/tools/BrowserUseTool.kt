package com.openminis.app.tools

import com.openminis.app.browser.BrowserAction
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tool schema definition for browser_use, mirroring the iOS agent tool registration.
 * Used when sending tool definitions to the LLM API.
 */
object BrowserUseTool {

    const val NAME = "browser_use"

    val description = """Control web browser with up to 10 tabs, including history and bookmarks. Actions: navigate to URL, take screenshot, click elements, type text, get page text, scroll, get page info, execute JavaScript, find elements by selector, hover, get readable content, set user agent, get page backbone, fetch resources, manage tabs, read/delete history, add/remove/list bookmarks, and open saved bookmarks.""".trimIndent()

    /**
     * Build the JSON tool definition for the Anthropic / OpenAI / Gemini API.
     * Returns a JSONObject suitable for the "tools" array in the API request.
     */
    fun toolDefinition(): JSONObject {
        val tool = JSONObject()
        tool.put("name", NAME)
        tool.put("description", description)

        val properties = JSONObject()

        properties.put("tool_title", JSONObject().apply {
            put("type", "string")
            put("description", "5-10 word summary of what this call does")
        })

        properties.put("action", JSONObject().apply {
            put("type", "string")
            put("description", "The browser action to perform")
            put("enum", JSONArray(BrowserAction.allValues))
        })

        properties.put("url", JSONObject().apply {
            put("type", "string")
            put("description", "URL to navigate to or fetch (for navigate/fetch/new_tab)")
        })

        properties.put("selector", JSONObject().apply {
            put("type", "string")
            put("description", "CSS selector for element interaction (click/type/hover/find_elements/get_text/scroll)")
        })

        properties.put("text", JSONObject().apply {
            put("type", "string")
            put("description", "Text to type into the selected element")
        })

        properties.put("coordinate_x", JSONObject().apply {
            put("type", "integer")
            put("description", "X coordinate for click-by-position")
        })

        properties.put("coordinate_y", JSONObject().apply {
            put("type", "integer")
            put("description", "Y coordinate for click-by-position")
        })

        properties.put("direction", JSONObject().apply {
            put("type", "string")
            put("description", "Scroll direction")
            put("enum", JSONArray(listOf("up", "down")))
        })

        properties.put("amount", JSONObject().apply {
            put("type", "integer")
            put("description", "Scroll amount in pixels (default 500)")
        })

        properties.put("script", JSONObject().apply {
            put("type", "string")
            put("description", "JavaScript code to execute (for execute_js). The script runs inside an async function wrapper — await and top-level return are both supported.")
        })

        properties.put("user_agent", JSONObject().apply {
            put("type", "string")
            put("description", "User agent profile to switch to")
            put("enum", JSONArray(listOf("desktop_chrome", "mobile_chrome")))
        })

        properties.put("max_depth", JSONObject().apply {
            put("type", "integer")
            put("description", "Maximum DOM tree depth for get_backbone (default 5)")
        })

        properties.put("tab_id", JSONObject().apply {
            put("type", "integer")
            put("description", "Tab ID for tab management actions")
        })

        properties.put("full_page", JSONObject().apply {
            put("type", "boolean")
            put("default", false)
            put("description", "When true on screenshot, capture the entire scrollable page by temporarily stretching the viewport to document.scrollHeight (height-capped at 32768 px). Default false captures only the current viewport.")
        })

        properties.put("scroll_count", JSONObject().apply {
            put("type", "integer")
            put("description", "Number of scroll steps for scroll_and_collect (default 10, maximum 20).")
        })

        properties.put("item_selector", JSONObject().apply {
            put("type", "string")
            put("description", "CSS selector for individual items captured by scroll_and_collect.")
        })

        properties.put("timeout", JSONObject().apply {
            put("type", "integer")
            put("description", "Timeout in seconds for wait_for_dom_stable (default 10).")
        })

        properties.put("viewport_width", JSONObject().apply {
            put("type", "integer")
            put("description", "Viewport width in CSS pixels for set_viewport; pass with viewport_height unless reset is true.")
        })

        properties.put("viewport_height", JSONObject().apply {
            put("type", "integer")
            put("description", "Viewport height in CSS pixels for set_viewport; pass with viewport_width unless reset is true.")
        })

        properties.put("reset", JSONObject().apply {
            put("type", "boolean")
            put("default", false)
            put("description", "For set_viewport, clear the session-level viewport override and use the global browser setting.")
        })

        properties.put("keywords", JSONObject().apply {
            put("type", "string")
            put("description", "Cookie-name filter for get_cookies: space-separated keywords or a JSON array of strings.")
        })

        properties.put("fuzzy", JSONObject().apply {
            put("type", "boolean")
            put("default", true)
            put("description", "For get_cookies, true matches cookie names containing all keywords; false requires exact matches.")
        })

        properties.put("cookies", JSONObject().apply {
            put("type", "string")
            put("description", "For set_cookies, a JSON array of cookie objects (or a JSON-encoded array string); each object requires name and value, with optional domain, path, secure, http_only, and expires.")
        })

        properties.put("item_id", JSONObject().apply {
            put("type", "string")
            put("description", "History or bookmark id for delete/open/remove actions; bookmark URLs are also accepted.")
        })

        properties.put("query", JSONObject().apply {
            put("type", "string")
            put("description", "Optional text filter for get_history or bookmark listing actions.")
        })

        properties.put("title", JSONObject().apply {
            put("type", "string")
            put("description", "Optional bookmark title for add_bookmark/bookmark actions.")
        })

        val inputSchema = JSONObject()
        inputSchema.put("type", "object")
        inputSchema.put("properties", properties)
        inputSchema.put("required", JSONArray(listOf("tool_title", "action")))
        inputSchema.put(
            "propertyOrdering",
            JSONArray(
                listOf(
                    "tool_title", "action", "tab_id", "url", "selector", "text",
                    "coordinate_x", "coordinate_y", "direction", "amount", "scroll_count",
                    "item_selector", "script", "user_agent", "max_depth", "keywords", "fuzzy",
                    "cookies", "timeout", "viewport_width", "viewport_height", "reset",
                    "full_page", "item_id", "query", "title",
                ),
            ),
        )

        tool.put("input_schema", inputSchema)
        return tool
    }

    /**
     * Build tool definition for Anthropic API format.
     */
    fun anthropicToolDefinition(): JSONObject = toolDefinition()

    /**
     * Build tool definition for OpenAI API function calling format.
     */
    fun openAIFunctionDefinition(): JSONObject {
        val def = toolDefinition()
        val fn = JSONObject()
        fn.put("type", "function")
        val function = JSONObject()
        function.put("name", def.getString("name"))
        function.put("description", def.getString("description"))
        function.put("parameters", def.getJSONObject("input_schema"))
        fn.put("function", function)
        return fn
    }
}
