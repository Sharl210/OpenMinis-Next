package com.openminis.app.ui.chat

import com.openminis.app.feature.runtime.RuntimeModelSnapshot
import com.openminis.app.feature.runtime.RuntimeNodeStatus
import com.openminis.app.feature.runtime.RuntimeSessionCoordinator
import com.openminis.app.feature.runtime.RuntimeTreeStore
import com.openminis.app.tools.AgentTools
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-child-restart] Whether the main agent's model can SEE the restart
 * tool, asserted against the tool table itself.
 *
 * ## The defect this exists because of
 *
 * `restart_descendant` was implemented (runtime transition, authorization,
 * executor branch) and its launcher was wired into `ChatViewModel` — and the
 * tool was still unreachable, because `AgentTools.makeAgentTools(restartAvailable
 * = …)` defaults that flag to `false` and the ViewModel never passed it. The
 * model never saw the name, so "the parent can bring an interrupted child back
 * up" (request.md:9) was true in the code and false in the product. The tool
 * table is the only thing that decides whether the model has the capability, so
 * that is what these tests read.
 *
 * ## What is asserted, and why it is the production composition
 *
 * The subject is [mainAgentTools] — the exact function `ChatViewModel.agentTools`
 * forwards to — over a REAL `RuntimeSessionCoordinator` with a REAL on-disk tree,
 * so the assertions run the production decision instead of restating it. The
 * assertions are membership tests on the returned definitions: "is the name in
 * the table", never source text.
 *
 * Boundary, stated rather than implied: this module has no Robolectric, so the
 * `ChatViewModel` object itself cannot be built here; what is pinned is the
 * composition it calls and nothing more. The gate on the `AgentTools` side
 * (the flag actually controls membership) is pinned in
 * [restart tool is offered only when the flag says a restart can run] below, so
 * both halves of the hop are covered.
 */
class MainAgentToolsRestartAvailabilityTest {

    // ─── fixtures ────────────────────────────────────────────────────────

    private fun store(dir: File): RuntimeTreeStore =
        RuntimeTreeStore.openForTest(File(dir, "session-tree.json")) { source, target ->
            Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }

