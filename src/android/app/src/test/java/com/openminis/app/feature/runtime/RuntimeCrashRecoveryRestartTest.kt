package com.openminis.app.feature.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-crash-recovery-scope] request.md:9 — "after the application
 * crashes, the main agent can see which sub-agents were interrupted, and bring
 * them back up".
 *
 * The defect this pins is not a missing mechanism. Every piece existed and was
 * individually reachable: `reconcileLeases` stamps a lease-expired node
 * ABNORMAL_INTERRUPTION, `restart` revives a terminal node, `restartDescendant`
 * authorizes and calls it, `supervise_descendants` reports whatever the
 * coordinator hands it. What was missing was SCOPE. After a reopen, `startRoot`
 * mints a new root generation (`"$sessionId#run-N"`) while the interrupted
 * children of the crashed run stay parented to the OLD root, and supervision,
 * the tool-visibility criterion and the restart itself were all answered from the
 * actor's own generation. The main agent therefore saw zero descendants, was
 * never offered `restart_descendant`, and — had it called the method anyway —
 * was refused for not sharing the actor's root. A crash is exactly the event that
 * splits those two views apart, which is why nothing caught it before: no
 * single-generation fixture can.
 *
 * These tests are written against the crash-recovery state as production
 * produces it, not against a hand-built approximation:
 *
 *  - the interruption comes from `RuntimeTreeStore.reconcile`, the same call
 *    `RuntimeSessionCoordinator.openShared` makes on every process start;
 *  - the reopen is a SECOND coordinator over the same durable store, which is
 *    what an empty `activeRuntimeIds` after a cold start amounts to;
 *  - every conclusion is drawn from the state the tree reports, never from the
 *    fact that a method returned `true`.
 *
 * Boundary, stated rather than implied: the "can the child really run again"
 * question is not answerable in this module (it needs a provider stack, a
 * coroutine scope and the parent's transcript — see `RuntimeChildRestartTest`
 * and `RuntimeChildSessionReuseTest` for the halves that are). What is pinned
 * here is that the node is RUNNING, persisted, and reachable from the parent
 * that has to drive it — the precondition the launch path depends on.
 */
class RuntimeCrashRecoveryRestartTest {

    // ─── fixtures ────────────────────────────────────────────────────────

    private class CrashFixture(
        val coordinator: RuntimeSessionCoordinator,
        val store: RuntimeTreeStore,
        val newRootId: String,
    ) {
        fun tree(): RuntimeSessionTree = store.snapshot()
    }

    private fun store(dir: File): RuntimeTreeStore =
        RuntimeTreeStore.openForTest(File(dir, "session-tree.json")) { source, target ->
            Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }

