package com.riffle.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

// Formatting stays per-device (never synced, never per-user). Once item ids collide across
// Sources, itemId alone would let two different books share one formatting row (ADR 0029), so
// `sourceId` is part of the PK. `sourceId` FK-cascades so a removed Source's formatting is
// cleared. `screenDimensionBucket` is `ScreenDimensionBucket.encode()` — e.g. "Compact_Medium"
// — so each screen-size class gets independent settings for the same book. The full-book reader
// and the elided (annotations) reader share the same row so per-book customisations propagate
// to both views without duplication.
@Entity(
    tableName = "book_formatting_preferences",
    primaryKeys = ["sourceId", "itemId", "screenDimensionBucket"],
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
    val screenDimensionBucket: String,
    val fontSize: Float? = null,
    val theme: String? = null,
    val fontFamily: String? = null,
    val lineSpacing: Float? = null,
    val margins: Float? = null,
    val orientation: String? = null,
    val showChapterMap: Boolean? = null,
    val coloredChapterMap: Boolean? = null,
    val showReadingProgressLabels: Boolean? = null,
    val showCurrentChapterLabel: Boolean? = null,
    val doublePageSpread: Boolean? = null,
    val justifyText: Boolean? = null,
    val showReadingTimeEstimate: Boolean? = null,
)
