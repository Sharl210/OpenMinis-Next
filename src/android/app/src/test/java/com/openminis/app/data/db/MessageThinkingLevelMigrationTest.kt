package com.openminis.app.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * [T-android-thinking-level-persist] The schema half of the fix: the migration
 * that carries `messages.thinking_level` must exist, must be additive, and must
 * produce exactly the column Room's own exported schema declares.
 *
 * ## Why this can run without a device
 *
 * `MigrationTestHelper` needs an Android runtime, but the two things that
 * actually break a schema bump do not:
 *
 *  - the migration may be missing, wrong, or destructive — so its body is
 *    EXECUTED here against a recording `SupportSQLiteDatabase`, and the SQL it
 *    issues is compared with Room's exported schema JSON (which Room regenerates
 *    from the entity classes on every build, so it is the source of truth and not
 *    a copy of it);
 *  - the entity may have gained a column the migration never adds, which Room
 *    only discovers when it opens the database (`IllegalStateException` on first
 *    DB access, i.e. the app will not start) — caught here by diffing the two
 *    exported schemas and requiring the migration to add exactly that delta.
 *
 * ## What is NOT covered here
 *
 * Real SQLite: that the column lands in an actual file, that Room's generated DAO
 * binds it, and that a v13 file upgrades without losing rows. Those need a
 * runtime and live in `MessageThinkingLevelRoomTest` (instrumented). Everything
 * that can be checked without one — including whether the migration is
 * REGISTERED, via [AppDatabase.ALL_MIGRATIONS], which is the same array
 * `getInstance` installs — is checked here.
 */
class MessageThinkingLevelMigrationTest {

    private val schemaDir = File("schemas/com.openminis.app.data.db.AppDatabase")

    /** The `messages` entity as Room exported it for [version]. */
    private fun messagesSchema(version: Int): JSONObject {
        val file = File(schemaDir, "$version.json")
        assertTrue(
            "missing exported Room schema ${file.absolutePath} — exportSchema must stay " +
                "on and room.schemaLocation must stay configured",
            file.exists(),
        )
        val entities = JSONObject(file.readText())
            .getJSONObject("database")
            .getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            if (entity.getString("tableName") == "messages") return entity
        }
        error("no `messages` entity in ${file.name}")
    }

    private fun columns(version: Int): Map<String, JSONObject> = buildMap {
        val fields = messagesSchema(version).getJSONArray("fields")
        for (i in 0 until fields.length()) {
            val field = fields.getJSONObject(i)
            put(field.getString("columnName"), field)
        }
    }

    /**
     * Runs a migration's body and records the SQL it issues.
     *
     * `SupportSQLiteDatabase` is an interface, so a proxy can stand in for the
     * real thing; anything other than `execSQL` is a bug in the migration and
     * throws instead of passing unnoticed.
     */
    private class RecordingDatabase : InvocationHandler {
        val statements = mutableListOf<String>()

        val db: SupportSQLiteDatabase = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
            this,
        ) as SupportSQLiteDatabase

        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? =
            when (method.name) {
                "execSQL" -> {
                    statements += args!![0] as String
                    null
                }
                "toString" -> "RecordingDatabase(${statements.size} statements)"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> throw UnsupportedOperationException(
                    "migration reached for ${method.name}(...), which this test does not model",
                )
            }
    }

    private fun runMigration(): List<String> {
        val recorder = RecordingDatabase()
        AppDatabase.MIGRATION_13_14.migrate(recorder.db)
        return recorder.statements
    }

    @Test
    fun `the migration covers exactly the version bump the app declares`() {
        assertEquals(13, AppDatabase.MIGRATION_13_14.startVersion)
        assertEquals(14, AppDatabase.MIGRATION_13_14.endVersion)

        // The version the build claims to be. Room reads it from the annotation,
        // which is not reflectable, so the exported schema file name (regenerated
        // from that annotation by KSP) stands in for it — the same trick
        // DatabaseVersionGuardTest uses to keep the guard's constant honest.
        assertTrue(
            "no 14.json was exported — @Database(version=...) and the migration disagree",
            File(schemaDir, "14.json").exists(),
        )
    }

    @Test
    fun `the 13 to 14 migration is registered, not merely declared`() {
        // Declaring a migration and registering it are different facts, and only
        // the second one stops Room from throwing on a real upgrade. The builder
        // installs exactly ALL_MIGRATIONS, so this asserts what the user's device
        // will actually run — an unregistered 13 → 14 needs a device to notice
        // otherwise.
        val registered = AppDatabase.ALL_MIGRATIONS.map { it.startVersion to it.endVersion }
        assertTrue(
            "13 → 14 is missing from the registered migration set: $registered — " +
                "a real upgrade would fail with \"A migration from 13 to 14 was " +
                "required but not found\"",
            (13 to 14) in registered,
        )
        // …and the neighbouring downgrade no-op must survive alongside it.
        assertTrue("12 → 11 was dropped from the registered set", (12 to 11) in registered)
    }

    @Test
    fun `the migration adds the thinking level column, nullable text`() {
        val statements = runMigration()

        // Narrow on purpose: this migration exists to add one column, and an
        // unexpected second statement is a change nobody reviewed.
        assertEquals(
            listOf("ALTER TABLE messages ADD COLUMN thinking_level TEXT"),
            statements,
        )

        val column = columns(14)["thinking_level"]
        assertNotNull("Room's exported v14 schema has no thinking_level column", column)
        assertEquals(
            "the migration's SQL type must match the affinity Room validates against",
            column!!.getString("affinity"),
            "TEXT",
        )
        // Nullable is load-bearing: NULL is "this row never recorded a level",
        // which is what stops the header drawing a capsule for rows written
        // before the column existed. NOT NULL would force a default that reads
        // as a level the user never chose.
        assertFalse("thinking_level must be nullable", column.getBoolean("notNull"))
    }

    @Test
    fun `the migration adds exactly the columns the schema gained, and drops none`() {
        val before = columns(13)
        val after = columns(14)
        val added = after.keys - before.keys

        assertEquals(
            "the v13 → v14 bump is expected to introduce exactly one column; if this " +
                "fails, update the migration (and this expectation) deliberately",
            setOf("thinking_level"),
            added,
        )
        assertTrue(
            "no column may disappear across a migration — a rebuild would drop rows",
            before.keys.all { it in after.keys },
        )

        // The migration is the only thing that can create the column on an
        // existing install, so what it adds must equal what the schema gained.
        val addedByMigration = runMigration()
            .filter { it.contains("ADD COLUMN") }
            .map { it.substringAfter("ADD COLUMN ").substringBefore(' ').trim() }
            .toSet()
        assertEquals(added, addedByMigration)

        // And it must be additive only: no rebuild, no wipe.
        for (statement in runMigration()) {
            for (destructive in listOf("DROP", "DELETE", "CREATE TABLE", "RENAME TO")) {
                assertFalse(
                    "an ADD COLUMN migration must not issue `$destructive`: $statement",
                    statement.uppercase().contains(destructive),
                )
            }
        }
    }

    @Test
    fun `the pre-existing attribution columns stay nullable through this bump`() {
        // Regression guard for the neighbouring feature: the same entity carries
        // the model-attribution snapshot, and its nullability is what lets rows
        // written before MIGRATION_11_12 keep rendering as "estimated".
        val columns = columns(14)
        for (name in listOf("model_id", "model_display_name", "provider_type", "provider_instance_id")) {
            val column = columns[name]
            assertNotNull("$name vanished from the exported v14 messages schema", column)
            assertFalse("$name must stay nullable", column!!.getBoolean("notNull"))
        }
    }
}
