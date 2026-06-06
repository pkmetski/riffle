package com.riffle.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Per-device readaloud resume position, keyed by the reader's (serverId, itemId). Mirrors
 * [ReadingPositionEntity]'s keying and CASCADE so deleting a server cleans up its rows. Never synced.
 */
@Entity(
    tableName = "readaloud_resume_positions",
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
data class ReadaloudResumePositionEntity(
    val serverId: String,
    val itemId: String,
    val href: String,
    val progression: Double?,
    val fragmentRef: String?,
    val localUpdatedAt: Long = 0,
)
