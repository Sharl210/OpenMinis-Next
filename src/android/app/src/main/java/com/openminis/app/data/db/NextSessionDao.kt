package com.openminis.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface NextSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(session: NextSessionEntity)
    @Query("SELECT * FROM next_sessions ORDER BY updatedAt DESC") suspend fun list(): List<NextSessionEntity>
    @Query("SELECT * FROM next_sessions WHERE id = :id") suspend fun get(id: String): NextSessionEntity?
    @Query("SELECT * FROM next_sessions") suspend fun listAllForLineage(): List<NextSessionEntity>
    @Query("DELETE FROM next_sessions WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM next_sessions WHERE id IN (:ids)") suspend fun deleteByIds(ids: List<String>): Int
}
