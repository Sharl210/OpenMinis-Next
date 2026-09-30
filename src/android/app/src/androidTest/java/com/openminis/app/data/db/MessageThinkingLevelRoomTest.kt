package com.openminis.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.model.MessageProvenance
import com.openminis.app.data.model.ModelAttributionSnapshot
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [T-android-thinking-level-persist] The on-device half of the fix, against real
 * SQLite and real Room.
 *
 * The JVM tests (`MessageThinkingLevelPersistenceTest`, `MessageThinkingLevelMigrationTest`)
 * cover the write call, the read function and the reported SQL — but nothing on
 * the JVM can prove that SQLite actually has the column, that Room's generated
 * DAO binds it, or that a v13 file upgrades without losing rows. Those three are
 * what a user's real database will do, and they are what this file exercises.
 *
 * It is instrumented, so it only runs where a device or emulator is attached
 * (`./gradlew :app:connectedDebugAndroidTest` or a direct `adb shell am
 * instrument`). It is written to be runnable rather than decorative: if it has
 * not been executed in a given environment, that must be reported as an
 * UNRUN check, not as passing coverage.
 */
@RunWith(AndroidJUnit4::class)
class MessageThinkingLevelRoomTest {

    private val databaseName = "thinking-level-roundtrip"

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    private lateinit var database: AppDatabase

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
    }

    private fun openInMemory(context: Context): AppDatabase =
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    // ------------------------------------------------------- write → read → UI

    @Test
    fun aTurnsThinkingLevelSurvivesARealRoomRoundTrip() = runBlocking {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        database = openInMemory(context)
        val dao = database.chatDao()
        val repository = ChatRepository(dao)

        dao.insertSession(
            ChatSessionEntity(
                id = "session-round-trip",
                modelId = "gpt-5.6",
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )

        repository.appendMessage(
            sessionId = "session-round-trip",
            role = "assistant",
            partsJson = """[{"type":"text","value":"answer"}]""",
            modelSnapshot = ModelAttributionSnapshot(
                modelId = "gpt-5.6",
                displayName = "gpt-5.6",
                providerTypeRaw = "openAI",
                providerInstanceId = "instance-1",
            ),
            provenance = MessageProvenance.ASSISTANT,
            thinkingLevel = ThinkingLevel.XHIGH,
        )

        // The raw column, read with SQL that does not go through the entity at
        // all: this is what fails if the column was never added to the schema, or
        // added under a different name.
        database.openHelper.readableDatabase
            .query("SELECT thinking_level FROM messages")
            .use { cursor ->
                assertTrue("no message row was written", cursor.moveToFirst())
                assertEquals("XHIGH", cursor.getString(0))
            }

        // …and through Room's generated DAO + the entity field, which is the path
        // a restart takes.
        val rows = dao.loadMessages("session-round-trip")
        assertEquals(1, rows.size)
        assertEquals("XHIGH", rows.single().thinkingLevel)
        assertEquals(
            "the level must decode back to the level the turn ran at",
            ThinkingLevel.XHIGH,
            rows.single().thinkingLevel?.let { ThinkingLevel.decoded(it) },
        )
    }

    @Test
    fun aRowWrittenWithoutALevelStaysNullRatherThanPickingOneUp() = runBlocking {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        database = openInMemory(context)
        val dao = database.chatDao()
        val repository = ChatRepository(dao)

        dao.insertSession(
            ChatSessionEntity(
                id = "session-no-level",
                modelId = "gpt-5.6",
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        repository.appendMessage(
            sessionId = "session-no-level",
            role = "assistant",
            partsJson = """[{"type":"text","value":"answer"}]""",
            provenance = MessageProvenance.ASSISTANT,
        )

        assertNull(dao.loadMessages("session-no-level").single().thinkingLevel)
    }

    // ----------------------------------------------------------------- migration

    @Test
    fun upgradingAV13DatabaseKeepsItsMessagesAndAddsTheLevelColumn() {
        migrationHelper.createDatabase(databaseName, 13).use { v13 ->
            // Seeded with the v13 column set only — there is no thinking_level to
            // write yet, which is exactly the state a real user's file is in.
            v13.execSQL(
                "INSERT INTO sessions (id, model_id, created_at, updated_at, memory_enabled, edit_count) " +
                    "VALUES ('session-migration', 'gpt-5.6', 1, 1, 1, 0)",
            )
            v13.execSQL(
                "INSERT INTO messages (id, session_id, role, parts_json, created_at, sort_order, stream_interrupt_count) " +
                    "VALUES ('msg-existing', 'session-migration', 'assistant', " +
                    "'[{\"type\":\"text\",\"value\":\"kept\"}]', 2, 0, 0)",
            )
        }

        // runMigrationsAndValidate ALSO validates the resulting schema against the
        // exported 14.json, so a migration that adds the wrong column type, or
        // none at all, fails here instead of on the user's first launch.
        val migrated = migrationHelper.runMigrationsAndValidate(
            databaseName,
            14,
            true,
            AppDatabase.MIGRATION_13_14,
        )
        try {
            migrated.query("PRAGMA table_info(messages)").use { cursor ->
                val columns = buildSet {
                    val nameIndex = cursor.getColumnIndex("name")
                    while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                }
                assertTrue(
                    "thinking_level is missing after the 13 → 14 upgrade: $columns",
                    "thinking_level" in columns,
                )
            }

            migrated.query("SELECT id, thinking_level FROM messages").use { cursor ->
                assertTrue("the pre-existing message row was lost by the migration", cursor.moveToFirst())
                assertEquals("msg-existing", cursor.getString(0))
                assertTrue(
                    "a row written before the column existed must read NULL, never a default level",
                    cursor.isNull(1),
                )
            }
        } finally {
            migrated.close()
        }
    }
}
