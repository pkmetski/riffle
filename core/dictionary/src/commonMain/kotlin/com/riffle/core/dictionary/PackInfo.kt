package com.riffle.core.dictionary

data class PackInfo(
    val languageTag: String,
    val packVersion: String,
    val downloadUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val attributionHtml: String,
    val licenseUrl: String,
)
