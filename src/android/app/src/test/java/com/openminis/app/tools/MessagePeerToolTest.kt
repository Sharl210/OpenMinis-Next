package com.openminis.app.tools

import android.content.Context
import android.content.ContextWrapper
import com.openminis.app.feature.runtime.ConversationIdProtocol
import com.openminis.app.feature.runtime.RuntimeCommunicationFileStore
import com.openminis.app.feature.runtime.RuntimeCommunicationRepository
import com.openminis.app.feature.runtime.RuntimeDelegationParser
import com.openminis.app.feature.runtime.RuntimeDelivery
import com.openminis.app.feature.runtime.RuntimeModelSnapshot
import com.openminis.app.feature.runtime.RuntimeSessionCoordinator
import com.openminis.app.feature.runtime.RuntimeTreeStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [team-peer-mesh] `message_peer` end to end: the tool, the runtime tree's
 * authorization and the peer's inbox reader.
 *
 * Requirement (request.md:5): 「新加入的子代理要有 team 能力：所有子代理可以相互沟通
 * …在 /team 模式下工作」; (request.md:33): 「团队模式的话，他们就是 P2P 的节点…
 * 从和从之间可以 P2P 也就是可以拥有更多的一些拓扑关系和连接关系和交流」.
 *
 * ## The gap these tests close
 *
 * The mesh half (joining a Team builds bidirectional `TEAM_PEER` edges) is covered
 * by `TeamPeerMeshTest`. This file covers the half that was still missing after
 * that: a SENDER. `AgentTools.makeChildAgentTools()` handed a delegated child no
 * messaging tool at all, so although the runtime would authorize
 * teammate-to-teammate delivery, no model could ask for one — the addresses
 * existed and nothing could dial.
 *
 * ## What is executed, and what is not
 *
 * Executed: the tool entry point, its argument handling, the gateway, the
 * coordinator's id resolution, the real runtime tree over a real on-disk store,
 * the mesh built by the real `/team` delegation path, and the peer-side claim
 * (`claimNextStep`). That is the whole chain from "the model called the tool" to
 * "the message is in the peer's inbox".
 *
 * Delegation itself is driven through `RuntimeDelegationParser.parse("/team …")`
 * — the same request the composer produces — rather than by hand-calling
 * `addTeamPeerEdge`, so the test cannot manufacture the authorization it asserts.
 *
 * NOT executed here, stated rather than implied: `RuntimeChildRunner.execute`,
 * which needs a live provider loop. The last hop — a drained envelope becoming an
 * injected message in the peer's model call — is covered by
 * `RuntimeChildRunnerSteerPerTurnTest` for the TEAM_PEER label.
 */
class MessagePeerToolTest {

    private companion object {
        /** The dispatcher: also a Team member, so it is a peer of both children. */
        const val ROOT = "root"

        /** Two teammates under that root. */
        const val A = "a"
        const val B = "b"

        /** A session in the DEFAULT sub-agent mode: no team, therefore no address. */
        const val OUTSIDER = "outsider"
        const val OTHER_ROOT = "other-root"
    }

    // ─── fixtures ────────────────────────────────────────────────────────