    private fun newCoordinator(): RuntimeSessionCoordinator {
        val dir = Files.createTempDirectory("main-agent-tools-restart").toFile()
        val constructor = RuntimeSessionCoordinator::class.java.getDeclaredConstructor(RuntimeTreeStore::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(store(dir))
    }

    private fun RuntimeSessionCoordinator.store(): RuntimeTreeStore {
        val field = RuntimeSessionCoordinator::class.java.getDeclaredField("store")
        field.isAccessible = true
        return field.get(this) as RuntimeTreeStore
    }

    /**
     * The table the app hands the model, built the way `ChatViewModel.agentTools`
     * builds it. The four unrelated gates are held constant so the only thing
     * that varies between tests is the runtime subtree.
     */
    private fun toolsFor(coordinator: RuntimeSessionCoordinator?, actor: String = "root") =
        mainAgentTools(
            supportsImageInput = true,
            visionGroupConfigured = false,
            memoryEnabled = true,
            goalActive = false,
            coordinator = coordinator,
            actorSessionId = actor,
        )

    private fun List<com.openminis.app.data.model.AgentToolDefinition>.hasRestartTool() =
        any { it.name == AgentTools.RESTART_DESCENDANT_TOOL_NAME }

    /** Root + one child, with the child stopped the way a crash leaves it. */
    private fun coordinatorWithInterruptedChild(): RuntimeSessionCoordinator {
        val coordinator = newCoordinator()
        assertTrue(coordinator.startRoot("root") != null)
        assertTrue(
            coordinator.startChild(
                "root",
                "child",
                RuntimeModelSnapshot(provider = "p", model = "m"),
            ),
        )
        // Exactly the crash-recovery path: the lease reaper marks an
        // un-heartbeated node ABNORMAL_INTERRUPTION.
        coordinator.store().update { abort("child", abnormal = true, reason = "lease expired") }
        return coordinator
    }

    // ─── the criterion: is there something to restart? ───────────────────

    @Test
    fun `an interrupted descendant puts the restart tool into the model's table`() {
        val coordinator = coordinatorWithInterruptedChild()

        assertTrue(
            "the parent was told it can restart an interrupted child (request.md:9); the model " +
                "must therefore have the tool — the runtime side being implemented is not enough",
            toolsFor(coordinator).hasRestartTool(),
        )
        assertTrue(
            "the criterion must be the tree's own restartability verdict",
            coordinator.hasRestartableDescendant("root"),
        )
    }

    @Test
    fun `a session with no descendants is not offered the restart tool`() {
        val coordinator = newCoordinator()
        assertTrue(coordinator.startRoot("root") != null)

        assertFalse(
            "offering a restart tool with nothing to restart invites the model to spend turns " +
                "on a call that can only be refused",
            toolsFor(coordinator).hasRestartTool(),
        )
        assertFalse(coordinator.hasRestartableDescendant("root"))
    }

    @Test
    fun `a child that is still running is not a restart candidate`() {
        val coordinator = newCoordinator()
        assertTrue(coordinator.startRoot("root") != null)
        assertTrue(coordinator.startChild("root", "child", RuntimeModelSnapshot("p", "m")))

        assertFalse(
            "live work must not be advertised as restartable",
            toolsFor(coordinator).hasRestartTool(),
        )
        assertFalse(coordinator.hasRestartableDescendant("root"))
    }

    /**
     * The tool's availability follows the tree, not the opposite: after the
     * interrupted child is brought back up, there is nothing left to restart.
     */
    @Test
    fun `the tool disappears again once the only candidate is running`() {
        val coordinator = coordinatorWithInterruptedChild()
        assertTrue(toolsFor(coordinator).hasRestartTool())

        assertTrue(coordinator.restartDescendant("root", "child"))

        assertFalse(
            "a restarted child is running, so the window for restarting it has closed",
            toolsFor(coordinator).hasRestartTool(),
        )
        assertFalse(coordinator.hasRestartableDescendant("root"))
    }

    @Test
    fun `a coordinator that is not open yields no restart tool`() {
        assertFalse(toolsFor(coordinator = null).hasRestartTool())
    }

    @Test
    fun `an interrupted grandchild is reachable from the root's tool table`() {
        val coordinator = newCoordinator()
        assertTrue(coordinator.startRoot("root") != null)
        assertTrue(coordinator.startChild("root", "child", RuntimeModelSnapshot("p", "m")))
        assertTrue(coordinator.startChild("child", "grandchild", RuntimeModelSnapshot("p", "m")))
        coordinator.store().update { abort("grandchild", abnormal = true, reason = "lease expired") }

        // A root recovers its whole subtree, not just the layer beneath it.
        assertTrue(toolsFor(coordinator).hasRestartTool())
    }

    @Test
    fun `another session's interrupted child does not offer this session the tool`() {
        val coordinator = coordinatorWithInterruptedChild()
        assertTrue(coordinator.startRoot("otherRoot") != null)

        // The criterion is scoped to the acting session's own subtree: a restart
        // is refused cross-root, so advertising it there would offer an action
        // that can only fail.
        assertTrue(toolsFor(coordinator, actor = "root").hasRestartTool())
        assertFalse(toolsFor(coordinator, actor = "otherRoot").hasRestartTool())
        assertFalse(coordinator.hasRestartableDescendant("otherRoot"))
    }

    // ─── the other half of the hop: the AgentTools gate itself ───────────

    @Test
    fun `restart tool is offered only when the flag says a restart can run`() {
        val withoutFlag = AgentTools.makeAgentTools(restartAvailable = false).map { it.name }
        val withFlag = AgentTools.makeAgentTools(restartAvailable = true).map { it.name }

        // If this stops holding, the availability decision above stops being
        // load-bearing and any test of it becomes decorative.
        assertFalse(withoutFlag.contains(AgentTools.RESTART_DESCENDANT_TOOL_NAME))
        assertTrue(withFlag.contains(AgentTools.RESTART_DESCENDANT_TOOL_NAME))
        // Everything else must be unaffected by the flag — otherwise "offering the
        // restart tool" would be hiding or adding unrelated capability.
        assertTrue(withoutFlag.toSet() + AgentTools.RESTART_DESCENDANT_TOOL_NAME == withFlag.toSet())
    }

    @Test
    fun `the node the tree calls restartable is the one the tool table counts`() {
        // Ties the two halves together on one tree: the same verdict that puts the
        // tool in the table is the verdict `restartDescendant` acts on, so the
        // model can never be shown a tool whose target the runtime would refuse.
        val coordinator = coordinatorWithInterruptedChild()
        assertTrue(toolsFor(coordinator).hasRestartTool())
        assertTrue(
            "the node the table counted must be the node a restart accepts",
            coordinator.restartDescendant("root", "child"),
        )
        assertTrue(coordinator.store().snapshot().node("child")!!.status == RuntimeNodeStatus.RUNNING)
    }
}
