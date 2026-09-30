package com.openminis.app.feature.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Read-only audit pin for request.md:33 / :113 (R19 traditional subscription +
 * notification, R20 Team P2P explicit edges / permissions / receipts / recovery).
 *
 * Every assertion here is deliberately tied to a production-reachable entry:
 *  - the coordinator path used by the composer `/team` command
 *    (RuntimeDelegationParser -> SessionActivityTracker -> coordinator.delegate),
 *  - or the tree API that production calls after that entry.
 *
 * These tests do NOT call `addTeamPeerEdge` / `addSubscription` unless the test
 * is specifically about "only this call can create the relation".
 */
class RuntimeSubscriptionPeerReachabilityTest {

    private fun coordinator(store: RuntimeTreeStore): RuntimeSessionCoordinator {
        val constructor = RuntimeSessionCoordinator::class.java
            .getDeclaredConstructor(RuntimeTreeStore::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(store)
    }

    private fun store(dir: File): RuntimeTreeStore =
        RuntimeTreeStore.openForTest(File(dir, "session-tree.json")) { source, target ->
            Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile()

    private val model = RuntimeModelSnapshot("p", "m")

    /** The exact request the composer produces for `/team <prompt>`. */
    private val teamRequest = requireNotNull(RuntimeDelegationParser.parse("/team split the work"))

    // ---------------------------------------------------------------------
    // 1. Production entry: /team turns Team MODE on, and the team members can
    //    then address each other. (Before the peer mesh was wired in, this case
    //    asserted the opposite — mode on, no edge, delivery refused — which is
    //    exactly the gap request.md:5/:33/:160 describe; the mesh is now built
    //    by the production lifecycle itself, so the assertion moved with the
    //    fact rather than being weakened. Behaviour coverage for the mesh lives
    //    in TeamPeerMeshTest.)
    // ---------------------------------------------------------------------
    @Test
    fun `slash team command enables team mode and the team members can address each other`() {
        val dir = tempDir("runtime-peer-mode")
        try {
            val coordinator = coordinator(store(dir))
            assertNotNull(coordinator.startRoot("root"))
            assertEquals(DelegationMode.TEAM, teamRequest.mode)
            assertTrue(coordinator.delegate("root", "a", teamRequest, model))
            assertTrue(coordinator.delegate("root", "b", teamRequest, model))

            val topology = coordinator.topologySnapshot()
            val byId = topology.nodes.associateBy { it.id }
            // Team mode really is on for the whole root — this part is reachable.
            assertEquals(DelegationMode.TEAM, byId.getValue("root").delegationMode)
            assertEquals(DelegationMode.TEAM, byId.getValue("a").delegationMode)
            assertEquals(DelegationMode.TEAM, byId.getValue("b").delegationMode)
            // Both siblings really do share one root.
            assertEquals("root", byId.getValue("a").rootId)
            assertEquals(byId.getValue("a").rootId, byId.getValue("b").rootId)
            // The edges the production lifecycle writes are PARENT_CHILD (delegation)
            // plus TEAM_PEER (the team's own addresses). No subscription is created:
            // that half is still explicit-only.
            assertTrue(topology.edges.any { it.kind == RuntimeEdgeKind.PARENT_CHILD })
            assertTrue(topology.edges.any { it.kind == RuntimeEdgeKind.TEAM_PEER })
            assertTrue(topology.subscriptions.isEmpty())

            // Consequence: a sibling-to-sibling peer message is deliverable now, and
            // the receiver really holds it.
            val delivered = coordinator.send("a", "b", "hello sibling", RuntimeDelivery.TEAM_PEER)
            assertTrue(delivered.accepted)
            assertNotNull(delivered.messageId)
            assertEquals("hello sibling", coordinator.claimNextStep("b")?.payload)
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------------
    // 2. The two other rejection reasons for TEAM_PEER, pinned verbatim.
    // ---------------------------------------------------------------------
    @Test
    fun `team peer delivery rejects traditional mode and cross root with exact reasons`() {
        val traditional = RuntimeSessionTree(clock = { 1L })
        traditional.createRoot("root", model)
        traditional.createChild("root", "a", model).getOrThrow()
        traditional.createChild("root", "b", model).getOrThrow()
        traditional.start("root"); traditional.start("a"); traditional.start("b")
        assertEquals(
            "Team mode is disabled",
            traditional.send("a", "b", "hi", RuntimeDelivery.TEAM_PEER).reason,
        )

        val team = RuntimeSessionTree(clock = { 2L })
        team.createRoot("root", model, delegationMode = DelegationMode.TEAM)
        team.createRoot("other", model, delegationMode = DelegationMode.TEAM)
        team.createChild("root", "a", model).getOrThrow()
        team.createChild("other", "z", model).getOrThrow()
        listOf("root", "other", "a", "z").forEach { team.start(it) }
        assertEquals(
            "Team peers must share a root",
            team.send("a", "z", "hi", RuntimeDelivery.TEAM_PEER).reason,
        )
    }

    // ---------------------------------------------------------------------
    // 3. Sibling P2P works from team membership alone — no explicit edge call.
    //    (This used to require `addTeamPeerEdge`, which no production code
    //    called; the mesh now supplies both directions when the team is formed.)
    // ---------------------------------------------------------------------
    @Test
    fun `sibling peer delivery works without any explicit edge call`() {
        val tree = RuntimeSessionTree(clock = { 10L })
        val root = tree.createRoot("root", model, delegationMode = DelegationMode.TEAM)
        val a = tree.createChild(root.id, "a", model).getOrThrow()
        val b = tree.createChild(root.id, "b", model).getOrThrow()
        tree.start(root.id); tree.start(a.id); tree.start(b.id)

        // Siblings: same rootId, and no PARENT_CHILD edge between them.
        assertEquals(a.rootId, b.rootId)
        assertTrue(tree.topology().activeEdges.none {
            it.kind == RuntimeEdgeKind.PARENT_CHILD && it.fromNodeId == a.id && it.toNodeId == b.id
        })
        assertTrue(tree.send(a.id, b.id, "x", RuntimeDelivery.TEAM_PEER).accepted)
        assertEquals("x", tree.claimNextStep(b.id)?.payload)

        // Peer edges stay directed; the mesh grants both directions, so the reply
        // the child runner mirrors back has a route too.
        assertTrue(tree.send(b.id, a.id, "reply", RuntimeDelivery.TEAM_PEER).accepted)
        assertEquals("reply", tree.claimNextStep(a.id)?.payload)
    }

    // ---------------------------------------------------------------------
    // 4. Subscriptions are never created by the node lifecycle either.
    // ---------------------------------------------------------------------
    @Test
    fun `notification and subscription authorization only exist after addSubscription`() {
        val tree = RuntimeSessionTree(clock = { 20L })
        val root = tree.createRoot("root", model)
        val child = tree.createChild(root.id, "child", model).getOrThrow()
        tree.start(root.id); tree.start(child.id)

        // Creating and starting nodes writes no subscription.
        assertTrue(tree.topology().subscriptions.isEmpty())
        assertTrue(tree.topology().edges.none { it.kind == RuntimeEdgeKind.SUBSCRIPTION })

        val subscription = tree.addSubscription(
            subscriberNodeId = child.id,
            publisherNodeId = root.id,
            permissions = setOf(RuntimeEdgePermission.NOTIFY),
        )
        assertTrue(subscription.accepted)
        // Publisher -> subscriber carries NOTIFY.
        assertTrue(tree.send(root.id, child.id, "notice", RuntimeDelivery.NOTIFY).accepted)
        assertEquals("notice", tree.claimNextNotification(child.id)?.payload)
        // The same subscription does NOT let the subscriber send anything upward:
        // SEND/STEER needs the permission on the subscription and is read in the
        // opposite direction (subscriber == from, publisher == to).
        assertEquals(
            "delivery edge is not authorized",
            tree.send(child.id, root.id, "up", RuntimeDelivery.STEER).reason,
        )

        assertTrue(tree.revokeSubscription(subscription.edgeId!!).accepted)
        // Finding: revoking the subscription does NOT stop parent -> child NOTIFY,
        // because the delegation (PARENT_CHILD) edge independently grants NOTIFY in
        // the same direction. In the parent -> child direction the subscription is
        // therefore redundant; it only adds reach where no delegation edge exists
        // (e.g. publisher and subscriber are not parent/child) — a case that needs
        // addSubscription, which production never calls.
        assertTrue(tree.send(root.id, child.id, "after revoke", RuntimeDelivery.NOTIFY).accepted)
        assertEquals(0, tree.topology().activeSubscriptions.size)
    }

    @Test
    fun `subscription send direction is subscriber to publisher the reverse of notify`() {
        val tree = RuntimeSessionTree(clock = { 21L })
        val root = tree.createRoot("root", model, delegationMode = DelegationMode.TEAM)
        val a = tree.createChild(root.id, "a", model).getOrThrow()
        val b = tree.createChild(root.id, "b", model).getOrThrow()
        tree.start(root.id); tree.start(a.id); tree.start(b.id)

        // subscriber = b, publisher = a, permission SEND (siblings: no parent/child edge).
        assertTrue(tree.addSubscription(b.id, a.id, setOf(RuntimeEdgePermission.SEND)).accepted)
        // subscriber -> publisher is the direction this branch authorizes.
        assertTrue(tree.send(b.id, a.id, "request", RuntimeDelivery.QUEUE).accepted)
        assertEquals("request", tree.claimNextTurn(a.id)?.payload)
        // The NOTIFY direction needs the NOTIFY permission, not SEND.
        assertEquals(
            "delivery edge is not authorized",
            tree.send(a.id, b.id, "notify", RuntimeDelivery.NOTIFY).reason,
        )
    }

    // ---------------------------------------------------------------------
    // 5. Receipt state machine: ENQUEUED -> CLAIMED, no consumption ack.
    //    The claim itself is a lease now, so "no consumption ack" no longer
    //    means "lost forever": see RuntimeMessageClaimLeaseTest for the
    //    expiry/requeue coverage.
    // ---------------------------------------------------------------------
    @Test
    fun `receipt status machine has no delivered or consumed terminal state`() {
        assertEquals(
            listOf(
                RuntimeReceiptStatus.ENQUEUED,
                RuntimeReceiptStatus.CLAIMED,
                RuntimeReceiptStatus.REJECTED,
            ),
            RuntimeReceiptStatus.values().toList(),
        )

        val tree = RuntimeSessionTree(clock = { 30L })
        val root = tree.createRoot("root", model)
        val child = tree.createChild(root.id, "child", model).getOrThrow()
        tree.start(root.id); tree.start(child.id)
        assertTrue(tree.addSubscription(child.id, root.id, setOf(RuntimeEdgePermission.NOTIFY)).accepted)
        assertTrue(tree.send(root.id, child.id, "notice", RuntimeDelivery.NOTIFY).accepted)
        assertEquals(RuntimeReceiptStatus.ENQUEUED, tree.receipts().last().status)

        assertEquals("notice", tree.claimNextNotification(child.id)?.payload)
        assertEquals(RuntimeReceiptStatus.CLAIMED, tree.receipts().last().status)
        assertEquals("message claimed", tree.receipts().last().reason)
        // Claiming twice returns nothing: the envelope is marked, not removed.
        assertNull(tree.claimNextNotification(child.id))

        // Nothing in the state machine can ever reach "consumed"/"delivered".
        assertTrue(tree.receipts().none { it.status.name == "DELIVERED" })
    }

    @Test
    fun `claimed but unconsumed message is held for one lease then redelivered after restore`() {
        val tree = RuntimeSessionTree(clock = { 31L })
        val root = tree.createRoot("root", model)
        val child = tree.createChild(root.id, "child", model).getOrThrow()
        tree.start(root.id); tree.start(child.id)
        assertTrue(tree.addSubscription(child.id, root.id, setOf(RuntimeEdgePermission.NOTIFY)).accepted)
        assertTrue(tree.send(root.id, child.id, "notice", RuntimeDelivery.NOTIFY).accepted)

        // The claim is taken, then the process dies before the payload reaches
        // the model. This used to be a one-way door; it is a lease now.
        val claimed = requireNotNull(tree.claimNextNotification(child.id))
        val claimedAt = requireNotNull(claimed.claimedAtMillis)

        // Inside the lease the message is held, so a live child is never fed the
        // same instruction twice.
        val held = RuntimeSessionTree(clock = { 32L })
        assertTrue(held.restoreJson(tree.toJson()))
        assertEquals(RuntimeReceiptStatus.CLAIMED, held.receipts().last().status)
        assertNull(held.claimNextNotification(child.id))
        assertTrue(held.toJson().contains("\"claimed\":true"))

        // Past the lease the claim belongs to a claimant that never consumed
        // anything, and the same durable envelope has to be claimable again
        // instead of staying invisible to every claim path forever.
        val recoveredClock = claimedAt + RuntimeSessionTree.MESSAGE_CLAIM_LEASE_MILLIS
        val recovered = RuntimeSessionTree(clock = { recoveredClock })
        assertTrue(recovered.restoreJson(tree.toJson()))
        val redelivered = requireNotNull(recovered.claimNextNotification(child.id))
        assertEquals(claimed.id, redelivered.id)
        assertEquals("notice", redelivered.payload)
        assertEquals(recoveredClock, redelivered.claimedAtMillis)
    }

    // ---------------------------------------------------------------------
    // 6. The gateway does not loosen authorization, and a teammate's peer route
    //    is recorded as an accepted directory entry against a real tree.
    // ---------------------------------------------------------------------
    @Test
    fun `gateway peer send against a real tree reaches the teammate and is recorded`() {
        val dir = tempDir("runtime-peer-gateway")
        try {
            val tree = RuntimeSessionTree(clock = { 40L })
            val root = tree.createRoot("root", model, delegationMode = DelegationMode.TEAM)
            val a = tree.createChild(root.id, "a", model).getOrThrow()
            val b = tree.createChild(root.id, "b", model).getOrThrow()
            tree.start(root.id); tree.start(a.id); tree.start(b.id)

            val repository = RuntimeCommunicationRepository(
                RuntimeCommunicationFileStore(File(dir, "communication-metadata.json")),
            )
            val gateway = RuntimeCommunicationGateway(
                repository = repository,
                // Same wiring production uses: the runtime stays the authority.
                authorizedSend = { actor, target, payload, delivery ->
                    tree.send(actor, target, payload, delivery)
                },
                nowMillis = { 40L },
            )

            val sent = gateway.send("a", "b", "hello", RuntimeDelivery.TEAM_PEER)
            assertTrue(sent is RuntimeCommunicationGateway.SendResult.Accepted)
            assertEquals("hello", tree.claimNextStep(b.id)?.payload)

            val page = repository.query(RuntimeCommunicationQuery(peerSessionId = "a"))
            val record = (page as RuntimeCommunicationQueryResult.Accepted).page.records.single()
            assertEquals(RuntimeCommunicationState.QUEUED, record.state)
            assertEquals(RuntimeCommunicationDirectoryMode.TEAM, record.directoryPolicy.mode)
            assertEquals(RuntimeCommunicationRouteKind.TEAM_PEER, record.directoryPolicy.routeKind)

            // The gateway is still not the authority: a default-mode pair keeps being
            // refused, and the refusal is recorded as such.
            val outsiderTree = RuntimeSessionTree(clock = { 41L })
            val outsiderRoot = outsiderTree.createRoot("outsider", model)
            outsiderTree.createChild(outsiderRoot.id, "a", model).getOrThrow()
            outsiderTree.start(outsiderRoot.id); outsiderTree.start("a")
            val outsideGateway = RuntimeCommunicationGateway(
                repository = repository,
                authorizedSend = { actor, target, payload, delivery ->
                    outsiderTree.send(actor, target, payload, delivery)
                },
                nowMillis = { 41L },
            )
            val rejected = outsideGateway.send("outsider", "a", "hello", RuntimeDelivery.TEAM_PEER)
            assertTrue(rejected is RuntimeCommunicationGateway.SendResult.Rejected)
            assertEquals(
                "Team mode is disabled",
                (rejected as RuntimeCommunicationGateway.SendResult.Rejected).reason,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------------
    // 7. Durability round trip: peer edges and subscriptions survive, so the
    //    missing piece is creation, not persistence.
    // ---------------------------------------------------------------------
    @Test
    fun `peer edges and subscriptions persist across restore`() {
        val tree = RuntimeSessionTree(clock = { 50L })
        val root = tree.createRoot("root", model, delegationMode = DelegationMode.TEAM)
        val a = tree.createChild(root.id, "a", model).getOrThrow()
        val b = tree.createChild(root.id, "b", model).getOrThrow()
        tree.start(root.id); tree.start(a.id); tree.start(b.id)
        // The peer edges exist because the members joined a team (both directions of
        // a-b, plus each member to the root); the subscription is still explicit-only
        // and is the part this case proves survives the round trip.
        assertEquals(1, tree.topology().activeEdges.count {
            it.kind == RuntimeEdgeKind.TEAM_PEER && it.fromNodeId == a.id && it.toNodeId == b.id
        })
        assertTrue(tree.addSubscription(b.id, a.id).accepted)

        val restored = RuntimeSessionTree(clock = { 51L })
        assertTrue(restored.restoreJson(tree.toJson()))
        assertEquals(1, restored.topology().activeEdges.count {
            it.kind == RuntimeEdgeKind.TEAM_PEER && it.fromNodeId == a.id && it.toNodeId == b.id
        })
        assertEquals(1, restored.topology().activeEdges.count {
            it.kind == RuntimeEdgeKind.TEAM_PEER && it.fromNodeId == b.id && it.toNodeId == a.id
        })
        assertEquals(1, restored.topology().activeSubscriptions.size)
        assertTrue(restored.send(a.id, b.id, "still works", RuntimeDelivery.TEAM_PEER).accepted)
    }

    // ---------------------------------------------------------------------
    // 8. Where a subscription WOULD matter (sibling -> sibling notify) it is
    //    the only possible authorization — and only addSubscription can grant it.
    // ---------------------------------------------------------------------
    @Test
    fun `subscription is the only way to notify a non parent and is unreachable in production`() {
        val tree = RuntimeSessionTree(clock = { 22L })
        val root = tree.createRoot("root", model)
        val a = tree.createChild(root.id, "a", model).getOrThrow()
        val b = tree.createChild(root.id, "b", model).getOrThrow()
        listOf(root.id, a.id, b.id).forEach { tree.start(it) }

        // Sibling -> sibling: no delegation edge exists, so NOTIFY is rejected.
        assertEquals(
            "delivery edge is not authorized",
            tree.send(a.id, b.id, "notice", RuntimeDelivery.NOTIFY).reason,
        )
        assertTrue(
            tree.addSubscription(
                subscriberNodeId = b.id,
                publisherNodeId = a.id,
                permissions = setOf(RuntimeEdgePermission.NOTIFY),
            ).accepted,
        )
        assertTrue(tree.send(a.id, b.id, "notice", RuntimeDelivery.NOTIFY).accepted)
        assertEquals("notice", tree.claimNextNotification(b.id)?.payload)
    }

    // ---------------------------------------------------------------------
    // 9. The runtime now AUTHORIZES and ENQUEUES a TEAM_PEER envelope between
    //    teammates.
    //
    // Before the peer mesh was wired in, this case asserted the opposite: send()
    // rejected TEAM_PEER without an edge, the only edge creator was
    // addTeamPeerEdge, and no production code called it.
    //
    // Scope of this case, stated precisely because the previous title overstated
    // it: the caller here is the coordinator API itself, NOT a model-facing path.
    // What is proven is that the runtime layer authorizes teammate-to-teammate
    // delivery and that the envelope is claimable; it is NOT proof that any
    // production caller performs a sibling-to-sibling send. As of this change that
    // caller still does not exist — delegated children are handed no messaging tool
    // (AgentTools.makeChildAgentTools) and `message_child` accepts only
    // steer/queue/notify. Closing that is a separate, model-facing change.
    //
    // What the mesh does settle is the boundary: it grants SEND between peers, and
    // SEND is what a peer message and a queued turn need — steering a peer and
    // notifying one are still refused, which is what keeps the master/slave
    // relation of request.md:33 intact.
    // ---------------------------------------------------------------------
    @Test
    fun `the runtime authorizes and enqueues a team peer envelope for a teammate`() {
        val dir = tempDir("runtime-peer-enqueue")
        try {
            val coordinator = coordinator(store(dir))
            assertNotNull(coordinator.startRoot("root"))
            assertTrue(coordinator.delegate("root", "a", teamRequest, model))
            assertTrue(coordinator.delegate("root", "b", teamRequest, model))

            // The peer lane a teammate can use, and the queued-turn lane the
            // message_child tool can request. Both are SEND-authorized.
            val peer = coordinator.send("a", "b", "peer", RuntimeDelivery.TEAM_PEER)
            assertTrue(peer.accepted)
            assertEquals(RuntimeDelivery.TEAM_PEER, peer.effectiveDelivery)
            assertEquals("peer", coordinator.claimNextStep("b")?.payload)

            assertTrue(coordinator.send("a", "b", "queue", RuntimeDelivery.QUEUE).accepted)
            assertEquals("queue", coordinator.claimNextTurn("b")?.payload)

            // Not granted by the mesh: a peer may not interrupt another peer, and
            // a peer may not notify one without a subscription.
            assertEquals(
                "delivery edge is not authorized",
                coordinator.send("a", "b", "steer", RuntimeDelivery.STEER).reason,
            )
            assertEquals(
                "delivery edge is not authorized",
                coordinator.send("a", "b", "notify", RuntimeDelivery.NOTIFY).reason,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------------
    // 10. Child-completion notifications ARE enqueued for the parent through a
    //     real production path (SessionActivityTracker -> finishChild), and they
    //     are claimable — but the notification half is delivered to whoever
    //     claims it, and the only production claimer is RuntimeChildRunner
    //     (:163) for a *child* session. A root-addressed notification therefore
    //     has no consumer in production.
    // ---------------------------------------------------------------------
    @Test
    fun `child completion notification is enqueued for the parent through the production finish path`() {
        val dir = tempDir("runtime-notify-finish")
        try {
            val coordinator = coordinator(store(dir))
            assertNotNull(coordinator.startRoot("root"))
            assertTrue(coordinator.delegate("root", "child", teamRequest, model))

            // This is exactly what SessionActivityTracker.finishDelegatedChild does.
            coordinator.finishChild("child")

            // The notification exists and is addressed to the root's runtime node.
            // It bypassed send() entirely (SessionTreeRuntime.notifyParentOnStop
            // appends straight to the inbox), so no subscription and no NOTIFY
            // authorization branch was ever evaluated on the way in.
            val notification = coordinator.claimNextNotification("root")
            assertNotNull(notification)
            assertEquals(RuntimeDelivery.NOTIFY, notification!!.delivery)
            assertEquals("child", notification.fromNodeId)
            assertTrue(notification.toNodeId == "root")
            assertTrue(notification.payload.contains("completed"))
            assertTrue(notification.taskIntent.contains("child_completed"))
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------------
    // 11. The tree cannot actually get deeper than root + one level, because a
    //     delegated child is handed a tool set with no spawn tool
    //     (AgentTools.makeChildAgentTools, :76-81) and delegation is otherwise a
    //     human composer command (RuntimeDelegationParser). Consequences for the
    //     requirement's own scenario at request.md:113 ("the main agent may need
    //     to send a message to a THIRD-level agent"): a third-level agent cannot
    //     exist in production, and siblings are the only non-parent/child
    //     topology that can exist at all.
    // ---------------------------------------------------------------------
    @Test
    fun `no production affordance can spawn a third level child`() {
        val childToolNames = com.openminis.app.tools.AgentTools.makeChildAgentTools().map { it.name }
        assertTrue(
            "a delegated child must have no spawn tool, otherwise the tree could recurse: $childToolNames",
            childToolNames.none {
                it in setOf("subagent", "delegate", "team", "spawn_subagent")
            },
        )
        // The names a configured child would need are still only reachable from the
        // human composer, and the child's own list contains none of them.
        assertFalse(
            com.openminis.app.tools.AgentTools.makeChildAgentTools().any {
                RuntimeDelegationParser.isCommand("/${it.name}")
            },
        )

        // And the runtime itself has no automatic child creation: createChild is
        // only reachable through the human-typed delegation path.
        val tree = RuntimeSessionTree(clock = { 60L })
        val root = tree.createRoot("root", model, delegationMode = DelegationMode.TEAM)
        val child = tree.createChild(root.id, "a", model).getOrThrow()
        tree.start(root.id); tree.start(child.id)
        // depth 0 = root, depth 1 = the only child level that can exist here.
        assertEquals(0, root.depth)
        assertEquals(1, child.depth)
        assertTrue(tree.descendants(child.id).isEmpty())
    }
}
