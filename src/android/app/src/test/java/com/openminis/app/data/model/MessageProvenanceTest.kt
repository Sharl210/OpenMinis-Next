package com.openminis.app.data.model

import com.openminis.app.ui.chat.ChatMessage
import com.openminis.app.ui.chat.isManualHumanUser
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageProvenanceTest {
    @Test
    fun `all supported provenance values round trip`() {
        val body = """[{"type":"text","value":"hello"}]"""
        MessageProvenance.entries
            .filter { it != MessageProvenance.UNKNOWN }
            .forEach { expected ->
                val encoded = MessagePartsCodec.withProvenance(body, expected)
                assertEquals(expected, MessagePartsCodec.provenanceOf(encoded))
                assertEquals(body, MessagePartsCodec.withoutProvenance(encoded))
            }
    }

    @Test
    fun `missing legacy and malformed markers remain unknown`() {
        assertEquals(MessageProvenance.UNKNOWN, MessagePartsCodec.provenanceOf("[]"))
        assertEquals(MessageProvenance.UNKNOWN, MessagePartsCodec.provenanceOf("[{\"type\":\"text\",\"value\":\"old\"}]"))
        assertEquals(MessageProvenance.UNKNOWN, MessagePartsCodec.provenanceOf("not-json"))
        assertEquals(
            MessageProvenance.UNKNOWN,
            MessagePartsCodec.provenanceOf("[{\"type\":\"messageMeta\",\"value\":{\"source\":\"future\"}}]"),
        )
    }

    @Test
    fun `conflicting markers are not guessed and marker insertion is idempotent`() {
        val first = MessagePartsCodec.withProvenance("[{\"type\":\"text\",\"value\":\"x\"}]", MessageProvenance.MANUAL_USER)
        val second = MessagePartsCodec.withProvenance(first, MessageProvenance.ASSISTANT)
        assertEquals(MessageProvenance.ASSISTANT, MessagePartsCodec.provenanceOf(second))
        val secondParts = JSONArray(second)
        var markerCount = 0
        for (index in 0 until secondParts.length()) {
            if (secondParts.optJSONObject(index)?.optString("type") == "messageMeta") markerCount++
        }
        assertEquals(1, markerCount)

        val conflicting = JSONArray()
            .put(JSONObject().put("type", "messageMeta").put("value", JSONObject().put("source", "manual_user")))
            .put(JSONObject().put("type", "messageMeta").put("value", JSONObject().put("source", "assistant")))
        assertEquals(MessageProvenance.UNKNOWN, MessagePartsCodec.provenanceOf(conflicting.toString()))
    }

    @Test
    fun `only marked manual user rows participate in human navigation`() {
        assertTrue(
            ChatMessage(
                id = "manual",
                role = "user",
                content = "hi",
                provenance = MessageProvenance.MANUAL_USER,
            ).isManualHumanUser(),
        )
        assertFalse(
            ChatMessage(
                id = "tool",
                role = "user",
                content = "tool result",
                provenance = MessageProvenance.TOOL_INJECTION,
            ).isManualHumanUser(),
        )
        assertFalse(
            ChatMessage(id = "legacy", role = "user", content = "old").isManualHumanUser(),
        )
    }

    @Test
    fun `legacy rows stay unknown and wire values normalize`() {
        val message = ChatMessage(id = "legacy", role = "user", content = "old")
        assertEquals(MessageProvenance.UNKNOWN, message.provenance)
        assertFalse(message.provenance == MessageProvenance.MANUAL_USER)
        assertTrue(MessageProvenance.fromWireValue(" MANUAL_USER ") == MessageProvenance.MANUAL_USER)
    }
}
