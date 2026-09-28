package com.openminis.app.feature.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeCommunicationGatewayTest {
    private fun repository(root: File): RuntimeCommunicationRepository =
        RuntimeCommunicationRepository(RuntimeCommunicationFileStore(File(root, "communication.json")))

    @Test
    fun `authorized send persists queued metadata`() {
        val root = createTempDir(prefix = "gateway-")
        try {
            val repo = repository(root)
            val gateway = RuntimeCommunicationGateway(
                repo,
                authorizedSend = { _, _, _, _ -> RuntimeDeliveryReceipt(true, "m1", null, null) },
                nowMillis = { 10L },
            )
            val result = gateway.send("parent", "child", "hello", RuntimeDelivery.QUEUE)
            assertTrue(result.accepted)
            val page = repo.query(RuntimeCommunicationQuery(peerSessionId = "parent")) as RuntimeCommunicationQueryResult.Accepted
            assertEquals(RuntimeCommunicationState.QUEUED, page.page.records.single().state)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `authorization rejection is not reported as accepted`() {
        val root = createTempDir(prefix = "gateway-")
        try {
            val repo = repository(root)
            val gateway = RuntimeCommunicationGateway(
                repository = repo,
                authorizedSend = { _, _, _, _ ->
                    RuntimeDeliveryReceipt(false, null, null, "not descendant")
                },
            )
            val result = gateway.send("sibling-a", "sibling-b", "hello", RuntimeDelivery.QUEUE)
            assertFalse(result.accepted)
            val page = repo.query(RuntimeCommunicationQuery(peerSessionId = "sibling-a")) as RuntimeCommunicationQueryResult.Accepted
            assertEquals(RuntimeCommunicationState.REJECTED, page.page.records.single().state)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `address locator is scoped to the injected actor`() {
        val root = createTempDir(prefix = "gateway-")
        try {
            val repo = repository(root)
            val gateway = RuntimeCommunicationGateway(
                repo,
                authorizedSend = { _, _, _, _ -> RuntimeDeliveryReceipt(true, "m1", null, null) },
            )
            gateway.send("parent", "child", "hello", RuntimeDelivery.QUEUE)
            val result = gateway.query("parent", RuntimeCommunicationQuery(address = RuntimeConversationAddress.fromStableSessionId("child")))
            assertTrue(result is RuntimeCommunicationQueryResult.Accepted)
            assertEquals(1, (result as RuntimeCommunicationQueryResult.Accepted).page.records.size)
        } finally { root.deleteRecursively() }
    }
    @Test
    fun `query and detail reject another actor`() {
        val root = createTempDir(prefix = "gateway-")
        try {
            val repo = repository(root)
            val gateway = RuntimeCommunicationGateway(
                repository = repo,
                authorizedSend = { _, _, _, _ ->
                    RuntimeDeliveryReceipt(true, "m1", null, null)
                },
            )
            val sent = gateway.send("parent", "child", "hello", RuntimeDelivery.QUEUE)
            val recordId = (sent as RuntimeCommunicationGateway.SendResult.Accepted).recordId
            assertTrue(gateway.query("other", RuntimeCommunicationQuery(peerSessionId = "parent")) is RuntimeCommunicationQueryResult.Rejected)
            assertTrue(gateway.detail("other", RuntimeCommunicationDetailRequest(recordId)) is RuntimeCommunicationDetailResult.Rejected)
        } finally { root.deleteRecursively() }
    }
}
