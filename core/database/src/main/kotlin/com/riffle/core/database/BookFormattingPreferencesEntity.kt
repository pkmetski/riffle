package com.riffle.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "book_formatting_preferences")
data class BookFormattingPreferencesEntity(
    @PrimaryKey val itemId: String,
    val fontSize: Float,
    val theme: String,
    val fontFamily: String,
    val lineSpacing: Float,
    val margins: Float,
)
