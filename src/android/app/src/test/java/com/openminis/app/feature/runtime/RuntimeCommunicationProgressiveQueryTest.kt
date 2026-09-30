package com.openminis.app.feature.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R43 contract tests: the request requires opaque prefixed IDs, bounded
 * progressive reads, honest budget rejection, and actor-scoped reads.
 */
class RuntimeCommunicationProgressiveQueryTest {
    @Test
    fun `conversation IDs use the runtime prefix and remain opaque`() {
        val address = RuntimeConversationAddress.fromStableSessionId("session-a")

        assertTrue(address.value.startsWith(RuntimeConversationAddress.PREFIX))
        assertEquals("openminis-conv:", RuntimeConversationAddress.PREFIX)
        assertTrue(RuntimeConversationAddress.isValid(address.value))
        assertNull(RuntimeConversationAddress.parse("session-a"))
    }

    @Test
    fun `empty progressive query terminates without a cursor`() {
        val directory = RuntimeCommunicationDirectory()

        val result = directory.query(
            RuntimeCommunicationQuery(limit = 20, resultBudgetChars = 8_000),
        ) as RuntimeCommunicationQueryResult.Accepted

        assertTrue(result.page.records.isEmpty())
        assertEquals(0, result.page.totalMatchingRecords)
        assertNull(result.page.nextCursor)
    }

    @Test
    fun `progressive query terminates on the final page`() {
        val directory = RuntimeCommunicationDirectory()
        repeat(2) { index -> assertTrue(directory.append(record("row-$index", "s$index"))) }

        val first = directory.query(
            RuntimeCommunicationQuery(limit = 1, resultBudgetChars = 8_000),
        ) as RuntimeCommunicationQueryResult.Accepted
        assertEquals(1, first.page.records.size)
        assertTrue(first.page.nextCursor != null)

        val final = directory.query(
            RuntimeCommunicationQuery(
                cursor = first.page.nextCursor,
                limit = 1,
                resultBudgetChars = 8_000,
            ),
        ) as RuntimeCommunicationQueryResult.Accepted
        assertEquals(1, final.page.records.size)
        assertNull(final.page.nextCursor)
    }

    @Test
    fun `budget exactly equal to serialized estimate is accepted`() {
        val directory = RuntimeCommunicationDirectory()
        val value = record("r", "summary")
        assertTrue(directory.append(value))
        // RuntimeCommunicationDirectory.estimateChars: 256 + summary + ids + peer session IDs.
        val exact = 256 + value.summary.length + value.recordId.length +
            value.sender.sessionId.length + value.receiver.sessionId.length

        val result = directory.query(
            RuntimeCommunicationQuery(limit = 1, resultBudgetChars = exact),
        )

        assertTrue(result is RuntimeCommunicationQueryResult.Accepted)
    }

    @Test
    fun `zero negative and over maximum budgets are rejected explicitly`() {
        val directory = RuntimeCommunicationDirectory()
        assertTrue(directory.append(record("r", "summary")))

        val zero = directory.query(RuntimeCommunicationQuery(resultBudgetChars = 0))
        val negative = directory.query(RuntimeCommunicationQuery(resultBudgetChars = -1))
        val overMaximum = directory.query(
            RuntimeCommunicationQuery(resultBudgetChars = RuntimeCommunicationDirectory.MAX_RESULT_BUDGET_CHARS + 1),
        )

        assertEquals(RuntimeCommunicationQueryRejection.RESULT_BUDGET_EXCEEDED, (zero as RuntimeCommunicationQueryResult.Rejected).reason)
        assertEquals(RuntimeCommunicationQueryRejection.RESULT_BUDGET_EXCEEDED, (negative as RuntimeCommunicationQueryResult.Rejected).reason)
        assertEquals(RuntimeCommunicationQueryRejection.RESULT_BUDGET_EXCEEDED, (overMaximum as RuntimeCommunicationQueryResult.Rejected).reason)
    }

