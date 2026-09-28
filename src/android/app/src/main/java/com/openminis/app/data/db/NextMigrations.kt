package com.openminis.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Next-only schema migration; legacy stores are never referenced. */
object NextMigrations {
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE next_sessions ADD COLUMN parentId TEXT")
            database.execSQL("ALTER TABLE next_sessions ADD COLUMN rootId TEXT NOT NULL DEFAULT ''")
            database.execSQL("ALTER TABLE next_sessions ADD COLUMN depth INTEGER NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE next_sessions ADD COLUMN birthChain TEXT NOT NULL DEFAULT '[]'")
            database.execSQL("UPDATE next_sessions SET rootId = id, depth = 0, birthChain = '[\"' || replace(replace(id, char(92), char(92) || char(92)), char(34), char(92) || char(34)) || '\"]'")
        }
    }
}
