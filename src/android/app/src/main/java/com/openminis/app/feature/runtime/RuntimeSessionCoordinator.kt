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
    fun finishChild(childSessionId: String, failed: Boolean = false) {
        val runtimeId = activeRuntimeIds.remove(childSessionId) ?: childSessionId
        store.update { complete(runtimeId, failed = failed) }
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

    @Synchronized
    fun reconcile(): List<String> = store.reconcile()

    @Synchronized
    fun export(format: String, zip: Boolean = false): RuntimeExport =
        if (zip) store.snapshot().exportZip(format) else store.snapshot().export(format)

    companion object {
        private const val TAG = "RuntimeCoordinator"
        private val DEFAULT_MODEL = RuntimeModelSnapshot(provider = "unknown", model = "active-session")

        fun open(context: Context, config: RuntimeTreeConfig = RuntimeTreeConfig()): RuntimeSessionCoordinator =
            RuntimeSessionCoordinator(RuntimeTreeStore.open(context, config)).also { coordinator ->
                val stale = coordinator.reconcile()
                if (stale.isNotEmpty()) Log.w(TAG, "Recovered ${stale.size} stale runtime node(s): ${stale.joinToString()}")
            }
    }
}
