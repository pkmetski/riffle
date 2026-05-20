package com.riffle.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "series")
data class SeriesEntity(
    @PrimaryKey val id: String,
    val libraryId: String,
    val name: String,
    val coverUrl: String?,
    val bookCount: Int,
)
