package com.riffle.core.dictionary

data class LanguageCatalogEntry(
    val languageTag: String,
    val displayName: String,
    val jsonlUrl: String,
    val approximateSizeBytes: Long,
    val attributionHtml: String,
    val licenseUrl: String,
)
