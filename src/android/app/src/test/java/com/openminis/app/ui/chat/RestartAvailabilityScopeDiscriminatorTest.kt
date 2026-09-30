package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.feature.runtime.ConversationIdProtocol
import com.openminis.app.feature.runtime.RuntimeModelSnapshot
import com.openminis.app.feature.runtime.RuntimeSessionCoordinator
import com.openminis.app.feature.runtime.RuntimeTreeStore
import com.openminis.app.tools.AgentTools
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-child-restart] Discriminators for the SCOPE of the restart-availability
 * criterion behind `restart_descendant`.
 *
 * ## Why this file exists next to [MainAgentToolsRestartAvailabilityTest]
 *
 * That file pins the criterion well — and it is blind to how far the criterion may
 * widen, because the only outsider it uses is `otherRoot`, a root of a *different*
 * conversation. Measured against widened copies of
 * `RuntimeSessionCoordinator.hasRestartableDescendant` (source copied to /tmp, the
 * scope expression replaced per run, `K2JVMCompiler` + JUnitCore):
 *
 * | widened rule | `MainAgentToolsRestartAvailabilityTest` (9 tests) | this file |
 * |---|---|---|
 * | any restartable node anywhere in the tree file | 1 red (`another session's interrupted child …`) | 2 red |
 * | previous-generation roots are candidates too | 9 green, 0 red | 1 red |
 * | my whole conversation, not just my subtree | 9 green, 0 red | 2 red |
 *
 * The first row is why the existing outsider looks like coverage: `otherRoot` is
 * not merely another session but a root outside the conversation, so a tree-wide
 * scope does redden. The two rows below it are the widenings that survive, and the
 * shape that catches them is the one this file adds — a node INSIDE the acting
 * session's conversation that is neither an ancestor nor a member of its subtree:
 *
 *  - `root#run-1`: the previous generation's root, i.e. the user reopening a
 *    conversation whose last run ended. It exists, it is terminal (restartable),
 *    `conversationIdOf` folds it onto the acting root — and it is still not a
 *    descendant, so it must not be offered. (`sessionScopedDescendants` excludes
 *    generation roots on purpose: "a restart whose target were an older root would
 *    leave one session with two live roots".)
 *  - `sibling`: for an acting session that is itself a child, the other child of the
 *    same root is in the same conversation but not under the actor.
 *
 * ## Premises are asserted, and the refusal is asserted as a verdict
 *
 * Every case states what makes it discriminating (one conversation, terminal but
 * not a descendant / not in the subtree) before it asserts the verdict, so a change
 * to the generation-folding or to the fixture cannot turn the case into a silently
 * passing "the fixture was wrong". A control that holds under every version of the
 * rule would not be worth much here, so none is offered as a stand-in: the sibling
 * and generation cases are the assertions.
 */
class RestartAvailabilityScopeDiscriminatorTest {

    private fun store(dir: File): RuntimeTreeStore =
        RuntimeTreeStore.openForTest(File(dir, "session-tree.json")) { source, target ->
            Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }

    private fun newCoordinator(): RuntimeSessionCoordinator {
        val dir = Files.createTempDirectory("restart-scope-disc").toFile()
        val constructor =
            RuntimeSessionCoordinator::class.java.getDeclaredConstructor(RuntimeTreeStore::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(store(dir))
    }

    private fun RuntimeSessionCoordinator.store(): RuntimeTreeStore {
        val field = RuntimeSessionCoordinator::class.java.getDeclaredField("store")
        field.isAccessible = true
        return field.get(this) as RuntimeTreeStore
    }

    private fun toolsFor(coordinator: RuntimeSessionCoordinator?, actor: String = "root") =
        mainAgentTools(
            supportsImageInput = true,
            visionGroupConfigured = false,
            memoryEnabled = true,
            goalActive = false,
            coordinator = coordinator,
            actorSessionId = actor,
        )

    private fun List<AgentToolDefinition>.hasRestartTool() =
        any { it.name == AgentTools.RESTART_DESCENDANT_TOOL_NAME }

    @Test
    fun `a previous generation's root is not a restart candidate for the current run`() {
        val coordinator = newCoordinator()
        assertTrue(coordinator.startRoot("root") != null)
        // The run ends the way a crash ends it: the runtime handle is dropped and the node
        // is left terminal. `finishRoot` is the production path that does both — completing
        // the node while its handle is still registered would make the next `startRoot`
        // resume the SAME node instead of minting a generation.
        coordinator.finishRoot("root")

        val reopened = coordinator.startRoot("root")
        assertNotNull("premise: reopening a finished conversation must start a new generation", reopened)
        assertEquals("premise: the new generation is a different root node", "root#run-1", reopened)

        val tree = coordinator.store().snapshot()
        assertNotNull("premise: the previous generation's root is still in the tree", tree.node("root"))
        assertTrue(
            "premise: the previous generation's root IS in a restartable (terminal) state",
            tree.canRestart("root"),
        )
        assertEquals(
            "premise: the two roots are one conversation, or this case tests nothing",
            ConversationIdProtocol.conversationIdOf("root"),
            ConversationIdProtocol.conversationIdOf("root#run-1"),
        )
        assertFalse(
            "premise: the previous generation's root is NOT one of the acting session's descendants",
            coordinator.supervisedDescendants("root").any { it.id == "root" },
        )

        assertFalse(
            "restarting an older generation's root would leave one conversation with two live " +
                "roots, so the criterion must not count it " +
                "(got restartable=${coordinator.hasRestartableDescendant("root")})",
            coordinator.hasRestartableDescendant("root"),
        )
        assertFalse(
            "and the model must therefore not be shown the restart tool",
            toolsFor(coordinator, actor = "root").hasRestartTool(),
        )
    }

    @Test
    fun `a sibling under the same root is not a restart candidate for a child actor`() {
        val coordinator = newCoordinator()
        assertTrue(coordinator.startRoot("root") != null)
        assertTrue(coordinator.startChild("root", "child", RuntimeModelSnapshot("p", "m")))
        assertTrue(coordinator.startChild("root", "sibling", RuntimeModelSnapshot("p", "m")))
        coordinator.store().update { abort("sibling", abnormal = true, reason = "lease expired") }

        val tree = coordinator.store().snapshot()
        assertTrue("premise: the sibling really is restartable", tree.canRestart("sibling"))
        assertEquals(
            "premise: sibling and actor are one conversation, or this case tests nothing",
            tree.node("child")!!.rootId,
            tree.node("sibling")!!.rootId,
        )
        assertFalse(
            "premise: the sibling is NOT a descendant of the acting session",
            coordinator.supervisedDescendants("child").any { it.id == "sibling" },
        )

        assertFalse(
            "the scope is the acting session's own descendants, not its whole conversation " +
                "(got restartable=${coordinator.hasRestartableDescendant("child")})",
            coordinator.hasRestartableDescendant("child"),
        )
    }
}
