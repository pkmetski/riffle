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
    val ebookFileIno: String? = null,
    val ebookFormat: String = "unsupported",
    val description: String? = null,
    val seriesName: String? = null,
    val publishedYear: String? = null,
    val genres: String = "",
    val publisher: String? = null,
    val lastOpenedAt: Long? = null,
)
