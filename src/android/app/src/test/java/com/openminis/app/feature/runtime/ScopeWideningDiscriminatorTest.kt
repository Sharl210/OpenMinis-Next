package com.openminis.app.feature.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Discriminators for four "rule-shaped refusals" that the rest of the suite
 * cannot distinguish from their wider version.
 *
 * ## The failure mode this file exists for
 *
 * A refusal assertion can be perfectly honest — real production code, no
 * mocking, a real defect turns it red — and still be blind to the *widening*
 * of the rule it names. It is blind when its scenario is refused under the
 * narrow rule AND under the wider one, which is exactly what happens when the
 * "stranger" it picks is the most obvious outsider available: another
 * session, another root, another tree. Rules in this package have historically
 * moved by WIDENING (strict ancestor → conversation root, one-directional
 * delivery → any direction inside one conversation, sender/receiver →
 * same conversation), not by deletion, so every "delete the rule and see what
 * breaks" experiment passes while the widening lands silently.
 *
 * The shape that separates two rules is therefore: **inside the same scope,
 * not an ancestor/descendant of the subject, and not a member of its subtree**
 * — adjusted per rule. Each case below is one such shape, and each was measured
 * against the widened rule before being pinned here.
 *
 * ## Measured, per case (off-tree harness, `/tmp/tva`, production tree untouched)
 *
 * | case | widened rule injected | result on the widened build |
 * |---|---|---|
 * | D1 | `gateway.query`: `peerSessionId != actor` → `conversationIdOf(peer) != conversationIdOf(actor)` | 20 tests in 3 gateway test classes stayed GREEN; only D1 red |
 * | D2a/D2b | `isScopedRootOf` call sites in `deleteSubtree` / `stopDescendant` neutralised | 53 tests stayed GREEN; only D2a and D2b red |
 * | D3 | `TEAM_PEER` "must share a root" → "must share a conversation" | 49 tests stayed GREEN; only D3 red |
 *
 * ## What each case is NOT
 *
 * Every case carries a control that passes under BOTH rules (a legitimate
 * caller still succeeding). The controls are there so that a red cannot be
 * produced by a broken fixture, a missing record or a wrong payload — but a
 * control that passes under both rules cannot substitute for the discriminator
 * above it, and none of them is written as if it could.
 */
class ScopeWideningDiscriminatorTest {

