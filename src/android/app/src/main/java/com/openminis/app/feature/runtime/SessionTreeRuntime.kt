package com.openminis.app.feature.runtime

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Traditional delegation keeps parent/child messages private; Team enables peer delivery. */
enum class DelegationMode { TRADITIONAL, TEAM }

enum class RuntimeNodeStatus {
    INACTIVE,
    STARTING,
    RUNNING,
    WAITING_CHILDREN,
    SUCCEEDED,
    FAILED,
    ABORTED,
    ABNORMAL_INTERRUPTION,
}

enum class RuntimeDelivery { QUEUE, STEER, NOTIFY, TEAM_PEER }

enum class RuntimeEdgeKind { PARENT_CHILD, SUBSCRIPTION, TEAM_PEER }

enum class RuntimeEdgePermission { SEND, NOTIFY, STEER }

data class RuntimeTopologyEdge(
    val id: String,
    val fromNodeId: String,
    val toNodeId: String,
    val kind: RuntimeEdgeKind,
    val permissions: Set<RuntimeEdgePermission>,
    val createdAtMillis: Long,
    val revokedAtMillis: Long? = null,
    val authorizedByNodeId: String? = null,
) {
    val active: Boolean
        get() = revokedAtMillis == null

    fun allows(permission: RuntimeEdgePermission): Boolean =
        active && permission in permissions
}

data class RuntimeSubscription(
    val id: String,
    val subscriberNodeId: String,
    val publisherNodeId: String,
    val permissions: Set<RuntimeEdgePermission>,
    val createdAtMillis: Long,
    val revokedAtMillis: Long? = null,
) {
    val active: Boolean
        get() = revokedAtMillis == null
}

data class RuntimeTopologySnapshot(
    val nodes: List<RuntimeSessionNode>,
    val edges: List<RuntimeTopologyEdge>,
    val subscriptions: List<RuntimeSubscription>,
) {
    val activeEdges: List<RuntimeTopologyEdge>
        get() = edges.filter(RuntimeTopologyEdge::active)

    val activeSubscriptions: List<RuntimeSubscription>
        get() = subscriptions.filter(RuntimeSubscription::active)
}

data class RuntimeEdgeReceipt(
    val accepted: Boolean,
    val edgeId: String? = null,
    val createdAtMillis: Long? = null,
    val revokedAtMillis: Long? = null,
    val reason: String? = null,
)

data class RuntimeModelSnapshot(
    val provider: String,
    val model: String,
    val note: String = "",
    val capabilities: Set<String> = DEFAULT_CAPABILITIES,
) {
    companion object {
        val DEFAULT_CAPABILITIES: Set<String> =
            setOf("tool_calling", "reasoning", "text_input", "image_input")
    }
}

data class RuntimeSessionNode(
    val id: String,
    val parentId: String?,
    val rootId: String,
    val depth: Int,
    val model: RuntimeModelSnapshot,
    val delegationMode: DelegationMode = DelegationMode.TRADITIONAL,
    val status: RuntimeNodeStatus = RuntimeNodeStatus.INACTIVE,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val leaseUntilMillis: Long? = null,
    val abnormal: Boolean = false,
    val role: String = "child",
    val task: String = "",
)

data class RuntimeEvent(
    val id: String,
    val nodeId: String,
    val kind: String,
    val timestampMillis: Long,
    val payload: String = "",
)

data class RuntimeEnvelope(
    val id: String,
    val fromNodeId: String,
    val toNodeId: String,
    val delivery: RuntimeDelivery,
    val payload: String,
    val createdAtMillis: Long,
    val claimed: Boolean = false,
    val taskIntent: String = "",
    val capabilitySnapshot: AgentCapabilitySnapshot? = null,
    val senderCapabilitySnapshot: AgentCapabilitySnapshot? = null,
)

data class RuntimeTreeConfig(
    var maxDepth: Int = 2,
    var maxParallelSubagents: Int = 5,
    var leaseMillis: Long = 120_000L,
    var mode: DelegationMode = DelegationMode.TRADITIONAL,
) {
    init {
        require(maxDepth in 0..MAX_DEPTH_LIMIT) { "maxDepth must be between 0 and $MAX_DEPTH_LIMIT" }
        require(maxParallelSubagents in 1..MAX_PARALLEL_LIMIT) {
            "maxParallelSubagents must be between 1 and $MAX_PARALLEL_LIMIT"
        }
        require(leaseMillis > 0) { "leaseMillis must be positive" }
    }

    companion object {
        const val MAX_DEPTH_LIMIT = 150
        const val MAX_PARALLEL_LIMIT = 150
    }
}

data class RuntimeAggregate(
    /** Legacy representative root; use [rootIds] for the complete aggregate. */
    val rootId: String?,
    val mainRunning: Boolean,
    val waitingForChildren: Boolean,
    val activeChildCount: Int,
    val activeTree: Boolean,
    val rootIds: Set<String> = emptySet(),
)

data class RuntimeDeliveryReceipt(
    val accepted: Boolean,
    val messageId: String?,
    val effectiveDelivery: RuntimeDelivery?,
    val reason: String? = null,
)

data class AgentCapabilitySnapshot(
    val nodeId: String,
    val rootId: String,
    val depth: Int,
    val role: String,
    val task: String,
    val maxDepth: Int,
    val effectiveDepth: Int,
    val selfCanDelegate: Boolean,
    val descendantCanDelegate: Boolean,
    val maxParallelSubagents: Int,
    val currentActiveChildren: Int,
    val delegationMode: DelegationMode,
    val configRevision: Long,
)

enum class RuntimeReceiptStatus { ENQUEUED, CLAIMED, REJECTED }

data class RuntimeMessageReceipt(
    val id: String,
    val messageId: String?,
    val fromNodeId: String,
    val toNodeId: String,
    val delivery: RuntimeDelivery?,
    val accepted: Boolean,
    val createdAtMillis: Long,
    val reason: String? = null,
    val status: RuntimeReceiptStatus = if (accepted) RuntimeReceiptStatus.ENQUEUED else RuntimeReceiptStatus.REJECTED,
)

