package com.openminis.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "next_messages",
    foreignKeys = [ForeignKey(
        entity = NextSessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["sessionId", "sortOrder"]), Index(value = ["sessionId", "createdAt"])],
)
data class NextMessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: String,
    val partsJson: String,
    val createdAt: Long,
    val sortOrder: Long,
    val tokenUsage: String? = null,
    val reasoningContent: String? = null,
    val updatedAt: Long? = null,
    val errorInfo: String? = null,
    val modelId: String? = null,
    val modelDisplayName: String? = null,
    val providerType: String? = null,
    val providerInstanceId: String? = null,
)
