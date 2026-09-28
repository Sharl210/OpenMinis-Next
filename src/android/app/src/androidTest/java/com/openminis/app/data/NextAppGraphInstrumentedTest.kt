package com.openminis.app.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.db.NextFolderEntity
import com.openminis.app.data.db.NextMessageEntity
import com.openminis.app.data.db.NextSessionEntity
import com.openminis.app.data.db.NextDatabaseProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device Room smoke for the Next-only graph. This deliberately uses the
 * target context and the real file-backed provider: the JVM test source set
 * has no Robolectric dependency and cannot provide a real filesDir Context.
 */
@RunWith(AndroidJUnit4::class)
class NextAppGraphInstrumentedTest {
    private lateinit var context: Context
    private lateinit var databaseFile: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        NextDatabaseProvider.closeForProcessTeardown()
        databaseFile = File(NextDatabaseProvider.databasePath(context))
        databaseFile.delete()
        File(databaseFile.path + "-wal").delete()
        File(databaseFile.path + "-shm").delete()
    }

    @After
    fun tearDown() {
        NextDatabaseProvider.closeForProcessTeardown()
        databaseFile.delete()
        File(databaseFile.path + "-wal").delete()
        File(databaseFile.path + "-shm").delete()
    }

    @Test
    fun graphCrudSurvivesCloseAndReopenWithoutLegacyDatabase() = runBlocking {
        val legacyPath = File(context.filesDir, "minis.db")
        val legacySentinel = byteArrayOf(0x4f, 0x4c, 0x44)
        legacyPath.writeBytes(legacySentinel)
        assertEquals(legacySentinel.toList(), legacyPath.readBytes().toList())
        val expectedPath = File(context.filesDir, "openminis-next/openminis-next.db").absolutePath
        val legacyDatabasePath = legacyPath.absolutePath
        assertEquals(expectedPath, databaseFile.absolutePath)
        assertNotEquals(legacyDatabasePath, databaseFile.absolutePath)

        val session = NextSessionEntity(
            id = "next-session-smoke",
            title = "Next smoke",
            modelBinding = "next-model-binding",
            createdAt = 10L,
            updatedAt = 11L,
        )
        val folder = NextFolderEntity(
            id = "next-folder-smoke",
            name = "Next folder",
            createdAt = 10L,
            updatedAt = 11L,
        )
        val message = NextMessageEntity(
            id = "next-message-smoke",
            sessionId = session.id,
            role = "user",
            partsJson = "[{\"type\":\"text\",\"value\":\"hello\"}]",
            createdAt = 12L,
            sortOrder = 0L,
        )

        var graph = NextAppGraph.open(context)
        graph.folders.upsert(folder)
        graph.sessions.upsert(session.copy(folderId = folder.id))
        graph.messages.upsert(message)

        assertEquals(folder, graph.folders.get(folder.id))
        assertEquals(session.copy(folderId = folder.id), graph.sessions.get(session.id))
        assertEquals(listOf(message), graph.messages.listForSession(session.id))
        assertTrue(databaseFile.isFile)
        graph.close()

        graph = NextAppGraph.open(context)
        try {
            assertNotNull(graph.sessions.get(session.id))
            assertEquals(folder.id, graph.sessions.get(session.id)?.folderId)
            assertEquals(listOf(message), graph.messages.listForSession(session.id))
            assertEquals(folder, graph.folders.get(folder.id))
            assertEquals(legacySentinel.toList(), legacyPath.readBytes().toList())
        } finally {
            graph.close()
        }
    }

    @Test
    fun recursiveDeletionRemovesMessagesAndPreservesSibling() = runBlocking {
        val graph = NextAppGraph.open(context)
        try {
            val root = NextSessionEntity("delete-root", modelBinding = "m", createdAt = 1L, updatedAt = 1L,
                rootId = "delete-root", depth = 0, birthChain = "[\"delete-root\"]")
            graph.sessions.upsert(root)
            graph.sessions.createChild(root.id, NextSessionEntity("delete-a", modelBinding = "m", createdAt = 2L, updatedAt = 2L))
            graph.sessions.createChild("delete-a", NextSessionEntity("delete-b", modelBinding = "m", createdAt = 3L, updatedAt = 3L))
            graph.sessions.createChild(root.id, NextSessionEntity("delete-sibling", modelBinding = "m", createdAt = 4L, updatedAt = 4L))
            val message = NextMessageEntity("delete-message", "delete-b", "user", "[]", 5L, 0L)
            graph.messages.upsert(message)

            val result = graph.subtreeDeletion.deleteSubtree("delete-a")
            assertTrue(result.success)
            assertEquals(listOf("delete-a", "delete-b"), result.affectedIds)
            assertEquals(null, graph.sessions.get("delete-a"))
            assertEquals(null, graph.sessions.get("delete-b"))
            assertTrue(graph.messages.listForSession("delete-b").isEmpty())
            assertNotNull(graph.sessions.get("delete-sibling"))
            assertNotNull(graph.sessions.get("delete-root"))

            val repeated = graph.subtreeDeletion.deleteSubtree("delete-a")
            assertEquals(false, repeated.success)
            assertEquals("not_found", repeated.reason)
        } finally {
            graph.close()
        }
    }

    @Test
    fun recursiveDeletionRollsBackWhenDeletionHookFails() = runBlocking {
        val graph = NextAppGraph.open(context)
        val database = graph.database
        try {
            graph.sessions.upsert(NextSessionEntity("rollback-root", modelBinding = "m", createdAt = 1L, updatedAt = 1L))
            graph.sessions.createChild("rollback-root", NextSessionEntity("rollback-child", modelBinding = "m", createdAt = 2L, updatedAt = 2L))
            graph.messages.upsert(NextMessageEntity("rollback-message", "rollback-child", "user", "[]", 3L, 0L))
            val failing = com.openminis.app.data.repository.NextSubtreeDeletionRepository(database) {
                error("injected deletion failure")
            }
            runCatching { failing.deleteSubtree("rollback-root") }.onSuccess { error("expected injected failure") }
            assertNotNull(graph.sessions.get("rollback-root"))
            assertNotNull(graph.sessions.get("rollback-child"))
            assertEquals(1, graph.messages.listForSession("rollback-child").size)
        } finally {
            graph.close()
        }
    }

    @Test
    fun lineageHelperCapturesRootDepthAndBirthChain() = runBlocking {
        val graph = NextAppGraph.open(context)
        try {
            val root = NextSessionEntity("lineage-root", modelBinding = "m", createdAt = 1L, updatedAt = 1L)
            graph.sessions.upsert(root)
            val child = graph.sessions.createChild(root.id,
                NextSessionEntity("lineage-child", modelBinding = "m", createdAt = 2L, updatedAt = 2L))
            val grandchild = graph.sessions.createChild(child.id,
                NextSessionEntity("lineage-grandchild", modelBinding = "m", createdAt = 3L, updatedAt = 3L))
            assertEquals("lineage-root", grandchild.rootId)
            assertEquals(2, grandchild.depth)
            assertEquals("[\"lineage-root\",\"lineage-child\",\"lineage-grandchild\"]", grandchild.birthChain)
        } finally {
            graph.close()
        }
    }

    @Test
    fun migrationFromV1InitializesExistingSessionsAsRoots() = runBlocking {
        val legacyPath = File(context.filesDir, "openminis-next/migration-v1.db")
        legacyPath.parentFile?.mkdirs()
        legacyPath.delete()
        val v1 = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(legacyPath.absolutePath)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS `next_sessions` (`id` TEXT NOT NULL, `title` TEXT, `modelBinding` TEXT NOT NULL, `category` TEXT, `lastMessage` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `memoryEnabled` INTEGER NOT NULL, `pinnedAt` INTEGER, `editCount` INTEGER NOT NULL, `thinkingOverride` TEXT, `folderId` TEXT, PRIMARY KEY(`id`))")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `next_runtime_metadata` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `next_folders` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `icon` TEXT, `color` TEXT, `origin` TEXT NOT NULL, `sortIndex` INTEGER NOT NULL, `pinnedAt` INTEGER, `description` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `next_messages` (`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `role` TEXT NOT NULL, `partsJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, `tokenUsage` TEXT, `reasoningContent` TEXT, `updatedAt` INTEGER, `errorInfo` TEXT, `modelId` TEXT, `modelDisplayName` TEXT, `providerType` TEXT, `providerInstanceId` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`sessionId`) REFERENCES `next_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_next_messages_sessionId_sortOrder` ON `next_messages` (`sessionId`, `sortOrder`)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_next_messages_sessionId_createdAt` ON `next_messages` (`sessionId`, `createdAt`)")
                    }
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build()
        )
        try {
            v1.writableDatabase.execSQL("INSERT INTO next_sessions (id, modelBinding, createdAt, updatedAt, memoryEnabled, editCount) VALUES ('migrated-session', 'm', 1, 2, 1, 0)")
            v1.writableDatabase.version = 1
        } finally {
            v1.close()
        }
        val migrated = androidx.room.Room.databaseBuilder(context, com.openminis.app.data.db.NextAppDatabase::class.java, legacyPath.absolutePath)
            .addMigrations(com.openminis.app.data.db.NextMigrations.MIGRATION_1_2)
            .build()
        try {
            val session = migrated.sessionDao().get("migrated-session")
            assertNotNull(session)
            assertEquals("migrated-session", session?.rootId)
            assertEquals(0, session?.depth)
            assertEquals("[\"migrated-session\"]", session?.birthChain)
        } finally {
            migrated.close()
            legacyPath.delete()
        }
    }
}
