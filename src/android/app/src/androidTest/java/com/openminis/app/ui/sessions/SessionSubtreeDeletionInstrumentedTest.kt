package com.openminis.app.ui.sessions

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.SessionDeletionRetryStore
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.ChatDao
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.storage.MediaStore
import com.openminis.app.feature.runtime.RuntimeSessionCoordinator
import com.openminis.app.feature.runtime.RuntimeTreeStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The user-facing recursive delete, end to end against a real Room database,
 * the real media tree and a real runtime tree.
 *
 * R44 line 252 asks for a *recursive* delete: removing a conversation must take
 * every sub-agent conversation derived from it. The reported defect was that
 * only a single row was removed, so the sub-agent conversations stayed behind as
 * orphans in the session list. These tests drive the same wiring the three UI
 * entry points use (`sessionSubtreeDeletionPipeline` +
 * `resolveSessionSubtreeDeletionPlan`), so a regression in the wiring fails here
 * rather than only in the JVM unit tests for the pure parts.
 *
 * Line 254 (an agent cannot delete itself) is enforced in the runtime tree and
 * pinned by `RuntimeSessionSubtreePurgeTest` on the JVM.
 */
@RunWith(AndroidJUnit4::class)
class SessionSubtreeDeletionInstrumentedTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: ChatDao
    private lateinit var repository: ChatRepository
    private lateinit var context: Context
    private lateinit var runtimeTreeFile: File

    @Before
    fun setUp() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val filesDir = File(base.cacheDir, "subtree-delete-${System.nanoTime()}")
            .apply { deleteRecursively(); mkdirs() }
        // Every store this path touches is rooted at `filesDir`, so redirecting it
        // keeps the test out of the app's real media / goals / retry ledger.
        context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = filesDir
        }
        database = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.chatDao()
        repository = ChatRepository(dao)
        runtimeTreeFile = File(filesDir, "session-tree.json")
    }

    @After
    fun tearDown() {
        database.close()
    }

    /**
     * A runtime tree in a temp file, so the purge runs against the production
     * implementation without touching the app's own tree.
     */
    private fun runtimeCoordinator(): RuntimeSessionCoordinator {
        val store = RuntimeTreeStore.openForTest(runtimeTreeFile) { source, target ->
            Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }
        val constructor = RuntimeSessionCoordinator::class.java
            .getDeclaredConstructor(RuntimeTreeStore::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(store)
    }

    /** A (root) → B → C, and an unrelated R (root) → S. */
    private fun seedRuntimeTree(coordinator: RuntimeSessionCoordinator) {
        assertTrue(coordinator.startRoot("A") != null)
        assertTrue(coordinator.startChild("A", "B"))
        assertTrue(coordinator.startChild("B", "C"))
        assertTrue(coordinator.startRoot("R") != null)
        assertTrue(coordinator.startChild("R", "S"))
        coordinator.finishChild("C")
        coordinator.finishChild("B")
        coordinator.finishChild("S")
    }

    private suspend fun seedChatRows(vararg ids: String) {
        ids.forEach { id ->
            dao.insertSession(ChatSessionEntity(id = id, modelId = "model", createdAt = 1L, updatedAt = 1L))
            dao.insertMessage(
                MessageEntity(
                    id = "message-$id",
                    sessionId = id,
                    role = "user",
                    partsJson = "[]",
                    createdAt = 1L,
                    sortOrder = 0,
                ),
            )
        }
    }

    private fun mediaStore() = MediaStore(context)

    private fun seedMedia(vararg ids: String) {
        val store = mediaStore()
        ids.forEach { id -> store.saveMedia("bytes-$id".toByteArray(), "image/png", id) }
    }

    private fun mediaExists(sessionId: String): Boolean =
        MediaStore(context).mediaBaseDir.walkTopDown().any { it.isDirectory && it.name == sessionId }

    private suspend fun planFor(coordinator: RuntimeSessionCoordinator, vararg roots: String) =
        resolveSessionSubtreeDeletionPlan(repository, coordinator.topologySnapshot().nodes, roots.toList())

    private fun pipeline(coordinator: () -> RuntimeSessionCoordinator) =
        sessionSubtreeDeletionPipeline(context, repository, runtimeCoordinator = coordinator)

    @Test
    fun deletingAConversationRemovesItsSubAgentRowsAndMediaButKeepsAnotherRoot() = runBlocking {
        val coordinator = runtimeCoordinator()
        seedRuntimeTree(coordinator)
        seedChatRows("A", "B", "C", "R", "S")
        seedMedia("A", "B", "C", "R", "S")

        val outcome = pipeline { coordinator }.run(planFor(coordinator, "A"))

        assertTrue("delete reported outstanding work: ${outcome.outstanding}", outcome.success)
        // The conversation and its whole sub-agent subtree are gone.
        assertNull("A", dao.getSession("A"))
        assertNull("sub-agent B survived the delete of A", dao.getSession("B"))
        assertNull("sub-agent C survived the delete of A", dao.getSession("C"))
        assertEquals(0, dao.countMessagesForSessions(listOf("A", "B", "C")))
        assertTrue("A media survived", !mediaExists("A"))
        assertTrue("B media survived", !mediaExists("B"))
        assertTrue("C media survived", !mediaExists("C"))
        // An unrelated tree is untouched.
        assertEquals("R", dao.getSession("R")?.id)
        assertEquals("S", dao.getSession("S")?.id)
        assertEquals(2, dao.countMessagesForSessions(listOf("R", "S")))
        assertTrue("R media was deleted", mediaExists("R"))
        assertTrue("S media was deleted", mediaExists("S"))
        // The runtime tree no longer points at conversations that do not exist.
        assertEquals(listOf("R", "S"), coordinator.topologySnapshot().nodes.map { it.id })
        // Nothing was left owing.
        assertTrue(SessionDeletionRetryStore.open(context).load("B").isEmpty())
    }

    @Test
    fun deletingASubAgentConversationKeepsItsAncestors() = runBlocking {
        val coordinator = runtimeCoordinator()
        seedRuntimeTree(coordinator)
        seedChatRows("A", "B", "C", "R", "S")

        pipeline { coordinator }.run(planFor(coordinator, "B"))

        assertEquals("A", dao.getSession("A")?.id)
        assertNull("B", dao.getSession("B"))
        assertNull("C", dao.getSession("C"))
        assertEquals(listOf("A", "R", "S"), coordinator.topologySnapshot().nodes.map { it.id })
    }

    @Test
    fun selectingAConversationAndItsOwnDescendantDeletesEachIdOnce() = runBlocking {
        val coordinator = runtimeCoordinator()
        seedRuntimeTree(coordinator)
        seedChatRows("A", "B", "C", "R", "S")

        val plan = planFor(coordinator, "A", "B")
        assertEquals(listOf("A", "B", "C"), plan.sessionIds)
        val outcome = pipeline { coordinator }.run(plan)

        assertTrue(outcome.success)
        assertEquals(listOf("A", "B", "C"), outcome.sessionIds)
        assertNull("A", dao.getSession("A"))
        assertNull("B", dao.getSession("B"))
        assertNull("C", dao.getSession("C"))
        assertEquals("R", dao.getSession("R")?.id)
    }

    @Test
    fun aFailedBatchStepIsRecordedForEverySessionAndOnlyTheFailedStepIsRetried() = runBlocking {
        val coordinator = runtimeCoordinator()
        seedRuntimeTree(coordinator)
        seedChatRows("A", "B", "C")
        seedMedia("A", "B", "C")
        // A delete that silently deletes nothing makes the batched `chat` step's
        // row-count check fail, which is the failure this ledger exists for.
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER block_session_delete BEFORE DELETE ON sessions " +
                "BEGIN SELECT RAISE(IGNORE); END",
        )

        val firstPlan = planFor(coordinator, "A")
        val firstRun = pipeline { coordinator }.run(firstPlan)

        assertTrue(!firstRun.success)
        val ledger = SessionDeletionRetryStore.open(context)
        // Every session in the batch owes the failed step — not just the root.
        assertEquals(setOf("chat"), ledger.load("A"))
        assertEquals(setOf("chat"), ledger.load("B"))
        assertEquals(setOf("chat"), ledger.load("C"))
        assertEquals("A", dao.getSession("A")?.id)

        database.openHelper.writableDatabase.execSQL("DROP TRIGGER block_session_delete")
        // Media that appears between the two attempts must survive the retry:
        // proof that the artifact steps are NOT run a second time.
        seedMedia("B")

        val retryPlan = planFor(coordinator, "A")
        val retry = pipeline { coordinator }.run(retryPlan)

        assertTrue("retry still owes: ${retry.outstanding}", retry.success)
        assertNull("A", dao.getSession("A"))
        assertNull("B", dao.getSession("B"))
        assertNull("C", dao.getSession("C"))
        assertTrue("A media step was re-run on the retry", !mediaExists("A"))
        assertTrue("media step re-ran on a retry that only owed the chat step", mediaExists("B"))
        assertTrue(ledger.load("A").isEmpty())
        assertTrue(ledger.load("C").isEmpty())
    }

    @Test
    fun aRuntimeTreeFailureDoesNotBlockTheConversationItself() = runBlocking {
        val coordinator = runtimeCoordinator()
        seedRuntimeTree(coordinator)
        seedChatRows("A", "B", "C")

        // The user asked for this conversation to be gone; a runtime tree that
        // cannot be written must not turn that into "nothing happens".
        val outcome = pipeline { error("injected runtime tree failure") }.run(planFor(coordinator, "A"))

        assertTrue(!outcome.success)
        assertNull("A", dao.getSession("A"))
        assertNull("B", dao.getSession("B"))
        val ledger = SessionDeletionRetryStore.open(context)
        assertEquals(setOf("runtime_tree"), ledger.load("A"))
        assertEquals(setOf("runtime_tree"), ledger.load("C"))
    }

    @Test
    fun deletingAnAncestorStillReachesASubtreeWhoseSubAgentWasReRun() = runBlocking {
        // A conversation that runs again gets a brand-new root node
        // (`<sessionId>#run-<n>`) with no parent, so B's later children have no
        // parent chain back to A. Resolving the delete set from A's own node alone
        // therefore loses them, and deleting A would leave a live subtree behind —
        // the orphan state this feature exists to prevent.
        val coordinator = runtimeCoordinator()
        assertTrue(coordinator.startRoot("A") != null)
        assertTrue(coordinator.startChild("A", "B"))
        coordinator.finishChild("B")
        // B is opened again: a fresh root, then C is delegated from it.
        assertTrue(coordinator.startRoot("B") != null)
        assertTrue(coordinator.startChild("B", "C"))
        coordinator.finishChild("C")
        // [T-android-subtree-delete-run-generation] The re-run mints a NEW
        // parentless root for B — `<sessionId>#run-<n>`, via
        // `RuntimeSessionCoordinator.startRoot`'s else-branch — and `generation`
        // there is a per-instance Long starting at 0 that ONLY that branch bumps
        // (`RuntimeSessionCoordinator.kt:18`, used once at `:65`;
        // `startChild`/`finishChild` never touch it). This coordinator is fresh and
        // `startRoot("A")` takes the `existing == null` branch, so the very first
        // re-run is `B#run-1`: the literal `B#run-2` this line used to require was
        // an off-by-one no fresh coordinator can ever produce, which is why the
        // assertion failed on the device before any delete ran.
        //
        // The generation NUMBER is an implementation detail elsewhere too — the
        // tree treats the suffix as opaque (`SessionTreeRuntime.kt:265-269`,
        // `startsWith("$sessionId#run-")`). So pin the SHAPE this scenario needs
        // instead: a second root for B exists and C hangs off it, which is exactly
        // the severed `parentId` link the delete set has to survive.
        val nodesAfterRerun = coordinator.topologySnapshot().nodes
        val rerunRoot = nodesAfterRerun.map { it.id }.firstOrNull { it.matches(Regex("^B#run-[0-9]+$")) }
        assertNotNull(
            "re-running a finished B must mint a second, parentless root for it; " +
                "nodes=${nodesAfterRerun.map { it.id }}",
            rerunRoot,
        )
        assertEquals(
            "C must hang off the re-run root rather than the original B node — that severed " +
                "parent link is the whole shape this test covers",
            rerunRoot,
            nodesAfterRerun.first { it.id == "C" }.parentId,
        )

        seedChatRows("A", "B", "C")
        seedMedia("A", "B", "C")

        val outcome = pipeline { coordinator }.run(planFor(coordinator, "A"))

        assertTrue("delete reported outstanding work: ${outcome.outstanding}", outcome.success)
        assertNull("A", dao.getSession("A"))
        assertNull("sub-agent B survived", dao.getSession("B"))
        assertNull("the sub-agent of the re-run B survived", dao.getSession("C"))
        assertTrue("C media survived", !mediaExists("C"))
        assertEquals(emptyList<String>(), coordinator.topologySnapshot().nodes.map { it.id })
    }

    @Test
    fun anArtifactFailureIsStillReachableAfterTheChatRowsAreGone() = runBlocking {
        // The batched `chat` step removes the rows, so a later attempt can no
        // longer rebuild this id's delete set from the database — B is simply not
        // in the plan any more. If the ledger is not what carries it back, the
        // failed artifact step is never revisited and its row sits in the ledger
        // forever while the media stays on disk.
        val coordinator = runtimeCoordinator()
        seedRuntimeTree(coordinator)
        seedChatRows("A", "B", "C")
        seedMedia("A", "B", "C")
        // B's row is already gone (the previous attempt's chat step committed),
        // which is exactly why it cannot be rediscovered from the chat table.
        dao.deleteSessionSubtree(listOf("B"))
        assertNull("precondition: B's row must be gone", dao.getSession("B"))
        val ledger = SessionDeletionRetryStore.openForTest(
            File(context.filesDir, "runtime/session-deletion-retries.json"),
        )
        ledger.save("B", setOf("media"))

        val plan = planFor(coordinator, "A")
        assertFalse("precondition: the plan can no longer name B", "B" in plan.sessionIds)
        val outcome = sessionSubtreeDeletionPipeline(
            context = context,
            chatRepository = repository,
            runtimeCoordinator = { coordinator },
            retryStore = ledger,
        ).run(plan)

        assertTrue("B's debt was not carried into the retry", outcome.sessionIds.contains("B"))
        assertTrue("retry still owes: ${outcome.outstanding}", outcome.success)
        assertTrue(ledger.load("B").isEmpty())
        assertTrue("B media was not cleaned on the carried-over retry", !mediaExists("B"))
    }
}
