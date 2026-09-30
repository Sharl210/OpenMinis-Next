package com.openminis.app.feature.runtime

import android.content.Context
import android.util.Log
import com.openminis.app.data.NextDataRoot
import java.io.File

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
            capabilityVersion = { sessionId -> capabilityVersion(sessionId) },
        )

    @Synchronized
    fun queryCommunication(
        repository: RuntimeCommunicationRepository,
        actorSessionId: String,
        query: RuntimeCommunicationQuery,
    ): RuntimeCommunicationQueryResult {
        val actor = activeRuntimeIds[actorSessionId] ?: actorSessionId
        return communicationGateway(repository).query(actor, query)
    }

    @Synchronized
    fun communicationDetail(
        repository: RuntimeCommunicationRepository,
        actorSessionId: String,
        request: RuntimeCommunicationDetailRequest,
    ): RuntimeCommunicationDetailResult {
        val actor = activeRuntimeIds[actorSessionId] ?: actorSessionId
        return communicationGateway(repository).detail(actor, request)
    }
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

    /**
     * Ensure [childSessionId] has a RUNNING runtime node under
     * [parentSessionId], creating the node when it does not exist yet and
     * RESTARTING it when a previous run left it terminal.
     *
     * [T-android-child-restart] This method returns whether the child node is
     * actually RUNNING when it returns — not whether the tree was written. The
     * two used to be conflated: the reuse branch called `start()` and
     * `heartbeat()`, discarded both results, and still returned `true`. For any
     * existing terminal node both calls are no-ops (`start` is refused by
     * `updateStatus`'s terminal guard; `heartbeat` refuses a status that is not
     * lease-active), so the caller — `SessionActivityTracker.beginDelegatedChild`
     * and, through it, the delegated-child runner — was told "the child is
     * registered" while its node stayed ABNORMAL_INTERRUPTION and a *terminal*
     * node was written into `activeRuntimeIds`. A failure swallowed this way is
     * indistinguishable from success at every layer above, which is the one
     * outcome this file must never produce.
     *
     * The branch used to be unreachable because the delegated-child runner
     * always minted a fresh session id, so no live failure ever surfaced. It is
     * the seam a restart has to use, so it is made honest first.
     */
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
        var launched = false
        val persisted = store.update {
            val parent = node(parentRuntimeId) ?: return@update
            val existing = node(childSessionId)
            val child = if (existing == null) {
                createChild(
                    parent.id,
                    childSessionId,
                    model,
                    delegationMode = delegationMode ?: parent.delegationMode,
                ).getOrNull() ?: return@update
            } else {
                // Same session id means "run this child again", not "make a
                // second node": the ChatSession row is the child's context, so
                // reusing the node keeps the restored transcript attached to the
                // node the parent already knows.
                existing
            }
            childRuntimeId = child.id
            // `canRestart` asks the tree which transition applies instead of this
            // class re-deriving "is this node final" from the status enum — the
            // private `isTerminal()` extension in SessionTreeRuntime stays the
            // single definition of that idea.
            launched = if (canRestart(child.id)) {
                restart(child.id, reason = "delegated child restarted on an existing session")
            } else {
                start(child.id)
            }
            // Best-effort lease refresh only: the RUNNING transition above is what
            // makes the child live, so a heartbeat refusal would not un-run it.
            if (launched) heartbeat(child.id)
        }
        // A terminal node must never be published as the child's active runtime.
        if (!persisted || !launched) return false
        activeRuntimeIds[childSessionId] = requireNotNull(childRuntimeId)
        return true
    }

    /**
     * [T-android-child-restart] Restart one interrupted descendant of
     * [parentSessionId] so the parent can pick a child back up after it stopped
     * abnormally (lease expiry after a crash lands a node in
     * ABNORMAL_INTERRUPTION).
     *
     * Authorization mirrors [stopDescendant] rather than re-deriving its own
     * rules: the actor is taken from the caller-supplied session id (the
     * application binding, never a model-supplied argument), the target must be
     * a real node, and it must be a descendant of the SAME CONVERSATION.
     *
     * [T-android-crash-recovery-scope] That ancestry is resolved across every root
     * generation of the conversation ([sessionScopedDescendants]) instead of only
     * the actor's own generation. "This node is a descendant of that session" is
     * the property that carries the authority, and a crash does not change whose
     * child a node is — it only changes which generation's root the actor happens
     * to be running as. The bounds that matter are unchanged: one conversation,
     * never across sessions, never the actor itself, never a generation root.
     *
     * Restart is a control operation with the same blast radius as stop, so it
     * keeps every check that still separates the authorized from the unauthorized
     * case and adds none weaker.
     *
     * Only a terminal node is restartable: a child that is still running must
     * not be "restarted" behind the back of whoever is driving it. Returns false
     * without touching anything in every refusal case — the caller reports the
     * refusal instead of being told a state change happened.
     */
    @Synchronized
    fun restartDescendant(
        parentSessionId: String,
        childSessionId: String,
        reason: String = "",
    ): Boolean {
        if (parentSessionId.isBlank() || childSessionId.isBlank()) return false
        val actor = activeRuntimeIds[parentSessionId] ?: parentSessionId
        val target = activeRuntimeIds[childSessionId] ?: childSessionId
        // Resolved from the durable tree BEFORE the write block, the same way
        // [drainInbox] resolves its actors, so the membership check sees every
        // generation of this conversation instead of only the current one.
        val scopedIds = sessionScopedDescendants(parentSessionId).mapTo(HashSet()) { it.id }
        var restarted = false
        val persisted = store.update {
            val actorNode = node(actor) ?: return@update
            val targetNode = node(target) ?: return@update
            if (actorNode.id == targetNode.id) return@update
            if (targetNode.id !in scopedIds) return@update
            // The caller's reason rides along so the audit event says WHY this
            // restart happened, not just that one did; "restart requested by X"
            // alone does not distinguish a crash recovery from a retry after a
            // bad answer.
            restarted = restart(
                targetNode.id,
                reason = buildString {
                    append("restart requested by ").append(actorNode.id)
                    if (reason.isNotBlank()) append(": ").append(reason)
                },
            )
        }
        if (!persisted || !restarted) return false
        activeRuntimeIds[childSessionId] = target
        return true
    }

    /**
     * [T-android-child-restart] Roll back a restart whose child never started.
     *
     * [restartDescendant] re-arms the node and the launch happens after, so a
     * launch that fails would otherwise leave a RUNNING node that nothing drives:
     * `supervise_descendants` would stop reporting it as interrupted and the
     * parent would wait for a result that cannot arrive. This returns the node to
     * the state that is actually true, and drops it from
     * [activeRuntimeIds] so it is not published as a live child either.
     *
     * Deliberately narrow: it only applies to a node that is currently RUNNING —
     * a node that already reached a terminal state is left alone — and it marks
     * the node abnormal rather than failed, because "this run never got going /
     * was cut short" is what happened, not "the task failed".
     *
     * [T-android-crash-recovery-scope] Its scope has to be exactly
     * [restartDescendant]'s: the two are one operation split in two, so a rollback
     * that refused a target the restart accepted would leave the node RUNNING with
     * nothing driving it — the precise lie this method exists to prevent, and it
     * would appear only on the crash-recovery path.
     */
    @Synchronized
    fun failRestartedChild(parentSessionId: String, childSessionId: String, reason: String): Boolean {
        if (parentSessionId.isBlank() || childSessionId.isBlank()) return false
        val actor = activeRuntimeIds[parentSessionId] ?: parentSessionId
        val target = activeRuntimeIds[childSessionId] ?: childSessionId
        val scopedIds = sessionScopedDescendants(parentSessionId).mapTo(HashSet()) { it.id }
        var rolledBack = false
        val persisted = store.update {
            val actorNode = node(actor) ?: return@update
            val targetNode = node(target) ?: return@update
            if (targetNode.id !in scopedIds) return@update
            if (targetNode.status != RuntimeNodeStatus.RUNNING) return@update
            rolledBack = abort(targetNode.id, abnormal = true, reason = reason)
        }
        if (rolledBack) activeRuntimeIds.remove(childSessionId)
        return persisted && rolledBack
    }

    @Synchronized
    fun finishChild(
        childSessionId: String,
        failed: Boolean = false,
        report: RuntimeStopReport? = null,
    ) {
        val runtimeId = activeRuntimeIds.remove(childSessionId) ?: childSessionId
        // [T-android-child-agent-completion] A child that was asked to record a
        // failure and claims no normal end did not fail a task — it stopped
        // without declaring completion, which is what ABNORMAL_INTERRUPTION
        // means. Routing those through complete(failed = true) stamped them
        // FAILED (a task-level failure) and left ABNORMAL_INTERRUPTION reachable
        // only through lease expiry. abort(abnormal = true) lands on the same
        // terminal state the lease reaper uses and still notifies the parent
        // once the node and its whole subtree settle.
        if (failed && report?.completedNormally != true) {
            // [T-android-stop-request-kind] One read of the verdict decides both the
            // node status AND how the stop is described. They were derived in two
            // places before — the description was fixed to "stopped abnormally"
            // whatever had happened — so a stop somebody asked for was recorded as a
            // crash in the node's event trail and in the notification's `debugInfo`
            // even after its STATUS had been corrected. The reason string is
            // `val`-shaped here only because the read has to happen under the same
            // tree update as the abort it decides.
            var reason = ""
            // `store.update` reports whether the tree was persisted, NOT what the
            // block returned, so abort()'s own verdict has to be captured inside.
            var aborted = false
            val persisted = store.update {
                // [T-android-stop-request-terminal] A child that was asked to stop
                // and then unwound did not die on its own: the reason its run ended
                // is the request, which is exactly what the node's STOP_REQUESTED
                // mark records. Aborting it `abnormal = true` filed one deliberate
                // stop as ABNORMAL_INTERRUPTION, the state the lease reaper uses
                // for a process that died mid-run — so the parent (and the chat
                // card) were told a child had crashed when the user had stopped it.
                //
                // Only the stop-REQUESTED case moves: a lease expiry, a crash or
                // an undeclared end never leaves a stop request behind, so those
                // keep landing on ABNORMAL_INTERRUPTION unchanged.
                val stoppedOnRequest = stopRequested(runtimeId)
                reason = abnormalStopReason(report, stoppedByRequest = stoppedOnRequest)
                aborted = abort(
                    runtimeId,
                    abnormal = !stoppedOnRequest,
                    reason = reason,
                    report = report,
                )
            }
            // abort() refuses a node that is already terminal (or unknown). The
            // refusal used to be invisible: no log, no event, and the child's
            // terminal state silently kept whatever it already had. That matters
            // because the lease reaper can stamp a node ABNORMAL_INTERRUPTION
            // before the child really finishes, and a STOP_REQUESTED node is
            // terminal too — in both cases the parent never learns why it heard
            // nothing. Logging keeps the cause reachable without changing which
            // state wins.
            if (persisted && !aborted) {
                Log.w(
                    TAG,
                    "finishChild: abort refused for node=$runtimeId (already terminal or absent) " +
                        "— terminal state left unchanged ($reason)",
                )
            }
        } else {
            store.update { complete(runtimeId, failed = failed, report = report) }
        }
    }

    /**
     * [T-android-child-agent-completion] `abort()` takes a human-readable reason
     * string, so the diagnostics a stopped child collected are also folded into
     * one line here. That line is what the node's event trail and the parent
     * notification's `debugInfo` show, so status code, error body and the end
     * reason stay readable without opening the structured fields.
     *
     * [finishChild] additionally hands `abort()` the original [RuntimeStopReport]
     * so the structured fields — notably `responseHeaders`, which no reason line
     * carries — reach the parent notification instead of being dropped.
     *
     * [stoppedByRequest] is the caller's verdict (the same one it passes to `abort`
     * as `abnormal = !stoppedByRequest`) and it changes only the LEADING phrase, not
     * the diagnostics that follow: "this run was stopped on request" and "this run
     * died" are different causes, and the sentence is the one place the difference
     * is still legible after the structured fields have been read out of context —
     * it is what the node's event trail and the notification's `debugInfo` carry.
     * Both phrases keep the same prefix so existing readers that match on
     * `delegated child stopped` keep working.
     *
     * Capped because the line is persisted with the tree.
     */
    private fun abnormalStopReason(report: RuntimeStopReport?, stoppedByRequest: Boolean = false): String {
        val lead = if (stoppedByRequest) {
            "delegated child stopped on request"
        } else {
            "delegated child stopped abnormally"
        }
        if (report == null) return "$lead (no stop report)"
        val parts = mutableListOf(lead)
        report.statusCode?.let { parts += "status=$it" }
        report.errorResponse?.trim()?.takeIf { it.isNotEmpty() }?.let {
            parts += "error=" + it.take(ABNORMAL_REASON_FIELD_CHARS)
        }
        report.debugInfo?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += it }
        report.lastSentBodyTail?.trim()?.takeIf { it.isNotEmpty() }?.let {
            parts += "last_sent_body_tail=" + it.take(ABNORMAL_REASON_FIELD_CHARS)
        }
        return parts.joinToString("; ").take(ABNORMAL_REASON_MAX_CHARS)
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

    @Synchronized
    fun capabilityVersion(sessionId: String): RuntimeCapabilitySnapshotVersion {
        val runtimeId = activeRuntimeIds[sessionId] ?: sessionId
        var snapshot: AgentCapabilitySnapshot? = null
        val persisted = store.update { snapshot = capabilitySnapshot(runtimeId) }
        return if (persisted && snapshot != null) {
            RuntimeCapabilitySnapshotVersion(
                configRevision = snapshot!!.configRevision,
                capabilityRevision = snapshot!!.currentActiveChildren.toLong(),
            )
        } else {
            RuntimeCapabilitySnapshotVersion(0, 0)
        }
    }

    /**
     * [T-android-child-agent-completion] Live delegation capabilities of one
     * session's runtime node, so a top-down message can tell the child what it
     * is — final executor or manager — without the child first having to spend a
     * tool call on `supervise_descendants`.
     *
     * Read fresh on every call, never cached: [AgentCapabilitySnapshot.selfCanDelegate]
     * depends on the node's currently active children and on its remaining
     * depth, both of which change while the tree runs.
     *
     * Read-only: goes through `store.snapshot()` (the path [supervisionSnapshot]
     * already uses) rather than `update { }`, because a per-message prompt
     * injection must not rewrite the tree file on disk.
     *
     * [sessionId] is mapped through [activeRuntimeIds] like every other entry
     * point, so a `#run-N` root or a delegated child resolves to its live node.
     * Returns null when the session has no runtime node yet; callers must read
     * that as UNKNOWN, never as "cannot delegate" — `false` means "certainly
     * cannot", and the two are not interchangeable.
     */
    @Synchronized
    fun capabilitySnapshot(sessionId: String): AgentCapabilitySnapshot? {
        if (sessionId.isBlank()) return null
        val runtimeId = activeRuntimeIds[sessionId] ?: sessionId
        return store.snapshot().capabilitySnapshot(runtimeId)
    }

    @Synchronized
    fun claimNextStep(sessionId: String): RuntimeEnvelope? {
        val runtimeId = activeRuntimeIds[sessionId] ?: sessionId
        var envelope: RuntimeEnvelope? = null
        val persisted = store.update { envelope = claimNextStep(runtimeId) }
        return if (persisted) envelope else null
    }

    @Synchronized
    fun claimNextTurn(sessionId: String): RuntimeEnvelope? {
        val runtimeId = activeRuntimeIds[sessionId] ?: sessionId
        var envelope: RuntimeEnvelope? = null
        val persisted = store.update { envelope = claimNextTurn(runtimeId) }
        return if (persisted) envelope else null
    }

    @Synchronized
    fun claimNextNotification(sessionId: String): RuntimeEnvelope? {
        val runtimeId = activeRuntimeIds[sessionId] ?: sessionId
        var envelope: RuntimeEnvelope? = null
        val persisted = store.update { envelope = claimNextNotification(runtimeId) }
        return if (persisted) envelope else null
    }

    /**
     * [T-android-parent-inbox-consumer] Drain everything the runtime put in
     * [sessionId]'s own mailbox, across all three delivery lanes.
     *
     * Why this exists: the runtime writes to a parent's inbox by itself —
     * `SessionTreeRuntime.notifyParentOnStop` (a stopped child:
     * `child_completed` / `child_abnormal_stop`), `deleteSubtree` and
     * `purgeSubtrees` (`child_subtree_deleted`, `subtree_delete_result`). Those
     * writes deliberately bypass [send]'s authorization branch because they are
     * the runtime's own internal channel, and until now the only reader of the
     * three lanes was `RuntimeChildRunner`, which claims as the CHILD. So every
     * one of those notifications reached a mailbox nobody opened: four
     * notification kinds with a producer and no consumer in `main/`.
     *
     * `claimNextNotification`/`Step`/`Turn` cannot serve this caller on their
     * own — each reads exactly one lane, once. A parent that has just settled
     * several children has several `NOTIFY` envelopes queued, and a single
     * reader call would surface one of them and silently leave the rest for a
     * drain that may never come. So this method repeats the three readers until
     * every lane comes back empty.
     *
     * ## The claim lease, and why the caller still needs its own de-duplication
     *
     * `SessionTreeRuntime.claim` hands an envelope to the same claimant again
     * once its claim lease ([RuntimeSessionTree.MESSAGE_CLAIM_LEASE_MILLIS],
     * ten minutes) has expired. That is correct for the child runner — a
     * claimant that died must not swallow a message — but it means a parent
     * that drains more than ten minutes apart re-claims envelopes it has
     * already surfaced. `claim` cannot distinguish "already rendered" from
     * "claimed by a process that died", so that distinction has to live with
     * the renderer; see `RuntimeInboxSurfacing`.
     *
     * Claims are taken inside ONE `store.update` block so either every claim
     * and the file rewrite survive together, or (on a persistence failure) the
     * tree is rolled back and this returns an empty list rather than handing
     * out envelopes whose claim was never made durable.
     *
     * @return the claimed envelopes in claim order (one inbox pass per lane per
     *   actor, repeated until drained). Empty when the session has no runtime
     *   node, when nothing is queued, or when the tree could not be persisted.
     */
    @Synchronized
    fun drainInbox(sessionId: String): List<RuntimeEnvelope> {
        if (sessionId.isBlank()) return emptyList()
        val actors = runtimeIdCandidates(sessionId)
        if (actors.isEmpty()) return emptyList()
        val drained = ArrayList<RuntimeEnvelope>()
        val seen = HashSet<String>()
        val persisted = store.update {
            var progressed = true
            // The bound is a safety net, never normal operation: every iteration
            // must claim at least one NEW envelope, the inbox is finite, and a
            // claim stamps a fresh lease that keeps the same envelope out of the
            // next iteration. Anything left beyond it is picked up by the next
            // drain, not dropped.
            while (progressed && drained.size < MAX_INBOX_DRAIN_PER_CALL) {
                progressed = false
                for (actor in actors) {
                    val claimed = listOfNotNull(
                        claimNextNotification(actor),
                        claimNextStep(actor),
                        claimNextTurn(actor),
                    )
                    if (claimed.isEmpty()) continue
                    progressed = true
                    for (envelope in claimed) if (seen.add(envelope.id)) drained += envelope
                }
            }
        }
        return if (persisted) drained else emptyList()
    }

    /**
     * [T-android-parent-inbox-consumer] Every runtime node id that stands for
     * the conversation [sessionId].
     *
     * Normally one: the live root, which [startRoot] records in
     * [activeRuntimeIds]. That map is per-process, so after a cold start it is
     * empty while the persisted tree still holds the node the notification was
     * addressed to — and `startRoot` mints `"$sessionId#run-N"` for every run
     * after the first. Resolving only the raw session id would therefore find
     * nothing exactly in the case the user notices most (reopen a conversation
     * that accumulated notifications while the app was closed), so run-scoped
     * ids sharing the session id are included too. A candidate that names no
     * existing node claims nothing and is filtered out.
     */
    private fun runtimeIdCandidates(sessionId: String): List<String> {
        val ids = LinkedHashSet<String>()
        activeRuntimeIds[sessionId]?.let { ids += it }
        val snapshot = store.snapshot()
        snapshot.topology().nodes
            .filter { it.id == sessionId || it.id.startsWith(sessionId + RUN_GENERATION_MARKER) }
            .forEach { ids += it.id }
        return ids.filter { snapshot.node(it) != null }
    }

    /**
     * [T-android-crash-recovery-scope] Every node that belongs to the
     * conversation [sessionId]'s runtime tree, across ALL of its root
     * generations.
     *
     * [runtimeIdCandidates] answers "which root nodes stand for this
     * conversation"; this asks the same question one level down, for the same
     * reason. Both halves of request.md:9 — "the main agent can see which
     * sub-agents were interrupted" and "…and bring one back up" — are asked from
     * the CURRENT generation's root, and a crash is precisely the event that puts
     * the descendants in a different generation: `startRoot` mints
     * `"$sessionId#run-N"` for a session whose previous node is terminal
     * (`SessionTreeRuntime` keeps that suffix as its root re-run generation),
     * while every child of the crashed run is still parented to the old root. A
     * scope of "the actor node's own descendants" therefore answers `[]` exactly
     * in the case the requirement is about: the interrupted children exist, are
     * persisted, and are invisible.
     *
     * The delivery path already refuses to make that mistake — [drainInbox]
     * resolves its actors through [runtimeIdCandidates] — so supervision and
     * control use that same definition here instead of a second, narrower one.
     *
     * Generation roots are excluded from the result: they are this conversation's
     * own main agent at different points in time, not its sub-agents, and a
     * restart whose target were an older root would leave one session with two
     * live roots, which nothing else in this tree is prepared for.
     *
     * Read-only by construction (`store.snapshot()` is the in-memory view), so a
     * tool-table getter or a supervision report never rewrites the tree file.
     */
    private fun sessionScopedDescendants(sessionId: String): List<RuntimeSessionNode> {
        if (sessionId.isBlank()) return emptyList()
        val snapshot = store.snapshot()
        val roots = runtimeIdCandidates(sessionId).toSet()
        return roots
            .flatMap { root -> snapshot.descendants(root) }
            .distinctBy { it.id }
            .filter { it.id !in roots }
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
    /**
     * Owner-authorized purge of runtime subtrees, for a user-initiated
     * conversation delete. See [SessionTreeRuntime.purgeSubtrees] for why this
     * is not [deleteSubtree]: the human owner is not a node in the tree, so the
     * strict-ancestor rule that keeps agents from deleting each other (and
     * themselves) cannot express what the user asked for. Agent-issued deletes
     * keep going through [deleteSubtree] and its guard is untouched.
     *
     * Returns `null` when the tree could not be persisted; the store rolls the
     * in-memory tree back in that case, so nothing was removed and the caller
     * still owes the purge.
     */
    @Synchronized
    fun purgeSubtrees(runtimeNodeIds: Collection<String>, reason: String = ""): PurgeSubtreesReceipt? {
        var receipt: PurgeSubtreesReceipt? = null
        val persisted = store.update { receipt = purgeSubtrees(runtimeNodeIds, reason) }
        if (!persisted) return null
        // [R44] Drop the per-process session→runtime memo for every node the purge
        // actually removed. A stale entry keeps routing later calls for that session
        // at a node that no longer exists, which reads as a refused operation on a
        // session the user already deleted. Only ids the purge removed are dropped,
        // so a node that is still live keeps its memo.
        val removed = receipt?.removedNodeIds.orEmpty().toHashSet()
        if (removed.isNotEmpty()) activeRuntimeIds.entries.removeAll { it.value in removed }
        return receipt
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


    /**
     * Read-only view of descendants managed by the calling runtime node.
     *
     * [T-android-crash-recovery-scope] Scoped to the whole conversation rather
     * than to the actor's current root generation, for the same reason as
     * [hasRestartableDescendant]: a crash moves the main agent into a new
     * generation while its children stay under the previous one, and a read that
     * reports nothing there is the difference between "no sub-agent was
     * interrupted" and "I cannot see them".
     */
    @Synchronized
    fun supervisedDescendants(actorSessionId: String): List<RuntimeSessionNode> {
        if (actorSessionId.isBlank()) return emptyList()
        return sessionScopedDescendants(actorSessionId)
    }

    /**
     * [T-android-child-restart] Whether [actorSessionId] has anything a restart
     * could act on right now.
     *
     * This is the criterion behind whether the main agent is even OFFERED
     * `restart_descendant`. The tool is not advertised unconditionally: a restart
     * tool with nothing to restart invites the model to spend turns on it (the
     * rule this codebase already follows for `goal_complete` and the memory
     * tools), and the requirement is narrower than "always available" — a child
     * only becomes a restart candidate when it stopped, which is exactly the
     * state this asks about.
     *
     * The answer comes from [SessionTreeRuntime.canRestart] per descendant, NOT
     * from a status comparison written here: `isTerminal()` is a private
     * extension kept inside SessionTreeRuntime so "which states are final" has
     * one definition, and a second copy in this file is how the two answers drift
     * apart. It also deliberately does not filter on the `abnormal` flag: that
     * flag is *auxiliary information for the parent's decision* (request.md:9),
     * not an authorization criterion — a child that ended FAILED is restartable
     * by the same rule and must not be hidden from the parent.
     *
     * [T-android-crash-recovery-scope] The question is asked of the whole
     * CONVERSATION, not of the actor's current root generation: after a crash the
     * interrupted children hang off the previous generation's root, so a
     * single-generation scope answers "nothing to restart" to a parent whose
     * children are right there on disk. The scope comes from
     * [sessionScopedDescendants] — the same generation union [drainInbox] has
     * always used — and stays inside one conversation, so it is not a widening of
     * *authority*, only of *visibility*.
     *
     * Read-only by construction: `store.snapshot()` is the in-memory view, so
     * this never rewrites the tree file, and it is cheap enough to call from a
     * tool-table getter that is re-evaluated on every model call.
     */
    @Synchronized
    fun hasRestartableDescendant(actorSessionId: String): Boolean {
        if (actorSessionId.isBlank()) return false
        val tree = store.snapshot()
        return sessionScopedDescendants(actorSessionId).any { tree.canRestart(it.id) }
    }

    /**
     * [T-android-crash-recovery-scope] The descendant list is scoped to the whole
     * conversation, not to the actor node's own generation, so
     * `supervise_descendants` can report the children a crash left behind under
     * the previous root. This is the "see which sub-agents were interrupted" half
     * of request.md:9 — the actor node stays the live one, but the answer to "what
     * is under me" must not silently depend on which generation the caller
     * happens to be running as.
     */
    @Synchronized
    fun supervisionSnapshot(actorSessionId: String): RuntimeSupervisionSnapshot {
        val actor = activeRuntimeIds[actorSessionId] ?: actorSessionId
        val actorNode = store.snapshot().node(actor)
        val descendants =
            if (actorNode == null) emptyList() else sessionScopedDescendants(actorSessionId)
        return RuntimeSupervisionSnapshot(
            actorNode = actorNode,
            actorCapabilities = actorNode?.let { store.snapshot().capabilitySnapshot(it.id) },
            descendants = descendants,
            configurationRevision = store.snapshot().configurationRevision(),
        )
    }

    /** Stable runtime topology used by export and diagnostics; content stays in ChatRepository. */
    @Synchronized
    fun topologySnapshot(): RuntimeTopologySnapshot = store.snapshot().topology()
    /** Persist Agent Behavior limits without canceling or deleting existing runtime nodes. */
    @Synchronized
    fun applyAgentBehaviorSettings(recursionDepth: Int, parallelAgentLimit: Int): Boolean {
        if (recursionDepth !in 0..RuntimeTreeConfig.MAX_DEPTH_LIMIT) return false
        if (parallelAgentLimit !in 1..RuntimeTreeConfig.MAX_PARALLEL_LIMIT) return false
        return store.update {
            updateConfig(
                maxDepth = recursionDepth,
                maxParallelSubagents = parallelAgentLimit,
            )
        }
    }

    companion object {
        private const val TAG = "RuntimeCoordinator"
        private val DEFAULT_MODEL = RuntimeModelSnapshot(provider = "unknown", model = "active-session")

        /**
         * [T-android-child-agent-completion] Caps for the folded abnormal-stop
         * reason: it is written into the persisted tree and into the parent's
         * notification, so a multi-megabyte error body must not ride along.
         */
        private const val ABNORMAL_REASON_MAX_CHARS = 600
        private const val ABNORMAL_REASON_FIELD_CHARS = 200

        /**
         * [T-android-parent-inbox-consumer] Safety bound for one [drainInbox]
         * pass. Never reached in normal operation (each loop iteration claims at
         * least one new envelope and the inbox is finite); it exists so a future
         * change to `claim` cannot turn an unbounded mailbox into an unbounded
         * loop inside a synchronized tree update.
         */
        private const val MAX_INBOX_DRAIN_PER_CALL = 64

        /** Root re-run suffix minted by [startRoot]; see [runtimeIdCandidates]. */
        private const val RUN_GENERATION_MARKER = "#run-"

        private val coordinatorsByFile = mutableMapOf<String, RuntimeSessionCoordinator>()

        private fun openShared(
            context: Context,
            config: RuntimeTreeConfig,
            next: Boolean,
        ): RuntimeSessionCoordinator {
            val appContext = context.applicationContext
            val file = if (next) {
                NextDataRoot.runtimeTreeFile(appContext.filesDir)
            } else {
                File(appContext.filesDir, "runtime/session-tree.json")
            }
            val key = file.absoluteFile.normalize().path
            synchronized(coordinatorsByFile) {
                coordinatorsByFile[key]?.let { return it }
                val coordinator = RuntimeSessionCoordinator(
                    if (next) RuntimeTreeStore.openNext(appContext, config)
                    else RuntimeTreeStore.open(appContext, config),
                ).also { created ->
                    val stale = created.store.reconcile()
                    if (stale.isNotEmpty()) {
                        Log.w(TAG, "Recovered ${stale.size} stale runtime node(s): ${stale.joinToString()}")
                    }
                }
                coordinatorsByFile[key] = coordinator
                return coordinator
            }
        }

        fun open(context: Context, config: RuntimeTreeConfig = RuntimeTreeConfig()): RuntimeSessionCoordinator =
            openShared(context, config, next = false)

        fun openNext(context: Context, config: RuntimeTreeConfig = RuntimeTreeConfig()): RuntimeSessionCoordinator =
            openShared(context, config, next = true)
    }
}
