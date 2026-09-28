package com.openminis.app.feature.runtime

/**
 * Narrow production boundary between the authorized runtime tree and the
 * metadata-only communication directory. Callers must provide the actor from
 * the current runtime/session context; request payloads cannot self-authorize.
 */
class RuntimeCommunicationGateway(
    private val repository: RuntimeCommunicationRepository,
    private val authorizedSend: (actorSessionId: String, targetSessionId: String, payload: String, delivery: RuntimeDelivery) -> RuntimeDeliveryReceipt,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    sealed interface SendResult {
        val accepted: Boolean
        data class Accepted(val receipt: RuntimeDeliveryReceipt, val recordId: String) : SendResult {
            override val accepted: Boolean = true
        }
        data class Rejected(val reason: String, val receipt: RuntimeDeliveryReceipt? = null) : SendResult {
            override val accepted: Boolean = false
        }
    }

    fun send(
        actorSessionId: String,
        targetSessionId: String,
        payload: String,
        delivery: RuntimeDelivery,
        summary: String = payload.take(RuntimeCommunicationMetadata.MAX_SUMMARY_CHARS),
    ): SendResult {
        if (actorSessionId.isBlank() || targetSessionId.isBlank()) return SendResult.Rejected("actor and target are required")
        if (payload.isBlank()) return SendResult.Rejected("payload must not be blank")
        val receipt = runCatching { authorizedSend(actorSessionId, targetSessionId, payload, delivery) }
            .getOrElse { error ->
                RuntimeDeliveryReceipt(
                    accepted = false,
                    messageId = null,
                    effectiveDelivery = null,
                    reason = error.message ?: "runtime authorization failed",
                )
            }
        val sender = peer(actorSessionId)
        val receiver = peer(targetSessionId)
        val policy = policy(delivery)
        val state = if (receipt.accepted) RuntimeCommunicationState.QUEUED else RuntimeCommunicationState.REJECTED
        val record = RuntimeCommunicationMetadata(
            sender = sender,
            receiver = receiver,
            originalMessageId = receipt.messageId,
            direction = RuntimeCommunicationDirection.OUTBOUND,
            timestampMillis = nowMillis().coerceAtLeast(0L),
            state = state,
            summary = summary.take(RuntimeCommunicationMetadata.MAX_SUMMARY_CHARS),
            senderCapabilityVersion = RuntimeCapabilitySnapshotVersion(0, 0),
            receiverCapabilityVersion = RuntimeCapabilitySnapshotVersion(0, 0),
            directoryPolicy = policy,
        )
        val persisted = runCatching { repository.append(record) }.getOrDefault(false)
        if (!persisted) return SendResult.Rejected("communication metadata persistence failed", receipt)
        return if (receipt.accepted) SendResult.Accepted(receipt, record.recordId)
        else SendResult.Rejected(receipt.reason ?: "runtime delivery rejected", receipt)
    }

    fun query(actorSessionId: String, query: RuntimeCommunicationQuery): RuntimeCommunicationQueryResult {
        if (actorSessionId.isBlank()) return RuntimeCommunicationQueryResult.Rejected(RuntimeCommunicationQueryRejection.UNAUTHORIZED_ACTOR)
        val scopedQuery = if (query.peerSessionId == null) {
            query.copy(peerSessionId = actorSessionId)
        } else query
        if (scopedQuery.peerSessionId != actorSessionId) {
            return RuntimeCommunicationQueryResult.Rejected(RuntimeCommunicationQueryRejection.UNAUTHORIZED_ACTOR)
        }
        return repository.query(scopedQuery)
    }

    fun detail(actorSessionId: String, request: RuntimeCommunicationDetailRequest): RuntimeCommunicationDetailResult {
        if (actorSessionId.isBlank()) return RuntimeCommunicationDetailResult.Rejected(RuntimeCommunicationQueryRejection.UNAUTHORIZED_ACTOR)
        val metadata = repository.find(request.recordId)
            ?: return RuntimeCommunicationDetailResult.Rejected(RuntimeCommunicationQueryRejection.RECORD_NOT_FOUND)
        if (metadata.sender.sessionId != actorSessionId && metadata.receiver.sessionId != actorSessionId) {
            return RuntimeCommunicationDetailResult.Rejected(RuntimeCommunicationQueryRejection.UNAUTHORIZED_ACTOR)
        }
        return repository.requestDetail(request)
    }

    private fun peer(sessionId: String): RuntimeCommunicationPeer =
        RuntimeCommunicationPeer(RuntimeConversationAddress.fromStableSessionId(sessionId), sessionId)

    private fun policy(delivery: RuntimeDelivery): RuntimeCommunicationDirectoryPolicy =
        if (delivery == RuntimeDelivery.TEAM_PEER) {
            RuntimeCommunicationDirectoryPolicy(RuntimeCommunicationDirectoryMode.TEAM, RuntimeCommunicationRouteKind.TEAM_PEER)
        } else {
            RuntimeCommunicationDirectoryPolicy(RuntimeCommunicationDirectoryMode.AGENT, RuntimeCommunicationRouteKind.PARENT_CHILD)
        }
}
