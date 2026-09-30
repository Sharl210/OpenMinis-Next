package com.openminis.app.feature.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-stop-request-kind] A sub-agent that stopped because it was ASKED to
 * must not be announced as one that stopped abnormally.
 *
 * The node status side of this was already right: `RuntimeSessionCoordinator
 * .finishChild` asks the tree whether a stop was requested and files a
 * deliberate stop as ABORTED rather than ABNORMAL_INTERRUPTION. What stayed
 * wrong was everything the parent actually reads — the notification's `kind`,
 * the card's sentence, and the `<system-reminder>` injected into the parent
 * agent. All three were derived from a single bit (`completedNormally = false`)
 * that a requested stop and a crash share, so pressing 「停止」 produced a card
 * saying "stopped abnormally" and a reminder telling the parent to decide
 * whether to restart a sub-agent the user had just stopped on purpose.
 *
 * ## What this file executes
 *
 * The whole production chain, over a real `RuntimeTreeStore` on a real temp
 * directory and a real `RuntimeSessionCoordinator`: create the root, create the
 * child, stop it through `RuntimeSessionCoordinator.stopDescendant` (the path
 * `AgentToolExecutor.restartDescendant`'s sibling `stop_descendant` tool takes),
 * let the now-cancelled run unwind through `finishChild` (what
 * `SessionActivityTracker.finishDelegatedChild` calls from the runner's
 * cancellation callback), drain as the parent, and render.
 *
 * ## What makes these tests able to tell the two cases apart
 *
 * Every assertion is made TWICE on the same fixture, once per cause, and the
 * two runs differ only in whether a stop was requested first:
 *
 *  - [requested stop] `stopDescendant` → STOP_REQUESTED → unwind → `finishChild`;
 *  - [unrequested stop] the same unwind with no stop request — the lease-expiry /
 *    crash shape, which is what the abnormal branch is for.
 *
 * A test that only built the requested case would still pass if the code kept
 * reporting "abnormal" for everything (that is the bug); a test that only built
 * the crash case would pass if the code stopped reporting "abnormal" at all.
 * Asserting both on the same driver is what pins the DISCRIMINATION rather than
 * either verdict on its own.
 */
class RuntimeStoppedByRequestNotificationTest {

    /** A store over a real file plus the coordinator that drives it. */
    private class ColdStart(val dir: File) {
        val store: RuntimeTreeStore = RuntimeTreeStore.openForTest(File(dir, "session-tree.json")) { source, target ->
            Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }
        val coordinator: RuntimeSessionCoordinator = run {
            val constructor = RuntimeSessionCoordinator::class.java
                .getDeclaredConstructor(RuntimeTreeStore::class.java)
            constructor.isAccessible = true
            constructor.newInstance(store)
        }
    }

    private class Fixture {
        val dir: File = Files.createTempDirectory("runtime-stop-request-kind").toFile()
        private val cold = ColdStart(dir)
        val store: RuntimeTreeStore = cold.store
        val coordinator: RuntimeSessionCoordinator = cold.coordinator

        /** Display names the renderer reads; stands in for the child's session title. */
        val names = mutableMapOf<String, String>()
        val surfacing = RuntimeInboxSurfacing { nodeId -> names[nodeId] }

        init {
            assertTrue(coordinator.startRoot("parent") != null)
            assertTrue(coordinator.startChild("parent", "child"))
        }

        fun node(): RuntimeSessionNode? = store.snapshot().node("child")

        /**
         * The production stop path for a stop somebody asked for: the
         * `stop_descendant` tool / `SessionActivityTracker.cancelChildSessions`
         * pair, which marks the node STOP_REQUESTED and cancels the run.
         */
        fun requestStop() {
            val receipt = coordinator.stopDescendant(
                actorSessionId = "parent",
                targetSessionId = "child",
                reason = "Requested by the user",
            )
            assertTrue("the tree must accept the stop, else this fixture proves nothing", receipt.accepted)
            assertEquals(
                "the stop must be marked before the run unwinds",
                RuntimeNodeStatus.STOP_REQUESTED,
                node()?.status,
            )
        }

        /**
         * The production unwind: a cancelled or failed child reaches
         * `finishChild(failed = true, report = …)` from `RuntimeChildRunner`'s
         * failure callback. A `CancellationException` carries no `LLMError`, so a
         * user-initiated stop reports no diagnostics — which is why the crash case
         * below passes a real status code and this one does not.
         */
        fun unwind(report: RuntimeStopReport = RuntimeStopReport(nodeId = "child", completedNormally = false)) {
            coordinator.finishChild("child", failed = true, report = report)
        }

        fun notices(): List<RuntimeInboxSurfacing.Notice> =
            surfacing.noticesFor(coordinator.drainInbox("parent"))

        fun dispose() {
            dir.deleteRecursively()
        }
    }

    // ------------------------------------------------- the requested stop

    @Test
    fun `a child stopped on request is reported as a stop on request, not as an abnormal stop`() {
        val f = Fixture()
        try {
            f.names["child"] = "Count the wires"
            f.requestStop()
            f.unwind()

            // The node half, kept here so a regression in it cannot hide behind the
            // message half (and vice versa): a deliberate stop is ABORTED.
            val node = f.node()
            assertEquals("a deliberate stop is not an abnormal interruption", RuntimeNodeStatus.ABORTED, node?.status)
            assertEquals(false, node?.abnormal)

            val drained = f.coordinator.drainInbox("parent")
            assertEquals("the direct parent must still be told, exactly once", 1, drained.size)

            val kind = JSONObject(drained[0].taskIntent).getString("kind")
            assertEquals(
                "the parent must be able to tell an obeyed stop from a crash",
                "child_stopped_by_request",
                kind,
            )
            assertFalse(
                "the machine-readable record must not call a requested stop abnormal: ${drained[0].taskIntent}",
                drained[0].taskIntent.contains("abnormal"),
            )
            val debugInfo = JSONObject(drained[0].taskIntent).optString("debugInfo")
            assertTrue(
                "the recorded reason must name the cause that actually applied: $debugInfo",
                debugInfo.contains("delegated child stopped on request"),
            )
            assertFalse(
                "and neither may the sentence a reader falls back to: ${drained[0].payload}",
                drained[0].payload.contains("abnormally"),
            )

            val notices = f.surfacing.noticesFor(drained)
            assertEquals(1, notices.size)
            val card = notices[0].cardText
            assertTrue("the card must name the sub-agent: $card", card.contains("Count the wires"))
            assertTrue("the card must say it stopped: $card", card.contains("stopped"))
            assertFalse("a requested stop is not an abnormal one: $card", card.contains("abnormally"))
            assertFalse("raw JSON must never reach the user: $card", card.contains("{"))
            assertTrue(
                "the card must fit the single ellipsised line it is drawn on: ${card.length} chars",
                card.length <= RuntimeInboxSurfacing.MAX_CARD_CHARS,
            )
            assertNull(
                "nobody may be asked to decide whether to restart a sub-agent " +
                    "somebody deliberately stopped, got: ${notices[0].modelReminder}",
                notices[0].modelReminder,
            )
        } finally {
            f.dispose()
        }
    }

    @Test
    fun `a stop request that lost the race to the child's own completion is never a crash`() {
        // The completion path races a stop request: the child declares completion in
        // the instant between "stop requested" and the cancellation landing, so
        // `complete()` runs on a node the tree refuses to rewrite (STOP_REQUESTED is
        // terminal). That branch is pre-existing and unchanged here; what this test
        // pins is only the invariant this fix owns — whatever that path reports, it
        // must not be "it crashed" and it must not invite a restart of a sub-agent
        // that was stopped on purpose.
        val f = Fixture()
        try {
            f.names["child"] = "Count the wires"
            f.requestStop()
            f.coordinator.finishChild("child")

            val notices = f.notices()
            assertEquals(1, notices.size)
            assertFalse(
                "a stop that was requested must never be announced as a crash: ${notices[0].cardText}",
                notices[0].cardText.contains("abnormally"),
            )
            assertNull(
                "and it must not invite a restart: ${notices[0].modelReminder}",
                notices[0].modelReminder,
            )
        } finally {
            f.dispose()
        }
    }

    // ----------------------------------------------- the unrequested stop

    @Test
    fun `a child that died on its own is still reported as an abnormal stop with its diagnostics`() {
        // The negative control. Without it, "never say abnormally" would pass the
        // test above while silently deleting the one notification that is supposed
        // to make a crash visible — the failure mode the abnormal branch exists for.
        val f = Fixture()
        try {
            f.names["child"] = "Count the wires"
            // No `requestStop()`: this is the lease-expiry / crash / undeclared-end
            // shape, which never leaves a stop request behind.
            f.unwind(
                RuntimeStopReport(
                    nodeId = "child",
                    statusCode = 503,
                    errorResponse = "upstream unavailable",
                    responseHeaders = mapOf("x-request-id" to "r1"),
                    debugInfo = "provider error",
                    completedNormally = false,
                ),
            )

            val node = f.node()
            assertEquals(RuntimeNodeStatus.ABNORMAL_INTERRUPTION, node?.status)
            assertEquals(true, node?.abnormal)

            val drained = f.coordinator.drainInbox("parent")
            assertEquals(1, drained.size)
            assertEquals(
                "a stop nobody asked for must keep its own kind",
                "child_abnormal_stop",
                JSONObject(drained[0].taskIntent).getString("kind"),
            )
            assertTrue(
                "and must keep the reason phrase that matches it: ${drained[0].taskIntent}",
                drained[0].taskIntent.contains("delegated child stopped abnormally"),
            )

            val notices = f.surfacing.noticesFor(drained)
            assertEquals(1, notices.size)
            val card = notices[0].cardText
            assertTrue("the card must still say it stopped abnormally: $card", card.contains("stopped abnormally"))
            assertTrue("and must still name the failing status: $card", card.contains("503"))
            val reminder = notices[0].modelReminder
            assertTrue("an abnormal stop must still reach the parent agent: $reminder", reminder != null)
            assertTrue(
                "and must still ask it to decide what to do: $reminder",
                reminder!!.contains("Decide whether to restart"),
            )
            assertTrue("the diagnostic must ride along: $reminder", reminder.contains("upstream unavailable"))
            assertTrue("the headers must ride along: $reminder", reminder.contains("x-request-id: r1"))
        } finally {
            f.dispose()
        }
    }

    @Test
    fun `the same child stopped twice for the two reasons is reported with two kinds`() {
        // One driver, two causes, one run — this is the assertion that cannot pass
        // by accident: the SAME node, the SAME message pipeline and the SAME card
        // renderer produce two different kinds purely because of whether a stop was
        // requested.
        val kinds = mutableListOf<String>()

        val requested = Fixture()
        try {
            requested.names["child"] = "Count the wires"
            requested.requestStop()
            requested.unwind()
            kinds += JSONObject(requested.coordinator.drainInbox("parent")[0].taskIntent).getString("kind")
        } finally {
            requested.dispose()
        }

        val crashed = Fixture()
        try {
            crashed.names["child"] = "Count the wires"
            crashed.unwind()
            kinds += JSONObject(crashed.coordinator.drainInbox("parent")[0].taskIntent).getString("kind")
        } finally {
            crashed.dispose()
        }

        assertEquals(
            "a requested stop and a crash must not share one kind: $kinds",
            listOf("child_stopped_by_request", "child_abnormal_stop"),
            kinds,
        )
    }
}
