package com.openminis.app.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Source of a transcript row as shown to a person.
 *
 * This is intentionally separate from the wire/API role. Tool results and
 * injected reminders may use the API's `user` role while not being human
 * messages, so the UI must use this explicit provenance instead.
 */
enum class MessageProvenance(val wireValue: String) {
    MANUAL_USER("manual_user"),
    ASSISTANT("assistant"),
    SYSTEM_CARD("system_card"),
    TOOL_INJECTION("tool_injection"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWireValue(value: String?): MessageProvenance =
            entries.firstOrNull { it.wireValue == value?.trim()?.lowercase() } ?: UNKNOWN
    }
}

/** JSON codec for the optional provenance part in a persisted message body. */
object MessagePartsCodec {
    private const val META_TYPE = "messageMeta"
    private const val SOURCE_KEY = "source"

    /**
     * Add exactly one source marker as the first part. Existing markers are
     * removed first so retries/copies remain idempotent. UNKNOWN deliberately
     * means "no trustworthy marker" and therefore removes any marker.
     */
    fun withProvenance(partsJson: String, provenance: MessageProvenance): String {
        val parts = runCatching { JSONArray(partsJson) }.getOrNull() ?: return partsJson
        val output = JSONArray()
        if (provenance != MessageProvenance.UNKNOWN) {
            output.put(
                JSONObject().put(
                    "type",
                    META_TYPE,
                ).put(
                    "value",
                    JSONObject().put(SOURCE_KEY, provenance.wireValue),
                ),
            )
        }
        for (index in 0 until parts.length()) {
            val part = parts.opt(index)
            if (part is JSONObject && part.optString("type") == META_TYPE) continue
            if (part != null) output.put(part)
        }
        return output.toString()
    }

    /** Read a source marker conservatively; absent, malformed, or conflicting means UNKNOWN. */
    fun provenanceOf(partsJson: String): MessageProvenance {
        val parts = runCatching { JSONArray(partsJson) }.getOrNull() ?: return MessageProvenance.UNKNOWN
        var found: MessageProvenance? = null
        for (index in 0 until parts.length()) {
            val part = parts.optJSONObject(index) ?: continue
            if (part.optString("type") != META_TYPE) continue
            val value = part.opt("value")
            val source = when (value) {
                is JSONObject -> value.optString(SOURCE_KEY).takeIf { it.isNotBlank() }
                else -> part.optString(SOURCE_KEY).takeIf { it.isNotBlank() }
            }
            val parsed = MessageProvenance.fromWireValue(source)
            if (parsed == MessageProvenance.UNKNOWN) return MessageProvenance.UNKNOWN
            if (found != null && found != parsed) return MessageProvenance.UNKNOWN
            found = parsed
        }
        return found ?: MessageProvenance.UNKNOWN
    }

    /** Remove source markers before passing serialized parts to any API boundary. */
    fun withoutProvenance(partsJson: String): String {
        val parts = runCatching { JSONArray(partsJson) }.getOrNull() ?: return partsJson
        val output = JSONArray()
        for (index in 0 until parts.length()) {
            val part = parts.opt(index)
            if (part is JSONObject && part.optString("type") == META_TYPE) continue
            if (part != null) output.put(part)
        }
        return output.toString()
    }
}