    /** A Context good enough for an executor in this test: it never reaches one. */
    private class TestContext(private val directory: File) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = directory
    }

    private class Fixture {
        val dir: File = Files.createTempDirectory("message-peer").toFile()
        val store: RuntimeTreeStore = RuntimeTreeStore.openForTest(File(dir, "session-tree.json")) { source, target ->
            java.nio.file.Files.move(
                source.toPath(),
                target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
        val coordinator: RuntimeSessionCoordinator = run {
            val constructor = RuntimeSessionCoordinator::class.java
                .getDeclaredConstructor(RuntimeTreeStore::class.java)
            constructor.isAccessible = true
            constructor.newInstance(store)
        }

        /** The directory instance production hands the executor (main-agent side). */
        val repository: RuntimeCommunicationRepository = RuntimeCommunicationRepository(
            RuntimeCommunicationFileStore(File(dir, "communication-metadata.json")),
        )

        private val model = RuntimeModelSnapshot("p", "m")

        /** The exact request the composer produces for `/team <prompt>`. */
        private val teamRequest = requireNotNull(RuntimeDelegationParser.parse("/team split the work"))

        /** The exact request the composer produces for `/subagent <prompt>`. */
        private val traditionalRequest =
            requireNotNull(RuntimeDelegationParser.parse("/subagent inspect the runtime tree"))

        init {
            assertNotNull("the root must be live", coordinator.startRoot(ROOT))
            // Both members arrive through the real Team delegation path, which is
            // what forms the team and therefore what builds the addresses.
            assertTrue("a must join the team", coordinator.delegate(ROOT, A, teamRequest, model))
            assertTrue("b must join the team", coordinator.delegate(ROOT, B, teamRequest, model))
            // A default-mode session for the "not a peer" cases.
            assertNotNull(coordinator.startRoot(OTHER_ROOT))
            assertTrue(
                "the outsider must be a default-mode child",
                coordinator.delegate(OTHER_ROOT, OUTSIDER, traditionalRequest, model),
            )
        }

        /** Main-agent-shaped executor: it HAS the directory instance. */
        fun executor(repository: RuntimeCommunicationRepository? = this.repository): AgentToolExecutor =
            AgentToolExecutor(TestContext(dir), communicationRepository = repository)

        /** Child-shaped executor: production constructs it WITHOUT a directory. */
        fun childSideExecutor(): AgentToolExecutor = AgentToolExecutor(TestContext(dir))

        fun dispose() {
            dir.deleteRecursively()
        }
    }

    private fun args(
        target: String = B,
        message: String = "Please re-run the failing test.",
        title: String = "Message Peer",
    ): String = JSONObject()
        .put("tool_title", title)
        .put("target_session_id", target)
        .put("message", message)
        .toString()

    private fun JSONObject.errorCode(): String = getJSONObject("error").getString("code")
    private fun JSONObject.errorMessage(): String = getJSONObject("error").getString("message")

    // ─── 1. the producer: a teammate can be reached ──────────────────────

    @Test
    fun `a team member can message a peer and the peer can read it`() = runBlocking {
        val f = Fixture()
        try {
            val result = f.executor().execute(AgentToolExecutor.MESSAGE_PEER, args(), A, f.coordinator)
            assertNotNull("message_peer must be dispatched by the shared executor", result)
            assertTrue("the tool must report success: ${result!!.output}", result.success)

            val body = JSONObject(result.output)
            assertTrue(body.getBoolean("ok"))
            assertTrue(body.getBoolean("delivered"))
            assertEquals(B, body.getString("target_session_id"))
            assertEquals(RuntimeDelivery.TEAM_PEER.name.lowercase(), body.getString("delivery"))
            assertFalse("a delivered peer message must carry a message id", body.isNull("message_id"))

            // The observable end of the chain: the peer actually holds it, under the
            // step lane (TEAM_PEER is what claimNextStep matches).
            val claimed = f.coordinator.claimNextStep(B)
            assertNotNull("the peer must hold the message", claimed)
            assertEquals("Please re-run the failing test.", claimed!!.payload)
            assertEquals(A, claimed.fromNodeId)
            assertEquals(RuntimeDelivery.TEAM_PEER, claimed.delivery)
        } finally {
            f.dispose()
        }
    }

    @Test
    fun `the dispatcher is a peer too so the mesh is not sibling only`() = runBlocking {
        val f = Fixture()
        try {
            // B -> root: the whole team knows each other (request.md:160), not just
            // siblings among themselves.
            val result = f.executor().execute(AgentToolExecutor.MESSAGE_PEER, args(target = ROOT), B, f.coordinator)
            assertTrue("the root must be reachable as a peer: ${result!!.output}", result.success)
            val claimed = f.coordinator.claimNextStep(ROOT)
            assertNotNull("the root must hold the message", claimed)
            assertEquals(B, claimed!!.fromNodeId)
        } finally {
            f.dispose()
        }
    }

    // ─── 2. authorization still comes from the runtime ───────────────────

    @Test
    fun `a session outside the team is refused with the runtime reason`() = runBlocking {
        val f = Fixture()
        try {
            val result = f.executor().execute(AgentToolExecutor.MESSAGE_PEER, args(target = OUTSIDER), A, f.coordinator)
            assertNotNull(result)
            assertFalse("a non-team target must not be reported as delivered", result!!.success)

            val body = JSONObject(result.output)
            assertFalse(body.getBoolean("ok"))
            assertFalse("the failure must say nothing was delivered", body.getBoolean("delivered"))
            assertFalse(body.getBoolean("accepted"))
            assertEquals("DELIVERY_REJECTED", body.errorCode())
            // The reason is the runtime's own, not a sentence invented by the tool.
            assertEquals("Team mode is disabled", body.errorMessage())
        } finally {
            f.dispose()
        }
    }

    @Test
    fun `a member of another team is refused because peers must share a root`() = runBlocking {
        val f = Fixture()
        try {
            // A second team: its own root, its own members, also Team mode.
            val otherTeam = requireNotNull(RuntimeDelegationParser.parse("/team other work"))
            assertNotNull(f.coordinator.startRoot("team-two"))
            assertTrue(
                f.coordinator.delegate("team-two", "peer-two", otherTeam, RuntimeModelSnapshot("p", "m")),
            )

            val result = f.executor().execute(AgentToolExecutor.MESSAGE_PEER, args(target = "peer-two"), A, f.coordinator)
            assertFalse("cross-root delivery must stay refused", result!!.success)
            assertEquals("DELIVERY_REJECTED", JSONObject(result.output).errorCode())
            assertEquals(
                "Team peers must share a root",
                JSONObject(result.output).errorMessage(),
            )
        } finally {
            f.dispose()
        }
    }

    @Test
    fun `an unknown target is refused instead of silently succeeding`() = runBlocking {
        val f = Fixture()
        try {
            val result = f.executor().execute(AgentToolExecutor.MESSAGE_PEER, args(target = "nobody"), A, f.coordinator)
            assertFalse(result!!.success)
            assertEquals("DELIVERY_REJECTED", JSONObject(result.output).errorCode())
            assertTrue(
                "an unknown node must be reported as such: ${JSONObject(result.output).errorMessage()}",
                JSONObject(result.output).errorMessage().contains("unknown node"),
            )
        } finally {
            f.dispose()
        }
    }

    @Test
    fun `a session cannot message itself`() = runBlocking {
        val f = Fixture()
        try {
            val result = f.executor().execute(AgentToolExecutor.MESSAGE_PEER, args(target = A), A, f.coordinator)
            assertFalse(result!!.success)
            assertEquals("INVALID_ARGUMENTS", JSONObject(result.output).errorCode())
        } finally {
            f.dispose()
        }
    }

    // ─── 3. argument discipline ──────────────────────────────────────────

    @Test
    fun `a blank or missing body is refused rather than waking the peer with nothing`() = runBlocking {
        val f = Fixture()
        try {
            listOf(
                JSONObject().put("tool_title", "t").put("target_session_id", B).toString(),
                JSONObject().put("tool_title", "t").put("target_session_id", B).put("message", "   ").toString(),
            ).forEach { malformed ->
                val result = f.executor().execute(AgentToolExecutor.MESSAGE_PEER, malformed, A, f.coordinator)
                assertFalse(result!!.success)
                assertEquals("INVALID_ARGUMENTS", JSONObject(result.output).errorCode())
            }
            assertEquals("nothing may reach the peer", null, f.coordinator.claimNextStep(B))
        } finally {
            f.dispose()
        }
    }

    @Test
    fun `a runtime route address is rejected with the address explanation`() = runBlocking {
        val f = Fixture()
        try {
            // A routing address is a one-way hash, so it can never resolve to a node;
            // the literal shape is what `isRuntimeAddress` matches on.
            val route = ConversationIdProtocol.RUNTIME_ADDRESS_PREFIX + "9f2c1a44deadbeef"
            val result = f.executor().execute(AgentToolExecutor.MESSAGE_PEER, args(target = route), A, f.coordinator)
            assertFalse(result!!.success)
            assertEquals("ADDRESS_NOT_MESSAGEABLE", JSONObject(result.output).errorCode())
        } finally {
            f.dispose()
        }
    }

    // ─── 4. the child-side executor, which has no directory instance ─────

    @Test
    fun `a child side executor without a directory still delivers and says so`() = runBlocking {
        val f = Fixture()
        try {
            // This is how production builds the CHILD's executor
            // (`RuntimeChildRunner` passes no communication repository), so this is
            // the configuration every real `message_peer` call runs in. If the tool
            // hard-required a repository it would answer UNAVAILABLE forever and the
            // capability would be decorative.
            val result = f.childSideExecutor()
                .execute(AgentToolExecutor.MESSAGE_PEER, args(message = "ping from a child"), A, f.coordinator)

            assertNotNull(result)
            assertTrue(
                "a child-side call must really deliver, not answer UNAVAILABLE: ${result!!.output}",
                result.success,
            )
            val body = JSONObject(result.output)
            assertTrue(body.getBoolean("delivered"))
            // Honest about the half that did not happen: no directory record.
            assertTrue(body.isNull("record_id"))
            assertTrue(
                "the reply must state that no directory record was written: ${body.getString("note")}",
                body.getString("note").contains("No communication-directory record"),
            )
            // And the message is genuinely in the peer's inbox.
            val claimed = f.coordinator.claimNextStep(B)
            assertNotNull(claimed)
            assertEquals("ping from a child", claimed!!.payload)
        } finally {
            f.dispose()
        }
    }

    // ─── 5. surface placement, which is what makes it reachable at all ───

    @Test
    fun `message peer is on the child surface and not on the main agent surface`() {
        val childNames = AgentTools.makeChildAgentTools().map { it.name }
        assertTrue(
            "a delegated child must actually be handed the sender, otherwise the capability is unreachable",
            childNames.contains(AgentTools.MESSAGE_PEER_TOOL_NAME),
        )
        assertTrue(
            "the child surface must remain exactly {completion} ∪ PORTABLE_TOOL_NAMES",
            childNames.toSet() == setOf(AgentTools.SUBAGENT_COMPLETE_TOOL_NAME) + AgentToolExecutor.PORTABLE_TOOL_NAMES,
        )
        assertTrue(
            "message_peer is child-facing, so the executor must advertise it as portable",
            AgentToolExecutor.PORTABLE_TOOL_NAMES.contains(AgentTools.MESSAGE_PEER_TOOL_NAME),
        )
        assertFalse(
            "message_peer is not main-agent-only: the sibling direction is the requirement",
            AgentToolExecutor.MAIN_AGENT_ONLY_TOOL_NAMES.contains(AgentTools.MESSAGE_PEER_TOOL_NAME),
        )
        // Deliberately absent from the main agent's surface: it already reaches
        // downwards with message_child, and the two names differ in who must be the
        // target — offering both invites the mix-up.
        listOf(false, true).forEach { memory ->
            listOf(false, true).forEach { goal ->
                val mainNames = AgentTools.makeAgentTools(memoryEnabled = memory, goalActive = goal).map { it.name }
                assertFalse(
                    "the main agent must not be handed message_peer (memory=$memory goal=$goal)",
                    mainNames.contains(AgentTools.MESSAGE_PEER_TOOL_NAME),
                )
                assertTrue(
                    "the main agent keeps message_child (memory=$memory goal=$goal)",
                    mainNames.contains(AgentTools.MESSAGE_CHILD_TOOL_NAME),
                )
            }
        }
    }

    @Test
    fun `the peer tool takes no delivery argument because a peer holds no step authority`() {
        val definition = AgentTools.makeChildAgentTools()
            .firstOrNull { it.name == AgentTools.MESSAGE_PEER_TOOL_NAME }
        val required = requireNotNull(definition).required
        assertEquals(listOf("tool_title", "target_session_id", "message"), required)
        assertFalse(
            "delivery is not selectable for a peer: the mesh grants SEND, never STEER",
            definition.propertyOrdering?.contains("delivery") == true,
        )
    }
}
