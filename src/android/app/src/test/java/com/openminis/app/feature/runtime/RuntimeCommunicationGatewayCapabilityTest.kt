package com.openminis.app.feature.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeCommunicationGatewayCapabilityTest {
    @Test
    fun `send attaches dynamic sender and receiver capability versions`() {
        val file = File.createTempFile("runtime-communication-", ".json")
        val repository = RuntimeCommunicationRepository(RuntimeCommunicationFileStore(file))
        val gateway = RuntimeCommunicationGateway(
            repository = repository,
            authorizedSend = { _, _, _, delivery -> RuntimeDeliveryReceipt(true, "m1", delivery, null) },
            capabilityVersion = { if (it == "a") RuntimeCapabilitySnapshotVersion(7, 3) else RuntimeCapabilitySnapshotVersion(8, 4) },
        )
        gateway.send("a", "b", "hello", RuntimeDelivery.QUEUE)
        val result = repository.query(RuntimeCommunicationQuery(peerSessionId = "a")) as RuntimeCommunicationQueryResult.Accepted
        assertEquals(RuntimeCapabilitySnapshotVersion(7, 3), result.page.records.single().senderCapabilityVersion)
        assertEquals(RuntimeCapabilitySnapshotVersion(8, 4), result.page.records.single().receiverCapabilityVersion)
        file.delete()
    }
}
