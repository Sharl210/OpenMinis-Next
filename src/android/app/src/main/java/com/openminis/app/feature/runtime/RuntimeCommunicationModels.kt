package com.openminis.app.feature.runtime

import java.security.MessageDigest
import java.util.UUID

/**
 * Stable, opaque address for a persisted OpenMinis conversation.
 *
 * The address is a locator only. It is deliberately not an authorization
 * credential; the runtime must authorize every lookup separately.
 */
@JvmInline
value class RuntimeConversationAddress private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        const val PREFIX = "openminis-conv:"
        private const val HASH_LENGTH = 64
        private val HASH_PATTERN = Regex("[0-9a-f]{$HASH_LENGTH}")

        /** Derive the same address for the same stable session id. */
        fun fromStableSessionId(stableSessionId: String): RuntimeConversationAddress {
            require(stableSessionId.isNotBlank()) { "stableSessionId must not be blank" }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(stableSessionId.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return RuntimeConversationAddress(PREFIX + digest)
        }

        /** Parse an address received from a model or user without authorizing it. */
        fun parse(raw: String): RuntimeConversationAddress? {
            if (!raw.startsWith(PREFIX)) return null
            val hash = raw.removePrefix(PREFIX)
            if (!HASH_PATTERN.matches(hash)) return null
            return RuntimeConversationAddress(raw)
        }

        fun isValid(raw: String): Boolean = parse(raw) != null
    }
}

data class RuntimeCommunicationPeer(
    val address: RuntimeConversationAddress,
    val sessionId: String,
    /** Runtime execution instance; never part of the stable conversation address. */
    val executionId: String? = null,
) {
    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(executionId == null || executionId.isNotBlank()) {
            "executionId must be blank or non-blank"
        }
    }
}

data class RuntimeCapabilitySnapshotVersion(
    val configRevision: Long,
    val capabilityRevision: Long,
) {
    init {
        require(configRevision >= 0) { "configRevision must be non-negative" }
        require(capabilityRevision >= 0) { "capabilityRevision must be non-negative" }
    }
}

enum class RuntimeCommunicationDirectoryMode { AGENT, TEAM }
enum class RuntimeCommunicationRouteKind { PARENT_CHILD, TEAM_PEER }

/**
 * Describes how a record was routed. This is metadata, not an authorization
 * decision; the runtime remains the authority for whether a route is allowed.
 */
data class RuntimeCommunicationDirectoryPolicy(
    val mode: RuntimeCommunicationDirectoryMode,
    val routeKind: RuntimeCommunicationRouteKind,
) {
    init {
        require(mode == RuntimeCommunicationDirectoryMode.TEAM ||
            routeKind == RuntimeCommunicationRouteKind.PARENT_CHILD) {
            "ordinary Agent directory cannot advertise a TEAM_PEER route"
        }
    }
}

enum class RuntimeCommunicationDirection { OUTBOUND, INBOUND }
enum class RuntimeCommunicationState { QUEUED, DELIVERED, CLAIMED, FAILED, REJECTED }

data class RuntimeCommunicationReplyRoute(
    val replyToRecordId: String? = null,
    val replyToMessageId: String? = null,
    val replyAddress: RuntimeConversationAddress? = null,
) {
    init {
        require(replyToRecordId == null || replyToRecordId.isNotBlank()) {
            "replyToRecordId must be blank or non-blank"
        }
        require(replyToMessageId == null || replyToMessageId.isNotBlank()) {
            "replyToMessageId must be blank or non-blank"
        }
        require(replyToRecordId != null || replyToMessageId != null || replyAddress != null) {
            "reply route must contain a target"
        }
    }
}

/**
 * Metadata-only communication record. It intentionally has no payload,
 * transcript, attachment, or media-byte field.
 */
