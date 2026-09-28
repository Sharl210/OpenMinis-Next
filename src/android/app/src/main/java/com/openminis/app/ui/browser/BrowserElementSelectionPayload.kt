package com.openminis.app.ui.browser

import org.json.JSONObject

/** Reproducible description of a user-picked DOM element. */
data class BrowserElementSelectionPayload(
    val pageId: String,
    val runtimeTabId: Int,
    val url: String,
    val title: String,
    val domPath: String,
    val stableSelector: String,
    val attributes: Map<String, String> = emptyMap(),
    val visibleText: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("pageId", pageId)
        .put("runtimeTabId", runtimeTabId)
        .put("url", url)
        .put("title", title)
        .put("domPath", domPath)
        .put("stableSelector", stableSelector)
        .put("attributes", JSONObject(attributes))
        .put("visibleText", visibleText)

    /** Compact, pasteable context for the chat composer. */
    fun toPromptSnippet(): String = "[browser-element ${toJson()}]"

    companion object {
        fun fromJson(raw: String): BrowserElementSelectionPayload? = runCatching {
            val o = JSONObject(raw)
            val attrs = o.optJSONObject("attributes")?.let { a ->
                a.keys().asSequence().associateWith { k -> a.optString(k) }
            }.orEmpty()
            BrowserElementSelectionPayload(
                pageId = o.getString("pageId"),
                runtimeTabId = o.getInt("runtimeTabId"),
                url = o.optString("url"),
                title = o.optString("title"),
                domPath = o.getString("domPath"),
                stableSelector = o.getString("stableSelector"),
                attributes = attrs,
                visibleText = o.optString("visibleText"),
            )
        }.getOrNull()
    }
}
