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
 * [team-peer-mesh] Behaviour coverage for the requirement that names the peer
 * mesh directly:
 *
 *  - request.md:5 — a sub-agent brought in by `/team` joins a team whose members
 *    "can all communicate with each other"; without `/team` it is the default
 *    sub-agent mode, which has no such addresses.
 *  - request.md:33 — traditional mode is one server to many clients over
 *    subscribe+notify, while Team members are "P2P nodes ... point to point, they
 *    can talk to each other", keeping the master/slave relation and adding
 *    sibling-to-sibling links.
 *  - request.md:160 — the team "lets the whole team know each other", members
 *    "build [addresses] for themselves, like IP addresses", which is exactly what
 *    traditional mode lacks ("no address, no direction").
 *
 * What is asserted is the observable outcome — an envelope that used to be
 * rejected now arrives, and the member on the other side really claims it — not
 * the mere presence of an edge. Edge counts appear only where the property being
 * pinned IS structural (idempotence, the direction set, the root boundary).
 *
 * Every test drives a real entry point: `RuntimeSessionCoordinator.delegate` is
 * the path the composer's `/team` command uses, and `RuntimeSessionTree` is what
 * that path calls after it. No test calls `addTeamPeerEdge` to set up the mesh it
 * is asserting on.
 */
class TeamPeerMeshTest {

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

    private fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    private val model = RuntimeModelSnapshot("p", "m")

    /** The exact request the composer produces for `/team <prompt>`. */
    private val teamRequest = requireNotNull(RuntimeDelegationParser.parse("/team split the work"))

    /** The exact request the composer produces for `/subagent <prompt>`. */
    private val traditionalRequest =
        requireNotNull(RuntimeDelegationParser.parse("/subagent inspect the runtime tree"))

    /**
     * The structural property the mesh is defined by: for one ordered pair of
     * members there is at most one active TEAM_PEER edge that carries SEND, which
     * is the permission a peer message needs. A set would hide duplicates, so
     * duplicates are counted, not collected.
     */
    private fun peerEdgeCount(topology: RuntimeTopologySnapshot, from: String, to: String): Int =
        topology.activeEdges.count {
            it.kind == RuntimeEdgeKind.TEAM_PEER &&
                it.fromNodeId == from && it.toNodeId == to &&
                RuntimeEdgePermission.SEND in it.permissions
        }

    private fun allPeerEdges(topology: RuntimeTopologySnapshot): List<RuntimeTopologyEdge> =
        topology.activeEdges.filter { it.kind == RuntimeEdgeKind.TEAM_PEER }

