package com.riffle.core.database

import androidx.room.Entity

// sourceId disambiguates colliding item ids across Sources (ADR 0025); a collection is library-
// (hence source-) scoped, so sourceId is the owning library's source.
@Entity(tableName = "collection_items", primaryKeys = ["collectionId", "sourceId", "itemId"])
data class CollectionItemEntity(
    val collectionId: String,
    val sourceId: String,
    val itemId: String,
)
