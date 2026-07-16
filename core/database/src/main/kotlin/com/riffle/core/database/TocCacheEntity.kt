package com.riffle.core.database

import androidx.room.Entity

@Entity(tableName = "toc_cache", primaryKeys = ["sourceId", "itemId"])
data class TocCacheEntity(
    val sourceId: String,
    val itemId: String,
    val ebookFileIno: String,
    val entriesJson: String,  // JSON-serialized List<TocEntry>
    val cachedAt: Long,       // wall-clock millis when this row was written; drives TTL
)