    // ---------------------------------------------------------------------
    // 1. The default sub-agent mode stays addressless.
    // ---------------------------------------------------------------------
    @Test
    fun `a traditional dispatch creates no peer address and siblings still cannot reach each other`() {
        val dir = tempDir("mesh-traditional")
        try {
            val coordinator = coordinator(store(dir))
            assertNotNull(coordinator.startRoot("root"))
            assertEquals(DelegationMode.TRADITIONAL, traditionalRequest.mode)
            assertTrue(coordinator.delegate("root", "a", traditionalRequest, model))
            assertTrue(coordinator.delegate("root", "b", traditionalRequest, model))

            val topology = coordinator.topologySnapshot()
            val byId = topology.nodes.associateBy { it.id }
            assertEquals(DelegationMode.TRADITIONAL, byId.getValue("a").delegationMode)
            assertEquals(DelegationMode.TRADITIONAL, byId.getValue("b").delegationMode)
            assertEquals("root", byId.getValue("a").rootId)
            assertEquals(byId.getValue("a").rootId, byId.getValue("b").rootId)

            // request.md:5 — no `/team` means the default mode, and the default mode
            // has no peer addresses: not one TEAM_PEER edge in either direction.
            assertTrue(
                "a default-mode dispatch must not mint a peer address: ${allPeerEdges(topology)}",
                allPeerEdges(topology).isEmpty(),
            )
            assertEquals(0, peerEdgeCount(topology, "a", "b"))
            assertEquals(0, peerEdgeCount(topology, "b", "a"))

            // The consequence is observable: the delivery is still refused.
            val rejected = coordinator.send("a", "b", "hello sibling", RuntimeDelivery.TEAM_PEER)
            assertFalse(rejected.accepted)
            assertEquals("Team mode is disabled", rejected.reason)
            assertNull(rejected.messageId)
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------------
    // 2. Team membership means reachability — asserted by delivery, not by edge.
    // ---------------------------------------------------------------------
    @Test
    fun `team members can reach each other as soon as the team is formed`() {
        val dir = tempDir("mesh-team")
        try {
            val coordinator = coordinator(store(dir))
            assertNotNull(coordinator.startRoot("root"))
            assertTrue(coordinator.delegate("root", "a", teamRequest, model))
            assertTrue(coordinator.delegate("root", "b", teamRequest, model))

            // No explicit "connect these two" call was ever made: the two members
            // joined a team, and joining is what builds the addresses.
            val delivery = coordinator.send("a", "b", "peer hello", RuntimeDelivery.TEAM_PEER)
            assertTrue(
                "Team members must be able to address each other, but delivery was refused: " +
                    delivery.reason,
                delivery.accepted,
            )
            assertEquals(RuntimeDelivery.TEAM_PEER, delivery.effectiveDelivery)
            assertNotNull(delivery.messageId)

            // The observable end of the chain: the other member actually reads it.
            val claimed = coordinator.claimNextStep("b")
            assertNotNull("the peer message must be waiting for the receiver", claimed)
            assertEquals("peer hello", claimed!!.payload)
            assertEquals("a", claimed.fromNodeId)
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------------
    // 3. Reachability is mutual, in both directions, including root <-> member.
    // ---------------------------------------------------------------------
    @Test
    fun `peer reachability is mutual in both directions`() {
        val dir = tempDir("mesh-both-ways")
        try {
            val coordinator = coordinator(store(dir))
            assertNotNull(coordinator.startRoot("root"))
            assertTrue(coordinator.delegate("root", "a", teamRequest, model))
            assertTrue(coordinator.delegate("root", "b", teamRequest, model))

            // Sibling -> sibling and its reverse. `RuntimeChildRunner` mirrors an
            // inbound TEAM_PEER back along the reply direction, so a one-way mesh
            // would make the answer undeliverable.
            assertTrue(coordinator.send("a", "b", "a to b", RuntimeDelivery.TEAM_PEER).accepted)
            assertTrue(coordinator.send("b", "a", "b to a", RuntimeDelivery.TEAM_PEER).accepted)
            assertEquals("a to b", coordinator.claimNextStep("b")?.payload)
            assertEquals("b to a", coordinator.claimNextStep("a")?.payload)

            // The dispatcher is a team member too (request.md:160 — the WHOLE team
            // knows each other), so the mesh is not sibling-only.
            assertTrue(coordinator.send("root", "a", "root to a", RuntimeDelivery.TEAM_PEER).accepted)
            assertTrue(coordinator.send("a", "root", "a to root", RuntimeDelivery.TEAM_PEER).accepted)
            assertEquals("root to a", coordinator.claimNextStep("a")?.payload)
            assertEquals("a to root", coordinator.claimNextStep("root")?.payload)
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------------
    // 4. The mesh is not a way around the "one root" rule.
    // ---------------------------------------------------------------------
    @Test
    fun `peer reachability never crosses a root boundary`() {
        val tree = RuntimeSessionTree(clock = { 2L })
        val root = tree.createRoot("root", model, delegationMode = DelegationMode.TEAM)
        val other = tree.createRoot("other", model, delegationMode = DelegationMode.TEAM)
        val a = tree.createChild(root.id, "a", model).getOrThrow()
        val z = tree.createChild(other.id, "z", model).getOrThrow()
        listOf(root.id, other.id, a.id, z.id).forEach { tree.start(it) }

        // Both are Team members, so both got addresses — inside their own team.
        assertTrue(tree.send("a", "root", "in team", RuntimeDelivery.TEAM_PEER).accepted)
        assertTrue(tree.send("z", "other", "in team", RuntimeDelivery.TEAM_PEER).accepted)

        // The boundary itself is untouched by the mesh.
        assertEquals(
            "Team peers must share a root",
            tree.send("a", "z", "hi", RuntimeDelivery.TEAM_PEER).reason,
        )
        assertEquals(
            "Team peers must share a root",
            tree.send("other", "root", "hi", RuntimeDelivery.TEAM_PEER).reason,
        )
        val crossRoot = allPeerEdges(tree.topology()).filter { edge ->
            tree.node(edge.fromNodeId)?.rootId != tree.node(edge.toNodeId)?.rootId
        }
        assertTrue("no peer edge may span two roots: $crossRoot", crossRoot.isEmpty())
    }

    // ---------------------------------------------------------------------
    // 5. Idempotence: edges scale with members, never with dispatches.
    // ---------------------------------------------------------------------
    @Test
    fun `repeated dispatches never stack duplicate peer edges`() {
        val dir = tempDir("mesh-idempotent")
        try {
            val coordinator = coordinator(store(dir))
            assertNotNull(coordinator.startRoot("root"))
            assertTrue(coordinator.delegate("root", "a", teamRequest, model))
            assertTrue(coordinator.delegate("root", "b", teamRequest, model))

            // Two members and the root, fully connected: 3 * 2 ordered pairs.
            val afterTwo = coordinator.topologySnapshot()
            assertEquals(6, allPeerEdges(afterTwo).size)
            assertEquals(1, peerEdgeCount(afterTwo, "a", "b"))
            assertEquals(1, peerEdgeCount(afterTwo, "b", "a"))

            // Re-dispatching the SAME child and adding more members must not add a
            // second edge for a pair that is already linked.
            assertTrue(coordinator.delegate("root", "a", teamRequest, model))
            assertTrue(coordinator.delegate("root", "b", teamRequest, model))
            val afterRedispatch = coordinator.topologySnapshot()
            assertEquals(6, allPeerEdges(afterRedispatch).size)
            assertEquals(1, peerEdgeCount(afterRedispatch, "a", "b"))
            assertEquals(1, peerEdgeCount(afterRedispatch, "b", "a"))
            assertEquals(1, peerEdgeCount(afterRedispatch, "root", "a"))

            assertTrue(coordinator.delegate("root", "c", teamRequest, model))
            assertTrue(coordinator.delegate("root", "d", teamRequest, model))
            val afterFour = coordinator.topologySnapshot()
            // Four children plus the root = 5 members, 5 * 4 ordered pairs, and
            // still exactly one edge per ordered pair.
            assertEquals(20, allPeerEdges(afterFour).size)
            assertEquals(1, peerEdgeCount(afterFour, "a", "b"))
            assertEquals(1, peerEdgeCount(afterFour, "d", "c"))
            assertEquals(1, peerEdgeCount(afterFour, "root", "d"))

            // And the mesh call itself reports "nothing new" for an already-linked
            // member, which is what makes a repeat call safe rather than merely
            // tolerated.
            val tree = RuntimeSessionTree(clock = { 3L })
            val root = tree.createRoot("root", model, delegationMode = DelegationMode.TEAM)
            tree.createChild(root.id, "x", model).getOrThrow()
            tree.createChild(root.id, "y", model).getOrThrow()
            // Three members, fully connected: 3 * 2 ordered directions.
            assertEquals(6, allPeerEdges(tree.topology()).size)
            val repeat = tree.linkTeamPeers("x")
            assertTrue("already linked: ${repeat.createdEdgeIds}", repeat.createdEdgeIds.isEmpty())
            assertEquals(6, allPeerEdges(tree.topology()).size)
            assertEquals(listOf("root", "y"), repeat.peerNodeIds)
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------------
    // 6. Revocation semantics: revoked wins until the team is formed again.
    // ---------------------------------------------------------------------
    @Test
    fun `a revoked peer direction stays revoked until the team is formed again`() {
        val tree = RuntimeSessionTree(clock = { 4L })
        val root = tree.createRoot("root", model, delegationMode = DelegationMode.TEAM)
        tree.createChild(root.id, "a", model).getOrThrow()
        tree.createChild(root.id, "b", model).getOrThrow()
        listOf(root.id, "a", "b").forEach { tree.start(it) }

        assertTrue(tree.send("a", "b", "before revoke", RuntimeDelivery.TEAM_PEER).accepted)
        val edge = tree.topology().activeEdges.single {
            it.kind == RuntimeEdgeKind.TEAM_PEER && it.fromNodeId == "a" && it.toNodeId == "b"
        }
        assertTrue(tree.revokeTopologyEdge(edge.id).accepted)

        // Revocation is effective immediately, and only in the direction revoked:
        // the mesh is bidirectional, so the reverse direction is a separate edge
        // and is still standing.
        assertFalse(tree.send("a", "b", "after revoke", RuntimeDelivery.TEAM_PEER).accepted)
        assertTrue(tree.send("b", "a", "reverse still open", RuntimeDelivery.TEAM_PEER).accepted)

        // A member joining at THIS level (createChild) does not silently resurrect
        // the revoked direction: the call only links the member whose membership
        // changed, so a pair that both already belonged is never revisited.
        //
        // Careful about the level: this is NOT what a `/team` dispatch does. In
        // production `RuntimeSessionCoordinator.delegate` re-asserts Team mode
        // before every Team child start, so "a member joined" and "the team was
        // formed again" are the same dispatch, and a dispatch DOES rebuild the mesh
        // (asserted below). The distinction exists only here, at the tree level.
        tree.createChild(root.id, "c", model).getOrThrow()
        assertFalse(
            "an unrelated join must not undo a revocation",
            tree.send("a", "b", "still revoked", RuntimeDelivery.TEAM_PEER).accepted,
        )
        // ... while the new member is fully reachable in both directions.
        assertTrue(tree.send("a", "c", "hi c", RuntimeDelivery.TEAM_PEER).accepted)
        assertTrue(tree.send("c", "a", "hi a", RuntimeDelivery.TEAM_PEER).accepted)

        // The team being formed again IS the event that rebuilds the mesh: this is
        // what a further Team dispatch does (RuntimeSessionCoordinator.delegate
        // re-asserts Team mode before it starts the child).
        assertTrue(tree.setDelegationMode(root.id, DelegationMode.TEAM))
        assertTrue(
            "re-forming the team must restore full reachability",
            tree.send("a", "b", "reformed", RuntimeDelivery.TEAM_PEER).accepted,
        )
        assertEquals(1, peerEdgeCount(tree.topology(), "a", "b"))

        // Leaving Team mode takes the addresses away again and revokes the mesh,
        // so a later default-mode dispatch cannot inherit a peer route.
        assertTrue(tree.setDelegationMode(root.id, DelegationMode.TRADITIONAL))
        assertTrue(allPeerEdges(tree.topology()).isEmpty())
        assertEquals(
            "Team mode is disabled",
            tree.send("a", "b", "no team", RuntimeDelivery.TEAM_PEER).reason,
        )
    }

    // ---------------------------------------------------------------------
    // 6b. The revocation semantics AT THE PRODUCTION DISPATCH LEVEL.
    //
    // The chosen rule is "revoked until the team is formed again", and the event
    // that forms a team again in production is a further `/team` dispatch: the
    // coordinator re-asserts Team mode before every Team child start. So a revoked
    // direction IS restored by the next dispatch — including one that merely adds a
    // member. This case pins that at the real entry point, because the tree-level
    // case above would otherwise be read as the production behaviour.
    // ---------------------------------------------------------------------
    @Test
    fun `a further team dispatch restores a revoked peer direction in production`() {
        val dir = tempDir("mesh-revoke-dispatch")
        try {
            val store = store(dir)
            val coordinator = coordinator(store)
            assertNotNull(coordinator.startRoot("root"))
            assertTrue(coordinator.delegate("root", "a", teamRequest, model))
            assertTrue(coordinator.delegate("root", "b", teamRequest, model))
            assertTrue(coordinator.send("a", "b", "open", RuntimeDelivery.TEAM_PEER).accepted)

            // Revoke every active grant for a -> b. `revokeTopologyEdge` has no
            // production caller yet (it is an operator/UI affordance like
            // `addTeamPeerEdge` used to be), so the revocation is driven through the
            // same store seam the coordinator itself uses, against the same live tree.
            val revoked = coordinator.topologySnapshot().activeEdges
                .filter {
                    it.kind == RuntimeEdgeKind.TEAM_PEER &&
                        it.fromNodeId == "a" && it.toNodeId == "b"
                }
                .map { it.id }
            assertTrue(revoked.isNotEmpty())
            assertTrue(store.update { revoked.forEach { revokeTopologyEdge(it) } })
            // Revoked wins immediately: this is the half that makes revocation mean
            // anything at all.
            assertFalse(
                coordinator.send("a", "b", "blocked", RuntimeDelivery.TEAM_PEER).accepted,
            )

            // Any further /team dispatch on this root re-forms the team and therefore
            // rebuilds the mesh — here it is a dispatch that adds a third member.
            assertTrue(coordinator.delegate("root", "c", teamRequest, model))
            assertTrue(
                "a new Team dispatch re-forms the team, which rebuilds the mesh",
                coordinator.send("a", "b", "open again", RuntimeDelivery.TEAM_PEER).accepted,
            )
            assertTrue(coordinator.send("a", "c", "new member reachable", RuntimeDelivery.TEAM_PEER).accepted)
            assertTrue(coordinator.send("c", "b", "both ways", RuntimeDelivery.TEAM_PEER).accepted)
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------------
    // 7. Regression: the traditional lanes are untouched.
    // ---------------------------------------------------------------------
    @Test
    fun `traditional parent child deliveries still work in all three lanes`() {
        val tree = RuntimeSessionTree(clock = { 5L })
        val root = tree.createRoot("root", model)
        val child = tree.createChild(root.id, "child", model).getOrThrow()
        tree.start(root.id); tree.start(child.id)

        assertTrue(tree.send(root.id, child.id, "steer it", RuntimeDelivery.STEER).accepted)
        assertEquals(RuntimeDelivery.STEER, tree.receipts().last().delivery)
        assertEquals("steer it", tree.claimNextStep(child.id)?.payload)

        assertTrue(tree.send(root.id, child.id, "next turn", RuntimeDelivery.QUEUE).accepted)
        assertEquals("next turn", tree.claimNextTurn(child.id)?.payload)

        assertTrue(tree.send(root.id, child.id, "notice", RuntimeDelivery.NOTIFY).accepted)
        assertEquals("notice", tree.claimNextNotification(child.id)?.payload)

        // A default-mode tree still has no peer edges anywhere.
        assertTrue(allPeerEdges(tree.topology()).isEmpty())
        assertEquals(0, peerEdgeCount(tree.topology(), "root", "child"))
    }
}
