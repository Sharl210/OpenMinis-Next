package com.openminis.app.feature.runtime

import android.content.Context
import android.util.Log

/**
 * Application-facing adapter around the durable runtime tree.
 *
 * ChatViewModel owns provider calls; this coordinator owns orchestration records,
 * delegation mode, and parent/child mailboxes.
 */
class RuntimeSessionCoordinator private constructor(
    private val store: RuntimeTreeStore,
) {
    private val activeRuntimeIds = LinkedHashMap<String, String>()
    private var generation = 0L

    fun communicationGateway(repository: RuntimeCommunicationRepository): RuntimeCommunicationGateway =
        RuntimeCommunicationGateway(
            repository = repository,
            authorizedSend = { actor, target, payload, delivery -> send(actor, target, payload, delivery) },
        )

    @Synchronized
    fun startRoot(
        sessionId: String,
        model: RuntimeModelSnapshot = DEFAULT_MODEL,
    ): String? {
        if (sessionId.isBlank()) return null
        val existingActive = activeRuntimeIds[sessionId]
        if (existingActive != null) {
            store.update { heartbeat(existingActive) }
            return existingActive
        }
        var runtimeId: String? = null
        val persisted = store.update {
            val existing = node(sessionId)
            val candidate = when {
                existing == null -> createRoot(sessionId, model)
                existing.status == RuntimeNodeStatus.STARTING ||
                    existing.status == RuntimeNodeStatus.RUNNING ||
                    existing.status == RuntimeNodeStatus.WAITING_CHILDREN -> existing
                else -> createRoot("$sessionId#run-${++generation}", model)
            }
            runtimeId = candidate.id
            start(candidate.id)
            heartbeat(candidate.id)
        }
        if (!persisted) return null
        runtimeId?.let { activeRuntimeIds[sessionId] = it }
        return runtimeId
    }

    @Synchronized
    fun finishRoot(sessionId: String, failed: Boolean = false) {
        val runtimeId = activeRuntimeIds.remove(sessionId) ?: sessionId
        store.update { complete(runtimeId, failed = failed) }
    }

    @Synchronized
    fun startChild(
        parentSessionId: String,
        childSessionId: String,
        model: RuntimeModelSnapshot = DEFAULT_MODEL,
        delegationMode: DelegationMode? = null,
    ): Boolean {
        if (parentSessionId.isBlank() || childSessionId.isBlank()) return false
        val parentRuntimeId = activeRuntimeIds[parentSessionId] ?: parentSessionId
        var childRuntimeId: String? = null
        val persisted = store.update {
            val parent = node(parentRuntimeId) ?: return@update
            val existing = node(childSessionId)
            val child = existing ?: createChild(
                parent.id,
                childSessionId,
                model,
                delegationMode = delegationMode ?: parent.delegationMode,
            ).getOrNull()
            if (child != null) {
                childRuntimeId = child.id
                start(child.id)
                heartbeat(child.id)
            }
        }
        if (persisted && childRuntimeId != null) activeRuntimeIds[childSessionId] = childRuntimeId!!
        return persisted && childRuntimeId != null
    }

    @Synchronized
    fun finishChild(
        childSessionId: String,
        failed: Boolean = false,
        report: RuntimeStopReport? = null,
    ) {
        val runtimeId = activeRuntimeIds.remove(childSessionId) ?: childSessionId
        store.update { complete(runtimeId, failed = failed, report = report) }
    }

    @Synchronized
    fun waitForChildren(sessionId: String): Boolean {
        val runtimeId = activeRuntimeIds[sessionId] ?: sessionId
        return store.update { waitForChildren(runtimeId) }
    }

    @Synchronized
    fun resume(sessionId: String): Boolean {
        val runtimeId = activeRuntimeIds[sessionId] ?: sessionId
        return store.update { resume(runtimeId) }
    }

    /** Start a child with a captured mode/model snapshot. */
    @Synchronized
    fun delegate(
        parentSessionId: String,
        childSessionId: String,
        request: RuntimeDelegationRequest,
        model: RuntimeModelSnapshot,
    ): Boolean {
        val parentRuntimeId = activeRuntimeIds[parentSessionId] ?: parentSessionId
        val configured = if (request.mode == DelegationMode.TEAM) {
            store.update {
                val parent = node(parentRuntimeId) ?: return@update
                setDelegationMode(parent.id, DelegationMode.TEAM)
            }
        } else {
            true
        }
        if (!configured) return false
        return startChild(
            parentSessionId,
            childSessionId,
            model.copy(note = request.note, capabilities = request.capabilities),
            delegationMode = request.mode,
        )
    }

    @Synchronized
    fun send(
        fromSessionId: String,
        toSessionId: String,
        payload: String,
        delivery: RuntimeDelivery,
    ): RuntimeDeliveryReceipt {
        val from = activeRuntimeIds[fromSessionId] ?: fromSessionId
        val to = activeRuntimeIds[toSessionId] ?: toSessionId
        var receipt = RuntimeDeliveryReceipt(false, null, null, "unknown node")
        val persisted = store.update { receipt = send(from, to, payload, delivery) }
        return if (persisted) receipt else receipt.copy(accepted = false, reason = "runtime tree persistence failed")
    }

    /** Alias used by callers that want an explicit durable-delivery name. */
    @Synchronized
    fun deliver(
        fromSessionId: String,
        toSessionId: String,
        payload: String,
        delivery: RuntimeDelivery,
    ): RuntimeDeliveryReceipt = send(fromSessionId, toSessionId, payload, delivery)

    /** Delete a completed descendant subtree while preserving initiator/executor provenance. */
    @Synchronized
    fun deleteSubtree(
        initiatorSessionId: String,
        executorSessionId: String,
        targetSessionId: String,
        rootId: String? = null,
        operationId: String? = null,
        idempotencyKey: String? = null,
    ): DeleteSubtreeReceipt {
        val initiator = activeRuntimeIds[initiatorSessionId] ?: initiatorSessionId
        val executor = activeRuntimeIds[executorSessionId] ?: executorSessionId
        val target = activeRuntimeIds[targetSessionId] ?: targetSessionId
        val operation = operationId ?: java.util.UUID.randomUUID().toString()
        val key = idempotencyKey ?: operation
        var receipt: DeleteSubtreeReceipt? = null
        val persisted = store.update {
            receipt = deleteSubtree(
                DeleteSubtreeRequest(
                    initiatorNodeId = initiator,
                    executorNodeId = executor,
                    targetNodeId = target,
                    rootId = rootId,
                    operationId = operation,
                    idempotencyKey = key,
                ),
            )
        }
        return if (persisted) {
            receipt ?: DeleteSubtreeReceipt(
                operationId = operation,
                idempotencyKey = key,
                result = DeleteSubtreeResult.REJECTED,
                initiatorNodeId = initiator,
                executorNodeId = executor,
                targetNodeId = target,
                rootId = rootId,
                oldParentId = null,
                affectedNodeIds = emptyList(),
                reason = "runtime delete produced no receipt",
                createdAtMillis = System.currentTimeMillis(),
            )
        } else {
            DeleteSubtreeReceipt(
                operationId = operation,
                idempotencyKey = key,
                result = DeleteSubtreeResult.REJECTED,
                initiatorNodeId = initiator,
                executorNodeId = executor,
                targetNodeId = target,
                rootId = rootId,
                oldParentId = null,
                affectedNodeIds = emptyList(),
                reason = "runtime tree persistence failed",
                createdAtMillis = System.currentTimeMillis(),
            )
        }
    }
    @Synchronized
    fun stopDescendant(
        actorSessionId: String,
        targetSessionId: String,
        rootId: String? = null,
        reason: String = "",
        operationId: String? = null,
        idempotencyKey: String? = null,
    ): RuntimeStopReceipt {
        val actor = activeRuntimeIds[actorSessionId] ?: actorSessionId
        val target = activeRuntimeIds[targetSessionId] ?: targetSessionId
        val operation = operationId ?: java.util.UUID.randomUUID().toString()
        val key = idempotencyKey ?: operation
        var receipt: RuntimeStopReceipt? = null
        val persisted = store.update {
            receipt = stopDescendant(
                RuntimeStopRequest(
                    actorNodeId = actor,
                    targetNodeId = target,
                    rootId = rootId,
                    reason = reason,
                    operationId = operation,
                    idempotencyKey = key,
                ),
            )
        }
        return receipt ?: RuntimeStopReceipt(
            operationId = operationId.orEmpty(),
            idempotencyKey = idempotencyKey ?: operationId.orEmpty(),
            type = RuntimeControlOperationType.STOP_DESCENDANT,
            actorNodeId = actor,
            targetNodeId = target,
            rootId = rootId,
            accepted = false,
            stateBefore = null,
            stateAfter = null,
            affectedNodeIds = emptyList(),
            actorCapabilitySnapshot = null,
            targetCapabilitySnapshot = null,
            createdAtMillis = System.currentTimeMillis(),
            reason = if (persisted) "runtime stop produced no receipt" else "runtime tree persistence failed",
        )
    }


    @Synchronized
    fun export(format: String, zip: Boolean = false): RuntimeExport =
        if (zip) store.snapshot().exportZip(format) else store.snapshot().export(format)

    companion object {
        private const val TAG = "RuntimeCoordinator"
        private val DEFAULT_MODEL = RuntimeModelSnapshot(provider = "unknown", model = "active-session")

        fun open(context: Context, config: RuntimeTreeConfig = RuntimeTreeConfig()): RuntimeSessionCoordinator =
            RuntimeSessionCoordinator(RuntimeTreeStore.open(context, config)).also { coordinator ->
                val stale = coordinator.store.reconcile()
                if (stale.isNotEmpty()) Log.w(TAG, "Recovered ${stale.size} stale runtime node(s): ${stale.joinToString()}")
            }
    }
}
