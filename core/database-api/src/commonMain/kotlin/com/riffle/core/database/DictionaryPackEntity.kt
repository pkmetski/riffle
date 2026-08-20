package com.riffle.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "dictionary_packs")
data class DictionaryPackEntity(
    @PrimaryKey val languageTag: String,
    val packVersion: String,
    val installedAt: Long,
    val sizeBytes: Long,
    val attributionHtml: String,
    val licenseUrl: String,
    val state: String,
)
