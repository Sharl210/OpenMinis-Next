package com.openminis.app.ui.chat

import com.openminis.app.data.model.MessagePartsCodec
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-human-turn-count-parity] The retry / delete / edit cut-offs count
 * "which human turn is this?" TWICE — once over the UI list
 * ([isHumanTurnShape] / [countsAsHumanTurn]) and once over the DB rows
 * ([MessagePartsCodec.hasHumanTurnContent]) — and then pair the two numbers. If
 * they disagree the DB walk never matches its index, `cutoffSortOrder` stays -1
 * and `deleteMessagesAfter` is skipped, so the history the user just rewound
 * past is silently retained.
 *
 * That is exactly what happened: an image-only turn persists NO text part
 * (`buildUserPartsJson` writes one only when the text is non-empty or there is
 * no media), so the old text-only DB rule skipped it while the old
 * `role == "user"` UI rule counted it.
 *
 * Both halves are pinned on the SAME fixtures, so changing one side alone fails
 * here. `ChatMessage` itself is not constructed: its `imageUris` is
 * `List<android.net.Uri>`, which the plain-JVM tests cannot build, so the UI
 * side is exercised through its pure shape function — the same one
 * `countsAsHumanTurn()` delegates to.
 */
class HumanTurnCountParityTest {

    private fun text(value: String) = """{"type":"text","value":${JSONObject.quote(value)}}"""

    private val mediaRef =
        """{"type":"mediaRef","value":{"kind":"image","relativePath":"media/a.png"}}"""

    private val meta = """{"type":"messageMeta","value":{"source":"manual_user"}}"""

    private fun attachmentsXml(count: Int): String =
        "<user-attached-files>${"<file>x</file>".repeat(count)}</user-attached-files>"

    // ── both sides: a human turn ─────────────────────────────────────────

    @Test
    fun `a typed turn counts on both sides`() {
        assertTrue(isHumanTurnShape("user", "hello", 0))
        assertTrue(MessagePartsCodec.hasHumanTurnContent("[$meta,${text("hello")}]"))
    }

    @Test
    fun `an image only turn counts on both sides`() {
        // No text part at all — the case that desynchronised the two counts.
        assertTrue(isHumanTurnShape("user", "", 1))
        assertTrue(MessagePartsCodec.hasHumanTurnContent("[$meta,$mediaRef]"))
    }

    @Test
    fun `an attachment-only turn counts on both sides`() {
        // Persists the <user-attached-files> XML as a real text part, while the
        // bubble's own text stays empty — file chips come from mediaRef parts.
        assertTrue(isHumanTurnShape("user", "", 1))
        assertTrue(
            MessagePartsCodec.hasHumanTurnContent("[$meta,$mediaRef,${text(attachmentsXml(1))}]"),
        )
    }

    @Test
    fun `a legacy row with no provenance marker still counts`() {
        // Pre-provenance rows carry no marker; counting by marker would drop
        // every human turn written before it existed.
        assertTrue(isHumanTurnShape("user", "old turn", 0))
        assertTrue(MessagePartsCodec.hasHumanTurnContent("[${text("old turn")}]"))
    }

    // ── both sides: not a human turn ─────────────────────────────────────

    @Test
    fun `the resume reminder is not a human turn on either side`() {
        // The UI strips the reminder, leaving a bubble with no text and no
        // attachments; the DB rule must skip the same row.
        assertFalse(isHumanTurnShape("user", "", 0))
        assertFalse(
            MessagePartsCodec.hasHumanTurnContent(
                "[$meta,${text("<system-reminder>\ncontinue\n</system-reminder>")}]",
            ),
        )
    }

    @Test
    fun `assistant rows never count on the UI side`() {
        assertFalse(isHumanTurnShape("assistant", "reply", 0))
    }

    @Test
    fun `a tool-result row is not a human turn on the DB side`() {
        assertFalse(
            MessagePartsCodec.hasHumanTurnContent(
                """[{"type":"toolResult","value":{"toolUseId":"t1","output":"ok","success":true}}]""",
            ),
        )
    }

    @Test
    fun `blank text alone is not a human turn`() {
        assertFalse(isHumanTurnShape("user", "   ", 0))
        assertFalse(MessagePartsCodec.hasHumanTurnContent("[$meta,${text("   ")}]"))
    }

    @Test
    fun `a media-free row with no parts is not a human turn on the DB side`() {
        // A blank user row is dropped from the UI transcript entirely
        // (toChatMessages returns null), so the DB side must not count it
        // either — otherwise the two counts drift by one per such row.
        assertFalse(MessagePartsCodec.hasHumanTurnContent("[$meta]"))
        assertFalse(MessagePartsCodec.hasHumanTurnContent("[]"))
    }

    @Test
    fun `a file-only turn is visible content, not a blank bubble to drop`() {
        // [T-android-human-turn-count-parity] The transcript filter that decides
        // whether a user row becomes a bubble must agree with this rule. It used
        // to test "blank text AND no IMAGE uris", so a message carrying only a
        // non-image attachment with no caption was dropped from the transcript —
        // even though `sendMessage` explicitly allows sending it (its reject
        // condition is blank text AND no attachments) and the file did reach the
        // model. Attachments of either kind are visible: the bubble chips them.
        assertTrue(
            "a non-image attachment alone must keep the bubble",
            isHumanTurnShape(role = "user", text = "", attachmentCount = 1),
        )
        // …and the no-content rows this filter exists to drop still drop.
        assertFalse(isHumanTurnShape(role = "user", text = "", attachmentCount = 0))
        assertFalse(isHumanTurnShape(role = "assistant", text = "", attachmentCount = 0))
    }

    @Test
    fun `unparseable parts fail open so a row is never silently dropped`() {
        // Preserved from the original rule: an unreadable row counts, because
        // under-counting pushes the index past every match and skips the
        // truncation, while over-counting only truncates one turn early.
        assertTrue(MessagePartsCodec.hasHumanTurnContent("{not json"))
    }
}
