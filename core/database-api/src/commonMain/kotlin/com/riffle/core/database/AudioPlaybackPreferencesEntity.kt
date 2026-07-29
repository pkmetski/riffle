package com.riffle.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

// Per-book audio playback settings, device-local and never synced (ADR 0028). Keyed by a resolved
// audio identity (sourceId, bookId): the linked audiobook's ABS id when present, else the Storyteller
// readaloud id. sourceId FK-cascades so a removed Source's settings are cleared. A row exists only
// when the user has overridden the fixed 1x default. `speed` is nullable to allow the table to grow
// further audio-setting columns later without forcing this one.
@Entity(
    tableName = "audio_playback_preferences",
    primaryKeys = ["sourceId", "bookId"],
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
data class AudioPlaybackPreferencesEntity(
    val sourceId: String,
    val bookId: String,
    val speed: Float? = null,
)
