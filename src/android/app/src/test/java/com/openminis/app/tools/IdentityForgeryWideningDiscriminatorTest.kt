package com.openminis.app.tools

import com.openminis.app.feature.runtime.ConversationIdProtocol
import com.openminis.app.feature.runtime.DeleteSubtreeResult
import com.openminis.app.feature.runtime.RuntimeDelivery
import com.openminis.app.feature.runtime.RuntimeNodeStatus
import com.openminis.app.feature.runtime.RuntimeSessionCoordinator
import com.openminis.app.feature.runtime.RuntimeTreeStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WIDENING DISCRIMINATORS for the three rules that
 * `AgentToolExecutorIdentityForgeryTest` does NOT discriminate.
 *
 * ## Why these exist
 *
 * Measured, not argued (raw JUnit output for every variant is under
 * `/tmp/idf-logs2/`, one log per variant, with the file-level `INPUT_SHA` of the
 * frozen production closure at the head of each). The
 * existing suite picks its
 * "stranger" as `other-root` / `other-child` — a session in ANOTHER conversation:
 *
 *  - `coordinator.stopDescendant(actor = OTHER_ROOT, target = CHILD)` → refused by
 *    the CROSS-CONVERSATION guard;
 *  - `coordinator.deleteSubtree(initiator/executor = OTHER_ROOT, target = CHILD)`
 *    → refused by the CROSS-CONVERSATION guard;
 *  - `message_child`/`message_peer` to `other-child` → refused by the
 *    cross-conversation delivery boundary.
 *
 * Under a widening that keeps the conversation boundary and relaxes only the
 * WITHIN-conversation rule, every one of those calls is still refused by the
 * boundary, so the suite stays green (`OK (11 tests)`) while the rule it claims
 * to pin is gone. Three such widenings were built and run:
 *
 *  - `wide-stop-sibling`     : `isWithinConversationScope` ancestry → `true`
 *  - `wide-del-sibling`      : `deleteSubtree` "executor must be a strict
 *                              ancestor" → dropped
 *  - `wide-delivery-sibling` : same `isWithinConversationScope` widening, seen
 *                              from the delivery side
 *
 * ## The discriminator shape (the whole point of this file)
 *
 * The actor here is a **sibling**: same conversation as the target, NOT an
 * ancestor of it, NOT a member of its subtree. A "stranger" from another
 * conversation cannot do this job — it is refused in both worlds.
 *
 * ## Every prerequisite is asserted
 *
 * If the fixture ever stops producing a same-conversation non-ancestor (a change
 * in `startChild`, in `conversationIdOf`, in the root-generation spelling), the
 * preconditions fail LOUDLY instead of letting the discriminator silently
 * degrade into "the fixture is wrong, but still green".
 *
 * ## Scope of the "0 red" verdict — per TEST CLASS, not per RULE
 *
 * "The existing tests stayed green" is measured over ONE test class
 * (`AgentToolExecutorIdentityForgeryTest`), not over the repository. A rule can
 * well be covered by a DIFFERENT test file, so a report must say "**the target
 * class** is blind to this widening", never "nobody covers this".
 *
 * `wide-team-cross-root` (the send-side "Team peers must share a root" guard) is
 * exactly that case, and it is therefore deliberately NOT discriminated here:
 * `ScopeWideningDiscriminatorTest` already pins it, with both the precondition
 * asserted (`A` and `A#run-1` really do fold into one conversation) and the
 * refusal reason asserted by literal (`"Team peers must share a root"`).
 *
 * ## This file does NOT replace the existing controls
 *
 * The controls in `AgentToolExecutorIdentityForgeryTest` (that `root` really may
 * stop / message / delete its own child) are correct and stay where they are.
 * The control blocks below exist only to prove that each refusal is about the
 * ACTOR rather than about an impossible or non-terminal request; a control that
 * passes under both the narrow and the widened rule is not a discriminator.
 */
class IdentityForgeryWideningDiscriminatorTest {

    private companion object {
        /** One conversation: a root with two children that are peers of each other. */
        const val ROOT = "root"

        /** The target: an ordinary, live child of [ROOT]. */
        const val TARGET = "target"

        /** The stranger: a SIBLING of [TARGET] — same conversation, no ancestry either way. */
        const val SIBLING = "sibling"
    }

    private class Fixture(prefix: String) {
        val dir: File = Files.createTempDirectory(prefix).toFile()
        val store: RuntimeTreeStore =
            RuntimeTreeStore.openForTest(File(dir, "session-tree.json")) { source, target ->
                Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            }
        val coordinator: RuntimeSessionCoordinator = run {
            val constructor = RuntimeSessionCoordinator::class.java
                .getDeclaredConstructor(RuntimeTreeStore::class.java)
            constructor.isAccessible = true
            constructor.newInstance(store)
        }

        fun statusOf(nodeId: String): RuntimeNodeStatus? = store.snapshot().node(nodeId)?.status

        fun node(nodeId: String) = store.snapshot().node(nodeId)

