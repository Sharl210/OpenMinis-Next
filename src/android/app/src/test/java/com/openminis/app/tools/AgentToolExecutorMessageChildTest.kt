package com.openminis.app.tools

import android.content.Context
import android.content.ContextWrapper
import com.openminis.app.feature.runtime.ConversationIdProtocol
import com.openminis.app.feature.runtime.RuntimeCommunicationFileStore
import com.openminis.app.feature.runtime.RuntimeCommunicationRepository
import com.openminis.app.feature.runtime.RuntimeDelivery
import com.openminis.app.feature.runtime.RuntimeSessionCoordinator
import com.openminis.app.feature.runtime.RuntimeTreeStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-agent-messaging] `message_child` end to end, at the layer where the
 * tool, the runtime tree's mailbox and the child's inbox reader meet.
 *
 * Requirement (request.md:7): 「主代理可以查看各子代理状态…并可以互发消息」;
 * (request.md:9): 「每次主代理给子代理发信息也注入一次」.
 *
 * ## The gap these tests close
 *
 * Before this tool, the delivery machinery was COMPLETE and IDLE:
 * `SessionTreeRuntime.send` enqueued into a child's inbox behind ancestry and
 * edge authorization, `RuntimeChildRunner` drained STEER/QUEUE/NOTIFY into the
 * child's next model call, and `RuntimeCommunicationGateway.send` wrapped the
 * two with a receipt — but no production call site ever invoked
 * `RuntimeCommunicationGateway.send`, so no message was ever sent. What these
 * tests assert is therefore the PROPERTY that was missing: calling the tool
 * actually puts a claimable envelope in the target child's inbox, carrying the
 * text the model wrote.
 *
 * ## What is executed, and what is not
 *
 * Executed: the tool entry point, its argument handling, the gateway, the
 * coordinator's id resolution, the real runtime tree over a real on-disk store,
 * and the child-side claim (`claimNextStep` / `claimNextTurn` /
 * `claimNextNotification`). That is the whole chain from "the model called the
 * tool" to "the message is in the child's inbox under the right delivery lane".
 *
 * NOT executed here, stated rather than implied: `RuntimeChildRunner.execute`,
 * which needs `ProviderRepository` (EncryptedSharedPreferences) and a live
 * provider loop. The one remaining hop — the drained envelope becoming an
 * injected message in the child's model call — is covered by
 * `RuntimeChildRunnerSteerPerTurnTest`, which drives the same
 * `claimPendingRuntimeMessages` the run body calls.
 */
class AgentToolExecutorMessageChildTest {

    private companion object {
        /** The acting (root) session id, as the application binds it. */
        const val ROOT = "root"

        /** The delegated child messages are sent to. */
        const val CHILD = "child"
    }

    // ─── fixtures ────────────────────────────────────────────────────────

