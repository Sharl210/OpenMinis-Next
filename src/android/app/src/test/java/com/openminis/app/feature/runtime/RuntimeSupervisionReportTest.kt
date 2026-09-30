package com.openminis.app.feature.runtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-supervise-conversation-id] What `supervise_descendants` hands the
 * model, pinned against request.md:132: 「查看每一个代理的**运行状况**，可以还有它的
 * **哈希ID**」.
 *
 * Asserted on the JSON the tool actually returns, not on the source text that
 * builds it.
 */
class RuntimeSupervisionReportTest {

    private val now = 1_700_000_000_000L
    private val actorId = "11111111-2222-3333-4444-555555555555"
    private val childId = "66666666-7777-8888-9999-aaaaaaaaaaaa"

    private fun node(
        id: String,
        parentId: String?,
        depth: Int,
        status: RuntimeNodeStatus,
        abnormal: Boolean = false,
        leaseUntilMillis: Long? = null,
        updatedAtMillis: Long = now,
        task: String = "",
    ) = RuntimeSessionNode(
        id = id,
        parentId = parentId,
        rootId = actorId,
        depth = depth,
        model = RuntimeModelSnapshot(provider = "anthropic", model = "claude-x"),
        status = status,
        createdAtMillis = now - 60_000,
        updatedAtMillis = updatedAtMillis,
        leaseUntilMillis = leaseUntilMillis,
        abnormal = abnormal,
        role = if (depth == 0) "root" else "child",
        task = task,
    )

    private fun snapshot(
        actorStatus: RuntimeNodeStatus = RuntimeNodeStatus.RUNNING,
        children: List<RuntimeSessionNode>,
    ) = RuntimeSupervisionSnapshot(
        actorNode = node(actorId, null, 0, actorStatus, leaseUntilMillis = now + 30_000),
        actorCapabilities = AgentCapabilitySnapshot(
            nodeId = actorId,
            rootId = actorId,
            depth = 0,
            role = "root",
            task = "",
            maxDepth = 2,
            effectiveDepth = 2,
            selfCanDelegate = true,
            descendantCanDelegate = true,
            maxParallelSubagents = 5,
            currentActiveChildren = children.size,
            delegationMode = DelegationMode.TRADITIONAL,
            configRevision = 7,
        ),
        descendants = children,
        configurationRevision = 7,
    )

    private fun childJson(
        status: RuntimeNodeStatus = RuntimeNodeStatus.RUNNING,
        abnormal: Boolean = false,
        leaseUntilMillis: Long? = now + 30_000,
        updatedAtMillis: Long = now,
        task: String = "summarise the logs",
    ): JSONObject {
        val report = RuntimeSupervisionReport.toJson(
            snapshot(
                children = listOf(
                    node(
                        childId, actorId, 1, status,
                        abnormal = abnormal,
                        leaseUntilMillis = leaseUntilMillis,
                        updatedAtMillis = updatedAtMillis,
                        task = task,
                    ),
                ),
            ),
            nowMillis = now,
        )
        return report.getJSONArray("descendants").getJSONObject(0)
    }

    /**
     * The "hash ID" the requirement asks for. It must be the SAME id as the
     * conversation id — request.md:136 says so outright — and it must be handed
     * over in the prefix form every other model-facing exit uses, or the model
     * cannot recognise it as this app's own id.
     */
    @Test
    fun `every node reports its conversation id in prefixed form`() {
        val report = RuntimeSupervisionReport.toJson(
            snapshot(children = listOf(node(childId, actorId, 1, RuntimeNodeStatus.RUNNING))),
            nowMillis = now,
        )

        val actor = report.getJSONObject("actor")
        assertEquals(
            ConversationIdProtocol.prefixedConversationId(actorId),
            actor.getString("conversation_id"),
        )
        val child = report.getJSONArray("descendants").getJSONObject(0)
        assertEquals(
            ConversationIdProtocol.prefixedConversationId(childId),
            child.getString("conversation_id"),
        )
        assertTrue(child.getString("conversation_id").startsWith(ConversationIdProtocol.PREFIX))

        // The raw runtime id is still present, because the `#run-N` generation
        // spelling lives there and the supervisor must not lose it.
        assertEquals(childId, child.getString("id"))

        // The capability block is a model-facing id exit too.
        val capabilities = report.getJSONObject("capabilities")
        assertEquals(
            ConversationIdProtocol.prefixedConversationId(actorId),
            capabilities.getString("conversation_id"),
        )
    }

    /**
     * A re-run root's node id is `"$sessionId#run-N"`, but the runtime ADDRESS for
     * that conversation must match what the gateway derives for it
     * (`RuntimeCommunicationGateway.peer` hashes the plain session id). If the
     * generation leaked into the hash, the same conversation would have two
     * addresses and a parent could not match a child to the attachment/record it
     * was handed.
     */
    @Test
    fun `a root run generation does not change the reported address`() {
        val reRunId = "$actorId#run-3"
        val report = RuntimeSupervisionReport.toJson(
            RuntimeSupervisionSnapshot(
                actorNode = node(reRunId, null, 0, RuntimeNodeStatus.RUNNING, leaseUntilMillis = now + 1_000),
                actorCapabilities = null,
                descendants = emptyList(),
                configurationRevision = 1,
            ),
            nowMillis = now,
        )
        val actor = report.getJSONObject("actor")

        assertEquals(
            "the address must be the CONVERSATION's address, not the run generation's",
            RuntimeConversationAddress.fromStableSessionId(actorId).value,
            actor.getString("address"),
        )
        assertNotEquals(
            RuntimeConversationAddress.fromStableSessionId(reRunId).value,
            actor.getString("address"),
        )
        // ...and the conversation id drops the generation too, so both fields
        // agree on which conversation this is.
        assertEquals(
            ConversationIdProtocol.prefixedConversationId(actorId),
            actor.getString("conversation_id"),
        )
        // The raw runtime id keeps the generation, because the supervisor still
        // needs the spelling it can act on.
        assertEquals(reRunId, actor.getString("id"))
    }

