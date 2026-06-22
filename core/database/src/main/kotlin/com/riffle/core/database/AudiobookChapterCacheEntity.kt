package com.riffle.core.database

import androidx.room.Entity

@Entity(tableName = "audiobook_chapter_cache", primaryKeys = ["serverId", "itemId"])
data class AudiobookChapterCacheEntity(
    val serverId: String,
    val itemId: String,
    val chaptersJson: String,  // JSON-serialized List<AudiobookChapter>
)
