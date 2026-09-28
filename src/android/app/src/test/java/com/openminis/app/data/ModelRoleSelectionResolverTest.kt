package com.openminis.app.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelRoleSelectionResolverTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `role selections survive config serialization and null remains legacy fallback`() {
        val selected = ProviderConfig(
            titleModelEntryId = "title-entry",
            compactionModelEntryId = "compact-entry",
        )
        val restored = json.decodeFromString<ProviderConfig>(json.encodeToString(selected))
        assertEquals("title-entry", restored.titleModelEntryId)
        assertEquals("compact-entry", restored.compactionModelEntryId)

        val legacy = json.decodeFromString<ProviderConfig>("{}")
        assertNull(legacy.titleModelEntryId)
        assertNull(legacy.compactionModelEntryId)
    }
    @Test
    fun `provider config prompts and role pointers preserve explicit null and values`() {
        val selected = ProviderConfig(
            titleModelEntryId = "title-entry",
            compactionModelEntryId = "compact-entry",
            titlePrompt = "custom title",
            compactionPrompt = "custom compact",
        )
        val encoded = com.openminis.app.backup.BackupFormat.json.encodeToString(
            ProviderConfig.serializer(), selected,
        )
        val restored = com.openminis.app.backup.BackupFormat.json.decodeFromString(
            ProviderConfig.serializer(), encoded,
        )
        assertEquals("title-entry", restored.titleModelEntryId)
        assertEquals("compact-entry", restored.compactionModelEntryId)
        assertEquals("custom title", restored.titlePrompt)
        assertEquals("custom compact", restored.compactionPrompt)

        val legacy = json.decodeFromString<ProviderConfig>("{}")
        assertNull(legacy.titleModelEntryId)
        assertNull(legacy.compactionModelEntryId)
        assertNull(legacy.titlePrompt)
        assertNull(legacy.compactionPrompt)

        val explicitNull = kotlinx.serialization.json.Json { explicitNulls = true }
            .decodeFromString<ProviderConfig>(
                """{"titlePrompt":null,"compactionPrompt":null,"titleModelEntryId":null,"compactionModelEntryId":null}"""
            )
        assertNull(explicitNull.titlePrompt)
        assertNull(explicitNull.compactionPrompt)
        assertNull(explicitNull.titleModelEntryId)
        assertNull(explicitNull.compactionModelEntryId)
    }

    @Test
    fun `backup scalar merge preserves missing fields and applies present nulls`() {
        val local = ProviderConfig(
            titlePrompt = "local title",
            compactionPrompt = "local compact",
            titleModelEntryId = "local-title-model",
            compactionModelEntryId = "local-compact-model",
            agentLoopModelEntryIds = mutableListOf("local-agent"),
            agentLoopGroupIds = mutableListOf("local-group"),
        )
        val remote = ProviderConfig(
            titlePrompt = "remote title",
            compactionPrompt = "remote compact",
            titleModelEntryId = "remote-title-model",
            compactionModelEntryId = "remote-compact-model",
            agentLoopModelEntryIds = mutableListOf("remote-agent"),
            agentLoopGroupIds = mutableListOf("remote-group"),
        )

        val legacy = ProviderConfig.mergeBackupScalars(local, remote, emptySet())
        assertEquals("local title", legacy.titlePrompt)
        assertEquals("local compact", legacy.compactionPrompt)
        assertEquals("local-title-model", legacy.titleModelEntryId)
        assertEquals("local-compact-model", legacy.compactionModelEntryId)

        val imported = ProviderConfig.mergeBackupScalars(
            local,
            remote,
            setOf(
                ProviderConfig.BACKUP_SCALAR_TITLE_PROMPT,
                ProviderConfig.BACKUP_SCALAR_COMPACTION_PROMPT,
                ProviderConfig.BACKUP_SCALAR_TITLE_MODEL_ENTRY_ID,
                ProviderConfig.BACKUP_SCALAR_COMPACTION_MODEL_ENTRY_ID,
            ),
        )
        assertEquals("remote title", imported.titlePrompt)
        assertEquals("remote compact", imported.compactionPrompt)
        assertEquals("remote-title-model", imported.titleModelEntryId)
        assertEquals("remote-compact-model", imported.compactionModelEntryId)
        assertEquals(listOf("local-agent"), imported.agentLoopModelEntryIds)
        assertEquals(listOf("local-group"), imported.agentLoopGroupIds)

        val clearedRemote = ProviderConfig(
            titlePrompt = null,
            compactionPrompt = null,
            titleModelEntryId = null,
            compactionModelEntryId = null,
        )
        val cleared = ProviderConfig.mergeBackupScalars(
            local,
            clearedRemote,
            setOf(
                ProviderConfig.BACKUP_SCALAR_TITLE_PROMPT,
                ProviderConfig.BACKUP_SCALAR_COMPACTION_PROMPT,
                ProviderConfig.BACKUP_SCALAR_TITLE_MODEL_ENTRY_ID,
                ProviderConfig.BACKUP_SCALAR_COMPACTION_MODEL_ENTRY_ID,
            ),
        )
        assertNull(cleared.titlePrompt)
        assertNull(cleared.compactionPrompt)
        assertNull(cleared.titleModelEntryId)
        assertNull(cleared.compactionModelEntryId)
    }
}
