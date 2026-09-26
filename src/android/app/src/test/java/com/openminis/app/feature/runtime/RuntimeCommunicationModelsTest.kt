package com.openminis.app.feature.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeCommunicationModelsTest {
    private val senderAddress = RuntimeConversationAddress.fromStableSessionId("session-a")
    private val receiverAddress = RuntimeConversationAddress.fromStableSessionId("session-b")
    private val version = RuntimeCapabilitySnapshotVersion(
        configRevision = 4L,
        capabilityRevision = 7L,
    )

    @Test
    fun `conversation address uses fixed prefix and is stable`() {
        val first = RuntimeConversationAddress.fromStableSessionId("stable-session")
        val second = RuntimeConversationAddress.fromStableSessionId("stable-session")
        val other = RuntimeConversationAddress.fromStableSessionId("another-session")

        assertEquals(first, second)
        assertTrue(first.value.startsWith(RuntimeConversationAddress.PREFIX))
        assertEquals(RuntimeConversationAddress.PREFIX.length + 64, first.value.length)
        assertTrue(RuntimeConversationAddress.isValid(first.value))
        assertFalse(RuntimeConversationAddress.isValid("stable-session"))
        assertFalse(RuntimeConversationAddress.isValid("${RuntimeConversationAddress.PREFIX}not-a-hash"))
        assertTrue(first != other)
        assertNull(RuntimeConversationAddress.parse("openminis-conv:ABC"))
    }

    @Test
    fun `stable address and runtime execution id remain separate`() {
        val peer = RuntimeCommunicationPeer(
            address = senderAddress,
            sessionId = "session-a",
            executionId = "session-a#run-3",
        )

        assertEquals(senderAddress, peer.address)
        assertEquals("session-a", peer.sessionId)
        assertEquals("session-a#run-3", peer.executionId)
        assertFalse(peer.address.value.contains("#run-3"))
    }

    @Test
    fun `communication metadata is metadata only by default`() {
        val record = metadata(summary = "delegated result is ready")

        assertTrue(record.metadataOnly)
        assertTrue(record.summary.contains("ready"))
        assertFalse(record::class.java.declaredFields.any { field ->
            field.name.contains("payload", ignoreCase = true) ||
                field.name.contains("transcript", ignoreCase = true) ||
                field.name.contains("media", ignoreCase = true) ||
                field.name.contains("bytes", ignoreCase = true)
        })
        assertTrue(
            RuntimeCommunicationDirectory().append(record),
        )
    }

    @Test
    fun `directory paginates with bounded cursor and filters metadata`() {
        val directory = RuntimeCommunicationDirectory()
        repeat(3) { index ->
            assertTrue(directory.append(metadata(summary = "result-$index")))
        }

        val first = directory.query(
            RuntimeCommunicationQuery(
                address = senderAddress,
                limit = 2,
                resultBudgetChars = 2_000,
            ),
        ) as RuntimeCommunicationQueryResult.Accepted
        assertEquals(2, first.page.records.size)
        assertEquals(3, first.page.totalMatchingRecords)
        assertNotNull(first.page.nextCursor)

        val second = directory.query(
            RuntimeCommunicationQuery(
                address = senderAddress,
                cursor = first.page.nextCursor,
                limit = 2,
                resultBudgetChars = 2_000,
            ),
        ) as RuntimeCommunicationQueryResult.Accepted
        assertEquals(1, second.page.records.size)
        assertNull(second.page.nextCursor)
        assertEquals("result-2", second.page.records.single().summary)
    }

    @Test
    fun `directory rejects page and result budgets`() {
        val directory = RuntimeCommunicationDirectory()
        assertTrue(directory.append(metadata(summary = "small")))

        val overPage = directory.query(
            RuntimeCommunicationQuery(limit = RuntimeCommunicationDirectory.MAX_PAGE_LIMIT + 1),
        )
        assertEquals(
            RuntimeCommunicationQueryRejection.PAGE_LIMIT_EXCEEDED,
            (overPage as RuntimeCommunicationQueryResult.Rejected).reason,
        )

        val overBudget = directory.query(
            RuntimeCommunicationQuery(limit = 1, resultBudgetChars = 1),
        )
        assertEquals(
            RuntimeCommunicationQueryRejection.RESULT_BUDGET_EXCEEDED,
            (overBudget as RuntimeCommunicationQueryResult.Rejected).reason,
        )

        val invalidCursor = directory.query(
            RuntimeCommunicationQuery(
                cursor = RuntimeCommunicationCursor("not-a-cursor"),
                limit = 1,
                resultBudgetChars = 2_000,
            ),
        )
        assertEquals(
            RuntimeCommunicationQueryRejection.INVALID_CURSOR,
            (invalidCursor as RuntimeCommunicationQueryResult.Rejected).reason,
        )
    }

    @Test
    fun `agent and team directory policies remain explicit`() {
        val agent = metadata(
            summary = "agent route",
            policy = RuntimeCommunicationDirectoryPolicy(
                mode = RuntimeCommunicationDirectoryMode.AGENT,
                routeKind = RuntimeCommunicationRouteKind.PARENT_CHILD,
            ),
        )
        val team = metadata(
            summary = "team route",
            policy = RuntimeCommunicationDirectoryPolicy(
                mode = RuntimeCommunicationDirectoryMode.TEAM,
                routeKind = RuntimeCommunicationRouteKind.TEAM_PEER,
            ),
        )
        assertEquals(RuntimeCommunicationDirectoryMode.AGENT, agent.directoryPolicy.mode)
        assertEquals(RuntimeCommunicationRouteKind.PARENT_CHILD, agent.directoryPolicy.routeKind)
        assertEquals(RuntimeCommunicationDirectoryMode.TEAM, team.directoryPolicy.mode)
        assertEquals(RuntimeCommunicationRouteKind.TEAM_PEER, team.directoryPolicy.routeKind)
    }

    @Test
    fun `detail requires explicit second read`() {
        val directory = RuntimeCommunicationDirectory()
        val record = metadata(summary = "detail available")
        assertTrue(directory.append(record))

        val implicit = directory.requestDetail(
            RuntimeCommunicationDetailRequest(recordId = record.recordId, explicitRead = false),
        )
        assertEquals(
            RuntimeCommunicationQueryRejection.DETAIL_REQUIRES_EXPLICIT_READ,
            (implicit as RuntimeCommunicationDetailResult.Rejected).reason,
        )
        val explicit = directory.requestDetail(
            RuntimeCommunicationDetailRequest(recordId = record.recordId, explicitRead = true),
        )
        assertTrue(explicit is RuntimeCommunicationDetailResult.Accepted)
    }

    private fun metadata(
        summary: String,
        policy: RuntimeCommunicationDirectoryPolicy = RuntimeCommunicationDirectoryPolicy(
            mode = RuntimeCommunicationDirectoryMode.AGENT,
            routeKind = RuntimeCommunicationRouteKind.PARENT_CHILD,
        ),
    ): RuntimeCommunicationMetadata = RuntimeCommunicationMetadata(
        sender = RuntimeCommunicationPeer(senderAddress, "session-a", "session-a#run-1"),
        receiver = RuntimeCommunicationPeer(receiverAddress, "session-b", "session-b#run-1"),
        originalMessageId = "message-1",
        replyRoute = RuntimeCommunicationReplyRoute(
            replyToRecordId = "record-parent",
            replyAddress = senderAddress,
        ),
        direction = RuntimeCommunicationDirection.OUTBOUND,
        timestampMillis = 100L,
        state = RuntimeCommunicationState.DELIVERED,
        summary = summary,
        senderCapabilityVersion = version,
        receiverCapabilityVersion = version,
        directoryPolicy = policy,
    )
}