data class RuntimeCommunicationMetadata(
    val recordId: String = UUID.randomUUID().toString(),
    val sender: RuntimeCommunicationPeer,
    val receiver: RuntimeCommunicationPeer,
    val originalMessageId: String? = null,
    val replyRoute: RuntimeCommunicationReplyRoute? = null,
    val direction: RuntimeCommunicationDirection,
    val timestampMillis: Long,
    val state: RuntimeCommunicationState,
    val summary: String,
    val senderCapabilityVersion: RuntimeCapabilitySnapshotVersion,
    val receiverCapabilityVersion: RuntimeCapabilitySnapshotVersion,
    val directoryPolicy: RuntimeCommunicationDirectoryPolicy,
) {
    init {
        require(recordId.isNotBlank()) { "recordId must not be blank" }
        require(originalMessageId == null || originalMessageId.isNotBlank()) {
            "originalMessageId must be blank or non-blank"
        }
        require(timestampMillis >= 0) { "timestampMillis must be non-negative" }
        require(summary.length <= MAX_SUMMARY_CHARS) {
            "summary exceeds $MAX_SUMMARY_CHARS characters"
        }
    }

    val metadataOnly: Boolean
        get() = true

    companion object {
        const val MAX_SUMMARY_CHARS = 512
    }
}

/** Opaque continuation token returned by [RuntimeCommunicationDirectory]. */
@JvmInline
value class RuntimeCommunicationCursor(val token: String)

data class RuntimeCommunicationQuery(
    val address: RuntimeConversationAddress? = null,
    val peerSessionId: String? = null,
    val summaryContains: String? = null,
    val direction: RuntimeCommunicationDirection? = null,
    val state: RuntimeCommunicationState? = null,
    val directoryPolicy: RuntimeCommunicationDirectoryPolicy? = null,
    val cursor: RuntimeCommunicationCursor? = null,
    val limit: Int = RuntimeCommunicationDirectory.DEFAULT_PAGE_LIMIT,
    val resultBudgetChars: Int = RuntimeCommunicationDirectory.DEFAULT_RESULT_BUDGET_CHARS,
) {
    init {
        require(peerSessionId == null || peerSessionId.isNotBlank()) {
            "peerSessionId must be blank or non-blank"
        }
        require(summaryContains == null || summaryContains.isNotBlank()) {
            "summaryContains must be blank or non-blank"
        }
    }
}

data class RuntimeCommunicationMetadataPage(
    val records: List<RuntimeCommunicationMetadata>,
    val nextCursor: RuntimeCommunicationCursor?,
    val totalMatchingRecords: Int,
    val metadataOnly: Boolean = true,
)

enum class RuntimeCommunicationQueryRejection {
    INVALID_CURSOR,
    UNAUTHORIZED_ACTOR,
    PAGE_LIMIT_EXCEEDED,
    RESULT_BUDGET_EXCEEDED,
    RECORD_NOT_FOUND,
    DETAIL_REQUIRES_EXPLICIT_READ,
}

sealed interface RuntimeCommunicationQueryResult {
    data class Accepted(val page: RuntimeCommunicationMetadataPage) : RuntimeCommunicationQueryResult
    data class Rejected(val reason: RuntimeCommunicationQueryRejection) : RuntimeCommunicationQueryResult
}

/** Explicit second-step request. It does not implicitly return transcript data. */
data class RuntimeCommunicationDetailRequest(
    val recordId: String,
    val explicitRead: Boolean = true,
) {
    init {
        require(recordId.isNotBlank()) { "recordId must not be blank" }
    }
}

sealed interface RuntimeCommunicationDetailResult {
    data class Accepted(val request: RuntimeCommunicationDetailRequest) : RuntimeCommunicationDetailResult
    data class Rejected(val reason: RuntimeCommunicationQueryRejection) : RuntimeCommunicationDetailResult
}

/**
 * Small in-memory metadata directory used by the runtime adapter and JVM
 * tests. It performs pagination and size budgeting only; it does not bypass
 * the runtime's authorization layer.
 */