data class RuntimeTranscriptMessage(
    val id: String,
    val nodeId: String,
    val role: String,
    val content: String,
    val createdAtMillis: Long,
    val metadata: String = "",
)

data class RuntimeExport(
    val format: String,
    val fileName: String,
    val bytes: ByteArray,
)

/**
 * Persistent-tree-ready runtime model. The tree is synchronized because stream
 * callbacks, notification actions, and child continuations can arrive together.
 */
class RuntimeSessionTree(
    val config: RuntimeTreeConfig = RuntimeTreeConfig(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val nodes = LinkedHashMap<String, RuntimeSessionNode>()
    private val children = LinkedHashMap<String, MutableList<String>>()
    private val events = ArrayList<RuntimeEvent>()
    private val inbox = ArrayList<RuntimeEnvelope>()
    private val deliveryReceipts = ArrayList<RuntimeMessageReceipt>()
    private val transcripts = LinkedHashMap<String, MutableList<RuntimeTranscriptMessage>>()
    private val topologyEdges = LinkedHashMap<String, RuntimeTopologyEdge>()
    private val subscriptions = LinkedHashMap<String, RuntimeSubscription>()
    private val rootIds = LinkedHashSet<String>()
    /** Legacy representative root retained for source and JSON compatibility. */
    private var rootId: String? = null
    private var configRevision = 0L

    @Synchronized
    fun createRoot(
        sessionId: String = UUID.randomUUID().toString(),
        model: RuntimeModelSnapshot,
        delegationMode: DelegationMode = config.mode,
        task: String = model.note,
    ): RuntimeSessionNode {
        require(isSafeNodeId(sessionId)) { "sessionId contains unsafe path characters" }
        nodes[sessionId]?.let { existing ->
            require(existing.parentId == null) { "sessionId belongs to a child node" }
            return existing
        }
        val now = clock()
        val node = RuntimeSessionNode(
            id = sessionId,
            parentId = null,
            rootId = sessionId,
            depth = 0,
            model = model,
            delegationMode = delegationMode,
            createdAtMillis = now,
            updatedAtMillis = now,
            role = "root/supervisor",
            task = task,
        )
        nodes[node.id] = node
        children[node.id] = mutableListOf()
        rootIds += node.id
        if (rootId == null) rootId = node.id
        record(node.id, "root_created", now, model.model)
        return node
    }

    @Synchronized
    fun createChild(
        parentId: String,
        childId: String = UUID.randomUUID().toString(),
        model: RuntimeModelSnapshot,
        delegationMode: DelegationMode = nodes[parentId]?.delegationMode ?: config.mode,
        task: String = model.note,
    ): Result<RuntimeSessionNode> {
        val parent = nodes[parentId] ?: return Result.failure(IllegalArgumentException("unknown parent"))
        if (!isSafeNodeId(childId)) return Result.failure(IllegalArgumentException("childId contains unsafe path characters"))
        if (config.maxDepth == 0 || parent.depth + 1 > config.maxDepth) {
            return Result.failure(IllegalStateException("delegation depth limit reached"))
        }
        if (capabilitySnapshot(parent.id)?.selfCanDelegate != true) {
            return Result.failure(IllegalStateException("parent cannot delegate at current depth or parallel limit"))
        }
        if (delegationMode == DelegationMode.TEAM && parent.delegationMode != DelegationMode.TEAM) {
            return Result.failure(IllegalStateException("Team child requires Team parent"))
        }
        if (nodes.containsKey(childId)) return Result.failure(IllegalArgumentException("child already exists"))
        val now = clock()
        val node = RuntimeSessionNode(
            id = childId,
            parentId = parent.id,
            rootId = parent.rootId,
            depth = parent.depth + 1,
            model = model,
            delegationMode = delegationMode,
            createdAtMillis = now,
            updatedAtMillis = now,
            role = "child",
            task = task,
        )
        nodes[node.id] = node
        children.getOrPut(parent.id) { mutableListOf() }.add(node.id)
        addTopologyEdge(
            fromNodeId = parent.id,
            toNodeId = node.id,
            kind = RuntimeEdgeKind.PARENT_CHILD,
            permissions = setOf(
                RuntimeEdgePermission.SEND,
                RuntimeEdgePermission.NOTIFY,
                RuntimeEdgePermission.STEER,
            ),
            createdAtMillis = now,
            authorizedByNodeId = parent.id,
        )
        record(parent.id, "child_created", now, node.id)
        return Result.success(node)
    }

    @Synchronized
    fun setDelegationMode(nodeId: String, mode: DelegationMode): Boolean {
        val node = nodes[nodeId] ?: return false
        val root = node.rootId
        nodes.values
            .filter { it.rootId == root }
            .forEach { current -> nodes[current.id] = current.copy(delegationMode = mode) }
        if (mode == DelegationMode.TRADITIONAL) {
            topologyEdges.values
                .filter { it.kind == RuntimeEdgeKind.TEAM_PEER && it.fromNodeId in nodes && it.toNodeId in nodes && nodes[it.fromNodeId]?.rootId == root }
                .forEach { edge -> topologyEdges[edge.id] = edge.copy(revokedAtMillis = clock()) }
        }
        config.mode = mode
        configRevision++
        record(root, "delegation_mode_changed", clock(), mode.name)
        return true
    }

    @Synchronized
    fun updateConfig(
        maxDepth: Int = config.maxDepth,
        maxParallelSubagents: Int = config.maxParallelSubagents,
        leaseMillis: Long = config.leaseMillis,
        mode: DelegationMode = config.mode,
    ): Boolean = runCatching {
        require(maxDepth in 0..RuntimeTreeConfig.MAX_DEPTH_LIMIT)
        require(maxParallelSubagents in 1..RuntimeTreeConfig.MAX_PARALLEL_LIMIT)
        require(leaseMillis > 0)
        config.maxDepth = maxDepth
        config.maxParallelSubagents = maxParallelSubagents
        config.leaseMillis = leaseMillis
        if (mode != config.mode) setDelegationMode(rootId ?: return false, mode) else {
            config.mode = mode
            configRevision++
        }
        true
    }.getOrDefault(false)

    private fun rejectDelivery(
        fromNodeId: String,
        toNodeId: String,
        delivery: RuntimeDelivery,
        nowMillis: Long,
        reason: String,
    ): RuntimeDeliveryReceipt {
        deliveryReceipts += RuntimeMessageReceipt(
            id = UUID.randomUUID().toString(), messageId = null,
            fromNodeId = fromNodeId, toNodeId = toNodeId, delivery = delivery,
            accepted = false, createdAtMillis = nowMillis, reason = reason,
            status = RuntimeReceiptStatus.REJECTED,
        )
        return RuntimeDeliveryReceipt(false, null, null, reason)
    }

    @Synchronized
    fun start(nodeId: String): Boolean = updateStatus(nodeId, RuntimeNodeStatus.RUNNING, "started")

    @Synchronized
    fun capabilitySnapshot(nodeId: String): AgentCapabilitySnapshot? {
        val node = nodes[nodeId] ?: return null
        val activeChildren = children[node.id].orEmpty().count { childId ->
            nodes[childId]?.status?.isActive() == true
        }
        val effectiveDepth = (config.maxDepth - node.depth).coerceAtLeast(0)
        return AgentCapabilitySnapshot(
            nodeId = node.id,
            rootId = node.rootId,
            depth = node.depth,
            role = node.role,
            task = node.task,
            maxDepth = config.maxDepth,
            effectiveDepth = effectiveDepth,
            selfCanDelegate = effectiveDepth > 0 && activeChildren < config.maxParallelSubagents,
            descendantCanDelegate = effectiveDepth > 1,
            maxParallelSubagents = config.maxParallelSubagents,
            currentActiveChildren = activeChildren,
            delegationMode = node.delegationMode,
            configRevision = configRevision,
        )
    }

    @Synchronized
    fun configurationRevision(): Long = configRevision


    @Synchronized
    fun heartbeat(nodeId: String, nowMillis: Long = clock()): Boolean {
        val node = nodes[nodeId] ?: return false
        if (!node.status.isActive()) return false
        nodes[nodeId] = node.copy(updatedAtMillis = nowMillis, leaseUntilMillis = nowMillis + config.leaseMillis)
        record(nodeId, "heartbeat", nowMillis)
        return true
    }

    @Synchronized
    fun renewActiveLeases(nowMillis: Long = clock()): List<String> {
        val active = nodes.values.filter { it.status.isActive() }.map { it.id }
        active.forEach { heartbeat(it, nowMillis) }
        return active
    }

    @Synchronized
    fun waitForChildren(nodeId: String): Boolean = updateStatus(nodeId, RuntimeNodeStatus.WAITING_CHILDREN, "waiting_children")

    @Synchronized
    fun resume(nodeId: String): Boolean = updateStatus(nodeId, RuntimeNodeStatus.RUNNING, "resumed")

    @Synchronized
    fun complete(nodeId: String, failed: Boolean = false): Boolean = updateStatus(
        nodeId,
        if (failed) RuntimeNodeStatus.FAILED else RuntimeNodeStatus.SUCCEEDED,
        if (failed) "failed" else "completed",
    )

    @Synchronized
    fun abort(nodeId: String, abnormal: Boolean, reason: String = ""): Boolean {
        val node = nodes[nodeId] ?: return false
        if (!node.status.isActive()) return false
        val now = clock()
        nodes[nodeId] = node.copy(
            status = if (abnormal) RuntimeNodeStatus.ABNORMAL_INTERRUPTION else RuntimeNodeStatus.ABORTED,
            updatedAtMillis = now,
            leaseUntilMillis = null,
            abnormal = abnormal,
        )
        record(nodeId, if (abnormal) "abnormal_interruption" else "aborted", now, reason)
        return true
    }

    /** Converts stale active nodes to recoverable abnormal interruptions. */
    @Synchronized
    fun reconcileLeases(nowMillis: Long = clock()): List<String> {
        val stale = nodes.values.filter {
            it.status.isActive() && it.leaseUntilMillis != null && it.leaseUntilMillis <= nowMillis
        }.map { it.id }
        stale.forEach { abort(it, abnormal = true, reason = "lease expired") }
        return stale
    }

    /**
     * Queue is an independent next-turn message. Steer targets the next step
     * while running; a closed delivery window is explicitly downgraded to Queue.
     */
    @Synchronized
    fun send(
        fromNodeId: String,
        toNodeId: String,
        payload: String,
        delivery: RuntimeDelivery,
        taskIntent: String = "",
    ): RuntimeDeliveryReceipt {
        val now = clock()
        if (payload.isBlank()) return rejectDelivery(fromNodeId, toNodeId, delivery, now, "payload must not be blank")
        val from = nodes[fromNodeId]
        val to = nodes[toNodeId]
        val senderSnapshot = capabilitySnapshot(fromNodeId)
        val receiverSnapshot = capabilitySnapshot(toNodeId)
        if (from == null || to == null || senderSnapshot == null || receiverSnapshot == null) {
            return rejectDelivery(fromNodeId, toNodeId, delivery, now, "unknown node")
        }
        if (delivery == RuntimeDelivery.TEAM_PEER &&
            (senderSnapshot.delegationMode != DelegationMode.TEAM || receiverSnapshot.delegationMode != DelegationMode.TEAM)
        ) {
            return rejectDelivery(fromNodeId, toNodeId, delivery, now, "Team mode is disabled")
        }
        if (delivery == RuntimeDelivery.TEAM_PEER && from.rootId != to.rootId) {
            return rejectDelivery(fromNodeId, toNodeId, delivery, now, "Team peers must share a root")
        }
        val permission = when (delivery) {
            RuntimeDelivery.TEAM_PEER -> RuntimeEdgePermission.SEND
            RuntimeDelivery.STEER -> RuntimeEdgePermission.STEER
            RuntimeDelivery.QUEUE -> RuntimeEdgePermission.SEND
            RuntimeDelivery.NOTIFY -> RuntimeEdgePermission.NOTIFY
        }
        val authorized = when (delivery) {
            RuntimeDelivery.TEAM_PEER -> topologyEdges.values.any {
                it.fromNodeId == fromNodeId && it.toNodeId == toNodeId &&
                    it.kind == RuntimeEdgeKind.TEAM_PEER && it.allows(permission)
            }
            RuntimeDelivery.NOTIFY -> topologyEdges.values.any {
                it.fromNodeId == fromNodeId && it.toNodeId == toNodeId &&
                    it.kind == RuntimeEdgeKind.PARENT_CHILD && it.allows(permission)
            } || subscriptions.values.any {
                it.subscriberNodeId == toNodeId && it.publisherNodeId == fromNodeId &&
                    it.active && RuntimeEdgePermission.NOTIFY in it.permissions
            }
            else -> topologyEdges.values.any {
                it.fromNodeId == fromNodeId && it.toNodeId == toNodeId &&
                    (it.kind == RuntimeEdgeKind.PARENT_CHILD || it.kind == RuntimeEdgeKind.TEAM_PEER) &&
                    it.allows(permission)
            } || subscriptions.values.any {
                it.subscriberNodeId == fromNodeId && it.publisherNodeId == toNodeId &&
                    it.active && permission in it.permissions
            }
        }
        if (!authorized) {
            val reason = if (delivery == RuntimeDelivery.TEAM_PEER) {
                "Team peer edge is not authorized"
            } else {
                "delivery edge is not authorized"
            }
            return rejectDelivery(fromNodeId, toNodeId, delivery, now, reason)
        }
        val effective = if (delivery == RuntimeDelivery.STEER && !to.status.isActive()) {
            RuntimeDelivery.QUEUE
        } else delivery
        val message = RuntimeEnvelope(
            id = UUID.randomUUID().toString(),
            fromNodeId = fromNodeId,
            toNodeId = toNodeId,
            delivery = effective,
            payload = payload,
            createdAtMillis = now,
            taskIntent = taskIntent,
            capabilitySnapshot = receiverSnapshot,
            senderCapabilitySnapshot = senderSnapshot,
        )
        inbox += message
        record(toNodeId, "message_enqueued", now, message.id)
        deliveryReceipts += RuntimeMessageReceipt(
            id = UUID.randomUUID().toString(), messageId = message.id,
            fromNodeId = fromNodeId, toNodeId = toNodeId, delivery = effective,
            accepted = true, createdAtMillis = now,
            reason = if (effective != delivery) "delivery window closed; queued" else null,
            status = RuntimeReceiptStatus.ENQUEUED,
        )
        return RuntimeDeliveryReceipt(
            true, message.id, effective,
            if (effective != delivery) "delivery window closed; queued" else null,
        )
    }

    @Synchronized
    fun claimNextStep(nodeId: String): RuntimeEnvelope? = claim(nodeId) { it.delivery == RuntimeDelivery.STEER || it.delivery == RuntimeDelivery.TEAM_PEER }

    @Synchronized
    fun claimNextNotification(nodeId: String): RuntimeEnvelope? = claim(nodeId) { it.delivery == RuntimeDelivery.NOTIFY }

    @Synchronized
    fun claimNextTurn(nodeId: String): RuntimeEnvelope? = claim(nodeId) { it.delivery == RuntimeDelivery.QUEUE }

    @Synchronized
    fun topology(): RuntimeTopologySnapshot = RuntimeTopologySnapshot(
        nodes = nodes.values.toList(),
        edges = topologyEdges.values.toList(),
        subscriptions = subscriptions.values.toList(),
    )

    @Synchronized
    fun addSubscription(
        subscriberNodeId: String,
        publisherNodeId: String,
        permissions: Set<RuntimeEdgePermission> = setOf(RuntimeEdgePermission.NOTIFY),
    ): RuntimeEdgeReceipt {
        if (nodes[subscriberNodeId] == null || nodes[publisherNodeId] == null) {
            return RuntimeEdgeReceipt(false, reason = "unknown node")
        }
        if (permissions.isEmpty()) return RuntimeEdgeReceipt(false, reason = "permissions must not be empty")
        val now = clock()
        val subscription = RuntimeSubscription(
            id = UUID.randomUUID().toString(),
            subscriberNodeId = subscriberNodeId,
            publisherNodeId = publisherNodeId,
            permissions = permissions.toSet(),
            createdAtMillis = now,
        )
        subscriptions[subscription.id] = subscription
        record(subscriberNodeId, "subscription_created", now, subscription.id)
        return RuntimeEdgeReceipt(true, subscription.id, now)
    }

    @Synchronized
    fun addTeamPeerEdge(
        fromNodeId: String,
        toNodeId: String,
        permissions: Set<RuntimeEdgePermission> = setOf(RuntimeEdgePermission.SEND),
    ): RuntimeEdgeReceipt {
        val from = nodes[fromNodeId]
        val to = nodes[toNodeId]
        if (from == null || to == null) return RuntimeEdgeReceipt(false, reason = "unknown node")
        if (from.rootId != to.rootId || from.delegationMode != DelegationMode.TEAM || to.delegationMode != DelegationMode.TEAM) {
            return RuntimeEdgeReceipt(false, reason = "Team mode is disabled")
        }
        if (fromNodeId == toNodeId || permissions.isEmpty()) return RuntimeEdgeReceipt(false, reason = "invalid peer edge")
        val now = clock()
        val edge = addTopologyEdge(fromNodeId, toNodeId, RuntimeEdgeKind.TEAM_PEER, permissions, now)
        record(fromNodeId, "team_peer_authorized", now, edge.id)
        return RuntimeEdgeReceipt(true, edge.id, now)
    }

    @Synchronized
    fun revokeTopologyEdge(edgeId: String, nowMillis: Long = clock()): RuntimeEdgeReceipt {
        val edge = topologyEdges[edgeId] ?: return RuntimeEdgeReceipt(false, edgeId = edgeId, reason = "unknown edge")
        if (!edge.active) return RuntimeEdgeReceipt(true, edgeId, edge.createdAtMillis, edge.revokedAtMillis)
        topologyEdges[edgeId] = edge.copy(revokedAtMillis = nowMillis)
        record(edge.fromNodeId, "topology_edge_revoked", nowMillis, edgeId)
        return RuntimeEdgeReceipt(true, edgeId, edge.createdAtMillis, nowMillis)
    }

    @Synchronized
    fun revokeSubscription(subscriptionId: String, nowMillis: Long = clock()): RuntimeEdgeReceipt {
        val subscription = subscriptions[subscriptionId]
            ?: return RuntimeEdgeReceipt(false, edgeId = subscriptionId, reason = "unknown subscription")
        if (!subscription.active) return RuntimeEdgeReceipt(true, subscriptionId, subscription.createdAtMillis, subscription.revokedAtMillis)
        subscriptions[subscriptionId] = subscription.copy(revokedAtMillis = nowMillis)
        record(subscription.subscriberNodeId, "subscription_revoked", nowMillis, subscriptionId)
        return RuntimeEdgeReceipt(true, subscriptionId, subscription.createdAtMillis, nowMillis)
    }

    @Synchronized
    fun appendTranscript(
        nodeId: String,
        role: String,
        content: String,
        metadata: String = "",
        messageId: String = UUID.randomUUID().toString(),
        createdAtMillis: Long = clock(),
    ): Boolean {
        if (nodes[nodeId] == null || role.isBlank() || content.isBlank()) return false
        transcripts.getOrPut(nodeId) { mutableListOf() } += RuntimeTranscriptMessage(
            id = messageId,
            nodeId = nodeId,
            role = role,
            content = content,
            createdAtMillis = createdAtMillis,
            metadata = metadata,
        )
        record(nodeId, "transcript_appended", createdAtMillis, messageId)
        return true
    }

    @Synchronized
    fun transcript(nodeId: String): List<RuntimeTranscriptMessage> = transcripts[nodeId].orEmpty().toList()

    @Synchronized
    fun receipts(): List<RuntimeMessageReceipt> = deliveryReceipts.toList()


    @Synchronized
    fun node(nodeId: String): RuntimeSessionNode? = nodes[nodeId]

    @Synchronized
    fun descendants(nodeId: String): List<RuntimeSessionNode> = descendantsOf(nodeId).mapNotNull(nodes::get)

    @Synchronized
    fun events(): List<RuntimeEvent> = events.toList()

    @Synchronized
    fun aggregate(): RuntimeAggregate {
        val roots = rootIds.mapNotNull(nodes::get)
        val activeChildren = nodes.values.count { it.parentId != null && it.status.isActive() }
        return RuntimeAggregate(
            rootId = rootId,
            mainRunning = roots.any { it.status == RuntimeNodeStatus.STARTING || it.status == RuntimeNodeStatus.RUNNING },
            waitingForChildren = roots.any { it.status == RuntimeNodeStatus.WAITING_CHILDREN },
            activeChildCount = activeChildren,
            activeTree = roots.any { it.status.isActive() } || activeChildren > 0,
            rootIds = rootIds.toSet(),
        )
    }

    @Synchronized
    fun toJson(): String {
        val out = JSONObject()
        // Keep rootId for old readers; rootIds is the complete multi-root view.
        out.put("rootId", rootId)
        out.put("rootIds", JSONArray(rootIds.toList()))
        out.put("config", JSONObject().apply {
            put("maxDepth", config.maxDepth)
            put("maxParallelSubagents", config.maxParallelSubagents)
            put("leaseMillis", config.leaseMillis)
            put("mode", config.mode.name)
            put("configRevision", configRevision)
        })
        out.put("nodes", JSONArray().apply { nodes.values.forEach { put(nodeJson(it)) } })
        out.put("events", JSONArray().apply { events.forEach { put(eventJson(it)) } })
        out.put("inbox", JSONArray().apply { inbox.forEach { put(envelopeJson(it)) } })
        out.put("topologyEdges", JSONArray().apply { topologyEdges.values.forEach { put(edgeJson(it)) } })
        out.put("subscriptions", JSONArray().apply { subscriptions.values.forEach { put(subscriptionJson(it)) } })
        out.put("deliveryReceipts", JSONArray().apply { deliveryReceipts.forEach { put(receiptJson(it)) } })
        out.put("transcripts", JSONArray().apply {
            transcripts.values.flatten().forEach { put(transcriptJson(it)) }
        })
        return out.toString()
    }

    @Synchronized
    fun restoreJson(raw: String): Boolean {
        return runCatching {
            val json = JSONObject(raw)
            nodes.clear(); children.clear(); events.clear(); inbox.clear()
            topologyEdges.clear(); subscriptions.clear(); deliveryReceipts.clear(); transcripts.clear()
            rootIds.clear()
            val configJson = json.optJSONObject("config")
            if (configJson != null) {
                config.mode = runCatching { DelegationMode.valueOf(configJson.optString("mode")) }
                    .getOrDefault(config.mode)
            }
            val legacyRoot = json.optString("rootId").takeIf { it.isNotBlank() && it != "null" }
            val serializedRoots = json.optJSONArray("rootIds")
            if (serializedRoots != null) {
                for (i in 0 until serializedRoots.length()) {
                    serializedRoots.optString(i).takeIf { it.isNotBlank() && it != "null" }?.let(rootIds::add)
                }
            } else {
                legacyRoot?.let(rootIds::add)
            }
            rootId = legacyRoot ?: rootIds.firstOrNull()
            val nodeArray = json.optJSONArray("nodes") ?: JSONArray()
            for (i in 0 until nodeArray.length()) {
                val node = parseNode(nodeArray.getJSONObject(i))
                nodes[node.id] = node
                children.getOrPut(node.id) { mutableListOf() }
            }
            nodes.values.forEach { node -> node.parentId?.let { children.getOrPut(it) { mutableListOf() }.add(node.id) } }
            val eventArray = json.optJSONArray("events") ?: JSONArray()
            for (i in 0 until eventArray.length()) events += parseEvent(eventArray.getJSONObject(i))
            val inboxArray = json.optJSONArray("inbox") ?: JSONArray()
            for (i in 0 until inboxArray.length()) inbox += parseEnvelope(inboxArray.getJSONObject(i))
            val edgeArray = json.optJSONArray("topologyEdges") ?: JSONArray()
            for (i in 0 until edgeArray.length()) {
                val edge = parseEdge(edgeArray.getJSONObject(i))
                topologyEdges[edge.id] = edge
            }
            val subscriptionArray = json.optJSONArray("subscriptions") ?: JSONArray()
            for (i in 0 until subscriptionArray.length()) {
                val subscription = parseSubscription(subscriptionArray.getJSONObject(i))
                subscriptions[subscription.id] = subscription
            }
            val receiptArray = json.optJSONArray("deliveryReceipts") ?: JSONArray()
            for (i in 0 until receiptArray.length()) deliveryReceipts += parseReceipt(receiptArray.getJSONObject(i))
            val transcriptArray = json.optJSONArray("transcripts") ?: JSONArray()
            for (i in 0 until transcriptArray.length()) {
                val message = parseTranscript(transcriptArray.getJSONObject(i))
                transcripts.getOrPut(message.nodeId) { mutableListOf() } += message
            }
            val actualRoots = nodes.values.filter { it.parentId == null }.map { it.id }.toSet()
            rootIds.retainAll(actualRoots)
            rootIds.addAll(actualRoots)
            rootId = rootId?.takeIf(actualRoots::contains) ?: rootIds.firstOrNull()
            true
        }.getOrDefault(false)
    }

    @Synchronized
    fun export(format: String): RuntimeExport {
        require(format == "json" || format == "text") { "format must be json or text" }
        val bytes = if (format == "json") {
            toJson().toByteArray(StandardCharsets.UTF_8)
        } else {
            textExport().toByteArray(StandardCharsets.UTF_8)
        }
        return RuntimeExport(format, if (format == "json") "session-tree.json" else "session-tree.txt", bytes)
    }

    @Synchronized
    fun exportZip(format: String): RuntimeExport {
        val file = export(format)
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(file.fileName)); zip.write(file.bytes); zip.closeEntry()
            nodes.values.forEach { node ->
                val relativePath = nodeDirectory(node).split('/')
                val nestedPath = buildString {
                    append(relativePath.first())
                    relativePath.drop(1).forEach { append("/children/").append(it) }
                }
                val name = "$nestedPath/node.${if (format == "json") "json" else "txt"}"
                zip.putNextEntry(ZipEntry(name))
                if (format == "json") zip.write(nodeExportJson(node).toString().toByteArray(StandardCharsets.UTF_8))
                else zip.write(nodeText(node).toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
        }
        return RuntimeExport(format, "session-tree.zip", output.toByteArray())
    }

    private fun addTopologyEdge(
        fromNodeId: String,
        toNodeId: String,
        kind: RuntimeEdgeKind,
        permissions: Set<RuntimeEdgePermission>,
        createdAtMillis: Long,
        authorizedByNodeId: String? = null,
    ): RuntimeTopologyEdge {
        val edge = RuntimeTopologyEdge(
            id = UUID.randomUUID().toString(),
            fromNodeId = fromNodeId,
            toNodeId = toNodeId,
            kind = kind,
            permissions = permissions.toSet(),
            createdAtMillis = createdAtMillis,
            authorizedByNodeId = authorizedByNodeId,
        )
        topologyEdges[edge.id] = edge
        return edge
    }
    private fun updateStatus(nodeId: String, status: RuntimeNodeStatus, event: String): Boolean {
        val node = nodes[nodeId] ?: return false
        if (node.status.isTerminal() && status != RuntimeNodeStatus.INACTIVE) return false
        val now = clock()
        nodes[nodeId] = node.copy(status = status, updatedAtMillis = now, leaseUntilMillis = if (status.isActive()) now + config.leaseMillis else null)
        record(nodeId, event, now)
        return true
    }

    private fun claim(nodeId: String, predicate: (RuntimeEnvelope) -> Boolean): RuntimeEnvelope? {
        val index = inbox.indexOfFirst { !it.claimed && it.toNodeId == nodeId && predicate(it) }
        if (index < 0) return null
        val claimed = inbox[index].copy(claimed = true)
        inbox[index] = claimed
        val receiptIndex = deliveryReceipts.indexOfLast { it.messageId == claimed.id }
        if (receiptIndex >= 0) {
            deliveryReceipts[receiptIndex] = deliveryReceipts[receiptIndex].copy(
                status = RuntimeReceiptStatus.CLAIMED,
                reason = "message claimed",
            )
        }
        record(nodeId, "message_claimed", clock(), claimed.id)
        return claimed
    }

    private fun descendantsOf(nodeId: String): List<String> = children[nodeId].orEmpty().flatMap { listOf(it) + descendantsOf(it) }

    private fun record(nodeId: String, kind: String, timestamp: Long, payload: String = "") {
        events += RuntimeEvent(UUID.randomUUID().toString(), nodeId, kind, timestamp, payload)
    }

    private fun snapshotJson(snapshot: AgentCapabilitySnapshot?) = snapshot?.let {
        JSONObject().apply {
            put("nodeId", it.nodeId); put("rootId", it.rootId); put("depth", it.depth)
            put("role", it.role); put("task", it.task); put("maxDepth", it.maxDepth)
            put("effectiveDepth", it.effectiveDepth); put("selfCanDelegate", it.selfCanDelegate)
            put("descendantCanDelegate", it.descendantCanDelegate)
            put("maxParallelSubagents", it.maxParallelSubagents)
            put("currentActiveChildren", it.currentActiveChildren)
            put("delegationMode", it.delegationMode.name); put("configRevision", it.configRevision)
        }
    }

    private fun parseSnapshot(json: JSONObject?): AgentCapabilitySnapshot? = json?.let {
        AgentCapabilitySnapshot(
            nodeId = it.optString("nodeId"), rootId = it.optString("rootId"), depth = it.optInt("depth"),
            role = it.optString("role"), task = it.optString("task"), maxDepth = it.optInt("maxDepth"),
            effectiveDepth = it.optInt("effectiveDepth"), selfCanDelegate = it.optBoolean("selfCanDelegate"),
            descendantCanDelegate = it.optBoolean("descendantCanDelegate"),
            maxParallelSubagents = it.optInt("maxParallelSubagents"),
            currentActiveChildren = it.optInt("currentActiveChildren"),
            delegationMode = runCatching { DelegationMode.valueOf(it.optString("delegationMode")) }
                .getOrDefault(DelegationMode.TRADITIONAL),
            configRevision = it.optLong("configRevision"),
        )
    }

    private fun nodeJson(node: RuntimeSessionNode) = JSONObject().apply {
        put("id", node.id); put("parentId", node.parentId); put("rootId", node.rootId); put("depth", node.depth)
        put("status", node.status.name); put("delegationMode", node.delegationMode.name); put("createdAtMillis", node.createdAtMillis); put("updatedAtMillis", node.updatedAtMillis)
        put("leaseUntilMillis", node.leaseUntilMillis); put("abnormal", node.abnormal)
        put("model", JSONObject().apply { put("provider", node.model.provider); put("model", node.model.model); put("note", node.model.note); put("capabilities", JSONArray(node.model.capabilities.toList())) })
    }

    private fun eventJson(event: RuntimeEvent) = JSONObject().apply {
        put("id", event.id); put("nodeId", event.nodeId); put("kind", event.kind); put("timestampMillis", event.timestampMillis); put("payload", event.payload)
    }

    private fun envelopeJson(message: RuntimeEnvelope) = JSONObject().apply {
        put("id", message.id); put("fromNodeId", message.fromNodeId); put("toNodeId", message.toNodeId); put("delivery", message.delivery.name); put("payload", message.payload); put("createdAtMillis", message.createdAtMillis); put("claimed", message.claimed); put("taskIntent", message.taskIntent)
        put("capabilitySnapshot", snapshotJson(message.capabilitySnapshot))
        put("senderCapabilitySnapshot", snapshotJson(message.senderCapabilitySnapshot))
    }

    private fun edgeJson(edge: RuntimeTopologyEdge) = JSONObject().apply {
        put("id", edge.id); put("fromNodeId", edge.fromNodeId); put("toNodeId", edge.toNodeId)
        put("kind", edge.kind.name); put("permissions", JSONArray(edge.permissions.map { it.name }))
        put("createdAtMillis", edge.createdAtMillis); put("revokedAtMillis", edge.revokedAtMillis)
    }

    private fun subscriptionJson(subscription: RuntimeSubscription) = JSONObject().apply {
        put("id", subscription.id); put("subscriberNodeId", subscription.subscriberNodeId)
        put("publisherNodeId", subscription.publisherNodeId)
        put("permissions", JSONArray(subscription.permissions.map { it.name }))
        put("createdAtMillis", subscription.createdAtMillis); put("revokedAtMillis", subscription.revokedAtMillis)
    }

    private fun receiptJson(receipt: RuntimeMessageReceipt) = JSONObject().apply {
        put("id", receipt.id); put("messageId", receipt.messageId)
        put("fromNodeId", receipt.fromNodeId); put("toNodeId", receipt.toNodeId)
        put("delivery", receipt.delivery?.name); put("accepted", receipt.accepted)
        put("createdAtMillis", receipt.createdAtMillis); put("reason", receipt.reason)
        put("status", receipt.status.name)
    }

    private fun transcriptJson(message: RuntimeTranscriptMessage) = JSONObject().apply {
        put("id", message.id); put("nodeId", message.nodeId); put("role", message.role)
        put("content", message.content); put("createdAtMillis", message.createdAtMillis)
        put("metadata", message.metadata)
    }

    private fun nodeExportJson(node: RuntimeSessionNode) = JSONObject().apply {
        put("node", nodeJson(node))
        put("transcript", JSONArray(transcripts[node.id].orEmpty().map(::transcriptJson)))
        put("events", JSONArray(events.filter { it.nodeId == node.id }.map(::eventJson)))
        put("inbox", JSONArray(inbox.filter { it.toNodeId == node.id || it.fromNodeId == node.id }.map(::envelopeJson)))
        put("receipts", JSONArray(deliveryReceipts.filter { it.toNodeId == node.id || it.fromNodeId == node.id }.map(::receiptJson)))
        put("edges", JSONArray(topologyEdges.values.filter { it.fromNodeId == node.id || it.toNodeId == node.id }.map(::edgeJson)))
        put("subscriptions", JSONArray(subscriptions.values.filter {
            it.subscriberNodeId == node.id || it.publisherNodeId == node.id
        }.map(::subscriptionJson)))
    }

    private fun parsePermissions(json: JSONObject): Set<RuntimeEdgePermission> =
        json.optJSONArray("permissions")?.let { array ->
            buildSet {
                for (i in 0 until array.length()) {
                    runCatching { RuntimeEdgePermission.valueOf(array.optString(i)) }
                        .getOrNull()
                        ?.let(::add)
                }
            }
        }.orEmpty()

    private fun parseEdge(json: JSONObject) = RuntimeTopologyEdge(
        id = json.optString("id"),
        fromNodeId = json.optString("fromNodeId"),
        toNodeId = json.optString("toNodeId"),
        kind = runCatching { RuntimeEdgeKind.valueOf(json.optString("kind")) }
            .getOrDefault(RuntimeEdgeKind.PARENT_CHILD),
        permissions = parsePermissions(json),
        createdAtMillis = json.optLong("createdAtMillis"),
        revokedAtMillis = if (json.isNull("revokedAtMillis")) null else json.optLong("revokedAtMillis"),
    )

    private fun parseSubscription(json: JSONObject) = RuntimeSubscription(
        id = json.optString("id"),
        subscriberNodeId = json.optString("subscriberNodeId"),
        publisherNodeId = json.optString("publisherNodeId"),
        permissions = parsePermissions(json),
        createdAtMillis = json.optLong("createdAtMillis"),
        revokedAtMillis = if (json.isNull("revokedAtMillis")) null else json.optLong("revokedAtMillis"),
    )

    private fun parseReceipt(json: JSONObject) = RuntimeMessageReceipt(
        id = json.optString("id"),
        messageId = json.optString("messageId").takeIf { it.isNotBlank() && it != "null" },
        fromNodeId = json.optString("fromNodeId"),
        toNodeId = json.optString("toNodeId"),
        delivery = runCatching { RuntimeDelivery.valueOf(json.optString("delivery")) }.getOrNull(),
        accepted = json.optBoolean("accepted"),
        createdAtMillis = json.optLong("createdAtMillis"),
        reason = json.optString("reason").takeIf { it.isNotBlank() && it != "null" },
        status = runCatching { RuntimeReceiptStatus.valueOf(json.optString("status")) }
            .getOrDefault(if (json.optBoolean("accepted")) RuntimeReceiptStatus.ENQUEUED else RuntimeReceiptStatus.REJECTED),
    )

    private fun parseTranscript(json: JSONObject) = RuntimeTranscriptMessage(
        id = json.optString("id"),
        nodeId = json.optString("nodeId"),
        role = json.optString("role"),
        content = json.optString("content"),
        createdAtMillis = json.optLong("createdAtMillis"),
        metadata = json.optString("metadata"),
    )

    private fun parseNode(json: JSONObject): RuntimeSessionNode {
        val modelJson = json.optJSONObject("model") ?: JSONObject()
        val capabilities = modelJson.optJSONArray("capabilities")?.let { array ->
            buildSet { for (i in 0 until array.length()) add(array.optString(i)) }
        }.orEmpty()
        return RuntimeSessionNode(
            id = json.optString("id"),
            parentId = json.optString("parentId").takeIf { it.isNotBlank() && it != "null" },
            rootId = json.optString("rootId"),
            depth = json.optInt("depth"),
            model = RuntimeModelSnapshot(
                modelJson.optString("provider"), modelJson.optString("model"),
                modelJson.optString("note"), capabilities,
            ),
            delegationMode = runCatching { DelegationMode.valueOf(json.optString("delegationMode")) }
                .getOrDefault(DelegationMode.TRADITIONAL),
            status = runCatching { RuntimeNodeStatus.valueOf(json.optString("status")) }
                .getOrDefault(RuntimeNodeStatus.INACTIVE),
            createdAtMillis = json.optLong("createdAtMillis"),
            updatedAtMillis = json.optLong("updatedAtMillis"),
            leaseUntilMillis = if (json.isNull("leaseUntilMillis")) null else json.optLong("leaseUntilMillis"),
            abnormal = json.optBoolean("abnormal"),
        )
    }

    private fun parseEvent(json: JSONObject) = RuntimeEvent(
        json.optString("id"), json.optString("nodeId"), json.optString("kind"),
        json.optLong("timestampMillis"), json.optString("payload"),
    )

    private fun parseEnvelope(json: JSONObject) = RuntimeEnvelope(
        id = json.optString("id"), fromNodeId = json.optString("fromNodeId"), toNodeId = json.optString("toNodeId"),
        delivery = runCatching { RuntimeDelivery.valueOf(json.optString("delivery")) }.getOrDefault(RuntimeDelivery.QUEUE),
        payload = json.optString("payload"), createdAtMillis = json.optLong("createdAtMillis"), claimed = json.optBoolean("claimed"),
        taskIntent = json.optString("taskIntent"),
        capabilitySnapshot = parseSnapshot(json.optJSONObject("capabilitySnapshot")),
        senderCapabilitySnapshot = parseSnapshot(json.optJSONObject("senderCapabilitySnapshot")),
    )

    private fun textExport(): String = buildString {
        appendLine("session-tree")
        nodes.values.filter { it.parentId == null }.forEach { appendNode(this, it.id, 0) }
        appendLine("events=${events.size} inbox=${inbox.count { !it.claimed }}")
    }

    private fun appendNode(out: StringBuilder, nodeId: String, indent: Int) {
        val node = nodes[nodeId] ?: return
        out.append("  ".repeat(indent)).append(node.id).append(" status=").append(node.status).append(" depth=").append(node.depth).append(" model=").append(node.model.model).appendLine()
        children[nodeId].orEmpty().forEach { appendNode(out, it, indent + 1) }
    }

    private fun isSafeNodeId(value: String): Boolean =
        value.isNotBlank() && value != "." && value != ".." &&
            '/' !in value && '\\' !in value && ".." !in value
    private fun nodeDirectory(node: RuntimeSessionNode): String {
        val path = ArrayDeque<String>()
        var current: RuntimeSessionNode? = node
        while (current != null) {
            path.addFirst(current.id)
            current = current.parentId?.let(nodes::get)
        }
        return path.joinToString("/")
    }

    private fun nodeText(node: RuntimeSessionNode): String = buildString {
        appendLine("id=${node.id}")
        appendLine("parent=${node.parentId}")
        appendLine("root=${node.rootId}")
        appendLine("depth=${node.depth}")
        appendLine("status=${node.status}")
        appendLine("model=${node.model.model}")
        transcripts[node.id].orEmpty().forEach { message ->
            appendLine("[${message.createdAtMillis}] ${message.role}: ${message.content}")
            if (message.metadata.isNotBlank()) appendLine("metadata: ${message.metadata}")
        }
        events.filter { it.nodeId == node.id }.forEach { appendLine("event ${it.timestampMillis} ${it.kind}: ${it.payload}") }
        inbox.filter { it.toNodeId == node.id || it.fromNodeId == node.id }.forEach {
            appendLine("message ${it.id} ${it.fromNodeId}->${it.toNodeId} ${it.delivery} claimed=${it.claimed}: ${it.payload}")
        }
    }


    private fun RuntimeNodeStatus.isActive() = this == RuntimeNodeStatus.STARTING || this == RuntimeNodeStatus.RUNNING || this == RuntimeNodeStatus.WAITING_CHILDREN
    private fun RuntimeNodeStatus.isTerminal() = this == RuntimeNodeStatus.SUCCEEDED || this == RuntimeNodeStatus.FAILED || this == RuntimeNodeStatus.ABORTED || this == RuntimeNodeStatus.ABNORMAL_INTERRUPTION
}
