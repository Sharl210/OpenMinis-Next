package com.openminis.app.share

import com.openminis.app.data.model.MessagePartsCodec
import com.openminis.app.data.model.MessageProvenance
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-android-human-turn-count-parity] The plain-text export decides who authored
 * each row. It used to decide from the `role` column alone
 * (`if (role == "user") "You" else "Assistant"`), which turned harness plumbing
 * into the user's own words: tool results and the `<system-reminder>` resume
 * entry are both persisted with the API's `user` role, so a shared transcript
 * read as if the human had typed them.
 *
 * The JSON export keeps `role` + `partsJson` verbatim, so provenance survives
 * there; the text export is the one place the distinction has to be re-derived.
 */
class ChatExporterSpeakerLabelTest {

    /** Mirrors how the codec writes provenance: a trailing `messageMeta` part. */
    private fun withProvenance(provenance: MessageProvenance, body: JSONObject): String =
        JSONArray()
            .put(body)
            .put(
                JSONObject()
                    .put("type", "messageMeta")
                    .put("value", JSONObject().put("source", provenance.wireValue)),
            )
            .toString()

    private fun text(value: String) = JSONObject().put("type", "text").put("value", value)

    @Test
    fun `a typed human turn is labelled You`() {
        assertEquals(
            "You",
            ChatExporter.speakerLabel(
                "user",
                withProvenance(MessageProvenance.MANUAL_USER, text("hello")),
            ),
        )
    }

    @Test
    fun `an image-only human turn is still labelled You`() {
        // No text part at all — the old role-only test got this right by
        // accident, and it must keep working.
        val json = withProvenance(
            MessageProvenance.MANUAL_USER,
            JSONObject().put("type", "mediaRef").put("value", "image-a"),
        )
        assertEquals("You", ChatExporter.speakerLabel("user", json))
    }

    @Test
    fun `a tool result is NOT labelled You`() {
        // The regression: role == "user", but the human never typed this.
        val json = withProvenance(
            MessageProvenance.TOOL_INJECTION,
            JSONObject().put("type", "toolResult").put("value", "stdout"),
        )
        assertEquals("Tool", ChatExporter.speakerLabel("user", json))
    }

    @Test
    fun `a system reminder resume entry is NOT labelled You`() {
        val json = withProvenance(
            MessageProvenance.SYSTEM_CARD,
            text("<system-reminder>continue</system-reminder>"),
        )
        assertEquals("System", ChatExporter.speakerLabel("user", json))
    }

    @Test
    fun `an unmarked legacy user row with real text still reads as You`() {
        // Rows written before provenance existed carry no meta part. Their text
        // is genuine user content, so the export must not relabel them "Tool".
        val json = JSONArray().put(text("hello there")).toString()
        assertEquals("You", ChatExporter.speakerLabel("user", json))
    }

    @Test
    fun `assistant and system rows keep their own labels`() {
        val json = JSONArray().put(text("answer")).toString()
        assertEquals("Assistant", ChatExporter.speakerLabel("assistant", json))
        assertEquals("System", ChatExporter.speakerLabel("system", json))
    }

    @Test
    fun `an unmarked user row with no content is not the human speaking`() {
        // Nothing visible and no provenance to vouch for it: a synthetic row.
        assertEquals("Tool", ChatExporter.speakerLabel("user", "[]"))
    }

    @Test
    fun `corrupt parts keep the human label rather than inventing a tool row`() {
        // `hasHumanTurnContent` is deliberately fail-open: an unreadable row is
        // treated as having content. That choice is shared with the retry and
        // delete cut-offs (under-counting would skip a truncation entirely,
        // over-counting only cuts one turn early), and the export inherits it —
        // a user row we cannot parse is still shown as the user, never silently
        // relabelled "Tool". The alternative would hide a real message behind a
        // wrong speaker.
        assertEquals("You", ChatExporter.speakerLabel("user", "{not json"))
        assertEquals("Assistant", ChatExporter.speakerLabel("assistant", "{not json"))
    }

    @Test
    fun `content decides first and provenance is the tiebreaker`() {
        // Precedence, stated once so it cannot drift: whether a row carries
        // visible content is the primary test (that is the shared rule used
        // everywhere else); provenance only decides the rows that have NO human
        // content. A realistic tool-injection row is a toolResult, and it must
        // never be exported as the user speaking.
        val toolResult = withProvenance(
            MessageProvenance.TOOL_INJECTION,
            JSONObject().put("type", "toolResult").put("value", "stdout"),
        )
        assertEquals(MessageProvenance.TOOL_INJECTION, MessagePartsCodec.provenanceOf(toolResult))
        assertEquals("Tool", ChatExporter.speakerLabel("user", toolResult))

        // An empty user row has no content to vouch for it, so provenance (absent
        // → not a manual user) is what keeps it from reading as the human.
        assertEquals("Tool", ChatExporter.speakerLabel("user", "[]"))
    }
}
