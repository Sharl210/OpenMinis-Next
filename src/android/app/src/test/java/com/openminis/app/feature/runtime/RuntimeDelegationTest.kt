package com.openminis.app.feature.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test

class RuntimeDelegationTest {
    @Test
    fun `command recognizer catches empty and full width delegation commands`() {
        assertTrue(RuntimeDelegationParser.isCommand("/team"))
        assertTrue(RuntimeDelegationParser.isCommand("／subagent\t"))
        assertTrue(RuntimeDelegationParser.isCommand("/delegate do work"))
        assertFalse(RuntimeDelegationParser.isCommand("/teammate do work"))
    }

    @Test
    fun `parser keeps traditional mode and defaults`() {
        val request = RuntimeDelegationParser.parse("/subagent inspect the runtime tree")
        assertNotNull(request)
        assertEquals(DelegationMode.TRADITIONAL, request?.mode)
        assertEquals("inspect the runtime tree", request?.prompt)
        assertEquals(RuntimeModelSnapshot.DEFAULT_CAPABILITIES, request?.capabilities)
    }

    @Test
    fun `parser reads team options and quoted note`() {
        val request = RuntimeDelegationParser.parse(
            "/team --provider=anthropic --model=claude-sonnet " +
                "--note=\"review this\" --capabilities=text_input,reasoning summarize tree",
        )
        assertNotNull(request)
        assertEquals(DelegationMode.TEAM, request?.mode)
        assertEquals("anthropic", request?.provider)
        assertEquals("claude-sonnet", request?.model)
        assertEquals("review this", request?.note)
        assertEquals(setOf("text_input", "reasoning"), request?.capabilities)
        assertEquals("summarize tree", request?.prompt)
    }

    @Test
    fun `team peer delivery is persisted and queue downgrades after child completion`() {
        val tree = RuntimeSessionTree(clock = { 10L })
        val root = tree.createRoot(
            "root",
            RuntimeModelSnapshot("p", "m"),
            delegationMode = DelegationMode.TEAM,
        )
        val childA = tree.createChild(root.id, "a", RuntimeModelSnapshot("p", "a")).getOrThrow()
        val childB = tree.createChild(root.id, "b", RuntimeModelSnapshot("p", "b")).getOrThrow()
        tree.start(root.id)
        tree.start(childA.id)
        tree.start(childB.id)

        val authorization = tree.addTeamPeerEdge(
            childA.id,
            childB.id,
            setOf(RuntimeEdgePermission.SEND, RuntimeEdgePermission.STEER),
        )
        assertTrue(authorization.accepted)
        val peer = tree.send(childA.id, childB.id, "hello", RuntimeDelivery.TEAM_PEER)
        assertTrue(peer.accepted)
        assertEquals(RuntimeDelivery.TEAM_PEER, peer.effectiveDelivery)
        assertEquals("hello", tree.claimNextStep(childB.id)?.payload)

        tree.complete(childB.id)
        val queued = tree.send(childA.id, childB.id, "next", RuntimeDelivery.STEER)
        assertTrue(queued.accepted)
        assertEquals(RuntimeDelivery.QUEUE, queued.effectiveDelivery)
        assertEquals("next", tree.claimNextTurn(childB.id)?.payload)
    }

