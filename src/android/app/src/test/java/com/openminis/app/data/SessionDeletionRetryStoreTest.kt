package com.openminis.app.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDeletionRetryStoreTest {
    @Test
    fun `failed step names survive reload and clear atomically`() {
        val dir = createTempDir(prefix = "delete-retry-")
        val store = SessionDeletionRetryStore.openForTest(File(dir, "retry.json"))
        assertTrue(store.save("s1", setOf("media", "goal")))
        val reloaded = SessionDeletionRetryStore.openForTest(File(dir, "retry.json"))
        assertEquals(setOf("media", "goal"), reloaded.load("s1"))
        assertTrue(reloaded.save("s1", emptySet()))
        assertEquals(emptySet<String>(), reloaded.load("s1"))
        dir.deleteRecursively()
    }

    @Test
    fun `retry ledger isolates sessions and stores deterministic unique step names`() {
        val dir = createTempDir(prefix = "delete-retry-isolated-")
        val file = File(dir, "retry.json")
        val store = SessionDeletionRetryStore.openForTest(file)

        assertTrue(store.save("s1", linkedSetOf("goal", "media", "goal")))
        assertTrue(store.save("s2", setOf("chat")))

        val reloaded = SessionDeletionRetryStore.openForTest(file)
        assertEquals(setOf("goal", "media"), reloaded.load("s1"))
        assertEquals(setOf("chat"), reloaded.load("s2"))
        assertEquals(emptySet<String>(), reloaded.load("missing"))
        dir.deleteRecursively()
    }

    @Test
    fun `repeated clear is idempotent`() {
        val dir = createTempDir(prefix = "delete-retry-clear-")
        val store = SessionDeletionRetryStore.openForTest(File(dir, "retry.json"))

        assertTrue(store.save("s1", setOf("media")))
        assertTrue(store.save("s1", emptySet()))
        assertTrue(store.save("s1", emptySet()))
        assertEquals(emptySet<String>(), store.load("s1"))
        dir.deleteRecursively()
    }
}
