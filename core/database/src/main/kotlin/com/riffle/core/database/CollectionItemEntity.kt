package com.riffle.core.database

import androidx.room.Entity

// serverId disambiguates colliding item ids across Servers (ADR 0025); a collection is library-
// (hence server-) scoped, so serverId is the owning library's server.
@Entity(tableName = "collection_items", primaryKeys = ["collectionId", "serverId", "itemId"])
data class CollectionItemEntity(
    val collectionId: String,
    val serverId: String,
    val itemId: String,
)