    // ─── D1 · the read rule: only a caller's OWN peer id may be queried ──────
    //
    // `RuntimeCommunicationGateway.query` refuses a query whose `peerSessionId`
    // is not the acting session. The obvious outsider — a genuinely different
    // chat, as every other refusal assertion in the suite uses — is refused by
    // "own peer id only" and by the wider "any node of my conversation" alike.
    // The discriminating shape is another NODE ID OF THE SAME CONVERSATION:
    // refused under the narrow rule, allowed under the widened one. Note it is
    // `chat-a#run-1`, which `conversationIdOf` folds onto `chat-a`, so the two
    // ids really do denote one conversation — asserted as a premise, because a
    // future change to that folding would otherwise silently turn this test
    // from "the widened rule reddens here" into "the fixture is wrong".
    @Test
    fun `a query may not be issued under another node id of the same conversation`() {
        val dir = Files.createTempDirectory("scope-widen-d1").toFile()
        try {
            val repository = RuntimeCommunicationRepository(
                RuntimeCommunicationFileStore(File(dir, "communication.json")),
            )
            val gateway = RuntimeCommunicationGateway(
                repository,
                authorizedSend = { _, _, _, _ -> RuntimeDeliveryReceipt(true, "m1", null, null) },
            )
            assertEquals(
                "premise: these two ids must denote ONE conversation, or this case tests nothing",
                ConversationIdProtocol.conversationIdOf("chat-a"),
                ConversationIdProtocol.conversationIdOf("chat-a#run-1"),
            )

            val result = gateway.query("chat-a", RuntimeCommunicationQuery(peerSessionId = "chat-a#run-1"))

            assertTrue(
                "reading under another node id of the same chat must be refused; got $result",
                result is RuntimeCommunicationQueryResult.Rejected,
            )
            assertEquals(
                RuntimeCommunicationQueryRejection.UNAUTHORIZED_ACTOR,
                (result as RuntimeCommunicationQueryResult.Rejected).reason,
            )

            // Control — holds under BOTH rules, so it cannot replace the
            // assertion above: a caller using its own peer id is accepted
            // whether the rule is "own id" or "whole conversation".
            assertTrue(
                "control: the caller's own peer id must still be readable",
                gateway.query("chat-a", RuntimeCommunicationQuery(peerSessionId = "chat-a"))
                    is RuntimeCommunicationQueryResult.Accepted,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    // ─── D2a · the caller-named `root_id`, checked before anything is removed ─
    //
    // `deleteSubtree` takes an OPTIONAL caller-named `rootId` and refuses the
    // request when that name is not a root of the target's conversation. The
    // refusals asserted elsewhere in the suite all fail the EARLIER
    // cross-conversation check, so they are green even with this check removed:
    // the wider rule "any root will do" is invisible to them. The
    // discriminating shape names a root that EXISTS and is real, but belongs to
    // a different conversation — exactly the argument an honest caller could
    // hand over by mistake, and the one the check exists for.
    @Test
    fun `deleteSubtree refuses a caller-named root that belongs to another conversation`() {
        val tree = RuntimeSessionTree(RuntimeTreeConfig(maxDepth = 4), clock = { 10L })
        tree.createRoot("root", RuntimeModelSnapshot("p", "root"))
        tree.createRoot("other-root", RuntimeModelSnapshot("p", "other-root"))
        tree.start("root")
        tree.createChild("root", "child", RuntimeModelSnapshot("p", "child")).getOrThrow()
        tree.complete("child")

        val receipt = tree.deleteSubtree(
            DeleteSubtreeRequest(
                initiatorNodeId = "root",
                executorNodeId = "root",
                targetNodeId = "child",
                // A real node id, but a root of a DIFFERENT conversation.
                rootId = "other-root",
                operationId = "scope-widen-d2a",
                idempotencyKey = "scope-widen-d2a-key",
            ),
        )

        assertEquals(
            "a wrong caller-named root must refuse rather than delete: ${receipt.reason}",
            DeleteSubtreeResult.REJECTED,
            receipt.result,
        )
        assertEquals(
            "the refusal must name the cross-root rule, not the ancestry rule behind it",
            "cross-root deletion is not authorized",
            receipt.reason,
        )
        assertNotNull("the refused target must still be there", tree.node("child"))

        // Control — holds under BOTH rules, so it cannot replace the assertion
        // above: the honest caller naming the correct root still deletes.
        val control = tree.deleteSubtree(
            DeleteSubtreeRequest(
                initiatorNodeId = "root",
                executorNodeId = "root",
                targetNodeId = "child",
                rootId = "root",
                operationId = "scope-widen-d2a-control",
                idempotencyKey = "scope-widen-d2a-control-key",
            ),
        )
        assertEquals("control: the correct root id must still work: ${control.reason}",
            DeleteSubtreeResult.DELETED, control.result)
    }

    // ─── D2b · the same check on the other destructive gate ──────────────────
    //
    // Pinned separately on purpose: `stopDescendant` and `deleteSubtree` are
    // different operations with different rules, and the check they share has
    // two call sites. One case would go red if EITHER were removed, which is
    // how a fix to one gate can look like coverage of both.
    @Test
    fun `stopDescendant refuses a caller-named root that belongs to another conversation`() {
        val tree = RuntimeSessionTree(RuntimeTreeConfig(maxDepth = 4), clock = { 10L })
        tree.createRoot("root", RuntimeModelSnapshot("p", "root"))
        tree.createRoot("other-root", RuntimeModelSnapshot("p", "other-root"))
        tree.start("root")
        tree.createChild("root", "child", RuntimeModelSnapshot("p", "child")).getOrThrow()
        tree.start("child")

        val refused = tree.stopDescendant(
            RuntimeStopRequest(
                actorNodeId = "root",
                targetNodeId = "child",
                rootId = "other-root",
                operationId = "scope-widen-d2b",
            ),
        )

        assertFalse(
            "a stop naming another conversation's root must be refused: ${refused.reason}",
            refused.accepted,
        )
        assertEquals(
            "the refusal must name the cross-root rule, not the ancestry rule behind it",
            "cross-root stop is not authorized",
            refused.reason,
        )

        // Control — holds under BOTH rules: the correct root name still works.
        val control = tree.stopDescendant(
            RuntimeStopRequest(
                actorNodeId = "root",
                targetNodeId = "child",
                rootId = "root",
                reason = "scope-widen-d2b control",
                operationId = "scope-widen-d2b-control",
            ),
        )
        assertTrue("control: the correct root id must still be accepted: ${control.reason}", control.accepted)
    }

    // ─── D3 · TEAM_PEER delivery must not leave the root it was formed in ────
    //
    // TEAM_PEER requires the two endpoints to share the same ROOT. The existing
    // refusals for this rule use two independent roots ("root" vs "other"),
    // which are also two different conversations — so they are refused whether
    // the rule is "same root" or the wider "same conversation", and they cannot
    // see the widening. The discriminating shape is two roots that ARE one
    // conversation: the `A` / `A#run-1` pair a crash and reopen produces. Both
    // are Team-mode roots, both mint peer addresses inside their own team, and
    // `conversationIdOf` folds them together — yet delivery between them must
    // still be refused, because a peer edge is a relationship inside one run.
    @Test
    fun `team peer delivery refuses two nodes that share only a conversation`() {
        val tree = RuntimeSessionTree(RuntimeTreeConfig(maxDepth = 4), clock = { 10L })
        tree.createRoot("A", RuntimeModelSnapshot("p", "A"), delegationMode = DelegationMode.TEAM)
        tree.createRoot("A#run-1", RuntimeModelSnapshot("p", "A#run-1"), delegationMode = DelegationMode.TEAM)
        tree.start("A")
        tree.start("A#run-1")
        val x = tree.createChild("A", "x", RuntimeModelSnapshot("p", "x"), delegationMode = DelegationMode.TEAM).getOrThrow()
        val y = tree.createChild("A#run-1", "y", RuntimeModelSnapshot("p", "y"), delegationMode = DelegationMode.TEAM).getOrThrow()
        tree.start(x.id)
        tree.start(y.id)

        assertEquals(
            "premise: one conversation, two roots — or this case tests nothing",
            ConversationIdProtocol.conversationIdOf("A"),
            ConversationIdProtocol.conversationIdOf("A#run-1"),
        )

        val refused = tree.send(x.id, y.id, "hello", RuntimeDelivery.TEAM_PEER)

        assertFalse("peer delivery must not span two roots: $refused", refused.accepted)
        assertEquals(
            "the refusal must name the ROOT rule, not the edge rule behind it",
            "Team peers must share a root",
            refused.reason,
        )

        // Control — holds under BOTH rules, so it cannot replace the assertion
        // above: two members of ONE root, with a peer edge, still deliver.
        val a = tree.createChild("A", "a", RuntimeModelSnapshot("p", "a"), delegationMode = DelegationMode.TEAM).getOrThrow()
        val b = tree.createChild("A", "b", RuntimeModelSnapshot("p", "b"), delegationMode = DelegationMode.TEAM).getOrThrow()
        tree.start(a.id)
        tree.start(b.id)
        assertTrue("control: the peer edge must be creatable", tree.addTeamPeerEdge(a.id, b.id).accepted)
        assertTrue(
            "control: same-root peers with an edge still deliver: ${tree.send(a.id, b.id, "ok", RuntimeDelivery.TEAM_PEER)}",
            tree.send(a.id, b.id, "ok", RuntimeDelivery.TEAM_PEER).accepted,
        )
    }
}
