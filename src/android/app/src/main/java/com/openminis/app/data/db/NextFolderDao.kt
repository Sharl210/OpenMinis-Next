package com.openminis.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface NextFolderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(folder: NextFolderEntity)
    @Query("SELECT * FROM next_folders ORDER BY sortIndex ASC, updatedAt DESC") suspend fun list(): List<NextFolderEntity>
    @Query("SELECT * FROM next_folders WHERE id = :id") suspend fun get(id: String): NextFolderEntity?
    @Query("DELETE FROM next_folders WHERE id = :id") suspend fun delete(id: String)
}
