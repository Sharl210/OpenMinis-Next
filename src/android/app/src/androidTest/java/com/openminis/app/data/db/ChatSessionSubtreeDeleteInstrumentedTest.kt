package com.openminis.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatSessionSubtreeDeleteInstrumentedTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: ChatDao
    private lateinit var repository: ChatRepository

    @Before
    fun setUp() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.chatDao()
        repository = ChatRepository(dao)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun insertSession(id: String) {
        dao.insertSession(ChatSessionEntity(id = id, modelId = "model", createdAt = 1L, updatedAt = 1L))
    }

    private suspend fun insertMessage(id: String, sessionId: String) {
        dao.insertMessage(
            MessageEntity(
                id = id,
                sessionId = sessionId,
                role = "user",
                partsJson = "[]",
                createdAt = 1L,
                sortOrder = 0,
            ),
        )
    }

    private suspend fun insertMarker(id: String, sessionId: String) {
        dao.insertCompactMarker(
            CompactMarkerEntity(
                id = id,
                sessionId = sessionId,
                summary = "summary",
                firstKeptSortOrder = 0,
                compactedCount = 1,
                createdAt = 1L,
            ),
        )
    }

    @Test
    fun deletesFrozenSubtreeAndCascadesMessagesAndMarkersButKeepsSibling() = runBlocking {
        listOf("parent", "child", "grandchild", "sibling").forEach { insertSession(it) }
        listOf("parent", "child", "grandchild", "sibling").forEach { id ->
            insertMessage("message-$id", id)
            insertMarker("marker-$id", id)
        }

        assertEquals(3, repository.deleteSessionSubtree(listOf("parent", "child", "grandchild")))

        assertEquals(null, dao.getSession("parent"))
        assertEquals(null, dao.getSession("child"))
        assertEquals(null, dao.getSession("grandchild"))
        assertEquals("sibling", dao.getSession("sibling")?.id)
        assertEquals(1, dao.totalMessageCount())
        assertEquals(1, dao.countMessagesForSessions(listOf("sibling")))
        assertEquals(0, dao.countMessagesForSessions(listOf("parent", "child", "grandchild")))
        assertEquals(1, dao.countCompactMarkersForSessions(listOf("sibling")))
        assertEquals(0, dao.countCompactMarkersForSessions(listOf("parent", "child", "grandchild")))
    }

    @Test
    fun duplicateAndEmptyIdsAreIdempotent() = runBlocking {
        insertSession("one")
        insertMessage("message-one", "one")
        insertMarker("marker-one", "one")

        assertEquals(0, repository.deleteSessionSubtree(emptyList()))
        assertEquals(1, repository.deleteSessionSubtree(listOf("one", "one")))
        assertEquals(0, repository.deleteSessionSubtree(emptyList()))
        assertEquals(null, dao.getSession("one"))
        assertEquals(0, dao.totalMessageCount())
        assertEquals(0, dao.countCompactMarkersForSessions(listOf("one")))
    }

    @Test
    fun missingIdRollsBackWholeBatch() = runBlocking {
        insertSession("kept-a")
        insertSession("kept-b")
        insertMessage("message-a", "kept-a")
        insertMessage("message-b", "kept-b")
        insertMarker("marker-a", "kept-a")
        insertMarker("marker-b", "kept-b")

        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.deleteSessionSubtree(listOf("kept-a", "missing")) }
        }

        assertEquals("kept-a", dao.getSession("kept-a")?.id)
        assertEquals("kept-b", dao.getSession("kept-b")?.id)
        assertEquals(2, dao.totalMessageCount())
        assertEquals(2, dao.countCompactMarkersForSessions(listOf("kept-a", "kept-b")))
    }

    @Test
    fun rowCountMismatchRollsBackWholeBatch() = runBlocking {
        insertSession("rollback-a")
        insertSession("rollback-b")
        insertMessage("message-a", "rollback-a")
        insertMessage("message-b", "rollback-b")
        insertMarker("marker-a", "rollback-a")
        insertMarker("marker-b", "rollback-b")
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER ignore_rollback_a BEFORE DELETE ON sessions " +
                "WHEN OLD.id = 'rollback-a' BEGIN SELECT RAISE(IGNORE); END",
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.deleteSessionSubtree(listOf("rollback-a", "rollback-b")) }
        }

        assertEquals("rollback-a", dao.getSession("rollback-a")?.id)
        assertEquals("rollback-b", dao.getSession("rollback-b")?.id)
        assertEquals(2, dao.totalMessageCount())
        assertEquals(2, dao.countCompactMarkersForSessions(listOf("rollback-a", "rollback-b")))
    }

    @Test
    fun legacySingleSessionDeleteDelegatesToTransaction() = runBlocking {
        insertSession("legacy")
        insertMessage("legacy-message", "legacy")
        insertMarker("legacy-marker", "legacy")

        repository.deleteSession("legacy")

        assertEquals(null, dao.getSession("legacy"))
        assertEquals(0, dao.totalMessageCount())
        assertEquals(0, dao.countCompactMarkersForSessions(listOf("legacy")))
    }
}
