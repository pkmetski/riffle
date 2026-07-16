package com.riffle.core.database

import androidx.room.Entity

@Entity(tableName = "audiobook_chapter_cache", primaryKeys = ["sourceId", "itemId"])
data class AudiobookChapterCacheEntity(
    val sourceId: String,
    val itemId: String,
    val chaptersJson: String,  // JSON-serialized List<AudiobookChapter>
    val cachedAt: Long,        // wall-clock millis when this row was written; drives TTL
)
