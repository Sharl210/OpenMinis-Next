package com.openminis.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "next_folders")
data class NextFolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val icon: String? = null,
    val color: String? = null,
    val origin: String = "manual",
    val sortIndex: Int = 0,
    val pinnedAt: Long? = null,
    val description: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
