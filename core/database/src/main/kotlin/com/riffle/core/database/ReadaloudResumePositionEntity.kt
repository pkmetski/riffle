package com.riffle.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Per-device readaloud resume position, keyed by the reader's (sourceId, itemId). Mirrors
 * [ReadingPositionEntity]'s keying and CASCADE so deleting a source cleans up its rows. Never synced.
 */
@Entity(
    tableName = "readaloud_resume_positions",
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
data class ReadaloudResumePositionEntity(
    val sourceId: String,
    val itemId: String,
    val href: String,
    val progression: Double?,
    val fragmentRef: String?,
    val localUpdatedAt: Long = 0,
)
