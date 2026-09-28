package com.openminis.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelRoleRequestSnapshotTest {
    @Test
    fun `snapshot captures four roles and compaction falls back to primary`() {
        val config = ProviderConfig(
            primaryModelEntryId = "primary",
            agentLoopModelEntryIds = mutableListOf("child-a"),
            agentLoopGroupIds = mutableListOf("group-a"),
            subAgentModelNotes = mutableMapOf("child-a" to "fast", "group-a" to "review"),
            titleModelEntryId = "title",
        )
        val snapshot = ModelRoleRequestSnapshot.from(config)
        assertEquals("primary", snapshot.primaryEntryId)
        assertEquals(listOf("child-a"), snapshot.childEntryIds)
        assertEquals(listOf("group-a"), snapshot.childGroupIds)
        assertEquals(mapOf("child-a" to "fast", "group-a" to "review"), snapshot.childNotes)
        assertEquals("title", snapshot.titleEntryId)
        assertEquals("primary", snapshot.effectiveCompactionEntryId())
    }

    @Test
    fun `legacy primary group is retained and explicit compaction wins`() {
        val config = ProviderConfig(defaultPrimaryGroupId = "legacy-group", compactionModelEntryId = "compact")
        val snapshot = ModelRoleRequestSnapshot.from(config)
        assertNull(snapshot.primaryEntryId)
        assertEquals("legacy-group", snapshot.legacyPrimaryGroupId)
        assertEquals("compact", snapshot.effectiveCompactionEntryId())
        assertNull(snapshot.titleEntryId)
    }
}
