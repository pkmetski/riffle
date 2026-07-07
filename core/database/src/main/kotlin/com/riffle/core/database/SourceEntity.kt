package com.riffle.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey val id: String,
    val url: String,
    val isActive: Boolean,
    val insecureConnectionAllowed: Boolean,
    val username: String,
    val serverType: String = "AUDIOBOOKSHELF",
    /**
     * Cross-device-stable identity for this ABS account, taken from `/api/me`'s `user.id`.
     * Used by annotation sync as the WebDAV path namespace so two devices pointing at the same
     * ABS server see each other's files (the primary-key [id] is a per-device random UUID and
     * cannot serve this purpose). Null on Storyteller servers and on ABS rows that were added
     * before this column existed — backfilled lazily on the next successful `/api/me` call.
     */
    val absUserId: String? = null,
    val type: String = "ABS",
)
