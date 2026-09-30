package com.openminis.app.service

import java.lang.reflect.Field
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-stop-descendant-cancel] The bookkeeping half of a stop: which
 * coroutine a stop reaches, and which one it must no longer reach.
 *
 * [SessionActivityTracker.cancelChildSessions] is the only thing in the app that
 * can end a delegated child's run — the runtime cannot (it only relabels nodes),
 * and `streamCancellers` deliberately covers root streams only. Two ways that
 * entry point can be wrong without any test noticing:
 *
 *  * it can hand a cancellation to a coroutine that has already finished, which
 *    turns "the child was interrupted" into a lie for a child that ended cleanly;
 *  * it can keep an old handle after the SAME session id starts running again
 *    (the child-restart path re-runs a session id), so a stop cancels the wrong
 *    run — or nothing.
 *
 * Both are decided by how the handle is retired, so both are pinned here against
 * real coroutines rather than against the map's contents.
 */
class SessionActivityTrackerChildCancellerTest {

    /**
     * Reads a private map field of the app-wide tracker singleton.
     *
     * Reflection, not a new public getter: the map is an implementation detail of
     * the two public entry points, and widening the API to make a test reachable
     * is how a test starts defining production.
     */
    private fun privateMap(name: String): MutableMap<*, *> {
        val field: Field = SessionActivityTracker::class.java.getDeclaredField(name)
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(SessionActivityTracker) as MutableMap<*, *>
    }

    private fun handleCount(): Int = privateMap("childCancellers").size

    private fun clearHandles() {
        privateMap("childCancellers").clear()
    }

    @Test
    fun `a registered child run is cancelled under its own session id`() = runBlocking {
        clearHandles()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val started = CompletableDeferred<Unit>()
            var loops = 0
            val job = scope.launch {
                SessionActivityTracker.registerChildCanceller("child-a", kotlinx.coroutines.currentCoroutineContext()[Job])
                started.complete(Unit)
                while (true) {
                    delay(20)
                    loops++
                }
            }
            started.await()
            delay(80)
            assertTrue("the child must be doing work before the stop", loops > 0)
            assertEquals("exactly the one run may be registered", 1, handleCount())

            val dispatched = SessionActivityTracker.cancelChildSessions(listOf("child-a", "child-a"))
            assertEquals(
                "the same session id may be reported once, not twice",
                listOf("child-a"),
                dispatched,
            )
            withTimeout(5_000) { job.join() }
            assertTrue(job.isCancelled)
            val atCancellation = loops
            delay(120)
            assertEquals("no further work after the cancellation", atCancellation, loops)
        } finally {
            scope.cancel()
            clearHandles()
        }
    }

    @Test
    fun `a finished run is not handed a cancellation after it retired its handle`() = runBlocking {
        clearHandles()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val started = CompletableDeferred<Unit>()
            val job = scope.launch {
                SessionActivityTracker.registerChildCanceller("child-b", kotlinx.coroutines.currentCoroutineContext()[Job])
                started.complete(Unit)
                delay(10)
            }
            started.await()
            job.join()
            withTimeout(5_000) {
                while (handleCount() != 0) delay(5)
            }
            assertEquals(
                "a completed run must retire its own handle, or a later stop would cancel a clean end",
                0,
                handleCount(),
            )
            assertEquals(
                "nothing may be reported as cancelled when no run is live",
                emptyList<String>(),
                SessionActivityTracker.cancelChildSessions(listOf("child-b")),
            )
            assertFalse("a cleanly finished run is not a cancelled one", job.isCancelled)
        } finally {
            scope.cancel()
            clearHandles()
        }
    }

    @Test
    fun `restarting the same session id replaces the handle so a stop reaches the live run`() = runBlocking {
        clearHandles()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            // First attempt: completes, as an interrupted-then-restarted child's
            // first run does.
            val firstStarted = CompletableDeferred<Unit>()
            val first = scope.launch {
                SessionActivityTracker.registerChildCanceller("child-c", kotlinx.coroutines.currentCoroutineContext()[Job])
                firstStarted.complete(Unit)
            }
            firstStarted.await()
            first.join()
            withTimeout(5_000) {
                while (handleCount() != 0) delay(5)
            }

            // Second attempt on the SAME session id — the restart path.
            val secondStarted = CompletableDeferred<Unit>()
            var secondLoops = 0
            val second = scope.launch {
                SessionActivityTracker.registerChildCanceller("child-c", kotlinx.coroutines.currentCoroutineContext()[Job])
                secondStarted.complete(Unit)
                while (true) {
                    delay(20)
                    secondLoops++
                }
            }
            secondStarted.await()
            delay(80)
            assertEquals("exactly the live run may be registered", 1, handleCount())

            assertEquals(
                listOf("child-c"),
                SessionActivityTracker.cancelChildSessions(listOf("child-c")),
            )
            withTimeout(5_000) { second.join() }
            assertTrue("the restarted run must be the one that was cancelled", second.isCancelled)
            assertFalse("the earlier, finished run must not be retroactively cancelled", first.isCancelled)
        } finally {
            scope.cancel()
            clearHandles()
        }
    }

    @Test
    fun `registering without a coroutine context is refused rather than stored as a no-op handle`() {
        clearHandles()
        try {
            SessionActivityTracker.registerChildCanceller("child-d", null)
            assertEquals(
                "a handle that cannot cancel anything must not be recorded as if it could",
                0,
                handleCount(),
            )
            SessionActivityTracker.registerChildCanceller("   ", Job())
            assertEquals("a blank session id addresses nothing", 0, handleCount())
        } finally {
            clearHandles()
        }
    }

    @Test
    fun `the root-stream cancel map is untouched by child cancellation`() {
        clearHandles()
        try {
            var rootCancelled = false
            SessionActivityTracker.setActive("root-session", onStop = { rootCancelled = true })
            try {
                SessionActivityTracker.cancelChildSessions(listOf("root-session"))
                assertFalse(
                    "a root stream is stopped by the notification's own path, not by a descendant stop",
                    rootCancelled,
                )
                assertTrue("the root stream must still be registered", SessionActivityTracker.isActive("root-session"))
            } finally {
                SessionActivityTracker.setInactive("root-session")
            }
            assertFalse(rootCancelled)
        } finally {
            clearHandles()
        }
    }

    /** The cancellers must survive a plain cancellation being thrown at them. */
    @Test
    fun `cancelling an already cancelled run is harmless`() = runBlocking {
        clearHandles()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val job = scope.launch {
                SessionActivityTracker.registerChildCanceller("child-e", kotlinx.coroutines.currentCoroutineContext()[Job])
                delay(10_000)
            }
            delay(50)
            job.cancel(CancellationException("already cancelled"))
            withTimeout(5_000) { job.join() }
            val dispatched = SessionActivityTracker.cancelChildSessions(listOf("child-e"))
            assertTrue(
                "cancelling an already-finished run must not throw: dispatched=$dispatched",
                dispatched.size <= 1,
            )
        } finally {
            scope.cancel()
            clearHandles()
        }
    }
}
