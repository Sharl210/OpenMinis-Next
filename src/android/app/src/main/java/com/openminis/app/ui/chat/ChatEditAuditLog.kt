package com.openminis.app.ui.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small durable audit trail for queue withdrawal/edit lifecycle events.
 * It stores only bounded operational metadata, never message bodies or attachments.
 */
internal class ChatEditAuditLog(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun record(
        event: String,
        sessionId: String,
        messageId: String,
        promptId: String? = null,
        delivery: QueuedPromptDelivery? = null,
        accepted: Boolean = true,
        reason: String? = null,
    ) {
        val events = runCatching {
            JSONArray(prefs.getString(KEY_EVENTS, "[]") ?: "[]")
        }.getOrDefault(JSONArray())
        events.put(JSONObject().apply {
            put("event", event)
            put("at", System.currentTimeMillis())
            put("sessionId", sessionId)
            put("messageId", messageId)
            promptId?.let { put("promptId", it) }
            delivery?.let { put("delivery", it.name) }
            put("accepted", accepted)
            reason?.let { put("reason", it) }
        })
        val first = (events.length() - MAX_EVENTS).coerceAtLeast(0)
        val bounded = JSONArray()
        for (index in first until events.length()) bounded.put(events.opt(index))
        prefs.edit().putString(KEY_EVENTS, bounded.toString()).commit()
    }

    companion object {
        private const val PREFS = "chat-edit-audit"
        private const val KEY_EVENTS = "events"
        private const val MAX_EVENTS = 128
    }
}
