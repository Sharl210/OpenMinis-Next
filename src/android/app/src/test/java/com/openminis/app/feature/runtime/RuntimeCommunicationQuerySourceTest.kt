package com.openminis.app.feature.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The coordinator's communication reads, exercised against a real runtime tree
 * and a real communication directory.
 *
 * ## What this file used to be
 *
 * Three of the four assertions were `text.contains("...")` over
 * `RuntimeSessionCoordinator.kt`, e.g. that the file contains the literal
 * `communicationGateway(repository).query(actor, query)`. That proves the
 * characters exist — not that an actor-scoped read happens, and not that a chat
 * session resolves to its live runtime node. If the line were moved into a
 * branch production never evaluates, or if the actor were dropped at the
 * boundary, the text would still match.
 *
 * ## What is asserted now
 *
 * `queryCommunication` / `communicationDetail` are called for real, and the
 * result is asserted. The behaviour that only the coordinator owns — and that
 * the gateway alone cannot provide — is the actor mapping: callers pass a CHAT
 * session id, while the runtime tree (and every record the directory stores)
 * uses the RUNTIME node id, which after a repeated run is `"<session>#run-N"`.
 * The tests below seed exactly that shape, so a query that skips the mapping
 * comes back empty (or is rejected for detail) instead of finding the traffic.
 */
class RuntimeCommunicationQuerySourceTest {

    private class Fixture {
        val dir: File = Files.createTempDirectory("runtime-comm-query").toFile()
        val store: RuntimeTreeStore = RuntimeTreeStore.openForTest(File(dir, "session-tree.json")) { source, target ->
            Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }
        val coordinator: RuntimeSessionCoordinator = run {
            val constructor = RuntimeSessionCoordinator::class.java
                .getDeclaredConstructor(RuntimeTreeStore::class.java)
            constructor.isAccessible = true
            constructor.newInstance(store)
        }
        val repository = RuntimeCommunicationRepository(
            RuntimeCommunicationFileStore(File(dir, "communication.json")),
        )

        fun dispose() {
            dir.deleteRecursively()
        }
    }

    /**
     * A chat session whose SECOND run owns the live runtime node, i.e. the
     * session id and the node id differ — the case the mapping exists for.
     */
    private fun Fixture.startSecondRunOf(sessionId: String): String {
        assertEquals(sessionId, coordinator.startRoot(sessionId))
        coordinator.finishRoot(sessionId)
        val secondRun = requireNotNull(coordinator.startRoot(sessionId)) { "second run must start" }
        assertTrue("a repeated run must mint a new runtime node id", secondRun != sessionId)
        return secondRun
    }

    private fun Fixture.record(actor: String, target: String, summary: String): String {
        val result = coordinator.communicationGateway(repository).recordDelivered(
            actorSessionId = actor,
            targetSessionId = target,
            summary = summary,
            delivery = RuntimeDelivery.QUEUE,
        )
        return (result as RuntimeCommunicationGateway.SendResult.Accepted).recordId
    }

    @Test
    fun `a query for a chat session reads the traffic of that session's live runtime node`() {
        val f = Fixture()
        try {
            val runtimeNode = f.startSecondRunOf("chat-a")
            f.record(actor = runtimeNode, target = "child-a", summary = "parent to child")
            // Unrelated traffic that a read without an actor scope would return.
            f.record(actor = "chat-b", target = "child-b", summary = "somebody else's")

            val result = f.coordinator.queryCommunication(
                f.repository,
                "chat-a",
                RuntimeCommunicationQuery(limit = 20),
            )

            assertTrue("an actor-scoped read must be accepted; got $result", result is RuntimeCommunicationQueryResult.Accepted)
            val page = (result as RuntimeCommunicationQueryResult.Accepted).page
            assertEquals(
                "only the caller's own runtime node's traffic may be returned",
                listOf("parent to child"),
                page.records.map { it.summary },
            )
            assertEquals(runtimeNode, page.records.single().sender.sessionId)
        } finally {
            f.dispose()
        }
    }

    @Test
    fun `one actor cannot read another actor's traffic, and the refusal leaves the directory alone`() {
        val f = Fixture()
        try {
            val runtimeNode = f.startSecondRunOf("chat-a")
            f.record(actor = runtimeNode, target = "child-a", summary = "parent to child")

            val before = f.repository.query(RuntimeCommunicationQuery(limit = 20))
            val result = f.coordinator.queryCommunication(
                f.repository,
                "chat-b",
                // The caller states the victim's node id explicitly; the gateway
                // must still refuse, because the request cannot self-authorize.
                RuntimeCommunicationQuery(peerSessionId = runtimeNode, limit = 20),
            )

            assertTrue(
                "a query naming somebody else's node must be rejected; got $result",
                result is RuntimeCommunicationQueryResult.Rejected,
            )
            assertEquals(
                RuntimeCommunicationQueryRejection.UNAUTHORIZED_ACTOR,
                (result as RuntimeCommunicationQueryResult.Rejected).reason,
            )
            assertEquals(
                "a refused read must not modify the directory",
                (before as RuntimeCommunicationQueryResult.Accepted).page.records,
                (f.repository.query(RuntimeCommunicationQuery(limit = 20)) as RuntimeCommunicationQueryResult.Accepted).page.records,
            )
        } finally {
            f.dispose()
        }
    }

