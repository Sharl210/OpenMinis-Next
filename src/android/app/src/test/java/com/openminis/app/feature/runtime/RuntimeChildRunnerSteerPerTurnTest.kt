package com.openminis.app.feature.runtime

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-agent-messaging] A delegated child must absorb a message that
 * arrives WHILE it is running.
 *
 * Requirement (request.md:7): 「加入像 DSH 一样的排队发送和 steer 发送…对话进行中默认
 * 点击发送为 steer」. "Steer" only means anything if the instruction reaches the
 * child during the work it is steering — otherwise it is a queued message wearing
 * a different name.
 *
 * ## The defect this file pins
 *
 * `RuntimeChildRunner` drained its inbox ONCE, before `ChildAgentLoop.run`, and
 * then started looping. A message sent after that single drain landed in the
 * child's inbox and sat there until the run ended; since one delegated run is one
 * bounded loop, a child that finished in one round never read it at all. Every
 * other part of the delivery path was correct — `SessionTreeRuntime.send`
 * authorized and enqueued, the three lanes were disjoint and each had its own
 * reader, and claim leases prevented double delivery — the READER was simply in
 * the wrong place.
 *
 * ## What is executed here
 *
 * The exact function the run body calls before every model call, against a real
 * [RuntimeTreeStore], a real root and child, and a real [RuntimeSessionCoordinator].
 * The loop that drives it is the production [ChildAgentLoop] with the production
 * `beforeTurn` hook, so "the message is read before round N" is observed as the
 * message list the model would have been handed, not as a call count.
 *
 * NOT executed: `RuntimeChildRunner.execute` itself (it needs a provider stack —
 * see `RuntimeChildRunnerMessagingSourceTest`'s class comment). The wiring between
 * that body and this function is a single call site; the behaviour is here.
 */
class RuntimeChildRunnerSteerPerTurnTest {

    private class Fixture {
        val dir: File = Files.createTempDirectory("child-steer-per-turn").toFile()
        val store: RuntimeTreeStore = RuntimeTreeStore.openForTest(File(dir, "session-tree.json")) { source, target ->
            Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }
        val coordinator: RuntimeSessionCoordinator = run {
            val constructor = RuntimeSessionCoordinator::class.java
                .getDeclaredConstructor(RuntimeTreeStore::class.java)
            constructor.isAccessible = true
            constructor.newInstance(store)
        }

        init {
            assertTrue(coordinator.startRoot("parent") != null)
            assertTrue(coordinator.startChild("parent", "child"))
        }

        fun dispose() {
            dir.deleteRecursively()
        }
    }

    /** What one drain produced, in the shape the run body uses. */
    private class Drain(
        val messages: List<LLMMessage>,
        val consumed: MutableList<RuntimeEnvelope>,
        val claimedIds: MutableSet<String>,
    ) {
        fun drainAgain(coordinator: RuntimeSessionCoordinator): List<LLMMessage> =
            RuntimeChildRunner.claimPendingRuntimeMessages(
                runtimeCoordinator = coordinator,
                childSessionId = "child",
                alreadyClaimedIds = claimedIds,
                into = consumed,
            )
    }

    private fun Drain(coordinator: RuntimeSessionCoordinator): Drain {
        val consumed = mutableListOf<RuntimeEnvelope>()
        val claimedIds = mutableSetOf<String>()
        val messages = RuntimeChildRunner.claimPendingRuntimeMessages(
            runtimeCoordinator = coordinator,
            childSessionId = "child",
            alreadyClaimedIds = claimedIds,
            into = consumed,
        )
        return Drain(messages, consumed, claimedIds)
    }

    private fun textOf(message: LLMMessage): String =
        message.contentParts.filterIsInstance<AgentContentPart.Text>()
            .joinToString("") { it.text }
            .ifEmpty { message.content ?: "" }

    private fun turn(
        text: String = "",
        calls: List<ChildToolCall> = emptyList(),
    ) = ChildTurnResult(text = text, toolCalls = calls)

    private fun call(name: String, args: JSONObject = JSONObject()) =
        ChildToolCall(id = "c-$name", name = name, args = args)

    // ─── 1. the property: mid-run delivery is read at the next round ──────

    /**
     * The core claim. Round 1 runs; the parent steers DURING it; the drain that
     * precedes round 2 must put that instruction in front of the model.
     *
     * The falsification for this test is the old behaviour: with the claim done
     * once before the loop, the second round's drain does not happen at all and
     * the steering instruction never reaches the model.
     */
    @Test
    fun `a message sent during a round is injected before the next round`() = runBlocking {
        val f = Fixture()
        try {
            val drained = Drain(f.coordinator)
            assertEquals("nothing was sent yet", 0, drained.messages.size)

            val seenByModel = mutableListOf<List<LLMMessage>>()
            val loop = ChildAgentLoop(maxTurns = 3, executeTool = { ChildToolOutcome("ok") })
            var steerSent = false
            val outcome = loop.run(
                seed = listOf(LLMMessage(role = LLMMessage.Role.USER, content = "do the work")),
                beforeTurn = { messages -> messages += drained.drainAgain(f.coordinator) },
            ) { messages ->
                seenByModel += messages.toList()
                if (!steerSent) {
                    // The parent steers WHILE the child is working — i.e. after the
                    // child's run has already begun.
                    steerSent = true
                    assertTrue(
                        f.coordinator.send("parent", "child", "Use the staging bucket.", RuntimeDelivery.STEER)
                            .accepted,
                    )
                    // Keep the child working so there IS a next round to steer into.
                    turn(text = "working", calls = listOf(call("web_search")))
                } else {
                    turn(text = "stopped asking for tools")
                }
            }

            assertTrue("the loop must have run more than one round", outcome.turns >= 2)
            assertTrue("seenByModel must have recorded every round", seenByModel.size >= 2)

            val secondRound = seenByModel[1].map(::textOf).joinToString("\n")
            assertTrue(
                "the mid-run steer must be in front of the model at the next round, got:\n$secondRound",
                secondRound.contains("Use the staging bucket."),
            )
            assertTrue(
                "and it must be labelled with its delivery class, not folded in as a bare turn",
                secondRound.contains("[runtime-message:STEER]"),
            )
            // Round 1 predates the send, so it must NOT contain it: a message can
            // only be read by a round that starts after it arrived.
            assertTrue(
                "a message sent during round 1 cannot retroactively be in round 1",
                !seenByModel[0].map(::textOf).joinToString("\n").contains("Use the staging bucket."),
            )
        } finally {
            f.dispose()
        }
    }

    /**
     * A parent-originated message re-teaches the completion protocol
     * (request.md:9): 「每次主代理给子代理发信息也注入一次」. The injection is what the
     * child's model actually reads, so this asserts the text, not a helper call.
     */
    @Test
    fun `a parent message carries the completion reminder`() = runBlocking {
        val f = Fixture()
        try {
            assertTrue(f.coordinator.send("parent", "child", "from the parent", RuntimeDelivery.STEER).accepted)

            val drained = Drain(f.coordinator)
            val parentText = drained.messages.map(::textOf).joinToString("\n")
            assertTrue(parentText.contains("from the parent"))
            assertTrue(
                "a top-down message must re-inject the completion protocol",
                parentText.contains(ChildCompletionProtocol.TOOL_NAME),
            )
        } finally {
            f.dispose()
        }
    }

    @Test
    fun `team peer traffic is injected without the completion reminder`() {
        val envelope = RuntimeEnvelope(
            id = "e1",
            fromNodeId = "peer",
            toNodeId = "child",
            delivery = RuntimeDelivery.TEAM_PEER,
            payload = "peer note",
            createdAtMillis = 1L,
        )

        val message = RuntimeChildRunner.runtimeMessageFor(envelope, capabilities = null)
        val text = textOf(message)

        assertTrue("the payload must be carried", text.contains("peer note"))
        assertTrue("and labelled as a peer delivery", text.contains("[runtime-message:TEAM_PEER]"))
        assertFalse(
            "peers are siblings, not the child's principal — no protocol re-teaching",
            text.contains(ChildCompletionProtocol.TOOL_NAME),
        )
    }

    // ─── 2. exactly-once, which the claim lease alone cannot give ─────────

    /**
     * The claim lease is the runtime's own de-duplication, but it EXPIRES
     * ([RuntimeSessionTree.MESSAGE_CLAIM_LEASE_MILLIS]). A child that runs longer
     * than the lease would therefore re-claim its own consumed message on a later
     * round — and, because the run body answers everything it consumed, answer it
     * again too.
     *
     * Reproduced honestly rather than by waiting: `reconcile` with a future clock
     * is exactly what a long-running child meets in production when the lease
     * lapses.
     */
    @Test
    fun `a re-claimable message is not handed to the model a second time`() = runBlocking {
        val f = Fixture()
        try {
            assertTrue(f.coordinator.send("parent", "child", "only once", RuntimeDelivery.STEER).accepted)

            val drained = Drain(f.coordinator)
            assertEquals(1, drained.messages.size)
            assertTrue(textOf(drained.messages.first()).contains("only once"))
            assertEquals("the run must remember what it absorbed", 1, drained.consumed.size)

            // Let the claim lease lapse, which is what a long run does to itself.
            f.store.reconcile(
                System.currentTimeMillis() + RuntimeSessionTree.MESSAGE_CLAIM_LEASE_MILLIS + 1_000L,
            )

            val second = drained.drainAgain(f.coordinator)
            assertEquals(
                "the same envelope must not be injected twice, lease or no lease",
                0,
                second.size,
            )
            assertEquals("and must not be queued for a second reply", 1, drained.consumed.size)
        } finally {
            f.dispose()
        }
    }

    /**
     * The reader order is part of the contract (one lane per reader), and a drain
     * must never take more than one message per lane: the run body's reply loop
     * answers every envelope it consumed, so a drain that swept a whole lane would
     * silently answer messages the child never read.
     */
    @Test
    fun `each lane is read by its own reader and one message per drain`() = runBlocking {
        val f = Fixture()
        try {
            assertTrue(f.coordinator.send("parent", "child", "steer-1", RuntimeDelivery.STEER).accepted)
            assertTrue(f.coordinator.send("parent", "child", "turn-1", RuntimeDelivery.QUEUE).accepted)
            assertTrue(f.coordinator.send("parent", "child", "notice-1", RuntimeDelivery.NOTIFY).accepted)

            val drained = Drain(f.coordinator)
            val text = drained.messages.map(::textOf).joinToString("\n")

            assertTrue(text.contains("[runtime-message:NOTIFY] notice-1"))
            assertTrue(text.contains("[runtime-message:STEER] steer-1"))
            assertTrue(text.contains("[runtime-message:QUEUE] turn-1"))
            assertEquals(3, drained.messages.size)
        } finally {
            f.dispose()
        }
    }

    /**
     * A sender with no delivery edge must not be readable either: refusing the
     * send is only half the property, and the half that matters is that nothing
     * appears in the child's lanes.
     */
    @Test
    fun `an envelope from a node the child may not answer is never claimed`() = runBlocking {
        val f = Fixture()
        try {
            assertTrue(f.coordinator.startRoot("unrelated") != null)
            val refused = f.coordinator.send("unrelated", "child", "should not arrive", RuntimeDelivery.QUEUE)
            assertTrue("a cross-root send must be refused", !refused.accepted)

            val drained = Drain(f.coordinator)
            assertEquals(0, drained.messages.size)
        } finally {
            f.dispose()
        }
    }

    // ─── 3. the loop hook itself ─────────────────────────────────────────

    /**
     * The hook has to run before EVERY model call, including the first — a message
     * that arrived before the run started must not wait a round for the hook to
     * fire for the first time.
     */
    @Test
    fun `the before-turn hook runs before every model call including the first`() = runBlocking {
        var hooks = 0
        var calls = 0
        val loop = ChildAgentLoop(maxTurns = 3, executeTool = { ChildToolOutcome("ok") })

        val outcome = loop.run(
            seed = emptyList(),
            beforeTurn = { hooks++ },
        ) { messages ->
            calls++
            assertNotNull(messages)
            if (calls < 3) turn(text = "working", calls = listOf(call("web_search")))
            else turn(text = "done")
        }

        assertEquals(3, calls)
        assertEquals("one hook per model call", calls, hooks)
        assertEquals(ChildEndReason.NO_TOOL_CALL, outcome.endReason)
    }

    /** With no hook supplied the loop keeps its previous behaviour exactly. */
    @Test
    fun `the hook is optional so existing callers are unaffected`() = runBlocking {
        var calls = 0
        val loop = ChildAgentLoop(executeTool = { ChildToolOutcome("ok") })

        val outcome = loop.run(seed = emptyList()) {
            calls++
            turn(text = "answer")
        }

        assertEquals(1, calls)
        assertEquals(ChildEndReason.NO_TOOL_CALL, outcome.endReason)
    }
}
