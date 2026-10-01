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
    STOP_REQUESTED,
    SUCCEEDED,
    FAILED,
    ABORTED,
    ABNORMAL_INTERRUPTION,
}

/** What a stopped child tells its direct parent. See `request.md:248`. */
data class RuntimeStopReport(
    val nodeId: String,
    val statusCode: Int? = null,
    val errorResponse: String? = null,
    val responseHeaders: Map<String, String> = emptyMap(),
    val debugInfo: String? = null,
    /**
     * The last body text this child's model produced, reported so the parent can
     * see where the child stopped. [lastSentBodyTail] keeps only the final
     * [MAX_TAIL_CHARS] characters, which is the 「最后200个字」 `request.md:248`
     * asks for.
     *
     * A run that died before producing any output has no model text to carry; the
     * constructors fall back to the last request body sent on the child's behalf,
     * which is the closest honest substitute and is never a fabricated value.
     */
    val lastSentBody: String? = null,
    /**
     * Whether the child declared a natural end. Drives which fields reach the
     * parent: `true` means the notification is a terse "completed" and every
     * diagnostic field above is stripped; `false` means they are all reported.
     * See [RuntimeSystemMessageMetadata.from].
     */
    val completedNormally: Boolean = false,
    /**
     * [T-android-stop-request-kind] Whether this run ended because somebody asked
     * it to, rather than because it died on its own.
     *
     * This is the same distinction the node status draws between ABORTED and
     * ABNORMAL_INTERRUPTION, carried into the notification instead of dying with
     * the node row: [abort] sets it from its own `abnormal` parameter, which is
     * `false` exactly on the deliberate paths (a stop an ancestor requested, a
     * subtree its owner purged) and `true` on the paths nobody asked for (a lapsed
     * lease, a crashed process).
     *
     * It exists because `completedNormally = false` cannot tell those two apart.
     * With only that flag, a user pressing 「停止」 got a notification whose kind,
     * card and model reminder all described a crash, and the parent agent was
     * invited to decide whether to restart the child — an ordinary action
     * explained by the wrong cause. See [RuntimeSystemMessageMetadata.from].
     */
    val stoppedByRequest: Boolean = false,
) {
    val lastSentBodyTail: String?
        get() = lastSentBody?.takeLast(MAX_TAIL_CHARS)

    companion object {
        const val MAX_TAIL_CHARS = 200
    }
}

/** Stable metadata contract for parent notifications; normal completion is deliberately terse. */
data class RuntimeSystemMessageMetadata(
    val kind: String,
    val childNodeId: String,
    val statusCode: Int? = null,
    val errorResponse: String? = null,
    val responseHeaders: Map<String, String> = emptyMap(),
    val debugInfo: String? = null,
    val lastSentBodyTail: String? = null,
) {
    fun toJson(): String = org.json.JSONObject().apply {
        put("kind", kind)
        put("childNodeId", childNodeId)
        statusCode?.let { put("statusCode", it) }
        errorResponse?.let { put("errorResponse", it) }
        if (responseHeaders.isNotEmpty()) put("responseHeaders", org.json.JSONObject(responseHeaders))
        debugInfo?.let { put("debugInfo", it) }
        lastSentBodyTail?.let { put("lastSentBodyTail", it) }
    }.toString()

    companion object {
        fun from(report: RuntimeStopReport): RuntimeSystemMessageMetadata = RuntimeSystemMessageMetadata(
            // Three outcomes, three kinds. `stoppedByRequest` is tested BEFORE the
            // abnormal fallback because a deliberate stop and an accidental one both
            // report `completedNormally = false`, and collapsing them into one kind
            // is precisely what let a requested stop be announced as an abnormal one.
            //
            // The tokens are literals on purpose: this file compiles on its own (no
            // Android, no other class of this feature), which the runtime harnesses in
            // this repo rely on, so it must not reach into the class that RENDERS the
            // kinds. The two sides are held together by
            // `RuntimeStoppedByRequestNotificationTest`, which asserts the kind emitted
            // here and the card `RuntimeInboxSurfacing` draws from it in one run — so a
            // drift between the two tokens turns that test red.
            kind = when {
                report.completedNormally -> "child_completed"
                report.stoppedByRequest -> "child_stopped_by_request"
                else -> "child_abnormal_stop"
            },
            childNodeId = report.nodeId,
            statusCode = if (report.completedNormally) null else report.statusCode,
            errorResponse = if (report.completedNormally) null else report.errorResponse,
            responseHeaders = if (report.completedNormally) emptyMap() else report.responseHeaders,
            debugInfo = if (report.completedNormally) null else report.debugInfo,
            lastSentBodyTail = if (report.completedNormally) null else report.lastSentBodyTail,
        )
    }
}


enum class RuntimeControlOperationType { STOP_DESCENDANT, DELETE_SUBTREE }

data class DeleteSubtreeRequest(
    val initiatorNodeId: String,
    val executorNodeId: String,
    val targetNodeId: String,
    val rootId: String? = null,
    val operationId: String = UUID.randomUUID().toString(),
    val idempotencyKey: String = operationId,
) {
    init {
        require(initiatorNodeId.isNotBlank() && executorNodeId.isNotBlank() && targetNodeId.isNotBlank())
        require(operationId.isNotBlank() && idempotencyKey.isNotBlank())
    }
}

enum class DeleteSubtreeResult { DELETED, REJECTED, BUSY, NOT_FOUND }

data class DeleteSubtreeReceipt(
    val operationId: String,
    val idempotencyKey: String,
    val result: DeleteSubtreeResult,
    val initiatorNodeId: String,
    val executorNodeId: String,
    val targetNodeId: String,
    val rootId: String?,
    val oldParentId: String?,
    val affectedNodeIds: List<String>,
    val reason: String? = null,
    val createdAtMillis: Long,
    val eventId: String? = null,
)

data class DeleteSubtreeOperation(
    val request: DeleteSubtreeRequest,
    val receipt: DeleteSubtreeReceipt,
)

/**
 * What [RuntimeSessionTree.purgeSubtrees] took out of the tree.
 *
 * [abortedNodeIds] are the nodes that were still live and had to be stamped
 * terminal before removal — reported separately because "the user deleted a
 * conversation whose sub-agent was still working" is a fact the caller should
 * be able to log, not something to discover later from the event ledger.
 */
data class PurgeSubtreesReceipt(
    val removedNodeIds: List<String>,
    val abortedNodeIds: List<String>,
    /**
     * False only when the caller wrapped this purge in a persistence attempt
     * that failed. The store restores the previous tree on a failed persist, so
     * false means *nothing* was removed and the whole purge is still owed.
     */
    val persisted: Boolean = true,
)

data class RuntimeStopRequest(
    val actorNodeId: String,
    val targetNodeId: String,
    val rootId: String? = null,
    val reason: String = "",
    val operationId: String = UUID.randomUUID().toString(),
    val idempotencyKey: String = operationId,
) {
    init {
        require(actorNodeId.isNotBlank()) { "actorNodeId must not be blank" }
        require(targetNodeId.isNotBlank()) { "targetNodeId must not be blank" }
        require(operationId.isNotBlank()) { "operationId must not be blank" }
        require(idempotencyKey.isNotBlank()) { "idempotencyKey must not be blank" }
    }
}

data class RuntimeStopReceipt(
    val operationId: String,
    val idempotencyKey: String,
    val type: RuntimeControlOperationType,
    val actorNodeId: String,
    val targetNodeId: String,
    val rootId: String?,
    val accepted: Boolean,
    val stateBefore: RuntimeNodeStatus?,
    val stateAfter: RuntimeNodeStatus?,
    val affectedNodeIds: List<String>,
    val actorCapabilitySnapshot: AgentCapabilitySnapshot?,
    val targetCapabilitySnapshot: AgentCapabilitySnapshot?,
    val createdAtMillis: Long,
    val reason: String? = null,
    val eventId: String? = null,
)

enum class RuntimeEdgeKind { PARENT_CHILD, SUBSCRIPTION, TEAM_PEER }

enum class RuntimeEdgePermission { SEND, NOTIFY, STEER }

enum class RuntimeDelivery { TEAM_PEER, STEER, QUEUE, NOTIFY }

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

    /** Runtime roots may use sessionId#run-N after a repeated run. */
    fun descendantsForChatSession(sessionId: String): List<RuntimeSessionNode> {
        val byId = nodes.associateBy { it.id }
        val roots = nodes.filter { node ->
            node.id == sessionId || node.id.startsWith("$sessionId#run-")
        }.map { it.id }.toSet()
        return nodes.filter { node ->
            var parent = node.parentId
            while (parent != null) {
                if (parent in roots) return@filter true
                parent = byId[parent]?.parentId
            }
            false
        }
    }
}

data class RuntimeEdgeReceipt(
    val accepted: Boolean,
    val edgeId: String? = null,
    val createdAtMillis: Long? = null,
    val revokedAtMillis: Long? = null,
    val reason: String? = null,
)

/**
 * [team-peer-mesh] What one Team-membership event did to the peer mesh.
 *
 * Returned by [SessionTreeRuntime.linkTeamPeers] so a caller (or a test) can tell
 * "this member already knew everyone" from "this member just joined and the mesh
 * grew", without diffing a whole topology snapshot. [createdEdgeIds] is empty on a
 * repeated call, which makes idempotence observable instead of merely asserted.
 */
data class RuntimeTeamPeerMesh(
    val nodeId: String,
    val peerNodeIds: List<String>,
    val createdEdgeIds: List<String>,
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
    /** Immutable ancestry snapshot captured at birth; old records default to an empty list. */
    val birthChain: List<String> = emptyList(),
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
    /**
     * Claim-lease stamp. A claim is honored only while its lease is unexpired
     * (see [RuntimeSessionTree.MESSAGE_CLAIM_LEASE_MILLIS]); a claimant that
     * dies before consuming the message leaves an expired stamp instead of a
     * permanent tombstone, so the message becomes claimable again.
     */
    val claimedAtMillis: Long? = null,
    val taskIntent: String = "",
    val capabilitySnapshot: AgentCapabilitySnapshot? = null,
    val senderCapabilitySnapshot: AgentCapabilitySnapshot? = null,
) {
    /** Derived, so the flag and the stamp it is read from can never disagree. */
    val claimed: Boolean get() = claimedAtMillis != null
}