    @Test
    fun `over-budget result is reported as rejection rather than silently truncated`() {
        val directory = RuntimeCommunicationDirectory()
        assertTrue(directory.append(record("r", "a sufficiently visible summary")))

        val result = directory.query(RuntimeCommunicationQuery(limit = 1, resultBudgetChars = 1))

        assertTrue(result is RuntimeCommunicationQueryResult.Rejected)
        assertEquals(
            RuntimeCommunicationQueryRejection.RESULT_BUDGET_EXCEEDED,
            (result as RuntimeCommunicationQueryResult.Rejected).reason,
        )
    }

    @Test
    fun `unauthorized detail is rejected before repository detail read`() {
        val file = File.createTempFile("runtime-communication-query-", ".json")
        try {
            val repository = RuntimeCommunicationRepository(RuntimeCommunicationFileStore(file))
            val gateway = RuntimeCommunicationGateway(
                repository = repository,
                authorizedSend = { _, _, _, _ -> RuntimeDeliveryReceipt(true, "m1", null, null) },
            )
            val sent = gateway.send("owner", "peer", "hello", RuntimeDelivery.QUEUE)
            val recordId = (sent as RuntimeCommunicationGateway.SendResult.Accepted).recordId
            val before = repository.query(RuntimeCommunicationQuery(limit = 20)) as RuntimeCommunicationQueryResult.Accepted

            val rejected = gateway.detail("unrelated", RuntimeCommunicationDetailRequest(recordId))
            val after = repository.query(RuntimeCommunicationQuery(limit = 20)) as RuntimeCommunicationQueryResult.Accepted

            assertTrue(rejected is RuntimeCommunicationDetailResult.Rejected)
            assertEquals(RuntimeCommunicationQueryRejection.UNAUTHORIZED_ACTOR, (rejected as RuntimeCommunicationDetailResult.Rejected).reason)
            assertEquals(before.page.records, after.page.records)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `unauthorized query is rejected without changing persisted records`() {
        val file = File.createTempFile("runtime-communication-query-", ".json")
        try {
            val repository = RuntimeCommunicationRepository(RuntimeCommunicationFileStore(file))
            val gateway = RuntimeCommunicationGateway(
                repository = repository,
                authorizedSend = { _, _, _, _ -> RuntimeDeliveryReceipt(true, "m1", null, null) },
            )
            gateway.send("owner", "peer", "hello", RuntimeDelivery.QUEUE)
            val before = repository.query(RuntimeCommunicationQuery(limit = 20)) as RuntimeCommunicationQueryResult.Accepted

            val rejected = gateway.query("unrelated", RuntimeCommunicationQuery(peerSessionId = "owner"))
            val after = repository.query(RuntimeCommunicationQuery(limit = 20)) as RuntimeCommunicationQueryResult.Accepted

            assertFalse(rejected is RuntimeCommunicationQueryResult.Accepted)
            assertEquals(RuntimeCommunicationQueryRejection.UNAUTHORIZED_ACTOR, (rejected as RuntimeCommunicationQueryResult.Rejected).reason)
            assertEquals(before.page.records, after.page.records)
        } finally {
            file.delete()
        }
    }

    private fun record(id: String, summary: String) = RuntimeCommunicationMetadata(
        recordId = id,
        sender = peer("sender-$id"),
        receiver = peer("receiver-$id"),
        originalMessageId = "message-$id",
        direction = RuntimeCommunicationDirection.OUTBOUND,
        timestampMillis = 1_700_000_000_000L,
        state = RuntimeCommunicationState.QUEUED,
        summary = summary,
        senderCapabilityVersion = RuntimeCapabilitySnapshotVersion(1, 1),
        receiverCapabilityVersion = RuntimeCapabilitySnapshotVersion(1, 1),
        directoryPolicy = RuntimeCommunicationDirectoryPolicy(
            RuntimeCommunicationDirectoryMode.AGENT,
            RuntimeCommunicationRouteKind.PARENT_CHILD,
        ),
    )

    private fun peer(sessionId: String) = RuntimeCommunicationPeer(
        RuntimeConversationAddress.fromStableSessionId(sessionId),
        sessionId,
    )
}
