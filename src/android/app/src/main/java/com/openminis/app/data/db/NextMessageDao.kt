package com.openminis.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface NextMessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(message: NextMessageEntity)
    @Query("SELECT * FROM next_messages WHERE sessionId = :sessionId ORDER BY sortOrder ASC, createdAt ASC") suspend fun listForSession(sessionId: String): List<NextMessageEntity>
    @Query("DELETE FROM next_messages WHERE sessionId = :sessionId") suspend fun deleteForSession(sessionId: String)
    @Query("DELETE FROM next_messages WHERE sessionId IN (:sessionIds)") suspend fun deleteForSessions(sessionIds: List<String>): Int
}
