package com.riffle.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

// The audiobook listen position (book-absolute seconds) + the wall-clock it was last set at, stored
// per (serverId, itemId) — the same identity and FK-cascade as `reading_positions`. Server-synced
// (unlike the device-local `readaloud_resume_positions`): it is a durable last-update-wins peer
// against ABS's media-progress record (ADR 0029).
@Entity(
    tableName = "audiobook_positions",
    primaryKeys = ["serverId", "itemId"],
    foreignKeys = [
        ForeignKey(
            entity = ServerEntity::class,
            parentColumns = ["id"],
            childColumns = ["serverId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("serverId")],
)
data class AudiobookPositionEntity(
    val serverId: String,
    val itemId: String,
    val positionSec: Double,
    val localUpdatedAt: Long = 0,
    // The localUpdatedAt value last confirmed pushed to / pulled from the server; the row is
    // **dirty** when localUpdatedAt > lastSyncedAt (ADR 0030). See ReadingPositionEntity.
    val lastSyncedAt: Long = 0,
)