    /** A Context good enough for an executor in this test: it never reaches one. */
    private class TestContext(private val directory: File) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = directory
    }

    private class Fixture {
        val dir: File = Files.createTempDirectory("message-child").toFile()
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

        /** The directory instance production hands the executor. */
        val repository: RuntimeCommunicationRepository = RuntimeCommunicationRepository(
            RuntimeCommunicationFileStore(File(dir, "communication-metadata.json")),
        )

        init {
            assertTrue("the root must be live", coordinator.startRoot(ROOT) != null)
            assertTrue("the child must be live", coordinator.startChild(ROOT, CHILD))
        }

        fun executor(repository: RuntimeCommunicationRepository? = this.repository): AgentToolExecutor =
            AgentToolExecutor(TestContext(dir), communicationRepository = repository)

        fun dispose() {
            dir.deleteRecursively()
        }
    }

    private fun args(
        target: String = CHILD,
        message: String = "Use the new API key.",
        delivery: String? = null,
        title: String = "Message Child",
    ): String = JSONObject()
        .put("tool_title", title)
        .put("target_session_id", target)
        .put("message", message)
        .apply { if (delivery != null) put("delivery", delivery) }
        .toString()

    private suspend fun send(
        executor: AgentToolExecutor,
        coordinator: RuntimeSessionCoordinator,
        argsJson: String,
        actor: String = ROOT,
    ): ToolExecutionResult = requireNotNull(
        executor.execute(AgentToolExecutor.MESSAGE_CHILD, argsJson, actor, coordinator),
    ) { "the executor owns ${AgentToolExecutor.MESSAGE_CHILD}" }

    // ─── 1. the property the whole feature exists for ────────────────────

    /**
     * The end-to-end claim: the main agent calls the tool, and a claimable
     * envelope carrying that text appears in the CHILD's inbox under the STEER
     * lane — which is the lane `RuntimeChildRunner` reads before the child's next
     * model call.
     */
    @Test
    fun `a sent message becomes a claimable steer envelope in the child's inbox`() = runBlocking {
        val f = Fixture()
        try {
            val result = send(f.executor(), f.coordinator, args())

            assertTrue("the tool must report success: ${result.output}", result.success)
            val json = JSONObject(result.output)
            assertTrue(json.getBoolean("ok"))
            assertTrue("delivery must be stated, not left to inference", json.getBoolean("delivered"))
            assertEquals(CHILD, json.getString("target_session_id"))
            assertEquals(
                ConversationIdProtocol.prefixedConversationId(CHILD),
                json.getString("conversation_id"),
            )

            // The child's own reader — the same call the run body makes — must now
            // see exactly this message. Nothing is asserted about intermediate
            // objects: this is the envelope the child's model will read.
            val step = f.coordinator.claimNextStep(CHILD)
            assertNotNull("the steer lane must carry the message", step)
            assertEquals("Use the new API key.", step!!.payload)
            assertEquals(RuntimeDelivery.STEER, step.delivery)
            assertEquals("the sender must be the acting session", ROOT, step.fromNodeId)

            // And it must be the one the tool reported, so a receipt can be
            // reconciled with the delivery that actually happened.
            assertEquals(json.getString("message_id"), step.id)
        } finally {
            f.dispose()
        }
    }

    /**
     * The default is `steer`: omitting `delivery` must behave as the schema says
     * it does, or the model's documented default is a lie.
     */
    @Test
    fun `delivery defaults to steer when the model omits it`() = runBlocking {
        val f = Fixture()
        try {
            val result = send(f.executor(), f.coordinator, args(delivery = null))
            val json = JSONObject(result.output)

            assertEquals("steer", json.getString("requested_delivery"))
            assertEquals("steer", json.getString("delivery"))
            assertEquals(RuntimeDelivery.STEER, f.coordinator.claimNextStep(CHILD)?.delivery)
            assertNull("a steer must not also land in the queued-turn lane", f.coordinator.claimNextTurn(CHILD))
        } finally {
            f.dispose()
        }
    }

    @Test
    fun `an explicit queue delivery lands in the queued-turn lane only`() = runBlocking {
        val f = Fixture()
        try {
            send(f.executor(), f.coordinator, args(message = "Next turn: summarise.", delivery = "queue"))

            val turn = f.coordinator.claimNextTurn(CHILD)
            assertEquals("Next turn: summarise.", turn?.payload)
            assertEquals(RuntimeDelivery.QUEUE, turn?.delivery)
            assertNull(f.coordinator.claimNextStep(CHILD))
        } finally {
            f.dispose()
        }
    }

    @Test
    fun `an explicit notify delivery lands in the notification lane only`() = runBlocking {
        val f = Fixture()
        try {
            send(f.executor(), f.coordinator, args(message = "FYI: the key rotated.", delivery = "notify"))

            val notice = f.coordinator.claimNextNotification(CHILD)
            assertEquals("FYI: the key rotated.", notice?.payload)
            assertEquals(RuntimeDelivery.NOTIFY, notice?.delivery)
            assertNull(f.coordinator.claimNextStep(CHILD))
            assertNull(f.coordinator.claimNextTurn(CHILD))
        } finally {
            f.dispose()
        }
    }

    /**
     * `delivery` arrives from a model, so its spelling is untrusted: case folding
     * has to happen here rather than producing a silent "unknown delivery".
     */
    @Test
    fun `delivery spelling is case-insensitive`() = runBlocking {
        val f = Fixture()
        try {
            val result = send(f.executor(), f.coordinator, args(delivery = "QUEUE"))
            assertTrue(result.success)
            assertEquals(RuntimeDelivery.QUEUE, f.coordinator.claimNextTurn(CHILD)?.delivery)
        } finally {
            f.dispose()
        }
    }

    /**
     * The child reports its id as `minis-conv-…`, so the tool has to accept
     * exactly what `supervise_descendants` handed the model — otherwise the model
     * copies the documented string and gets a rejection.
     */
    @Test
    fun `the prefixed conversation id is accepted as the target`() = runBlocking {
        val f = Fixture()
        try {
            val result = send(
                f.executor(),
                f.coordinator,
                args(target = ConversationIdProtocol.prefixedConversationId(CHILD)),
            )
            assertTrue("the prefixed id must resolve: ${result.output}", result.success)
            assertEquals("sent to the same node", CHILD, JSONObject(result.output).getString("target_session_id"))
            assertNotNull(f.coordinator.claimNextStep(CHILD))
        } finally {
            f.dispose()
        }
    }

    /** A message written to the tree must appear in the communication directory. */
    @Test
    fun `an accepted send is recorded in the communication directory`() = runBlocking {
        val f = Fixture()
        try {
            val result = send(f.executor(), f.coordinator, args())
            val recordId = JSONObject(result.output).getString("record_id")

            val record = requireNotNull(f.repository.find(recordId)) {
                "an accepted send must leave a directory record"
            }
            assertEquals(ROOT, record.sender.sessionId)
            assertEquals(CHILD, record.receiver.sessionId)
        } finally {
            f.dispose()
        }
    }

    // ─── 2. failure is never softened ────────────────────────────────────

    /**
     * The defect class this project keeps hitting: a call that FAILED reading as a
     * success. The runtime refuses a target it has no delivery edge to, and that
     * refusal has to reach the model verbatim — with `delivered = false` stated,
     * so a caller checking one field still cannot be misled.
     */
    @Test
    fun `a target with no delivery edge is refused with the runtime's own reason`() = runBlocking {
        val f = Fixture()
        try {
            // A second root: a real live node the acting session has no edge to.
            assertTrue(f.coordinator.startRoot("other-root") != null)

            val result = send(f.executor(), f.coordinator, args(target = "other-root"))

            assertFalse("a refused send must not report success", result.success)
            val json = JSONObject(result.output)
            assertFalse(json.getBoolean("ok"))
            assertFalse("the model must be able to read 'not delivered'", json.getBoolean("delivered"))
            assertFalse(json.getBoolean("accepted"))
            assertEquals("DELIVERY_REJECTED", json.getJSONObject("error").getString("code"))
            assertEquals(
                "the runtime's own reason must be passed through, not paraphrased",
                "delivery edge is not authorized",
                json.getJSONObject("error").getString("message"),
            )

            // And nothing may have been enqueued on a refused send.
            assertNull(f.coordinator.claimNextStep("other-root"))
            assertNull(f.coordinator.claimNextTurn("other-root"))
            assertNull(f.coordinator.claimNextNotification("other-root"))
        } finally {
            f.dispose()
        }
    }

    /**
     * An unknown id is the other refusal the runtime owns: it must come back as a
     * failure naming the runtime's reason rather than as an accepted no-op.
     */
    @Test
    fun `an unknown target is refused instead of silently accepted`() = runBlocking {
        val f = Fixture()
        try {
            val result = send(f.executor(), f.coordinator, args(target = "no-such-child"))

            assertFalse(result.success)
            val json = JSONObject(result.output)
            assertFalse(json.getBoolean("delivered"))
            assertEquals("DELIVERY_REJECTED", json.getJSONObject("error").getString("code"))
            assertEquals("unknown node", json.getJSONObject("error").getString("message"))
        } finally {
            f.dispose()
        }
    }

    /**
     * No directory configured ⇒ an explicit failure. This is the shape the
     * executor's other optional ports take (`conversation_query` without a
     * source), and it has to stay a failure: sending into the mailbox while
     * unable to record the directory would be a half-done delivery reported as a
     * whole one.
     */
    @Test
    fun `without a communication directory the tool refuses and sends nothing`() = runBlocking {
        val f = Fixture()
        try {
            val result = send(f.executor(repository = null), f.coordinator, args())

            assertFalse(result.success)
            val json = JSONObject(result.output)
            assertFalse(json.getBoolean("delivered"))
            assertEquals("UNAVAILABLE", json.getJSONObject("error").getString("code"))
            assertNull("nothing may reach the child when the tool refused", f.coordinator.claimNextStep(CHILD))
        } finally {
            f.dispose()
        }
    }

    /** No runtime coordinator ⇒ the same honest refusal, never a silent drop. */
    @Test
    fun `without a runtime coordinator the tool refuses and sends nothing`() = runBlocking {
        val f = Fixture()
        try {
            val result = requireNotNull(
                f.executor().execute(AgentToolExecutor.MESSAGE_CHILD, args(), ROOT, null),
            )

            assertFalse(result.success)
            assertEquals("UNAVAILABLE", JSONObject(result.output).getJSONObject("error").getString("code"))
            assertNull(f.coordinator.claimNextStep(CHILD))
        } finally {
            f.dispose()
        }
    }

    // ─── 3. argument handling ────────────────────────────────────────────

    @Test
    fun `a blank message is refused, because an empty steer would interrupt for nothing`() = runBlocking {
        val f = Fixture()
        try {
            val result = send(f.executor(), f.coordinator, args(message = "   "))
            assertFalse(result.success)
            assertEquals("INVALID_ARGUMENTS", JSONObject(result.output).getJSONObject("error").getString("code"))
            assertNull(f.coordinator.claimNextStep(CHILD))
        } finally {
            f.dispose()
        }
    }

    @Test
    fun `a missing target is refused with guidance pointing at the supervisor tool`() = runBlocking {
        val f = Fixture()
        try {
            val result = send(f.executor(), f.coordinator, args(target = ""))
            assertFalse(result.success)
            val error = JSONObject(result.output).getJSONObject("error")
            assertEquals("INVALID_ARGUMENTS", error.getString("code"))
            assertTrue(
                "the model must be told where the ids come from: ${error.getString("message")}",
                error.getString("message").contains("supervise_descendants"),
            )
        } finally {
            f.dispose()
        }
    }

    /**
     * A runtime ROUTE address is not a conversation and cannot be resolved to a
     * node; the honest answer names the id form to use instead of letting the
     * tree answer "unknown node" and sending the model after the wrong mistake.
     */
    @Test
    fun `a runtime route address is refused with the id form to use instead`() = runBlocking {
        val f = Fixture()
        try {
            val result = send(
                f.executor(),
                f.coordinator,
                args(target = ConversationIdProtocol.RUNTIME_ADDRESS_PREFIX + "a".repeat(64)),
            )
            assertFalse(result.success)
            val error = JSONObject(result.output).getJSONObject("error")
            assertEquals("ADDRESS_NOT_MESSAGEABLE", error.getString("code"))
            assertTrue(error.getString("message").contains(ConversationIdProtocol.PREFIX))
        } finally {
            f.dispose()
        }
    }

    /** An unknown delivery must refuse rather than pick a lane on the model's behalf. */
    @Test
    fun `an unknown delivery is refused and nothing is sent`() = runBlocking {
        val f = Fixture()
        try {
            val result = send(f.executor(), f.coordinator, args(delivery = "interrupt"))

            assertFalse(result.success)
            assertEquals("UNKNOWN_DELIVERY", JSONObject(result.output).getJSONObject("error").getString("code"))
            assertNull(f.coordinator.claimNextStep(CHILD))
            assertNull(f.coordinator.claimNextTurn(CHILD))
            assertNull(f.coordinator.claimNextNotification(CHILD))
        } finally {
            f.dispose()
        }
    }

    @Test
    fun `invalid JSON is refused rather than thrown`() = runBlocking {
        val f = Fixture()
        try {
            val result = requireNotNull(
                f.executor().execute(AgentToolExecutor.MESSAGE_CHILD, "{not json", ROOT, f.coordinator),
            )
            assertFalse(result.success)
            assertEquals("INVALID_ARGUMENTS", JSONObject(result.output).getJSONObject("error").getString("code"))
        } finally {
            f.dispose()
        }
    }

    /**
     * The actor is the application-bound session and is read from the argument to
     * `execute`, never from the argument JSON: a model-supplied identity field
     * could only ever be a forgery attempt, so it must have no effect at all.
     */
    @Test
    fun `an actor_session_id in the arguments is ignored`() = runBlocking {
        val f = Fixture()
        try {
            val forged = JSONObject(args()).put("actor_session_id", "other-root").toString()
            val result = send(f.executor(), f.coordinator, forged)

            assertTrue(result.success)
            assertEquals(
                "the sender must be the bound session, not the one the model named",
                ROOT,
                f.coordinator.claimNextStep(CHILD)?.fromNodeId,
            )
        } finally {
            f.dispose()
        }
    }
}
