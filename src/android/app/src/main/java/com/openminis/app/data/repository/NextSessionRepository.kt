package com.openminis.app.data.repository

import com.openminis.app.data.db.NextSessionDao
import com.openminis.app.data.db.NextSessionEntity
import org.json.JSONArray

class NextSessionRepository(private val dao: NextSessionDao) {
    suspend fun upsert(session: NextSessionEntity) = dao.upsert(session)
    suspend fun list(): List<NextSessionEntity> = dao.list()
    suspend fun get(id: String): NextSessionEntity? = dao.get(id)
    suspend fun delete(id: String) = dao.delete(id)

    suspend fun createRoot(session: NextSessionEntity): NextSessionEntity {
        require(dao.get(session.id) == null) { "session already exists: ${session.id}" }
        val created = session.copy(parentId = null, rootId = session.id, depth = 0,
            birthChain = JSONArray(listOf(session.id)).toString())
        dao.upsert(created)
        return created
    }

    suspend fun createChild(parentId: String, child: NextSessionEntity): NextSessionEntity {
        require(child.id != parentId) { "child id must differ from parent id" }
        require(dao.get(child.id) == null) { "session already exists: ${child.id}" }
        val parent = requireNotNull(dao.get(parentId)) { "parent session not found: $parentId" }
        val chain = runCatching {
            val array = JSONArray(parent.birthChain)
            List(array.length()) { index -> array.getString(index) }
        }.getOrElse { throw IllegalStateException("invalid parent birthChain", it) }
        require(parent.parentId != null || (parent.rootId == parent.id && parent.depth == 0)) { "invalid root lineage" }
        require(chain.lastOrNull() == parent.id && chain.distinct().size == chain.size) { "invalid parent lineage" }
        val created = child.copy(parentId = parent.id, rootId = parent.rootId, depth = parent.depth + 1,
            birthChain = JSONArray(chain + child.id).toString())
        dao.upsert(created)
        return created
    }
}
