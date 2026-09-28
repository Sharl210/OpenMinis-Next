package com.openminis.app.data.db

import android.content.Context
import androidx.room.Room
import com.openminis.app.data.NextDataRoot

/** Opens only the Next-owned Room file; never consults the legacy AppDatabase. */
object NextDatabaseProvider {
    @Volatile
    private var instance: NextAppDatabase? = null

    fun getInstance(context: Context): NextAppDatabase = instance ?: synchronized(this) {
        instance ?: newBuilder(context).build().also { instance = it }
    }

    /** Public for deterministic path assertions without opening Android storage. */
    fun databasePath(context: Context): String =
        NextDataRoot.databaseFile(context.applicationContext.filesDir).absolutePath

    internal fun newBuilder(context: Context) = Room.databaseBuilder(
        context.applicationContext,
        NextAppDatabase::class.java,
        databasePath(context),
    ).addMigrations(NextMigrations.MIGRATION_1_2)

    /** Test/process teardown only; the database file itself is preserved. */
    fun closeForProcessTeardown() {
        synchronized(this) {
            instance?.close()
            instance = null
        }
    }
}
