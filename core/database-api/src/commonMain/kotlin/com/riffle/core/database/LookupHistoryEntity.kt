package com.riffle.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "lookup_history")
data class LookupHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val languageTag: String,
    val form: String,
    val lookedUpAt: Long,
)
