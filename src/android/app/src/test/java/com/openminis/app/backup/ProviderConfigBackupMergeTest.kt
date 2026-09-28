package com.openminis.app.backup

import com.openminis.app.data.model.ProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderConfigBackupMergeTest {
    private val scalarFields = setOf(
        ProviderConfig.BACKUP_SCALAR_PRIMARY_MODEL_ENTRY_ID,
        ProviderConfig.BACKUP_SCALAR_SUBAGENT_GROUP_ID,
        ProviderConfig.BACKUP_SCALAR_TITLE_MODEL_ENTRY_ID,
        ProviderConfig.BACKUP_SCALAR_COMPACTION_MODEL_ENTRY_ID,
        ProviderConfig.BACKUP_SCALAR_TITLE_PROMPT,
        ProviderConfig.BACKUP_SCALAR_COMPACTION_PROMPT,
    )

    @Test
    fun `provider config scalar fields round trip with custom prompts and four role selections`() {
        val original = ProviderConfig(
            primaryModelEntryId = "primary-entry",
            defaultSubGroupId = "subagent-group",
            titleModelEntryId = "title-entry",
            compactionModelEntryId = "compact-entry",
            titlePrompt = "custom title prompt",
            compactionPrompt = "custom compact prompt",
        )
        val wire = BackupFormat.json.encodeToString(ProviderConfig.serializer(), original)
        val parsed = BackupImporter.parseProviderConfigLeniently(wire)
        val merged = ProviderConfig.mergeBackupScalars(ProviderConfig(), parsed.config, parsed.presentTopLevelFields)

        assertEquals(original.primaryModelEntryId, merged.primaryModelEntryId)
        assertEquals(original.defaultSubGroupId, merged.defaultSubGroupId)
        assertEquals(original.titleModelEntryId, merged.titleModelEntryId)
        assertEquals(original.compactionModelEntryId, merged.compactionModelEntryId)
        assertEquals(original.titlePrompt, merged.titlePrompt)
        assertEquals(original.compactionPrompt, merged.compactionPrompt)
    }

    @Test
    fun `old backup absent scalar fields preserve local values`() {
        val local = ProviderConfig(
            primaryModelEntryId = "local-primary",
            defaultSubGroupId = "local-subagent",
            titleModelEntryId = "local-title-model",
            compactionModelEntryId = "local-compact-model",
            titlePrompt = "local title prompt",
            compactionPrompt = "local compact prompt",
        )
        val parsed = BackupImporter.parseProviderConfigLeniently(
            """{"instances":[],"modelEntries":[],"modelGroups":[],"defaultPrimaryGroupId":"legacy-group"}""",
        )
        val merged = ProviderConfig.mergeBackupScalars(local, parsed.config, parsed.presentTopLevelFields)

        assertEquals("local-primary", merged.primaryModelEntryId)
        assertEquals("local-subagent", merged.defaultSubGroupId)
        assertEquals("local-title-model", merged.titleModelEntryId)
        assertEquals("local-compact-model", merged.compactionModelEntryId)
        assertEquals("local title prompt", merged.titlePrompt)
        assertEquals("local compact prompt", merged.compactionPrompt)
    }

    @Test
    fun `explicit null clears prompt back to default and preserves absent model selections`() {
        val local = ProviderConfig(
            primaryModelEntryId = "primary",
            defaultSubGroupId = "subagent",
            titleModelEntryId = "title",
            compactionModelEntryId = "compaction",
            titlePrompt = "custom title",
            compactionPrompt = "custom compact",
        )
        val parsed = BackupImporter.parseProviderConfigLeniently(
            """{"titlePrompt":null,"compactionPrompt":null,"instances":[],"modelEntries":[],"modelGroups":[]}""",
        )
        val merged = ProviderConfig.mergeBackupScalars(local, parsed.config, parsed.presentTopLevelFields)

        assertNull(merged.titlePrompt)
        assertNull(merged.compactionPrompt)
        assertEquals("primary", merged.primaryModelEntryId)
        assertEquals("subagent", merged.defaultSubGroupId)
        assertEquals("title", merged.titleModelEntryId)
        assertEquals("compaction", merged.compactionModelEntryId)
    }

    @Test
    fun `explicit values replace local values for each scalar`() {
        val local = ProviderConfig(
            primaryModelEntryId = "old-primary",
            defaultSubGroupId = "old-subagent",
            titleModelEntryId = "old-title",
            compactionModelEntryId = "old-compaction",
            titlePrompt = "old title prompt",
            compactionPrompt = "old compact prompt",
        )
        val remote = ProviderConfig(
            primaryModelEntryId = "new-primary",
            defaultSubGroupId = "new-subagent",
            titleModelEntryId = "new-title",
            compactionModelEntryId = "new-compaction",
            titlePrompt = "new title prompt",
            compactionPrompt = "new compact prompt",
        )
        val merged = ProviderConfig.mergeBackupScalars(local, remote, scalarFields)

        assertEquals("new-primary", merged.primaryModelEntryId)
        assertEquals("new-subagent", merged.defaultSubGroupId)
        assertEquals("new-title", merged.titleModelEntryId)
        assertEquals("new-compaction", merged.compactionModelEntryId)
        assertEquals("new title prompt", merged.titlePrompt)
        assertEquals("new compact prompt", merged.compactionPrompt)
    }
}