    @Test
    fun `team peer authorization revocation is recorded and blocks future delivery`() {
        val tree = RuntimeSessionTree(clock = { 20L })
        val root = tree.createRoot("root", RuntimeModelSnapshot("p", "m"), DelegationMode.TEAM)
        val a = tree.createChild(root.id, "a", RuntimeModelSnapshot("p", "a")).getOrThrow()
        val b = tree.createChild(root.id, "b", RuntimeModelSnapshot("p", "b")).getOrThrow()
        tree.start(root.id); tree.start(a.id); tree.start(b.id)
        val edge = tree.addTeamPeerEdge(a.id, b.id)
        assertTrue(edge.accepted)
        assertTrue(tree.send(a.id, b.id, "before", RuntimeDelivery.TEAM_PEER).accepted)
        // [team-peer-mesh] Joining a Team already links a member to its peers, so the
        // explicit call above adds a SECOND grant for the same direction. Revoking one
        // of two deliberate grants must not read as "revoked": the direction is blocked
        // only once every active grant for it is gone.
        val directionEdges = tree.topology().activeEdges.filter {
            it.kind == RuntimeEdgeKind.TEAM_PEER && it.fromNodeId == a.id && it.toNodeId == b.id
        }
        assertTrue("expected the mesh grant plus the explicit one", directionEdges.size >= 2)
        directionEdges.forEach { assertTrue(tree.revokeTopologyEdge(it.id).accepted) }
        val blocked = tree.send(a.id, b.id, "after", RuntimeDelivery.TEAM_PEER)
        assertFalse(blocked.accepted)
        assertTrue(tree.receipts().any { !it.accepted && it.reason?.contains("not authorized") == true })
        val restored = RuntimeSessionTree(clock = { 21L })
        assertTrue(restored.restoreJson(tree.toJson()))
        assertFalse(restored.topology().activeEdges.any { it.id == edge.edgeId })
        assertTrue(restored.receipts().size >= 2)
    }
    @Test
    fun `notify requires authorization can be claimed and receipt survives restore`() {
        val tree = RuntimeSessionTree(clock = { 30L })
        val root = tree.createRoot("root", RuntimeModelSnapshot("p", "m"))
        val child = tree.createChild(root.id, "child", RuntimeModelSnapshot("p", "m")).getOrThrow()
        tree.start(root.id); tree.start(child.id)

        val rejected = tree.send(child.id, root.id, "unauthorized", RuntimeDelivery.NOTIFY)
        assertFalse(rejected.accepted)
        assertTrue(tree.receipts().last().status == RuntimeReceiptStatus.REJECTED)

        val subscription = tree.addSubscription(child.id, root.id, setOf(RuntimeEdgePermission.NOTIFY))
        assertTrue(subscription.accepted)
        val accepted = tree.send(root.id, child.id, "notice", RuntimeDelivery.NOTIFY)
        assertTrue(accepted.accepted)
        assertEquals(RuntimeReceiptStatus.ENQUEUED, tree.receipts().last().status)
        assertEquals("notice", tree.claimNextNotification(child.id)?.payload)
        assertEquals(RuntimeReceiptStatus.CLAIMED, tree.receipts().last().status)

        val restored = RuntimeSessionTree(clock = { 31L })
        assertTrue(restored.restoreJson(tree.toJson()))
        assertEquals(RuntimeReceiptStatus.CLAIMED, restored.receipts().last().status)
        assertTrue(restored.claimNextNotification(child.id) == null)
    }
    @Test
    fun `descendant stop authorizes descendants rejects siblings ancestors and cross root`() {
        val tree = RuntimeSessionTree(
            config = RuntimeTreeConfig(maxDepth = 3),
            clock = { 40L },
        )
        val root = tree.createRoot("root", RuntimeModelSnapshot("p", "root"))
        val child = tree.createChild(root.id, "child", RuntimeModelSnapshot("p", "child")).getOrThrow()
        val grandchild = tree.createChild(child.id, "grandchild", RuntimeModelSnapshot("p", "grandchild")).getOrThrow()
        val greatGrandchild = tree.createChild(grandchild.id, "great-grandchild", RuntimeModelSnapshot("p", "great-grandchild")).getOrThrow()
        val sibling = tree.createChild(root.id, "sibling", RuntimeModelSnapshot("p", "sibling")).getOrThrow()
        val otherRoot = tree.createRoot("other", RuntimeModelSnapshot("p", "other"))
        val otherChild = tree.createChild(otherRoot.id, "other-child", RuntimeModelSnapshot("p", "other-child")).getOrThrow()
        listOf(root, child, grandchild, greatGrandchild, sibling, otherRoot, otherChild).forEach { assertTrue(tree.start(it.id)) }

        val accepted = tree.stopDescendant(
            RuntimeStopRequest(
                actorNodeId = root.id,
                targetNodeId = grandchild.id,
                rootId = root.id,
                reason = "user interruption",
                operationId = "stop-1",
                idempotencyKey = "stop-key-1",
            ),
        )
        assertTrue(accepted.accepted)
        assertEquals(RuntimeNodeStatus.RUNNING, accepted.stateBefore)
        assertEquals(RuntimeNodeStatus.STOP_REQUESTED, accepted.stateAfter)
        assertEquals(listOf(grandchild.id, greatGrandchild.id), accepted.affectedNodeIds)
        assertEquals(RuntimeNodeStatus.STOP_REQUESTED, tree.node(grandchild.id)?.status)
        assertEquals(RuntimeNodeStatus.STOP_REQUESTED, tree.node(greatGrandchild.id)?.status)
        assertTrue(tree.complete(grandchild.id).not())
        assertTrue(tree.abort(grandchild.id, abnormal = false, reason = "stop acknowledged"))
        assertTrue(tree.abort(greatGrandchild.id, abnormal = false, reason = "stop acknowledged"))
        assertEquals(RuntimeNodeStatus.ABORTED, tree.node(grandchild.id)?.status)
        assertEquals(RuntimeNodeStatus.ABORTED, tree.stopReceipt("stop-1")?.stateAfter)
        assertTrue(tree.events().any { it.kind == "stop_requested" && it.payload.contains("stop-1") })
        assertTrue(tree.events().any { it.kind == "aborted" && it.payload.contains("stop-1") })

        val duplicate = tree.stopDescendant(
            RuntimeStopRequest(
                actorNodeId = root.id,
                targetNodeId = grandchild.id,
                rootId = root.id,
                operationId = "stop-1-retry",
                idempotencyKey = "stop-key-1",
            ),
        )
        assertEquals("stop-1", duplicate.operationId)
        assertEquals("stop-key-1", duplicate.idempotencyKey)
        assertEquals(RuntimeNodeStatus.ABORTED, duplicate.stateAfter)

        val siblingAttempt = tree.stopDescendant(
            RuntimeStopRequest(actorNodeId = sibling.id, targetNodeId = child.id, rootId = root.id, operationId = "stop-sibling"),
        )
        assertFalse(siblingAttempt.accepted)
        assertTrue(siblingAttempt.reason?.contains("descendants") == true)

        val ancestorAttempt = tree.stopDescendant(
            RuntimeStopRequest(actorNodeId = child.id, targetNodeId = root.id, rootId = root.id, operationId = "stop-ancestor"),
        )
        assertFalse(ancestorAttempt.accepted)

        val crossRootAttempt = tree.stopDescendant(
            RuntimeStopRequest(actorNodeId = root.id, targetNodeId = otherChild.id, rootId = root.id, operationId = "stop-cross-root"),
        )
        assertFalse(crossRootAttempt.accepted)
        assertTrue(tree.events().any { it.kind == "descendant_stop_rejected" && it.payload.contains("cross-root") })
    }

