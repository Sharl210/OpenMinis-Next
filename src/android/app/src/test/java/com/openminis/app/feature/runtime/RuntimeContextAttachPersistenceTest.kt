package com.openminis.app.feature.runtime

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The durable half of R56: a delegation's share reaches disk as a reference.
 *
 * `request.md:138` asks for the attachment to be recorded with the message the
 * receiver gets — 「作为转发的时候的一个附加项，一同录入到对应的接收方的消息中」 —
 * and explicitly NOT as copied text. These tests therefore assert the record on
 * disk twice: that it CONTAINS the conversation locator plus the indices, and
 * that it does NOT contain the conversation.
 *
 * [CANARY] is the sender's message text. If a future change inlines the shared
 * transcript into the record, `the persisted record holds a reference and not
 * the context` fails — which is the point of writing that test this way.
 */
class RuntimeContextAttachPersistenceTest {

    @Test
    fun `the persisted record holds a reference and not the context`() {
        val root = Files.createTempDirectory("runtime-context-attach").toFile()
        val file = root.resolve("communication.json")
        val repository = RuntimeCommunicationRepository(RuntimeCommunicationFileStore(file))
        val gateway = gateway(repository)
        val conversation = RuntimeConversationAddress.fromStableSessionId("parent-session")

        val attachment = RuntimeContextAttachments.resolve(
            RuntimeContextShareRequest.Selected(listOf(1, 3)),
            conversation,
            listOf("turn 0", "turn 1 holds $CANARY", "turn 2", "turn 3 holds $CANARY as well"),
        )!!

        val result = gateway.recordDelivered(
            actorSessionId = "parent-session",
            targetSessionId = "child-session",
            summary = RuntimeContextAttachments.capsule(attachment),
            delivery = RuntimeDelivery.QUEUE,
            attachment = attachment,
        )

        assertTrue("recording the entry must be accepted", result.accepted)

        val onDisk = file.readText()
        assertTrue("the conversation locator must be persisted: $onDisk", onDisk.contains(conversation.value))
        assertTrue(
            "the indices the receiver queries must be persisted as a list: $onDisk",
            onDisk.contains("[1,3]"),
        )
        assertFalse(
            "the shared messages must NOT be persisted — the whole point of the reference form",
            onDisk.contains(CANARY),
        )

        // And it survives a restart as the same reference, not as something a
        // decoder had to guess at.
        val restored = RuntimeCommunicationRepository(RuntimeCommunicationFileStore(file))
        val record = (restored.query(RuntimeCommunicationQuery(limit = 10))
            as RuntimeCommunicationQueryResult.Accepted).page.records.single()
        assertEquals(attachment, record.attachedContext)
        assertEquals(
            RuntimeContextSelection.Indices(listOf(1, 3)),
            record.attachedContext!!.selection,
        )
    }

    @Test
    fun `a share-all record round trips its message count`() {
        val root = Files.createTempDirectory("runtime-context-attach-all").toFile()
        val file = root.resolve("communication.json")
        val repository = RuntimeCommunicationRepository(RuntimeCommunicationFileStore(file))
        val conversation = RuntimeConversationAddress.fromStableSessionId("parent-session")
        val attachment = RuntimeContextAttachments.resolve(
            RuntimeContextShareRequest.All,
            conversation,
            List(4) { "message $it" },
        )!!

        gateway(repository).recordDelivered(
            actorSessionId = "parent-session",
            targetSessionId = "child-session",
            summary = RuntimeContextAttachments.capsule(attachment),
            delivery = RuntimeDelivery.TEAM_PEER,
            attachment = attachment,
        )

        val restored = RuntimeCommunicationRepository(RuntimeCommunicationFileStore(file))
        val record = (restored.query(RuntimeCommunicationQuery(limit = 10))
            as RuntimeCommunicationQueryResult.Accepted).page.records.single()
        assertEquals(RuntimeContextSelection.All(4), record.attachedContext?.selection)
        assertEquals(listOf(0, 1, 2, 3), record.attachedContext?.indices)
    }

