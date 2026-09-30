package com.openminis.app.data.repository

import android.content.Context
import com.openminis.app.feature.runtime.RuntimeSessionCoordinator

/**
 * Live `parentOf` lookup for [resolveInheritedEnabled], read from the shared
 * runtime tree — the same tree that records which conversation spawned which
 * sub-agent session (`SessionActivityTracker.registerChild` → `startChild`
 * creates the child runtime node *with the child session id as its node id*, so
 * no extra mapping is needed).
 *
 * Deliberately uncached. The whole point of resolving the chain at read time is
 * that an ancestor's toggle is visible to a descendant's very next read; a
 * short-lived topology cache would reintroduce a (small) staleness window in
 * exactly the case the feature exists for — a child spawned moments ago reading
 * its switches. The read itself is cheap: [RuntimeSessionCoordinator] is the
 * process-wide shared instance, so this copies an in-memory node list (tens of
 * entries) rather than re-parsing the persisted JSON file, and it is only
 * consulted on an explicit enablement lookup — not on every frame.
 */
internal class SessionTreeParentChain(context: Context) {

    private val appContext = context.applicationContext

    fun parentOf(sessionId: String): String? = parentIndex()[sessionId]

    /**
     * A tree read must never break enablement resolution: on failure the walk
     * sees "no parent" and falls back to the global default, which is the
     * required safe degradation (an unknown link must not silently disable
     * everything, nor resurrect an ancestor's off state).
     */
    private fun parentIndex(): Map<String, String> = runCatching {
        sessionParentIndex(RuntimeSessionCoordinator.open(appContext).topologySnapshot().nodes)
    }.getOrDefault(emptyMap())
}
