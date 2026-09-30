package com.openminis.app.data.repository

import com.openminis.app.feature.runtime.RuntimeSessionNode
import com.openminis.app.feature.runtime.RuntimeTopologySnapshot

/**
 * What a user-initiated conversation delete has to take with it.
 *
 * Deleting a conversation is a *recursive* delete: a sub-agent session only
 * exists because an ancestor conversation spawned it, so removing the ancestor
 * while leaving its descendants behind would leave orphan rows in the session
 * list (and orphan nodes in the runtime tree) that no longer have a parent to
 * belong to.
 *
 * [sessionIds] is what the chat database has to lose, [runtimeNodeIds] is what
 * the runtime tree has to lose. They are computed together, from one topology
 * read, because both halves describe the same subtree — computing them
 * separately would let the two drift apart mid-delete.
 */
internal data class SessionSubtreeDeletionPlan(
    /**
     * The conversations the caller actually asked to delete. Kept so the runtime
     * tree can be re-resolved on a retry even after the chat rows are gone —
     * `sessionIds` is empty by then, but the tree nodes may still be there.
     */
    val rootSessionIds: List<String>,
    val sessionIds: List<String>,
    val runtimeNodeIds: List<String>,
) {
    val isEmpty: Boolean get() = sessionIds.isEmpty() && runtimeNodeIds.isEmpty()
}

/**
 * Resolve the delete set for [targetSessionIds] out of the runtime tree.
 *
 * The parent/child link between conversations lives **only** in the runtime tree
 * (`ChatSessionEntity` has no parent column), so the topology is the one
 * authority here. The traversal reuses
 * [RuntimeTopologySnapshot.descendantsForChatSession] rather than
 * re-implementing a walk of its own, and adds the two things that API
 * structurally cannot express:
 *
 *  - **A conversation's own node(s).** `descendantsForChatSession` returns
 *    *strict* descendants, so the `#run-<n>` root that
 *    `RuntimeSessionTree.createRoot` makes for every run is not in its result.
 *  - **Descendants whose parent link was severed.** See below.
 *
 * ### Why the walk is a fixed point over *session identities*, not over the targets
 *
 * A conversation that is opened again gets a **brand-new root node**
 * (`<sessionId>#run-<n>`) with `parentId = null` and a birth chain holding only
 * itself (`RuntimeSessionCoordinator.startRoot`). Its children then hang off
 * that new root — so `C.parentId == "B#run-2"`, whose parent is `null`.
 *
 * Walking from the original target alone therefore loses that whole branch: a
 * child of a re-run sub-agent has no `parentId` path back to the ancestor
 * conversation. Deleting the ancestor would delete the sub-agent's *old* node
 * (still attached to the ancestor) and leave the sub-agent's **new** subtree
 * behind — the exact orphan state this function exists to prevent.
 *
 * The fix is to treat every discovered **session identity** as a fresh root and
 * keep going until nothing new appears. Expanding identity `B` picks up all of
 * `B`'s nodes (its old node *and* `B#run-2`), and asking
 * `descendantsForChatSession("B")` from there finds `C`, because that API
 * already treats every `B#run-<n>` as one of `B`'s roots. The closure is what
 * makes a re-run in the middle of a subtree harmless.
 *
 * Order is breadth-first from the targets, every id appears once, so a batch
 * that selects both a conversation and one of its own descendants
 * ("[A, B] where B is A's child") deletes each id exactly once.
 *
 * @param sessionExists filter for ids that must still be deletable. Pass
 *   `{ true }` to get the *candidate* set (used to ask the database which of
 *   them are real), then call again with the surviving ids — see
 *   `resolveSessionSubtreeDeletionPlan` in
 *   `ui/sessions/SessionSubtreeDeletionWiring.kt`.
 */
internal fun sessionSubtreeDeletionPlan(
    nodes: List<RuntimeSessionNode>,
    targetSessionIds: Collection<String>,
    sessionExists: (String) -> Boolean = { true },
): SessionSubtreeDeletionPlan {
    val targets = targetSessionIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    if (targets.isEmpty()) return SessionSubtreeDeletionPlan(emptyList(), emptyList(), emptyList())
    // The snapshot type owns the traversal; only `nodes` is read from it.
    val topology = RuntimeTopologySnapshot(nodes = nodes, edges = emptyList(), subscriptions = emptyList())
    val nodesByIdentity: Map<String, List<String>> = nodes.groupBy({ nodeIdentity(it.id) }, { it.id })
    val sessionIds = LinkedHashSet<String>()
    val runtimeNodeIds = LinkedHashSet<String>()
    val visited = HashSet<String>()
    val queue = ArrayDeque(targets)

    fun collect(node: RuntimeSessionNode) {
        runtimeNodeIds += node.id
        val identity = nodeIdentity(node.id)
        if (sessionExists(identity)) sessionIds += identity
        if (identity !in visited) queue += identity
    }

    while (queue.isNotEmpty()) {
        val session = queue.removeFirst()
        if (!visited.add(session)) continue
        if (sessionExists(session)) sessionIds += session
        // Every node of this conversation first: its ordinary node, any `#run-N`
        // root, and any node a repeat run left behind.
        nodesByIdentity[session].orEmpty().forEach { runtimeNodeIds += it }
        // Descendants reachable through the parent chain, from any of the
        // conversation's roots (`descendantsForChatSession` accepts all runs).
        topology.descendantsForChatSession(session).forEach(::collect)
        // Descendants whose only surviving link is the immutable birth chain:
        // the parent-chain walk stops at the first link whose parent node is no
        // longer in the snapshot, and the chain cannot lose a link that way.
        nodes.filter { node ->
            nodeIdentity(node.id) != session &&
                node.birthChain.any { nodeIdentity(it) == session }
        }.forEach(::collect)
    }
    return SessionSubtreeDeletionPlan(targets, sessionIds.toList(), runtimeNodeIds.toList())
}

/**
 * A runtime node id folded onto the conversation it belongs to:
 * `<sessionId>#run-<n>` → `<sessionId>`, everything else unchanged.
 *
 * Uses the shared [canonicalRuntimeSessionId] so the delete set, the session
 * switch inheritance and the runtime tree all agree on what "the same
 * conversation" means — and so a node whose suffix merely *looks* like a run
 * marker (`B#run-abc`) is not silently folded onto `B`.
 */
internal fun nodeIdentity(runtimeNodeId: String): String = canonicalRuntimeSessionId(runtimeNodeId)