    @Test
    fun `sender and receiver addresses are persisted on the delegation record`() {
        val root = Files.createTempDirectory("runtime-context-addresses").toFile()
        val file = root.resolve("communication.json")
        val repository = RuntimeCommunicationRepository(RuntimeCommunicationFileStore(file))

        gateway(repository).recordDelivered(
            actorSessionId = "parent-session",
            targetSessionId = "child-session",
            summary = "delegated: do the thing",
            delivery = RuntimeDelivery.QUEUE,
        )

        val record = (RuntimeCommunicationRepository(RuntimeCommunicationFileStore(file))
            .query(RuntimeCommunicationQuery(limit = 10)) as RuntimeCommunicationQueryResult.Accepted)
            .page.records.single()

        // request.md:162 — 「需要由发送人地址和接收人地址」, so both ends must be
        // addressable without re-deriving anything from the transcript.
        assertEquals("parent-session", record.sender.sessionId)
        assertEquals("child-session", record.receiver.sessionId)
        assertEquals(
            RuntimeConversationAddress.fromStableSessionId("parent-session"),
            record.sender.address,
        )
        assertEquals(
            RuntimeConversationAddress.fromStableSessionId("child-session"),
            record.receiver.address,
        )
        // request.md:164 — 「这份原信息里面还要包含对方的能力」. Both ends' capability
        // versions ride along, through the SAME attach R39 built (this test
        // pins that the delegation record uses it, not a second scheme).
        assertEquals(RuntimeCapabilitySnapshotVersion(7, 8), record.senderCapabilityVersion)
        assertEquals(RuntimeCapabilitySnapshotVersion(7, 8), record.receiverCapabilityVersion)
        assertNull("no share was requested, so nothing may be attached", record.attachedContext)
    }

    @Test
    fun `the mailbox send path records the same attachment`() {
        // request.md:138 — 「不仅仅只有派遣的这个动作是可以这样做。就是代理之间发送
        // 消息接收…都是有这种对应的这个相应的工具，可以进行一个附加项的一个attach的」
        // The peer-to-peer path is the same field, not a second mechanism.
        val root = Files.createTempDirectory("runtime-context-send").toFile()
        val file = root.resolve("communication.json")
        val repository = RuntimeCommunicationRepository(RuntimeCommunicationFileStore(file))
        val conversation = RuntimeConversationAddress.fromStableSessionId("peer-a")
        val attachment = RuntimeContextAttachments.resolve(
            RuntimeContextShareRequest.Selected(listOf(2)),
            conversation,
            listOf("a", "b", "$CANARY is here"),
        )!!

        val result = gateway(
            repository,
            // The mailbox path must still record the attachment on the message
            // it actually delivers, so this one is authorised for real.
            authorizedSend = { _, _, _, delivery ->
                RuntimeDeliveryReceipt(accepted = true, messageId = "msg-1", effectiveDelivery = delivery)
            },
        ).send(
            actorSessionId = "peer-a",
            targetSessionId = "peer-b",
            payload = "please look at message 2",
            delivery = RuntimeDelivery.TEAM_PEER,
            attachment = attachment,
        )

        assertTrue(result.accepted)
        val onDisk = file.readText()
        assertTrue(onDisk.contains(conversation.value))
        assertFalse(onDisk.contains(CANARY))
    }

    @Test
    fun `a record written by an older build simply has no attachment`() {
        val root = Files.createTempDirectory("runtime-context-legacy").toFile()
        val file = root.resolve("communication.json")
        val conversation = RuntimeConversationAddress.fromStableSessionId("legacy")
        file.writeText(
            """
            {"schemaVersion":1,"records":[{"recordId":"old","sender":{"address":"${conversation.value}","sessionId":"legacy","executionId":null},"receiver":{"address":"${conversation.value}","sessionId":"other","executionId":null},"originalMessageId":null,"replyRoute":null,"direction":"OUTBOUND","timestampMillis":1,"state":"QUEUED","summary":"s","senderCapabilityVersion":{"configRevision":0,"capabilityRevision":0},"receiverCapabilityVersion":{"configRevision":0,"capabilityRevision":0},"directoryPolicy":{"mode":"AGENT","routeKind":"PARENT_CHILD"}}]}
            """.trimIndent(),
        )

        val records = RuntimeCommunicationFileStore(file).read()

        assertEquals(1, records.size)
        assertNull("a row from before this field existed must not invent one", records.single().attachedContext)
    }

    private fun gateway(
        repository: RuntimeCommunicationRepository,
        /**
         * [recordDelivered] must not route anything through the mailbox — the
         * delegation already delivered the text — so the default here throws,
         * which proves that path never calls it.
         */
        authorizedSend: (String, String, String, RuntimeDelivery) -> RuntimeDeliveryReceipt =
            { _, _, _, _ -> error("recordDelivered must not deliver through the mailbox") },
    ) = RuntimeCommunicationGateway(
        repository = repository,
        authorizedSend = authorizedSend,
        capabilityVersion = { RuntimeCapabilitySnapshotVersion(7, 8) },
        nowMillis = { 1_700_000_000_000L },
    )

    private companion object {
        /** The sender's message text; must never reach disk. */
        const val CANARY = "CANARY_TRANSCRIPT_BODY_9f3a"
    }
}
