package com.riffle.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

// Formatting stays per-device (never synced, never per-user), but the row must point at the *right*
// book: once item ids collide across Sources, itemId alone would let two different books share one
// formatting row (ADR 0025). sourceId is added for *identity*, not as a per-source feature; it
// FK-cascades so a removed Source's formatting is cleared.
@Entity(
    tableName = "book_formatting_preferences",
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
data class BookFormattingPreferencesEntity(
    val sourceId: String,
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
    val showReadingTimeEstimate: Boolean? = null,
)
