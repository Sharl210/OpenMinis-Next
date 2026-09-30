package com.openminis.app.ui.sessions

import android.content.Context
import com.openminis.app.data.SessionDeletionRetryStore
import com.openminis.app.data.SessionSubtreeDeletionPipeline
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.SessionSubtreeDeletionPlan
import com.openminis.app.data.repository.sessionSubtreeDeletionPlan
import com.openminis.app.data.storage.MediaStore
import com.openminis.app.feature.runtime.RuntimeCommunicationFileStore
import com.openminis.app.feature.runtime.RuntimeCommunicationRepository
import com.openminis.app.feature.runtime.RuntimeSessionCoordinator
import com.openminis.app.feature.runtime.RuntimeSessionNode
import com.openminis.app.ui.chat.ChatViewModelStore
import java.io.File

/**
 * The one place a *user-initiated* conversation delete is wired to its cleanup
 * steps.
 *
 * Three entry points (delete one, delete a selection, delete a group with its
 * members) plus the retry path all have to run exactly these steps; spelling
 * them out per call site is how the recursive half went missing in the first
 * place. It also gives the tests a way to exercise the real wiring rather than a
 * copy of it.
 *
 * The two batched steps are the ones that had to see the whole subtree:
 *
 *  - `chat` deletes every row in one transaction
 *    ([ChatRepository.deleteSessionSubtree]), because a half-deleted subtree is
 *    exactly the orphan state this feature exists to avoid.
 *  - `runtime_tree` removes the tree nodes so the runtime never keeps pointing
 *    at a conversation that no longer exists. It re-resolves the node ids from
 *    the plan's roots at call time: a retry after the chat rows are gone still
 *    has nodes to clean, and a tree read failure has to surface as *this step
 *    failing* rather than as a silent "nothing to purge".
 */
internal fun sessionSubtreeDeletionPipeline(
    context: Context,
    chatRepository: ChatRepository,
    runtimeCoordinator: () -> RuntimeSessionCoordinator = { RuntimeSessionCoordinator.open(context) },
    retryStore: SessionDeletionRetryStore = SessionDeletionRetryStore.open(context),
): SessionSubtreeDeletionPipeline {
    val mediaStore = MediaStore(context)
    return SessionSubtreeDeletionPipeline(
        retryStore = retryStore,
        batchSteps = listOf(
            SessionSubtreeDeletionPipeline.BatchStep("runtime_tree") { plan ->
                purgeRuntimeSubtrees(runtimeCoordinator(), plan)
            },
            SessionSubtreeDeletionPipeline.BatchStep("chat") { plan ->
                deleteChatRows(chatRepository, plan.sessionIds)
            },
        ),
        perSessionSteps = listOf(
            SessionSubtreeDeletionPipeline.PerSessionStep("media") { sessionId ->
                mediaStore.deleteSessionMedia(sessionId)
            },
            SessionSubtreeDeletionPipeline.PerSessionStep("permissions") { sessionId ->
                com.openminis.app.offload.OffloadPermissionManager.clearSessionGrants(sessionId)
            },
            SessionSubtreeDeletionPipeline.PerSessionStep("goal") { sessionId ->
                com.openminis.app.feature.runtime.GoalRuntimeStore.open(context, sessionId).delete()
            },
            SessionSubtreeDeletionPipeline.PerSessionStep("communication") { sessionId ->
                RuntimeCommunicationRepository(
                    RuntimeCommunicationFileStore(File(context.filesDir, "runtime/communication-metadata.json")),
                ).deleteForSession(sessionId)
                Unit
            },
            SessionSubtreeDeletionPipeline.PerSessionStep("view_model") { sessionId ->
                ChatViewModelStore.release(sessionId)
            },
            SessionSubtreeDeletionPipeline.PerSessionStep("badge") { sessionId ->
                com.openminis.app.service.SessionBadgeStore.clear(sessionId)
            },
        ),
    )
}

/** Where [RuntimeSessionCoordinator.open] keeps the tree this path purges. */
internal const val RUNTIME_TREE_RELATIVE_PATH: String = "runtime/session-tree.json"