    /**
     * The conversation id above must be the value `conversation_query` accepts —
     * otherwise the tool tells the model about an id it cannot then use.
     */
    @Test
    fun `the reported id is the value the read tool accepts`() {
        val child = childJson()
        assertEquals(
            childId,
            ConversationIdProtocol.strip(child.getString("conversation_id")),
        )
    }

    /**
     * 运行状况 has to answer "is it actually working", not merely report a status
     * word: a node whose owner died keeps saying RUNNING until its lease expires,
     * which is exactly the false reassurance request.md:132 asks the orchestrator
     * to be able to spot.
     */
    @Test
    fun `a node reports the liveness signals that a bare status word cannot`() {
        val health = childJson()
        assertEquals(RuntimeNodeStatus.RUNNING.name, health.getString("status"))
        assertFalse(health.getBoolean("abnormal"))
        assertEquals(30_000L, health.getLong("lease_remaining_ms"))
        assertEquals(0L, health.getLong("last_activity_age_ms"))
        assertEquals("summarise the logs", health.getString("task"))

        // A stale node is distinguishable from a live one by the same fields.
        val stale = childJson(updatedAtMillis = now - 45_000, leaseUntilMillis = now - 5_000)
        assertEquals(-5_000L, stale.getLong("lease_remaining_ms"))
        assertEquals(45_000L, stale.getLong("last_activity_age_ms"))
    }

    @Test
    fun `an abnormally interrupted node is flagged and still reported`() {
        val node = childJson(
            status = RuntimeNodeStatus.ABNORMAL_INTERRUPTION,
            abnormal = true,
            leaseUntilMillis = null,
        )
        assertEquals(RuntimeNodeStatus.ABNORMAL_INTERRUPTION.name, node.getString("status"))
        assertTrue(node.getBoolean("abnormal"))
        // No lease is not the same as "expired": it must read as absent, never as
        // a made-up number.
        assertTrue(node.isNull("lease_remaining_ms"))
    }

    /**
     * The actor and every descendant come out of ONE serializer. Before this they
     * were two inline blocks that had already drifted (the actor reported no
     * `root_id`/`depth`, a descendant did), so "the same field means the same
     * thing" held only by luck. Same key set is the executable form of that.
     */
    @Test
    fun `actor and descendants are serialised with the same key set`() {
        val report = RuntimeSupervisionReport.toJson(
            snapshot(children = listOf(node(childId, actorId, 1, RuntimeNodeStatus.RUNNING))),
            nowMillis = now,
        )
        val actorKeys = report.getJSONObject("actor").keys().asSequence().toSet()
        val childKeys = report.getJSONArray("descendants").getJSONObject(0).keys().asSequence().toSet()
        assertEquals(actorKeys, childKeys)
        assertNotNull(actorKeys)
        assertTrue("the shared shape must carry status", actorKeys.contains("status"))
        assertTrue("the shared shape must carry the conversation id", actorKeys.contains("conversation_id"))
    }

    /**
     * [T-android-conversation-id-query] request.md:132 calls a sub-agent's
     * identifier a 哈希ID, and the app's existing 哈希ID is the runtime ROUTE
     * address. Both are therefore reported — but as two fields, because they are
     * different in kind: only `conversation_id` can be resolved back to a
     * conversation and used to read it.
     */
    @Test
    fun `each node reports both its conversation id and its runtime route address`() {
        val child = childJson()

        val address = child.getString("address")
        assertTrue(
            "the route address must be in the app's address form",
            address.startsWith(ConversationIdProtocol.RUNTIME_ADDRESS_PREFIX),
        )
        assertEquals(
            "the address must be derived from the same conversation as the id",
            RuntimeConversationAddress.fromStableSessionId(childId).value,
            address,
        )
        // The two fields must not be the same string, or there would be no point
        // in reporting both and a caller could not tell which one it can read.
        assertFalse(address == child.getString("conversation_id"))
        assertTrue(ConversationIdProtocol.isRuntimeAddress(address))
        assertFalse(ConversationIdProtocol.isRuntimeAddress(child.getString("conversation_id")))
    }

    @Test
    fun `a session with no runtime node reports ok false rather than inventing one`() {
        val report = RuntimeSupervisionReport.toJson(
            RuntimeSupervisionSnapshot(
                actorNode = null,
                actorCapabilities = null,
                descendants = emptyList(),
                configurationRevision = 0,
            ),
            nowMillis = now,
        )
        assertFalse(report.getBoolean("ok"))
        assertTrue(report.isNull("actor"))
        assertTrue(report.isNull("capabilities"))
        assertEquals(0, report.getJSONArray("descendants").length())
    }
}
