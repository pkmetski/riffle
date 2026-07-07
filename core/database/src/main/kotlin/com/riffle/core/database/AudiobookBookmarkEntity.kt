package com.riffle.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

// A user bookmark in an audiobook: a titled point (book-absolute seconds) on a library item.
// Unlike audiobook_positions (one value per item) this is a COLLECTION per (sourceId, itemId).
// Dirty-tracking + soft-delete mirror ADR 0030: a row is dirty when localUpdatedAt > lastSyncedAt;
// a delete is a tombstone (deleted = 1) kept until the server delete is confirmed, then hard-removed.
@Entity(
    tableName = "audiobook_bookmarks",
    primaryKeys = ["id"],
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sourceId"), Index(value = ["sourceId", "itemId"])],
)
data class AudiobookBookmarkEntity(
    val id: String,
    val sourceId: String,
    val itemId: String,
    val positionSec: Double,
    val title: String,
    val createdAt: Long,
    val localUpdatedAt: Long = 0,
    val lastSyncedAt: Long = 0,
    val deleted: Boolean = false,
)
