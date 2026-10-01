package com.openminis.app.ui.sessions

import android.content.Context
import android.content.ContextWrapper
import com.openminis.app.data.SessionDeletionRetryStore
import com.openminis.app.data.db.ChatDao
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.feature.runtime.GoalRuntimeSnapshot
import com.openminis.app.feature.runtime.GoalRuntimeStore
import com.openminis.app.feature.runtime.GoalStatus
import com.openminis.app.feature.runtime.RuntimeCapabilitySnapshotVersion
import com.openminis.app.feature.runtime.RuntimeCommunicationDirectoryMode
import com.openminis.app.feature.runtime.RuntimeCommunicationDirectoryPolicy
import com.openminis.app.feature.runtime.RuntimeCommunicationDirection
import com.openminis.app.feature.runtime.RuntimeCommunicationFileStore
import com.openminis.app.feature.runtime.RuntimeCommunicationMetadata
import com.openminis.app.feature.runtime.RuntimeCommunicationPeer
import com.openminis.app.feature.runtime.RuntimeCommunicationRepository
import com.openminis.app.feature.runtime.RuntimeCommunicationRouteKind
import com.openminis.app.feature.runtime.RuntimeCommunicationState
import com.openminis.app.feature.runtime.RuntimeConversationAddress
import com.openminis.app.feature.runtime.RuntimeSessionCoordinator
import com.openminis.app.service.SessionBadgeStore
import com.openminis.app.ui.chat.ChatViewModelStore
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A user-initiated conversation delete has to take three things with it: the
 * chat rows of the whole subtree, the runtime tree nodes behind them, and the
 * per-session artifacts. Every user-facing entry point has to land on that one
 * cleanup instead of quietly deleting a single row — which is exactly how the
 * recursive half went missing in the first place.
 *
 * ## The split in this file, stated up front
 *
 *  - **[A] TWO behavioural tests** run the production wiring end to end:
 *    `resolveSessionSubtreeDeletionPlan` against a real runtime topology, and
 *    `sessionSubtreeDeletionPipeline` — the function all three entry points call
 *    — against a real coordinator, a real retry ledger and a temp filesystem.
 *    They assert what is left afterwards: which chat rows are gone, which
 *    runtime nodes are gone, and whether the deleted session's media, goal file,
 *    badge, communication record and ViewModel store are gone with them.
 *  - **[C] SIX structural pins, kept deliberately.** They assert on the text of
 *    `SessionListViewModel.kt` / `SessionSubtreeDeletionWiring.kt`, and they are
 *    the part no JVM test can reach:
 *      - the three entry points (`deleteSession`, `deleteSelected`,
 *        `deleteFolderWithSessions`) are members of `SessionListViewModel`, which
 *        needs a live Android ViewModel, a Room `ChatDao` and the Compose session
 *        list behind it. "This method calls that helper" is a call-graph fact;
 *        there is no Robolectric in this module to observe it, and the behaviour
 *        the call produces is already covered (see below). The only part of that
 *        class the unit-test source set can reach is its companion's pure
 *        helpers (see `GroupSuggestionParseTest`), which is not where the delete
 *        entry points live.
 *      - `OffloadPermissionManager.clearSessionGrants` is observable only through
 *        private in-memory maps — the object exposes no reader for a session's
 *        grants, so "the wired step really ran" cannot be asserted, only read.
 *
 * ## What is *not* duplicated here, and what is
 *
 * The two pieces the old text assertions only *claimed* are pinned on the JVM
 * by files that already existed: `SessionSubtreeDeletionPlanTest` (which ids a
 * subtree delete must take) and `SessionSubtreeDeletionPipelineTest` (the retry
 * ledger, per-step failures, and the batched chat delete). This file now adds
 * the seam those two cannot see — that the wiring production actually calls
 * resolves and deletes the real subtree.
 *
 * The wiring itself is also driven end to end, against a real Room database, by
 * `SessionSubtreeDeletionInstrumentedTest` — in particular
 * `deletingAConversationRemovesItsSubAgentRowsAndMediaButKeepsAnotherRoot` and
 * `deletingASubAgentConversationKeepsItsAncestors`, which assert the deleted and
 * surviving chat rows, the deleted media and the reduced runtime tree. That is
 * the (B) coverage for those three steps. It is an instrumented test, so it only
 * runs with a device attached; the JVM test below mirrors those three steps so
 * the routine unit suite keeps them, and adds the four artifact steps the
 * instrumented test does not touch at all (goal state, badge, communication
 *
 * This file is behavioural only. The source-text pins it once carried — the three
 * delete entry points routing through the recursive helper, and the per-session
 * permission-grant call — were exact duplicates of assertions in
 * `SessionSubtreeDeletionWiringSourceTest` and were folded into that file, which
 * declares this rule in its own KDoc. Two copies of one rule count it twice and,
 * as that pair had already done, drift apart.
 *
 * The class name still says `SourceTest`, which no longer describes it. Left as-is
 * deliberately: the audit ledger cites this file by name, and a rename is a
 * separate change. `ChatExporterRuntimeTreeSourceTest` carries the same wart.
 * record, ViewModel store). Nothing here replaces the instrumented test.
 */