    @Test
    fun `detail resolves the caller's live runtime node before deciding who may read the record`() {
        val f = Fixture()
        try {
            val runtimeNode = f.startSecondRunOf("chat-a")
            val recordId = f.record(actor = runtimeNode, target = "child-a", summary = "parent to child")

            // The participant: the chat session id must resolve to the node id
            // that actually appears on the record. Without the mapping the actor
            // is neither sender nor receiver and the read is refused.
            val owner = f.coordinator.communicationDetail(
                f.repository,
                "chat-a",
                RuntimeCommunicationDetailRequest(recordId),
            )
            assertTrue(
                "the participant must be allowed to read the detail; got $owner",
                owner is RuntimeCommunicationDetailResult.Accepted,
            )

            // A third party is refused on the same record.
            val stranger = f.coordinator.communicationDetail(
                f.repository,
                "chat-c",
                RuntimeCommunicationDetailRequest(recordId),
            )
            assertTrue("an unrelated actor must be refused; got $stranger", stranger is RuntimeCommunicationDetailResult.Rejected)
            assertEquals(
                RuntimeCommunicationQueryRejection.UNAUTHORIZED_ACTOR,
                (stranger as RuntimeCommunicationDetailResult.Rejected).reason,
            )

            // An unknown record id is a different refusal, i.e. the actor check
            // did not simply swallow everything.
            val missing = f.coordinator.communicationDetail(
                f.repository,
                "chat-a",
                RuntimeCommunicationDetailRequest("no-such-record"),
            )
            assertEquals(
                RuntimeCommunicationQueryRejection.RECORD_NOT_FOUND,
                (missing as RuntimeCommunicationDetailResult.Rejected).reason,
            )
        } finally {
            f.dispose()
        }
    }

    @Test
    fun `a same-conversation node that is neither sender nor receiver may not read the record`() {
        val f = Fixture()
        try {
            // The record is authored by the FIRST generation of this chat …
            val gen1 = requireNotNull(f.coordinator.startRoot("chat-a")) { "first run must start" }
            val recordId = f.record(actor = gen1, target = "child-x", summary = "gen1 to child-x")
            f.coordinator.finishRoot("chat-a")
            val gen2 = requireNotNull(f.coordinator.startRoot("chat-a")) { "second run must start" }
            assertTrue("a repeated run must mint a new runtime node id", gen2 != gen1)

            // … and read back by the SECOND generation of the same chat session.
            // The two generations are the same conversation, but neither is the
            // other, and the reader is neither the sender nor the receiver. The
            // refusal must therefore be on authorisation grounds: the record is
            // present, it is simply not this actor's to read.
            //
            // This shape is the semantic discriminator for the read rule, and it is
            // deliberately the only one of its kind in this file. Every other
            // refusal asserted here (and in the sibling messaging file) hands the
            // stranger a genuinely DIFFERENT conversation, and such a stranger is
            // refused by "sender or receiver only" and by the wider "any node in
            // the same conversation" rule alike — so widening the read rule to
            // conversation scope leaves those assertions green. Measured: with the
            // gateway's check replaced by same-conversation scope, this file's
            // other three tests all stay green and only this one reddens.
            assertTrue(
                "the record itself must still be there, so the refusal is not a missing record",
                f.repository.find(recordId) != null,
            )
            val sameChatOtherGeneration = f.coordinator.communicationDetail(
                f.repository,
                "chat-a",
                RuntimeCommunicationDetailRequest(recordId),
            )
            assertTrue(
                "another generation of the same chat is still another actor; got $sameChatOtherGeneration",
                sameChatOtherGeneration is RuntimeCommunicationDetailResult.Rejected,
            )
            assertEquals(
                "the previous generation's traffic is not the current generation's to read",
                RuntimeCommunicationQueryRejection.UNAUTHORIZED_ACTOR,
                (sameChatOtherGeneration as RuntimeCommunicationDetailResult.Rejected).reason,
            )

            // Control: the record's actual counterparty can still read it, so the
            // refusal above is about the relationship, not about the record having
            // become unusable. Note this control holds under BOTH rules, which is
            // exactly why it cannot replace the assertion above.
            assertTrue(
                "the node named as the receiver must still be able to read the record",
                f.coordinator.communicationDetail(
                    f.repository,
                    "child-x",
                    RuntimeCommunicationDetailRequest(recordId),
                ) is RuntimeCommunicationDetailResult.Accepted,
            )
        } finally {
            f.dispose()
        }
    }
}
