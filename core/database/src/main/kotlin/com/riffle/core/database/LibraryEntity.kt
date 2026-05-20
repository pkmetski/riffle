package com.riffle.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "libraries")
data class LibraryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val mediaType: String,
    val serverId: String,
)
