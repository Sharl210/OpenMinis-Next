package com.openminis.app.feature.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeChildRunnerTest {
    @Test
    fun `ordinary child failure becomes failed result and invokes cleanup`() = runBlocking {
        var cleanup: Throwable? = null
        val result = RuntimeChildRunner.runChildAttempt<Int>(
            onFailure = { cleanup = it },
        ) {
            error("provider failed")
        }

        assertFalse(result.isSuccess)
        assertEquals("provider failed", result.exceptionOrNull()?.message)
        assertEquals("provider failed", cleanup?.message)
    }

    @Test
    fun `successful child invokes success callback`() = runBlocking {
        var observed = 0
        val result = RuntimeChildRunner.runChildAttempt(
            onSuccess = { observed = it },
        ) { 42 }

        assertTrue(result.isSuccess)
        assertEquals(42, result.getOrNull())
        assertEquals(42, observed)
    }

    @Test
    fun `CancellationException propagates unchanged after cleanup`() = runBlocking {
        var cleanup: Throwable? = null
        val cancellation = CancellationException("caller cancelled")
        var thrown: CancellationException? = null

        try {
            RuntimeChildRunner.runChildAttempt<Unit>(
                onFailure = { cleanup = it },
            ) {
                throw cancellation
            }
        } catch (error: CancellationException) {
            thrown = error
        }

        assertSame(cancellation, thrown)
        assertSame(cancellation, cleanup)
    }

    @Test
    fun `cleanup failure never replaces original cancellation`() = runBlocking {
        val cancellation = CancellationException("caller cancelled")
        var thrown: CancellationException? = null

        try {
            RuntimeChildRunner.runChildAttempt<Unit>(
                onFailure = { failure -> error("cleanup failed: ${failure.message}") },
            ) {
                throw cancellation
            }
        } catch (error: CancellationException) {
            thrown = error
        }

        assertSame(cancellation, thrown)
    }
}
