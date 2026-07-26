package com.riffle.core.domain

/** Bookmark row as seen by the sync layer — includes dirty-tracking fields. */
data class SyncableAudiobookBookmark(
    val id: String,
    val sourceId: String,
    val itemId: String,
    val positionSec: Double,
    val title: String,
    val createdAt: Long,
    val localUpdatedAt: Long,
    val lastSyncedAt: Long,
    val deleted: Boolean,
)
