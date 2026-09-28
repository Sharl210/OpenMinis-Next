package com.openminis.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ActualModelRequestSnapshotTest {
    @Test
    fun `snapshot preserves actual resolved model identity independent of role settings`() {
        val entry = ModelEntry(
            providerInstanceId = "instance-1",
            baseModel = LLMModel(id = "m-1", displayName = "Model One", provider = "test"),
            uuid = "entry-1",
        )
        val snapshot = ActualModelRequestSnapshot.capture(ModelRole.PRIMARY, entry, "openAI")
        assertEquals(ModelRole.PRIMARY, snapshot.role)
        assertEquals("entry-1", snapshot.entryId)
        assertEquals("m-1", snapshot.modelId)
        assertEquals("Model One", snapshot.displayName)
        assertEquals("openAI", snapshot.providerTypeRaw)
        assertEquals("instance-1", snapshot.providerInstanceId)
    }
}
