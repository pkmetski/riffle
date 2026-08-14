package com.riffle.core.data.dictionary

import kotlinx.serialization.Serializable

@Serializable
internal data class PackManifestJson(
    val version: Int,
    val packs: List<PackInfoJson>,
)

@Serializable
internal data class PackInfoJson(
    val languageTag: String,
    val packVersion: String,
    val downloadUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val attributionHtml: String,
    val licenseUrl: String,
)
