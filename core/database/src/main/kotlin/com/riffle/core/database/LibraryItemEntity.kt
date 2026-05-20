package com.riffle.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "library_items")
data class LibraryItemEntity(
    @PrimaryKey val id: String,
    val libraryId: String,
    val title: String,
    val author: String,
    val coverUrl: String?,
    val readingProgress: Float,
    val isDownloaded: Boolean,
    val isSupported: Boolean = true,
)