class RuntimeCommunicationDirectory(
    private val maxPageLimit: Int = MAX_PAGE_LIMIT,
    private val maxResultBudgetChars: Int = MAX_RESULT_BUDGET_CHARS,
) {
    private val records = LinkedHashMap<String, RuntimeCommunicationMetadata>()
    private var revision = 0L

    init {
        require(maxPageLimit in 1..MAX_PAGE_LIMIT) { "maxPageLimit is out of bounds" }
        require(maxResultBudgetChars in 1..MAX_RESULT_BUDGET_CHARS) {
            "maxResultBudgetChars is out of bounds"
        }
    }

    @Synchronized
    fun append(record: RuntimeCommunicationMetadata): Boolean {
        val inserted = records.put(record.recordId, record) == null
        if (inserted) revision++
        return inserted
    }

    @Synchronized
    fun find(recordId: String): RuntimeCommunicationMetadata? = records[recordId]

    @Synchronized
    fun query(query: RuntimeCommunicationQuery): RuntimeCommunicationQueryResult {
        if (query.limit !in 1..maxPageLimit) {
            return RuntimeCommunicationQueryResult.Rejected(
                RuntimeCommunicationQueryRejection.PAGE_LIMIT_EXCEEDED,
            )
        }
        if (query.resultBudgetChars !in 1..maxResultBudgetChars) {
            return RuntimeCommunicationQueryResult.Rejected(
                RuntimeCommunicationQueryRejection.RESULT_BUDGET_EXCEEDED,
            )
        }
        val offset = decodeOffset(query.cursor) ?: if (query.cursor == null) 0 else {
            return RuntimeCommunicationQueryResult.Rejected(
                RuntimeCommunicationQueryRejection.INVALID_CURSOR,
            )
        }
        val matching = records.values.filter { record ->
            (query.address == null ||
                record.sender.address == query.address || record.receiver.address == query.address) &&
                (query.peerSessionId == null ||
                    record.sender.sessionId == query.peerSessionId ||
                    record.receiver.sessionId == query.peerSessionId) &&
                (query.summaryContains == null ||
                    record.summary.contains(query.summaryContains, ignoreCase = true)) &&
                (query.direction == null || record.direction == query.direction) &&
                (query.state == null || record.state == query.state) &&
                (query.directoryPolicy == null || record.directoryPolicy == query.directoryPolicy)
        }
        if (offset > matching.size) {
            return RuntimeCommunicationQueryResult.Rejected(
                RuntimeCommunicationQueryRejection.INVALID_CURSOR,
            )
        }
        val pageRecords = matching.drop(offset).take(query.limit)
        val estimate = pageRecords.sumOf(::estimateChars)
        if (estimate > query.resultBudgetChars) {
            return RuntimeCommunicationQueryResult.Rejected(
                RuntimeCommunicationQueryRejection.RESULT_BUDGET_EXCEEDED,
            )
        }
        val end = offset + pageRecords.size
        val next = if (end < matching.size) {
            RuntimeCommunicationCursor("v$revision-$end")
        } else {
            null
        }
        return RuntimeCommunicationQueryResult.Accepted(
            RuntimeCommunicationMetadataPage(
                records = pageRecords,
                nextCursor = next,
                totalMatchingRecords = matching.size,
            ),
        )
    }

    @Synchronized
    fun requestDetail(request: RuntimeCommunicationDetailRequest): RuntimeCommunicationDetailResult {
        if (records[request.recordId] == null) {
            return RuntimeCommunicationDetailResult.Rejected(
                RuntimeCommunicationQueryRejection.RECORD_NOT_FOUND,
            )
        }
        if (!request.explicitRead) {
            return RuntimeCommunicationDetailResult.Rejected(
                RuntimeCommunicationQueryRejection.DETAIL_REQUIRES_EXPLICIT_READ,
            )
        }
        return RuntimeCommunicationDetailResult.Accepted(request)
    }

    private fun decodeOffset(cursor: RuntimeCommunicationCursor?): Int? {
        if (cursor == null) return 0
        val match = CURSOR_PATTERN.matchEntire(cursor.token) ?: return null
        if (match.groupValues[1].toLongOrNull() != revision) return null
        return match.groupValues[2].toIntOrNull()?.takeIf { it >= 0 }
    }

    private fun estimateChars(record: RuntimeCommunicationMetadata): Int =
        256 + record.summary.length + record.recordId.length +
            record.sender.sessionId.length + record.receiver.sessionId.length

    companion object {
        const val DEFAULT_PAGE_LIMIT = 20
        const val DEFAULT_RESULT_BUDGET_CHARS = 8_000
        const val MAX_PAGE_LIMIT = 100
        const val MAX_RESULT_BUDGET_CHARS = 64_000
        private val CURSOR_PATTERN = Regex("v(\\d+)-(\\d+)")
    }
}
