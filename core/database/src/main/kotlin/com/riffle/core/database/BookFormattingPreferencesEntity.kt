package com.riffle.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "book_formatting_preferences")
data class BookFormattingPreferencesEntity(
    @PrimaryKey val itemId: String,
    val fontSize: Float? = null,
    val theme: String? = null,
    val fontFamily: String? = null,
    val lineSpacing: Float? = null,
    val margins: Float? = null,
    val orientation: String? = null,
    val showChapterMap: Boolean? = null,
    val showReadingProgressLabels: Boolean? = null,
    val showCurrentChapterLabel: Boolean? = null,
    val doublePageSpread: Boolean? = null,
    val justifyText: Boolean? = null,
)
