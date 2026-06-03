package com.riffle.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

// Formatting stays per-device (never synced, never per-user), but the row must point at the *right*
// book: once item ids collide across Servers, itemId alone would let two different books share one
// formatting row (ADR 0025). serverId is added for *identity*, not as a per-server feature; it
// FK-cascades so a removed Server's formatting is cleared.
@Entity(
    tableName = "book_formatting_preferences",
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
data class BookFormattingPreferencesEntity(
    val serverId: String,
    val itemId: String,
    val fontSize: Float? = null,
    val theme: String? = null,
    val fontFamily: String? = null,
    val lineSpacing: Float? = null,
    val margins: Float? = null,
    val orientation: String? = null,
    val showChapterMap: Boolean? = null,
    val showReadingProgressLabels: Boolean? = null,
    val showCurrentChapterLabel: Boolean? = null,
    val doublePageSpread: Boolean? = null,
    val justifyText: Boolean? = null,
)
