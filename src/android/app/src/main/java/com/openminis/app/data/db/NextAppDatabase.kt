package com.openminis.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room schema for the opt-in OpenMinis-Next data root.
 *
 * Do not add legacy AppDatabase entities or migrations here. Next owns an
 * independent database file and schema lifecycle.
 */
@Database(
    entities = [
        NextRuntimeMetadataEntity::class,
        NextSessionEntity::class,
        NextMessageEntity::class,
        NextFolderEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class NextAppDatabase : RoomDatabase() {
    abstract fun runtimeMetadataDao(): NextRuntimeMetadataDao
    abstract fun sessionDao(): NextSessionDao
    abstract fun messageDao(): NextMessageDao
    abstract fun folderDao(): NextFolderDao
}
