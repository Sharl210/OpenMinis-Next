package com.openminis.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Minimal Next-owned marker table; legacy session/message entities are intentionally excluded. */
@Entity(tableName = "next_runtime_metadata")
data class NextRuntimeMetadataEntity(
    @PrimaryKey val key: String,
    val value: String,
)
