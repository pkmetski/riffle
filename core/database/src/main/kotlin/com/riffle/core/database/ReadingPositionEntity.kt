package com.riffle.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "reading_positions",
    primaryKeys = ["sourceId", "itemId"],
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sourceId")],
)
data class ReadingPositionEntity(
    val sourceId: String,
    val itemId: String,
    val cfi: String,
    val localUpdatedAt: Long = 0,
    // The localUpdatedAt value last confirmed pushed to / pulled from the server. The row is
    // **dirty** (has unsynced local progress) when localUpdatedAt > lastSyncedAt — the durable
    // offline-reconcile marker the sweep worker enumerates on (ADR 0030).
    val lastSyncedAt: Long = 0,
)
