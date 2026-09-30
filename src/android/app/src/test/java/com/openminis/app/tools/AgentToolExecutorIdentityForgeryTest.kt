package com.openminis.app.tools

import android.content.Context
import android.content.ContextWrapper
import com.openminis.app.feature.runtime.DeleteSubtreeResult
import com.openminis.app.feature.runtime.RuntimeCommunicationFileStore
import com.openminis.app.feature.runtime.RuntimeCommunicationRepository
import com.openminis.app.feature.runtime.RuntimeDelegationParser
import com.openminis.app.feature.runtime.RuntimeDelegationRequest
import com.openminis.app.feature.runtime.RuntimeDelivery
import com.openminis.app.feature.runtime.RuntimeModelSnapshot
import com.openminis.app.feature.runtime.RuntimeNodeStatus
import com.openminis.app.feature.runtime.RuntimeSessionCoordinator
import com.openminis.app.feature.runtime.RuntimeTreeStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The actor of every agent tool is the session the APPLICATION bound, never an
 * identity field the model can write into the arguments.
 *
 * ## The gap these tests close
 *
 * Until now the identity discipline was asserted only at the SCHEMA layer:
 * `assertFalse(definition.parameters.containsKey("actor_session_id"))` in
 * `AgentToolsGoalCompleteTest` and `AgentToolsRestartDescendantTest`. That is a
 * statement about the tool's advertisement to the model, not about the code that
 * runs. A tool can advertise no identity field and still read one —
 * `args.optString("actor_session_id")` compiles fine against a schema that
 * documents nothing. The only way to tell those two worlds apart is to forge the
 * field and watch what actually happens.
 *
 * ## How the forgery is built (and why it is not a nonsense id)
 *
 * Every forged value below names a session that REALLY EXISTS, is really live,
 * and really owns the thing the forged identity would have been allowed to do:
 *
 *  - `other-root` is a second root with its own child `other-child`;
 *  - `root` is a real Team member, so a forged `actor_session_id = "root"` is an
 *    identity that WOULD be authorized to message peer `b`.
 *
 * An invented id would only prove the tool rejects nonsense. A real one makes the
 * two readings of the same call visibly different — if the code read the
 * arguments, the observable outcome (which tree is reported, which node is
 * touched, who the sender is) would be the OTHER session's. Each test therefore
 * compares the forged call against the same call made honestly.
 *
 * ## What is executed, and what is not
 *
 * Executed: the real `AgentToolExecutor` entry point, its argument handling, the
 * real `RuntimeSessionCoordinator` over a real on-disk `RuntimeTreeStore`, the
 * real communication gateway and directory, the real Team mesh built through
 * `RuntimeDelegationParser.parse("/team …")`, and the child-side claim
 * (`claimNextStep` / `claimNextTurn`). `ChatViewModel`'s actor binding for
 * `stop_descendant` / `delete_subtree` is NOT reachable from a JVM test (it needs
 * the Android chat stack), so those two are pinned at the layer that decides —
 * see the last two tests, which state the boundary in place.
 */
class AgentToolExecutorIdentityForgeryTest {

    private companion object {
        /** The acting session the application binds, and its delegated child. */
        const val ROOT = "root"
        const val CHILD = "child"

        /** A SECOND, real, live session that owns a subtree of its own. */
        const val OTHER_ROOT = "other-root"
        const val OTHER_CHILD = "other-child"

        /** Two Team members under [ROOT]; [ROOT] is a peer of both. */
        const val A = "a"
        const val B = "b"

        /** A default-mode child of [OTHER_ROOT]: real, live, and in no team. */
        const val OUTSIDER = "outsider"
    }

    // ─── fixtures ────────────────────────────────────────────────────────

