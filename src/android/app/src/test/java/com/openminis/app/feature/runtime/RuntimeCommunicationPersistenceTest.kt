package com.openminis.app.feature.runtime

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeCommunicationPersistenceTest {
    @Test
    fun appendThenRestartRestoresMetadataOnlyRecords() {
        val root = Files.createTempDirectory("runtime-communication").toFile()
        val file = root.resolve("communication.json")
        val first = RuntimeCommunicationRepository(RuntimeCommunicationFileStore(file))
        val record = record("one", "hello")

        assertTrue(first.append(record))
        assertFalse(first.append(record))

        val restored = RuntimeCommunicationRepository(RuntimeCommunicationFileStore(file))
        val result = restored.query(RuntimeCommunicationQuery(limit = 10))
        val accepted = result as RuntimeCommunicationQueryResult.Accepted
        assertEquals(listOf(record), accepted.page.records)
        assertTrue(accepted.page.metadataOnly)
        assertTrue(accepted.page.records.all { it.metadataOnly })
    }

    @Test
    fun detailRequiresExplicitSecondRead() {
        val root = Files.createTempDirectory("runtime-communication-detail").toFile()
        val repository = RuntimeCommunicationRepository(RuntimeCommunicationFileStore(root.resolve("records.json")))
        val record = record("detail", "summary")
        repository.append(record)

        assertEquals(
            RuntimeCommunicationQueryRejection.DETAIL_REQUIRES_EXPLICIT_READ,
            (repository.requestDetail(RuntimeCommunicationDetailRequest(record.recordId, false)) as RuntimeCommunicationDetailResult.Rejected).reason,
        )
        assertEquals(
            RuntimeCommunicationDetailResult.Accepted(RuntimeCommunicationDetailRequest(record.recordId, true)),
            repository.requestDetail(RuntimeCommunicationDetailRequest(record.recordId, true)),
        )
        assertEquals(
            RuntimeCommunicationQueryRejection.RECORD_NOT_FOUND,
            (repository.requestDetail(RuntimeCommunicationDetailRequest("missing", true)) as RuntimeCommunicationDetailResult.Rejected).reason,
        )
    }

    @Test
    fun cursorLimitAndResultBudgetAreEnforcedAcrossRestart() {
        val root = Files.createTempDirectory("runtime-communication-pages").toFile()
        val file = root.resolve("records.json")
        val repository = RuntimeCommunicationRepository(
            RuntimeCommunicationFileStore(file),
            maxPageLimit = 2,
            maxResultBudgetChars = 4_000,
        )
        val records = (1..3).map { record("row-$it", "summary-$it") }
        records.forEach { assertTrue(repository.append(it)) }

        val first = repository.query(RuntimeCommunicationQuery(limit = 2, resultBudgetChars = 4_000))
            as RuntimeCommunicationQueryResult.Accepted
        assertEquals(2, first.page.records.size)
        assertEquals(3, first.page.totalMatchingRecords)
        val cursor = first.page.nextCursor
        assertTrue(cursor != null)
        val second = repository.query(RuntimeCommunicationQuery(cursor = cursor, limit = 2, resultBudgetChars = 4_000))
            as RuntimeCommunicationQueryResult.Accepted
        assertEquals(listOf(records[2]), second.page.records)
        assertNull(second.page.nextCursor)

        assertEquals(
            RuntimeCommunicationQueryRejection.PAGE_LIMIT_EXCEEDED,
            (repository.query(RuntimeCommunicationQuery(limit = 3, resultBudgetChars = 4_000)) as RuntimeCommunicationQueryResult.Rejected).reason,
        )
        assertEquals(
            RuntimeCommunicationQueryRejection.RESULT_BUDGET_EXCEEDED,
            (repository.query(RuntimeCommunicationQuery(limit = 1, resultBudgetChars = 1)) as RuntimeCommunicationQueryResult.Rejected).reason,
        )

        val restarted = RuntimeCommunicationRepository(RuntimeCommunicationFileStore(file), 2, 4_000)
        val restoredPage = restarted.query(RuntimeCommunicationQuery(limit = 2, resultBudgetChars = 4_000))
            as RuntimeCommunicationQueryResult.Accepted
        assertEquals(records.take(2), restoredPage.page.records)
    }

    @Test
    fun deleteForSessionRemovesSenderAndReceiverRecordsAndPreservesOthers() {
        val root = Files.createTempDirectory("runtime-communication-delete").toFile()
        val repository = RuntimeCommunicationRepository(RuntimeCommunicationFileStore(root.resolve("records.json")))
        val senderMatch = record("sender-match", "sender")
        val receiverMatch = record("receiver-match", "receiver").copy(
            sender = peer("other-sender"),
            receiver = peer("target-session"),
        )
        val preserved = record("preserved", "keep")
        repository.append(senderMatch)
        repository.append(receiverMatch)
        repository.append(preserved)

        assertEquals(1, repository.deleteForSession("sender-sender-match"))
        assertEquals(1, repository.deleteForSession("target-session"))
        val page = repository.query(RuntimeCommunicationQuery(limit = 10)) as RuntimeCommunicationQueryResult.Accepted
        assertEquals(listOf(preserved), page.page.records)
        val restarted = RuntimeCommunicationRepository(RuntimeCommunicationFileStore(root.resolve("records.json")))
        val restored = restarted.query(RuntimeCommunicationQuery(limit = 10)) as RuntimeCommunicationQueryResult.Accepted
        assertEquals(listOf(preserved), restored.page.records)
        assertEquals(0, restarted.deleteForSession(""))
    }
    @Test
    fun corruptJsonAndStaleTempFileDoNotBecomeRecords() {
        val root = Files.createTempDirectory("runtime-communication-recovery").toFile()
        val file = root.resolve("records.json")
        file.writeText("{not-json")
        root.resolve("records.json.tmp").writeText("{\"schemaVersion\":1,\"records\":[]}")

        assertTrue(RuntimeCommunicationFileStore(file).read().isEmpty())
        val repository = RuntimeCommunicationRepository(RuntimeCommunicationFileStore(file))
        assertTrue((repository.query(RuntimeCommunicationQuery(limit = 1)) as RuntimeCommunicationQueryResult.Accepted).page.records.isEmpty())

        val record = record("atomic", "written")
        assertTrue(repository.append(record))
        assertTrue(file.isFile)
        assertFalse(root.resolve("records.json.tmp").exists())
        assertTrue(file.readText().contains("schemaVersion"))
        assertEquals(listOf(record), RuntimeCommunicationFileStore(file).read())
    }

    private fun record(id: String, summary: String) = RuntimeCommunicationMetadata(
        recordId = id,
        sender = peer("sender-$id"),
        receiver = peer("receiver-$id"),
        originalMessageId = "message-$id",
        replyRoute = RuntimeCommunicationReplyRoute(replyToRecordId = "reply-$id"),
        direction = RuntimeCommunicationDirection.OUTBOUND,
        timestampMillis = 1_700_000_000_000L,
        state = RuntimeCommunicationState.DELIVERED,
        summary = summary,
        senderCapabilityVersion = RuntimeCapabilitySnapshotVersion(1, 2),
        receiverCapabilityVersion = RuntimeCapabilitySnapshotVersion(3, 4),
        directoryPolicy = RuntimeCommunicationDirectoryPolicy(
            RuntimeCommunicationDirectoryMode.TEAM,
            RuntimeCommunicationRouteKind.TEAM_PEER,
        ),
    )

    private fun peer(sessionId: String) = RuntimeCommunicationPeer(
        RuntimeConversationAddress.fromStableSessionId(sessionId),
        sessionId,
        executionId = "execution-$sessionId",
    )
}
