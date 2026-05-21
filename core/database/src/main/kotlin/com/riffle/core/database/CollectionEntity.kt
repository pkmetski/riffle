package com.riffle.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey val id: String,
    val libraryId: String,
    val name: String,
    val bookCount: Int,
)
