package com.riffle.core.dictionary

data class InstalledPack(
    val languageTag: String,
    val packVersion: String,
    val installedAt: Long,
    val sizeBytes: Long,
    val attributionHtml: String,
    val licenseUrl: String,
)
