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
    private val capabilityVersion: (sessionId: String) -> RuntimeCapabilitySnapshotVersion = {
        RuntimeCapabilitySnapshotVersion(0, 0)
    },
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
        attachment: RuntimeContextAttachment? = null,
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
        val record = buildRecord(
            actorSessionId = actorSessionId,
            targetSessionId = targetSessionId,
            summary = summary,
            delivery = delivery,
            state = if (receipt.accepted) RuntimeCommunicationState.QUEUED else RuntimeCommunicationState.REJECTED,
            originalMessageId = receipt.messageId,
            attachment = attachment,
        )
        val persisted = runCatching { repository.append(record) }.getOrDefault(false)
        if (!persisted) return SendResult.Rejected("communication metadata persistence failed", receipt)
        return if (receipt.accepted) SendResult.Accepted(receipt, record.recordId)
        else SendResult.Rejected(receipt.reason ?: "runtime delivery rejected", receipt)
    }

    /**
     * Record the directory entry for a message the runtime tree delivers through
     * its OWN path, together with whatever the dispatcher chose to attach.
     *
     * A delegated child's task is the case this exists for: the parent's prompt
     * reaches the child through the delegation launcher, so routing the same
     * text through [send]'s mailbox would deliver it twice — but the
     * parent→child traffic still has to appear in the communication directory,
     * and so does the reference `--share=` produced. Recording the entry while
     * leaving delivery where it already happens is what keeps one message out of
     * the receiver's mailbox twice.
     *
     * The body itself is NOT copied here beyond the caller's already-bounded
     * [summary]; [attachment] is a reference and cannot contain the shared
     * messages. See [RuntimeContextAttachment].
     */
    fun recordDelivered(
        actorSessionId: String,
        targetSessionId: String,
        summary: String,
        delivery: RuntimeDelivery,
        attachment: RuntimeContextAttachment? = null,
        state: RuntimeCommunicationState = RuntimeCommunicationState.QUEUED,
        originalMessageId: String? = null,
    ): SendResult {
        if (actorSessionId.isBlank() || targetSessionId.isBlank()) {
            return SendResult.Rejected("actor and target are required")
        }
        val record = buildRecord(
            actorSessionId = actorSessionId,
            targetSessionId = targetSessionId,
            summary = summary,
            delivery = delivery,
            state = state,
            originalMessageId = originalMessageId,
            attachment = attachment,
        )
        val persisted = runCatching { repository.append(record) }.getOrDefault(false)
        if (!persisted) return SendResult.Rejected("communication metadata persistence failed")
        return SendResult.Accepted(
            receipt = RuntimeDeliveryReceipt(
                accepted = true,
                messageId = originalMessageId,
                effectiveDelivery = delivery,
                reason = null,
            ),
            recordId = record.recordId,
        )
    }

    /**
     * The single place a communication record is constructed. Both the mailbox
     * path ([send]) and the out-of-band path ([recordDelivered]) go through it,
     * so sender/receiver addresses, capability versions and the attachment can
     * never come out different depending on which one a caller used.
     */
    private fun buildRecord(
        actorSessionId: String,
        targetSessionId: String,
        summary: String,
        delivery: RuntimeDelivery,
        state: RuntimeCommunicationState,
        originalMessageId: String?,
        attachment: RuntimeContextAttachment?,
    ): RuntimeCommunicationMetadata = RuntimeCommunicationMetadata(
        sender = peer(actorSessionId),
        receiver = peer(targetSessionId),
        originalMessageId = originalMessageId,
        direction = RuntimeCommunicationDirection.OUTBOUND,
        timestampMillis = nowMillis().coerceAtLeast(0L),
        state = state,
        summary = summary.take(RuntimeCommunicationMetadata.MAX_SUMMARY_CHARS),
        senderCapabilityVersion = capabilityVersion(actorSessionId),
        receiverCapabilityVersion = capabilityVersion(targetSessionId),
        directoryPolicy = policy(delivery),
        attachedContext = attachment,
    )

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
