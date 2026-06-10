package com.riffle.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

// Per-book audio playback settings, device-local and never synced (ADR 0028). Keyed by a resolved
// audio identity (serverId, bookId): the linked audiobook's ABS id when present, else the Storyteller
// readaloud id. serverId FK-cascades so a removed Server's settings are cleared. A row exists only
// when the user has overridden the fixed 1x default. `speed` is nullable to allow the table to grow
// further audio-setting columns later without forcing this one.
@Entity(
    tableName = "audio_playback_preferences",
    primaryKeys = ["serverId", "bookId"],
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
data class AudioPlaybackPreferencesEntity(
    val serverId: String,
    val bookId: String,
    val speed: Float? = null,
)
