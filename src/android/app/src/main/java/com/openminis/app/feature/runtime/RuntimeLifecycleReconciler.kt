package com.openminis.app.feature.runtime

import com.openminis.app.data.NextDataRoot
import java.io.File
import java.nio.charset.StandardCharsets

/** A read-only validation and lease-reconciliation boundary for runtime recovery. */
class RuntimeLifecycleReconciler(
    private val tree: RuntimeSessionTree,
    private val runtimeTreeFile: File,
    private val persist: () -> Boolean,
) {
    fun reconcile(nowMillis: Long): RuntimeLifecycleRecovery {
        if (runtimeTreeFile.isFile) {
            val raw = runCatching { runtimeTreeFile.readText(StandardCharsets.UTF_8) }.getOrNull()
                ?: return corruptBaseline()
            if (!isValidTreeJson(raw)) return corruptBaseline()
            if (!tree.restoreJson(raw)) return corruptBaseline()
        }

        val stale = tree.reconcileLeases(nowMillis)
        val snapshot = tree.topology().nodes
        val active = snapshot.filter { it.status.isRecoveryActive() }
        val persisted = if (stale.isNotEmpty()) persist() else true
        return RuntimeLifecycleRecovery(
            state = if (persisted) RuntimeLifecycleRecoveryState.RECONCILED else RuntimeLifecycleRecoveryState.PERSIST_FAILED,
            activeRootIds = active.filter { it.parentId == null }.map { it.id },
            activeChildIds = active.filter { it.parentId != null }.map { it.id },
            staleNodeIds = stale,
            persisted = persisted,
        )
    }

    private fun isValidTreeJson(raw: String): Boolean = runCatching {
        RuntimeSessionTree().restoreJson(raw)
    }.getOrDefault(false)

    private fun corruptBaseline() = RuntimeLifecycleRecovery(
        state = RuntimeLifecycleRecoveryState.CORRUPT_BASELINE_PRESERVED,
        activeRootIds = emptyList(),
        activeChildIds = emptyList(),
        staleNodeIds = emptyList(),
        persisted = false,
    )

    private fun RuntimeNodeStatus.isRecoveryActive(): Boolean = this == RuntimeNodeStatus.STARTING ||
        this == RuntimeNodeStatus.RUNNING ||
        this == RuntimeNodeStatus.WAITING_CHILDREN ||
        this == RuntimeNodeStatus.STOP_REQUESTED

    companion object {
        fun forNext(
            filesDir: File,
            tree: RuntimeSessionTree,
            persist: () -> Boolean,
        ): RuntimeLifecycleReconciler = RuntimeLifecycleReconciler(
            tree = tree,
            runtimeTreeFile = NextDataRoot.runtimeTreeFile(filesDir),
            persist = persist,
        )
    }
}

enum class RuntimeLifecycleRecoveryState {
    RECONCILED,
    CORRUPT_BASELINE_PRESERVED,
    PERSIST_FAILED,
}

data class RuntimeLifecycleRecovery(
    val state: RuntimeLifecycleRecoveryState,
    val activeRootIds: List<String>,
    val activeChildIds: List<String>,
    val staleNodeIds: List<String>,
    val persisted: Boolean,
)
