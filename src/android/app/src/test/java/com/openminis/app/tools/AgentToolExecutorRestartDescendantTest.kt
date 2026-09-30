package com.openminis.app.tools

import android.content.Context
import android.content.ContextWrapper
import com.openminis.app.feature.runtime.RuntimeModelSnapshot
import com.openminis.app.feature.runtime.RuntimeNodeStatus
import com.openminis.app.feature.runtime.RuntimeSessionCoordinator
import com.openminis.app.feature.runtime.RuntimeTreeStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-child-restart] `restart_descendant` end to end, at the layer where
 * the runtime tree and the launch path meet.
 *
 * Requirement (`request.md:9`): "应用崩溃后，主代理可以查看哪些子代理异常中断并拉起"
 * — the parent must be able to bring an abnormally interrupted child back up.
 *
 * ## What was missing, and what these tests pin
 *
 * `AgentToolExecutor.restartDescendant` re-arms the runtime NODE and then hands
 * the child to a [RestartedChildLauncher] that only the app can implement. In
 * production that launcher was `null` (its only assignment site was the
 * constructor default), so the tool ALWAYS refused with "restart is not
 * available" — the honest half was wired, the working half was not. These tests
 * cover all three shapes of that contract, with the real executor, a real
 * `RuntimeSessionCoordinator` over a real on-disk tree, and a real launcher:
 *
 *  1. no launcher (the production default before this wiring) → refuse WITHOUT
 *     touching the tree: node still ABNORMAL_INTERRUPTION, still listed as such
 *     by `supervise_descendants`, still restartable, no new event, and the tree
 *     file on disk untouched (same bytes, same mtime — the coordinator's
 *     `store.update { }` rewrites the file even when it refuses, so an mtime
 *     check is what actually distinguishes "asked and refused" from "never
 *     asked");
 *  2. a launcher that reports `ok = false` or throws → the node goes BACK to
 *     ABNORMAL_INTERRUPTION and is dropped from the coordinator's active set,
 *     because "nothing is running this child" has to read as "nothing is
 *     running this child";
 *  3. a launcher that really ran the child → `ok = true`, the child's node stays
 *     RUNNING, it is published as the live child, and no second node appears.
 *
 * The child's task text is also pinned here: the executor rebuilds the run
 * request from the NODE (never from the model's arguments), and the assertion
 * below shows the node's `task` field is `model.note` — which is why the app-side
 * launcher prefers the child's own transcript (`restartedChildTaskPrompt`).
 *
 * Boundary, stated rather than implied: `ChatViewModel`'s launcher body and its
 * transcript write-back are not reachable from a JVM test (the ViewModel needs
 * the Android chat stack and this module has no Robolectric). What is asserted
 * here is the contract the launcher is written against; the task-text rule it
 * obeys is pinned separately in `RestartedChildTaskPromptTest`.
 */
class AgentToolExecutorRestartDescendantTest {

    // ─── fixtures ────────────────────────────────────────────────────────

    /** A Context good enough for an executor whose restart path never needs one. */
    private class TestContext(private val directory: File) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = directory
    }

    private fun store(dir: File): RuntimeTreeStore =
        RuntimeTreeStore.openForTest(File(dir, "session-tree.json")) { source, target ->
            Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }

    private fun coordinator(store: RuntimeTreeStore): RuntimeSessionCoordinator {
        val constructor = RuntimeSessionCoordinator::class.java.getDeclaredConstructor(RuntimeTreeStore::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(store)
    }

    private fun newCoordinator(): RuntimeSessionCoordinator =
        coordinator(store(Files.createTempDirectory("executor-restart").toFile()))

    private fun newDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    private fun RuntimeSessionCoordinator.store(): RuntimeTreeStore {
        val field = RuntimeSessionCoordinator::class.java.getDeclaredField("store")
        field.isAccessible = true
        return field.get(this) as RuntimeTreeStore
    }

    private fun RuntimeSessionCoordinator.tree() = store().snapshot()

    /** The file the coordinator persists to — the medium, not the in-memory view. */
    private fun RuntimeSessionCoordinator.treeFile(): File {
        val field = RuntimeTreeStore::class.java.getDeclaredField("file")
        field.isAccessible = true
        return field.get(store()) as File
    }

    /**
     * The ids this coordinator currently publishes as live children. Read
     * reflectively because there is no accessor, and "the child is not published
     * as active" is exactly the statement that has to be checkable.
     */
    private fun RuntimeSessionCoordinator.activeIds(): Map<String, String> {
        val field = RuntimeSessionCoordinator::class.java.getDeclaredField("activeRuntimeIds")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(this) as Map<String, String>
    }

    /** The node's status as a LATER process would load it. */
    private fun RuntimeSessionCoordinator.persistedStatus(nodeId: String): String? {
        val nodes = JSONObject(treeFile().readText()).getJSONArray("nodes")
        return (0 until nodes.length())
            .map { nodes.getJSONObject(it) }
            .firstOrNull { it.optString("id") == nodeId }
            ?.optString("status")
    }

    /**
     * Crash-recovery state: root + one child, and the child is where the lease
     * reaper leaves an interrupted run (`reconcileLeases` → `abort(abnormal =
     * true, reason = "lease expired")`), not a hand-built approximation.
     *
     * [task] becomes `node.task` through `createChild(task = model.note)` — see
     * the note-field test below for why the app-side launcher cannot rely on it.
     */
    private fun coordinatorWithInterruptedChild(task: String = ""): RuntimeSessionCoordinator {
        val coordinator = newCoordinator()
        assertTrue(coordinator.startRoot("root") != null)
        assertTrue(
            coordinator.startChild(
                "root",
                "child",
                RuntimeModelSnapshot(provider = "test-provider", model = "test-model", note = task),
            ),
        )
        coordinator.store().update { abort("child", abnormal = true, reason = "lease expired") }
        assertEquals(
            RuntimeNodeStatus.ABNORMAL_INTERRUPTION,
            coordinator.tree().node("child")?.status,
        )
        return coordinator
    }

    private fun args(childSessionId: String = "child"): String =
        """{"tool_title":"Restart Descendant","child_session_id":"$childSessionId"}"""

    private suspend fun restart(
        executor: AgentToolExecutor,
        coordinator: RuntimeSessionCoordinator,
        childSessionId: String = "child",
    ): ToolExecutionResult {
        val result = executor.execute(
            AgentToolExecutor.RESTART_DESCENDANT,
            args(childSessionId),
            "root",
            coordinator,
        )
        assertNotNull("the executor owns ${AgentToolExecutor.RESTART_DESCENDANT}", result)
        return requireNotNull(result)
    }

    /** What the parent is actually told by `supervise_descendants`. */
    private suspend fun supervisedStatus(
        executor: AgentToolExecutor,
        coordinator: RuntimeSessionCoordinator,
        childSessionId: String = "child",
    ): String? {
        val result = requireNotNull(
            executor.execute(
                AgentToolExecutor.SUPERVISE_DESCENDANTS,
                """{"tool_title":"Supervise Descendants"}""",
                "root",
                coordinator,
            ),
        )
        val descendants = JSONObject(result.output).getJSONArray("descendants")
        return (0 until descendants.length())
            .map { descendants.getJSONObject(it) }
            .firstOrNull { it.optString("id") == childSessionId }
            ?.optString("status")
    }

    // ─── 1. the production default: no launcher at all ───────────────────

    /**
     * The path production took before this wiring, and the path it must keep
     * taking if the launcher is ever absent again: an honest refusal that leaves
     * the tree exactly as it found it.
     *
     * "Does not touch the tree" is checked on the medium, not only in memory: the
     * coordinator persists through `store.update { }`, which rewrites the file
     * even when its block refuses, so an mtime comparison is what separates
     * "asked the tree and was refused" from "never asked".
     */
    @Test
    fun `restart without a launcher refuses and never touches the tree`() = runBlocking {
        val coordinator = coordinatorWithInterruptedChild()
        // Exactly the production default: no launch path is supplied.
        val executor = AgentToolExecutor(TestContext(newDir("executor-restart-null")))
        val before = requireNotNull(coordinator.tree().node("child"))
        val eventsBefore = coordinator.tree().events().filter { it.nodeId == "child" }.map { it.kind }
        // The fixture runs in a LIVE process, so the aborted child is still in the
        // coordinator's published set (after a real crash that set is empty and
        // every entry point falls back to the raw ids). What matters here is that
        // a refusal does not CHANGE that set either way.
        val publishedBefore = coordinator.activeIds().toMap()

        // A read-only pass first, so the mtime baseline belongs to a settled file.
        assertEquals(RuntimeNodeStatus.ABNORMAL_INTERRUPTION.name, supervisedStatus(executor, coordinator))
        Thread.sleep(20)
        val mtimeBefore = coordinator.treeFile().lastModified()
        val bytesBefore = coordinator.treeFile().readBytes()

        val result = restart(executor, coordinator)

        assertFalse("no launch path means no restart", result.success)
        val json = JSONObject(result.output)
        assertFalse(json.getBoolean("ok"))
        val error = json.getString("error")
        assertTrue("must name the missing capability: $error", error.contains("restart is not available"))
        assertTrue(
            "and must leave the child marked as interrupted: $error",
            error.contains("ABNORMAL_INTERRUPTION") || error.contains("abnormally interrupted"),
        )
        assertTrue("the model must be told not to retry this call: $error", error.contains("Do not retry"))

        // The node is untouched — in memory …
        assertEquals(before, coordinator.tree().node("child"))
        assertTrue(coordinator.tree().node("child")!!.abnormal)
        assertTrue("a refused restart must leave it restartable", coordinator.tree().canRestart("child"))
        assertEquals(eventsBefore, coordinator.tree().events().filter { it.nodeId == "child" }.map { it.kind })
        assertFalse(coordinator.tree().events().any { it.nodeId == "child" && it.kind == "restarted" })
        // … on disk …
        assertEquals(RuntimeNodeStatus.ABNORMAL_INTERRUPTION.name, coordinator.persistedStatus("child"))
        // … and on the medium itself: no write happened at all.
        assertEquals("the refusal must not rewrite the runtime tree", mtimeBefore, coordinator.treeFile().lastModified())
        assertArrayEquals(bytesBefore, coordinator.treeFile().readBytes())
        // … and it is not published as a live child by this call either.
        assertEquals("a refusal must not change what is published as live", publishedBefore, coordinator.activeIds())
        // Finally: the parent can still SEE it, which is the whole point of
        // leaving the node alone.
        assertEquals(RuntimeNodeStatus.ABNORMAL_INTERRUPTION.name, supervisedStatus(executor, coordinator))
    }

    @Test
    fun `the task field is the node's note, which is why the launcher reads the transcript`() = runBlocking {
        // Documents (and pins) the fact the app-side launcher has to work around:
        // a delegated child's `task` is its `model.note`, and the composer's note
        // is the `--note=` option — not the prompt it was delegated with.
        val coordinator = coordinatorWithInterruptedChild(task = "--note value, not the prompt")
        var seenPrompt: String? = null
        val executor = AgentToolExecutor(
            TestContext(newDir("executor-restart-note")),
            RestartedChildLauncher { _, child, request ->
                seenPrompt = request.prompt
                RestartedChildRun(ok = false, childSessionId = child, output = "stopped for the test")
            },
        )

        restart(executor, coordinator)

        assertEquals("--note value, not the prompt", seenPrompt)
    }

    // ─── 2. a launch that does not actually run the child ────────────────

    /**
     * The defect class this project keeps hitting: a state that looks better than
     * reality. Without the rollback the node stays RUNNING, `supervise_descendants`
     * stops reporting it as interrupted, and the parent waits for a result that
     * cannot arrive.
     */
    @Test
    fun `a launcher reporting ok = false rolls the node back to abnormal`() = runBlocking {
        val coordinator = coordinatorWithInterruptedChild()
        var seenParent: String? = null
        var seenChild: String? = null
        val executor = AgentToolExecutor(
            TestContext(newDir("executor-restart-failed")),
            RestartedChildLauncher { parent, child, _ ->
                seenParent = parent
                seenChild = child
                assertEquals(
                    "the executor must re-arm the node BEFORE handing it to the launcher",
                    RuntimeNodeStatus.RUNNING,
                    coordinator.tree().node("child")?.status,
                )
                RestartedChildRun(
                    ok = false,
                    childSessionId = child,
                    output = "no usable provider credential",
                    endReason = "NO_TOOL_CALL",
                )
            },
        )

        val result = restart(executor, coordinator)

        assertFalse("a child that never ran is not a restart", result.success)
        val json = JSONObject(result.output)
        assertFalse(json.getBoolean("ok"))
        assertTrue("the reason must reach the model", json.getString("error").contains("no usable provider credential"))
        assertEquals("the actor is the bound session, not an argument", "root", seenParent)
        assertEquals("child", seenChild)

        val node = requireNotNull(coordinator.tree().node("child"))
        assertEquals(
            "'nothing is running this child' has to look like 'nothing is running this child'",
            RuntimeNodeStatus.ABNORMAL_INTERRUPTION,
            node.status,
        )
        assertTrue(node.abnormal)
        assertEquals(RuntimeNodeStatus.ABNORMAL_INTERRUPTION.name, coordinator.persistedStatus("child"))
        assertFalse("a rolled-back child is not a live child", coordinator.activeIds().containsKey("child"))
        assertEquals(
            "supervise_descendants must report it as interrupted again",
            RuntimeNodeStatus.ABNORMAL_INTERRUPTION.name,
            supervisedStatus(executor, coordinator),
        )
        assertTrue("and the parent must be able to try again", coordinator.tree().canRestart("child"))
    }

    @Test
    fun `a launcher that throws rolls the node back to abnormal`() = runBlocking {
        val coordinator = coordinatorWithInterruptedChild()
        val executor = AgentToolExecutor(
            TestContext(newDir("executor-restart-throwing")),
            RestartedChildLauncher { _, _, _ -> error("provider instance is unavailable") },
        )

        val result = restart(executor, coordinator)

        assertFalse(result.success)
        assertTrue(JSONObject(result.output).getString("error").contains("provider instance is unavailable"))
        assertEquals(RuntimeNodeStatus.ABNORMAL_INTERRUPTION, coordinator.tree().node("child")?.status)
        assertEquals(RuntimeNodeStatus.ABNORMAL_INTERRUPTION.name, coordinator.persistedStatus("child"))
        assertFalse(coordinator.activeIds().containsKey("child"))
    }

    /**
     * The launcher is a full child agent run — minutes long, and therefore
     * cancellable. `runCatching` catches `CancellationException` like any other
     * throwable, so without the explicit rethrow a Stop during a restart would be
     * reported to the agent loop as an ordinary launch failure while the
     * surrounding coroutine was being cancelled.
     *
     * ## Why the reason text is asserted, and not only the status
     *
     * Both halves matter (the node must not be left RUNNING with nothing driving
     * it, and the cancellation must still unwind), but they are NOT equally
     * falsifiable, and the difference is worth writing down because it decides
     * what this test is worth:
     *
     *  - removing the `is CancellationException` branch kills the rethrow, so the
     *    propagation assertion goes red on its own;
     *  - the STATUS assertion, however, would stay GREEN after that same removal,
     *    because the ordinary failure path calls `markRestartedChildAbnormal`
     *    too — `CancellationException` is a `Throwable`, so the node is rolled
     *    back either way. A status-only assertion here would be riding along on
     *    behaviour it does not actually distinguish.
     *
     * So the rollback half is pinned by the REASON the rollback records, which is
     * the one thing only the cancellation path produces: the ordinary path stamps
     * `error.message` ("the user stopped the run"), while the cancellation path
     * stamps its own text. Remove the branch and this assertion goes red with the
     * status assertion still green — i.e. it distinguishes, instead of agreeing
     * by coincidence.
     */
    @Test
    fun `a cancelled launch rolls the node back and still propagates the cancellation`() = runBlocking {
        val coordinator = coordinatorWithInterruptedChild()
        val executor = AgentToolExecutor(
            TestContext(newDir("executor-restart-cancelled")),
            RestartedChildLauncher { _, _, _ ->
                throw kotlinx.coroutines.CancellationException("the user stopped the run")
            },
        )

        var thrown: Throwable? = null
        try {
            restart(executor, coordinator)
        } catch (error: Throwable) {
            thrown = error
        }

        assertTrue(
            "a cancelled restart must not be swallowed into a tool result; it was <$thrown>",
            thrown is kotlinx.coroutines.CancellationException,
        )
        val node = requireNotNull(coordinator.tree().node("child"))
        assertEquals(
            "the node was re-armed before the launch, so a cancelled launch must roll it back — a " +
                "RUNNING node with nothing driving it is the defect this path exists to prevent",
            RuntimeNodeStatus.ABNORMAL_INTERRUPTION,
            node.status,
        )
        assertTrue(node.abnormal)
        assertEquals(RuntimeNodeStatus.ABNORMAL_INTERRUPTION.name, coordinator.persistedStatus("child"))
        assertFalse(coordinator.activeIds().containsKey("child"))

        // The distinguishing assertion: only the cancellation path writes this.
        val interruption = coordinator.tree()
            .events()
            .filter { it.nodeId == "child" && it.kind == "abnormal_interruption" }
            .lastOrNull()
        val reason = interruption?.payload.orEmpty()
        assertTrue(
            "the rollback must record that it was a CANCELLATION, not fall through to the generic " +
                "launch-failure path — otherwise this test would pass even with the cancellation " +
                "branch deleted. Recorded reason: <$reason>",
            reason.contains("restart cancelled before the child ran"),
        )
        assertFalse(
            "and it must not have been stamped with the raw exception message, which is what the " +
                "ordinary failure path writes: <$reason>",
            reason.contains("the user stopped the run"),
        )
    }

    // ─── 3. a launch that really ran the child ───────────────────────────

    @Test
    fun `a launcher that really ran the child leaves the node running and reports its result`() = runBlocking {
        val coordinator = coordinatorWithInterruptedChild()
        val executor = AgentToolExecutor(
            TestContext(newDir("executor-restart-ok")),
            RestartedChildLauncher { _, child, _ ->
                assertEquals(
                    "the node must be RUNNING by the time the child is launched",
                    RuntimeNodeStatus.RUNNING,
                    coordinator.tree().node("child")?.status,
                )
                RestartedChildRun(
                    ok = true,
                    childSessionId = child,
                    output = "the tree holds one child",
                    endReason = "COMPLETION_TOOL",
                )
            },
        )

        val result = restart(executor, coordinator)

        assertTrue("a real restart is a success", result.success)
        val json = JSONObject(result.output)
        assertTrue(json.getBoolean("ok"))
        assertEquals("the tree holds one child", json.getString("result"))
        assertEquals("COMPLETION_TOOL", json.getString("end_reason"))
        assertEquals("child", json.getString("child_session_id"))

        assertEquals(RuntimeNodeStatus.RUNNING, coordinator.tree().node("child")?.status)
        assertFalse(coordinator.tree().node("child")!!.abnormal)
        assertEquals(
            "the restarted child is published as the live one",
            "child",
            coordinator.activeIds()["child"],
        )
        assertEquals(
            "a restart reuses the child's node instead of adding a second one",
            1,
            coordinator.supervisionSnapshot("root").descendants.size,
        )
    }

    // ─── 4. wiring a launcher does not widen what may be restarted ───────

    @Test
    fun `with a launcher wired, a running child and an unknown child are still refused`() = runBlocking {
        val coordinator = newCoordinator()
        assertTrue(coordinator.startRoot("root") != null)
        assertTrue(coordinator.startChild("root", "child"))
        val before = requireNotNull(coordinator.tree().node("child"))
        val publishedBefore = coordinator.activeIds().toMap()
        var launches = 0
        val executor = AgentToolExecutor(
            TestContext(newDir("executor-restart-gated")),
            RestartedChildLauncher { _, _, _ ->
                launches++
                error("must never run")
            },
        )

        val running = restart(executor, coordinator)
        assertFalse(running.success)
        assertTrue(JSONObject(running.output).getString("error").contains("restart rejected"))

        val unknown = restart(executor, coordinator, childSessionId = "ghost")
        assertFalse(unknown.success)

        assertEquals("neither refusal may reach the launcher", 0, launches)
        assertEquals(before, coordinator.tree().node("child"))
        assertEquals(
            "refusing to restart live work must not change the published set",
            publishedBefore,
            coordinator.activeIds(),
        )
    }
}
