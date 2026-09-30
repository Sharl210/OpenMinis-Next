package com.openminis.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SessionArtifactDeletionCoordinatorTest {
    @Test
    fun `one failed step does not prevent later cleanup`() = runTest {
        val calls = mutableListOf<String>()
        val result = SessionArtifactDeletionCoordinator(
            listOf(
                SessionArtifactDeletionCoordinator.Step("first") { calls += "first" },
                SessionArtifactDeletionCoordinator.Step("bad") { calls += "bad"; error("boom") },
                SessionArtifactDeletionCoordinator.Step("last") { calls += "last" },
            ),
        ).run()
        assertEquals(listOf("first", "bad", "last"), calls)
        assertFalse(result.success)
        assertEquals(listOf("bad"), result.failed.keys.toList())
    }

    @Test
    fun `retry after partial failure completes remaining cleanup and reports success`() = runTest {
        val calls = mutableListOf<String>()
        var failOnce = true
        val coordinator = SessionArtifactDeletionCoordinator(
            listOf(
                SessionArtifactDeletionCoordinator.Step("chat") { calls += "chat" },
                SessionArtifactDeletionCoordinator.Step("media") {
                    calls += "media"
                    if (failOnce) {
                        failOnce = false
                        error("media unavailable")
                    }
                },
                SessionArtifactDeletionCoordinator.Step("goal") { calls += "goal" },
            ),
        )

        val first = coordinator.run()
        assertFalse(first.success)
        assertEquals(listOf("chat", "media", "goal"), calls)
        assertEquals(listOf("media"), first.failed.keys.toList())

        val second = coordinator.run()
        assertEquals(true, second.success)
        assertEquals(emptyMap<String, Throwable>(), second.failed)
        assertEquals(listOf("chat", "media", "goal", "chat", "media", "goal"), calls)
    }

    @Test
    fun `empty cleanup is successful and has no failed steps`() = runTest {
        val result = SessionArtifactDeletionCoordinator(emptyList()).run()

        assertEquals(true, result.success)
        assertEquals(emptyList<String>(), result.completed)
        assertEquals(emptyMap<String, Throwable>(), result.failed)
    }

    @Test(expected = CancellationException::class)
    fun `cancellation aborts cleanup without running later destructive steps`() = runTest {
        val calls = mutableListOf<String>()
        SessionArtifactDeletionCoordinator(
            listOf(
                SessionArtifactDeletionCoordinator.Step("first") { calls += "first"; throw CancellationException("cancelled") },
                SessionArtifactDeletionCoordinator.Step("later") { calls += "later" },
            ),
        ).run()
        assertEquals(listOf("first"), calls)
    }
}
