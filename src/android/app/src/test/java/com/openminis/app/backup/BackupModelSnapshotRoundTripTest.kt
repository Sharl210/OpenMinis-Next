package com.openminis.app.backup

import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.CompactMarkerEntity
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Executable wire round-trip contract for the model-attribution snapshot fields.
 * The production record builders/import loop are private and require Android
 * Context + Room; this test exercises the same wire keys and importer coercions
 * without bringing Android runtime dependencies into JVM tests.
 */
class BackupModelSnapshotRoundTripTest {
    @Test
    fun `session title snapshot fields round trip including nullable values and time`() {
        val source = ChatSessionEntity(
            id = "session-1",
            title = "Title",
            modelId = "chat-model",
            createdAt = 1_700_000_000_123,
            updatedAt = 1_700_000_010_456,
            titleModelEntryId = "entry-title",
            titleModelId = "title-model",
            titleModelDisplayName = "Title Model",
            titleProviderType = "OPENAI",
            titleGeneratedAt = 1_700_000_020_789,
        )

        val wire = BackupModelSnapshotCodec.sessionTitleFields(source)
        val restored = ChatSessionEntity(
            id = source.id,
            title = source.title,
            modelId = source.modelId,
            createdAt = source.createdAt,
            updatedAt = source.updatedAt,
            titleModelEntryId = BackupModelSnapshotCodec.stringOrNull(wire, "titleModelEntryId"),
            titleModelId = BackupModelSnapshotCodec.stringOrNull(wire, "titleModelId"),
            titleModelDisplayName = BackupModelSnapshotCodec.stringOrNull(wire, "titleModelDisplayName"),
            titleProviderType = BackupModelSnapshotCodec.stringOrNull(wire, "titleProviderType"),
            titleGeneratedAt = BackupModelSnapshotCodec.millisOrNull(wire, "titleGeneratedAt"),
        )

        assertEquals(source.titleModelEntryId, restored.titleModelEntryId)
        assertEquals(source.titleModelId, restored.titleModelId)
        assertEquals(source.titleModelDisplayName, restored.titleModelDisplayName)
        assertEquals(source.titleProviderType, restored.titleProviderType)
        assertEquals(source.titleGeneratedAt, restored.titleGeneratedAt)
    }

    @Test
    fun `compact marker snapshot fields round trip including nullable values and time`() {
        val source = CompactMarkerEntity(
            id = "marker-1",
            sessionId = "session-1",
            summary = "summary",
            firstKeptSortOrder = 10,
            compactedCount = 8,
            createdAt = 1_700_000_000_123,
            modelRole = "COMPACTION",
            modelEntryId = "entry-compact",
            modelId = "compact-model",
            modelDisplayName = "Compact Model",
            providerType = "ANTHROPIC",
            providerInstanceId = "provider-instance",
            effectiveCompactionEntryId = "effective-entry",
            modelGeneratedAt = 1_700_000_030_987,
        )

        val wire = BackupModelSnapshotCodec.markerFields(source)
        val restored = CompactMarkerEntity(
            id = "marker-1",
            sessionId = "session-1",
            summary = source.summary,
            firstKeptSortOrder = source.firstKeptSortOrder,
            compactedCount = source.compactedCount,
            createdAt = source.createdAt,
            modelRole = BackupModelSnapshotCodec.stringOrNull(wire, "modelRole"),
            modelEntryId = BackupModelSnapshotCodec.stringOrNull(wire, "modelEntryId"),
            modelId = BackupModelSnapshotCodec.stringOrNull(wire, "modelId"),
            modelDisplayName = BackupModelSnapshotCodec.stringOrNull(wire, "modelDisplayName"),
            providerType = BackupModelSnapshotCodec.stringOrNull(wire, "providerType"),
            providerInstanceId = BackupModelSnapshotCodec.stringOrNull(wire, "providerInstanceId"),
            effectiveCompactionEntryId = BackupModelSnapshotCodec.stringOrNull(wire, "effectiveCompactionEntryId"),
            modelGeneratedAt = BackupModelSnapshotCodec.millisOrNull(wire, "modelGeneratedAt"),
        )

        assertEquals(source.modelRole, restored.modelRole)
        assertEquals(source.modelEntryId, restored.modelEntryId)
        assertEquals(source.modelId, restored.modelId)
        assertEquals(source.modelDisplayName, restored.modelDisplayName)
        assertEquals(source.providerType, restored.providerType)
        assertEquals(source.providerInstanceId, restored.providerInstanceId)
        assertEquals(source.effectiveCompactionEntryId, restored.effectiveCompactionEntryId)
        assertEquals(source.modelGeneratedAt, restored.modelGeneratedAt)
    }

    @Test
    fun `older backup without snapshot fields restores nulls`() {
        val oldSession = BackupFormat.json.parseToJsonElement("""{"id":"old-session"}""").jsonObject
        val oldMarker = BackupFormat.json.parseToJsonElement("""{"id":"old-marker","sessionId":"old-session"}""").jsonObject

        assertNull(BackupModelSnapshotCodec.stringOrNull(oldSession, "titleModelEntryId"))
        assertNull(BackupModelSnapshotCodec.stringOrNull(oldSession, "titleModelId"))
        assertNull(BackupModelSnapshotCodec.stringOrNull(oldSession, "titleModelDisplayName"))
        assertNull(BackupModelSnapshotCodec.stringOrNull(oldSession, "titleProviderType"))
        assertNull(BackupModelSnapshotCodec.millisOrNull(oldSession, "titleGeneratedAt"))
        assertNull(BackupModelSnapshotCodec.stringOrNull(oldMarker, "modelRole"))
        assertNull(BackupModelSnapshotCodec.stringOrNull(oldMarker, "modelEntryId"))
        assertNull(BackupModelSnapshotCodec.stringOrNull(oldMarker, "modelId"))
        assertNull(BackupModelSnapshotCodec.stringOrNull(oldMarker, "modelDisplayName"))
        assertNull(BackupModelSnapshotCodec.stringOrNull(oldMarker, "providerType"))
        assertNull(BackupModelSnapshotCodec.stringOrNull(oldMarker, "providerInstanceId"))
        assertNull(BackupModelSnapshotCodec.stringOrNull(oldMarker, "effectiveCompactionEntryId"))
        assertNull(BackupModelSnapshotCodec.millisOrNull(oldMarker, "modelGeneratedAt"))
    }

}
