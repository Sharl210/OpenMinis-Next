package com.openminis.app.data.repository

import com.openminis.app.data.db.NextMessageDao
import com.openminis.app.data.db.NextMessageEntity

class NextMessageRepository(private val dao: NextMessageDao) {
    suspend fun upsert(message: NextMessageEntity) = dao.upsert(message)
    suspend fun listForSession(sessionId: String): List<NextMessageEntity> = dao.listForSession(sessionId)
    suspend fun deleteForSession(sessionId: String) = dao.deleteForSession(sessionId)
}
