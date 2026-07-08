package com.riffle.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

// Formatting stays per-device (never synced, never per-user). The row must point at the *right*
// book and the *right* reading context: once item ids collide across Sources, itemId alone would
// let two different books share one formatting row (ADR 0025), and once the annotations reading
// view got its own preferences chain, sourceId+itemId alone would let the annotations view and
// full-book view collide on the same book. `sourceId` FK-cascades so a removed Source's
// formatting is cleared. `scope` is the `FormattingScope` enum name ("FullBook" / "Highlights").
@Entity(
    tableName = "book_formatting_preferences",
    primaryKeys = ["sourceId", "itemId", "scope"],
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
    val scope: String,
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
