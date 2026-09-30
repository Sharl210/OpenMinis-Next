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
import org.junit.After
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

    /**
     * Backstop for the app-wide singleton the cases above mutate.
     *
     * Every case already clears the map in its own `finally`, which is what makes
     * the assertions inside it meaningful; this only covers the window BEFORE that
     * `finally` can run (a failure while setting the case up) so one case cannot
     * hand a stale handle to the next one in a different order.
     */
    @After
    fun clearTrackerStateAfterEachCase() {
        clearHandles()
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
            val started = CompletableDeferred<Unit>()
            val job = scope.launch {
                SessionActivityTracker.registerChildCanceller("child-e", kotlinx.coroutines.currentCoroutineContext()[Job])
                started.complete(Unit)
                delay(10_000)
            }
            // Handshake, not a bare sleep: a fixed delay races the registration, and
            // a cancel that lands before `registerChildCanceller` runs would leave
            // no handle at all — the assertions below would then pass for the wrong
            // reason, on a run that never existed.
            started.await()
            assertEquals("the run must be registered before the cancellation", 1, handleCount())
            job.cancel(CancellationException("already cancelled"))
            withTimeout(5_000) { job.join() }
            withTimeout(5_000) {
                while (handleCount() != 0) delay(5)
            }
            val dispatched = SessionActivityTracker.cancelChildSessions(listOf("child-e"))
            assertEquals(
                "a run that already ended by cancellation has retired its handle: it must not be " +
                    "handed a second cancellation, and must not be reported as newly interrupted: dispatched=$dispatched",
                emptyList<String>(),
                dispatched,
            )
        } finally {
            scope.cancel()
            clearHandles()
        }
    }

    /**
     * The explicit counterpart of the assertion above.
     *
     * `emptyList()` alone is only half a statement: an implementation that never
     * dispatched anything would satisfy it for the wrong reason. Running the same
     * entry point against a LIVE run and requiring the non-empty answer rules that
     * family out, so both directions are pinned together in one file.
     *
     * What the pair does NOT establish (measured against a candidate mutant): an
     * implementation that retires a handle only when the job was cancelled would
     * satisfy BOTH of these — the cancelled run is retired and the live one is
     * still registered. That defect is caught by `a finished run is not handed a
     * cancellation after it retired its handle` instead. So this is a discriminator
     * against the never-dispatch and report-the-request families, not a proof that
     * "live vs finished" is the only thing being distinguished.
     *
     * The observation shape matches `a registered child run is cancelled under its
     * own session id`; this one is kept as the explicit near-miss for the assertion
     * above, not as new coverage.
     */
    @Test
    fun `a live run is handed the cancellation, so the empty answer above discriminates`() = runBlocking {
        clearHandles()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val started = CompletableDeferred<Unit>()
            var loops = 0
            val job = scope.launch {
                SessionActivityTracker.registerChildCanceller("child-f", kotlinx.coroutines.currentCoroutineContext()[Job])
                started.complete(Unit)
                while (true) {
                    delay(20)
                    loops++
                }
            }
            started.await()
            delay(80)
            assertTrue("the run must be doing work before the stop", loops > 0)
            assertEquals("exactly the live run may be registered", 1, handleCount())

            assertEquals(
                "a live run must be handed the cancellation",
                listOf("child-f"),
                SessionActivityTracker.cancelChildSessions(listOf("child-f")),
            )
            withTimeout(5_000) { job.join() }
            assertTrue(job.isCancelled)
        } finally {
            scope.cancel()
            clearHandles()
        }
    }
}
