package com.openminis.app.feature.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The child runner's runtime-message contract, split into what can be executed
 * and what cannot — with the difference stated instead of blurred.
 *
 * ## What this file used to be
 *
 * Seven `source.contains("...")` assertions over `RuntimeChildRunner.kt`. One of
 * them was not merely weak but **vacuous**: it compared the index of
 * `runtimeCoordinator.send(` against the index of `provider.sendMessage(`, and
 * `provider.sendMessage(` appears nowhere in that file — `indexOf` returned -1,
 * so the comparison was `something > -1`. It could never fail, whatever the code
 * said. (It is deleted below; being unfalsifiable, it covered nothing.)
 *
 * ## Executed here (A)
 *
 * The child's inbox contract — three delivery lanes, each drained by its own
 * reader, each message claimable once — is production code the JVM can run:
 * [RuntimeSessionCoordinator.claimNextNotification], `claimNextStep` and
 * `claimNextTurn` against a real [RuntimeTreeStore] with a real root and child.
 * That is the half of "the child claims durable step and queued messages before
 * the model call" that lives in the coordinator; the tests assert the observable
 * envelopes, not that a call site exists.
 *
 * ## Retained as a structural check (C), and why
 *
 * The remaining three assertions describe one block inside
 * `RuntimeChildRunner`'s run body: the prompt prefix
 * `"[runtime-message:<delivery>] <payload>"`, the reply `send(...)`, and its
 * `toSessionId = envelope.fromNodeId`. Executing that block needs
 * `RuntimeChildRunner.execute`, which resolves a provider through
 * `ProviderRepository` — a class whose only constructor reads
 * `EncryptedSharedPreferences` (see `ProviderReorderTest`'s note) and then runs
 * a real HTTP provider loop. This module's unit-test source set has no
 * Robolectric, no Android keystore and no provider harness, so those three
 * statements cannot be reached; `RuntimeChildRunnerTest` covers the only seam
 * that IS reachable (`runChildAttempt`) and it is the generic helper the run
 * body is passed into, not the body itself.
 *
 * They are kept rather than deleted because deleting them would remove the only
 * guard on the addressing rule — a reply must go back to the node the message
 * came from — and they are labelled (C) here so a later reader can see they are
 * structural, not behavioural.
 */
class RuntimeChildRunnerMessagingSourceTest {

    private class Fixture {
        val dir: File = Files.createTempDirectory("runtime-child-messaging").toFile()
        val store: RuntimeTreeStore = RuntimeTreeStore.openForTest(File(dir, "session-tree.json")) { source, target ->
            Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }
        val coordinator: RuntimeSessionCoordinator = run {
            val constructor = RuntimeSessionCoordinator::class.java
                .getDeclaredConstructor(RuntimeTreeStore::class.java)
            constructor.isAccessible = true
            constructor.newInstance(store)
        }

        init {
            assertTrue(coordinator.startRoot("parent") != null)
            assertTrue(coordinator.startChild("parent", "child"))
        }

        fun dispose() {
            dir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------ (A)

    @Test
    fun `the child's three inbox readers each drain their own delivery lane exactly once`() {
        val f = Fixture()
        try {
            // The three kinds of top-down traffic a child can be sent: a steering
            // instruction, a queued turn, and a subscription notice.
            assertTrue(f.coordinator.send("parent", "child", "steer payload", RuntimeDelivery.STEER).accepted)
            assertTrue(f.coordinator.send("parent", "child", "turn payload", RuntimeDelivery.QUEUE).accepted)
            assertTrue(f.coordinator.send("parent", "child", "notice payload", RuntimeDelivery.NOTIFY).accepted)

            // The runner reads them in this order — one reader per delivery class,
            // so a message cannot be handed to the model under the wrong heading.
            val notice = f.coordinator.claimNextNotification("child")
            assertEquals(RuntimeDelivery.NOTIFY, notice?.delivery)
            assertEquals("notice payload", notice?.payload)
            assertEquals("parent", notice?.fromNodeId)

            val step = f.coordinator.claimNextStep("child")
            assertEquals(RuntimeDelivery.STEER, step?.delivery)
            assertEquals("steer payload", step?.payload)

            val turn = f.coordinator.claimNextTurn("child")
            assertEquals(RuntimeDelivery.QUEUE, turn?.delivery)
            assertEquals("turn payload", turn?.payload)

            // The lanes are disjoint: "everything was claimed" is the point, and a
            // reader that quietly matched another lane would have taken two
            // messages and left one behind.
            assertNull("the inbox must be drained", f.coordinator.claimNextNotification("child"))
            assertNull(f.coordinator.claimNextStep("child"))
            assertNull(f.coordinator.claimNextTurn("child"))
        } finally {
            f.dispose()
        }
    }

    @Test
    fun `a claimed message is not handed to the child twice while its lease holds`() {
        val f = Fixture()
        try {
            assertTrue(f.coordinator.send("parent", "child", "only once", RuntimeDelivery.QUEUE).accepted)
            assertEquals("only once", f.coordinator.claimNextTurn("child")?.payload)
            assertNull(
                "a second claim must not re-deliver the same envelope to the same run",
                f.coordinator.claimNextTurn("child"),
            )
        } finally {
            f.dispose()
        }
    }

    @Test
    fun `a message from a node with no delivery edge is refused instead of queued`() {
        val f = Fixture()
        try {
            // A second runtime root with no edge to this child: claiming is a
            // delivery contract, not an open mailbox, so the send is refused and
            // nothing appears in the child's lanes.
            assertTrue(f.coordinator.startRoot("other") != null)
            val refused = f.coordinator.send("other", "child", "cross root", RuntimeDelivery.QUEUE)
            org.junit.Assert.assertFalse(
                "a sender with no delivery edge must not be reported as delivered",
                refused.accepted,
            )
            assertNull("nothing may be enqueued for a refused send", f.coordinator.claimNextTurn("child"))
            assertNull(f.coordinator.claimNextStep("child"))
            assertNull(f.coordinator.claimNextNotification("child"))
        } finally {
            f.dispose()
        }
    }

    @Test
    fun `a sibling may not send sideways into another branch of the same conversation`() {
        val f = Fixture()
        try {
            // `parent` has two children, so both branches belong to ONE
            // conversation. Inside a conversation the send rule authorises a
            // DESCENT only (see the [T-android-conversation-scope] note in
            // SessionTreeRuntime): a sibling is neither the other branch's parent
            // nor its ancestor, so the send must be refused even though the two
            // nodes are in the same conversation.
            //
            // This shape is the semantic discriminator for that rule. The other
            // refusal asserted in this file gives the stranger its OWN root, i.e. a
            // different conversation, and a different conversation is refused by
            // the descending rule and by a symmetric "any node in the same
            // conversation" rule alike. Measured: replacing the descent test with
            // "same conversation, any direction" leaves the other three tests in
            // this file green and only this one reddens.
            assertTrue(f.coordinator.startChild("parent", "sibling"))

            val refused = f.coordinator.send("sibling", "child", "sideways", RuntimeDelivery.QUEUE)
            org.junit.Assert.assertFalse(
                "a sibling must not be able to push into another branch: $refused",
                refused.accepted,
            )
            assertNull(
                "nothing may be enqueued for a sideways send",
                f.coordinator.claimNextTurn("child"),
            )

            // Control: the same payload travelling the authorised direction DOES
            // arrive, so the refusal above is about the relationship and not about
            // the payload or the target. This control holds under both rules, which
            // is why it cannot replace the assertion above.
            assertTrue(f.coordinator.send("parent", "child", "sideways", RuntimeDelivery.QUEUE).accepted)
            assertEquals("sideways", f.coordinator.claimNextTurn("child")?.payload)
        } finally {
            f.dispose()
        }
    }

    // ------------------------------------------------------------------ (C)

    /**
     * (C) The three statements of the run body that no JVM test in this module
     * can execute — see the class comment. Each one is asserted where it lives so
     * the addressing rule keeps a guard, and each carries the reason it cannot be
     * a behaviour test.
     */
    @Test
    fun `the run body's prompt prefix and reply addressing stay as written`() {
        val source = sequenceOf(
            File("src/main/java/com/openminis/app/feature/runtime/RuntimeChildRunner.kt"),
            File("app/src/main/java/com/openminis/app/feature/runtime/RuntimeChildRunner.kt"),
        ).first { it.isFile }.readText()

        // Builds a string constant inside the run body. (C): not reachable — the
        // loop that uses it runs under RuntimeChildRunner.execute, behind
        // ProviderRepository (EncryptedSharedPreferences) and a live provider.
        assertTrue(
            "the child must be told which delivery class a runtime message arrived under",
            source.contains("runtime-message:${'$'}{envelope.delivery}"),
        )

        // (C): the reply send itself. The coordinator half of it IS behaviourally
        // covered above (send/claim), and `a message the child may not receive is
        // refused instead of queued` pins that authorization is enforced on the
        // same path; what cannot be executed is this call site.
        assertTrue(source.contains("runtimeCoordinator.send("))

        // (C): the addressing rule — a reply goes back to the node the message came
        // from, not to the parent by assumption (a TEAM peer's reply must not be
        // redirected to the parent either).
        assertTrue(source.contains("toSessionId = envelope.fromNodeId"))
    }
}
