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
 * [T-android-parent-inbox-consumer] The parent half of the runtime mailbox — the
 * half that had no reader.
 *
 * `request.md:248` asks that when a sub-agent stops, the runtime
 * 「自发向它上一级的那一个，就是它的对应的直系上级发送一条系统消息」. The runtime
 * does exactly that (`SessionTreeRuntime.notifyParentOnStop`), and `request.md:109`
 * / `:111` ask for a central mailbox (「邮局/短信中心」). Both existed. What did not
 * exist was anything on the PARENT's side that opened the mailbox: the only three
 * readers in `main/` (`claimNextNotification` / `claimNextStep` / `claimNextTurn`)
 * were called exclusively by `RuntimeChildRunner` with a CHILD session id. So four
 * notification kinds had a producer and no consumer.
 *
 * ## What is executed here (A) — the whole chain except the ViewModel's wiring
 *
 * Every test below drives production code against a REAL `RuntimeTreeStore` on a
 * real temp directory and a REAL `RuntimeSessionCoordinator`: create the root,
 * create the child, stop the child through `finishChild` (the call
 * `SessionActivityTracker.finishDelegatedChild` makes from
 * `RuntimeChildRunner`'s success/failure callbacks), drain as the parent, and
 * render. The child's identity in the rendered sentence is asserted, so
 * "the user is told which sub-agent stopped" is a fact this file checks rather
 * than a claim it repeats.
 *
 * ## What is NOT executed, and is pinned instead (C)
 *
 * `ChatViewModel` cannot be constructed in this module: no Robolectric, no
 * compose-ui-test, and its constructor needs Room, the provider repository and
 * `Dispatchers.Main`. The three call sites of `surfaceRuntimeInbox()` and the
 * `appendSystemInfo` call that renders the card are therefore asserted against the
 * file's text, each labelled `(C)` — the same split, for the same reason,
 * `ChatViewModelChildAbnormalEndReminderSourceTest` documents for its own subject.
 * The behaviour those call sites invoke is what the (A) tests execute.
 */
class RuntimeParentInboxDrainTest {

    /**
     * A `RuntimeTreeStore` over a real file plus the coordinator that drives it.
     *
     * Built twice per cold-start test, on purpose: the second build is what makes
     * "the notification is on disk" a fact rather than an in-memory artefact.
     */
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
        val dir: File = Files.createTempDirectory("runtime-parent-inbox").toFile()
        private val cold = ColdStart(dir)
        val store: RuntimeTreeStore = cold.store
        val coordinator: RuntimeSessionCoordinator = cold.coordinator

        /** Display names the renderer reads; stands in for the child's session title. */
        val names = mutableMapOf<String, String>()
        val surfacing = RuntimeInboxSurfacing { nodeId -> names[nodeId] }

        /**
         * A process that died and came back: a NEW store and a NEW coordinator over
         * the SAME file, so `activeRuntimeIds` is empty exactly as it is after a
         * cold start.
         */
        fun coldStart(): ColdStart = ColdStart(dir)

        init {
            assertTrue(coordinator.startRoot("parent") != null)
            assertTrue(coordinator.startChild("parent", "child"))
        }

        /**
         * The production stop path for a delegated child that never declared
         * completion: `RuntimeChildRunner.onFailure` → `SessionActivityTracker`
         * .finishDelegatedChild(failed = true) → `finishChild(failed = true)`, which
         * aborts the node and lets `maybeNotifyWhenSettled` notify the direct parent.
         */
        fun stopChildAbnormally(reason: String = "Delegated child ended without calling 'complete_child_task'") {
            coordinator.finishChild(
                "child",
                failed = true,
                report = RuntimeStopReport(
                    nodeId = "child",
                    debugInfo = reason,
                    completedNormally = false,
                ),
            )
        }

        fun dispose() {
            dir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------ (A)

    @Test
    fun `a stopped child's notification reaches the parent's own drain and names that child`() {
        val f = Fixture()
        try {
            f.names["child"] = "Parse the server logs"
            f.stopChildAbnormally()

            val drained = f.coordinator.drainInbox("parent")
            assertEquals("the parent must receive exactly the child's stop notification", 1, drained.size)
            assertEquals(RuntimeDelivery.NOTIFY, drained[0].delivery)
            assertEquals("child", drained[0].fromNodeId)
            assertEquals("parent", drained[0].toNodeId)
            assertEquals(
                "child_abnormal_stop",
                JSONObject(drained[0].taskIntent).getString("kind"),
            )

            val notices = f.surfacing.noticesFor(drained)
            assertEquals(1, notices.size)
            val text = notices[0].cardText
            assertTrue("the card must name the sub-agent that stopped: $text", text.contains("Parse the server logs"))
            assertTrue("the card must say it stopped: $text", text.contains("stopped abnormally"))
            assertFalse("raw JSON must never reach the user: $text", text.contains("{"))
            assertFalse("taskIntent field names must never reach the user: $text", text.contains("childNodeId"))
            // An abnormal stop is the ONE notice that also reaches the model, and the
            // reason is not redundancy with publishDelegatedChildOutcome: that
            // reminder reports only the child's own end_reason, while the HTTP
            // diagnostics (status code, error response, headers, body tail) are
            // collected by the provider, threaded through the stop report, and would
            // otherwise be shown to nobody — the card is ellipsised by design and
            // cannot carry an error body. See abnormalStopReminder.
            val reminder = notices[0].modelReminder
            assertTrue("an abnormal stop must reach the parent agent", reminder != null)
            assertTrue("and it must carry the folded reason: $reminder", reminder!!.contains("complete_child_task"))
            assertTrue(reminder.startsWith("<system-reminder>"))
            assertTrue(reminder.endsWith("</system-reminder>"))
        } finally {
            f.dispose()
        }
    }

    @Test
    fun `the parent is told a child finished, separately from one that stopped`() {
        val f = Fixture()
        try {
            f.names["child"] = "Fetch the API spec"
            // The declared-completion path: RuntimeChildRunner.onSuccess with
            // completedNaturally == true.
            f.coordinator.finishChild("child")

            val notices = f.surfacing.noticesFor(f.coordinator.drainInbox("parent"))
            assertEquals(1, notices.size)
            val text = notices[0].cardText
            assertTrue("a clean finish must read as a completion: $text", text.contains("finished"))
            assertTrue(text.contains("Fetch the API spec"))
        } finally {
            f.dispose()
        }
    }

    @Test
    fun `a child with no display name is still identifiable by its node id`() {
        val f = Fixture()
        try {
            f.stopChildAbnormally()

            val notices = f.surfacing.noticesFor(f.coordinator.drainInbox("parent"))
            assertEquals(1, notices.size)
            assertEquals(
                "an unnamed child must render as an identified one, never as a bare blank",
                "Sub-agent \"child\" stopped abnormally.",
                notices[0].cardText,
            )
        } finally {
            f.dispose()
        }
    }

    @Test
    fun `every card fits the single line the neutral system row can actually draw`() {
        // FallbackInfoBlock — the row appendSystemInfo's "injection" cards render
        // through — draws its label with maxLines = 1 and TextOverflow.Ellipsis.
        // A longer sentence is not merely "longer": its tail is dropped before the
        // user ever sees it, so the budget has to hold for every kind this class
        // phrases, not just the ones a happy-path test happens to build.
        val longName = (1..20).joinToString(" ") { "word$it" }
        val cases = listOf(
            RuntimeEnvelope(
                id = "c1", fromNodeId = "child", toNodeId = "parent",
                delivery = RuntimeDelivery.NOTIFY, payload = "Child child completed.", createdAtMillis = 1L,
                taskIntent = JSONObject().put("kind", "child_completed").put("childNodeId", longName).toString(),
            ),
            RuntimeEnvelope(
                id = "c2", fromNodeId = "child", toNodeId = "parent",
                delivery = RuntimeDelivery.NOTIFY, payload = "Child child stopped abnormally.", createdAtMillis = 1L,
                taskIntent = JSONObject().put("kind", "child_abnormal_stop").put("childNodeId", longName).toString(),
            ),
            RuntimeEnvelope(
                id = "c3", fromNodeId = "child", toNodeId = "parent",
                delivery = RuntimeDelivery.NOTIFY, payload = "Subtree child deleted.", createdAtMillis = 1L,
                taskIntent = JSONObject()
                    .put("kind", "child_subtree_deleted")
                    .put("targetNodeId", longName)
                    .put("affectedNodeIds", org.json.JSONArray(listOf("a", "b", "c"))).toString(),
            ),
            RuntimeEnvelope(
                id = "c4", fromNodeId = "parent", toNodeId = "parent",
                delivery = RuntimeDelivery.NOTIFY, payload = "Subtree deletion completed.", createdAtMillis = 1L,
                taskIntent = JSONObject()
                    .put("kind", "subtree_delete_result")
                    .put("targetNodeId", longName)
                    .put("affectedNodeIds", org.json.JSONArray(listOf("a", "b", "c"))).toString(),
            ),
        )
        // Every node id resolves to this over-long title, so the clipping rule —
        // not the id fallback — is what these four cards exercise.
        val notices = RuntimeInboxSurfacing { longName }.noticesFor(cases)
        assertEquals(cases.size, notices.size)
        for (notice in notices) {
            assertTrue(
                "a card longer than ${RuntimeInboxSurfacing.MAX_CARD_CHARS} chars would be ellipsised: " +
                    "'${notice.cardText}'",
                notice.cardText.length <= RuntimeInboxSurfacing.MAX_CARD_CHARS,
            )
        }
        assertTrue(
            "the identifying clause and the event must both survive: ${notices[0].cardText}",
            notices[0].cardText.startsWith("Sub-agent \"word1 word2 ") &&
                notices[0].cardText.endsWith("finished."),
        )
        assertTrue(
            "a long title must be clipped, not passed through whole: ${notices[0].cardText}",
            notices[0].cardText.contains("…"),
        )
    }

    @Test
    fun `a single drain takes every queued notification, and the next one takes nothing`() {
        val f = Fixture()
        try {
            assertTrue(f.coordinator.startChild("parent", "child-2"))
            f.names["child"] = "One"
            f.names["child-2"] = "Two"
            f.coordinator.finishChild("child")
            f.coordinator.finishChild("child-2")

            // One reader call per lane would have returned ONE of these and left the
            // other for a drain that may never come; that is exactly what
            // claimNextNotification cannot do for this caller.
            val drained = f.coordinator.drainInbox("parent")
            assertEquals("both notifications must come out of one drain", 2, drained.size)
            assertEquals(setOf("child", "child-2"), drained.map { it.fromNodeId }.toSet())
            assertTrue("everything queued must be drained", f.coordinator.drainInbox("parent").isEmpty())

            val text = f.surfacing.noticesFor(drained).joinToString("\n") { it.cardText }
            assertTrue(text.contains("One"))
            assertTrue(text.contains("Two"))
        } finally {
            f.dispose()
        }
    }

    @Test
    fun `the claim lease expires but the renderer still shows a notification once`() {
        val f = Fixture()
        try {
            f.names["child"] = "Parse the server logs"
            f.stopChildAbnormally()

            val first = f.coordinator.drainInbox("parent")
            assertEquals(1, first.size)
            assertEquals(1, f.surfacing.noticesFor(first).size)

            // Past MESSAGE_CLAIM_LEASE_MILLIS the runtime requeues the claim — it
            // cannot tell "already rendered" from "claimed by a process that died".
            f.store.reconcile(System.currentTimeMillis() + RuntimeSessionTree.MESSAGE_CLAIM_LEASE_MILLIS + 1_000L)

            val second = f.coordinator.drainInbox("parent")
            assertEquals("the lease really does hand the same envelope back", 1, second.size)
            assertEquals(first[0].id, second[0].id)
            assertTrue(
                "and the renderer must NOT surface it a second time",
                f.surfacing.noticesFor(second).isEmpty(),
            )
            assertTrue(f.surfacing.hasSurfaced(first[0].id))
        } finally {
            f.dispose()
        }
    }

    @Test
    fun `a subtree deletion is reported from the structured record, not from the model-facing payload`() {
        val f = Fixture()
        try {
            f.names["child"] = "Parse the server logs"
            f.stopChildAbnormally()
            f.coordinator.drainInbox("parent")

            // Agent-authorized delete: the parent is both initiator and executor.
            val receipt = f.coordinator.deleteSubtree(
                initiatorSessionId = "parent",
                executorSessionId = "parent",
                targetSessionId = "child",
            )
            assertEquals(DeleteSubtreeResult.DELETED, receipt.result)

            val drained = f.coordinator.drainInbox("parent")
            val kinds = drained.map { JSONObject(it.taskIntent).getString("kind") }.toSet()
            assertEquals(
                "the deleted node's ex-parent and the executor are both in this mailbox",
                setOf("child_subtree_deleted", "subtree_delete_result"),
                kinds,
            )

            val texts = f.surfacing.noticesFor(drained).map { it.cardText }
            assertTrue(
                "the deletion must name the deleted sub-agent, not just its raw id: $texts",
                texts.any { it.contains("Parse the server logs") },
            )
            assertFalse(
                "the model-facing payload ('Subtree child deleted.') must not be what the user reads: $texts",
                texts.any { it == "Subtree child deleted." },
            )
        } finally {
            f.dispose()
        }
    }

    @Test
    fun `a notification written before the process died is drained after a cold start`() {
        val f = Fixture()
        try {
            f.names["child"] = "Parse the server logs"
            f.stopChildAbnormally()

            // The process dies here: no dispose, no in-memory hand-off. Everything
            // the next block can see is what reached the file.
            val reborn = f.coldStart()

            // No startRoot call first — this is the loadSession shape, where the
            // conversation is merely reopened. activeRuntimeIds is empty, so the
            // root is found through the persisted tree instead.
            val drained = reborn.coordinator.drainInbox("parent")
            assertEquals(
                "a notification that survived the process must be drainable after it",
                1,
                drained.size,
            )
            assertEquals("child", drained[0].fromNodeId)

            val notices = RuntimeInboxSurfacing { f.names[it] }.noticesFor(drained)
            assertEquals(1, notices.size)
            assertTrue(
                "and it must still name the sub-agent: ${notices[0].cardText}",
                notices[0].cardText.contains("Parse the server logs"),
            )
        } finally {
            f.dispose()
        }
    }

    @Test
    fun `a notification addressed to a re-run root is still drained after a cold start`() {
        val f = Fixture()
        try {
            f.names["child-2"] = "Parse the server logs"
            // A second run of the same conversation: startRoot mints
            // "<sessionId>#run-N" once the previous root is terminal, so a child
            // created under it notifies THAT node id, not the bare session id.
            f.coordinator.finishRoot("parent")
            val rerunRoot = f.coordinator.startRoot("parent")
            assertEquals(
                "the second run must be a distinct node id, or this test proves nothing",
                "parent#run-1",
                rerunRoot,
            )
            assertTrue(f.coordinator.startChild("parent", "child-2"))
            f.coordinator.finishChild("child-2")

            val reborn = f.coldStart()

            // The cold start is what makes this the ONLY test that can tell the two
            // id-resolution sources apart, and it is deliberate: while the process
            // is alive `activeRuntimeIds["parent"]` alone already yields
            // "parent#run-1", so a warm drain passes whether or not the run-scoped
            // scan exists. After a restart that map is empty, and the bare session
            // id matches no node at all — so if the run-scoped ids stop being
            // considered, this drain returns nothing. (Verified by falsification:
            // disabling that scan turns this test red and leaves the warm ones
            // green.)
            val drained = reborn.coordinator.drainInbox("parent")
            assertEquals(1, drained.size)
            assertEquals(
                "the envelope is addressed to the re-run root",
                "parent#run-1",
                drained[0].toNodeId,
            )
            val notices = RuntimeInboxSurfacing { f.names[it] }.noticesFor(drained)
            assertEquals(1, notices.size)
            assertTrue(notices[0].cardText.contains("Parse the server logs"))
        } finally {
            f.dispose()
        }
    }

    @Test
    fun `notifications from an earlier run are still drained after the root was re-run`() {
        val f = Fixture()
        try {
            // Run 0's child stops while the first root is live, and nobody drains.
            f.names["child"] = "Parse the server logs"
            f.stopChildAbnormally()

            // Run 1 begins. The bare "parent" node is now history; the live root is
            // "parent#run-1". Its envelope was addressed to the OLD node.
            f.coordinator.finishRoot("parent")
            val rerunRoot = f.coordinator.startRoot("parent")
            assertEquals("parent#run-1", rerunRoot)

            // The old node and the new one coexist, so a drain that follows only the
            // LIVE root would strand what run 0's children wrote — the same defect
            // this feature fixes, one generation back.
            val drained = f.coordinator.drainInbox("parent")
            assertEquals(
                "the earlier run's notification must not be stranded by the re-run",
                1,
                drained.size,
            )
            assertEquals("parent", drained[0].toNodeId)
            val notices = RuntimeInboxSurfacing { f.names[it] }.noticesFor(drained)
            assertEquals(1, notices.size)
            assertTrue(
                "and it must still name the sub-agent: ${notices[0].cardText}",
                notices[0].cardText.contains("Parse the server logs"),
            )
        } finally {
            f.dispose()
        }
    }

    @Test
    fun `ordinary notices stay cards and never reach the model`() {
        val f = Fixture()
        try {
            f.names["child"] = "Fetch the API spec"
            // A clean finish. `publishDelegatedChildOutcome` already puts the child's
            // own result in front of the parent, so a reminder here would be the same
            // event reported twice — precisely the noise the card-only rule avoids.
            f.coordinator.finishChild("child")
            val completed = f.surfacing.noticesFor(f.coordinator.drainInbox("parent"))
            assertEquals(1, completed.size)
            assertNull(
                "a normal completion must not inject: ${completed[0].cardText}",
                completed[0].modelReminder,
            )

            // A subtree deletion is likewise a notice about the tree, not traffic
            // addressed to this agent.
            val receipt = f.coordinator.deleteSubtree(
                initiatorSessionId = "parent",
                executorSessionId = "parent",
                targetSessionId = "child",
            )
            assertEquals(DeleteSubtreeResult.DELETED, receipt.result)
            val deletions = f.surfacing.noticesFor(f.coordinator.drainInbox("parent"))
            assertEquals(2, deletions.size)
            for (notice in deletions) {
                assertNull(
                    "a deletion notice must not inject: ${notice.cardText}",
                    notice.modelReminder,
                )
            }
        } finally {
            f.dispose()
        }
    }

    @Test
    fun `a queued message addressed to the agent is surfaced and injected, a notice is not`() {
        val f = Fixture()
        try {
            f.names["child"] = "Parse the server logs"
            // The only authorizable way to put non-NOTIFY traffic in a parent's
            // inbox today: SEND runs subscriber -> publisher, and nothing in
            // production calls addSubscription yet (see the class doc).
            assertTrue(
                f.store.update {
                    addSubscription("child", "parent", setOf(RuntimeEdgePermission.SEND)).accepted
                },
            )
            assertTrue(f.coordinator.send("child", "parent", "please wrap up and report", RuntimeDelivery.QUEUE).accepted)

            val drained = f.coordinator.drainInbox("parent")
            assertEquals(1, drained.size)
            assertEquals(RuntimeDelivery.QUEUE, drained[0].delivery)

            val notices = f.surfacing.noticesFor(drained)
            assertEquals(1, notices.size)
            val reminder = notices[0].modelReminder
            assertTrue(
                "a message addressed to the agent MUST reach it — the drain is the only claimant",
                reminder != null,
            )
            assertTrue("the reminder must carry the message: ${reminder!!}", reminder.contains("please wrap up and report"))
            assertTrue("the reminder must name the sender: $reminder", reminder.contains("Parse the server logs"))
            assertTrue(reminder.startsWith("<system-reminder>"))
            assertTrue(reminder.endsWith("</system-reminder>"))
        } finally {
            f.dispose()
        }
    }

    @Test
    fun `an unknown notification kind is still surfaced instead of dropped`() {
        val envelope = RuntimeEnvelope(
            id = "future-kind",
            fromNodeId = "child",
            toNodeId = "parent",
            delivery = RuntimeDelivery.NOTIFY,
            payload = "Child did something new.",
            createdAtMillis = 1L,
            taskIntent = JSONObject().put("kind", "child_something_new").toString(),
        )
        val notices = RuntimeInboxSurfacing().noticesFor(listOf(envelope))
        assertEquals(1, notices.size)
        assertEquals("Child did something new.", notices[0].cardText)
    }

    // ------------------------------------------------------------------ (C)

    /**
     * (C) The three call sites and the card render, asserted on `ChatViewModel.kt`.
     *
     * Not convertible to a behavioural test in this module — see the class doc.
     * Each assertion pins a distinct way the chain can be broken while every (A)
     * test above still passes.
     */
    @Test
    fun `ChatViewModel drains the parent mailbox and renders a neutral card at every required moment`() {
        val text = sequenceOf(
            File("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt"),
            File("app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt"),
        ).first { it.isFile }.readText()

        assertTrue(
            "the parent side must actually call the new drain",
            text.contains("runtimeCoordinator.drainInbox(session)"),
        )
        assertTrue(
            "the card must be the neutral system row, not a bubble",
            text.contains("appendSystemInfo(notice.cardText, \"injection\", stableId = notice.envelopeId)"),
        )
        assertTrue(
            "several cards in one millisecond must stay individually addressable",
            text.contains("val rowId = if (stableId != null) \"sysinfo_\$stableId\" else"),
        )
        assertTrue(
            "a message addressed to the agent must be injected, or the claim would lose it",
            text.contains("appendRuntimeMailboxReminder(session, reminder)"),
        )
        val callSites = Regex("^\\s*surfaceRuntimeInbox\\(\\)$", RegexOption.MULTILINE).findAll(text).count()
        assertEquals(
            "session load, run end and the delegation outcome are all required moments",
            3,
            callSites,
        )
    }
}
