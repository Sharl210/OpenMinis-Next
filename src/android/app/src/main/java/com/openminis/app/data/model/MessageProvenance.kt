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

    /**
     * Does this row carry content a PERSON authored, i.e. is it a human turn?
     *
     * Both the UI transcript and the retry / delete / edit cut-offs ask "which
     * human turn is this?" and then pair an index counted on ONE side with a
     * `sort_order` found by counting on the OTHER. The two counts therefore have
     * to use one rule; when they drifted apart the cut-off silently never
     * matched and `deleteMessagesAfter` was skipped.
     *
     * The rule, and why each clause exists:
     *  - a `mediaRef` part means the human attached an image/file. An
     *    image-only message persists NO text part at all
     *    (`buildUserPartsJson` adds one only when the text is non-empty or there
     *    is no media), so a text-only test mis-classified it and desynchronised
     *    the two counts.
     *  - a non-blank text part counts, EXCEPT one that starts with
     *    `<system-reminder>`: that is the app's own resume() re-entry, written
     *    with the API's `user` role but standing for no human turn.
     *  - `<user-attached-files>` XML is a real text part and counts, which is
     *    what keeps an attachment-only turn aligned with its UI bubble.
     *
     * Deliberately NOT provenance-based: this answers "did a human put content
     * here", while [provenanceOf] answers "how was the row classified". Legacy
     * rows written before provenance existed have no marker at all.
     */
    fun hasHumanTurnContent(partsJson: String): Boolean {
        val parts = runCatching { JSONArray(partsJson) }.getOrNull() ?: return true
        for (index in 0 until parts.length()) {
            val part = parts.optJSONObject(index) ?: continue
            when (part.optString("type")) {
                "mediaRef" -> return true
                "text" -> {
                    val value = part.optString("value", "")
                    if (value.isNotBlank() && !value.trimStart().startsWith(SYSTEM_REMINDER_PREFIX)) {
                        return true
                    }
                }
            }
        }
        return false
    }

    /** Prefix of the app-authored reminder that must never count as a human turn. */
    const val SYSTEM_REMINDER_PREFIX = "<system-reminder>"

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
