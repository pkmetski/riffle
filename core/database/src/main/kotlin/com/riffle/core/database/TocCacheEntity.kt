package com.riffle.core.database

import androidx.room.Entity

@Entity(tableName = "toc_cache", primaryKeys = ["serverId", "itemId"])
data class TocCacheEntity(
    val serverId: String,
    val itemId: String,
    val ebookFileIno: String,
    val entriesJson: String,  // JSON-serialized List<TocEntry>
)
