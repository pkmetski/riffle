package com.riffle.core.database

import androidx.room.Entity

@Entity(tableName = "collection_items", primaryKeys = ["collectionId", "itemId"])
data class CollectionItemEntity(
    val collectionId: String,
    val itemId: String,
)
