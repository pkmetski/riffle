package com.riffle.core.database

import androidx.room.Entity

@Entity(tableName = "series_items", primaryKeys = ["seriesId", "itemId"])
data class SeriesItemEntity(
    val seriesId: String,
    val itemId: String,
    val sequenceOrder: Float,
)
