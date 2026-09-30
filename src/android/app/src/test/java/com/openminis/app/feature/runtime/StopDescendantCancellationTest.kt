package com.openminis.app.feature.runtime

import com.openminis.app.service.SessionActivityTracker
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-stop-descendant-cancel] What `request.md:250` means by "stopped":
 * 「它本身自己停下来，并且，他所派出去的子代理也都停下来了，才算是停止工作」 —
 * the agent itself stops AND every sub-agent it dispatched stops.
 *
 * The runtime could always say that. `SessionTreeRuntime.stopDescendant` walks the
 * target and its descendants and writes [RuntimeNodeStatus.STOP_REQUESTED] — and
 * `STOP_REQUESTED` is a TERMINAL status, so the stop receipt, the tree snapshot and
 * every status surface agreed that the work was over. Nothing cancelled the child's
 * coroutine. `stop_descendant` is dispatched by `ChatViewModel`, whose child runs
 * inside `viewModelScope`; `SessionActivityTracker` had a cancellation map for ROOT
 * streams only ([SessionActivityTracker.cancelAllActiveStreams] fans out
 * `streamCancellers`, populated by `setActive` — and a delegated child is
 * deliberately never a root). A child whose provider call was in flight therefore
 * kept requesting, kept appending to its transcript, and only ended when it ran out
 * of work by itself, while the parent was told it had stopped.
 *
 * ## What is executed here (A) — everything except two wiring lines
 *
 * `a stop request cancels the child's coroutine` runs a REAL coroutine, registers
 * its [Job] through the REAL [SessionActivityTracker.registerChildCanceller] (the
 * call `RuntimeChildRunner` makes), stops the child through the REAL
 * `RuntimeSessionCoordinator.stopDescendant`, then hands the receipt's ids to the
 * REAL [SessionActivityTracker.cancelChildSessions] — the call `ChatViewModel`
 * makes — and asserts the coroutine actually stopped: no further "provider call"
 * and no further "transcript write" happens, which a status assertion cannot show.
 * Its cancellation handler is the production cleanup (`finishDelegatedChild` →
 * `finishChild`), so the parent notification asserted afterwards is produced by the
 * runtime, not by the test.
 *
 * The second half of the defect is that the same window reported the wrong thing:
 * `complete()` refused a `STOP_REQUESTED` node and dropped the parent notification
 * with it, and `finishChild` filed a child the user had stopped as
 * `ABNORMAL_INTERRUPTION`. Both are pinned below against a real tree.
 *
 * ## What is NOT executed (C)
 *
 * The two wiring lines themselves — `ChatViewModel.executeStopDescendantTool`
 * calling `cancelChildSessions`, and `RuntimeChildRunner` calling
 * `registerChildCanceller` — cannot be executed in this module: `ChatViewModel`
 * needs Room, the provider repository and `Dispatchers.Main`, and
 * `RuntimeChildRunner.execute` needs a live provider stack. `RuntimeParentInboxDrainTest`
 * documents the same split for the same reason. Their (A) behaviour is what the
 * tests below run.
 */
class StopDescendantCancellationTest {

    /**
     * A real `RuntimeTreeStore` on a real temp file, a real
     * `RuntimeSessionCoordinator` over it, and that coordinator installed in the
     * app-wide [SessionActivityTracker] — which is how the tracker reaches the
     * runtime in production ([SessionActivityTracker.init]).
     *
     * Installing it through the tracker rather than driving the coordinator alone
     * matters here: the delegation, the stop and the cancelled child's cleanup then
     * travel the same three hops production uses.
     */
    private class Fixture {
        val dir: File = Files.createTempDirectory("runtime-stop-cancel").toFile()
        val store: RuntimeTreeStore = RuntimeTreeStore.openForTest(
            File(dir, "session-tree.json"),
        ) { source, target ->
            Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }
        val coordinator: RuntimeSessionCoordinator = run {
            val constructor = RuntimeSessionCoordinator::class.java
                .getDeclaredConstructor(RuntimeTreeStore::class.java)
            constructor.isAccessible = true
            constructor.newInstance(store)
        }

        val names = mutableMapOf<String, String>()
        val surfacing = RuntimeInboxSurfacing { nodeId -> names[nodeId] }

        init {
            installCoordinator()
        }

        private fun installCoordinator() {
            val field = SessionActivityTracker::class.java.getDeclaredField("runtimeCoordinator")
            field.isAccessible = true
            field.set(SessionActivityTracker, coordinator)
        }

        /**
         * The production delegation path, so the child node is the tracker's child
         * and the tracker's own bookkeeping has to clean it up afterwards.
         */
        fun delegateChild(childSessionId: String = "child") {
            assertTrue(
                SessionActivityTracker.beginDelegatedChild(
                    parentSessionId = "parent",
                    childSessionId = childSessionId,
                    request = RuntimeDelegationRequest(prompt = "count the wires"),
                    model = RuntimeModelSnapshot("test", "test-model"),
                ),
            )
        }

