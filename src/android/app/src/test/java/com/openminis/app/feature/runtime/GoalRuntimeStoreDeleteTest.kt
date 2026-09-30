package com.openminis.app.feature.runtime

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalRuntimeStoreDeleteTest {
    @Test
    fun `delete is idempotent and removes persisted goal`() {
        val dir = createTempDir(prefix = "goal-delete-")
        val file = File(dir, "goal.json")
        val store = GoalRuntimeStore.openForTest(file)
        assertTrue(store.save(GoalRuntimeSnapshot(GoalStatus.ACTIVE, "x")))
        assertTrue(store.delete())
        assertFalse(file.exists())
        assertTrue(store.delete())
        dir.deleteRecursively()
    }
}