/**
 * Whether the runtime tree file holds *something*.
 *
 * Used only to tell two cases apart that look identical through
 * [RuntimeSessionCoordinator.topologySnapshot]: a tree that genuinely has no
 * nodes yet, and a read that silently degraded (the store swallows a malformed
 * file and leaves the tree empty). Those need different words in the log —
 * "nothing to resolve" versus "sub-agents could not be resolved".
 *
 * Deliberately a cheap existence/size probe rather than a parse: parsing here
 * would be a second, divergent reader of a format the store owns, and the cost
 * of being wrong is a log line, not a decision.
 */
internal fun runtimeTreeFileHasContent(context: Context): Boolean =
    File(context.filesDir, RUNTIME_TREE_RELATIVE_PATH).let { it.isFile && it.length() > 0L }

/**
 * Resolve the delete set for [rootIds] from the runtime topology plus the chat
 * database.
 *
 * Two passes, because existence needs a database read: the first pass returns
 * every candidate id (targets plus descendants, including runtime nodes whose
 * session never reached the database), the second drops the ids that have no
 * row. [ChatRepository.deleteSessionSubtree] refuses a batch containing an
 * unknown id, so a stale runtime node must not reach it. Shared with the tests
 * so they exercise the real resolution rather than a copy.
 */
internal suspend fun resolveSessionSubtreeDeletionPlan(
    chatRepository: ChatRepository,
    nodes: List<RuntimeSessionNode>,
    rootIds: Collection<String>,
): SessionSubtreeDeletionPlan {
    val candidates = sessionSubtreeDeletionPlan(nodes, rootIds, sessionExists = { true })
    val live = chatRepository.existingSessionIds(candidates.sessionIds).toHashSet()
    return sessionSubtreeDeletionPlan(nodes, rootIds, sessionExists = live::contains)
}

private fun purgeRuntimeSubtrees(
    coordinator: RuntimeSessionCoordinator,
    plan: SessionSubtreeDeletionPlan,
) {
    val nodes = coordinator.topologySnapshot().nodes
    val nodeIds = sessionSubtreeDeletionPlan(nodes, plan.rootSessionIds, sessionExists = { true }).runtimeNodeIds
    // Nothing to purge is the normal case for a conversation that predates the
    // runtime tree, so this cannot be an error. The *dangerous* variant — a tree
    // file full of nodes that produced none — is detected once, where the tree is
    // read (see `SessionListViewModel.deleteSessionSubtrees`), because only there
    // is the file itself in scope.
    if (nodeIds.isEmpty()) return
    val receipt = coordinator.purgeSubtrees(nodeIds, "conversation deleted from the session list")
        ?: error("runtime tree could not be persisted")
    android.util.Log.i(
        "SessionSubtreeDelete",
        "runtime subtree purge: removed=${receipt.removedNodeIds.size} aborted=${receipt.abortedNodeIds.size}",
    )
}

/**
 * The chat rows of the whole subtree go in one transaction.
 *
 * Existence is re-checked immediately before, because
 * [ChatRepository.deleteSessionSubtree] refuses the entire batch when one id has
 * already been removed elsewhere. This keeps the ids handed over to the
 * transaction *all* real, which matters because the ids arriving here are not
 * all freshly resolved: `SessionSubtreeDeletionPipeline` also feeds in ids
 * carried over from the retry ledger, whose chat rows a previous attempt may
 * already have deleted. Without the filter those stale ids would reach the
 * transaction — and `ChatDao.deleteSessionSubtree` aborts the **whole** batch on
 * an id it cannot see (`check(countSessions(chunk) == chunk.size)`), so a single
 * already-deleted id would roll back the rows that are still there and leave the
 * `chat` debt on every id in the subtree.
 *
 * That is a race, not a degradation: the step fails, says so, and the debt stays
 * until a later attempt succeeds. Nothing here deletes "the rows that are still
 * there" while claiming the batch succeeded.
 */
private suspend fun deleteChatRows(chatRepository: ChatRepository, sessionIds: List<String>) {
    if (sessionIds.isEmpty()) return
    val live = chatRepository.existingSessionIds(sessionIds)
    if (live.isEmpty()) return
    chatRepository.deleteSessionSubtree(live)
}
