package com.riffle.core.database

import androidx.room.Entity

// sourceId disambiguates colliding item ids across Sources (ADR 0025); a series is library-
// (hence source-) scoped, so sourceId is the owning library's source.
@Entity(tableName = "series_items", primaryKeys = ["seriesId", "sourceId", "itemId"])
data class SeriesItemEntity(
    val seriesId: String,
    val sourceId: String,
    val itemId: String,
    val sequenceOrder: Float,
)
