package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONObject

/**
 * AI-only browser diagnostics contract. This file deliberately contains no
 * Compose/UI entry point: the normal browser remains the only user surface.
 * A caller must bind every request to the current session, tab and page id.
 */
object BrowserDevToolsTools {
    const val TOOL_NAME = "browser_devtools"
    const val DEFAULT_SCRIPT_TIMEOUT_MS = 10_000L
    const val MAX_SCRIPT_TIMEOUT_MS = 60_000L

    enum class Action(val wire: String) {
        DOM("dom"), ACCESSIBILITY("accessibility"), CONSOLE("console"),
        NETWORK("network"), STORAGE("storage"), SCREENSHOT("screenshot"),
        SCRIPT_DEBUG("script_debug");

        companion object {
            fun fromWire(value: String): Action? = entries.firstOrNull { it.wire == value.trim().lowercase() }
        }
    }

    data class Scope(val sessionId: String, val tabId: Int, val pageId: String) {
        fun permits(request: Request): Boolean =
            sessionId.isNotBlank() && request.sessionId == sessionId &&
                request.tabId == tabId && request.pageId == pageId
    }

    data class Request(
        val action: Action,
        val sessionId: String,
        val tabId: Int,
        val pageId: String,
        val script: String? = null,
        val timeoutMs: Long = DEFAULT_SCRIPT_TIMEOUT_MS,
    ) {
        fun normalized(): Request = copy(
            sessionId = sessionId.trim(),
            pageId = pageId.trim(),
            timeoutMs = timeoutMs.coerceIn(1L, MAX_SCRIPT_TIMEOUT_MS),
        )

        companion object {
            fun parse(json: String): Request? = runCatching {
                val obj = JSONObject(json)
                val action = Action.fromWire(obj.optString("action")) ?: return null
                Request(
                    action = action,
                    sessionId = obj.optString("session_id"),
                    tabId = obj.optInt("tab_id", -1),
                    pageId = obj.optString("page_id"),
                    script = obj.optString("script").takeIf { it.isNotBlank() },
                    timeoutMs = obj.optLong("timeout_ms", DEFAULT_SCRIPT_TIMEOUT_MS),
                ).normalized()
            }.getOrNull()
        }
    }

    enum class Status { OK, DENIED, UNSUPPORTED, TIMEOUT, EXCEPTION, CANCELLED, INVALID }

    data class Result(val status: Status, val message: String = "", val scope: Scope? = null) {
        fun toJson(): String = JSONObject().apply {
            put("status", status.name.lowercase())
            put("message", message)
            scope?.let {
                put("session_id", it.sessionId)
                put("tab_id", it.tabId)
                put("page_id", it.pageId)
            }
        }.toString()
    }

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = TOOL_NAME,
        description = "AI-only browser diagnostics bound to one authorized session, tab, and page. " +
            "No user DevTools panel is exposed. Actions inspect DOM/accessibility/console/network/storage, " +
            "capture a screenshot, or run a bounded diagnostic script. Never return data from another scope.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "Short description shown for this AI tool call."),
            "action" to AgentToolParam("string", "Diagnostic operation.", Action.entries.map { it.wire }),
            "session_id" to AgentToolParam("string", "Exact authorized chat session id."),
            "tab_id" to AgentToolParam("integer", "Exact authorized browser tab id."),
            "page_id" to AgentToolParam("string", "Stable page identity bound to the tab."),
            "script" to AgentToolParam("string", "Optional diagnostic JavaScript for script_debug only."),
            "timeout_ms" to AgentToolParam("integer", "Script timeout, clamped to 1..60000 ms."),
        ),
        required = listOf("tool_title", "action", "session_id", "tab_id", "page_id"),
        propertyOrdering = listOf("tool_title", "action", "session_id", "tab_id", "page_id", "script", "timeout_ms"),
    )

    /** Validates scope before any WebView/DevTools call is made. */
    fun authorize(request: Request, scope: Scope): Result =
        if (request.action == Action.SCRIPT_DEBUG && request.script.isNullOrBlank()) {
            Result(Status.INVALID, "script is required for script_debug")
        } else if (!scope.permits(request)) {
            Result(Status.DENIED, "browser scope is not authorized")
        } else {
            Result(Status.OK, "authorized", scope)
        }

    fun timeoutResult(scope: Scope): Result = Result(Status.TIMEOUT, "script timed out", scope)
    fun exceptionResult(scope: Scope, error: Throwable): Result = Result(Status.EXCEPTION, error.message ?: "script failed", scope)
    fun cancelledResult(scope: Scope): Result = Result(Status.CANCELLED, "script cancelled", scope)
}