class SessionDeletionCleanupSourceTest {

    // --------------------------------------------------------------- fixtures

    /**
     * A throw-away app context. The directory fields are deliberately NOT named
     * `filesDir`: `Context` already declares that name, so inside the anonymous
     * wrapper below an unqualified `filesDir` would resolve to the wrapper's own
     * property and `override fun getFilesDir() = filesDir` would recurse until
     * the stack died.
     */
    private class TestRoot {
        val dir: File = Files.createTempDirectory("session-deletion-cleanup").toFile()
        val filesRoot: File = File(dir, "files").apply { mkdirs() }

        fun context(): Context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = filesRoot
        }

        fun dispose() {
            dir.deleteRecursively()
        }
    }

    /**
     * The chat table as a plain set, served through a JDK proxy over the real
     * [ChatDao] interface: Room needs a device, and `ChatRepository` — the thing
     * the production wiring calls — takes a `ChatDao`. Every method the wiring
     * does not use fails loudly rather than returning a plausible default.
     */
    private class SessionTableChatDao(
        private val sessions: MutableSet<String>,
    ) : InvocationHandler {

        /**
         * Makes the batched delete fail the way the database makes it fail.
         *
         * [ChatDao.deleteSessionSubtree] aborts the whole transaction when the
         * affected row count is not what it asked for (and `deleteSession` when a
         * single id is unknown), so a database that silently deletes nothing is
         * exactly a failed step. The attempt that has to be recoverable is that
         * failure happening *after* the runtime-tree step already committed.
         */
        var failDelete: Boolean = false

        val dao: ChatDao = Proxy.newProxyInstance(
            ChatDao::class.java.classLoader,
            arrayOf(ChatDao::class.java),
            this,
        ) as ChatDao

        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            val raw = args?.toList().orEmpty()
            // A suspend DAO method carries a trailing Continuation; this fake
            // answers synchronously, so the continuation is only stripped.
            val arguments = if (raw.lastOrNull() is kotlin.coroutines.Continuation<*>) raw.dropLast(1) else raw
            return when (method.name) {
                "existingSessionIds" ->
                    (arguments[0] as List<*>).filterIsInstance<String>().filter { it in sessions }

                "deleteSessionSubtree" -> {
                    if (failDelete) {
                        error("injected chat-step failure: the transaction could not be applied")
                    }
                    val ids = (arguments[0] as List<*>).filterIsInstance<String>()
                    // Room's contract: `@Transaction` refuses the whole batch when
                    // one id is unknown. Mirrored so the guard cannot go unnoticed.
                    check(ids.all { it in sessions }) {
                        "deleteSessionSubtree refuses a batch containing an unknown id: $ids"
                    }
                    ids.count { sessions.remove(it) }
                }

                "toString" -> "SessionTableChatDao($sessions)"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments.firstOrNull()
                else -> error(
                    "SessionTableChatDao: unexpected ${method.name}(...). The delete reached for a DAO " +
                        "method this fake does not model — model it explicitly instead of letting it pass silently.",
                )
            }
        }
    }

    /**
     * `A → B → C` with a runtime-only child `ghost` (a node whose session never
     * reached the database), plus an unrelated root `R → S`. Returns the
     * coordinator, the chat table and the repository over it.
     */
    private class Fixture(root: TestRoot) {
        val coordinator: RuntimeSessionCoordinator = RuntimeSessionCoordinator.open(root.context())
        val chatTable: MutableSet<String> = mutableSetOf("A", "B", "C", "R", "S")

        /** The fake behind [repository], exposed so a test can make a step fail. */
        val chatFake = SessionTableChatDao(chatTable)
        val repository = ChatRepository(chatFake.dao)

        init {
            check(coordinator.startRoot("A") == "A")
            check(coordinator.startChild("A", "B"))
            check(coordinator.startChild("B", "C"))
            check(coordinator.startChild("A", "ghost"))
            check(coordinator.startRoot("R") == "R")
            check(coordinator.startChild("R", "S"))
        }

        fun nodeIds(): List<String> = coordinator.topologySnapshot().nodes.map { it.id }
    }

    /**
     * The production resolution the three entry points run before deleting —
     * both ends real: the topology comes off the live coordinator, the existence
     * filter off the chat table.
     */
    private suspend fun planFor(fixture: Fixture, vararg rootIds: String) =
        resolveSessionSubtreeDeletionPlan(
            fixture.repository,
            fixture.coordinator.topologySnapshot().nodes,
            rootIds.toList(),
        )

    private fun communicationRecord(recordId: String, sessionId: String) = RuntimeCommunicationMetadata(
        recordId = recordId,
        sender = RuntimeCommunicationPeer(
            RuntimeConversationAddress.fromStableSessionId(sessionId),
            sessionId,
            executionId = "execution-$sessionId",
        ),
        receiver = RuntimeCommunicationPeer(
            RuntimeConversationAddress.fromStableSessionId("parent"),
            "parent",
            executionId = "execution-parent",
        ),
        direction = RuntimeCommunicationDirection.OUTBOUND,
        timestampMillis = 1_700_000_000_000L,
        state = RuntimeCommunicationState.DELIVERED,
        summary = "delegated: $sessionId",
        senderCapabilityVersion = RuntimeCapabilitySnapshotVersion(1, 2),
        receiverCapabilityVersion = RuntimeCapabilitySnapshotVersion(3, 4),
        directoryPolicy = RuntimeCommunicationDirectoryPolicy(
            RuntimeCommunicationDirectoryMode.TEAM,
            RuntimeCommunicationRouteKind.TEAM_PEER,
        ),
    )

    // ------------------------------------------------------- the resolvable half

    @Test
    fun `the delete set is resolved from the runtime topology and filtered by the chat table`() = runBlocking {
        val root = TestRoot()
        try {
            val fixture = Fixture(root)

            val plan = planFor(fixture, "A")

            assertEquals(
                "the delete set is the subtree the runtime tree describes, not the one id the user tapped",
                listOf("A", "B", "C"),
                plan.sessionIds,
            )
            assertEquals(
                "the runtime nodes of the same subtree, including the node whose chat row never existed — " +
                    "it is what the runtime-tree step purges",
                listOf("A", "B", "C", "ghost"),
                plan.runtimeNodeIds,
            )
            assertEquals(listOf("A"), plan.rootSessionIds)

            assertEquals(
                "a sibling tree under another root is not part of the delete set",
                listOf("R", "S"),
                planFor(fixture, "R").sessionIds,
            )
        } finally {
            root.dispose()
        }
    }

    // ------------------------------------------------ the wired cleanup, executed

    @Test
    fun `the wired cleanup deletes the subtree's chat rows, purges its runtime nodes and drops its artifacts`() =
        runBlocking {
            val root = TestRoot()
            try {
                val fixture = Fixture(root)
                val context = root.context()

                // Real artifacts for the two subtrees, laid down where the
                // production steps look for them.
                val media = { id: String -> File(root.filesRoot, "media/2026/01/01/$id/pic.bin") }
                listOf("A", "B", "C", "R", "S").forEach { id ->
                    media(id).apply { parentFile?.mkdirs() }.writeBytes(byteArrayOf(1, 2, 3))
                }
                // Goal state is seeded and read back through the PRODUCTION store
                // instead of by hard-coding `runtime/goals/$id.json`, because that
                // is not the path today's store uses: `GoalRuntimeStore.open`
                // builds its name with `sessionId.map { ... }`, and `String.map`
                // returns a `List<Char>`, so the string template interpolates
                // `[<id>]` — the real file is `runtime/goals/[A].json`.
                //
                // Hard-coding the intended path would make this test red for a
                // reason unrelated to the cleanup under test (that naming defect is
                // reported separately; it is not papered over here). What this test
                // owns is the CONTRACT: after the pipeline runs, a deleted session's
                // goal state reads back IDLE, and the untouched subtree's does not.
                // Asserting through the store keeps that contract without binding the
                // test to a file name.
                val goal = { id: String -> GoalRuntimeStore.open(context, id) }
                listOf("A", "B", "C", "R", "S").forEach { id ->
                    assertTrue(
                        "fixture: the goal state must have been written for $id",
                        goal(id).save(
                            GoalRuntimeSnapshot(
                                status = GoalStatus.ACTIVE,
                                objective = "goal of $id",
                                updatedAtMillis = 1_700_000_000_000L,
                            ),
                        ),
                    )
                    assertEquals(
                        "fixture: the goal state must read back as written for $id",
                        GoalStatus.ACTIVE,
                        goal(id).load().status,
                    )
                }
                val communicationFile = File(root.filesRoot, "runtime/communication-metadata.json")
                val communicationSeeded = RuntimeCommunicationRepository(RuntimeCommunicationFileStore(communicationFile))
                assertTrue(communicationSeeded.append(communicationRecord("comm-A", "A")))
                assertTrue(communicationSeeded.append(communicationRecord("comm-R", "R")))
                SessionBadgeStore.push("A", SessionBadgeStore.SessionBadgeState.PAUSED)
                val storeBefore = ChatViewModelStore.ownerFor("A").viewModelStore

                // The production wiring, with only its test seams injected.
                val pipeline = sessionSubtreeDeletionPipeline(
                    context = context,
                    chatRepository = fixture.repository,
                    runtimeCoordinator = { fixture.coordinator },
                    retryStore = SessionDeletionRetryStore.openForTest(File(root.dir, "retries.json")),
                )

                val outcome = pipeline.run(planFor(fixture, "A"))

                assertTrue(
                    "every wired step has to complete; outstanding=${outcome.outstanding}",
                    outcome.success,
                )
                assertEquals(listOf("A", "B", "C"), outcome.sessionIds)
                assertEquals(
                    "the chat rows of the subtree, and only those, are gone",
                    setOf("R", "S"),
                    fixture.chatTable,
                )
                assertEquals(
                    "the runtime tree keeps exactly the unrelated root — the ghost node, which had no " +
                        "chat row to be filtered by, is purged too",
                    listOf("R", "S"),
                    fixture.nodeIds(),
                )
                listOf("A", "B", "C").forEach { id ->
                    assertFalse("media for the deleted session must be gone: $id", media(id).exists())
                }
                listOf("A", "B", "C").forEach { id ->
                    assertEquals(
                        "the deleted session's goal state must read back IDLE: $id",
                        GoalStatus.IDLE,
                        goal(id).load().status,
                    )
                }
                assertFalse(
                    "the deleted session's badge must not survive it",
                    SessionBadgeStore.byId.value.containsKey("A"),
                )
                assertNull(
                    "the deleted session's communication record must go with it",
                    RuntimeCommunicationRepository(RuntimeCommunicationFileStore(communicationFile)).find("comm-A"),
                )
                assertNotSame(
                    "the deleted session's ViewModel store must be dropped, not handed back",
                    storeBefore,
                    ChatViewModelStore.ownerFor("A").viewModelStore,
                )

                // …and the untouched subtree is untouched.
                assertTrue("the sibling root's media must survive", media("R").isFile)
                assertTrue("the sibling root's media must survive", media("S").isFile)
                assertTrue("the sibling root's goal state must survive", goal("R").load().status == GoalStatus.ACTIVE)
                assertTrue("the sibling root's goal state must survive", goal("S").load().status == GoalStatus.ACTIVE)
                assertTrue(
                    "the sibling root's communication record must survive",
                    RuntimeCommunicationRepository(RuntimeCommunicationFileStore(communicationFile)).find("comm-R") != null,
                )
            } finally {
                root.dispose()
            }
        }

    /**
     * The failure shape that a device run exposed, reproduced end to end here.
     *
     * The two batched steps run in wiring order — `runtime_tree` first, `chat`
     * second — so the attempt in which the chat step fails is also the attempt
     * that empties the tree. The retry rebuilds its delete set from a tree that
     * no longer names B or C, leaving a plan that names only A, while the ledger
     * still records that B and C owe the chat step.
     *
     * The pipeline unions those carried-over ids into the set it *works on*, but
     * a batch step deletes exactly the ids in the plan it is handed: hand it a
     * plan naming only A and the chat step deletes only A — and then clears that
     * step for **every** id in the run, because a committed batch is
     * all-or-nothing for the whole subtree. B's and C's rows would stay in the
     * database, the ledger would read "nothing owed", and the run would report
     * success. That is an orphan reported as success, the one outcome a recursive
     * delete may never produce.
     */
    @Test
    fun `a retry after the tree is gone still deletes every row the ledger says is owed`() = runBlocking {
        val root = TestRoot()
        try {
            val fixture = Fixture(root)
            val context = root.context()
            val ledger = SessionDeletionRetryStore.openForTest(File(root.dir, "retries.json"))
            val pipeline = {
                sessionSubtreeDeletionPipeline(
                    context = context,
                    chatRepository = fixture.repository,
                    runtimeCoordinator = { fixture.coordinator },
                    retryStore = ledger,
                )
            }

            // Attempt 1: the chat step fails at the database, after the
            // runtime-tree step before it already committed.
            fixture.chatFake.failDelete = true
            val first = pipeline().run(planFor(fixture, "A"))

            assertFalse("precondition: the first attempt must fail", first.success)
            assertEquals(
                "precondition: the runtime-tree step runs first and takes A, B, C and ghost with it",
                listOf("R", "S"),
                fixture.nodeIds(),
            )
            assertEquals(
                "precondition: the failed step is owed by the whole subtree, not just the root",
                setOf("chat"),
                ledger.load("B"),
            )
            assertEquals(setOf("chat"), ledger.load("C"))

            // Attempt 2 — the plan a retry can still rebuild once the tree is
            // empty. This is the plan the device test drives.
            val retryPlan = planFor(fixture, "A")
            assertEquals(
                "precondition: the rebuilt plan can no longer name B or C — the ledger is the only " +
                    "thing that still remembers them",
                listOf("A"),
                retryPlan.sessionIds,
            )

            fixture.chatFake.failDelete = false
            val retry = pipeline().run(retryPlan)

            assertTrue("the retry did not finish: outstanding=${retry.outstanding}", retry.success)
            assertEquals(
                "the retry reported success (${retry.success}) while rows it still had to delete are in " +
                    "the table; the ledger no longer names them, so nothing will ever come back for them",
                setOf("R", "S"),
                fixture.chatTable,
            )
            assertTrue("B's debt was cleared without B being deleted", ledger.load("B").isEmpty())
            assertTrue("C's debt was cleared without C being deleted", ledger.load("C").isEmpty())
            assertEquals(listOf("R", "S"), fixture.nodeIds())
        } finally {
            root.dispose()
        }
    }

    // ---------------------------------------- the retained structural checks (C)

    // Two source-text pins used to live here. Both moved to their authoritative
    // home, `SessionSubtreeDeletionWiringSourceTest` — that file's KDoc is what
    // claims this rule ("every entry point must reach the one recursive helper"),
    // and it asserts a strict superset: all six literals checked here are also
    // checked there, and this file contributed no literal of its own.
    //
    // Keeping both copies counted one rule twice, which is worse than merely
    // redundant — the two had already drifted. The sibling also pins
    // `retryStore.ids()`, which this copy never did, so a reader of this file
    // would have taken the routing guard for complete when it was not. One rule,
    // one home.
    //
    // The per-session permission-grant pin travelled with them, together with its
    // "not convertible" rationale: the grants live in `OffloadPermissionManager`'s
    // private in-memory maps with no reader, so a behavioural test could only
    // assert that the call did not throw — which is not evidence. This file is
    // behavioural only now.

}