data class RuntimeTreeConfig(
    var maxDepth: Int = 2,
    var maxParallelSubagents: Int = 5,
    var leaseMillis: Long = 120_000L,
    var mode: DelegationMode = DelegationMode.TRADITIONAL,
    /**
     * Retention ceiling for one node's runtime transcript, in messages.
     *
     * Oldest messages are evicted first; the newest message of a node is always
     * retained, so a single oversized message can exceed the ceiling but is
     * never dropped on arrival. See [DEFAULT_TRANSCRIPT_MESSAGES_PER_NODE] for
     * the requirement this traces to and the arithmetic behind the default.
     */
    var maxTranscriptMessagesPerNode: Int = DEFAULT_TRANSCRIPT_MESSAGES_PER_NODE,
    /**
     * Retention ceiling for one node's runtime transcript, in retained
     * `content` + `metadata` characters. Independent of
     * [maxTranscriptMessagesPerNode]: eviction starts when either budget is
     * exceeded. See [DEFAULT_TRANSCRIPT_CHARS_PER_NODE].
     */
    var maxTranscriptCharsPerNode: Int = DEFAULT_TRANSCRIPT_CHARS_PER_NODE,
    /**
     * Retention ceiling for the tree's event ledger, in events.
     *
     * The event ledger is the tree's *history*, not a work queue: nothing in
     * the app reads it to decide what to do next (only tests and the export
     * surfaces do), yet every state transition appends to it forever. It is
     * therefore the single largest growth term of a long single session — in
     * the measurement that motivated this field, 20 000 appends produced
     * 20 001 events carrying 86.7% of the tree JSON. Oldest events are evicted
     * first, the newest is never a casualty of its own arrival, and everything
     * evicted is counted in [RuntimeEventRetention] so a trimmed ledger is
     * never mistaken for a complete one.
     */
    var maxEvents: Int = DEFAULT_EVENTS_LIMIT,
    /**
     * Retention ceiling for the event ledger, in retained `nodeId` + `kind` +
     * `payload` characters. Independent of [maxEvents]: eviction starts when
     * either budget is exceeded.
     */
    var maxEventChars: Int = DEFAULT_EVENT_CHARS_LIMIT,
    /**
     * Retention ceiling for the delivery-receipt ledger, in receipts.
     *
     * Same reasoning as [maxEvents], and the same evidence: `receipts()` has no
     * production caller anywhere in the app, so the ledger is a pure audit
     * trail — and it is appended to on every accepted *and* every rejected
     * send. Counted by [RuntimeReceiptRetention] once it trims.
     */
    var maxDeliveryReceipts: Int = DEFAULT_DELIVERY_RECEIPTS_LIMIT,
    /**
     * Retention ceiling for the delivery-receipt ledger, in retained
     * `fromNodeId` + `toNodeId` + `reason` characters. Independent of
     * [maxDeliveryReceipts].
     */
    var maxDeliveryReceiptChars: Int = DEFAULT_DELIVERY_RECEIPT_CHARS_LIMIT,
    /**
     * Retention ceiling for the control ledger — the tree's record of stop
     * operations — in receipts.
     *
     * This one is not a pure audit trail like the two above, and that is the
     * whole difficulty: it is also the tree's *idempotency table*.
     * [stopDescendant] answers a repeat of a stop request out of it, so what
     * this budget bounds is the window in which a retried stop is recognised as
     * a retry instead of being carried out a second time — which for a
     * destructive operation is the difference between one stop and two. The
     * number is therefore a behavioural choice, not only a memory one.
     *
     * The index that makes the lookup work (`controlOperationIdByIdempotencyKey`)
     * is not a second table with its own budget: it is a *derived view* of this
     * one, and it is capped **together with it**. Evicting a receipt evicts every
     * key that pointed at it, looked up by value rather than by position, so the
     * index can never name an operation this ledger no longer holds. Without
     * that coupling, bounding this map alone would leave the index to grow
     * forever and would turn "this key is known" into a statement that is no
     * longer true.
     *
     * Why 2 000: measured on this tree, one stop receipt costs about 1 982 bytes
     * of tree JSON — roughly ten times a delivery receipt, because it carries the
     * actor's and the target's capability snapshots. Matching the delivery
     * ledger's [DEFAULT_DELIVERY_RECEIPTS_LIMIT] would therefore let this single
     * ledger hold ~15.9 MB, more than the rest of the tree together, while 2 000
     * still covers every stop one session can realistically issue: a stop is a
     * user- or supervisor-initiated interruption, and 2 000 of them is far past
     * any interactive or delegated run. Oldest receipts are evicted first, so the
     * window that survives is the newest one — the retry that arrives moments
     * after the original is always inside it. It is a ceiling, not a target: a
     * tree that stays inside it serializes byte-for-byte as before.
     */
    var maxControlReceipts: Int = DEFAULT_CONTROL_RECEIPTS_LIMIT,
    /**
     * Retention ceiling for the control ledger, in retained `actorNodeId` +
     * `targetNodeId` + `reason` characters — the same shape the delivery ledger
     * counts, because `reason` is likewise the only free-text field a stop
     * receipt carries. Independent of [maxControlReceipts]: eviction starts when
     * either budget is exceeded.
     */
    var maxControlReceiptChars: Int = DEFAULT_CONTROL_RECEIPT_CHARS_LIMIT,
) {
    init {
        require(maxDepth in 0..MAX_DEPTH_LIMIT) { "maxDepth must be between 0 and $MAX_DEPTH_LIMIT" }
        require(maxParallelSubagents in 1..MAX_PARALLEL_LIMIT) {
            "maxParallelSubagents must be between 1 and $MAX_PARALLEL_LIMIT"
        }
        require(leaseMillis > 0) { "leaseMillis must be positive" }
        require(maxTranscriptMessagesPerNode in 1..MAX_TRANSCRIPT_MESSAGES_LIMIT) {
            "maxTranscriptMessagesPerNode must be between 1 and $MAX_TRANSCRIPT_MESSAGES_LIMIT"
        }
        require(maxTranscriptCharsPerNode in 1..MAX_TRANSCRIPT_CHARS_LIMIT) {
            "maxTranscriptCharsPerNode must be between 1 and $MAX_TRANSCRIPT_CHARS_LIMIT"
        }
        require(maxEvents in 1..MAX_EVENTS_LIMIT) {
            "maxEvents must be between 1 and $MAX_EVENTS_LIMIT"
        }
        require(maxEventChars in 1..MAX_EVENT_CHARS_LIMIT) {
            "maxEventChars must be between 1 and $MAX_EVENT_CHARS_LIMIT"
        }
        require(maxDeliveryReceipts in 1..MAX_DELIVERY_RECEIPTS_LIMIT) {
            "maxDeliveryReceipts must be between 1 and $MAX_DELIVERY_RECEIPTS_LIMIT"
        }
        require(maxDeliveryReceiptChars in 1..MAX_DELIVERY_RECEIPT_CHARS_LIMIT) {
            "maxDeliveryReceiptChars must be between 1 and $MAX_DELIVERY_RECEIPT_CHARS_LIMIT"
        }
        require(maxControlReceipts in 1..MAX_CONTROL_RECEIPTS_LIMIT) {
            "maxControlReceipts must be between 1 and $MAX_CONTROL_RECEIPTS_LIMIT"
        }
        require(maxControlReceiptChars in 1..MAX_CONTROL_RECEIPT_CHARS_LIMIT) {
            "maxControlReceiptChars must be between 1 and $MAX_CONTROL_RECEIPT_CHARS_LIMIT"
        }
    }

    companion object {
        const val MAX_DEPTH_LIMIT = 150
        const val MAX_PARALLEL_LIMIT = 150

        /**
         * Retention ceiling for ONE node's runtime transcript.
         *
         * Why a ceiling exists at all: the only sanctioned reason to restrict
         * anything in this project is an explicit user requirement, a proven
         * hard boundary, or catastrophic-resource protection. This one is the
         * first. `plans/ULW-2026-09-25-01/request.md:136`, verbatim:
         *
         *   「我或者就是占用的内存然后系统强制性的给他杀掉，这是我唯一能容忍的
         *     ……它不能够自发性的自杀……我不希望这种事情发生在我们开发的这个APP
         *     上，所以你你要去保证这个APP绝对没有这种问题」
         *
         * A per-node transcript with no ceiling is exactly a self-inflicted
         * growth surface, so the tree owns a finite budget for it.
         *
         * Why these numbers, and why they are ceilings rather than targets:
         * 2000 messages plus 262 144 retained characters (the two budgets are
         * independent — a node may hit either one first) caps a single node at
         * roughly 1 MiB of retained text even in the pathological case where
         * every message is as long as the node budget allows. That is small
         * enough to be survivable per node, and it is generous enough that no
         * realistic conversation is affected: at the cap the node is holding
         * more text than a provider context window can consume in one turn.
         *
         * Both budgets are `var` fields, so the app may raise or lower them;
         * nothing here is a hard-coded silent truncation. Eviction is always
         * reported — see [RuntimeSessionTree.transcriptRetention] — and
         * [RuntimeSessionTree.reclaimTranscripts] applies a lowered budget to
         * already-retained history instead of waiting for the next append.
         */
        const val DEFAULT_TRANSCRIPT_MESSAGES_PER_NODE = 2_000

        /**
         * Upper bound accepted for [maxTranscriptMessagesPerNode]. A validation
         * rail for the config, not a second retention policy: it only stops a
         * caller from configuring a budget so large that it re-introduces the
         * unbounded behaviour this field exists to remove.
         */
        const val MAX_TRANSCRIPT_MESSAGES_LIMIT = 200_000

        /** Retained `content` + `metadata` characters per node. See above. */
        const val DEFAULT_TRANSCRIPT_CHARS_PER_NODE = 262_144

        /** Upper bound accepted for [maxTranscriptCharsPerNode]; see above. */
        const val MAX_TRANSCRIPT_CHARS_LIMIT = 200_000_000

        /**
         * Retention ceiling for the tree's event ledger.
         *
         * Traces to the same requirement as the transcript ceilings above
         * (`plans/ULW-2026-09-25-01/request.md:136`: the app must never kill
         * itself by growing). Measured on this tree, a single long session's
         * ledger is the dominant growth term — at 20 000 appends it held 86.7%
         * of the serialized tree, growing one event per append, forever.
         *
         * Why 8 000: the ledger's job is to answer "what happened to this tree
         * recently, and was this node ever interrupted" — the restart and
         * abnormal-finish decisions read the newest events of a node, and those
         * arrive within one delegation cycle of the question. 8 000 events is
         * several hundred delegated children' worth of transitions for a single
         * run, so no realistic single-session diagnosis is cut off, while the
         * ledger stops being able to grow without bound. It is a ceiling, not a
         * target: a tree that stays inside it is serialized byte-for-byte as
         * before.
         */
        const val DEFAULT_EVENTS_LIMIT = 8_000

        /**
         * Upper bound accepted for [maxEvents]. A validation rail for the
         * config, not a second retention policy: it only stops a caller from
         * configuring a ceiling so large that it re-introduces the unbounded
         * behaviour the field exists to remove.
         */
        const val MAX_EVENTS_LIMIT = 2_000_000

        /**
         * Retained `nodeId` + `kind` + `payload` characters in the event
         * ledger. Independent of [DEFAULT_EVENTS_LIMIT]: eviction starts when
         * either budget is exceeded. Sized so the ledger's retained text stays
         * in the same order as one node's transcript budget.
         */
        const val DEFAULT_EVENT_CHARS_LIMIT = 512_000

        /** Upper bound accepted for [maxEventChars]; see above. */
        const val MAX_EVENT_CHARS_LIMIT = 400_000_000

        /**
         * Retention ceiling for the delivery-receipt ledger. Same requirement
         * and same measurement as [DEFAULT_EVENTS_LIMIT]; kept equal because
         * `send` appends exactly one receipt per appended event, so the ledger
         * that would grow faster would decide the tree's growth on its own.
         */
        const val DEFAULT_DELIVERY_RECEIPTS_LIMIT = 8_000

        /** Upper bound accepted for [maxDeliveryReceipts]; see above. */
        const val MAX_DELIVERY_RECEIPTS_LIMIT = 2_000_000

        /** Retained `fromNodeId` + `toNodeId` + `reason` characters. */
        const val DEFAULT_DELIVERY_RECEIPT_CHARS_LIMIT = 512_000

        /** Upper bound accepted for [maxDeliveryReceiptChars]; see above. */
        const val MAX_DELIVERY_RECEIPT_CHARS_LIMIT = 400_000_000

        /**
         * Retention ceiling for the control (stop) ledger, in receipts.
         *
         * Traces to the same requirement as [DEFAULT_EVENTS_LIMIT] — the app must
         * never kill itself by growing — and was found the same way the other two
         * ledgers were: every stop appends exactly one receipt to the tree's
         * `stopReceipts` array and nothing ever removed one, so a session that is
         * stopped repeatedly grows without bound (measured on this tree: ~1 982
         * bytes of JSON per stop, linear with no knee, and a restart did not trim
         * it either).
         *
         * Smaller than [DEFAULT_DELIVERY_RECEIPTS_LIMIT] on purpose, because a
         * receipt here costs about ten times as much to serialize: it carries the
         * actor's and the target's capability snapshots. The same count would be
         * an order of magnitude more memory for the same number of operations.
         * See [RuntimeTreeConfig.maxControlReceipts] for the behavioural side of
         * the number — this ledger doubles as the stop idempotency table.
         */
        const val DEFAULT_CONTROL_RECEIPTS_LIMIT = 2_000

        /** Upper bound accepted for [maxControlReceipts]; see above. */
        const val MAX_CONTROL_RECEIPTS_LIMIT = 2_000_000

        /** Retained `actorNodeId` + `targetNodeId` + `reason` characters. */
        const val DEFAULT_CONTROL_RECEIPT_CHARS_LIMIT = 512_000

        /** Upper bound accepted for [maxControlReceiptChars]; see above. */
        const val MAX_CONTROL_RECEIPT_CHARS_LIMIT = 400_000_000
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

data class RuntimeSupervisionSnapshot(
    val actorNode: RuntimeSessionNode?,
    val actorCapabilities: AgentCapabilitySnapshot?,
    val descendants: List<RuntimeSessionNode>,
    val configurationRevision: Long,
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

/**
 * What one node's transcript still holds, and what the retention budget has
 * already taken from it.
 *
 * This type exists so a reader can never mistake "this is all there is" for
 * "this is all that survived". A node whose [droppedMessages] is zero really
 * has its complete transcript; a node whose [droppedMessages] is non-zero is
 * *truncated*, and the count says by how much.
 *
 * Eviction order is oldest-first, so the messages still retained are the
 * newest [retainedMessages] ones and [droppedMessages] is also the absolute
 * position of the first retained message: absolute position `i` (counting from
 * the node's very first message, oldest first) is held at list index
 * `i - droppedMessages`. A caller that stored an absolute position before the
 * node was trimmed can therefore still tell whether its message is gone, and
 * which retained index replaced it.
 */
data class RuntimeTranscriptRetention(
    /** Transcript messages still held in memory for this node. */
    val retainedMessages: Int,
    /** Retained `content` + `metadata` characters for this node. */
    val retainedChars: Int,
    /** Messages evicted from the front so far. `0` means nothing was lost. */
    val droppedMessages: Int,
    /** Characters those evicted messages carried. */
    val droppedChars: Int,
) {
    /** True when the budget has taken messages from this node. */
    val truncated: Boolean get() = droppedMessages > 0
}

/**
 * What the tree's event ledger still holds, and what its retention budget has
 * already taken from it.
 *
 * Same contract as [RuntimeTranscriptRetention], for the same reason: a reader
 * must never mistake "this is all there is" for "this is all that survived". A
 * ledger whose [droppedEvents] is zero really is the complete history; one whose
 * [droppedEvents] is non-zero is *truncated*, and the count says by how much.
 *
 * Note that [retainedEvents] is the whole tree's ledger, not one node's: the
 * events of a specific node are a filtered view of it, so a per-node count
 * cannot be stated here without lying about the events of other nodes. A caller
 * asking "how many events does this node have" reads the filtered list.
 */
data class RuntimeEventRetention(
    /** Events still held in memory for the whole tree. */
    val retainedEvents: Int,
    /** Retained `nodeId` + `kind` + `payload` characters for the whole tree. */
    val retainedChars: Int,
    /** Events evicted from the front so far. `0` means nothing was lost. */
    val droppedEvents: Int,
    /** Characters those evicted events carried. */
    val droppedChars: Int,
) {
    /** True when the budget has taken events from the ledger. */
    val truncated: Boolean get() = droppedEvents > 0
}

/**
 * What the tree's delivery-receipt ledger still holds, and what its retention
 * budget has already taken from it. Same three-state contract as
 * [RuntimeEventRetention].
 */
data class RuntimeReceiptRetention(
    /** Receipts still held in memory for the whole tree. */
    val retainedReceipts: Int,
    /** Retained `fromNodeId` + `toNodeId` + `reason` characters. */
    val retainedChars: Int,
    /** Receipts evicted from the front so far. `0` means nothing was lost. */
    val droppedReceipts: Int,
    /** Characters those evicted receipts carried. */
    val droppedChars: Int,
) {
    /** True when the budget has taken receipts from the ledger. */
    val truncated: Boolean get() = droppedReceipts > 0
}

/**
 * What the tree's control (stop) ledger still holds, and what its retention
 * budget has already taken from it — including the idempotency index that had
 * to leave with the receipts.
 *
 * Same three-state contract as [RuntimeReceiptRetention], and one field more,
 * because this ledger is not only an audit trail: [RuntimeSessionTree
 * .stopDescendant] answers a retried stop out of it. [droppedIdempotencyKeys] is
 * what makes the *coupling* visible — the index entry for a receipt that is gone
 * is deleted in the same step, so a non-zero count is the proof that the two
 * were capped as one unit rather than the receipts alone. An index left holding
 * keys for evicted receipts would grow forever and would keep claiming that a
 * retry is recognised when the ledger it points into can no longer answer.
 */
data class RuntimeControlReceiptRetention(
    /** Stop receipts still held in memory for the whole tree. */
    val retainedReceipts: Int,
    /** Retained `actorNodeId` + `targetNodeId` + `reason` characters. */
    val retainedChars: Int,
    /** Receipts evicted from the front so far. `0` means nothing was lost. */
    val droppedReceipts: Int,
    /** Characters those evicted receipts carried. */
    val droppedChars: Int,
    /**
     * Idempotency keys deleted together with those receipts. Every key that
     * pointed at an evicted operation is counted here, so a receipt reachable
     * under several keys contributes several.
     */
    val droppedIdempotencyKeys: Int,
) {
    /** True when the budget has taken receipts from the ledger. */
    val truncated: Boolean get() = droppedReceipts > 0
}

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
    companion object {
        /**
         * How long a claim on an inbox envelope is honored.
         *
         * A claim is taken immediately before the claimant builds its request,
         * but the *next* claim by the same claimant only happens at the start of
         * its next turn. The window therefore has to exceed one delegated turn
         * (provider call plus tool loop), otherwise a live child would be re-fed
         * a message it already consumed. It is five times
         * [RuntimeTreeConfig.leaseMillis] (the node liveness lease) because a
         * turn is much longer than a heartbeat, and it stays finite so a message
         * claimed by a claimant that died before consuming it returns to the
         * claimable set instead of being lost forever.
         */
        const val MESSAGE_CLAIM_LEASE_MILLIS = 600_000L
    }

    private val nodes = LinkedHashMap<String, RuntimeSessionNode>()
    private val children = LinkedHashMap<String, MutableList<String>>()
    private val events = ArrayList<RuntimeEvent>()
    private val inbox = ArrayList<RuntimeEnvelope>()
    private val deliveryReceipts = ArrayList<RuntimeMessageReceipt>()
    private val transcripts = LinkedHashMap<String, MutableList<RuntimeTranscriptMessage>>()
    /**
     * Running retained-character total per node, kept incrementally so the
     * append path stays O(1) in the retained size: recomputing the total on
     * every append would make a capped transcript quadratic in its own ceiling.
     * Must stay in sync with [transcripts] (append, evict, remove, clear,
     * restore) — [transcriptRetention] and [enforceTranscriptBudget] both read
     * it instead of summing the list.
     */
    private val transcriptChars = LinkedHashMap<String, Int>()
    /**
     * Per-node eviction ledger. Absent means "this node never lost a message";
     * the map only ever holds nodes that did.
     */
    private val transcriptEvictions = LinkedHashMap<String, TranscriptEviction>()

    /** Mutable running total for [transcriptEvictions]. */
    private class TranscriptEviction(var messages: Int = 0, var chars: Int = 0)

    /**
     * Running retained-character total for [events], kept incrementally so the
     * append path stays O(1) in the retained size — recomputing it per append
     * would make a capped ledger quadratic in its own ceiling. Must stay in
     * sync with [events] (append, evict, clear, restore).
     */
    private var eventChars = 0
    /**
     * Tree-wide eviction ledger for [events]. Unlike [transcriptEvictions] this
     * is not keyed by node, because the budget is per tree: what grows without
     * bound is the tree's history, and one node losing events while another
     * keeps them would be a policy nobody asked for.
     */
    private val eventEvictions = LedgerEviction()
    /**
     * Per-node count of events the *tree-wide* budget has taken, so a per-node
     * reader can answer "is this node's event list complete" about that node
     * rather than about the tree. Only nodes that actually lost events appear.
     * Persisted (see [toJson]) for the same reason the tree-wide counters are:
     * otherwise a restart would launder a trimmed node into a complete-looking
     * one. Bounded by the number of nodes, which [nodes] already bounds.
     */
    private val eventDropsByNode = LinkedHashMap<String, Int>()
    /** Counterpart of [eventChars] for [deliveryReceipts]. */
    private var receiptChars = 0
    /** Counterpart of [eventEvictions] for [deliveryReceipts]. */
    private val receiptEvictions = LedgerEviction()
    /** Counterpart of [eventChars] for [controlReceipts]. */
    private var controlReceiptChars = 0
    /** Counterpart of [eventEvictions] for [controlReceipts]. */
    private val controlReceiptEvictions = ControlLedgerEviction()

    /** Mutable running eviction total for one tree-wide ledger budget. */
    private class LedgerEviction(var dropped: Int = 0, var droppedChars: Int = 0)

    /**
     * [LedgerEviction] plus the count of index entries that had to leave with
     * it. The control ledger is the one ledger whose index is memory too, so
     * "what did the budget cost" is not answerable from the receipt counts
     * alone: see [RuntimeControlReceiptRetention.droppedIdempotencyKeys].
     */
    private class ControlLedgerEviction(
        var dropped: Int = 0,
        var droppedChars: Int = 0,
        var droppedIdempotencyKeys: Int = 0,
    )

    private val topologyEdges = LinkedHashMap<String, RuntimeTopologyEdge>()
    private val subscriptions = LinkedHashMap<String, RuntimeSubscription>()
    private val controlReceipts = LinkedHashMap<String, RuntimeStopReceipt>()
    private val controlOperationIdByIdempotencyKey = LinkedHashMap<String, String>()
    private val deletionReceipts = LinkedHashMap<String, DeleteSubtreeReceipt>()
    private val deletionOperationIdByIdempotencyKey = LinkedHashMap<String, String>()
    private val pendingStopOperationByNodeId = LinkedHashMap<String, String>()
    private val pendingStopReportByNodeId = LinkedHashMap<String, RuntimeStopReport>()
    private val stopNotificationSentForNodeIds = mutableSetOf<String>()
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
            birthChain = listOf(sessionId),
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
            birthChain = parent.birthChain.ifEmpty { listOf(parent.rootId) } + childId,
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
        // [team-peer-mesh] A member born into a Team must know the team it was born
        // into, and be known by it: request.md:160 describes the team members as
        // building each other's addresses, which is a property of joining, not a
        // follow-up call someone has to remember. `linkTeamPeers` links this child to
        // every existing Team node under the same root in both directions and is a
        // no-op in TRADITIONAL mode, which is what keeps the default sub-agent mode
        // addressless (request.md:5).
        if (node.delegationMode == DelegationMode.TEAM) linkTeamPeers(node.id)
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
        // [team-peer-mesh] Turning a root into Team mode is a team-formation event for
        // EVERY node under it — this switch is root-wide, not node-local — so the
        // members that already exist have to become peers of each other too, not only
        // of whichever node the caller happened to name. Idempotent, so a repeat
        // switch to TEAM just re-confirms the mesh; TRADITIONAL deliberately does
        // nothing here beyond revoking, so no default-mode dispatch can create a peer
        // address.
        if (mode == DelegationMode.TEAM) {
            nodes.values
                .filter { it.rootId == root && it.delegationMode == DelegationMode.TEAM }
                .map { it.id }
                .sorted()
                .forEach { linkTeamPeers(it) }
        }
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
        val modeChanges = mode != config.mode
        if (modeChanges && rootId == null) return false
        config.maxDepth = maxDepth
        config.maxParallelSubagents = maxParallelSubagents
        config.leaseMillis = leaseMillis
        if (modeChanges) {
            setDelegationMode(rootId!!, mode)
        } else {
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
        appendReceipt(
            RuntimeMessageReceipt(
                id = UUID.randomUUID().toString(), messageId = null,
                fromNodeId = fromNodeId, toNodeId = toNodeId, delivery = delivery,
                accepted = false, createdAtMillis = nowMillis, reason = reason,
                status = RuntimeReceiptStatus.REJECTED,
            )
        )
        return RuntimeDeliveryReceipt(false, null, null, reason)
    }

    @Synchronized
    fun start(nodeId: String): Boolean = updateStatus(nodeId, RuntimeNodeStatus.RUNNING, "started")

    /**
     * [T-android-child-restart] Bring a TERMINAL node back to RUNNING.
     *
     * Why this cannot go through [start]: `updateStatus` refuses any terminal
     * node (`if (node.status.isTerminal()) return false`), which is the correct
     * guard for ordinary transitions — a finished node must not silently
     * un-finish. A restart is the one transition that is *supposed* to leave a
     * terminal state, so it needs its own door rather than a weakened guard.
     *
     * The interruption evidence is deliberately NOT erased: the existing
     * `abnormal_interruption` / `aborted` / `failed` event stays in [events]
     * forever, and the restart is appended as its own `restarted` event. The
     * `abnormal` flag is the only thing cleared, because it answers "is this
     * node running right now from a state that never ended?" — once the node is
     * running again the answer is no. "Was this node ever interrupted?" stays
     * answerable from the event log, which is exactly what the caller needs to
     * decide whether to restart.
     *
     * Returns false (and changes nothing) for a non-terminal node: restarting
     * live work is a no-op, not an error to paper over.
     */
    @Synchronized
    fun restart(nodeId: String, reason: String = ""): Boolean {
        val node = nodes[nodeId] ?: return false
        if (!node.status.isTerminal()) return false
        val now = clock()
        val previousStatus = node.status
        nodes[nodeId] = node.copy(
            status = RuntimeNodeStatus.RUNNING,
            updatedAtMillis = now,
            leaseUntilMillis = now + config.leaseMillis,
            abnormal = false,
        )
        clearStopBookkeeping(nodeId)
        record(
            nodeId,
            "restarted",
            now,
            buildString {
                append("from=").append(previousStatus.name)
                if (reason.isNotBlank()) append(';').append(reason)
            },
        )
        return true
    }

    /**
     * [T-android-child-restart] Drop the stop bookkeeping that belonged to the run
     * that just ended, so the NEXT run is not judged by it.
     *
     * This exists because restart invalidates a once-true assumption: before it,
     * a node could stop exactly once in its lifetime, so "we already told the
     * parent" and "here is the report to send" could legitimately be remembered
     * forever. Restart makes a second stop possible for the same node, and
     * everything below was keyed on the node id alone — i.e. on "which node",
     * never on "which run". Left alone, each of these three turns into a lie
     * after a restart:
     *
     *  - [stopNotificationSentForNodeIds]: the parent was already told about the
     *    first stop, so `notifyParentOnStop` returns early and the second stop is
     *    NEVER announced. The parent keeps believing a terminal child is alive and
     *    waits for a result that cannot come. This is the one that matters most —
     *    request.md:248 requires a stopping child to notify its direct parent.
     *  - [pendingStopReportByNodeId]: `maybeNotifyWhenSettled` only clears this on
     *    the branch that actually notifies. Once the dedup set blocks that branch
     *    the entry survives, so a later delivery would carry the PREVIOUS run's
     *    stop report as if it described this one.
     *  - [pendingStopOperationByNodeId]: restart accepts STOP_REQUESTED (it is
     *    terminal). An abandoned stop request left pending here is consumed by the
     *    NEXT `abort`, which stamps its `stateAfter` onto the OLD receipt — filing
     *    a new stop under someone else's operation id.
     *
     * Deliberately narrow: [events] is NOT touched, so "this node was interrupted"
     * stays answerable, and `abnormal` is cleared by the caller as its own
     * decision rather than as a side effect here.
     */
    private fun clearStopBookkeeping(nodeId: String) {
        stopNotificationSentForNodeIds.remove(nodeId)
        pendingStopReportByNodeId.remove(nodeId)
        pendingStopOperationByNodeId.remove(nodeId)
    }

    /**
     * [T-android-child-restart] Whether [restart] would accept [nodeId].
     *
     * Exists so callers OUTSIDE this file (the coordinator, deciding whether an
     * existing child needs a restart rather than a start) can ask the question
     * without re-deriving it. [isTerminal] is a private extension on
     * [RuntimeNodeStatus] precisely to keep "which states are final" defined in
     * exactly one place; a second copy of
     * `status == ABORTED || status == ABNORMAL_INTERRUPTION || …` in another
     * class is how those two answers drift apart. A caller that cannot see the
     * concept asks this predicate instead of restating it.
     *
     * The answer is scoped to restart specifically, not to that class of states:
     * "is this node finished" is a different question from "may this node be
     * brought back", and only the second one is what a caller needs here.
     */
    @Synchronized
    fun canRestart(nodeId: String): Boolean = nodes[nodeId]?.status?.isTerminal() == true

    /**
     * [T-android-stop-request-terminal] Whether [nodeId] is currently marked as
     * stopped at the request of an ancestor.
     *
     * Exists for the same reason as [canRestart]: a caller outside this file has
     * to distinguish "this run ended on its own terms" from "this run ended
     * because it was told to", and re-deriving that from the status enum in
     * another class is how two answers to one question start to drift.
     * `RuntimeSessionCoordinator.finishChild` is the caller that needs it — a
     * deliberately stopped child is not an abnormal interruption, and the only
     * place that knows a stop was requested is this tree.
     */
    @Synchronized
    fun stopRequested(nodeId: String): Boolean =
        nodes[nodeId]?.status == RuntimeNodeStatus.STOP_REQUESTED

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
        if (!node.status.isLeaseActive()) return false
        nodes[nodeId] = node.copy(updatedAtMillis = nowMillis, leaseUntilMillis = nowMillis + config.leaseMillis)
        record(nodeId, "heartbeat", nowMillis)
        return true
    }

    @Synchronized
    fun renewActiveLeases(nowMillis: Long = clock()): List<String> {
        val active = nodes.values.filter { it.status.isLeaseActive() }.map { it.id }
        active.forEach { heartbeat(it, nowMillis) }
        return active
    }

    @Synchronized
    fun waitForChildren(nodeId: String): Boolean = updateStatus(nodeId, RuntimeNodeStatus.WAITING_CHILDREN, "waiting_children")

    @Synchronized
    fun resume(nodeId: String): Boolean = updateStatus(nodeId, RuntimeNodeStatus.RUNNING, "resumed")

    @Synchronized
    fun complete(nodeId: String, failed: Boolean = false, report: RuntimeStopReport? = null): Boolean {
        val changed = updateStatus(
            nodeId,
            if (failed) RuntimeNodeStatus.FAILED else RuntimeNodeStatus.SUCCEEDED,
            if (failed) "failed" else "completed",
        )
        if (changed) {
            pendingStopReportByNodeId[nodeId] = report ?: RuntimeStopReport(nodeId = nodeId, completedNormally = !failed)
            maybeNotifyWhenSettled(nodeId)
            return true
        }
        // [T-android-stop-request-terminal] A node whose stop was REQUESTED is
        // already terminal, so [updateStatus] refuses to rewrite it into
        // SUCCEEDED/FAILED and the "stopped on request" verdict stands. That
        // verdict is right; what was wrong was that the refusal swallowed the rest
        // of this method with it — no stop report was filed and the direct parent
        // was told NOTHING. A child that finished its work in the instant between
        // the stop request and that request's cancellation therefore looked, from
        // the parent's side, like a live child that never reports back, which is
        // the one outcome `request.md:248` exists to prevent.
        //
        // Only the missing notification is restored: the status and the return
        // value are untouched, because "may this node be brought back" / "is it
        // settled" must keep exactly one answer (see [canRestart]).
        if (nodes[nodeId]?.status == RuntimeNodeStatus.STOP_REQUESTED) {
            pendingStopReportByNodeId[nodeId] = report ?: RuntimeStopReport(nodeId = nodeId, completedNormally = !failed)
            maybeNotifyWhenSettled(nodeId)
        }
        return false
    }

    @Synchronized
    fun stopDescendant(request: RuntimeStopRequest): RuntimeStopReceipt {
        controlOperationIdByIdempotencyKey[request.idempotencyKey]?.let { previousId ->
            controlReceipts[previousId]?.let { return it }
        }
        controlReceipts[request.operationId]?.let { previous ->
            controlOperationIdByIdempotencyKey[request.idempotencyKey] = previous.operationId
            return previous
        }

        val now = clock()
        val actor = nodes[request.actorNodeId]
        val target = nodes[request.targetNodeId]
        val actorSnapshot = actor?.let { capabilitySnapshot(it.id) }
        val targetSnapshot = target?.let { capabilitySnapshot(it.id) }
        val authorizationFailure = authorizeDescendantStop(
            actor = actor,
            target = target,
            requestedRootId = request.rootId,
        )
        val allowed = authorizationFailure == null
        if (!allowed) {
            val reason = authorizationFailure ?: "stop is not authorized"
            val rejected = RuntimeStopReceipt(
                operationId = request.operationId,
                idempotencyKey = request.idempotencyKey,
                type = RuntimeControlOperationType.STOP_DESCENDANT,
                actorNodeId = request.actorNodeId,
                targetNodeId = request.targetNodeId,
                rootId = target?.rootId ?: request.rootId,
                accepted = false,
                stateBefore = target?.status,
                stateAfter = target?.status,
                affectedNodeIds = emptyList(),
                actorCapabilitySnapshot = actorSnapshot,
                targetCapabilitySnapshot = targetSnapshot,
                createdAtMillis = now,
                reason = reason,
            )
            val event = record(
                request.actorNodeId,
                "descendant_stop_rejected",
                now,
                stopAuditPayload(
                    request = request,
                    actorNodeId = request.actorNodeId,
                    targetNodeId = request.targetNodeId,
                    rootId = target?.rootId ?: request.rootId,
                    stateBefore = target?.status,
                    stateAfter = target?.status,
                    reason = reason,
                    actorSnapshot = actorSnapshot,
                    targetSnapshot = targetSnapshot,
                ),
            )
            val stored = rejected.copy(eventId = event.id)
            storeControlReceipt(stored)
            return stored
        }

        val authorizedActor = requireNotNull(actor)
        val authorizedTarget = requireNotNull(target)
        val stateBefore = authorizedTarget.status
        if (!authorizedTarget.status.isActive()) {
            val reason = "target is not active"
            val event = record(
                authorizedActor.id,
                "descendant_stop_rejected",
                now,
                stopAuditPayload(
                    request = request,
                    actorNodeId = authorizedActor.id,
                    targetNodeId = authorizedTarget.id,
                    rootId = authorizedTarget.rootId,
                    stateBefore = stateBefore,
                    stateAfter = stateBefore,
                    reason = reason,
                    actorSnapshot = actorSnapshot,
                    targetSnapshot = targetSnapshot,
                ),
            )
            val rejected = RuntimeStopReceipt(
                operationId = request.operationId,
                idempotencyKey = request.idempotencyKey,
                type = RuntimeControlOperationType.STOP_DESCENDANT,
                actorNodeId = authorizedActor.id,
                targetNodeId = authorizedTarget.id,
                rootId = authorizedTarget.rootId,
                accepted = false,
                stateBefore = stateBefore,
                stateAfter = stateBefore,
                affectedNodeIds = emptyList(),
                actorCapabilitySnapshot = actorSnapshot,
                targetCapabilitySnapshot = targetSnapshot,
                createdAtMillis = now,
                reason = reason,
                eventId = event.id,
            )
            storeControlReceipt(rejected)
            return rejected
        }

        val affected = listOf(authorizedTarget.id) + descendantsOf(authorizedTarget.id)
        val changed = affected.mapNotNull { id ->
            val node = nodes[id] ?: return@mapNotNull null
            if (!node.status.isActive()) return@mapNotNull null
            nodes[id] = node.copy(
                status = RuntimeNodeStatus.STOP_REQUESTED,
                updatedAtMillis = now,
                leaseUntilMillis = now + config.leaseMillis,
                abnormal = false,
            )
            record(id, "stop_requested", now, "operationId=${request.operationId};actor=${authorizedActor.id}")
            pendingStopOperationByNodeId[id] = request.operationId
            id
        }
        val event = record(
            authorizedActor.id,
            "descendant_stop_requested",
            now,
            stopAuditPayload(
                request = request,
                actorNodeId = authorizedActor.id,
                targetNodeId = authorizedTarget.id,
                rootId = authorizedTarget.rootId,
                stateBefore = stateBefore,
                stateAfter = nodes[authorizedTarget.id]?.status,
                reason = request.reason.ifBlank { null },
                actorSnapshot = actorSnapshot,
                targetSnapshot = targetSnapshot,
                affectedNodeIds = changed,
            ),
        )
        val accepted = RuntimeStopReceipt(
            operationId = request.operationId,
            idempotencyKey = request.idempotencyKey,
            type = RuntimeControlOperationType.STOP_DESCENDANT,
            actorNodeId = authorizedActor.id,
            targetNodeId = authorizedTarget.id,
            rootId = authorizedTarget.rootId,
            accepted = changed.isNotEmpty(),
            stateBefore = stateBefore,
            stateAfter = nodes[authorizedTarget.id]?.status,
            affectedNodeIds = changed,
            actorCapabilitySnapshot = actorSnapshot,
            targetCapabilitySnapshot = targetSnapshot,
            createdAtMillis = now,
            reason = if (changed.isEmpty()) "target is not active" else request.reason.ifBlank { null },
            eventId = event.id,
        )
        storeControlReceipt(accepted)
        return accepted
    }

    @Synchronized
    fun deleteSubtree(request: DeleteSubtreeRequest): DeleteSubtreeReceipt {
        deletionOperationIdByIdempotencyKey[request.idempotencyKey]?.let { previous ->
            deletionReceipts[previous]?.let { return it }
        }
        deletionReceipts[request.operationId]?.let { return it }
        val now = clock()
        val target = nodes[request.targetNodeId]
        val executor = nodes[request.executorNodeId]
        val initiator = nodes[request.initiatorNodeId]
        val affected = target?.let { listOf(it.id) + descendantsOf(it.id) } ?: emptyList()
        fun reject(result: DeleteSubtreeResult, reason: String): DeleteSubtreeReceipt {
            val receipt = DeleteSubtreeReceipt(request.operationId, request.idempotencyKey, result,
                request.initiatorNodeId, request.executorNodeId, request.targetNodeId,
                target?.rootId, target?.parentId, affected, reason, now)
            deletionReceipts[request.operationId] = receipt
            deletionOperationIdByIdempotencyKey[request.idempotencyKey] = request.operationId
            record(request.executorNodeId, "subtree_delete_rejected", now, reason)
            return receipt
        }
        if (target == null || executor == null || initiator == null) return reject(DeleteSubtreeResult.NOT_FOUND, "unknown initiator, executor, or target")
        // [T-android-conversation-scope] Ownership is a property of the
        // CONVERSATION, not of one root generation. `ConversationIdProtocol
        // .conversationIdOf` is this package's one answer to "which conversation
        // does this runtime id belong to" — the identity
        // `descendantsForChatSession` already walks by — so the `"$sessionId#run-N"`
        // root a re-run mints does not, on its own, put a target out of the
        // executor's reach. Nothing else moves: two different sessions share no
        // conversation id and are still refused here, and the executor must still
        // own the target.
        if (ConversationIdProtocol.conversationIdOf(executor.rootId) != ConversationIdProtocol.conversationIdOf(target.rootId) ||
            ConversationIdProtocol.conversationIdOf(initiator.rootId) != ConversationIdProtocol.conversationIdOf(target.rootId) ||
            (request.rootId != null && !isScopedRootOf(request.rootId, target.rootId))
        ) {
            return reject(DeleteSubtreeResult.REJECTED, "cross-root deletion is not authorized")
        }
        // [T-android-conversation-scope] DELETION IS NARROWER THAN DELIVERY.
        // The conversation-scope predicate lets a generation root reach every
        // member of its conversation, which is what `request.md:111` needs for
        // sending. Reused for deletion it also let ANY generation root delete a
        // target it has no ancestry over — e.g. the crashed root `S` deleting a
        // child of the re-run root `S#run-1` — and `request.md:9` never asked for
        // cross-generation deletion: it asks to VIEW interrupted descendants and
        // to RESTART them. Destroying a subtree stays what this comment always
        // said it was, a strict-ancestor operation:
        if (executor.id == target.id || !isAncestor(executor.id, target.id)) {
            return reject(DeleteSubtreeResult.REJECTED, "executor must be a strict ancestor of target")
        }
        if (!affected.all { id -> nodes[id]?.status?.isTerminal() == true }) {
            return reject(DeleteSubtreeResult.BUSY, "target subtree is still running or waiting for children")
        }
        val oldParentId = target.parentId
        val snapshot = affected.mapNotNull(nodes::get)
        removeNodes(affected)
        val scope = affected.joinToString(",")
        record(request.executorNodeId, "subtree_deleted", now, "operationId=${request.operationId};target=${target.id};affected=$scope")
        oldParentId?.let { parentId ->
            val metadata = JSONObject().put("kind", "child_subtree_deleted")
                .put("targetNodeId", target.id).put("initiatorNodeId", request.initiatorNodeId)
                .put("executorNodeId", request.executorNodeId).put("affectedNodeIds", JSONArray(affected)).toString()
            inbox += RuntimeEnvelope(UUID.randomUUID().toString(), target.id, parentId, RuntimeDelivery.NOTIFY,
                "Subtree ${target.id} deleted.", now, taskIntent = metadata)
        }
        val resultMetadata = JSONObject().put("kind", "subtree_delete_result")
            .put("operationId", request.operationId).put("success", true).put("targetNodeId", target.id)
            .put("affectedNodeIds", JSONArray(affected)).toString()
        inbox += RuntimeEnvelope(UUID.randomUUID().toString(), request.executorNodeId, request.executorNodeId,
            RuntimeDelivery.NOTIFY, "Subtree deletion completed.", now, taskIntent = resultMetadata)
        val event = record(request.executorNodeId, "subtree_delete_result", now, resultMetadata)
        val receipt = DeleteSubtreeReceipt(request.operationId, request.idempotencyKey, DeleteSubtreeResult.DELETED,
            request.initiatorNodeId, request.executorNodeId, request.targetNodeId, target.rootId, oldParentId,
            snapshot.map { it.id }, createdAtMillis = now, eventId = event.id)
        deletionReceipts[request.operationId] = receipt
        deletionOperationIdByIdempotencyKey[request.idempotencyKey] = request.operationId
        return receipt
    }

    @Synchronized
    fun deleteSubtreeReceipt(operationId: String): DeleteSubtreeReceipt? = deletionReceipts[operationId]

    /**
     * Owner-authorized removal of whole subtrees, used when the *user* deletes
     * conversations from the session list.
     *
     * [deleteSubtree] deliberately cannot serve this caller and is left exactly
     * as it was: it demands a strict-ancestor executor inside the same root, and
     * the human owner is not a node in this tree — deleting a conversation that
     * is itself a root has no legal executor at all. The authorization model
     * here is therefore not "an agent proved ancestry" but "the UI asked on the
     * user's behalf", which is also why this is a separate entry point rather
     * than a flag on [deleteSubtree]: nothing in the tool/agent surface reaches
     * it, so an agent still cannot delete itself (or anyone else) through it.
     *
     * Two differences from an agent-issued delete, both deliberate:
     *
     *  - No BUSY refusal. The user's delete is not a negotiation: a sub-agent
     *    that is still running is first stamped [RuntimeNodeStatus.ABORTED]
     *    (`abortedNodeIds`) so the tree records "this run did not finish"
     *    instead of leaving a node that looks live forever, and the nodes are
     *    then removed. Refusing would make "delete" a lie, and there is no
     *    cancel handle for an in-flight child run to wait on.
     *  - Unknown ids are skipped instead of rejected, because the caller passes
     *    a subtree computed from a snapshot that may already be stale.
     *
     * The removal body itself is shared with [deleteSubtree] (see [removeNodes]),
     * so edges, subscriptions, inbox envelopes, transcripts and root ids are
     * cleaned identically no matter which entry point ran.
     */
    @Synchronized
    fun purgeSubtrees(targetNodeIds: Collection<String>, reason: String = ""): PurgeSubtreesReceipt {
        val affected = LinkedHashSet<String>()
        for (raw in targetNodeIds) {
            val id = raw.trim()
            if (id.isEmpty() || nodes[id] == null) continue
            affected += id
            affected += descendantsOf(id)
        }
        if (affected.isEmpty()) return PurgeSubtreesReceipt(emptyList(), emptyList())
        val now = clock()
        val parents = affected.associateWith { nodes[it]?.parentId }
        // Deepest first, so an ancestor is not stamped terminal while a live
        // child is still attached to it.
        val aborted = affected.sortedByDescending { nodes[it]?.depth ?: 0 }
            .filter { abort(it, abnormal = false, reason = reason) }
        removeNodes(affected)
        record(affected.first(), "session_subtree_purged", now, "affected=${affected.joinToString(",")};reason=$reason")
        // The parent that survives the purge is told its child subtree is gone;
        // notifications *between* removed nodes were dropped with them above.
        for (id in affected) {
            val parentId = parents[id] ?: continue
            if (parentId in affected) continue
            val metadata = JSONObject().put("kind", "child_subtree_deleted")
                .put("targetNodeId", id).put("affectedNodeIds", JSONArray(affected.toList())).toString()
            inbox += RuntimeEnvelope(UUID.randomUUID().toString(), id, parentId, RuntimeDelivery.NOTIFY,
                "Subtree ${id} deleted.", now, taskIntent = metadata)
        }
        return PurgeSubtreesReceipt(affected.toList(), aborted)
    }

    /**
     * Shared removal body: drop every trace of [affected] from the tree.
     *
     * Extracted so [deleteSubtree] (agent-authorized) and [purgeSubtrees]
     * (owner-authorized) cannot drift apart in *what* they clean — only in who
     * is allowed to ask.
     */
    private fun removeNodes(affected: Collection<String>) {
        val doomed = affected.toSet()
        doomed.forEach { id ->
            nodes.remove(id)
            children.remove(id)
            transcripts.remove(id)
            transcriptChars.remove(id)
            transcriptEvictions.remove(id)
            eventDropsByNode.remove(id)
            pendingStopOperationByNodeId.remove(id)
            pendingStopReportByNodeId.remove(id)
            stopNotificationSentForNodeIds.remove(id)
        }
        children.values.forEach { it.removeAll(doomed) }
        topologyEdges.entries.removeIf { it.value.fromNodeId in doomed || it.value.toNodeId in doomed }
        subscriptions.entries.removeIf { it.value.subscriberNodeId in doomed || it.value.publisherNodeId in doomed }
        inbox.removeAll { it.fromNodeId in doomed || it.toNodeId in doomed }
        deliveryReceipts.removeAll { it.fromNodeId in doomed || it.toNodeId in doomed }
        // The receipt ledger's cached character total must follow the ledger it
        // describes: receipts that leave with a deleted subtree would otherwise
        // keep counting against a budget they are no longer part of, so the
        // total would claim a size the ledger does not have.
        receiptChars = deliveryReceipts.sumOf { it.retainedChars() }
        rootIds.removeAll(doomed)
    }


    @Synchronized
    fun stopReceipt(operationId: String): RuntimeStopReceipt? = controlReceipts[operationId]

    @Synchronized
    fun abort(
        nodeId: String,
        abnormal: Boolean,
        reason: String = "",
        report: RuntimeStopReport? = null,
    ): Boolean {
        val node = nodes[nodeId] ?: return false
        if (!node.status.isActive() && node.status != RuntimeNodeStatus.STOP_REQUESTED) return false
        val now = clock()
        val finalStatus = if (abnormal) RuntimeNodeStatus.ABNORMAL_INTERRUPTION else RuntimeNodeStatus.ABORTED
        nodes[nodeId] = node.copy(
            status = finalStatus,
            updatedAtMillis = now,
            leaseUntilMillis = null,
            abnormal = abnormal,
        )
        val operationId = pendingStopOperationByNodeId.remove(nodeId)
        if (operationId != null) {
            controlReceipts[operationId]?.let { receipt ->
                if (receipt.targetNodeId == nodeId) {
                    // Through the same single writer as every other change to this
                    // ledger, so the cached character total and the idempotency
                    // index stay in step with it. An in-place edit that skipped the
                    // accounting is the exact defect the delivery ledger's
                    // `replaceReceiptAt` was centralised to prevent; there is no
                    // reason to reintroduce it one ledger over.
                    storeControlReceipt(receipt.copy(stateAfter = finalStatus))
                }
            }
        }
        record(
            nodeId,
            if (abnormal) "abnormal_interruption" else "aborted",
            now,
            buildString {
                if (operationId != null) append("operationId=").append(operationId).append(';')
                if (reason.isNotBlank()) append(reason)
            },
        )
        val stopReport = report?.copy(
            nodeId = nodeId,
            completedNormally = false,
            // [T-android-stop-request-kind] The caller's report cannot know whether
            // anybody asked for this stop — the diagnosis is the caller's, the
            // verdict is ours, and this is the only place that has it.
            stoppedByRequest = !abnormal,
            debugInfo = listOfNotNull(
                report.debugInfo?.takeIf { it.isNotBlank() },
                reason.takeIf { it.isNotBlank() },
            ).joinToString("; ").takeIf { it.isNotBlank() },
        ) ?: RuntimeStopReport(
            nodeId = nodeId,
            completedNormally = false,
            stoppedByRequest = !abnormal,
            debugInfo = reason.takeIf { it.isNotBlank() },
        )
        pendingStopReportByNodeId[nodeId] = stopReport
        maybeNotifyWhenSettled(nodeId)
        return true
    }

    /** Notify only after this node and its complete recursive subtree are terminal. */
    private fun maybeNotifyWhenSettled(nodeId: String) {
        val node = nodes[nodeId] ?: return
        if (!node.status.isTerminal() || hasActiveDescendants(nodeId)) return
        if (!stopNotificationSentForNodeIds.contains(nodeId)) {
            pendingStopReportByNodeId.remove(nodeId)?.let { notifyParentOnStop(nodeId, it) }
        }
        node.parentId?.let { maybeNotifyWhenSettled(it) }
    }

    private fun hasActiveDescendants(nodeId: String): Boolean = descendantsOf(nodeId).any { id ->
        val status = nodes[id]?.status ?: return@any false
        !status.isTerminal()
    }

    /** Sends at most one system notification to the direct parent; roots only get an audit event. */
    private fun notifyParentOnStop(nodeId: String, report: RuntimeStopReport) {
        if (!stopNotificationSentForNodeIds.add(nodeId)) return
        val node = nodes[nodeId] ?: return
        val metadata = RuntimeSystemMessageMetadata.from(report)
        val parentId = node.parentId
        if (parentId == null) {
            record(nodeId, "child_stop_notification_unroutable", clock(), metadata.toJson())
            return
        }
        val now = clock()
        val envelope = RuntimeEnvelope(
            id = UUID.randomUUID().toString(),
            fromNodeId = nodeId,
            toNodeId = parentId,
            delivery = RuntimeDelivery.NOTIFY,
            // The sentence a reader falls back to when it cannot phrase this kind.
            // It must agree with the kind [RuntimeSystemMessageMetadata.from] chose:
            // a fallback that says "abnormally" while the record says "on request"
            // would reinstate the wrong cause this notification was fixed to stop
            // reporting.
            payload = when {
                report.completedNormally -> "Child ${nodeId} completed."
                report.stoppedByRequest -> "Child ${nodeId} stopped on request."
                else -> "Child ${nodeId} stopped abnormally."
            },
            createdAtMillis = now,
            taskIntent = metadata.toJson(),
            capabilitySnapshot = capabilitySnapshot(parentId),
            senderCapabilitySnapshot = capabilitySnapshot(nodeId),
        )
        inbox += envelope
        record(parentId, "child_stop_notification_enqueued", now, metadata.toJson())
        appendReceipt(
            RuntimeMessageReceipt(
                id = UUID.randomUUID().toString(), messageId = envelope.id,
                fromNodeId = nodeId, toNodeId = parentId, delivery = RuntimeDelivery.NOTIFY,
                accepted = true, createdAtMillis = now, status = RuntimeReceiptStatus.ENQUEUED,
            )
        )
    }
    @Synchronized
    fun reconcileLeases(nowMillis: Long = clock()): List<String> {
        val stale = nodes.values.filter {
            it.status.isLeaseActive() && it.leaseUntilMillis != null && it.leaseUntilMillis <= nowMillis
        }.map { it.id }
        stale.forEach { abort(it, abnormal = true, reason = "lease expired") }
        return stale
    }

    /**
     * Requeues every inbox envelope whose claim lease expired, so a message
     * claimed by a claimant that died before consuming it returns to the
     * claimable set. Returns the affected message ids; empty when nothing
     * lapsed. Idempotent: an envelope already requeued is not touched again.
     *
     * [reconcileLeases] reconciles node liveness and deliberately keeps its own
     * contract (it returns stale node ids), so message claims are reconciled by
     * this separate entry point.
     */
    @Synchronized
    fun reconcileMessageClaims(nowMillis: Long = clock()): List<String> {
        val expired = inbox.filter { it.claimedAtMillis != null && !isClaimLeaseActive(it, nowMillis) }
        expired.forEach { requeueExpiredClaim(it, nowMillis) }
        return expired.map { it.id }
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
        // [T-android-conversation-scope] A message can also travel DOWN an
        // ancestry line, not only across one delegation edge. `createChild` mints
        // exactly one PARENT_CHILD edge — parent to DIRECT child — so "the main
        // agent sends to its third-level sub-agent" (request.md:111) had no edge
        // that could authorize it even though the target is an ordinary
        // descendant, and the same conversation across two root generations had
        // none either. Descending inside one conversation grants exactly the
        // permission the direct edge would have granted, and never the reverse
        // direction: a sibling, a parent, a peer and another conversation are
        // still refused. Team delivery is deliberately outside this — a peer edge
        // is a different relationship with its own rule — so the TEAM_PEER branch
        // below is untouched.
        val downwardWithinConversation = isWithinConversationScope(from, to)
        val authorized = when (delivery) {
            RuntimeDelivery.TEAM_PEER -> topologyEdges.values.any {
                it.fromNodeId == fromNodeId && it.toNodeId == toNodeId &&
                    it.kind == RuntimeEdgeKind.TEAM_PEER && it.allows(permission)
            }
            RuntimeDelivery.NOTIFY -> downwardWithinConversation || topologyEdges.values.any {
                it.fromNodeId == fromNodeId && it.toNodeId == toNodeId &&
                    it.kind == RuntimeEdgeKind.PARENT_CHILD && it.allows(permission)
            } || subscriptions.values.any {
                it.subscriberNodeId == toNodeId && it.publisherNodeId == fromNodeId &&
                    it.active && RuntimeEdgePermission.NOTIFY in it.permissions
            }
            else -> downwardWithinConversation || topologyEdges.values.any {
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
        appendReceipt(
            RuntimeMessageReceipt(
                id = UUID.randomUUID().toString(), messageId = message.id,
                fromNodeId = fromNodeId, toNodeId = toNodeId, delivery = effective,
                accepted = true, createdAtMillis = now,
                reason = if (effective != delivery) "delivery window closed; queued" else null,
                status = RuntimeReceiptStatus.ENQUEUED,
            )
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

    /**
     * [team-peer-mesh] Materialize the mesh a Team mode team is supposed to have:
     * [nodeId] and every other Team-mode node under the same root, connected in
     * BOTH directions, so any member can address any other member.
     *
     * Why this exists instead of leaving it to a caller: the capability is stated
     * as a property of the team, not as an action anyone has to remember.
     * request.md:5 — "all sub-agents can communicate with each other"; request.md:160
     * — "the whole team knows each other ... they build [addresses] for themselves,
     * like IP addresses, so they can interact". A team whose members each hold a
     * mailbox but no route is exactly the traditional mode the requirement
     * contrasts with ("no address, no direction"), so the mesh is created when the
     * team is created, not on a second, optional call.
     *
     * Where it is called: the two moments a team changes shape — [setDelegationMode]
     * turning a root into Team mode (every member becomes a peer at once) and
     * [createChild] adding a member into an already-Team team (the new member must
     * learn every existing peer, and they must learn it). Nothing else calls this,
     * and in particular a TRADITIONAL dispatch never does: request.md:5 — without
     * `/team` the sub-agent mode is the default one, which has no addresses.
     *
     * Direction is deliberately both ways. Peer delivery is directed — [send]
     * matches `fromNodeId -> toNodeId`, and [RuntimeChildRunner] mirrors an inbound
     * TEAM_PEER back along the reply direction — so a one-way mesh would make an
     * answer undeliverable in exactly the direction that would answer it.
     *
     * Authorization is NOT re-implemented here: every edge is requested from
     * [addTeamPeerEdge], which stays the single owner of "both nodes are Team and
     * share a root". This function only decides WHICH pairs should be linked and
     * which are already linked.
     *
     * Idempotence: per direction, an edge that is already active and already carries
     * SEND grants everything the mesh wants to grant, so it is left alone and
     * reported as pre-existing. Without that check a repeat call would stack a
     * duplicate edge every time (`addTopologyEdge` mints a fresh id on each call),
     * and the topology would grow with the number of dispatches rather than the
     * number of members. The observable contract is therefore: a direction carries
     * at most one active SEND-bearing TEAM_PEER edge, and [RuntimeTeamPeerMesh]
     * .createdEdgeIds is empty for a member that was already fully linked.
     *
     * Revocation: a revoked direction stays revoked, and an unrelated member joining
     * does not silently resurrect it — this only ever links the member whose
     * membership just changed to its current peers, so a pair that both sides
     * already belong to is never revisited. It comes back when that node re-enters
     * Team mode, i.e. on the next Team dispatch, which is the moment the team is
     * being formed again and a missing route would be a surprise rather than a
     * decision. Revoking therefore means "these two must not talk right now" and
     * holds until the team is re-formed, instead of being a one-dispatch hiccup or a
     * permanent veto that would need a second, unstated mechanism to undo.
     */
    @Synchronized
    fun linkTeamPeers(nodeId: String): RuntimeTeamPeerMesh {
        val self = nodes[nodeId] ?: return RuntimeTeamPeerMesh(nodeId, emptyList(), emptyList())
        if (self.delegationMode != DelegationMode.TEAM) return RuntimeTeamPeerMesh(nodeId, emptyList(), emptyList())
        val peers = nodes.values
            .filter { it.id != nodeId && it.rootId == self.rootId && it.delegationMode == DelegationMode.TEAM }
            .map { it.id }
            .sorted()
        val created = mutableListOf<String>()
        peers.forEach { peer ->
            created += linkTeamPeerDirection(nodeId, peer)
            created += linkTeamPeerDirection(peer, nodeId)
        }
        return RuntimeTeamPeerMesh(nodeId, peers, created)
    }

    /**
     * One direction of [linkTeamPeers]: link `from -> to` unless that direction is
     * already authorized, and return the id of the edge that was created (empty when
     * the direction was already present).
     */
    private fun linkTeamPeerDirection(fromNodeId: String, toNodeId: String): List<String> {
        val alreadyAuthorized = topologyEdges.values.any {
            it.fromNodeId == fromNodeId && it.toNodeId == toNodeId &&
                it.kind == RuntimeEdgeKind.TEAM_PEER && it.allows(RuntimeEdgePermission.SEND)
        }
        if (alreadyAuthorized) return emptyList()
        val receipt = addTeamPeerEdge(fromNodeId, toNodeId, setOf(RuntimeEdgePermission.SEND))
        return if (receipt.accepted) listOfNotNull(receipt.edgeId) else emptyList()
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
        val message = RuntimeTranscriptMessage(
            id = messageId,
            nodeId = nodeId,
            role = role,
            content = content,
            createdAtMillis = createdAtMillis,
            metadata = metadata,
        )
        transcripts.getOrPut(nodeId) { mutableListOf() } += message
        transcriptChars[nodeId] = transcriptChars.getOrDefault(nodeId, 0) + message.retainedChars()
        evictTranscriptOverflow(nodeId)
        record(nodeId, "transcript_appended", createdAtMillis, messageId)
        return true
    }

    @Synchronized
    fun transcript(nodeId: String): List<RuntimeTranscriptMessage> = transcripts[nodeId].orEmpty().toList()

    /**
     * What this node's transcript holds and what the retention budget took from
     * it — the only honest way to read [transcript] as "complete".
     *
     * Returns `null` when [nodeId] is not a node of this tree. That is a real
     * third state and not a variation of "empty": `null` = "there is no such
     * node", a value with [RuntimeTranscriptRetention.truncated] `false` = "this
     * is genuinely all of it", and a value with `truncated` `true` = "older
     * messages were evicted, here is how many". Reading [transcript] alone
     * cannot tell the last two apart, which is exactly why this accessor
     * exists.
     */
    @Synchronized
    fun transcriptRetention(nodeId: String): RuntimeTranscriptRetention? {
        if (nodes[nodeId] == null) return null
        val eviction = transcriptEvictions[nodeId]
        return RuntimeTranscriptRetention(
            retainedMessages = transcripts[nodeId]?.size ?: 0,
            retainedChars = transcriptChars.getOrDefault(nodeId, 0),
            droppedMessages = eviction?.messages ?: 0,
            droppedChars = eviction?.chars ?: 0,
        )
    }

    /**
     * Apply the *current* [RuntimeTreeConfig] retention budget to transcripts
     * that are already in memory, and report how many messages that freed.
     *
     * Appends enforce the budget on their own; this exists for the other order
     * — the app lowering a budget on a live tree — because a ceiling that only
     * applies to future writes cannot reclaim memory that has already been
     * spent. Returns 0 when nothing needed evicting, so "called and there was
     * nothing to do" stays distinguishable from "called and it worked".
     */
    @Synchronized
    fun reclaimTranscripts(): Int {
        val before = transcriptEvictions.values.sumOf { it.messages }
        transcripts.keys.toList().forEach { evictTranscriptOverflow(it) }
        return transcriptEvictions.values.sumOf { it.messages } - before
    }

    /**
     * Evict oldest-first until both budgets hold, keeping at least the newest
     * message: the message currently being appended is never a casualty of its
     * own arrival, so [appendTranscript] cannot silently swallow a write.
     *
     * Cost is proportional to what is actually evicted, not to the ceiling; the
     * steady state under a full node is one `removeAt(0)` per append.
     */
    private fun evictTranscriptOverflow(nodeId: String) {
        val list = transcripts[nodeId] ?: return
        var chars = transcriptChars.getOrDefault(nodeId, 0)
        val maxMessages = config.maxTranscriptMessagesPerNode
        val maxChars = config.maxTranscriptCharsPerNode
        if (list.size <= maxMessages && chars <= maxChars) return
        var doomedMessages = 0
        var doomedChars = 0
        var firstSurvivor = 0
        while (firstSurvivor < list.size - 1 &&
            (list.size - firstSurvivor > maxMessages || chars > maxChars)
        ) {
            val evicted = list[firstSurvivor]
            doomedMessages++
            doomedChars += evicted.retainedChars()
            firstSurvivor++
            chars -= evicted.retainedChars()
        }
        if (doomedMessages == 0) return
        list.subList(0, firstSurvivor).clear()
        transcriptChars[nodeId] = chars
        val ledger = transcriptEvictions.getOrPut(nodeId) { TranscriptEviction() }
        ledger.messages += doomedMessages
        ledger.chars += doomedChars
    }

    @Synchronized
    fun receipts(): List<RuntimeMessageReceipt> = deliveryReceipts.toList()

    /**
     * What the tree's delivery-receipt ledger holds, and what its budget has
     * already taken from it — the only honest way to read [receipts] as
     * "complete". `truncated` `false` means "this really is every receipt";
     * `truncated` `true` means older ones were evicted and by how many.
     *
     * Consumer status, stated plainly so nobody assumes otherwise: as of this
     * change **no production code calls this accessor**; only tests do. The same
     * facts are carried by [toJson]'s `ledgerRetention` block (which production
     * does write, though nothing reads that block back yet) and by the `text`
     * export's `ledgerEvicted=...` line. A future "your agent history was
     * trimmed" surface should read them from there rather than invent a second
     * source; until such a surface exists this accessor is a diagnostic and test
     * seam, not a live path.
     */
    @Synchronized
    fun receiptRetention(): RuntimeReceiptRetention = RuntimeReceiptRetention(
        retainedReceipts = deliveryReceipts.size,
        retainedChars = receiptChars,
        droppedReceipts = receiptEvictions.dropped,
        droppedChars = receiptEvictions.droppedChars,
    )

    /**
     * The control ledger's half of the same contract, plus the one fact the other
     * two ledgers do not have: [RuntimeControlReceiptRetention
     * .droppedIdempotencyKeys] says how many index entries left with the receipts
     * they pointed at.
     *
     * Consumer status, stated plainly so nobody assumes otherwise: as of this
     * change **no production code calls this accessor**; only tests do. The same
     * counts are carried by [toJson]'s `ledgerRetention` block (which production
     * does write) and by the `text` export's `ledgerEvicted=` line.
     */
    @Synchronized
    fun controlReceiptRetention(): RuntimeControlReceiptRetention = RuntimeControlReceiptRetention(
        retainedReceipts = controlReceipts.size,
        retainedChars = controlReceiptChars,
        droppedReceipts = controlReceiptEvictions.dropped,
        droppedChars = controlReceiptEvictions.droppedChars,
        droppedIdempotencyKeys = controlReceiptEvictions.droppedIdempotencyKeys,
    )

    /**
     * How many idempotency keys name an operation this ledger no longer holds.
     *
     * This is the invariant the control ledger's budget is built on, stated as a
     * number: `controlOperationIdByIdempotencyKey` is a *derived view* of
     * `controlReceipts`, so every value in it must be a live key of that map. A
     * non-zero answer means the index has outlived the ledger it indexes — the
     * failure mode in which a retried stop looks recognised and then is not, and
     * in which the index keeps growing without bound while the receipts it points
     * into are capped.
     *
     * Read as a whole-map scan on purpose: it is a diagnostic seam (no production
     * caller; tests and a debugging session only), and an incrementally
     * maintained counter is exactly the kind of thing that can drift away from
     * the fact it describes — which is the class of bug it exists to detect.
     */
    @Synchronized
    fun danglingIdempotencyKeyCount(): Int =
        controlOperationIdByIdempotencyKey.values.count { it !in controlReceipts }

    /**
     * What the tree's event ledger holds, and what its budget has already taken
     * from it. See [receiptRetention]; the same distinction applies, including
     * the consumer status note — no production code calls this yet.
     *
     * The counts are the *whole tree's* ledger, which is what the budget is
     * measured against. For one node's events use [eventsOf], whose `null`
     * answers "is this even a node of this tree" — a question this accessor
     * cannot answer, because the tree it describes always exists.
     */
    @Synchronized
    fun eventRetention(): RuntimeEventRetention = RuntimeEventRetention(
        retainedEvents = events.size,
        retainedChars = eventChars,
        droppedEvents = eventEvictions.dropped,
        droppedChars = eventEvictions.droppedChars,
    )

    /**
     * Apply the *current* [RuntimeTreeConfig] ledger budgets to everything
     * already in memory — events, delivery receipts and the control (stop)
     * ledger with the idempotency index that belongs to it — and report how many
     * entries that freed.
     *
     * All three ledgers are reclaimed through this one call rather than through
     * three call sites: a ceiling that applies to memory already spent has to be
     * applied in one place, or the ledgers start diverging in *when* their
     * ceilings take effect — the same defect as a budget one write path can walk
     * around, one level up.
     *
     * The append paths enforce their budgets on their own; this exists for the
     * other orders, because a ceiling that only applies to future writes cannot
     * reclaim memory that has already been spent:
     *
     *  - **Restore.** [restoreJson] calls this once it has loaded everything, so
     *    a tree loaded from a file becomes bounded immediately instead of only
     *    at its next append. This is the reachable case that matters most: the
     *    ceilings are new, so every tree already on disk was written without
     *    them and is therefore over budget by definition.
     *  - **A budget lowered on a live tree.** A caller that assigns a smaller
     *    [RuntimeTreeConfig.maxEvents] &c. and wants it applied to what is
     *    already held calls this explicitly. Note that no such caller exists in
     *    this codebase today: [updateConfig] does not take the budget fields, so
     *    the budgets are not reachable through the tree's own config API — a
     *    future config path for them should call this.
     *
     * Returns `0` when nothing needed evicting, so "called and there was nothing
     * to do" stays distinguishable from "called and it worked". It does not
     * report failures as `0`: an untenable budget is rejected by the config
     * itself (see [RuntimeTreeConfig.init]) and any exception propagates to the
     * caller rather than being laundered into an empty success.
     */
    @Synchronized
    fun reclaimLedgers(): Int {
        val before = eventEvictions.dropped + receiptEvictions.dropped + controlReceiptEvictions.dropped
        evictEventOverflow()
        evictReceiptOverflow()
        evictControlReceiptOverflow()
        return (eventEvictions.dropped + receiptEvictions.dropped + controlReceiptEvictions.dropped) - before
    }

    /**
     * Events belonging to [nodeId], or `null` when [nodeId] is not a node of
     * this tree.
     *
     * That `null` is a genuine third state and not a variation of "empty":
     * `null` = "there is no such node" — an unknown query, which must not read
     * as "this node has no events"; a non-null empty list = "this node exists
     * and genuinely produced nothing"; and the list itself, read together with
     * [eventRetention]`.truncated`, tells "these are all of them" from "the
     * tree's ledger dropped older events, so this node's list may be partial".
     * A caller that collapsed the first two would make its own `?:` dead code.
     *
     * Consumer status, stated plainly so nobody assumes otherwise: as of this
     * change **no production code calls this**; only tests do. It exists so the
     * per-node question above has exactly one answer defined in one place (the
     * tree), rather than being re-derived by a caller that cannot see
     * [eventEvictions]. Nothing in the app asks it yet, and it is deliberately
     * not given a synthetic caller to look busy.
     */
    @Synchronized
    fun eventsOf(nodeId: String): List<RuntimeEvent>? =
        if (nodes[nodeId] == null) null else events.filter { it.nodeId == nodeId }

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
            // Additive: an older reader ignores these, and a tree persisted
            // before they existed restores to the defaults.
            put("maxTranscriptMessagesPerNode", config.maxTranscriptMessagesPerNode)
            put("maxTranscriptCharsPerNode", config.maxTranscriptCharsPerNode)
            // Written only when they differ from the defaults, so a tree running
            // on the shipped budgets still serializes to exactly the bytes it
            // serialized before these ceilings existed. Restoring a tree that
            // does not carry them keeps whatever config the store was opened
            // with, which is the same value.
            if (config.maxEvents != RuntimeTreeConfig.DEFAULT_EVENTS_LIMIT) {
                put("maxEvents", config.maxEvents)
            }
            if (config.maxEventChars != RuntimeTreeConfig.DEFAULT_EVENT_CHARS_LIMIT) {
                put("maxEventChars", config.maxEventChars)
            }
            if (config.maxDeliveryReceipts != RuntimeTreeConfig.DEFAULT_DELIVERY_RECEIPTS_LIMIT) {
                put("maxDeliveryReceipts", config.maxDeliveryReceipts)
            }
            if (config.maxDeliveryReceiptChars != RuntimeTreeConfig.DEFAULT_DELIVERY_RECEIPT_CHARS_LIMIT) {
                put("maxDeliveryReceiptChars", config.maxDeliveryReceiptChars)
            }
            if (config.maxControlReceipts != RuntimeTreeConfig.DEFAULT_CONTROL_RECEIPTS_LIMIT) {
                put("maxControlReceipts", config.maxControlReceipts)
            }
            if (config.maxControlReceiptChars != RuntimeTreeConfig.DEFAULT_CONTROL_RECEIPT_CHARS_LIMIT) {
                put("maxControlReceiptChars", config.maxControlReceiptChars)
            }
        })
        out.put("nodes", JSONArray().apply { nodes.values.forEach { put(nodeJson(it)) } })
        out.put("events", JSONArray().apply { events.forEach { put(eventJson(it)) } })
        out.put("inbox", JSONArray().apply { inbox.forEach { put(envelopeJson(it)) } })
        out.put("topologyEdges", JSONArray().apply { topologyEdges.values.forEach { put(edgeJson(it)) } })
        out.put("subscriptions", JSONArray().apply { subscriptions.values.forEach { put(subscriptionJson(it)) } })
        out.put("deliveryReceipts", JSONArray().apply { deliveryReceipts.forEach { put(receiptJson(it)) } })
        out.put("stopReceipts", JSONArray().apply { controlReceipts.values.forEach { put(stopReceiptJson(it)) } })
        out.put("transcripts", JSONArray().apply {
            transcripts.values.flatten().forEach { put(transcriptJson(it)) }
        })
        // Only nodes that actually lost something appear here, so a tree that
        // never hit the budget serializes to exactly the bytes it used to. When
        // a node is present, a reader of the restored tree can still tell
        // "truncated" from "complete" — without this, a restart would launder a
        // trimmed transcript into a transcript that looks whole.
        if (transcriptEvictions.isNotEmpty()) {
            out.put("transcriptRetention", JSONObject().apply {
                transcriptEvictions.forEach { (nodeId, eviction) ->
                    put(nodeId, JSONObject().apply {
                        put("droppedMessages", eviction.messages)
                        put("droppedChars", eviction.chars)
                    })
                }
            })
        }
        // Same rule as transcriptRetention above, extended to the two tree-wide
        // ledgers: only a ledger that actually lost entries appears here, so a
        // tree that never hit a ceiling serializes to exactly the bytes it used
        // to. When a ledger *is* present, a reader of the restored tree can
        // still tell "complete" from "trimmed" — without this, a restart would
        // launder a trimmed history into one that looks whole.
        if (eventEvictions.dropped > 0 || receiptEvictions.dropped > 0 || controlReceiptEvictions.dropped > 0) {
            out.put("ledgerRetention", JSONObject().apply {
                if (eventEvictions.dropped > 0) {
                    put("events", JSONObject().apply {
                        put("droppedEvents", eventEvictions.dropped)
                        put("droppedChars", eventEvictions.droppedChars)
                    })
                    // Per-node detail, written only when some node actually lost
                    // events, so an under-budget tree still serializes to exactly
                    // the bytes it used to.
                    if (eventDropsByNode.isNotEmpty()) {
                        put("eventsByNode", JSONObject().apply {
                            eventDropsByNode.forEach { (nodeId, dropped) -> put(nodeId, dropped) }
                        })
                    }
                }
                if (receiptEvictions.dropped > 0) {
                    put("deliveryReceipts", JSONObject().apply {
                        put("droppedReceipts", receiptEvictions.dropped)
                        put("droppedChars", receiptEvictions.droppedChars)
                    })
                }
                // The control ledger is the one whose budget also removed index
                // entries, so it reports three numbers rather than two: a reader
                // has to be able to see that the idempotency keys went with the
                // receipts, not that the receipts went and the keys stayed.
                if (controlReceiptEvictions.dropped > 0) {
                    put("stopReceipts", JSONObject().apply {
                        put("droppedReceipts", controlReceiptEvictions.dropped)
                        put("droppedChars", controlReceiptEvictions.droppedChars)
                        put("droppedIdempotencyKeys", controlReceiptEvictions.droppedIdempotencyKeys)
                    })
                }
            })
        }
        return out.toString()
    }

    @Synchronized
    fun restoreJson(raw: String): Boolean {
        return runCatching {
            val json = JSONObject(raw)
            nodes.clear(); children.clear(); events.clear(); inbox.clear()
            topologyEdges.clear(); subscriptions.clear(); deliveryReceipts.clear(); transcripts.clear()
            transcriptChars.clear(); transcriptEvictions.clear()
            eventChars = 0
            eventEvictions.dropped = 0; eventEvictions.droppedChars = 0
            eventDropsByNode.clear()
            receiptChars = 0
            receiptEvictions.dropped = 0; receiptEvictions.droppedChars = 0
            controlReceiptChars = 0
            controlReceiptEvictions.dropped = 0; controlReceiptEvictions.droppedChars = 0
            controlReceiptEvictions.droppedIdempotencyKeys = 0
            controlReceipts.clear(); controlOperationIdByIdempotencyKey.clear()
            pendingStopOperationByNodeId.clear()
            rootIds.clear()
            val configJson = json.optJSONObject("config")
            if (configJson != null) {
                // [T-android-tree-restore-coerce] Coerced, not required — the same rule
                // the eight budget keys below already follow, for the reason written out
                // verbatim down there: a stored value outside the current bounds must not
                // be what makes the tree unloadable.
                //
                // `require` here threw out of this body, so restoreJson returned false,
                // and `RuntimeTreeStore.loadLocked` answers a false by restoring
                // EMPTY_TREE_JSON and then persisting that empty tree over the file on the
                // next write. So an out-of-range number cost the user their entire runtime
                // tree — nodes, transcripts, inbox, control receipts — silently, with only
                // an AppLogger line. A tree written by a build whose limits differed, or
                // edited by hand, is exactly what this path exists to survive.
                config.maxDepth = configJson
                    .optInt("maxDepth", config.maxDepth)
                    .coerceIn(0, RuntimeTreeConfig.MAX_DEPTH_LIMIT)
                config.maxParallelSubagents = configJson
                    .optInt("maxParallelSubagents", config.maxParallelSubagents)
                    .coerceIn(1, RuntimeTreeConfig.MAX_PARALLEL_LIMIT)
                config.leaseMillis = configJson
                    .optLong("leaseMillis", config.leaseMillis)
                    .coerceAtLeast(1L)
                config.mode = runCatching { DelegationMode.valueOf(configJson.optString("mode")) }
                    .getOrDefault(config.mode)
                configRevision = configJson.optLong("configRevision", configRevision).coerceAtLeast(0L)
                // Coerced rather than required: a config file written before
                // these keys existed must keep restoring, and restoring must
                // never be the thing that decides a bad value becomes live.
                config.maxTranscriptMessagesPerNode = configJson
                    .optInt("maxTranscriptMessagesPerNode", config.maxTranscriptMessagesPerNode)
                    .coerceIn(1, RuntimeTreeConfig.MAX_TRANSCRIPT_MESSAGES_LIMIT)
                config.maxTranscriptCharsPerNode = configJson
                    .optInt("maxTranscriptCharsPerNode", config.maxTranscriptCharsPerNode)
                    .coerceIn(1, RuntimeTreeConfig.MAX_TRANSCRIPT_CHARS_LIMIT)
                config.maxEvents = configJson
                    .optInt("maxEvents", config.maxEvents)
                    .coerceIn(1, RuntimeTreeConfig.MAX_EVENTS_LIMIT)
                config.maxEventChars = configJson
                    .optInt("maxEventChars", config.maxEventChars)
                    .coerceIn(1, RuntimeTreeConfig.MAX_EVENT_CHARS_LIMIT)
                config.maxDeliveryReceipts = configJson
                    .optInt("maxDeliveryReceipts", config.maxDeliveryReceipts)
                    .coerceIn(1, RuntimeTreeConfig.MAX_DELIVERY_RECEIPTS_LIMIT)
                config.maxDeliveryReceiptChars = configJson
                    .optInt("maxDeliveryReceiptChars", config.maxDeliveryReceiptChars)
                    .coerceIn(1, RuntimeTreeConfig.MAX_DELIVERY_RECEIPT_CHARS_LIMIT)
                config.maxControlReceipts = configJson
                    .optInt("maxControlReceipts", config.maxControlReceipts)
                    .coerceIn(1, RuntimeTreeConfig.MAX_CONTROL_RECEIPTS_LIMIT)
                config.maxControlReceiptChars = configJson
                    .optInt("maxControlReceiptChars", config.maxControlReceiptChars)
                    .coerceIn(1, RuntimeTreeConfig.MAX_CONTROL_RECEIPT_CHARS_LIMIT)
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
            for (i in 0 until eventArray.length()) {
                val event = parseEvent(eventArray.getJSONObject(i))
                events += event
                eventChars += event.retainedChars()
            }
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
            for (i in 0 until receiptArray.length()) {
                val receipt = parseReceipt(receiptArray.getJSONObject(i))
                deliveryReceipts += receipt
                receiptChars += receipt.retainedChars()
            }
            val stopReceiptArray = json.optJSONArray("stopReceipts") ?: JSONArray()
            for (i in 0 until stopReceiptArray.length()) {
                val receipt = parseStopReceipt(stopReceiptArray.getJSONObject(i))
                // Written into the two maps directly rather than through
                // [storeControlReceipt], because restore applies the budgets once,
                // at the end: the file's own recorded eviction counts are read
                // back *after* every array has been parsed and *before* the current
                // ceilings add to them. Evicting per receipt here would spend the
                // budget before that read-back, and the read-back assigns rather
                // than accumulates — so this load's overflow would be laundered
                // into the number the file remembered.
                controlReceipts[receipt.operationId] = receipt
                controlOperationIdByIdempotencyKey[receipt.idempotencyKey] = receipt.operationId
                controlReceiptChars += receipt.retainedChars()
                if (receipt.accepted) {
                    receipt.affectedNodeIds
                        .filter { nodes[it]?.status == RuntimeNodeStatus.STOP_REQUESTED }
                        .forEach { pendingStopOperationByNodeId[it] = receipt.operationId }
                }
            }
            val transcriptArray = json.optJSONArray("transcripts") ?: JSONArray()
            for (i in 0 until transcriptArray.length()) {
                val message = parseTranscript(transcriptArray.getJSONObject(i))
                transcripts.getOrPut(message.nodeId) { mutableListOf() } += message
                transcriptChars[message.nodeId] =
                    transcriptChars.getOrDefault(message.nodeId, 0) + message.retainedChars()
            }
            // Restored last and deliberately without eviction: a tree that was
            // already inside its budget when it was persisted must come back
            // byte-for-byte, and one that was truncated must come back still
            // saying so. Restore is not the place to discover a smaller budget.
            json.optJSONObject("transcriptRetention")?.let { retentionJson ->
                retentionJson.keys().forEach { nodeId ->
                    val entry = retentionJson.optJSONObject(nodeId) ?: return@forEach
                    val dropped = entry.optInt("droppedMessages", 0)
                    if (dropped > 0) {
                        transcriptEvictions[nodeId] = TranscriptEviction(
                            messages = dropped,
                            chars = entry.optInt("droppedChars", 0),
                        )
                    }
                }
            }
            // The eviction counters are read back *before* the budgets are
            // applied at the end of this method, so a tree that was already
            // trimmed restores still saying so and its counts are added to
            // (never replaced by) whatever the current budgets then take.
            json.optJSONObject("ledgerRetention")?.let { ledgerJson ->
                ledgerJson.optJSONObject("events")?.let { entry ->
                    val dropped = entry.optInt("droppedEvents", 0)
                    if (dropped > 0) {
                        eventEvictions.dropped = dropped
                        eventEvictions.droppedChars = entry.optInt("droppedChars", 0)
                    }
                }
                // A sibling of `events`, not a child of it: the per-node tally is
                // a different question ("which node lost events") from the
                // tree-wide total, and nesting it would have made its level an
                // accident of formatting rather than of meaning.
                ledgerJson.optJSONObject("eventsByNode")?.let { byNode ->
                    byNode.keys().forEach { nodeId ->
                        val nodeDropped = byNode.optInt(nodeId, 0)
                        if (nodeDropped > 0) eventDropsByNode[nodeId] = nodeDropped
                    }
                }
                ledgerJson.optJSONObject("deliveryReceipts")?.let { entry ->
                    val dropped = entry.optInt("droppedReceipts", 0)
                    if (dropped > 0) {
                        receiptEvictions.dropped = dropped
                        receiptEvictions.droppedChars = entry.optInt("droppedChars", 0)
                    }
                }
                // The third ledger, and the only one that also has index entries
                // to remember losing. Read back under the same rule as the two
                // above: a file written before this budget existed carries no such
                // key, and "this tree never lost an index entry" is the truth for
                // it — which is why the default is an explicit `0` rather than a
                // guess derived from the receipts that are still there.
                ledgerJson.optJSONObject("stopReceipts")?.let { entry ->
                    val dropped = entry.optInt("droppedReceipts", 0)
                    if (dropped > 0) {
                        controlReceiptEvictions.dropped = dropped
                        controlReceiptEvictions.droppedChars = entry.optInt("droppedChars", 0)
                        controlReceiptEvictions.droppedIdempotencyKeys =
                            entry.optInt("droppedIdempotencyKeys", 0)
                    }
                }
            }
            val actualRoots = nodes.values.filter { it.parentId == null }.map { it.id }.toSet()
            rootIds.retainAll(actualRoots)
            rootIds.addAll(actualRoots)
            rootId = rootId?.takeIf(actualRoots::contains) ?: rootIds.firstOrNull()
            // Apply the current budgets to what was just loaded.
            //
            // A restored tree can be over its budgets with nothing appended yet,
            // and on this version that is not a corner case at all: these
            // ceilings are new, so *every* tree already on disk was written
            // without them. Loading one and leaving it over budget would hold the
            // whole legacy history in memory until some later append happened to
            // evict it — while every `toJson()` in between (and `RuntimeTreeStore
            // .update` performs two per state mutation) still serialized all of
            // it. Eviction-and-count is what makes the ceiling true of the tree
            // that was just loaded rather than only of the next write, and it is
            // the same "reclaim" the explicit [reclaimLedgers] performs.
            //
            // A tree that was already inside its budgets has nothing to evict,
            // so it still restores byte-for-byte and gains no retention block.
            reclaimLedgers()
            // The transcript ceiling is the third budget that is newer than every
            // tree already on disk, so the paragraph above applies to it verbatim:
            // without this line an over-budget transcript restored from a file
            // stays over budget until that node happens to append again, and every
            // `toJson()` in between (two per `RuntimeTreeStore.update`) still
            // serializes all of it. `reclaimTranscripts` returns 0 on a tree that is
            // already inside its budget, so an under-budget tree still restores
            // byte-for-byte.
            reclaimTranscripts()
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
        if (node.status.isTerminal()) return false
        val now = clock()
        nodes[nodeId] = node.copy(status = status, updatedAtMillis = now, leaseUntilMillis = if (status.isActive()) now + config.leaseMillis else null)
        record(nodeId, event, now)
        return true
    }

    private fun claim(nodeId: String, predicate: (RuntimeEnvelope) -> Boolean): RuntimeEnvelope? {
        val now = clock()
        val index = inbox.indexOfFirst {
            it.toNodeId == nodeId && predicate(it) && !isClaimLeaseActive(it, now)
        }
        if (index < 0) return null
        val previous = inbox[index]
        if (previous.claimedAtMillis != null) requeueExpiredClaim(previous, now)
        val claimed = previous.copy(claimedAtMillis = now)
        inbox[index] = claimed
        val receiptIndex = deliveryReceipts.indexOfLast { it.messageId == claimed.id }
        if (receiptIndex >= 0) {
            replaceReceiptAt(
                receiptIndex,
                deliveryReceipts[receiptIndex].copy(
                    status = RuntimeReceiptStatus.CLAIMED,
                    reason = "message claimed",
                ),
            )
        }
        record(nodeId, "message_claimed", now, claimed.id)
        return claimed
    }

    /**
     * True while an earlier claim still holds, i.e. while its holder may be
     * consuming the message right now. An unstamped envelope is never held.
     */
    private fun isClaimLeaseActive(message: RuntimeEnvelope, nowMillis: Long): Boolean {
        val claimedAt = message.claimedAtMillis ?: return false
        return nowMillis - claimedAt < MESSAGE_CLAIM_LEASE_MILLIS
    }

    /**
     * Clears an expired claim so the message can be claimed again, and makes the
     * transition visible: the receipt returns to ENQUEUED with a distinct reason
     * and the durable event ledger records the expiry. The envelope itself is
     * kept (same id, same position), never duplicated or removed.
     */
    private fun requeueExpiredClaim(message: RuntimeEnvelope, nowMillis: Long) {
        val index = inbox.indexOfFirst { it.id == message.id }
        if (index >= 0) inbox[index] = inbox[index].copy(claimedAtMillis = null)
        val receiptIndex = deliveryReceipts.indexOfLast { it.messageId == message.id }
        if (receiptIndex >= 0) {
            replaceReceiptAt(
                receiptIndex,
                deliveryReceipts[receiptIndex].copy(
                    status = RuntimeReceiptStatus.ENQUEUED,
                    reason = "claim lease expired; message requeued",
                ),
            )
        }
        record(message.toNodeId, "message_claim_lease_expired", nowMillis, message.id)
    }

    private fun descendantsOf(nodeId: String): List<String> = children[nodeId].orEmpty().flatMap { listOf(it) + descendantsOf(it) }

    private fun record(nodeId: String, kind: String, timestamp: Long, payload: String = ""): RuntimeEvent {
        val event = RuntimeEvent(UUID.randomUUID().toString(), nodeId, kind, timestamp, payload)
        events += event
        eventChars += event.retainedChars()
        evictEventOverflow()
        return event
    }

    /**
     * Single producer for the receipt ledger. Centralized so the retention
     * budget cannot be bypassed by one of the three call sites that append a
     * receipt — `send`, [rejectDelivery] and the stop-notification enqueue —
     * because a budget that one path can walk around is not a budget.
     */
    private fun appendReceipt(receipt: RuntimeMessageReceipt) {
        deliveryReceipts += receipt
        receiptChars += receipt.retainedChars()
        evictReceiptOverflow()
    }

    /**
     * The only way a receipt already in the ledger may change.
     *
     * The claim and lease-expiry paths rewrite a receipt's `reason` in place,
     * and `reason` is a *counted* field — so an edit that bypassed the cache
     * would leave [receiptChars] claiming a size the ledger no longer has, and a
     * budget that reads a stale total is a budget that stops firing: the
     * character ceiling could be exceeded in truth while the cached total still
     * looked under it. Centralizing the edit is the fix, not "remember to adjust
     * the counter at each call site" — the original defect was exactly a call
     * site that did not.
     */
    private fun replaceReceiptAt(index: Int, replacement: RuntimeMessageReceipt) {
        val previous = deliveryReceipts[index]
        receiptChars += replacement.retainedChars() - previous.retainedChars()
        deliveryReceipts[index] = replacement
        // A reason string is caller-supplied, so an edit can grow a receipt past
        // the ceiling the same way an append can.
        evictReceiptOverflow()
    }

    /**
     * What one event costs against [RuntimeTreeConfig.maxEventChars].
     *
     * All three text fields are counted because all three are retained in
     * memory and written to the tree JSON; a budget that ignored `payload`
     * would keep exactly the growing thing it exists to bound.
     */
    private fun RuntimeEvent.retainedChars(): Int = nodeId.length + kind.length + payload.length

    /**
     * What one receipt costs against [RuntimeTreeConfig.maxDeliveryReceiptChars].
     * `reason` is nullable and is the only free-text field a receipt carries.
     */
    private fun RuntimeMessageReceipt.retainedChars(): Int =
        fromNodeId.length + toNodeId.length + (reason?.length ?: 0)

    /**
     * Evict the oldest events until both event budgets hold, keeping the newest
     * event: the event currently being appended is never a casualty of its own
     * arrival, so [record] cannot silently swallow the evidence of the very
     * transition that caused it.
     *
     * Cost is proportional to what is actually evicted, not to the ceiling; the
     * steady state under a full ledger is one `removeAt(0)` per append.
     */
    private fun evictEventOverflow() {
        val maxEvents = config.maxEvents
        val maxChars = config.maxEventChars
        if (events.size <= maxEvents && eventChars <= maxChars) return
        var doomedEvents = 0
        var doomedChars = 0
        var firstSurvivor = 0
        while (firstSurvivor < events.size - 1 &&
            (events.size - firstSurvivor > maxEvents || eventChars > maxChars)
        ) {
            val evicted = events[firstSurvivor]
            val cost = evicted.retainedChars()
            // The budget is per tree, but the *question a reader asks* is per
            // node: "is this node's list of events complete". Without this
            // per-node tally the only available signal is the tree-wide one, and
            // a node whose own events were never touched would then be reported
            // as trimmed — or, worse, a node whose events were all evicted would
            // be indistinguishable from a node that never had any.
            eventDropsByNode[evicted.nodeId] = (eventDropsByNode[evicted.nodeId] ?: 0) + 1
            doomedEvents++
            doomedChars += cost
            firstSurvivor++
            eventChars -= cost
        }
        if (doomedEvents == 0) return
        events.subList(0, firstSurvivor).clear()
        eventEvictions.dropped += doomedEvents
        eventEvictions.droppedChars += doomedChars
    }

    /**
     * Counterpart of [evictEventOverflow] for the receipt ledger: oldest first,
     * newest never evicted, every eviction counted.
     */
    private fun evictReceiptOverflow() {
        val maxReceipts = config.maxDeliveryReceipts
        val maxChars = config.maxDeliveryReceiptChars
        if (deliveryReceipts.size <= maxReceipts && receiptChars <= maxChars) return
        var doomedReceipts = 0
        var doomedChars = 0
        var firstSurvivor = 0
        while (firstSurvivor < deliveryReceipts.size - 1 &&
            (deliveryReceipts.size - firstSurvivor > maxReceipts || receiptChars > maxChars)
        ) {
            val evicted = deliveryReceipts[firstSurvivor]
            val cost = evicted.retainedChars()
            doomedReceipts++
            doomedChars += cost
            firstSurvivor++
            receiptChars -= cost
        }
        if (doomedReceipts == 0) return
        deliveryReceipts.subList(0, firstSurvivor).clear()
        receiptEvictions.dropped += doomedReceipts
        receiptEvictions.droppedChars += doomedChars
    }

    /**
     * Single writer for the control ledger **as a unit**.
     *
     * That ledger is not one data structure but two — `controlReceipts` holds the
     * stop receipts and `controlOperationIdByIdempotencyKey` is the index
     * [stopDescendant] answers a retried stop from — and the second is only ever
     * meaningful as a view of the first. Every write therefore goes through here,
     * for the same reason [appendReceipt] exists one ledger over: a budget that
     * one path can walk around is not a budget, and an index one path can leave
     * behind is not an index. Both maps are written, then the budget is applied to
     * both at once.
     *
     * Upsert rather than append: the operation id is this ledger's key, and the
     * abort path rewrites an existing receipt's `stateAfter` through this method
     * too — an in-place edit that skipped the accounting is exactly the defect
     * `replaceReceiptAt` was centralised to prevent for the delivery ledger.
     */
    private fun storeControlReceipt(receipt: RuntimeStopReceipt) {
        controlReceipts[receipt.operationId]?.let { previous ->
            controlReceiptChars -= previous.retainedChars()
        }
        controlReceipts[receipt.operationId] = receipt
        // Registering a *new* key for an operation already stored is the
        // documented way a caller retries one stop under a fresh idempotency key,
        // and has to keep resolving; that is also why one operation id can be
        // named by several keys, and why the eviction below deletes by value.
        controlOperationIdByIdempotencyKey[receipt.idempotencyKey] = receipt.operationId
        controlReceiptChars += receipt.retainedChars()
        evictControlReceiptOverflow()
    }

    /**
     * What one stop receipt costs against [RuntimeTreeConfig.maxControlReceiptChars].
     *
     * Same shape as the delivery ledger's: the two node ids that answer *who asked*
     * and *who was stopped*, plus `reason`, the only free-text field a stop receipt
     * carries. The capability snapshots are deliberately not counted — they are
     * fixed-field structures whose size does not follow anything a caller writes,
     * so charging them here would turn this into a second copy of the receipt
     * budget instead of an independent rail.
     */
    private fun RuntimeStopReceipt.retainedChars(): Int =
        actorNodeId.length + targetNodeId.length + (reason?.length ?: 0)

    /**
     * Counterpart of [evictReceiptOverflow] for the control ledger: oldest first,
     * newest never evicted — and, uniquely among the three ledgers, the index is
     * reclaimed together with the receipts it points at.
     *
     * The index deletion is **by value**: one receipt may be reachable under
     * several idempotency keys (that is exactly what [stopDescendant]'s "same
     * operation, new key" branch produces), so there is no positional pairing
     * between the two maps to walk. Every key whose *value* is one of the doomed
     * operation ids leaves, in one pass. That is what keeps "every value in the
     * index is a live key of `controlReceipts`" true after every eviction — the
     * property [RuntimeSessionTree.danglingIdempotencyKeyCount] measures.
     *
     * Walking the map and collecting first, instead of fetching keys by position,
     * is what keeps this O(1) in the common case: a `LinkedHashMap` has no index
     * access, so copying its keys on every append would make the append path
     * quadratic in the ceiling — the same trap the running character totals exist
     * to avoid. The loop stops at the first entry the budget can keep, and the
     * last entry is never even considered.
     *
     * Oldest-first is the friendly order for an idempotency window: the newest
     * operation — the one whose retry is most likely still in flight — is always
     * still answerable. A retried stop that arrives after its receipt has left the
     * window *is* carried out again; that is the unavoidable cost of a bounded
     * window, and it is why the counts below are persisted rather than the window
     * being pretended unlimited. What this design refuses to do is leave the index
     * claiming that a recognition will happen when the ledger can no longer
     * answer.
     */
    private fun evictControlReceiptOverflow() {
        val maxReceipts = config.maxControlReceipts
        val maxChars = config.maxControlReceiptChars
        if (controlReceipts.size <= maxReceipts && controlReceiptChars <= maxChars) return
        var retained = controlReceipts.size
        var chars = controlReceiptChars
        var doomedChars = 0
        val doomedOperationIds = LinkedHashSet<String>()
        for (entry in controlReceipts.entries) {
            // The newest receipt is never a casualty of its own arrival: it is the
            // one entry the caller that just wrote it must be able to read back.
            if (retained <= 1) break
            if (retained <= maxReceipts && chars <= maxChars) break
            val cost = entry.value.retainedChars()
            doomedOperationIds.add(entry.key)
            doomedChars += cost
            chars -= cost
            retained--
        }
        if (doomedOperationIds.isEmpty()) return
        doomedOperationIds.forEach { controlReceipts.remove(it) }
        val indexBefore = controlOperationIdByIdempotencyKey.size
        controlOperationIdByIdempotencyKey.entries.removeIf { it.value in doomedOperationIds }
        controlReceiptEvictions.dropped += doomedOperationIds.size
        controlReceiptEvictions.droppedChars += doomedChars
        controlReceiptEvictions.droppedIdempotencyKeys +=
            indexBefore - controlOperationIdByIdempotencyKey.size
        controlReceiptChars -= doomedChars
    }

    private fun isAncestor(ancestorNodeId: String, nodeId: String): Boolean {
        var current = nodes[nodeId]?.parentId
        while (current != null) {
            if (current == ancestorNodeId) return true
            current = nodes[current]?.parentId
        }
        return false
    }

    /**
     * [T-android-conversation-scope] Whether [actor] may act on [target] as one of
     * its own, across the generations a crash or a re-run splits a conversation
     * into.
     *
     * The conversation identity is [ConversationIdProtocol.conversationIdOf] — the
     * package's single definition of "which conversation is this runtime node
     * part of", and the identity
     * [RuntimeTopologySnapshot.descendantsForChatSession] already walks by — so a
     * reopen that mints `"$sessionId#run-N"` does not by itself move the target out
     * of the actor's reach.
     *
     * What a crash must NOT buy is authority, so the rule stays narrower than
     * "same conversation":
     *
     *  - the target is never the actor itself (no self-stop, self-delete or
     *    self-send), and never a generation root — those are this conversation's
     *    own main agent at another point in time, and one agent acting on another
     *    main agent is not a relationship this tree endorses;
     *  - a node that is not a root keeps the strict-ancestor rule it always had, so
     *    a sub-agent still cannot reach a sibling or its own parent;
     *  - only a root — the conversation's main agent, the one actor request.md:9
     *    is about — additionally reaches the members of the OTHER generations,
     *    which is exactly the run union
     *    [RuntimeSessionCoordinator.supervisedDescendants] already reports.
     */
    private fun isWithinConversationScope(actor: RuntimeSessionNode, target: RuntimeSessionNode): Boolean {
        if (actor.id == target.id) return false
        if (target.parentId == null) return false
        if (ConversationIdProtocol.conversationIdOf(actor.rootId) !=
            ConversationIdProtocol.conversationIdOf(target.rootId)
        ) {
            return false
        }
        return actor.parentId == null || isAncestor(actor.id, target.id)
    }

    /**
     * [T-android-conversation-scope] Whether a caller-named `root_id` really names
     * a root of the conversation [nodeRootId] belongs to.
     *
     * The tool schema calls the argument an "expected runtime root ID", so a caller
     * that read it from `supervise_descendants` hands over the node's own
     * generation root — which, for a descendant a crash left behind, is the OLD
     * generation. Accepting only `== actor.rootId` therefore turned the honest
     * answer into a refusal; accepting only roots of the same conversation still
     * refuses a name that belongs to another conversation, a node that is not a
     * root at all, or an id that does not exist.
     */
    private fun isScopedRootOf(namedRootId: String, nodeRootId: String): Boolean =
        nodes[namedRootId]?.parentId == null &&
            ConversationIdProtocol.conversationIdOf(namedRootId) ==
            ConversationIdProtocol.conversationIdOf(nodeRootId)

    private fun authorizeDescendantStop(
        actor: RuntimeSessionNode?,
        target: RuntimeSessionNode?,
        requestedRootId: String?,
    ): String? {
        if (actor == null || target == null) return "unknown actor or target"
        if (ConversationIdProtocol.conversationIdOf(actor.rootId) !=
            ConversationIdProtocol.conversationIdOf(target.rootId)
        ) {
            return "cross-root stop is not authorized"
        }
        if (requestedRootId != null && !isScopedRootOf(requestedRootId, actor.rootId)) {
            return "cross-root stop is not authorized"
        }
        if (actor.id == target.id || !isWithinConversationScope(actor, target)) {
            return "stop is limited to strict ancestors, plus the conversation root stopping any of its descendants"
        }
        return null
    }

    private fun stopAuditPayload(
        request: RuntimeStopRequest,
        actorNodeId: String,
        targetNodeId: String,
        rootId: String?,
        stateBefore: RuntimeNodeStatus?,
        stateAfter: RuntimeNodeStatus?,
        reason: String?,
        actorSnapshot: AgentCapabilitySnapshot?,
        targetSnapshot: AgentCapabilitySnapshot?,
        affectedNodeIds: List<String> = emptyList(),
    ): String = JSONObject().apply {
        put("operationId", request.operationId)
        put("idempotencyKey", request.idempotencyKey)
        put("type", RuntimeControlOperationType.STOP_DESCENDANT.name)
        put("actorNodeId", actorNodeId)
        put("targetNodeId", targetNodeId)
        put("rootId", rootId)
        put("stateBefore", stateBefore?.name)
        put("stateAfter", stateAfter?.name)
        put("affectedNodeIds", JSONArray(affectedNodeIds))
        put("reason", reason)
        put("actorCapabilitySnapshot", snapshotJson(actorSnapshot))
        put("targetCapabilitySnapshot", snapshotJson(targetSnapshot))
    }.toString()

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
        put("birthChain", JSONArray(node.birthChain))
        put("leaseUntilMillis", node.leaseUntilMillis); put("abnormal", node.abnormal)
        put("model", JSONObject().apply { put("provider", node.model.provider); put("model", node.model.model); put("note", node.model.note); put("capabilities", JSONArray(node.model.capabilities.toList())) })
    }

    private fun eventJson(event: RuntimeEvent) = JSONObject().apply {
        put("id", event.id); put("nodeId", event.nodeId); put("kind", event.kind); put("timestampMillis", event.timestampMillis); put("payload", event.payload)
    }

    private fun envelopeJson(message: RuntimeEnvelope) = JSONObject().apply {
        put("id", message.id); put("fromNodeId", message.fromNodeId); put("toNodeId", message.toNodeId); put("delivery", message.delivery.name); put("payload", message.payload); put("createdAtMillis", message.createdAtMillis); put("claimed", message.claimed); put("claimedAtMillis", message.claimedAtMillis); put("taskIntent", message.taskIntent)
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

    private fun stopReceiptJson(receipt: RuntimeStopReceipt) = JSONObject().apply {
        put("operationId", receipt.operationId)
        put("idempotencyKey", receipt.idempotencyKey)
        put("type", receipt.type.name)
        put("actorNodeId", receipt.actorNodeId)
        put("targetNodeId", receipt.targetNodeId)
        put("rootId", receipt.rootId)
        put("accepted", receipt.accepted)
        put("stateBefore", receipt.stateBefore?.name)
        put("stateAfter", receipt.stateAfter?.name)
        put("affectedNodeIds", JSONArray(receipt.affectedNodeIds))
        put("actorCapabilitySnapshot", snapshotJson(receipt.actorCapabilitySnapshot))
        put("targetCapabilitySnapshot", snapshotJson(receipt.targetCapabilitySnapshot))
        put("createdAtMillis", receipt.createdAtMillis)
        put("reason", receipt.reason)
        put("eventId", receipt.eventId)
    }

    private fun parseStopReceipt(json: JSONObject): RuntimeStopReceipt = RuntimeStopReceipt(
        operationId = json.optString("operationId"),
        idempotencyKey = json.optString("idempotencyKey"),
        type = runCatching { RuntimeControlOperationType.valueOf(json.optString("type")) }
            .getOrDefault(RuntimeControlOperationType.STOP_DESCENDANT),
        actorNodeId = json.optString("actorNodeId"),
        targetNodeId = json.optString("targetNodeId"),
        rootId = json.optString("rootId").takeIf { it.isNotBlank() && it != "null" },
        accepted = json.optBoolean("accepted"),
        stateBefore = json.optString("stateBefore").takeIf { it.isNotBlank() && it != "null" }
            ?.let { value -> runCatching { RuntimeNodeStatus.valueOf(value) }.getOrNull() },
        stateAfter = json.optString("stateAfter").takeIf { it.isNotBlank() && it != "null" }
            ?.let { value -> runCatching { RuntimeNodeStatus.valueOf(value) }.getOrNull() },
        affectedNodeIds = json.optJSONArray("affectedNodeIds")?.let { array ->
            buildList { for (i in 0 until array.length()) add(array.optString(i)) }
        }.orEmpty(),
        actorCapabilitySnapshot = parseSnapshot(json.optJSONObject("actorCapabilitySnapshot")),
        targetCapabilitySnapshot = parseSnapshot(json.optJSONObject("targetCapabilitySnapshot")),
        createdAtMillis = json.optLong("createdAtMillis"),
        reason = json.optString("reason").takeIf { it.isNotBlank() && it != "null" },
        eventId = json.optString("eventId").takeIf { it.isNotBlank() && it != "null" },
    )

    private fun nodeExportJson(node: RuntimeSessionNode) = JSONObject().apply {
        put("node", nodeJson(node))
        put("transcript", JSONArray(transcripts[node.id].orEmpty().map(::transcriptJson)))
        // Per-node export is read on its own, without the tree's `nodes` block
        // in hand, so "this node's transcript is complete" and "this node's
        // transcript was trimmed" have to be decidable from this object alone.
        put("transcriptRetention", JSONObject().apply {
            put("retainedMessages", transcripts[node.id]?.size ?: 0)
            put("retainedChars", transcriptChars.getOrDefault(node.id, 0))
            put("droppedMessages", transcriptEvictions[node.id]?.messages ?: 0)
            put("droppedChars", transcriptEvictions[node.id]?.chars ?: 0)
        })
        // The ledger budget is per tree, but this document is per node and is
        // read on its own, so it has to answer *this node's* question: the count
        // is the events the budget took from this node, not the tree's total.
        // Always present (zeros included), like `transcriptRetention` above, so
        // "zero" and "field missing" can never be confused for each other.
        put("ledgerRetention", JSONObject().apply {
            put("retainedEvents", events.count { it.nodeId == node.id })
            put("droppedEvents", eventDropsByNode[node.id] ?: 0)
        })
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
            birthChain = json.optJSONArray("birthChain")?.let { array ->
                buildList { for (i in 0 until array.length()) add(array.optString(i)) }
            } ?: emptyList(),
        )
    }

    private fun parseEvent(json: JSONObject) = RuntimeEvent(
        json.optString("id"), json.optString("nodeId"), json.optString("kind"),
        json.optLong("timestampMillis"), json.optString("payload"),
    )

    private fun parseEnvelope(json: JSONObject) = RuntimeEnvelope(
        id = json.optString("id"), fromNodeId = json.optString("fromNodeId"), toNodeId = json.optString("toNodeId"),
        delivery = runCatching { RuntimeDelivery.valueOf(json.optString("delivery")) }.getOrDefault(RuntimeDelivery.QUEUE),
        payload = json.optString("payload"), createdAtMillis = json.optLong("createdAtMillis"),
        claimedAtMillis = if (json.has("claimedAtMillis") && !json.isNull("claimedAtMillis")) {
            json.optLong("claimedAtMillis")
        } else if (json.optBoolean("claimed")) {
            legacyClaimStamp()
        } else {
            null
        },
        taskIntent = json.optString("taskIntent"),
        capabilitySnapshot = parseSnapshot(json.optJSONObject("capabilitySnapshot")),
        senderCapabilitySnapshot = parseSnapshot(json.optJSONObject("senderCapabilitySnapshot")),
    )

    /**
     * Envelopes written before claim leases existed carry only the boolean
     * `claimed`, so the moment they were claimed is unknowable. They are stamped
     * exactly one lease in the past: already expired, hence claimable again. For
     * an old record, re-delivering the message once is strictly better than
     * leaving it invisible to every claim path forever.
     */
    private fun legacyClaimStamp(): Long = clock() - MESSAGE_CLAIM_LEASE_MILLIS

    private fun textExport(): String = buildString {
        appendLine("session-tree")
        nodes.values.filter { it.parentId == null }.forEach { appendNode(this, it.id, 0) }
        appendLine("events=${events.size} inbox=${inbox.count { !isClaimLeaseActive(it, clock()) }}")
        // A trimmed ledger must not read as a short but complete one: the
        // eviction counts are printed here for the same reason the per-node
        // transcript marker exists.
        appendLine(
            "ledgerBudget=${config.maxEvents} events / ${config.maxEventChars} chars, " +
                "${config.maxDeliveryReceipts} receipts / ${config.maxDeliveryReceiptChars} chars; " +
                "ledgerEvicted=${eventEvictions.dropped} events / ${receiptEvictions.dropped} receipts"
        )
        appendLine(
            "transcripts=${transcripts.values.sumOf { it.size }} messages retained; " +
                "transcriptBudget=${config.maxTranscriptMessagesPerNode} msgs / ${config.maxTranscriptCharsPerNode} chars per node; " +
                "transcriptEvicted=${transcriptEvictions.values.sumOf { it.messages }} messages across ${transcriptEvictions.size} node(s)"
        )
        // The third ledger, on the same terms — and this one has to report the
        // idempotency keys as well: a reader told only "3 receipts were evicted"
        // would come away thinking the tree still recognises the stop requests
        // those receipts answered. It does not, and the number of keys that left
        // with them is the honest way to say so.
        appendLine(
            "controlBudget=${config.maxControlReceipts} receipts / ${config.maxControlReceiptChars} chars; " +
                "controlEvicted=${controlReceiptEvictions.dropped} receipts / " +
                "${controlReceiptEvictions.droppedIdempotencyKeys} idempotency keys"
        )
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
        // A truncated node must never read as a short but complete one: the
        // marker goes above the retained lines so it cannot be missed by a
        // reader that stops at the end of the transcript.
        transcriptEvictions[node.id]?.let { eviction ->
            appendLine("transcript-truncated: oldest ${eviction.messages} message(s) / ${eviction.chars} char(s) evicted by the retention budget")
        }
        transcripts[node.id].orEmpty().forEach { message ->
            appendLine("[${message.createdAtMillis}] ${message.role}: ${message.content}")
            if (message.metadata.isNotBlank()) appendLine("metadata: ${message.metadata}")
        }
        // A node whose events were partly evicted must not read as a node that
        // simply had few events; the marker goes above the lines so a reader
        // that stops at the end cannot miss it.
        // A node whose events were partly evicted must not read as a node that
        // simply had few events. The count is *this node's*, not the tree's: the
        // tree-wide ledger can drop one node's events while leaving a sibling's
        // untouched, so a tree-wide test would mark a node that lost nothing and
        // tell a reader nothing useful about the node in hand.
        val droppedHere = eventDropsByNode[node.id] ?: 0
        if (droppedHere > 0) {
            appendLine("ledger-truncated: oldest $droppedHere event(s) of this node evicted by the tree's retention budget")
        }
        events.filter { it.nodeId == node.id }.forEach { appendLine("event ${it.timestampMillis} ${it.kind}: ${it.payload}") }
        inbox.filter { it.toNodeId == node.id || it.fromNodeId == node.id }.forEach {
            appendLine("message ${it.id} ${it.fromNodeId}->${it.toNodeId} ${it.delivery} claimed=${it.claimed}: ${it.payload}")
        }
    }


    private fun RuntimeNodeStatus.isActive() = this == RuntimeNodeStatus.STARTING || this == RuntimeNodeStatus.RUNNING || this == RuntimeNodeStatus.WAITING_CHILDREN

    /**
     * What one transcript message costs against [RuntimeTreeConfig.maxTranscriptCharsPerNode].
     *
     * Both fields are counted because both are retained in memory and both are
     * written to the tree JSON: a budget that ignored `metadata` would keep the
     * growing thing it is supposed to be bounding.
     */
    private fun RuntimeTranscriptMessage.retainedChars(): Int = content.length + metadata.length
    private fun RuntimeNodeStatus.isLeaseActive() = isActive() || this == RuntimeNodeStatus.STOP_REQUESTED
    private fun RuntimeNodeStatus.isTerminal() = this == RuntimeNodeStatus.STOP_REQUESTED || this == RuntimeNodeStatus.SUCCEEDED || this == RuntimeNodeStatus.FAILED || this == RuntimeNodeStatus.ABORTED || this == RuntimeNodeStatus.ABNORMAL_INTERRUPTION
}