    @Test
    fun `descendant stop receipt and aborted state survive json round trip`() {
        val source = RuntimeSessionTree(clock = { 50L })
        val root = source.createRoot("root", RuntimeModelSnapshot("p", "root"))
        val child = source.createChild(root.id, "child", RuntimeModelSnapshot("p", "child")).getOrThrow()
        source.start(root.id)
        source.start(child.id)
        val receipt = source.stopDescendant(
            RuntimeStopRequest(
                actorNodeId = root.id,
                targetNodeId = child.id,
                rootId = root.id,
                operationId = "stop-roundtrip",
                idempotencyKey = "stop-roundtrip-key",
            ),
        )
        assertTrue(receipt.accepted)

        val restored = RuntimeSessionTree(clock = { 51L })
        assertTrue(restored.restoreJson(source.toJson()))
        assertEquals(RuntimeNodeStatus.STOP_REQUESTED, receipt.stateAfter)
        assertEquals(RuntimeNodeStatus.STOP_REQUESTED, restored.node(child.id)?.status)
        assertTrue(restored.complete(child.id).not())
        assertEquals(receipt, restored.stopReceipt("stop-roundtrip"))
        assertEquals(receipt, restored.stopDescendant(
            RuntimeStopRequest(
                actorNodeId = root.id,
                targetNodeId = child.id,
                rootId = root.id,
                operationId = "stop-roundtrip-new",
                idempotencyKey = "stop-roundtrip-key",
            ),
        ))
    }
}
