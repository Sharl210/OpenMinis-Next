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
)

data class RuntimeTreeConfig(
    val maxDepth: Int = 2,
    val maxParallelSubagents: Int = 5,
    val leaseMillis: Long = 120_000L,
    var mode: DelegationMode = DelegationMode.TRADITIONAL,
) {
    init {
        require(maxDepth in 0..2) { "maxDepth must be between 0 and 2" }
        require(maxParallelSubagents > 0) { "maxParallelSubagents must be positive" }
        require(leaseMillis > 0) { "leaseMillis must be positive" }
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
    private val topologyEdges = LinkedHashMap<String, RuntimeTopologyEdge>()
    private val subscriptions = LinkedHashMap<String, RuntimeSubscription>()
    private val rootIds = LinkedHashSet<String>()
    /** Legacy representative root retained for source and JSON compatibility. */
    private var rootId: String? = null

    @Synchronized
    fun createRoot(
        sessionId: String = UUID.randomUUID().toString(),
        model: RuntimeModelSnapshot,
        delegationMode: DelegationMode = config.mode,
    ): RuntimeSessionNode {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        nodes[sessionId]?.let { existing ->
            require(existing.parentId == null) { "sessionId belongs to a child node" }
            return existing
        }
        val now = clock()
        val node = RuntimeSessionNode(
            sessionId,
            null,
            sessionId,
            0,
            model,
            delegationMode = delegationMode,
            createdAtMillis = now,
            updatedAtMillis = now,
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
    ): Result<RuntimeSessionNode> {
        val parent = nodes[parentId] ?: return Result.failure(IllegalArgumentException("unknown parent"))
        if (config.maxDepth == 0 || parent.depth + 1 > config.maxDepth) {
            return Result.failure(IllegalStateException("delegation depth limit reached"))
        }
        val runningChildren = nodes.values.count { it.depth > 0 && it.status.isActive() }
        if (runningChildren >= config.maxParallelSubagents) {
            return Result.failure(IllegalStateException("parallel subagent limit reached"))
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
        record(root, "delegation_mode_changed", clock(), mode.name)
        return true
    }

    @Synchronized
    fun start(nodeId: String): Boolean = updateStatus(nodeId, RuntimeNodeStatus.RUNNING, "started")

    @Synchronized
    fun heartbeat(nodeId: String, nowMillis: Long = clock()): Boolean {
        val node = nodes[nodeId] ?: return false
        if (!node.status.isActive()) return false
        nodes[nodeId] = node.copy(updatedAtMillis = nowMillis, leaseUntilMillis = nowMillis + config.leaseMillis)
        record(nodeId, "heartbeat", nowMillis)
        return true
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
    ): RuntimeDeliveryReceipt {
        val from = nodes[fromNodeId]
        val to = nodes[toNodeId]
        if (from == null || to == null) return RuntimeDeliveryReceipt(false, null, null, "unknown node")
        if (payload.isBlank()) return RuntimeDeliveryReceipt(false, null, null, "payload must not be blank")
        if (delivery == RuntimeDelivery.TEAM_PEER) {
            if (from.delegationMode != DelegationMode.TEAM || to.delegationMode != DelegationMode.TEAM || from.rootId != to.rootId) {
                return RuntimeDeliveryReceipt(false, null, null, "Team mode is disabled")
            }
            val sameTeam = from.parentId == to.parentId || from.id == from.rootId || to.id == to.rootId
            if (!sameTeam) return RuntimeDeliveryReceipt(false, null, null, "nodes are not Team peers")
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
            createdAtMillis = clock(),
        )
        inbox += message
        record(toNodeId, if (effective == RuntimeDelivery.QUEUE) "message_queued" else "message_steered", message.createdAtMillis, message.id)
        return RuntimeDeliveryReceipt(true, message.id, effective, if (effective != delivery) "delivery window closed; queued" else null)
    }

    @Synchronized
    fun claimNextStep(nodeId: String): RuntimeEnvelope? = claim(nodeId) { it.delivery == RuntimeDelivery.STEER || it.delivery == RuntimeDelivery.TEAM_PEER }

    @Synchronized
    fun claimNextTurn(nodeId: String): RuntimeEnvelope? = claim(nodeId) { it.delivery == RuntimeDelivery.QUEUE }

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
        })
        out.put("nodes", JSONArray().apply { nodes.values.forEach { put(nodeJson(it)) } })
        out.put("events", JSONArray().apply { events.forEach { put(eventJson(it)) } })
        out.put("inbox", JSONArray().apply { inbox.forEach { put(envelopeJson(it)) } })
        return out.toString()
    }

    @Synchronized
    fun restoreJson(raw: String): Boolean {
        return runCatching {
            val json = JSONObject(raw)
            nodes.clear(); children.clear(); events.clear(); inbox.clear()
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
                val name = "children/${node.rootId}/${node.id}/node.${if (format == "json") "json" else "txt"}"
                zip.putNextEntry(ZipEntry(name))
                if (format == "json") zip.write(nodeJson(node).toString().toByteArray(StandardCharsets.UTF_8))
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
    ): RuntimeTopologyEdge {
        val edge = RuntimeTopologyEdge(
            id = UUID.randomUUID().toString(),
            fromNodeId = fromNodeId,
            toNodeId = toNodeId,
            kind = kind,
            permissions = permissions.toSet(),
            createdAtMillis = createdAtMillis,
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
        record(nodeId, "message_claimed", clock(), claimed.id)
        return claimed
    }

    private fun descendantsOf(nodeId: String): List<String> = children[nodeId].orEmpty().flatMap { listOf(it) + descendantsOf(it) }

    private fun record(nodeId: String, kind: String, timestamp: Long, payload: String = "") {
        events += RuntimeEvent(UUID.randomUUID().toString(), nodeId, kind, timestamp, payload)
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
        put("id", message.id); put("fromNodeId", message.fromNodeId); put("toNodeId", message.toNodeId); put("delivery", message.delivery.name); put("payload", message.payload); put("createdAtMillis", message.createdAtMillis); put("claimed", message.claimed)
    }

    private fun parseNode(json: JSONObject): RuntimeSessionNode {
        val modelJson = json.optJSONObject("model") ?: JSONObject()
        val capabilities = modelJson.optJSONArray("capabilities")?.let { array -> buildSet { for (i in 0 until array.length()) add(array.optString(i)) } }.orEmpty()
        return RuntimeSessionNode(
            id = json.optString("id"), parentId = json.optString("parentId").takeIf { it.isNotBlank() && it != "null" }, rootId = json.optString("rootId"), depth = json.optInt("depth"),
            model = RuntimeModelSnapshot(modelJson.optString("provider"), modelJson.optString("model"), modelJson.optString("note"), capabilities),
            delegationMode = runCatching { DelegationMode.valueOf(json.optString("delegationMode")) }.getOrDefault(DelegationMode.TRADITIONAL),
            status = runCatching { RuntimeNodeStatus.valueOf(json.optString("status")) }.getOrDefault(RuntimeNodeStatus.INACTIVE),
            createdAtMillis = json.optLong("createdAtMillis"), updatedAtMillis = json.optLong("updatedAtMillis"), leaseUntilMillis = if (json.isNull("leaseUntilMillis")) null else json.optLong("leaseUntilMillis"), abnormal = json.optBoolean("abnormal"),
        )
    }

    private fun parseEvent(json: JSONObject) = RuntimeEvent(json.optString("id"), json.optString("nodeId"), json.optString("kind"), json.optLong("timestampMillis"), json.optString("payload"))

    private fun parseEnvelope(json: JSONObject) = RuntimeEnvelope(json.optString("id"), json.optString("fromNodeId"), json.optString("toNodeId"), runCatching { RuntimeDelivery.valueOf(json.optString("delivery")) }.getOrDefault(RuntimeDelivery.QUEUE), json.optString("payload"), json.optLong("createdAtMillis"), json.optBoolean("claimed"))

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

    private fun nodeText(node: RuntimeSessionNode): String = "id=${node.id}\nparent=${node.parentId}\nroot=${node.rootId}\ndepth=${node.depth}\nstatus=${node.status}\nmodel=${node.model.model}\n"

    private fun RuntimeNodeStatus.isActive() = this == RuntimeNodeStatus.STARTING || this == RuntimeNodeStatus.RUNNING || this == RuntimeNodeStatus.WAITING_CHILDREN
    private fun RuntimeNodeStatus.isTerminal() = this == RuntimeNodeStatus.SUCCEEDED || this == RuntimeNodeStatus.FAILED || this == RuntimeNodeStatus.ABORTED || this == RuntimeNodeStatus.ABNORMAL_INTERRUPTION
}
