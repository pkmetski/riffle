package com.riffle.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

// Per-library cover-grid zoom. Each row holds the pinch-to-zoom scale factor a user set for
// one library on one screen-size class. `sourceId` FK-cascades so a removed Source's rows are
// cleared. `screenDimensionBucket` is `ScreenDimensionBucket.encode()` — e.g. "Compact_Medium"
// — so a phone in portrait and in landscape share one row (rotation-invariant, per ADR 0029).
@Entity(
    tableName = "cover_grid_scale",
    primaryKeys = ["sourceId", "libraryId", "screenDimensionBucket"],
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
data class CoverGridScaleEntity(
    val sourceId: String,
    val libraryId: String,
    val screenDimensionBucket: String,
    val scale: Float,
)