        /** root + two live children of it. Both children are real, live nodes. */
        fun startSiblings() {
            assertNotNull("the root must be live", coordinator.startRoot(ROOT))
            assertTrue("the target must be live", coordinator.startChild(ROOT, TARGET))
            assertTrue("the sibling must be live", coordinator.startChild(ROOT, SIBLING))
        }

        /** The state a crash leaves behind, produced the way the reaper produces it. */
        fun makeAbnormal(nodeId: String) {
            store.update { abort(nodeId, abnormal = true, reason = "lease expired") }
            assertEquals(RuntimeNodeStatus.ABNORMAL_INTERRUPTION, statusOf(nodeId))
        }

        fun dispose() {
            dir.deleteRecursively()
        }
    }

    /**
     * PREREQUISITES, asserted once and shared by all three tests.
     *
     * Without these, a fixture change would turn every discriminator below into a
     * test that cannot fail: the call would be refused for a different reason (or
     * accepted for a different reason) and the assertions would still line up.
     */
    private fun Fixture.assertSiblingIsARealSameConversationStranger() {
        assertNotNull("the sibling must exist as a runtime node", node(SIBLING))
        assertNotNull("the target must exist as a runtime node", node(TARGET))
        val siblingNode = requireNotNull(node(SIBLING))
        val targetNode = requireNotNull(node(TARGET))

        // 1. Same conversation — by the SAME predicate production uses, so the test
        //    cannot disagree with the runtime about what "one conversation" means.
        assertEquals(
            "prerequisite: the sibling and the target must be in ONE conversation",
            ConversationIdProtocol.conversationIdOf(siblingNode.rootId),
            ConversationIdProtocol.conversationIdOf(targetNode.rootId),
        )
        assertEquals("prerequisite: both share the root node", ROOT, siblingNode.rootId)
        assertEquals(ROOT, targetNode.rootId)

        // 2. Not an ancestor: the target's parent is the ROOT, not the sibling.
        assertEquals(
            "prerequisite: the sibling must NOT be the target's parent",
            ROOT,
            targetNode.parentId,
        )
        // 3. Not the target itself, and not an ancestor of it either — the sibling
        //    has no subtree at all, so it cannot contain the target.
        assertTrue(
            "prerequisite: the sibling must have no subtree of its own",
            store.snapshot().descendants(SIBLING).isEmpty(),
        )
        assertFalse("prerequisite: sibling != target", SIBLING == TARGET)
        // 4. Neither is the conversation ROOT, so neither gets the root's
        //    conversation-wide authority for free.
        assertNotNull("prerequisite: the sibling must not be a generation root", siblingNode.parentId)
        assertNotNull("prerequisite: the target must not be a generation root", targetNode.parentId)
    }

    // ─── 1. stop_descendant: the within-conversation ancestry rule ────────

    /**
     * `authorizeDescendantStop` has TWO rules. The existing suite only ever
     * exercises the first one (cross-conversation). This pins the second:
     * *inside one conversation, only a strict ancestor (or the conversation root)
     * may stop a node.*
     *
     * Narrow rule: `isWithinConversationScope(actor, target)` is
     * `actor.parentId == null || isAncestor(actor.id, target.id)` — for a live
     * sibling that is `false`, so the stop is refused with the ancestry reason.
     * Widened rule (`isWithinConversationScope` → `true`): accepted.
     */
    @Test
    fun `stop_descendant refuses a same-conversation non-ancestor actor`() {
        val f = Fixture("widen-disc-stop")
        try {
            f.startSiblings()
            f.assertSiblingIsARealSameConversationStranger()
            // The target must be ACTIVE: a stop of a terminal node is refused for a
            // reason that has nothing to do with the actor.
            assertEquals(
                "prerequisite: the target must be live, or the refusal could be 'target is not active'",
                RuntimeNodeStatus.RUNNING,
                f.statusOf(TARGET),
            )

            // FORGED (well, mis-scoped) ACTOR FIRST: a stop is only accepted while
            // the target is ACTIVE, so running the control first would leave the
            // target terminal and the sibling's refusal would arrive for the wrong
            // reason.
            val refused = f.coordinator.stopDescendant(
                actorSessionId = SIBLING,
                targetSessionId = TARGET,
                reason = "widening discriminator",
            )
            assertFalse(
                "a same-conversation non-ancestor must not stop the target: ${refused.reason}",
                refused.accepted,
            )
            // WHICH refusal. Without this, a refusal produced by the
            // cross-conversation guard (or by "target is not active") would look
            // exactly like the ancestry refusal.
            assertEquals(
                "the refusal must name the ancestry rule, not another guard",
                "stop is limited to strict ancestors, plus the conversation root stopping any of its descendants",
                refused.reason,
            )
            assertEquals(
                "a refused stop must leave the target exactly where it was",
                RuntimeNodeStatus.RUNNING,
                f.statusOf(TARGET),
            )

            // CONTROL: the conversation root may stop the same live target, which
            // proves the request is executable and the target was actionable. This
            // control passes under BOTH the narrow and the widened rule, so it is
            // NOT a discriminator — it only rules out "impossible request".
            val control = f.coordinator.stopDescendant(
                actorSessionId = ROOT,
                targetSessionId = TARGET,
                reason = "widening discriminator control",
            )
            assertTrue("control: the conversation root may stop it: ${control.reason}", control.accepted)
        } finally {
            f.dispose()
        }
    }

