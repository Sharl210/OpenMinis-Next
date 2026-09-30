package com.openminis.app.feature.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalRuntimeStoreTest {
    @Test
    fun `goal snapshot survives atomic save and reload`() {
        val dir = createTempDir(prefix = "goal-store-")
        val file = File(dir, "goal.json")
        val store = GoalRuntimeStore.openForTest(file)
        val expected = GoalRuntimeSnapshot(GoalStatus.ACTIVE, "ship", updatedAtMillis = 42)
        assertTrue(store.save(expected))
        assertEquals(expected, store.load())
        dir.deleteRecursively()
    }
}
