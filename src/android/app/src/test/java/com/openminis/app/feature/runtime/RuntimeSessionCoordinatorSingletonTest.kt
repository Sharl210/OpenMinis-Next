package com.openminis.app.feature.runtime

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSessionCoordinatorSingletonTest {
    @Test
    fun `legacy open reuses coordinator for the same runtime file`() {
        val directory = Files.createTempDirectory("runtime-coordinator").toFile()
        try {
            val context = TestContext(directory)
            val first = RuntimeSessionCoordinator.open(context)
            val second = RuntimeSessionCoordinator.open(context)

            assertSame(first, second)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `next runtime coordinator is isolated from legacy coordinator`() {
        val directory = Files.createTempDirectory("runtime-coordinator-next").toFile()
        try {
            val context = TestContext(directory)
            val legacy = RuntimeSessionCoordinator.open(context)
            val next = RuntimeSessionCoordinator.openNext(context)

            assertNotSame(legacy, next)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `agent behavior runtime config persists and rejects invalid bounds`() {
        val directory = Files.createTempDirectory("runtime-coordinator-config").toFile()
        try {
            val context = TestContext(directory)
            val coordinator = RuntimeSessionCoordinator.open(context)
            assertTrue(coordinator.applyAgentBehaviorSettings(recursionDepth = 1, parallelAgentLimit = 12))
            assertFalse(coordinator.applyAgentBehaviorSettings(recursionDepth = 3, parallelAgentLimit = 200))

            val reopened = RuntimeTreeStore.open(context).snapshot()
            assertEquals(1, reopened.config.maxDepth)
            assertEquals(12, reopened.config.maxParallelSubagents)
        } finally {
            directory.deleteRecursively()
        }
    }

    private class TestContext(private val directory: File) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = directory
    }
}
