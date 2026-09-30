package com.openminis.app.backup

import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.ui.chat.persistedThinkingLevel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-thinking-level-persist] Backup → restore must preserve the thinking
 * level a reply was produced at, and a package that predates the field must
 * restore as "not recorded" rather than crashing or inventing a level.
 *
 * ## Why this file exists separately from the local round trip
 *
 * Making the level survive a restart fixed the path the user sees most, but a
 * message has TWO ways to reach a device: the local database, and a package
 * restore. `BackupExporter.messageRecord` and
 * `BackupImporter.messageEntityFromRecord` are a second, independently
 * maintained mapping of the same row — and they were in exactly the state the
 * whole defect is about: the four model-attribution columns were copied on both
 * sides, and the level was in neither, so a restored package dropped every level
 * badge. A field added to one mapping has to be added to the other, and only a
 * test that runs both can notice when it is not.
 *
 * ## Both halves are the production functions
 *
 * No copy of the field list is written here. The export side calls
 * [BackupExporter.messageRecord], the import side calls
 * [BackupImporter.messageEntityFromRecord] — the same two functions the real
 * export and restore use. A test that restated the mapping would have shared the
 * omission it is supposed to catch.
 *
 * ## Reverse proof
 *
 * Checked by deleting each side in turn: removing
 * `m.thinkingLevel?.let { put("thinkingLevel", …) }` from the exporter, and
 * removing `thinkingLevel = m.str("thinkingLevel")` from the importer, each turn
 * the round-trip test red (tagged per test below). Restored afterwards.
 *
 * ## Version bump — checked, not assumed
 *
 * Adding a field does NOT need a `BackupFormat` bump, and bumping would be
 * actively harmful here:
 *
 *  - `CURRENT = "minisbak/1"` is matched on the MAJOR version only
 *    (`BackupPackageReader` compares `substringBefore('.')`), so a new field
 *    inside `messages.jsonl` cannot make an old reader refuse the package. A
 *    bump to `minisbak/2` would make every package already on a user's device —
 *    and every package iOS writes — refuse to import with "created by a newer
 *    version of Minis".
 *  - the per-record `v` in the `t`/`v`/`d` envelope is written by
 *    `BackupJsonlWriter` and **never read**: `readJsonl` takes only `d`. Bumping
 *    a record version would advertise a per-record migration protocol that does
 *    not exist.
 *  - the field is optional in both directions, which is how the neighbouring
 *    attribution columns shipped (MIGRATION_11_12, no format bump): the exporter
 *    omits it when null, the importer defaults it to null when absent, and an
 *    older importer that does not know the key simply ignores it.
 */
class BackupThinkingLevelRoundTripTest {

    private fun row(level: String?, model: String? = "gpt-5.6") = MessageEntity(
        id = "msg-1",
        sessionId = "session-1",
        role = "assistant",
        partsJson = """[{"type":"text","value":"answer"}]""",
        createdAt = 1_755_000_000_000L,
        sortOrder = 0,
        modelId = model,
        modelDisplayName = model,
        providerType = model?.let { "openAI" },
        thinkingLevel = level,
    )

    /**
     * Export one row, then import the record it produced — the two production
     * mappings end to end.
     */
    private fun restore(exported: MessageEntity): MessageEntity {
        val record = BackupExporter.messageRecord(exported).jsonObject
        return BackupImporter.messageEntityFromRecord(
            m = record,
            id = exported.id,
            sessionId = exported.sessionId,
            createdAt = exported.createdAt,
        )
    }

    // ------------------------------------------------------------- round trip

    @Test
    fun `a backed-up message keeps its thinking level through export and restore`() {
        // REVERSE-PROOF: fails if EITHER the export line or the import line is
        // removed — that is the point of going through both functions.
        val restored = restore(row(ThinkingLevel.HIGH.name))

        assertEquals("HIGH", restored.thinkingLevel)
        assertEquals(
            "the restored level must decode to the level the reply ran at",
            ThinkingLevel.HIGH,
            persistedThinkingLevel(restored.thinkingLevel),
        )
        // The model attribution next to it must keep working: this field was
        // added to a codec that already carried four, and a refactor that fixed
        // one by breaking the others would be a net loss.
        assertEquals("gpt-5.6", restored.modelDisplayName)
        assertEquals("openAI", restored.providerType)
    }

    @Test
    fun `every recorded level survives the wire`() {
        for (level in ThinkingLevel.entries) {
            val restored = restore(row(level.name))
            assertEquals(
                "level $level did not survive backup → restore",
                level,
                persistedThinkingLevel(restored.thinkingLevel),
            )
        }
    }

    @Test
    fun `OFF is restored as OFF, not as unrecorded`() {
        // The two states must not merge: `OFF` renders an "Off" capsule, while a
        // row with no recorded level renders none. Emitting null instead of
        // "OFF" (or vice versa) would silently change what history claims.
        val restored = restore(row(ThinkingLevel.OFF.name))
        assertEquals("OFF", restored.thinkingLevel)
        assertEquals(ThinkingLevel.OFF, persistedThinkingLevel(restored.thinkingLevel))
        assertTrue(persistedThinkingLevel(restored.thinkingLevel) != null)
    }

    // -------------------------------------------------------- the wire shape