    /** A Context good enough for an executor in this test: it never reaches one. */
    private class TestContext(private val directory: File) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = directory
    }

    private class Fixture(prefix: String) {
        val dir: File = Files.createTempDirectory(prefix).toFile()
        val store: RuntimeTreeStore =
            RuntimeTreeStore.openForTest(File(dir, "session-tree.json")) { source, target ->
                Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            }
        val coordinator: RuntimeSessionCoordinator = run {
            val constructor = RuntimeSessionCoordinator::class.java
                .getDeclaredConstructor(RuntimeTreeStore::class.java)
            constructor.isAccessible = true
            constructor.newInstance(store)
        }
        val repository: RuntimeCommunicationRepository = RuntimeCommunicationRepository(
            RuntimeCommunicationFileStore(File(dir, "communication-metadata.json")),
        )

        fun executor(launcher: RestartedChildLauncher? = null): AgentToolExecutor =
            AgentToolExecutor(TestContext(dir), launcher, null, repository)

        fun dispose() {
            dir.deleteRecursively()
        }
    }

    /** Records the parent/child a restart was actually authorized for. */
    private class RecordingLauncher : RestartedChildLauncher {
        val calls = mutableListOf<Pair<String, String>>()

        override suspend fun restart(
            parentSessionId: String,
            childSessionId: String,
            request: RuntimeDelegationRequest,
        ): RestartedChildRun {
            calls += parentSessionId to childSessionId
            return RestartedChildRun(
                ok = true,
                childSessionId = childSessionId,
                output = "the child ran",
                endReason = "completed",
            )
        }
    }

    private fun Fixture.startTree(root: String, child: String) {
        assertNotNull("the root must be live", coordinator.startRoot(root))
        assertTrue("the child must be live", coordinator.startChild(root, child))
    }

    /** The state a crash leaves behind, produced the way the reaper produces it. */
    private fun Fixture.makeAbnormal(nodeId: String) {
        store.update { abort(nodeId, abnormal = true, reason = "lease expired") }
        assertEquals(RuntimeNodeStatus.ABNORMAL_INTERRUPTION, statusOf(nodeId))
    }

    private fun Fixture.statusOf(nodeId: String): RuntimeNodeStatus? =
        store.snapshot().node(nodeId)?.status

    /** Root + two Team members, plus a second root whose child is in no team. */
    private fun Fixture.startTeam() {
        val model = RuntimeModelSnapshot(provider = "test-provider", model = "test-model")
        val team = requireNotNull(RuntimeDelegationParser.parse("/team split the work"))
        val traditional = requireNotNull(RuntimeDelegationParser.parse("/subagent inspect the runtime tree"))
        assertNotNull(coordinator.startRoot(ROOT))
        assertTrue("a must join the team", coordinator.delegate(ROOT, A, team, model))
        assertTrue("b must join the team", coordinator.delegate(ROOT, B, team, model))
        assertNotNull(coordinator.startRoot(OTHER_ROOT))
        assertTrue("the outsider must be a default-mode child", coordinator.delegate(OTHER_ROOT, OUTSIDER, traditional, model))
    }

    // ─── supervise_descendants ───────────────────────────────────────────

    private fun superviseArgs(): String =
        JSONObject().put("tool_title", "Supervise Descendants").toString()

    private suspend fun supervise(
        executor: AgentToolExecutor,
        coordinator: RuntimeSessionCoordinator,
        bound: String,
        argsJson: String,
    ): JSONObject {
        val result = requireNotNull(
            executor.execute(AgentToolExecutor.SUPERVISE_DESCENDANTS, argsJson, bound, coordinator),
        ) { "the executor owns ${AgentToolExecutor.SUPERVISE_DESCENDANTS}" }
        assertTrue("supervise_descendants must answer: ${result.output}", result.success)
        return JSONObject(result.output)
    }

    private fun JSONObject.actorId(): String = getJSONObject("actor").getString("id")

    private fun JSONObject.descendantIds(): Set<String> {
        val descendants = getJSONArray("descendants")
        return (0 until descendants.length())
            .map { descendants.getJSONObject(it).getString("id") }
            .toSet()
    }

    /**
     * Observable effect: WHICH TREE the report describes.
     *
     * `other-root` is live and has `other-child` under it, so the forged id would
     * have produced a well-formed, plausible report — of somebody else's tree.
     * The assertion is not "the field is ignored" (unobservable) but "the actor
     * field says `root` and the descendants are `root`'s".
     */
    @Test
    fun `supervise_descendants ignores a forged actor and reports the bound session's tree`() = runBlocking {
        val f = Fixture("identity-supervise")
        try {
            f.startTree(ROOT, CHILD)
            f.startTree(OTHER_ROOT, OTHER_CHILD)

            // CONTROL: the same call with no forged field at all.
            val control = supervise(f.executor(), f.coordinator, ROOT, superviseArgs())
            assertEquals("control: the actor is the bound session", ROOT, control.actorId())
            assertEquals("control: the bound session's own subtree", setOf(CHILD), control.descendantIds())

            val forged = supervise(
                f.executor(),
                f.coordinator,
                ROOT,
                JSONObject(superviseArgs()).put("actor_session_id", OTHER_ROOT).toString(),
            )

            assertEquals(
                "the actor must stay the bound session, not the one the model named",
                ROOT,
                forged.actorId(),
            )
            assertEquals(setOf(CHILD), forged.descendantIds())
            assertFalse(
                "another session's child must never appear in this report",
                forged.descendantIds().contains(OTHER_CHILD),
            )
        } finally {
            f.dispose()
        }
    }

    // ─── restart_descendant ──────────────────────────────────────────────

    private fun restartArgs(childSessionId: String): String = JSONObject()
        .put("tool_title", "Restart Descendant")
        .put("child_session_id", childSessionId)
        .toString()

    private suspend fun restart(
        executor: AgentToolExecutor,
        coordinator: RuntimeSessionCoordinator,
        bound: String,
        argsJson: String,
    ): ToolExecutionResult = requireNotNull(
        executor.execute(AgentToolExecutor.RESTART_DESCENDANT, argsJson, bound, coordinator),
    ) { "the executor owns ${AgentToolExecutor.RESTART_DESCENDANT}" }

    /** CONTROL: with no forgery anywhere, the operation this test class forges works. */
    @Test
    fun `restart_descendant restarts an interrupted descendant of the bound session`() = runBlocking {
        val f = Fixture("identity-restart-control")
        try {
            f.startTree(ROOT, CHILD)
            f.makeAbnormal(CHILD)
            val launcher = RecordingLauncher()

            val result = restart(f.executor(launcher), f.coordinator, ROOT, restartArgs(CHILD))

            assertTrue(
                "restarting the bound session's own interrupted child must work: ${result.output}",
                result.success,
            )
            assertEquals(
                "the launcher must be told the BOUND parent, not a named one",
                listOf(ROOT to CHILD),
                launcher.calls,
            )
            assertEquals(RuntimeNodeStatus.RUNNING, f.statusOf(CHILD))
        } finally {
            f.dispose()
        }
    }

    /**
     * Observable effect: whether the restart happens at all.
     *
     * The forged id is `other-root`, a real live root. If the actor came from the
     * arguments, the target `child` would be OUTSIDE that actor's tree and the
     * call would have been refused. It succeeds, so the actor was the bound one.
     */
    @Test
    fun `restart_descendant ignores a forged actor_session_id`() = runBlocking {
        val f = Fixture("identity-restart-forged")
        try {
            f.startTree(ROOT, CHILD)
            f.makeAbnormal(CHILD)
            val launcher = RecordingLauncher()

            val forged = JSONObject(restartArgs(CHILD)).put("actor_session_id", OTHER_ROOT).toString()
            val result = restart(f.executor(launcher), f.coordinator, ROOT, forged)

            assertTrue(
                "the forged field must not change the outcome: ${result.output}",
                result.success,
            )
            assertEquals(
                "the launcher must be told the bound parent, not the one the model named",
                listOf(ROOT to CHILD),
                launcher.calls,
            )
            assertEquals(RuntimeNodeStatus.RUNNING, f.statusOf(CHILD))
        } finally {
            f.dispose()
        }
    }

    /**
     * The reverse direction, and the one a schema assertion can never reach: the
     * forged actor really DOES own the target.
     *
     * `other-child` is `other-root`'s interrupted child, so `other-root` is
     * entitled to restart it. The bound session is not. Nothing moves, nothing is
     * launched — and then the byte-identical request executed under the identity
     * the model named succeeds, which is what proves the refusal came from
     * identity scoping rather than from an impossible request.
     */
    @Test
    fun `restart_descendant refuses another session's child even when the forged actor owns it`() = runBlocking {
        val f = Fixture("identity-restart-cross")
        try {
            f.startTree(ROOT, CHILD)
            f.startTree(OTHER_ROOT, OTHER_CHILD)
            f.makeAbnormal(OTHER_CHILD)
            val launcher = RecordingLauncher()
            val executor = f.executor(launcher)

            val forged = JSONObject(restartArgs(OTHER_CHILD)).put("actor_session_id", OTHER_ROOT).toString()
            val refused = restart(executor, f.coordinator, ROOT, forged)

            assertFalse("a cross-session restart must be refused: ${refused.output}", refused.success)
            assertEquals(
                "the other session's child must be left exactly where it was",
                RuntimeNodeStatus.ABNORMAL_INTERRUPTION,
                f.statusOf(OTHER_CHILD),
            )
            assertTrue("nothing may be launched for a refused restart", launcher.calls.isEmpty())

            // CONTROL: same arguments, only the bound identity differs.
            val control = restart(executor, f.coordinator, OTHER_ROOT, forged)
            assertTrue(
                "control: the same request from its real owner must work: ${control.output}",
                control.success,
            )
            assertEquals(listOf(OTHER_ROOT to OTHER_CHILD), launcher.calls)
            assertEquals(RuntimeNodeStatus.RUNNING, f.statusOf(OTHER_CHILD))
        } finally {
            f.dispose()
        }
    }

    // ─── message_peer ────────────────────────────────────────────────────

    private fun peerArgs(target: String = B, message: String = "Please re-run the failing test."): String =
        JSONObject()
            .put("tool_title", "Message Peer")
            .put("target_session_id", target)
            .put("message", message)
            .toString()

    private suspend fun messagePeer(
        executor: AgentToolExecutor,
        coordinator: RuntimeSessionCoordinator,
        bound: String,
        argsJson: String,
    ): ToolExecutionResult = requireNotNull(
        executor.execute(AgentToolExecutor.MESSAGE_PEER, argsJson, bound, coordinator),
    ) { "the executor owns ${AgentToolExecutor.MESSAGE_PEER}" }

    /** CONTROL: `a` really can reach `b`, and `b` really holds what `a` sent. */
    @Test
    fun `message_peer delivers from the bound session to a team peer`() = runBlocking {
        val f = Fixture("identity-peer-control")
        try {
            f.startTeam()

            val result = messagePeer(f.executor(), f.coordinator, A, peerArgs())
            assertTrue("a teammate must be able to reach a peer: ${result.output}", result.success)

            val claimed = f.coordinator.claimNextStep(B)
            assertNotNull("the peer must hold the message", claimed)
            assertEquals(A, claimed!!.fromNodeId)
            assertEquals(RuntimeDelivery.TEAM_PEER, claimed.delivery)
        } finally {
            f.dispose()
        }
    }

    /**
     * Observable effect: WHO the message is from.
     *
     * `root` is a real peer of `b`, so reading the forged field would not have
     * broken the call — it would have silently re-labelled the sender, which is
     * exactly the kind of forgery that stays invisible in a passing test. The
     * sender is checked in both places it is written: the envelope `b` claims,
     * and the communication-directory record.
     */
    @Test
    fun `message_peer attributes the message to the bound sender, not a forged one`() = runBlocking {
        val f = Fixture("identity-peer-attribution")
        try {
            f.startTeam()

            val forged = JSONObject(peerArgs()).put("actor_session_id", ROOT).toString()
            val result = messagePeer(f.executor(), f.coordinator, A, forged)

            assertTrue("the send itself must still succeed: ${result.output}", result.success)
            val claimed = f.coordinator.claimNextStep(B)
            assertNotNull("the peer must hold the message", claimed)
            assertEquals(
                "the envelope's sender must be the bound session, not the one the model named",
                A,
                claimed!!.fromNodeId,
            )
            val recordId = JSONObject(result.output).getString("record_id")
            assertEquals(
                "the directory record's sender must agree with the envelope",
                A,
                requireNotNull(f.repository.find(recordId)).sender.sessionId,
            )
        } finally {
            f.dispose()
        }
    }

    /**
     * Cross-session forgery, rejected: `outsider` is a default-mode child of
     * another root and has no team address at all, while the forged id `root` IS
     * a peer of `b`. Read from the arguments, this send would have been
     * authorized; the message is refused and `b`'s inbox stays empty. Executing
     * the identical request under the name the model used — bound this time — is
     * accepted, so the refusal is the identity, not the request.
     */
    @Test
    fun `message_peer refuses a forged actor that would have been authorized`() = runBlocking {
        val f = Fixture("identity-peer-cross")
        try {
            f.startTeam()

            val forged = JSONObject(peerArgs(target = B)).put("actor_session_id", ROOT).toString()
            val refused = messagePeer(f.executor(), f.coordinator, OUTSIDER, forged)

            assertFalse("the forgery must not authorize a stranger: ${refused.output}", refused.success)
            assertNull("nothing may reach b from a forged identity", f.coordinator.claimNextStep(B))

            val control = messagePeer(f.executor(), f.coordinator, ROOT, forged)
            assertTrue("control: root really is a peer of b: ${control.output}", control.success)
            assertEquals(ROOT, f.coordinator.claimNextStep(B)?.fromNodeId)
        } finally {
            f.dispose()
        }
    }

    // ─── message_child (the same cross-session shape, for the tool that
    //     already had an ignored-actor test) ─────────────────────────────

    private fun childArgs(target: String, message: String = "Use the new API key."): String =
        JSONObject()
            .put("tool_title", "Message Child")
            .put("target_session_id", target)
            .put("message", message)
            .toString()

    /**
     * `AgentToolExecutorMessageChildTest` already pins the ignored field for
     * `message_child`, but with a target inside the BOUND tree, where a forged
     * actor that was honoured would simply have been refused. Here the forged
     * actor really owns the target, so honouring it would have DELIVERED: the
     * two readings differ by whether the message arrives.
     */
    @Test
    fun `message_child refuses another session's child even when the forged actor owns it`() = runBlocking {
        val f = Fixture("identity-child-cross")
        try {
            f.startTree(ROOT, CHILD)
            f.startTree(OTHER_ROOT, OTHER_CHILD)
            val executor = f.executor()

            val forged = JSONObject(childArgs(OTHER_CHILD)).put("actor_session_id", OTHER_ROOT).toString()
            val refused = requireNotNull(
                executor.execute(AgentToolExecutor.MESSAGE_CHILD, forged, ROOT, f.coordinator),
            )
            assertFalse("a cross-session send must be refused: ${refused.output}", refused.success)
            assertFalse(JSONObject(refused.output).getBoolean("delivered"))
            assertNull(f.coordinator.claimNextStep(OTHER_CHILD))
            assertNull(f.coordinator.claimNextTurn(OTHER_CHILD))
            assertNull(f.coordinator.claimNextNotification(OTHER_CHILD))

            // CONTROL: same arguments, bound identity = the actor the model named.
            val control = requireNotNull(
                executor.execute(AgentToolExecutor.MESSAGE_CHILD, forged, OTHER_ROOT, f.coordinator),
            )
            assertTrue("control: the real owner reaches its own child: ${control.output}", control.success)
            assertEquals(OTHER_ROOT, f.coordinator.claimNextStep(OTHER_CHILD)?.fromNodeId)
        } finally {
            f.dispose()
        }
    }

    // ─── conversation_query: an argument-only tool, pinned as such ───────

    private class FakeConversationSource(
        private val rows: List<ConversationMessage>,
    ) : ConversationTranscriptSource {
        override suspend fun count(sessionId: String): Int = rows.size

        override suspend fun page(
            sessionId: String,
            offset: Int,
            limit: Int,
            maxChars: Int,
        ): ConversationSlice {
            val window = rows.drop(offset).take(limit)
            return ConversationSlice(
                items = window,
                resumeOffsets = window.indices.map { offset + it },
                resumeAfter = offset + window.size,
                moreMayRemain = window.size >= limit,
            )
        }

        override suspend fun search(
            sessionId: String,
            keyword: String,
            offset: Int,
            limit: Int,
        ): ConversationSlice {
            val scanned = rows.drop(offset)
            val hits = mutableListOf<ConversationMessage>()
            val indices = mutableListOf<Int>()
            var consumed = scanned.size
            for ((i, r) in scanned.withIndex()) {
                if (!r.text.contains(keyword, ignoreCase = true)) continue
                hits += r
                indices += i
                if (hits.size >= limit) {
                    consumed = i + 1
                    break
                }
            }
            return ConversationSlice(
                items = hits,
                resumeOffsets = indices.map { offset + it },
                resumeAfter = offset + consumed,
                moreMayRemain = consumed < scanned.size,
            )
        }
    }

    /**
     * `conversation_query` is NOT a bound-identity tool: its dispatch is
     * `conversationQuery(argsJson)` — `AgentToolExecutor.execute` passes it no
     * session at all, so there is no binding for a forged field to lose to. Its
     * authority model is stated in its own KDoc: a conversation id is an
     * unguessable UUID, so possession of the id IS the capability, and the
     * requirement asks for no authorization (「不需要有鉴权」).
     *
     * What is pinned here is the only thing that could regress: the field the
     * other tools must ignore must not start influencing this one either — so a
     * later change that adds actor-scoped filtering to this read goes red.
     */
    @Test
    fun `conversation_query's answer is unchanged by an actor_session_id in the arguments`() = runBlocking {
        val dir = Files.createTempDirectory("identity-conversation").toFile()
        try {
            val source = FakeConversationSource(
                listOf(ConversationMessage("m-1", "user", 1L, "hello from the other conversation")),
            )
            val executor = AgentToolExecutor(TestContext(dir), null, source, null)
            val plain = JSONObject()
                .put("tool_title", "Conversation Query")
                .put("conversation_id", "some-conversation")
                .toString()

            val control = requireNotNull(
                executor.execute(AgentToolExecutor.CONVERSATION_QUERY, plain, ROOT, null),
            )
            val forged = requireNotNull(
                executor.execute(
                    AgentToolExecutor.CONVERSATION_QUERY,
                    JSONObject(plain).put("actor_session_id", OTHER_ROOT).toString(),
                    ROOT,
                    null,
                ),
            )

            assertTrue("control: the read must work at all: ${control.output}", control.success)
            assertEquals(
                "a forged identity field must change nothing about this read",
                control.output,
                forged.output,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    // ─── stop_descendant / delete_subtree: the layer that decides ────────

    /**
     * `stop_descendant` and `delete_subtree` are NOT owned by `AgentToolExecutor`.
     * Their bodies live in `ChatViewModel.executeStopDescendantTool` (the actor is
     * `val actorSessionId = activeSessionId.trim()`) and
     * `executeDeleteSubtreeTool` (`val initiator = activeSessionId.trim()`) — the
     * VIEWMODEL's bound session, never the arguments — and they are dispatched
     * from the chat tool switch rather than from the portable executor. Those
     * bodies need the Android chat stack and are not reachable from a JVM test,
     * so this boundary is stated instead of implied.
     *
     * What IS reachable, and what is asserted here, is the layer that decides: the
     * coordinator they call. It is also the reason the binding is worth anything —
     * the actor really does select what may be touched, so an actor taken from the
     * model's arguments would be a working cross-session capability, not an inert
     * extra field. A forged actor that exists is the exact shape an injection
     * would take, and it is refused.
     */
    @Test
    fun `the runtime scopes stop_descendant by actor, so the bound binding is load-bearing`() = runBlocking {
        val f = Fixture("identity-runtime-stop")
        try {
            f.startTree(ROOT, CHILD)
            f.startTree(OTHER_ROOT, OTHER_CHILD)

            // ORDER MATTERS, and getting it wrong turns this into a test that
            // cannot fail: a stop is only accepted while the target is ACTIVE, so
            // running the bound control first would leave the target terminal and
            // the forged attempt would then be refused for the wrong reason
            // ("target is not active") — a refusal that arrives whether or not the
            // cross-session guard exists. The forged attempt therefore goes first,
            // while the target is still live.
            val forged = f.coordinator.stopDescendant(
                actorSessionId = OTHER_ROOT,
                targetSessionId = CHILD,
                reason = "identity test",
            )
            assertFalse(
                "a real-but-forged actor must not reach across roots: ${forged.reason}",
                forged.accepted,
            )
            assertEquals(OTHER_ROOT, forged.actorNodeId)
            assertEquals(
                "the refusal must name the root rule, not some unrelated failure",
                "cross-root stop is not authorized",
                forged.reason,
            )

            // CONTROL: the bound actor is a real ancestor of the same live target.
            val control = f.coordinator.stopDescendant(
                actorSessionId = ROOT,
                targetSessionId = CHILD,
                reason = "identity test",
            )
            assertTrue("the bound session may stop its own child: ${control.reason}", control.accepted)
            assertEquals(ROOT, control.actorNodeId)
            assertEquals(CHILD, control.targetNodeId)
        } finally {
            f.dispose()
        }
    }

    /** The same scoping rule for `delete_subtree`, checked before anything is removed. */
    @Test
    fun `the runtime scopes delete_subtree by actor, and the bound session still succeeds`() = runBlocking {
        val f = Fixture("identity-runtime-delete")
        try {
            f.startTree(ROOT, CHILD)
            f.startTree(OTHER_ROOT, OTHER_CHILD)
            f.makeAbnormal(CHILD)

            val forged = f.coordinator.deleteSubtree(
                initiatorSessionId = OTHER_ROOT,
                executorSessionId = OTHER_ROOT,
                targetSessionId = CHILD,
                operationId = "forged-delete",
            )
            assertEquals(
                "a real-but-forged actor must not delete across roots: ${forged.reason}",
                DeleteSubtreeResult.REJECTED,
                forged.result,
            )
            // WHICH refusal, not merely "a refusal". `delete_subtree` has a second
            // guard right behind this one ("executor must be a strict ancestor of
            // target") that a cross-root actor would also trip, so asserting only
            // `REJECTED` would stay green if the cross-root rule were deleted —
            // the two causes would be indistinguishable. Naming the reason keeps
            // that signal.
            assertEquals(
                "the refusal must name the cross-session rule, not the ancestor rule behind it",
                "cross-root deletion is not authorized",
                forged.reason,
            )
            assertEquals(
                "a refused delete must leave the target exactly where it was",
                RuntimeNodeStatus.ABNORMAL_INTERRUPTION,
                f.statusOf(CHILD),
            )

            // CONTROL: the same request from the real owner. The operation id has
            // to differ — the runtime caches a receipt per operation id, so
            // replaying the refused one would just hand back the same refusal and
            // prove nothing about the actor.
            val control = f.coordinator.deleteSubtree(
                initiatorSessionId = ROOT,
                executorSessionId = ROOT,
                targetSessionId = CHILD,
                operationId = "bound-delete",
            )
            assertEquals("the real ancestor may delete it: ${control.reason}", DeleteSubtreeResult.DELETED, control.result)
            assertNull("the deleted node must be gone", f.statusOf(CHILD))
        } finally {
            f.dispose()
        }
    }
}
