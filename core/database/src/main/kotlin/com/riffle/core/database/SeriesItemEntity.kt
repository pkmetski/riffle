package com.riffle.core.database

import androidx.room.Entity

// serverId disambiguates colliding item ids across Servers (ADR 0025); a series is library-
// (hence server-) scoped, so serverId is the owning library's server.
@Entity(tableName = "series_items", primaryKeys = ["seriesId", "serverId", "itemId"])
data class SeriesItemEntity(
    val seriesId: String,
    val serverId: String,
    val itemId: String,
    val sequenceOrder: Float,
)
