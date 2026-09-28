package com.openminis.app.data.repository

import com.openminis.app.data.db.NextFolderDao
import com.openminis.app.data.db.NextFolderEntity

class NextFolderRepository(private val dao: NextFolderDao) {
    suspend fun upsert(folder: NextFolderEntity) = dao.upsert(folder)
    suspend fun list(): List<NextFolderEntity> = dao.list()
    suspend fun get(id: String): NextFolderEntity? = dao.get(id)
    suspend fun delete(id: String) = dao.delete(id)
}
