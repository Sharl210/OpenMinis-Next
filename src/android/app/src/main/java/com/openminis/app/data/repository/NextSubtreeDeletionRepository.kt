package com.openminis.app.data.repository

import androidx.room.withTransaction
import com.openminis.app.data.db.NextAppDatabase
import com.openminis.app.data.db.NextSessionEntity
import org.json.JSONArray

/** Outcome of an atomic Next-session subtree deletion. */
data class NextSubtreeDeletionResult(
    val targetId: String,
    val affectedIds: List<String>,
    val success: Boolean,
    val reason: String? = null,
)

/** Next-only transactional deletion service. */
class NextSubtreeDeletionRepository(
    private val database: NextAppDatabase,
    private val beforeDelete: suspend (List<String>) -> Unit = {},
) {
    suspend fun descendantIds(targetId: String): List<String> {
        val all = database.sessionDao().listAllForLineage()
        return lineageSubtree(all, targetId)
    }

    suspend fun deleteSubtree(targetId: String): NextSubtreeDeletionResult = database.withTransaction {
        val dao = database.sessionDao()
        val messages = database.messageDao()
        val all = dao.listAllForLineage()
        val target = all.firstOrNull { it.id == targetId }
            ?: return@withTransaction NextSubtreeDeletionResult(targetId, emptyList(), success = false, reason = "not_found")
        val affected = lineageSubtree(all, targetId)
        val byId = all.associateBy(NextSessionEntity::id)
        require(affected.all { id -> byId[id]?.rootId == target.rootId }) { "subtree contains a different root" }
        require(affected.distinct().size == affected.size) { "lineage contains a cycle" }
        messages.deleteForSessions(affected)
        beforeDelete(affected)
        val deleted = dao.deleteByIds(affected)
        check(deleted == affected.size) { "subtree changed during deletion: expected ${affected.size}, deleted $deleted" }
        NextSubtreeDeletionResult(targetId, affected, success = true)
    }

    private fun lineageSubtree(all: List<NextSessionEntity>, targetId: String): List<String> {
        val byParent = all.groupBy { it.parentId }
        val target = all.firstOrNull { it.id == targetId } ?: return emptyList()
        val root = target.rootId
        val result = ArrayList<String>()
        val visiting = HashSet<String>()
        val visited = HashSet<String>()
        fun visit(id: String) {
            require(visiting.add(id)) { "lineage contains a cycle at $id" }
            require(visited.add(id)) { "lineage contains duplicate reachability at $id" }
            val node = all.firstOrNull { it.id == id } ?: error("lineage references missing session $id")
            require(node.rootId == root) { "lineage crosses root at $id" }
            result += id
            byParent[id].orEmpty().forEach { child ->
                require(child.rootId == root && child.parentId == id) { "invalid parent/root relationship at ${child.id}" }
                require(child.depth == node.depth + 1) { "invalid depth at ${child.id}" }
                val expectedChain = parseBirthChain(node.birthChain) + child.id
                require(parseBirthChain(child.birthChain) == expectedChain) { "invalid birth chain at ${child.id}" }
                visit(child.id)
            }
            visiting.remove(id)
        }
        visit(targetId)
        return result
    }

    private fun parseBirthChain(raw: String): List<String> = runCatching {
        val array = JSONArray(raw)
        List(array.length()) { index -> array.getString(index) }
    }.getOrElse { throw IllegalStateException("invalid birthChain JSON", it) }
}
