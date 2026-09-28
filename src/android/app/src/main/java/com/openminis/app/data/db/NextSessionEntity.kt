package com.openminis.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "next_sessions")
data class NextSessionEntity(
    @PrimaryKey val id: String,
    val title: String? = null,
    val modelBinding: String,
    val category: String? = null,
    val lastMessage: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val memoryEnabled: Int = 1,
    val pinnedAt: Long? = null,
    val editCount: Int = 0,
    val thinkingOverride: String? = null,
    val folderId: String? = null,
    val parentId: String? = null,
    val rootId: String = id,
    val depth: Int = 0,
    val birthChain: String = "[\"$id\"]",
)