    private fun coordinator(store: RuntimeTreeStore): RuntimeSessionCoordinator {
        val constructor = RuntimeSessionCoordinator::class.java
            .getDeclaredConstructor(RuntimeTreeStore::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(store)
    }

    /**
     * A single run that crashed, then the reopen.
     *
     * The crash is reproduced with the lease reaper rather than with a direct
     * `abort`: `reconcile` is what `openShared` runs on the next start, and it is
     * the only reason a node ends up in ABNORMAL_INTERRUPTION without anyone
     * asking for it. `nowMillis` is pushed past the lease so the outcome does not
     * depend on how long the test takes.
     */
    private fun crashThenReopen(): CrashFixture {
        val dir = Files.createTempDirectory("crash-recovery-scope").toFile()
        val store = store(dir)
        val beforeCrash = coordinator(store)
        assertEquals("root", beforeCrash.startRoot("root"))
        assertTrue(beforeCrash.startChild("root", "child"))

        val stale = store.reconcile(System.currentTimeMillis() + 10_000_000L)
        assertTrue("the crash must have interrupted the run: stale=$stale", stale.contains("child"))
        assertEquals(
            "fixture: the reaper must have landed the child in ABNORMAL_INTERRUPTION",
            RuntimeNodeStatus.ABNORMAL_INTERRUPTION,
            store.snapshot().node("child")?.status,
        )
        assertEquals(
            "fixture: the root was running when the process died, so its lease lapsed too",
            RuntimeNodeStatus.ABNORMAL_INTERRUPTION,
            store.snapshot().node("root")?.status,
        )

        // The reopen. A second coordinator over the same store has exactly what a
        // cold start has: the persisted tree, and no in-process runtime ids.
        val reopened = coordinator(store)
        val newRootId = requireNotNull(reopened.startRoot("root"))
        return CrashFixture(reopened, store, newRootId)
    }

    private fun RuntimeSessionCoordinator.persistedNode(nodeId: String): JSONObject? {
        val fileField = RuntimeTreeStore::class.java.getDeclaredField("file")
        fileField.isAccessible = true
        val file = fileField.get(storeField()) as File
        assertTrue("the runtime tree must exist on disk at ${file.absolutePath}", file.isFile)
        val nodes = JSONObject(file.readText()).getJSONArray("nodes")
        return (0 until nodes.length())
            .map { nodes.getJSONObject(it) }
            .firstOrNull { it.optString("id") == nodeId }
    }

    private fun RuntimeSessionCoordinator.storeField(): RuntimeTreeStore {
        val field = RuntimeSessionCoordinator::class.java.getDeclaredField("store")
        field.isAccessible = true
        return field.get(this) as RuntimeTreeStore
    }

    // ─── 1. the shape that makes scope load-bearing ──────────────────────

    /**
     * Pins WHY the scope has to span generations. If this test ever fails, the
     * project has moved to reusing the same root node across runs, and the
     * generation-scoped reads below are no longer what keeps the requirement
     * true — read them again instead of deleting them.
     */
    @Test
    fun `a reopen runs as a new generation while the interrupted child hangs off the old root`() {
        val fixture = crashThenReopen()
        val tree = fixture.tree()

        assertNotEquals(
            "a terminal root is not reused, it is superseded by a new generation",
            "root",
            fixture.newRootId,
        )
        assertEquals("root#run-1", fixture.newRootId)
        assertEquals("root#run-1", requireNotNull(tree.node(fixture.newRootId)).rootId)
        assertEquals(
            "the crashed run's child keeps pointing at the root it was born under",
            "root",
            requireNotNull(tree.node("child")).parentId,
        )
        assertEquals("root", requireNotNull(tree.node("child")).rootId)
    }

    // ─── 2. the "see which sub-agents were interrupted" half ─────────────

    @Test
    fun `supervise reports the interrupted child of the crashed generation`() {
        val fixture = crashThenReopen()

        val snapshot = fixture.coordinator.supervisionSnapshot("root")
        assertEquals(
            "the actor is the live node of the new generation",
            "root#run-1",
            snapshot.actorNode?.id,
        )
        val child = snapshot.descendants.firstOrNull { it.id == "child" }
        assertNotNull(
            "the parent must be able to SEE the interrupted child, or 'view which sub-agents " +
                "were interrupted' (request.md:9) is false in the product: " +
                snapshot.descendants.map { it.id },
            child,
        )
        assertEquals(RuntimeNodeStatus.ABNORMAL_INTERRUPTION, child?.status)
        assertTrue(
            "the interrupted flag is the auxiliary information the parent decides on",
            child?.abnormal == true,
        )
    }

    @Test
    fun `the scoped read sees the crashed generation's child and no other conversation's`() {
        val fixture = crashThenReopen()
        assertEquals("other", fixture.coordinator.startRoot("other"))
        assertTrue(fixture.coordinator.startChild("other", "otherChild"))
        fixture.store.update { abort("otherChild", abnormal = true, reason = "lease expired") }

        assertTrue(fixture.coordinator.supervisedDescendants("root").any { it.id == "child" })
        assertFalse(
            "widening the scope to every generation must not turn it into every session",
            fixture.coordinator.supervisedDescendants("root").any { it.id == "otherChild" },
        )
        assertFalse(
            "nor the other way round",
            fixture.coordinator.supervisedDescendants("other").any { it.id == "child" },
        )
    }

    // ─── 3. the tool-visibility criterion ────────────────────────────────

    @Test
    fun `after a crash reopen the session has a restartable descendant`() {
        val fixture = crashThenReopen()

        assertTrue(
            "this is the gate behind `restart_descendant` entering the model's tool table; " +
                "false here means the model never sees the tool",
            fixture.coordinator.hasRestartableDescendant("root"),
        )
    }

    @Test
    fun `a terminal previous-generation root is not itself a restart candidate`() {
        val fixture = crashThenReopen()
        assertEquals(
            "fixture: the old root is still terminal",
            RuntimeNodeStatus.ABNORMAL_INTERRUPTION,
            fixture.tree().node("root")?.status,
        )

        assertFalse(
            "a generation root is this conversation's own main agent, not a sub-agent: reviving " +
                "one would leave a single session with two live roots",
            fixture.coordinator.restartDescendant("root", "root"),
        )
        assertEquals(
            RuntimeNodeStatus.ABNORMAL_INTERRUPTION,
            fixture.tree().node("root")?.status,
        )

        // And once the real child is back up there is nothing left to restart —
        // the old terminal root must not keep the tool advertised forever.
        assertTrue(fixture.coordinator.restartDescendant("root", "child"))
        assertFalse(
            "a running child and an old terminal root are not restart candidates",
            fixture.coordinator.hasRestartableDescendant("root"),
        )
    }

    // ─── 4. the "bring them back up" half ───────────────────────────────

    @Test
    fun `the interrupted child of the crashed generation is brought back up`() {
        val fixture = crashThenReopen()

        assertTrue(
            "the parent that can see the interrupted child must also be able to restart it",
            fixture.coordinator.restartDescendant("root", "child", reason = "recover after crash"),
        )

        val node = requireNotNull(fixture.tree().node("child"))
        assertEquals(RuntimeNodeStatus.RUNNING, node.status)
        assertFalse(node.abnormal)
        assertNotNull(
            "a running node without a lease would be killed by the next reconcile pass",
            node.leaseUntilMillis,
        )
        assertEquals(
            "a restart the parent relies on has to survive the process that ordered it",
            RuntimeNodeStatus.RUNNING.name,
            requireNotNull(fixture.coordinator.persistedNode("child")).optString("status"),
        )
        assertFalse(requireNotNull(fixture.coordinator.persistedNode("child")).optBoolean("abnormal"))
        assertEquals(
            "the restart must reuse the child's node, not add a second one",
            1,
            fixture.coordinator.supervisionSnapshot("root").descendants.size,
        )
    }

    /**
     * The two halves of a restart are `restartDescendant` (re-arm the node) and
     * the launch (re-run the agent). When the launch fails the first half has to
     * be undone, and the rollback has to accept the same target the restart did —
     * otherwise the crash-recovery path is the one path that leaves a RUNNING node
     * nothing is driving, which is what the parent would then wait on forever.
     */
    @Test
    fun `a restart whose launch failed is rolled back across the generation gap`() {
        val fixture = crashThenReopen()
        assertTrue(fixture.coordinator.restartDescendant("root", "child"))
        assertEquals(RuntimeNodeStatus.RUNNING, fixture.tree().node("child")?.status)

        assertTrue(
            fixture.coordinator.failRestartedChild(
                parentSessionId = "root",
                childSessionId = "child",
                reason = "the restarted child never started",
            ),
        )

        val node = requireNotNull(fixture.tree().node("child"))
        assertEquals(RuntimeNodeStatus.ABNORMAL_INTERRUPTION, node.status)
        assertTrue(node.abnormal)
        assertTrue(
            "a failed restart must leave the parent where it started, able to try again",
            fixture.tree().canRestart("child"),
        )
        assertTrue(fixture.coordinator.restartDescendant("root", "child"))
        assertEquals(RuntimeNodeStatus.RUNNING, fixture.tree().node("child")?.status)
    }

    // ─── 5. the loop actually closes: the child can be launched and reports back

    /**
     * The feasibility half of the fix, pinned where it is testable.
     *
     * `restartDescendant` only re-arms the NODE; the agent is re-run by
     * `RuntimeChildRunner`, whose first gate is
     * `SessionActivityTracker.beginDelegatedChild` → `startRoot` + `delegate`.
     * A `false` from `delegate` is "Runtime tree rejected delegated child" and the
     * child never runs, so a scope fix that left this seam refusing would have
     * refreshed the tree's bookkeeping and nothing else. The rest of the launch
     * (provider stack, coroutine scope, parent transcript) lives outside this
     * module; what is pinned here is that the tree lets the run through.
     */
    @Test
    fun `the launch seam accepts the restarted child of the crashed generation`() {
        val fixture = crashThenReopen()
        assertTrue(fixture.coordinator.restartDescendant("root", "child"))

        // Exactly the runner's opening pair, in the same order.
        assertEquals("root#run-1", fixture.coordinator.startRoot("root"))
        assertTrue(
            "a restart the tree accepted must not then be refused by the seam that runs the child",
            fixture.coordinator.delegate(
                parentSessionId = "root",
                childSessionId = "child",
                request = RuntimeDelegationRequest(prompt = "resume and finish your task"),
                model = RuntimeModelSnapshot(provider = "p", model = "m"),
            ),
        )
        assertEquals(RuntimeNodeStatus.RUNNING, fixture.tree().node("child")?.status)
        assertNotNull(
            "the child reads its own lane; a claim is keyed on its node, not on the " +
                "generation its parent happens to be running as",
            fixture.coordinator.capabilitySnapshot("child"),
        )
    }

    /**
     * …and the other end of the loop: when the restarted child stops, the parent
     * that brought it up has to hear about it. The child reports to the parent id
     * it was born with (the OLD root), while the parent drains under its current
     * generation — the same generation union `drainInbox` has always used, and the
     * reason the widening here is a convergence rather than a new mechanism.
     */
    @Test
    fun `the parent receives the restarted child's stop notification across the generation gap`() {
        val fixture = crashThenReopen()
        assertTrue(fixture.coordinator.restartDescendant("root", "child"))

        fixture.coordinator.finishChild(
            childSessionId = "child",
            failed = true,
            report = RuntimeStopReport(
                nodeId = "child",
                completedNormally = false,
                debugInfo = "the restarted run stopped without completing",
            ),
        )
        assertEquals(
            "the child went back to the state that is actually true",
            RuntimeNodeStatus.ABNORMAL_INTERRUPTION,
            fixture.tree().node("child")?.status,
        )

        val drained = fixture.coordinator.drainInbox("root")
        assertTrue(
            "the parent must be told its restarted child stopped; a notification addressed to the " +
                "previous generation's root and drained under the current one is exactly the " +
                "generation crossing this fix has to keep working: ${drained.map { it.payload }}",
            drained.any { it.fromNodeId == "child" },
        )
    }

    // ─── 6. the widening stops at one conversation ──────────────────────

    @Test
    fun `the generation scope does not authorize control over another session`() {
        val fixture = crashThenReopen()
        assertEquals("other", fixture.coordinator.startRoot("other"))
        assertTrue(fixture.coordinator.startChild("other", "otherChild"))
        fixture.store.update { abort("otherChild", abnormal = true, reason = "lease expired") }

        assertFalse(
            "another session's interrupted child is not this session's to restart",
            fixture.coordinator.restartDescendant("root", "otherChild"),
        )
        assertFalse(
            "and this session's child is not the other one's to restart",
            fixture.coordinator.restartDescendant("other", "child"),
        )
        assertEquals(
            "a refused restart must not touch the target",
            RuntimeNodeStatus.ABNORMAL_INTERRUPTION,
            fixture.tree().node("otherChild")?.status,
        )
        assertEquals(
            RuntimeNodeStatus.ABNORMAL_INTERRUPTION,
            fixture.tree().node("child")?.status,
        )
        assertFalse(
            "the rollback is scoped the same way, so a cross-session rollback cannot mark " +
                "another tree's child abnormal",
            fixture.coordinator.failRestartedChild("root", "otherChild", reason = "cross-session"),
        )
    }
}
