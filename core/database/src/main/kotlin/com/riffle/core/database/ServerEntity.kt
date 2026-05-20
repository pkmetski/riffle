package com.riffle.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey val id: String,
    val url: String,
    val displayName: String,
    val isActive: Boolean,
    val insecureConnectionAllowed: Boolean,
)