    @Test
    fun `the exported record uses the same token as the local column`() {
        // The local column stores ThinkingLevel.name. If the wire used the
        // display label ("XHigh") or the ordinal, the same level would decode to
        // different values depending on which path a message arrived by — the
        // clamping in ThinkingLevel.decoded means that never throws, it just
        // becomes a different level.
        val record = BackupExporter.messageRecord(row(ThinkingLevel.XHIGH.name)).jsonObject
        assertEquals("XHIGH", record["thinkingLevel"]?.jsonPrimitive?.content)
        assertEquals(
            "the wire key must stay camelCase to match the rest of the record " +
                "(and iOS's Codable property names)",
            "thinkingLevel",
            record.keys.first { it.equals("thinkinglevel", ignoreCase = true) },
        )
    }

    @Test
    fun `a message with no recorded level omits the key instead of writing null`() {
        // Same convention as the four attribution columns beside it: omitted when
        // absent, so a package from a device with no levels keeps its previous
        // shape and older importers see nothing new.
        val record = BackupExporter.messageRecord(row(level = null)).jsonObject
        assertFalse("unrecorded level must not be written", "thinkingLevel" in record.keys)
        assertNull(restore(row(level = null)).thinkingLevel)
    }

    // ------------------------------------------------------ backward compatibility

    @Test
    fun `a package written before the field existed restores as unrecorded`() {
        // A record from an older build (or from iOS, which has no such field):
        // the key is simply absent. The importer must not crash and must not
        // substitute a default — "unknown" has to stay unknown, which is what
        // keeps it from being displayed as a level the user never chose.
        val legacy = buildJsonObject {
            put("id", JsonPrimitive("msg-legacy"))
            put("sessionId", JsonPrimitive("session-1"))
            put("role", JsonPrimitive("assistant"))
            put("parts", BackupFormat.json.parseToJsonElement("""[{"type":"text","value":"old"}]"""))
            put("createdAt", JsonPrimitive("2026-08-01T00:00:00Z"))
            put("sortOrder", JsonPrimitive(0))
            put("modelDisplayName", JsonPrimitive("gpt-5.6"))
            put("providerType", JsonPrimitive("openAI"))
        }

        val restored = BackupImporter.messageEntityFromRecord(
            m = legacy,
            id = "msg-legacy",
            sessionId = "session-1",
            createdAt = 1L,
        )
        assertNull(restored.thinkingLevel)
        assertNull(persistedThinkingLevel(restored.thinkingLevel))
        // …and the fields that ARE present still come through, so "tolerant of a
        // missing key" did not become "skips the row".
        assertEquals("gpt-5.6", restored.modelDisplayName)
        assertEquals("assistant", restored.role)
    }

    @Test
    fun `a null-typed or empty level field is unrecorded, not a level`() {
        // kotlinx writes an explicit JSON null for a nullable field that IS
        // present; some writers also emit "". Neither may become a capsule.
        for (raw in listOf("null", "\"\"", "\"   \"")) {
            val weird = buildJsonObject {
                put("role", JsonPrimitive("assistant"))
                put("parts", BackupFormat.json.parseToJsonElement("[]"))
                put("thinkingLevel", BackupFormat.json.parseToJsonElement(raw))
            }
            val restored = BackupImporter.messageEntityFromRecord(weird, "m", "s", 1L)
            assertNull("raw=$raw must restore as unrecorded", persistedThinkingLevel(restored.thinkingLevel))
        }
    }

    @Test
    fun `a level token from a newer build does not abort the restore`() {
        // Forward tolerance, same rule as ThinkingLevel.decoded everywhere else:
        // an unrecognised token clamps to the highest level this build knows
        // rather than throwing — an exception here would abort a whole restore.
        val future = buildJsonObject {
            put("role", JsonPrimitive("assistant"))
            put("parts", BackupFormat.json.parseToJsonElement("[]"))
            put("thinkingLevel", JsonPrimitive("SUPREME"))
        }
        val restored = BackupImporter.messageEntityFromRecord(future, "m", "s", 1L)
        assertEquals(ThinkingLevel.XHIGH, persistedThinkingLevel(restored.thinkingLevel))
        // Still not the same state as "unrecorded", so the capsule logic can
        // tell them apart.
        assertNull(persistedThinkingLevel(null))
    }

    // ------------------------------------------------------------- the format

    @Test
    fun `the package format major version is untouched by this field`() {
        // Guards the temptation to bump the version for a purely additive field:
        // `readManifest` refuses any package whose MAJOR version is unrecognised,
        // so a bump would lock out every existing package (and every iOS one)
        // with "created by a newer version of Minis".
        assertEquals("minisbak/1", BackupFormat.CURRENT)
    }

    @Test
    fun `the restored row is a full message row, not a partial one`() {
        // A mapping extracted for testability must still build a complete
        // `MessageEntity`: the surrounding import loop no longer names its
        // fields, so an argument dropped during the move would compile and then
        // import rows with the wrong session, role or body.
        val restored = restore(row(ThinkingLevel.MEDIUM.name))
        assertEquals("msg-1", restored.id)
        assertEquals("session-1", restored.sessionId)
        assertEquals("assistant", restored.role)
        assertEquals(1_755_000_000_000L, restored.createdAt)
        assertEquals(1_755_000_000_000L, restored.updatedAt)
        assertTrue(restored.partsJson.contains("answer"))
        // errorInfo is device-local and must never be restored (§0.2).
        assertNull(restored.errorInfo)
    }

    /** The record the round trip actually puts on the wire, for eyeballing. */
    @Test
    fun `the exported record still carries the pre-existing fields`() {
        val record: JsonObject = BackupExporter.messageRecord(row(ThinkingLevel.LOW.name)).jsonObject
        for (key in listOf("id", "sessionId", "role", "parts", "createdAt", "sortOrder")) {
            assertTrue("$key vanished from the exported message record", key in record.keys)
        }
    }
}