        /**
         * The tree-side half of delegation, without the tracker's child registry.
         *
         * Used by the tests that call `finishChild` themselves: they are about what
         * the runtime does with a stopped child, and routing them through the
         * tracker would leave its app-wide child map populated for the next test in
         * the same JVM.
         */
        fun startChildDirectly() {
            assertTrue(coordinator.startRoot("parent") != null)
            assertTrue(coordinator.startChild("parent", "child"))
        }

        fun node(childSessionId: String = "child"): RuntimeSessionNode? =
            store.snapshot().node(childSessionId)

        fun dispose() {
            val field = SessionActivityTracker::class.java.getDeclaredField("runtimeCoordinator")
            field.isAccessible = true
            field.set(SessionActivityTracker, null)
            dir.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------- (A)

    @Test
    fun `a stop request cancels the child's coroutine instead of only relabelling its node`() = runBlocking {
        val fixture = Fixture()
        val childScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            fixture.names["child"] = "Count the wires"
            fixture.delegateChild()

            val childStarted = CompletableDeferred<Unit>()
            val cleanupRan = CompletableDeferred<String>()
            // The child's "work": each round stands in for one provider call plus
            // the transcript append that follows it. Counting them is what turns
            // "the coroutine was cancelled" into an observation.
            var providerCalls = 0
            var transcriptWrites = 0

            val childJob: Job = childScope.launch {
                // The production registration, exactly as RuntimeChildRunner does
                // it: the coroutine that IS the child hands over its own Job.
                SessionActivityTracker.registerChildCanceller("child", currentCoroutineContext()[Job])
                try {
                    childStarted.complete(Unit)
                    while (isActive) {
                        delay(20)
                        providerCalls++
                        transcriptWrites++
                    }
                } catch (cancellation: CancellationException) {
                    // RuntimeChildRunner.runChildAttempt's cancellation branch.
                    SessionActivityTracker.finishDelegatedChild(
                        parentSessionId = "parent",
                        childSessionId = "child",
                        failed = true,
                        report = RuntimeStopReport(
                            nodeId = "child",
                            debugInfo = cancellation.message,
                            completedNormally = false,
                        ),
                    )
                    cleanupRan.complete(cancellation.message ?: "cancelled")
                    throw cancellation
                }
            }
            childStarted.await()
            delay(120)
            val callsBeforeStop = providerCalls
            val writesBeforeStop = transcriptWrites
            assertTrue(
                "the child must be doing work before the stop, otherwise the test proves nothing",
                callsBeforeStop > 0 && writesBeforeStop > 0,
            )

            // ── the production stop path ────────────────────────────────────────
            val receipt = fixture.coordinator.stopDescendant(
                actorSessionId = "parent",
                targetSessionId = "child",
                reason = "Requested by parent agent",
            )
            assertTrue("the tree must accept the stop", receipt.accepted)
            assertEquals(
                RuntimeNodeStatus.STOP_REQUESTED,
                fixture.node()?.status,
            )
            val dispatched = SessionActivityTracker.cancelChildSessions(
                receipt.affectedNodeIds + "child",
            )
            assertEquals(
                "the running child's cancel handle must be found under its session id",
                listOf("child"),
                dispatched,
            )

            // A cancelled coroutine finishes; a merely relabelled one does not, so
            // the join is time-boxed rather than allowed to hang the suite — the
            // counters below carry the verdict either way.
            val joined = runCatching { withTimeout(5_000) { childJob.join() } }

            val callsAtCancellation = providerCalls
            val writesAtCancellation = transcriptWrites
            // Give a run that was only RELABELLED every chance to keep going: this is
            // the assertion the old behaviour fails, and it fails it without looking
            // at a single status field.
            delay(150)
            assertEquals(
                "the child kept working after it was stopped (join=${joined.exceptionOrNull()?.message ?: "returned"}): " +
                    "provider calls $callsAtCancellation -> $providerCalls, " +
                    "transcript writes $writesAtCancellation -> $transcriptWrites",
                callsAtCancellation,
                providerCalls,
            )
            assertEquals(
                "the child kept writing its transcript after it was stopped: " +
                    "$writesAtCancellation -> $transcriptWrites",
                writesAtCancellation,
                transcriptWrites,
            )
            assertTrue("the child coroutine must be cancelled", childJob.isCancelled)
            assertNotNull("the child's own cleanup must have run", cleanupRan.await())

            // ── and the parent is told the child it asked to stop has stopped ──
            val childNode = fixture.node()
            assertEquals(
                "a deliberate stop is not an abnormal interruption",
                RuntimeNodeStatus.ABORTED,
                childNode?.status,
            )
            assertEquals(false, childNode?.abnormal)
            val drained = fixture.coordinator.drainInbox("parent")
            assertEquals(
                "the direct parent must receive exactly one stop notification",
                1,
                drained.size,
            )
            assertEquals("child", drained[0].fromNodeId)
            assertEquals("parent", drained[0].toNodeId)
            assertTrue(
                "the notification must describe the stop: ${drained[0].taskIntent}",
                // [T-android-stop-request-kind] This stop was REQUESTED, so the
                // notification says so instead of borrowing the crash vocabulary: the
                // kind, the card, and the deliberately absent model reminder all follow
                // the ABORTED-vs-ABNORMAL_INTERRUPTION verdict asserted just above.
                JSONObject(drained[0].taskIntent).getString("kind") == "child_stopped_by_request",
            )
            val notices = fixture.surfacing.noticesFor(drained)
            assertEquals(1, notices.size)
            assertTrue(
                "the parent must be able to name the sub-agent that stopped: ${notices[0].cardText}",
                notices[0].cardText.contains("Count the wires"),
            )
        } finally {
            // A red run leaves the child loop spinning by construction; the scope
            // is what keeps that failure a failure instead of a hung test.
            childScope.cancel()
            fixture.dispose()
        }
    }