    // ─── 2. delete_subtree: the strict-ancestor executor rule ─────────────

    /**
     * `deleteSubtree` has TWO rules, and the second one is stated in the existing
     * suite's own comment as the safety net behind the first:
     * "`executor must be a strict ancestor of target`".
     *
     * The existing suite only ever sends a CROSS-CONVERSATION executor, which the
     * first rule rejects — so the safety net is never actually tested, and
     * deleting it (`wide-del-sibling`) left all 11 tests green.
     *
     * Narrow rule: refused with `DELETE_SUBTREE`'s ancestry reason. Widened rule:
     * the target is terminal and same-conversation, so the subtree is DELETED.
     */
    @Test
    fun `delete_subtree refuses a same-conversation non-ancestor executor`() {
        val f = Fixture("widen-disc-delete")
        try {
            f.startSiblings()
            f.assertSiblingIsARealSameConversationStranger()
            // Terminal, so the refusal cannot be the BUSY rule ("subtree is still
            // running") — that guard sits BEHIND the ancestry guard.
            f.makeAbnormal(TARGET)

            val refused = f.coordinator.deleteSubtree(
                initiatorSessionId = SIBLING,
                executorSessionId = SIBLING,
                targetSessionId = TARGET,
                operationId = "widen-disc-delete-forged",
            )
            assertEquals(
                "a same-conversation non-ancestor must not delete the target: ${refused.reason}",
                DeleteSubtreeResult.REJECTED,
                refused.result,
            )
            // WHICH refusal: the cross-conversation reason and the ancestry reason
            // are both `REJECTED`, and the existing suite pins only the former.
            assertEquals(
                "the refusal must name the ancestry rule, not the cross-conversation guard",
                "executor must be a strict ancestor of target",
                refused.reason,
            )
            assertEquals(
                "a refused delete must leave the target exactly where it was",
                RuntimeNodeStatus.ABNORMAL_INTERRUPTION,
                f.statusOf(TARGET),
            )

            // CONTROL (not a discriminator: it passes under both rules). The audit
            // trail is the only thing that enforces the ANCESTRY rule there, and an
            // ancestry violation would be the one bad write, so the check has to stay
            // ahead of the deletion.
            val control = f.coordinator.deleteSubtree(
                initiatorSessionId = ROOT,
                executorSessionId = ROOT,
                targetSessionId = TARGET,
                operationId = "widen-disc-delete-control",
            )
            assertEquals("control: the real ancestor may delete it: ${control.reason}", DeleteSubtreeResult.DELETED, control.result)
            assertNull("the deleted node must be gone", f.statusOf(TARGET))
        } finally {
            f.dispose()
        }
    }

    // ─── 3. delivery: the downward-within-conversation scope ─────────────

    /**
     * `SessionTreeRuntime.send` authorizes a non-Team delivery either through a
     * real topology edge or through `isWithinConversationScope(from, to)` — i.e.
     * "a message travels DOWN an ancestry line". A sibling is not on that line.
     *
     * Narrow rule: no edge SIBLING→TARGET exists and the sibling is not an
     * ancestor, so the send is refused with the delivery reason. Widened rule
     * (`isWithinConversationScope` → `true`): the message is enqueued and the
     * target can claim it.
     */
    @Test
    fun `send refuses a same-conversation non-ancestor sender`() = runBlocking {
        val f = Fixture("widen-disc-send")
        try {
            f.startSiblings()
            f.assertSiblingIsARealSameConversationStranger()

            val refused = f.coordinator.send(
                fromSessionId = SIBLING,
                toSessionId = TARGET,
                payload = "steer from a sibling",
                delivery = RuntimeDelivery.STEER,
            )
            assertFalse(
                "a same-conversation non-ancestor must not reach the target: ${refused.reason}",
                refused.accepted,
            )
            // WHICH refusal. The cross-conversation boundary and the missing-edge
            // boundary both reject; only the edge reason proves the ancestry rule ran.
            assertEquals(
                "the refusal must name the delivery-edge rule, not another guard",
                "delivery edge is not authorized",
                refused.reason,
            )
            assertNull(
                "a refused send must leave the target's inbox empty",
                f.coordinator.claimNextStep(TARGET),
            )

            // CONTROL (not a discriminator: it passes under both rules). The real
            // parent may steer the same target, which proves the payload, the
            // delivery kind and the target are all actionable.
            val control = f.coordinator.send(
                fromSessionId = ROOT,
                toSessionId = TARGET,
                payload = "steer from the real parent",
                delivery = RuntimeDelivery.STEER,
            )
            assertTrue("control: the real parent may steer it: ${control.reason}", control.accepted)
            val claimed = f.coordinator.claimNextStep(TARGET)
            assertNotNull("control: the target must hold the parent's steer", claimed)
            assertEquals(ROOT, claimed!!.fromNodeId)
        } finally {
            f.dispose()
        }
    }
}
