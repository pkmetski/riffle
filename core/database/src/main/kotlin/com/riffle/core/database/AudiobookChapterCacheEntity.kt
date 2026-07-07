package com.riffle.core.database

import androidx.room.Entity

@Entity(tableName = "audiobook_chapter_cache", primaryKeys = ["sourceId", "itemId"])
data class AudiobookChapterCacheEntity(
    val sourceId: String,
    val itemId: String,
    val chaptersJson: String,  // JSON-serialized List<AudiobookChapter>
)