    @Test
    fun `an unregistered session is not claimed as cancelled`() {
        assertEquals(
            "nothing may be reported as dispatched when no child is running",
            emptyList<String>(),
            SessionActivityTracker.cancelChildSessions(listOf("no-such-session")),
        )
    }

    @Test
    fun `a child stopped on request is not filed as an abnormal interruption`() {
        val fixture = Fixture()
        try {
            fixture.startChildDirectly()

            val receipt = fixture.coordinator.stopDescendant(
                actorSessionId = "parent",
                targetSessionId = "child",
                reason = "Requested by parent agent",
            )
            assertTrue(receipt.accepted)

            // The cancelled child's cleanup: SessionActivityTracker
            // .finishDelegatedChild(failed = true) → RuntimeSessionCoordinator
            // .finishChild(failed = true, report.completedNormally = false), which
            // is the exact call a cancelled RuntimeChildRunner makes.
            fixture.coordinator.finishChild(
                "child",
                failed = true,
                report = RuntimeStopReport(
                    nodeId = "child",
                    debugInfo = "stopped at the request of an ancestor (stop_descendant)",
                    completedNormally = false,
                ),
            )

            val node = fixture.node()
            assertEquals(
                "the user stopped this child; it did not die on its own",
                RuntimeNodeStatus.ABORTED,
                node?.status,
            )
            assertEquals(false, node?.abnormal)
            assertEquals(
                "the stop receipt must carry the outcome it produced",
                RuntimeNodeStatus.ABORTED,
                fixture.store.snapshot().stopReceipt(receipt.operationId)?.stateAfter,
            )
        } finally {
            fixture.dispose()
        }
    }

    @Test
    fun `a child that finishes inside the stop window still notifies its direct parent`() {
        val fixture = Fixture()
        try {
            fixture.names["child"] = "Count the wires"
            fixture.startChildDirectly()

            val receipt = fixture.coordinator.stopDescendant(
                actorSessionId = "parent",
                targetSessionId = "child",
                reason = "Requested by parent agent",
            )
            assertTrue(receipt.accepted)
            assertEquals(RuntimeNodeStatus.STOP_REQUESTED, fixture.node()?.status)

            // The race the fix has to survive: the child declared completion in the
            // instant between the stop request and the cancellation reaching it.
            // `complete` refuses a terminal node — correct — but the refusal used to
            // take the parent notification with it, so the parent waited for a child
            // that was already over.
            fixture.coordinator.finishChild("child", failed = false)

            assertEquals(
                "the stop request is the verdict that stands",
                RuntimeNodeStatus.STOP_REQUESTED,
                fixture.node()?.status,
            )
            val drained = fixture.coordinator.drainInbox("parent")
            assertEquals(
                "the parent must still be told the child settled",
                1,
                drained.size,
            )
            assertEquals("child", drained[0].fromNodeId)
            assertTrue(
                "a child that declared completion is reported as completed: ${drained[0].taskIntent}",
                JSONObject(drained[0].taskIntent).getString("kind") == "child_completed",
            )
            val notices = fixture.surfacing.noticesFor(drained)
            assertTrue(
                "the parent must be able to name the sub-agent that settled: ${notices[0].cardText}",
                notices[0].cardText.contains("Count the wires"),
            )
        } finally {
            fixture.dispose()
        }
    }

    @Test
    fun `an undeclared end with no stop request is still an abnormal interruption`() {
        val fixture = Fixture()
        try {
            fixture.startChildDirectly()

            fixture.coordinator.finishChild(
                "child",
                failed = true,
                report = RuntimeStopReport(nodeId = "child", completedNormally = false),
            )

            val node = fixture.node()
            assertEquals(
                "the deliberate-stop exemption must not swallow an ordinary crash",
                RuntimeNodeStatus.ABNORMAL_INTERRUPTION,
                node?.status,
            )
            assertEquals(true, node?.abnormal)
        } finally {
            fixture.dispose()
        }
    }
}
